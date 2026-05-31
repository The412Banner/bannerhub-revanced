package app.revanced.patches.gamehub.explore

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Bakes the Explore manifest into the APK as assets/bh_explore.json so the
// BannerHub-owned Explore screen renders our shipped rails offline on a fresh
// install — WITHOUT waiting for the live release asset
// (releases/latest/download/bh_explore.json), which only updates on a STABLE
// cut.
//
// BhExploreManifest.load() reads, in order: external override → newer-of
// {baked asset, network cache} (by root "build") → BUNDLED_JSON. Baking the
// asset (stamped with THIS build's "build" via stamp_version.py) means a newer
// build's rails win over an older cached stable, so freshly-shipped Explore
// content (e.g. the Banner Tools card) appears immediately rather than being
// shadowed by a previously-fetched older manifest.
//
// Source: patches/src/main/resources/explore/bh_explore.json — kept in sync
// with the canonical explore/bh_explore.json by stamp_version.py (run before
// the gradle patch build). Mirrors ExploreVersionAssetPatch's classloader copy.
// =========================================================================

private const val MANIFEST_SRC = "explore/bh_explore.json"
private const val MANIFEST_DEST = "assets/bh_explore.json"

// Sentinel for classloader access — same trick as ExploreVersionAssetPatch.
private object ExploreManifestResources

@Suppress("unused")
val exploreManifestAssetPatch = resourcePatch(
    name = "Explore manifest asset",
    description = "Bundles the Explore manifest (assets/bh_explore.json) so the " +
        "Explore screen shows BannerHub's shipped rails offline on a fresh " +
        "install, instead of falling back to an older cached release manifest.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = ExploreManifestResources::class.java.classLoader
            ?: error("classloader unavailable for explore manifest asset")

        classLoader.getResourceAsStream(MANIFEST_SRC)?.use { input ->
            val dest = get(MANIFEST_DEST)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("missing $MANIFEST_SRC in patch bundle resources")
    }
}
