package app.revanced.patches.gamehub.components

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// Diagnostic instrumentation: log a unique tag at method entry of every
// candidate dropdown-feeder method. v0.3.5 narrowed the universe to iv6.invoke
// firing 3x — but iv6 is a polymorphic synthetic lambda with a packed-switch
// on field a:I across 28+ cases (most are debug-log formatters). v0.3.6 adds:
//   - iv6.invoke entry now logs the discriminator value (which case fired)
//   - v86.b callers (the Composable-picker invokers)
//   - dh7 synthetic deserialization ctor (in case kotlinx.serialization path
//     constructs the parcel instead of the primary ctor)
//   - more dh7.a / dh7.b consumers
private const val DIAG_DESCRIPTOR =
    "Lapp/revanced/extension/gamehub/components/ComponentDiag;"

private data class Target(
    val className: String,
    val methodName: String,
    val parameterTypes: List<String>,
    val tag: String,
    val isIv6Invoke: Boolean = false,
)

private val TARGETS = listOf(
    // --- iv6.invoke with discriminator logging (special case: log a:I value)
    Target("Liv6;", "invoke", emptyList(), "iv6.invoke a=", isIv6Invoke = true),

    // --- Game config response data class — both ctors (primary + synthetic deserialization)
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
        "dh7.<init>(primary)",
    ),
    Target(
        "Ldh7;", "<init>",
        listOf(
            "I",
            "Ljava/util/List;",
            "Ljava/util/List;",
            "Lcom/xiaoji/egggame/common/winemu/bean/EnvLayerEntity;",
            "Lcom/xiaoji/egggame/common/winemu/bean/EnvLayerEntity;",
            "Lcom/xiaoji/egggame/common/winemu/bean/TranslatorConfigs;",
            "Lcom/xiaoji/egggame/common/winemu/bean/PcEmuControllerEntity;",
            "I", "Ljava/lang/String;", "I", "Ljava/lang/String;",
            "I", "I", "I", "Ljava/lang/String;", "I", "Ljava/lang/String;", "J",
        ),
        "dh7.<init>(synthetic)",
    ),

    // --- API caller for getGameConfigByScript
    Target("Ljg2;", "invoke", emptyList(), "jg2.invoke"),
    Target("Lehn;", "invoke", emptyList(), "ehn.invoke"),

    // --- Per-game-settings handlers reading dh7.a / dh7.b
    Target("Lb54;", "b", listOf("Lr91;", "Lfe3;"), "b54.b"),
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

    // --- v86.b callers — these methods host the picker Composable invocation
    Target(
        "Ll61;", "invoke",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "l61.invoke",
    ),
    Target(
        "Lo61;", "r",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
        "o61.r",
    ),
    Target(
        "Lxn7;", "invoke",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
        "xn7.invoke",
    ),
    Target(
        "Lj23;", "invoke",
        listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
        "j23.invoke",
    ),

    // --- Sanity checks: previous miss-targets
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
                    if (t.isIv6Invoke) {
                        // iv6 is polymorphic — log the discriminator field a:I
                        // alongside the tag so we can identify which case fired.
                        addInstructions(
                            0,
                            """
                                iget v0, p0, Liv6;->a:I
                                const-string v1, "${t.tag}"
                                invoke-static {v1, v0}, $DIAG_DESCRIPTOR->log(Ljava/lang/String;I)V
                            """,
                        )
                    } else {
                        addInstructions(
                            0,
                            """
                                const-string v0, "${t.tag}"
                                invoke-static {v0}, $DIAG_DESCRIPTOR->log(Ljava/lang/String;)V
                            """,
                        )
                    }
                }
            } catch (e: Throwable) {
                System.err.println("componentDiagnosticPatch: skipped ${t.className}->${t.methodName}: ${e.message}")
            }
        }
    }
}
