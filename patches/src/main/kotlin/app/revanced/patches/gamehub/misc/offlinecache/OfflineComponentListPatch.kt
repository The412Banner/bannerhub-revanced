package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch

// ============================================================================
// Offline component-picker fix — gof.a delegation.
//
// gof.a is the CONFIRMED per-type picker component-list feed (pre5,
// model-free: invoked with the exact ComponentType online AND offline; stack
// sz2 picker-VM → zxf → gof.a; return type non-obf
// BaseResult<EnvListData<EnvLayerEntity>>). It is a tiny static dispatcher:
//   if ((flags & 4) != 0) page = 200
//   gof.b(componentType.getType(), 1, page, continuation)   // suspend impl
//
// We replace its body with a single delegating call to
// OfflineComponentList.dispatch(gof, ComponentType, page, Continuation, flags),
// which:
//   • OFFLINE → returns a BaseResult synthesised from the on-device saved
//     catalog (sp_winemu_unified_resources — the user's real downloads),
//     filtered by ComponentType.type → picker lists them offline.
//   • ONLINE / any failure → reflectively invokes the original
//     gof.b(int,int,int,Continuation) and returns its value verbatim
//     (COROUTINE_SUSPENDED / fresh result passes straight through — online
//     behaviour byte-identical).
//
// Register-safe: no branch, no extra register — `invoke-static {p0..p4}` +
// `move-result-object p0` + `return-object p0`; the original body becomes
// unreachable (valid dalvik). gof.a's trivial logic is re-implemented in the
// extension. Fail-safe by construction: the only non-original path is the
// offline synthesis, and that is itself fully guarded (returns null → the
// reflective original is used) so the picker can never be broken.
// ============================================================================

// 6.0.7: gof reshuffled Lgof;->Li6e; (verified: a(Li6e;,ComponentType,I,Lkq3;,I)Object
// is the page=200 dispatcher to b(IIILkq3;)Object; c(Lkq3;)Object has .locals 11).
// 6.0.8: Li6e;->Ll6e; (verified ~/gh608-apktool-d/smali_classes3/l6e.smali:
// a(Ll6e;,ComponentType,I,Lkq3;,I)Object page=200 (0xc8) → b(IIILkq3;)Object;
// c(Lkq3;)Object present; ComponentType param still the stable anchor).
// ComponentType stays unobfuscated; method names a/c + shapes unchanged.
// 6.0.9: Ll6e;->Lrpe; (verified ~/gh609-apktool-d/smali_classes3/rpe.smali:
// a(Lrpe;,ComponentType,I,Lpv3;,I)Object page=200 (0xc8) → b(IIILpv3;)Object;
// b(IIILpv3;)Object impl; c(Lpv3;)Object .locals 11 present). Continuation type
// Lkq3;→Lpv3; (not anchored — patch keys on size==5 + [1]==ComponentType).
// ⚠️ EXTENSION FIX (the real "never worked" cause): the success-result wrapper
// is NOT n55 on 6.0.9. Confirmed via the caller unwrap (as5.smali after the
// rpe.a call): check-cast Lzi5; (sealed base); instance-of Lyi5; (SUCCESS) →
// iget Lyi5;->a:Object → check-cast List. So N55_SUCCESS n55→yi5 (Lyi5; extends
// Lzi5;, field a:Object, ctor(Object); error variant Lxi5;). 608 n55/o55 →
// 609 yi5/zi5. See OfflineComponentList.java.
//
// 6.3.1 (also 6.1.0+): TARGET RESTRUCTURED + EXTENSION DEAD → GATED.
// • The stable anchor is gone: `Lcom/xiaoji/egggame/common/winemu/bean/
//   ComponentType;` has 0 references in the 631 host smali (no class file, no
//   `/ComponentType;` descriptor anywhere), so the size==5 + [1]==ComponentType
//   fingerprint cannot resolve. EnvLayerEntity, EnvListData, State and
//   BaseResult are all R8-renamed too — they survive ONLY as kotlinx-
//   serialization descriptor / cast-message strings (e.g. Le8b; <clinit>
//   "com.xiaoji.egggame.common.winemu.bean.EnvLayerEntity", Lg8b; toString
//   "EnvLayerEntity(blurb=").
// • The per-type PAGED feed this patch replaced (gof.a → gof.b(type,1,page))
//   no longer exists as such. The host's component feed is now a single
//   all-components fetch: `Luq7;->c(Lrjs;)Object` (.locals 3) → withContext(IO,
//   `Ltq7;` SuspendLambda) → const-string "simulator/v2/getAllComponentList"
//   (smali_classes3/tq7.smali:348, the only hit in the APK), unwrapped as
//   BaseResult<EnvListData<EnvLayerEntity>> (tq7.smali:796). The container
//   list (`simulator/v2/getContainerList`) is a separate single hit. No
//   ComponentType/page params anywhere on that path — filtering by type is
//   done client-side after the fetch.
// • The extension is dead regardless: OfflineComponentList.java builds its
//   offline result via Class.forName(EnvLayerEntity / State) + the `yi5`
//   success wrapper, none of which exist on 631; a port is a structural
//   rewrite (obfuscated bean ctors + wrapper discovery), not a rename.
// ⇒ `use = false`. The body below is left as the last known-good (6.0.9)
//   anchor logic so a future port has the shape to diff against; enabling it
//   with `-e` on 6.3.1 will fail loudly at the first fingerprint.
private const val GOF_CLASS = "Lrpe;"
private const val COMPONENT_TYPE =
    "Lcom/xiaoji/egggame/common/winemu/bean/ComponentType;"
