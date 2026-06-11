package com.xj.winemu.steamchat;

import android.app.Activity;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebSettings;

import org.json.JSONObject;

/**
 * WebRTC voice call for the in-game Steam chat overlay (Option C: peer-to-peer
 * audio, signalled over Steam chat).
 *
 * <p>Injecting a native WebRTC {@code .so} into a patched APK is fragile, so the
 * call runs inside a headless {@link WebView} — Android's System WebView already
 * ships a full Chromium WebRTC stack. A tiny embedded HTML page does
 * {@code getUserMedia(audio)} + {@code RTCPeerConnection}; a {@link JavascriptInterface}
 * bridges it to Java. The SDP/ICE handshake is relayed by Java as <b>hidden</b>
 * Steam chat messages (see {@link BhSteamChatOverlay}'s signal interception), so
 * no signalling server is needed. TURN/STUN come from the page config; STUN-only
 * works on most networks, Cloudflare TURN drops in later.
 *
 * <p>One controller per active call. Lifecycle:
 * {@code new → start(caller) → (onSignal …) → hangup()}.
 */
public final class BhVoiceController {

    private static final String TAG = "BhSteamChat";

    /** Zero-width-marked prefix identifying a voice-signalling chat message, so
     *  it never shows as text and is trivially recognised by the receiver. */
    public static final String SIG_PREFIX = "⁣⁣BHVOICE1⁣";

    /** Overlay hooks: send a signalling line out over Steam chat, and surface
     *  call-state changes (ringing/connecting/in-call/ended) on the UI. */
    public interface Host {
        void sendVoiceSignal(String body);     // body already includes SIG_PREFIX
        void onVoiceState(String state, String detail);
    }

    private final Activity act;
    private final long friendSteamId;
    private final Host host;
    private WebView web;
    private boolean caller;
    private boolean muted;
    private volatile boolean ended;

    public BhVoiceController(Activity act, long friendSteamId, Host host) {
        this.act = act;
        this.friendSteamId = friendSteamId;
        this.host = host;
    }

    public long friendSteamId() { return friendSteamId; }

