package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val disableCrashlyticsPatch = bytecodePatch(
    name = "Disable Firebase Crashlytics",
    description = "Skips Firebase Crashlytics initialization in Application.onCreate to fix crash on launch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // BaseAndroidApp.onCreate() calls FirebaseCrashlytics.getInstance() which throws NPE
        // because the Crashlytics component registrar is stripped by ReVanced's extension merge.
        // Remove getInstance, move-result-object, and setCrashlyticsCollectionEnabled in reverse
        // index order so that the const/4 between them stays in place — it redefines v2 from
        // String to Boolean, and code after this block relies on that typing.
        firstMethod {
            definingClass == "Lcom/xiaoji/egggame/BaseAndroidApp;" && name == "onCreate"
        }.apply {
            val getInstanceIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC &&
                    (this as ReferenceInstruction).reference.toString()
                        .contains("FirebaseCrashlytics;->getInstance")
            }
            val setCrashlyticsIdx = indexOfFirstInstructionOrThrow(getInstanceIdx) {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as ReferenceInstruction).reference.toString()
                        .contains("setCrashlyticsCollectionEnabled")
            }
            // Remove in reverse order so earlier indices remain valid.
            removeInstruction(setCrashlyticsIdx)
            removeInstruction(getInstanceIdx + 1) // move-result-object
            removeInstruction(getInstanceIdx)     // invoke-static getInstance
        }
    }
}
