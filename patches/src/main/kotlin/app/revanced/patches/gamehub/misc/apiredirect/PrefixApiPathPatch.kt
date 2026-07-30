package app.revanced.patches.gamehub.misc.apiredirect

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// The static URL-path helper every GameHub API request flows through.
// Signature: `<helper>.<m>(<builder>, String path)` where `path` is a relative
// path like "simulator/v2/getAllComponentList" and the builder is Ktor's
// HttpRequestBuilder. Patching this single chokepoint with a "v6/" prefix is
// enough to tag every request from the patched APK.
//
// ─── Anchored STRUCTURALLY as of 6.1.0 — no more per-bump letter re-pins. ───
// This patch used to hardcode the R8-mangled class/builder/method triple and
// needed editing on every base APK bump:
//   6.0.0 → Lzdb;->b(Lqx9;Ljava/lang/String;)V
//   6.0.1 → Lohb;->b(Lj1a;Ljava/lang/String;)V
//   6.0.2 → Lvob;->b(Lm7a;Ljava/lang/String;)V
//   6.0.4 → Lcpb;->b(Ln7a;Ljava/lang/String;)V  (string-trim helper Lbml;->s1)
//   6.0.7 → Lzua;->a(Lgn9;Ljava/lang/String;)V  (method b->a; trim Lcxj;->G1)
//   6.0.8 → Ldva;->a(Ljn9;Ljava/lang/String;)V  (trim Ljxj;->G1)
//   6.0.9 → Lscb;->a(Lfy9;Ljava/lang/String;)V  (trim Lkpk;->o1)
//   6.1.0 → Lkee;->a(Ltgc;Ljava/lang/String;)V  (5 callers = the API client's
//            five request methods b/c/d/e/f; Lkee; is the client itself now)
//
// The anchor now exploits an invariant of what this helper IS: it is the one
// place that decides "absolute URL or relative path", so it is the only static
// (builder, String) -> void method in the APK carrying BOTH the "http://" and
// "https://" scheme literals. Verified on 6.1.0: 33 classes contain "https://",
// 15 contain both schemes, and across all of them exactly ONE method matches
// static + 2 params + param2 String + returns void — kee.a. The only near-misses
// are com.xiaomi.push.service.x's (Context, String)V methods, excluded by the
// non-framework first-parameter check below.
//
// Body shape on 6.1.0, for re-derivation if the anchor ever breaks:
//   g7r urlBuilder = builder.a;
//   String s = StringsKt.trim(path).toString();
//   if (s.isEmpty()) return;
//   if (s.startsWith("http://") || s.startsWith("https://")) {
//       h7r.b(urlBuilder, s); return;          // absolute URL: used verbatim
//   }
//   String rel = StringsKt.trimStart(s, '/');
//   if (rel.isNotEmpty()) m6v.e0(urlBuilder, rel);
// Re-derive from a surviving catalog path literal, e.g.
//   grep -rn "simulator/v2/getAllComponentList" smali*/   (6.1.0: ao4.smali:348)
// then follow the request through the client's request methods to the shared
// static helper they all call first.
//
// NOTE the useful consequence of that absolute-URL branch: any patch handing
// this helper a full https:// URL bypasses BOTH the host and this prefix. That
// is exactly how PcEnginePluginManifestRedirectPatch works, and it is why the
// two patches compose without double-prefixing the plugin manifest request.
private const val HTTP_SCHEME_LITERAL = "http://"
private const val HTTPS_SCHEME_LITERAL = "https://"

// V6PathPrefix.prefix(String) returns "v6/" + path for relative paths and
// passes full-URL paths (http://, https://) through unchanged. Implementing
// the conditional in Java keeps the smali edit tiny — single invoke-static.
private const val PREFIX_HELPER = "Lapp/revanced/extension/gamehub/api/V6PathPrefix;"

@Suppress("unused")
val prefixApiPathPatch = bytecodePatch(
    name = "Prefix API path with /v6",
    description = "Prepends \"v6/\" to every relative API path emitted by the single static " +
        "(builder, path) helper through which GameHub funnels all simulator/v2/* and other " +
        "catalog requests. The BannerHub Worker strips the prefix and uses it to branch " +
        "6.0-only response variants (e.g. firmware 1.3.4 vs 1.3.3, base.fileType=0 vs " +
        "default 4). Pairs with Redirect catalog API — that patch swaps the host; this one " +
        "tags the path. Located structurally as the only static (builder, String) -> void " +
        "method carrying both URL scheme literals, so there is no R8 letter to re-pin on a " +
        "base bump. Full URLs (http://, https://) are passed through untouched so direct " +
        "downloads still work.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(redirectCatalogApiPatch)

    apply {
        // p0 is the builder, p1 is the path. Inject at the very head: rewrite p1
        // in place via the helper, then let the original body run unchanged. A
        // static helper means no register juggling beyond the move-result.
        firstMethod {
            AccessFlags.STATIC.isSet(accessFlags) &&
                returnType == "V" &&
                parameterTypes.size == 2 &&
                parameterTypes[1] == "Ljava/lang/String;" &&
                // Exclude framework-typed first params (com.xiaomi.push's
                // (Context, String)V helpers also carry both scheme literals).
                parameterTypes[0].let { it.startsWith("L") && !it.startsWith("Landroid/") } &&
                implementation?.instructions?.let { instructions ->
                    val literals = instructions.mapNotNull { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    }.toSet()
                    HTTP_SCHEME_LITERAL in literals && HTTPS_SCHEME_LITERAL in literals
                } == true
        }.addInstructions(
            0,
            """
                invoke-static {p1}, $PREFIX_HELPER->prefix(Ljava/lang/String;)Ljava/lang/String;
                move-result-object p1
            """.trimIndent(),
        )
    }
}
