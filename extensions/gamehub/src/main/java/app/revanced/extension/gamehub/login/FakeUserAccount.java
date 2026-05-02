package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

/**
 * Constructs a synthetic f4m (user-account) so is0.e() and is0.b() return a
 * non-null value when login is bypassed. The Compose library-list pipeline
 * is `is0.e().flatMapLatest { f4m? -> if (null) emptyFlow else dao.subjectAllByUserId(f4m.a) }`.
 * Without an auth-token row in the DB, os0.e()'s underlying StateFlow emits
 * null and the list shows empty even after the row is in t_game_library_base.
 *
 * f4m has 27 fields (a..z plus A). Constructor sig:
 *   (String,String,String,String,String,String,I,I,Z,String,I,I,I,I,I,J,
 *    String,String,I,I,String,J,I,String,String,J,J)V
 * Smali ctor body asserts non-null on p1 (a) and p18 (q, the 17th Java arg).
 * We pass empty strings for every String field to be safe.
 */
public final class FakeUserAccount {
    private static final String FAKE_USER_ID = "99999";
    private static volatile Object cached;

    public static Object get() {
        DebugTrace.write("FakeUserAccount.get() called");
        Object u = cached;
        if (u != null) return u;
        synchronized (FakeUserAccount.class) {
            if (cached != null) return cached;
            try {
                Class<?> f4m = Class.forName("f4m");
                Constructor<?> ctor = f4m.getDeclaredConstructor(
                        String.class, String.class, String.class, String.class,
                        String.class, String.class,
                        int.class, int.class, boolean.class,
                        String.class,
                        int.class, int.class, int.class, int.class, int.class,
                        long.class,
                        String.class, String.class,
                        int.class, int.class,
                        String.class,
                        long.class,
                        int.class,
                        String.class, String.class,
                        long.class, long.class);
                ctor.setAccessible(true);
                cached = ctor.newInstance(
                        FAKE_USER_ID, "", "", "", "", "",
                        0, 0, false,
                        "",
                        0, 0, 0, 0, 0,
                        0L,
                        "", "",
                        0, 0,
                        "",
                        0L,
                        0,
                        "", "",
                        0L, 0L);
                DebugTrace.write("FakeUserAccount: built synthetic f4m a=" + FAKE_USER_ID);
                return cached;
            } catch (Throwable e) {
                DebugTrace.write("FakeUserAccount: construction failed", e);
                return null;
            }
        }
    }
}
