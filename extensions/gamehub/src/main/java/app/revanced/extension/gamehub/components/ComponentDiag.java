package app.revanced.extension.gamehub.components;

import android.util.Log;

/**
 * Diagnostic logging entry point used by {@code ComponentDiagnosticPatch}.
 * Each candidate method has a smali prologue that calls {@link #log(String)}
 * with a unique {@code "ClassName.methodName"} tag, so logcat shows exactly
 * which methods fire when the user opens the per-game settings dropdown.
 *
 * <p>Logs to logcat tag {@code GH600-DIAG} for grep-friendliness:
 * {@code getlog com.xiaoji.egggame | grep GH600-DIAG}.</p>
 */
public final class ComponentDiag {
    private static final String TAG = "GH600-DIAG";

    private ComponentDiag() {}

    public static void log(String label) {
        Log.i(TAG, label);
    }
}
