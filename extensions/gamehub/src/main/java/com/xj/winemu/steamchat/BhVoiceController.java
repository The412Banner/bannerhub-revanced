package com.xj.winemu.steamchat;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;

/**
 * WebRTC voice call for the in-game Steam chat overlay.
 *
 * <p>The call runs inside an attached (1×1, invisible) {@link WebView} that loads
 * a small page served from our bannerhub-api worker at a <b>real https origin</b>.
 * Earlier builds used {@code loadDataWithBaseURL}, whose opaque origin made
 * Chromium refuse microphone access ({@code getUserMedia} hung forever); serving
 * the page from a real origin fixes that. The hosted page owns the whole call —
 * {@code getUserMedia} + {@code RTCPeerConnection}, with SDP/ICE relayed through
 * the worker's R2-backed mailbox and STUN/TURN from {@code /voice/turn}. Java only
 * attaches the WebView, surfaces call state to the overlay through the
 * {@code BhVoice} JS bridge, and can mute / hang up. No Steam-chat signalling is
 * involved.
 *
 * <p>Two ways to identify a room, both landing in the same worker mesh:
 * <ul>
 *   <li><b>Steam 1:1 / party</b> — room id = sorted SteamID pair, self/peer are
 *       SteamID64s. Used by the Steam-friend call buttons.</li>
 *   <li><b>Room code</b> — a short shared code, self = a stable client id, plus a
 *       {@code name} (nickname/persona) shown in everyone's roster, and no fixed
 *       peer (full-mesh roster discovery). This is the path that interops with
 *       BannerHub 3.7.5's code rooms: the mesh connects purely by peer-id + room
 *       with no friendship check, so a v6 user and a 3.7.5 nickname user can share
 *       one code and talk.</li>
 * </ul>
 */
public final class BhVoiceController {

    private static final String TAG = "BhSteamChat";
    private static final String BASE = "https://bannerhub-api.the412banner.workers.dev";

    /** WebView (Chromium) major versions below this can't reliably open the mic
     *  for WebRTC inside an embedded WebView — observed: 113 hangs getUserMedia
     *  forever while Chrome 149 on the same device works. Below the threshold we
     *  skip the embedded WebView and open the room in the external browser. */
    private static final int MIN_WEBVIEW_MAJOR = 120;

    /** Overlay hook: surface call-state changes (calling/connecting/in-call/ended). */
    public interface Host {
        void onVoiceState(String state, String detail);
        /** Live participant roster (comma-separated peer ids, includes self). */
        void onVoiceRoster(String idsCsv);
        /** Optional id→nickname map (JSON object) for the roster, so code-room /
         *  3.7.5 peers (who carry a self-chosen name rather than a known SteamID)
         *  can be labelled by name instead of "Guest". */
        void onVoiceRosterNames(String namesJson);
    }

    private final Activity act;
    private final String roomId;
    private final String selfId;       // self peer id: SteamID64 string or a client id
    private final String peerId;       // fixed 1:1 peer (Steam); "" for code-room mesh
    private final String displayName;  // roster name (nickname/persona); "" = none
    private final long peerSteamId;    // numeric peer SteamID (0 in code-room mode)
    private final Host host;
    private WebView web;
    private boolean webAttached;
    private boolean muted;
    private volatile boolean ended;
    private String roomUrl;            // the /voice/room URL for this call
    private boolean fellBackToBrowser; // already escalated to the external browser

    /** Steam 1:1 / party call: room id = sorted SteamID pair, self/peer = SteamID64. */
    public BhVoiceController(Activity act, String roomId, long selfSteamId, long peerSteamId, Host host) {
        this(act, roomId, String.valueOf(selfSteamId), String.valueOf(peerSteamId), "", peerSteamId, host);
    }

    /** Room-code call: a shared code, a stable client id, and a display name shown
     *  in everyone's roster. No fixed peer — the page discovers peers via the
     *  roster (full mesh). This is the BannerHub-3.7.5-compatible path. */
    public BhVoiceController(Activity act, String roomCode, String selfId, String displayName, Host host) {
        this(act, roomCode, selfId, "", displayName == null ? "" : displayName, 0L, host);
    }

    private BhVoiceController(Activity act, String roomId, String selfId, String peerId,
                             String displayName, long peerSteamId, Host host) {
        this.act = act;
        this.roomId = roomId;
        this.selfId = selfId;
        this.peerId = peerId == null ? "" : peerId;
        this.displayName = displayName == null ? "" : displayName;
        this.peerSteamId = peerSteamId;
        this.host = host;
    }

    public long friendSteamId() { return peerSteamId; }

