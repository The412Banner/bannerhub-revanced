package com.xj.winemu.steamchat;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static volatile String sLastError = "";

    private static Object sClient;          // SteamBridgeClient instance
    private static Method sExecuteRaw;      // executeRaw-<mangled>(String,String,kri,r65,Continuation)
    private static Object sKriDefault;      // kri.values()[0]
    private static Object sEmptyContext;    // Proxy CoroutineContext (empty)
    private static Class<?> sContinuationClass;
    private static ClassLoader sLoader;
    private static Object sUnit;             // kotlin.Unit.INSTANCE (emit() return), or null

    /** Callback for {@link #listen}: receives each event's payload JSON for the topic. */
    public interface EventListener { void onEvent(String payloadJson); }

    private BhSteamBridge() {}

    /** True once the Koin SteamBridgeClient singleton + reflection handles resolve. */
    public static synchronized boolean isAvailable() {
        if (!sResolved) resolve();
        return sUsable;
    }

    /** Human-readable resolve outcome (shown on the overlay; logcat scrolls out
     *  fast under Wine's log volume). */
    public static String getStatus() { return sStatus; }
    /** Reason the most recent request() returned null (timeout / native failure / exception). */
    public static String getLastError() { return sLastError; }

    private static ClassLoader hostLoader() {
        ClassLoader cl = BhSteamBridge.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    /** A SingleInstanceFactory caches its created singleton in an instance field;
     *  return that field's value if it's an instance of {@code want} (the cached
     *  SteamBridgeClient). Scans the factory class + superclasses; tolerant of
     *  obfuscated field names. */
    private static Object instanceHeldBy(Object factory, Class<?> want) {
        if (factory == null) return null;
        for (Class<?> c = factory.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(factory);
                    if (want.isInstance(v)) return v;
                } catch (Throwable ignored) {}
            }
        }
        return null;
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

            // Koin.get(KClass,..) is unusable here: the app shades kotlin so its
            // KClass type ("doa") differs from the kept kotlin.jvm.internal
            // .ClassReference that getKotlinClass yields (device-confirmed
            // "argument 1 has type doa, got ClassReference"). Instead, pull the
            // singleton straight out of Koin's instance registry by Java type —
            // org.koin.core.* names + getInstanceRegistry()/getInstances() are kept.
            step = "getInstanceRegistry";
            Object instanceRegistry = koin.getClass().getMethod("getInstanceRegistry").invoke(koin);
            step = "getInstances";
            Object instancesObj = instanceRegistry.getClass().getMethod("getInstances").invoke(instanceRegistry);
            if (!(instancesObj instanceof java.util.Map))
                throw new IllegalStateException("getInstances() did not return a Map");
            java.util.Map<?, ?> instances = (java.util.Map<?, ?>) instancesObj;

            step = "scan registry for SteamBridgeClient";
            for (Object factory : instances.values()) {
                Object inst = instanceHeldBy(factory, sbcClass);
                if (inst != null) { sClient = inst; break; }
            }
            if (sClient == null) throw new IllegalStateException(
                    "SteamBridgeClient not created among " + instances.size()
                    + " Koin singletons — sign into Steam in GameHub first");

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
        sLastError = "";
        if (!isAvailable()) { sLastError = "bridge unavailable"; return null; }
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
                sLastError = "timeout after " + timeoutMs + "ms";
                Log.w(TAG, "request " + topic + " " + sLastError);
                return null;
            }
            if (fail.get() != null) {
                sLastError = "native: " + fail.get();
                Log.w(TAG, "request " + topic + " failed: " + fail.get());
                return null;
            }
            Object v = out.get();
            if (v instanceof String) return (String) v;
            sLastError = "non-string result: " + v;
            return null;
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            sLastError = c.getClass().getSimpleName() + ": " + c.getMessage();
            Log.w(TAG, "request " + topic + " threw: " + c);
            return null;
        }
    }

    /**
     * Live event subscription. {@code listenJson(topic)} returns a kotlin Flow
     * ({@code vg6}) of {@code SteamBridgeEvent} filtered to the topic; we collect
     * it by driving {@code Flow.collect(FlowCollector, Continuation)} reflectively
     * (both interfaces R8-renamed → found structurally). The collector Proxy
     * extracts {@code payloadJson} and hands it to {@code listener} on the flow's
     * emitter thread. Because our Continuation reports an EMPTY CoroutineContext
     * (no dispatcher), resumptions run Unconfined-style on the emitter thread, so
     * events keep arriving without a coroutine runtime of our own.
     *
     * @return an opaque handle for {@link #unlisten}, or null if it couldn't start.
     */
    public static Object listen(final String topic, final EventListener listener) {
        if (!isAvailable() || listener == null) { sLastError = "listen: bridge unavailable"; return null; }
        try {
            Method listenJson = sClient.getClass().getMethod("listenJson", String.class);
            final Object flow = listenJson.invoke(sClient, topic);
            if (flow == null) { sLastError = "listen: listenJson null"; return null; }

            // Flow.collect(collector, continuation): the one 2-arg method whose
            // 2nd param is the Continuation type and 1st is an interface.
            Method collect = null;
            for (Method m : flow.getClass().getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[1] == sContinuationClass && p[0].isInterface()) { collect = m; break; }
            }
            if (collect == null) { sLastError = "listen: no collect()"; return null; }
            final Class<?> collectorItf = collect.getParameterTypes()[0];
            if (sUnit == null) sUnit = resolveUnit();

            final AtomicBoolean cancelled = new AtomicBoolean(false);

            final Object collector = Proxy.newProxyInstance(sLoader, new Class<?>[]{collectorItf},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            // emit(value, Continuation): the 2-arg method.
                            if (method.getParameterTypes().length == 2) {
                                if (cancelled.get()) throw new RuntimeException("bh-unsubscribe");
                                Object ev = (args != null && args.length > 0) ? args[0] : null;
                                String json = eventToJson(ev);
                                if (json != null) { try { listener.onEvent(json); } catch (Throwable ignored) {} }
                                return sUnit; // != COROUTINE_SUSPENDED → upstream continues
                            }
                            switch (method.getName()) {
                                case "toString": return "BhFlowCollector(" + topic + ")";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals":   return proxy == (args != null ? args[0] : null);
                                default:          return null;
                            }
                        }
                    });

            final Object completion = Proxy.newProxyInstance(sLoader, new Class<?>[]{sContinuationClass},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            switch (method.getName()) {
                                case "getContext": return sEmptyContext;
                                case "resumeWith": return null;   // flow ended/cancelled — nothing to do
                                case "toString":   return "BhFlowCompletion(" + topic + ")";
                                case "hashCode":   return System.identityHashCode(proxy);
                                case "equals":     return proxy == (args != null ? args[0] : null);
                                default:            return null;
                            }
                        }
                    });

            final Method collectF = collect;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try { collectF.invoke(flow, collector, completion); }
                    catch (Throwable err) { Log.i(TAG, "listen " + topic + " ended: " + err); }
                }
            }, "bh-steam-listen");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "listening on " + topic);
            return cancelled;
        } catch (Throwable t) {
            Throwable c = (t.getCause() != null) ? t.getCause() : t;
            sLastError = "listen: " + c.getClass().getSimpleName() + ": " + c.getMessage();
            Log.w(TAG, "listen " + topic + " failed", t);
            return null;
        }
    }

    /** Stop a {@link #listen} subscription (effective on the next emitted event). */
    public static void unlisten(Object handle) {
        if (handle instanceof AtomicBoolean) ((AtomicBoolean) handle).set(true);
    }

    private static Object resolveUnit() {
        try {
            Class<?> u = Class.forName("kotlin.Unit", false, sLoader);
            Field inst = u.getField("INSTANCE");
            return inst.get(null);
        } catch (Throwable t) {
            return null; // returning null from emit() is also accepted by the flow machinery
        }
    }

    /** Pull the JSON payload out of a SteamBridgeEvent (or a bare JSON string). */
    private static String eventToJson(Object ev) {
        if (ev == null) return null;
        if (ev instanceof String) return (String) ev;
        for (String n : new String[]{"getPayloadJson", "component2", "getPayload"}) {
            try {
                Object v = ev.getClass().getMethod(n).invoke(ev);
                if (v instanceof String) return (String) v;
            } catch (Throwable ignored) {}
        }
        // R8 may have renamed the accessors — find a no-arg String getter that
        // returns something JSON-shaped.
        try {
            for (Method m : ev.getClass().getMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == String.class) {
                    Object v = m.invoke(ev);
                    if (v instanceof String) { String s = ((String) v).trim(); if (s.startsWith("{") || s.startsWith("[")) return s; }
                }
            }
            for (Field f : ev.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    Object v = f.get(ev);
                    if (v instanceof String) { String s = ((String) v).trim(); if (s.startsWith("{") || s.startsWith("[")) return s; }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
