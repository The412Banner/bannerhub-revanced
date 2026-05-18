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
// The mci.a hook is dead in 6.0.4 (device-proven: mciEnter never fired).
// Real picker architecture (precise winemu files, no R8 collision):
//   - myo.w(RepoCategory)->ArrayList  = the picker's category-list source;
//     it filters the in-memory myo.c ConcurrentHashMap by category, reads
//     NO disk.
//   - j7o.<init>(Application,myo,oja,cla,fxo,mei,mci,dj9)V  = the WinEmu
//     repo ctor; contains the real disk hydrator (dj9.a().getAll() ->
//     Gson -> ici.e -> map.put) that is supposed to fill myo.c.
//
// Three probes (OfflineDiag internal-files sink, root-readable):
//   1. myoW()        @ myo.w index 0          → does the picker use myo.w?
//   2. myoWReturn(v) before myo.w return      → size of what it returns
//   3. j7oCtor()     @ j7o.<init> index 0     → did disk-hydration run?
//
// Decodes: myoW fires + size 0 + j7oCtor fired → disk read ran but didn't
// fill the map (or ran after) ; myoW fires + size 0 + no j7oCtor → repo
// never constructed before picker ; myoW fires + size>0 → map IS populated,
// failure is downstream (picker sub-filter) ; no myoW → some other feed.
// All injected calls are register-safe (zero-arg, or one already-live reg).
// ============================================================================

private const val MYO_CLASS     = "Lmyo;"
private const val J7O_CLASS     = "Lj7o;"
private const val REPO_CATEGORY = "Lcom/xiaoji/egggame/common/winemu/bean/RepoCategory;"
private const val DIAG =
    "Lapp/revanced/extension/gamehub/winemu/OfflineDiag;"

@Suppress("unused")
val offlineDiagProbePatch = bytecodePatch(
    name = "Offline picker diagnostic probe (THROWAWAY)",
    description = "Probes myo.w (picker list source) + j7o.<init> (disk " +
        "hydrator) to locate the real 6.0.4 offline picker path. Throwaway.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // --- Probe 1+2: myo.w(RepoCategory) -> ArrayList ---
        firstMethod {
            definingClass == MYO_CLASS &&
                name == "w" &&
                parameterTypes == listOf(REPO_CATEGORY) &&
                returnType == "Ljava/util/ArrayList;"
        }.apply {
            // size-of-returned-list, injected before the final return-object
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

        // --- Probe 3: j7o.<init>(...8 args...) V ---
        firstMethod {
            definingClass == J7O_CLASS &&
                name == "<init>" &&
                returnType == "V" &&
                parameterTypes.size == 8
        }.apply {
            // Inject AFTER the super-constructor invoke-direct so `this` is
            // initialized (avoids any uninitialized-this VerifyError that
            // would crash the app on launch).
            val superIdx = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT &&
                    getReference<MethodReference>()?.name == "<init>"
            }
            addInstructions(superIdx + 1, "invoke-static {}, $DIAG->j7oCtor()V")
        }
    }
}
