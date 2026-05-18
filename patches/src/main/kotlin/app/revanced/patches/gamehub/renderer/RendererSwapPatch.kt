package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
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
// Milestone 2 — the conditional 6.0.2 libxserver swap + JNI bridge, gated
// per-game so New mode is provably stock (zero regression).
//
// Proven by throwaway test1→test3 (GoW device-confirmed, 2026-05-17):
//   • 6.0.2 libxserver.so loads on 6.0.4 once XServer carries a native
//     `setRenderingEnabled(Z)V` (6.0.4 renamed it `setFlipEnabled`); its
//     JNI_OnLoad RegisterNatives then resolves (10/11 already matched).
//   • 6.0.4's two `setFlipEnabled(Z)V` call sites must reach the native
//     the loaded lib actually binds.
// test3 did all of this ALWAYS-ON. M2 makes it per-game:
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
//
// xserver-only-first (user decision 2026-05-18): libwinemu is NOT gated —
// 7 early clinit loaders lock it to the first-loaded copy; GoW rendered on
// the full pair so xserver-only is the cheap, ordering-proof first cut.
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
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(
        sharedGamehubExtensionPatch,
        rendererManifestPatch,
        rendererLibBundlePatch,
        rendererDiagEnvPatch, // THROWAWAY diagnostic — revert before M2 ship
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

        // (4) FULL-PAIR (user decision 2026-05-18): also gate libwinemu.
        //     Redirect every System.loadLibrary("winemu") early loader to
        //     BhRendererController.loadWinemu, which swaps the 6.0.2
        //     libwinemu_legacy.so when Legacy is active for the launching
        //     game (test3's proven pair) and is bit-identical stock
        //     otherwise. Idempotent + always-falls-back so New mode and a
        //     missing/failed legacy lib never regress. This is the
        //     deliberate trade-off the xserver-only-first cut avoided:
        //     libwinemu's decision can't be strictly per-game (early
        //     loaders), so it resolves per :wine launch via the same
        //     sniff+global path.
        redirectStaticLibLoad(
            "winemu",
            "$RENDER_CTL->loadWinemu(Ljava/lang/String;)V",
        )
    }
}
