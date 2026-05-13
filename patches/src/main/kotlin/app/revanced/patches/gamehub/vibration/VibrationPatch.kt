package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// 6.0.4 R8-mangled class letter map for the vibration patch.
//
// Source: TideGear/GameHub-Vibration-Fix (BannerHub PR #80 / GameNative
// PR #1214). 6.0.2 letters were `za8` (Physical), `lrl` (motor manager
// type), `dg5` (env builder), `ns2.I0` (join helper), `ow6` (Function1).
//
// PHYSICAL_CLASS  — GamepadDevice$Physical-equivalent. Structural anchor:
//                   public final class extending Lcb8;, with:
//                     - method `g(II)V` .locals 3, body starts `const v0, 0xffff`
//                     - method `f()V`   .locals 1, body starts iget on `k:L<motor>;`
//                     - field `f:I` (deviceId)
//                     - field `k:L<motor manager>;`
//                   6.0.2 → `Lza8;`, 6.0.4 → `Lab8;`. Note: a different,
//                   unrelated class still uses the letter `Lza8;` in 6.0.4 —
//                   match by content (instructions + fields) not by name.
// ENV_BUILDER     — Wine env-vars builder that constructs the LD_PRELOAD
//                   list. Structural anchor:
//                     - method `a(...)V` .locals 35 with first arg the
//                       Wine config object
//                     - body builds an ArrayList<String> in v12, calls
//                       Kotlin joinToString$default with ":" separator
//                     - has field `a:Landroid/content/Context;`
//                   6.0.2 → `Ldg5;`, 6.0.4 → `Lbg5;`.
// JOIN_HELPER     — Kotlin CollectionsKt's joinToString$default static helper.
//                   Method name `I0` survived R8 across 6.0.2 → 6.0.4; only
//                   the class letter shifted.
//                   6.0.2 → `Lns2;`, 6.0.4 → `Lps2;`.
// JOIN_LAMBDA     — Function1 type accepted by joinToString$default. Same
//                   sub-letter shift as JOIN_HELPER.
//                   6.0.2 → `Low6;`, 6.0.4 → `Lpw6;`.
private const val PHYSICAL_CLASS = "Lab8;"
private const val ENV_BUILDER    = "Lbg5;"
private const val JOIN_HELPER    = "Lps2;"
private const val JOIN_METHOD    = "I0"
private const val JOIN_LAMBDA    = "Lpw6;"

private const val VIB_HANDLER =
    "Lcom/xj/winemu/vibration/BhVibrationController;"

private const val GAMEPAD_SERVER_MANAGER =
    "Lcom/winemu/core/gamepad/GamepadServerManager;"

// =========================================================================

