# xserver_shim — legacy GLES2 renderer wrapper for GameHub 6.0.7

**Status: SKELETON / DRAFT. Compiles; not yet device-validated.** Solves only the JNI
command-surface mismatch from `docs/LEGACY_RENDERER_607_SHIM_RECON.md`. The two device-only
unknowns (does the GLES2 engine composite under 6.0.7's single-process model; the
`libwinemu`/`DirectRendering` coupling) are still open and out of scope for this file.

## What it does

Builds a wrapper **`libxserver.so`**. The per-game legacy swap loads this instead of the
raw `libxserver_legacy.so` (6.0.2). On load it:

1. `dlopen`s the legacy lib (a plain `dlopen` does **not** run its `JNI_OnLoad`).
2. Runs the legacy `JNI_OnLoad` with the global JNI table's `RegisterNatives` temporarily
   redirected to our hook — **harvesting the 11 real legacy fn-pointers** and ensuring the
   two methods 6.0.7 deleted (`setRenderingEnabled`, `setSurfaceFormat`) are **never named
   against the live class** (that naming is what aborts the unmodified lib).
3. Publishes a **40-entry** table onto `com/winemu/core/server/XServer`:
   - **9 forward** (captured ptr; `surfaceChanged` is wrapped to also inject `setSurfaceFormat`),
   - `setGpuPassthroughEnabled` → drives the captured `setRenderingEnabled(true)` (the proven 6.0.4 force-on remap),
   - `stop` → best-effort teardown,
   - **29 `effects*`** → inert stubs.

See `docs/LEGACY_RENDERER_607_SHIM_RECON.md` Appendix A for the recovered legacy table.

## Build (standalone, for iteration)

```sh
# requires the Android NDK; arm64 only
$NDK/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android30-clang \
    -shared -fPIC -O2 -o libxserver.so xserver_shim.c -llog
# or via CMake:
cmake -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -B build .
cmake --build build
```

In CI this should build the same way the old `native/evshim/` step did (NDK + a Gradle/CI
hook), and the resulting `libxserver.so` gets bundled by the renderer patch alongside the
renamed `libxserver_legacy.so` + `libwinemu_legacy.so`.

## Wiring into the patch (when promoted past skeleton)

- Rename the bundled legacy lib so the wrapper can `dlopen` it by a distinct name
  (`libxserver_legacy.so`) while the wrapper itself ships as `libxserver.so`.
- Point `BhRendererController.loadXserver` at the wrapper for legacy-mode games.
- Un-pin the renderer patches from `compatibleWith("6.0.4")`.

## Device-tuning TODOs (the parts no static analysis can settle)

1. **`DEFAULT_SURFACE_FORMAT`** — 6.0.7 stopped calling `setSurfaceFormat(I)V`; confirm the
   right value/ordering (capture what 6.0.4 passed, or try the GLES2 default).
2. **Does it composite?** Build, force a Vulkan-regressed title to legacy, confirm: no
   `SIGABRT`, then a rendered frame. `/proc/<pid>/maps` should show the wrapper + the
   `_legacy` pair, no stock libs.
3. **`libwinemu`/`DirectRendering`** — if a frame still doesn't present, the 6.0.4
   "force `setRenderingEnabled(true)` self-drive" path may need the 6.0.2 orchestration
   ported (the larger job in §6 of the recon doc).
4. **`mprotect` of the JNI table** is process-wide for one call on the load thread — fine
   here, but verify on a CheckJNI/debuggable build (BannerHub ships debuggable).
