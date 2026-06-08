package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.renderer.BhRendererSettingsActivity"

@Suppress("unused")
val rendererManifestPatch = resourcePatch(
    name = "Renderer settings activity",
    description = "Registers BhRendererSettingsActivity in the manifest so the " +
        "per-game renderer dialog can be launched by explicit-Intent. " +
        "Internal-only (android:exported=\"false\"); no <intent-filter>.",
) {
    // 6.0.8: re-enabled alongside the wrapper-shim legacy renderer.
    compatibleWith(GAMEHUB_PACKAGE("6.0.8"))

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
