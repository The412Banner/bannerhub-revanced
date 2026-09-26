package app.revanced.patches.gamehub.misc.debuglog

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.extensions.getInstruction
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import app.revanced.patches.gamehub.misc.extension.sharedGamehubExtensionPatch
import app.revanced.util.getNode
import app.revanced.util.getReference
import app.revanced.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private val debuggableManifestPatch = resourcePatch {
    apply {
        document("AndroidManifest.xml").use { dom ->
            val app = dom.getNode("application") as Element
            app.setAttribute("android:debuggable", "true")
        }
    }
}

private const val DEBUG_TRACE = "Lapp/revanced/extension/gamehub/debug/DebugTrace;"

// =========================================================================
// R8-mangled class-letter map for the DebugLog probe targets, version-by-
// version. The probes trace the PC/GOG game-import → library-write path.
//
// Y2D_INTERFACE  : in-app error reporter interface. The catch handlers funnel
//                  caught Throwables here. The method + Function0 type were
//                  renamed in 6.0.7.
//                  6.0.0/6.0.1 Ly2d;  6.0.2 Lpgd;  6.0.4 Lxgd; (all `e(Throwable,Function0)`)
//                  6.0.7 Lt9c;  — method renamed `e` → `a(Ljava/lang/Throwable;Lev6;)V`
//                  (Function0 type Lmw6; → Lev6;).
// Y2D_IMPL       : the decorator impl whose body delegates to Y2D_INTERFACE
//                  then writes to a sink (Crashlytics in stock; a no-op with
//                  Crashlytics disabled). Anchor: the Lt9c; impl whose `a()`
//                  re-invokes Lt9c;->a on a wrapped field (the OTHER impl,
//                  `qua`, instead builds a Pua and calls Lfoa;->v — not it).
//                  6.0.0/6.0.1 Lodb;  6.0.2 Li86;  6.0.4 Lj86; (`e`)
//                  6.0.7 Lz86; — method `e` → `a`.
// SAVE_REPO      : the GAME_LIB_REPO class (same anchor BypassLoginPatch uses,
//                  GAME_LIB_REPO = Lam7; on 6.0.7). The 3-arg save method is
//                  still `v(GameInfo, LaunchMethod, Continuation)` — the two
//                  model param types are STABLE (non-obfuscated), so the method
//                  is anchored structurally. Its catch path invokes
//                  Y2D_INTERFACE (now Lt9c;->a) with the Throwable in p3.
//                  6.0.0 Lxm7;  6.0.1 Lhp7;  6.0.2 Luu7;  6.0.4 Lvu7;
//                  6.0.7 Lam7; (method still `v`)
// RETRO_REPO     : DROPPED on 6.0.7. The retro-game write path was rebuilt —
//                  RetroGameDao moved to
//                  com.xiaoji.egggame.retro_emulators.data.local and its
//                  `upsert` no longer exists (Room-generated *_Impl.a/b/f/g/h),
//                  so the old "retro upsert wrapper" probe has no equivalent
//                  target. The other three probes still cover the PC/GOG
//                  import path the patch was built to debug.
//                  6.0.0/6.0.1 Ly4i;  6.0.2 Lyji;  6.0.4 Lfki; (`b`)  6.0.7 —
// IMPORT_TXN     : the withTransaction body doing both
//                  GameLaunchMethodDao.insert and GameLibraryBaseDao.insert.
//                  In 6.0.7 R8 merged the import lambdas into one dispatch
//                  class `Lza;` (constructed inside Lam7;->v with discriminator
//                  0x17) and outlined this body into method `w` (no longer
//                  `invokeSuspend`); `w` is the sole method in `Lza;` holding
//                  both inserts. Anchored on the two STABLE DAO insert calls.
//                  6.0.0/6.0.1 Lel7;  6.0.2 Lvs7;  6.0.4 Lws7; (invokeSuspend)
//                  6.0.7 Lza; — method `invokeSuspend` → `w`.
// =========================================================================

