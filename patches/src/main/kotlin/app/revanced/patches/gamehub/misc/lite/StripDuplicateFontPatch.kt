package app.revanced.patches.gamehub.misc.lite

import app.revanced.patcher.patch.resourcePatch

// =============================================================================
// "BannerHub V6 Lite" — Tier 1 size reduction.
//
// GameHub 6.0.4 ships the 20 MB variable font misans_vf.ttf TWICE, byte-for-byte
// identical (MD5 579ce9d39b6ebc71a0522c95ab85b17f), under two Compose
// Multiplatform resource namespaces:
//
//   assets/composeResources/com.xiaoji.egggame.core/font/misans_vf.ttf       (DEAD)
//   assets/composeResources/com.xiaoji.egggame.cardsystem/font/misans_vf.ttf (LIVE)
//
// Verified against the full 6.0.4 smali decompile: the ONLY misans_vf.ttf
// reference anywhere in the bytecode is the .cardsystem literal at
// oj6.smali:427 (feeding the "font:misans_vf" FontFamily). The .core module is
// referenced 245x — drawables/values only, never the font. There is no
// path-concatenation pattern; every Compose resource path in this app is a
// full const-string literal. The .core copy is therefore unreferenced dead
// weight, not a redirect target — it can simply be deleted.
//
// Guard: only delete the .core copy if the .cardsystem (live) copy is still
// present, so a future base bump that flips which namespace is live can't
// cause this patch to strip the only remaining copy.
// =============================================================================

private const val DEAD_FONT =
    "assets/composeResources/com.xiaoji.egggame.core/font/misans_vf.ttf"
private const val LIVE_FONT =
    "assets/composeResources/com.xiaoji.egggame.cardsystem/font/misans_vf.ttf"

@Suppress("unused")
val stripDuplicateFontPatch = resourcePatch(
    name = "Strip duplicate font",
    description = "Removes the unreferenced 20 MB duplicate copy of misans_vf.ttf " +
        "shipped under the com.xiaoji.egggame.core Compose namespace. The live " +
        "copy under com.xiaoji.egggame.cardsystem (the only one any code path " +
        "loads) is left untouched. ~20 MB APK reduction, no functional change.",
    use = false,
) {
    apply {
        val live = get(LIVE_FONT)
        val dead = get(DEAD_FONT)
        if (live.exists() && dead.exists()) {
            delete(DEAD_FONT)
        }
    }
}
