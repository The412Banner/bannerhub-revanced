package app.revanced.patches.gamehub.steamchat

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.steamchat.BhRingtonePickerActivity"

@Suppress("unused")
val steamChatRingtonePickerManifestPatch = resourcePatch(
    name = "Steam chat ringtone-picker activity",
    description = "Registers BhRingtonePickerActivity so the in-game Steam chat call " +
        "settings can pick a custom MP3 ringtone (persistable URI), and adds the " +
        "VIBRATE permission for vibrate-on-incoming-call. Internal-only " +
        "(android:exported=\"false\"); no-ops if already present.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val manifest = dom.documentElement

            // VIBRATE permission (no-op if already declared).
            fun hasPermission(name: String): Boolean {
                val nodes = manifest.getElementsByTagName("uses-permission")
                for (i in 0 until nodes.length) {
                    val el = nodes.item(i) as Element
                    if (el.getAttribute("android:name") == name) return true
                }
                return false
            }
            if (!hasPermission("android.permission.VIBRATE")) {
                val el = dom.createElement("uses-permission")
                el.setAttribute("android:name", "android.permission.VIBRATE")
                manifest.appendChild(el)
            }

            // Register the transparent picker activity (no-op if already present).
            val app = dom.getNode("application") as Element
            val existing = app.getElementsByTagName("activity")
            for (i in 0 until existing.length) {
                val node = existing.item(i) as Element
                if (node.getAttribute("android:name") == ACTIVITY_CLASS) return@use
            }
            val activity = dom.createElement("activity").apply {
                setAttribute("android:name", ACTIVITY_CLASS)
                setAttribute("android:exported", "false")
                setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                setAttribute("android:configChanges", "orientation|screenSize|keyboardHidden")
            }
            app.appendChild(activity)
        }
    }
}
