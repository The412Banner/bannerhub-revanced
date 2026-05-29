package app.revanced.extension.gamehub.gog;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * HEAD-probe-based latency ranking for GOG / Epic / generic CDN base URLs.
 *
 * Ported from utkarshdalal/GameNative CdnRankingUtils.kt (PR #1220 by Utkarsh
 * Dalal). The Kotlin/OkHttp version is reimplemented here in Java/
 * HttpURLConnection so it fits BannerHub's existing networking stack without
 * pulling in new dependencies.
 *
 * Used by:
 *   - GogDownloadManager.runGen2 — ranks the secure_link CDN list at download
 *     start so the fastest CDN is tried first and retries cycle to other
 *     CDNs instead of hammering the same dead edge node.
 *   - Future: GOG download dialog CDN picker — shows latency next to each
 *     option so power users can pin a specific endpoint.
 *
 * Reachability heuristic: response codes 200..499 count as "reachable" — 403
 * against a bare CDN host (no secure-link token in the URL) is the typical
 * GOG response and tells us the edge is alive even though it refused the
 * unauthenticated request. Treating 5xx + connection errors as unreachable.
 */
public final class BhCdnHelper {

    private BhCdnHelper() {}

    /** Single CDN probe result. Used by the picker UI to show latency. */
    public static final class ProbeResult {
        public final String url;
        public final long latencyMs;       // -1 when unreachable
        public final boolean reachable;

        ProbeResult(String url, long latencyMs, boolean reachable) {
            this.url = url;
            this.latencyMs = latencyMs;
            this.reachable = reachable;
        }
    }

    /**
     * HEAD-probe each base URL in parallel, return them sorted by latency
     * (fastest first). Unreachable URLs go to the end. Used by the picker UI.
     *
     * @param baseUrls  list of CDN base URLs (e.g. from parseCdnUrls)
     * @param timeoutMs per-probe connect + read timeout
     */
    public static List<ProbeResult> probeAndRank(List<String> baseUrls, int timeoutMs) {
        if (baseUrls == null || baseUrls.isEmpty()) return Collections.emptyList();
        if (baseUrls.size() == 1) {
            return Collections.singletonList(new ProbeResult(baseUrls.get(0), 0L, true));
        }

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(baseUrls.size(), 4));
        List<Future<ProbeResult>> futures = new ArrayList<>();
        for (final String u : baseUrls) {
            futures.add(pool.submit((Callable<ProbeResult>) () -> probeOne(u, timeoutMs)));
        }
        pool.shutdown();

        List<ProbeResult> out = new ArrayList<>();
        long perFutureTimeout = timeoutMs + 1000L;
        for (Future<ProbeResult> f : futures) {
            try {
                out.add(f.get(perFutureTimeout, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                // Drop the URL that didn't finish in time — its data isn't reliable
            }
        }

        // Reachable first, then by ascending latency
        out.sort(new Comparator<ProbeResult>() {
            @Override public int compare(ProbeResult a, ProbeResult b) {
                if (a.reachable != b.reachable) return a.reachable ? -1 : 1;
                return Long.compare(a.latencyMs, b.latencyMs);
            }
        });
        return out;
    }

    /**
     * Convenience: HEAD-probe + rank + return reachable URLs in fastest-first
     * order. Used by the download path (which doesn't need the latency data).
     *
     * Fallback: if ALL probes failed (every host unreachable), returns the
     * original baseUrls verbatim so the caller can still attempt downloads —
     * a 5xx during probe doesn't necessarily mean the chunk URL will fail.
     */
    public static List<String> rankByLatency(List<String> baseUrls, int timeoutMs) {
        List<ProbeResult> probed = probeAndRank(baseUrls, timeoutMs);
        List<String> out = new ArrayList<>();
        for (ProbeResult p : probed) {
            if (p.reachable) out.add(p.url);
        }
        if (out.isEmpty() && baseUrls != null) {
            return new ArrayList<>(baseUrls);
        }
        return out;
    }

    private static ProbeResult probeOne(String url, int timeoutMs) {
        long start = System.nanoTime();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setRequestProperty("User-Agent", "GOG Galaxy");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            long elapsed = (System.nanoTime() - start) / 1_000_000L;
            return new ProbeResult(url, elapsed, code >= 200 && code < 500);
        } catch (Exception e) {
            return new ProbeResult(url, -1L, false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
