package app.revanced.extension.gamehub.winemu;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline component-picker fix — short-circuits gof.a (the per-type picker
 * component-list feed, model-free CONFIRMED reached online AND offline,
 * stack: sz2 picker-VM → zxf → gof.a) with a populated
 * {@code BaseResult<EnvListData<EnvLayerEntity>>} synthesised from the
 * on-device saved catalog when the device is offline.
 *
 * <p>Online: returns null → original network path runs (fresh data).
 * Offline: builds the result from {@code sp_winemu_unified_resources.xml}
 * (the user's actual downloaded catalog, written by the app itself when
 * online — always current, no stale bundle), filtered by the requested
 * ComponentType.type.
 *
 * <p>Construction uses {@code Unsafe.allocateInstance} + reflective field-set
 * (the proven trick in this codebase): sidesteps the R8-renamed BaseResult
 * ({@code Lc91;}), the obfuscated kotlinx synthetic-ctor marker, and the
 * 20-arg ambiguous primary ctor. EnvLayerEntity's Kotlin field names are
 * byte-identical to the catalog entry's JSON keys (camelCase 1:1).
 *
 * <p>TOTAL fail-safe: any failure returns null → gof.a runs unchanged →
 * offline that yields the same empty list as today (zero regression);
 * online is never touched.
 */
public final class OfflineComponentList {
    private OfflineComponentList() {}

    private static final String TAG = "BH-OFFLINE-LIST";
    private static final String PREFS_REL =
            "/shared_prefs/sp_winemu_unified_resources.xml";
    private static final String ENV_LAYER =
            "com.xiaoji.egggame.common.winemu.bean.EnvLayerEntity";
    private static final String ENV_LIST =
            "com.xiaoji.egggame.common.winemu.bean.EnvListData";
    private static final String STATE_ENUM =
            "com.xiaoji.egggame.common.winemu.bean.State";
    // R8-renamed BaseResult (verified: gof.b casts to Lc91;, NPE msg names
    // com.xiaoji.egggame.core.network.model.BaseResult). Fields a:int(code),
    // b:String(msg), c:String(time), d:Object(data).
    private static final String BASE_RESULT = "c91";

    private static final Pattern ENTRY = Pattern.compile(
            "<string name=\"COMPONENT:[^\"]*\">(.*?)</string>", Pattern.DOTALL);

    /**
     * Replaces gof.a's body entirely (register-safe: no branch/extra reg).
     * Replicates gof.a's trivial original logic and, for the online /
     * any-failure path, reflectively invokes the original suspend impl
     * {@code gof.b(int,int,int,Continuation)} and returns its value verbatim
     * (suspension/COROUTINE_SUSPENDED passes straight through). Offline,
     * returns a synthesised BaseResult so the picker lists the user's
     * downloaded components.
     *
     * @param gof   the gof instance (p0)
     * @param ct    ComponentType (p1)
     * @param page  page arg (p2)
     * @param cont  Continuation (p3)
     * @param flags flag bits (p4) — original: (flags & 4)!=0 ⇒ page=200
     */
    public static Object dispatch(Object gof, Object ct, int page,
                                  Object cont, int flags) {
        // Original gof.a: if ((flags & 4) != 0) page = 200;
        int pageArg = ((flags & 4) != 0) ? 200 : page;
        try {
            Object shim = getList(ct);
            if (shim != null) return shim;            // offline → synthesised
        } catch (Throwable ignored) {
            // fall through to the original network path
        }
        // Online / fallback: invoke the original gof.b(type,1,page,cont).
        return callOriginalB(gof, ct, pageArg, cont);
    }

    private static Object callOriginalB(Object gof, Object ct, int pageArg,
                                        Object cont) {
        int type;
        try {
            type = ((Number) ct.getClass().getMethod("getType")
                    .invoke(ct)).intValue();
        } catch (Throwable t) {
            type = 0;
        }
        for (java.lang.reflect.Method mth : gof.getClass().getDeclaredMethods()) {
            if (!mth.getName().equals("b")) continue;
            Class<?>[] p = mth.getParameterTypes();
            if (p.length == 4 && p[0] == int.class && p[1] == int.class
                    && p[2] == int.class) {
                try {
                    mth.setAccessible(true);
                    return mth.invoke(gof, type, 1, pageArg, cont);
                } catch (Throwable t) {
                    throw new RuntimeException("gof.b invoke failed", t);
                }
            }
        }
        throw new IllegalStateException("gof.b(int,int,int,Cont) not found");
    }

    /** Offline-only: synthesised BaseResult, or null to use the original. */
    private static Object getList(Object componentType) {
        try {
            Application app = currentApp();
            if (app == null) return null;
            if (isOnline(app)) return null;            // online → fresh original

            int wantType = ((Number) componentType.getClass()
                    .getMethod("getType").invoke(componentType)).intValue();

            String xml = readCatalog(app);
            if (xml == null) return null;

            Class<?> envLayerCls = Class.forName(ENV_LAYER);
            ArrayList<Object> list = new ArrayList<>();
            Matcher m = ENTRY.matcher(xml);
            while (m.find()) {
                try {
                    JSONObject repo = new JSONObject(unescapeXml(m.group(1)));
                    JSONObject e = repo.optJSONObject("entry");
                    if (e == null) continue;
                    if (e.optInt("type", Integer.MIN_VALUE) != wantType) continue;
                    Object env = buildEnvLayer(envLayerCls, e);
                    if (env != null) list.add(env);
                } catch (Throwable perEntry) {
                    // skip a bad entry, keep the rest
                }
            }
            if (list.isEmpty()) return null;

            Object envListData = newEnvListData(list);
            if (envListData == null) return null;
            Object baseResult = newBaseResult(envListData);
            diag("getList type=" + wantType + " built=" + list.size()
                    + (baseResult != null ? " OK" : " BR_FAIL"));
            return baseResult;
        } catch (Throwable t) {
            diag("getList FAILED passthrough: " + t);
            return null;
        }
    }

