package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import java.io.File

// =========================================================================
// Milestone 2 — additive bundling of the 6.0.2 GLES2-era libxserver.so.
//
// xserver-only-first scope (user decision 2026-05-18): only the renderer's
// libxserver is gated. libwinemu has 7 early clinit loaders and locks to the
// first-loaded copy for the whole process, so it stays stock; GoW rendered
// fine on the full pair so xserver-only is the cheap, ordering-proof first
// cut. Add libwinemu gating later only if a title proves it needs the 6.0.2
// ASurfaceTransaction plane compositor.
//
// This patch is ADDITIVE: it writes the 6.0.2 binary as
// `libxserver_legacy.so` ALONGSIDE the stock 6.0.4 `libxserver.so`. The
// stock lib is never touched, so New mode is provably bit-identical to
// upstream. BhRendererController.loadXserver picks between them at load
// time per the launching game's renderer pref.
//
// Bundled binary md5: e8eb894825da66cca0fc59b242ac0ad5 (verified 6.0.2).
// =========================================================================

private const val RES_DIR = "/legacyrenderer"
private const val ABI_DIR = "lib/arm64-v8a"

// FULL-PAIR (user decision 2026-05-18): bundle BOTH 6.0.2 libs (test3's
// proven pair). Each maps stock <name>.so -> bundled <name>_legacy.so,
// additive (stock never overwritten → New mode provably bit-identical).
//   libxserver_legacy.so  md5 e8eb894825da66cca0fc59b242ac0ad5
//   libwinemu_legacy.so   md5 407f274d998335dbce03b2074a187e9f
private val LEGACY_LIBS = mapOf(
    "libxserver.so" to "libxserver_legacy.so",
    "libwinemu.so" to "libwinemu_legacy.so",
)

@Suppress("unused")
val rendererLibBundlePatch = resourcePatch(
    name = "Legacy renderer libxserver bundle",
    description = "Bundles the 6.0.2 GLES2-era libxserver.so + libwinemu.so " +
        "as *_legacy.so alongside the stock 6.0.4 ones (additive, never " +
        "overwrites stock). The conditional loaders choose per game.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        LEGACY_LIBS.forEach { (stockName, legacyName) ->
            val bundled = object {}.javaClass.getResourceAsStream("$RES_DIR/$legacyName")
                ?.use { it.readBytes() }
                ?: throw PatchException(
                    "Bundled 6.0.2 $legacyName not found at $RES_DIR/$legacyName in patch resources.",
                )

            // Anchor on the stock lib so we land in the right (existing) ABI
            // dir and never have to create one; assert it stays untouched.
            val stock: File = get("$ABI_DIR/$stockName")
            if (!stock.isFile) {
                throw PatchException(
                    "Expected stock $ABI_DIR/$stockName not found — base APK layout changed.",
                )
            }

            File(stock.parentFile, legacyName).writeBytes(bundled)
        }
    }
}
