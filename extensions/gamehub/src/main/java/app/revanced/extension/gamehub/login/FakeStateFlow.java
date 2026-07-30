package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Method;

/**
 * Builds a host-compatible StateFlow wrapping a constant value.
 *
 * Why this exists: the auth-session getters we patch return a StateFlow, and the
 * smali edit that replaces them must produce one without growing the patched
 * method's `.locals` from 0. A Java helper keeps the edit at a one-line
 * invoke-static.
 *
 * ─── 6.1.0: NO MORE R8 LETTERS. ────────────────────────────────────────────
 * On 6.0.0–6.0.9 the auth interface declared an OBFUSCATED StateFlow interface
 * as its return type, and no un-obfuscated factory produced something
 * assignable to it. So this class had to reflectively construct two mangled
 * classes (a state holder + a wrapper implementing the mangled interface) and
 * carried THREE letter constants that had to be re-derived on every base bump:
 *
 *   6.0.2: tjk / hzh / vfe        6.0.7: qdi / o4g / p3d
 *   6.0.4: akk / ozh / dge        6.0.8: udi / q4g / s3d
 *                                 6.0.9: a5j / crg / smd
 *
 * 6.1.0 restructured the auth layer to use the REAL kotlinx types: the
 * interface (`Lrf1;`) declares `kotlinx.coroutines.flow.StateFlow` directly,
 * and the impl (`Lyf1;`) holds real `StateFlow`/`MutableStateFlow` fields.
 * `kotlinx.coroutines.flow.StateFlowKt.MutableStateFlow(Object)` survives R8
 * UN-OBFUSCATED (verified in 6.1.0: smali_classes4/kotlinx/coroutines/flow/
 * StateFlowKt.smali:80), and a MutableStateFlow IS a StateFlow — so one call to
 * a permanently-stable name replaces the whole two-stage mangled dance.
 *
 * ⇒ There is nothing version-specific left in this file. Do not reintroduce
 * letter constants here unless upstream goes back to an obfuscated flow type.
 *
 * Reflection is still used (rather than a direct call) only to avoid adding a
 * kotlinx-coroutines compile dependency to the extension module. The target
 * name is stable, so this is not a maintenance burden.
 */
public final class FakeStateFlow {

    private static final String STATE_FLOW_KT = "kotlinx.coroutines.flow.StateFlowKt";
    private static final String FACTORY_METHOD = "MutableStateFlow";

    private static volatile Method factory;

    private static volatile Object cachedTrueFlow;
    private static volatile Object cachedFalseFlow;
    private static volatile Object cachedUserFlow;

    private FakeStateFlow() {}

    /** Returns a StateFlow whose constant value is Boolean.TRUE. Cached. */
    public static Object boolTrue() {
        Object cached = cachedTrueFlow;
        if (cached != null) return cached;
        synchronized (FakeStateFlow.class) {
            if (cachedTrueFlow != null) return cachedTrueFlow;
            cachedTrueFlow = wrap(Boolean.TRUE);
            DebugTrace.write("FakeStateFlow.boolTrue() built");
            return cachedTrueFlow;
        }
    }

    /**
     * Returns a StateFlow whose constant value is Boolean.FALSE. Cached.
     *
     * Added for 6.1.0 (pre20). The auth interface exposes a GUEST StateFlow
     * (rf1.l(), backing the k()Z default) — NOT an "isLoggedIn" flow as earlier
     * derivations assumed. Presenting a full (non-guest) account therefore
     * requires this flow to read FALSE, so the navigation-layer full-account gate
     * (fch.j(): "Navigate intercepted guest for full-account") does not fire.
     */
    public static Object boolFalse() {
        Object cached = cachedFalseFlow;
        if (cached != null) return cached;
        synchronized (FakeStateFlow.class) {
            if (cachedFalseFlow != null) return cachedFalseFlow;
            cachedFalseFlow = wrap(Boolean.FALSE);
            DebugTrace.write("FakeStateFlow.boolFalse() built");
            return cachedFalseFlow;
        }
    }

    /** Returns a StateFlow whose constant value is the synthetic FakeUserAccount. Cached. */
    public static Object userFlow() {
        Object cached = cachedUserFlow;
        if (cached != null) return cached;
        synchronized (FakeStateFlow.class) {
            if (cachedUserFlow != null) return cachedUserFlow;
            cachedUserFlow = wrap(FakeUserAccount.get());
            DebugTrace.write("FakeStateFlow.userFlow() built");
            return cachedUserFlow;
        }
    }

    /**
     * Returns a StateFlow whose constant value is the synthetic FakeAuthToken.
     * Added for 6.1.0, where the token is exposed as its own StateFlow on the
     * auth impl rather than only via a default accessor.
     */
    public static Object tokenFlow() {
        return wrap(FakeAuthToken.get());
    }

    private static Object wrap(Object value) {
        try {
            Method f = factory;
            if (f == null) {
                synchronized (FakeStateFlow.class) {
                    f = factory;
                    if (f == null) {
                        f = Class.forName(STATE_FLOW_KT)
                                .getDeclaredMethod(FACTORY_METHOD, Object.class);
                        f.setAccessible(true);
                        factory = f;
                    }
                }
            }
            // MutableStateFlow(value) — a MutableStateFlow IS a StateFlow, so the
            // result satisfies every getter we patch.
            return f.invoke(null, value);
        } catch (Throwable e) {
            DebugTrace.write("FakeStateFlow.wrap failed", e);
            return null;
        }
    }
}
