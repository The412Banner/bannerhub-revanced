package app.revanced.patches.gamehub.audio

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.audio.BhAudioSettingsActivity"

@Suppress("unused")
val audioManifestPatch = resourcePatch(
    name = "Recording-compatible audio settings activity",
    description = "Registers BhAudioSettingsActivity in the manifest so the " +
        "Banner Tools \"Audio\" tile can launch it by explicit-Intent. " +
        "Internal-only (android:exported=\"false\"); no <intent-filter>.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
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
