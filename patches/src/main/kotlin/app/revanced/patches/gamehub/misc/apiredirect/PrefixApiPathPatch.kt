package app.revanced.patches.gamehub.misc.apiredirect

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION

// The static URL-path helper every GameHub API request flows through.
// Signature: `<helper>.b(<builder>, String path)` where `path` is a relative
// path like "simulator/v2/getAllComponentList" and the builder is Ktor's
// HttpRequestBuilder.url. Patching this single chokepoint with a "v6/"
// prefix is enough to tag every request from the patched 6.0 APK.
//
// R8-mangled letters, update on each base APK bump:
//   6.0.0 → Lzdb;->b(Lqx9;Ljava/lang/String;)V
//   6.0.1 → Lohb;->b(Lj1a;Ljava/lang/String;)V
//   6.0.2 → Lvob;->b(Lm7a;Ljava/lang/String;)V
//   6.0.4 → Lcpb;->b(Ln7a;Ljava/lang/String;)V  (string-trim helper Lbml;->s1)
//   6.0.7 → Lzua;->a(Lgn9;Ljava/lang/String;)V  (method b->a; trim now Lcxj;->G1; 38 call sites)
//   6.0.8 → Ldva;->a(Ljn9;Ljava/lang/String;)V  (trim now Ljxj;->G1; ~19 call sites)
//   6.0.9 → Lscb;->a(Lfy9;Ljava/lang/String;)V  (trim now Lkpk;->o1)
// Structural anchor: a static method `(L<2-3-letter>;Ljava/lang/String;)V`
// whose body starts with `iget-object` from the builder's URL field then
// calls a string-trim helper. Body shape is byte-stable across versions.
// Re-derive via: const-string "simulator/v2/getComponentList" (6.0.9: in
// rpe.smali) → the immediately-following `invoke-static {builder, path},
// L?;->?(L?;String)V`.
// 6.0.9 verified (~/gh609-apktool-d/smali_classes3/scb.smali): a(Lfy9;String)V
// `.locals 3`, body `iget-object p0, p0, Lfy9;->a:Lj5m;` then trim Lkpk;->o1
// (call site rpe.smali:227 `invoke-static {v11, v0}, Lscb;->a(Lfy9;String)V`).
private const val URL_HELPER_CLASS  = "Lscb;"
private const val URL_BUILDER_TYPE  = "Lfy9;"
private const val URL_HELPER_METHOD = "a" // was "b" in 6.0.0–6.0.4

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
            definingClass == URL_HELPER_CLASS &&
                name == URL_HELPER_METHOD &&
                parameterTypes == listOf(URL_BUILDER_TYPE, "Ljava/lang/String;") &&
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
