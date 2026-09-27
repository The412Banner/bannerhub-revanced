package com.xj.winemu.steamchat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code :pcengine}-side client of {@link BhSteamRelayService} (main process).
 * Binds lazily, keeps the binding for the overlay's lifetime, correlates
 * replies by {@code request_id}, reassembles paged strings, and fans pushed
 * {@code EVENT}s out to topic listeners. Re-subscribes every live topic after
 * a reconnect. See the service for the message contract.
 *
 * Blocking calls are worker-thread only. Tag {@code BH_STEAM}.
 */
public final class BhSteamRelayClient {

    private static final String TAG = "BH_STEAM";
    private static final long BIND_TIMEOUT_MS = 8_000L;   // cold main process may need to start

    private static final BhSteamRelayClient INSTANCE = new BhSteamRelayClient();
    public static BhSteamRelayClient get() { return INSTANCE; }

    public interface EventListener { void onEvent(String payloadJson); }

    private final Object lock = new Object();
    private final AtomicLong requestIds = new AtomicLong((System.nanoTime() >>> 8) & 0xffffffL);
    private final Map<Long, Pending> pending = new HashMap<>();
    private final Map<String, List<EventListener>> listeners = new HashMap<>();
    /** (topic, event_seq) → partial pages of a multi-page event. */
    private final Map<String, StringBuilder[]> eventPages = new HashMap<>();

    private HandlerThread callbackThread;
    private Messenger replyMessenger;
    private Messenger service;
    private CountDownLatch bindLatch;
    private boolean binding;
    private String lastError = "";

    /** Parsed STATUS from the service: whether the main-process bridge is attached + its text. */
    public static final class RelayStatus {
        public boolean attached;
        public String status = "";
        public int pid;
    }

    private BhSteamRelayClient() {}

    public String getLastError() { return lastError; }

    private static Context appContext() { return BhSteamBridge.appContext(); }

