package app.revanced.patches.gamehub.common

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// =========================================================================
// Shared per-game id capture. Injects ONE index-0
//   invoke-static/range {p0 .. p0},
//       Lcom/xj/winemu/common/BhMenuGameId;->captureGameId(Ljava/lang/Object;)V
// into BOTH per-game menu builders (Lx57;->a More Menu, p0=Lf37
// GameDetailArgs; Lted;->f tile popup, p0=Lued — both `static final`, so
// p0 is the menu-data param). Runs once per menu open; the Renderer / GPU
// Spoof / PC Vibration row clicks all read BhMenuGameId.getCaptured().
//
// One shared capture (the three menu-row patches dependOn this) avoids
// three duplicate index-0 head-blocks in the same hot methods. Single
// no-label invoke, once per menu open — not the per-resolve l1 path —
// so no ANR / trailing-label footgun.
// =========================================================================

private const val GAMEID = "Lcom/xj/winemu/common/BhMenuGameId;"

@Suppress("unused")
val menuGameIdCapturePatch = bytecodePatch(
    name = "Per-game menu id capture (shared)",
    description = "Captures the per-game gameId from the menu-data param at " +
        "entry of GameHub's two per-game menu builders so BannerHub's " +
        "injected rows (Renderer / GPU Spoof / PC Vibration) scope to the " +
        "correct game even from a pre-launch menu. Shared by all three.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        val capture =
            "invoke-static/range {p0 .. p0}, " +
                "$GAMEID->captureGameId(Ljava/lang/Object;)V"

        // Game-details More Menu — Lx57;->a(Lf37;Lpo7;Lv83;I)V
        val menuMethod = firstMethod {
            parameterTypes == listOf("Lf37;", "Lpo7;", "Lv83;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.reference
                            ?.let { it is MethodReference &&
                                    it.definingClass == "Liae;" &&
                                    it.name == "<init>" &&
                                    it.parameterTypes.toList() == listOf(
                                        "Lo05;", "Ljava/lang/String;", "Lpw6;"
                                    )
                            } == true
                } ?: false) &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.SGET_OBJECT &&
                        (ins as? ReferenceInstruction)?.reference?.toString()
                            ?.contains("Lwhl;->S:Lxrl;") == true
                } ?: false)
        }
        menuMethod.addInstructions(0, capture)

        // Library-tile popup — Lted;->f(Lued;Lpw6;Lnw6;ZLt9e;Lv83;I)V
        val libraryMenuMethod = firstMethod {
            parameterTypes == listOf("Lued;", "Lpw6;", "Lnw6;", "Z", "Lt9e;", "Lv83;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.count { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let { it.definingClass == "Lscd;" && it.name == "<init>" } == true
                } ?: 0) >= 4 &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lqs2;" && it.name == "H" &&
                                    it.parameterTypes.toList() == listOf("[Ljava/lang/Object;") &&
                                    it.returnType == "Ljava/util/List;"
                            } == true
                } ?: false)
        }
        libraryMenuMethod.addInstructions(0, capture)

        // Library-LIST popup — Lpzc;->j0(Laub;Z…)Ljava/util/List; (static,
        // p0=Laub which holds a kept-name GameInfo). This is the 3rd entry
        // point only PC Vibration has a row in; without capture here it fell
        // back to the global sniff. (GPU Spoof/Renderer rows aren't here
        // yet — Task: add them — but capturing now makes that free.)
        val pzcMethod = firstMethod {
            parameterTypes == listOf(
                "Laub;", "Z", "Llvc;", "Llvc;", "Lmob;", "Lmob;",
                "Lz9;", "Ljn9;", "Lmvc;", "Lmvc;", "Ljvc;"
            ) &&
                returnType == "Ljava/util/List;" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_VIRTUAL &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lx9d;" && it.name == "i" &&
                                    it.returnType == "Lx9d;"
                            } == true
                } ?: false)
        }
        pzcMethod.addInstructions(0, capture)
    }
}
