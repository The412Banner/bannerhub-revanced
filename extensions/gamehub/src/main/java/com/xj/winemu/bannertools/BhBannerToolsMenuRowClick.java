package com.xj.winemu.bannertools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.xj.winemu.common.BhMenuGameId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import kotlin.jvm.functions.Function1;

/**
 * Onclick handler for the consolidated "Banner Tools" row injected at the
 * 3 per-game menu sites. Click pops an {@link AlertDialog} whose tiles
 * dispatch into the existing per-feature handlers — so all settings
 * activities (BhVibrationSettingsActivity / BhAudioSettingsActivity, etc.)
 * and the game-id dialog stay in their current modules and code paths.
 * (Renderer + GPU Spoof tiles are dropped on 6.0.7 — see TILE_LABELS.)
 *
 * <p>Sibling of {@link com.xj.winemu.vibration.BhMenuRowClick} and the
 * other 3 per-feature row handlers — same reflection strategy against the
 * R8-mangled Compose menu data classes, same activity-stack resolution.
 * The only behavioural difference is that this handler's
 * {@link #invoke(Object)} opens a dialog instead of launching an Activity
 * directly.
 *
 * <p>Per-game id is captured by the shared {@code menuGameIdCapturePatch}
 * into {@link BhMenuGameId}; the downstream per-feature handlers read it
 * on dispatch — we don't need to pass anything through the dialog.
 */
public final class BhBannerToolsMenuRowClick implements Function1<Object, Object> {

    private static final String TAG = "BhBannerToolsRow";

    private static final String ROW_LABEL = "Banner Tools";
    private static final String ACTION_ID = "local_detail_menu_banner_tools";
    static final String LABEL_SENTINEL = "string:bh_banner_tools_label";
    static final String LABEL_SENTINEL_KEY = "bh_banner_tools_label";

    // ── 6.3.1 host letter map (re-derive on every base bump; see the patch
    // headers in patches/.../gamehub/common/MenuGameIdCapturePatch.kt) ──
    // 6.0.9 → 6.3.1: uhd→cpm, xoc→blk, pcd→dzl, yc5.x→ena.L; qd5/t47/r47/lok are
    // now the real library names (un-obfuscated since 6.1.0).
    private static final String MORE_MENU_ROW_CLS = "cpm";   // MoreMenuItem(DrawableResource,String,Function1)
    private static final String TILE_ROW_CLS      = "blk";   // LocalDetailMoreMenuItem(String,String,Function0,DrawableResource)
    private static final String LIST_ROW_CLS      = "dzl";   // MenuItemData(Function0,StringResource,boolean)
    private static final String ICON_HOLDER_CLS   = "ena";   // Lena;->L:Lkotlin/Lazy; = drawable:core_icon_pc
    private static final String ICON_HOLDER_FIELD = "L";
    private static final String DRAWABLE_RES_CLS  = "org.jetbrains.compose.resources.DrawableResource";
    private static final String STRING_RES_CLS    = "org.jetbrains.compose.resources.StringResource";
    private static final String FUNCTION0_CLS     = "kotlin.jvm.functions.Function0";
    private static final String FUNCTION1_CLS     = "kotlin.jvm.functions.Function1";

    // Tile spec: short label + drawable name resolved at runtime via
    // Resources.getIdentifier() (R class belongs to the foreign GameHub pkg).
    // Order maps 1:1 onto dispatch(int).
    // NOTE: "GPU Spoof" dropped on 6.0.7 — the base GameHub app now ships a
    // native GPU-spoof feature, so BannerHub's redundant tile + patches are
    // gated off on 607 (GpuSpoof{Patch,ManifestPatch,MenuRowPatch} pinned to
    // 6.0.4). "Renderer" likewise dropped on 6.0.7: the Legacy GLES2 path needs
    // the 6.0.2 libxserver, which is binary-incompatible with 6.0.7's rewritten
    // XServer (device-confirmed SIGABRT), so the renderer patches are pinned to
    // 6.0.4 and the tile would just dead-launch an unregistered activity. Keep
    // this array in lock-step with dispatch(int).
    private static final String[] TILE_LABELS = new String[] {
        "Vibration",
        "Game ID",
        "Audio",
        "GOG",
        "Overlay",
        "Root",
    };
    private static final String[] TILE_DRAWABLES = new String[] {
        "bh_bt_vibration",
        "bh_bt_game_id",
        "bh_bt_audio",
        "bh_bt_gog",
        "bh_bt_overlay",
        "bh_bt_root",
    };

