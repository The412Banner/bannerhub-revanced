package app.revanced.patches.gamehub.misc.lite

import app.revanced.patcher.extensions.addInstruction
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =============================================================================
// "BannerHub V6 Lite" — Tier 3: drop Haima cloud gaming.
//
// Cloud gaming (Haima HMCP SDK) is feature bloat for a local-emulation build
// and almost certainly non-functional under BannerHub's catalog redirect
// anyway (it talks to XiaoJi cloud servers). It pulls in ~21.5 MB of
// near-incompressible payload:
//
//   lib/arm64-v8a/libhaima_rtc_so.so      ~7.9 MB  (Haima WebRTC native)
//   lib/arm64-v8a/libIjkffmpeg_haima.so   ~1.82 MB (cloud video stream)
//   lib/arm64-v8a/libIjksdl_haima.so      ~0.44 MB
//   lib/arm64-v8a/libIjkplayer_haima.so   ~0.43 MB
//   assets/composeResources/com.xiaoji.egggame.features.cloud/  ~10.9 MB
//                                          (33 files, ~all in the 3 big PNGs
//                                           idle_bg / peak_bg / full_bg)
//
// Unlike the Tier 1 dead font, these are LIVE — the cloud feature loads them.
// A bare delete would risk UnsatisfiedLinkError if any cloud entry point is
// reached. So we neutralise the two SDK native load sites FIRST (same
// stub-then-strip discipline as the libpns / Mob patches), then strip.
//
// Layer B — SDK load-site stubs (anchored on stable, non-R8-mangled SDK class
// names, so they survive base bumps better than the XiaoJi letter classes):
//
//  * tv.haima.ijk.media.player.IjkMediaPlayer.loadLibrariesOnce(IjkLibLoader)V
//    — invokes IjkLibLoader.loadLibrary for Ijkffmpeg_haima / Ijksdl_haima /
//      Ijkplayer_haima. Body replaced with an immediate return-void (it is
//      synchronized; returning before monitor-enter is valid — no monitor is
//      acquired so none needs releasing).
//
//  * org.hmwebrtc.NativeLibrary$DefaultLoader.load(String)Z — the real
//    System.loadLibrary("haima_rtc_so") site. The method already has a
//    try/catch returning false (0) on UnsatisfiedLinkError, i.e. the SDK
//    already tolerates a missing lib HERE. We short-circuit to that exact
//    designed outcome: return false immediately, never calling loadLibrary.
//
// Layer C — strip the 4 libs + the whole features.cloud Compose asset tree.
// =============================================================================

private const val IJK_LOADER_CLASS =
    "Ltv/haima/ijk/media/player/IjkMediaPlayer;"
private const val IJK_LOADER_METHOD = "loadLibrariesOnce"
private const val WEBRTC_LOADER_CLASS =
    "Lorg/hmwebrtc/NativeLibrary\$DefaultLoader;"
private const val WEBRTC_LOADER_METHOD = "load"

private const val CLOUD_ASSET_DIR =
    "assets/composeResources/com.xiaoji.egggame.features.cloud"

private val haimaLibNames = listOf(
    "libhaima_rtc_so.so",
    "libIjkffmpeg_haima.so",
    "libIjksdl_haima.so",
    "libIjkplayer_haima.so",
)

private val stripCloudGamingResourcePatch = resourcePatch {
    apply {
        // 4 Haima native libs, per ABI.
        val libDir = get("lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    haimaLibNames.forEach { lib ->
                        if (archDir.resolve(lib).exists()) {
                            delete("lib/${archDir.name}/$lib")
                        }
                    }
                }
            }
        }

        // The entire features.cloud Compose asset module.
        val cloudDir = get(CLOUD_ASSET_DIR)
        if (cloudDir.exists() && cloudDir.isDirectory) {
            cloudDir.walkTopDown()
                .filter { it.isFile }
                .map { "$CLOUD_ASSET_DIR/${it.relativeTo(cloudDir).path}" }
                .toList()
                .forEach { rel -> delete(rel) }
        }
    }
}

@Suppress("unused")
val stripCloudGamingPatch = bytecodePatch(
    name = "Strip cloud gaming",
    description = "Removes the Haima cloud-gaming stack (~21.5 MB): the 4 Haima " +
        "WebRTC/IJK native libs and the features.cloud Compose asset module. " +
        "Stubs the IjkMediaPlayer + hmwebrtc native load sites first so the " +
        "strip cannot crash if a cloud entry point is reached. Cloud gaming is " +
        "non-functional under the BannerHub catalog redirect anyway.",
    // Ported to gamehub-607-build (applies by default; was opt-in `use = false`
    // on the Lite-variant branch). Cloud gaming is dead weight under the catalog
    // redirect, so the full build strips it too (~21.5 MB + removes Haima CN SDK).
    use = true,
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(stripCloudGamingResourcePatch)

    apply {
        // IjkMediaPlayer.loadLibrariesOnce(IjkLibLoader)V -> no-op.
        firstMethod {
            definingClass == IJK_LOADER_CLASS &&
                name == IJK_LOADER_METHOD
        }.apply {
            addInstruction(0, "return-void")
        }

        // NativeLibrary$DefaultLoader.load(String)Z -> return false (the SDK's
        // own already-handled "native lib unavailable" outcome).
        firstMethod {
            definingClass == WEBRTC_LOADER_CLASS &&
                name == WEBRTC_LOADER_METHOD
        }.apply {
            addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }
    }
}
