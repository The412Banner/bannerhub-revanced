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
private const val LEGACY_SO = "libxserver_legacy.so"

@Suppress("unused")
val rendererLibBundlePatch = resourcePatch(
    name = "Legacy renderer libxserver bundle",
    description = "Bundles the 6.0.2 GLES2-era libxserver.so as " +
        "libxserver_legacy.so alongside the stock 6.0.4 one (additive, " +
        "never overwrites stock). The conditional loader chooses per game.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val bundled = object {}.javaClass.getResourceAsStream("$RES_DIR/$LEGACY_SO")
            ?.use { it.readBytes() }
            ?: throw PatchException(
                "Bundled 6.0.2 $LEGACY_SO not found at $RES_DIR/$LEGACY_SO in patch resources.",
            )

        // Anchor on the stock lib so we land in the right (existing) ABI dir
        // and never have to create one; assert it stays untouched.
        val stock: File = get("$ABI_DIR/libxserver.so")
        if (!stock.isFile) {
            throw PatchException(
                "Expected stock $ABI_DIR/libxserver.so not found — base APK layout changed.",
            )
        }

        val target = File(stock.parentFile, LEGACY_SO)
        target.writeBytes(bundled)
    }
}
