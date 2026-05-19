package com.xj.winemu.gpuspoof;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.xj.winemu.common.BhMenuGameId;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * BhGpuSpoofController — per-game GPU-identity spoofing for GameHub/BannerHub.
 *
 * CryEngine (Crysis 2 / 3) and a number of other titles gate on the Vulkan/
 * D3D adapter's PCI vendor/device IDs. On Adreno the adapter reports vendor
 * 0x5143 (Qualcomm) which CryEngine's whitelist rejects ("Unsupported video
 * card detected" → crash after OK). DXVK exposes
 * {@code dxgi.customVendorId} / {@code dxgi.customDeviceId} (and the d3d9 /
 * dxvk equivalents) to override exactly these fields. This controller stores
 * a per-game spoof choice and, at Wine launch, writes a small dxvk.conf and
 * points {@code DXVK_CONFIG_FILE} at it.
 *
 * Storage mirrors {@code BhVibrationController}: per-game values live in the
 * stock {@code pc_g_setting<gameId>} SharedPreferences file under
 * {@code bh_gpuspoof_*} keys so {@code BhSettingsExporter}'s existing
 * export/import path carries them automatically; a global default lives in
 * {@code bh_gpuspoof_prefs}. Files lacking our keys default to MODE_OFF →
 * stock behaviour, zero regression risk.
 */
public final class BhGpuSpoofController {

    private static final String TAG = "BhGpuSpoof";

    // Mode 0 = off (stock, zero regression). 1 = spoof a GPU picked from the
    // cascading Vendor → Model list (BhGpuCards, 313 cards). 2 = custom hex.
    // Modes 1 and 2 both just apply the stored vendor/device/name triplet —
    // the only difference is which editor BhGpuSpoofSettingsActivity shows.
    public static final int MODE_OFF    = 0;
    public static final int MODE_SPOOF  = 1;
    public static final int MODE_CUSTOM = 2;
    public static final int MODE_MAX    = 2;

    public static final String GLOBAL_PREFS_FILE = "bh_gpuspoof_prefs";
    public static final String PER_GAME_PREFS_FMT = "pc_g_setting%s";
    public static final String KEY_MODE   = "bh_gpuspoof_mode";
    public static final String KEY_VENDOR = "bh_gpuspoof_vendor";
    public static final String KEY_DEVICE = "bh_gpuspoof_device";
    public static final String KEY_NAME   = "bh_gpuspoof_name";
    /** Opt-in: also spoof DX12/VKD3D + native Vulkan via the libGameScopeV2
     *  ICD. Disables AI frame-gen direct rendering for the game, so it is a
     *  deliberate per-game choice rather than always-on. */
    public static final String KEY_DEEP   = "bh_gpuspoof_deep";

    // The app builds VK_ICD_FILENAMES as new File(<prefix>/usr, SUFFIX_VK)
    // → libGameScopeVK.so (frame-gen / direct rendering). imagefs 1.4.1 also
    // ships a same-named ICD json at <prefix>/usr/<SUFFIX_V2> pointing at
    // libGameScopeV2.so, whose vkGetPhysicalDeviceProperties2 hook rewrites
    // the adapter from the GAMESCOPE_SPOOF_* env vars. Swapping the suffix in
    // whatever path the app already computed is base-agnostic.
    private static final String SUFFIX_VK =
            "home/steamuser/.config/vulkan/icd.d/GameScopeVK_icd.json";
    private static final String SUFFIX_V2 = "share/vulkan/GameScopeVK_icd.json";

    private static final int DEFAULT_MODE = MODE_OFF;

    private static volatile BhGpuSpoofController INSTANCE;

    private Context appContext;
    private String containerGameId;

    private int     cachedMode   = DEFAULT_MODE;
    private String  cachedVendor = "";
    private String  cachedDevice = "";
    private String  cachedName   = "";
    private boolean cachedDeep   = false;

