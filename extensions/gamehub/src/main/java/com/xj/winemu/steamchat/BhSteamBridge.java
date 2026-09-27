package com.xj.winemu.steamchat;

import android.content.Context;
import android.util.Log;

/**
 * Transport facade for the in-game Steam · Friends overlay
 * ({@link BhSteamChatOverlay}), GameHub 6.3.1 design.
 *
 * <p>Public surface is unchanged from the 6.0.x bridge so the overlay stays
 * untouched: {@link #isAvailable()}, {@link #getStatus()},
 * {@link #getLastError()}, {@link #request(String, String, long)},
 * {@link #listen(String, EventListener)}, {@link #unlisten(Object)}.
 *
 * <h3>Why the old in-process path died</h3>
 * 6.3.1 R8-renames {@code SteamBridgeClient} (→ {@code Ltwv;}), types its
 * suspend parameter as {@code ContinuationImpl}, drops
 * {@code Koin.getInstanceRegistry()}, and — decisively — creates the Steam
 * bridge ONLY in the main process while this overlay runs in
 * {@code :pcengine} (the host activity is
 * {@code PcEnginePluginHostActivity}, {@code android:process=":pcengine"}).
 * Every command string and event topic the overlay uses still exists
 * verbatim in 6.3.1's typed friends repository; only the transport moved.
 *
 * <h3>Transports, best first</h3>
 * <ol>
 *   <li><b>RELAY</b> ({@link BhSteamRelayClient} → {@link BhSteamRelayService},
 *       main process): full {@code executeRaw}/{@code listenJson} — every
 *       command + live {@code steam:chat-message}/{@code steam:chat-typing}
 *       events. Chosen when the service binds AND reports the bridge
 *       singleton attached.</li>
 *   <li><b>IPC</b> ({@link BhSteamIpcClient}, XiaoJi's
 *       {@code OVERLAY_INVITE_IPC} Messenger): friends list + presence (polled
 *       every {@link BhSteamIpcClient#PRESENCE_POLL_MS}) and the own SteamID
 *       ({@code auth.bootstrap_snapshot} equivalent). No chat — those commands
 *       return null with {@link #getLastError()} = "chat relay unavailable".
 *       Status reads "friends only — chat relay unavailable: &lt;why&gt;".</li>
 *   <li><b>NONE</b>: "FAILED @ …" with both reasons.</li>
 * </ol>
 * While not in RELAY mode the resolver re-probes the relay every
 * {@link #REPROBE_MS} (a cold main process attaches the bridge lazily), so a
 * session that opened in IPC mode upgrades to chat without restarting.
 *
 * <p>All Binder payloads are paged by the relay (≤ ~192 KB per transaction)
 * and by the invite IPC (100 friends per page). NEVER call {@link #request}
 * on the UI thread. Tag {@code BH_STEAM}.
 */
public final class BhSteamBridge {

    private static final String TAG = "BH_STEAM";

    /** How often a non-RELAY session re-probes the relay. */
    public static final long REPROBE_MS = 15_000L;

    public enum Mode { NONE, IPC, RELAY }

    /** Callback for {@link #listen}: receives each event's payload JSON for the topic. */
    public interface EventListener { void onEvent(String payloadJson); }

    private static volatile Mode sMode = Mode.NONE;
    private static volatile boolean sResolvedOnce = false;
    private static volatile long sLastProbe = 0;
    private static volatile String sStatus = "not resolved";
    private static volatile String sLastError = "";
    private static volatile String sRelayWhy = "";

    private BhSteamBridge() {}

    // ── status ─────────────────────────────────────────────────────────────

    /** True when at least the friends list can be served (IPC or RELAY). */
    public static boolean isAvailable() {
        maybeResolve();
        return sMode != Mode.NONE;
    }

    /** True only when chat (send/history/events) works — i.e. the relay attached. */
    public static boolean isChatAvailable() {
        maybeResolve();
        return sMode == Mode.RELAY;
    }

    public static Mode getMode() { return sMode; }

    /** Human-readable resolve outcome (shown on the overlay). */
    public static String getStatus() { return sStatus; }

    /** Reason the most recent request() returned null. */
    public static String getLastError() { return sLastError; }

    private static void maybeResolve() {
        if (sMode == Mode.RELAY) return;
        long now = System.currentTimeMillis();
        if (sResolvedOnce && now - sLastProbe < REPROBE_MS) return;
        resolve();
    }

