package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.gpuspoof.BhGpuSpoofSettingsActivity"

@Suppress("unused")
val gpuSpoofManifestPatch = resourcePatch(
    name = "GPU spoof settings activity",
    description = "Registers BhGpuSpoofSettingsActivity in the manifest so the " +
        "per-game GPU-identity dialog can be launched by explicit-Intent. " +
        "Internal-only (android:exported=\"false\"); no <intent-filter>.",
) {
    // Pinned to 6.0.4 (skipped on 6.0.7): the 6.0.7 base app ships a native
    // GPU-spoof feature, so BannerHub's redundant. Version-incompatible = skipped, not failed.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))

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
