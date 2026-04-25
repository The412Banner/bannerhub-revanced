package app.revanced.patches.gamehub.steam.connection

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethodOrNull
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal val forceSteamTcpPatch = bytecodePatch(
    name = "Force Steam TCP",
    description =
        "Forces Steam connections to use TCP instead of WebSocket " +
            "to avoid TLS issues with custom network security configs.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(creditsPatch)

    apply {
        // No-op when JavaSteam classes aren't present (safe for non-GameHub apps).
        val addCoreMethod = firstMethodOrNull {
            definingClass == "Lin/dragonbra/javasteam/steam/discovery/SmartCMServerList;" &&
                name == "addCore"
        } ?: return@apply

        addCoreMethod.apply {
            // Find: check-cast v1, Lin/dragonbra/javasteam/networking/steam3/ProtocolTypes;
            val checkCastIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CHECK_CAST &&
                    getReference<TypeReference>()?.type ==
                    "Lin/dragonbra/javasteam/networking/steam3/ProtocolTypes;"
            }

            // The goto at end of loop body jumps back to :goto_0 (iterator.hasNext check).
            val gotoIndex = indexOfFirstInstructionOrThrow(checkCastIndex) {
                opcode == Opcode.GOTO
            }

            // After check-cast v1, ProtocolTypes: skip WEB_SOCKET entries.
            // v2 is available (.locals 3) and isn't read until new-instance below.
            addInstructionsWithLabels(
                checkCastIndex + 1,
                """
                    sget-object v2, Lin/dragonbra/javasteam/networking/steam3/ProtocolTypes;->WEB_SOCKET:Lin/dragonbra/javasteam/networking/steam3/ProtocolTypes;
                    if-eq v1, v2, :skip_websocket
                """,
                ExternalLabel("skip_websocket", getInstruction(gotoIndex)),
            )
        }

        addCredit("Force Steam TCP", "PlayDay" to "https://github.com/playday3008")
    }
}