// 6.0.8 re-derive (~/gh608-apktool-d): Y2D_INTERFACE Lt9c;->Lw9c; (= Ldm7;->x
// catch invoke target, a(Throwable,Ldv6;)); Y2D_IMPL Lz86;->Ly86; (its a()
// iget a:Lw9c; then invoke-interface Lw9c;->a — the delegator; sibling tua
// builds Lsua;+calls Lioa;->j = decoy); SAVE_REPO Lam7;->Ldm7; (= GAME_LIB_REPO,
// am7 now a lambda), SAVE_METHOD v->x; IMPORT_TXN class stays Lza; (coincidental)
// but method w->x (za.x(Object)Object outlined, holds both DAO inserts, dm7 refs it).
// 6.0.9 re-derive (~/gh609-apktool-d): full reshuffle from 6.0.8. Y2D_INTERFACE Lw9c;→Llsc;
// (= the Throwable+Function0 reporter invoked in SAVE_REPO catch as Llsc;->e(Throwable,Lr47;);
// Llsc; is now a 10-method logging iface, the caught-error method is `e`, so Y2D_ERR_METHOD a→e).
// Y2D_IMPL Ly86;→Lrh6; (the sole class IMPLEMENTING Llsc;; its e(Throwable,Lr47;) does
// iget a:Llsc; then invoke-interface Llsc;->e = delegator; Y2D_IMPL_METHOD a→e, p1=Throwable).
// SAVE_REPO Ldm7;→Lqv7; (= GAME_LIB_REPO, shared w/ BypassLogin), SAVE_METHOD x unchanged
// (x(GameInfo,LaunchMethod,Lpv3;)). IMPORT_TXN Lza;→Lcb; (super Luuk; continuation, referenced
// by qv7; the sole method holding BOTH DAO inserts is now w(Object)Object), IMPORT_TXN_METHOD x→w.
// 6.3.1 re-derive (~/gh631-apktool-d; 6.1.x never had this patch re-pinned, it
// SEVERE-failed there too). Y2D_INTERFACE Llsc;→Ljqk; (smali_classes3/jqk.smali —
// the ONLY interface in the APK declaring `k(Throwable,Function0)V` (it also has a
// sibling `j`); the repo's catch handlers all call `Ljqk;->k`, so Y2D_ERR_METHOD
// e→k). Y2D_IMPL Lrh6;→Lpec; (two classes implement Ljqk;: pec.k() does `iget
// a:Ljqk;` + `invoke-interface Ljqk;->k` = the delegator; bui.k() builds an Laui;
// and calls Ljhi;->u = the decoy). Y2D_IMPL_METHOD e→k, Throwable still p1.
// SAVE_REPO Lqv7;→Lq7e; (= GAME_LIB_REPO: the only ctor (GameLibraryDatabase, Lm62;)).
// SAVE_METHOD x→C: q7e.C(Ly1e;Lsgj;ContinuationImpl) — the model types are now
// R8 letters too (y1e toString "GameInfo(id=", sgj "LaunchMethod(id="); C is the
// only q7e method with that (GameInfo, LaunchMethod, Continuation) shape, reads
// q7e.k() (userId) and wraps the writes in RoomDatabaseKt.useWriterConnection.
// Its catch (q7e.smali:8528) is `invoke-interface {v1, v0, v3}, Ljqk;->k` — the
// Throwable is v0, NOT p3 as on 6.0.x (p3 is the continuation here), so the
// probe now reads the register off the invoke and traces AFTER the call via
// DebugTrace.writeCaught(Throwable), which needs no scratch register (v0 is the
// Throwable itself, so the old `const-string v0` would have clobbered it).
// IMPORT_TXN — DROPPED on 6.3.1: `GameLaunchMethodDao` / `GameLibraryBaseDao` have
// ZERO hits (com.xiaoji.egggame.game.database now holds only GameLibraryDatabase
// + _Impl; every DAO is an R8 letter), and the transaction body is an Ln4b;
// dispatch lambda handed to useWriterConnection. Without the stable DAO names
// there is nothing to place the two insert markers against, so probe 4 is gone
// the same way probe 3 went on 6.0.7. Probes 1 + 2 still cover the import path.
private const val Y2D_INTERFACE = "Ljqk;"    // 6.0.9: Llsc;  6.0.8: Lw9c;  6.0.7: Lt9c;
private const val Y2D_ERR_METHOD = "k"       // 6.0.9: e  6.0.8: a  6.0.4: e
private const val Y2D_IMPL = "Lpec;"         // 6.0.9: Lrh6;  6.0.8: Ly86;  6.0.7: Lz86;
private const val Y2D_IMPL_METHOD = "k"      // 6.0.9: e  6.0.8: a  6.0.4: e
private const val SAVE_REPO = "Lq7e;"        // 6.0.9: Lqv7;  6.0.8: Ldm7;  6.0.7: Lam7;
private const val SAVE_METHOD = "C"          // 6.0.9: x  6.0.7: v

