package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =========================================================================
// Launch plumbing for the per-game GPU spoof.
//
// The Wine env builder Lbg5;->a(Leco;Ljava/lang/String;Z)V (.locals 35 —
// the same class the vibration patch's Hook 4 anchors on) builds the env via
// repeated invoke-virtual to Lcom/winemu/core/utils/EnvVars;->a(
// Ljava/lang/String;Ljava/lang/Object;)V on a stable receiver register
// (v11 on 6.0.4 — used identically from the DXVK block at smali ~2472 right
// through ~3099).
//
// The app's ONLY DXVK_CONFIG_FILE write (verified: single occurrence,
// smali ~2472) lives inside a `:cond_15` block gated on the generated
// dxvk.conf body being non-empty (only when the max-device-memory advanced
// option is set). To make the spoof always win we must inject AFTER that
// conditional block, at a point that is unconditionally reached.
//
// Anchor: the `ZINK_DESCRIPTORS` env set. It sits right after `:cond_16`
// (the MANGOHUD if/else merge), which itself is after `:cond_15` (the DXVK
// merge) — i.e. on the main path, past both conditional blocks and past the
// sole DXVK_CONFIG_FILE write. Injecting our single invoke-static there
// guarantees: (a) it always runs, (b) our EnvVars#a("DXVK_CONFIG_FILE",..)
// in the Java helper overrides any value the app set earlier.
//
// Context + gameId are resolved Java-side via ActivityThread (as in
// BhVibrationController) so the smali stays a single zero-clobber
// invoke-static. Spoof Off → helper no-ops → stock env, zero regression.
// =========================================================================

private const val ENV_BUILDER = "Lbg5;"
private const val ENV_VARS    = "Lcom/winemu/core/utils/EnvVars;"
private const val SPOOF_CTL   = "Lcom/xj/winemu/gpuspoof/BhGpuSpoofController;"
private const val ANCHOR_STR  = "ZINK_DESCRIPTORS"

@Suppress("unused")
val gpuSpoofPatch = bytecodePatch(
    name = "GPU spoof DXVK plumbing",
    description = "Force-writes dxgi/d3d9/dxvk customVendorId/customDeviceId " +
        "into a per-game dxvk.conf and points DXVK_CONFIG_FILE at it, after " +
        "the Wine env builder's conditional DXVK block, so the spoof always " +
        "applies. No-ops when the game's spoof mode is Off.",
) {
    // Pinned to 6.0.4 (skipped on 6.0.7): the 6.0.7 base app ships a native
    // GPU-spoof feature, so BannerHub's redundant. Version-incompatible = skipped, not failed.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))

    dependsOn(sharedGamehubExtensionPatch, gpuSpoofManifestPatch)

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

        // Locate the unconditional `ZINK_DESCRIPTORS` const-string …
        val zinkIdx = insns.indexOfFirst { ins ->
            ins.opcode == Opcode.CONST_STRING &&
                (ins as? ReferenceInstruction)?.getReference<StringReference>()
                    ?.string == ANCHOR_STR
        }
        require(zinkIdx >= 0) {
            "GpuSpoofPatch: \"$ANCHOR_STR\" const-string not found in " +
                "$ENV_BUILDER->a — anchor invalid for this build"
        }

        // … then the next EnvVars#a(String,Object)V setter that consumes it.
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
            "GpuSpoofPatch: no EnvVars setter after \"$ANCHOR_STR\" in $ENV_BUILDER->a"
        }

        // The setter's object register is the EnvVars instance (v11 on 6.0.4):
        //   35c  invoke-virtual {vEnv, vKey, vVal} -> registerC
        //   3rc  invoke-virtual/range {vEnv..vVal} -> startRegister
        val anchor = insns[setterIdx]
        val envReg = when (anchor) {
            is FiveRegisterInstruction -> anchor.registerC
            is RegisterRangeInstruction -> anchor.startRegister
            else -> error("GpuSpoofPatch: unexpected instruction format at anchor")
        }

        val call = if (envReg <= 15) {
            "invoke-static {v$envReg}, $SPOOF_CTL->applyGpuSpoof(Ljava/lang/Object;)V"
        } else {
            "invoke-static/range {v$envReg .. v$envReg}, " +
                "$SPOOF_CTL->applyGpuSpoof(Ljava/lang/Object;)V"
        }

        // Inject immediately AFTER the ZINK_DESCRIPTORS setter.
        envMethod.addInstructions(setterIdx + 1, call)
    }
}
