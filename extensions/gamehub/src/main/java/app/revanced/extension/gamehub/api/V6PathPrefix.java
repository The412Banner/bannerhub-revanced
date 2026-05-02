package app.revanced.extension.gamehub.api;

/**
 * Prefixes every relative GameHub API path with "v6/" so the BannerHub
 * Cloudflare Worker can branch 6.0-only response variants (e.g. firmware
 * 1.3.4) off the URL path without changing the catalog host.
 *
 * Hooked at the head of zdb.b(qx9, path) — the single chokepoint every
 * simulator/v2 / vtouch / cards / chat call passes through. Full URLs (path
 * already starts with http:// or https://) are left untouched so direct
 * downloads keep working.
 *
 * The Worker strips "/v6" off the request path and dispatches as if it
 * weren't there; 5.x clients (no patch) keep hitting the bare paths and get
 * the legacy responses.
 */
public final class V6PathPrefix {
    private static final String PREFIX = "v6/";

    public static String prefix(String path) {
        if (path == null) return null;
        if (path.startsWith("http://") || path.startsWith("https://")) return path;
        return PREFIX + path;
    }

    private V6PathPrefix() {}
}
