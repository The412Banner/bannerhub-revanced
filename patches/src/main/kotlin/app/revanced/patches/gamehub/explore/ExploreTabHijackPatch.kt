package app.revanced.patches.gamehub.explore

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =========================================================================
// Hijacks GameHub 6.0.4's unused bottom-nav "Explore" tab to open the
// BannerHub-owned Explore screen (BannerExploreActivity) instead of xiaoji's
// server-driven discovery feed. See GOG_LIBRARY_TAB_DESIGN §42 (full scope +
// spike).
//
// SEAM (spike §42.4): the bottom-nav controller is the ViewModel `w1a`. Tab
// selection converges on `q(Lyw9;)V` — BOTH the UI tap (n(r1a) handles a q1a
// SelectTab → q) and programmatic deep-links (zu9 home_tab_selection_request
// → w1a.q). w1a is a SINGLE shared VM across handheld + explore modes (its
// ctor builds both tab orderings), so one seam covers every route in both
// modes. The default tab is seeded directly into state by the ctor — NOT via
// q() — so this never fires on cold start.
//
// `yw9` = the live selected-tab enum: HOME(0)=the "Explore" bar item, PLAY(1),
// LEADERBOARD(2), LIBRARY(3), PROFILE(4). The ordinal-0 check lives in
// BhExploreTabClick.maybeHijack (R8-proof: enum ordinal is stable; no
// obfuscated field sget needed).
//
// INJECT (method head of q):
//   move-object/from16 v0, p1      # p1 = yw9Var; from16 survives high regs
//   invoke-static {v0}, BhExploreTabClick;->maybeHijack(Object)Z
//   move-result v0
//   if-eqz v0, :continue           # not explore / failed → native behaviour
//   return-void                    # hijacked → skip the StateFlow tab switch
//   :continue  (original q() body)
//
// FAIL-SAFE: maybeHijack returns false on anything but a successful explore
// open, so GameHub always falls through to its native Explore. v0 is a low
// local (q()'s CAS-loop body has ample registers); the from16 read of p1
// avoids the high-register invoke trap ([[feedback_revanced_high_register_invoke]]).
//
// FINGERPRINT: q's name (`q`) and class (`w1a`) are R8-volatile, so anchor on
// structure — the (Lyw9;)V method carrying the const-string "main_menu" (the
// `yw9 == yw9.d ? "main_menu" : ...` branch unique to q on 6.0.4). The ctor
// also uses "main_menu" but is <init> with a different signature.
// =========================================================================

private const val CLICK = "Lcom/xj/winemu/explore/BhExploreTabClick;"
// 6.0.4 → 6.0.7 (R8 reshuffle): tab-select VM `w1a`→`ai9`, dispatch `q`→`u`,
// tab enum `Lyw9;`→`Lhd9;` (hd9 ordinals HOME(0)=Explore/PLAY/LEADERBOARD/
// LIBRARY/PROFILE — identical to 604).
// 6.0.7 → 6.0.8: VM `ai9`→`di9`, dispatch stays `u`, tab enum `Lhd9;`→`Lkd9;`
// (verified ~/gh608-apktool-d: kd9 = enum with 5 values a–e; di9.u(Lkd9;)V is
// the UNIQUE apk-wide method matching param-type Lkd9; + V + "main_menu";
// sibling di9.w takes Leh9; (plain class), di9.q takes interface Lxh9;).
// 6.0.8 → 6.0.9: VM `di9`→`ys9`, dispatch `u`→`t`, tab enum `Lkd9;`→`Lrn9;`
// (verified ~/gh609-apktool-d: rn9 = enum extends Enum, 5 values a–e =
// HOME(0)/PLAY(1)/LEADERBOARD(2)/LIBRARY(3)/PROFILE(4), byte-identical ordinal
// mapping to kd9. ys9.t(Lrn9;)V is the UNIQUE apk-wide method matching param-type
// Lrn9; + V + "main_menu" — sibling ys9.r(Lrn9;)V has NO "main_menu", ys9.v takes
// Las9; (plain class), rs9.<init> takes Lrn9; but is a ctor w/o "main_menu").
// The patch is structure-anchored (param-type + "main_menu"), so only this enum
// letter is hardcoded.
// 6.0.9 → 6.1.0: bottom-nav VM ys9→sac (its base dn1→wb2), tab-select dispatch
// t()→v(), tab enum Lrn9;→Lh5c;. Verified against ~/gh610-apktool-d:
//   - Lh5c; is an enum whose <clinit> declares HOME(0) / PLAY(1) / LEADERBOARD(2) /
//     LIBRARY(3) / PROFILE(4) — a byte-identical ordinal mapping to 6.0.9's Lrn9;,
//     so CLICK's ordinal-0 assumption still holds.
//   - sac.v(Lh5c;)V is the ONLY method in the whole APK matching
//     (one param + returns V + contains "main_menu"), and "main_menu" now occurs
//     in exactly ONE class app-wide (6.0.9 had two: ys9 and mhc) — so this
//     fingerprint is stricter on 610, not looser.
//   - Sibling sac.s(Lh5c;)V takes the same enum but has NO "main_menu";
//     sac.x(...) DOES contain "main_menu" but takes Lu9c; (a plain class, not the
//     enum). Both would be mis-targeted without the full predicate.
// ⚠️ Do NOT "improve" this to a purely structural 5-value-enum anchor: 6.1.0 has
// TWO such enums (Lh5c; = live nav, Lnd; = the analytics mirror, ex-Laa;), exactly
// as 6.0.9 did (Lrn9; + Laa;). The enum letter must stay pinned, or the predicate
// must additionally assert the parameter enum's constant names.
private const val TAB_ENUM = "Lh5c;"
private const val ANCHOR_STRING = "main_menu"

@Suppress("unused")
val exploreTabHijackPatch = bytecodePatch(
    name = "Explore tab hijack",
    description = "Opens the BannerHub-owned Explore screen when the Explore " +
        "bottom-nav tab is tapped, instead of xiaoji's server-driven feed. " +
        "Intercepts the bottom-nav controller's tab-select dispatch " +
        "(w1a.q); fail-safe falls through to the native Explore on any error.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    // This patch injects a call into our extension (BhExploreTabClick), but never
    // declared the dependency that actually bundles the extension into the APK — it
    // worked only because some OTHER selected bytecode patch happened to pull it in.
    // On 6.1.0, where most bytecode patches SEVERE-fail, that implicit coupling is
    // exactly how you get "patch succeeded, runtime no-op". Declared explicitly.
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // The tab-select dispatch q(Lyw9;)V — the convergence point for UI taps
        // and programmatic nav. Anchored by its sole (Lyw9;)V + "main_menu" body.
        val tabSelectMethod = firstMethod {
            parameterTypes == listOf(TAB_ENUM) &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.CONST_STRING &&
                        (ins as? ReferenceInstruction)
                            ?.getReference<StringReference>()?.string == ANCHOR_STRING
                } ?: false)
        }

        val firstInstruction = tabSelectMethod.getInstruction(0)
        tabSelectMethod.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, p1
                invoke-static {v0}, $CLICK->maybeHijack(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :bh_explore_continue
                return-void
            """.trimIndent(),
            ExternalLabel("bh_explore_continue", firstInstruction),
        )
    }
}
