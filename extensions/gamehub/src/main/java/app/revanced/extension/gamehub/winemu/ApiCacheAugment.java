package app.revanced.extension.gamehub.winemu;

import android.app.Application;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline component-picker fix (write-side, at the API cache).
 *
 * <p>ROOT CAUSE (forensically established 2026-05-18): the 6.0.4 component
 * pickers are fed by the cached {@code winemu_game_config} API response
 * (Room table {@code api_cache} in {@code egggame.db}); its JSON
 * {@code component[]} array is the per-type option list. ONLINE the
 * repository merges the user's locally-downloaded components in; OFFLINE the
 * cached response is served WITHOUT them (the merge is network-gated, and the
 * saved catalog {@code sp_winemu_unified_resources} is never read offline).
 * So offline pickers show only the server-recommended set.
 *
 * <p>FIX: hook {@code ApiCacheDao.saveCache(ApiCacheEntity)} — the single
 * Room write chokepoint for every {@code winemu_game_config} cache row. When
 * the key is a winemu_game_config key, union the user's saved catalog
 * (sp_winemu_unified_resources, the authoritative list of everything they've
 * downloaded) into the response's {@code component[]} before it is persisted.
 * The on-disk cache the offline picker reads then already contains every
 * downloaded component, sub-typed correctly (the picker filters by
 * {@code type}). De-duplicated by {@code name}; the server/recommended set is
 * preserved and ordered first. ANY failure leaves the entity untouched — the
 * cache (and picker) can never be broken by this.
 *
 * <p>Catalog entries store a kotlinx/Gson {@code WinEmuRepo}
 * ({@code {entry:{downloadUrl,fileMd5,fileName,fileType,type,versionCode,…}}},
 * camelCase). The cached config's component element is the server API shape
 * (snake_case {@code {file_md5,file_name,download_url,version_code,…}}). We
 * map camel→snake explicitly. All anchors are non-obfuscated and stable
 * (cache_key string, ApiCacheEntity getKey/getBody, the literal prefs file,
 * the documented JSON field names).
 */
public final class ApiCacheAugment {
    private ApiCacheAugment() {}

    private static final String TAG = "BH-APICACHE-AUG";
    private static final String KEY_PREFIX = "winemu_game_config";
    private static final String PREFS_REL =
            "/shared_prefs/sp_winemu_unified_resources.xml";

    // SharedPreferences <string name="COMPONENT:NAME">escaped-json</string>
    private static final Pattern ENTRY = Pattern.compile(
            "<string name=\"COMPONENT:[^\"]*\">(.*?)</string>", Pattern.DOTALL);

    // WinEmuRepo.entry (camelCase)  ->  config component element (snake_case).
    private static final String[][] MAP = {
            {"name", "name"}, {"version", "version"},
            {"versionCode", "version_code"}, {"fileMd5", "file_md5"},
            {"fileSize", "file_size"}, {"fileName", "file_name"},
            {"downloadUrl", "download_url"}, {"displayName", "display_name"},
            {"upgradeMsg", "upgrade_msg"}, {"frameworkType", "framework_type"},
            {"isSteam", "is_steam"}, {"framework", "framework"},
            {"type", "type"}, {"fileType", "fileType"}, {"id", "id"},
            {"logo", "logo"}, {"blurb", "blurb"}, {"status", "status"},
    };

    /**
     * Injected at {@code ApiCacheDao_Impl.saveCache} entry on the
     * {@code ApiCacheEntity} argument. Returns the (same, possibly mutated)
     * entity to persist.
     */
    public static Object augment(Object entity) {
        try {
            if (entity == null) return entity;
            String key = str(call(entity, "getKey"));
            if (key == null || !key.startsWith(KEY_PREFIX)) return entity;
            String body = str(call(entity, "getBody"));
            if (body == null || body.isEmpty()) return entity;

            JSONObject root = new JSONObject(body);
            JSONArray comp = root.optJSONArray("component");
            if (comp == null) comp = new JSONArray();

            Set<String> have = new HashSet<>();
            for (int i = 0; i < comp.length(); i++) {
                JSONObject o = comp.optJSONObject(i);
                if (o != null) have.add(o.optString("name"));
            }

            String prefs = readCatalog();
            if (prefs == null) {
                diag("no catalog (ctx/file) — passthrough key=" + key);
                return entity;
            }

            int added = 0;
            Matcher m = ENTRY.matcher(prefs);
            while (m.find()) {
                JSONObject el = toComponentElement(m.group(1));
                if (el == null) continue;
                String name = el.optString("name");
                if (name.isEmpty() || !have.add(name)) continue;
                comp.put(el);
                added++;
            }

            root.put("component", comp);
            setField(entity, "body", root.toString());
            diag("augment key=" + key + " added=" + added
                    + " total=" + comp.length());
            return entity;
        } catch (Throwable t) {
            diag("augment FAILED passthrough: " + t);
            return entity;
        }
    }

    /** One escaped WinEmuRepo JSON -> snake_case config component element. */
    private static JSONObject toComponentElement(String escaped) {
        try {
            String json = unescapeXml(escaped);
            JSONObject repo = new JSONObject(json);
            JSONObject entry = repo.optJSONObject("entry");
            if (entry == null) return null;
            JSONObject el = new JSONObject();
            for (String[] kv : MAP) {
                if (entry.has(kv[0])) el.put(kv[1], entry.get(kv[0]));
            }
            // Fall back to the WinEmuRepo-level name/version if entry lacked
            // them (shape has been stable, but be defensive).
            if (!el.has("name") && repo.has("name")) el.put("name", repo.get("name"));
            if (!el.has("version") && repo.has("version")) el.put("version", repo.get("version"));
            return el.has("name") ? el : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readCatalog() {
        try {
            Application app = currentApp();
            if (app == null) return null;
            String dataDir = app.getApplicationInfo().dataDir;
            if (dataDir == null) return null;
            File f = new File(dataDir + PREFS_REL);
            if (!f.exists() || !f.canRead()) return null;
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            }
            return new String(buf, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- helpers ------------------------------------------------------------

    private static String unescapeXml(String s) {
        // SharedPreferences double-escapes: XML entities wrapping HTML-escaped
        // JSON. Order matters; &amp; last.
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#10;", "\n").replace("&#13;", "\r")
                .replace("&#9;", "\t").replace("&amp;", "&");
    }

    private static Object call(Object o, String getter) {
        try {
            Method mth = method(o.getClass(), getter);
            return mth == null ? null : mth.invoke(o);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method method(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method mth = k.getDeclaredMethod(name);
                mth.setAccessible(true);
                return mth;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static void setField(Object o, String name, Object val) throws Exception {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, val);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Application currentApp() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object a = at.getMethod("currentApplication").invoke(null);
            return (a instanceof Application) ? (Application) a : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Verification breadcrumb. saveCache runs on a network-triggered DB write
     * (app fully up) so currentApplication() is valid here; no Context => no
     * log (never a hardcoded package — this ships to all variants).
     */
    private static void diag(String msg) {
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
        try {
            Application app = currentApp();
            if (app == null) return;
            File dir = app.getFilesDir();
            if (dir == null) return;
            byte[] line = (System.currentTimeMillis() + "  " + msg + "\n")
                    .getBytes("UTF-8");
            try (FileOutputStream fos =
                         new FileOutputStream(new File(dir, "bh_api_cache_augment.log"), true)) {
                fos.write(line);
                fos.flush();
            }
        } catch (Throwable ignored) {
        }
    }
}