    /** Begin the call: attach the WebView and load the hosted room page, which
     *  opens the mic and drives the SDP/ICE handshake itself. The page reports
     *  state back via the {@code BhVoice} bridge. Must run on the UI thread. */
    @SuppressWarnings({"SetJavaScriptEnabled"})
    public void start() {
        // peer is sent only for a fixed 1:1 Steam call; a code-room call omits it
        // and joins the mesh purely via the roster. name carries the roster label.
        StringBuilder u = new StringBuilder(BASE)
                .append("/voice/room?room=").append(enc(roomId))
                .append("&self=").append(enc(selfId));
        if (!peerId.isEmpty()) u.append("&peer=").append(enc(peerId));
        if (!displayName.isEmpty()) u.append("&name=").append(enc(displayName));
        roomUrl = u.toString();

        // Some devices ship an ancient System WebView (e.g. 113) whose embedded
        // WebRTC can't open the mic — getUserMedia hangs forever. Detect that up
        // front and run the call in the external browser instead.
        int wv = webViewMajor();
        Log.i(TAG, "voice: webview major=" + wv + " (min " + MIN_WEBVIEW_MAJOR + ")");
        if (wv > 0 && wv < MIN_WEBVIEW_MAJOR) {
            Log.i(TAG, "voice: webview too old, opening call in browser");
            openInBrowser();
            return;
        }

        try {
            web = new WebView(act);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setDomStorageEnabled(true);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(final PermissionRequest req) {
                    // Grant the page's mic request (app-level RECORD_AUDIO is
                    // requested separately before a call starts).
                    act.runOnUiThread(new Runnable() { public void run() {
                        try { req.grant(req.getResources()); } catch (Throwable ignored) {}
                    }});
                }
            });
            web.addJavascriptInterface(new Bridge(), "BhVoice");
            // The WebView MUST be attached to a window or Chromium backgrounds the
            // page and getUserMedia never resolves; attach it 1×1 and invisible.
            attachHeadless();
            web.loadUrl(roomUrl);
        } catch (Throwable t) {
            Log.w(TAG, "voice start failed", t);
            host.onVoiceState("ended", "init failed");
            cleanup();
        }
    }

    /** Hand the call off to the device's default browser (which uses an
     *  up-to-date Chromium that can open the mic). Used when the embedded
     *  WebView is too old, or as a backstop when its mic request times out. */
    private void openInBrowser() {
        if (fellBackToBrowser) return;
        fellBackToBrowser = true;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            host.onVoiceState("external", "");
        } catch (Throwable t) {
            Log.w(TAG, "voice browser fallback failed", t);
            host.onVoiceState("ended", "no browser for voice");
        }
        cleanup();
    }

    /** Major version of the active System WebView (Chromium), or -1 if unknown. */
    private int webViewMajor() {
        try {
            PackageInfo pi = WebView.getCurrentWebViewPackage();
            if (pi == null || pi.versionName == null) return -1;
            String v = pi.versionName.trim();
            int dot = v.indexOf('.');
            return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v);
        } catch (Throwable t) {
            return -1;
        }
    }

    public void setMuted(boolean m) {
        muted = m;
        runJs("bhSetMuted(" + (m ? "true" : "false") + ")");
        host.onVoiceState("in-call", m ? "muted" : "");
    }

    public boolean isMuted() { return muted; }

    public void hangup() {
        if (ended) return;
        runJs("bhHangup()");   // the page posts a bye into the peer's mailbox
        host.onVoiceState("ended", "");
        cleanup();
    }

    /** Add the WebView to the window as a 1×1, transparent, non-interactive panel
     *  so Chromium treats the page as foreground and mic capture can resolve.
     *  Same WindowManager technique as the chat overlay. UI thread. */
    private void attachHeadless() {
        try {
            WindowManager wm = act.getWindowManager();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(web, lp);
            webAttached = true;
        } catch (Throwable t) {
            Log.w(TAG, "voice webview attach failed", t);
        }
    }

    private void cleanup() {
        ended = true;
        final WebView w = web;
        final boolean wasAttached = webAttached;
        web = null;
        webAttached = false;
        if (w == null) return;
        act.runOnUiThread(new Runnable() { public void run() {
            if (wasAttached) {
                try { act.getWindowManager().removeView(w); } catch (Throwable ignored) {}
            }
            try { w.loadUrl("about:blank"); w.removeAllViews(); w.destroy(); } catch (Throwable ignored) {}
        }});
    }

    private void runJs(final String js) {
        final WebView w = web;
        if (w == null) return;
        act.runOnUiThread(new Runnable() { public void run() {
            try { w.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        }});
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return s; }
    }

    // ── JS → Java bridge ──────────────────────────────────────────────────────
    private final class Bridge {
        /** Page lifecycle: calling / connecting / in-call / failed / ended. */
        @JavascriptInterface public void state(String st, String detail) {
            if ("in-call".equals(st)) host.onVoiceState("in-call", detail == null ? "" : detail);
            else if ("failed".equals(st)) {
                // If the embedded mic capture failed/timed out, the WebView's
                // WebRTC is the culprit — retry the whole call in the browser
                // rather than just giving up.
                String d = detail == null ? "" : detail;
                if (!fellBackToBrowser && d.toLowerCase().contains("mic")) {
                    act.runOnUiThread(new Runnable() { public void run() { openInBrowser(); } });
                } else {
                    host.onVoiceState("ended", d.isEmpty() ? "failed" : d); cleanup();
                }
            }
            else if ("ended".equals(st)) { host.onVoiceState("ended", detail == null ? "" : detail); cleanup(); }
            else host.onVoiceState(st, detail == null ? "" : detail);
        }
        /** Live roster from the mesh page: comma-separated peer ids (incl. self). */
        @JavascriptInterface public void roster(String idsCsv) {
            host.onVoiceRoster(idsCsv == null ? "" : idsCsv);
        }
        /** id→nickname map (JSON) so the call box can label code-room / 3.7.5 peers
         *  by their chosen name rather than as a bare id. */
        @JavascriptInterface public void rosterNames(String json) {
            host.onVoiceRosterNames(json == null ? "" : json);
        }
        @JavascriptInterface public void log(String m) { Log.i(TAG, "voicejs: " + m); }
    }
}
