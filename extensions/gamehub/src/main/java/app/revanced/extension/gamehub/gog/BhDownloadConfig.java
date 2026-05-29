package app.revanced.extension.gamehub.gog;

/**
 * Per-download thread-count presets. Used by the install-confirmation dialog and
 * passed through {@link BhDownloadService} to the download managers.
 *
 * No global setting — every install opens at {@link #DEFAULT_THREADS} and the user
 * can override per-install via the dialog. The chosen value is plumbed through the
 * service intent and lives only for that one download.
 */
public final class BhDownloadConfig {

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
