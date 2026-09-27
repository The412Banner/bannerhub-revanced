package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Per-download thread-count presets. Used by the install-confirmation dialog and
 * passed through {@link BhDownloadService} to the download managers.
 *
 * No global setting — every install opens at {@link #DEFAULT_THREADS} and the user
 * can override per-install via the dialog. The chosen value is plumbed through the
 * service intent and lives only for that one download.
 *
 * One exception: the Rust GOG engine switch ({@link #isRustEngineEnabled}) IS a
 * persisted setting in {@code bh_gog_prefs}. It is read by
 * {@link GogDownloadManager} at download start (the point where the byte-fetch loop
 * begins), so flipping it takes effect on the next download without a restart and
 * needs no extra service-intent plumbing.
 */
public final class BhDownloadConfig {

    /** {@code bh_gog_prefs} key for the Rust GOG download engine switch. */
    public static final String PREF_RUST_ENGINE = "use_rust_gog_engine";

    /** Engine used when the user has never touched the toggle: the Rust engine. */
    public static final boolean RUST_ENGINE_DEFAULT = true;

    private static final String GOG_PREFS = "bh_gog_prefs";

    /**
     * True = {@code libblsteam.so} fetches the bytes (when it is available on this
     * install); false = the manager's existing Java loop, unchanged. Never throws.
     */
    public static boolean isRustEngineEnabled(Context ctx) {
        if (ctx == null) return RUST_ENGINE_DEFAULT;
        try {
            return ctx.getSharedPreferences(GOG_PREFS, 0)
                    .getBoolean(PREF_RUST_ENGINE, RUST_ENGINE_DEFAULT);
        } catch (Throwable t) {
            return RUST_ENGINE_DEFAULT;
        }
    }

    public static void setRustEngineEnabled(Context ctx, boolean enabled) {
        if (ctx == null) return;
        try {
            SharedPreferences.Editor ed = ctx.getSharedPreferences(GOG_PREFS, 0).edit();
            ed.putBoolean(PREF_RUST_ENGINE, enabled).apply();
        } catch (Throwable ignored) {
        }
    }

    public static final int LOW    = 4;
    public static final int MEDIUM = 8;
    public static final int HIGH   = 16;
    public static final int MAX    = 24;

    /** Default the picker opens at — conservative on CPU + battery. */
    public static final int DEFAULT_THREADS = LOW;

    /** Floor + ceiling for any incoming count (handles bad/legacy values). */
    public static final int MIN = 1;
    public static final int CAP = 32;

    private BhDownloadConfig() {}

    /** Map a preset constant to its label. */
    public static String labelFor(int threads) {
        if (threads <= LOW)    return "Low (" + LOW + " threads)";
        if (threads <= MEDIUM) return "Medium (" + MEDIUM + " threads)";
        if (threads <= HIGH)   return "High (" + HIGH + " threads)";
        return "Max (" + MAX + " threads)";
    }

    public static int auto() {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores < MIN) cores = MIN;
        if (cores > HIGH) cores = HIGH;
        return cores;
    }

    public static int clamp(int threads) {
        if (threads < MIN) return MIN;
        if (threads > CAP) return CAP;
        return threads;
    }

    public static int[] presets() {
        return new int[]{LOW, MEDIUM, HIGH, MAX, auto()};
    }

    public static String[] presetLabels() {
        int autoCount = auto();
        return new String[]{
                "Low (" + LOW + " threads)",
                "Medium (" + MEDIUM + " threads)",
                "High (" + HIGH + " threads)",
                "Max (" + MAX + " threads)",
                "Auto (" + autoCount + " — based on CPU)"
        };
    }
}
