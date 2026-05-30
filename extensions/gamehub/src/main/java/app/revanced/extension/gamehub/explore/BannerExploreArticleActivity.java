package app.revanced.extension.gamehub.explore;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Article / detail page reached from an Explore card whose {@code action} is
 * "article". Shows an image header (network, with gradient fallback), title,
 * an optional meta line, and the body text. Classic Views — no Compose.
 *
 * Content is passed via intent extras by {@link BhExploreActions} so this
 * activity needs no manifest of its own beyond registration. exported=false.
 */
public class BannerExploreArticleActivity extends Activity {

    static final String EXTRA_TITLE = "bh_title";
    static final String EXTRA_BODY  = "bh_body";
    static final String EXTRA_IMAGE = "bh_image";
    static final String EXTRA_META  = "bh_meta";
    static final String EXTRA_ICON  = "bh_icon";   // bundled drawable name (logo)
    static final String EXTRA_LINK  = "bh_link";   // optional URL → browser button

    private static final int ACCENT = 0xFF8B5CF6;

    private static final int BG       = 0xFF0D0D0D;
    private static final int TEXT     = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFB0B0B5;
    private static final int PLACE    = 0xFF26262B;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        String body  = getIntent().getStringExtra(EXTRA_BODY);
        String image = getIntent().getStringExtra(EXTRA_IMAGE);
        String meta  = getIntent().getStringExtra(EXTRA_META);
        String icon  = getIntent().getStringExtra(EXTRA_ICON);
        final String link = getIntent().getStringExtra(EXTRA_LINK);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(BG);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(column, new ScrollView.LayoutParams(-1, -1));

        // Header: a bundled logo drawable (centered on a dark band) when given,
        // otherwise a network photo with a scrim. Always carries a back chip.
        FrameLayout header = new FrameLayout(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(168)));

        int logoId = 0;
        if (icon != null && !icon.isEmpty()) {
            try { logoId = getResources().getIdentifier(icon, "drawable", getPackageName()); }
            catch (Throwable ignored) { }
        }

        if (logoId != 0) {
            header.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{ 0xFF1C1530, 0xFF0A0A0C }));
            ImageView logo = new ImageView(this);
            logo.setImageResource(logoId);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setAdjustViewBounds(true);
            FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(-2, dp(72));
            logoLp.gravity = Gravity.CENTER;
            logoLp.setMargins(dp(24), dp(24), dp(24), dp(24));
            header.addView(logo, logoLp);
        } else {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackgroundColor(PLACE);
            header.addView(img, new FrameLayout.LayoutParams(-1, -1));
            if (image != null && !image.isEmpty()) BhImageLoader.load(img, image);

            View scrim = new View(this);
            scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ 0x66000000, Color.TRANSPARENT, 0xCC000000 }));
            header.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        }

        TextView back = new TextView(this);
        back.setText("←  Back");
        back.setTextColor(TEXT);
        back.setTextSize(14);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setColor(0x99000000);
        backBg.setCornerRadius(dp(20));
        back.setBackground(backBg);
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(-2, -2);
        backLp.setMargins(dp(14), dp(16), 0, 0);
        header.addView(back, backLp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        column.addView(header);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(20), dp(20), dp(32));
        column.addView(content);

        TextView t = new TextView(this);
        t.setText(title != null ? title : "");
        t.setTextColor(TEXT);
        t.setTextSize(24);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(t);

        if (meta != null && !meta.isEmpty()) {
            TextView m = new TextView(this);
            m.setText(meta);
            m.setTextColor(TEXT_DIM);
            m.setTextSize(13);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(-1, -2);
            mLp.topMargin = dp(6);
            content.addView(m, mLp);
        }

        TextView b = new TextView(this);
        b.setText(body != null ? body : "");
        b.setTextColor(0xFFD8D8DC);
        b.setTextSize(16);
        b.setLineSpacing(dp(6), 1f);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, -2);
        bLp.topMargin = dp(16);
        content.addView(b, bLp);

        // Optional CTA → open a link (e.g. the GitHub repo) in the browser.
        if (link != null && !link.isEmpty()) {
            TextView btn = new TextView(this);
            btn.setText("View on GitHub  →");
            btn.setTextColor(0xFFFFFFFF);
            btn.setTextSize(15);
            btn.setTypeface(Typeface.DEFAULT_BOLD);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(20), dp(14), dp(20), dp(14));
            GradientDrawable btnBg = new GradientDrawable();
            btnBg.setColor(ACCENT);
            btnBg.setCornerRadius(dp(12));
            btn.setBackground(btnBg);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, -2);
            btnLp.topMargin = dp(24);
            content.addView(btn, btnLp);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link));
                        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        android.util.Log.w("BhExplore", "open link failed", t);
                    }
                }
            });
        }

        setContentView(scroller);
        hideSystemBars();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        try {
            Window window = getWindow();
            View decor = window.getDecorView();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController c = decor.getWindowInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable t) {
            android.util.Log.w("BhExplore", "hideSystemBars failed", t);
        }
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (v * density);
    }
}
