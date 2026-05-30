package app.revanced.patches.gamehub.misc.exportcontrols

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

private const val ACTIVITY_CLASS =
    "com.xj.winemu.exportcontrols.BhSafProxyActivity"

@Suppress("unused")
val exportControlsManifestPatch = resourcePatch(
    name = "VJoy export/import SAF proxy activity",
    description = "Registers BhSafProxyActivity in the manifest so the VJoy " +
        "share/apply bytecode hooks can launch ACTION_CREATE_DOCUMENT / " +
        "ACTION_OPEN_DOCUMENT from anywhere in the app. Translucent, " +
        "internal-only (android:exported=\"false\"), no <intent-filter>.",
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
                setAttribute(
                    "android:configChanges",
                    "orientation|screenSize|keyboardHidden",
                )
                // android:multiprocess so the activity launches in the
                // caller's process. GameHub runs WineActivity (the in-game
                // emulator) in a SEPARATE process from the main UI; when
                // an export/import is triggered from inside a game, the
                // hook fires in `:wine` while a single-process Activity
                // would launch in the main process. The IMPORT path relies
                // on a CompletableFuture<byte[]> bridging from the SAF
                // result callback to the worker thread — that future
                // can't cross process boundaries. Setting multiprocess=
                // true keeps caller and Activity in the same process so
                // the future works. The EXPORT path already passes bytes
                // via Intent extras, so it's process-agnostic regardless.
                setAttribute("android:multiprocess", "true")
            }
            app.appendChild(activity)
        }
    }
}