    public static BhGpuSpoofController getInstance() {
        BhGpuSpoofController i = INSTANCE;
        if (i == null) {
            synchronized (BhGpuSpoofController.class) {
                i = INSTANCE;
                if (i == null) {
                    i = new BhGpuSpoofController();
                    INSTANCE = i;
                }
            }
        }
        return i;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Settings API (called from BhGpuSpoofSettingsActivity)
    // ─────────────────────────────────────────────────────────────────────

    public void init(Context ctx) {
        if (ctx != null && this.appContext == null) {
            this.appContext = ctx.getApplicationContext();
        }
        reloadSettings();
    }

    /** Scope per-game; gameId carried in via Intent from the menu row. */
    public void setContainerForSettings(String gameId) {
        this.containerGameId = (gameId == null || gameId.isEmpty()) ? null : gameId;
        reloadSettings();
        Log.i(TAG, "container=" + (containerGameId != null ? containerGameId : "(global)")
                + " mode=" + cachedMode + " vendor=" + cachedVendor + " device=" + cachedDevice);
    }

    public int     getMode()   { return cachedMode; }
    public String  getVendor() { return cachedVendor; }
    public String  getDevice() { return cachedDevice; }
    public String  getName()   { return cachedName; }
    public boolean getDeep()   { return cachedDeep; }

    // STRICT PER-GAME: state is written ONLY to our own bh_gpuspoof_prefs file,
    // keyed per-game (KEY + "__" + gameId). NEVER global, NEVER the host-owned
    // pc_g_setting<id> (host rewrites that → reset-to-default bug). No game in
    // scope ⇒ nothing is persisted (a spoof set for one game stays that game's
    // only — it never leaks to other games or app-wide).

    public void setDeep(boolean deep) {
        this.cachedDeep = deep;
        if (containerGameId != null) writeBoolOwned(pgKey(KEY_DEEP, containerGameId), deep);
    }

    public void setMode(int mode) {
        if (mode < 0 || mode > MODE_MAX) return;
        this.cachedMode = mode;
        if (containerGameId != null) writeIntOwned(pgKey(KEY_MODE, containerGameId), mode);
    }

    /** Custom vendor/device/name — only meaningful when mode == MODE_CUSTOM. */
    public void setCustom(String vendorHex, String deviceHex, String name) {
        this.cachedVendor = sanitizeHex(vendorHex);
        this.cachedDevice = sanitizeHex(deviceHex);
        this.cachedName   = name == null ? "" : name.trim();
        if (containerGameId != null) {
            writeStringOwned(pgKey(KEY_VENDOR, containerGameId), cachedVendor);
            writeStringOwned(pgKey(KEY_DEVICE, containerGameId), cachedDevice);
            writeStringOwned(pgKey(KEY_NAME,   containerGameId), cachedName);
        }
    }

    /** Strip "0x"/"0X" prefix and non-hex chars; lowercase. */
    public static String sanitizeHex(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 8; i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) b.append(c);
        }
        return b.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Smali entry: Lbg5;->a(...) Wine env builder, injected AFTER the app's
    // own DXVK_CONFIG_FILE block so our EnvVars write wins.
    //
    //   invoke-static {vEnv}, BhGpuSpoofController->applyGpuSpoof(Object)V
    //
    // Return-safe: any failure logs and leaves the env untouched (stock).
    // ─────────────────────────────────────────────────────────────────────
    public static void applyGpuSpoof(Object envVars) {
        try {
            getInstance().applyGpuSpoofImpl(envVars);
        } catch (Throwable t) {
            Log.w(TAG, "applyGpuSpoof failed", t);
        }
    }

    private void applyGpuSpoofImpl(Object envVars) {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null || envVars == null) return;

        // Scope to the launching game. The env builder Lbg5;->a often runs
        // BEFORE WineActivity is registered in ActivityThread.mActivities, so
        // sniffGameIdFromStack() returns null at launch and the spoof would
        // silently no-op as "global → off" (observed: store correct but
        // bh_gpuspoof_dxvk.conf never rewritten, game keeps real adapter).
        // MenuGameIdCapturePatch already stashed the id when the user opened
        // the per-game menu to set the spoof, so prefer that — identical
        // resolution order to BhGpuSpoofMenuRowClick — and only fall back to
        // the stack sniff (covers the in-game sidebar path).
        String gid = BhMenuGameId.getCaptured();
        if (gid == null || gid.isEmpty()) gid = sniffGameIdFromStack();
        setContainerForSettings(gid);

