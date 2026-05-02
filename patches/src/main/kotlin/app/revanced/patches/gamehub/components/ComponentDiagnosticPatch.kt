package app.revanced.patches.gamehub.components

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// Diagnostic instrumentation: log a unique tag at method entry of every
// candidate dropdown-feeder method. User opens the FEXCore picker once,
// pulls logcat, greps "GH600-DIAG" — whichever tags fired narrow down where
// the per-game dropdown actually pulls its options from in 6.0.
//
// Pattern matches 5.3.5's discovery method: instrument first, hook second.
// The ComponentInjectionPatch hook on Ll9o;->z and previous attempts on
// Lgxh;->a / Lm13;->b never produced logs in the field, so we don't yet
// know the actual feed.
private const val DIAG_DESCRIPTOR =
    "Lapp/revanced/extension/gamehub/components/ComponentDiag;"

private data class Target(
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val tag: String,
)

private val TARGETS = listOf(
    // --- Game config response data class — primary ctor builds the parcel
    //     that flows into per-game pickers. dh7.a / dh7.b are the two
    //     pre-fetched List<EnvLayerEntity> fields the dropdown likely reads.
    Target(
        "Ldh7;", "<init>",
        listOf(
            "Ljava/util/List;",
            "Ljava/util/List;",
            "Lcom/xiaoji/egggame/common/winemu/bean/EnvLayerEntity;",
            "Lcom/xiaoji/egggame/common/winemu/bean/EnvLayerEntity;",
            "Lcom/xiaoji/egggame/common/winemu/bean/TranslatorConfigs;",
            "Lcom/xiaoji/egggame/common/winemu/bean/PcEmuControllerEntity;",
            "I", "Ljava/lang/String;", "I", "Ljava/lang/String;",
            "I", "I", "I", "Ljava/lang/String;", "I",
        ),
        "dh7.<init>",
    ),
    // --- API caller for getGameConfigByScript (logs visible in WinEmuModule)
    Target("Ljg2;", "invoke", emptyList(), "jg2.invoke"),
    Target("Lehn;", "invoke", emptyList(), "ehn.invoke"),

    // --- Per-game-settings handlers that read dh7.a / dh7.b
    Target("Lb54;", "b", listOf("Lr91;", "Lfe3;"), "b54.b"),
    Target("Liv6;", "invoke", emptyList(), "iv6.invoke"),
    Target("Lhv6;", "e", listOf("Lor6;", "Lfe3;"), "hv6.e"),
    Target("Lq6f;", "c", listOf("Ldh7;", "Lfe3;"), "q6f.c"),
    Target("Lq6f;", "d", listOf("Ldh7;", "Lfe3;"), "q6f.d"),
    Target("Lnhn;", "f", listOf("Ljava/lang/String;", "I", "Lns7;", "Lfe3;"), "nhn.f"),

    // --- Composable picker (renders DialogSettingListItemEntity rows)
    Target(
        "Lv86;", "b",
        listOf(
            "Ljava/lang/String;", "Ljava/lang/String;", "Lns7;",
            "Z", "Z", "Z", "Ljava/lang/String;",
            "Lmr6;", "Lmr6;", "Lf53;", "I", "I",
        ),
        "v86.b",
    ),

    // --- Sanity checks: previous hook targets — confirm whether they fire
    Target(
        "Ll9o;", "z",
        listOf("Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"),
        "l9o.z",
    ),
    Target("Lm13;", "b", listOf("Lexh;"), "m13.b"),
    Target(
        "Lgxh;", "a",
        listOf("Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;", "Lfe3;"),
        "gxh.a",
    ),
)

@Suppress("unused")
val componentDiagnosticPatch = bytecodePatch(
    name = "Component diagnostic",
    description = "Logs GH600-DIAG entry tags from candidate dropdown-feeder methods so " +
        "we can identify the actual per-game-setting list source in 6.0 KMP.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        for (t in TARGETS) {
            try {
                firstMethod {
                    definingClass == t.className &&
                        name == t.methodName &&
                        parameterTypes.size == t.parameterTypes.size &&
                        parameterTypes.toList() == t.parameterTypes
                }.apply {
                    addInstructions(
                        0,
                        """
                            const-string v0, "${t.tag}"
                            invoke-static {v0}, $DIAG_DESCRIPTOR->log(Ljava/lang/String;)V
                        """,
                    )
                }
            } catch (e: Throwable) {
                // Method not found / signature mismatch — log and continue with the
                // remaining targets so a single missing class doesn't kill the build.
                System.err.println("componentDiagnosticPatch: skipped ${t.className}->${t.methodName}: ${e.message}")
            }
        }
    }
}