private const val LIST =
    "Lapp/revanced/extension/gamehub/winemu/OfflineComponentList;"

@Suppress("unused")
val offlineComponentListPatch = bytecodePatch(
    name = "Offline component picker — local list",
    description = "Replaces gof.a (the picker's per-type component-list feed) " +
        "with a delegating call: offline it returns the user's downloaded " +
        "components (from sp_winemu_unified_resources, filtered by " +
        "ComponentType) so GPU driver / DXVK / VKD3D / FEXCore / Box64 / " +
        "container pickers list them offline; online it reflectively invokes " +
        "the original suspend impl unchanged. Register-safe, fail-safe. " +
        "GATED on 6.1.0+: the per-type paged feed and the kept bean classes it " +
        "relies on no longer exist in the host (see file header).",
    // 6.3.1: target restructured + extension dead (see header) — not applied.
    use = false,
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // gof.a(Lgof;,ComponentType,I,Lci3;,I)Object — p0=gof p1=ComponentType
        // p2=page p3=Continuation p4=flags. Replace body with the delegate.
        firstMethod {
            definingClass == GOF_CLASS &&
                name == "a" &&
                parameterTypes.size == 5 &&
                parameterTypes[1] == COMPONENT_TYPE &&
                returnType == "Ljava/lang/Object;"
        }.apply {
            addInstructions(
                0,
                """
                    invoke-static {p0, p1, p2, p3, p4}, $LIST->dispatch(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;I)Ljava/lang/Object;
                    move-result-object p0
                    return-object p0
                """.trimIndent(),
            )
        }

        // gof.c(Lci3;)Object — the container list (Wine/Proton picker), via
        // getContainerList. No ComponentType (returns all containers); no
        // sibling impl to delegate to, so index-0 conditional short-circuit:
        // offline → OfflineComponentList.getContainers() (n55(List) or null);
        // null → fall through to the original gof.c (online fetch unchanged).
        // .locals 11 ⇒ v0 is a free scratch local (original's first insn
        // overwrites v0 on the fall-through path, so reuse is safe).
        firstMethod {
            definingClass == GOF_CLASS &&
                name == "c" &&
                parameterTypes.size == 1 &&
                returnType == "Ljava/lang/Object;"
        }.apply {
            val orig = getInstruction(0)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $LIST->getContainers()Ljava/lang/Object;
                    move-result-object v0
                    if-eqz v0, :bhOrig
                    return-object v0
                """.trimIndent(),
                ExternalLabel("bhOrig", orig),
            )
        }
    }
}
