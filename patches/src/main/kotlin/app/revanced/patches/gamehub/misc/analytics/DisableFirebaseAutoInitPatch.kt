package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// "Plan 11" — neutralize the app's runtime Firebase-collection re-enable.
//
// Surprise from the 6.0.8 decompile: `com.xiaoji.egggame.AndroidApp` has a
// Firebase-setup helper (the `a()V` method, called from onCreate) that, at
// startup, RE-ENABLES Firebase data collection and Crashlytics collection at
// runtime — it writes:
//     firebase_data_collection_default_enabled   = true
//     firebase_crashlytics_collection_enabled    = true
// straight into the SDK's SharedPreferences (o14 / n14 DataCollectionArbiter).
//
// Runtime setCrashlyticsCollectionEnabled(true) OVERRIDES our manifest
// <meta-data> "...collection_enabled=false" kill switch — which is why, despite
// DisableCrashlyticsPatch, the device traces still showed live connections to
// firebase-settings.crashlytics.com and firebaselogging-pa.googleapis.com (the
// datatransport/Firelog transport), and firebaseinstallations.googleapis.com
// (FID, fetched once Crashlytics is active). Firebase Analytics itself stays dead
// because `firebase_analytics_collection_deactivated` is a hard manifest-only
// flag the app can't override at runtime — hence no app-measurement.com traffic.
//
// The same method also does `requireNotNull(FirebaseApp.get(Crashlytics))`
// (`if-eqz ... -> Lq7l;->r("FirebaseCrashlytics component is not present.")`),
// so an earlier approach that disabled ComponentDiscoveryService crashed here on
// launch.
//
// Fix: stub the helper to `return-void`. With it gone, the runtime re-enable
// never runs, so the manifest `false` defaults finally take effect — Crashlytics
// collection stays off, so no settings fetch, no Firelog, and Installations has
// no active Firebase service to request a FID for. No component is removed, so
// nothing crashes. The whole method is Firebase-only setup (td6 = FirebaseApp,
// o14 = DataCollectionConfigStorage, n14 = Crashlytics arbiter, ae6 = Crashlytics
// component), so stubbing it has no non-Firebase side effects.
//
// Anchored structurally on the app class + the stable, never-obfuscated string
// "FirebaseCrashlytics component is not present." so it survives R8 reshuffles.
// =============================================================================

private const val ANCHOR_STRING = "FirebaseCrashlytics component is not present."

@Suppress("unused")
val disableFirebaseAutoInitPatch = bytecodePatch(
    name = "Disable Firebase auto-init",
    description = "Stubs the AndroidApp Firebase-setup helper that re-enables Firebase/Crashlytics " +
        "data collection at runtime (writing firebase_data_collection_default_enabled=true and " +
        "firebase_crashlytics_collection_enabled=true, which silently overrode the manifest kill " +
        "switches). With it stubbed, the manifest 'false' defaults hold, so Crashlytics stays off " +
        "and the residual Firebase network calls (firebase-settings.crashlytics.com, " +
        "firebaselogging-pa.googleapis.com, firebaseinstallations.googleapis.com) no longer fire. " +
        "Anchored on the app class + a stable Firebase error string.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            definingClass == "Lcom/xiaoji/egggame/AndroidApp;" &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == ANCHOR_STRING } == true
                } == true
        }.addInstructions(0, "return-void")
    }
}
