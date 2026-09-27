package app.revanced.patches.gamehub.gog

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import java.io.File

// =========================================================================
// Bundles Bannerlator's Rust GOG download engine (libblsteam.so) + its PEM
// trust bundle so BannerHub's GOG store can fetch depots natively.
//
// The .so is Bannerlator's `libblsteam.so` (Rust; GOG gen2 chunk engine +
// gen1 range stream mode, JNI-facing). Its JNI exports bind to
// `com.winlator.star.store.blsteam.BlGogDownload` — a class the host has no
// trace of, so the extension ships it (Java-only port of Bannerlator's Kotlin
// facade) and this patch ships the library next to it:
//
//   lib/arm64-v8a/libblsteam.so      ← patches resources /gogengine/libblsteam.so
//   assets/blsteam_cacert.pem        ← patches resources gogengine/blsteam_cacert.pem
//
// The .so also carries Bannerlator's Steam/Epic/Amazon JNI exports; those bind
// to classes that do not exist here and are simply never resolved (JNI symbol
// lookup is lazy, per-class, at first native call). Only the four
// `BlGogDownload_native{Probe,Start,Cancel,Release}` symbols are used.
//
// ADDITIVE: the stock libs are never touched. The lib is written next to a
// stock lib that is present in every 6.x base (libc++_shared.so) so we land in
// the existing ABI dir and never have to create one. The base APK is
// arm64-v8a-only with extractNativeLibs="true" (checked on 6.3.1), so no
// page-alignment / uncompressed-store concern for the added .so.
//
// Runtime gate: GogDownloadManager.useRustEngine reads the
// `use_rust_gog_engine` switch (bh_gog_prefs, default ON) and
// BlGogDownload.isAvailable() (System.loadLibrary + probe, cached, never
// throws). Either false → the existing Java download loop runs unchanged, so
// a packaging regression of this patch degrades, never crashes.
//
// Bundled binary:
//   libblsteam.so        4,798,088 B, sha256 68a1207e… (SONAME libblsteam.so;
//                        NEEDED libc / libdl / liblog only)
//   blsteam_cacert.pem   224,449 B (Mozilla-derived bundle; fallback when the
//                        system cacerts dirs are unreadable)
// =========================================================================

private const val RES_DIR = "/gogengine"
private const val ABI_DIR = "lib/arm64-v8a"
private const val ENGINE_LIB = "libblsteam.so"
/** Any stock lib present in every 6.x base — anchors the write into the existing ABI dir. */
private const val ANCHOR_LIB = "libc++_shared.so"

private const val CA_SRC = "gogengine/blsteam_cacert.pem"
private const val CA_DEST = "assets/blsteam_cacert.pem"

// Sentinel for classloader access — same trick as ExploreManifestAssetPatch.
private object GogEngineResources

@Suppress("unused")
val gogEngineLibBundlePatch = resourcePatch(
    name = "GOG Rust download engine bundle",
    description = "Bundles Bannerlator's native GOG download engine " +
        "(lib/arm64-v8a/libblsteam.so) and its TLS trust bundle " +
        "(assets/blsteam_cacert.pem). Additive — no stock lib is touched. " +
        "GogDownloadManager uses it when the 'Rust engine (fast)' switch is " +
        "on and the library loads; otherwise the Java loop runs unchanged.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = GogEngineResources::class.java.classLoader
            ?: throw PatchException("classloader unavailable for the GOG engine bundle")

        // ── native library ────────────────────────────────────────────────
        val bundled = classLoader.getResourceAsStream("${RES_DIR.trimStart('/')}/$ENGINE_LIB")
            ?.use { it.readBytes() }
            ?: throw PatchException(
                "Bundled $ENGINE_LIB not found at $RES_DIR/$ENGINE_LIB in patch resources.",
            )
        if (bundled.size < 1024 * 1024) {
            throw PatchException(
                "Bundled $ENGINE_LIB is only ${bundled.size} B — truncated resource?",
            )
        }

        // Anchor on a stock lib so we land in the right (existing) ABI dir and
        // never have to create one.
        val anchor: File = get("$ABI_DIR/$ANCHOR_LIB")
        if (!anchor.isFile) {
            throw PatchException(
                "Expected stock $ABI_DIR/$ANCHOR_LIB not found — base APK layout changed.",
            )
        }
        File(anchor.parentFile, ENGINE_LIB).writeBytes(bundled)

        // ── CA bundle asset ───────────────────────────────────────────────
        classLoader.getResourceAsStream(CA_SRC)?.use { input ->
            val dest = get(CA_DEST)
            dest.parentFile?.mkdirs()
            dest.outputStream().use { input.copyTo(it) }
        } ?: throw PatchException("missing $CA_SRC in patch bundle resources")
    }
}
