package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// =========================================================================
// In-game Steam Chat overlay (READ-ONLY PROTOTYPE).
//
// Attaches a Banner-owned classic-View pill + slide-out panel over the Wine
// game surface that surfaces your Steam friends list + presence, and a
// friend's recent message history on tap — pulled from GameHub's in-process
// Steam client via the steam_sdk bridge (Koin-singleton SteamBridgeClient,
// JSON-RPC over JNA). Request/response only; live push is a later increment.
// Gated by the Banner Tools -> Steam Chat master toggle.
//
// Same hook surface + register idiom as the perf overlay: WineActivity is NOT
// obfuscated (com.xiaoji.egggame.features.winemu.WineActivity); we anchor its
// lifecycle and materialise `this` into v0 via move-object/from16 so the
// invoke-static is valid even when the method has .locals > 15.
//
//   onResume()V  -> BhSteamChatOverlay.attach(this)   [idempotent via view-map]
//   onDestroy()V -> BhSteamChatOverlay.detach(this)
//
// 6.3.1 (also 6.1.0+): WineActivity is GONE from the host dex (manifest keeps
// only an <activity-alias> onto LegacyPcEngineActivityTrampoline,
// AndroidManifest.xml:136). Re-anchored, exactly like PerfOverlayPatch, on the
// kept `:pcengine` host Activity
// com.xiaoji.egggame.plugin.pcengine.host.PcEnginePluginHostActivity
// (smali/com/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity.smali):
// attach on its own `onStart()V` (final, .locals 3, :1699 — it has no onResume
// override, and attach() already defers via decor.post() until the window
// token exists), detach on `onDestroy()V` (final, .locals 4, :1322). Both
// write v0 before reading it, so the from16 clobber at index 0 stays safe.
// Exact class => no class-name gate needed in the extension.
//
// ⚠️ RUNTIME CAVEAT (not a fingerprint problem): the hook now applies, but
// BhSteamBridge's live path is dead on 6.3.1 —
// Class.forName("com.xiaoji.egggame.common.steam_sdk.bridge.SteamBridgeClient")
// has 0 hits in the host smali (class is R8-renamed), `listenJson` /
// `executeRaw` method names have 0 hits (renamed with it), and
// org.koin.core.Koin no longer exposes getInstanceRegistry() (631 keeps only
// getScopeRegistry(); the registry is the letter field Koin.d). The overlay
// will attach and report "not resolved". Fixing it is a structural rewrite of
// BhSteamBridge (Koin field walk for the singleton + a ContinuationImpl
// subclass for the suspend ABI), not a rename — see the 610 re-derivation
// notes.
// =========================================================================

private const val WINE_ACTIVITY =
    "Lcom/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity;"

private const val OVERLAY =
    "Lcom/xj/winemu/steamchat/BhSteamChatOverlay;"

@Suppress("unused")
val steamChatOverlayPatch = bytecodePatch(
    name = "In-game Steam chat overlay",
    description = "Adds a draggable pill + slide-out panel over the Wine game " +
        "surface that shows your Steam friends list, presence, and a friend's " +
        "recent message history (read-only). Reads GameHub's in-process Steam " +
        "client via the steam_sdk JSON-RPC bridge. Off by default; toggle from " +
        "Banner Tools -> Steam Chat.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(
        sharedGamehubExtensionPatch,
        steamChatImagePickerManifestPatch,
        steamChatVoiceManifestPatch,
        steamChatRingtonePickerManifestPatch,
        steamChatRingtonesAssetPatch,
    )

    apply {
        // 6.3.1: onStart — the host activity has no onResume override.
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

        firstMethod {
            definingClass == WINE_ACTIVITY &&
                name == "onDestroy" &&
                parameterTypes.isEmpty() &&
                returnType == "V"
        }.addInstructions(
            0,
            """
                move-object/from16 v0, p0
                invoke-static {v0}, $OVERLAY->detach(Landroid/app/Activity;)V
            """.trimIndent(),
        )
    }
}
