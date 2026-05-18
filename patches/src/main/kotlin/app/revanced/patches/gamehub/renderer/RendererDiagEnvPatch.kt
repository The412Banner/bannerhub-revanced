package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =========================================================================
// THROWAWAY DIAGNOSTIC — Patch B of the M2 Legacy-renderer device probe.
// NOT FOR MERGE. Revert (this file + BhRendererDiag + its controller calls)
// before any real M2 ship. Effect is additionally gated by
// BhRendererDiag.DIAG and isLegacyForLaunchingGame, so it no-ops in New
// mode and for non-legacy games even if accidentally left in.
//
// Reuses GpuSpoofPatch's proven anchor verbatim: the Wine env builder
// Lbg5;->a(...)V, injecting one invoke-static after the unconditional
// ZINK_DESCRIPTORS EnvVars setter (main path, past every conditional block).
// BhRendererDiag.applyDiagEnv sets WINEDEBUG + FEX_SILENTLOG=0 +
// FEX_OUTPUTLOG via the same reflective EnvVars#a path, ONLY when Legacy is
// active for the launching game — so the next GoW run leaves a persisted
// Wine-loader + FEX trace that survives the ~40 s :wine death.
//
// Independent of gpuSpoofPatch: both re-scan the method fresh and stack
// their own invoke-static after the same setter; different helper targets,
// no register/index conflict.
// =========================================================================

private const val ENV_BUILDER = "Lbg5;"
private const val ENV_VARS    = "Lcom/winemu/core/utils/EnvVars;"
private const val DIAG        = "Lcom/xj/winemu/renderer/BhRendererDiag;"
private const val ANCHOR_STR  = "ZINK_DESCRIPTORS"

@Suppress("unused")
val rendererDiagEnvPatch = bytecodePatch(
    name = "Legacy renderer diagnostic env (THROWAWAY)",
    description = "Diagnostic-only: injects WINEDEBUG + FEX log env into the " +
        "Wine env builder so an M2 Legacy GoW launch leaves a persisted " +
        "loader trace. No-ops unless Legacy is active for the game.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(sharedGamehubExtensionPatch, rendererManifestPatch)

    apply {
        val envMethod = firstMethod {
            definingClass == ENV_BUILDER && name == "a" && returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        (ins as? ReferenceInstruction)?.getReference<StringReference>()
                            ?.string == ANCHOR_STR
                } ?: false)
        }

        val insns = envMethod.implementation!!.instructions.toList()

        val zinkIdx = insns.indexOfFirst { ins ->
            ins.opcode == Opcode.CONST_STRING &&
                (ins as? ReferenceInstruction)?.getReference<StringReference>()
                    ?.string == ANCHOR_STR
        }
        require(zinkIdx >= 0) {
            "RendererDiagEnvPatch: \"$ANCHOR_STR\" const-string not found in " +
                "$ENV_BUILDER->a — anchor invalid for this build"
        }

        val setterIdx = (zinkIdx until insns.size).firstOrNull { i ->
            val ins = insns[i]
            (ins.opcode == Opcode.INVOKE_VIRTUAL ||
                ins.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let {
                        it.definingClass == ENV_VARS && it.name == "a" &&
                            it.parameterTypes.toList() ==
                                listOf("Ljava/lang/String;", "Ljava/lang/Object;") &&
                            it.returnType == "V"
                    } == true
        }
        require(setterIdx != null) {
            "RendererDiagEnvPatch: no EnvVars setter after \"$ANCHOR_STR\" in " +
                "$ENV_BUILDER->a"
        }

        val anchor = insns[setterIdx]
        val envReg = when (anchor) {
            is FiveRegisterInstruction -> anchor.registerC
            is RegisterRangeInstruction -> anchor.startRegister
            else -> error("RendererDiagEnvPatch: unexpected instruction format")
        }

        val call = if (envReg <= 15) {
            "invoke-static {v$envReg}, $DIAG->applyDiagEnv(Ljava/lang/Object;)V"
        } else {
            "invoke-static/range {v$envReg .. v$envReg}, " +
                "$DIAG->applyDiagEnv(Ljava/lang/Object;)V"
        }

        envMethod.addInstructions(setterIdx + 1, call)
    }
}