    /** Begin the call. {@code caller=true} sends the offer; the callee waits for
     *  the offer to arrive via {@link #onSignal}. Must run on the UI thread. */
    @SuppressWarnings({"SetJavaScriptEnabled"})
    public void start(boolean caller) {
        this.caller = caller;
        host.onVoiceState(caller ? "calling" : "connecting", "");
        try {
            web = new WebView(act);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setDomStorageEnabled(true);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onPermissionRequest(final PermissionRequest req) {
                    // Grant the page's mic request (the app-level RECORD_AUDIO is
                    // requested separately before a call starts).
                    act.runOnUiThread(new Runnable() { public void run() {
                        try { req.grant(req.getResources()); } catch (Throwable ignored) {}
                    }});
                }
            });
            web.addJavascriptInterface(new Bridge(), "BhVoice");
            web.loadDataWithBaseURL("https://localhost/", page(caller),
                    "text/html", "utf-8", null);
        } catch (Throwable t) {
            Log.w(TAG, "voice start failed", t);
            host.onVoiceState("ended", "init failed");
            cleanup();
        }
    }

    /** Feed an inbound signalling payload (already stripped of {@link #SIG_PREFIX})
     *  to the page. Safe to call before start() resolves — calls are queued by JS. */
    public void onSignal(final String json) {
        if (ended) return;
        runJs("bhOnSignal(" + jsString(json) + ")");
    }

    public void setMuted(boolean m) {
        muted = m;
        runJs("bhSetMuted(" + (m ? "true" : "false") + ")");
        host.onVoiceState("in-call", m ? "muted" : "");
    }

    public boolean isMuted() { return muted; }

    public void hangup() {
        if (ended) return;
        // Tell the peer, then tear down.
        try { host.sendVoiceSignal(SIG_PREFIX + b64("{\"t\":\"bye\"}")); } catch (Throwable ignored) {}
        runJs("bhHangup()");
        host.onVoiceState("ended", "");
        cleanup();
    }

    /** Peer hung up (received a bye); tear down without re-sending bye. */
    public void remoteHangup() {
        if (ended) return;
        runJs("bhHangup()");
        host.onVoiceState("ended", "peer left");
        cleanup();
    }

    private void cleanup() {
        ended = true;
        final WebView w = web;
        web = null;
        if (w == null) return;
        act.runOnUiThread(new Runnable() { public void run() {
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

    // ── JS → Java bridge ──────────────────────────────────────────────────────
    private final class Bridge {
        /** The page emits each SDP/ICE blob here; we forward it over Steam chat. */
        @JavascriptInterface public void signal(String json) {
            try { host.sendVoiceSignal(SIG_PREFIX + b64(json)); } catch (Throwable ignored) {}
        }
        /** Page lifecycle: connecting / connected / failed / closed. */
        @JavascriptInterface public void state(String st, String detail) {
            if ("connected".equals(st)) host.onVoiceState("in-call", "");
            else if ("failed".equals(st)) { host.onVoiceState("ended", detail == null ? "failed" : detail); cleanup(); }
            else if ("closed".equals(st)) { host.onVoiceState("ended", ""); cleanup(); }
            else host.onVoiceState(st, detail == null ? "" : detail);
        }
        @JavascriptInterface public void log(String m) { Log.i(TAG, "voicejs: " + m); }
    }

    // ── embedded WebRTC page ──────────────────────────────────────────────────
    private static String page(boolean caller) {
        // STUN-only for the spike; a TURN entry can be appended once Cloudflare
        // Realtime is enabled. The page is symmetric: the caller creates the
        // offer, the callee answers on receiving it.
        return "<!doctype html><html><head><meta charset=utf-8></head><body>"
            + "<audio id=a autoplay></audio><script>\n"
            + "var ICE=[{urls:['stun:stun.l.google.com:19302','stun:stun1.l.google.com:19302']}];\n"
            + "var CALLER=" + (caller ? "true" : "false") + ";\n"
            + "var pc,localStream,pending=[];\n"
            + "function log(m){try{BhVoice.log(''+m)}catch(e){}}\n"
            + "function send(o){try{BhVoice.signal(JSON.stringify(o))}catch(e){}}\n"
            + "function st(s,d){try{BhVoice.state(s,d||'')}catch(e){}}\n"
            + "async function init(){\n"
            + " try{localStream=await navigator.mediaDevices.getUserMedia({audio:true,video:false});}\n"
            + " catch(e){st('failed','mic '+e);return;}\n"
            + " pc=new RTCPeerConnection({iceServers:ICE});\n"
            + " localStream.getTracks().forEach(function(t){pc.addTrack(t,localStream);});\n"
            + " pc.onicecandidate=function(e){if(e.candidate)send({t:'ice',c:e.candidate});};\n"
            + " pc.ontrack=function(e){var a=document.getElementById('a');if(a.srcObject!==e.streams[0]){a.srcObject=e.streams[0];}};\n"
            + " pc.onconnectionstatechange=function(){var s=pc.connectionState;log('pc '+s);"
            + "  if(s==='connected')st('connected');else if(s==='failed')st('failed','ice');"
            + "  else if(s==='disconnected'||s==='closed')st('closed');};\n"
            + " while(pending.length){await handle(pending.shift());}\n"
            + " if(CALLER){var off=await pc.createOffer();await pc.setLocalDescription(off);send({t:'offer',sdp:off.sdp});}\n"
            + "}\n"
            + "async function handle(m){\n"
            + " if(!pc){pending.push(m);return;}\n"
            + " try{\n"
            + "  if(m.t==='offer'){await pc.setRemoteDescription({type:'offer',sdp:m.sdp});"
            + "   var an=await pc.createAnswer();await pc.setLocalDescription(an);send({t:'answer',sdp:an.sdp});}\n"
            + "  else if(m.t==='answer'){await pc.setRemoteDescription({type:'answer',sdp:m.sdp});}\n"
            + "  else if(m.t==='ice'){try{await pc.addIceCandidate(m.c);}catch(e){log('ice add '+e);}}\n"
            + " }catch(e){log('handle '+e);st('failed',''+e);}\n"
            + "}\n"
            + "window.bhOnSignal=function(s){var m;try{m=JSON.parse(s);}catch(e){return;}"
            + " if(!pc&&m.t!=='offer'&&!CALLER){pending.push(m);}else{handle(m);}};\n"
            + "window.bhSetMuted=function(m){if(localStream)localStream.getAudioTracks().forEach(function(t){t.enabled=!m;});};\n"
            + "window.bhHangup=function(){try{if(pc)pc.close();}catch(e){}try{if(localStream)localStream.getTracks().forEach(function(t){t.stop();});}catch(e){}};\n"
            + "init();\n"
            + "</script></body></html>";
    }

    // ── small helpers ─────────────────────────────────────────────────────────
    static String b64(String s) {
        try { return android.util.Base64.encodeToString(s.getBytes("UTF-8"), android.util.Base64.NO_WRAP); }
        catch (Throwable t) { return ""; }
    }
    static String unb64(String s) {
        try { return new String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP), "UTF-8"); }
        catch (Throwable t) { return ""; }
    }
    /** JSON-string-escape for safe injection into evaluateJavascript. */
    private static String jsString(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append("\"").toString();
    }
}