    // ── bind ────────────────────────────────────────────────────────────────

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            List<String> topics;
            synchronized (lock) {
                service = new Messenger(binder);
                binding = false;
                if (bindLatch != null) bindLatch.countDown();
                topics = new ArrayList<>(listeners.keySet());
            }
            BhSteamLog.i("relay-client: bound (" + topics.size() + " topics to (re)subscribe)");
            for (String t : topics) sendSubscribe(t);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            BhSteamLog.w("relay-client: disconnected");
            synchronized (lock) { service = null; failAllLocked("relay disconnected"); }
        }
        @Override public void onBindingDied(ComponentName name) {
            BhSteamLog.w("relay-client: binding died");
            synchronized (lock) { service = null; binding = false; failAllLocked("relay binding died"); }
            try { Context c = appContext(); if (c != null) c.unbindService(this); } catch (Throwable ignored) {}
        }
        @Override public void onNullBinding(ComponentName name) {
            synchronized (lock) {
                binding = false;
                lastError = "relay service returned a null binding";
                if (bindLatch != null) bindLatch.countDown();
            }
        }
    };

    /** Bind (once) and wait. Blocking; worker thread only. */
    public boolean ensureBound(long timeoutMs) {
        CountDownLatch latch;
        synchronized (lock) {
            if (service != null) return true;
            if (!binding) {
                Context ctx = appContext();
                if (ctx == null) { lastError = "no application context"; return false; }
                if (callbackThread == null) {
                    callbackThread = new HandlerThread("bh-steam-relay-cb");
                    callbackThread.setDaemon(true);
                    callbackThread.start();
                    replyMessenger = new Messenger(new ReplyHandler(callbackThread));
                }
                Intent it = new Intent();
                it.setClassName(ctx.getPackageName(), BhSteamRelayService.SERVICE_CLASS);
                bindLatch = new CountDownLatch(1);
                lastError = "";
                boolean ok;
                try { ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT); }
                catch (Throwable t) { ok = false; lastError = "bindService threw " + t; }
                if (!ok) {
                    if (lastError.isEmpty()) lastError = "bindService returned false — relay service not in the manifest?";
                    BhSteamLog.w("relay-client: " + lastError);
                    try { ctx.unbindService(conn); } catch (Throwable ignored) {}
                    return false;
                }
                binding = true;
            }
            latch = bindLatch;
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        synchronized (lock) {
            if (service == null && lastError.isEmpty()) lastError = "relay bind timeout after " + timeoutMs + "ms";
            return service != null;
        }
    }

    public boolean isBound() { synchronized (lock) { return service != null; } }

    // ── replies ─────────────────────────────────────────────────────────────

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        String[] pages;
        int received;
        boolean ok;
        String error;
        String errorKind;
    }

    private final class ReplyHandler extends Handler {
        ReplyHandler(HandlerThread t) { super(t.getLooper()); }
        @Override public void handleMessage(Message msg) {
            Bundle d = msg.getData();
            if (d == null) return;
            if (msg.what == BhSteamRelayService.MSG_REPLY) onReply(d);
            else if (msg.what == BhSteamRelayService.MSG_EVENT) onEvent(d);
        }
    }

    private void onReply(Bundle d) {
        long id = d.getLong("request_id", 0);
        Pending p;
        synchronized (lock) { p = pending.get(id); }
        if (p == null) return;
        synchronized (p) {
            if (!d.getBoolean("ok", false)) {
                p.ok = false;
                p.error = d.getString("error", "unknown");
                p.errorKind = d.getString("error_kind", "");
                p.done.countDown();
                return;
            }
            int count = Math.max(1, d.getInt("page_count", 1));
            int index = d.getInt("page_index", 0);
            if (p.pages == null) p.pages = new String[count];
            if (index >= 0 && index < p.pages.length && p.pages[index] == null) {
                p.pages[index] = d.getString("result", "");
                p.received++;
            }
            if (p.received >= p.pages.length) { p.ok = true; p.done.countDown(); }
        }
    }

    private void onEvent(Bundle d) {
        String topic = d.getString("topic", "");
        int count = Math.max(1, d.getInt("page_count", 1));
        int index = d.getInt("page_index", 0);
        String chunk = d.getString("json", "");
        String json;
        if (count == 1) {
            json = chunk;
        } else {
            String key = topic + "#" + d.getLong("event_seq", 0);
            synchronized (eventPages) {
                StringBuilder[] parts = eventPages.get(key);
                if (parts == null) { parts = new StringBuilder[count]; eventPages.put(key, parts); }
                if (index >= 0 && index < parts.length) parts[index] = new StringBuilder(chunk);
                for (StringBuilder sb : parts) if (sb == null) return;   // wait for the rest
                eventPages.remove(key);
                StringBuilder all = new StringBuilder();
                for (StringBuilder sb : parts) all.append(sb);
                json = all.toString();
            }
        }
        List<EventListener> ls;
        synchronized (lock) {
            List<EventListener> cur = listeners.get(topic);
            if (cur == null || cur.isEmpty()) return;
            ls = new ArrayList<>(cur);
        }
        for (EventListener l : ls) { try { l.onEvent(json); } catch (Throwable ignored) {} }
    }

    private void failAllLocked(String why) {
        for (Pending p : pending.values()) {
            synchronized (p) { p.ok = false; p.error = why; p.done.countDown(); }
        }
        pending.clear();
    }

    // ── requests ────────────────────────────────────────────────────────────

    private Pending send(int what, Bundle data, long waitMs) {
        lastError = "";
        if (!ensureBound(BIND_TIMEOUT_MS)) return null;
        long id = requestIds.incrementAndGet();
        data.putInt("protocol_version", BhSteamRelayService.PROTOCOL_VERSION);
        data.putLong("request_id", id);
        Message m = Message.obtain(null, what);
        m.setData(data);
        Pending p = new Pending();
        Messenger svc;
        synchronized (lock) {
            svc = service;
            if (svc == null) { lastError = "relay not bound"; return null; }
            m.replyTo = replyMessenger;
            pending.put(id, p);
        }
        try {
            svc.send(m);
        } catch (RemoteException e) {
            synchronized (lock) { pending.remove(id); service = null; }
            lastError = "relay send failed: " + e;
            return null;
        }
        boolean finished = false;
        try { finished = p.done.await(waitMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        synchronized (lock) { pending.remove(id); }
        if (!finished) { lastError = "relay timeout after " + waitMs + "ms"; return null; }
        if (!p.ok) {
            lastError = (p.errorKind != null && !p.errorKind.isEmpty() ? p.errorKind + " — " : "") + p.error;
            return null;
        }
        return p;
    }

    private static String join(Pending p) {
        if (p.pages == null) return "";
        if (p.pages.length == 1) return p.pages[0] != null ? p.pages[0] : "";
        StringBuilder sb = new StringBuilder();
        for (String s : p.pages) if (s != null) sb.append(s);
        return sb.toString();
    }

    /** Ask the main-process service whether it has attached to the Steam bridge. */
    public RelayStatus status(long timeoutMs) {
        Pending p = send(BhSteamRelayService.MSG_STATUS, new Bundle(), timeoutMs);
        if (p == null) return null;
        RelayStatus s = new RelayStatus();
        try {
            JSONObject o = new JSONObject(join(p));
            s.attached = o.optBoolean("attached", false);
            s.status = o.optString("status", "");
            s.pid = o.optInt("pid", 0);
        } catch (Throwable t) {
            s.status = "bad status json: " + t;
        }
        return s;
    }

    /** {@code executeRaw(cmd, json)} in the main process; null on failure (see {@link #getLastError()}). */
    public String exec(String cmd, String json, long timeoutMs) {
        Bundle b = new Bundle();
        b.putString("cmd", cmd);
        b.putString("json", json != null ? json : "{}");
        b.putLong("timeout_ms", timeoutMs);
        Pending p = send(BhSteamRelayService.MSG_EXEC, b, timeoutMs + 2500);
        return p == null ? null : join(p);
    }

    /** Subscribe to a topic; events are delivered on the callback thread. Returns null when the service accepted it. */
    public String subscribe(String topic, EventListener l) {
        synchronized (lock) {
            List<EventListener> ls = listeners.get(topic);
            if (ls == null) { ls = new ArrayList<>(); listeners.put(topic, ls); }
            ls.add(l);
        }
        return sendSubscribe(topic);
    }

    private String sendSubscribe(String topic) {
        Bundle b = new Bundle();
        b.putString("topic", topic);
        Pending p = send(BhSteamRelayService.MSG_SUBSCRIBE, b, 6000);
        if (p == null) {
            BhSteamLog.w("relay-client: subscribe " + topic + " failed: " + lastError);
            return lastError.isEmpty() ? "subscribe failed" : lastError;
        }
        return null;
    }

    public void unsubscribe(String topic, EventListener l) {
        boolean last;
        synchronized (lock) {
            List<EventListener> ls = listeners.get(topic);
            if (ls == null) return;
            ls.remove(l);
            last = ls.isEmpty();
            if (last) listeners.remove(topic);
        }
        if (!last) return;
        Messenger svc;
        synchronized (lock) { svc = service; }
        if (svc == null) return;
        Bundle b = new Bundle();
        b.putString("topic", topic);
        b.putInt("protocol_version", BhSteamRelayService.PROTOCOL_VERSION);
        Message m = Message.obtain(null, BhSteamRelayService.MSG_UNSUBSCRIBE);
        m.setData(b);
        m.replyTo = replyMessenger;
        try { svc.send(m); } catch (RemoteException ignored) {}
    }

    public void shutdown() {
        Context ctx = appContext();
        synchronized (lock) {
            if (service != null || binding) {
                try { if (ctx != null) ctx.unbindService(conn); } catch (Throwable ignored) {}
            }
            service = null;
            binding = false;
            listeners.clear();
            failAllLocked("shutdown");
        }
    }
}