    // Tile indices that act on the CURRENT GAME (need a gameId in scope).
    // When Banner Tools is opened from the Explore page (no game), these are
    // greyed + non-interactive; the global tiles (Audio, GOG, Overlay, Root)
    // stay usable. Keep in sync with dispatch(int) ordering.
    private static boolean isPerGameTile(int i) {
        return i == 0 || i == 1; // Vibration/Game ID
    }

    @Override
    public Object invoke(Object ignoredFromCompose) {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot show dialog");
                return kotlin.Unit.INSTANCE;
            }
            host.runOnUiThread(() -> showDialog(host, false));
        } catch (Throwable t) {
            Log.w(TAG, "menu click failed", t);
        }
        return kotlin.Unit.INSTANCE;
    }

    /** Entry point for the Explore page's Banner Tools card. No game is in
     *  scope there, so the per-game tiles are greyed; the global tiles
     *  (Audio, GOG, Overlay, Root) stay usable. */
    public static void openFromExplore() {
        try {
            Activity host = resolveTopActivity();
            if (host == null) {
                Log.w(TAG, "no top Activity resolvable; cannot show dialog");
                return;
            }
            host.runOnUiThread(() -> showDialog(host, true));
        } catch (Throwable t) {
            Log.w(TAG, "openFromExplore failed", t);
        }
    }

    private static void showDialog(Activity host, boolean fromExplore) {
        try {
            java.util.List<View> tiles = new java.util.ArrayList<>();
            LinearLayout content = buildTilesView(host, tiles);

            final AlertDialog dialog = new AlertDialog.Builder(host)
                .setTitle(ROW_LABEL)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

            // Wire per-tile clicks now that we have the dialog reference;
            // tiles are in dispatch-index order so which == dispatch case.
            // The Overlay tile is NOT root-gated any more — it opens for everyone
            // and holds the Steam-chat (no root) + Performance (root-gated inside
            // the dialog itself) toggles.
            for (int i = 0; i < tiles.size(); i++) {
                final int which = i;
                final View tile = tiles.get(i);
                if (fromExplore && isPerGameTile(which)) {
                    // No game in scope — grey out and explain on tap.
                    tile.setAlpha(0.4f);
                    tile.setOnClickListener(v ->
                        Toast.makeText(host, "Open from a game", Toast.LENGTH_SHORT).show());
                } else {
                    tile.setOnClickListener(v -> {
                        dispatch(host, which);
                        dialog.dismiss();
                    });
                }
            }

            dialog.show();
        } catch (Throwable t) {
            Log.w(TAG, "showDialog failed", t);
        }
    }

    // ── Tile grid (icon + short label per tile) ──────────────────────────
    // Built programmatically so we don't need an XML layout resource
    // injected into the foreign package. Icons resolve to the bh_bt_*
    // vector drawables shipped by BannerToolsDrawablesPatch. Tiles wrap at
    // 5 per row (greedy, left-aligned) so 6+ tiles don't clip the fixed-size
    // 56dp icons on narrow/portrait screens; the short final row is padded
    // with weighted spacers to keep its tiles the same width as a full row.
    // Tiles are appended to outTiles in dispatch-index order so click wiring
    // stays 1:1 with dispatch(int) regardless of how they're grouped.
    private static LinearLayout buildTilesView(Activity host, java.util.List<View> outTiles) {
        final Resources res = host.getResources();
        final String pkg = host.getPackageName();
        final float density = res.getDisplayMetrics().density;

        final int n = TILE_LABELS.length;
        // 5 tiles across, then start a new row.
        final int perRow = 5;

        LinearLayout container = new LinearLayout(host);
        container.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(density, 8);
        int padV = dp(density, 12);
        container.setPadding(padH, padV, padH, padV);

        LinearLayout currentRow = null;
        for (int i = 0; i < n; i++) {
            if (i % perRow == 0) {
                currentRow = new LinearLayout(host);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                rowLp.topMargin = (i == 0) ? 0 : dp(density, 8);
                container.addView(currentRow, rowLp);
            }
            View tile = buildTile(host, res, pkg, density, TILE_DRAWABLES[i], TILE_LABELS[i]);
            currentRow.addView(tile);
            outTiles.add(tile);
        }
        // Pad the final row with weighted spacers so its tiles keep the same
        // width as full rows (otherwise a short last row stretches its tiles).
        int remainder = n % perRow;
        if (remainder != 0) {
            for (int k = remainder; k < perRow; k++) {
                View spacer = new View(host);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
                currentRow.addView(spacer);
            }
        }
        return container;
    }

    private static View buildTile(Activity host, Resources res, String pkg,
                                  float density, String drawableName, String label) {
        LinearLayout tile = new LinearLayout(host);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int sideMargin = dp(density, 2);
        lp.setMargins(sideMargin, 0, sideMargin, 0);
        tile.setLayoutParams(lp);

        int inner = dp(density, 8);
        tile.setPadding(inner, inner, inner, inner);

        // Ripple feedback — selectableItemBackground resolves to the host
        // theme's ripple drawable, so M3 themes get bounded ripples and
        // legacy themes get a sensible fallback.
        TypedValue tv = new TypedValue();
        if (host.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            tile.setBackgroundResource(tv.resourceId);
        }
        tile.setClickable(true);
        tile.setFocusable(true);

        // Icon — 56dp, self-contained dark background already baked into
        // the vector drawable so we don't tint it.
        ImageView iv = new ImageView(host);
        int iconId = res.getIdentifier(drawableName, "drawable", pkg);
        if (iconId != 0) {
            iv.setImageResource(iconId);
        } else {
            Log.w(TAG, "drawable not found: " + drawableName);
        }
        int iconPx = dp(density, 56);
        iv.setLayoutParams(new LinearLayout.LayoutParams(iconPx, iconPx));
        tile.addView(iv);

        // Label.
        TextView txt = new TextView(host);
        txt.setText(label);
        txt.setGravity(Gravity.CENTER_HORIZONTAL);
        txt.setSingleLine(true);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        txtLp.topMargin = dp(density, 6);
        txt.setLayoutParams(txtLp);
        tile.addView(txt);

        return tile;
    }

    private static int dp(float density, int dp) {
        return Math.round(density * dp);
    }

    private static void dispatch(Activity host, int which) {
        try {
            switch (which) {
                case 0:
                    new com.xj.winemu.vibration.BhMenuRowClick().invoke(null);
                    break;
                case 1:
                    new com.xj.winemu.gameid.BhGameIdDisplayMenuRowClick().invoke(null);
                    break;
                case 2:
                    new com.xj.winemu.audio.BhAudioMenuRowClick().invoke(null);
                    break;
                case 3:
                    // GOG tile opens the GOG login/library hub Activity
                    // (not a dialog like the others); BhGogMenuRowClick.invoke
                    // walks to the top Activity and startActivity(GogMainActivity).
                    new com.xj.winemu.gog.BhGogMenuRowClick().invoke(null);
                    break;
                case 4:
                    com.xj.winemu.perf.BhPerfMenus.showOverlayToggleDialog(host);
                    break;
                case 5:
                    com.xj.winemu.perf.BhPerfMenus.showRootDialog(host);
                    break;
                default:
                    Log.w(TAG, "unknown dialog item index " + which);
            }
        } catch (Throwable t) {
            Log.w(TAG, "dispatch failed for item " + which, t);
        }
    }

    /** Walk ActivityThread.mActivities for a Resumed/Started activity. */
    private static Activity resolveTopActivity() {
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Method cur = atCls.getMethod("currentActivityThread");
            Object at = cur.invoke(null);
            if (at == null) return null;
            Field fActs = atCls.getDeclaredField("mActivities");
            fActs.setAccessible(true);
            Object acts = fActs.get(at);
            if (!(acts instanceof Map)) return null;
            Activity fallback = null;
            for (Object record : ((Map<?, ?>) acts).values()) {
                if (record == null) continue;
                Field fAct = record.getClass().getDeclaredField("activity");
                fAct.setAccessible(true);
                Object a = fAct.get(record);
                if (!(a instanceof Activity)) continue;
                Activity act = (Activity) a;
                if (act.isFinishing() || act.isDestroyed()) continue;
                if (fallback == null) fallback = act;
                Field fPaused = record.getClass().getDeclaredField("paused");
                fPaused.setAccessible(true);
                Object paused = fPaused.get(record);
                if (paused instanceof Boolean && !((Boolean) paused)) {
                    return act;
                }
            }
            return fallback;
        } catch (Throwable t) {
            Log.w(TAG, "resolveTopActivity failed", t);
            return null;
        }
    }

    /** Game-details More Menu (Lx57;->a): appends an Liae row. */
    public static void appendBannerToolsRowTo(Object menuList) {
        try {
            if (!(menuList instanceof java.util.List)) return;
            java.util.List list = (java.util.List) menuList;

            // 6.0.7: row Liae→Ltyc, icon Lo05→Ln55, onClick(Function1) Lpw6→Lgv6,
            // icon-holder Lzz4→Lv45 (field v→l), wrapper Lxrl→Lu3k.
            // 6.0.9 — ICON MODEL CHANGED: row Lwyc→Luhd, ctor (Lqd5 icon, String,
            // Lt47 onClick). The "icon" param is now a Lqd5 DrawableResource
            // (extends resource base Lo4h), NOT an ImageVector — so the old
            // Lv45.l → Lu3k.getValue() unwrap is gone; we load a ready-made
            // static Lqd5 directly (see loadStaticIcon). onClick Lfv6→Lt47
            // (Function1: invoke(Object)Object).
            // 6.3.1: row Luhd→Lcpm "MoreMenuItem(iconRes, label, onClick, d)". The
            // library types are un-obfuscated since 6.1.0: icon is
            // org.jetbrains.compose.resources.DrawableResource, onClick is
            // kotlin.jvm.functions.Function1 — only the row class letter moves.
            Class<?> uhdCls = Class.forName(MORE_MENU_ROW_CLS);
            Class<?> qd5Cls = Class.forName(DRAWABLE_RES_CLS);
            Class<?> t47Cls = Class.forName(FUNCTION1_CLS);

            Object icon = loadStaticIcon();
            if (icon == null || !qd5Cls.isInstance(icon)) {
                Log.w(TAG, "no DrawableResource icon resolved; skipping More-Menu row");
                return;
            }

            Object click = newFunction1Proxy(t47Cls);
            java.lang.reflect.Constructor<?> ctor =
                uhdCls.getDeclaredConstructor(qd5Cls, String.class, t47Cls);
            ctor.setAccessible(true);
            list.add(ctor.newInstance(icon, ROW_LABEL, click));
        } catch (Throwable t) {
            Log.w(TAG, "appendBannerToolsRowTo failed", t);
        }
    }

    /**
     * A {@code Lqd5} DrawableResource for our row's leading icon.
     * 6.0.9: the row-icon render path XML-parses the DrawableResource (vector
     * loader), so a raster (PNG)-backed Lqd5 crashes with SAXParseException
     * ("Unexpected token: PNG …IHDR") — device-found 2026-06-18 with the first
     * pick {@code Leyn.b} (a PNG). Fix: reuse the EXACT icon a NATIVE More-Menu
     * row uses — {@code Lyc5;->x:Lkwk;} is sget'd in lc7.a and its
     * {@code getValue()} is passed straight into a {@code Luhd} row ctor as the
     * Lqd5 icon, so it is guaranteed to render in this very menu. Unwrap the
     * lazy {@code Lkwk} (Function0-backed) to the cached Lqd5; no Composer
     * needed (the wrapper resolves its provider on first getValue, same as the
     * app's own static init). Returns null on failure (callers skip the row).
     */
    private static Object loadStaticIcon() {
        try {
            // 6.3.1: the native "PC Game Settings" row (first row in Lwhd;->a)
            // takes its icon from Lena;->L:Lkotlin/Lazy; = drawable:core_icon_pc
            // (an .xml vector, so it survives the vector-only loader). The lazy
            // wrapper is plain kotlin.Lazy now.
            Field f = Class.forName(ICON_HOLDER_CLS).getDeclaredField(ICON_HOLDER_FIELD);
            f.setAccessible(true);
            Object wrapper = f.get(null);          // kotlin.Lazy<DrawableResource>
            if (wrapper == null) return null;
            return wrapper.getClass().getMethod("getValue").invoke(wrapper);  // DrawableResource
        } catch (Throwable t) {
            Log.w(TAG, "loadStaticIcon failed", t);
            return null;
        }
    }

    /** Library-tile popup (ted.f): rebuilds the Lscd list with our row added. */
    public static java.util.List<Object> appendScdRowToTedList(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.ArrayList<Object> augmented =
                new java.util.ArrayList<>((java.util.List<?>) original);

            // 6.0.7: tile row Lscd→Lg6c (ctor String,icon,String,onClick), icon
            // Lo05→Ln55, onClick(Function0) Lnw6→Lev6, holder Lzz4→Lv45(v→l).
            // 6.0.9: tile row Lj6c→Lxoc (ctor String action, Lqd5 icon, String
            // label, Lr47 onClick). Icon is now Lqd5 DrawableResource (load a
            // static one, no getValue unwrap). onClick Ldv6→Lr47 (Function0).
            // 6.3.1: tile row Lxoc→Lblk "LocalDetailMoreMenuItem(id, icon, label,
            // onClick)" — ctor PARAM ORDER is (String id, String label, Function0
            // onClick, DrawableResource icon) (fields a=id b=icon c=label d=onClick).
            Class<?> xocCls = Class.forName(TILE_ROW_CLS);
            Class<?> qd5Cls = Class.forName(DRAWABLE_RES_CLS);
            Class<?> r47Cls = Class.forName(FUNCTION0_CLS);

            Object icon = loadStaticIcon();
            if (icon == null || !qd5Cls.isInstance(icon)) return safeReturn(original);

            Object click = newFunction0Proxy(r47Cls);
            java.lang.reflect.Constructor<?> ctor =
                xocCls.getDeclaredConstructor(String.class, String.class, r47Cls, qd5Cls);
            ctor.setAccessible(true);
            augmented.add(ctor.newInstance(ACTION_ID, ROW_LABEL, click, icon));
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendScdRowToTedList failed", t);
            return safeReturn(original);
        }
    }

    /**
     * Library-LIST popup (Lpzc;->j0): appends an Lz4e(Lell,Lnw6,int) row.
     * The Lell label carries sentinel key "string:bh_banner_tools_label",
     * resolved by the shared Lxd3;->l1 hook in
     * BhMenuRowClick.maybeResolveCustomLabel (owned by vibrationMenuRowPatch).
     */
    public static java.util.List<Object> appendLibraryPopupRow(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.ArrayList<Object> augmented =
                new java.util.ArrayList<>((java.util.List<?>) original);

            // 6.0.7: list-popup row Lz4e→Lstc (ctor Ldwj,Lev6,int), StringResource
            // Lell→Ldwj, resource base Ltdi→Lshg, onClick(Function0) Lnw6→Lev6.
            // 6.0.9: list-popup row Lvtc→Lpcd (ctor Llok label, Lr47 onClick, int
            // — NO icon param). StringResource Lkwj→Llok; resource base Lvhg→Lo4h
            // (a=key, b=locales); onClick Ldv6→Lr47 (Function0).
            // 6.3.1: list-popup row Lpcd→Ldzl "MenuItemData(textRes, b, onClick)",
            // primary ctor (Function0 onClick, StringResource textRes, boolean b);
            // native rows pass b=true (synthetic mask 0x6 → bit 0x4 set). The label
            // is the REAL org.jetbrains.compose.resources.StringResource now, with a
            // public (String id, String key, Set items) ctor — super Lpms; stores
            // id in field `a`, which is what maybeResolveCustomLabel reads — so no
            // Unsafe allocation is needed any more.
            Class<?> pcdCls = Class.forName(LIST_ROW_CLS);
            Class<?> lokCls = Class.forName(STRING_RES_CLS);
            Class<?> r47Cls = Class.forName(FUNCTION0_CLS);

            java.lang.reflect.Constructor<?> labelCtor =
                lokCls.getDeclaredConstructor(String.class, String.class, java.util.Set.class);
            labelCtor.setAccessible(true);
            Object label = labelCtor.newInstance(
                LABEL_SENTINEL, LABEL_SENTINEL_KEY, java.util.Collections.emptySet());

            Object click = newFunction0Proxy(r47Cls);
            java.lang.reflect.Constructor<?> z4eCtor =
                pcdCls.getDeclaredConstructor(r47Cls, lokCls, boolean.class);
            z4eCtor.setAccessible(true);
            augmented.add(z4eCtor.newInstance(click, label, true));
            return augmented;
        } catch (Throwable t) {
            Log.w(TAG, "appendLibraryPopupRow failed", t);
            return safeReturn(original);
        }
    }

    // R8 renamed kotlin Function1/Function0; a Java `implements` is a
    // different JVM type than the host's Lpw6;/Lnw6;. Proxy actually
    // implements the host interface and delegates to invoke().
    private static Object newFunction1Proxy(Class<?> pw6Cls) {
        final BhBannerToolsMenuRowClick handler = new BhBannerToolsMenuRowClick();
        return java.lang.reflect.Proxy.newProxyInstance(
            pw6Cls.getClassLoader(), new Class<?>[]{ pw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) {
                    return handler.invoke(args != null && args.length > 0 ? args[0] : null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhBannerToolsRowClickProxy";
                return null;
            });
    }

    private static Object newFunction0Proxy(Class<?> nw6Cls) {
        final BhBannerToolsMenuRowClick handler = new BhBannerToolsMenuRowClick();
        return java.lang.reflect.Proxy.newProxyInstance(
            nw6Cls.getClassLoader(), new Class<?>[]{ nw6Cls },
            (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) {
                    return handler.invoke(null);
                }
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "BhBannerToolsRowClick0";
                return null;
            });
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> safeReturn(Object o) {
        if (o instanceof java.util.List) return (java.util.List<Object>) o;
        return new java.util.ArrayList<>();
    }
}
