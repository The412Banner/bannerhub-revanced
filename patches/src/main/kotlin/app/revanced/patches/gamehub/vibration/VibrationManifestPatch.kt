package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.vibration.BhVibrationSettingsActivity"

@Suppress("unused")
val vibrationManifestPatch = resourcePatch(
    name = "Vibration settings activity",
    description = "Registers BhVibrationSettingsActivity in the manifest so the " +
        "Mode/Intensity dialog can be launched by an explicit-Intent from anywhere. " +
        "Internal-only (android:exported=\"false\"); no <intent-filter>.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            // Skip if a previous patch run already registered the activity.
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
