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

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService IMG_IO = Executors.newFixedThreadPool(2);
    private static final java.util.regex.Pattern URL_RE =
            java.util.regex.Pattern.compile("https?://[^\\s\\]\\[\"']+");

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

    private static final class Controller {
        private final Activity act;
        private WindowManager wm;
        private WindowManager.LayoutParams lp;
        private LinearLayout container;  // [panel][pill]
        private LinearLayout panel;
        private TextView pill;
        private TextView status;
        private LinearLayout listCol;    // friend rows / message rows
        private boolean expanded = false;
        private boolean attached = false;
        private long openFriendId = 0;   // 0 = showing friends list

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
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.token = token;
                lp.y = dp(180);
                // Pan the window up when the soft keyboard shows so the composer stays visible.
                lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;

                wm.addView(container, lp);
                attached = true;
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
            try { if (wm != null && container != null) wm.removeView(container); } catch (Throwable ignored) {}
        }

        // ── views ────────────────────────────────────────────────────────────

        private void buildPill() {
            pill = new TextView(act);
            pill.setText("💬"); // 💬
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(10), dp(14), dp(10), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(COL_PILL_BG);
            bg.setCornerRadii(new float[]{0,0, dp(14),dp(14), dp(14),dp(14), 0,0});
            pill.setBackground(bg);
            pill.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { setExpanded(!expanded); }
            });
            container.addView(pill);
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

            ScrollView scroll = new ScrollView(act);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(320)));
            listCol = new LinearLayout(act);
            listCol.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(listCol);
            panel.addView(scroll);

            panel.setVisibility(View.GONE);
            container.addView(panel);
        }

        private String currentTitle = "";
        private String lastFriendsJson;
        private boolean offlineCollapsed = true;

        private void setExpanded(boolean exp) {
            expanded = exp;
            panel.setVisibility(exp ? View.VISIBLE : View.GONE);
            // Take key/IME focus only while the panel is open, so the composer's
            // EditText can receive text; collapse hands input back to the game.
            setWindowFocusable(exp);
            if (exp && listCol.getChildCount() == 0) loadFriends();
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
            setStatus("Loading friends…");
            IO.execute(new Runnable() {
                public void run() {
                    if (!BhSteamBridge.isAvailable()) { post(new Runnable(){ public void run(){ showNotReady(); } }); return; }
                    final String json = BhSteamBridge.request("friends.list", "{}", 8000);
                    post(new Runnable() { public void run() { renderFriends(json); } });
                }
            });
        }

        private void loadHistory(final long steamId, final String name) {
            openFriendId = steamId;
            currentTitle = name;
            setStatus("Loading messages…");
            IO.execute(new Runnable() {
                public void run() {
                    String payload = "{\"steamId\":" + steamId + ",\"limit\":30}";
                    final String json = BhSteamBridge.request("friends.message_history", payload, 8000);
                    post(new Runnable() { public void run() { renderHistory(json, name); } });
                }
            });
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

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));

            View dot = new View(act);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(inGame ? COL_INGAME : online ? COL_ONLINE : COL_OFFLINE);
            dot.setBackground(d);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
            dlp.rightMargin = dp(10);
            dot.setLayoutParams(dlp);
            row.addView(dot);

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
            // back row
            TextView back = new TextView(act);
            back.setText("‹ Back to friends");
            back.setTextColor(COL_ACCENT);
            back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            back.setPadding(0, dp(2), 0, dp(8));
            back.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { loadFriends(); } });
            listCol.addView(back);

            if (json == null) { setStatus("No history yet — say hello."); addComposer(openFriendId); return; }
            try {
                JSONArray arr = asArray(json, "messages", "items", "data", "history", "value");
                if (arr == null) { setStatus("Chat with " + name); addRaw(json); addComposer(openFriendId); return; }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String text = firstNonEmpty(m.optString("message"), m.optString("text"),
                            m.optString("body"), m.optString("content"), "");
                    boolean fromMe = m.optBoolean("fromLocalUser", m.optBoolean("isOutgoing", false));
                    String imgUrl = extractImageUrl(text);
                    if (imgUrl != null) {
                        listCol.addView(imageRow(imgUrl, fromMe));
                    } else {
                        TextView mv = new TextView(act);
                        mv.setText((fromMe ? "You: " : "") + stripBBCode(text));
                        mv.setTextColor(fromMe ? COL_SUBTEXT : COL_TEXT);
                        mv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                        mv.setPadding(0, dp(4), 0, dp(4));
                        listCol.addView(mv);
                    }
                }
                setStatus("Chat with " + name + " · " + arr.length() + " messages");
                addComposer(openFriendId);
            } catch (Throwable t) {
                setStatus("Chat with " + name); addRaw(json); addComposer(openFriendId);
            }
        }

        /** Bottom-of-conversation message composer: input + Send → friends.send_message. */
        private void addComposer(final long steamId) {
            if (steamId == 0) return;
            LinearLayout bar = new LinearLayout(act);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(0, dp(8), 0, dp(2));

            final EditText input = new EditText(act);
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
            listCol.addView(bar);
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

    /**
     * Steam chat image messages arrive as BBCode carrying an https image URL
     * (e.g. {@code [img src=…steamusercontent…]…[/img]}). Pull the first such URL
     * so we can render it inline; returns null for ordinary text messages.
     */
    private static String extractImageUrl(String text) {
        if (text == null) return null;
        boolean looksImg = text.contains("[img") || text.contains("steamusercontent")
                || text.contains("steamuserimages");
        if (!looksImg) return null;
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
