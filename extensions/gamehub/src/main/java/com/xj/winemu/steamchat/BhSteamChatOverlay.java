package com.xj.winemu.steamchat;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.WeakHashMap;

/**
 * In-game Steam friends/chat overlay.
 *
 * A Banner-owned classic-View pill + slide-out panel attached over the Wine
 * game surface (identical WindowManager technique to {@code BhPerfOverlay}),
 * gated by the Banner Tools → Steam Chat master toggle. The panel pulls your
 * Steam friends list (and, on tap, a friend's recent message history) from the
 * in-process Steam client via {@link BhSteamBridge}, and can send replies
 * (friends.send_message). Delivery is request/response; incoming messages show
 * on the next history refresh — live push (steam:chat-message Flow) is a later
 * increment.
 *
 * Hooked from WineActivity.onResume -> attach / onDestroy -> detach.
 */
public final class BhSteamChatOverlay {

    private static final String TAG = "BhSteamChat";

    private static final int COL_PANEL_BG = 0xF21A1D24;
    private static final int COL_PILL_BG  = 0xF22A2E38;
    private static final int COL_ACCENT   = 0xFF66C0F4; // Steam blue
    private static final int COL_ONLINE   = 0xFF57CBDE;
    private static final int COL_INGAME   = 0xFF90BA3C; // Steam green
    private static final int COL_OFFLINE  = 0xFF6A707C;
    private static final int COL_TEXT     = 0xFFEFEFEF;
    private static final int COL_SUBTEXT  = 0xFF9AA0AC;
    private static final int COL_BUBBLE_THEM = 0xFF2A2E38; // incoming bubble (dark)
    private static final int COL_BUBBLE_ME   = 0xFF24506E; // outgoing bubble (Steam blue)
    private static final int COL_FAILED      = 0xFFE05B5B; // failed-send label

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMG_IO = Executors.newFixedThreadPool(2);
    private static final java.util.regex.Pattern URL_RE =
            java.util.regex.Pattern.compile("https?://[^\\s\\]\\[\"']+");
    // SteamID64 for individual accounts: 17 digits beginning 7656119… .
    private static final java.util.regex.Pattern STEAMID64_RE =
            java.util.regex.Pattern.compile("\"steamId\"\\s*:\\s*\"?(7656\\d{13})\"?");
    // Invite-family BBCode tags, exactly the set GameHub's own chat parser
    // special-cases (kgj). An invite message's plainMessage is empty, which is
    // why these used to render as blank bubbles.
    private static final java.util.regex.Pattern INVITE_TAG_RE = java.util.regex.Pattern.compile(
            "\\[(gameinvite|lobbyinvite|lobbyinviteconnectstring|remoteplaytogetherinvite"
            + "|playtestinvite|broadcastinvite|broadcastviewrequest|tradeoffer|inviteurl|invite)\\b([^\\]]*)\\]",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TAG_ATTR_RE = java.util.regex.Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"?([^\"\\]\\s]+)\"?");
    private static final java.util.regex.Pattern STICKER_RE = java.util.regex.Pattern.compile(
            "\\[sticker\\b([^\\]]*)\\](?:([^\\[]*)\\[/sticker\\])?", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern EMOTICON_RE = java.util.regex.Pattern.compile(
            "\\[emoticon\\]([^\\[]+)\\[/emoticon\\]", java.util.regex.Pattern.CASE_INSENSITIVE);
    // Same CDN endpoints GameHub's chat renderer uses for sticker/emoticon art.
    private static final String STICKER_CDN  = "https://community.fastly.steamstatic.com/economy/sticker/";
    private static final String EMOTICON_CDN = "https://community.fastly.steamstatic.com/economy/emoticon/";
    private static final String APP_HEADER_CDN = "https://cdn.cloudflare.steamstatic.com/steam/apps/";
    // appId → {gameName, headerImageUrl} resolved via apps.app_details, session cache.
    private static final java.util.HashMap<Integer, String[]> sAppInfo = new java.util.HashMap<>();

    private static final WeakHashMap<Activity, Controller> sOverlays = new WeakHashMap<>();

    private BhSteamChatOverlay() {}

    // ── lifecycle (called from the WineActivity hooks) ───────────────────────

    public static void attach(final Activity activity) {
        if (activity == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(new Runnable() { public void run() { attach(activity); } });
            return;
        }
        try {
            boolean enabled = BhSteamChatController.get().isEnabled(activity);
            Controller existing = sOverlays.get(activity);
            if (!enabled) {
                if (existing != null) { existing.detach(); sOverlays.remove(activity); }
                return;
            }
            if (existing != null && existing.attached) return;

            final Controller c = new Controller(activity);
            View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            if (decor == null) { Log.w(TAG, "attach: no decor"); return; }

            // Decor's window token isn't valid until ActivityThread adds it AFTER
            // onResume; defer the add until the token exists (same as BhPerfOverlay).
            final Runnable doAttach = new Runnable() {
                public void run() {
                    Controller cur = sOverlays.get(activity);
                    if (cur != null && cur.attached) return;
                    if (c.attachToWindow()) sOverlays.put(activity, c);
                }
            };
            if (decor.getWindowToken() != null) doAttach.run();
            else decor.post(doAttach);
        } catch (Throwable t) {
            Log.w(TAG, "attach failed", t);
        }
    }

    public static void detach(final Activity activity) {
        if (activity == null) return;
        Runnable r = new Runnable() {
            public void run() {
                try {
                    Controller c = sOverlays.remove(activity);
                    if (c != null) c.detach();
                } catch (Throwable ignored) {}
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else activity.runOnUiThread(r);
    }

    // ── controller ───────────────────────────────────────────────────────────

    private static final class Controller implements BhVoiceController.Host, BhVoiceCallBox.Actions {
        private final Activity act;
        private WindowManager wm;
        private WindowManager.LayoutParams lp;
        private LinearLayout container;  // [panel][pill]
        private LinearLayout panel;
        private TextView pill;
        private TextView status;
        private TextView backRow;        // pinned "‹ Back to friends" (conversation view only)
        private LinearLayout listCol;    // friend rows / message rows
        private ScrollView scroll;       // the list scroller (for auto-scroll-to-bottom)
        private EditText composerInput;  // current composer field (null on friends list)
        private String draft = "";       // composer text, preserved across silent refreshes
        private boolean composerWasFocused; // composer focus state, preserved across refreshes
        private boolean opacityExpanded = false; // opacity slider collapsed by default
        private boolean expanded = false;
        private boolean attached = false;
        private long openFriendId = 0;   // 0 = showing friends list
        private Object chatSub;          // live steam:chat-message subscription handle
        private Object typingSub;        // live steam:chat-typing subscription handle
        private long lastTypingSentMs;   // throttle for our own friends.send_typing
        private BhVoiceController voice;  // active WebRTC voice call (null when idle)
        private String pendingRoom;       // pairRoom of a ringing incoming call (null = none)
        private long pendingOfferPeer;    // peer SteamID of the ringing incoming call
        private String pendingPeerName;   // display name of the ringing caller
        private volatile boolean lobbyPolling; // ring-inbox poll loop active
        private Thread lobbyThread;       // dedicated thread for the ring poll
        private BhVoiceCallBox callBox;   // standalone movable call window (null when idle)
        private long callPeer;            // peer SteamID of the active/dialing call
        private String callPeerName;      // display name of the active/dialing call peer
        private boolean callMuted;        // local mic muted in the active call
        private boolean callConnected;    // true once WebRTC reports in-call (timer started)
        // Reverts the "is typing…" status if no message follows within Steam's
        // ~15s typing-notification window.
        private final Runnable clearTyping = new Runnable() {
            public void run() {
                if (openFriendId != 0) setStatus("Chat with " + currentTitle);
            }
        };

        Controller(Activity a) { this.act = a; }

        boolean attachToWindow() {
            try {
                wm = act.getWindowManager();
                if (wm == null) return false;
                IBinder token = act.getWindow() != null && act.getWindow().getDecorView() != null
                        ? act.getWindow().getDecorView().getWindowToken() : null;
                if (token == null) { Log.w(TAG, "attachToWindow: token null"); return false; }

                container = new LinearLayout(act);
                container.setOrientation(LinearLayout.HORIZONTAL);
                container.setGravity(Gravity.CENTER_VERTICAL);
                buildPanel();
                buildPill();

                lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;  // right edge, like the ⚡ perf pill
                lp.token = token;
                lp.y = BhSteamChatController.get().getPillY(act, dp(180));
                // Pan the window up when the soft keyboard shows so the composer stays visible.
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

                wm.addView(container, lp);
                attached = true;
                startLobbyPoll();   // listen for incoming voice rings while attached
                Log.i(TAG, "steam chat overlay attached");
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "attachToWindow failed", t);
                return false;
            }
        }

        void detach() {
            if (!attached) return;
            attached = false;
            stopLobbyPoll();
            try { if (voice != null) { voice.hangup(); voice = null; } } catch (Throwable ignored) {}
            try { if (callBox != null) { callBox.close(); callBox = null; } } catch (Throwable ignored) {}
            try { BhSteamBridge.unlisten(chatSub); } catch (Throwable ignored) {}
            chatSub = null;
            try { BhSteamBridge.unlisten(typingSub); } catch (Throwable ignored) {}
            typingSub = null;
            MAIN.removeCallbacks(clearTyping);
            try { if (wm != null && container != null) wm.removeView(container); } catch (Throwable ignored) {}
        }

        // ── views ────────────────────────────────────────────────────────────

        private void buildPill() {
            pill = new TextView(act);
            pill.setText("💬");
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(10), dp(14), dp(10), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PILL_BG);
            // Rounded on the inner (left) edge, flat against the right screen edge.
            bg.setCornerRadii(new float[]{dp(14),dp(14), 0,0, 0,0, dp(14),dp(14)});
            pill.setBackground(bg);
            pill.setAlpha(opacityFraction());
            pill.setOnTouchListener(new PillTouch());
            container.addView(pill);
        }

        /** Stored pill opacity as an alpha fraction (0.05..1.0). */
        private float opacityFraction() {
            return BhSteamChatController.get().getPillOpacity(act) / 100f;
        }

        private void buildPanel() {
            panel = new LinearLayout(act);
            panel.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PANEL_BG);
            bg.setCornerRadius(dp(14));
            panel.setBackground(bg);
            panel.setPadding(dp(14), dp(12), dp(14), dp(14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT);
            panel.setLayoutParams(lp);

            // header row: title + refresh
            LinearLayout header = new LinearLayout(act);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(act);
            title.setText("Steam · Friends");
            title.setTextColor(COL_ACCENT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            title.setLayoutParams(tlp);
            header.addView(title);
            TextView refresh = new TextView(act);
            refresh.setText("↻"); // ↻
            refresh.setTextColor(COL_TEXT);
            refresh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            refresh.setPadding(dp(8), dp(2), dp(4), dp(2));
            refresh.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (openFriendId == 0) loadFriends(); else loadHistory(openFriendId, currentTitle); }
            });
            header.addView(refresh);
            panel.addView(header);

            status = new TextView(act);
            status.setTextColor(COL_SUBTEXT);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            status.setPadding(0, dp(4), 0, dp(8));
            status.setText("Steam friends");
            panel.addView(status);

            // Voice calls render in a standalone movable window (BhVoiceCallBox),
            // not inside the chat panel, so a call/incoming prompt surfaces over
            // the game even when the chat pill is collapsed.

            // Pinned back affordance: lives in the panel's fixed zone (above the
            // ScrollView) so it never scrolls away while a conversation is open.
            // Shown only in the conversation view; hidden on the friends list.
            backRow = new TextView(act);
            backRow.setText("‹ Back to friends");
            backRow.setTextColor(COL_ACCENT);
            backRow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            backRow.setPadding(0, dp(2), 0, dp(8));
            backRow.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loadFriends(); } });
            backRow.setVisibility(View.GONE);
            panel.addView(backRow);

            scroll = new ScrollView(act);
            // Cap the list height so the panel (header + list + composer-in-list +
            // opacity row, even when the opacity slider is expanded) fits within the
            // screen instead of running off the bottom. Reserve room for the chrome
            // + an expanded opacity slider.
            int screenH = act.getResources().getDisplayMetrics().heightPixels;
            int listH = Math.max(dp(140), Math.min(dp(320), screenH - dp(210)));
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, listH));
            listCol = new LinearLayout(act);
            listCol.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(listCol);
            panel.addView(scroll);

            panel.addView(buildOpacityRow());

            panel.setVisibility(View.GONE);
            container.addView(panel);
        }

        // pill-opacity slider (mirrors the perf overlay), collapsed behind an
        // arrow header at the panel's bottom so it only takes space when wanted.
        private View buildOpacityRow() {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(0, dp(10), 0, dp(2));

            View div = new View(act);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dlp.bottomMargin = dp(8);
            div.setLayoutParams(dlp);
            div.setBackgroundColor(0x14FFFFFF);
            col.addView(div);

            final int pct = BhSteamChatController.get().getPillOpacity(act);

            // Collapsible header: "▸ Pill opacity — N%" toggles the slider box.
            final TextView header = new TextView(act);
            header.setTextColor(COL_SUBTEXT);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            header.setPadding(0, dp(2), 0, dp(2));
            col.addView(header);

            final LinearLayout sliderBox = new LinearLayout(act);
            sliderBox.setOrientation(LinearLayout.VERTICAL);
            sliderBox.setVisibility(opacityExpanded ? View.VISIBLE : View.GONE);

            final TextView label = new TextView(act);
            label.setText("Drag to fade the pill");
            label.setTextColor(COL_TEXT);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sliderBox.addView(label);

            header.setText((opacityExpanded ? "▾  " : "▸  ") + "Pill opacity — " + pct + "%");
            header.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    opacityExpanded = !opacityExpanded;
                    sliderBox.setVisibility(opacityExpanded ? View.VISIBLE : View.GONE);
                    int p = BhSteamChatController.get().getPillOpacity(act);
                    header.setText((opacityExpanded ? "▾  " : "▸  ") + "Pill opacity — " + p + "%");
                    if (opacityExpanded) fitPanelOnScreen();  // keep the slider on-screen
                }
            });

            final int min = BhSteamChatController.PILL_OPACITY_MIN;
            final android.widget.SeekBar bar = new android.widget.SeekBar(act);
            // Map slider 0..(100-MIN) onto opacity MIN..100 so the pill can fade
            // to nearly invisible but never fully vanish.
            bar.setMax(100 - min);
            bar.setProgress(pct - min);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(2);
            bar.setLayoutParams(blp);
            bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(android.widget.SeekBar sb, int progress, boolean fromUser) {
                    int p = progress + min;
                    header.setText("▾  Pill opacity — " + p + "%");
                    if (pill != null) pill.setAlpha(p / 100f);  // live preview
                }
                public void onStartTrackingTouch(android.widget.SeekBar sb) {}
                public void onStopTrackingTouch(android.widget.SeekBar sb) {
                    BhSteamChatController.get().setPillOpacity(act, sb.getProgress() + min);
                }
            });
            sliderBox.addView(bar);
            col.addView(sliderBox);
            return col;
        }

        // pill drag (vertical move) + tap (expand) ----------------------------
        private final class PillTouch implements View.OnTouchListener {
            private float downRawY;
            private int downY;
            private boolean dragged;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downRawY = e.getRawY();
                        downY = lp.y;
                        dragged = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int dy = (int) (e.getRawY() - downRawY);
                        if (Math.abs(dy) > dp(6)) dragged = true;
                        int ny = downY + dy;
                        if (ny < 0) ny = 0;
                        int max = act.getResources().getDisplayMetrics().heightPixels
                                - container.getHeight();
                        if (max > 0 && ny > max) ny = max;
                        lp.y = ny;
                        try { if (wm != null && attached) wm.updateViewLayout(container, lp); }
                        catch (Throwable ignored) {}
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (dragged) BhSteamChatController.get().setPillY(act, lp.y);
                        else setExpanded(!expanded);
                        return true;
                    default:
                        return false;
                }
            }
        }

        private String currentTitle = "";
        private String lastFriendsJson;
        private boolean offlineCollapsed = true;
        // friendSteamId → unread count, from friends.conversation_summaries
        private final java.util.HashMap<Long, Integer> unreadByFriend = new java.util.HashMap<>();
        // Local user's SteamID64 (for mark_conversation_read); 0 until resolved.
        private volatile long localSteamId = 0;

        private void setExpanded(boolean exp) {
            expanded = exp;
            panel.setVisibility(exp ? View.VISIBLE : View.GONE);
            // Full opacity while open (easy to grab), faded back when collapsed.
            if (pill != null) pill.setAlpha(exp ? 1f : opacityFraction());
            // Take key/IME focus only while the panel is open, so the composer's
            // EditText can receive text; collapse hands input back to the game.
            setWindowFocusable(exp);
            if (exp) ensureChatSubscription();
            if (exp && listCol.getChildCount() == 0) loadFriends();
            if (exp) fitPanelOnScreen();
        }

        /** After the panel lays out, nudge the whole overlay window up if its
         *  bottom would fall off-screen, so the bottom controls (composer area,
         *  the opacity slider when expanded) stay visible regardless of where the
         *  pill was dragged. Does not persist the pill's stored Y. */
        private void fitPanelOnScreen() {
            if (container == null) return;
            container.post(new Runnable() {
                public void run() {
                    if (!attached || wm == null || lp == null) return;
                    int screenH = act.getResources().getDisplayMetrics().heightPixels;
                    int h = container.getHeight();
                    if (h <= 0) return;
                    int maxY = Math.max(0, screenH - h);
                    if (lp.y > maxY) {
                        lp.y = maxY;
                        try { wm.updateViewLayout(container, lp); } catch (Throwable ignored) {}
                    }
                }
            });
        }

        /** Subscribe once to live chat messages; reload the open thread when one arrives for it. */
        private void ensureChatSubscription() {
            if (chatSub == null) {
                chatSub = BhSteamBridge.listen("steam:chat-message", new BhSteamBridge.EventListener() {
                    public void onEvent(final String payloadJson) {
                        long who = 0; String dir = ""; String body = "";
                        try {
                            JSONObject o = new JSONObject(payloadJson);
                            who = o.optLong("friendSteamId", o.optLong("steamId", o.optLong("senderSteamId", 0)));
                            dir = o.optString("direction", "");
                            body = firstNonEmpty(o.optString("message"), o.optString("plainMessage"), o.optString("rawMessage"));
                        } catch (Throwable ignored) {}
                        final long from = who;
                        post(new Runnable() {
                            public void run() {
                                // Only refresh if this message belongs to the conversation on screen.
                                // Silent: no "Loading…" flash, keep the user's draft, auto-scroll.
                                if (expanded && openFriendId != 0 && (from == 0 || from == openFriendId)) {
                                    loadHistory(openFriendId, currentTitle, true);
                                }
                            }
                        });
                    }
                });
            }
            // SteamChatTypingDto carries only {friendSteamId}; surface it on the
            // status line for the open conversation and let it lapse after the
            // ~15s window Steam clients use when no message follows.
            if (typingSub == null) {
                typingSub = BhSteamBridge.listen("steam:chat-typing", new BhSteamBridge.EventListener() {
                    public void onEvent(final String payloadJson) {
                        long who = 0;
                        try { who = new JSONObject(payloadJson).optLong("friendSteamId", 0); }
                        catch (Throwable ignored) {}
                        final long from = who;
                        post(new Runnable() {
                            public void run() {
                                if (expanded && openFriendId != 0 && from == openFriendId) {
                                    setStatus(currentTitle + " is typing…");
                                    MAIN.removeCallbacks(clearTyping);
                                    MAIN.postDelayed(clearTyping, 15000);
                                }
                            }
                        });
                    }
                });
            }
        }

        /** Toggle FLAG_NOT_FOCUSABLE on the live overlay window. */
        private void setWindowFocusable(boolean focusable) {
            if (wm == null || lp == null || container == null || !attached) return;
            int flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            int updated = focusable ? (lp.flags & ~flag) : (lp.flags | flag);
            if (updated == lp.flags) return;
            lp.flags = updated;
            try { wm.updateViewLayout(container, lp); } catch (Throwable ignored) {}
        }

        // ── data ──────────────────────────────────────────────────────────────

        private void loadFriends() {
            openFriendId = 0;
            draft = ""; composerWasFocused = false;  // leaving the conversation
            if (backRow != null) backRow.setVisibility(View.GONE);
            setStatus("Loading friends…");
            IO.execute(new Runnable() {
                public void run() {
                    if (!BhSteamBridge.isAvailable()) { post(new Runnable(){ public void run(){ showNotReady(); } }); return; }
                    final String json = BhSteamBridge.request("friends.list", "{}", 8000);
                    // Unread counts live in the conversation summaries, not the
                    // friend objects (SteamFriendDto has no unread field).
                    final String convJson = BhSteamBridge.request("friends.conversation_summaries", "{}", 8000);
                    post(new Runnable() { public void run() { parseUnread(convJson); renderFriends(json); } });
                }
            });
        }

        /** Build the friendSteamId→unreadCount map from a conversation_summaries response. */
        private void parseUnread(String convJson) {
            unreadByFriend.clear();
            if (convJson == null) return;
            try {
                JSONArray arr = asArray(convJson, "conversations", "summaries", "items", "data", "value");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    long id = c.optLong("friendSteamId", 0);
                    int n = c.optInt("unreadCount", 0);
                    if (id != 0 && n > 0) unreadByFriend.put(id, n);
                }
            } catch (Throwable ignored) {}
        }

        private void loadHistory(final long steamId, final String name) {
            loadHistory(steamId, name, false);
        }

        /** @param silent when true (live-message refresh) skip the "Loading…" flash
         *  and preserve the in-progress composer draft + focus so a reload doesn't
         *  interrupt the user mid-type. */
        private void loadHistory(final long steamId, final String name, final boolean silent) {
            boolean switching = (openFriendId != steamId);
            openFriendId = steamId;
            currentTitle = name;
            if (backRow != null) backRow.setVisibility(View.VISIBLE);
            if (switching) {
                // Opening a different conversation — don't carry the old draft over.
                draft = ""; composerWasFocused = false;
            } else if (composerInput != null) {
                // Same conversation refresh — snapshot so the rebuild restores it.
                draft = composerInput.getText().toString();
                composerWasFocused = composerInput.hasFocus();
            }
            if (!silent) setStatus("Loading messages…");
            IO.execute(new Runnable() {
                public void run() {
                    String payload = "{\"steamId\":" + steamId + ",\"limit\":30}";
                    final String json = BhSteamBridge.request("friends.message_history", payload, 8000);
                    post(new Runnable() { public void run() { renderHistory(json, name); } });
                    // Opening a conversation marks it read → clears the unread badge.
                    markConversationRead(steamId);
                }
            });
        }

        /** Tell Steam we've read this conversation (clears its unread count), then
         *  drop the local badge. Worker-thread. MarkConversationReadRequest takes
         *  steamId(self) + friendSteamId(peer); fall back to the peer for self if
         *  our own SteamID can't be resolved. */
        private void markConversationRead(final long friendSteamId) {
            try {
                long me = ensureLocalSteamId();
                long self = me != 0 ? me : friendSteamId;
                BhSteamBridge.request("friends.mark_conversation_read",
                        "{\"steamId\":" + self + ",\"friendSteamId\":" + friendSteamId + "}", 6000);
            } catch (Throwable ignored) {}
            post(new Runnable() { public void run() { unreadByFriend.remove(friendSteamId); } });
        }

        /** Local user's SteamID64 via auth.bootstrap_snapshot, cached. Worker-thread. */
        private long ensureLocalSteamId() {
            if (localSteamId != 0) return localSteamId;
            try {
                long id = extractSteamId64(BhSteamBridge.request("auth.bootstrap_snapshot", "{}", 6000));
                if (id != 0) localSteamId = id;
            } catch (Throwable ignored) {}
            return localSteamId;
        }

        // ── voice calls (WebRTC in a WebView, signalled via hidden chat) ─────────

        private static final int MIC_REQ = 0xB402;

        private static final String VOICE_BASE = "https://bannerhub-api.the412banner.workers.dev";

        /** 🎙 tapped: open the standalone call box in its outgoing-idle state
         *  (Close / Call). No ring is sent until the user presses Call. */
        private void startVoiceCall(final long peer, final String name) {
            if (peer == 0) return;
            if (voice != null || (callBox != null && callBox.isShowing())) { toast("Already in a call"); return; }
            if (!ensureMicPermission()) { toast("Grant microphone access, then call again"); return; }
            callPeer = peer;
            callPeerName = name;
            ensureCallBox().showOutgoingIdle(name);
        }

        // ── BhVoiceCallBox.Actions (button taps from the call box) ──────────────

        /** Call pressed: ring the callee through the lobby inbox, then open our
         *  own room WebView (the hosted page does the mic + SDP/ICE). */
        @Override public void onPlaceCall() {
            final long peer = callPeer;
            final String name = callPeerName;
            if (peer == 0) { onEnd(); return; }
            if (callBox != null) callBox.showOutgoingRinging(name);
            IO.execute(new Runnable() { public void run() {
                final long self = ensureLocalSteamId();
                if (self == 0) {
                    post(new Runnable() { public void run() {
                        toast("Can't start call — Steam not signed in");
                        endCall();
                    }});
                    return;
                }
                final String room = pairRoom(self, peer);
                postRing(self, peer, room, name);   // doorbell via the lobby inbox
                post(new Runnable() { public void run() {
                    if (voice != null) return;
                    voice = new BhVoiceController(act, room, self, peer, Controller.this);
                    voice.start();
                }});
            }});
        }

        /** A ring landed in our lobby inbox — pop the incoming-call box. UI thread. */
        private void onIncomingRing(long peer, String room, String name) {
            if (voice != null || pendingRoom != null) return;  // already busy / already ringing
            pendingOfferPeer = peer;
            pendingRoom = room;
            pendingPeerName = name;
            callPeer = peer;
            callPeerName = name;
            ensureCallBox().showIncoming(name);
        }

        @Override public void onAnswer() {
            if (pendingRoom == null) return;
            if (!ensureMicPermission()) { toast("Grant microphone access, then accept"); return; }
            final long peer = pendingOfferPeer;
            final String room = pendingRoom;
            pendingRoom = null; pendingPeerName = null;
            if (callBox != null) callBox.showConnecting();
            IO.execute(new Runnable() { public void run() {
                final long self = ensureLocalSteamId();
                post(new Runnable() { public void run() {
                    if (voice != null) return;
                    voice = new BhVoiceController(act, room, self, peer, Controller.this);
                    voice.start();
                }});
            }});
        }

        @Override public void onDecline() {
            final long peer = pendingOfferPeer;
            final String room = pendingRoom;
            pendingRoom = null; pendingOfferPeer = 0; pendingPeerName = null;
            callPeer = 0; callPeerName = null;
            closeCallBox();
            if (peer != 0 && room != null) {
                IO.execute(new Runnable() { public void run() {
                    long self = ensureLocalSteamId();
                    postSignal(room, peer, self, "{\"t\":\"bye\"}");  // tell the caller we declined
                }});
            }
        }

        @Override public void onToggleMute() {
            if (voice == null) return;
            callMuted = !voice.isMuted();
            voice.setMuted(callMuted);
            if (callBox != null) callBox.setMuted(callMuted);
        }

        /** Close / Cancel / Hang up — tear the call down from any state. */
        @Override public void onEnd() { endCall(); }

        private void endCall() {
            // voice.hangup() posts a bye into the peer's room so they get notified;
            // for a pre-ring Close there's no voice yet and nothing to send (the
            // lobby ring, if any, self-expires).
            if (voice != null) { try { voice.hangup(); } catch (Throwable ignored) {} voice = null; }
            pendingRoom = null; pendingOfferPeer = 0; pendingPeerName = null;
            callPeer = 0; callPeerName = null; callMuted = false;
            closeCallBox();
        }

        // BhVoiceController.Host -------------------------------------------------
        public void onVoiceState(final String state, final String detail) {
            post(new Runnable() { public void run() {
                if ("external".equals(state)) {
                    voice = null; closeCallBox();
                    callPeer = 0; callPeerName = null;
                    toast("Voice call opened in your browser (update Android System WebView for in-app calls)");
                    return;
                }
                if ("ended".equals(state)) {
                    voice = null; closeCallBox();
                    callPeer = 0; callPeerName = null; callMuted = false;
                    toast((detail != null && !detail.isEmpty()) ? "Call ended: " + detail : "Call ended");
                    return;
                }
                if (callBox == null) return;
                if ("in-call".equals(state)) {
                    callMuted = detail != null && detail.contains("mut");
                    if (!callConnected) {
                        callConnected = true;
                        callBox.showConnected("You", callPeerName);  // starts the timer
                    }
                    callBox.setMuted(callMuted);
                } else if (!callConnected && "connecting".equals(state)) {
                    callBox.showConnecting();
                } else if (!callConnected && "calling".equals(state)) {
                    callBox.showOutgoingRinging(callPeerName);
                }
            }});
        }

        // ── call box lifecycle ─────────────────────────────────────────────────
        private BhVoiceCallBox ensureCallBox() {
            if (callBox == null) callBox = new BhVoiceCallBox(act, this);
            return callBox;
        }

        private void closeCallBox() {
            if (callBox != null) { callBox.close(); callBox = null; }
            callConnected = false;
        }

        /** pairRoom = sorted SteamID pair "smaller-larger" (matches the hosted
         *  page's deterministic offerer rule, so both sides derive the same id). */
        private static String pairRoom(long a, long b) {
            return a < b ? (a + "-" + b) : (b + "-" + a);
        }

        // ── ring inbox (worker /voice/signal + /voice/poll on a "lobby" room) ────

        /** Ring the callee: drop a {t:ring} blob in their lobby inbox. Worker-thread. */
        private void postRing(long self, long peer, String room, String name) {
            try {
                String payload = new JSONObject()
                        .put("t", "ring").put("room", room).put("from", self)
                        .put("name", name == null ? "" : name)
                        .put("ts", System.currentTimeMillis()).toString();
                Log.i(TAG, "voice: postRing self=" + self + " peer=" + peer + " room=" + room);
                postSignal("lobby", peer, self, payload);
            } catch (Throwable ignored) {}
        }

        /** POST one signal blob to the worker mailbox. Worker-thread. */
        private void postSignal(String room, long to, long from, String payload) {
            try {
                // to/from MUST be strings: a SteamID64 (~7.7e16) exceeds JS's safe
                // integer range, so the worker's JSON.parse would round a bare
                // number and store the ring under a corrupted key the callee's
                // poll (which uses the exact string id) could never match.
                String body = new JSONObject()
                        .put("room", room)
                        .put("to", String.valueOf(to))
                        .put("from", String.valueOf(from))
                        .put("payload", payload).toString();
                httpPost(VOICE_BASE + "/voice/signal", body);
            } catch (Throwable ignored) {}
        }

        /** Poll our lobby inbox once for incoming rings. Worker-thread. */
        private void pollLobbyOnce(long self) {
            String resp = httpGet(VOICE_BASE + "/voice/poll?room=lobby&self=" + self);
            if (resp == null) return;
            try {
                JSONArray sigs = new JSONObject(resp).optJSONArray("signals");
                if (sigs == null || sigs.length() == 0) return;
                Log.i(TAG, "voice: lobby poll self=" + self + " got " + sigs.length() + " signal(s)");
                for (int i = 0; i < sigs.length(); i++) {
                    JSONObject row = sigs.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject p = new JSONObject(row.optString("payload", "{}"));
                    if (!"ring".equals(p.optString("t"))) continue;
                    long ts = p.optLong("ts", 0);
                    if (ts != 0 && System.currentTimeMillis() - ts > 45000) {
                        Log.i(TAG, "voice: ignoring stale ring");
                        continue;
                    }
                    final long peer = p.optLong("from", 0);
                    final String room = p.optString("room", "");
                    if (peer == 0 || room.isEmpty()) continue;
                    // The ring's "name" is whatever the caller put there (it's the
                    // CALLEE's name from the caller's conversation, so useless to
                    // us); resolve the CALLER's name from our own friends list by
                    // their SteamID (you can only be called by a friend).
                    final String name = resolveFriendName(peer, "");
                    Log.i(TAG, "voice: incoming ring from=" + peer + " room=" + room + " name=" + name);
                    post(new Runnable() { public void run() { onIncomingRing(peer, room, name); } });
                }
            } catch (Throwable ignored) {}
        }

        /** Display name for a SteamID from our cached friends list (fetching the
         *  list once if we haven't yet). Worker-thread. */
        private String resolveFriendName(long steamId, String fallback) {
            String json = lastFriendsJson;
            if (json == null) {
                json = BhSteamBridge.request("friends.list", "{}", 8000);
                if (json != null) lastFriendsJson = json;
            }
            if (json != null) {
                try {
                    JSONArray arr = asArray(json, "friends", "items", "data", "list", "value");
                    if (arr != null) for (int i = 0; i < arr.length(); i++) {
                        JSONObject f = arr.optJSONObject(i);
                        if (f == null || f.optLong("steamId", 0) != steamId) continue;
                        String n = firstNonEmpty(f.optString("nickname"), f.optString("displayName"),
                                f.optString("personaName"));
                        if (n != null && !n.isEmpty()) return n;
                    }
                } catch (Throwable ignored) {}
            }
            return (fallback != null && !fallback.isEmpty()) ? fallback : "Steam friend";
        }

        /** Background ring-inbox poll; runs while the overlay is attached. */
        private void startLobbyPoll() {
            if (lobbyThread != null) return;
            lobbyPolling = true;
            lobbyThread = new Thread(new Runnable() { public void run() {
                long self = 0;
                Log.i(TAG, "voice: lobby poll started");
                while (lobbyPolling) {
                    try {
                        if (self == 0) {
                            self = ensureLocalSteamId();
                            if (self != 0) Log.i(TAG, "voice: lobby poll self resolved=" + self);
                        }
                        if (self != 0) pollLobbyOnce(self);
                    } catch (Throwable ignored) {}
                    try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                }
            }}, "bh-voice-lobby");
            lobbyThread.setDaemon(true);
            lobbyThread.start();
        }

        private void stopLobbyPoll() {
            lobbyPolling = false;
            Thread t = lobbyThread;
            lobbyThread = null;
            if (t != null) t.interrupt();
        }

        private static String httpGet(String urlStr) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                if (c.getResponseCode() / 100 != 2) return null;
                return readAll(c.getInputStream());
            } catch (Throwable t) { return null; }
            finally { if (c != null) c.disconnect(); }
        }

        private static void httpPost(String urlStr, String body) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush(); os.close();
                c.getResponseCode();  // drive the request
            } catch (Throwable ignored) {}
            finally { if (c != null) c.disconnect(); }
        }

        private static String readAll(java.io.InputStream in) throws Exception {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        }

        private boolean ensureMicPermission() {
            try {
                if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
                act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, MIC_REQ);
                return false;
            } catch (Throwable t) { return true; }  // pre-M / odd host: assume granted
        }

        // ── render (UI thread) ──────────────────────────────────────────────────

        private void showNotReady() {
            listCol.removeAllViews();
            setStatus("Bridge: " + BhSteamBridge.getStatus());
            TextView hint = new TextView(act);
            hint.setText("Steam SDK bridge could not resolve. Sign into Steam in "
                    + "GameHub if you haven't; otherwise this is a reflection mismatch "
                    + "(see status above).");
            hint.setTextColor(COL_SUBTEXT);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            listCol.addView(hint);
        }

        private void renderFriends(String json) {
            composerInput = null;  // no composer on the friends list
            listCol.removeAllViews();
            if (json == null) { setStatus("friends.list → null · bridge: " + BhSteamBridge.getStatus()); return; }
            lastFriendsJson = json;
            try {
                JSONArray arr = asArray(json, "friends", "items", "data", "list", "value");
                if (arr == null) { setStatus("Unexpected response."); addRaw(json); return; }

                // Partition online (incl. in-game) from offline; in-game sorts first.
                java.util.List<JSONObject> online = new java.util.ArrayList<>();
                java.util.List<JSONObject> offline = new java.util.ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null) continue;
                    boolean on = f.optBoolean("isOnline", false) || f.optBoolean("isInGame", false);
                    (on ? online : offline).add(f);
                }
                java.util.Collections.sort(online, new java.util.Comparator<JSONObject>() {
                    public int compare(JSONObject a, JSONObject b) {
                        return (a.optBoolean("isInGame", false) ? 0 : 1)
                                - (b.optBoolean("isInGame", false) ? 0 : 1);
                    }
                });

                if (!online.isEmpty()) {
                    listCol.addView(sectionHeader("Online — " + online.size(), false));
                    for (JSONObject f : online) listCol.addView(friendRow(f));
                }
                if (!offline.isEmpty()) {
                    View h = sectionHeader((offlineCollapsed ? "▸  " : "▾  ") + "Offline — " + offline.size(), true);
                    h.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            offlineCollapsed = !offlineCollapsed;
                            renderFriends(lastFriendsJson);
                        }
                    });
                    listCol.addView(h);
                    if (!offlineCollapsed) for (JSONObject f : offline) listCol.addView(friendRow(f));
                }
                setStatus(arr.length() + " friends · " + online.size() + " online");
            } catch (Throwable t) {
                setStatus("Parse error."); addRaw(json);
            }
        }

        /** A small all-caps section label; {@code tappable} headers get accent color. */
        private TextView sectionHeader(String text, boolean tappable) {
            TextView h = new TextView(act);
            h.setText(text);
            h.setAllCaps(true);
            h.setTextColor(tappable ? COL_ACCENT : COL_SUBTEXT);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            h.setPadding(0, dp(10), 0, dp(4));
            return h;
        }

        private View friendRow(final JSONObject f) {
            final long steamId = f.optLong("steamId", 0);
            String name = firstNonEmpty(f.optString("nickname"), f.optString("displayName"),
                    f.optString("personaName"), "Friend " + steamId);
            boolean online = f.optBoolean("isOnline", false);
            boolean inGame = f.optBoolean("isInGame", false);
            // org.json's optString returns the literal "null" for a JSONObject.NULL
            // value, so guard explicitly or the in-game label reads "In-Game · null".
            String game = f.isNull("gameName") ? "" : f.optString("gameName", "");
            if ("null".equalsIgnoreCase(game)) game = "";

            String avatarUrl = f.isNull("avatarUrl") ? "" : f.optString("avatarUrl", "");

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            row.addView(avatarWithPresence(avatarUrl, 34, online, inGame));

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView nm = new TextView(act);
            nm.setText(name);
            nm.setTextColor(online ? COL_TEXT : COL_SUBTEXT);
            nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            col.addView(nm);
            String sub = inGame && !game.isEmpty() ? "In-Game · " + game
                    : online ? "Online" : "Offline";
            TextView st = new TextView(act);
            st.setText(sub);
            st.setTextColor(inGame ? COL_INGAME : COL_SUBTEXT);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            col.addView(st);
            row.addView(col);

            Integer unread = unreadByFriend.get(steamId);
            if (unread != null && unread > 0) row.addView(unreadBadge(unread));

            if (steamId != 0) {
                row.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        loadHistory(steamId, firstNonEmpty(f.optString("displayName"), f.optString("personaName"), "Friend"));
                    }
                });
            }
            return row;
        }

        private void renderHistory(String json, String name) {
            listCol.removeAllViews();
            // The "‹ Back to friends" control is now the pinned `backRow` in the
            // panel's fixed zone (made visible in loadHistory), so it stays put
            // instead of scrolling away with the message list.
            if (json == null) { setStatus("No history yet — say hello."); addComposer(openFriendId); return; }
            try {
                JSONArray arr = asArray(json, "messages", "items", "data", "history", "value");
                if (arr == null) { setStatus("Chat with " + name); addRaw(json); addComposer(openFriendId); return; }
                Boolean prevFromMe = null;   // group consecutive messages by sender
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    // SteamChatMessageDto.direction is the only reliable sender
                    // signal — "Incoming" (friend) vs "Outgoing" (us). friendSteamId
                    // is the conversation peer on BOTH sides, so it can't tell who
                    // sent it. (Old fromLocalUser/isOutgoing guesses never matched,
                    // so every bubble fell through to "incoming" = left.)
                    boolean fromMe = "Outgoing".equalsIgnoreCase(m.optString("direction", ""))
                            || m.optBoolean("fromLocalUser", m.optBoolean("isOutgoing", false));
                    // Name + timestamp header, shown once per sender run (like the
                    // native screen): friend's displayName / "You", time from the
                    // unsigned-32 epoch-seconds `timestamp`.
                    if (prevFromMe == null || prevFromMe.booleanValue() != fromMe) {
                        String who = fromMe ? "You"
                                : firstNonEmpty(m.optString("displayName"), name, "Friend");
                        String av = m.isNull("avatarUrl") ? "" : m.optString("avatarUrl", "");
                        listCol.addView(senderHeader(who, m.optLong("timestamp", 0), fromMe, av));
                    }
                    prevFromMe = fromMe;
                    // rawMessage carries BBCode (incl. [img …]) for image detection;
                    // plainMessage is the display-ready text the native UI shows.
                    String rawMessage = firstNonEmpty(m.optString("rawMessage"), m.optString("message"),
                            m.optString("text"), m.optString("body"), m.optString("content"), "");
                    String plainMessage = firstNonEmpty(m.optString("plainMessage"), "");
                    String imgUrl = extractImageUrl(rawMessage);
                    java.util.regex.Matcher invite = INVITE_TAG_RE.matcher(rawMessage);
                    String stickerName = extractStickerName(rawMessage);
                    java.util.List<String> emotes = emoticonOnlyNames(rawMessage);
                    if (invite.find()) {
                        // Game/lobby/etc. invite: plainMessage is empty for these,
                        // which is what used to render as a blank bubble.
                        listCol.addView(bubbleRow(fromMe,
                                inviteCard(invite.group(1).toLowerCase(java.util.Locale.ROOT),
                                        attrsOf(invite.group(2)), fromMe, name)));
                    } else if (stickerName != null) {
                        listCol.addView(bubbleRow(fromMe, stickerView(stickerName)));
                    } else if (emotes != null) {
                        // Message is nothing but emoticons → render them as images.
                        listCol.addView(bubbleRow(fromMe, emoticonRow(emotes)));
                    } else if (imgUrl != null) {
                        // Image inside an aligned, sender-tinted bubble.
                        listCol.addView(bubbleRow(fromMe, imageRow(imgUrl, fromMe)));
                    } else {
                        TextView mv = new TextView(act);
                        // Mixed text+emoticon: show emoticons in their :name: form.
                        String shown = plainMessage.isEmpty()
                                ? stripBBCode(EMOTICON_RE.matcher(rawMessage).replaceAll(":$1:"))
                                : plainMessage;
                        mv.setText(shown);
                        mv.setTextColor(COL_TEXT);
                        mv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        // Cap width so long lines wrap into the bubble instead of
                        // stretching the whole panel; alignment + tint convey "who".
                        mv.setMaxWidth(dp(200));
                        listCol.addView(bubbleRow(fromMe, mv));
                    }
                    // Delivery state for our own messages (sendState enum:
                    // Sending / Sent / Failed). Only surface the non-final states.
                    if (fromMe) {
                        String ss = m.optString("sendState", "");
                        if (ss.equalsIgnoreCase("Sending") || ss.equalsIgnoreCase("pending"))
                            listCol.addView(sendStatusLabel("Sending…", false));
                        else if (ss.equalsIgnoreCase("Failed"))
                            listCol.addView(sendStatusLabel("Failed to send", true));
                    }
                }
                setStatus("Chat with " + name + " · " + arr.length() + " messages");
                addComposer(openFriendId);
                scrollToBottom();
            } catch (Throwable t) {
                setStatus("Chat with " + name); addRaw(json); addComposer(openFriendId);
                scrollToBottom();
            }
        }

        // Unicode emoji always available regardless of Steam ownership — this is
        // the "emoji options" the picker leads with (stickers/emoticons are
        // account-gated purchased items and most users own none).
        private static final String[] EMOJI = {
                "😀","😁","😂","🤣","😊","😍","😘","😎","🤩","🥳","😉","😜","😇","🙂","🙃","😴",
                "😢","😭","😡","🤔","😱","🤯","🥺","😬","🤗","🤝","👍","👎","👏","🙏","💪","🔥",
                "✨","🎉","❤️","💔","💯","✅","❌","⭐","🎮","🕹️","👀","💀","🤡","🫡","😏","🤙"};

        /** Bottom-of-conversation message composer: picker (emoji/emoticon/sticker)
         *  + image + input + Send. Composer text + focus survive silent refreshes. */
        private void addComposer(final long steamId) {
            if (steamId == 0) return;
            LinearLayout composer = new LinearLayout(act);
            composer.setOrientation(LinearLayout.VERTICAL);

            // ── picker panel (hidden until ☺ is tapped) ──
            final LinearLayout pickerBox = new LinearLayout(act);
            pickerBox.setOrientation(LinearLayout.VERTICAL);
            pickerBox.setVisibility(View.GONE);
            LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pbp.topMargin = dp(6);
            pickerBox.setLayoutParams(pbp);

            final android.widget.HorizontalScrollView pickerScroll = new android.widget.HorizontalScrollView(act);
            final LinearLayout strip = new LinearLayout(act);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, dp(6), 0, dp(2));
            pickerScroll.addView(strip);

            final EditText input = new EditText(act);   // declared early so tabs can insert

            // tab row: Emoji · Steam (emoticons) · Stickers
            LinearLayout tabs = new LinearLayout(act);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            final TextView tabEmoji = pickerTab("😀 Emoji");
            final TextView tabEmote = pickerTab("🙂 Steam");
            final TextView tabStick = pickerTab("🏷 Stickers");
            tabs.addView(tabEmoji); tabs.addView(tabEmote); tabs.addView(tabStick);
            pickerBox.addView(tabs);
            pickerBox.addView(pickerScroll);

            final Runnable showEmoji = new Runnable() { public void run() {
                setActiveTab(tabEmoji, tabEmote, tabStick);
                strip.removeAllViews(); populateEmoji(strip, input);
            }};
            final Runnable showEmote = new Runnable() { public void run() {
                setActiveTab(tabEmote, tabEmoji, tabStick);
                strip.removeAllViews(); loadEmoticons(strip, input);
            }};
            final Runnable showStick = new Runnable() { public void run() {
                setActiveTab(tabStick, tabEmoji, tabEmote);
                strip.removeAllViews(); loadStickers(strip, steamId);
            }};
            tabEmoji.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showEmoji.run(); }});
            tabEmote.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showEmote.run(); }});
            tabStick.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ showStick.run(); }});
            composer.addView(pickerBox);

            // ── input bar ──
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(0, dp(8), 0, dp(2));

            final TextView pickBtn = new TextView(act);
            pickBtn.setText("☺");
            pickBtn.setTextColor(COL_ACCENT);
            pickBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            pickBtn.setPadding(dp(2), dp(4), dp(6), dp(4));
            pickBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (pickerBox.getVisibility() == View.VISIBLE) { pickerBox.setVisibility(View.GONE); return; }
                    pickerBox.setVisibility(View.VISIBLE);
                    if (strip.getChildCount() == 0) showEmoji.run();  // default to always-available emoji
                    scrollToBottom();
                }
            });
            bar.addView(pickBtn);

            final TextView imgBtn = new TextView(act);
            imgBtn.setText("🖼");
            imgBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            imgBtn.setPadding(dp(2), dp(4), dp(6), dp(4));
            imgBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { pickImage(steamId); }
            });
            bar.addView(imgBtn);

            final TextView callBtn = new TextView(act);
            callBtn.setText("🎙");
            callBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            callBtn.setPadding(dp(2), dp(4), dp(8), dp(4));
            callBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { startVoiceCall(steamId, currentTitle); }
            });
            bar.addView(callBtn);

            input.setHint("Message…");
            input.setHintTextColor(COL_OFFLINE);
            input.setTextColor(COL_TEXT);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            input.setSingleLine(true);
            input.setFocusable(true);
            input.setFocusableInTouchMode(true);
            input.setImeOptions(EditorInfo.IME_ACTION_SEND);
            input.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showKeyboard(input); }
            });
            input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                public void onFocusChange(View v, boolean has) { if (has) showKeyboard(input); }
            });
            GradientDrawable ibg = new GradientDrawable();
            ibg.setColor(0x22000000);
            ibg.setCornerRadius(dp(6));
            input.setBackground(ibg);
            input.setPadding(dp(8), dp(6), dp(8), dp(6));
            input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // Restore an in-progress draft from before a silent refresh BEFORE the
            // watcher is attached, so programmatic restore doesn't fire send_typing.
            if (draft != null && !draft.isEmpty()) {
                input.setText(draft);
                input.setSelection(input.getText().length());
                if (composerWasFocused) input.requestFocus();
            }
            // Steam's "X is typing…" hint on the friend's side: notify at most
            // once per 10s while there's text in the box (their window is ~15s).
            input.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(android.text.Editable s) { draft = s == null ? "" : s.toString(); }
                public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (s == null || s.length() == 0) return;
                    long now = System.currentTimeMillis();
                    if (now - lastTypingSentMs < 10000) return;
                    lastTypingSentMs = now;
                    IO.execute(new Runnable() { public void run() {
                        BhSteamBridge.request("friends.send_typing",
                                "{\"steamId\":" + steamId + "}", 4000);
                    }});
                }
            });
            composerInput = input;
            bar.addView(input);

            final TextView send = new TextView(act);
            send.setText("Send");
            send.setTextColor(COL_ACCENT);
            send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            send.setPadding(dp(12), dp(6), dp(4), dp(6));
            bar.addView(send);

            final Runnable doSend = new Runnable() {
                public void run() {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) return;
                    input.setText("");
                    draft = "";
                    hideKeyboard(input);
                    sendMessage(steamId, text);
                }
            };
            send.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { doSend.run(); }
            });
            input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                    if (actionId == EditorInfo.IME_ACTION_SEND
                            || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                        doSend.run();
                        return true;
                    }
                    return false;
                }
            });
            composer.addView(bar);
            listCol.addView(composer);
        }

        /** A picker tab chip. */
        private TextView pickerTab(String label) {
            TextView t = new TextView(act);
            t.setText(label);
            t.setTextColor(COL_SUBTEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            t.setPadding(dp(8), dp(4), dp(8), dp(4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            t.setLayoutParams(lp);
            return t;
        }

        private void setActiveTab(TextView active, TextView... others) {
            active.setTextColor(COL_ACCENT);
            active.setTypeface(Typeface.DEFAULT_BOLD);
            for (TextView o : others) { o.setTextColor(COL_SUBTEXT); o.setTypeface(Typeface.DEFAULT); }
        }

        /** Insert text at the composer's caret and keep the draft in sync. */
        private void insertIntoComposer(EditText input, String t) {
            if (input == null || t == null || t.isEmpty()) return;
            int s = Math.max(input.getSelectionStart(), 0);
            int e = Math.max(input.getSelectionEnd(), 0);
            input.getText().replace(Math.min(s, e), Math.max(s, e), t, 0, t.length());
            draft = input.getText().toString();
        }

        /** Unicode-emoji strip: tap appends the glyph to the message. */
        private void populateEmoji(final LinearLayout strip, final EditText input) {
            for (int i = 0; i < EMOJI.length; i++) {
                final String g = EMOJI[i];
                TextView t = new TextView(act);
                t.setText(g);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                t.setPadding(dp(4), dp(2), dp(4), dp(2));
                t.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { insertIntoComposer(input, g); }
                });
                strip.addView(t);
            }
        }

        /** Steam emoticon strip from friends.chat_emoticons; tap inserts the
         *  emoticon token (e.g. ":steamhappy:") which Steam expands on send. */
        private void loadEmoticons(final LinearLayout strip, final EditText input) {
            TextView loading = new TextView(act);
            loading.setText("Loading emoticons…");
            loading.setTextColor(COL_SUBTEXT);
            loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            strip.addView(loading);
            IO.execute(new Runnable() {
                public void run() {
                    final String json = BhSteamBridge.request("friends.chat_emoticons", "{}", 8000);
                    post(new Runnable() {
                        public void run() {
                            strip.removeAllViews();
                            JSONArray arr = json == null ? null
                                    : asArray(json, "emoticons", "items", "data", "list", "value");
                            if (arr == null || arr.length() == 0) {
                                TextView none = new TextView(act);
                                none.setText(json == null ? "Couldn't load emoticons"
                                        : "No Steam emoticons owned — use Emoji");
                                none.setTextColor(COL_SUBTEXT);
                                none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                                strip.addView(none);
                                return;
                            }
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject s = arr.optJSONObject(i);
                                if (s == null) continue;
                                String token = firstNonEmpty(s.optString("token"), s.optString("name"));
                                if (token.isEmpty()) continue;
                                // Steam tokens are usually already ":name:"; normalise.
                                final String insert = token.startsWith(":") ? token : ":" + token + ":";
                                String img = firstNonEmpty(s.optString("imageUrl"), s.optString("largeImageUrl"),
                                        EMOTICON_CDN + urlEnc(token.replace(":", "")));
                                final android.widget.ImageView iv = new android.widget.ImageView(act);
                                int sz = dp(34);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                                if (i > 0) lp.leftMargin = dp(6);
                                iv.setLayoutParams(lp);
                                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                                iv.setContentDescription(insert);
                                final String u = img;
                                IMG_IO.execute(new Runnable() { public void run() {
                                    final android.graphics.Bitmap b = fetchBitmap(u);
                                    if (b != null) post(new Runnable() { public void run() { iv.setImageBitmap(b); } });
                                }});
                                iv.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) { insertIntoComposer(input, insert); }
                                });
                                strip.addView(iv);
                            }
                        }
                    });
                }
            });
        }

        /** Fill the picker strip from friends.chat_stickers; tap sends the sticker.
         *  SteamChatStickerDto has no name field, so derive the sticker name from
         *  the image URL path (…/economy/sticker/<appid>/<name>/…) for send. */
        private void loadStickers(final LinearLayout strip, final long steamId) {
            TextView loading = new TextView(act);
            loading.setText("Loading stickers…");
            loading.setTextColor(COL_SUBTEXT);
            loading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            strip.addView(loading);
            IO.execute(new Runnable() {
                public void run() {
                    final String json = BhSteamBridge.request("friends.chat_stickers", "{}", 8000);
                    post(new Runnable() {
                        public void run() {
                            strip.removeAllViews();
                            JSONArray arr = json == null ? null
                                    : asArray(json, "stickers", "items", "data", "list", "value");
                            if (arr == null || arr.length() == 0) {
                                TextView none = new TextView(act);
                                none.setText(json == null ? "Couldn't load stickers"
                                        : "No stickers owned — these are purchased Steam items");
                                none.setTextColor(COL_SUBTEXT);
                                none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                                strip.addView(none);
                                return;
                            }
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject s = arr.optJSONObject(i);
                                if (s == null) continue;
                                String img = firstNonEmpty(s.optString("staticImageUrl"), s.optString("imageUrl"));
                                if (img.isEmpty()) continue;
                                final String name = stickerNameFromUrl(img);
                                final android.widget.ImageView iv = new android.widget.ImageView(act);
                                int sz = dp(48);
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
                                if (i > 0) lp.leftMargin = dp(6);
                                iv.setLayoutParams(lp);
                                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                                iv.setContentDescription(name);
                                final String u = img;
                                IMG_IO.execute(new Runnable() { public void run() {
                                    final android.graphics.Bitmap b = fetchBitmap(u);
                                    if (b != null) post(new Runnable() { public void run() { iv.setImageBitmap(b); } });
                                }});
                                iv.setOnClickListener(new View.OnClickListener() {
                                    public void onClick(View v) {
                                        if (name.isEmpty()) toast("Couldn't identify sticker");
                                        else sendSticker(steamId, name);
                                    }
                                });
                                strip.addView(iv);
                            }
                        }
                    });
                }
            });
        }

        /** Launch the transparent image picker, which uploads the chosen image to
         *  this conversation via friends.upload_chat_image and finishes itself. */
        private void pickImage(long steamId) {
            try {
                android.content.Intent it = new android.content.Intent(act,
                        Class.forName("com.xj.winemu.steamchat.BhSteamImagePickerActivity"));
                it.putExtra("steamId", steamId);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(it);
            } catch (Throwable t) {
                toast("Image picker unavailable");
                Log.w(TAG, "pickImage failed", t);
            }
        }

        private void toast(final String msg) {
            try { android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Throwable ignored) {}
        }

        private void sendSticker(final long steamId, final String stickerName) {
            setStatus("Sending sticker…");
            IO.execute(new Runnable() {
                public void run() {
                    String payload;
                    try {
                        // SendStickerRequest: steamId, stickerName, clientMessageId
                        // (String, same caveat as send_message).
                        payload = new JSONObject()
                                .put("steamId", steamId)
                                .put("stickerName", stickerName)
                                .put("clientMessageId", String.valueOf(System.currentTimeMillis()))
                                .toString();
                    } catch (Throwable t) { return; }
                    final String resp = BhSteamBridge.request("friends.send_sticker", payload, 8000);
                    post(new Runnable() {
                        public void run() {
                            if (resp == null) setStatus("Sticker failed · " + BhSteamBridge.getLastError());
                            else loadHistory(steamId, currentTitle);
                        }
                    });
                }
            });
        }

        private void sendMessage(final long steamId, final String text) {
            setStatus("Sending…");
            IO.execute(new Runnable() {
                public void run() {
                    String payload;
                    try {
                        // SendMessageRequest (elh): steamId:long, message:String,
                        // clientMessageId:String (optional). It MUST be a string —
                        // kotlinx rejects a number here and the send silently fails.
                        payload = new JSONObject()
                                .put("steamId", steamId)
                                .put("message", text)
                                .put("clientMessageId", String.valueOf(System.currentTimeMillis()))
                                .toString();
                    } catch (Throwable t) {
                        post(new Runnable() { public void run() { setStatus("Send failed: bad payload."); } });
                        return;
                    }
                    final String resp = BhSteamBridge.request("friends.send_message", payload, 8000);
                    post(new Runnable() {
                        public void run() {
                            if (resp == null) {
                                setStatus("Send failed · " + BhSteamBridge.getLastError());
                            } else {
                                // Reload so the just-sent message appears in the thread.
                                loadHistory(steamId, currentTitle);
                            }
                        }
                    });
                }
            });
        }

        /**
         * Name + timestamp line above a sender run, aligned to that sender's side:
         * friend name (blue) on the left, "You" (green) on the right — same idea as
         * GameHub's native chat screen.
         */
        private View senderHeader(String who, long tsEpochSecs, boolean fromMe, String avatarUrl) {
            TextView h = new TextView(act);
            String t = formatTime(tsEpochSecs);
            h.setText(t.isEmpty() ? who : who + "  ·  " + t);
            h.setTextColor(fromMe ? COL_INGAME : COL_ACCENT);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);

            LinearLayout rowH = new LinearLayout(act);
            rowH.setOrientation(LinearLayout.HORIZONTAL);
            rowH.setGravity(Gravity.CENTER_VERTICAL | (fromMe ? Gravity.END : Gravity.START));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            rowH.setLayoutParams(lp);
            rowH.setPadding(dp(2), 0, dp(2), dp(1));

            // Avatar sits on the speaker's outer edge: left of name (them) / right (you).
            android.widget.ImageView av = circleAvatar(avatarUrl, 18);
            android.view.View gap = new android.view.View(act);
            gap.setLayoutParams(new LinearLayout.LayoutParams(dp(6), dp(1)));
            if (fromMe) { rowH.addView(h); rowH.addView(gap); rowH.addView(av); }
            else        { rowH.addView(av); rowH.addView(gap); rowH.addView(h); }
            return rowH;
        }

        /** Format an unsigned-32 Unix-epoch-seconds timestamp as the device's clock
         *  (12/24h per system setting); "" when absent. */
        private String formatTime(long tsEpochSecs) {
            if (tsEpochSecs <= 0) return "";
            // Tolerate millis too, in case a future source sends them.
            long ms = tsEpochSecs < 100000000000L ? tsEpochSecs * 1000L : tsEpochSecs;
            try {
                return android.text.format.DateFormat.getTimeFormat(act)
                        .format(new java.util.Date(ms));
            } catch (Throwable t) { return ""; }
        }

        /** A circular avatar that lazy-loads {@code url}; shows a neutral circle until then. */
        private android.widget.ImageView circleAvatar(String url, int sizeDp) {
            final android.widget.ImageView iv = new android.widget.ImageView(act);
            int s = dp(sizeDp);
            iv.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ph = new GradientDrawable();
            ph.setShape(GradientDrawable.OVAL);
            ph.setColor(COL_PILL_BG);
            iv.setBackground(ph);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                public void getOutline(android.view.View v, android.graphics.Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            if (url != null && url.startsWith("http")) {
                final String u = url;
                IMG_IO.execute(new Runnable() { public void run() {
                    final android.graphics.Bitmap b = fetchBitmap(u);
                    if (b != null) post(new Runnable() { public void run() { iv.setImageBitmap(b); } });
                }});
            }
            return iv;
        }

        /** Avatar with a presence dot (online/in-game/offline) in the bottom-right. */
        private android.widget.FrameLayout avatarWithPresence(String url, int sizeDp, boolean online, boolean inGame) {
            android.widget.FrameLayout fl = new android.widget.FrameLayout(act);
            int s = dp(sizeDp);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(s, s);
            flp.rightMargin = dp(10);
            fl.setLayoutParams(flp);
            fl.addView(circleAvatar(url, sizeDp));

            android.view.View dot = new android.view.View(act);
            int ds = dp(10);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(inGame ? COL_INGAME : online ? COL_ONLINE : COL_OFFLINE);
            d.setStroke(dp(2), COL_PANEL_BG);  // ring so it reads against the avatar
            dot.setBackground(d);
            android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(ds, ds);
            dlp.gravity = Gravity.BOTTOM | Gravity.END;
            dot.setLayoutParams(dlp);
            fl.addView(dot);
            return fl;
        }

        /** Pill badge with the unread-message count for a friend row. */
        private TextView unreadBadge(int n) {
            TextView b = new TextView(act);
            b.setText(n > 99 ? "99+" : String.valueOf(n));
            b.setTextColor(0xFF0E141B);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(6), dp(1), dp(6), dp(1));
            b.setMinWidth(dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_ACCENT);
            bg.setCornerRadius(dp(10));
            b.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            b.setLayoutParams(lp);
            return b;
        }

        /** Right-aligned delivery-status line under one of our own messages. */
        private TextView sendStatusLabel(String text, boolean failed) {
            TextView t = new TextView(act);
            t.setText(text);
            t.setTextColor(failed ? COL_FAILED : COL_SUBTEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            t.setLayoutParams(lp);
            t.setGravity(Gravity.END);
            t.setPadding(dp(2), 0, dp(4), dp(2));
            return t;
        }

        /**
         * Wrap a message view in a sender-attributed bubble: incoming bubbles are
         * dark and left-aligned, outgoing are Steam-blue and right-aligned, so
         * alignment + colour alone tell the user who said what.
         */
        private View bubbleRow(boolean fromMe, View content) {
            LinearLayout bubble = new LinearLayout(act);
            bubble.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(fromMe ? COL_BUBBLE_ME : COL_BUBBLE_THEM);
            bg.setCornerRadius(dp(12));
            bubble.setBackground(bg);
            bubble.setPadding(dp(10), dp(7), dp(10), dp(7));
            bubble.addView(content);

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setGravity(fromMe ? Gravity.END : Gravity.START);
            row.setPadding(0, dp(3), 0, dp(3));
            row.addView(bubble);
            return row;
        }

        /** Render a chat image: async-download the bitmap into an ImageView; tap opens full-res in browser. */
        private View imageRow(final String url, boolean fromMe) {
            final android.widget.ImageView iv = new android.widget.ImageView(act);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(220),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            ilp.topMargin = dp(4); ilp.bottomMargin = dp(4);
            iv.setLayoutParams(ilp);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_START);
            iv.setMinimumHeight(dp(80));
            GradientDrawable ph = new GradientDrawable();
            ph.setColor(0x22FFFFFF); ph.setCornerRadius(dp(6));
            iv.setBackground(ph);
            iv.setContentDescription("Steam chat image");
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try {
                        android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url));
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(it);
                    } catch (Throwable ignored) {}
                }
            });
            IMG_IO.execute(new Runnable() {
                public void run() {
                    final android.graphics.Bitmap bmp = fetchBitmap(url);
                    post(new Runnable() { public void run() {
                        if (bmp != null) { iv.setBackground(null); iv.setImageBitmap(bmp); }
                    }});
                }
            });
            return iv;
        }

        /**
         * Rich card for an invite-family BBCode message, mirroring the native
         * chat screen: game artwork, "<sender> invited you to play", game name,
         * and a live/Expired state. The invite tag itself carries only ids
         * (appid / lobbyid / connect string) — name + artwork resolve from
         * apps.app_details, and liveness is the same signal the native card
         * uses: the inviting friend is still in that app right now
         * (SteamFriendDto.gameAppId).
         */
        private View inviteCard(String tag, java.util.Map<String, String> attrs,
                                boolean fromMe, String friendName) {
            int appId = 0;
            try { appId = Integer.parseInt(attrs.containsKey("appid") ? attrs.get("appid") : "0"); }
            catch (Throwable ignored) {}

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutParams(new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT));

            // Artwork (Steam header capsule, 460×215). CDN path by appid is what
            // app_details' headerImageUrl resolves to for almost every app.
            if (appId > 0) {
                final android.widget.ImageView art = new android.widget.ImageView(act);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(200), dp(94));
                alp.bottomMargin = dp(6);
                art.setLayoutParams(alp);
                art.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                GradientDrawable ph = new GradientDrawable();
                ph.setColor(0x22FFFFFF); ph.setCornerRadius(dp(6));
                art.setBackground(ph);
                final String headerUrl = APP_HEADER_CDN + appId + "/header.jpg";
                IMG_IO.execute(new Runnable() { public void run() {
                    final android.graphics.Bitmap b = fetchBitmap(headerUrl);
                    if (b != null) post(new Runnable() { public void run() {
                        art.setBackground(null); art.setImageBitmap(b);
                    }});
                }});
                card.addView(art);
            }

            TextView verb = new TextView(act);
            verb.setText(inviteVerbLine(tag, fromMe, friendName));
            verb.setTextColor(COL_SUBTEXT);
            verb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            card.addView(verb);

            final TextView gameName = new TextView(act);
            gameName.setText(appId > 0 ? "App " + appId : inviteKindLabel(tag));
            gameName.setTextColor(COL_TEXT);
            gameName.setTypeface(Typeface.DEFAULT_BOLD);
            gameName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            card.addView(gameName);
            if (appId > 0) resolveAppName(appId, gameName);

            // State line for invites we received: joinable while the friend is
            // still in that game, otherwise Expired (grey) — same rule as the
            // native card. We deliberately don't offer Join: another game is
            // already running fullscreen; joining happens from GameHub itself.
            if (!fromMe && appId > 0) {
                TextView state = new TextView(act);
                boolean live = friendCurrentlyInApp(openFriendId, appId);
                state.setText(live ? "● Active now — join from GameHub's Steam chat" : "Expired");
                state.setTextColor(live ? COL_INGAME : COL_OFFLINE);
                state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                state.setPadding(0, dp(4), 0, 0);
                card.addView(state);
            }
            return card;
        }

        /** "<who> invited you to play / playtest / watch …" per invite tag kind. */
        private String inviteVerbLine(String tag, boolean fromMe, String friendName) {
            String who = fromMe ? "You invited " + friendName : friendName + " invited you";
            if (tag.equals("playtestinvite"))             return who + " to playtest";
            if (tag.equals("remoteplaytogetherinvite"))   return who + " to Remote Play";
            if (tag.startsWith("broadcast"))              return who + " to watch";
            if (tag.equals("tradeoffer"))
                return fromMe ? "You sent " + friendName + " a trade offer"
                              : friendName + " sent you a trade offer";
            if (tag.equals("invite") || tag.equals("inviteurl"))
                return fromMe ? "You sent an invite" : friendName + " sent you an invite";
            return who + " to play";
        }

        /** Fallback card title when the tag carries no appid. */
        private String inviteKindLabel(String tag) {
            if (tag.equals("tradeoffer"))               return "Trade offer";
            if (tag.startsWith("broadcast"))            return "Steam broadcast";
            if (tag.equals("remoteplaytogetherinvite")) return "Remote Play Together";
            return "Steam invite";
        }

        /** True if `steamId` is in-game in `appId` right now, per the cached
         *  friends.list snapshot (SteamFriendDto.gameAppId/isInGame). */
        private boolean friendCurrentlyInApp(long steamId, int appId) {
            if (lastFriendsJson == null || steamId == 0) return false;
            try {
                JSONArray arr = asArray(lastFriendsJson, "friends", "items", "data", "list", "value");
                if (arr == null) return false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null || f.optLong("steamId", 0) != steamId) continue;
                    return f.optBoolean("isInGame", false) && f.optLong("gameAppId", 0) == appId;
                }
            } catch (Throwable ignored) {}
            return false;
        }

        /** Resolve appId → display name via apps.app_details (session-cached)
         *  and drop it into `target` when it arrives. */
        private void resolveAppName(final int appId, final TextView target) {
            synchronized (sAppInfo) {
                String[] hit = sAppInfo.get(appId);
                if (hit != null) { if (!hit[0].isEmpty()) target.setText(hit[0]); return; }
            }
            IO.execute(new Runnable() {
                public void run() {
                    String name = "";
                    try {
                        String resp = BhSteamBridge.request("apps.app_details",
                                "{\"appId\":" + appId + "}", 8000);
                        if (resp != null) name = appNameFrom(resp);
                    } catch (Throwable ignored) {}
                    final String got = name;
                    synchronized (sAppInfo) { sAppInfo.put(appId, new String[]{got, ""}); }
                    if (!got.isEmpty()) post(new Runnable() { public void run() { target.setText(got); } });
                }
            });
        }

        /** A Steam sticker, rendered from the same CDN the native screen uses. */
        private View stickerView(String stickerName) {
            final android.widget.ImageView iv = new android.widget.ImageView(act);
            int s = dp(92);  // native sticker render size
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription(stickerName);
            final String url = STICKER_CDN + urlEnc(stickerName);
            IMG_IO.execute(new Runnable() { public void run() {
                final android.graphics.Bitmap b = fetchBitmap(url);
                if (b != null) post(new Runnable() { public void run() { iv.setImageBitmap(b); } });
            }});
            return iv;
        }

        /** A run of emoticon images for an all-emoticon message (max 6 shown). */
        private View emoticonRow(java.util.List<String> names) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int n = Math.min(names.size(), 6);
            for (int i = 0; i < n; i++) {
                final android.widget.ImageView iv = new android.widget.ImageView(act);
                int s = dp(28);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
                if (i > 0) lp.leftMargin = dp(3);
                iv.setLayoutParams(lp);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                iv.setContentDescription(names.get(i));
                final String url = EMOTICON_CDN + urlEnc(names.get(i));
                IMG_IO.execute(new Runnable() { public void run() {
                    final android.graphics.Bitmap b = fetchBitmap(url);
                    if (b != null) post(new Runnable() { public void run() { iv.setImageBitmap(b); } });
                }});
                row.addView(iv);
            }
            return row;
        }

        private void addRaw(String json) {
            TextView raw = new TextView(act);
            raw.setText(json.length() > 1200 ? json.substring(0, 1200) + "…" : json);
            raw.setTextColor(COL_SUBTEXT);
            raw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            raw.setTypeface(Typeface.MONOSPACE);
            listCol.addView(raw);
        }

        private void setStatus(String s) { if (status != null) status.setText(s); }
        private void post(Runnable r) { MAIN.post(r); }

        /** Snap the conversation to the newest message (bottom). */
        private void scrollToBottom() {
            if (scroll == null) return;
            scroll.post(new Runnable() { public void run() { scroll.fullScroll(View.FOCUS_DOWN); } });
        }

        private void showKeyboard(final View v) {
            v.requestFocus();
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
        }

        private void hideKeyboard(final View v) {
            InputMethodManager imm = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
        private int dp(int v) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                    act.getResources().getDisplayMetrics());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Accept either a bare JSON array or an object wrapping the array under one of `keys`. */
    private static JSONArray asArray(String json, String... keys) {
        String s = json.trim();
        try {
            if (s.startsWith("[")) return new JSONArray(s);
            JSONObject o = new JSONObject(s);
            for (String k : keys) {
                JSONArray a = o.optJSONArray(k);
                if (a != null) return a;
                // one level of nesting (e.g. {"data":{"friends":[...]}})
                JSONObject inner = o.optJSONObject(k);
                if (inner != null) for (String k2 : keys) {
                    JSONArray a2 = inner.optJSONArray(k2);
                    if (a2 != null) return a2;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty() && !v.equals("null")) return v;
        return "";
    }

    /** Pull the first SteamID64 out of an auth.bootstrap_snapshot response (the
     *  local user's id), accepting either a numeric or string-quoted value. */
    private static long extractSteamId64(String json) {
        if (json == null) return 0;
        java.util.regex.Matcher m = STEAMID64_RE.matcher(json);
        if (m.find()) { try { return Long.parseLong(m.group(1)); } catch (Throwable t) { return 0; } }
        return 0;
    }

    /**
     * Steam chat image messages arrive as BBCode carrying an https image URL
     * (e.g. {@code [img src=…steamusercontent…]…[/img]}). Pull the first such URL
     * so we can render it inline; returns null for ordinary text messages.
     */
    // A bare https URL ending in an image extension (our R2-hosted chat images
    // arrive as plain …/chat/i/<id>.jpg messages, as do many pasted image links).
    private static final java.util.regex.Pattern IMG_URL_RE = java.util.regex.Pattern.compile(
            "https?://[^\\s\\]\\[\"']+\\.(?:jpg|jpeg|png|gif|webp)(?:\\?[^\\s\"']*)?",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    private static String extractImageUrl(String text) {
        if (text == null) return null;
        boolean looksImg = text.contains("[img") || text.contains("steamusercontent")
                || text.contains("steamuserimages") || text.contains("/chat/i/")
                || IMG_URL_RE.matcher(text).find();
        if (!looksImg) return null;
        // Prefer the image-extension URL when present (skips non-image links).
        java.util.regex.Matcher im = IMG_URL_RE.matcher(text);
        if (im.find()) return im.group();
        java.util.regex.Matcher m = URL_RE.matcher(text);
        // Prefer a thumbnail URL if one is called out; else the first URL.
        String first = null;
        while (m.find()) {
            String u = m.group();
            if (first == null) first = u;
            if (u.contains("thumb")) return u;
        }
        return first;
    }

    /** Parse the attribute blob of a BBCode tag (`appid="550" steamid=…`) into a map. */
    private static java.util.Map<String, String> attrsOf(String attrBlob) {
        java.util.HashMap<String, String> out = new java.util.HashMap<>();
        if (attrBlob == null) return out;
        java.util.regex.Matcher m = TAG_ATTR_RE.matcher(attrBlob);
        while (m.find()) out.put(m.group(1).toLowerCase(java.util.Locale.ROOT), m.group(2));
        return out;
    }

    /** Sticker name from `[sticker type="name"]` / `[sticker]name[/sticker]`, or null. */
    private static String extractStickerName(String raw) {
        if (raw == null || !raw.toLowerCase(java.util.Locale.ROOT).contains("[sticker")) return null;
        java.util.regex.Matcher m = STICKER_RE.matcher(raw);
        if (!m.find()) return null;
        java.util.Map<String, String> attrs = attrsOf(m.group(1));
        String name = attrs.containsKey("type") ? attrs.get("type") : attrs.get("name");
        if (name == null || name.isEmpty()) name = m.group(2);
        return (name == null || name.trim().isEmpty()) ? null : name.trim();
    }

    /** If the message body is nothing but `[emoticon]…[/emoticon]` runs, return
     *  the emoticon names; null when there's any other content. */
    private static java.util.List<String> emoticonOnlyNames(String raw) {
        if (raw == null || !raw.toLowerCase(java.util.Locale.ROOT).contains("[emoticon")) return null;
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher m = EMOTICON_RE.matcher(raw);
        while (m.find()) names.add(m.group(1).trim());
        if (names.isEmpty()) return null;
        return EMOTICON_RE.matcher(raw).replaceAll("").trim().isEmpty() ? names : null;
    }

    /** App display name out of an apps.app_details response (AppDetailsDto:
     *  localizedNames map keyed by language, installDirName as fallback). */
    private static String appNameFrom(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject d = root.has("appId") ? root : null;
            if (d == null) for (String k : new String[]{"data", "details", "appDetails", "value"}) {
                JSONObject inner = root.optJSONObject(k);
                if (inner != null && inner.has("appId")) { d = inner; break; }
            }
            if (d == null) d = root;
            JSONObject names = d.optJSONObject("localizedNames");
            if (names != null) {
                for (String k : new String[]{"english", "en", "name"}) {
                    String v = names.optString(k, "");
                    if (!v.isEmpty() && !"null".equals(v)) return v;
                }
                java.util.Iterator<String> it = names.keys();
                if (it.hasNext()) {
                    String v = names.optString(it.next(), "");
                    if (!v.isEmpty() && !"null".equals(v)) return v;
                }
            }
            String dir = d.optString("installDirName", "");
            if (!dir.isEmpty() && !"null".equals(dir)) return dir;
        } catch (Throwable ignored) {}
        return "";
    }

    /** Derive a sticker name from its CDN url. Steam sticker art lives at
     *  …/economy/sticker/&lt;appid&gt;/&lt;name&gt;/… — the segment after the appid is the
     *  sticker name used by friends.send_sticker. Returns "" if it can't be found. */
    private static String stickerNameFromUrl(String url) {
        if (url == null) return "";
        int i = url.indexOf("/economy/sticker/");
        if (i < 0) return "";
        String rest = url.substring(i + "/economy/sticker/".length());
        String[] seg = rest.split("/");
        // seg[0] = appid, seg[1] = sticker name (when present).
        if (seg.length >= 2 && !seg[1].isEmpty()) return seg[1];
        if (seg.length == 1 && !seg[0].isEmpty()) return seg[0];
        return "";
    }

    private static String urlEnc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Throwable t) { return s; }
    }

    /** Strip the most common Steam BBCode tags so non-image messages read cleanly. */
    private static String stripBBCode(String text) {
        if (text == null || text.indexOf('[') < 0) return text;
        return text.replaceAll("\\[/?[a-zA-Z][^\\]]*\\]", "").trim();
    }

    private static android.graphics.Bitmap fetchBitmap(String url) {
        java.net.HttpURLConnection c = null;
        java.io.InputStream in = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            in = c.getInputStream();
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2; // thumbnails are ~512px; halve to save memory
            return android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (c != null) c.disconnect();
        }
    }
}
