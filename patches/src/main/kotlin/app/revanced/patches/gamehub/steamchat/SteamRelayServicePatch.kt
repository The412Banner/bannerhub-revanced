package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// =========================================================================
// Steam relay service (6.3.1 chat transport for the Steam · Friends overlay).
//
// GameHub 6.3.1 creates its Steam bridge (R8 `twv`, ex-SteamBridgeClient)
// ONLY in the main process, while the overlay runs in `:pcengine`
// (PcEnginePluginHostActivity, android:process=":pcengine"). The extension's
// com.xj.winemu.steamchat.BhSteamRelayService is a Messenger service that
// lives in the MAIN process (no android:process attribute), attaches to the
// bridge singleton structurally through Koin, and exposes
// EXEC(cmd, json, timeout) / SUBSCRIBE(topic) → EVENT(topic, json) to the
// overlay's BhSteamRelayClient. Without this <service> entry bindService()
// returns false and BhSteamBridge falls back to XiaoJi's invite IPC (friends
// + presence only, no chat).
//
// Manifest-editing idiom copied from gog/GogManifestPatch.kt (idempotent).
// =========================================================================

private const val RELAY_SERVICE = "com.xj.winemu.steamchat.BhSteamRelayService"

@Suppress("unused")
val steamRelayServicePatch = resourcePatch(
    name = "Steam chat relay service",
    description = "Registers the main-process Messenger service that relays the " +
        "in-game Steam chat overlay's commands and events to GameHub's Steam " +
        "bridge (6.3.1 keeps the bridge main-process-only while the overlay " +
        "runs in :pcengine). exported=false, same process as the bridge.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            val services = app.getElementsByTagName("service")
            for (i in 0 until services.length) {
                if ((services.item(i) as Element).getAttribute("android:name") == RELAY_SERVICE) {
                    return@use
                }
            }

            val service = dom.createElement("service").apply {
                setAttribute("android:name", RELAY_SERVICE)
                setAttribute("android:exported", "false")
                // Deliberately NO android:process — it must share the main
                // process with the Steam bridge singleton.
            }
            app.appendChild(service)
        }
    }
}
