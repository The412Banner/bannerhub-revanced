package app.revanced.patches.gamehub.gameid

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Mirrors VibrationMenuLabelPatch — appends a "bh_gameid_label" string entry
// to features.home Compose Multiplatform resources so the library-list popup
// row's Lell-typed label can resolve to "Show Game ID".
//
// The actual runtime resolution is short-circuited by the shared resolver in
// BhMenuRowClick.maybeResolveCustomLabel (a small edit there adds the
// sentinel → label mapping). This CVR entry exists so a stricter resolver
// path (or any code that hits the resource lookup before the head-block
// fires) doesn't choke on the unknown key.
// =========================================================================

private const val LABEL_KEY = "bh_gameid_label"
private const val LABEL_VALUE = "Show Game ID"
// Base64 of "Show Game ID"
private const val LABEL_B64 = "U2hvdyBHYW1lIElE"

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
val gameIdDisplayMenuLabelPatch = resourcePatch(
    name = "Show Game ID label resource",
    description = "Appends a 'bh_gameid_label' = 'Show Game ID' string entry " +
        "to features.home Compose Multiplatform resources so the library-list " +
        "popup row's Lell-typed label has a registered key. Runtime resolution " +
        "is handled by the shared resolver hook in BhMenuRowClick.",
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
