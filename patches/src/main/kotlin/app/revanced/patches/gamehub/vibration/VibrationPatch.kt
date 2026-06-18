package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// =========================================================================
// R8-mangled class/method map for the vibration patch, version by version.
//
// Source: TideGear/GameHub-Vibration-Fix (BannerHub PR #80 / GameNative
// PR #1214). 6.0.2 letters were `za8` (Physical), `dg5` (env builder).
//
// PHYSICAL_CLASS  — the multi-motor "Physical" gamepad device (dual-motor
//                   VibratorManager blend). The abstract base is `Lpz7;`
//                   (fields a:I=low, b:I=high cache; abstract h(II)V + g()V);
//                   PHYSICAL_CLASS is its concrete subclass. Structural anchor:
//                     - extends the device base, method `h(II)V` .locals 3
//                       starting `const v0, 0xffff` (the 65535 rumble scale)
//                     - method `g()V` .locals 1 (multi-motor stop)
//                     - field `f:I` = the Android device id
//                       (constructor wires getAndroidDeviceId() into it)
//                     - field `k:` = the motor/vibrator manager
//                   The sibling subclass (`oz7` on 6.0.7) is the single
//                   android.os.Vibrator device — match by content, not name.
//                   6.0.2 Lza8;  6.0.4 Lab8; (dispatch g(II)V, stop f()V)
//                   6.0.7 Lnz7; — methods shifted: dispatch g(II)V → h(II)V,
//                   stop f()V → g()V; the device-id field is still `f:I`.
//                   6.0.8 Lpz7; (base Lpz7;→Lrz7;) — verified
//                   ~/gh608-apktool-d/smali_classes3/pz7.smali: extends abstract
//                   Lrz7; (abstract g()V + h(II)V); h(II)V .locals 3 + const
//                   0xffff; g()V .locals 1; field f:I (device id); field k:Lb4k;
//                   (motor mgr). Sibling qz7 (no f:I) is the single-Vibrator decoy.
//                   6.0.9 Ly98; (base Lrz7;→Laa8;) — verified
//                   ~/gh609-apktool-d/smali_classes3/y98.smali: extends Laa8;;
//                   h(II)V .locals 3 + const 0xffff; g()V .locals 1; field f:I
//                   (device id); field k:Lkwk; (motor mgr); i()List multi-motor.
//                   Sibling z98 (field f:Z boolean, has Context, h(II)V .locals 2)
//                   is the single-Vibrator decoy. Method/field names (h/g/f:I)
//                   preserved — only the class letter reshuffled (Lpz7→Ly98; the
//                   old Lpz7 letter is now an unrelated string class). Hooks 1
//                   (GamepadServerManager.onRumble, kept name) & 4 (WineActivity
//                   .onCreate, kept name, .locals 20 → from16 still valid) and the
//                   pattern-based winebus.so disk patcher are 6.0.9-unaffected.
// WINE_ACTIVITY   — the Wine session Activity. Hook 4 (the winebus disk-patch
//                   trigger) used to anchor the Wine env-vars builder
//                   (6.0.2 Ldg5;, 6.0.4 Lbg5;->a(...)V .locals 35). In 6.0.7
//                   that dedicated builder was dissolved — the LD_PRELOAD env
//                   is now assembled inside a merged WineActivity class
//                   (`bqn;->t0`) with no clean Context field. WineActivity is
//                   a stable, non-obfuscated AppCompatActivity (= a Context)
//                   whose onCreate runs at game launch, before the Wine
//                   process maps winebus.so — a strictly more robust anchor.
private const val PHYSICAL_CLASS = "Ly98;"   // 6.0.8: Lpz7;  6.0.7: Lnz7;  6.0.4: Lab8;
private const val WINE_ACTIVITY =
    "Lcom/xiaoji/egggame/features/winemu/WineActivity;"

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
        // Original body starts `if-ltz p1, :cond_0` and dispatches to the
        // device manager (Lfc8;->G(III)V). We prepend an invoke-static into
        // our handler; if it returns true we early-return (we handled the
        // rumble), otherwise fall through to the stock path. The method is
        // @Keep so R8 doesn't touch its signature; class name is stable.
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
        // Hook 2: PHYSICAL_CLASS.h(II)V — per-controller dispatch delegate.
        //
        // The device manager (Lfc8;->G) routes a non-zero (low, high) to
        // PHYSICAL.h(II)V. Reads deviceId from PHYSICAL.f:I, hands
        // (deviceId, low, high) to the extension. If it returns true
        // (handled), we early-return; otherwise fall through to stock
        // per-vibrator blending (the single-motor `low*0.80 + high*0.33`
        // blend we want to skip on multi-motor pads).
        // 6.0.4: Lab8;->g(II)V → 6.0.7: Lnz7;->h(II)V.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == PHYSICAL_CLASS &&
                name == "h" &&
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
        // Hook 3: PHYSICAL_CLASS.g()V — stop hook.
        //
        // Stock GameHub routes (0, 0) through g() instead of h(II) (see
        // Lfc8;->G), so hook 2 doesn't catch the release. We notify the
        // keepalive map here, then fall through to the original cleanup.
        // 6.0.4: Lab8;->f()V → 6.0.7: Lnz7;->g()V.
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == PHYSICAL_CLASS &&
                name == "g" &&
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
        // Hook 4: WineActivity.onCreate — preload-free winebus disk-patch.
        //
        // Replaces the former libevshim.so LD_PRELOAD injection. Mapping an
        // extra .so into the Wine subprocess destabilises box64 under
        // new-WoW64 and silently exits a class of games (DiRT 3 →
        // STATUS_INVALID_IMAGE_FORMAT c000007b; Shotgun King ~700ms). Instead
        // we call BhVibrationController.ensureWinebusDurationPatchOnce(ctx)
        // once per app process. The Java side scans the app files tree and
        // rewrites every winebus.so's two non-zero SDL_JoystickRumble duration
        // loads to 0xffffffff on disk (aarch64 + x86_64) so SDL2's ~1s
        // rumble_expiration never fires; an AtomicBoolean gates repeat scans.
        // No LD_PRELOAD, no extra mapping.
        //
        // 6.0.7 anchor change: the dedicated env-vars builder this hook used
        // (6.0.4 Lbg5;->a(...)V) was dissolved into a merged WineActivity
        // class with no clean Context field. WineActivity.onCreate is a
        // stable, guaranteed-at-launch entry that IS a Context, and runs
        // before the Wine process maps winebus.so — and the
        // AtomicBoolean-gated patcher is self-deduplicating, so an at-onCreate
        // call is correct. onCreate is `.locals 19`, so `p0` (this) is a high
        // register; materialise it via move-object/from16 (v0 is clobbered but
        // the method re-initialises it, so prepending at index 0 is safe).
        // -----------------------------------------------------------------
        firstMethod {
            definingClass == WINE_ACTIVITY &&
                name == "onCreate" &&
                parameterTypes == listOf("Landroid/os/Bundle;") &&
                returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    move-object/from16 v0, p0
                    invoke-static {v0}, $VIB_HANDLER->ensureWinebusDurationPatchOnce(Landroid/content/Context;)V
                """.trimIndent(),
            )
        }
    }
}
