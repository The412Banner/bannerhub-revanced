# Legacy GLES2 Renderer on GameHub 6.0.7 — Recon & Shim Spec

*Status: static recon complete (2026-06-07). No code written yet. The renderer
patches remain pinned to `compatibleWith("6.0.4")`; this doc scopes what it would
take to bring the legacy GLES2 renderer back on the 6.0.7 base.*

---

## TL;DR

- The legacy GLES2 renderer is a **closed-source native pair** — `libxserver.so` +
  `libwinemu.so` — from the 6.0.2 era (build tree `WinEmuKernel4`).
- On **6.0.4** it dropped straight in: the app and the old engine spoke nearly the
  same command set (**10 of 11 commands matched**; one was renamed). A one-method
  shim fixed it. ✅
- On **6.0.7** the renderer subsystem was **rewritten**: the command set grew from
  **11 → 40**, and **two commands the old engine announces at startup were deleted**.
  The old engine aborts the instant it loads. ❌ (device-confirmed `SIGABRT`).
- **It is not a dead end.** The crash is a **command-list (JNI) mismatch**, which is
  fixable with a small **wrapper library** (a "translator") that re-publishes the old
  engine's functions under the new 40-command contract. The hard part — the actual
  screen-drawing/transport contract — **already matches**.
- **Two things still can't be proven on paper**: (1) whether the old engine actually
  composites a frame under 6.0.7's single-process model, and (2) the `libwinemu`
  partner-lib coupling. Both need a build + device run.

---

## 1. Background — why GLES2 was dropped on 6.0.7

GameHub's in-Wine display server (the "X-server" / compositor) lives in a native
pair, `libxserver.so` + `libwinemu.so`. XiaoJi switched its rendering backend from
**GLES2** (≤ 6.0.2) to **Vulkan** in 6.0.4, and some older titles regressed. BannerHub's
"Legacy renderer" toggle restored the proven 6.0.2 **GLES2** pair, swapped in per-game.

On 6.0.7 the toggle was removed and pinned to 6.0.4, because forcing it crashes. This
doc explains exactly why, and how it could be restored.

---

## 2. Root cause — JNI command-surface drift (not an architecture wall)

The app's Java class `com.winemu.core.server.XServer` declares a set of `native`
methods. When a renderer `.so` loads, its `JNI_OnLoad` runs `RegisterNatives(...)` to
bind its C functions to those Java methods **by name + signature**.

The 6.0.2 `libxserver.so` exports **zero** `Java_*` symbols — it registers all of its
**11** natives dynamically inside `JNI_OnLoad`. Two of those 11 names (`setRenderingEnabled`,
`setSurfaceFormat`) **were deleted from the 6.0.7 `XServer` class**. So on 6.0.7 the old
engine's `JNI_OnLoad` reaches for a method that no longer exists → `NoSuchMethodError`
mid-registration → ART aborts the whole process before a single frame is drawn.

**Already captured on device** (earlier 6.0.7 test, `gamehub.lite`):

```
F libc:   Fatal signal 6 (SIGABRT) … gamehub.lite
JNI DETECTED ERROR: java.lang.NoSuchMethodError:
  no … method "Lcom/winemu/core/server/XServer;.setSurfaceFormat(I)V"
  at XServer.<clinit>  ← libxserver_legacy.so (JNI_OnLoad+128)
```

This is the **shimmable** failure class (a registration mismatch), not the
"engine hard-depends on a vanished process model" class.

---

## 3. Evidence — the command-set diff

Artifacts (all on disk):

| Item | Path | md5 |
| --- | --- | --- |
| Legacy GLES2 `libxserver` (6.0.2) | `patches/.../legacyrenderer/libxserver_legacy.so` | `e8eb8948…` |
| Legacy `libwinemu` (6.0.2) | `patches/.../legacyrenderer/libwinemu_legacy.so` | `407f274d…` |
| Stock 6.0.7 `libxserver` | `gh607-apktool-d/lib/arm64-v8a/libxserver.so` | — |
| 6.0.7 `XServer` class | `gamehub-6.0.7-jadx/.../com/winemu/core/server/XServer.java` | — |

Counts:

- **6.0.7 `XServer` declares 40 native methods.** Stock 6.0.7 lib provides 40/40.
- **Legacy 6.0.2 lib provides 11 methods.** They reconcile exactly:
  - **9** are still present in 6.0.7 (the core renderer/transport/input set) → **names AND signatures match**.
  - **2** (`setRenderingEnabled`, `setSurfaceFormat`) were **deleted** in 6.0.7 → these are the crash trigger.
- **6.0.7's 31 extra methods** = **29 `effects*`** (a new ReShade-style post-processing
  subsystem) + **`setGpuPassthroughEnabled`** + **`stop`**.

---

## 4. The 40-command contract (categorized)

