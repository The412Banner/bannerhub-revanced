package app.revanced.extension.gamehub.explore;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * BannerHub-owned Explore screen — opened when the user taps the (otherwise
 * unused) "Explore" bottom-nav tab, hijacked by {@code ExploreTabHijackPatch}
 * via {@link com.xj.winemu.explore.BhExploreTabClick}.
 *
 * Instead of forging xiaoji's server-driven discovery feed (which we don't
 * control), this renders content WE own from {@link BhExploreManifest}
 * (bundled JSON in v1), with each card routed to our own handlers by
 * {@link BhExploreActions}. Classic programmatic Views only — our ReVanced
 * extension has no Compose compiler plugin. See GOG_LIBRARY_TAB_DESIGN §42.
 */
public class BannerExploreActivity extends Activity {

    private static final int BG          = 0xFF0D0D0D;
    private static final int CARD_BG     = 0xFF1C1C1E;
    private static final int CARD_STROKE = 0xFF2E2E32;
    private static final int TEXT        = 0xFFFFFFFF;
    private static final int TEXT_DIM    = 0xFFB0B0B5;
    private static final int ACCENT      = 0xFF8B5CF6; // GOG-ish purple

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(BG);
        scroller.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroller.addView(column, new ScrollView.LayoutParams(-1, -1));

        TextView title = new TextView(this);
        title.setText("Explore");
        title.setTextColor(TEXT);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), 0, 0, dp(16));
        column.addView(title);

        List<BhExploreManifest.Rail> rails = BhExploreManifest.load(this);
        if (rails.isEmpty()) {
            column.addView(emptyState());
        } else {
            for (BhExploreManifest.Rail rail : rails) {
                column.addView(buildRail(rail));
            }
        }

        setContentView(scroller);
    }

    // ── Rail ────────────────────────────────────────────────────────────────

    private View buildRail(BhExploreManifest.Rail rail) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp =
            new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = dp(20);
        section.setLayoutParams(sectionLp);

        if (rail.title != null && !rail.title.isEmpty()) {
            TextView header = new TextView(this);
            header.setText(rail.title);
            header.setTextColor(TEXT);
            header.setTextSize(18);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(dp(4), 0, 0, dp(10));
            section.addView(header);
        }

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hsv.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));

        for (BhExploreManifest.Card card : rail.cards) {
            row.addView(buildCard(card));
        }
        section.addView(hsv);
        return section;
    }

    // ── Card ──────────────────────────────────────────────────────────────

    private View buildCard(final BhExploreManifest.Card card) {
        LinearLayout cardView = new LinearLayout(this);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(14), dp(14), dp(14), dp(14));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_BG);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), CARD_STROKE);
        cardView.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), -2);
        lp.rightMargin = dp(12);
        cardView.setLayoutParams(lp);

        // Accent chip
        View chip = new View(this);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(ACCENT);
        chipBg.setCornerRadius(dp(6));
        chip.setBackground(chipBg);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        chipLp.bottomMargin = dp(12);
        cardView.addView(chip, chipLp);

        TextView label = new TextView(this);
        label.setText(card.label);
        label.setTextColor(TEXT);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        cardView.addView(label);

        if (card.subtitle != null && !card.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(card.subtitle);
            sub.setTextColor(TEXT_DIM);
            sub.setTextSize(12);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
            subLp.topMargin = dp(4);
            cardView.addView(sub, subLp);
        }

        cardView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BhExploreActions.dispatch(BannerExploreActivity.this, card.action, card.arg);
            }
        });
        return cardView;
    }

    private View emptyState() {
        TextView tv = new TextView(this);
        tv.setText("Nothing here yet.");
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(48), 0, 0);
        return tv;
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (v * density);
    }
}
