package app.revanced.patches.gamehub.misc.lite

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =============================================================================
// "BannerHub V6 Lite" — Tier 4: drop the AVIF/HEIC/HEIF image-codec stack.
//
// The 5 "codec" libs are NOT independent and have ZERO Java loadLibrary
// sites. They are the native backend of awxkee `avif-coil` — an AVIF/HEIC/
// HEIF decoder plugged into the Coil image loader:
//
//   libcoder.so   2.17 MB  System.loadLibrary("coder") from HeifCoder /
//                           AvifAnimatedDecoder; hard-NEEDEDs all 5 below
//   libheif.so    1.40 MB  (also NEEDEDs libx265 + libde265)
//   libaom.so     4.83 MB  AV1
//   libx265.so    1.96 MB  HEVC encode
//   libde265.so   1.62 MB  HEVC decode
//   libdav1d.so   0.71 MB  AV1 decode
//                ~12.7 MB total, near-incompressible -> ~that much on disk
//
// ELF NEEDED is resolved at dlopen, so a partial strip is impossible:
// deleting libheif while keeping libcoder => UnsatisfiedLinkError on
// loadLibrary("coder"). All 6 go together.
//
// Gate (single, clean, robust): both avif-coil Coil Decoder.Factory
// implementations expose `create(Ljfk;Lk3f;Lkda;)Lzx3;` (the Coil
// Decoder.Factory.create — method name retained; the avif-coil/radzivon
// classes are NOT R8-mangled so this anchor survives base bumps). Stub
// each to return null. Coil semantics: a factory returning null means
// "can't handle this source" -> Coil falls through to the next factory and
// ultimately its default BitmapFactory decoder, which on Android API 28+
// decodes HEIF/HEIC and API 31+ decodes AVIF via the *platform*. So:
//
//   * No avif-coil native code is ever reached (HeifCoder is used ONLY by
//     this path — verified), so libcoder + the 5 codecs become unreferenced
//     and the strip cannot UnsatisfiedLinkError anywhere (startup OR decode).
//   * Modern devices still render HEIF/HEIC/AVIF via the platform decoder.
//   * Old devices: those formats fail gracefully to the placeholder;
//     JPEG/PNG/WebP/GIF are unaffected on every device.
//
// First Lite tier with a user-visible functional cost — shipping as a
// prerelease for empirical device test of cover-art/avatar rendering.
// =============================================================================

private const val HEIF_FACTORY =
    "Lcom/github/awxkee/avifcoil/decoder/HeifDecoder\$Factory;"
private const val ANIMATED_AVIF_FACTORY =
    "Lcom/github/awxkee/avifcoil/decoder/animation/AnimatedAvifDecoder\$Factory;"
private const val COIL_FACTORY_CREATE = "create"

private val codecLibNames = listOf(
    "libcoder.so",
    "libheif.so",
    "libaom.so",
    "libx265.so",
    "libde265.so",
    "libdav1d.so",
)

private val stripImageCodecsResourcePatch = resourcePatch {
    apply {
        val libDir = get("lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { archDir ->
                if (archDir.isDirectory) {
                    codecLibNames.forEach { lib ->
                        if (archDir.resolve(lib).exists()) {
                            delete("lib/${archDir.name}/$lib")
                        }
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val stripImageCodecsPatch = bytecodePatch(
    name = "Strip AVIF/HEIC codecs",
    description = "Removes the avif-coil AVIF/HEIC/HEIF image-codec native stack " +
        "(libcoder + libheif/libaom/libx265/libde265/libdav1d, ~12.7 MB). The two " +
        "avif-coil Coil Decoder.Factory.create() methods are stubbed to return " +
        "null so Coil falls back to the Android platform decoder (which natively " +
        "handles HEIF/AVIF on modern Android); the avif-coil native path is then " +
        "unreachable and the libs strip safely. JPEG/PNG/WebP/GIF unaffected.",
    use = false,
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(stripImageCodecsResourcePatch)

    apply {
        listOf(HEIF_FACTORY, ANIMATED_AVIF_FACTORY).forEach { factoryClass ->
            firstMethod {
                definingClass == factoryClass &&
                    name == COIL_FACTORY_CREATE
            }.apply {
                addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
            }
        }
    }
}
