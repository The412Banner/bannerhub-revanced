# Legacy (GLES2) Renderer Toggle — implementation plan

Branch: `feature/legacy-renderer-toggle` off `gamehub-604-build` (post GPU-Spoof merge).
Goal: per-game user choice between **New renderer** (6.0.4 Vulkan, default) and
**Legacy renderer** (6.0.2 GLES2 + ASurfaceTransaction plane compositor).

## Proven so far (throwaway test1→test3, 2026-05-17)
- 6.0.2 `libxserver.so`+`libwinemu.so` can be bundled and swapped in.
- JNI bridge works: add native `setRenderingEnabled(Z)V` to XServer
  (`BytecodeUtils.addNativeMethod`) + redirect the 2 `setFlipEnabled(Z)V`
  call sites to it (`BytecodeUtils.redirectVirtualCalls`). Both helpers are
  now on `gamehub-604-build` (merged with GPU Spoof).
- On **x64 + Box64** containers: 6.0.2 pair LOADS and the game RUNS and
  produces frames (fps=29, 110s, DiRT 3).
- On **arm64x/FEX**: 6.0.2 libwinemu fails the wow64 bootstrap
  (`load_64bit_module c000007b` / `/wine memory region`). => legacy mode
  must require/auto-pair an x64 container.

## THE OPEN BLOCKER (the crux)
test3 = **black screen + audio while fps=29**. The game renders but frames
never reach the Android surface. 6.0.4 deleted `com.winemu.core.DirectRendering`
(+`$Companion`) and `DirectRenderingActivationView`; the 6.0.2 libxserver/
libwinemu present path is *driven through* that deleted Java orchestration
(create DirectRendering → `nativeInitialize(Surface,...)` → state listener).
Without restoring it, legacy mode = black screen. **Solving this is the
feature; everything else is plumbing.**

## Milestones
1. **DirectRendering restoration (HARD, do first).** Reconstruct
   `com.winemu.core.DirectRendering` + `$Companion` as smali from the 6.0.2
   APK, with the native decls libwinemu statically exports
   (`nativeInitialize`, `nativeSetSurfaceFormat`, `nativeStartTestClient`,
   `onDirectRenderingStateChanged`), and port the 6.0.2 Java call sequence
   that hands the WineActivity Surface to it. Validate: a game *displays*
   in an always-legacy build (no toggle yet).
2. **Gate behind a per-game pref + UI** (only after #1 displays). Clone the
   GPU-Spoof scaffold (`BhGpuSpoofController`/menu-row pattern) →
   `BhRendererController` + a "Renderer: New / Legacy (GLES2)" row. Bundle
   libs as `*_legacy.so`; smali hook on the native lib load to pick the
   pair per the pref; apply the JNI shim+redirect only in legacy mode.
3. **x64 container guard + warnings.** Refuse/auto-switch legacy mode unless
   the container is x64+Box64; warn that AI frame-gen / HDR / deep GPU-spoof
   go inert in legacy mode.

## Reusable assets already in-tree
`BytecodeUtils.addNativeMethod`, `BytecodeUtils.redirectVirtualCalls`,
the GPU-Spoof menu-injection + per-game-pref pattern.
