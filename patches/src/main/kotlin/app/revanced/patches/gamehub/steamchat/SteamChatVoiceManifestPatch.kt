package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import org.w3c.dom.Element

@Suppress("unused")
val steamChatVoiceManifestPatch = resourcePatch(
    name = "Steam chat voice permission",
    description = "Adds RECORD_AUDIO (+ MODIFY_AUDIO_SETTINGS) so the in-game Steam " +
        "chat overlay can run a WebRTC voice call (WebView getUserMedia). No-op if " +
        "the permissions are already present.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val manifest = dom.documentElement

            fun hasPermission(name: String): Boolean {
                val nodes = manifest.getElementsByTagName("uses-permission")
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as Element
                    if (el.getAttribute("android:name") == name) return true
                }
                return false
            }

            for (perm in listOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.MODIFY_AUDIO_SETTINGS",
            )) {
                if (hasPermission(perm)) continue
                val el = dom.createElement("uses-permission")
                el.setAttribute("android:name", perm)
                manifest.appendChild(el)
            }
        }
    }
}
