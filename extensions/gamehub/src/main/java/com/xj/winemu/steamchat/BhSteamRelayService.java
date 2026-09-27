package com.xj.winemu.steamchat;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.jvm.internal.ContinuationImpl;
import kotlinx.coroutines.flow.FlowCollector;

/**
 * M2 — Banner's Steam relay: a MAIN-process Messenger service that drives
 * GameHub 6.3.1's in-process Steam bridge on behalf of the {@code :pcengine}
 * overlay ({@link BhSteamRelayClient} → {@link BhSteamBridge}).
 *
 * <h3>Why a service</h3>
 * The bridge (6.3.1: {@code Ltwv;}, ex-{@code SteamBridgeClient}) is a Koin
 * singleton whose factory throws outside the main process, and the overlay
 * lives in {@code :pcengine}. Registered by {@code SteamRelayServicePatch}
 * with no {@code android:process}, so it runs in the main process next to
 * the bridge.
 *
 * <h3>Attach walk (structural — no R8 letter is hardcoded)</h3>
 * <ol>
 *   <li>{@code org.koin.core.context.GlobalContext.INSTANCE.get()} → {@code Koin} (kept).</li>
 *   <li>Every non-static field of {@code Koin} whose declared type has a
 *       {@code (Koin)} constructor is a registry candidate (6.3.1: {@code Koin.d:Lm35;};
 *       {@code ScopeRegistry} also matches and is harmlessly scanned).</li>
 *   <li>In each candidate, every non-static field whose VALUE is a
 *       {@code ConcurrentHashMap} (declared type is {@code Object} after R8
 *       class merging) is walked; values that are
 *       {@code org.koin.core.instance.SingleInstanceFactory} (kept) are inspected.</li>
 *   <li>A factory's cached singleton = its {@code volatile} instance field
 *       (6.3.1: {@code b:Ljava/lang/Object;}); null = not created yet.</li>
 *   <li>The bridge is the instance whose class declares
 *       {@code (String, String, <enum>, kotlin.time.Duration, ContinuationImpl) : Object}
 *       (= {@code executeRaw}, 6.3.1 {@code twv.c}) AND
 *       {@code (String) : <class implementing kotlinx.coroutines.flow.Flow>}
 *       (= {@code listenJson}, 6.3.1 {@code twv.d}).</li>
 * </ol>
 *
 * <h3>Suspend ABI</h3>
 * kotlin/kotlinx keep their names on 6.3.x, so {@link SuspendCall} subclasses
 * {@code kotlin.coroutines.jvm.internal.ContinuationImpl} (ctor
 * {@code (Continuation, CoroutineContext)} with {@code EmptyCoroutineContext.INSTANCE})
 * and receives the resumption in {@code invokeSuspend(Object)} — the value is
 * {@code kotlin.Result}-encoded, so failures are unwrapped with
 * {@code Result.exceptionOrNull-impl}. A direct return that is not
 * {@code IntrinsicsKt.getCOROUTINE_SUSPENDED()} means the call completed
 * synchronously. Error kind enum = the parameter type's first constant
 * ("Message"); the timeout is {@code DurationKt.toDuration(ms, MILLISECONDS)}
 * boxed via {@code Duration.box-impl}. Bridge failures arrive as an
 * {@code IllegalStateException} subclass wrapping {@code {kind:<enum>, message}}
 * — the kind name ({@code SessionNotReady}, {@code NotConnected}, …) is
 * extracted structurally and mapped to a readable error string.
 *
 * <h3>Messenger contract</h3>
 * <pre>
 *  client → service (Message.replyTo = client Messenger; data.protocol_version = 1)
 *    what 1 STATUS       {request_id}
 *    what 2 EXEC         {request_id, cmd, json, timeout_ms}
 *    what 3 SUBSCRIBE    {request_id, topic}
 *    what 4 UNSUBSCRIBE  {topic}
 *  service → client
 *    what 100 REPLY      {request_id, ok, page_index, page_count,
 *                         result (page of the JSON string) | error, error_kind}
 *    what 101 EVENT      {topic, event_seq, page_index, page_count, json (page)}
 * </pre>
 * Every string payload is paged in {@link #PAGE_CHARS}-char slices so a
 * single Binder transaction stays far below the 1 MB limit.
 *
 * Tag {@code BH_STEAM}.
 */
