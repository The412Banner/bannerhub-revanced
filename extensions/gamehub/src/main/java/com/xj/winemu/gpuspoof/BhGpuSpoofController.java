package com.xj.winemu.gpuspoof;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

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

    private static final int DEFAULT_MODE = MODE_OFF;

    private static volatile BhGpuSpoofController INSTANCE;

    private Context appContext;
    private String containerGameId;

    private int    cachedMode   = DEFAULT_MODE;
    private String cachedVendor = "";
    private String cachedDevice = "";
    private String cachedName   = "";

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

    public int    getMode()   { return cachedMode; }
    public String getVendor() { return cachedVendor; }
    public String getDevice() { return cachedDevice; }
    public String getName()   { return cachedName; }

    public void setMode(int mode) {
        if (mode < 0 || mode > MODE_MAX) return;
        this.cachedMode = mode;
        writeIntGlobal(KEY_MODE, mode);
        if (containerGameId != null) writeIntPerGame(containerGameId, KEY_MODE, mode);
    }

    /** Custom vendor/device/name — only meaningful when mode == MODE_CUSTOM. */
    public void setCustom(String vendorHex, String deviceHex, String name) {
        this.cachedVendor = sanitizeHex(vendorHex);
        this.cachedDevice = sanitizeHex(deviceHex);
        this.cachedName   = name == null ? "" : name.trim();
        writeStringGlobal(KEY_VENDOR, cachedVendor);
        writeStringGlobal(KEY_DEVICE, cachedDevice);
        writeStringGlobal(KEY_NAME,   cachedName);
        if (containerGameId != null) {
            writeStringPerGame(containerGameId, KEY_VENDOR, cachedVendor);
            writeStringPerGame(containerGameId, KEY_DEVICE, cachedDevice);
            writeStringPerGame(containerGameId, KEY_NAME,   cachedName);
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

        // Scope to the game that is launching (its WineActivity is in the
        // stack with a "gameId" Intent extra by the time the env is built).
        String gid = sniffGameIdFromStack();
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
        StringBuilder inline = new StringBuilder();
        appendKv(inline, "dxgi.customVendorId", vendor);
        appendKv(inline, "dxgi.customDeviceId", device);
        if (!desc.isEmpty()) appendKv(inline, "dxgi.customDeviceDesc", desc);
        appendKv(inline, "d3d9.customVendorId", vendor);
        appendKv(inline, "d3d9.customDeviceId", device);
        appendKv(inline, "dxvk.customVendorId", vendor);
        appendKv(inline, "dxvk.customDeviceId", device);
        String dxvkConfig = inline.toString();

        // Belt-and-braces: also write a file and point DXVK_CONFIG_FILE at it,
        // in case a future container's DXVK predates DXVK_CONFIG or the path
        // does happen to be guest-visible. Newline-separated for the file.
        String fileBody = dxvkConfig.replace(';', '\n');
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
    }

    /** Appends "k = v;" — ';'-separated for the DXVK_CONFIG inline env var. */
    private static void appendKv(StringBuilder b, String k, String v) {
        b.append(k).append(" = ").append(v).append(';');
    }

    // ─────────────────────────────────────────────────────────────────────
    // Settings I/O — mirrors BhVibrationController
    // ─────────────────────────────────────────────────────────────────────

    private void reloadSettings() {
        ensureContext();
        Context ctx = appContext;
        if (ctx == null) return;

        SharedPreferences gp = ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE);
        int    gMode   = gp.getInt(KEY_MODE, DEFAULT_MODE);
        String gVendor = gp.getString(KEY_VENDOR, "");
        String gDevice = gp.getString(KEY_DEVICE, "");
        String gName   = gp.getString(KEY_NAME, "");

        if (containerGameId == null) {
            cachedMode = gMode; cachedVendor = gVendor; cachedDevice = gDevice; cachedName = gName;
            return;
        }

        SharedPreferences pgp = ctx.getSharedPreferences(
                String.format(PER_GAME_PREFS_FMT, containerGameId), Context.MODE_PRIVATE);
        cachedMode   = pgp.getInt(KEY_MODE, gMode);
        cachedVendor = pgp.getString(KEY_VENDOR, gVendor);
        cachedDevice = pgp.getString(KEY_DEVICE, gDevice);
        cachedName   = pgp.getString(KEY_NAME, gName);
    }

    private void writeIntGlobal(String key, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putInt(key, val).apply();
    }

    private void writeIntPerGame(String gameId, String key, int val) {
        Context ctx = ctxOrNull();
        if (ctx == null || gameId == null || gameId.isEmpty()) return;
        ctx.getSharedPreferences(String.format(PER_GAME_PREFS_FMT, gameId), Context.MODE_PRIVATE)
                .edit().putInt(key, val).apply();
    }

    private void writeStringGlobal(String key, String val) {
        Context ctx = ctxOrNull();
        if (ctx == null) return;
        ctx.getSharedPreferences(GLOBAL_PREFS_FILE, Context.MODE_PRIVATE)
                .edit().putString(key, val).apply();
    }

    private void writeStringPerGame(String gameId, String key, String val) {
        Context ctx = ctxOrNull();
        if (ctx == null || gameId == null || gameId.isEmpty()) return;
        ctx.getSharedPreferences(String.format(PER_GAME_PREFS_FMT, gameId), Context.MODE_PRIVATE)
                .edit().putString(key, val).apply();
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
