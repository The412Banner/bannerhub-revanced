package app.revanced.patches.gamehub.perf

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// =========================================================================
// In-game Performance Overlay — attaches a Banner-owned, classic-View edge
// pill + slide-out panel over the Wine game surface with two root-gated
// toggles (Sustained Performance Mode, Max Adreno Clocks). Auto-reverts both
// to hardware defaults when the game exits.
//
// WHY AN OVERLAY (not a native menu row): the 6.0.4 in-game side-menu drawer
// (Controls / Performance / Settings / Keyboard) is entirely hand-coded
// Jetpack Compose — the Performance tab's row layout is one ~45-parameter
// Composable with no List<row> seam to append to (verified by decompiling
// gamehub.lite vc114, ojo.java). Our ReVanced extension has no Compose
// compiler, so we cannot author a native @Composable row, and the menus we
// CAN inject native rows into (game-detail popup, via appendScdRowToTedList)
// are pre-launch surfaces, not the in-game overlay. A View overlay on
// WineActivity's decor is the only way to surface these toggles WHILE a game
// is running.
//
// Hook surface: WineActivity is NOT obfuscated
// (com.xiaoji.egggame.features.winemu.WineActivity), so we anchor directly on
// its lifecycle methods (verified present in 6.0.4: onCreate(Bundle),
// onResume(), onDestroy()).
//
//   onResume()V  -> BhPerfOverlay.attach(this)    [idempotent; re-attaches
//                   after Home->resume; guarded by a view tag so double
//                   onCreate/onResume can't double-add]
//   onDestroy()V -> BhPerfOverlay.revertAndDetach(this)  [restores governor
//                   ->schedutil and kgsl min_freq->0, removes the overlay]
//
// Register safety: both lifecycle methods may have .locals > 15, which would
// make a direct `invoke-static {p0}` (pN above v15) fail. We materialise `this`
// into v0 via move-object/from16 first. Writing v0 at method entry is safe:
// local registers are undefined at entry, so the original body must write v0
// before any read — our clobber can't be observed. Same idiom the vibration
// ENV_BUILDER hook uses.
//
// 6.3.1 (also 6.1.0+): WineActivity is GONE from the host dex — the manifest
// only keeps it as an <activity-alias> onto LegacyPcEngineActivityTrampoline
// (AndroidManifest.xml:136), so the old fingerprints resolve to nothing. The
// Wine session now runs in the downloadable pcengine plugin, hosted by the
// kept, manifest-declared, `:pcengine`-process Activity
// com.xiaoji.egggame.plugin.pcengine.host.PcEnginePluginHostActivity
// (smali/com/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity.smali,
// AndroidManifest.xml:133 android:process=":pcengine"). It extends
// com.combo.core.component.activity.BaseHostActivity (a ComponentActivity —
// p0 IS an Activity, so attach(Activity) stays type-correct) and forwards
// every lifecycle callback to the plugin's EngineWinePluginActivity, so the
// timing matches the old WineActivity hooks. It does NOT override onResume
// (only BaseHostActivity does, .locals 0, shared with the pcengine
// settings/dependency-install/recommendation host activities), so the attach
// hook moves to its own `onStart()V` (final, .locals 3, :1699) — exact class,
// no class-name gate needed in the extension; attach() already defers via
// decor.post() until the window token exists, so onStart-vs-onResume is
// immaterial. Detach stays on `onDestroy()V` (final, .locals 4, :1322). Both
// bodies write v0 before reading it (`const/4 v0` / `move-result v0`), so the
// from16 clobber at index 0 is still safe. Each name is unique on the class.
// Runtime caveat: the overlay now lives in `:pcengine` while its toggle UI is
// written in the main process — MODE_PRIVATE SharedPreferences are not
// multi-process-coherent, so a stale toggle is possible until the process dies.
// =========================================================================

private const val WINE_ACTIVITY =
    "Lcom/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity;"

private const val OVERLAY =
    "Lcom/xj/winemu/perf/BhPerfOverlay;"

@Suppress("unused")
val perfOverlayPatch = bytecodePatch(
    name = "In-game performance overlay",
    description = "Adds a draggable edge pill + slide-out panel over the Wine " +
        "game surface with two root-gated toggles — Sustained Performance Mode " +
        "(locks all CPU cores to the 'performance' governor) and Max Adreno " +
        "Clocks (pins the KGSL GPU min_freq to max_freq). Both auto-revert to " +
        "defaults when the game exits. Root is checked once and cached; the " +
        "toggles are greyed until granted.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // attach on start (6.3.1: the host activity has no onResume override;
        // decor view exists after onCreate; idempotent via view tag)
        firstMethod {
            definingClass == WINE_ACTIVITY &&
                name == "onStart" &&
                parameterTypes.isEmpty() &&
                returnType == "V"
        }.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-static {v0}, $OVERLAY->attach(Landroid/app/Activity;)V
            """.trimIndent(),
        )

        // revert hardware + detach overlay on destroy
        firstMethod {
            definingClass == WINE_ACTIVITY &&
                name == "onDestroy" &&
                parameterTypes.isEmpty() &&
                returnType == "V"
        }.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-static {v0}, $OVERLAY->revertAndDetach(Landroid/app/Activity;)V
            """.trimIndent(),
        )
    }
}