public final class BhSteamRelayService extends Service {

    private static final String TAG = "BH_STEAM";

    public static final String SERVICE_CLASS = "com.xj.winemu.steamchat.BhSteamRelayService";
    public static final int PROTOCOL_VERSION = 1;

    public static final int MSG_STATUS = 1;
    public static final int MSG_EXEC = 2;
    public static final int MSG_SUBSCRIBE = 3;
    public static final int MSG_UNSUBSCRIBE = 4;
    public static final int MSG_REPLY = 100;
    public static final int MSG_EVENT = 101;

    /** Max chars per Bundle string page (~192 KB of UTF-16 on the wire). */
    public static final int PAGE_CHARS = 96 * 1024;

    private HandlerThread thread;
    private Messenger messenger;
    private final ExecutorService exec = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "bh-steam-relay-exec");
            t.setDaemon(true);
            return t;
        }
    });
    private final AtomicLong eventSeq = new AtomicLong(1);

    /** topic → subscribers (client Messengers). */
    private final Map<String, List<Messenger>> subscribers = new HashMap<>();
    /** topic → cancel flag of the running flow collector. */
    private final Map<String, AtomicBoolean> collectors = new HashMap<>();

    // ── lifecycle ──────────────────────────────────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("bh-steam-relay");
        thread.setDaemon(true);
        thread.start();
        messenger = new Messenger(new RelayHandler(thread));
        BhSteamLog.i("relay: service created in pid " + android.os.Process.myPid());
    }

    @Override public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override public void onDestroy() {
        synchronized (subscribers) {
            for (AtomicBoolean c : collectors.values()) c.set(true);
            collectors.clear();
            subscribers.clear();
        }
        exec.shutdownNow();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    // ── handler ────────────────────────────────────────────────────────────

    private final class RelayHandler extends Handler {
        RelayHandler(HandlerThread t) { super(t.getLooper()); }

        @Override public void handleMessage(Message msg) {
            final Bundle d = msg.getData();
            final Messenger reply = msg.replyTo;
            final long id = d != null ? d.getLong("request_id", 0) : 0;
            switch (msg.what) {
                case MSG_STATUS: {
                    final Bridge b = Bridge.get();
                    sendReply(reply, id, true, b.statusJson(), null, null);
                    break;
                }
                case MSG_EXEC: {
                    if (d == null) return;
                    final String cmd = d.getString("cmd", "");
                    final String json = d.getString("json", "{}");
                    final long timeout = d.getLong("timeout_ms", 8000);
                    exec.execute(new Runnable() {
                        public void run() {
                            Bridge b = Bridge.get();
                            if (!b.attached) { sendReply(reply, id, false, null, b.status, "NotAttached"); return; }
                            Outcome o = b.execute(cmd, json, timeout);
                            sendReply(reply, id, o.ok, o.value, o.error, o.kind);
                        }
                    });
                    break;
                }
                case MSG_SUBSCRIBE: {
                    if (d == null) return;
                    final String topic = d.getString("topic", "");
                    if (reply == null || topic.isEmpty()) return;
                    Bridge b = Bridge.get();
                    if (!b.attached) { sendReply(reply, id, false, null, b.status, "NotAttached"); return; }
                    String err = subscribe(topic, reply);
                    sendReply(reply, id, err == null, err == null ? "subscribed" : null, err, err == null ? null : "Listen");
                    break;
                }
                case MSG_UNSUBSCRIBE: {
                    if (d == null) return;
                    unsubscribe(d.getString("topic", ""), reply);
                    break;
                }
                default:
                    super.handleMessage(msg);
            }
        }
    }

    private void sendReply(Messenger to, long id, boolean ok, String value, String error, String kind) {
        if (to == null) return;
        if (!ok) {
            Bundle b = base(id);
            b.putBoolean("ok", false);
            b.putString("error", error != null ? error : "unknown");
            if (kind != null) b.putString("error_kind", kind);
            b.putInt("page_index", 0);
            b.putInt("page_count", 1);
            send(to, MSG_REPLY, b);
            return;
        }
        String v = value != null ? value : "";
        int pages = Math.max(1, (v.length() + PAGE_CHARS - 1) / PAGE_CHARS);
        for (int i = 0; i < pages; i++) {
            Bundle b = base(id);
            b.putBoolean("ok", true);
            b.putInt("page_index", i);
            b.putInt("page_count", pages);
            int from = i * PAGE_CHARS;
            b.putString("result", v.substring(from, Math.min(v.length(), from + PAGE_CHARS)));
            if (!send(to, MSG_REPLY, b)) return;
        }
    }

    private static Bundle base(long id) {
        Bundle b = new Bundle();
        b.putInt("protocol_version", PROTOCOL_VERSION);
        b.putLong("request_id", id);
        return b;
    }

    private boolean send(Messenger to, int what, Bundle data) {
        Message m = Message.obtain(null, what);
        m.setData(data);
        try { to.send(m); return true; }
        catch (RemoteException e) {
            BhSteamLog.w("relay: client gone (" + e + ")");
            dropClient(to);
            return false;
        }
    }

    // ── subscriptions ──────────────────────────────────────────────────────

    private String subscribe(final String topic, Messenger client) {
        synchronized (subscribers) {
            List<Messenger> subs = subscribers.get(topic);
            if (subs == null) { subs = new ArrayList<>(); subscribers.put(topic, subs); }
            if (!subs.contains(client)) subs.add(client);
            AtomicBoolean cancel = collectors.get(topic);
            if (cancel != null && !cancel.get()) return null;   // collector already running
            cancel = new AtomicBoolean(false);
            String err = Bridge.get().startCollect(topic, cancel, new EventSink() {
                public void onEvent(String json) { fanOut(topic, json); }
            });
            if (err != null) { subs.remove(client); return err; }
            collectors.put(topic, cancel);
            return null;
        }
    }

    private void unsubscribe(String topic, Messenger client) {
        if (client == null) return;
        synchronized (subscribers) {
            List<Messenger> subs = subscribers.get(topic);
            if (subs == null) return;
            subs.remove(client);
            if (subs.isEmpty()) stopTopicLocked(topic);
        }
    }

    private void dropClient(Messenger client) {
        synchronized (subscribers) {
            Iterator<Map.Entry<String, List<Messenger>>> it = subscribers.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, List<Messenger>> e = it.next();
                e.getValue().remove(client);
                if (e.getValue().isEmpty()) { stopTopicLocked(e.getKey()); it.remove(); }
            }
        }
    }

    private void stopTopicLocked(String topic) {
        AtomicBoolean c = collectors.remove(topic);
        if (c != null) c.set(true);
        subscribers.remove(topic);
    }

    private void fanOut(String topic, String json) {
        List<Messenger> targets;
        synchronized (subscribers) {
            List<Messenger> subs = subscribers.get(topic);
            if (subs == null || subs.isEmpty()) return;
            targets = new ArrayList<>(subs);
        }
        long seq = eventSeq.getAndIncrement();
        String v = json != null ? json : "";
        int pages = Math.max(1, (v.length() + PAGE_CHARS - 1) / PAGE_CHARS);
        for (Messenger m : targets) {
            for (int i = 0; i < pages; i++) {
                Bundle b = new Bundle();
                b.putInt("protocol_version", PROTOCOL_VERSION);
                b.putString("topic", topic);
                b.putLong("event_seq", seq);
                b.putInt("page_index", i);
                b.putInt("page_count", pages);
                int from = i * PAGE_CHARS;
                b.putString("json", v.substring(from, Math.min(v.length(), from + PAGE_CHARS)));
                if (!send(m, MSG_EVENT, b)) break;
            }
        }
    }

    interface EventSink { void onEvent(String json); }

    static final class Outcome {
        boolean ok; String value; String error; String kind;
        static Outcome ok(String v) { Outcome o = new Outcome(); o.ok = true; o.value = v; return o; }
        static Outcome fail(String kind, String err) { Outcome o = new Outcome(); o.kind = kind; o.error = err; return o; }
    }

    // ── the bridge: attach + suspend/flow plumbing ─────────────────────────

    /**
     * Process-wide handle on the host's Steam bridge singleton. Re-attaches on
     * every call until it succeeds (the singleton is created lazily when
     * GameHub first touches Steam; a fresh main process may not have it yet).
     */
    static final class Bridge {
        private static Bridge sInstance;

        boolean attached;
        String status = "not resolved";

        Object client;             // twv
        Method executeRaw;         // twv.c
        Method listenJson;         // twv.d
        Class<?> kindEnum;         // x3w
        Object kindDefault;        // x3w.values()[0] = Message
        Method toDuration;         // DurationKt.toDuration(long, DurationUnit)
        Object msUnit;             // DurationUnit.MILLISECONDS
        Method boxDuration;        // Duration.box-impl(long)
        Object suspended;          // IntrinsicsKt.getCOROUTINE_SUSPENDED()
        Method exceptionOrNull;    // Result.exceptionOrNull-impl(Object)
        Object emptyContext;       // EmptyCoroutineContext.INSTANCE
        Object unit;               // Unit.INSTANCE
        Class<?> flowItf;          // kotlinx.coroutines.flow.Flow

        static synchronized Bridge get() {
            if (sInstance == null) sInstance = new Bridge();
            if (!sInstance.attached) sInstance.attach();
            return sInstance;
        }

        String statusJson() {
            return "{\"attached\":" + attached + ",\"status\":" + quote(status)
                    + ",\"pid\":" + android.os.Process.myPid() + "}";
        }

        private static String quote(String s) {
            if (s == null) return "null";
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') sb.append('\\').append(c);
                else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                else sb.append(c);
            }
            return sb.append('"').toString();
        }

        private static ClassLoader loader() {
            ClassLoader cl = BhSteamRelayService.class.getClassLoader();
            return cl != null ? cl : ClassLoader.getSystemClassLoader();
        }

        private synchronized void attach() {
            String step = "init";
            try {
                ClassLoader cl = loader();
                step = "kotlin ABI";
                flowItf = Class.forName("kotlinx.coroutines.flow.Flow", false, cl);
                Class<?> contImpl = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl", false, cl);
                Class<?> durationCls = Class.forName("kotlin.time.Duration", false, cl);
                Class<?> unitCls = Class.forName("kotlin.time.DurationUnit", false, cl);
                toDuration = Class.forName("kotlin.time.DurationKt", false, cl)
                        .getMethod("toDuration", long.class, unitCls);
                msUnit = unitCls.getField("MILLISECONDS").get(null);
                boxDuration = durationCls.getMethod("box-impl", long.class);
                emptyContext = Class.forName("kotlin.coroutines.EmptyCoroutineContext", false, cl).getField("INSTANCE").get(null);
                unit = Class.forName("kotlin.Unit", false, cl).getField("INSTANCE").get(null);
                exceptionOrNull = Class.forName("kotlin.Result", false, cl).getMethod("exceptionOrNull-impl", Object.class);
                try {
                    // Lives on the multifile facade parent (IntrinsicsKt__IntrinsicsKt);
                    // getMethod resolves inherited public statics.
                    suspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", false, cl)
                            .getMethod("getCOROUTINE_SUSPENDED").invoke(null);
                } catch (Throwable t) {
                    BhSteamLog.w("relay: COROUTINE_SUSPENDED not resolvable (" + t + ") — falling back to enum check");
                    suspended = null;
                }

                step = "GlobalContext.get()";
                Class<?> gc = Class.forName("org.koin.core.context.GlobalContext", false, cl);
                Object koin = gc.getMethod("get").invoke(gc.getField("INSTANCE").get(null));
                if (koin == null) throw new IllegalStateException("Koin not started");

                step = "registry walk";
                Class<?> sifCls = Class.forName("org.koin.core.instance.SingleInstanceFactory", false, cl);
                int factories = 0, created = 0;
                Object found = null;
                for (Object registry : registryCandidates(koin)) {
                    for (Object map : concurrentMapsOf(registry)) {
                        for (Object factory : ((ConcurrentHashMap<?, ?>) map).values()) {
                            if (!sifCls.isInstance(factory)) continue;
                            factories++;
                            Object inst = cachedInstance(factory, sifCls);
                            if (inst == null) continue;
                            created++;
                            if (looksLikeBridge(inst.getClass(), contImpl, durationCls)) { found = inst; break; }
                        }
                        if (found != null) break;
                    }
                    if (found != null) break;
                }
                if (found == null) throw new IllegalStateException(
                        "Steam bridge singleton not created yet (" + created + "/" + factories
                        + " Koin singletons live) — open Steam in GameHub first");
                client = found;

                step = "bind methods";
                for (Method m : client.getClass().getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (executeRaw == null && p.length == 5 && p[0] == String.class && p[1] == String.class
                            && p[2].isEnum() && p[3] == durationCls && p[4] == contImpl) {
                        m.setAccessible(true);
                        executeRaw = m;
                        kindEnum = p[2];
                        Object[] consts = p[2].getEnumConstants();
                        kindDefault = consts != null && consts.length > 0 ? consts[0] : null;
                    } else if (listenJson == null && p.length == 1 && p[0] == String.class
                            && flowItf.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        listenJson = m;
                    }
                }
                if (executeRaw == null || listenJson == null)
                    throw new NoSuchMethodException("executeRaw/listenJson not found on " + client.getClass().getName());

                attached = true;
                status = "ok (" + client.getClass().getName() + "." + executeRaw.getName() + "/"
                        + listenJson.getName() + ", kind=" + kindDefault + ")";
                BhSteamLog.i("relay: attached " + status);
            } catch (Throwable t) {
                attached = false;
                Throwable c = t.getCause() != null ? t.getCause() : t;
                status = "FAILED @ " + step + ": " + c.getClass().getSimpleName()
                        + (c.getMessage() != null ? " " + c.getMessage() : "");
                BhSteamLog.w("relay: attach " + status, t);
            }
        }

        /** Koin fields whose declared type has a (Koin) constructor, then every other object field. */
        private static List<Object> registryCandidates(Object koin) throws IllegalAccessException {
            List<Object> first = new ArrayList<>();
            List<Object> rest = new ArrayList<>();
            for (Field f : koin.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object v = f.get(koin);
                if (v == null) continue;
                boolean koinCtor = false;
                for (Constructor<?> c : v.getClass().getDeclaredConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0] == koin.getClass()) { koinCtor = true; break; }
                }
                (koinCtor ? first : rest).add(v);
            }
            first.addAll(rest);
            return first;
        }

        private static List<Object> concurrentMapsOf(Object o) throws IllegalAccessException {
            List<Object> out = new ArrayList<>();
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof ConcurrentHashMap) out.add(v);
                }
            }
            return out;
        }

        /** The volatile instance field declared on SingleInstanceFactory (fallback: any non-static Object field). */
        private static Object cachedInstance(Object factory, Class<?> sifCls) throws IllegalAccessException {
            Field fallback = null;
            for (Field f : sifCls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                if (Modifier.isVolatile(f.getModifiers())) return f.get(factory);
                if (fallback == null && f.getType() == Object.class) fallback = f;
            }
            return fallback != null ? fallback.get(factory) : null;
        }

        private static boolean looksLikeBridge(Class<?> cls, Class<?> contImpl, Class<?> durationCls) {
            boolean exec = false, listen = false;
            try {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 5 && p[0] == String.class && p[1] == String.class
                            && p[2].isEnum() && p[3] == durationCls && p[4] == contImpl) exec = true;
                    else if (p.length == 1 && p[0] == String.class
                            && kotlinxFlow(m.getReturnType())) listen = true;
                }
            } catch (Throwable ignored) {}
            return exec && listen;
        }

        private static boolean kotlinxFlow(Class<?> ret) {
            for (Class<?> c = ret; c != null; c = c.getSuperclass())
                for (Class<?> i : c.getInterfaces())
                    if ("kotlinx.coroutines.flow.Flow".equals(i.getName())) return true;
            return false;
        }

        // ── executeRaw over the suspend ABI ────────────────────────────────

        Outcome execute(String cmd, String json, long timeoutMs) {
            final SuspendCall call = new SuspendCall(this);
            try {
                Object duration = boxDuration.invoke(null, toDuration.invoke(null, timeoutMs, msUnit));
                Object ret = executeRaw.invoke(client, cmd, json, kindDefault, duration, call);
                if (!isSuspended(ret)) {
                    // completed synchronously — ret is the raw response
                    if (ret instanceof String) return Outcome.ok((String) ret);
                    return Outcome.fail("Unexpected", "non-string result: " + ret);
                }
                if (!call.done.await(timeoutMs + 1000, TimeUnit.MILLISECONDS))
                    return Outcome.fail("Timeout", "timeout after " + timeoutMs + "ms");
                Throwable err = call.failure.get();
                if (err != null) return describe(err);
                Object v = call.value.get();
                if (v instanceof String) return Outcome.ok((String) v);
                return Outcome.fail("Unexpected", "non-string result: " + v);
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                return describe(c);
            }
        }

        boolean isSuspended(Object ret) {
            if (suspended != null) return ret == suspended;
            return ret instanceof Enum;   // CoroutineSingletons.COROUTINE_SUSPENDED is an enum constant
        }

        /** Map a bridge failure to {kind, message}; kind names are the enum's own (SessionNotReady, NotConnected, …). */
        Outcome describe(Throwable t) {
            String kind = null, msg = null;
            try {
                for (Throwable cur = t; cur != null && kind == null; cur = cur.getCause() == cur ? null : cur.getCause()) {
                    for (Class<?> c = cur.getClass(); c != null && c != Throwable.class && kind == null; c = c.getSuperclass()) {
                        for (Field f : c.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object v = f.get(cur);
                            if (v == null) continue;
                            if (kindEnum != null && kindEnum.isInstance(v)) { kind = ((Enum<?>) v).name(); }
                            else if (kindEnum != null && v.getClass() != String.class) {
                                // one level of wrapping: {kind:<enum>, message:String}
                                for (Field g : v.getClass().getDeclaredFields()) {
                                    if (Modifier.isStatic(g.getModifiers())) continue;
                                    g.setAccessible(true);
                                    Object w = g.get(v);
                                    if (kindEnum.isInstance(w)) kind = ((Enum<?>) w).name();
                                    else if (w instanceof String && msg == null && !((String) w).isEmpty()) msg = (String) w;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (msg == null) msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            if (kind == null) {
                String n = t.getClass().getName();
                if (n.contains("Timeout")) kind = "Timeout";
                else if (n.contains("Cancellation")) kind = "Cancelled";
                else kind = "Exception";
            }
            String human;
            switch (kind) {
                case "SessionNotReady": human = "Steam session not ready — GameHub is still connecting"; break;
                case "NotConnected":    human = "Steam not connected"; break;
                case "NotAuthenticated": human = "not signed into Steam in GameHub"; break;
                case "Timeout":         human = "timed out"; break;
                default:                human = kind; break;
            }
            return Outcome.fail(kind, human + ": " + msg);
        }

        // ── listenJson → Flow.collect ──────────────────────────────────────

        String startCollect(final String topic, final AtomicBoolean cancel, final EventSink sink) {
            try {
                final Object flow = listenJson.invoke(client, topic);
                if (flow == null) return "listenJson returned null";
                final Method collect = flow.getClass().getMethod("collect",
                        Class.forName("kotlinx.coroutines.flow.FlowCollector", false, loader()),
                        Class.forName("kotlin.coroutines.Continuation", false, loader()));
                final Object unitVal = unit;
                final FlowCollector<Object> collector = new FlowCollector<Object>() {
                    public Object emit(Object value, Continuation<?> continuation) {
                        if (cancel.get()) throw new IllegalStateException("bh-unsubscribe");
                        String json = eventToJson(value, topic);
                        if (json != null) { try { sink.onEvent(json); } catch (Throwable ignored) {} }
                        return unitVal;
                    }
                };
                final Continuation<Object> completion = new Continuation<Object>() {
                    public CoroutineContext getContext() { return (CoroutineContext) emptyContext; }
                    public void resumeWith(Object result) {
                        BhSteamLog.i("relay: flow " + topic + " completed: " + result);
                    }
                };
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        try { collect.invoke(flow, collector, completion); }
                        catch (Throwable err) { BhSteamLog.i("relay: collect " + topic + " ended: " + err); }
                    }
                }, "bh-steam-relay-flow");
                t.setDaemon(true);
                t.start();
                BhSteamLog.i("relay: listening on " + topic);
                return null;
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                return "listen failed: " + c.getClass().getSimpleName() + ": " + c.getMessage();
            }
        }

        /** Payload JSON out of the bridge event (6.3.1: {topic:String, payloadJson:String} data class). */
        static String eventToJson(Object ev, String topic) {
            if (ev == null) return null;
            if (ev instanceof String) return (String) ev;
            for (String n : new String[]{"getPayloadJson", "component2", "getPayload"}) {
                try {
                    Object v = ev.getClass().getMethod(n).invoke(ev);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) {}
            }
            try {
                String other = null;
                for (Field f : ev.getClass().getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(ev);
                    if (!(v instanceof String)) continue;
                    String s = ((String) v).trim();
                    if (s.startsWith("{") || s.startsWith("[")) return s;
                    if (!s.equals(topic)) other = s;
                }
                return other;
            } catch (Throwable ignored) {}
            return null;
        }
    }

    /**
     * The continuation handed to {@code executeRaw}. Extends the KEPT
     * {@code ContinuationImpl} so the R8-typed parameter accepts it; the
     * resumption lands in {@link #invokeSuspend} with a {@code kotlin.Result}
     * -encoded value. Returning {@code Unit} (never COROUTINE_SUSPENDED)
     * lets {@code BaseContinuationImpl.resumeWith} finish by resuming the
     * completion latch below.
     */
    static final class SuspendCall extends ContinuationImpl {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Object> value = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final Bridge bridge;

        SuspendCall(Bridge bridge) {
            super(new Latch(bridge.emptyContext), (CoroutineContext) bridge.emptyContext);
            this.bridge = bridge;
        }

        @Override public Object invokeSuspend(Object result) {
            try {
                Throwable t = (Throwable) bridge.exceptionOrNull.invoke(null, result);
                if (t != null) failure.set(t); else value.set(result);
            } catch (Throwable t) {
                failure.set(t.getCause() != null ? t.getCause() : t);
            }
            done.countDown();
            return bridge.unit;
        }

        /** Completion for the state machine: nothing to do, the outcome was captured in invokeSuspend. */
        static final class Latch implements Continuation<Object> {
            private final Object ctx;
            Latch(Object ctx) { this.ctx = ctx; }
            public CoroutineContext getContext() { return (CoroutineContext) ctx; }
            public void resumeWith(Object result) { /* already captured */ }
        }
    }
}
