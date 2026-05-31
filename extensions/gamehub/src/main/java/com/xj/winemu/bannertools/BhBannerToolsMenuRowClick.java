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
 * 3 per-game menu sites. Click pops an {@link AlertDialog} whose 4 items
 * dispatch into the existing per-feature handlers — so all settings
 * activities (BhVibrationSettingsActivity / BhGpuSpoofSettingsActivity /
 * BhRendererSettingsActivity) and the game-id dialog stay in their
 * current modules and code paths.
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

    // Tile spec: short label + drawable name resolved at runtime via
    // Resources.getIdentifier() (R class belongs to the foreign GameHub pkg).
    // Order maps 1:1 onto dispatch(int).
    private static final String[] TILE_LABELS = new String[] {
        "Vibration",
        "GPU Spoof",
        "Renderer",
        "Game ID",
        "Audio",
        "GOG",
        "Overlay",
        "Root",
    };
    private static final String[] TILE_DRAWABLES = new String[] {
        "bh_bt_vibration",
        "bh_bt_gpu_spoof",
        "bh_bt_renderer",
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
        return i == 0 || i == 1 || i == 2 || i == 3; // Vibration/GPU Spoof/Renderer/Game ID
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
                    new com.xj.winemu.gpuspoof.BhGpuSpoofMenuRowClick().invoke(null);
                    break;
                case 2:
                    new com.xj.winemu.renderer.BhRendererMenuRowClick().invoke(null);
                    break;
                case 3:
                    new com.xj.winemu.gameid.BhGameIdDisplayMenuRowClick().invoke(null);
                    break;
                case 4:
                    new com.xj.winemu.audio.BhAudioMenuRowClick().invoke(null);
                    break;
                case 5:
                    // GOG tile opens the GOG login/library hub Activity
                    // (not a dialog like the others); BhGogMenuRowClick.invoke
                    // walks to the top Activity and startActivity(GogMainActivity).
                    new com.xj.winemu.gog.BhGogMenuRowClick().invoke(null);
                    break;
                case 6:
                    com.xj.winemu.perf.BhPerfMenus.showOverlayToggleDialog(host);
                    break;
                case 7:
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

            Class<?> iaeCls = Class.forName("iae");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> pw6Cls = Class.forName("pw6");

            Class<?> zz4Cls = Class.forName("zz4");
            // zz4.v = "settings" icon (same icon other 3 rows use). Field
            // is an Lxrl wrapper whose getValue() returns the Lo05 icon.
            Field iconHolderField = zz4Cls.getDeclaredField("v");
            iconHolderField.setAccessible(true);
            Object xrlWrapper = iconHolderField.get(null);
            if (xrlWrapper == null) {
                Log.w(TAG, "zz4.v is null; cannot resolve icon");
                return;
            }
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) {
                Log.w(TAG, "zz4.v.getValue() did not return Lo05");
                return;
            }

            Object click = newFunction1Proxy(pw6Cls);
            java.lang.reflect.Constructor<?> ctor =
                iaeCls.getDeclaredConstructor(o05Cls, String.class, pw6Cls);
            ctor.setAccessible(true);
            list.add(ctor.newInstance(iconValue, ROW_LABEL, click));
        } catch (Throwable t) {
            Log.w(TAG, "appendBannerToolsRowTo failed", t);
        }
    }

    /** Library-tile popup (ted.f): rebuilds the Lscd list with our row added. */
    public static java.util.List<Object> appendScdRowToTedList(Object original) {
        try {
            if (!(original instanceof java.util.List)) return safeReturn(original);
            java.util.ArrayList<Object> augmented =
                new java.util.ArrayList<>((java.util.List<?>) original);

            Class<?> scdCls = Class.forName("scd");
            Class<?> o05Cls = Class.forName("o05");
            Class<?> nw6Cls = Class.forName("nw6");
            Class<?> zz4Cls = Class.forName("zz4");

            Field iconField = zz4Cls.getDeclaredField("v");
            iconField.setAccessible(true);
            Object xrlWrapper = iconField.get(null);
            if (xrlWrapper == null) return safeReturn(original);
            Object iconValue = xrlWrapper.getClass().getMethod("getValue").invoke(xrlWrapper);
            if (!o05Cls.isInstance(iconValue)) return safeReturn(original);

            Object click = newFunction0Proxy(nw6Cls);
            java.lang.reflect.Constructor<?> ctor =
                scdCls.getDeclaredConstructor(String.class, o05Cls, String.class, nw6Cls);
            ctor.setAccessible(true);
            augmented.add(ctor.newInstance(ACTION_ID, iconValue, ROW_LABEL, click));
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

            Class<?> z4eCls = Class.forName("z4e");
            Class<?> ellCls = Class.forName("ell");
            Class<?> tdiCls = Class.forName("tdi");
            Class<?> nw6Cls = Class.forName("nw6");

            // Lell is an empty Kotlin subclass of Ltdi — allocate via
            // Unsafe (skips ctor) and reflect-set inherited fields. Same
            // technique as the 4 per-feature handlers.
            Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Object label = unsafeCls.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, ellCls);
            Field aField = tdiCls.getDeclaredField("a");
            aField.setAccessible(true);
            aField.set(label, LABEL_SENTINEL);
            Field bField = tdiCls.getDeclaredField("b");
            bField.setAccessible(true);
            bField.set(label, java.util.Collections.emptySet());

            Object click = newFunction0Proxy(nw6Cls);
            java.lang.reflect.Constructor<?> z4eCtor =
                z4eCls.getDeclaredConstructor(ellCls, nw6Cls, int.class);
            z4eCtor.setAccessible(true);
            augmented.add(z4eCtor.newInstance(label, click, 0));
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
