package app.revanced.extension.gamehub.login;

import android.util.Log;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

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
 * Constructor sig is stable across versions: (S,S,S,S,Long,Long,J,Z,J,J)V.
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
    private static final String AUTH_TOKEN_CLASS = "qbm"; // 6.0.9 (6.0.8 t2l, 6.0.7 n2l, was wpm); = Lrx0;->f() return type, 10-field (S,S,S,S,Long,Long,J,Z,J,J), .a=userId. Ctor sig IDENTICAL to 6.0.8 (verified ~/gh609-apktool-d).

    private static volatile Object cached;

    public static Object get() {
        DebugTrace.write("FakeAuthToken.get() called");
        Object t = cached;
        if (t != null) return t;
        synchronized (FakeAuthToken.class) {
            if (cached != null) return cached;
            try {
                Class<?> tokenClass = Class.forName(AUTH_TOKEN_CLASS);
                Constructor<?> ctor = tokenClass.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        Long.class, Long.class,
                        long.class, boolean.class,
                        long.class, long.class);
                ctor.setAccessible(true);
                cached = ctor.newInstance(
                        FAKE_USER_ID, "", null, null, null, null,
                        0L, false, 0L, 0L);
                DebugTrace.write("FakeAuthToken: built synthetic " + AUTH_TOKEN_CLASS + " a=" + FAKE_USER_ID);
                return cached;
            } catch (Throwable e) {
                DebugTrace.write("FakeAuthToken: construction failed", e);
                return null;
            }
        }
    }
}