    private static synchronized void resolve() {
        long now = System.currentTimeMillis();
        if (sMode == Mode.RELAY) return;
        if (sResolvedOnce && now - sLastProbe < REPROBE_MS) return;
        sLastProbe = now;
        sResolvedOnce = true;

        // 1. relay (main process)
        BhSteamRelayClient relay = BhSteamRelayClient.get();
        if (relay.ensureBound(8000)) {
            BhSteamRelayClient.RelayStatus st = relay.status(4000);
            if (st != null && st.attached) {
                sMode = Mode.RELAY;
                sStatus = "ok (relay → main pid " + st.pid + ": " + st.status + ")";
                sRelayWhy = "";
                Log.i(TAG, "bridge: mode RELAY — " + sStatus);
                return;
            }
            sRelayWhy = st != null ? st.status : relay.getLastError();
        } else {
            sRelayWhy = relay.getLastError();
        }
        Log.w(TAG, "bridge: relay unavailable — " + sRelayWhy);

        // 2. invite IPC (friends + presence + own id)
        BhSteamIpcClient ipc = BhSteamIpcClient.get();
        BhSteamIpcClient.Status is = ipc.status(6000);
        if (is != null) {
            sMode = Mode.IPC;
            sStatus = "friends only — chat relay unavailable: " + sRelayWhy
                    + " · IPC readiness=" + is.readiness;
            Log.i(TAG, "bridge: mode IPC — " + sStatus);
            return;
        }

        // 3. nothing
        sMode = Mode.NONE;
        sStatus = "FAILED @ relay: " + sRelayWhy + " · invite IPC: " + ipc.getLastError();
        Log.w(TAG, "bridge: " + sStatus);
    }

    /** Called when the relay reports it lost the bridge; forces a re-probe on the next call. */
    private static void demoteRelay(String why) {
        if (sMode != Mode.RELAY) return;
        Log.w(TAG, "bridge: relay demoted — " + why);
        sMode = Mode.NONE;
        sResolvedOnce = false;
        sRelayWhy = why;
    }

    // ── request ────────────────────────────────────────────────────────────

    /**
     * Fire a Steam JSON-RPC command and return the raw response JSON, or null
     * on any failure/timeout (reason in {@link #getLastError()}). Blocking.
     */
    public static String request(String topic, String payloadJson, long timeoutMs) {
        sLastError = "";
        if (!isAvailable()) { sLastError = "bridge unavailable: " + sStatus; return null; }
        switch (sMode) {
            case RELAY: {
                BhSteamRelayClient relay = BhSteamRelayClient.get();
                String r = relay.exec(topic, payloadJson, timeoutMs);
                if (r != null) return r;
                sLastError = relay.getLastError();
                Log.w(TAG, "request " + topic + " failed: " + sLastError);
                if (sLastError.startsWith("NotAttached") || sLastError.contains("relay disconnected")
                        || sLastError.contains("relay binding died") || sLastError.contains("relay not bound")) {
                    demoteRelay(sLastError);
                }
                return null;
            }
            case IPC: {
                BhSteamIpcClient ipc = BhSteamIpcClient.get();
                if ("friends.list".equals(topic)) {
                    String r = ipc.friendsListJson(timeoutMs);
                    if (r == null) sLastError = "invite IPC: " + ipc.getLastError();
                    return r;
                }
                if ("auth.bootstrap_snapshot".equals(topic)) {
                    BhSteamIpcClient.Status s = ipc.status(timeoutMs);
                    if (s == null) { sLastError = "invite IPC: " + ipc.getLastError(); return null; }
                    return s.toJson();
                }
                sLastError = "chat relay unavailable (" + sRelayWhy + ") — " + topic + " needs the main-process bridge";
                return null;
            }
            default:
                sLastError = "bridge unavailable";
                return null;
        }
    }

    // ── events ─────────────────────────────────────────────────────────────

    private static final class Sub {
        final String topic; final BhSteamRelayClient.EventListener l;
        Sub(String t, BhSteamRelayClient.EventListener l) { this.topic = t; this.l = l; }
    }

    /**
     * Live event subscription (RELAY mode only — the invite IPC has no push).
     * Events are delivered on the relay client's callback thread.
     *
     * @return an opaque handle for {@link #unlisten}, or null if it couldn't start.
     */
    public static Object listen(final String topic, final EventListener listener) {
        if (listener == null) return null;
        if (!isChatAvailable()) { sLastError = "listen: chat relay unavailable (" + sRelayWhy + ")"; return null; }
        BhSteamRelayClient.EventListener l = new BhSteamRelayClient.EventListener() {
            public void onEvent(String payloadJson) { listener.onEvent(payloadJson); }
        };
        String err = BhSteamRelayClient.get().subscribe(topic, l);
        if (err != null) {
            BhSteamRelayClient.get().unsubscribe(topic, l);
            sLastError = "listen: " + err;
            Log.w(TAG, "listen " + topic + " failed: " + err);
            return null;
        }
        Log.i(TAG, "listening on " + topic + " via relay");
        return new Sub(topic, l);
    }

    /** Stop a {@link #listen} subscription. */
    public static void unlisten(Object handle) {
        if (handle instanceof Sub) {
            Sub s = (Sub) handle;
            BhSteamRelayClient.get().unsubscribe(s.topic, s.l);
        }
    }

    // ── context ────────────────────────────────────────────────────────────

    /**
     * Application context for binding services from static code. The overlay
     * only hands us an Activity, so pull the Application off ActivityThread
     * (works in every process), then fall back to the host's blankj Utils.
     */
    static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        try {
            Class<?> u = Class.forName("com.blankj.utilcode.util.Utils");
            Object app = u.getMethod("a").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }
}
