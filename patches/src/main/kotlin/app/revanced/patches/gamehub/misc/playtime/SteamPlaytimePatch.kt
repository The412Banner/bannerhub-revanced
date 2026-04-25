package app.revanced.patches.gamehub.misc.playtime

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.instructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.EXTENSION_PLAYTIME_HELPER
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.credits.addCredit
import app.revanced.patches.gamehub.misc.credits.creditsPatch
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

@Suppress("unused")
val steamPlaytimePatch = bytecodePatch(
    name = "Steam playtime",
    description = "Reads game playtime from the local Steam database instead of GameHub's server.",
) {
    dependsOn(sharedGamehubExtensionPatch, creditsPatch)
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod("heartbeat/game/getUserPlayTimeList").apply {
            // Find the const-string that starts the API call.
            val apiStringIndex = instructions.indexOfFirst {
                it.opcode == Opcode.CONST_STRING &&
                    (it as ReferenceInstruction).reference.toString() ==
                    "heartbeat/game/getUserPlayTimeList"
            }

            // Inject before the API call. If the local DB returns non-null, return it
            // directly; otherwise fall through to the original HTTP path.
            // p1 is safe to reuse here — it was consumed by ResultKt.b() and is about
            // to be overwritten by the const-string we're jumping over on fallback.
            addInstructionsWithLabels(
                apiStringIndex,
                """
                    invoke-static {}, $EXTENSION_PLAYTIME_HELPER->fetchPlaytimeFromLocalDb()Ljava/lang/Object;
                    move-result-object p1
                    if-eqz p1, :original
                    check-cast p1, Ljava/util/List;
                    return-object p1
                """,
                ExternalLabel("original", getInstruction(apiStringIndex)),
            )
        }

        addCredit("Steam playtime", "PlayDay" to "https://github.com/playday3008")
    }
}
