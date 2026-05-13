package app.revanced.patches.gamehub.icon

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Replaces five drawables in the staged APK with BannerHub branding:
//
//   1. Launcher adaptive-icon foreground — original is a vector XML at
//      res/drawable/ic_launcher_foreground.xml. We ship a 432×432 raster
//      at res/drawable-xxxhdpi/ and DELETE the vector so the new raster
//      wins on every device density (Android downsamples for lower
//      buckets — imperceptible at icon sizes).
//   2. In-app wine_logo — referenced from ego.smali around line 1218
//      (probably a Wine-container header logo). Overwritten at the same
//      240×72 dimensions so any ImageView using wrap_content keeps its
//      intrinsic measurement.
//   3. Auth screen "landscape" logo — Compose-multiplatform resource at
//      assets/composeResources/.../drawable/features_auth_ic_logo_landscape.png.
//      Despite the name, the original is a 96×96 square (the "landscape"
//      probably refers to the auth screen orientation).
//   4. Auth screen "overseas" logo — Compose-multiplatform resource at
//      .../features_auth_ic_logo_overseas.png, 366×72 wide rectangle.
//   5. Splash-screen logo — Compose-multiplatform resource at
//      assets/composeResources/com.xiaoji.egggame.features.splash/drawable/
//      splash_logo.png, 996×200 wide banner. Same overseas-banner artwork
//      sized for the splash slot.
//
// The launcher icon background (res/drawable/ic_launcher_background.xml),
// the CN-locale auth logo (features_auth_ic_logo_cn.png), and the
// CN-locale splash logo (drawable-zh-rCN/splash_logo.png) are left alone —
// backgrounds are mostly masked away by launcher shapes, and the CN
// drawables aren't shown on overseas builds.
//
// Source PNGs are staged at patches/src/main/resources/bannerhub-icon/
// during gradle build (no NDK or external generator needed — they're
// checked in alongside the patch source). CI bakes them into the .rvp;
// this patch reads them back via classloader at patch time.
//
// Sizing rationale (per drawable):
//   - ic_launcher_foreground.png: 432×432 px = 108 dp at xxxhdpi, full
//     adaptive-icon foreground canvas. BannerHub logo content fits inside
//     the inner 72 dp safe-zone circle (288 px at xxxhdpi); outer 18 dp
//     on each side reserved for launcher masking + parallax animations.
//   - wine_logo.png: 240×72 px, matches stock exactly. xxhdpi qualifier
//     means 80×24 dp intrinsic. BannerHub icon at 72×72 px (24 dp square)
//     centered with transparent left/right padding.
//   - features_auth_ic_logo_landscape.png: 96×96 px, matches stock
//     exactly. BannerHub icon scaled to fit with transparent padding so
//     the aspect ratio isn't distorted.
//   - features_auth_ic_logo_overseas.png: 366×72 px (5.08:1 aspect),
//     matches stock exactly. Source provided by user (2277×448 RGB, same
//     5.08:1 aspect) downscaled directly with no padding. Note: stock
//     was RGBA, replacement is RGB — if the auth-screen background
//     doesn't match the new logo's edges, the logo will show as a
//     rectangle. The auth screen's solid background in GameHub means
//     this is usually fine.
//   - splash_logo.png: 996×200 px (4.98:1 aspect), matches stock exactly.
//     Same overseas-banner source (2277×448, 5.08:1). The 2% aspect
//     mismatch is resolved by resizing to 996×196 and padding 2 px of
//     transparency top+bottom; output is RGBA so future splash background
//     changes can bleed through. ImageMagick produces RGBA automatically
//     when an RGB input is -extent'd with a transparent background.
// =========================================================================

private const val FOREGROUND_RESOURCE = "bannerhub-icon/ic_launcher_foreground.png"
private const val FOREGROUND_DEST     = "res/drawable-xxxhdpi/ic_launcher_foreground.png"
private const val OLD_FOREGROUND_XML  = "res/drawable/ic_launcher_foreground.xml"

private const val WINE_LOGO_RESOURCE  = "bannerhub-icon/wine_logo.png"
private const val WINE_LOGO_DEST      = "res/drawable-xxhdpi/wine_logo.png"

