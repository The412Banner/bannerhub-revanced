package app.revanced.patches.gamehub.bannertools

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Ships the 4 vector drawables (Vibration / GPU Spoof / Renderer / Game ID)
// rendered inside the Banner Tools dialog's 1×4 tile row. Sources live at
// patches/src/main/resources/banner-tools/bh_bt_*.xml — 56dp Material 3
// surface-container tiles, self-contained dark backgrounds (no theme tint).
//
// New res/drawable entries (resource IDs assigned by apktool/aapt2 at
// reassembly time); the runtime handler resolves them via
// Resources.getIdentifier("bh_bt_*", "drawable", pkgName) because the
// compile-time R class belongs to the foreign GameHub package.
//
// SVG → vector-drawable note: VectorDrawable does not support
// stroke-dasharray, so the GPU Spoof chip outline is shipped as a solid
// stroke instead of the dashed mockup variant. Other shapes mechanically
// round-trip (rect → rounded-rect path, circle → 2-arc path).
// =========================================================================

private const val DRAWABLE_DIR = "res/drawable"

private val DRAWABLE_NAMES = listOf(
    "bh_bt_vibration",
    // bh_bt_gpu_spoof dropped on 6.0.7 (base app has native GPU spoof)
    "bh_bt_renderer",
    "bh_bt_game_id",
    "bh_bt_audio",
    "bh_bt_gog",
    "bh_bt_overlay",
    "bh_bt_root",
    "bh_bt_steam_chat",
)

// Sentinel for classloader access — same trick as ChangeAppIconPatch's
// IconResources object. Avoids the self-referential type-inference snag
// where the patch's type is being inferred at the same site we'd read
// its classloader.
private object BannerToolsDrawableResources

@Suppress("unused")
val bannerToolsDrawablesPatch = resourcePatch(
    name = "Banner Tools drawables",
    description = "Adds the bh_bt_* vector drawables (vibration, gpu_spoof, " +
        "renderer, game_id, audio, gog, overlay, root) to res/drawable. " +
        "Rendered by the Banner Tools dialog's tile grid.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val classLoader = BannerToolsDrawableResources::class.java.classLoader
            ?: error("classloader unavailable for banner-tools drawables")

        for (name in DRAWABLE_NAMES) {
            val resource = "banner-tools/$name.xml"
            val dest = "$DRAWABLE_DIR/$name.xml"
            classLoader.getResourceAsStream(resource)?.use { input ->
                val destFile = get(dest)
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { input.copyTo(it) }
            } ?: error("missing $resource in patch bundle resources")
        }
    }
}
