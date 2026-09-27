package app.revanced.extension.gamehub.gog;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Async cover loader for the GOG storefront (rails, tiles, heroes, media strips). Pure-framework,
 * like the Explore screen's loader — no Glide/Coil dependency in the extension.
 *
 * Takes an ORDERED candidate chain (wide art then tall, or the GOG avatar size formatters):
 * each miss falls through to the next URL, and a total miss leaves the caller's placeholder in
 * place — never a spinner, never a broken image. Memory-cached (LruCache), decoded off the main
 * thread on a small pool, posted back on main. Each target ImageView is tagged with its chain so
 * a recycled view only accepts the bitmap it actually asked for.
 *
 * Large sources (GOG's 2x screenshots are ~2560px wide) are downsampled to at most
 * {@link #MAX_DIM} on the long edge so a strip of them cannot exhaust the heap.
 */
final class GogImageLoader {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_DIM = 1600;

    private static final LruCache<String, Bitmap> CACHE =
        new LruCache<String, Bitmap>(24 * 1024 * 1024) {
            @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
        };

    /** Sentinel for URLs that failed: skipped on the next request instead of re-fetched. */
    private static final java.util.Set<String> MISSES =
        Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private GogImageLoader() {}

    interface Listener { void onLoaded(Bitmap bmp); }

    static void load(ImageView target, String url) {
        List<String> one = new ArrayList<>(1);
        if (url != null && !url.trim().isEmpty()) one.add(url);
        load(target, one, null);
    }

    static void load(ImageView target, List<String> candidates) {
        load(target, candidates, null);
    }

    static void load(final ImageView target, List<String> candidates, final Listener listener) {
        if (target == null) return;
        final List<String> urls = new ArrayList<>();
        if (candidates != null) for (String c : candidates) {
            String u = GogStoreCatalog.absolutize(c);
            if (!u.isEmpty() && !urls.contains(u) && !MISSES.contains(u)) urls.add(u);
        }
        if (urls.isEmpty()) return;
        final String tag = urls.toString();
        target.setTag(tag);

        for (String u : urls) {
            Bitmap cached = CACHE.get(u);
            if (cached != null) {
                target.setImageBitmap(cached);
                if (listener != null) listener.onLoaded(cached);
                return;
            }
        }

        POOL.execute(() -> {
            Bitmap bmp = null;
            for (String u : urls) {
                bmp = fetch(u);
                if (bmp != null) { CACHE.put(u, bmp); break; }
                MISSES.add(u);
            }
            if (bmp == null) return;
            final Bitmap result = bmp;
            MAIN.post(() -> {
                if (tag.equals(target.getTag())) {
                    target.setImageBitmap(result);
                    if (listener != null) listener.onLoaded(result);
                }
            });
        });
    }

    /** Blocking fetch + downsample; worker thread only. Null on any failure. */
    static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", BhStoreNet.BROWSER_UA);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            byte[] bytes;
            try (InputStream in = conn.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                bytes = bos.toByteArray();
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            int sample = 1;
            int longEdge = Math.max(opts.outWidth, opts.outHeight);
            while (longEdge / sample > MAX_DIM) sample *= 2;
            BitmapFactory.Options real = new BitmapFactory.Options();
            real.inSampleSize = sample;
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, real);
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
