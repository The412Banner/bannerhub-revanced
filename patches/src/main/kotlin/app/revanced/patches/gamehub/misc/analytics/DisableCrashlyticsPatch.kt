package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// =============================================================================
// Disable Firebase Crashlytics (+ related Firebase data collection) via manifest
// meta-data — the 6.0.7-correct approach.
//
// History: the 5.x/6.0.4 patch stripped a FirebaseCrashlytics.getInstance() /
// setCrashlyticsCollectionEnabled() call out of the app class's onCreate (it
// threw an NPE after ReVanced's extension merge). 6.0.7 removed that call entirely
// (the app class BaseAndroidApp→AndroidApp; getInstance/setCollectionEnabled = 0
// occurrences), so there is nothing to strip and the launch crash no longer
// happens. BUT the Crashlytics SDK still auto-initialises through its
// FirebaseInitProvider ContentProvider (confirmed on-device: "Initializing
// Firebase Crashlytics 20.0.3 … new session"), independent of any app call — so a
// bytecode strip can't stop it.
//
// The collection flags below are what actually gate whether Crashlytics (and the
// related Firebase data-collection surface) reports anything. 6.0.7's stock
// manifest already sets them to "false", and the app never calls
// setCrashlyticsCollectionEnabled(true) — so on a clean 6.0.7 the SDK initialises
// but collects/uploads nothing (no upload endpoints were seen in the on-device
// capture). This patch ENFORCES that posture: it sets each flag to "false"
// (updating an existing <meta-data> in place, or adding it if absent), so the
// privacy guarantee holds even if an upstream build flips a default — and the
// patch is an active, version-agnostic resource patch rather than a no-op.
// =============================================================================

private val firebaseCollectionFlagsOff = listOf(
    "firebase_crashlytics_collection_enabled",
    "firebase_data_collection_default_enabled",
)

@Suppress("unused")
val disableCrashlyticsPatch = resourcePatch(
    name = "Disable Firebase Crashlytics",
    description = "Forces Firebase Crashlytics data collection off via manifest meta-data " +
        "(firebase_crashlytics_collection_enabled + firebase_data_collection_default_enabled = false). " +
        "6.0.7's Crashlytics SDK auto-initialises through its ContentProvider regardless of app code, so " +
        "the collection flags — not a bytecode strip — are what stop it reporting. Stock 6.0.7 already " +
        "sets these false and never re-enables them; this enforces it and future-proofs against a flip.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val application = dom.getNode("application") as Element
            val metas = application.getElementsByTagName("meta-data")

            firebaseCollectionFlagsOff.forEach { flag ->
                var updated = false
                for (i in 0 until metas.length) {
                    val node = metas.item(i) as Element
                    if (node.getAttribute("android:name") == flag) {
                        node.setAttribute("android:value", "false")
                        updated = true
                    }
                }
                if (!updated) {
                    val meta = dom.createElement("meta-data")
                    meta.setAttribute("android:name", flag)
                    meta.setAttribute("android:value", "false")
                    application.appendChild(meta)
                }
            }
        }
    }
}
