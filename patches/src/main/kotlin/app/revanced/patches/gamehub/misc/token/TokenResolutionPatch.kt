package app.revanced.patches.gamehub.misc.token

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.replaceInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.EXTENSION_TOKEN_PROVIDER
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val TOKEN_PROVIDER_CLASS = EXTENSION_TOKEN_PROVIDER

/**
 * Internal patch that hooks `UserManager.getToken()` to pass the original return value
 * through `TokenProvider.resolveToken()`. This lets the decision tree in the extension
 * choose the right token based on which patches are active and which API is selected.
 *
 * Both [bypassLoginPatch][app.revanced.patches.gamehub.misc.login.bypassLoginPatch] and
 * [apiServerSwitchPatch][app.revanced.patches.gamehub.network.apiServerSwitchPatch]
 * depend on this patch and inject their respective flags into `TokenProvider.<clinit>`.
 * When neither patch is included, this patch is never pulled in and `getToken()` stays unpatched.
 */
internal val tokenResolutionPatch = bytecodePatch {
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // Patch every return-object in UserManager.getToken() to pass the value
        // through TokenProvider.resolveToken(String) before returning.
        firstMethod {
            definingClass == "Lcom/xj/common/user/UserManager;" && name == "getToken"
        }.apply {
            val instructions = implementation!!.instructions

            // Collect return-object indices in descending order so insertions don't shift
            // earlier indices.
            val returnIndices = instructions
                .mapIndexedNotNull { index, instruction ->
                    if (instruction.opcode == Opcode.RETURN_OBJECT) index else null
                }.sortedDescending()

            for (returnIndex in returnIndices) {
                val returnReg = getInstruction<OneRegisterInstruction>(returnIndex).registerA

                // Replace the return-object with the extension call so any label on it
                // moves to the invoke-static (label-safe insertion).
                replaceInstruction(
                    returnIndex,
                    "invoke-static {v$returnReg}, $TOKEN_PROVIDER_CLASS->resolveToken(Ljava/lang/String;)Ljava/lang/String;",
                )
                addInstructions(
                    returnIndex + 1,
                    """
                        move-result-object v$returnReg
                        return-object v$returnReg
                    """,
                )
            }
        }
    }
}
