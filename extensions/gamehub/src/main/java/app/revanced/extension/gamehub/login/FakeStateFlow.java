package app.revanced.extension.gamehub.login;

import app.revanced.extension.gamehub.debug.DebugTrace;

import java.lang.reflect.Constructor;

/**
 * Builds a host-compatible StateFlow wrapping a constant value.
 *
 * Why this exists: in 6.0.0 / 6.0.1 the patch could call a single static factory
 * (`Lumn;->h(Object)Lt8k;`) that returned a class directly assignable to the
 * AUTH_INTERFACE.h() / .e() return type. In 6.0.2 the only one-arg StateFlow
 * factory is `Ltwo;->l(Object)Ltjk;`, and `Ltjk;` does NOT implement the
 * abstract StateFlow interface that h()/e() declare in their return signature.
 * The host wraps it in `Lhzh;` (which DOES implement that interface) before
 * exposing it. Doing the same wrap from smali would require growing the
 * patched method's `.locals` from 0 to 2; doing it from a Java helper keeps
 * the smali edit at a one-line invoke-static.
 *
 * R8-mangled letter map (update on each base APK bump):
 *   6.0.2: tjk / hzh / vfe (Lrjk; was the abstract StateFlow iface)
 *   6.0.4: akk / ozh / dge (Lyjk; is the abstract StateFlow iface)
 *   STATE_FLOW_IMPL_CLASS = "akk"   (the (Object) → Ldge; constructible state holder)
 *   STATE_FLOW_WRAPPER_CLASS = "ozh"   (the Ldge; → Lyjk;-implementing wrapper)
 *   STATE_FLOW_HOLDER_INTERFACE = "dge" (the interface Lozh ctor accepts)
 *
 * Structural anchors:
 *   STATE_FLOW_IMPL_CLASS       — final class with `<init>(Ljava/lang/Object;)V`
 *                                 that implements the same interface as
 *                                 STATE_FLOW_HOLDER_INTERFACE; constructed by
 *                                 the unique static (Object) → ? factory found
 *                                 via `grep -r 'public static.*\\(Ljava/lang/Object;\\)L'`
 *                                 on the smali tree.
 *   STATE_FLOW_WRAPPER_CLASS    — final class implementing the same interface
 *                                 as AUTH_INTERFACE.h()'s return type, with
 *                                 `<init>(STATE_FLOW_HOLDER_INTERFACE)V` ctor.
 *                                 Found by inspecting the it0 ctor's call
 *                                 sequence — it builds the StateFlow fields
 *                                 via `Leuo;->e0(...)Lhzh;` (stateIn) — so
 *                                 the resulting field type IS the wrapper.
 *   STATE_FLOW_HOLDER_INTERFACE — the interface STATE_FLOW_WRAPPER_CLASS's
 *                                 ctor accepts; STATE_FLOW_IMPL_CLASS
 *                                 implements it.
 */
public final class FakeStateFlow {

    // 6.0.7: wrapper o4g (implements Lodi; = StateFlow iface h()/e() return, ctor (Lp3d;)V);
    // holder interface p3d; impl qdi (implements Lp3d;, ctor (Object)V).
    // 6.0.8: StateFlow iface is now Lsdi; (fw0.h()/e()/d() return it). wrapper q4g
    // (implements Lsdi;, ctor (Ls3d;)V — q4g is also the type of fw0's 3 StateFlow
    // fields); holder interface s3d; impl udi (implements Ls3d;, ctor (Object)V).
    // 6.0.9: StateFlow iface is now Ly4j; (ux0.h()/e()/d() return it). wrapper crg
    // (implements Ly4j;, ctor (Lsmd;)V — crg is also the type of ux0's 3 StateFlow
    // fields a/b/c); holder interface smd; impl a5j (sole impl of Lsmd; w/ ctor (Object)V).
    private static final String STATE_FLOW_IMPL_CLASS       = "a5j";
    private static final String STATE_FLOW_WRAPPER_CLASS    = "crg";
    private static final String STATE_FLOW_HOLDER_INTERFACE = "smd";

    private static volatile Constructor<?> implCtor;
    private static volatile Constructor<?> wrapperCtor;

    private static volatile Object cachedTrueFlow;
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

    private static Object wrap(Object value) {
        try {
            Constructor<?> impl = implCtor;
            if (impl == null) {
                synchronized (FakeStateFlow.class) {
                    impl = implCtor;
                    if (impl == null) {
                        Class<?> implClass = Class.forName(STATE_FLOW_IMPL_CLASS);
                        impl = implClass.getDeclaredConstructor(Object.class);
                        impl.setAccessible(true);
                        implCtor = impl;
                    }
                }
            }
            Object inner = impl.newInstance(value);

            Constructor<?> wrapper = wrapperCtor;
            if (wrapper == null) {
                synchronized (FakeStateFlow.class) {
                    wrapper = wrapperCtor;
                    if (wrapper == null) {
                        Class<?> wrapperClass = Class.forName(STATE_FLOW_WRAPPER_CLASS);
                        Class<?> holderIface  = Class.forName(STATE_FLOW_HOLDER_INTERFACE);
                        wrapper = wrapperClass.getDeclaredConstructor(holderIface);
                        wrapper.setAccessible(true);
                        wrapperCtor = wrapper;
                    }
                }
            }
            return wrapper.newInstance(inner);
        } catch (Throwable e) {
            DebugTrace.write("FakeStateFlow.wrap failed", e);
            return null;
        }
    }
}
