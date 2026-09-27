package app.revanced.extension.gamehub.gog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Message;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;

/**
 * WebView-based GOG OAuth2 login screen (implicit flow, response_type=token).
 *
 * Port of the device-proven Bannerlator GogLoginActivity (Kotlin) into the
 * extension. Same prefs contract as before — GogMainActivity / GogTokenRefresh
 * read these and must keep working:
 *   "bh_gog_prefs": access_token, refresh_token, user_id, username,
 *                   bh_gog_login_time (int seconds), bh_gog_expires_in (3600)
 *
 * Flow:
 *   1. Load GOG auth page (layout=client2, Galaxy UA) in the MAIN WebView
 *   2. Social buttons call window.open(): onCreateWindow hosts the identity
 *      provider in a stacked POPUP WebView (Chrome UA + third-party cookies)
 *   3. Intercept redirect to embed.gog.com/on_login_success on EITHER frame
 *   4. Parse fragment: access_token, refresh_token, user_id (+ state check)
 *   5. Background thread: fetch userData.json -> username, save prefs, finish()
 *
 * Why this shape (history of the source, kept so nobody undoes it):
 *   - The main frame's config (layout=client2 + Galaxy UA + default cookie
 *     policy + plain setContentView) is the ONLY combination that renders the
 *     GOG login form. Dropping layout, changing the UA, or enabling third-party
 *     cookies on the main frame were all proven dead ends. Do not touch them.
 *   - Social login is a window.open() popup. Without
 *     setSupportMultipleWindows + onCreateWindow the WebView silently drops the
 *     call and the button "does nothing" (the old white screen).
 *   - The popup stays a SEPARATE child WebView so window.opener / postMessage
 *     keep working; collapsing it into the main frame breaks GOG's handshake.
 *   - Popup teardown is POSTED, never done inline: the redirect can land inside
 *     the popup's own shouldOverrideUrlLoading, and destroying a WebView from
 *     within its own callback crashes chromium.
 *   - Google refuses OAuth from an embedded WebView (403 disallowed_useragent)
 *     when it sees the "; wv" UA token and/or the X-Requested-With: <package>
 *     header. The popup runs a real Chrome-on-Android UA (no "; wv"). The
 *     header can only be suppressed through androidx.webkit
 *     WebSettingsCompat.setRequestedWithHeaderOriginAllowList; see
 *     applyIdpHardening() for what the host can and cannot offer.
 *   - OAuth `state` is checked MISMATCH-ONLY: GOG's social path
 *     (external-accounts.gog.com/login/providers/<p>/back) echoes no state at
 *     all, and rejecting absent-state threw away perfectly good logins.
 *   - Every failure renders on screen (error panel + Retry) and logs under
 *     BH_GOG; the old screen only logged, which looked like a blank hang.
 */
public class GogLoginActivity extends Activity {

    private static final String TAG = "BH_GOG";

    static final String AUTH_URL =
            "https://auth.gog.com/auth" +
            "?client_id=46899977096215655" +
            "&redirect_uri=https%3A%2F%2Fembed.gog.com%2Fon_login_success%3Forigin%3Dclient" +
            "&response_type=token&layout=client2";

    private static final String KEY_STATE = "bh_gog_oauth_state";

