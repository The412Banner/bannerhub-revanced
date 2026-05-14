package app.revanced.patches.gamehub.misc.analytics

import app.revanced.patcher.extensions.addInstructions
import app.revanced.patcher.firstMethod
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.gamehub.GAMEHUB_PACKAGE
import app.revanced.patches.gamehub.GAMEHUB_VERSION
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// =============================================================================
// Pure-stub neutralization of XiaoJi's two analytics-event reporters. Both
// methods early-return a fake success instance before any URL is allocated,
// HTTP client touched, socket opened, or radio woken.
//
// Surface (anchors valid for GameHub 6.0.4 — class letters reshuffle on every
// minor base bump; structural string + signature anchors stay stable):
//
//   - Lcx5;->a(Collection, Continuation)Object
//     Sends a BATCH of analytics events to /events.
//     Anchor: method named "a" on Lcx5; whose body references the production
//     URL "https://statistic-gamehub-api.vgabc.com/events". Caller Lazi; does
//     `check-cast … Lyw5;` on the result, so we must return a Lyw5 instance
//     (4-field data class: boolean success, Integer code, String msg,
//     Throwable err, plus int default-mask).
//
//   - Loh4;->b(int, long, Continuation)Object
//     Reports a device-performance-config event to
//     /events/device-performance-config. The URL string itself lives in the
//     lambda body Lnh4;->invokeSuspend, but the OUTER public method Loh4;->b
//     is what 5+ external classes (lh4, zz3, xz3, uz3, b04) actually invoke.
//     Anchor: method named "b" on Loh4; with signature `(IJLci3;)Ljava/lang/Object;`.
//     Caller does `check-cast … Lxnm;` on the result, so we must return a
//     Lxnm instance (2-field data class: int, LinkedHashSet).
//
// Why stubbing at the public method (not the lambda invokeSuspend) for Loh4:
// callers' check-cast contracts force a specific concrete return type.
// Returning Unit.INSTANCE deeper down would unwind through the runCatching
// frame back to Loh4;->b which would then try to build a Lxnm from a Unit
// value and crash.
//
// Why a body-string anchor for Lcx5 but not Loh4: cx5's body contains the URL
// const-string directly, so it's the natural anchor. oh4.b doesn't — the URL
// only appears in nh4.invokeSuspend. The (IJLci3;)Object signature is unique
// enough on Loh4 to anchor by parameter shape instead. If both methods on a
// future Loh4 happened to share that signature, fall back to "class whose
// public method 'b' is reachable from a method that constructs Lnh4 (the
// lambda whose body contains '/events/device-performance-config')".
//
// How to re-derive class letters on a future base bump (any of the 4 letters
// may shift; the search recipes don't):
//   - Lcx5 = grep smali for production URL `"https://statistic-gamehub-api.vgabc.com/events"`
//           (with no trailing path) → the .class line of the file is Lcx5
//   - Loh4 = grep smali for `"/events/device-performance-config"` → that file
//           is Lnh4; its first constructor parameter type is Loh4
//   - Lyw5 = grep `azi.smali` for the `check-cast … L…;` that immediately
//           follows the invoke-virtual of Lcx5;->a — the class is Lyw5;
//           verify: 4 fields (Z, Integer, String, Throwable) + int ctor mask
//   - Lxnm = grep any caller of Loh4;->b (zz3 / lh4 / etc.) for the
//           check-cast that immediately follows; verify 2 fields (I, Set)
// =============================================================================

private const val LCX5 = "Lcx5;"
private const val LOH4 = "Loh4;"
private const val LYW5 = "Lyw5;"
private const val LXNM = "Lxnm;"

// NOTE: invoke-direct's standard form (Dalvik format 35c) is capped at 5
// registers. The Lyw5 constructor takes 6 args (Z, Integer, String, Throwable,
// I + the implicit `this` = 6 total registers), so we use invoke-direct/range.
// (Format 35c silently drops the instruction at assembly time without raising
// SEVERE — first attempt hit this exact pitfall.)
private val returnYw5Success = """
    new-instance v0, $LYW5
    const/4 v1, 0x1
    const/4 v2, 0x0
    const/4 v3, 0x0
    const/4 v4, 0x0
    const/4 v5, 0x0
    invoke-direct/range {v0 .. v5}, $LYW5-><init>(ZLjava/lang/Integer;Ljava/lang/String;Ljava/lang/Throwable;I)V
    return-object v0
""".trimIndent()

private val returnXnmEmpty = """
    new-instance v0, $LXNM
    const/4 v1, 0x0
    new-instance v2, Ljava/util/LinkedHashSet;
    invoke-direct {v2}, Ljava/util/LinkedHashSet;-><init>()V
    invoke-direct {v0, v1, v2}, $LXNM-><init>(ILjava/util/LinkedHashSet;)V
    return-object v0
""".trimIndent()

@Suppress("unused")
val stubAnalyticsEventsPatch = bytecodePatch(
    name = "Stub analytics events",
    description = "Pure-stub neutralization of XiaoJi's two analytics-event " +
        "reporters. Lcx5;->a (general /events POST batches) and Loh4;->b " +
        "(/events/device-performance-config) early-return fake success instances " +
        "before any URL string is built or HTTP client touched. Zero outbound " +
        "traffic to statistic-gamehub-api.vgabc.com, zero retry attempts, zero " +
        "battery / radio wake from telemetry. Internal coroutine state machines " +
        "(Lbx5, Llh4, Lmh4, Lnh4) become unreachable dead code. Complements " +
        "Plan 4 (Disable Firebase Analytics) and Plan 10 (Disable GMS Measurement) " +
        "to fully silence XiaoJi-side, Firebase-side, and GMS-side telemetry " +
        "without any Worker-side redirect or trust shift.",
) {
    compatibleWith(GAMEHUB_PACKAGE(GAMEHUB_VERSION))

    apply {
        // Lcx5;->a — anchor on URL string in body
        firstMethod {
            name == "a" &&
                definingClass == LCX5 &&
                implementation?.instructions?.any { ins ->
                    (ins as? ReferenceInstruction)?.reference
                        ?.let { it is StringReference &&
                            it.string == "https://statistic-gamehub-api.vgabc.com/events" } == true
                } == true
        }.addInstructions(0, returnYw5Success)

        // Loh4;->b — anchor on class + name + (int, long, Continuation) signature
        firstMethod {
            name == "b" &&
                definingClass == LOH4 &&
                returnType == "Ljava/lang/Object;" &&
                parameters.size == 3 &&
                parameters[0].toString() == "I" &&
                parameters[1].toString() == "J"
        }.addInstructions(0, returnXnmEmpty)
    }
}
