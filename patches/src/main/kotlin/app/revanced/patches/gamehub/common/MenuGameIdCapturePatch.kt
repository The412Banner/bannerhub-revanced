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

        // Game-details More Menu — 6.0.7: Lc37;->a(Lf17;ILev6;Ljh7;Leh3;I)V
        // (6.0.4 was Lx57;->a(Lf37;Lpo7;Lv83;I)V). The builder gained params
        // and moved class in the 6.0.7 Steam restructure, but still takes
        // GameDetailArgs (Lf17, ex-Lf37) as p0 and builds rows via the Ltyc
        // row-data ctor (ex-Liae): (Ln55 icon, String label, Lgv6 onClick).
        // The param signature alone is shared by 3 methods (su/c37/v90); the
        // Ltyc;-><init> anchor disambiguates to the real builder (c37). The
        // 6.0.4 Lwhl;->S label sget anchor is dropped (label classes moved).
        val menuMethod = firstMethod {
            parameterTypes == listOf("Lf17;", "I", "Lev6;", "Ljh7;", "Leh3;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.reference
                            ?.let { it is MethodReference &&
                                    it.definingClass == "Ltyc;" &&
                                    it.name == "<init>" &&
                                    it.parameterTypes.toList() == listOf(
                                        "Ln55;", "Ljava/lang/String;", "Lgv6;"
                                    )
                            } == true
                } ?: false)
        }
        menuMethod.addInstructions(0, capture)

        // Library-tile popup — 6.0.7: Ly7c;->f(Lz7c;Lgv6;Lev6;ZLfyc;Leh3;I)V
        // (6.0.4 was Lted;->f(Lued;Lpw6;Lnw6;ZLt9e;Lv83;I)V — same method name
        // f, same 7-param shape). p0=Lz7c menu data (ex-Lued). Rows are now
        // built via Lg6c;-><init>(String;Ln55;String;Lev6) (ex-Lscd) — 5 rows.
        // The 6.0.4 Lqs2;->H asList anchor is dropped (rows are no longer
        // collected via a [Object]->List call); the Lg6c ctor count >=4 is
        // unique to this method (the same 7-param sig also matches on2/w16,
        // which build 0 Lg6c rows).
        val libraryMenuMethod = firstMethod {
            parameterTypes == listOf("Lz7c;", "Lgv6;", "Lev6;", "Z", "Lfyc;", "Leh3;", "I") &&
                returnType == "V" &&
                (implementation?.instructions?.count { ins ->
                    ins.opcode == Opcode.INVOKE_DIRECT &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let { it.definingClass == "Lg6c;" && it.name == "<init>" } == true
                } ?: 0) >= 4
        }
        libraryMenuMethod.addInstructions(0, capture)

        // Library-LIST popup — 6.0.7: Levb;->b0(Loza;Z…11 params)List; (static,
        // p0=Loza menu data, ex-Laub). 6.0.4 was Lpzc;->j0(Laub;Z…)List;. This
        // is the 3rd entry point only PC Vibration has a row in; without
        // capture here it fell back to the global sniff. The 11-param
        // signature is globally UNIQUE (only this one method in the whole apk
        // has the L,Z,9×L → List shape), so it pins the method on its own; the
        // 6.0.4 Lx9d;->i() finalize anchor (no 6.0.7 equivalent) is replaced
        // with the resolver call (Lok8;->c0, ex-Lxd3;->l1 — invoked 9× here to
        // resolve the row labels) as a robustness secondary anchor.
        val pzcMethod = firstMethod {
            parameterTypes == listOf(
                "Loza;", "Z", "Ldtb;", "Ldtb;", "Lpg8;", "Lpg8;",
                "Lx6b;", "Lny;", "Ljq2;", "Lctb;", "Ldtb;"
            ) &&
                returnType == "Ljava/util/List;" &&
                (implementation?.instructions?.any { ins ->
                    ins.opcode == Opcode.INVOKE_STATIC &&
                        (ins as? ReferenceInstruction)?.getReference<MethodReference>()
                            ?.let {
                                it.definingClass == "Lok8;" && it.name == "c0" &&
                                    it.returnType == "Ljava/lang/String;"
                            } == true
                } ?: false)
        }
        pzcMethod.addInstructions(0, capture)
    }
}
