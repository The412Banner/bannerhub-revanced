package app.revanced.patches.gamehub.pcengine

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// pcengine signature bypass — GATE 2 of 2 (the pcengine MANAGER's own check).
//
// Independent of the com/combo framework's ValidationStrategy (GATE 1), the
// pcengine plugin manager (obfuscated `xy5`) runs its OWN belt-and-suspenders
// host-vs-plugin signing-cert comparison before it will load the plugin. That
// method:
//   - reads the host certs   via PackageManager.getPackageInfo(GET_SIGNING...)
//   - reads the plugin certs via PackageManager.getPackageArchiveInfo(file)
//   - returns null when they MATCH, or a mismatch message string otherwise:
//       "插件签名与宿主不一致 host=<hostSha256> plugin=<pluginSha256>"
// Its sole caller branches `if-eqz <result>` -> proceeds only when the result
// is null. THIS is the check that logs the on-device Verification failures in
// pc_engine_plugin_manager_journal.json once the host is re-signed.
//
// GATE 1 relaxing the framework strategy does NOT disable this one, so it must
// be neutralized separately. Force the method to return null unconditionally
// (`const/4 v0, 0x0 ; return-object v0`) at its head -> the caller always takes
// the "certs match" path -> the re-signed host accepts XiaoJi's genuine plugin.
//
// `xy5` is R8-obfuscated and renames every base bump, so the method is anchored
// structurally: the unique, never-localized mismatch string it builds. That
// string is globally unique to this one method in the APK (verified on 6.1.0),
// so it unambiguously identifies the target regardless of the class letter.
// The trailing " host=" is part of the const so re-derivation is trivial: if
// this ever fails to match, re-confirm the exact literal in the new base.
// =============================================================================

private const val MISMATCH_ANCHOR = "插件签名与宿主不一致 host="

@Suppress("unused")
val pcEnginePluginSignatureCheckPatch = bytecodePatch(
    name = "PC engine plugin — bypass manager signature check",
    description = "Neutralizes the pcengine plugin manager's own host-vs-plugin signing-certificate " +
        "comparison (separate from the com.combo ValidationStrategy gate) by forcing its check " +
        "method to report a match. Lets a re-signed BannerHub build load XiaoJi's genuine pcengine " +
        "plugin on GameHub 6.1.0+ instead of rejecting it with '插件签名与宿主不一致'. Anchored on the " +
        "unique mismatch string the method builds. Pairs with the ValidationStrategy patch (both " +
        "gates are required).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            returnType == "Ljava/lang/String;" &&
                implementation?.instructions?.any { instruction ->
                    (instruction as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference && it.string == MISMATCH_ANCHOR } == true
                } == true
        }.addInstructions(
            0,
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
    }
}
