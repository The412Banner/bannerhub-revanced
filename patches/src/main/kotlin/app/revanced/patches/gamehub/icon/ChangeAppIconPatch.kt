package app.revanced.patches.gamehub.icon

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Replaces the GameHub launcher icon foreground (currently a vector at
// res/drawable/ic_launcher_foreground.xml) with the BannerHub raster icon,
// and rebrands res/drawable-xxhdpi/wine_logo.png too (used inside the app
// by ego.smali around line 1218 — probably a Wine-container header logo).
//
// The launcher icon background drawable (res/drawable/ic_launcher_background.xml)
// is intentionally left alone — most launchers mask the adaptive icon to a
// circle/squircle, so only the foreground content + a sliver of background
// at the edge are visible. The default GameHub background is a neutral fill
// that the BannerHub foreground sits cleanly against.
//
// Source PNGs are staged at patches/src/main/resources/bannerhub-icon/
// during gradle build (no NDK or external generator needed — they're checked
// in alongside the patch source). CI bakes them into the .rvp; this patch
// reads them back via classloader at patch time.
//
// Sizing rationale:
//   - ic_launcher_foreground.png: 432×432 px. That's 108 dp at xxxhdpi,
//     which is the full adaptive-icon foreground canvas size per Android's
//     "Designing Adaptive Icons" guide. The visible/safe area is the inner
//     72 dp circle (288 px at xxxhdpi); the BannerHub logo content is sized
//     to fit inside that circle, with the outer 18 dp on each side reserved
//     for launcher masking + parallax animations.
//   - wine_logo.png: 240×72 px, matching the original GameHub asset exactly.
//     The xxhdpi qualifier means Android treats it as 80×24 dp intrinsic;
//     keeping the dimensions identical to the original means any ImageView
//     using wrap_content measures the same size — no layout regressions.
//     The BannerHub icon is sized to 72×72 px (24 dp square) centered with
//     transparent padding on the left/right.
//
// Foreground delivery strategy:
//   The original ic_launcher_foreground is a vector XML in res/drawable/
//   (no density qualifier, which Android treats as the mdpi fallback).
//   Adding a PNG at res/drawable-xxxhdpi/ alone would produce mixed results
//   — modern devices pick the raster, mdpi/hdpi devices fall back to the
//   vector (= still the GameHub logo). So we DELETE the vector and ship
//   the raster at xxxhdpi only; Android downsamples for lower-density
//   devices, which is imperceptible at icon sizes.
// =========================================================================

private const val FOREGROUND_RESOURCE = "bannerhub-icon/ic_launcher_foreground.png"
private const val FOREGROUND_DEST     = "res/drawable-xxxhdpi/ic_launcher_foreground.png"
private const val OLD_FOREGROUND_XML  = "res/drawable/ic_launcher_foreground.xml"

private const val WINE_LOGO_RESOURCE  = "bannerhub-icon/wine_logo.png"
private const val WINE_LOGO_DEST      = "res/drawable-xxhdpi/wine_logo.png"

// Sentinel for classloader access — same trick as VibrationLibPatch. Avoids
// Kotlin's self-referential type-inference snag where the patch's type is
// being inferred at the same site we try to read its classloader.
private object IconResources

@Suppress("unused")
val changeAppIconPatch = resourcePatch(
    name = "Change app icon",
    description = "Replaces the GameHub launcher icon foreground with the " +
        "BannerHub icon and rebrands the in-app Wine logo. Deletes the " +
        "stock vector foreground in res/drawable/ so the new raster wins on " +
        "all device densities. Background drawable is left as-is; most " +
        "launchers mask the adaptive icon to a circle/squircle so only the " +
        "foreground content shows.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = IconResources::class.java.classLoader
            ?: error("classloader unavailable for icon resources")

        // ---- Launcher foreground ---------------------------------------------
        classLoader.getResourceAsStream(FOREGROUND_RESOURCE)?.use { input ->
            val dest = get(FOREGROUND_DEST)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("missing $FOREGROUND_RESOURCE in patch bundle resources")

        // Delete the stock vector. Two definitions for the same resource ID
        // (vector in drawable/, raster in drawable-xxxhdpi/) would split the
        // device-density resolution: aapt2 keeps both, and lower-density
        // devices fall back to the vector (= GameHub logo). Removing the
        // vector forces every density bucket to use the xxxhdpi raster,
        // downsampling as needed.
        val oldVector = get(OLD_FOREGROUND_XML)
        if (oldVector.exists()) {
            oldVector.delete()
        }

        // ---- In-app Wine logo ------------------------------------------------
        // Overwrites the original 240×72 wine_logo.png. Dimensions match,
        // aspect ratio matches, so any ImageView measuring wrap_content
        // against the resource keeps its existing layout.
        classLoader.getResourceAsStream(WINE_LOGO_RESOURCE)?.use { input ->
            val dest = get(WINE_LOGO_DEST)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("missing $WINE_LOGO_RESOURCE in patch bundle resources")
    }
}
