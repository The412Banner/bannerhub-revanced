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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.WeakHashMap;

/**
 * In-game Steam friends/chat overlay — READ-ONLY PROTOTYPE.
 *
 * A Banner-owned classic-View pill + slide-out panel attached over the Wine
 * game surface (identical WindowManager technique to {@code BhPerfOverlay}),
 * gated by the Banner Tools → Steam Chat master toggle. The panel pulls your
 * Steam friends list (and, on tap, a friend's recent message history) from the
 * in-process Steam client via {@link BhSteamBridge} — request/response only;
 * live push (steam:chat-message Flow) is a later increment.
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

                WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
                p.gravity = Gravity.TOP | Gravity.START;
                p.token = token;
                p.y = dp(180);

                wm.addView(container, p);
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
            status.setText("Read-only prototype");
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

        private void setExpanded(boolean exp) {
            expanded = exp;
            panel.setVisibility(exp ? View.VISIBLE : View.GONE);
            if (exp && listCol.getChildCount() == 0) loadFriends();
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
            setStatus("Steam not connected — sign in to Steam in GameHub first.");
        }

        private void renderFriends(String json) {
            listCol.removeAllViews();
            if (json == null) { setStatus("No response (not signed in, or request failed)."); return; }
            try {
                JSONArray arr = asArray(json, "friends", "items", "data", "list", "value");
                if (arr == null) { setStatus("Unexpected response."); addRaw(json); return; }
                int online = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject f = arr.optJSONObject(i);
                    if (f == null) continue;
                    if (f.optBoolean("isOnline", false)) online++;
                    listCol.addView(friendRow(f));
                }
                setStatus(arr.length() + " friends · " + online + " online");
            } catch (Throwable t) {
                setStatus("Parse error."); addRaw(json);
            }
        }

        private View friendRow(final JSONObject f) {
            final long steamId = f.optLong("steamId", 0);
            String name = firstNonEmpty(f.optString("nickname"), f.optString("displayName"),
                    f.optString("personaName"), "Friend " + steamId);
            boolean online = f.optBoolean("isOnline", false);
            boolean inGame = f.optBoolean("isInGame", false);
            String game = f.optString("gameName", "");

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

            if (json == null) { setStatus("No messages (or request failed)."); return; }
            try {
                JSONArray arr = asArray(json, "messages", "items", "data", "history", "value");
                if (arr == null) { setStatus("Chat with " + name); addRaw(json); return; }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) continue;
                    String text = firstNonEmpty(m.optString("message"), m.optString("text"),
                            m.optString("body"), m.optString("content"), "");
                    long sender = m.optLong("senderSteamId", m.optLong("steamId", 0));
                    boolean fromMe = m.optBoolean("fromLocalUser", m.optBoolean("isOutgoing", false));
                    TextView mv = new TextView(act);
                    mv.setText((fromMe ? "You: " : "") + text);
                    mv.setTextColor(fromMe ? COL_SUBTEXT : COL_TEXT);
                    mv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    mv.setPadding(0, dp(4), 0, dp(4));
                    listCol.addView(mv);
                }
                setStatus("Chat with " + name + " · " + arr.length() + " messages (read-only)");
            } catch (Throwable t) {
                setStatus("Chat with " + name); addRaw(json);
            }
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
}
