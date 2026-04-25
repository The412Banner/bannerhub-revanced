package app.revanced.patches.gamehub.misc.launcher

import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.asSequence
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

private val externalLauncherResourcePatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            dom
                .getElementsByTagName("activity")
                .asSequence()
                .map { it as Element }
                .filter { it.getAttribute("android:name").contains("GameDetailActivity") }
                .forEach { activity ->
                    activity.setAttribute("android:exported", "true")

                    // gamehub.lite.LAUNCH_GAME — compatible with ES-DE's built-in GAMEHUB-LITE emulator entry.
                    dom
                        .createElement("intent-filter")
                        .apply {
                            dom
                                .createElement("action")
                                .apply {
                                    setAttribute("android:name", "gamehub.lite.LAUNCH_GAME")
                                }.let(this::appendChild)
                            dom
                                .createElement("category")
                                .apply {
                                    setAttribute("android:name", "android.intent.category.DEFAULT")
                                }.let(this::appendChild)
                        }.let(activity::appendChild)
                }
        }
    }
}

@Suppress("unused")
val externalLauncherPatch = bytecodePatch(
    name = "External launcher support",
    description = "Enables launching games from external frontends like ES-DE.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(externalLauncherResourcePatch, creditsPatch)

    apply {
        // External launchers send an intent with steamAppId/localGameId/autoStartGame extras
        // but no "type" field. The original default for "type" is "" which causes the game
        // detail lookup to fail. GameHub Lite fixes this by defaulting to "0".
        firstMethod {
            definingClass == "Lcom/xj/landscape/launcher/ui/gamedetail/GameDetailActivity;" &&
                name == "initView"
        }.apply {
            val typeIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    (this as? ReferenceInstruction)?.reference?.toString() == "type"
            }

            // The next instruction is the default value: const-string vN, ""
            // Change it to "0" so the detail page can resolve the game.
            val defaultReg = getInstruction<OneRegisterInstruction>(typeIndex + 1).registerA
            replaceInstruction(typeIndex + 1, "const-string v$defaultReg, \"0\"")
        }

        addCredit("External launcher support", "PlayDay" to "https://github.com/playday3008")
    }
}
