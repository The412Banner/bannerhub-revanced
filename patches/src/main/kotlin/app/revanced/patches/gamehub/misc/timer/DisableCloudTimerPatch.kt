package app.revanced.patches.gamehub.misc.timer

import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val disableCloudTimerPatch = bytecodePatch(
    name = "Disable cloud gaming timer",
    description = "Removes cloud gaming timer checks that cause crashes.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(creditsPatch)

    apply {
        // HomeInfoRepository.b(Function1) — obfuscated name for checkUserTimer.
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/data/repository/HomeInfoRepository;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("HomeInfoRepository\$checkUserTimer")
                } == true
        }.returnEarly()

        // CloudGameInfoRepository.checkUserTimer(Function1).
        firstMethod {
            definingClass == "Lcom/xj/cloud/data/repository/CloudGameInfoRepository;" &&
                implementation?.instructions?.any { instruction ->
                    instruction is ReferenceInstruction &&
                        instruction.reference.toString().contains("CloudGameInfoRepository\$checkUserTimer")
                } == true
        }.returnEarly()

        addCredit("Disable cloud gaming timer", "Producdevity" to "https://github.com/Producdevity/gamehub-lite")
    }
}
