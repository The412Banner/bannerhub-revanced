package app.revanced.extension.gamehub.gog;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The signed-in GOG user, from {@code embed.gog.com/userData.json} — the call the login screen
 * already makes to learn the username, read here in full: avatar, Galaxy id, owned-game count,
 * wishlist count and the friends list GOG embeds in the same payload.
 *
 * Every field is optional and parsed defensively; a missing section renders as absent, never as
 * a zero. The last good payload is mirrored to {@code bh_gog_prefs} so the Profile tab paints
 * instantly on the next open and survives an offline launch.
 *
 * GOG friends carry NO presence — the payload is a roster, not a status feed.
 */
public final class GogUserData {

    private static final String TAG = "GogUser";
    private static final String PREFS = "bh_gog_prefs";
    private static final String KEY_CACHE = "gog_userdata_cache";

    private GogUserData() {}

    public static final class Friend {
        public final String username;
        public final String galaxyId;
        public final String avatar;
        public final String userSince;
        Friend(String username, String galaxyId, String avatar, String userSince) {
            this.username = username; this.galaxyId = galaxyId;
            this.avatar = avatar; this.userSince = userSince;
        }
    }

    public static final class Profile {
        public final String username;
        public final String userId;
        public final String galaxyUserId;
        public final String avatar;
        public final String country;
        public final int ownedGames;
        public final int ownedMovies;
        public final int wishlisted;
        public final List<Friend> friends;
        public final long fetchedAt;
        Profile(String username, String userId, String galaxyUserId, String avatar, String country,
                int ownedGames, int ownedMovies, int wishlisted, List<Friend> friends, long fetchedAt) {
            this.username = username; this.userId = userId; this.galaxyUserId = galaxyUserId;
            this.avatar = avatar; this.country = country; this.ownedGames = ownedGames;
            this.ownedMovies = ownedMovies; this.wishlisted = wishlisted; this.friends = friends;
            this.fetchedAt = fetchedAt;
        }
    }

    /**
     * GOG avatar URLs come without an extension; the site appends a size formatter. Try medium,
     * then small, then the bare jpg, then the raw value — the image loader walks this chain.
     */
    public static List<String> avatarCandidates(String avatar) {
        List<String> out = new ArrayList<>();
        if (avatar == null || avatar.trim().isEmpty()) return out;
        String base = GogStoreCatalog.absolutize(avatar);
        if (base.endsWith(".jpg") || base.endsWith(".png")) { out.add(base); return out; }
        out.add(base + "_avm.jpg");
        out.add(base + "_avs.jpg");
        out.add(base + ".jpg");
        out.add(base);
        return out;
    }

    public static Profile cached(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            String s = p.getString(KEY_CACHE, null);
            if (s == null) return null;
            return parse(new JSONObject(s), p.getLong(KEY_CACHE + "_at", 0L));
        } catch (Exception e) {
            return null;
        }
    }

    /** Blocking; worker thread only. Null when signed out, offline or unparsable. */
    public static Profile fetch(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, 0);
        String token = GogLibraryRepo.validToken(ctx);
        if (token == null) return null;
        String body = BhStoreNet.get("https://embed.gog.com/userData.json", token, BhStoreNet.GALAXY_UA);
        if (body == null) return null;
        JSONObject json;
        try { json = new JSONObject(body); } catch (Exception e) { return null; }
        long now = System.currentTimeMillis();
        Profile profile = parse(json, now);
        if (profile == null) return null;
        prefs.edit().putString(KEY_CACHE, body).putLong(KEY_CACHE + "_at", now).apply();
        Log.i(TAG, "userData: games=" + profile.ownedGames + " friends=" + profile.friends.size());
        return profile;
    }

    private static Profile parse(JSONObject j, long at) {
        String username = j.optString("username", "");
        if (username.isEmpty() && !j.optBoolean("isLoggedIn", false)) return null;
        JSONObject purchased = j.optJSONObject("purchasedItems");
        List<Friend> friends = new ArrayList<>();
        JSONArray arr = j.optJSONArray("friends");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.optJSONObject(i);
            if (f == null) continue;
            String name = f.optString("username", "");
            if (name.isEmpty()) continue;
            String avatar = f.optString("avatar", "");
            friends.add(new Friend(name,
                    f.optString("galaxyId", f.optString("id", "")),
                    avatar.trim().isEmpty() ? null : avatar,
                    f.optString("userSince", "")));
        }
        Collections.sort(friends, (a, b) ->
                a.username.toLowerCase(Locale.ROOT).compareTo(b.username.toLowerCase(Locale.ROOT)));
        String avatar = j.optString("avatar", "");
        return new Profile(username,
                j.optString("userId", ""),
                j.optString("galaxyUserId", ""),
                avatar.trim().isEmpty() ? null : avatar,
                j.optString("country", ""),
                purchased != null ? purchased.optInt("games", 0) : 0,
                purchased != null ? purchased.optInt("movies", 0) : 0,
                j.optInt("wishlistedItems", 0),
                friends, at);
    }
}
