package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// "Plan 11" — stop the app re-enabling Firebase/Crashlytics collection at runtime.
//
// `com.xiaoji.egggame.AndroidApp` has a Firebase-setup helper (the `a()V` method,
// called from onCreate) that does TWO things, in order:
//
//   1. INITIALIZES FirebaseApp:
//        td6.h(context)  = FirebaseApp.initializeApp(context)
//        td6.d()         = FirebaseApp.getInstance()
//        td6.a()
//      Other app code (a background coroutine: hgf.a -> zo.j) later calls
//      FirebaseApp.getInstance(), so this init MUST stay — without it the app
//      crashes with "Default FirebaseApp is not initialized".
//
//   2. RE-ENABLES collection (the part we want gone): under a monitor it writes
//        firebase_data_collection_default_enabled = true   (o14 / DataCollectionConfigStorage)
//        firebase_crashlytics_collection_enabled  = true   (n14 / Crashlytics arbiter)
//      via SharedPreferences. A runtime setCrashlyticsCollectionEnabled(true)
//      OVERRIDES our manifest "...collection_enabled=false" kill switch — which is
//      why device traces still showed firebase-settings.crashlytics.com,
//      firebaselogging-pa.googleapis.com (datatransport/Firelog) and
//      firebaseinstallations.googleapis.com despite DisableCrashlyticsPatch.
//      (Firebase Analytics stayed dead the whole time because
//      firebase_analytics_collection_deactivated is a hard manifest flag the app
//      can't override — hence no app-measurement.com traffic.)
//
// Fix: insert `return-void` immediately BEFORE the first `monitor-enter` — i.e.
// after step 1 (FirebaseApp is initialized) and before step 2 (the collection
// re-enable, which lives inside that monitor). FirebaseApp init still runs (no
// "not initialized" crash; the later getInstance works), the runtime re-enable
// never executes (so the manifest `false` defaults finally hold -> Crashlytics
// stays off -> no settings fetch, no Firelog, no FID request), and the
// `requireNotNull(FirebaseApp.get(Crashlytics))` further down (which threw
// "FirebaseCrashlytics component is not present.") is skipped too.
//
// Inserting before the monitor-enter (not inside it) keeps the lock balanced.
// Method anchored on the app class + the stable, never-obfuscated string
// "FirebaseCrashlytics component is not present." so it survives R8 reshuffles;
// the cut point is the first MONITOR_ENTER, which is structural (not name-based).
// =============================================================================

private const val ANCHOR_STRING = "FirebaseCrashlytics component is not present."

@Suppress("unused")
val disableFirebaseAutoInitPatch = bytecodePatch(
    name = "Disable Firebase auto-init",
    description = "Stops the AndroidApp Firebase-setup helper from re-enabling Firebase/Crashlytics " +
        "data collection at runtime (it wrote firebase_data_collection_default_enabled=true and " +
        "firebase_crashlytics_collection_enabled=true, silently overriding the manifest kill " +
        "switches). Returns right after FirebaseApp init but before the collection re-enable, so " +
        "FirebaseApp still initializes (other code needs it) while Crashlytics stays off — removing " +
        "the residual firebase-settings.crashlytics.com / firebaselogging-pa.googleapis.com / " +
        "firebaseinstallations.googleapis.com traffic. Anchored on the app class + a stable Firebase " +
        "error string; cut at the first monitor-enter.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            definingClass == "Lcom/xiaoji/egggame/AndroidApp;" &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == ANCHOR_STRING } == true
                } == true
        }.apply {
            val cut = indexOfFirstInstructionOrThrow { opcode == Opcode.MONITOR_ENTER }
            addInstructions(cut, "return-void")
        }
    }
}
