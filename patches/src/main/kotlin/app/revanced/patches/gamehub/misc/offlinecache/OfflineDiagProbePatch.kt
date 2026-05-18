package app.revanced.patches.gamehub.misc.offlinecache

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// ============================================================================
// ⚠ THROWAWAY DIAGNOSTIC — DO NOT MERGE / DO NOT SHIP
//
// CORRECTED-SINK round. Build identity is now PROVEN (installed base.apk
// md5 == delivered pre2 diag APK; probes confirmed injected). Every prior
// "method never called" was a SINK failure: OfflineDiag used only
// ActivityThread.currentApplication() (null at DI sites) and DebugTrace used
// com.blankj…Utils.a() — a class R8 STRIPPED from the host (renamed hu5) —
// so BOTH sinks were structurally unable to write on banner.hub. OfflineDiag
// is rewritten to a Context-free hardcoded /data/data/banner.hub/files/ sink.
//
// Probes (OfflineDiag, root-readable):
//   0. beacon()     @ BaseAndroidApp.onCreate (after super.onCreate)
//                    → CONTROL: unambiguous "our code runs + sink works".
//   1. kekO()       @ kek.o(Application) index 0
//                    → did the lazy j7o singleton factory run before picker?
//   2. j7oCtor()    @ j7o.<init> (after super-ctor)
//                    → did the disk-hydrator constructor run?
//   3. f4oA()       @ f4o.a(f4o,RepoCategory,Cont) index 0
//                    → did the picker's category feed get hit?
//   4. myoW()       @ myo.w(RepoCategory) index 0
//   5. myoWReturn() before myo.w return  → size of the list
//
// Decode (beacon MUST fire — it's the control):
//   beacon only, no kekO/j7oCtor/f4oA/myoW → picker uses a different feed.
//   beacon + f4oA + myoW size 0 + no kekO/j7oCtor → cause (i): repo never
//     constructed offline ⟹ myo.c never hydrated.
//   beacon + kekO + j7oCtor + myoW size>0 but picker still empty → cause
//     (ii): map IS populated, failure is a downstream per-picker sub-filter.
// All injected calls are register-safe (zero-arg, or one already-live reg).
// ============================================================================

private const val MYO_CLASS     = "Lmyo;"
private const val J7O_CLASS     = "Lj7o;"
private const val KEK_CLASS     = "Lkek;"
private const val F4O_CLASS     = "Lf4o;"
private const val APP_CLASS     = "Lcom/xiaoji/egggame/BaseAndroidApp;"
private const val REPO_CATEGORY = "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val APPLICATION   = "Landroid/app/Application;"
private const val CONTINUATION  = "Lci3;"
private const val DIAG =
    "Lapp/revanced/extension/gamehub/winemu/OfflineDiag;"

@Suppress("unused")
val offlineDiagProbePatch = bytecodePatch(
    name = "Offline picker diagnostic probe (THROWAWAY)",
    description = "Corrected-sink probes (beacon/kek.o/j7o.<init>/f4o.a/myo.w) " +
        "to disambiguate offline-picker cause (i) vs (ii). Throwaway.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // --- Probe 0: CONTROL beacon @ BaseAndroidApp.onCreate -------------
        firstMethod {
            definingClass == APP_CLASS &&
                name == "onCreate" &&
                returnType == "V" &&
                parameterTypes.isEmpty()
        }.apply {
            // After invoke-super {..}, Application;->onCreate()V so the
            // Application is fully initialized when the beacon fires.
            val superIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_SUPER &&
                    getReference<MethodReference>()?.name == "onCreate"
            }
            addInstructions(superIdx + 1, "invoke-static {}, $DIAG->beacon()V")
        }

        // --- Probe 1: kek.o(Application) -> j7o (lazy DCL factory) ----------
        firstMethod {
            definingClass == KEK_CLASS &&
                name == "o" &&
                parameterTypes == listOf(APPLICATION) &&
                returnType == J7O_CLASS
        }.apply {
            addInstructions(0, "invoke-static {}, $DIAG->kekO()V")
        }

        // --- Probe 2: j7o.<init>(...8 args...) V ---------------------------
        firstMethod {
            definingClass == J7O_CLASS &&
                name == "<init>" &&
                returnType == "V" &&
                parameterTypes.size == 8
        }.apply {
            // After the super-constructor invoke-direct so `this` is
            // initialized (avoids uninitialized-this VerifyError).
            val superIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT &&
                    getReference<MethodReference>()?.name == "<init>"
            }
            addInstructions(superIdx + 1, "invoke-static {}, $DIAG->j7oCtor()V")
        }

        // --- Probe 3: f4o.a(f4o,RepoCategory,Cont) (picker feed) -----------
        firstMethod {
            definingClass == F4O_CLASS &&
                name == "a" &&
                parameterTypes == listOf(F4O_CLASS, REPO_CATEGORY, CONTINUATION) &&
                returnType == "Ljava/lang/Object;"
        }.apply {
            addInstructions(0, "invoke-static {}, $DIAG->f4oA()V")
        }

        // --- Probe 4+5: myo.w(RepoCategory) -> ArrayList -------------------
        firstMethod {
            definingClass == MYO_CLASS &&
                name == "w" &&
                parameterTypes == listOf(REPO_CATEGORY) &&
                returnType == "Ljava/util/ArrayList;"
        }.apply {
            // size-of-returned-list, before the final return-object
            // (do this first so its index isn't shifted by the index-0 insert)
            val retIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.RETURN_OBJECT
            }
            val retReg = getInstruction<OneRegisterInstruction>(retIdx).registerA
            addInstructions(
                retIdx,
                "invoke-static {v$retReg}, $DIAG->myoWReturn(Ljava/lang/Object;)V",
            )
            addInstructions(0, "invoke-static {}, $DIAG->myoW()V")
        }
    }
}
