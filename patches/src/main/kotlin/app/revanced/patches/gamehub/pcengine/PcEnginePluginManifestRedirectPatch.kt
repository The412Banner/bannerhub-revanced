package app.revanced.patches.gamehub.pcengine

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Point the PC-engine plugin manifest request at the BannerHub Worker.
//
// WHY THIS EXISTS
// GameHub 6.1.0 moved the entire PC emulation engine out of the APK into a
// downloadable, hash-verified ComboLite plugin (com.xiaoji.egggame.plugin
// .pcengine, loaded by DexClassLoader into the :pcengine process). The host asks
// a server "which engine should I install?" and installs whatever it is told.
// Unpatched, that question goes to XiaoJi, which means XiaoJi decides — at any
// time, remotely — which engine every BannerHub build downloads. This patch
// moves that decision to us. We serve our own v6-signed copy of the plugin, so
// the signing cert matches the host natively and the three signature-bypass
// patches in this package become redundant seatbelts rather than load-bearing.
//
// WHY IT IS *NOT* A HOST REWRITE (the important part)
// 6.1.0 introduced a new API host resolver, `pe0`, whose `a(String path)` builds
// "https://<host>/<path>". It is tempting to just rewrite pe0's host literals —
// DON'T. pe0 serves the ENTIRE new 6.1.0 API surface: ~10 caller classes
// (zy5, ffq, aos, gos, e2p, hos, zns, vdh, zot, zw3) covering login, profile and
// more. Retargeting its hosts would send all of that to our Worker, which serves
// none of it, and break the app. The redirect must be per-call-site.
//
// Note also that pe0's four host literals are a SEPARATE set from the two
// `landscape-api-*.vgabc.com` literals that RedirectCatalogApiPatch rewrites, so
// the two patches cannot collide and have no ordering constraint between them.
//
// HOW IT WORKS
// The API client's shared URL helper (kee.a(builder, String) on 6.1.0) tests the
// path it is given: if it starts with "http://" or "https://" the string is used
// VERBATIM as the request URL; otherwise it is appended to the resolved host as
// a relative path. So handing the call site a complete URL bypasses pe0 entirely
// without touching pe0 at all.
//
// The call site, in the manifest fetcher (zy5 on 6.1.0):
//     sget-object v1, Lpe0;->a:Ljava/util/Set;               # dead load
//     const-string v1, "game/mobile/v1/plugin/latest"
//     invoke-static {v1}, Lpe0;->a(Ljava/lang/String;)Ljava/lang/String;
//     move-result-object v1
//     new-instance v4, Lrk0;                                 # query params
//     ...
//     invoke-virtual {v0, v1, v4, v2}, Lkee;->c(...)         # GET, v1 = the URL
//
// We insert one instruction immediately AFTER the move-result-object, clobbering
// pe0's result with our absolute URL. Deliberately NOT removing the
// invoke-static/move-result pair: move-result-object must directly follow its
// invoke, so deleting the invoke would produce invalid bytecode. Letting the
// original call run and throwing its result away costs one wasted string concat
// per manifest fetch — irrelevant, and far safer than restructuring the region.
// Verified on 6.1.0 that the target register is not read between the insertion
// point and the request call, so the clobber cannot corrupt anything else.
//
// The URL carries the "/v6/" prefix explicitly so this endpoint is v6-gated by
// the Worker's existing convention (it strips "/v6/" and sets is60) WITHOUT
// depending on PrefixApiPathPatch being active. And because the helper passes
// absolute URLs through untouched, that patch — when it IS active — will not
// double-prefix this one. The two compose cleanly in either combination.
//
// ANCHOR
// Structural, on the endpoint path literal, which is globally unique to this one
// class on 6.1.0 (verified: `grep -rln "game/mobile/v1/plugin/latest" smali*/`
// returns exactly one file). No R8 letter to re-pin on a base bump. If this ever
// stops matching, upstream renamed the endpoint — re-confirm the literal, and
// remember the Worker route has to be renamed in lockstep.
//
// SERVER SIDE
// The Worker must answer this path with a BaseResult envelope
// {code:200, msg:"Success", time, data:{...}} wrapping the eight-field snake_case
// PcEnginePluginUpdateDataDto: update_type, plugin_name ("pcengine"),
// plugin_version, schema_version (a STRING, not an int), apk_url, md5, sha256,
// file_size (long). The 14-field camelCase PcEnginePluginVersion is the client's
// INTERNAL model — channel / rolloutPercent / forceUpdate / min-maxHostVersionCode
// never appear on the wire and cannot be set from the server.
// =============================================================================

private const val MANIFEST_PATH_ANCHOR = "game/mobile/v1/plugin/latest"

private const val MANIFEST_URL =
    "https://bannerhub-api.the412banner.workers.dev/v6/game/mobile/v1/plugin/latest"

@Suppress("unused")
val pcEnginePluginManifestRedirectPatch = bytecodePatch(
    name = "Redirect PC engine plugin manifest",
    description = "Points GameHub 6.1.0's PC-engine plugin manifest request " +
        "(game/mobile/v1/plugin/latest) at the BannerHub Cloudflare Worker instead of XiaoJi's " +
        "api-international-gamehub.xiaoji.com, so BannerHub builds install our own v6-signed " +
        "copy of the pcengine plugin rather than whichever build XiaoJi happens to be serving. " +
        "Injects a single absolute-URL const-string at the manifest fetcher's call site, " +
        "exploiting the API client's absolute-URL passthrough. Deliberately does NOT rewrite " +
        "the pe0 host resolver, which serves the whole 6.1.0 API surface including login. " +
        "Anchored on the endpoint path literal, which is globally unique in the APK.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        firstMethod {
            implementation?.instructions?.any { instruction ->
                (instruction as? ReferenceInstruction)?.reference
                    ?.let { it is StringReference && it.string == MANIFEST_PATH_ANCHOR } == true
            } == true
        }.apply {
            val pathIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    getReference<StringReference>()?.string == MANIFEST_PATH_ANCHOR
            }

            // The host-resolver call consumes the path literal and returns the
            // full URL; grab the register its result lands in and overwrite it.
            val moveResultIndex = indexOfFirstInstructionOrThrow(pathIndex) {
                opcode == Opcode.MOVE_RESULT_OBJECT
            }
            val urlRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructions(moveResultIndex + 1, "const-string v$urlRegister, \"$MANIFEST_URL\"")
        }
    }
}
