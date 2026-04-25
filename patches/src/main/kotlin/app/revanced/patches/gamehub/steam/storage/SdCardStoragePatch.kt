package app.revanced.patches.gamehub.steam.storage

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.CONTENT_TYPE_SD_CARD_STORAGE
import app.revanced.patches.gamehub.EXTENSION_PREFS
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.patches.gamehub.misc.settings.addSteamSetting
import app.revanced.patches.gamehub.misc.settings.settingsMenuPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STEAM_EXTENSION = EXTENSION_PREFS

@Suppress("unused")
val sdCardStoragePatch = bytecodePatch(
    name = "SD card Steam storage",
    description = "Allows redirecting Steam game storage to a custom location such as an SD card.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(sharedGamehubExtensionPatch, settingsMenuPatch, creditsPatch)

    apply {
        addSteamSetting(CONTENT_TYPE_SD_CARD_STORAGE, "CONTENT_TYPE_SD_CARD_STORAGE")

        // Intercept AppMetadata.setInstallPath setter to redirect the path.
        firstMethod {
            definingClass == "Lcom/xj/standalone/steam/data/bean/AppMetadata;" &&
                name == "setInstallPath"
        }.apply {
            val iputIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.IPUT_OBJECT }
            addInstructions(
                iputIndex,
                """
                    invoke-static {p1}, $STEAM_EXTENSION->getEffectiveStoragePath(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
        }

        // Intercept SteamDownloadExtend.setInstallDirPath setter to redirect the path.
        firstMethod {
            definingClass == "Lcom/xj/standalone/steam/data/bean/SteamDownloadExtend;" &&
                name == "setInstallDirPath"
        }.apply {
            val iputIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.IPUT_OBJECT }
            addInstructions(
                iputIndex,
                """
                    invoke-static {p1}, $STEAM_EXTENSION->getEffectiveStoragePath(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """,
            )
        }

        // DownloadGameSizeInfoDialog$computeAvailableSize$2.invokeSuspend returns Object (boxed Long).
        // Override to return available bytes on the effective storage location (SD card or internal).
        firstMethod {
            definingClass ==
                $$"Lcom/xj/landscape/launcher/ui/dialog/DownloadGameSizeInfoDialog$computeAvailableSize$2;" &&
                implementation?.instructions?.any { instr ->
                    instr.getReference<MethodReference>()?.name == "getExternalStorageDirectory"
                } == true
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {}, $STEAM_EXTENSION->getAvailableStorage()J
                    move-result-wide v0
                    invoke-static {v0, v1}, Lkotlin/coroutines/jvm/internal/Boxing;->f(J)Ljava/lang/Long;
                    move-result-object v0
                    return-object v0
                """,
            )
        }

        // SteamDownloadInfoHelper.a() calls AppMetadata.setInstallPath(path) when the current
        // install path is empty. We intercept just before that call to translate the path through
        // our extension, which may redirect it to the user-configured custom storage path.
        firstMethod {
            definingClass == "Lcom/xj/standalone/steam/core/SteamDownloadInfoHelper;" &&
                name == "a" &&
                implementation?.instructions?.any { instruction ->
                    (instruction as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "setInstallPath"
                    } == true
                } == true
        }.apply {
            val setInstallPathIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_VIRTUAL &&
                    (this as? ReferenceInstruction)?.reference?.let {
                        it is MethodReference && it.name == "setInstallPath"
                    } == true
            }
            // invoke-virtual {v6, v1}, AppMetadata->setInstallPath(String)V
            // registerD is the second argument (the path string).
            val pathRegister = getInstruction<Instruction35c>(setInstallPathIndex).registerD

            addInstructions(
                setInstallPathIndex,
                """
                    invoke-static {v$pathRegister}, $STEAM_EXTENSION->getEffectiveStoragePath(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$pathRegister
                """,
            )
        }

        addCredit("SD card Steam storage", "Producdevity" to "https://github.com/Producdevity/gamehub-lite")
    }
}
