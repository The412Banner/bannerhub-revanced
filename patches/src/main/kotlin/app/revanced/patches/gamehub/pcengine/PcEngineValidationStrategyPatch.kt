package app.revanced.patches.gamehub.pcengine

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// =============================================================================
// pcengine signature bypass — GATE 1 of 2 (the "combo" plugin framework).
//
// GameHub 6.1.0 moved the whole PC emulator out of the APK into a downloadable,
// hash-verified plugin (`com.xiaoji.egggame.plugin.pcengine`, ~22 MB) loaded via
// DexClassLoader into a `:pcengine` process. The `com/combo` plugin framework
// that installs it enforces a signing-cert policy through
//   com.combo.core.runtime.ValidationStrategy  { Insecure, Strict, UserGrant }
// XiaoJi ships `Strict`, so the framework's InstallerManager (`p6d`) does a
// host-vs-plugin `Set.containsAll(pluginCerts)` cert check and rejects the
// plugin when they differ.
//
// Because EVERY BannerHub build is re-signed with our own keystore, our host
// cert can never equal XiaoJi's plugin cert -> the plugin is rejected -> a
// patched 6.1.0 has NO PC emulation at all. (Device-proven: the stock host's
// pc_engine_plugin_manager_journal.json logs repeated Verification failures
// "插件签名与宿主不一致" once the host is re-signed.)
//
// The framework funnels every strategy read through the single accessor
//   com.combo.core.model.PluginFrameworkContext.getValidationStrategy()
// (PluginManager.getValidationStrategy() delegates to it). Force that accessor
// to return `Insecure` so the framework skips the cert match entirely.
//
// This is only HALF the fix: the pcengine manager (`xy5`) has its OWN, separate
// cert check that is NOT governed by ValidationStrategy — see
// PcEnginePluginSignatureCheckPatch (GATE 2). Both are required.
//
// Anchored on non-obfuscated `com/combo` class + method names (the framework is
// a vendored library and is not renamed by R8), plus the stable enum-constant
// reference `ValidationStrategy;->Insecure`.
// =============================================================================

private const val CONTEXT_CLASS = "Lcom/combo/core/model/PluginFrameworkContext;"
private const val STRATEGY_CLASS = "Lcom/combo/core/runtime/ValidationStrategy;"

@Suppress("unused")
val pcEngineValidationStrategyPatch = bytecodePatch(
    name = "PC engine plugin — force Insecure validation",
    description = "Forces the com.combo plugin framework's ValidationStrategy accessor to return " +
        "Insecure so the pcengine plugin's signing certificate is not required to match the " +
        "(re-signed) host. Without this, a re-signed BannerHub build rejects XiaoJi's genuine " +
        "plugin and PC emulation never loads on GameHub 6.1.0+. Pairs with the pcengine manager " +
        "signature-check bypass (both gates are required).",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            definingClass == CONTEXT_CLASS && name == "getValidationStrategy"
        }.addInstructions(
            0,
            """
                sget-object p0, $STRATEGY_CLASS->Insecure:$STRATEGY_CLASS
                return-object p0
            """,
        )
    }
}