@Suppress("unused")
val vibrationPatch = bytecodePatch(
    name = "PC-accurate vibration",
    description = "Routes Wine XInput rumble (low, high) into Android's " +
        "VibratorManager with dual-motor independent dispatch on multi-motor " +
        "controllers, sustained holds (via guest-side libevshim.so LD_PRELOAD " +
        "shim re-issuing SDL_JoystickRumble every 500ms so SDL2's 1s " +
        "auto-stop never fires), and instant release. Adapted from " +
        "TideGear/GameHub-Vibration-Fix (BannerHub PR #80).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, vibrationManifestPatch, vibrationLibPatch)

    apply {
        // -----------------------------------------------------------------
        // Hook 1: GamepadServerManager.onRumble(III)V — dispatcher entry.
        //
        // Original body starts:
        //   .line 1
        //   if-ltz p1, :cond_4
        //
        // We prepend an invoke-static into our handler; if it returns true
        // we early-return (we handled the rumble), otherwise fall through
        // to the stock path. The method is annotated @Keep so R8 doesn't
        // touch its signature across versions; the `:cond_4` label has been
        // stable on 6.0.x as well.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == GAMEPAD_SERVER_MANAGER && name == "onRumble"
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {p1, p2, p3}, $VIB_HANDLER->onRumble(III)Z
                    move-result v0
                    if-eqz v0, :bh_rumble_fallthrough
                    return-void
                    :bh_rumble_fallthrough
                """.trimIndent(),
            )
        }

        // -----------------------------------------------------------------
        // Hook 2: PHYSICAL_CLASS.g(II)V — per-controller dispatch delegate.
        //
        // Reads deviceId from PHYSICAL.f:I, hands (deviceId, low, high) to
        // the extension. If the extension returns true (handled), we
        // early-return; otherwise fall through to stock per-vibrator
        // blending (which is the single-motor `low*0.80 + high*0.33` blend
        // we want to skip on multi-motor pads).
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == PHYSICAL_CLASS &&
                name == "g" &&
                parameterTypes == listOf("I", "I") &&
                returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    iget v0, p0, $PHYSICAL_CLASS->f:I
                    invoke-static {v0, p1, p2}, $VIB_HANDLER->dispatchToController(III)Z
                    move-result v0
                    if-eqz v0, :bh_phys_fallthrough
                    return-void
                    :bh_phys_fallthrough
                """.trimIndent(),
            )
        }

        // -----------------------------------------------------------------
        // Hook 3: PHYSICAL_CLASS.f()V — stop hook.
        //
        // Stock GameHub routes (0, 0) through f() instead of g(II), so
        // hook 2 doesn't catch the release. We notify the keepalive map
        // here, then fall through to the original cleanup.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == PHYSICAL_CLASS &&
                name == "f" &&
                parameterTypes.isEmpty() &&
                returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    iget v0, p0, $PHYSICAL_CLASS->f:I
                    invoke-static {v0}, $VIB_HANDLER->onStop(I)V
                """.trimIndent(),
            )
        }

        // -----------------------------------------------------------------
        // Hook 4: ENV_BUILDER.a(...)V — prepend libevshim.so to LD_PRELOAD.
        //
        // The method builds an ArrayList<String> in v12 then calls
        // joinToString$default with ":" separator. We inject just before
        // that join: resolve nativeLibraryDir + "/libevshim.so", verify
        // the file exists (so the build still works without the .so),
        // and add it as index 0 of the ArrayList. Registers v13..v15 are
        // clobbered by the join setup that immediately follows, so safe
        // to reuse here.
        //
        // The original anchor block (lines 458-465 in 6.0.2 AND 6.0.4 — XJ
        // didn't touch the method between versions, only R8 renamed) is:
        //
        //     const/16 v16, 0x0
        //     const/16 v17, 0x3e
        //     const-string v13, ":"
        //     const/4 v14, 0x0
        //     const/4 v15, 0x0
        //     invoke-static/range {v12 .. v17}, JOIN_HELPER->I0(...)
        //
        // We can't do a unique-line-anchored injection through the
        // patcher API easily, so we walk the method to find the
        // invoke-static/range whose method ref is JOIN_HELPER->I0(...)
        // and inject 13 instructions just before it.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == ENV_BUILDER && name == "a" && returnType == "V"
        }.apply {
            val joinIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_STATIC_RANGE &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == JOIN_HELPER && it.name == JOIN_METHOD
                    } == true
            }

            // Walk back to the start of the joinToString$default arg-setup
            // block — the 5 instructions immediately preceding the
            // invoke-static/range:
            //
            //   const/16 v16, 0x0
            //   const/16 v17, 0x3e
            //   const-string v13, ":"
            //   const/4 v14, 0x0
            //   const/4 v15, 0x0
            //
            // We insert BEFORE this setup (not after, as v1.1.0-pre1+pre2
            // did). The setup re-initializes v13..v17 to the types
            // invoke-static/range expects (`null` ConstZero for the
            // CharSequence prefix/postfix slots, `:` String for the
            // separator, int for the limit + mask).
            //
            // v1.1.0-pre2 inserted AT joinIdx (after setup, before invoke),
            // so our File path-builder clobbered v14 with `File` and the
            // verifier rejected the invoke with
            // `register v14 has type Reference: java.io.File
            //  but expected Reference: java.lang.String`.
            // By moving the insertion 5 instructions earlier, both the
            // fall-through and branch-taken paths from our `if-eqz` flow
            // into the setup block, which restores the join args cleanly.
            //
            // The ExternalLabel target is the original instruction at
            // setupStartIdx (the const/16 v16); after insertion shifts it
            // down by 18, the patcher tracks the new position via
            // Instruction identity.
            val setupStartIdx = joinIdx - 5
            require(setupStartIdx >= 0) {
                "ENV_BUILDER.a join setup block not found (joinIdx=$joinIdx); " +
                    "expected ≥5 instructions of arg setup before invoke-static/range"
            }
            val setupStartInstruction = getInstruction(setupStartIdx)

            addInstructionsWithLabels(
                setupStartIdx,
                """
                    iget-object v13, v0, $ENV_BUILDER->a:Landroid/content/Context;
                    invoke-virtual {v13}, Landroid/content/Context;->getApplicationInfo()Landroid/content/pm/ApplicationInfo;
                    move-result-object v13
                    iget-object v13, v13, Landroid/content/pm/ApplicationInfo;->nativeLibraryDir:Ljava/lang/String;
                    new-instance v14, Ljava/lang/StringBuilder;
                    invoke-direct {v14}, Ljava/lang/StringBuilder;-><init>()V
                    invoke-virtual {v14, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    const-string v13, "/libevshim.so"
                    invoke-virtual {v14, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
                    invoke-virtual {v14}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
                    move-result-object v13
                    new-instance v14, Ljava/io/File;
                    invoke-direct {v14, v13}, Ljava/io/File;-><init>(Ljava/lang/String;)V
                    invoke-virtual {v14}, Ljava/io/File;->exists()Z
                    move-result v15
                    if-eqz v15, :bh_skip_evshim_preload
                    const/4 v15, 0x0
                    invoke-virtual {v12, v15, v13}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
                """.trimIndent(),
                ExternalLabel("bh_skip_evshim_preload", setupStartInstruction),
            )
        }
    }
}
