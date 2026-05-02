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
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// The Environment enum that holds the catalog API host pairs (cn + oversea)
// for Online / Beta / Test. The Online value's two host literals are the redirect
// targets — its <clinit> initializer in mcj.smali is where we swap.
private const val MCJ_CLASS = "Lmcj;"

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
        "in the Online enum value's <clinit> initializer in mcj.smali. Beta + Test enum values, " +
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
            definingClass == MCJ_CLASS && name == "<clinit>"
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
