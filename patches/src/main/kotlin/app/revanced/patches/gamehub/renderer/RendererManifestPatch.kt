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
    // GATED OUT of 6.0.7: pinned to 6.0.4 so the patcher SKIPS it (version-
    // incompatible, not a SEVERE failure). The Legacy GLES2 path swaps in the
    // 6.0.2 libxserver, whose JNI_OnLoad RegisterNatives needs XServer methods
    // 6.0.7 deleted (setSurfaceFormat/setFlipEnabled) -> SIGABRT at <clinit>
    // (device-confirmed on DOOMBLADE, 2026-06-06). 6.0.7 grew XServer 11->40
    // natives (ReShade FX engine), so the old .so cannot satisfy the contract;
    // not patchable without a source-built GLES2 libxserver. New mode = stock,
    // unaffected. Revive only with a 6.0.7-contract GLES2 libxserver.
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
