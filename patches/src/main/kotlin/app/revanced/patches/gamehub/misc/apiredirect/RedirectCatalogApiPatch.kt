package app.revanced.patches.gamehub.misc.apiredirect

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.extensions.removeInstruction
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

// The Environment enum that holds the catalog API host pairs (cn + oversea)
// for Online / Beta / Test. The Online value's two host literals are the redirect
// targets — its <clinit> initializer is where we swap.
//
// ─── Anchored STRUCTURALLY as of 6.1.0 — no more per-bump letter re-pins. ───
// This patch used to hardcode the R8-mangled class letter and needed editing on
// every single base APK bump:
//   6.0.0 → Lmcj;   6.0.1 → Lzhj;   6.0.2 → Lxrj;   6.0.4 → Lesj;
//   6.0.7 → Lnnh;   6.0.8 → Lqnh;   6.0.9 → Lyei;   6.1.0 → Lf7n;
// Seven re-pins was enough. The anchor the old comment already described is now
// the actual implementation: locate the unique <clinit> containing BOTH host
// literals. Verified on 6.1.0 that exactly ONE class in the APK carries both
// (smali_classes3/f7n.smali) — the enum values are constructed in order, so the
// FIRST occurrence of each literal belongs to the Online value (cn→v5 at :39,
// oversea→v6 at :43; Beta follows at :75/:79 with the -beta hosts, which we
// deliberately leave alone). 6-arg <init>(I,String,String,String,String,String).
//
// If this ever stops matching, upstream renamed or restructured the hosts
// themselves — re-confirm with:
//   grep -rl "landscape-api-cn.vgabc.com" smali*/
// and check the hit also contains landscape-api-oversea.vgabc.com.

// Original GameHub 6.0 hosts the patch removes from the Online enum value.
// They are bare hostnames — t40.smali builds the URL as "<scheme>://<host>",
// so the replacement must also be a bare hostname (no scheme, no path).
private const val ORIGINAL_CN_HOST = "landscape-api-cn.vgabc.com"
private const val ORIGINAL_OVERSEA_HOST = "landscape-api-oversea.vgabc.com"

// New origin: the deployed BannerHub Cloudflare Worker. Same value for both
// slots — there is no separate CN/Oversea behavior we need to preserve, and
// the Worker forwards unallowlisted paths back to landscape-api.vgabc.com so
// it already serves as the conditional fallback layer.
private const val WORKER_HOST = "bannerhub-api.the412banner.workers.dev"

@Suppress("unused")
val redirectCatalogApiPatch = bytecodePatch(
    name = "Redirect catalog API",
    description = "Redirects GameHub 6.0's catalog API (simulator/v2/* — getAllComponentList, " +
        "getContainerList, getContainerDetail, getDefaultComponent, getImagefsDetail, " +
        "executeScript) from landscape-api-{cn,oversea}.vgabc.com to the BannerHub Cloudflare " +
        "Worker, which serves the curated catalog from the412banner.github.io/bannerhub-api " +
        "and falls back to vgabc for unallowlisted paths. Patches the two host string literals " +
        "in the Online enum value's <clinit> initializer, located structurally as the unique " +
        "class carrying both hosts (no R8 letter to re-pin). Beta + Test enum values, " +
        "the analytics hosts (landscape-api-*-*.vgabc.com/events), the clientapi host " +
        "(clientgsw.vgabc.com), and the component CDN (zlyer-cdn-comps-en.bigeyes.com) are " +
        "intentionally left untouched.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // Each enum value is constructed in mcj.<clinit>() with five const-string
        // instructions feeding the <init>(I, displayName, value, displayName_zh, cnHost, overseaHost)
        // call. The Online value is the first one constructed; cnHost loads into v5
        // and overseaHost into v6 in the original. We don't depend on register
        // numbers — we locate by StringReference, then preserve whatever register
        // each instruction targets.
        firstMethod {
            name == "<clinit>" &&
                implementation?.instructions?.let { instructions ->
                    val literals = instructions.mapNotNull { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    }.toSet()
                    ORIGINAL_CN_HOST in literals && ORIGINAL_OVERSEA_HOST in literals
                } == true
        }.apply {
            // Replace cnHost literal.
            val cnIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    getReference<StringReference>()?.string == ORIGINAL_CN_HOST
            }
            val cnReg = getInstruction<OneRegisterInstruction>(cnIdx).registerA
            removeInstruction(cnIdx)
            addInstructions(cnIdx, "const-string v$cnReg, \"$WORKER_HOST\"")

            // Replace overseaHost literal. Re-search after the cn replacement —
            // the index of the oversea literal hasn't shifted (we replaced one
            // instruction with one instruction at the same index), but locating
            // by StringReference is robust either way.
            val overseaIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING &&
                    getReference<StringReference>()?.string == ORIGINAL_OVERSEA_HOST
            }
            val overseaReg = getInstruction<OneRegisterInstruction>(overseaIdx).registerA
            removeInstruction(overseaIdx)
            addInstructions(overseaIdx, "const-string v$overseaReg, \"$WORKER_HOST\"")
        }
    }
}
