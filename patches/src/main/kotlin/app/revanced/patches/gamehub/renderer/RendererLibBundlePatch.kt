package app.revanced.patches.gamehub.renderer

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import java.io.File

// =========================================================================
// Additive bundling of the 6.0.2 GLES2-era libxserver.so + libwinemu.so.
//
// The full 6.0.2 pair is required: xserver-only crashed ~40 s into a Legacy
// launch (missing the 6.0.2 compositor); the full pair is device-confirmed
// (GoW, 2026-05-18).
//
// This patch is ADDITIVE: it writes each 6.0.2 binary as
// `lib<name>_legacy.so` ALONGSIDE the stock 6.0.4 `lib<name>.so`. The stock
// libs are never touched, so New mode is provably bit-identical to upstream.
// BhRendererController.loadXserver / loadWinemu pick between them at load
// time per the launching game's renderer pref.
//
// Bundled binary md5s:
//   libxserver_legacy.so  e8eb894825da66cca0fc59b242ac0ad5 (verified 6.0.2)
//   libwinemu_legacy.so   407f274d998335dbce03b2074a187e9f (verified 6.0.2)
// =========================================================================

private const val RES_DIR = "/legacyrenderer"
private const val ABI_DIR = "lib/arm64-v8a"

// Bundle BOTH 6.0.2 libs (the proven pair). Each maps stock <name>.so ->
// bundled <name>_legacy.so, additive (stock never overwritten → New mode
// provably bit-identical).
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
    // GATED OUT of 6.0.7: pinned to 6.0.4 so the patcher SKIPS it (version-
    // incompatible, not a SEVERE failure). The Legacy GLES2 path swaps in the
    // 6.0.2 libxserver, whose JNI_OnLoad RegisterNatives needs XServer methods
    // 6.0.7 deleted (setSurfaceFormat/setFlipEnabled) -> SIGABRT at <clinit>
    // (device-confirmed on DOOMBLADE, 2026-06-06). 6.0.7 grew XServer 11->40
    // natives (ReShade FX engine), so the old .so cannot satisfy the contract;
    // not patchable without a source-built GLES2 libxserver. New mode = stock,
    // unaffected. Revive only with a 6.0.7-contract GLES2 libxserver.
    compatibleWith(GAMEHUB_PACKAGE("6.0.4"))

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
