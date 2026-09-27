package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The GOG library as the storefront hub reads it: the {@code gog_library_cache} JSON the full
 * games screen ({@link GogGamesActivity}) writes, plus a sync that mirrors that screen's
 * incremental owned-id diff so the Library tab fills itself on first open instead of waiting for
 * the full games screen to be visited.
 *
 * Enumerates the account through {@code embed.gog.com/account/getFilteredProducts?mediaType=1}
 * (paged, one row per PLAYABLE product, skipping isMovie / !isGame / isHidden) exactly as the
 * games screen does — {@code user/data/games} is only the fallback, it lists retired ids and DLC.
 *
 * Writes the SAME cache, DLC buffer ({@code gog_dlcs_<baseId>}) and per-id prefs ({@code gog_gen_},
 * {@code gog_release_}, {@code gog_rating_}, {@code gog_size_}, {@code gog_vcover_}) the games
 * screen writes, so the two surfaces never disagree. Install state ({@code gog_exe_} /
 * {@code gog_dir_}) is READ only — that stays owned by {@link GogInstallPath} and the engine.
 *
 * Blocking; {@link #sync} runs on the caller's worker thread.
 */
public final class GogLibraryRepo {

    private static final String TAG = "GogLibrary";
    private static final String PREFS = "bh_gog_prefs";
    private static final String CACHE_KEY = "gog_library_cache";
    private static final String LAST_SYNC_KEY = "gog_library_synced_at";
    private static final long THROTTLE_MS = 15L * 60L * 1000L;
    private static final String VCOVER_PREFIX = "gog_vcover_";

    private static final AtomicBoolean syncing = new AtomicBoolean(false);

    private GogLibraryRepo() {}

    public enum Status { OK, FAILED, NOT_LOGGED_IN, BUSY }

    public static final class SyncResult {
        public final Status status;
        public final List<GogGame> games;
        public final int fetched;
        public final String message;
        SyncResult(Status status, List<GogGame> games, int fetched, String message) {
            this.status = status; this.games = games; this.fetched = fetched; this.message = message;
        }
    }

    public interface StatusSink { void onStatus(String text); }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    public static boolean isLoggedIn(Context ctx) {
        return prefs(ctx).getString("access_token", null) != null;
    }

    /** The cached library, alphabetical. Empty until the first sync. */
    public static List<GogGame> cached(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String json = p.getString(CACHE_KEY, null);
        List<GogGame> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("gameId", "");
                if (id.isEmpty()) continue;
                String vcover = o.optString("verticalCover", "");
                // The full games screen rewrites the cache without the vertical cover; the per-id
                // pref is the durable copy, so re-attach it on every read.
                if (vcover.isEmpty()) vcover = p.getString(VCOVER_PREFIX + id, "");
                out.add(new GogGame(id,
                        o.optString("title", ""),
                        o.optString("imageUrl", ""),
                        o.optString("description", ""),
                        o.optString("developer", ""),
                        o.optString("category", ""),
                        o.optInt("generation", 1),
                        vcover));
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
        sortByTitle(out);
        return out;
    }

    /** One cached game by id, or null when the cache never held it. */
    public static GogGame find(Context ctx, String gameId) {
        if (gameId == null) return null;
        for (GogGame g : cached(ctx)) if (gameId.equals(g.gameId)) return g;
        return null;
    }

    public static Set<String> ownedIds(List<GogGame> games) {
        Set<String> s = new HashSet<>(games.size());
        for (GogGame g : games) s.add(g.gameId);
        return s;
    }

    /**
     * A usable access token, refreshing through {@link GogTokenRefresh} when the recorded login
     * time says it has expired. Null when signed out or the refresh failed.
     */
    public static String validToken(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String token = p.getString("access_token", null);
        if (token == null) return null;
        int loginTime = p.getInt("bh_gog_login_time", 0);
        int expiresIn = p.getInt("bh_gog_expires_in", 3600);
        long nowSec = System.currentTimeMillis() / 1000L;
        if (loginTime == 0 || nowSec >= loginTime + expiresIn) {
            token = GogTokenRefresh.refresh(ctx.getApplicationContext());
        }
        return token;
    }

    /**
     * Sync the library: owned-id diff against the cache, fetch only what is new (or everything
     * when {@code force} / empty cache / 15 min stale — the games screen's exact policy).
     */
    public static SyncResult sync(Context ctx, boolean force, StatusSink onStatus) {
        if (!syncing.compareAndSet(false, true)) return new SyncResult(Status.BUSY, null, 0, "");
        try {
            Context appCtx = ctx.getApplicationContext();
            SharedPreferences p = prefs(appCtx);
            String token = validToken(appCtx);
            if (token == null) return new SyncResult(Status.NOT_LOGGED_IN, null, 0, "");

            status(onStatus, "Fetching game list…");
            List<String> ids = enumerateOwned(token);
            if (ids == null) return new SyncResult(Status.FAILED, null, 0, "Couldn't reach GOG");

            Set<String> ownedSet = new HashSet<>(ids);
            List<GogGame> cachedList = cached(appCtx);
            long lastSync = p.getLong(LAST_SYNC_KEY, 0L);
            boolean stale = System.currentTimeMillis() - lastSync >= THROTTLE_MS;
            boolean heavy = force || cachedList.isEmpty() || stale;
            Set<String> cachedIds = ownedIds(cachedList);
            List<String> idsToFetch = new ArrayList<>();
            for (String id : ids) if (heavy || !cachedIds.contains(id)) idsToFetch.add(id);
            Set<String> fetchSet = new HashSet<>(idsToFetch);

            LinkedHashMap<String, GogGame> merged = new LinkedHashMap<>();
            for (GogGame g : cachedList) {
                if (ownedSet.contains(g.gameId) && !fetchSet.contains(g.gameId)) merged.put(g.gameId, g);
            }

            if (idsToFetch.isEmpty()) {
                List<GogGame> list = new ArrayList<>(merged.values());
                saveCache(p, list);
                sortByTitle(list);
                return new SyncResult(Status.OK, list, 0, "");
            }

            status(onStatus, "Syncing " + idsToFetch.size() + (idsToFetch.size() == 1 ? " game" : " games") + "…");
            final Map<String, List<String[]>> dlcBuffer = new HashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(5);
            List<Future<GogGame>> futures = new ArrayList<>();
            final String finalToken = token;
            for (final String id : idsToFetch) {
                futures.add(pool.submit(new Callable<GogGame>() {
                    @Override public GogGame call() { return fetchGame(p, id, finalToken, dlcBuffer); }
                }));
            }
            pool.shutdown();
            int fetched = 0;
            for (int idx = 0; idx < futures.size(); idx++) {
                GogGame g = null;
                try { g = futures.get(idx).get(); } catch (Exception ignored) {}
                if (g != null) { merged.put(g.gameId, g); fetched++; }
                if ((idx + 1) % 5 == 0) status(onStatus, "Syncing… " + (idx + 1) + "/" + futures.size());
            }
            saveDlcBuffer(p, dlcBuffer);
            List<GogGame> finalList = new ArrayList<>(merged.values());
            saveCache(p, finalList);
            if (heavy) p.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply();
            Log.i(TAG, "sync: owned=" + ids.size() + " fetched=" + fetched + " cached=" + finalList.size() + " heavy=" + heavy);
            sortByTitle(finalList);
            return new SyncResult(Status.OK, finalList, fetched, "");
        } catch (Exception e) {
            Log.w(TAG, "sync failed: " + e.getMessage());
            return new SyncResult(Status.FAILED, null, 0, e.getMessage() != null ? e.getMessage() : "Sync failed");
        } finally {
            syncing.set(false);
        }
    }

    /**
     * Owned PLAYABLE product ids: {@code account/getFilteredProducts} (paged), falling back to the
     * raw {@code user/data/games} owned set. Null when neither endpoint answered.
     */
    private static List<String> enumerateOwned(String token) {
        List<String> ids = new ArrayList<>();
        boolean filteredOk = false;
        try {
            for (int page = 1, totalPages = 1; page <= totalPages && page <= 50; page++) {
                String pageJson = BhStoreNet.get(
                        "https://embed.gog.com/account/getFilteredProducts?mediaType=1&page=" + page,
                        token, BhStoreNet.GALAXY_UA);
                if (pageJson == null) break;
                JSONObject pageObj = new JSONObject(pageJson);
                totalPages = Math.max(1, pageObj.optInt("totalPages", 1));
                JSONArray products = pageObj.optJSONArray("products");
                if (products == null) break;
                for (int i = 0; i < products.length(); i++) {
                    JSONObject prod = products.optJSONObject(i);
                    if (prod == null) continue;
                    if (prod.optBoolean("isMovie", false)) continue;
                    if (!prod.optBoolean("isGame", true)) continue;
                    if (prod.optBoolean("isHidden", false)) continue;
                    String id = String.valueOf(prod.optLong("id", 0));
                    if (!"0".equals(id) && !"1801418160".equals(id) && !ids.contains(id)) ids.add(id);
                }
                filteredOk = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "getFilteredProducts failed, falling back to user/data/games: " + e.getMessage());
            ids.clear();
            filteredOk = false;
        }
        if (filteredOk && !ids.isEmpty()) return ids;
        String gamesJson = BhStoreNet.get("https://embed.gog.com/user/data/games", token, BhStoreNet.GALAXY_UA);
        if (gamesJson == null) return filteredOk ? ids : null;
        try {
            JSONArray owned = new JSONObject(gamesJson).optJSONArray("owned");
            if (owned != null) for (int i = 0; i < owned.length(); i++) {
                String id = String.valueOf(owned.optLong(i));
                if (!"1801418160".equals(id) && !"0".equals(id) && !ids.contains(id)) ids.add(id);
            }
        } catch (Exception e) {
            return null;
        }
        return ids;
    }

    // ── Per-game fetch (mirror of GogGamesActivity.fetchGame) ─────────────────

    private static GogGame fetchGame(SharedPreferences prefs, String id, String token,
                                     Map<String, List<String[]>> dlcBuffer) {
        try {
            String productJson = GogGamesActivity.httpGet(
                    "https://api.gog.com/products/" + id + "?expand=downloads,description", token);
            if (productJson == null) return null;
            JSONObject prod = new JSONObject(productJson);
            if (prod.optBoolean("is_secret", false)) return null;
            String gameType = prod.optString("game_type", "");
            if ("dlc".equals(gameType)) {
                storeDlc(id, prod, dlcBuffer);
                return null;
            }
            if (!gameType.isEmpty() && !"game".equals(gameType) && !"pack".equals(gameType)) return null;

            JSONObject titleObj = prod.optJSONObject("title");
            String titleStr = titleObj != null ? titleObj.optString("*") : null;
            if (titleStr == null || titleStr.isEmpty()) titleStr = prod.optString("title");
            if (titleStr == null || titleStr.isEmpty()) return null;

            String imageUrl = GogGamesActivity.sgdbFetchCover(titleStr);
            if (imageUrl.isEmpty()) {
                JSONObject images = prod.optJSONObject("images");
                imageUrl = images != null ? images.optString("icon", "") : "";
                if (imageUrl.isEmpty()) imageUrl = images != null ? images.optString("background", "") : "";
            }

            JSONObject descObj = prod.optJSONObject("description");
            String desc = descObj != null ? descObj.optString("lead", "") : "";
            JSONObject company = prod.optJSONObject("developers");
            String developer = company != null ? company.optString("name", "") : prod.optString("developer", "");
            JSONArray genres = prod.optJSONArray("genres");
            String category = "";
            if (genres != null && genres.length() > 0) {
                JSONObject g = genres.optJSONObject(0);
                if (g != null) category = g.optString("name", "");
            }

            int generation = 1;
            boolean hasWindowsBuild = false;
            try {
                String buildsJson = GogGamesActivity.httpGet(
                        "https://content-system.gog.com/products/" + id + "/os/windows/builds?generation=2", token);
                if (buildsJson != null) {
                    JSONArray bitems = new JSONObject(buildsJson).optJSONArray("items");
                    if (bitems != null && bitems.length() > 0) {
                        hasWindowsBuild = true;
                        int maxGen = 0;
                        for (int bi = 0; bi < bitems.length(); bi++) {
                            JSONObject b = bitems.optJSONObject(bi);
                            int g = b != null ? b.optInt("generation", 0) : 0;
                            if (g > maxGen) maxGen = g;
                        }
                        if (maxGen > 0) generation = maxGen;
                    }
                }
            } catch (Exception ignored) {}
            prefs.edit().putInt("gog_gen_" + id, generation).apply();

            String releaseDate = prod.optString("release_date", "");
            if (!releaseDate.isEmpty()) prefs.edit().putString("gog_release_" + id, releaseDate).apply();
            int rating = prod.optInt("rating", -1);
            if (rating >= 0) prefs.edit().putInt("gog_rating_" + id, rating).apply();

            if (prefs.getLong("gog_size_" + id, -1) <= 0) {
                long size = GogDownloadManager.fetchInstallSizeBytes(id, token);
                if (size > 0) prefs.edit().putLong("gog_size_" + id, size).apply();
            }

            JSONObject downloads = prod.optJSONObject("downloads");
            boolean hasWindowsInstaller = false;
            JSONArray installers = downloads != null ? downloads.optJSONArray("installers") : null;
            if (installers != null) for (int di = 0; di < installers.length(); di++) {
                JSONObject inst = installers.optJSONObject(di);
                if (inst != null && "windows".equals(inst.optString("os", ""))) { hasWindowsInstaller = true; break; }
            }
            if (!hasWindowsBuild && !hasWindowsInstaller) return null;

            String verticalCover = prefs.getString(VCOVER_PREFIX + id, null);
            if (verticalCover == null || verticalCover.isEmpty()) {
                verticalCover = fetchVerticalCover(id);
                if (verticalCover != null && !verticalCover.isEmpty()) {
                    prefs.edit().putString(VCOVER_PREFIX + id, verticalCover).apply();
                }
            }

            return new GogGame(id, titleStr, imageUrl, desc, developer, category, generation, verticalCover);
        } catch (Exception e) {
            return null;
        }
    }

    /** GOG's own 2:3 box art via gamesdb (public, no token). Null when it has none. */
    static String fetchVerticalCover(String productId) {
        try {
            String extJson = BhStoreNet.get("https://gamesdb.gog.com/platforms/gog/external_releases/" + productId);
            if (extJson == null) return null;
            String gameId = new JSONObject(extJson).optString("game_id", "");
            if (gameId.isEmpty()) return null;
            String gameJson = BhStoreNet.get("https://gamesdb.gog.com/games/" + gameId);
            if (gameJson == null) return null;
            JSONObject vc = new JSONObject(gameJson).optJSONObject("vertical_cover");
            String fmt = vc != null ? vc.optString("url_format", "") : "";
            if (fmt.isEmpty()) return null;
            return fmt.replace("{formatter}", "").replace("{ext}", "webp");
        } catch (Exception e) {
            return null;
        }
    }

    private static void storeDlc(String dlcId, JSONObject prod, Map<String, List<String[]>> buffer) {
        try {
            JSONObject titleObj = prod.optJSONObject("title");
            String dlcTitle = titleObj != null ? titleObj.optString("*", "") : "";
            if (dlcTitle.isEmpty()) dlcTitle = prod.optString("title", "");
            if (dlcTitle.isEmpty()) dlcTitle = "Unknown DLC";
            String baseId = "";
            JSONObject reqGame = prod.optJSONObject("required_game");
            if (reqGame != null) baseId = reqGame.optString("id", "");
            if (baseId.isEmpty()) {
                JSONArray reqArr = prod.optJSONArray("requiredGames");
                if (reqArr != null && reqArr.length() > 0) baseId = reqArr.optString(0, "");
            }
            if (baseId.isEmpty()) return;
            synchronized (buffer) {
                List<String[]> list = buffer.get(baseId);
                if (list == null) { list = new ArrayList<>(); buffer.put(baseId, list); }
                list.add(new String[]{dlcId, dlcTitle});
            }
        } catch (Exception ignored) {}
    }

    private static void saveDlcBuffer(SharedPreferences prefs, Map<String, List<String[]>> buffer) {
        for (Map.Entry<String, List<String[]>> e : buffer.entrySet()) {
            try {
                JSONArray arr = new JSONArray();
                for (String[] dlc : e.getValue()) {
                    arr.put(new JSONObject().put("id", dlc[0]).put("title", dlc[1]));
                }
                prefs.edit().putString("gog_dlcs_" + e.getKey(), arr.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    private static void saveCache(SharedPreferences prefs, List<GogGame> games) {
        try {
            JSONArray arr = new JSONArray();
            for (GogGame g : games) {
                JSONObject o = new JSONObject();
                o.put("gameId", g.gameId);
                o.put("title", g.title);
                o.put("imageUrl", g.imageUrl);
                o.put("description", g.description);
                o.put("developer", g.developer);
                o.put("category", g.category);
                o.put("generation", g.generation);
                if (g.verticalCover != null) o.put("verticalCover", g.verticalCover);
                arr.put(o);
            }
            prefs.edit().putString(CACHE_KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static void sortByTitle(List<GogGame> list) {
        Collections.sort(list, (a, b) -> a.title.toLowerCase(Locale.ROOT).compareTo(b.title.toLowerCase(Locale.ROOT)));
    }

    private static void status(StatusSink sink, String text) {
        if (sink != null) sink.onStatus(text);
    }

    /** A cached {@link GogGame} as a catalog item for the shared tiles / rails. */
    public static GogCatalogItem toCatalogItem(GogGame g) {
        String wide = GogStoreCatalog.absolutize(g.imageUrl);
        String tall = GogStoreCatalog.absolutize(g.verticalCover != null ? g.verticalCover : g.imageUrl);
        return new GogCatalogItem(g.gameId, g.title, wide, tall, g.category, false, false,
                "", "", 0, "", g.developer, "", g.description, null);
    }
}
