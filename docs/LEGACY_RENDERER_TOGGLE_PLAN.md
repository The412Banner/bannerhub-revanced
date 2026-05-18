# Legacy (GLES2) Renderer Toggle — implementation plan

Branch: `feature/legacy-renderer-toggle` off `gamehub-604-build` (post GPU-Spoof merge).
Goal: per-game user choice between **New renderer** (6.0.4 Vulkan, default) and
**Legacy renderer** (6.0.2 GLES2 + ASurfaceTransaction plane compositor).

## Proven (throwaway test1→test3 + user confirmation, 2026-05-17)
- 6.0.2 `libxserver.so`+`libwinemu.so` can be bundled and swapped in.
- JNI bridge works: add native `setRenderingEnabled(Z)V` to XServer
  (`BytecodeUtils.addNativeMethod`) + redirect the 2 `setFlipEnabled(Z)V`
  call sites to it (`BytecodeUtils.redirectVirtualCalls`). Both helpers are
  on `gamehub-604-build` (merged with GPU Spoof, `792ae69`).
- **The legacy renderer DISPLAYS and plays.** God of War ran fine on the
  legacy renderer on `wine_proton10.0-arm64x-2` (arm64x+FEX) —
  user-confirmed; telemetry shows fps=310/299 @ gpuPercent=80 (heavy real
  rendering). The deleted-`DirectRendering` "present crux" is **NOT a
  universal blocker** — it presents for real games as-is.
- **DiRT 3 is an outlier, not the renderer.** Its black-screen and the
  `load_64bit_module c000007b` / `/wine memory region` failure are DiRT-3
  specific (GFWL + 32-bit wow64), independent of renderer. Do NOT
  generalise DiRT 3.
- No hard x64-only constraint: GoW (64-bit) on arm64x works. Some 32-bit
  GFWL/wow64 titles may still need x64+Box64 — that's their issue.

## Real scope (much smaller than the earlier pessimistic take)
Gate the **already-proven** lib-swap + JNI bridge behind a per-game toggle.
No DirectRendering reconstruction required for the general case; only
revisit per-title if a specific game proves to need it.

## Status (2026-05-18)
- **Milestone 1: DONE** (scaffold + per-game pref + menu rows, device-confirmed).
- **Milestone 2: IMPLEMENTED + CI-VERIFIED** (xserver-only-first). Commits
  `75ebe7f` (impl) + CME fix, build run **26022795219** = 0 SEVERE, all 4
  renderer patches `succeeded` 9/9. Mechanism: additive
  `libxserver_legacy.so`; `XServer.<clinit>` loadLibrary →
  `BhRendererController.loadXserver` (Legacy → `System.load` legacy lib,
  else stock, frozen decision, stock fallback never bricks); 2
  `setFlipEnabled` sites → static `flip()` reflective dispatcher
  (`setRenderingEnabled` legacy / `setFlipEnabled` stock); `addNativeMethod`
  shim; new `BytecodeUtils.redirectVirtualToStatic`. libwinemu NOT gated.
- **Milestone 3: NEXT** — device-test the Legacy toggle on a known-good
  title (GoW = proven). Needs the feature on the user's actual install
  (V6 **Lite** `banner.hub`) → same cherry-pick-into-`feature/lite-variant-tier1`
  pattern as the preload-free vibration landing.

## Milestones
1. **Toggle plumbing.** Clone the GPU-Spoof scaffold →
   `BhRendererController` (per-game + global pref, same `pc_g_setting<id>`
   storage pattern) + a "Renderer: New (Vulkan) / Legacy (GLES2)" menu row
   (reuse the gpuspoof menu-injection). Bundle 6.0.2 libs as `*_legacy.so`.
2. **Conditional swap.** Apply the 6.0.2 pair + the `setRenderingEnabled`
   shim + `setFlipEnabled` redirect **only when the per-game pref =
   Legacy**; New mode = stock 6.0.4 untouched (zero regression). Mechanism:
   ship both lib sets + a native-load chooser, or a launch-time swap gated
   on the pref.
3. **Per-game validation + UX.** Test a spread of titles (GoW = known-good).
   Warn that AI frame-gen / HDR / deep GPU-spoof go inert in legacy mode;
   note 32-bit GFWL titles (DiRT-3-class) are out of scope by their own
   wow64/GFWL issues, not the renderer.

## Per-game-from-menu gap (shared by Vibration/GPU-Spoof/Renderer) + fix

All three resolve gameId via `sniffGameIdFromStack()` = a *running*
WineActivity. From a pre-launch More Menu / library popup there is none →
**all three fall back to GLOBAL prefs** (confirmed by user: Vibration is
global from both menus too). Not a Renderer bug — universal & pre-existing.

**Investigation result (2026-05-17):** the real per-game id source =
`com.xiaoji.egggame.game.di.model.game.GameInfo` — a **kept-name (non-R8)**
class with **`getServerGameId()I`** (== the `pc_g_setting<id>` / launchLog
`gameId`). In scope in BOTH builders: `Lx57;->a(Lf37;…)` (More Menu) and
`Lpzc;->j0(Laub;…)` (library popup) — stock rows read
`GameInfo.getServerGameId()` (e.g. `pzc.j0` ~lines 2146/2180/2192);
`Laub`/`Lf37` carry the game context (`libraryGameId`).

**Fix (shared, build once, apply to all three):** at each menu-row
injection site, capture the in-scope `GameInfo` register, call
`getServerGameId()`, and thread it into the click handler → settings
Intent extra (replacing `sniffGameIdFromStack`). This is exactly how stock
"PC Game Settings" is per-game.

**Also:** GPU-Spoof & Renderer were NEVER added to the library per-game
popup (`Lpzc;->j0`/`Lz4e`) — only Vibration has it
(`appendLibraryPopupRow`, which needs the `Lxd3;->l1` resolver). gpuspoof
dropped that path over the l1-ANR (stacking a 2nd l1 head-block). Adding
GPU-Spoof/Renderer to the library popup must avoid a 2nd l1 hook (share
Vibration's single resolver, or raw-label route).

## Reusable assets already in-tree
`BytecodeUtils.addNativeMethod`, `BytecodeUtils.redirectVirtualCalls`,
the GPU-Spoof per-game-pref + menu-injection pattern.

## Lesson recorded
Do not generalise DiRT 3's behaviour to the renderer; validate renderer
questions with a clean title (GoW) before concluding.
