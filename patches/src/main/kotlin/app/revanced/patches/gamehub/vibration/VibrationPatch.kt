package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.extensions.addInstructions
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
        "controllers, sustained holds (preload-free: an in-process hook " +
        "patches every winebus.so on disk so SDL2's ~1s rumble_expiration " +
        "never fires — no libevshim.so, no LD_PRELOAD, no extra mapping in " +
        "the Wine subprocess), and instant release. Adapted from " +
        "TideGear/GameHub-Vibration-Fix (GameNative PR #1214 lineage) with " +
        "the author's permission.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch, vibrationManifestPatch)

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
        // Hook 4: ENV_BUILDER.a(...)V — preload-free winebus disk-patch.
        //
        // Replaces the former libevshim.so LD_PRELOAD injection. Mapping an
        // extra .so into the Wine subprocess destabilises box64 under
        // new-WoW64 and silently exits a class of games (DiRT 3 →
        // STATUS_INVALID_IMAGE_FORMAT c000007b; Shotgun King ~700ms). Instead
        // we call BhVibrationController.ensureWinebusDurationPatchOnce(ctx)
        // once per app process, right before the env builder hands the env
        // list to the Wine launcher. The Java side scans the app files tree
        // and rewrites every winebus.so's two non-zero SDL_JoystickRumble
        // duration loads to 0xffffffff on disk (aarch64 + x86_64) so SDL2's
        // ~1s rumble_expiration never fires; an AtomicBoolean gates repeat
        // scans. No LD_PRELOAD, no extra mapping.
        //
        // Anchor: the joinToString$default arg-setup block (const/16 v16,0x0;
        // const/16 v17,0x3e; const-string v13,":"; const/4 v14,0x0; const/4
        // v15,0x0) immediately preceding the JOIN_HELPER->I0 invoke-static/
        // range. We insert our 2-instruction call just BEFORE that setup.
        // v0 is `this` (the env builder); v13 holds the Context only until
        // the setup block (which runs immediately after) re-initialises it
        // for the join, so the reuse is safe with no branch/label needed.
        //
        // Ported from TideGear/GameHub-Vibration-Fix Patch 4 (GameNative
        // PR #1214 lineage) with the author's permission.
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

            val setupStartIdx = joinIdx - 5
            require(setupStartIdx >= 0) {
                "ENV_BUILDER.a join setup block not found (joinIdx=$joinIdx); " +
                    "expected ≥5 instructions of arg setup before invoke-static/range"
            }

            addInstructions(
                setupStartIdx,
                """
                    iget-object v13, v0, $ENV_BUILDER->a:Landroid/content/Context;
                    invoke-static {v13}, $VIB_HANDLER->ensureWinebusDurationPatchOnce(Landroid/content/Context;)V
                """.trimIndent(),
            )
        }
    }
}