// Compose Multiplatform resource paths. The auth-feature module ships its
// drawables under assets/composeResources/<module>/drawable/ instead of the
// normal res/ tree — Compose's resource loader reads them by string path
// rather than R.drawable ID, so simple byte-replace is enough; no aapt2
// or resource-ID juggling required.
private const val AUTH_DRAWABLE_PREFIX =
    "assets/composeResources/com.xiaoji.egggame.features.auth/drawable"

private const val AUTH_LANDSCAPE_RESOURCE = "bannerhub-icon/features_auth_ic_logo_landscape.png"
private const val AUTH_LANDSCAPE_DEST     = "$AUTH_DRAWABLE_PREFIX/features_auth_ic_logo_landscape.png"

private const val AUTH_OVERSEAS_RESOURCE  = "bannerhub-icon/features_auth_ic_logo_overseas.png"
private const val AUTH_OVERSEAS_DEST      = "$AUTH_DRAWABLE_PREFIX/features_auth_ic_logo_overseas.png"

// Splash module ships under its own Compose resource namespace. Same byte-
// replace approach as the auth drawables; no res/ or aapt2 work needed.
// We deliberately overwrite ONLY the default-locale splash_logo and leave
// drawable-zh-rCN/splash_logo.png alone — the CN locale keeps its Chinese
// branding, same policy as features_auth_ic_logo_cn.png.
private const val SPLASH_LOGO_RESOURCE = "bannerhub-icon/splash_logo.png"
private const val SPLASH_LOGO_DEST     =
    "assets/composeResources/com.xiaoji.egggame.features.splash/drawable/splash_logo.png"

// Sentinel for classloader access — same trick as VibrationLibPatch. Avoids
// Kotlin's self-referential type-inference snag where the patch's type is
// being inferred at the same site we try to read its classloader.
private object IconResources

@Suppress("unused")
val changeAppIconPatch = resourcePatch(
    name = "Change app icon",
    description = "Replaces five drawables in the patched APK with BannerHub " +
        "branding: the launcher adaptive-icon foreground (deleting the stock " +
        "vector so the new raster wins on every density), the in-app Wine " +
        "logo, the auth-screen landscape + overseas logos, and the splash " +
        "screen banner — the last three are shipped as Compose Multiplatform " +
        "resources under assets/composeResources/. Background drawable and " +
        "CN-locale variants are left as-is.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = IconResources::class.java.classLoader
            ?: error("classloader unavailable for icon resources")

        fun copy(resource: String, dest: String) {
            classLoader.getResourceAsStream(resource)?.use { input ->
                val destFile = get(dest)
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { input.copyTo(it) }
            } ?: error("missing $resource in patch bundle resources")
        }

        // ---- Launcher foreground ---------------------------------------------
        copy(FOREGROUND_RESOURCE, FOREGROUND_DEST)

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
        copy(WINE_LOGO_RESOURCE, WINE_LOGO_DEST)

        // ---- Auth-screen "landscape" logo (Compose Multiplatform) -----------
        // 96×96 square. Replacement is the same dimensions; Compose layout
        // measurements remain identical.
        copy(AUTH_LANDSCAPE_RESOURCE, AUTH_LANDSCAPE_DEST)

        // ---- Auth-screen "overseas" logo (Compose Multiplatform) -------------
        // 366×72 wide rectangle (5.08:1 aspect). User-provided replacement
        // matches the aspect ratio exactly so a direct downscale preserves
        // proportions. The stock asset was RGBA; the replacement is RGB —
        // acceptable here because the auth screen's background is opaque
        // behind the logo.
        copy(AUTH_OVERSEAS_RESOURCE, AUTH_OVERSEAS_DEST)

        // ---- Splash-screen logo (Compose Multiplatform) ----------------------
        // 996×200 (4.98:1) — same overseas-banner artwork sized for the
        // splash slot. The user's source has a 5.08:1 aspect, so we resize
        // to 996×196 to preserve proportions exactly, then pad 2 px of
        // transparency top + bottom to reach the stock 996×200 canvas.
        // Output is RGBA; the transparent padding lets any future splash
        // background change (e.g. dark mode) bleed through cleanly.
        copy(SPLASH_LOGO_RESOURCE, SPLASH_LOGO_DEST)
    }
}