    private static final String GALAXY_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) GOG Galaxy/2.0";

    // Real Chrome-on-Android UA. Must NOT contain the "; wv" token — that token is
    // one of the two signals Google uses to reject sign-in from an embedded WebView
    // (the other is the X-Requested-With header, see applyIdpHardening()).
    private static final String CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/127.0.6533.103 Mobile Safari/537.36";

    private static final String REDIRECT_PREFIX = "https://embed.gog.com/on_login_success";

    // Colours (no resources in the extension module — ints only).
    private static final int BG        = 0xFF0B0B0F;
    private static final int BAR_BG    = 0xFF15151C;
    private static final int TEXT      = 0xFFE6E6EA;

    // UI text (no resources in the extension module — literals only).
    private static final String TXT_TITLE          = "Sign in to GOG";
    private static final String TXT_PROVIDER_TITLE = "Sign in with your provider";
    private static final String TXT_FINISHING      = "Finishing sign-in\u2026";
    private static final String TXT_RELOAD         = "Reload";
    private static final String TXT_CLOSE          = "Close";
    private static final String TXT_RETRY          = "Try again";
    private static final String TXT_ERR_NETWORK    = "Could not load the GOG sign-in page.\n\n";
    private static final String TXT_ERR_HTTP       = "The GOG sign-in page returned an error (HTTP %d).";
    private static final String TXT_ERR_SSL        =
            "The sign-in page failed its security check and was not loaded.";
    private static final String TXT_ERR_VERIFY     = "Login verification failed, please try again";
    private static final String TXT_ERR_GENERIC    = "Login error, please try again";
    private static final String TXT_ERR_IDP        =
            "This sign-in provider refused to load inside the app.\n\n" +
            "Sign in with your GOG email and password instead. If your GOG account has no " +
            "password yet, set one at gog.com in your phone's browser first.";

    private WebView webView;
    private WebView popupWebView;

    private FrameLayout contentHost;
    private FrameLayout popupHost;
    private ProgressBar progressBar;
    private LinearLayout errorPanel;
    private TextView errorText;
    private TextView titleText;

    // CSRF state we sent on AUTH_URL; must survive Activity recreation.
    private String oauthState;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        oauthState = savedInstanceState != null ? savedInstanceState.getString(KEY_STATE) : null;
        if (oauthState == null) oauthState = generateState();

        setContentView(buildChrome());

        webView = newWebView(GALAXY_UA, false);
        // Index 0: buildChrome() already put popupHost and errorPanel in contentHost,
        // and they must stay ABOVE the page, not behind it.
        contentHost.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Log.d(TAG, "login screen up, loading auth page");
        webView.loadUrl(buildAuthUrl(oauthState));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (oauthState != null) outState.putString(KEY_STATE, oauthState);
    }

    // The host manifest has enableOnBackInvokedCallback="false", so the classic
    // onBackPressed() path is the one that runs (no androidx OnBackPressedCallback here).
    @Override
    public void onBackPressed() {
        if (popupWebView != null) {
            Log.d(TAG, "back: closing popup");
            dismissPopup();
        } else if (webView != null && webView.canGoBack()) {
            Log.d(TAG, "back: history");
            webView.goBack();
        } else {
            Log.d(TAG, "back: finish");
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        WebView child = popupWebView;
        if (child != null) {
            popupWebView = null;
            destroyPopupView(child);
        }
        WebView main = webView;
        if (main != null) {
            main.setWebChromeClient(null);
            ViewGroup parent = (ViewGroup) main.getParent();
            if (parent != null) parent.removeView(main);
            main.destroy();
        }
        webView = null;
        super.onDestroy();
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    /** Appends the CSRF state to the base AUTH_URL. */
    static String buildAuthUrl(String state) {
        return AUTH_URL + "&state=" + Uri.encode(state);
    }

    /** Random URL-safe CSRF state (24 bytes -> ~32 chars, well over the 16-byte floor). */
    static String generateState() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** Extracts a string field from a minimal JSON blob (no library needed). Used by GogTokenRefresh. */
    public static String parseJsonStringField(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int start = idx + search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    static boolean isGogHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("gog.com") || h.endsWith(".gog.com");
    }

    /**
     * Hosts that serve a third-party identity provider's sign-in UI. These are the
     * pages that must NOT look like an embedded WebView, and the only ones we swap
     * the UA for.
     */
    static boolean isIdentityProviderHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase();
        return h.equals("google.com") || h.endsWith(".google.com")
                || h.equals("youtube.com") || h.endsWith(".youtube.com")
                || h.equals("facebook.com") || h.endsWith(".facebook.com")
                || h.equals("apple.com") || h.endsWith(".apple.com");
    }

    /**
     * Log-safe URL: drops the fragment and query (the OAuth fragment carries LIVE
     * tokens) and any user:pass@ userinfo. Never returns the raw input on failure.
     */
    static String redactUrl(String url) {
        if (url == null) return null;
        try {
            String out = url;
            int hash = out.indexOf('#');
            if (hash >= 0) out = out.substring(0, hash);
            int q = out.indexOf('?');
            if (q >= 0) out = out.substring(0, q);
            int scheme = out.indexOf("://");
            if (scheme >= 0) {
                int authStart = scheme + 3;
                int pathStart = out.indexOf('/', authStart);
                String authority = (pathStart >= 0)
                        ? out.substring(authStart, pathStart)
                        : out.substring(authStart);
                int at = authority.lastIndexOf('@');
                if (at >= 0) {
                    out = out.substring(0, authStart)
                            + authority.substring(at + 1)
                            + (pathStart >= 0 ? out.substring(pathStart) : "");
                }
            }
            return out.isEmpty() ? "[url]" : out;
        } catch (Exception e) {
            return "[url]";
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ── Chrome (title bar / progress / content host / error panel) ────────────

    /**
     * Title bar (close + reload) -> progress bar -> content host. The old screen was
     * a bare WebView handed straight to setContentView, so a failed load left the
     * user staring at an undismissable white rectangle with no way back and no text.
     */
    private View buildChrome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BAR_BG);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        titleText = new TextView(this);
        titleText.setText(TXT_TITLE);
        titleText.setTextColor(TEXT);
        titleText.setTextSize(16f);
        titleText.setPadding(dp(16), 0, dp(8), 0);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton reload = new ImageButton(this);
        reload.setImageResource(android.R.drawable.ic_menu_rotate);
        reload.setBackgroundColor(Color.TRANSPARENT);
        reload.setContentDescription(TXT_RELOAD);
        reload.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        reload.setOnClickListener(v -> reloadLogin());

        ImageButton close = new ImageButton(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(TXT_CLOSE);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        close.setOnClickListener(v -> finish());

        bar.addView(titleText);
        bar.addView(reload);
        bar.addView(close);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        contentHost = new FrameLayout(this);
        contentHost.setBackgroundColor(BG);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        popupHost = new FrameLayout(this);
        popupHost.setBackgroundColor(BG);
        popupHost.setVisibility(View.GONE);
        contentHost.addView(popupHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(Gravity.CENTER);
        errorPanel.setBackgroundColor(BG);
        errorPanel.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorPanel.setVisibility(View.GONE);

        errorText = new TextView(this);
        errorText.setTextColor(TEXT);
        errorText.setTextSize(15f);
        errorText.setGravity(Gravity.CENTER);

        Button retry = new Button(this);
        retry.setText(TXT_RETRY);
        retry.setOnClickListener(v -> reloadLogin());
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryLp.topMargin = dp(16);

        errorPanel.addView(errorText);
        errorPanel.addView(retry, retryLp);
        contentHost.addView(errorPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(bar);
        root.addView(progressBar);
        root.addView(contentHost);
        return root;
    }

    private void showError(final String message) {
        runOnUiThread(() -> {
            errorText.setText(message);
            errorPanel.setVisibility(View.VISIBLE);
            errorPanel.bringToFront();
            progressBar.setVisibility(View.GONE);
        });
    }

    private void clearError() {
        runOnUiThread(() -> errorPanel.setVisibility(View.GONE));
    }

    private void reloadLogin() {
        dismissPopup();
        clearError();
        String fresh = generateState();
        oauthState = fresh;
        if (webView != null) {
            webView.getSettings().setUserAgentString(GALAXY_UA);   // undo any IdP-leg UA swap
            webView.loadUrl(buildAuthUrl(fresh));
        }
    }

    /** Shows a themed error and reloads the login page with a fresh CSRF state. */
    private void rejectLogin(final String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
            reloadLogin();
        });
    }

    // ── WebViews ──────────────────────────────────────────────────────────────

    // Result of the one-time androidx.webkit probe: null = not probed yet.
    private static Boolean requestedWithSuppressible;

    /**
     * Tries to remove the X-Requested-With: <package> header Google uses (with the
     * "; wv" UA token, absent from CHROME_UA) to reject sign-in from an embedded
     * WebView. The only supported way is androidx.webkit
     * WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()).
     *
     * This module cannot depend on androidx.webkit, so the call is made by
     * reflection against whatever the HOST ships. GameHub 6.3.1 keeps
     * androidx.webkit.WebViewFeature (the constant REQUESTED_WITH_HEADER_ALLOW_LIST
     * is in it) but R8 stripped WebSettingsCompat entirely — so on 6.3.1 this logs
     * "unavailable" and continues. Consequence: the popup still sends the header,
     * and Google MAY answer 403 disallowed_useragent; that case is rendered by
     * onReceivedHttpError as TXT_ERR_IDP (use email+password) instead of a white
     * screen. If a future host build ships WebSettingsCompat, this lights up on its
     * own.
     */
    private static void applyIdpHardening(WebView wv) {
        if (Boolean.FALSE.equals(requestedWithSuppressible)) return;
        try {
            Class<?> compat = Class.forName("androidx.webkit.WebSettingsCompat");
            Class<?> feature = Class.forName("androidx.webkit.WebViewFeature");
            Method supported = feature.getMethod("isFeatureSupported", String.class);
            boolean ok = Boolean.TRUE.equals(
                    supported.invoke(null, "REQUESTED_WITH_HEADER_ALLOW_LIST"));
            if (!ok) {
                requestedWithSuppressible = Boolean.FALSE;
                Log.w(TAG, "REQUESTED_WITH_HEADER_ALLOW_LIST unsupported on this WebView build");
                return;
            }
            Method set = compat.getMethod("setRequestedWithHeaderOriginAllowList",
                    WebSettings.class, Set.class);
            set.invoke(null, wv.getSettings(), Collections.emptySet());
            requestedWithSuppressible = Boolean.TRUE;
            Log.d(TAG, "X-Requested-With suppressed (empty origin allow-list)");
        } catch (Throwable t) {
            requestedWithSuppressible = Boolean.FALSE;
            Log.w(TAG, "X-Requested-With suppression unavailable in host ("
                    + t.getClass().getSimpleName() + ": " + t.getMessage()
                    + ") — Google sign-in may be refused with 403 disallowed_useragent");
        }
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private WebView newWebView(String userAgent, boolean isPopup) {
        WebView wv = new WebView(this);
        wv.setBackgroundColor(BG);   // no white flash while the page loads
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(userAgent);
        // GOG's social-login buttons are window.open() popups. Without these two the
        // WebView silently drops the call and the user just sees the page do nothing.
        ws.setSupportMultipleWindows(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        applyIdpHardening(wv);
        if (isPopup) {
            // Only the IdP leg. The main auth.gog.com view keeps default cookie policy —
            // enabling third-party cookies there was a proven dead end (see class doc).
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }
        wv.setWebViewClient(new GogWebViewClient(isPopup));
        wv.setWebChromeClient(new GogChromeClient(isPopup));
        return wv;
    }

    /**
     * Deferred teardown. handleImplicitRedirect() can fire from inside the POPUP's
     * own shouldOverrideUrlLoading (when the IdP hop finishes in the popup rather
     * than the opener), and destroying a WebView from within one of its own
     * callbacks crashes chromium. Posting hops to the next loop iteration, after
     * the callback returns.
     */
    private void dismissPopup() {
        final WebView child = popupWebView;
        if (child == null) return;
        // Detach the reference NOW so a popup opened in the same turn (onCreateWindow
        // replacing a stale one) is not the view the posted teardown destroys.
        popupWebView = null;
        popupHost.post(() -> destroyPopupView(child));
    }

    private void destroyPopupView(WebView child) {
        Log.d(TAG, "popup destroyed");
        child.stopLoading();
        child.setWebChromeClient(null);
        child.loadUrl("about:blank");
        popupHost.removeView(child);
        child.destroy();
        if (popupWebView == null) {
            popupHost.setVisibility(View.GONE);
            if (!isFinishing()) titleText.setText(TXT_TITLE);
        }
    }

    // ── WebChromeClient ───────────────────────────────────────────────────────

    private class GogChromeClient extends WebChromeClient {
        private final boolean isPopup;

        GogChromeClient(boolean isPopup) { this.isPopup = isPopup; }

        @Override
        public boolean onConsoleMessage(ConsoleMessage cm) {
            Log.d(TAG, "console[" + cm.messageLevel() + "] " + cm.message()
                    + " @" + cm.sourceId() + ":" + cm.lineNumber());
            return true;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(
                    (newProgress >= 1 && newProgress <= 99) ? View.VISIBLE : View.GONE);
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      Message resultMsg) {
            if (resultMsg == null) return false;
            Log.d(TAG, "onCreateWindow dialog=" + isDialog + " gesture=" + isUserGesture
                    + " fromPopup=" + isPopup);
            dismissPopup();
            WebView child = newWebView(CHROME_UA, true);
            popupWebView = child;
            popupHost.addView(child, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            popupHost.setVisibility(View.VISIBLE);
            popupHost.bringToFront();
            titleText.setText(TXT_PROVIDER_TITLE);
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            Log.d(TAG, "onCloseWindow popup=" + isPopup);
            if (isPopup || window == popupWebView) dismissPopup();
        }
    }

    // ── WebViewClient ─────────────────────────────────────────────────────────

    private class GogWebViewClient extends WebViewClient {
        private final boolean isPopup;

        GogWebViewClient(boolean isPopup) { this.isPopup = isPopup; }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Log.d(TAG, "pageStarted[popup=" + isPopup + "]: " + redactUrl(url));
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "pageFinished[popup=" + isPopup + "]: " + redactUrl(url));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    WebResourceError error) {
            String url = request != null ? redactUrl(request.getUrl().toString()) : null;
            boolean main = request != null && request.isForMainFrame();
            int code = error != null ? error.getErrorCode() : 0;
            CharSequence desc = error != null ? error.getDescription() : null;
            Log.e(TAG, "recvError[popup=" + isPopup + "]: " + code + " " + desc
                    + " url=" + url + " mainFrame=" + main);
            if (main) showError(TXT_ERR_NETWORK + (desc != null ? desc : ""));
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            int status = errorResponse != null ? errorResponse.getStatusCode() : 0;
            String reason = errorResponse != null ? errorResponse.getReasonPhrase() : null;
            String url = request != null ? redactUrl(request.getUrl().toString()) : null;
            boolean main = request != null && request.isForMainFrame();
            Log.e(TAG, "recvHttpError[popup=" + isPopup + "]: " + status + " " + reason
                    + " url=" + url + " mainFrame=" + main);
            if (!main) return;
            // The specific failure this screen used to render as a blank white page:
            // Google refuses OAuth from an embedded browser with 403 disallowed_useragent.
            if (isIdentityProviderHost(request.getUrl().getHost())
                    && (status == 403 || status == 400)) {
                showError(TXT_ERR_IDP);
            } else {
                showError(String.format(TXT_ERR_HTTP, status));
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.e(TAG, "recvSslError[popup=" + isPopup + "]: " + error);
            // Do NOT proceed() — a real cert error should surface, not be silently bypassed.
            if (handler != null) handler.cancel();
            showError(TXT_ERR_SSL);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return route(view, request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return route(view, Uri.parse(url));
        }

        /**
         * Both the main view and the popup can be the frame that lands on the
         * redirect, depending on whether GOG finishes the flow in the opener or in
         * the popup.
         */
        private boolean route(WebView view, Uri uri) {
            if (uri == null) return false;
            if (uri.toString().startsWith(REDIRECT_PREFIX)) {
                Log.d(TAG, "redirect seen[popup=" + isPopup + "]");
                handleImplicitRedirect(uri);
                return true;
            }
            String ua = view.getSettings().getUserAgentString();
            // Back on GOG after an in-place IdP hop: restore the Galaxy UA. A plain
            // Chrome UA makes auth.gog.com serve a login form that never renders here,
            // so the Chrome UA must not outlive the IdP leg.
            if (!isPopup && isGogHost(uri.getHost()) && !GALAXY_UA.equals(ua)) {
                Log.d(TAG, "back on GOG -> restoring Galaxy UA");
                view.getSettings().setUserAgentString(GALAXY_UA);
                return false;
            }
            // A main-frame hop to the IdP (GOG sometimes navigates in place instead of
            // opening a popup). Swap to the Chrome UA before the request goes out —
            // these hops are always GETs, so re-issuing via loadUrl loses nothing.
            if (!isPopup && isIdentityProviderHost(uri.getHost()) && !CHROME_UA.equals(ua)) {
                Log.d(TAG, "main-frame IdP hop -> switching to Chrome UA");
                view.getSettings().setUserAgentString(CHROME_UA);
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
                view.loadUrl(uri.toString());
                return true;
            }
            return false;
        }

        private void handleImplicitRedirect(Uri uri) {
            String fragment = uri.getFragment();
            if (fragment == null) fragment = "";
            Uri frag = Uri.parse("x://x?" + fragment);

            // CSRF: we send a `state` on AUTH_URL and validate it if the auth server
            // echoes it back. MISMATCH-ONLY, deliberately: GOG returns NO state at all
            // on the social-login path (the provider round-trip goes through
            // external-accounts.gog.com/login/providers/<p>/back), so a stricter
            // "missing counts as mismatch" rule throws away a perfectly good
            // access_token. A present-but-wrong state is still rejected, which is the
            // case a forged redirect would actually produce.
            String expected = oauthState;
            String returnedState = frag.getQueryParameter("state");
            if (expected != null && returnedState != null && !returnedState.equals(expected)) {
                Log.e(TAG, "OAuth state mismatch — rejecting redirect");
                rejectLogin(TXT_ERR_VERIFY);
                return;
            }
            if (returnedState == null) {
                Log.d(TAG, "redirect carried no state (expected on social login)");
            }

            String accessToken = frag.getQueryParameter("access_token");
            if (accessToken == null) {
                // Name the keys, never the values — a fragment carries live tokens.
                Log.e(TAG, "redirect had no access_token; fragment keys="
                        + frag.getQueryParameterNames());
                rejectLogin(TXT_ERR_VERIFY);
                return;
            }
            final String refreshToken = frag.getQueryParameter("refresh_token");
            final String userId = frag.getQueryParameter("user_id");
            Log.d(TAG, "redirect ok: access_token present, refresh_token="
                    + (refreshToken != null) + " user_id=" + (userId != null));

            dismissPopup();
            runOnUiThread(() -> {
                clearError();
                titleText.setText(TXT_FINISHING);
                if (webView != null) {
                    webView.loadData(
                            "<html><body style='background:#0b0b0f;color:#e6e6ea;" +
                            "font-family:sans-serif;font-size:20px;text-align:center;" +
                            "padding-top:40%'>Logging in to GOG...</body></html>",
                            "text/html", "UTF-8");
                }
            });

            new Thread(new LoginRunnable(accessToken, refreshToken, userId)).start();
        }
    }

    // ── Background Runnable: fetch username, save prefs, finish ──────────────

    private class LoginRunnable implements Runnable {
        final String accessToken, refreshToken, userId;

        LoginRunnable(String accessToken, String refreshToken, String userId) {
            this.accessToken  = accessToken;
            this.refreshToken = refreshToken;
            this.userId       = userId;
        }

        @Override
        public void run() {
            try {
                // Fetch username from userData.json
                String username = "Unknown";
                try {
                    URL url = new URL("https://embed.gog.com/userData.json");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                    }
                    conn.disconnect();
                    String parsed = parseJsonStringField(sb.toString(), "username");
                    if (parsed != null) username = parsed;
                } catch (Exception e) {
                    Log.w(TAG, "userData.json fetch failed, username stays Unknown: " + e);
                }

                // Save to SharedPreferences — same contract GogMainActivity / GogTokenRefresh read.
                SharedPreferences.Editor ed = GogLoginActivity.this
                        .getSharedPreferences("bh_gog_prefs", 0).edit();
                ed.putString("access_token", accessToken);
                if (refreshToken != null) ed.putString("refresh_token", refreshToken);
                if (userId != null)       ed.putString("user_id", userId);
                ed.putString("username", username);
                int nowSec = (int) (System.currentTimeMillis() / 1000L);
                ed.putInt("bh_gog_login_time", nowSec);
                ed.putInt("bh_gog_expires_in", 3600);
                ed.apply();

                Log.d(TAG, "GOG login saved OK");   // don't log username (PII)
                runOnUiThread(() -> finish());
            } catch (Exception e) {
                Log.e(TAG, "Login post-processing failed", e);
                rejectLogin(TXT_ERR_GENERIC);
            }
        }
    }
}
