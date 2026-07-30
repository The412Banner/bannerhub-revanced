package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;


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
 * NOTE: the constructor signature is NOT stable across versions — 6.1.0
 * changed it, which is why construction is now shape-based via SyntheticModel
 * rather than pinned to an exact parameter list.
 *   (String,String,String,String,String,String,I,I,Z,String,I,I,I,I,I,J,
 *    String,String,I,I,String,J,I,String,String,J,J)V
 * Smali ctor body asserts non-null on p1 (a) and p18 (q, the 17th Java arg).
 * Empty strings for every String field are safe.
 */
public final class FakeUserAccount {
    private static final String FAKE_USER_ID = "99999";

    /** R8-mangled class name of the user-account data class. Update on base APK bump. */
    // 6.1.0: Lkbm; -> Lhfr; (= "UserProfile", .a = userId). Its ctor GAINED a param
    //   on 6.1.0 (27 -> 28: a boolean before the trailing two longs), which is what
    //   broke the old exact-signature lookup and silently emptied the Library.
    //   History: 6.0.9 kbm, 6.0.8 n2l, 6.0.7 h2l, 6.0.4 rpm.
    private static final String USER_ACCOUNT_CLASS = "hfr";

    private static volatile Object cached;

    public static Object get() {
        DebugTrace.write("FakeUserAccount.get() called");
        Object u = cached;
        if (u != null) return u;
        synchronized (FakeUserAccount.class) {
            if (cached != null) return cached;
            cached = SyntheticModel.build(USER_ACCOUNT_CLASS, FAKE_USER_ID);
            return cached;
        }
    }
}