@Suppress("unused")
val debugLogPatch = bytecodePatch(
    name = "Debug logging",
    description = "Marks the APK debuggable and routes diagnostic probes through " +
        "DebugTrace. Writes to logcat with tag GH600-DEBUG at Log.i level (the device " +
        "filter strips app-tagged Log.e but lets Log.i through) and also appends to a " +
        "file on external storage at " +
        "/storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log " +
        "as a backup channel.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    dependsOn(debuggableManifestPatch)
    // Every probe calls into DebugTrace, which lives in the shared extension. Like
    // Bypass login / Explore hijack, this patch used to ride on some OTHER patch
    // pulling the extension in; declare it so it cannot silently become a no-op.
    dependsOn(sharedGamehubExtensionPatch)

    apply {
        // PROBE 1 — the in-app error reporter impl (Lz86;->a). Every release
        // catch handler funnels caught Throwables to the Lt9c; reporter, and
        // disabling Firebase Crashlytics turned its sink into a no-op. Trace
        // every Throwable that reaches it so silently-swallowed exceptions
        // surface. The Throwable is p1. 6.0.4: Lj86;->e → 6.0.7: Lz86;->a.
        // 6.3.1: Lpec;->k (`.locals 3`; v0 is free at entry — the body's first
        // write to it is the `iget a:Ljqk;` that follows).
        firstMethod {
            definingClass == Y2D_IMPL && name == Y2D_IMPL_METHOD
        }.apply {
            addInstructions(
                0,
                """
                    const-string v0, "y2d.e caught"
                    invoke-static {v0, p1}, $DEBUG_TRACE->write(Ljava/lang/String;Ljava/lang/Throwable;)V
                """,
            )
        }

        // PROBE 2 — the game-import save function (Lam7;->v(GameInfo,
        // LaunchMethod, Continuation)). Probe at:
        //   1. Entry — confirms the save use case reached this method.
        //   2. Catch path (right before the Lt9c;->a call) — independent of
        //      which Lt9c; impl is bound to Lam7;->c, so we capture exceptions
        //      even if it is not Lz86;. At that call site the Throwable is p3.
        // 6.0.4: SAVE_REPO Lvu7;, Y2D_INTERFACE Lxgd;->e →
        // 6.0.7: SAVE_REPO Lam7;, Y2D_INTERFACE Lt9c;->a.
        firstMethod {
            definingClass == SAVE_REPO && name == SAVE_METHOD
        }.apply {
            // Insert probe 2 first so its index doesn't shift when probe 1 inserts at 0.
            val y2dCallIdx = indexOfFirstInstructionOrThrow {
                (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
                    getReference<MethodReference>()?.let {
                        it.definingClass == Y2D_INTERFACE && it.name == Y2D_ERR_METHOD
                    } == true
            }
            // 6.3.1: the Throwable is whatever register the reporter call passes
            // as its first argument (v0 at q7e.C:8528) — no longer p3. Read it off
            // the invoke, and trace AFTER the call so no scratch register is
            // needed (the registers are untouched by the callee).
            val y2dCall = getInstruction(y2dCallIdx)
            val throwableReg = when (y2dCall) {
                is FiveRegisterInstruction -> y2dCall.registerD
                is RegisterRangeInstruction -> y2dCall.startRegister + 1
                else -> error("DebugLogPatch: unexpected reporter invoke shape ${y2dCall.opcode}")
            }
            addInstructions(
                y2dCallIdx + 1,
                "invoke-static {v$throwableReg}, $DEBUG_TRACE->writeCaught(Ljava/lang/Throwable;)V",
            )

            addInstructions(
                0,
                """
                    const-string v0, "q7e.C ENTRY"
                    invoke-static {v0}, $DEBUG_TRACE->write(Ljava/lang/String;)V
                """,
            )
        }

        // PROBE 3 (retro upsert marker) — DROPPED on 6.0.7. RetroGameDao.upsert
        // no longer exists (the retro-emulator persistence layer was rebuilt;
        // see RETRO_REPO in the map above). Nothing to anchor.

        // PROBE 4 (Room import-transaction markers) — DROPPED on 6.3.1. It was
        // anchored on the STABLE DAO names GameLaunchMethodDao / GameLibraryBaseDao,
        // which no longer exist (every DAO is an R8 letter now; see IMPORT_TXN in
        // the map above). Nothing stable to anchor — same fate as probe 3 on 6.0.7.
        // Up to 6.0.9 it was: Lcb;->w (6.0.8 Lza;->x, 6.0.7 Lza;->w, 6.0.4
        // Lws7;->invokeSuspend) with markEl7Entry + markLaunchInsert/markLibraryInsert
        // before each DAO insert.
    }
}
