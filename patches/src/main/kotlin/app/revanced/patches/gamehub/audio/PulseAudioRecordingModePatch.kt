package app.revanced.patches.gamehub.audio

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =========================================================================
// Makes PulseAudio screen-recordable on demand.
//
// PulseAudioComponent (R8-obfuscated to Lqnh;) builds default.pa in code:
//
//   const-string vN, "load-module module-aaudio-sink"   <-- anchor
//   ...aput into the String[] joined by "\n" and written to default.pa
//
// With no pm= arg the module opens its AAudio stream in LOW_LATENCY mode →
// the framework grants MMAP → bypasses the AudioFlinger mixer that
// MediaProjection AudioPlaybackCapture taps → screen recordings are silent.
//
// We rewrite that literal at runtime via BhAudioController.configLine(),
// which appends " pm=0" (→ setPerformanceMode(pm+10) = PERFORMANCE_MODE_NONE)
// when the global "Recording-compatible audio" toggle is ON. Default OFF =
// the helper returns the string untouched = stock behaviour, zero regression.
//
// Anchored on the const-string literal (survives R8 renames); the toggle UI
// lives in the Banner Tools dialog's "Audio" tile.
//
// 6.3.1 (also 6.1.0+): TARGET LEFT THE HOST. PulseAudioComponent moved into
// the downloadable pcengine plugin with the rest of the Wine runtime:
// `load-module module-aaudio-sink` has 0 hits in the 631 host smali (all five
// dex dirs; the bare `module-aaudio-sink` too), so the literal fingerprint
// resolves to nothing and there is nothing host-side to re-anchor on. The
// anchor logic itself is still right for the plugin dex (the 610 notes found
// it register-identical under Lxjp/x46;), but this pipeline patches only the
// host APK, so the patch is gated (`use = false`) instead of re-derived.
// Re-enable only via a plugin-side patch path. Note: the companion
// "Recording-compatible audio settings activity" (audioManifestPatch) still
// applies on its own, so the Banner Tools → Audio toggle exists but is inert.
// =========================================================================

private const val SINK_LINE  = "load-module module-aaudio-sink"
private const val CONTROLLER = "Lcom/xj/winemu/audio/BhAudioController;"

private fun Instruction.isSinkLine(): Boolean =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == SINK_LINE

@Suppress("unused")
val pulseAudioRecordingModePatch = bytecodePatch(
    name = "Recording-compatible audio",
    description = "Lets PulseAudio game audio be captured by screen recording. " +
        "When the global toggle (Banner Tools → Audio) is on, appends pm=0 to " +
        "the module-aaudio-sink config line so the AAudio stream leaves the " +
        "MMAP fast path and sits on the normal mixer the recorder taps. " +
        "Default off — stock low-latency audio, no change for non-recorders. " +
        "GATED on 6.1.0+: PulseAudioComponent lives in the pcengine plugin, " +
        "not the host APK, so the anchor cannot resolve here.",
    // 6.3.1: target is plugin-only (see header) — not applied by default.
    use = false,
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, audioManifestPatch)

    apply {
        val method = firstMethod {
            implementation?.instructions?.any { it.isSinkLine() } == true
        }

        val instructions = method.implementation!!.instructions.toList()
        val idx = instructions.indexOfFirst { it.isSinkLine() }
        require(idx >= 0) {
            "PulseAudioRecordingModePatch: '$SINK_LINE' const-string not found"
        }

        val reg = (instructions[idx] as OneRegisterInstruction).registerA
        require(reg <= 15) {
            "PulseAudioRecordingModePatch: sink-line register v$reg > 15; needs /range form"
        }

        // Rewrite the literal in place: vReg = configLine(vReg). It is then
        // aput into the joined String[] exactly as the stock literal was.
        method.addInstructions(
            idx + 1,
            """
                invoke-static {v$reg}, $CONTROLLER->configLine(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$reg
            """.trimIndent(),
        )
    }
}
