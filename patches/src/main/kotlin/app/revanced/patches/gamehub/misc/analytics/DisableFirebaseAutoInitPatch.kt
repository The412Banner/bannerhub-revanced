package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getNode
import org.w3c.dom.Element

// =============================================================================
// "Plan 11" — stop the residual Firebase network housekeeping.
//
// The collection kill-switches (Disable Firebase Analytics / Crashlytics) stop
// the *payloads*, but on 6.0.8 the Firebase SDK still opens connections to
// Google at process start:
//   - firebaseinstallations.googleapis.com   (Installations / FID registration)
//   - firebase-settings.crashlytics.com       (Crashlytics settings fetch)
//   - firebaselogging-pa.googleapis.com        (datatransport "Firelog" transport)
// all device-confirmed in the 6.0.8 SNI trace.
//
// 6.0.8 does NOT ship the classic `com.google.firebase.provider.FirebaseInitProvider`
// (verified: 0 occurrences in the base manifest). Firebase instead discovers and
// registers its components through the
//   `com.google.firebase.components.ComponentDiscoveryService`
// <service>, whose <meta-data> list every Registrar (Installations, Crashlytics,
// Sessions, datatransport/Transport, Analytics-connector, Messaging, Auth, Common).
//
// FirebaseApp.initializeApp() discovers these via
// PackageManager.getServiceInfo(component, GET_META_DATA). With the service set to
// android:enabled="false", getServiceInfo throws NameNotFoundException (the default
// query does not match disabled components), so ComponentDiscovery returns an EMPTY
// registrar set — none of the telemetry components register, so none of their
// startup network calls fire. This is the same proven technique as
// DisableGmsMeasurementPatch (flip enabled=false; PackageManager treats it as
// not-present), and starving the whole set avoids the dependency-graph init crash
// that selectively removing individual Registrar <meta-data> would risk.
//
// Safe here because: Analytics/Crashlytics collection is already off; FCM push is
// served by the (disabled) Mob stack, not standalone Firebase Messaging; login is
// bypassed so Firebase Auth is unused; and the host app has no direct
// FirebaseCrashlytics/FirebaseApp call sites to NPE on a missing component
// (verified 0 on the 6.0.7/6.0.8 line). Device-test launch after applying.
// =============================================================================

private const val COMPONENT_DISCOVERY =
    "com.google.firebase.components.ComponentDiscoveryService"

@Suppress("unused")
val disableFirebaseAutoInitPatch = resourcePatch(
    name = "Disable Firebase auto-init",
    description = "Disables Firebase's ComponentDiscoveryService so the SDK registers no " +
        "components at startup — stopping the residual Firebase network calls that the " +
        "collection kill-switches leave in place (firebaseinstallations.googleapis.com, " +
        "firebase-settings.crashlytics.com, firebaselogging-pa.googleapis.com / datatransport). " +
        "Complements 'Disable Firebase Analytics' + 'Disable Firebase Crashlytics': those stop " +
        "the payloads, this stops the connections. No standalone Firebase feature is used by the " +
        "app (push is via the disabled Mob stack; login is bypassed).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element
            val services = app.getElementsByTagName("service")
            for (i in 0 until services.length) {
                val node = services.item(i) as Element
                if (node.getAttribute("android:name") == COMPONENT_DISCOVERY) {
                    node.setAttribute("android:enabled", "false")
                }
            }
        }
    }
}
