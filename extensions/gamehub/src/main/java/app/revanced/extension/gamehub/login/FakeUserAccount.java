package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

/**
 * Constructs a synthetic user-account so AUTH_INTERFACE.e() and .b() return a
 * non-null value when login is bypassed. The Compose library-list pipeline is
 * `AUTH_INTERFACE.e().flatMapLatest { acc? -> if (null) emptyFlow else dao.subjectAllByUserId(acc.a) }`.
 * Without an auth-token row in the DB, the underlying StateFlow emits null
 * and the list shows empty even after the row is in t_game_library_base.
 *
 * Class name is R8-mangled and must be updated per base-APK bump:
 *   6.0.0 → f4m
 *   6.0.1 → adm
 *   6.0.2 → fpm
 *   6.0.4 → rpm  (current)
 * 27 fields (a..z plus A). Constructor sig is stable across versions:
 *   (String,String,String,String,String,String,I,I,Z,String,I,I,I,I,I,J,
 *    String,String,I,I,String,J,I,String,String,J,J)V
 * Smali ctor body asserts non-null on p1 (a) and p18 (q, the 17th Java arg).
 * Empty strings for every String field are safe.
 */
public final class FakeUserAccount {
    private static final String FAKE_USER_ID = "99999";

    /** R8-mangled class name of the user-account data class. Update on base APK bump. */
    private static final String USER_ACCOUNT_CLASS = "kbm"; // 6.0.9 (6.0.8 n2l, 6.0.7 h2l, 6.0.4 rpm); 27-field (a..z + A), = Lrx0;.b() return (qv7.h() reads Lkbm;->a as userId fallback). Ctor sig IDENTICAL to 6.0.8 (verified in ~/gh609-apktool-d). Device-confirmed pattern: stale class makes get() return null → library list empty despite the row being in t_game_library_base.

    private static volatile Object cached;

    public static Object get() {
        DebugTrace.write("FakeUserAccount.get() called");
        Object u = cached;
        if (u != null) return u;
        synchronized (FakeUserAccount.class) {
            if (cached != null) return cached;
            try {
                Class<?> userClass = Class.forName(USER_ACCOUNT_CLASS);
                Constructor<?> ctor = userClass.getDeclaredConstructor(
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
                DebugTrace.write("FakeUserAccount: built synthetic " + USER_ACCOUNT_CLASS + " a=" + FAKE_USER_ID);
                return cached;
            } catch (Throwable e) {
                DebugTrace.write("FakeUserAccount: construction failed", e);
                return null;
            }
        }
    }
}
