package com.xj.winemu.steamchat;

import android.app.Activity;
import android.graphics.PixelFormat;
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
 * the worker's R2-backed mailbox (room id = sorted SteamID pair) and STUN/TURN
 * from {@code /voice/turn}. Java only attaches the WebView, surfaces call state
 * to the overlay through the {@code BhVoice} JS bridge, and can mute / hang up.
 * No Steam-chat signalling is involved.
 */
public final class BhVoiceController {

    private static final String TAG = "BhSteamChat";
    private static final String BASE = "https://bannerhub-api.the412banner.workers.dev";

    /** Overlay hook: surface call-state changes (calling/connecting/in-call/ended). */
    public interface Host {
        void onVoiceState(String state, String detail);
    }

    private final Activity act;
    private final String roomId;
    private final long selfSteamId;
    private final long peerSteamId;
    private final Host host;
    private WebView web;
    private boolean webAttached;
    private boolean muted;
    private volatile boolean ended;

    public BhVoiceController(Activity act, String roomId, long selfSteamId, long peerSteamId, Host host) {
        this.act = act;
        this.roomId = roomId;
        this.selfSteamId = selfSteamId;
        this.peerSteamId = peerSteamId;
        this.host = host;
    }

    public long friendSteamId() { return peerSteamId; }

    /** Begin the call: attach the WebView and load the hosted room page, which
     *  opens the mic and drives the SDP/ICE handshake itself. The page reports
     *  state back via the {@code BhVoice} bridge. Must run on the UI thread. */
    @SuppressWarnings({"SetJavaScriptEnabled"})
    public void start() {
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
            String url = BASE + "/voice/room?room=" + enc(roomId)
                    + "&self=" + selfSteamId + "&peer=" + peerSteamId;
            web.loadUrl(url);
        } catch (Throwable t) {
            Log.w(TAG, "voice start failed", t);
            host.onVoiceState("ended", "init failed");
            cleanup();
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
            else if ("failed".equals(st)) { host.onVoiceState("ended", detail == null ? "failed" : detail); cleanup(); }
            else if ("ended".equals(st)) { host.onVoiceState("ended", detail == null ? "" : detail); cleanup(); }
            else host.onVoiceState(st, detail == null ? "" : detail);
        }
        @JavascriptInterface public void log(String m) { Log.i(TAG, "voicejs: " + m); }
    }
}
