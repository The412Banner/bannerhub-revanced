package app.revanced.patches.gamehub.components

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// =========================================================================
// Component Manager (3.8.1 parity on v6) — registers the three Java activities
// behind the Banner Tools "Components" tile:
//   ComponentManagerActivity  — list / remove injected components, Inject entry
//   ComponentPickerActivity   — in-app browser over device storage (.tzst + folders)
//   ComponentDownloadActivity — online repos (WCPHub, driver mirrors, Nightlies)
//                               → download → inject through the same injector
// All are internal (exported=false): reached only from the Banner Tools
// dialog (BhBannerToolsMenuRowClick → BhComponentsMenu.open) and from each
// other (startActivityForResult). Same theme / configChanges / `behind`
// orientation as the GOG activities — see GogManifestPatch for the §34
// reasoning (same task as MainActivity, so `behind` follows GameHub's
// handheld/explore mode).
//
// The runtime side writes only prefs (sp_bh_injected_components +
// bh_component_manager + bh_component_downloads) and files under filesDir /
// externalFilesDir; no permission is needed beyond the host's existing
// INTERNET and MANAGE_EXTERNAL_STORAGE (the latter for the picker only).
// =========================================================================

private const val PKG = "app.revanced.extension.gamehub.components"

private val ACTIVITIES = listOf(
    "$PKG.ComponentManagerActivity",
    "$PKG.ComponentPickerActivity",
    "$PKG.ComponentDownloadActivity",
)

@Suppress("unused")
val componentManagerManifestPatch = resourcePatch(
    name = "Component Manager activities",
    description = "Registers the Component Manager (inject / list / remove GPU driver, " +
        "DXVK, VKD3D, translator and library components for the PC engine), its " +
        "storage picker and the online-repo download screen. Opened from the Banner Tools dialog.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            // Collect already-registered activity names (idempotent re-runs).
            val existing = HashSet<String>()
            val nodes = app.getElementsByTagName("activity")
            for (i in 0 until nodes.length) {
                existing.add((nodes.item(i) as Element).getAttribute("android:name"))
            }

            for (name in ACTIVITIES) {
                if (name in existing) continue
                val activity = dom.createElement("activity").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:exported", "false")
                    setAttribute("android:theme", "@android:style/Theme.Black.NoTitleBar")
                    setAttribute(
                        "android:configChanges",
                        "orientation|screenSize|keyboardHidden",
                    )
                    setAttribute("android:screenOrientation", "behind")
                }
                app.appendChild(activity)
            }
        }
    }
}
