package app.revanced.patches.gamehub.vibration

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =========================================================================
// Adds a custom "PC Vibration Settings" string entry to the features.home
// Compose Multiplatform resource bundle. Needed because the library-tile
// popup's row data class (Lz4e) requires a label of type Lell (a string-
// resource descriptor), not a raw String. Lxd3.l1 then resolves Lell ->
// String at render time by looking up the key in the loaded resources.
//
// Without this entry, Lxd3.l1 would fail to resolve our custom key and
// the row would either render empty or crash.
//
// CVR file format (confirmed: ASCII text, pipe-delimited):
//   version:0
//   string|<key>|<base64-encoded-utf8-value>
//   ...
//
// Just append our line to each locale file. Same English text everywhere
// — translation is post-stable polish.
// =========================================================================

private const val LABEL_KEY = "bh_pc_vibration_label"
private const val LABEL_VALUE = "PC Vibration Settings"
// Base64 of "PC Vibration Settings"
private const val LABEL_B64 = "UEMgVmlicmF0aW9uIFNldHRpbmdz"

private const val CVR_DIR = "assets/composeResources/com.xiaoji.egggame.features.home"

// Locales present in 6.0.4. The default (`values/`) is the always-loaded
// fallback; the per-locale files override it on matching devices. Adding
// the entry to every locale prevents the entry from missing when a user
// runs in zh/ja/etc.
private val CVR_LOCALES = listOf(
    "values",
    "values-en",
    "values-zh-rCN",
    "values-ja-rJP",
    "values-pt-rBR",
    "values-ru-rRU",
)

private object MenuLabelResources

@Suppress("unused")
val vibrationMenuLabelPatch = resourcePatch(
    name = "PC Vibration Settings label resource",
    description = "Appends a 'bh_pc_vibration_label' = 'PC Vibration Settings' " +
        "string entry to features.home Compose Multiplatform resources so the " +
        "library-tile popup row's Lell-typed label can resolve to our text.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val newLine = "string|$LABEL_KEY|$LABEL_B64\n"

        for (locale in CVR_LOCALES) {
            val path = "$CVR_DIR/$locale/strings.commonMain.cvr"
            val file = get(path)
            if (!file.exists()) continue  // locale not present in this APK

            val existing = file.readText()
            if (existing.contains("|$LABEL_KEY|")) continue  // already added

            // Append; preserve existing newline-terminated structure.
            val terminator = if (existing.endsWith("\n")) "" else "\n"
            file.writeText(existing + terminator + newLine)
        }
    }
}