        if (cachedMode == MODE_OFF) {
            Log.i(TAG, "spoof off for " + (gid != null ? gid : "(global)") + " — stock env");
            return;
        }

        // MODE_SPOOF and MODE_CUSTOM both apply the stored triplet; SPOOF's
        // was written by the Model spinner, CUSTOM's typed by the user.
        String vendor = sanitizeHex(cachedVendor);
        String device = sanitizeHex(cachedDevice);
        String desc   = cachedName == null ? "" : cachedName;
        if (vendor.isEmpty() || device.isEmpty()) {
            Log.w(TAG, "spoof mode=" + cachedMode + " but vendor/device empty — skipping");
            return;
        }

        // Primary mechanism: DXVK's inline DXVK_CONFIG env var (DXVK >= 2.1;
        // this container ships DXVK 2.4.1). Entries are ';'-separated. This
        // avoids a config FILE entirely — the earlier file approach wrote to
        // ctx.getFilesDir() (/data/user/0/<pkg>/files/...), which is NOT
        // visible inside the Proton/FEX guest filesystem, so DXVK could
        // never open it. DXVK_CONFIG rides the exact same env channel as the
        // working DXVK_HUD/DXVK_ASYNC the app already sets, so no path or
        // mount-namespace dependency.
        //
        // DXGI (D3D10/11), D3D9 and generic dxvk.* keys are all set so the
        // adapter-identity surface CryEngine reads is covered regardless of
        // which DXVK frontend the title uses.
        // DXVK's inline DXVK_CONFIG parser tokenises each value on whitespace,
        // so a free-text string like "NVIDIA GeForce RTX 4080" is truncated at
        // the first space (verified in GoW_dxgi.log: customDeviceDesc came
        // through as just "NVIDIA"). The space-free numeric IDs survive. So
        // the inline channel carries ONLY the IDs; the human-readable
        // customDeviceDesc rides the CONFIG FILE below (the dxvk.conf file
        // parser handles spaces), pointed at by DXVK_CONFIG_FILE.
        StringBuilder inline = new StringBuilder();
        appendKv(inline, "dxgi.customVendorId", vendor);
        appendKv(inline, "dxgi.customDeviceId", device);
        appendKv(inline, "d3d9.customVendorId", vendor);
        appendKv(inline, "d3d9.customDeviceId", device);
        appendKv(inline, "dxvk.customVendorId", vendor);
        appendKv(inline, "dxvk.customDeviceId", device);
        String dxvkConfig = inline.toString();

        // Config FILE body: the same IDs (newline-separated) PLUS the
        // space-containing customDeviceDesc, which only the file parser
        // reads correctly. DXVK loads the file then lets DXVK_CONFIG env
        // override per-key; customDeviceDesc isn't in the env so the file's
        // full value stands. Point DXVK_CONFIG_FILE at it below.
        StringBuilder fileSb = new StringBuilder(dxvkConfig.replace(';', '\n'));
        if (!desc.isEmpty()) {
            fileSb.append("dxgi.customDeviceDesc = ").append(desc).append('\n');
        }
        String fileBody = fileSb.toString();
        String confPath = null;
        try {
            File out = new File(ctx.getFilesDir(), "bh_gpuspoof_dxvk.conf");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false);
            try {
                fos.write(fileBody.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            confPath = out.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "could not write dxvk.conf (non-fatal; DXVK_CONFIG still set)", t);
        }

        // EnvVars#a(String key, Object value) — same setter the env builder
        // uses for DXVK_HUD/DXVK_CONFIG_FILE. Injected after the app's own
        // conditional DXVK block so our values win.
        try {
            Method a = envVars.getClass().getMethod("a", String.class, Object.class);
            a.setAccessible(true);
            a.invoke(envVars, "DXVK_CONFIG", dxvkConfig);
            if (confPath != null) {
                a.invoke(envVars, "DXVK_CONFIG_FILE", confPath);
            }
            // DIAGNOSTIC (pre6): force DXVK logging via the SAME env channel.
            // If DXVK then writes d3d9.log/dxgi.log into filesDir, env
            // propagation to the guest works and the log shows whether it
            // read our config + what adapter it reports. If no log appears,
            // env vars we set here are NOT reaching the game process — that
            // is the real bug, not the spoof keys/path. filesDir is proven
            // guest-visible (the prefix system32 d3d9.dll symlink resolves a
            // /data/user/0/<pkg>/files/... path and DXVK loads from it).
            a.invoke(envVars, "DXVK_LOG_LEVEL", "info");
            a.invoke(envVars, "DXVK_LOG_PATH", ctx.getFilesDir().getAbsolutePath());
            Log.i(TAG, "GPU spoof active: " + vendor + ":" + device
                    + " (" + desc + ") for " + (gid != null ? gid : "(global)")
                    + " | DXVK_CONFIG=[" + dxvkConfig + "] file="
                    + (confPath != null ? confPath : "(skipped)")
                    + " | DXVK_LOG -> " + ctx.getFilesDir());
        } catch (Throwable t) {
            Log.w(TAG, "EnvVars#a reflection failed; spoof not applied", t);
        }

