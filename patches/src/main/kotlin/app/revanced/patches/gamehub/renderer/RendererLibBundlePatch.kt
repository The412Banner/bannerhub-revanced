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
// `lib<name>_legacy.so` ALONGSIDE the stock `lib<name>.so`. The stock libs are
// never touched, so New mode is provably bit-identical to upstream.
// BhRendererController.loadXserver / loadWinemu pick between them at load
// time per the launching game's renderer pref.
//
// 6.0.8 also bundles the WRAPPER `libxserver_shim.so` (built from
// native/xserver_shim/). On 6.0.8 the raw 6.0.2 libxserver can't load against
// the rewritten 40-method XServer; loadXserver loads this wrapper instead, and
// the wrapper dlopens libxserver_legacy.so under the new contract.
//
// Bundled binary md5s:
//   libxserver_legacy.so  e8eb894825da66cca0fc59b242ac0ad5 (verified 6.0.2)
//   libwinemu_legacy.so   407f274d998335dbce03b2074a187e9f (verified 6.0.2)
//   libxserver_shim.so    built from native/xserver_shim/ (arm64-v8a)
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

// Standalone wrapper libs (no stock counterpart) written into the same ABI dir.
// Anchored on libxserver.so just to locate the (existing) arm64-v8a directory.
private val EXTRA_LIBS = listOf("libxserver_shim.so")

@Suppress("unused")
val rendererLibBundlePatch = resourcePatch(
    name = "Legacy renderer libxserver bundle",
    description = "Bundles the 6.0.2 GLES2-era libxserver.so + libwinemu.so " +
        "as *_legacy.so alongside the stock 6.0.4 ones (additive, never " +
        "overwrites stock). The conditional loaders choose per game.",
) {
    // 6.0.8: the wrapper shim makes the legacy pair loadable against the
    // rewritten XServer, so this is no longer pinned to 6.0.4.
    compatibleWith(GAMEHUB_PACKAGE("6.0.8"))

    apply {
        // Anchor on the stock libxserver.so to locate the (existing) ABI dir.
        val anchor: File = get("$ABI_DIR/libxserver.so")
        if (!anchor.isFile) {
            throw PatchException(
                "Expected stock $ABI_DIR/libxserver.so not found — base APK layout changed.",
            )
        }
        val abiDir = anchor.parentFile

        fun stage(resName: String, outName: String) {
            val bytes = object {}.javaClass.getResourceAsStream("$RES_DIR/$resName")
                ?.use { it.readBytes() }
                ?: throw PatchException(
                    "Bundled $resName not found at $RES_DIR/$resName in patch resources.",
                )
            File(abiDir, outName).writeBytes(bytes)
        }

        // The 6.0.2 pair, additive (stock <name>.so never overwritten).
        LEGACY_LIBS.forEach { (stockName, legacyName) ->
            if (!File(abiDir, stockName).isFile) {
                throw PatchException(
                    "Expected stock $ABI_DIR/$stockName not found — base APK layout changed.",
                )
            }
            stage(legacyName, legacyName)
        }

        // The wrapper shim (loaded in place of stock libxserver in Legacy mode).
        EXTRA_LIBS.forEach { stage(it, it) }
    }
}
