package app.revanced.extension.gamehub.explore;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny async image loader for the Explore screen's network artwork (hero
 * banners, game covers, news thumbnails). Pure-framework — no Glide/Coil/okhttp
 * dependency to keep the ReVanced extension self-contained.
 *
 * Memory-cached (LruCache), loaded off the main thread on a small pool, posted
 * back on the main thread. Each target ImageView is tagged with its URL so a
 * recycled view (horizontal scroll) only accepts the bitmap it actually asked
 * for. Anything that fails (offline, 404, decode error) leaves the caller's
 * placeholder in place — the screen stays usable with no network.
 */
final class BhImageLoader {

    private static final String TAG = "BhExplore";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ~8 MB of decoded bitmaps is plenty for a scrolling card list.
    private static final LruCache<String, Bitmap> CACHE =
        new LruCache<String, Bitmap>(8 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };

    private BhImageLoader() { }

    static void load(final ImageView target, final String url) {
        if (target == null || url == null || url.isEmpty()) return;

        // Guard against view recycling: remember what THIS view wants.
        target.setTag(url);

        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }

        POOL.execute(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = fetch(url);
                if (bmp == null) return;
                CACHE.put(url, bmp);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        // Only apply if the view is still asking for this URL.
                        if (url.equals(target.getTag())) {
                            target.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = conn.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (Throwable t) {
            // Offline / blocked / decode failure — caller keeps its placeholder.
            Log.d(TAG, "image load failed: " + url + " (" + t.getMessage() + ")");
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
