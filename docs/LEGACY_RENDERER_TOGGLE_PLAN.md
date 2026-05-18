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

## Reusable assets already in-tree
`BytecodeUtils.addNativeMethod`, `BytecodeUtils.redirectVirtualCalls`,
the GPU-Spoof per-game-pref + menu-injection pattern.

## Lesson recorded
Do not generalise DiRT 3's behaviour to the renderer; validate renderer
questions with a clean title (GoW) before concluding.
