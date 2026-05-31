package app.revanced.extension.gamehub.explore;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Routes an Explore card tap to one of OUR own handlers — never into xiaoji's
 * server-backed game-detail/download flow (that would be a dead card offline;
 * see GOG_LIBRARY_TAB_DESIGN §42).
 *
 * Actions:
 *   "gog"         → GogMainActivity (login / owned-library hub)
 *   "url"         → ACTION_VIEW the card's {@code arg} link
 *   "article"     → BannerExploreArticleActivity, fed the card's title/body/image
 *   "bannertools" → Banner Tools dialog (from-Explore: per-game tiles greyed)
 *   "soon"        → "Coming soon" toast (placeholder cards)
 * Unknown actions also show the "coming soon" toast (forward-compatible: a
 * future bundled/remote manifest can add cards before the handler ships).
 */
public final class BhExploreActions {

    private static final String TAG = "BhExplore";

    private static final String GOG_HUB =
        "app.revanced.extension.gamehub.gog.GogMainActivity";

    private BhExploreActions() { }

    public static void dispatch(Activity host, BhExploreManifest.Card card) {
        if (host == null || card == null || card.action == null) return;
        try {
            switch (card.action) {
                case "gog":
                    openActivity(host, GOG_HUB);
                    break;
                case "bannertools":
                    // Opens the same Banner Tools dialog as the per-game menus,
                    // but flagged as "from Explore" so the per-game tiles
                    // (Vibration/GPU Spoof/Renderer/Game ID) grey out while the
                    // global tiles (Audio, GOG, Overlay, Root) stay usable.
                    com.xj.winemu.bannertools.BhBannerToolsMenuRowClick.openFromExplore();
                    break;
                case "url":
                    if (card.arg != null && !card.arg.isEmpty()) {
                        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(card.arg));
                        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        host.startActivity(view);
                    }
                    break;
                case "article":
                    openArticle(host, card);
                    break;
                case "soon":
                    Toast.makeText(host, "Coming soon", Toast.LENGTH_SHORT).show();
                    break;
                default:
                    Toast.makeText(host, "Coming soon", Toast.LENGTH_SHORT).show();
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "card action '" + card.action + "' failed", t);
            Toast.makeText(host, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openArticle(Activity host, BhExploreManifest.Card card) {
        Intent intent = new Intent(host, BannerExploreArticleActivity.class);
        intent.putExtra(BannerExploreArticleActivity.EXTRA_TITLE, card.label);
        intent.putExtra(BannerExploreArticleActivity.EXTRA_BODY,
            card.body != null ? card.body : (card.subtitle != null ? card.subtitle : ""));
        intent.putExtra(BannerExploreArticleActivity.EXTRA_IMAGE, card.image);
        intent.putExtra(BannerExploreArticleActivity.EXTRA_META, card.date);
        intent.putExtra(BannerExploreArticleActivity.EXTRA_ICON, card.icon);
        intent.putExtra(BannerExploreArticleActivity.EXTRA_LINK, card.arg);
        host.startActivity(intent);
    }

    private static void openActivity(Activity host, String className) throws Exception {
        Class<?> cls = Class.forName(className);
        Intent intent = new Intent(host, cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        host.startActivity(intent);
    }
}
