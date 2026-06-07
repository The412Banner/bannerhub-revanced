package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.addNativeMethod
import app.revanced.util.getReference
import app.revanced.util.redirectStaticLibLoad
import app.revanced.util.redirectVirtualToStatic
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// The conditional 6.0.2 libxserver + libwinemu swap + JNI bridge, gated
// per-game so New mode is provably stock (zero regression).
//
// Device-confirmed end-to-end (GoW, 2026-05-18):
//   • The 6.0.2 libxserver.so + libwinemu.so pair loads on 6.0.4 once
//     XServer carries a native `setRenderingEnabled(Z)V` (6.0.4 renamed it
//     `setFlipEnabled`); its JNI_OnLoad RegisterNatives then resolves
//     (10/11 already matched).
//   • 6.0.4's two `setFlipEnabled(Z)V` call sites must reach the native the
//     loaded lib actually binds, and Legacy must drive the 6.0.2 enable
//     switch with `true` (see BhRendererController.flip — the two natives
//     are NOT semantically the same despite the version-rename).
//
//   1. addNativeMethod  → XServer gains native setRenderingEnabled(Z)V so
//      the legacy lib's RegisterNatives can bind when it loads. Harmless
//      when the stock lib is loaded: it is simply an unbound native that
//      flip() never invokes in New mode.
//   2. <clinit> loader  → XServer.<clinit>'s System.loadLibrary("xserver")
//      becomes BhRendererController.loadXserver(name). The helper loads
//      libxserver_legacy.so only when the launching game's pref = Legacy;
//      otherwise it calls System.loadLibrary(name) bit-identically. The
//      renderer decision is frozen there for the process.
//   3. flip dispatch    → both setFlipEnabled(Z)V call sites become
//      BhRendererController.flip(xserver, flag), which reflectively invokes
//      setFlipEnabled (stock lib) or setRenderingEnabled (legacy lib) per
//      the frozen decision. Static target avoids the self-recursion a
//      virtual redirect on XServer would cause (see redirectVirtualToStatic).
//   4. winemu loader    → every System.loadLibrary("winemu") early loader
//      becomes BhRendererController.loadWinemu. The 6.0.2 pair is required
//      (xserver-only crashed ~40 s in, missing the 6.0.2 compositor); the
//      swap is idempotent + always-falls-back so New mode and a
//      missing/failed legacy lib never regress.
// =========================================================================

private const val XSERVER  = "Lcom/winemu/core/server/XServer;"
private const val SYSTEM   = "Ljava/lang/System;"
private const val RENDER_CTL = "Lcom/xj/winemu/renderer/BhRendererController;"

@Suppress("unused")
val rendererSwapPatch = bytecodePatch(
    name = "Legacy renderer conditional swap",
    description = "Per-game gates the proven 6.0.2 libxserver swap + JNI " +
        "bridge: adds the setRenderingEnabled native, routes XServer's " +
        "loadLibrary and setFlipEnabled call sites through " +
        "BhRendererController. New mode = stock, zero regression.",
) {
    // GATED OUT of 6.0.7: pinned to 6.0.4 so the patcher SKIPS it (version-
    // incompatible, not a SEVERE failure). The Legacy GLES2 path swaps in the
    // 6.0.2 libxserver, whose JNI_OnLoad RegisterNatives needs XServer methods
    // 6.0.7 deleted (setSurfaceFormat/setFlipEnabled) -> SIGABRT at <clinit>
    // (device-confirmed on DOOMBLADE, 2026-06-06). 6.0.7 grew XServer 11->40
    // natives (ReShade FX engine), so the old .so cannot satisfy the contract;
    // not patchable without a source-built GLES2 libxserver. New mode = stock,
    // unaffected. Revive only with a 6.0.7-contract GLES2 libxserver.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))

    dependsOn(
        sharedGamehubExtensionPatch,
        rendererManifestPatch,
        rendererLibBundlePatch,
    )

    apply {
        // (1) Native shim so the 6.0.2 libxserver's RegisterNatives binds.
        addNativeMethod(XSERVER, "setRenderingEnabled", listOf("Z"), "V")

        // (2) Redirect XServer.<clinit>'s System.loadLibrary("xserver").
        val clinit = firstMethod {
            definingClass == XSERVER && name == "<clinit>"
        }
        val insns = clinit.implementation!!.instructions.toList()
        val loadIdx = insns.indexOfFirst { ins ->
            ins.opcode == Opcode.INVOKE_STATIC &&
                (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                    ?.let { it.definingClass == SYSTEM && it.name == "loadLibrary" } == true
        }
        require(loadIdx >= 0) {
            "RendererSwapPatch: System.loadLibrary not found in " +
                "$XSERVER-><clinit> — base APK layout changed"
        }
        val loadIns = insns[loadIdx] as FiveRegisterInstruction
        val nameReg = loadIns.registerC
        clinit.removeInstruction(loadIdx)
        clinit.addInstructions(
            loadIdx,
            "invoke-static {v$nameReg}, " +
                "$RENDER_CTL->loadXserver(Ljava/lang/String;)V",
        )

        // (3) Route both setFlipEnabled(Z)V call sites to the dispatcher.
        redirectVirtualToStatic(
            XSERVER,
            "setFlipEnabled",
            "(Z)V",
            "$RENDER_CTL->flip(Ljava/lang/Object;Z)V",
        )

        // (4) Also gate libwinemu. Redirect every System.loadLibrary(
        //     "winemu") early loader to BhRendererController.loadWinemu,
        //     which swaps the 6.0.2 libwinemu_legacy.so when Legacy is
        //     active for the launching game (the proven pair) and is
        //     bit-identical stock otherwise. Idempotent + always-falls-back
        //     so New mode and a missing/failed legacy lib never regress.
        //     libwinemu's decision can't be strictly per-game (early
        //     loaders), so it resolves per :wine launch via the same
        //     sniff+global path.
        redirectStaticLibLoad(
            "winemu",
            "$RENDER_CTL->loadWinemu(Ljava/lang/String;)V",
        )
    }
}
