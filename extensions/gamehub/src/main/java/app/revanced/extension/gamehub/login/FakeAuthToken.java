package app.revanced.extension.gamehub.login;

import android.util.Log;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

/**
 * Constructs a synthetic l4m (auth-token wrapper) so is0.f() returns a
 * non-null value when login is bypassed. l4m carries the user-id string in
 * field 'a' and the username in field 'b'. Consumers we know of:
 *   - xm7 (GameLibraryRepository) — read+write, via xm7.f() → is0.f().a
 *   - lvd ("checkLocalHandTourGame" request prep) — reads l4m.b
 *   - aae (synthetic property-getter lambda)
 *
 * l4m is in the R8-renamed default package, so we reach it via reflection.
 * Constructor sig: (String,String,String,String,Long,Long,J,Z,J,J)V
 * The first two String args are non-null asserted via getClass() in <init>.
 */
public final class FakeAuthToken {
    private static final String TAG = "GH600-DEBUG";
    private static final String FAKE_USER_ID = "99999";
    private static volatile Object cached;

    public static Object get() {
        Object t = cached;
        if (t != null) return t;
        synchronized (FakeAuthToken.class) {
            if (cached != null) return cached;
            try {
                Class<?> l4m = Class.forName("l4m");
                Constructor<?> ctor = l4m.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        Long.class, Long.class,
                        long.class, boolean.class,
                        long.class, long.class);
                ctor.setAccessible(true);
                cached = ctor.newInstance(
                        FAKE_USER_ID, "", null, null, null, null,
                        0L, false, 0L, 0L);
                DebugTrace.write("FakeAuthToken: built synthetic l4m a=" + FAKE_USER_ID);
                return cached;
            } catch (Throwable e) {
                DebugTrace.write("FakeAuthToken: construction failed", e);
                return null;
            }
        }
    }
}
