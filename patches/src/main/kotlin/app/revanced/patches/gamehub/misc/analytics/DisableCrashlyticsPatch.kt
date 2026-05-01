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
    description = "Removes Firebase Crashlytics initialization from Application.onCreate to fix crash on launch.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // BaseAndroidApp.onCreate() calls FirebaseCrashlytics.getInstance() which throws NPE
        // because the Crashlytics component registrar is stripped by ReVanced's extension merge.
        // Remove the 3-instruction block: invoke-static getInstance / move-result-object / invoke-virtual setCrashlyticsCollectionEnabled
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
            for (i in setCrashlyticsIdx downTo getInstanceIdx) {
                removeInstruction(i)
            }
        }
    }
}
