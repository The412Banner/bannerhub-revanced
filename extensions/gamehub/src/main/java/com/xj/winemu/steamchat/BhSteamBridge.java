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
 * which is registered as a <b>Koin singleton</b>. Every operation is a string
 * JSON-RPC: {@code executeRaw(topic, payloadJson) : String}. We grab the
 * already-authenticated singleton from Koin's global context and call
 * {@code executeRaw} — a Kotlin suspend fun — via a hand-rolled
 * {@link java.lang.reflect.Proxy} Continuation plus a {@link CountDownLatch}.
 *
 * Stable seams (low per-version churn — plain protocol strings shared with the
 * Rust core): topics {@code friends.list}, {@code friends.conversation_summaries},
 * {@code friends.message_history}; package {@code ...steam_sdk.bridge}.
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
    private static Object sKriDefault;      // kri.values()[0]  (the "Message" default)
    private static Object sSuspended;       // COROUTINE_SUSPENDED sentinel
    private static Object sEmptyContext;    // EmptyCoroutineContext.INSTANCE
    private static Class<?> sContinuationClass;
    private static ClassLoader sLoader;

    private BhSteamBridge() {}

    /** True once the Koin SteamBridgeClient singleton + reflection handles resolve. */
    public static synchronized boolean isAvailable() {
        if (!sResolved) resolve();
        return sUsable;
    }

    /** Human-readable resolve outcome (shown on the overlay so we don't depend
     *  on logcat, which scrolls out fast under Wine's log volume). */
    public static String getStatus() { return sStatus; }

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
            Method koinGetByClass = null;
            for (Method m : koin.getClass().getMethods()) {
                if (!m.getName().equals("get")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 1 && p[0].getName().equals("kotlin.reflect.KClass")) { koinGetByClass = m; break; }
            }
            if (koinGetByClass == null) throw new NoSuchMethodException("Koin.get(KClass,...) on " + koin.getClass().getName());

            step = "Koin.get(SteamBridgeClient)";
            Object[] getArgs = new Object[koinGetByClass.getParameterTypes().length];
            getArgs[0] = kClass; // remaining (qualifier, parameters) left null
            sClient = koinGetByClass.invoke(koin, getArgs);
            if (sClient == null) throw new IllegalStateException("Koin returned null SteamBridgeClient");

            step = "find executeRaw";
            sContinuationClass = Class.forName("kotlin.coroutines.Continuation", false, sLoader);
            for (Method m : sbcClass.getDeclaredMethods()) {
                if (!m.getName().startsWith("executeRaw")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 5 && p[0] == String.class && p[1] == String.class && p[4] == sContinuationClass) {
                    m.setAccessible(true);
                    sExecuteRaw = m;
                    Object[] consts = p[2].getEnumConstants();   // kri enum default
                    sKriDefault = (consts != null && consts.length > 0) ? consts[0] : null;
                    break;
                }
            }
            if (sExecuteRaw == null) throw new NoSuchMethodException("SteamBridgeClient.executeRaw(String,String,?,?,Continuation)");

            step = "coroutine intrinsics";
            sEmptyContext = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, sLoader)
                    .getField("INSTANCE").get(null);
            sSuspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", false, sLoader)
                    .getMethod("getCOROUTINE_SUSPENDED").invoke(null);

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

    private static ClassLoader hostLoader() {
        ClassLoader cl = BhSteamBridge.class.getClassLoader();
        return cl != null ? cl : ClassLoader.getSystemClassLoader();
    }

    /**
     * Fire a Steam JSON-RPC command and return the raw response JSON, or null on
     * any failure/timeout. Blocking — call only from a worker thread.
     */
    public static String request(String topic, String payloadJson, long timeoutMs) {
        if (!isAvailable()) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Object> out = new AtomicReference<>(null);
        final AtomicReference<Throwable> err = new AtomicReference<>(null);
        try {
            Object continuation = Proxy.newProxyInstance(
                    sLoader,
                    new Class<?>[]{sContinuationClass},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            String n = method.getName();
                            if (n.equals("getContext")) return sEmptyContext;
                            if (n.equals("resumeWith")) {
                                Object r = (args != null && args.length > 0) ? args[0] : null;
                                if (isResultFailure(r)) err.set(extractFailure(r));
                                else out.set(r);
                                latch.countDown();
                                return null;
                            }
                            if (n.equals("toString")) return "BhSteamBridge$Continuation";
                            if (n.equals("hashCode")) return System.identityHashCode(proxy);
                            if (n.equals("equals")) return proxy == (args != null ? args[0] : null);
                            return null;
                        }
                    });

            Object ret = sExecuteRaw.invoke(sClient, topic, payloadJson, sKriDefault, null, continuation);
            if (ret != sSuspended) {
                // completed synchronously — ret is the response (or a Result.Failure)
                if (isResultFailure(ret)) { Log.w(TAG, "request " + topic + " failed (sync)", extractFailure(ret)); return null; }
                return (ret instanceof String) ? (String) ret : null;
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "request " + topic + " timed out after " + timeoutMs + "ms");
                return null;
            }
            if (err.get() != null) { Log.w(TAG, "request " + topic + " failed", err.get()); return null; }
            Object v = out.get();
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            Log.w(TAG, "request " + topic + " threw", t);
            return null;
        }
    }

    // kotlin.Result failure marker is an instance of kotlin.Result$Failure
    private static boolean isResultFailure(Object o) {
        return o != null && o.getClass().getName().equals("kotlin.Result$Failure");
    }

    private static Throwable extractFailure(Object failure) {
        try {
            java.lang.reflect.Field f = failure.getClass().getField("exception");
            Object e = f.get(failure);
            return (e instanceof Throwable) ? (Throwable) e : new RuntimeException(String.valueOf(e));
        } catch (Throwable t) {
            return new RuntimeException("steam request failed");
        }
    }
}
