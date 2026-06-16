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
// =========================================================================

private const val WINE_ACTIVITY =
    "Lcom/xiaoji/egggame/features/winemu/WineActivity;"

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
        firstMethod {
            definingClass == WINE_ACTIVITY &&
                name == "onResume" &&
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
