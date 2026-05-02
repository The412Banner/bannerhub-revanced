package app.revanced.patches.gamehub.misc.apiredirect

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// zdb is the static URL-path helper every GameHub API request flows through:
// `zdb.b(qx9 builder, String path)` accepts a relative path like
// "simulator/v2/getAllComponentList" and appends it to the Ktor builder's
// host (set elsewhere from mcj.b()). Patching this single chokepoint with a
// "v6/" prefix is enough to tag every request from the patched 6.0 APK.
private const val ZDB_CLASS = "Lzdb;"

// V6PathPrefix.prefix(String) returns "v6/" + path for relative paths and
// passes full-URL paths (http://, https://) through unchanged. Implementing
// the conditional in Java keeps the smali edit tiny — single invoke-static.
private const val PREFIX_HELPER = "Lapp/revanced/extension/gamehub/api/V6PathPrefix;"

@Suppress("unused")
val prefixApiPathPatch = bytecodePatch(
    name = "Prefix API path with /v6",
    description = "Prepends \"v6/\" to every relative API path emitted by " +
        "zdb.b(qx9, path), the single helper through which GameHub 6.0 funnels " +
        "all simulator/v2/* and other catalog requests. The BannerHub Worker " +
        "strips the prefix and uses it to branch 6.0-only response variants " +
        "(e.g. firmware 1.3.4 vs 1.3.3, base.fileType=0 vs default 4). " +
        "Pairs with Redirect catalog API — that patch swaps the host; this " +
        "one tags the path. Full URLs (http://, https://) are passed through " +
        "untouched so direct downloads still work.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(redirectCatalogApiPatch)

    apply {
        // zdb.b(Lqx9;Ljava/lang/String;)V — p0 is the builder, p1 is the path.
        // Inject at the very head: rewrite p1 in place via the helper, then
        // let the original method body run unchanged. Static helper means no
        // register juggling beyond the move-result.
        firstMethod {
            definingClass == ZDB_CLASS &&
                name == "b" &&
                parameterTypes == listOf("Lqx9;", "Ljava/lang/String;") &&
                returnType == "V"
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {p1}, $PREFIX_HELPER->prefix(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object p1
                """.trimIndent(),
            )
        }
    }
}
