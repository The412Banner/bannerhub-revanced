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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1 — friends + presence + own SteamID over XiaoJi's cross-process
 * "overlay invite" IPC (GameHub 6.3.1), usable from the {@code :pcengine}
 * process where the in-process Steam bridge does not exist.
 *
 * <p>Host surface (all KEPT names / literal keys, verified in the 6.3.1 smali):
 * <pre>
 *   service  com.xiaoji.egggame.common.steam.friends.SteamFriendsChatAndroidService   (main process)
 *   onBind   only for action com.xiaoji.egggame.steam.action.OVERLAY_INVITE_IPC → Messenger
 *   request  Message.what = 1 status | 2 friends | 3 invite_lobby | 4 invite_game
 *            Message.replyTo = our Messenger
 *            data { protocol_version:int=1, request_id:long, timeout_ms:long }
 *   reply    Message.what = 100
 *            data { protocol_version, request_id, ok:boolean, page_index:int, page_count:int,
 *                   [error_code:String, error_message:String]
 *                   status  → readiness:String (Available|SignedOut|Connecting|Offline|Unavailable), steam_id:long
 *                   friends → friends_json:String = JSON array of SteamOverlayInviteFriendPayload
 *                             {steamId, displayName, displayNameSource, personaName, nickname,
 *                              avatarUrl, presenceKnown, personaState, isOnline, isInGame,
 *                              gameName, gameAppId}, chunked 100 per page, one reply per page
 *                             (an empty list still yields one "[]" page) }
 * </pre>
 * The friend payload field names are exactly what {@code BhSteamChatOverlay}
 * already reads from {@code friends.list} ({@code steamId}, {@code nickname} /
 * {@code displayName} / {@code personaName}, {@code isOnline}, {@code isInGame},
 * {@code gameName}, {@code avatarUrl}), so pages are concatenated into
 * {@code {"friends":[...]}} and handed over unchanged.
 *
 * <p>The IPC has no push, so presence is polled: {@link #PRESENCE_POLL_MS}
 * (30 s) refreshes the cached list in the background while the client is
 * bound; {@link #friendsListJson} serves the cache when it is fresh and
 * fetches synchronously otherwise. There is NO chat operation on this
 * channel — chat needs the M2 relay ({@link BhSteamRelayClient}).
 *
 * <p>Worker-thread only for the blocking calls; the reply Messenger runs on
 * its own HandlerThread. Payloads stay per-page (≤ 100 friends), well under
 * the 1 MB Binder transaction limit.
 */
public final class BhSteamIpcClient {

    private static final String TAG = "BH_STEAM";

    /** Presence refresh interval (no push on this IPC). */
    public static final long PRESENCE_POLL_MS = 30_000L;

    static final String SERVICE_CLASS =
            "com.xiaoji.egggame.common.steam.friends.SteamFriendsChatAndroidService";
    static final String ACTION_INVITE_IPC = "com.xiaoji.egggame.steam.action.OVERLAY_INVITE_IPC";

    static final int WHAT_STATUS = 1;
    static final int WHAT_FRIENDS = 2;
    static final int WHAT_REPLY = 100;
    static final int PROTOCOL_VERSION = 1;

    private static final long BIND_TIMEOUT_MS = 6_000L;

    private static final BhSteamIpcClient INSTANCE = new BhSteamIpcClient();

    public static BhSteamIpcClient get() { return INSTANCE; }

    private final Object lock = new Object();
    private final AtomicLong requestIds = new AtomicLong(System.nanoTime() & 0xffffffL);
    private final Map<Long, Pending> pending = new HashMap<>();

    private HandlerThread callbackThread;
    private Messenger replyMessenger;
    private Messenger service;            // null while unbound
    private CountDownLatch bindLatch;
    private boolean binding;
    private String lastError = "";

    // presence cache
    private volatile String cachedFriendsJson;
    private volatile long cachedAt;
    private Thread pollThread;

    private BhSteamIpcClient() {}

    /** Reason the last operation failed (bind timeout, remote error, timeout). */
    public String getLastError() { return lastError; }

    // ── bind ────────────────────────────────────────────────────────────────

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (lock) {
                service = new Messenger(binder);
                binding = false;
                if (bindLatch != null) bindLatch.countDown();
            }
            BhSteamLog.i("ipc: bound to invite IPC");
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            BhSteamLog.w("ipc: invite IPC disconnected");
            synchronized (lock) {
                service = null;
                failAllLocked("service disconnected");
            }
        }
        @Override public void onBindingDied(ComponentName name) {
            synchronized (lock) {
                service = null;
                binding = false;
                failAllLocked("binding died");
            }
            try { appContext().unbindService(this); } catch (Throwable ignored) {}
        }
        @Override public void onNullBinding(ComponentName name) {
            BhSteamLog.w("ipc: null binding (action not accepted?)");
            synchronized (lock) {
                binding = false;
                lastError = "invite IPC returned a null binding";
                if (bindLatch != null) bindLatch.countDown();
            }
        }
    };

    private static Context appContext() { return BhSteamBridge.appContext(); }

    /** Bind (once) and wait for the Messenger. Blocking; worker thread only. */
    public boolean ensureBound(long timeoutMs) {
        CountDownLatch latch;
        synchronized (lock) {
            if (service != null) return true;
            if (!binding) {
                Context ctx = appContext();
                if (ctx == null) { lastError = "no application context"; return false; }
                if (callbackThread == null) {
                    callbackThread = new HandlerThread("bh-steam-ipc-cb");
                    callbackThread.setDaemon(true);
                    callbackThread.start();
                    replyMessenger = new Messenger(new ReplyHandler(callbackThread));
                }
                Intent it = new Intent(ACTION_INVITE_IPC);
                it.setClassName(ctx.getPackageName(), SERVICE_CLASS);
                bindLatch = new CountDownLatch(1);
                boolean ok;
                try { ok = ctx.bindService(it, conn, Context.BIND_AUTO_CREATE); }
                catch (Throwable t) { ok = false; lastError = "bindService threw " + t; }
                if (!ok) {
                    if (lastError.isEmpty()) lastError = "bindService returned false (service missing?)";
                    BhSteamLog.w("ipc: " + lastError);
                    try { ctx.unbindService(conn); } catch (Throwable ignored) {}
                    return false;
                }
                binding = true;
            }
            latch = bindLatch;
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        synchronized (lock) {
            if (service == null && lastError.isEmpty()) lastError = "bind timeout after " + timeoutMs + "ms";
            return service != null;
        }
    }

    // ── request / reply plumbing ────────────────────────────────────────────

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        final HashMap<Integer, Bundle> pages = new HashMap<>();
        int pageCount = -1;
        boolean ok;
        String error;
        Bundle first;
    }

    private final class ReplyHandler extends Handler {
        ReplyHandler(HandlerThread t) { super(t.getLooper()); }
        @Override public void handleMessage(Message msg) {
            if (msg.what != WHAT_REPLY) return;
            Bundle d = msg.getData();
            if (d == null) return;
            long id = d.getLong("request_id", 0);
            Pending p;
            synchronized (lock) { p = pending.get(id); }
            if (p == null) return;
            boolean ok = d.getBoolean("ok", false);
            int pageIndex = d.getInt("page_index", 0);
            int pageCount = d.getInt("page_count", 1);
            synchronized (p) {
                if (p.first == null) p.first = new Bundle(d);
                if (!ok) {
                    p.ok = false;
                    p.error = d.getString("error_code", "remote_failure") + ": "
                            + d.getString("error_message", "");
                    p.done.countDown();
                    return;
                }
                p.ok = true;
                p.pageCount = Math.max(1, pageCount);
                p.pages.put(pageIndex, new Bundle(d));
                if (p.pages.size() >= p.pageCount) p.done.countDown();
            }
        }
    }

    private void failAllLocked(String why) {
        for (Pending p : pending.values()) {
            synchronized (p) { p.ok = false; p.error = why; p.done.countDown(); }
        }
        pending.clear();
    }

    /** Send one request and gather all its reply pages. */
    private Pending call(int what, long timeoutMs) {
        lastError = "";
        if (!ensureBound(BIND_TIMEOUT_MS)) return null;
        long id = requestIds.incrementAndGet();
        Pending p = new Pending();
        Bundle data = new Bundle();
        data.putInt("protocol_version", PROTOCOL_VERSION);
        data.putLong("request_id", id);
        data.putLong("timeout_ms", timeoutMs);
        Message m = Message.obtain(null, what);
        m.setData(data);
        Messenger svc;
        synchronized (lock) {
            svc = service;
            if (svc == null) { lastError = "not bound"; return null; }
            m.replyTo = replyMessenger;
            pending.put(id, p);
        }
        try {
            svc.send(m);
        } catch (RemoteException e) {
            synchronized (lock) { pending.remove(id); service = null; }
            lastError = "send failed: " + e;
            BhSteamLog.w("ipc: " + lastError);
            return null;
        }
        boolean finished = false;
        try { finished = p.done.await(timeoutMs + 1500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException ignored) {}
        synchronized (lock) { pending.remove(id); }
        if (!finished) { lastError = "timeout after " + timeoutMs + "ms (what=" + what + ")"; return null; }
        if (!p.ok) { lastError = p.error != null ? p.error : "remote failure"; return null; }
        return p;
    }

    // ── public ops ──────────────────────────────────────────────────────────

    /** Own SteamID64 + readiness, or null (see {@link #getLastError()}). */
    public Status status(long timeoutMs) {
        Pending p = call(WHAT_STATUS, timeoutMs);
        if (p == null) return null;
        Bundle b = p.first;
        Status s = new Status();
        s.readiness = b.getString("readiness", "");
        s.steamId = b.getLong("steam_id", 0);
        return s;
    }

    public static final class Status {
        public String readiness = "";
        public long steamId;
        public boolean isAvailable() { return "Available".equalsIgnoreCase(readiness); }
        /** Shape expected by the overlay's {@code auth.bootstrap_snapshot} SteamID64 regex. */
        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("steamId", String.valueOf(steamId));
                o.put("readiness", readiness);
                o.put("source", "invite_ipc");
                return o.toString();
            } catch (Throwable t) { return "{\"steamId\":\"" + steamId + "\"}"; }
        }
    }

    /**
     * Fresh friends list as {@code {"friends":[...]}} (all pages joined), or
     * null. Also refreshes the presence cache.
     */
    public String fetchFriendsJson(long timeoutMs) {
        Pending p = call(WHAT_FRIENDS, timeoutMs);
        if (p == null) return null;
        JSONArray all = new JSONArray();
        for (int i = 0; i < p.pageCount; i++) {
            Bundle page = p.pages.get(i);
            if (page == null) continue;
            String js = page.getString("friends_json", "[]");
            try {
                JSONArray arr = new JSONArray(js);
                for (int k = 0; k < arr.length(); k++) all.put(arr.opt(k));
            } catch (Throwable t) {
                BhSteamLog.w("ipc: bad friends_json page " + i + ": " + t);
            }
        }
        String out;
        try {
            JSONObject o = new JSONObject();
            o.put("friends", all);
            o.put("source", "invite_ipc");
            out = o.toString();
        } catch (Throwable t) { out = "{\"friends\":" + all + "}"; }
        cachedFriendsJson = out;
        cachedAt = System.currentTimeMillis();
        startPollIfNeeded();
        return out;
    }

    /** Cached list if younger than {@link #PRESENCE_POLL_MS}, else a fresh fetch. */
    public String friendsListJson(long timeoutMs) {
        String c = cachedFriendsJson;
        if (c != null && System.currentTimeMillis() - cachedAt < PRESENCE_POLL_MS) return c;
        String fresh = fetchFriendsJson(timeoutMs);
        return fresh != null ? fresh : c;
    }

    private void startPollIfNeeded() {
        synchronized (lock) {
            if (pollThread != null && pollThread.isAlive()) return;
            pollThread = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        try { Thread.sleep(PRESENCE_POLL_MS); } catch (InterruptedException e) { return; }
                        synchronized (lock) { if (service == null) return; }
                        try { fetchFriendsJson(8000); } catch (Throwable ignored) {}
                    }
                }
            }, "bh-steam-presence-poll");
            pollThread.setDaemon(true);
            pollThread.start();
        }
    }

    /** Drop the binding (the overlay is going away). */
    public void shutdown() {
        Context ctx = appContext();
        synchronized (lock) {
            if (service != null || binding) {
                try { if (ctx != null) ctx.unbindService(conn); } catch (Throwable ignored) {}
            }
            service = null;
            binding = false;
            failAllLocked("shutdown");
            if (pollThread != null) { pollThread.interrupt(); pollThread = null; }
        }
    }
}