        // ── Prong B: wined3d (D3D9/10/11 NOT on DXVK) via wine registry ──
        // Harmless when the title uses DXVK (these keys only matter to
        // wined3d). Applied whenever a spoof is active; no toggle.
        try {
            String prefix = readEnv(envVars, "WINEPREFIX");
            if (prefix != null && !prefix.isEmpty()) {
                upsertWineRegistry(new File(prefix, "user.reg"), vendor, device, desc);
            } else {
                Log.i(TAG, "WINEPREFIX not in env — skipping wined3d prong");
            }
        } catch (Throwable t) {
            Log.w(TAG, "wined3d registry prong failed (non-fatal)", t);
        }

        // ── Prong C: DX12/VKD3D + native Vulkan via libGameScopeV2 ──
        // Opt-in only (cachedDeep): swapping to V2 disables frame-gen direct
        // rendering for this game.
        if (cachedDeep) {
            try {
                applyVulkanSpoof(envVars, vendor, device, desc);
            } catch (Throwable t) {
                Log.w(TAG, "Vulkan (deep) spoof prong failed (non-fatal)", t);
            }
        }
    }

    /** Reads an already-set env value out of EnvVars' public LinkedHashMap a. */
    private static String readEnv(Object envVars, String key) {
        try {
            Field f;
            try { f = envVars.getClass().getField("a"); }
            catch (NoSuchFieldException e) { f = envVars.getClass().getDeclaredField("a"); }
            f.setAccessible(true);
            Object m = f.get(envVars);
            if (m instanceof Map) {
                Object v = ((Map<?, ?>) m).get(key);
                return v == null ? null : v.toString();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void putEnv(Object envVars, String key, String val) throws Exception {
        Method a = envVars.getClass().getMethod("a", String.class, Object.class);
        a.setAccessible(true);
        a.invoke(envVars, key, val);
    }

    /**
     * DX12/VKD3D + native Vulkan: swap VK_ICD_FILENAMES from libGameScopeVK
     * (frame-gen) to the libGameScopeV2 (device-spoof) ICD shipped in imagefs
     * 1.4.1, and feed it the adapter via GAMESCOPE_SPOOF_*. V2 hooks
     * vkGetPhysicalDeviceProperties2 so this covers every Vulkan-backed API
     * (DX12/VKD3D, DXVK, native Vulkan) at once — at the cost of V2 dropping
     * the DirectRendering compositor path (frame-gen) for this game.
     */
    private void applyVulkanSpoof(Object envVars, String vendor, String device, String desc)
            throws Exception {
        String cur = readEnv(envVars, "VK_ICD_FILENAMES");
        if (cur == null || !cur.contains(SUFFIX_VK)) {
            Log.w(TAG, "VK_ICD_FILENAMES missing/unexpected ('" + cur
                    + "') — cannot swap to libGameScopeV2; deep spoof skipped");
            return;
        }
        String swapped = cur.replace(SUFFIX_VK, SUFFIX_V2);
        putEnv(envVars, "VK_ICD_FILENAMES", swapped);
        // libGameScopeV2 parses these with strtoul (base 0) — the 0x prefix
        // forces hex regardless of base.
        putEnv(envVars, "GAMESCOPE_SPOOF_VENDOR_ID", "0x" + vendor);
        putEnv(envVars, "GAMESCOPE_SPOOF_DEVICE_ID", "0x" + device);
        if (desc != null && !desc.isEmpty()) {
            putEnv(envVars, "GAMESCOPE_SPOOF_DEVICE_NAME", desc);
        }
        Log.i(TAG, "deep (DX12/Vulkan) spoof: ICD -> libGameScopeV2 [" + swapped
                + "], GAMESCOPE_SPOOF 0x" + vendor + ":0x" + device
                + " (frame-gen direct rendering disabled for this game)");
    }

    /**
     * wined3d reads HKCU\Software\Wine\Direct3D VideoPciVendorID /
     * VideoPciDeviceID (DWORD) + VideoDescription (string). Upserts those into
     * user.reg in place, atomically, with a one-time backup. Wine loads
     * user.reg at wineserver start (after this env builder runs) so the edit
     * takes effect for this launch. Mirrors GameNative/Winlator ContainerUtils.
     */
    private void upsertWineRegistry(File userReg, String vendorHex, String deviceHex, String desc) {
        try {
            if (userReg == null || !userReg.isFile()) {
                Log.i(TAG, "user.reg not found (" + userReg + ") — skipping wined3d prong");
                return;
            }
            long vendorId = Long.parseLong(vendorHex, 16);
            long deviceId = Long.parseLong(deviceHex, 16);
            String vDword = String.format("dword:%08x", vendorId);
            String dDword = String.format("dword:%08x", deviceId);
            String descLine = (desc == null || desc.isEmpty()) ? null
                    : "\"VideoDescription\"=\""
                      + desc.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";

            java.util.List<String> lines = new java.util.ArrayList<>();
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(userReg), "UTF-8"));
            try {
                String ln;
                while ((ln = br.readLine()) != null) lines.add(ln);
            } finally { br.close(); }

            // Section name is stored with doubled backslashes in user.reg.
            final String HDR = "[Software\\\\Wine\\\\Direct3D]";
            int secStart = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(HDR)) { secStart = i; break; }
            }

            boolean setV = false, setD = false, setDesc = (descLine == null);
            if (secStart >= 0) {
                int i = secStart + 1;
                for (; i < lines.size(); i++) {
                    String s = lines.get(i);
                    if (s.startsWith("[")) break;                 // next section
                    if (s.startsWith("\"VideoPciVendorID\"=")) {
                        lines.set(i, "\"VideoPciVendorID\"=" + vDword); setV = true;
                    } else if (s.startsWith("\"VideoPciDeviceID\"=")) {
                        lines.set(i, "\"VideoPciDeviceID\"=" + dDword); setD = true;
                    } else if (descLine != null && s.startsWith("\"VideoDescription\"=")) {
                        lines.set(i, descLine); setDesc = true;
                    }
                }
                int ins = i;
                if (!setDesc && descLine != null) lines.add(ins++, descLine);
                if (!setD) lines.add(ins++, "\"VideoPciDeviceID\"=" + dDword);
                if (!setV) lines.add(ins++, "\"VideoPciVendorID\"=" + vDword);
            } else {
                lines.add("");
                lines.add(HDR + " " + (System.currentTimeMillis() / 1000L));
                lines.add("\"VideoPciVendorID\"=" + vDword);
                lines.add("\"VideoPciDeviceID\"=" + dDword);
                if (descLine != null) lines.add(descLine);
            }

            File bak = new File(userReg.getParentFile(), "user.reg.bhgpuspoof.bak");
            if (!bak.exists()) copyFile(userReg, bak);

            File tmp = new File(userReg.getParentFile(), "user.reg.bhtmp");
            java.io.BufferedWriter bw = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(tmp, false), "UTF-8"));
            try {
                for (String s : lines) { bw.write(s); bw.write("\n"); }
            } finally { bw.close(); }
            if (!tmp.renameTo(userReg)) { copyFile(tmp, userReg); tmp.delete(); }
            Log.i(TAG, "wined3d registry: Software\\Wine\\Direct3D VideoPci{Vendor="
                    + vDword + ",Device=" + dDword + "} in " + userReg);
        } catch (Throwable t) {
            Log.w(TAG, "upsertWineRegistry failed (non-fatal)", t);
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(src);
        try {
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally { out.close(); }
        } finally { in.close(); }
    }

    /** Appends "k = v;" — ';'-separated for the DXVK_CONFIG inline env var. */
    private static void appendKv(StringBuilder b, String k, String v) {
        b.append(k).append(" = ").append(v).append(';');
    }

    // ─────────────────────────────────────────────────────────────────────
    // Settings I/O — mirrors BhVibrationController
    // ─────────────────────────────────────────────────────────────────────

    /** Per-game key inside our OWN bh_gpuspoof_prefs file. */
    private static String pgKey(String base, String gameId) {
        return base + "__" + gameId;
    }

    private void reloadSettings() {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null) return;

        // No game in scope ⇒ stock defaults, nothing persisted/applied.
        // (Strictly per-game: no global value exists or is consulted.)
        if (containerGameId == null) {
            cachedMode = DEFAULT_MODE; cachedVendor = ""; cachedDevice = "";
            cachedName = ""; cachedDeep = false;
            return;
        }

        String gid = containerGameId;
        SharedPreferences own =
                ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE);

        // One-time migration: if this game has no entry in our own store yet,
        // adopt its legacy value from the host-owned pc_g_setting<id> co-store
        // (the old buggy location) so an already-set game keeps its choice.
        // We do NOT read the abandoned unsuffixed "global" keys.
        if (!own.contains(pgKey(KEY_MODE, gid))) {
            try {
                SharedPreferences legacy = ctx.getSharedPreferences(
                        String.format(PER_GAME_PREFS_FMT, gid), Context.MODE_PRIVATE);
                if (legacy.contains(KEY_MODE)) {
                    own.edit()
                       .putInt(pgKey(KEY_MODE, gid),   legacy.getInt(KEY_MODE, DEFAULT_MODE))
                       .putString(pgKey(KEY_VENDOR, gid), legacy.getString(KEY_VENDOR, ""))
                       .putString(pgKey(KEY_DEVICE, gid), legacy.getString(KEY_DEVICE, ""))
                       .putString(pgKey(KEY_NAME, gid),   legacy.getString(KEY_NAME, ""))
                       .putBoolean(pgKey(KEY_DEEP, gid),  legacy.getBoolean(KEY_DEEP, false))
                       .apply();
                }
            } catch (Throwable ignored) {
            }
        }

        cachedMode   = own.getInt(pgKey(KEY_MODE, gid), DEFAULT_MODE);
        cachedVendor = own.getString(pgKey(KEY_VENDOR, gid), "");
        cachedDevice = own.getString(pgKey(KEY_DEVICE, gid), "");
        cachedName   = own.getString(pgKey(KEY_NAME, gid), "");
        cachedDeep   = own.getBoolean(pgKey(KEY_DEEP, gid), false);
    }

    // Owned-store writers (bh_gpuspoof_prefs, full per-game key) — host-proof.
    private void writeBoolOwned(String fullKey, boolean val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(fullKey, val).apply();
    }

    private void writeIntOwned(String fullKey, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putInt(fullKey, val).apply();
    }

    private void writeStringOwned(String fullKey, String val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putString(fullKey, val).apply();
    }

    private Context ctxOrNull() {
        ensureContext();
        return appContext;
    }

    private void ensureContext() {
        if (appContext != null) return;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            Object app = m.invoke(null);
            if (app instanceof Context) {
                appContext = ((Context) app).getApplicationContext();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureContext failed", t);
        }
    }

    /** If a WineActivity is in the stack, grab its gameId Intent extra. */
    private static String sniffGameIdFromStack() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method cur = atCls.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at == null) return null;
            Field fActs = atCls.getDeclaredField("mActivities");
            fActs.setAccessible(true);
            Object acts = fActs.get(at);
            if (!(acts instanceof Map)) return null;
            for (Object record : ((Map<?, ?>) acts).values()) {
                if (record == null) continue;
                Field fAct = record.getClass().getDeclaredField("activity");
                fAct.setAccessible(true);
                Object a = fAct.get(record);
                if (!(a instanceof Activity)) continue;
                if (!a.getClass().getName().endsWith(".WineActivity")) continue;
                Intent it = ((Activity) a).getIntent();
                if (it == null) continue;
                String gid = it.getStringExtra("gameId");
                if (gid != null && !gid.isEmpty()) return gid;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
