package app.revanced.patches.gamehub.gpuspoof

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Adds a "GPU Spoof" string entry to the features.home Compose Multiplatform
// resource bundle so the library-list popup row's Lell-typed label can
// resolve. Same mechanism as VibrationMenuLabelPatch — see that file for the
// CVR format rationale. (The resolver Lxd3.l1 is also short-circuited in
// GpuSpoofMenuRowPatch so this is belt-and-braces; appending here keeps the
// row well-formed even if the resolver path changes upstream.)
// =========================================================================

private const val LABEL_KEY = "bh_gpuspoof_label"
// Base64 of "GPU Spoof"
private const val LABEL_B64 = "R1BVIFNwb29m"

private const val CVR_DIR = "assets/composeResources/com.xiaoji.egggame.features.home"

private val CVR_LOCALES = listOf(
    "values",
    "values-en",
    "values-zh-rCN",
    "values-ja-rJP",
    "values-pt-rBR",
    "values-ru-rRU",
)

@Suppress("unused")
val gpuSpoofMenuLabelPatch = resourcePatch(
    name = "GPU Spoof label resource",
    description = "Appends a 'bh_gpuspoof_label' = 'GPU Spoof' string entry to " +
        "features.home Compose Multiplatform resources so the library-list " +
        "popup row's Lell-typed label can resolve to our text.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val newLine = "string|$LABEL_KEY|$LABEL_B64\n"

        for (locale in CVR_LOCALES) {
            val path = "$CVR_DIR/$locale/strings.commonMain.cvr"
            val file = get(path)
            if (!file.exists()) continue

            val existing = file.readText()
            if (existing.contains("|$LABEL_KEY|")) continue

            val terminator = if (existing.endsWith("\n")) "" else "\n"
            file.writeText(existing + terminator + newLine)
        }
    }
}
