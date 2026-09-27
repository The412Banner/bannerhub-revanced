package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * The detail pages' <b>Media</b> tab: a row of trailer posters and a row of screenshot
 * thumbnails, a full-screen swipe viewer for the screenshots and one playback handoff for the
 * videos (GOG only embeds YouTube: {@code vnd.youtube:} intent, then the watch URL).
 *
 * Layout rules follow Bannerlator's shared tab: thumbnails only in the strip, the full-size
 * image only in the viewer; strips are full-bleed (16dp content padding) rather than inside a
 * card so a 300dp tile still fits in portrait and the next tile peeks in either orientation.
 * Pinch-zoom is not ported — the viewer is swipe / tap-zone only.
 */
final class GogMediaView {

    private static final int TILE_W_DP = 300;
    private static final int TILE_H_DP = 169;

    private GogMediaView() {}

    /** The tab body: spinner while loading, a notice when empty, else the two strips. */
    static View build(final Activity ctx, GogStoreCatalog.StoreMedia media, boolean loading) {
        LinearLayout col = BhStoreUi.column(ctx);
        col.setPadding(0, BhStoreUi.dp(ctx, 6), 0, BhStoreUi.dp(ctx, 8));
        if (media == null && loading) {
            ProgressBar pb = new ProgressBar(ctx);
            LinearLayout.LayoutParams l = BhStoreUi.lp(-2, -2);
            l.gravity = Gravity.CENTER_HORIZONTAL;
            l.topMargin = BhStoreUi.dp(ctx, 24);
            col.addView(pb, l);
            return col;
        }
        if (media == null || media.isEmpty()) {
            col.addView(BhStoreUi.notice(ctx, "No media", "GOG.com published no videos or screenshots for this title.", null, null));
            return col;
        }
        if (!media.videos.isEmpty()) {
            col.addView(rowTitle(ctx, "Videos"));
            LinearLayout strip = stripRow(ctx);
            for (final GogStoreCatalog.MediaVideo v : media.videos) {
                FrameLayout tile = tile(ctx, v.poster);
                TextView play = BhStoreUi.text(ctx, "▶", 18f, BhStoreUi.TEXT, true);
                play.setGravity(Gravity.CENTER);
                android.graphics.drawable.GradientDrawable pb = new android.graphics.drawable.GradientDrawable();
                pb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                pb.setColor(0x8C000000);
                play.setBackground(pb);
                FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(BhStoreUi.dp(ctx, 46), BhStoreUi.dp(ctx, 46));
                pl.gravity = Gravity.CENTER;
                tile.addView(play, pl);
                TextView cap = BhStoreUi.oneLine(BhStoreUi.text(ctx, v.title, 12f, BhStoreUi.TEXT, true), 1);
                cap.setBackground(new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xBF000000}));
                cap.setPadding(BhStoreUi.dp(ctx, 10), BhStoreUi.dp(ctx, 18), BhStoreUi.dp(ctx, 10), BhStoreUi.dp(ctx, 8));
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2);
                cl.gravity = Gravity.BOTTOM;
                tile.addView(cap, cl);
                tile.setOnClickListener(x -> openVideo(ctx, v));
                strip.addView(tile, tileLp(ctx));
            }
            col.addView(wrapStrip(ctx, strip));
            col.addView(BhStoreUi.spacer(ctx, 14));
        }
        if (!media.screenshots.isEmpty()) {
            col.addView(rowTitle(ctx, "Screenshots"));
            LinearLayout strip = stripRow(ctx);
            final List<GogStoreCatalog.MediaImage> shots = media.screenshots;
            for (int i = 0; i < shots.size(); i++) {
                final int idx = i;
                FrameLayout tile = tile(ctx, shots.get(i).thumb);
                tile.setOnClickListener(x -> openViewer(ctx, shots, idx));
                strip.addView(tile, tileLp(ctx));
            }
            col.addView(wrapStrip(ctx, strip));
            col.addView(BhStoreUi.spacer(ctx, 8));
        }
        return col;
    }

    private static TextView rowTitle(Activity ctx, String s) {
        TextView tv = BhStoreUi.text(ctx, s, 14f, BhStoreUi.TEXT, true);
        tv.setPadding(BhStoreUi.dp(ctx, 16), 0, BhStoreUi.dp(ctx, 16), BhStoreUi.dp(ctx, 8));
        return tv;
    }

    private static LinearLayout stripRow(Activity ctx) {
        LinearLayout row = BhStoreUi.row(ctx);
        row.setPadding(BhStoreUi.dp(ctx, 16), 0, BhStoreUi.dp(ctx, 6), 0);
        return row;
    }

    private static View wrapStrip(Activity ctx, LinearLayout strip) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(strip, new FrameLayout.LayoutParams(-2, -2));
        return hs;
    }

    private static LinearLayout.LayoutParams tileLp(Activity ctx) {
        LinearLayout.LayoutParams l = BhStoreUi.lp(BhStoreUi.dp(ctx, TILE_W_DP), BhStoreUi.dp(ctx, TILE_H_DP));
        l.rightMargin = BhStoreUi.dp(ctx, 10);
        return l;
    }

    /** One 300x169 rounded tile, focusable for the pad. */
    private static FrameLayout tile(Activity ctx, String image) {
        FrameLayout box = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg = BhStoreUi.roundBg(ctx, BhStoreUi.SURFACE_VAR, 10, BhStoreUi.OUTLINE);
        box.setBackground(bg);
        box.setClipToOutline(true);
        BhStoreUi.focusRing(box, bg, BhStoreUi.SURFACE_VAR, BhStoreUi.CARD_HI);
        ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        GogImageLoader.load(iv, image);
        return box;
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    static void openVideo(Activity ctx, GogStoreCatalog.MediaVideo v) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + v.youtubeId)));
        } catch (Exception e) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + v.youtubeId)));
            } catch (Exception ignored) {}
        }
    }

    // ── Screenshot viewer ─────────────────────────────────────────────────────

    /**
     * Full-screen black viewer: fit-centre image, "3 / 12" counter, close button, swipe or tap the
     * left / right thirds to move. The full-size rendition is loaded on demand per page.
     */
    static void openViewer(final Activity ctx, final List<GogStoreCatalog.MediaImage> shots, int start) {
        if (shots == null || shots.isEmpty()) return;
        final Dialog dlg = new Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final int[] index = {Math.max(0, Math.min(shots.size() - 1, start))};

        FrameLayout root = new FrameLayout(ctx);
        root.setBackgroundColor(0xFF000000);
        final ImageView iv = new ImageView(ctx);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        final ProgressBar spinner = new ProgressBar(ctx);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2);
        sl.gravity = Gravity.CENTER;
        root.addView(spinner, sl);

        final TextView counter = BhStoreUi.text(ctx, "", 13f, BhStoreUi.TEXT, true);
        counter.setPadding(BhStoreUi.dp(ctx, 10), BhStoreUi.dp(ctx, 4), BhStoreUi.dp(ctx, 10), BhStoreUi.dp(ctx, 4));
        counter.setBackground(BhStoreUi.roundBg(ctx, 0x99000000, 12));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2);
        cl.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        cl.topMargin = BhStoreUi.dp(ctx, 12);
        root.addView(counter, cl);

        TextView close = BhStoreUi.text(ctx, "✕", 18f, BhStoreUi.TEXT, true);
        close.setPadding(BhStoreUi.dp(ctx, 12), BhStoreUi.dp(ctx, 6), BhStoreUi.dp(ctx, 12), BhStoreUi.dp(ctx, 6));
        close.setBackground(BhStoreUi.roundBg(ctx, 0x99000000, 12));
        close.setFocusable(true);
        close.setOnClickListener(v -> dlg.dismiss());
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(-2, -2);
        xl.gravity = Gravity.TOP | Gravity.END;
        xl.setMargins(0, BhStoreUi.dp(ctx, 8), BhStoreUi.dp(ctx, 8), 0);
        root.addView(close, xl);

        final Runnable show = new Runnable() {
            @Override public void run() {
                counter.setText((index[0] + 1) + " / " + shots.size());
                iv.setImageDrawable(null);
                spinner.setVisibility(View.VISIBLE);
                GogStoreCatalog.MediaImage m = shots.get(index[0]);
                java.util.List<String> chain = new java.util.ArrayList<>(2);
                chain.add(m.full);
                chain.add(m.thumb);
                GogImageLoader.load(iv, chain, bmp -> spinner.setVisibility(View.GONE));
            }
        };
        final Runnable next = () -> { if (index[0] < shots.size() - 1) { index[0]++; show.run(); } };
        final Runnable prev = () -> { if (index[0] > 0) { index[0]--; show.run(); } };

        final GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) < BhStoreUi.dp(ctx, 60) || Math.abs(vx) < 300) return false;
                if (dx < 0) next.run(); else prev.run();
                return true;
            }
            @Override public boolean onSingleTapUp(MotionEvent e) {
                int w = root.getWidth();
                if (e.getX() < w / 3f) prev.run();
                else if (e.getX() > w * 2f / 3f) next.run();
                return true;
            }
        });
        root.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev) || ev.getAction() == MotionEvent.ACTION_UP);
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener((v, keyCode, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { next.run(); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { prev.run(); return true; }
            return false;
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        dlg.show();
        show.run();
    }
}
