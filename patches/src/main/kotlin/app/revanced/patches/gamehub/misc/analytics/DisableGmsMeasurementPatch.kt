package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// Google Play Services Measurement runs independently of Firebase Analytics.
// Plan 4's firebase_analytics_collection_deactivated meta-data does NOT stop
// these components — they're driven by GMS's own service registration and
// continue writing app_instance_id + session_id into
// /data/data/<pkg>/shared_prefs/com.google.android.gms.measurement.prefs.xml
// (verified live on banner.hub Path 2 build, 2026-05-13).
//
// ContentProvider-style auto-init isn't the mechanism here — these are bound
// services + a broadcast receiver — so a pure manifest disable is sufficient.
// No bytecode layer needed; GMS itself respects android:enabled="false" on
// its own AppMeasurementService class when other GMS code queries its
// registration via PackageManager.
private val gmsMeasurementComponents = listOf(
    "com.google.android.gms.measurement.AppMeasurementReceiver" to "receiver",
    "com.google.android.gms.measurement.AppMeasurementService" to "service",
    "com.google.android.gms.measurement.AppMeasurementJobService" to "service",
)

@Suppress("unused")
val disableGmsMeasurementPatch = resourcePatch(
    name = "Disable GMS Measurement",
    description = "Disables the three Google Play Services Measurement manifest " +
        "components (AppMeasurementReceiver, AppMeasurementService, " +
        "AppMeasurementJobService) by flipping android:enabled to false. " +
        "Complementary to 'Disable Firebase Analytics' — that patch stops the " +
        "bundled Firebase SDK from initialising, but GMS Measurement runs as a " +
        "separate Play Services component and is unaffected by Firebase's manifest " +
        "kill switch. With both patches applied, no app_instance_id / session_id is " +
        "written to GMS-side shared_prefs and the Play Services-backed analytics " +
        "pipeline stays dormant.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element

            gmsMeasurementComponents.forEach { (fqcn, tag) ->
                val nodes = app.getElementsByTagName(tag)
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    if (node.getAttribute("android:name") == fqcn) {
                        node.setAttribute("android:enabled", "false")
                    }
                }
            }
        }
    }
}
