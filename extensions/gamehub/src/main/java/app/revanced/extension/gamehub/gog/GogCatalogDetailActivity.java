package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Detail page for a catalog title the user does NOT own — the Steam detail layout
 * ({@link GogDetailScaffold}) with GOG's description and media, a price row, and one primary
 * action that opens the store page in the browser ("Get for free" / "View on GOG.com").
 *
 * The {@link GogCatalogItem} rides in the intent as JSON; the description + media are fetched on
 * open from the public product endpoint ({@link GogStoreCatalog#product}). Owned titles never
 * land here — the storefront routes them to {@link GogGameDetailActivity}, which carries
 * install / DLC / cloud saves.
 */
public class GogCatalogDetailActivity extends Activity {

    private static final String EXTRA_ITEM = "catalog_item_json";

    public static Intent intent(Context ctx, GogCatalogItem item) {
        return new Intent(ctx, GogCatalogDetailActivity.class).putExtra(EXTRA_ITEM, item.toJson().toString());
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private GogCatalogItem item;
    private GogDetailScaffold scaffold;
    private GogStoreCatalog.ProductDetail detail;
    private boolean loading = true;
    private int tab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String json = getIntent().getStringExtra(EXTRA_ITEM);
        try { item = json == null ? null : GogCatalogItem.fromJson(new JSONObject(json)); } catch (Exception e) { item = null; }
        if (item == null || item.id.isEmpty()) { finish(); return; }

        scaffold = new GogDetailScaffold(this, item.title, this::finish,
                () -> startActivity(new Intent(this, BhDownloadsActivity.class)));
        setContentView(scaffold.root);
        BhStoreUi.hideSystemBars(this);

        scaffold.loadHero(item.wideArt());
        List<View> sub = new ArrayList<>();
        sub.add(BhStoreUi.priceRow(this, item));
        if (!item.tags.isEmpty()) sub.add(BhStoreUi.oneLine(BhStoreUi.text(this, item.tags, 11f, BhStoreUi.MUTED, false), 1));
        scaffold.setSubtitle(sub);

        String primaryLabel = item.isFree ? "Get for free on GOG.com" : "View on GOG.com";
        scaffold.primary.setAction(primaryLabel, !item.storeUrl.isEmpty(), v -> openStore());
        List<GogDetailScaffold.GearItem> gear = new ArrayList<>();
        gear.add(new GogDetailScaffold.GearItem("Open in browser", !item.storeUrl.isEmpty(), false, this::openStore));
        scaffold.setGear(gear);
        scaffold.setInfoLine(item.isFree
                ? "Free claims go through the store's own checkout — you'll be signed in there."
                : "Purchases go through GOG.com. Owned titles appear in your Library after a sync.");

        render();
        new Thread(() -> {
            GogStoreCatalog.ProductDetail d = null;
            try { d = GogStoreCatalog.product(item.id); } catch (Throwable ignored) {}
            final GogStoreCatalog.ProductDetail res = d;
            ui.post(() -> {
                if (isFinishing()) return;
                detail = res;
                loading = false;
                if (detail != null && detail.background != null) {
                    List<String> chain = new ArrayList<>();
                    chain.add(detail.background);
                    chain.addAll(item.wideArt());
                    scaffold.loadHero(chain);
                }
                render();
            });
        }, "gog-catalog-detail").start();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) BhStoreUi.hideSystemBars(this);
    }

    private void openStore() {
        if (item.storeUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.storeUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private void render() {
        boolean mediaVisible = detail != null && detail.media != null && !detail.media.isEmpty();
        String[] tabs = mediaVisible ? new String[]{"Details", "Media"} : new String[]{"Details"};
        if (tab >= tabs.length) tab = 0;
        scaffold.setTabs(tabs, tab, idx -> { tab = idx; render(); });
        if (mediaVisible) scaffold.setTabBadge(1, String.valueOf(detail.media.count()));
        scaffold.setBody(tab == 1 && mediaVisible ? GogMediaView.build(this, detail.media, false) : buildDetails());
    }

    private View buildDetails() {
        LinearLayout col = BhStoreUi.column(this);
        col.setPadding(BhStoreUi.dp(this, 16), BhStoreUi.dp(this, 6), BhStoreUi.dp(this, 16), BhStoreUi.dp(this, 16));

        LinearLayout chips = BhStoreUi.row(this);
        if (!item.developer.isEmpty()) chips.addView(BhStoreUi.infoChip(this, item.developer), BhStoreUi.chipLp(this));
        String rel = detail != null && !detail.releaseDate.isEmpty() ? detail.releaseDate : item.releaseDate;
        if (!rel.isEmpty()) chips.addView(BhStoreUi.infoChip(this, BhStoreUi.formatDate(rel)), BhStoreUi.chipLp(this));
        String rating = item.extra.get("rating");
        if (rating != null && !rating.isEmpty()) chips.addView(BhStoreUi.infoChip(this, rating + "% positive"), BhStoreUi.chipLp(this));
        String type = item.extra.get("type");
        if ("pack".equalsIgnoreCase(type)) chips.addView(BhStoreUi.infoChip(this, "Bundle"), BhStoreUi.chipLp(this));
        if (chips.getChildCount() > 0) col.addView(chips, BhStoreUi.lp(-1, -2));

        String lead = detail != null ? detail.lead : item.description;
        String full = detail != null ? detail.full : "";
        if (loading && detail == null) {
            col.addView(BhStoreUi.text(this, "Loading description…", 12f, BhStoreUi.MUTED, false));
        } else if ((lead == null || lead.isEmpty()) && (full == null || full.isEmpty())) {
            col.addView(BhStoreUi.text(this, "GOG.com has no description for this title.", 12f, BhStoreUi.MUTED, false));
        } else {
            if (lead != null && !lead.isEmpty()) {
                TextView tv = BhStoreUi.text(this, Html.fromHtml(lead, Html.FROM_HTML_MODE_COMPACT).toString().trim(), 13f, BhStoreUi.TEXT2, true);
                LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2); l.topMargin = BhStoreUi.dp(this, 10);
                col.addView(tv, l);
            }
            if (full != null && !full.isEmpty()) {
                TextView tv = BhStoreUi.text(this, Html.fromHtml(full, Html.FROM_HTML_MODE_COMPACT).toString().trim(), 12f, BhStoreUi.TEXT2, false);
                LinearLayout.LayoutParams l = BhStoreUi.lp(-1, -2); l.topMargin = BhStoreUi.dp(this, 10);
                col.addView(tv, l);
            }
        }
        return col;
    }
}
