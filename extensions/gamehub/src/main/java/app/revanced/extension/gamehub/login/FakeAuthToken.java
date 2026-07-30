package app.revanced.extension.gamehub.login;

import android.util.Log;

import app.revanced.extension.gamehub.debug.DebugTrace;


/**
 * Constructs a synthetic auth-token wrapper so AUTH_INTERFACE.f() returns a
 * non-null value when login is bypassed. The wrapper carries the user-id
 * string in field 'a' and the username in field 'b'.
 *
 * Class name is R8-mangled and must be updated per base-APK bump:
 *   6.0.0 → l4m
 *   6.0.1 → fdm
 *   6.0.2 → kpm
 *   6.0.4 → wpm  (current)
 * NOTE: the constructor signature is NOT stable across versions — 6.1.0
 * changed it, which is why construction is now shape-based via SyntheticModel
 * rather than pinned to an exact parameter list.
 * The first two String args are non-null asserted via getClass() in <init>.
 *
 * On a base APK bump, find the new letter via BypassLoginPatch.kt's
 * AUTH_TOKEN structural anchor (10-field data class returned by the auth
 * interface's f() method).
 */
public final class FakeAuthToken {
    private static final String TAG = "GH600-DEBUG";
    private static final String FAKE_USER_ID = "99999";

    /** R8-mangled class name of the auth-token wrapper. Update on base APK bump. */
    // 6.1.0: Lqbm; -> Lpfr; (= "UserToken"; AUTH_INTERFACE.i() return type; 10 fields
    //   S,S,S,S,Long,Long,J,Z,J,J with .a = userId — same shape as 6.0.9).
    //   History: 6.0.9 qbm, 6.0.8 t2l, 6.0.7 n2l, earlier wpm.
    private static final String AUTH_TOKEN_CLASS = "pfr";

    private static volatile Object cached;

    public static Object get() {
        DebugTrace.write("FakeAuthToken.get() called");
        Object t = cached;
        if (t != null) return t;
        synchronized (FakeAuthToken.class) {
            if (cached != null) return cached;
            cached = SyntheticModel.build(AUTH_TOKEN_CLASS, FAKE_USER_ID);
            return cached;
        }
    }
}