    // ---- construction -------------------------------------------------------

    private static Object buildEnvLayer(Class<?> cls, JSONObject e)
            throws Exception {
        Object o = allocate(cls);
        if (o == null) return null;
        // Kotlin field names == catalog entry JSON keys (camelCase 1:1).
        setStr(o, "name", e, "name");
        setStr(o, "version", e, "version");
        setStr(o, "displayName", e, "displayName");
        setStr(o, "fileMd5", e, "fileMd5");
        setStr(o, "fileName", e, "fileName");
        setStr(o, "downloadUrl", e, "downloadUrl");
        setStr(o, "logo", e, "logo");
        setStr(o, "blurb", e, "blurb");
        setStr(o, "upgradeMsg", e, "upgradeMsg");
        setStr(o, "framework", e, "framework");
        setStr(o, "frameworkType", e, "frameworkType");
        setLong(o, "fileSize", e, "fileSize");
        setInt(o, "id", e, "id");
        setInt(o, "isSteam", e, "isSteam");
        setInt(o, "status", e, "status");
        setInt(o, "fileType", e, "fileType");
        setInt(o, "versionCode", e, "versionCode");
        // type is a boxed Integer field
        if (e.has("type") && !e.isNull("type")) {
            setField(o, "type", Integer.valueOf(e.optInt("type")));
        }
        // state: enum, default to a benign value if present/parseable
        try {
            Class<?> st = Class.forName(STATE_ENUM);
            String sv = e.optString("state", "None");
            Object stv;
            try {
                stv = Enum.valueOf(st.asSubclass(Enum.class), sv);
            } catch (Throwable bad) {
                stv = st.getEnumConstants() != null && st.getEnumConstants().length > 0
                        ? st.getEnumConstants()[0] : null;
            }
            if (stv != null) setField(o, "state", stv);
        } catch (Throwable ignored) {
        }
        // base, subData left null (zero-initialised by allocateInstance).
        return o;
    }

    private static Object newEnvListData(ArrayList<Object> list) {
        try {
            Class<?> c = Class.forName(ENV_LIST);
            int n = list.size();
            // public ctor (List, int page, int pageSize, int total)
            try {
                Constructor<?> ct = c.getDeclaredConstructor(
                        java.util.List.class, int.class, int.class, int.class);
                ct.setAccessible(true);
                return ct.newInstance(list, 1, n, n);
            } catch (NoSuchMethodException no) {
                Object o = allocate(c);
                if (o == null) return null;
                setField(o, "list", list);
                setField(o, "page", 1);
                setField(o, "pageSize", n);
                setField(o, "total", n);
                return o;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newBaseResult(Object data) {
        try {
            Class<?> c = Class.forName(BASE_RESULT);
            Object o = allocate(c);
            if (o == null) return null;
            // a:int code, b:String msg, c:String time, d:Object data
            setField(o, "a", 200);
            setField(o, "b", "Success");
            setField(o, "c", String.valueOf(System.currentTimeMillis()));
            setField(o, "d", data);
            return o;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- reflection helpers -------------------------------------------------

    private static Object UNSAFE;
    private static java.lang.reflect.Method ALLOC;

    private static Object allocate(Class<?> cls) {
        try {
            if (ALLOC == null) {
                Class<?> u = Class.forName("sun.misc.Unsafe");
                Field f = u.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                UNSAFE = f.get(null);
                ALLOC = u.getMethod("allocateInstance", Class.class);
            }
            return ALLOC.invoke(UNSAFE, cls);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void setField(Object o, String name, Object val)
            throws Exception {
        for (Class<?> k = o.getClass(); k != null && k != Object.class;
             k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, val);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
    }

    private static void setStr(Object o, String fld, JSONObject e, String key)
            throws Exception {
        if (e.has(key) && !e.isNull(key)) setField(o, fld, e.optString(key, ""));
        else setField(o, fld, "");
    }

    private static void setInt(Object o, String fld, JSONObject e, String key)
            throws Exception {
        setField(o, fld, e.optInt(key, 0));
    }

    private static void setLong(Object o, String fld, JSONObject e, String key)
            throws Exception {
        setField(o, fld, e.optLong(key, 0L));
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#10;", "\n").replace("&#13;", "\r")
                .replace("&#9;", "\t").replace("&amp;", "&");
    }

    private static String readCatalog(Application app) {
        try {
            String dir = app.getApplicationInfo().dataDir;
            if (dir == null) return null;
            File f = new File(dir + PREFS_REL);
            if (!f.exists() || !f.canRead()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, r;
                while (off < buf.length
                        && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            }
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isOnline(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return false; // treat unknown as offline → safe (we may serve cache)
        }
    }

    private static Application currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object a = at.getMethod("currentApplication").invoke(null);
            return (a instanceof Application) ? (Application) a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void diag(String msg) {
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
        try {
            Application app = currentApp();
            if (app == null) return;
            File d = app.getFilesDir();
            if (d == null) return;
            try (FileOutputStream fo = new FileOutputStream(
                    new File(d, "bh_offline_list.log"), true)) {
                fo.write((System.currentTimeMillis() + "  " + msg + "\n")
                        .getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {
        }
    }
}
