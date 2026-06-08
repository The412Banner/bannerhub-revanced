package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.removeInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.redirectStaticLibLoad
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// The conditional legacy-GLES2 renderer swap, gated per-game so New mode is
// provably stock (zero regression). 6.0.8 edition — via the wrapper shim.
//
// 6.0.7/6.0.8 rewrote the X-server: XServer grew 11→40 natives (a ReShade
// `effects*` subsystem + setGpuPassthroughEnabled + stop) and DELETED the two
// the 6.0.2 engine announces at startup (setRenderingEnabled, setSurfaceFormat).
// Loading the raw 6.0.2 libxserver therefore aborts (NoSuchMethodError at
// <clinit>). So unlike the 6.0.4 path (direct lib load + a one-method
// addNativeMethod shim + setFlipEnabled redirect), 6.0.8 loads a WRAPPER —
// libxserver_shim.so — that re-publishes the 6.0.2 engine's functions under the
// new 40-command contract (see native/xserver_shim/ + docs/LEGACY_RENDERER_607
// _SHIM_RECON.md). Because the wrapper owns RegisterNatives and drives the
// enable switch internally (setGpuPassthroughEnabled→setRenderingEnabled, plus a
// surface-ready self-drive), 6.0.8 needs NEITHER the addNativeMethod shim NOR
// the setFlipEnabled redirect the 6.0.4 patch used (6.0.8 has no setFlipEnabled).
//
//   1. <clinit> loader  → XServer.<clinit>'s System.loadLibrary("xserver")
//      becomes BhRendererController.loadXserver(name). The helper loads the
//      wrapper libxserver_shim.so (which dlopens libxserver_legacy.so) only
//      when the launching game's pref = Legacy; otherwise it calls
//      System.loadLibrary(name) bit-identically. Decision frozen for the proc.
//   2. winemu loader    → every System.loadLibrary("winemu") early loader
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
    description = "Per-game gates the legacy GLES2 renderer on 6.0.8 via the " +
        "wrapper shim: routes XServer's loadLibrary(\"xserver\") and the " +
        "winemu loaders through BhRendererController. New mode = stock, " +
        "zero regression.",
) {
    // 6.0.8: the wrapper shim (native/xserver_shim, loaded by loadXserver)
    // satisfies the 40-method XServer contract, so this is no longer pinned to
    // 6.0.4. 6.0.8's XServer native surface is byte-identical to 6.0.7's, and
    // 6.0.8 has NO setFlipEnabled (the 6.0.4 redirect target) — the wrapper
    // drives the enable switch internally, so neither the addNativeMethod shim
    // nor the setFlipEnabled redirect is used here.
    compatibleWith(GAMEHUB_PACKAGE("6.0.8"))

    dependsOn(
        sharedGamehubExtensionPatch,
        rendererManifestPatch,
        rendererLibBundlePatch,
    )

    apply {
        // (1) Redirect XServer.<clinit>'s System.loadLibrary("xserver").
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

        // (2) Also gate libwinemu. Redirect every System.loadLibrary(
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
