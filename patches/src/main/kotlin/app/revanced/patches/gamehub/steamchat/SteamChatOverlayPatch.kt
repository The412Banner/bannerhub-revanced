package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// =========================================================================
// In-game Steam · Friends overlay.
//
// Attaches a Banner-owned classic-View pill + slide-out panel over the Wine
// game surface: Steam friends list + presence, per-friend message history,
// send/receive (text, emoticons, stickers, images), voice rooms. Gated by
// the Banner Tools -> Steam Chat master toggle. The overlay only ever talks
// to BhSteamBridge.request(cmd, json, timeoutMs) / listen(topic, cb); every
// command string it sends (friends.list, friends.conversation_summaries,
// friends.message_history, friends.mark_conversation_read,
// friends.send_message, friends.send_typing, friends.chat_emoticons,
// friends.chat_stickers, friends.send_sticker, auth.bootstrap_snapshot,
// apps.app_details) and topic (steam:chat-message, steam:chat-typing) exists
// verbatim in 6.3.1's typed friends repository.
//
// Hook surface (6.3.1, also 6.1.0+): WineActivity is GONE from the host dex
// (manifest keeps only an <activity-alias> onto
// LegacyPcEngineActivityTrampoline). Anchored, exactly like PerfOverlayPatch,
// on the kept `:pcengine` host Activity
// com.xiaoji.egggame.plugin.pcengine.host.PcEnginePluginHostActivity:
// attach on its own `onStart()V` (it has no onResume override; attach()
// already defers via decor.post() until the window token exists), detach on
// `onDestroy()V`. Both write v0 before reading it, so the from16 clobber at
// index 0 stays safe. Exact class => no class-name gate in the extension.
//
// TRANSPORT (6.3.1 design — the reason the 6.0.x in-process bridge died):
// the host's Steam bridge (R8 `twv`, ex-SteamBridgeClient; `c` = executeRaw
// typed (String,String,<enum>,kotlin.time.Duration,ContinuationImpl), `d` =
// listenJson returning a kotlinx Flow) is a Koin singleton that exists ONLY
// in the MAIN process, while this overlay runs in `:pcengine`. BhSteamBridge
// therefore picks the best of three transports at first use and re-probes
// every 15 s until it has chat:
//   RELAY  BhSteamRelayClient (:pcengine) -> BhSteamRelayService (main
//          process, registered by steamRelayServicePatch). The service finds
//          the bridge structurally (GlobalContext.INSTANCE.get() -> the Koin
//          field with a (Koin) ctor -> its ConcurrentHashMaps ->
//          SingleInstanceFactory cached instances -> the class declaring the
//          executeRaw/listenJson shapes; no R8 letter hardcoded), drives the
//          suspend ABI with a ContinuationImpl subclass (kotlin/kotlinx keep
//          their names on 6.3.x) and collects Flows with a Java
//          FlowCollector. Messenger: EXEC(cmd,json,timeout) -> paged
//          REPLY{ok,result|error,error_kind}; SUBSCRIBE(topic) -> pushed
//          EVENT(topic,json). Full chat.
//   IPC    BhSteamIpcClient over XiaoJi's own cross-process
//          SteamFriendsChatAndroidService / OVERLAY_INVITE_IPC Messenger
//          (what 1 status -> readiness + steam_id, what 2 friends -> paged
//          friends_json whose fields match the overlay's parser). Friends +
//          presence (polled every 30 s) + own SteamID only; status reads
//          "friends only — chat relay unavailable: <why>".
//   NONE   "FAILED @ ..." with both reasons.
// Every Binder payload is paged (relay ~192 KB pages, IPC 100 friends/page)
// under the 1 MB transaction limit. logcat tag: BH_STEAM.
// =========================================================================

private const val WINE_ACTIVITY =
    "Lcom/xiaoji/egggame/plugin/pcengine/host/PcEnginePluginHostActivity;"

private const val OVERLAY =
    "Lcom/xj/winemu/steamchat/BhSteamChatOverlay;"

@Suppress("unused")
val steamChatOverlayPatch = bytecodePatch(
    name = "In-game Steam chat overlay",
    description = "Adds a draggable pill + slide-out panel over the Wine game " +
        "surface with your Steam friends list, presence, and chat. On 6.3.1 the " +
        "overlay (:pcengine) reaches GameHub's main-process Steam bridge through " +
        "the Banner relay service, falling back to XiaoJi's invite IPC for " +
        "friends/presence only. Off by default; toggle from Banner Tools -> Steam Chat.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(
        sharedGamehubExtensionPatch,
        steamRelayServicePatch,
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
