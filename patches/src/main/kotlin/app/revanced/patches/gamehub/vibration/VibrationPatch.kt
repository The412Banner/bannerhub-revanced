package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

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
private const val PHYSICAL_CLASS = "Lab8;"
private const val ENV_BUILDER    = "Lbg5;"

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
        // Anchor: method ENTRY (index 0) of the env builder. The former
        // anchor used fragile index arithmetic — `joinIdx - 5`, assuming the
        // five instructions before the joinToString$default invoke-static/
        // range were the `:`-separator arg-setup block. In the 6.0.4 base
        // (versionCode 114) the instruction layout differs: `joinIdx - 5`
        // lands inside the ArrayList-building loop, immediately AFTER an
        // unconditional `goto` and before a `:cond_*` label, so the two
        // injected instructions become unreachable dead code and the patch
        // never runs (confirmed by zero WINEBUS breadcrumbs on a live
        // launch). Index 0 is unconditionally reached every time the env
        // builder is invoked at launch — the same guaranteed-reachable spot
        // Hooks 1–3 use. `ensureWinebusDurationPatchOnce` is AtomicBoolean-
        // gated so an at-entry call is correct and self-deduplicating.
        //
        // `p0` is `this` (the env builder, a high register under .locals 35);
        // materialise it into v0, read the Context field, call the patcher.
        // v0 is clobbered, but the method's own first instruction
        // (`move-object/from16 v0, p0`) re-initialises it immediately after,
        // so prepending here is safe with no label needed.
        //
        // Ported from TideGear/GameHub-Vibration-Fix Patch 4 (GameNative
        // PR #1214 lineage) with the author's permission.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == ENV_BUILDER && name == "a" && returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    iget-object v0, v0, $ENV_BUILDER->a:Landroid/content/Context;
                    invoke-static {v0}, $VIB_HANDLER->ensureWinebusDurationPatchOnce(Landroid/content/Context;)V
                """.trimIndent(),
            )
        }
    }
}
