package app.revanced.patches.gamehub.explore

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Ships the build's own version into the APK as assets/bh_version.json so the
// Explore screen can show "installed vs latest" and an in-app update banner.
//
// The file is stamped at build time by explore/stamp_version.py (run BEFORE the
// gradle patch build, so this patch bundles the freshly-stamped copy). It holds
// {"version": "...", "build": <int>} — the INSTALLED version, read offline by
// BhExploreManifest.installedVersion(). The LATEST version comes from the remote
// bh_explore.json root (releases/latest/download), and the screen compares the
// two integers. Deliberately avoids getPackageInfo().versionName, which returns
// the host GameHub version rather than our BannerHub release tag.
//
// Mirrors ExploreDrawablesPatch's classloader copy mechanism.
// =========================================================================

private const val VERSION_SRC = "explore/bh_version.json"
private const val VERSION_DEST = "assets/bh_version.json"

// Sentinel for classloader access — same trick as ExploreDrawablesPatch.
private object ExploreVersionResources

@Suppress("unused")
val exploreVersionAssetPatch = resourcePatch(
    name = "Explore version stamp",
    description = "Bundles the build's own version (assets/bh_version.json) so " +
        "the Explore screen can compare it against the latest release and show " +
        "an in-app update banner.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = ExploreVersionResources::class.java.classLoader
            ?: error("classloader unavailable for explore version asset")

        classLoader.getResourceAsStream(VERSION_SRC)?.use { input ->
            val dest = get(VERSION_DEST)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
        } ?: error("missing $VERSION_SRC in patch bundle resources")
    }
}
