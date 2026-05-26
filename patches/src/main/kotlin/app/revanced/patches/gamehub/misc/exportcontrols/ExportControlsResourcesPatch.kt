package app.revanced.patches.gamehub.misc.exportcontrols

import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =============================================================================
// Relabels the two host buttons that drive the VJoy cloud-share UI so their
// text matches what we actually do under the hood (write/read a local file).
//
// Translation strategy follows the [[bannerhub-revanced-vibration-row]] /
// VibrationMenuLabelPatch precedent: append our overrides to the relevant
// Compose Multiplatform `.cvr` resource bundle. The shared resolver short-
// circuit at Lxd3;->l1 (injected once by VibrationMenuRowPatch) makes our
// sentinel keys resolve to our hardcoded strings at render time, sidestepping
// the "manifest/index registration required" failure mode the .cvr alone hits.
//
// Two relabels are needed:
//   - The "Share" / "Share layout" / "上传至云端" button on the VJoy edit
//     screen → "Export to file".
//   - The "Apply share code" / "应用分享码" button on the VJoy main /
//     bottom-sheet → "Import from file".
//
// IMPORTANT — both relabels assume the host's button label is loaded via the
// standard Compose-resource pattern (`sget Lwhl;->?:Lxrl;` or similar →
// resolver Lxd3;->l1 → String). If the share UI uses a string literal const-
// string instead, this patch is a no-op for that label and the bytecode-side
// hook still works (the user just sees the original Chinese/English text).
// In that case, fall back to a string-replace pass over const-string
// instructions in the VJoy share Composable — TODO if needed once we have
// the 6.0.4 smali on disk.
// =============================================================================

// Compose Multiplatform resource module for the VJoy feature.
//
// 600 master map §4.6 confirms the bundle exists at
//   composeResources/com.xiaoji.egggame.features.vjoy/files/...
// but the *string* resources may actually live in a sibling module
// (`common.vjoy` for shared strings, or `features.winemu` if the share
// UI was packaged with the Wine emu feature). Check all three on first run;
// the patch tolerates a missing path (continue-on-no-file) so adding extras
// is safe.
private val CVR_DIRS = listOf(
    "assets/composeResources/com.xiaoji.egggame.features.vjoy",
    "assets/composeResources/com.xiaoji.egggame.common.vjoy",
    "assets/composeResources/com.xiaoji.egggame.features.winemu",
)

private val CVR_LOCALES = listOf(
    "values",
    "values-en",
    "values-zh-rCN",
    "values-ja-rJP",
    "values-pt-rBR",
    "values-ru-rRU",
)

// Sentinel keys used by the bytecode patch when it rewrites the host's
// label-load sgets. These must match the keys handled in
// BhMenuRowClick.maybeResolveCustomLabel (extended by this patch — see note).
private const val EXPORT_LABEL_KEY = "bh_vjoy_export_label"
private const val IMPORT_LABEL_KEY = "bh_vjoy_import_label"

// English defaults. Translation is post-stable polish — same English text
// per locale for now (mirrors VibrationMenuLabelPatch behavior).
private const val EXPORT_LABEL_VALUE = "Export to file"
private const val IMPORT_LABEL_VALUE = "Import from file"

// Pre-computed Base64 of the UTF-8 string values. The .cvr line format is:
//   string|<key>|<base64-of-utf8-value>
//
// "Export to file" → "RXhwb3J0IHRvIGZpbGU="
// "Import from file" → "SW1wb3J0IGZyb20gZmlsZQ=="
private const val EXPORT_LABEL_B64 = "RXhwb3J0IHRvIGZpbGU="
private const val IMPORT_LABEL_B64 = "SW1wb3J0IGZyb20gZmlsZQ=="

@Suppress("unused")
val exportControlsResourcesPatch = resourcePatch(
    name = "VJoy export/import label resources",
    description = "Adds 'bh_vjoy_export_label' and 'bh_vjoy_import_label' " +
        "string entries to the VJoy feature's Compose Multiplatform resource " +
        "bundles. The bytecode patch (ExportControlsPatch) rewrites the host's " +
        "share-button and apply-share-code-button label-load sgets to point at " +
        "these keys; the shared Lxd3;->l1 resolver hook (injected by the " +
        "vibration patch family) makes them resolve to 'Export to file' / " +
        "'Import from file' at render time.\n\n" +
        "NOTE: this patch must ALSO extend BhMenuRowClick.maybeResolveCustomLabel " +
        "(in extensions/gamehub) to recognize the two new sentinel keys — that " +
        "switch table is the single point of truth for ALL BannerHub label " +
        "overrides per the gpuspoof-ANR precedent. See the [[bannerhub-revanced-" +
        "label-resolver]] feedback note.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        val newLines = listOf(
            "string|$EXPORT_LABEL_KEY|$EXPORT_LABEL_B64",
            "string|$IMPORT_LABEL_KEY|$IMPORT_LABEL_B64",
        )

        for (cvrDir in CVR_DIRS) {
            for (locale in CVR_LOCALES) {
                val path = "$cvrDir/$locale/strings.commonMain.cvr"
                val file = get(path)
                if (!file.exists()) continue // bundle/locale not present here

                val existing = file.readText()
                val toAppend = newLines.filter { line ->
                    val key = line.substringAfter("string|").substringBefore('|')
                    !existing.contains("|$key|")
                }
                if (toAppend.isEmpty()) continue

                val terminator = if (existing.endsWith("\n")) "" else "\n"
                file.writeText(existing + terminator + toAppend.joinToString("\n") + "\n")
            }
        }
    }
}
