package com.xj.winemu.steamchat;

import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Read-only bridge into GameHub's in-process Steam client, by reflection only
 * (the host classes are not on our compile classpath).
 *
 * GameHub 6.0.7+/6.0.8 talks to a native Rust SteamKit core
 * ({@code libsteamkit_core.so}) over JNA through a thin, NON-obfuscated Kotlin
 * facade {@code com.xiaoji.egggame.common.steam_sdk.bridge.SteamBridgeClient},
 * registered as a <b>Koin singleton</b>. Every op is a string JSON-RPC:
 * {@code executeRaw(topic, payloadJson) : String}. We grab the already-
 * authenticated singleton from Koin and drive the suspend {@code executeRaw}
 * via a hand-rolled {@link java.lang.reflect.Proxy} Continuation + latch.
 *
 * Obfuscation-proofing: R8 renames the whole kotlin coroutines ABI
 * (Continuation / CoroutineContext / intrinsics) AND kotlin.reflect.KClass.
 * So we never reference those by name — we derive every type STRUCTURALLY:
 *   - Koin.get(KClass,..) is matched by paramType.isInstance(ourKClass)
 *   - Continuation type = executeRaw's last parameter
 *   - CoroutineContext type = Continuation.getContext()'s return type
 *   - an "empty" CoroutineContext is itself a Proxy
 * Only the protocol strings + steam_sdk.bridge / org.koin / JvmClassMappingKt
 * names (all kept) are used literally.
 *
 * NEVER call {@link #request} on the UI thread — it blocks on the network.
 */
public final class BhSteamBridge {

    private static final String TAG = "BhSteamChat";

    private static final String SBC_CLASS =
            "com.xiaoji.egggame.common.steam_sdk.bridge.SteamBridgeClient";

    private static volatile boolean sResolved = false;
    private static volatile boolean sUsable = false;
    private static volatile String sStatus = "not resolved";

    private static Object sClient;          // SteamBridgeClient instance
    private static Method sExecuteRaw;      // executeRaw-<mangled>(String,String,kri,r65,Continuation)
    private static Object sKriDefault;      // kri.values()[0]
    private static Object sEmptyContext;    // Proxy CoroutineContext (empty)
    private static Class<?> sContinuationClass;
    private static ClassLoader sLoader;

    private BhSteamBridge() {}

    /** True once the Koin SteamBridgeClient singleton + reflection handles resolve. */
    public static synchronized boolean isAvailable() {
        if (!sResolved) resolve();
        return sUsable;
    }

    /** Human-readable resolve outcome (shown on the overlay; logcat scrolls out
     *  fast under Wine's log volume). */
    public static String getStatus() { return sStatus; }

    private static ClassLoader hostLoader() {
        ClassLoader cl = BhSteamBridge.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    private static synchronized void resolve() {
        sResolved = true;
        String step = "init";
        try {
            sLoader = hostLoader();

            step = "Class.forName SteamBridgeClient";
            Class<?> sbcClass = Class.forName(SBC_CLASS, false, sLoader);
            sLoader = sbcClass.getClassLoader();

            step = "Class.forName GlobalContext";
            Class<?> globalCtx = Class.forName("org.koin.core.context.GlobalContext", false, sLoader);
            step = "GlobalContext.INSTANCE";
            Object globalInstance = globalCtx.getField("INSTANCE").get(null);
            step = "GlobalContext.get()";
            Object koin = globalCtx.getMethod("get").invoke(globalInstance);
            if (koin == null) throw new IllegalStateException("GlobalContext.get() returned null");

            step = "getKotlinClass";
            Class<?> jvmMap = Class.forName("kotlin.jvm.JvmClassMappingKt", false, sLoader);
            Object kClass = jvmMap.getMethod("getKotlinClass", Class.class).invoke(null, sbcClass);

            step = "find Koin.get(KClass)";
            // R8 renames kotlin.reflect.KClass — match the resolver by whether our
            // KClass object is an INSTANCE of the first param type, not by name.
            Method koinGet = null;
            for (Method m : koin.getClass().getMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[0].isInstance(kClass)) { koinGet = m; break; }
            }
            if (koinGet == null) for (Method m : koin.getClass().getMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 1 && p[0].isInstance(kClass)) { koinGet = m; break; }
            }
            if (koinGet == null) throw new NoSuchMethodException(
                    "Koin.get(KClass,..) on " + koin.getClass().getName());

            step = "Koin.get(SteamBridgeClient)";
            Object[] getArgs = new Object[koinGet.getParameterTypes().length];
            getArgs[0] = kClass; // remaining (qualifier, parameters) null
            sClient = koinGet.invoke(koin, getArgs);
            if (sClient == null) throw new IllegalStateException("Koin returned null SteamBridgeClient");

            step = "find executeRaw";
            // executeRaw is the only (String,String,…,Continuation) method.
            // kotlin.coroutines.Continuation is renamed → identify structurally
            // and read the Continuation type off the last param.
            for (Method m : sbcClass.getDeclaredMethods()) {
                if (!m.getName().startsWith("executeRaw")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[0] == String.class && p[1] == String.class) {
                    m.setAccessible(true);
                    sExecuteRaw = m;
                    sContinuationClass = p[4];                  // obfuscated Continuation
                    Object[] consts = p[2].getEnumConstants();  // kri enum default
                    sKriDefault = (consts != null && consts.length > 0) ? consts[0] : null;
                    break;
                }
            }
            if (sExecuteRaw == null)
                throw new NoSuchMethodException("SteamBridgeClient.executeRaw(String,String,?,?,Continuation)");

            step = "build empty CoroutineContext";
            // Continuation.getContext() : CoroutineContext — derive that interface
            // from the return type and proxy an empty context.
            final Class<?> ctxClass = sContinuationClass.getMethod("getContext").getReturnType();
            sEmptyContext = Proxy.newProxyInstance(sLoader, new Class<?>[]{ctxClass},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            switch (method.getName()) {
                                case "get":      return null;                 // no interceptor → undispatched resume
                                case "fold":     return args != null ? args[0] : null; // empty.fold(init,op)=init
                                case "plus":     return args != null ? args[0] : proxy; // empty+ctx=ctx
                                case "minusKey": return proxy;
                                case "toString": return "BhSteamBridge$EmptyCtx";
                                case "hashCode": return 0;
                                case "equals":   return proxy == (args != null ? args[0] : null);
                                default:          return null;
                            }
                        }
                    });

            sUsable = true;
            sStatus = "ok (" + sExecuteRaw.getName() + ")";
            Log.i(TAG, "bridge resolved: " + sStatus);
        } catch (Throwable t) {
            sUsable = false;
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            sStatus = "FAILED @ " + step + ": " + c.getClass().getSimpleName()
                    + (c.getMessage() != null ? " " + c.getMessage() : "");
            Log.w(TAG, "bridge resolve " + sStatus, t);
        }
    }

    /**
     * Fire a Steam JSON-RPC command and return the raw response JSON, or null on
     * any failure/timeout. Blocking — call only from a worker thread.
     */
    public static String request(String topic, String payloadJson, long timeoutMs) {
        if (!isAvailable()) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Object> out = new AtomicReference<>(null);
        final AtomicReference<Object> fail = new AtomicReference<>(null);
        try {
            Object continuation = Proxy.newProxyInstance(
                    sLoader,
                    new Class<?>[]{sContinuationClass},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            switch (method.getName()) {
                                case "getContext": return sEmptyContext;
                                case "resumeWith":
                                    Object r = (args != null && args.length > 0) ? args[0] : null;
                                    if (r instanceof String) out.set(r);
                                    else fail.set(r);            // Result.Failure (renamed) or unexpected
                                    latch.countDown();
                                    return null;
                                case "toString": return "BhSteamBridge$Continuation";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals":   return proxy == (args != null ? args[0] : null);
                                default:          return null;
                            }
                        }
                    });

            Object ret = sExecuteRaw.invoke(sClient, topic, payloadJson, sKriDefault, null, continuation);
            // Completed synchronously → ret is the String response. Otherwise it
            // suspended (ret is the unnameable, post-R8 COROUTINE_SUSPENDED
            // sentinel) and the result arrives via resumeWith → wait on the latch.
            if (ret instanceof String) return (String) ret;
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "request " + topic + " timed out after " + timeoutMs + "ms");
                return null;
            }
            if (fail.get() != null) { Log.w(TAG, "request " + topic + " failed: " + fail.get()); return null; }
            Object v = out.get();
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            Log.w(TAG, "request " + topic + " threw: " + c);
            return null;
        }
    }
}