| Class | Count | Methods | Plan |
| --- | --- | --- | --- |
| **Forward** (old engine already provides; name+sig match) | 9 | `surfaceChanged(Surface)`, `setShmPath(String)`, `start(String,String[])Z`, `startUI()`, `sendKeyEvent(IIZ)Z`, `sendMouseEvent(FFIZZ)`, `sendTextEvent([B)`, `sendTouchEvent(IIII)`, `sendWindowChange(IIILjava/lang/String;)` | Pass straight through to the old engine's real function. |
| **Bridge** (no direct old equivalent, but mappable) | 2 | `setGpuPassthroughEnabled(Z)`, `stop()Z` | `setGpuPassthroughEnabled` → drive the old engine's `setRenderingEnabled` (same semantic remap the 6.0.4 toggle used). `stop` → old teardown or safe no-op. |
| **Stub** (optional eye-candy, never needed to run a game) | 29 | all `effects*` (ReShade FX: presets / techniques / uniforms) | Return empty/false/null/0. Cleanly disables filters; the renderer is unaffected. |
| **(legacy-only, must be hidden)** | 2 | `setRenderingEnabled`, `setSurfaceFormat` | **Do NOT register against the 6.0.7 class** (they don't exist there) — this is what removes the crash. `setSurfaceFormat` is still called *internally* by the shim (see §5). |

---

## 5. How to teach the old engine the right commands — the wrapper ("translator")

The old engine's functions are **not exported by name** (all-dynamic registration), so
we can't just `dlsym` them. The clean technique is **RegisterNatives interception**:

Ship our own `libxserver.so` (the wrapper) as the library the app loads. Its `JNI_OnLoad`:

1. **`dlopen` the real legacy `.so`** (a plain `dlopen` does *not* trigger its
   `JNI_OnLoad`, so it can't crash yet).
2. **`dlsym("JNI_OnLoad")`** on the legacy lib and **call it ourselves**, but hand it a
   **fake `JavaVM`/`JNIEnv`** whose `RegisterNatives` is **our** function.
3. When the legacy `JNI_OnLoad` calls `RegisterNatives(XServer, methods[11], 11)`, our
   hook **captures the real C function pointers** (keyed by name) instead of registering
   them — including the two deleted ones, harmlessly, since we never touch the real class.
4. **Build our own 40-entry table** for the real 6.0.7 `XServer` class:
   - 9 **forward** entries → the captured legacy pointers,
   - `setGpuPassthroughEnabled` → a tiny wrapper that calls the captured
     `setRenderingEnabled` pointer (forced-on is the proven 6.0.4 behaviour),
   - `stop` → captured teardown or no-op,
   - 29 `effects*` → our stub functions.
5. **Call the *real* `RegisterNatives`** with our 40-entry table. No deleted method is
   ever named against the 6.0.7 class → **no crash**, and every command the app can
   issue is now answered.
6. **Call `setSurfaceFormat` ourselves** with a sane default at the right moment (inside
   `surfaceChanged`/`start`), since 6.0.7 no longer calls it but the GLES2 engine still
   needs its surface format set.

Net: ~9 forwards + ~2 bridges + 29 trivial stubs + 1 interception harness. Bounded.

---

## 6. The second wall — `libwinemu` + actually compositing

Two things the JNI fix above does **not** settle:

- **`libwinemu` coupling.** The 6.0.2 `libwinemu.so` statically exports
  `Java_com_winemu_core_DirectRendering_…` against `com.winemu.core.DirectRendering`,
  a class **6.0.4 already deleted**. The 6.0.4 toggle worked anyway because the GLES2
  engine **self-drives once `setRenderingEnabled(true)` is called** — no DirectRendering
  port was needed (device-proven on 6.0.4). Whether the same holds on 6.0.7 is unproven.
- **Does it paint a frame?** Even with all 40 commands answered, the old GLES2 context
  has to actually light up and present into 6.0.7's surface under the **single-process**
  model (6.0.7 folded the old `:wine` subprocess into the main process). The fact that
  `setShmPath` + `surfaceChanged` already match is a strong omen, but only a build +
  device run can confirm pixels on screen.

---

## 7. Risks & unknowns

| # | Risk | Resolves how |
| --- | --- | --- |
| 1 | Exact abort site | **Already known** — `setSurfaceFormat` at `JNI_OnLoad+128` (captured). A fresh tombstone is confirmation only. |
| 2 | `setSurfaceFormat` no longer called → wrong/unset surface format | Shim calls it internally with a default; tune on device. |
| 3 | Single-process GLES2 context doesn't present | Build the wrapper, run on device, read `/proc/<pid>/maps` + logcat. |
| 4 | `libwinemu`/DirectRendering orchestration needed after all | Falls back to the 6.0.4 "force `setRenderingEnabled(true)`, self-drive" approach; if insufficient, port the 6.0.2 Java orchestration (larger). |
| 5 | Effects stubs return wrong shapes → NPE in app UI | Match declared return types exactly (empty arrays, `false`, `null`, `0L`). |

---

## 8. Effort & next steps

**Effort:** a bounded native (C/JNI) component + a small ReVanced resource/patch to ship
the wrapper as `libxserver.so` and keep bundling the legacy pair. Days, not weeks —
*if* §6 (runtime compositing) cooperates. The real schedule risk is entirely in §6, not
in the command-list translation, which is well-understood.

**Next steps, in order:**
1. ~~Enumerate the legacy lib's full 11-entry `RegisterNatives` table.~~ **DONE — see Appendix A.**
2. Build the wrapper `libxserver.so` (interception harness + 40-entry table + stubs).
3. Repoint the per-game legacy swap to load the wrapper instead of the raw legacy lib,
   un-pin from `6.0.4`, build artifact-only.
4. **Device test** a known Vulkan-regressed title: confirm no `SIGABRT`, then confirm
   a rendered frame. Read `/proc/<pid>/maps` to prove the wrapper + legacy pair are live.

**Payoff check:** on 6.0.7 the Vulkan X-server is the default everywhere and there's a
native GPU spoof, so this only helps the specific older titles that regressed under
Vulkan. Worth it if those titles matter; the cost is the §6 runtime unknown.

---

*Recon by static analysis of the on-disk 6.0.7 decompile + the bundled 6.0.2 legacy
pair. Companion: `docs/LEGACY_RENDERER_TOGGLE_PLAN.md` (the 6.0.4 implementation),
auto-memory `project_bannerhub_revanced_legacy_gles2_renderer.md`.*

---

## Appendix A — Legacy lib `RegisterNatives` table (binary-recovered)

Recovered statically from `libxserver_legacy.so` (md5 `e8eb8948…`) by locating the
`JNINativeMethod` array directly in `.data` (the lib uses **dynamic** registration —
0 `Java_*` exports; the array is at `.data+0x8`, **11 entries, 24-byte stride**, ending
at `.data+0x110`). Registered onto class **`com/winemu/core/server/XServer`**
(`JNI_OnLoad` @ `0x8888c`). `fn` = address of the native function in `.text`.

| # | name | signature | fn (.text) | 6.0.7 disposition |
|---|------|-----------|-----------|-------------------|
| 1 | `startUI` | `()V` | `0x88944` | **forward** |
| 2 | `start` | `(Ljava/lang/String;[Ljava/lang/String;)Z` | `0x88964` | **forward** |
| 3 | `setShmPath` | `(Ljava/lang/String;)V` | `0x88c10` | **forward** |
| 4 | `surfaceChanged` | `(Landroid/view/Surface;)V` | `0x88c7c` | **forward** |
| 5 | `sendWindowChange` | `(IIILjava/lang/String;)V` | `0x88cb4` | **forward** |
| 6 | `sendMouseEvent` | `(FFIZZ)V` | `0x88d64` | **forward** |
| 7 | `sendTouchEvent` | `(IIII)V` | `0x88f58` | **forward** |
| 8 | `sendKeyEvent` | `(IIZ)Z` | `0x89070` | **forward** |
| 9 | `sendTextEvent` | `([B)V` | `0x890b4` | **forward** |
| 10 | `setRenderingEnabled` | `(Z)V` | `0x891e8` | **deleted in 6.0.7** → don't register against the class; reuse this fn-ptr to back 6.0.7's `setGpuPassthroughEnabled(Z)` (forced-true = the proven 6.0.4 behaviour) |
| 11 | `setSurfaceFormat` | `(I)V` | `0x89200` | **deleted in 6.0.7** → don't register; the wrapper **calls this fn-ptr itself** with a sane default (6.0.7 never calls it) |

**Forward map for the wrapper:** 9 of the 11 map 1:1 (name + signature) onto the 6.0.7
`XServer` class — register those straight through to the harvested fn-ptrs. The remaining
2 are exactly the methods 6.0.7 deleted (the crash triggers): keep their fn-ptrs but
**never name them against the 6.0.7 class** — instead drive `setRenderingEnabled` via the
new `setGpuPassthroughEnabled` entry, and invoke `setSurfaceFormat` internally from the
wrapper's `surfaceChanged`/`start`.

This locks the entire forward half of the 40-entry wrapper table. The only entries still
needing bodies are the **29 `effects*` stubs**, `stop`, and the `setGpuPassthroughEnabled`
shim — none of which depend on device data.

*Recovered offline 2026-06-07; reproducible with `docs/` script notes (ELF `.data` scan
for the `{char* name; char* sig; void* fn}` triple — reloc-format-agnostic, since the lib
ships packed/RELR relocations that store link-time VAs in-slot).*
