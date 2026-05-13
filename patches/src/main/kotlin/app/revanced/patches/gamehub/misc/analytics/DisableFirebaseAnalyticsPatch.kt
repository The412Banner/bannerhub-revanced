package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

private data class MetaData(val name: String, val value: String)

private val flags = listOf(
    // Firebase's strongest documented kill switch. Stops Analytics SDK init entirely;
    // no auto-collected events (session_start, screen_view, first_open, app_update,
    // in_app_purchase, etc.) and no custom events are sent to app-measurement.com.
    MetaData("firebase_analytics_collection_deactivated", "true"),
    // Belt-and-braces: even if the flag above is ignored or a future Firebase update
    // changes its semantics, also force-disable Google Ads ID collection.
    MetaData("google_analytics_adid_collection_enabled", "false"),
    // Disable Analytics SSAID (Settings.Secure.ANDROID_ID) collection too.
    MetaData("google_analytics_ssaid_collection_enabled", "false"),
)

@Suppress("unused")
val disableFirebaseAnalyticsPatch = resourcePatch(
    name = "Disable Firebase Analytics",
    description = "Adds Firebase's manifest kill-switch <meta-data> entries so the bundled " +
        "Firebase Analytics SDK never initializes data collection. Stops all auto-collected " +
        "and custom events from being sent to app-measurement.com. Firebase Cloud Messaging, " +
        "Firebase Auth, and Firebase Remote Config are unaffected (GameHub 6.0.4 bundles " +
        "Remote Config but never invokes it — verified zero call sites in the host APK).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            flags.forEach { (name, value) ->
                // Skip if an earlier patch run (or upstream) already declared this key —
                // identity is the meta-data name; first declaration wins at install time.
                val existing = app.getElementsByTagName("meta-data")
                var alreadyPresent = false
                for (i in 0 until existing.length) {
                    val node = existing.item(i) as Element
                    if (node.getAttribute("android:name") == name) {
                        alreadyPresent = true
                        break
                    }
                }
                if (alreadyPresent) return@forEach

                val md = dom.createElement("meta-data").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:value", value)
                }
                app.appendChild(md)
            }
        }
    }
}
