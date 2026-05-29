package app.revanced.extension.gamehub.explore;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

/**
 * Routes an Explore card tap to one of OUR own handlers — never into xiaoji's
 * server-backed game-detail/download flow (that would be a dead card offline;
 * see GOG_LIBRARY_TAB_DESIGN §42). Each {@code action} string maps to a local
 * destination.
 *
 * v1 actions:
 *   "gog"  → GogMainActivity (login / owned-library hub)
 *   "url"  → ACTION_VIEW the {@code arg} link (e.g. a store page)
 * Unknown actions show a "coming soon" toast (forward-compatible: a future
 * bundled/remote manifest can add cards before the handler ships).
 */
public final class BhExploreActions {

    private static final String TAG = "BhExplore";

    private static final String GOG_HUB =
        "app.revanced.extension.gamehub.gog.GogMainActivity";

    private BhExploreActions() { }

    public static void dispatch(Activity host, String action, String arg) {
        if (host == null || action == null) return;
        try {
            switch (action) {
                case "gog":
                    openActivity(host, GOG_HUB);
                    break;
                case "url":
                    if (arg != null && !arg.isEmpty()) {
                        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(arg));
                        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        host.startActivity(view);
                    }
                    break;
                default:
                    Toast.makeText(host, "Coming soon", Toast.LENGTH_SHORT).show();
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "card action '" + action + "' failed", t);
            Toast.makeText(host, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private static void openActivity(Activity host, String className) throws Exception {
        Class<?> cls = Class.forName(className);
        Intent intent = new Intent(host, cls);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        host.startActivity(intent);
    }
}
