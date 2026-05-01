package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Instruction
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
        // Insert a goto to skip over the getInstance+setCrashlyticsCollectionEnabled block.
        // We use goto (not removeInstruction) to preserve the original type flow — removing
        // instructions shifts the const/4 that lives between them, breaking register v2's type.
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
            // Capture the instruction AFTER setCrashlyticsCollectionEnabled before inserting.
            val afterCrashlytics = getInstruction<Instruction>(setCrashlyticsIdx + 1)
            addInstructionsWithLabels(
                getInstanceIdx,
                "goto :after_crashlytics",
                ExternalLabel("after_crashlytics", afterCrashlytics),
            )
        }
    }
}
