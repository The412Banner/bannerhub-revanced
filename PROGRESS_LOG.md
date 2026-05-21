# BannerHub ReVanced — GameHub 6.0 Port Progress Log

## 2026-05-21 — feature/local-gameid-assignment pre1: synthesize server_game_id for -1 rows

### Background
A user-supplied screenshot of the "View All Games" dialog showed 3 imported PC games — Dispatch, Elementallis, Quartet — all displayed with `ID: -1`. Schema dump of `db_game_library.db` (`dbgl_dump.db`, on-device sample) confirms the value isn't a display artifact:

```
t_game_library_base.server_game_id INTEGER NOT NULL
  -1     | 'local_eMB3uA3zTMKX8hrx4mIkow'  | source_type=0  | 'Blur'          ← catalog miss
  49908  | 'local_PABUmurJS8u8D0wpv2BfDg'  | source_type=0  | 'God of War'    ← real GH id
  131962 | 'local_McKtgYBRTPqk0LhFYFKIeQ'  | source_type=0  | 'Dirt 3'        ← real GH id
  135805 | 'local_Nh0ZuHZBRgqjBli7rKceRQ'  | source_type=0  | 'PRAGMATA'      ← real GH id
```

GameHub assigns `server_game_id = -1` to PC-imported games when its catalog lookup finds no match. Every unmatched game collapses to the same `-1`, which collides at `DeepLinkActivity` because the deep-link dispatch parses `app_nav_game_id` as Integer (per `ExternalLauncher.java` doc comment). Beacon / ES-DE / Daijishou therefore can't address those games individually — they show up in the library and play fine in-app, but won't launch from external front ends.

### Fix design
New patch + extension that rewrites every `-1` row to a stable synthetic integer:

```
synthetic = (id.hashCode() & 0x3FFFFFFF) | 0x40000000
          ∈ [0x40000000, 0x7FFFFFFF]
          ∈ [1,073,741,824, 2,147,483,647]
```

- **Stable** — `id` is the `local_<UUID>` TEXT assigned by GameHub at game-import time and never rewritten across app restarts, library refreshes, or game moves. `String.hashCode()` is JLS-spec-stable.
- **Collision-safe** — 2^30 value space; birthday-paradox 50% threshold ≈ 32 768 games. Effectively zero for the few hundred unmatched games a single user accumulates.
- **Range-safe** — fits Java's signed 32-bit Integer (required by 6.0.4's `Integer.parseInt(app_nav_game_id)`); never collides with real GameHub catalog IDs (~10^5 in observed samples) or Steam appids (~10^7).
- **Idempotent + self-healing** — re-runs match only `server_game_id = -1`, so previously-assigned synthetic rows stay put, and if GameHub later overwrites a row with a real catalog ID, we stop touching it.

### Files added
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/localgameid/LocalGameIdAssignment.java`
  - Public entrypoint `scanAndAssign(Context)` invoked from the bytecode hook.
  - Single-shot daemon thread (priority MIN) so Application.onCreate never waits on disk I/O.
  - Opens `db_game_library.db` `OPEN_READWRITE` (WAL-compatible with GameHub's own writer).
  - SELECT `_id, id` WHERE `server_game_id = -1`; per row computes the synthetic; UPDATE in a single transaction keyed by `_id`; logs `assigned synthetic server_game_id to N row(s)`.
  - All throwables caught and logged — Application startup never gated on this.
- `patches/src/main/kotlin/app/revanced/patches/gamehub/localgameid/LocalGameIdAssignmentPatch.kt`
  - Anchors on `Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate()V` (stable non-mangled class name confirmed via `DisableMobPushPatch`).
  - Injects one `invoke-static` at index 0 passing `p0` (the Application "this" reference, a Context) to `LocalGameIdAssignment.scanAndAssign`.
  - Verifier-safe: single void-returning invoke, no v0 reuse, no move-result.
  - Depends on `sharedGamehubExtensionPatch`.

### Hash sanity-check (Python-equivalent of Java String.hashCode())
```
local_eMB3uA3zTMKX8hrx4mIkow → 1,566,639,775 (0x5D61069F) ✓ in range
local_PABUmurJS8u8D0wpv2BfDg → 2,072,875,144 (0x7B8D9088) ✓ in range
local_McKtgYBRTPqk0LhFYFKIeQ → 1,819,924,988 (0x6C79D9FC) ✓ in range
local_Nh0ZuHZBRgqjBli7rKceRQ → 1,367,434,163 (0x518163B3) ✓ in range
```
All four distinct, all positive, all inside `[0x40000000, 0x7FFFFFFF]`. The first row (Blur, currently at -1) would become `1,566,639,775` after the first onCreate. The other three are already non-negative real catalog IDs and the scanner leaves them alone.

### Branch
- `feature/local-gameid-assignment` off `gamehub-604-build@616d0ea`
- Commits:
  - `6e404e5` — extension (scanner)
  - `acc7dfe` — patch (BaseAndroidApp.onCreate hook)
  - `a1b218d` — docs (this entry's earlier draft)
  - `80eeaca` — fix: filter `firstMethod` predicate by `implementation != null` (pre1 → pre2)
  - `480e1c1` — fix: `move-object/from16 v0, p0` before invoke-static for high-register encoding (pre2 → pre3)
- Pre1 CI: run [26235997506](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26235997506) — SEVERE on all 9 variants.
- Pre2 CI: run [26236629500](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26236629500) — still SEVERE.
- Pre3 CI: run [26237217658](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26237217658) ✅ all 9 variants, **`INFO: "Local game-id assignment" succeeded`**, 0 SEVERE, 38 patches succeeded on Normal-GHL.
- APK staged: `/storage/emulated/0/Download/local-gameid-pre3/BannerHub-V6-1.5.0-604-local-gameid-pre3-Patched-Normal-GHL.apk` (~111 MB).

### Pre1 / pre2 / pre3 — what went wrong, what fixed it
**Pre1 failure (all 9 variants):** `SEVERE: "Local game-id assignment" failed: NullPointerException — Cannot invoke ClassDef.getMethods() because "classDef" is null` at `Instruction.kt:114` inside `addInstructions`. Initial hypothesis: `firstMethod` matched a method REFERENCE (from some subclass's invoke-super) instead of the concrete `BaseAndroidApp.onCreate` definition.

**Pre2 fix:** Added `implementation != null` to the `firstMethod` predicate (the Mob patch's implicit filter). **Did not resolve the SEVERE** — identical NPE.

**Real root cause (found by disassembling the base APK):** `BaseAndroidApp.onCreate` is declared `.registers 55` with 1 parameter (`this`), so `p0` resolves to `v54`. `invoke-static` encodes its register list with 4-bit nibbles (max v15). My pre1/pre2 snippet `invoke-static {p0}, ...` could not be encoded → smali parser failed deep inside Patcher's instruction-construction code, surfacing as the `classDef is null` NPE rather than a clearer "register out of range" error.

**Pre3 fix:** Mirror the working pattern from `ExternalLauncherPatch` — emit `move-object/from16 v0, p0` first (encoded as 16-bit dest + 16-bit src, valid for any register), then `invoke-static {v0}, ...`. The original `BaseAndroidApp.onCreate` opens with the same `move-object/from16 v0, p0`, so our prepended pair is shape-consistent and the verifier accepts the join cleanly.

### Lessons captured (memory)
- Any patch injecting into a method with `.registers > 16` MUST move high-register parameters into v0–v15 before any 3rc/35c-encoded invoke. The clearer "register out of range" surfaces as the cryptic `classDef is null` NPE from Patcher; if you see that NPE, check `.registers N` on the target method first.
- `firstMethod` predicate should always include `implementation != null` to avoid matching method references vs definitions — even when not strictly required, it eliminates a class of error.

### Device-test resumption order
1. Install `local-gameid-pre3-Patched-Normal-GHL.apk` over current build.
2. Open GameHub; confirm "View All Games" dialog no longer shows `ID: -1` for unmatched imports (each shows a number in the 1.07B–2.15B range).
3. In Beacon / ES-DE / Daijishou, configure a launch entry for one previously-unlaunchable game using the new ID; confirm launch.
4. Optional: copy ID from one game, kill app, reopen, verify same ID — confirms `String.hashCode()` stability across process restarts.
5. On success: merge to `gamehub-604-build` (--no-ff), refresh Lite (--no-ff), per the standard merge workflow.



### Why no UI changes
The scanner has no UI of its own and adds no menu rows — it's a silent fix. The existing "View All Games" dialog and "Show Game ID" menu row will both stop showing `-1` for these rows after the scan runs (they query the same `server_game_id` column). External launcher routing continues to work via the existing `ExternalLauncher` extension; no changes there.

### Not enrolled into Banner Tools
Per the enrollment rule, only patches that ADD a new top-level menu row need to enroll into Banner Tools. This patch is invisible at the UI level — no row to enroll.

## 2026-05-21 — Banner Tools pre2 MERGED to gamehub-604-build + Lite refreshed

Device-confirmed pre2 (1×4 tile dialog, 56dp vector icons) merged into both branches.

- **`gamehub-604-build`** ← `feature/banner-tools-menu` via `--no-ff` merge commit `0021862` ("Merge branch 'feature/banner-tools-menu' — Banner Tools consolidated menu (pre2)"). 13 files / +1050 / −9.
- **`feature/lite-variant-tier1`** ← `gamehub-604-build` via `--no-ff` merge commit `1f07d33` ("merge gamehub-604-build: Banner Tools consolidated menu (pre2)"). 14 files / +1053 / −10 (extra delta = README link auto-merge).
- Validation CI on the merge tips:
  - `release.yml` on `gamehub-604-build` — run [26231488560](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26231488560)
  - `release.yml` on `feature/lite-variant-tier1` — run [26231454988](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26231454988)
- Going-forward rule recorded in memory (`feedback_banner_tools_enrollment.md`): any new BannerHub patch that adds a row to the More Menu or Library-Tile popup MUST also enroll the feature into the Banner Tools consolidated dialog (drawable + arrays + dispatch case) and wrap its standalone `*MenuRowPatch` injection blocks in `if (false)`. Default = enroll; standalone is the exception, not the norm.

## 2026-05-21 — feature/banner-tools-menu pre2: custom-icon 1×4 tile dialog (56dp vectors)

### Decisions locked (user, 2026-05-21)
1. **Icon size in dialog:** 56dp
2. **Backgrounds:** keep self-contained dark Material 3 surface-container tiles (no host-theme tinting)
3. **Ship format:** Android vector drawable XML

### New files
- `patches/src/main/resources/banner-tools/bh_bt_vibration.xml`
- `patches/src/main/resources/banner-tools/bh_bt_gpu_spoof.xml`
- `patches/src/main/resources/banner-tools/bh_bt_renderer.xml`
- `patches/src/main/resources/banner-tools/bh_bt_game_id.xml`

  Mechanical SVG → vector-drawable conversion of the 4 SVG templates archived in `/storage/emulated/0/Download/banner-tools-custom-icons-preview.html`. 512×512 viewport, 56dp intrinsic size, dark rounded-rect background path baked in (rx=112). Conversion rules: `<rect>` → multi-arc rounded-rect path; `<circle>` → 2-arc path; `transform="rotate(...)"` → `<group android:pivotX/Y android:rotation>`; `opacity="0.85"` → `android:fillAlpha`; `stroke-opacity="0.5"` → `android:strokeAlpha`.

  **Known conversion loss:** the GPU Spoof chip outline used SVG `stroke-dasharray="16 12"`, which Android VectorDrawable does not support. Shipped as a solid stroke instead — chip silhouette still reads, just less "blueprint-esque." If the user later vetoes this, alternatives are (a) pre-compute 24+ short line segments around the rounded-rect perimeter, or (b) drop the chip outline entirely and rely on the 6 pin marks for chip identity.

- `patches/src/main/kotlin/app/revanced/patches/gamehub/bannertools/BannerToolsDrawablesPatch.kt` — `resourcePatch` that ships the 4 vectors as new entries in `res/drawable/` of the staged APK. Mirrors the classloader-read pattern from `ChangeAppIconPatch`. Resource IDs are assigned by apktool/aapt2 at reassembly; runtime resolution is via `Resources.getIdentifier("bh_bt_*", "drawable", pkgName)` since the host R class is in the foreign package.

### Modified files
- `patches/src/main/kotlin/app/revanced/patches/gamehub/bannertools/BannerToolsMenuRowPatch.kt` — added `bannerToolsDrawablesPatch` to `dependsOn(...)` so the 4 drawables are guaranteed present before the bytecode patch applies.

- `extensions/gamehub/src/main/java/com/xj/winemu/bannertools/BhBannerToolsMenuRowClick.java` — `showDialog()` rewritten:
  - **Old (pre1):** `AlertDialog.Builder().setTitle(...).setItems(STRING_ARRAY, ...)` text list, 4 long labels.
  - **New (pre2):** `setView(buildTileRow(...))` — programmatically built `LinearLayout` HORIZONTAL with 4 children, each a vertical tile (`ImageView` 56dp + `TextView` short label). Short labels: **Vibration / GPU Spoof / Renderer / Game ID**.
  - Tile interaction: `selectableItemBackground` ripple, `clickable=true`, `focusable=true`. Per-tile click handler captures the tile index and dispatches into the same 4 per-feature handlers (`BhMenuRowClick` / `BhGpuSpoofMenuRowClick` / `BhRendererMenuRowClick` / `BhGameIdDisplayMenuRowClick`), then dismisses the dialog.
  - Cancel-only action button (no OK) — `setNegativeButton(android.R.string.cancel, null)`.
  - Drawables resolved at runtime via `res.getIdentifier(drawableName, "drawable", pkgName)` because the static R class belongs to GameHub's package, not BannerHub's. Missing-drawable case logs and falls through (tile renders without an icon rather than crashing).
  - `dp(float density, int dp)` helper added; programmatic view construction avoids needing to inject an XML layout into the foreign package.

### Why programmatic view (not XML layout)
Injecting a new `res/layout/bh_banner_tools_dialog.xml` would mean adding a new layout entry to the staged APK's resource table and either reflectively resolving its ID or hardcoding one. Programmatic construction sidesteps both — only the 4 drawables ride along, which apktool handles cleanly (proven by `ChangeAppIconPatch`).

### CI — both runs green
- **`build_pull_request` run [26228524223](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26228524223)** ✅ (1m55s) — patches bundle compile-only sanity check.
- **`release.yml` workflow_dispatch run [26228708841](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26228708841)** ✅ — full pipeline: build patches bundle → revanced-cli matrix across all 9 variants → resign with BannerHub test key → upload `apk-*` artifacts. Triggered with `version="1.5.0-604-banner-tools-pre2"` and `stable=false` (per pre-release policy after v1.5.0-604 stable; the `Create GitHub Release` job is correctly skipped). All 9 patch jobs `success`. `Patch Normal-GHL` log shows `INFO: "Banner Tools drawables" succeeded` + `INFO: "Banner Tools menu row" succeeded`, zero `SEVERE`, output 116.1 MB pre-resign.

### APK verification
Downloaded `apk-Normal-GHL` artifact and verified the 4 vector drawables landed inside the APK:
```
res/drawable/bh_bt_game_id.xml    (2028 B)
res/drawable/bh_bt_gpu_spoof.xml  (2252 B)
res/drawable/bh_bt_renderer.xml   (4056 B)
res/drawable/bh_bt_vibration.xml  (2268 B)
```
All 4 names match the runtime `Resources.getIdentifier("bh_bt_*", "drawable", pkg)` lookup keys in `BhBannerToolsMenuRowClick.buildTile`.

### Staged for device test
`/storage/emulated/0/Download/banner-tools-pre2/BannerHub-V6-1.5.0-604-banner-tools-pre2-Patched-Normal-GHL.apk` (≈111 MB, BannerHub test key signature unchanged from pre1 — in-place update over pre1).

### Open follow-ups (after device test of pre2)
- Confirm icon legibility at 56dp on user's device (font scale, screen density).
- If GPU Spoof dashed-outline absence is too jarring, swap to alternative (a) or (b) above.
- Once pre2 lands, address the Lx57.o Steam/Epic hide bug (separate from this branch — see `[[project_bannerhub_revanced]]` known-issue block).

---

## 2026-05-21 — feature/banner-tools-menu: pre1 device-tested + Layout 2 picked + custom icons received

### Device test result
Pre1 (text-list `AlertDialog`) installed from `/storage/emulated/0/Download/banner-tools-pre1/BannerHub-V6-1.5.0-604-banner-tools-pre1-Patched-Normal-GHL.apk` and confirmed working on device — single "Banner Tools" row appears at all 3 menu sites; tap opens the 4-item list; each item routes into the correct per-feature handler. No regressions to the 4 underlying settings activities / dialogs.

### Dialog UX iteration → Layout 2 picked
Mocked 3 dialog layouts in `/storage/emulated/0/Download/banner-tools-dialog-mockups.html` (Material 3 styled, 28dp rounded, light+dark adaptive). User picked **Variant 2 — 1×4 horizontal row of icon+label tiles**. Labels shortened to fit 4 tiles in a 360dp dialog: **Vibration / GPU Spoof / Renderer / Game ID** (vs pre1's longer "PC Vibration Settings" / "Show Game ID"). Cancel-only action button.

### Custom icons received
User supplied 4 self-contained SVG tiles (512×512 viewBox, 22% corner ratio, dark Material 3 surface-container backgrounds, multi-color hardcoded fills — no auto theme tinting):
- **Vibration** — controller body + dpad + 2 action buttons + 4 haptic wave arcs (`#FFD284` waves, `#E6E1E5` controller)
- **GPU Spoof** — spy/bandit mask over a dashed-outline chip with 6 pin marks (`#D0BCFF` mask, `#381E72` hat)
- **Renderer** — inner chip (`#2D3033` + `#006684` outline) + 12 outer pin pads + Android-bot core with shader grid (`#00E676` bot, `#A3EDFF` grid bars)
- **Game ID** — ID badge with avatar circle + barcode rows + decorative slot (`#BFC2C9` avatar, `#FFB4AB` accent bars)

Full SVG markup preserved as JS templates in the preview HTML below — re-extract from there if originals are lost.

### Preview HTML
Written to `/storage/emulated/0/Download/banner-tools-custom-icons-preview.html`. Shows:
- Layout 2 at 48dp / 56dp / 64dp icon sizes, light + dark themes side-by-side
- Per-icon size sampler (32 / 40 / 48 / 64 / 96 px) so user can judge detail legibility
- Pre1 text-list vs Layout 2 with custom icons A/B

### Pending decisions before next code change
1. **Icon size in dialog:** 48dp / 56dp / 64dp — recommended 56dp; 64dp would need dialog widened to ~400dp
2. **Self-contained dark backgrounds:** keep as-is (always dark squares, bold visual identity) vs strip the first `<rect>` from each so glyphs sit on the dialog's own chip background and pick up host theme tinting
3. **Ship format:** Android vector drawable XML (recommended — ~1–2 KB/icon, mechanical SVG→XML conversion since shapes are plain `<rect>`/`<circle>`/`<path>`/`<g>` with `transform="rotate(...)"`) vs PNG raster (20 files across 5 density buckets × 4 icons)

### No code changes this session
Branch head still `e156467` (pre1 scaffold). All work was UX/asset discussion — implementation of the new dialog + custom icons happens next session once the 3 pending decisions are answered.

---

## 2026-05-21 — feature/banner-tools-menu pre1 (scaffold)

Cut new branch off `gamehub-604-build@661a82d` for the Banner Tools menu-consolidation experiment listed in v1.5.0-604's "Upcoming features" section. Goal: collapse the 4 per-game More Menu rows (PC Vibration / GPU Spoof / Renderer / Show Game ID) into a single "Banner Tools" entry whose tap opens an `AlertDialog` listing the 4 sub-features. Each item dispatches into the existing per-feature handlers (`new BhMenuRowClick().invoke(null)`, etc.) so all settings activities / dialogs / prefs are reused unchanged.

### Scope (test branch — not for stable)
- Pure consolidation. Lx57.o Steam/Epic hide bug **deferred**.
- The 4 standalone *MenuRow patches are wrapped in `if (false) { @Suppress("UNREACHABLE_CODE") ... }` so their row injections do not run — the patches remain compiled and apply cleanly, but emit zero bytecode at the 3 menu sites. The shared `Lxd3;->l1` resolver hook in `VibrationMenuRowPatch` is **kept** (outside the `if (false)`) since `BannerToolsMenuRowPatch` reuses it for its Injection-3 Lell label sentinel.
- Per-feature settings code (`BhVibrationSettingsActivity`, `BhGpuSpoofSettingsActivity`, `BhRendererSettingsActivity`, gameId dialog) is untouched.

### New files
- `patches/src/main/kotlin/app/revanced/patches/gamehub/bannertools/BannerToolsMenuRowPatch.kt` — structural clone of `GpuSpoofMenuRowPatch`; injects one row at all 3 standard sites (`Lx57;->a`, `Lted;->f`, `Lpzc;->j0`). `dependsOn(menuGameIdCapturePatch, vibrationMenuRowPatch)` for the shared `Lxd3;->l1` resolver.
- `extensions/gamehub/src/main/java/com/xj/winemu/bannertools/BhBannerToolsMenuRowClick.java` — 3 `appendRow*` builders + `invoke()` that pops an `AlertDialog` with 4 items dispatching via `new <Sibling>().invoke(null)`. Sentinel key `"string:bh_banner_tools_label"`.

### Modified files
- `extensions/gamehub/src/main/java/com/xj/winemu/vibration/BhMenuRowClick.java` — added `"string:bh_banner_tools_label" → "Banner Tools"` mapping in `maybeResolveCustomLabel`.
- 4 × `*MenuRowPatch.kt` — wrapped the existing 3-site injection blocks in `if (false) { ... }`. Header comment in each explains how to revert (parent commit `661a82d`).

### Build
Push to fork → CI builds via `Any branch compilation` workflow (per `[[feedback_ci_workflows]]`). No CI matrix changes.

---

## 2026-05-21 — 🚀 v1.5.0-604 STABLE SHIPPED

Cut from `gamehub-604-build` at HEAD `af40a90` (PR #6 merge) — Lite refresh on `feature/lite-variant-tier1` at `af522a2` (merge of 604 into Lite). Stable keystore unchanged from v1.1.0+; cert SHA-256 `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba`. 18 APKs attached (9 full + 9 Lite) + 3 `.rvp` bundles.

### Four headline changes on top of v1.4.0-604

1. **External launcher integration (Beacon / ES-DE / Daijishou)** — `feature/external-launcher` merged via `cec34f0`. Port of PlayDay's 5.3.5 patch forward to the 6.0.4 base. Intents accept both `--ei` (int) and `--es` (String) extras. Epic Games attempted across pre1–pre5 and dropped — the upstream `GameDetailViewModel` ignores the `app_nav_epic_app_name` route. PC + Steam ship working end-to-end.
2. **Show Game ID menu row + View All Games dialog** — `feature/menu-gameid-display` merged via `090706e`. New per-game menu row pops a dialog with the gameId + Copy button + "View All Games" button that opens the full library list backed by `db_game_library.db`.
3. **Proper menu row icons (TideGear PR #6)** — merged via `af40a90`. Vibration / GPU Spoof / Renderer rows previously read `zz4.m` (the Remove-from-Library trash icon); each now reads its correct icon field (`zz4.b0` / `zz4.v` / `zz4.c0` respectively).
4. **Portrait layout for PC Vibration Settings dialog (TideGear PR #6)** — same merge. Dialog detects orientation, stacks Mode + Intensity vertically in portrait at `min(360dp, screenW - 24dp)` width, intensity slider caps at 220dp. Landscape unchanged.

### Docs

- README: bumped latest-stable badge + link to v1.5.0-604, rewrote "What's new" section, rotated v1.4.0 into the past-release archive line.
- `.github/workflows/release.yml`: refreshed the `### What's new in ${version}` template body + changed prior-stable reference from `v1.3.0-604` → `v1.4.0-604`.
- PROGRESS_LOG: this entry.
- Memory: updated `project_bannerhub_revanced.md` with new active state.

### Known issue (not blocking — flagged for next investigation)

Steam-source and Epic-source games (e.g. Brawlhalla Steam id 291550, Doomblade Epic id 0) hide all 4 of our More Menu rows (Vibration / GPU Spoof / Renderer / Show Game ID) on the game-details page More Menu. PC Game Settings ungate works on the same games, confirming `Lx57.a` IS being invoked. A finalize-anchor fix (move injection from `lastIdx(Lx9d->add) + 1` to `firstIdx(Lqs2->v finalize)`) was built and device-tested on `fix/menu-row-anchor-merge-point` — did NOT resolve the issue, branch deleted. Root cause is downstream in `Lx57.o` row iteration (the 2×2 icon grid renderer at line 333+); not yet bottomed out. Tracked separately; not blocking v1.5.0-604.

---

## 2026-05-01 — GameHub 6.0 port session

### Goal
Port the existing 5.3.5 ReVanced patches to GameHub 6.0.0 (`com.xiaoji.egggame` KMP rewrite).

### Branch
`gamehub-600-build` — forked from `playday-build`, retargeted to `GameHub_beta_6.0.0_global.apk`

### Completed fixes

#### 1. CI compile error (commit `e1a6a12`)
- `getInstruction<Instruction>(...)` — patcher v22 does NOT accept type parameters
- Fix: remove `import com.android.tools.smali.dexlib2.iface.Instruction`, use bare `getInstruction(idx)`

#### 2. Firebase Crashlytics crash-on-launch — VerifyError (commit `2437dca`)
- **Root cause:** Original `DisableCrashlyticsPatch` used a `goto` to skip the Crashlytics block.
  At the join point, v2 had type `String` (goto path) vs `Boolean` (fall-through path) → ART VerifyError.
- **Fix:** Remove all 3 Crashlytics instructions in **reverse order**:
  - `setCrashlyticsCollectionEnabled` (endIdx)
  - `move-result-object` (getInstanceIdx + 1)
  - `invoke-static getInstance` (getInstanceIdx)
  This leaves the `const/4 v2, 0x0` between them in place, which redefines v2 String→Boolean and satisfies the ART verifier at the join point.

#### 3. TokenProvider.<clinit> dead-code removal (commit `aab98d7`)
- **Root cause:** `TokenProvider.loginBypassed` is `static boolean = false` — Java does NOT generate
  `<clinit>` for default-value static fields. `firstMethod { name == "<clinit>" }` threw "Required value was null".
- **Fix:** Remove the entire `TokenProvider.<clinit>` patching block plus the `addInstructions`,
  `TOKEN_PROVIDER_CLASS`, and `sharedGamehubExtensionPatch` imports/dependencies.

#### 4. Bypass login — complete rewrite for 6.0 (commit `f53a74d`)
- **Root cause:** `UserManager` class (`Lcom/xj/common/user/UserManager;`) is gone in 6.0.
  All 5 `firstMethod { definingClass == USER_MANAGER_CLASS }` calls threw "Required value was null".
  `HomeLeftMenuDialog` also renamed/restructured.
- **6.0 architecture analysis (via apktool decompile of `GameHub_beta_6.0.0_global.apk`):**
  - Login state is now managed by `Lis0;` interface with two implementations:
    - `Los0;` — real DB-backed impl using `UserDao` + `AuthTokenDao` (Room database), StateFlow initialized to `Boolean.FALSE`
    - `Lah;` — `os0` decorator that hardcodes `a()=true` and wraps StateFlow with `Boolean.TRUE`
  - `Lg8e;` is the navigator class (holds `is0` as field `b`)
  - Two methods in `g8e` gate Login navigation by calling `is0.a()`:
    - `g8e.i(Lrh0;)V` — guards via `iget Lg8e;->b → invoke-interface Lis0;->a() → if-nez → new Lga0;`
    - `g8e.r(Lrh0;)V` — same pattern with different register (v1 vs v3)
  - `Lga0;` is the Login navigation intent
- **Fix:** For both methods `i` and `r`:
  - Find `iget-object vN, p0, Lg8e;->b:Lis0;` → get register N
  - Remove `invoke-interface {vN}, Lis0;->a()Z` (igetIdx+1)
  - Remove `move-result vN` (igetIdx+2, removed first to keep indices stable)
  - Insert `const/4 vN, 0x1` at igetIdx+1
  - Result: `if-nez vN, :skipLogin` always branches → Login navigation never reached

### CI results

| Test tag | Run ID | "Bypass login" | "Disable Firebase Crashlytics" | Notes |
|---|---|---|---|---|
| v0.0.1-600-test | 25215699388 | SEVERE | SEVERE | Compile error (getInstruction type param) |
| v0.0.2-600-test | ~25218... | SEVERE | SEVERE | goto approach → VerifyError crash |
| v0.0.3-600-test | 25220... | SEVERE | ✅ INFO | Crashlytics fixed; login still failing (TokenProvider.<clinit>) |
| v0.0.4-600-test | 25222889321 | SEVERE | ✅ INFO | TokenProvider block removed; login still failing (UserManager gone) |
| v0.0.5-600-test | 25224133234 | ✅ INFO | ✅ INFO | Login bypass rewritten for 6.0 g8e navigator |

### Pending (other patches still failing)

All remaining patches target 5.3.5 class names not present in 6.0. Each needs:
1. `apktool d` decompile + grep for the target functionality
2. Find the 6.0 equivalent class/method
3. Rewrite the patch fingerprint and bytecode manipulation

Known failing patches and root causes:
- **appNullSafetyPatch** → targets `Lcom/xj/app/App;` (renamed in 6.0)
- **bypassTokenExpiryPatch** → targets `RouterUtils$checkGuideStep$1` (renamed)
- **settingsMenuPatch** → UI classes renamed
- **errorHandlingPatch** → `NetErrorHandler$DefaultImpls` renamed
- **tokenResolutionPatch** → `UserManager.getToken()` gone
- Everything that depends on these (cascade failures via "patch failed previously")

### Local resources
- 6.0 APK decompile: `/tmp/gh600_smali/` (rebuilt from `GameHub_beta_6.0.0_global.apk` via apktool each session — `/tmp/` is ephemeral)
- 6.0 APK local copy: `/data/data/com.termux/files/home/GameHub_beta_6.0.0_global.apk`
- To rebuild smali: `java -jar ~/apktool.jar d ~/GameHub_beta_6.0.0_global.apk -o /tmp/gh600_smali -f --no-res`

### v0.0.6 / v0.0.7 / v0.0.8 incremental fixes (post-v0.0.5)

| Tag | Commit | Patch added |
|---|---|---|
| v0.0.6 | `65f2349` | `os0.h()` → `MutableStateFlow(Boolean.TRUE)` — NavHost `collectAsState()` had been picking Login as start destination because StateFlow init was FALSE |
| v0.0.7 | `21b151f` | `xm7.f()` → `"99999"` — game-import save was hitting null UID null-check in `xm7.u()` and short-circuiting to FALSE |
| v0.0.8 | `02195ff` | New `DebugLogPatch.kt` — sets `android:debuggable="true"` and prepends `Log.e("GH600-DEBUG", "y2d.e caught", t)` to `odb.e()` so swallowed exceptions surface in logcat |

### v0.0.8 device test result (2026-05-01, log_2026_05_01_17_04_18.log)

- Login is bypassed cleanly ✅ — landed on home screen, no Login route
- Game import dialog opens, can select APK + metadata
- Tap Save → dialog dismisses with no toast → game does NOT appear in library ❌

Decompiled v0.0.8 APK to verify all three patches were live:
- ✅ `android:debuggable="true"` set in `<application>`
- ✅ `"GH600-DEBUG"` in `odb.smali:129`
- ✅ `xm7.f()` returns `"99999"`
- ✅ `os0.h()` returns `r8o.r(Boolean.TRUE)`

Logcat shows NO `GH600-DEBUG y2d.e caught` line — meaning `xm7.u()` did NOT throw an exception. So the save use case `q1d.a()` got `Boolean.TRUE` back from `xm7.u()` (dialog dismissed cleanly because save reported success). But the row still doesn't appear in the library list.

#### Root-cause analysis (smali trace)

`xm7.u()` flow at `smali_classes5/xm7.smali:13667`:
1. `invoke-virtual xm7.f()` → "99999"
2. `if-nez :cond_3` (non-null, branch taken)
3. Build `fl7` lambda with userId="99999"
4. `withTransaction { fl7.invoke() }` → `el7.invokeSuspend` → `GameLibraryBaseDao.insert` (line 922 of `el7.smali`)
5. Return `Boolean.TRUE`

Local readers (`xm7.p`, `xm7.s`) ALSO call `xm7.f()` for their `WHERE user_id = ?` filters. So the writer and these specific readers are consistent on "99999".

But `is0.f()` is called directly by **other** consumers — `lvd` (network request prep, reads `l4m.b` username), `aae` (synthetic property-getter lambda), `fh2`, `dt0`, `sak`, `w79`, `kpl`, `dlk`, `npl`. The auth-token StateFlow that backs `is0.f()` is built off `AuthTokenDao.observeCurrent()` and emits null when the table is empty. With our login bypass there's no `auth_token` row in the DB, so `is0.f()` returns null, and any refresh/library-list signal that keys off this Flow stays in an "empty" state regardless of what's in `t_game_library_base`.

#### Fix planned for v0.0.9

Patch `is0.f()` (the interface default method in `is0.smali`) to return a non-null synthetic `l4m` constructed via reflection in a Java extension helper.

**New file:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/login/FakeAuthToken.java`
- `get()` reflectively constructs `Class.forName("l4m")` with `(a="99999", b="", c..f=null, g=h=i=j=0)`, caches in volatile static
- Logs to `GH600-DEBUG` tag on success/failure

**`BypassLoginPatch.kt`:**
- Adds patch to `Lis0;->f()Ll4m;` — removes its 6 original instructions (`invoke-interface d()` → `getValue()` → `check-cast Ll4m;` → `return-object`) and replaces with `invoke-static FakeAuthToken.get()` → `check-cast Ll4m;` → `return-object`
- Keeps existing `xm7.f()`="99999" patch as redundant safety net (and so xm7's local cache logic stays consistent)
- Keeps the `g8e.i/r` navigator bypass and `os0.h()`=TRUE

---

## 2026-05-01 evening — Save-button silent-failure investigation

### Symptom
Bypass-login works (no Login screen), but **clicking Import → fill game form → Save** does not add the game to the library. Repeated tests show no rows ever appear in the library UI.

### Test 1 — v0.0.9-600-test (commit `59ab364`)
Existing patches: xm7.u ENTRY/CATCH probes, odb.e Throwable hook. DebugTrace writes to file at `/storage/emulated/0/Android/data/com.xiaoji.egggame/files/gh600-debug.log` AND Log.e.

Reproduction with `getlog -n 15000 com.xiaoji.egggame` after Save:
- 10251 lines captured, **0 GH600-DEBUG entries, 0 E-level lines from the app** (66 D, 10146 I, 38 W).

Hypothesis: this device (or kernel build) filters app-tagged Log.e for non-system uids. File output unreachable from PRoot due to scoped storage.

### Test 2 — v0.1.0-600-test (commit `ac86a5f`, CI 25237506742 ✅)
Changes:
- DebugTrace switches from `Log.e` to `Log.i` (Log.i lines ARE reaching logcat per the test-1 capture).
- DebugTrace adds zero-arg markers `markY4iUpsert()`, `markFakeAuth()` for probes inserted into methods with `.locals 0`.
- New probe at `y4i.b` ENTRY (RetroGameDao upsert wrapper).
- `FakeAuthToken.get()` now logs on every call, not only on first construction.

Reproduction:
- `xm7.u ENTRY` fires **once** at 19:33:23.935 ✓
- `xm7.u CATCH` fires **0** times — transaction did not throw
- `FakeAuthToken.get() called` fires **45×** — bypass-login pathway is alive
- `y4i.b ENTRY` fires **0** — RetroGameDao not touched
- `y2d.e caught` fires **0**

### Conclusion of Test 2
xm7.u runs successfully end-to-end without exception, yet nothing lands in the library. `y4i.b` was a red herring — `RetroGameDao` is for retro emulators only. Re-tracing xm7.u smali (`smali_classes4/xm7.smali` line 13663) shows the actual write path:

```
xm7.u
  ├─ early bail: if xm7.f() returns null → return Boolean.FALSE     [line 13822]
  └─ withTransaction(GameLibraryDatabase, fl7) → fl7.invokeSuspend
       └─ withTransaction body: el7.invokeSuspend (.locals 69)
            ├─ build GameLaunchMethodTable, setLinkedGameId
            ├─ GameLaunchMethodDao.insert(table, cont)               ← line 609 in el7.smali
            ├─ build GameLibraryBaseTable via oh7.c(GameInfo)
            └─ GameLibraryBaseDao.insert(table, cont)                ← line 922 in el7.smali
```

The actual main-library writes are inside `el7.invokeSuspend` against `GameLibraryDatabase` — separate database from `RetroGameDatabase`.

### Test 3 — v0.1.1-600-test (commit `0892555`, CI 25237940015)
Added probes:
- `el7.invokeSuspend` ENTRY → confirms transaction body started
- `GameLaunchMethodDao.insert` PRE → marker right before INVOKE_INTERFACE
- `GameLibraryBaseDao.insert` PRE → marker right before INVOKE_INTERFACE

Implementation: `addInstructions` walked from highest target index to lowest so earlier insertions don't shift later targets. All three markers route through `DebugTrace.markEl7Entry()` / `markLaunchInsert()` / `markLibraryInsert()` (no-arg statics) since el7.invokeSuspend doesn't have free local registers everywhere.

**Branching logic for next reproduction:**
- el7 ENTRY missing → xm7.u took the early `Boolean.FALSE` branch (xm7.f() patch silently shadowed somehow)
- el7 ENTRY hit, no insert markers → withTransaction body bailed before reaching inserts
- both insert markers hit, library still empty → bug is **library-read-side**: UI either filters by a userId mismatch or fetches from a remote endpoint that 401s with our empty-bearer fake token

### Parallel infrastructure: logcat-bridge v1.1.0
The bridge can't read scoped external storage from PRoot, but the daemon runs as root. v1.1.0 (zip ready at `/data/data/com.termux/files/home/logcat-bridge-magisk.zip`, awaiting flash) adds `cat <path>`, `ls <path>`, and `sql <dbpath> <query>` verbs to the handler with allowlisted prefixes (`/data/data/`, `/data/local/tmp/`, `/data/tombstones/`, `/data/adb/modules/`, `/storage/emulated/0/Android/`, `/sdcard/Android/`) and `..` traversal blocked. `sqlite3` invoked with `-readonly -header`. Client side: `getlog --cat <path>`, `getlog --ls <path>`, `getlog --sql <dbpath> "SELECT ..."`. Once flashed + rebooted, this lets us inspect `GameLibraryDatabase` rows directly to confirm whether writes actually persist — covering the case where probes show inserts firing but UI still shows empty.

### Status awaiting user
- Flash logcat-bridge v1.1.0 zip + reboot.
- Install v0.1.1-600-test APK, reproduce Save, capture logs.
- Then I pull `getlog -n 20000 com.xiaoji.egggame` for probe markers AND `getlog --sql /data/data/com.xiaoji.egggame/databases/<gameLibraryDbName> "SELECT count(*) FROM game_library_base"` for the conclusive write-vs-read answer.

### Test 3 device-test result (2026-05-01, v0.1.1-600-test, run 25237940015)

All four probes fired in order, transaction body completed without CATCH:

```
19:59:41.063  GH600-DEBUG: xm7.u ENTRY
19:59:41.065  GH600-DEBUG: el7.invokeSuspend ENTRY
19:59:41.065  GH600-DEBUG: GameLaunchMethodDao.insert PRE
19:59:41.069  GH600-DEBUG: GameLibraryBaseDao.insert PRE
19:59:41.090  W App_Lifecycle: DISPOSE overlay=ye0      ← dialog dismisses
```

DB inspection (post-test) via `getlog --cat` + Python `sqlite3`:

- `egggame.db` — auth/UI DB (NOT GameLibraryDatabase). All tables empty as expected — login bypass means no auth_token / user_account row.
- **`db_game_library.db`** — actual GameLibraryDatabase, found via `et2.smali:584 const-string "db_game_library.db"`. Earlier listing missed it because the file is created lazily on first write.
  - `t_game_library_base` count = **1** (the imported row landed)
  - `t_game_launch_method` count = **1**
  - `t_game_install_state` count = 0
  - Imported row: `user_id='99999'`, `game_name='God of War'`, `id='local_DaebwST-TEyzp1KJX2xRzQ'`, `extension_data={"filePath":"/storage/emulated/0/Winlator/Games/GodOfWar/GoW.exe","steamAppid":"1593500"}`, `launch_method_id=1`. Write side **fully working**.

### Root cause of empty library UI (read-side, not write-side)

Library-list reader pipeline (smali trace, `wl7.smali` → `erc.smali:340`):

```
is0.e()                              ← StateFlow<f4m?> for current user account
  ↓
flatMapLatest { f4m ->
    if (f4m == null) emptyFlow()    ← TAKEN under our bypass
    else dao.subjectAllByUserId(f4m.a)
}
```

`is0` interface (smali_classes4/is0.smali):
- `d()Ld3k;` → Flow<l4m?> (auth token)
- `e()Ld3k;` → Flow<f4m?> (user account)        **← library reader uses this**
- `h()Ld3k;` → Flow<Boolean?> (is logged in)    **← we patched in v0.0.6**
- `f()Ll4m;` → `d().getValue()`                 **← we patched in v0.0.9**
- `b()Lf4m;` → `e().getValue()`

We patched `is0.f()` (l4m getter) and `os0.h()` (Boolean flow), but NOT `os0.e()` (f4m flow). With `t_user_account` empty (login bypassed), `os0.a` field's underlying StateFlow emits null, flatMapLatest drops to the empty branch, library list shows zero entries despite the row being in `t_game_library_base`.

### Fix planned for v0.1.2-600-test

**New extension** `extensions/gamehub/.../FakeUserAccount.java`:
- Reflectively constructs `Lf4m;` via `Class.forName("f4m").getDeclaredConstructor(...)` with sig
  `(String,String,String,String,String,String,I,I,Z,String,I,I,I,I,I,J,String,String,I,I,String,J,I,String,String,J,J)V`.
- Sets `a="99999"`, all other String fields `""`, all numerics zero.
- Caches in volatile static, logs to `GH600-DEBUG`. f4m's ctor null-checks `a` (p1) and `q` (p18); both pass.

**`BypassLoginPatch.kt` addition** (mirrors v0.0.6 `os0.h()` block):
```kotlin
firstMethod { definingClass == "Los0;" && name == "e" }.apply {
    removeInstruction(0) // iget-object p0, Los0;->a:Likh;
    removeInstruction(0) // return-object p0
    addInstructions(0, """
        invoke-static {}, Lapp/revanced/extension/gamehub/login/FakeUserAccount;->get()Ljava/lang/Object;
        move-result-object p0
        invoke-static {p0}, Lr8o;->r(Ljava/lang/Object;)Lf3k;
        move-result-object p0
        return-object p0
    """)
}
```

Debug probes intentionally **kept in place** (xm7.u ENTRY/CATCH, el7 ENTRY, both insert PRE markers, FakeAuthToken.get, DebugLogPatch) so the next device test can confirm `FakeUserAccount.get() called` fires before the UI populates and that the import flow is otherwise unchanged. Probes will be removed in a cleanup pass after the import flow is confirmed end-to-end.

## 2026-05-02 — v1.0.0-600 stable release session

### Goal
Ship the first stable BannerHub-API-enabled GameHub 6.0 release that pairs with the Worker's `/v6/` gate.

### Branch operations
- `bannerhub-api-patch` (commits `7644ad0` Redirect catalog API + `561b246` Prefix /v6) was fast-forward merged into `gamehub-600-build`. Branch kept (not deleted) per user instruction.
- `gamehub-537-build` deleted from local + `origin` (9 unique commits, all 5.3.7 work — the v6 CLI revert, settings menu disable for 5.3.7 R8 renames, target-APK swap, etc.). Abandoned 5.3.7 port; nothing reachable elsewhere.

### Stable release v1.0.0-600 (run 25264095270, commit 572ff30)
Triggered via `gh workflow run release.yml --ref gamehub-600-build -f tag=v1.0.0-600 -f stable=true`. Workflow opt-in `stable=true` checkbox produces the GitHub Release; default behavior is artifacts-only prerelease.

Title: **"Gamehub 6.0 - Bannerhub API - No Login - Muted UI"** (renamed in-place via `gh release edit` after the initial publish; workflow `name:` field also updated for future runs).

9 variant APKs + .rvp bundle + .rve extension files attached:
- Normal (`banner.hub`), Normal-GHL (`gamehub.lite`), PuBG (`com.tencent.ig`), AnTuTu (`com.antutu.ABenchMark`), alt-AnTuTu (`com.antutu.benchmark.full`), PuBG-CrossFire (`com.tencent.tmgp.cf`), Ludashi (`com.ludashi.aibench`), Genshin (`com.miHoYo.GenshinImpact`), Original (`com.xiaoji.egggame`).

### Release notes iterations (workflow body + live release body kept in sync via `gh release edit --notes-file`)
1. **Initial body was stale** — described only Bypass login + Disable Crashlytics + Debug logging + File manager + per-variant naming. Missing 3 patches that had landed since: Mute UI sounds (5ce470d, 2df0e54), Redirect catalog API (7644ad0), Prefix API path /v6 (561b246). Cancelled the in-flight run, updated `release.yml` body, retriggered. Run 25264031838 (cancelled) → 25264095270 (succeeded).
2. **Cross-release install warning added** — every CI run mints a new debug keystore (no `--keystore` passed to revanced-cli, ephemeral runner has empty workdir), so Android refuses cross-release upgrades with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Replaced the wrong "To upgrade in place install the same variant" line with explicit uninstall-first instruction.
3. **Known limitations section added** with two callouts:
   - Steam launches via standard client are likely broken — use Lightweight Steam (`steam_client_0403`).
   - Imported games need cover art set manually.
4. **PC game settings orientation note added** — side benefit of the API redirect: per-game PC settings now renders in both landscape AND portrait (vanilla locked it to landscape only). Caused by the BannerHub Worker not carrying upstream's orientation constraint in the catalog response.

### README rewritten for v1.0.0-600 (commit 816dd2d, then b4cee47 for the orientation note)
Full doc rewrite covering all four user-facing patches (was stale, only described the No Login flow). Adds: cross-release fresh-install warning, Known Limitations section, per-patch sections for Mute UI sounds + Redirect catalog API + Prefix /v6, link to `bannerhub-api` repo as catalog backend, Releases section explaining stable-vs-prerelease workflow.

### Open follow-ups
- **Persistent keystore** — pin one in Actions secrets and pass `--keystore`/`--keystore-entry-alias`/`--keystore-password`/`--keystore-entry-password` on the revanced-cli patch command. Eliminates the cross-release uninstall requirement.
- **Bump versionCode per release** — patches don't currently touch versionCode; APKs ship with the base APK's value. Cosmetic only (Android allows install-replace with equal versionCode), but proper update-detection in package installers wants it bumped.
- **Component Manager port still pinned on `component-manager-injection`** at `5b89073`. Picker still doesn't show injected `Fex_2604`. Resume plan in memory unchanged: ship debug build with `Log.i("GH600-DEBUG", ...)` at top of every `HostCache.*` method, then in-foreground inject test.

## 2026-05-05 — Fix: per-variant DocumentsProvider authority

### Problem
v1.0.0-600 ships 9 variants that all declare the **same** DocumentsProvider authority, baked at the unrenamed `com.xiaoji.egggame.app.revanced.extension.gamehub.filemanager.MTDataFilesProvider`. Cause: `FileManagerAccessPatch` runs in the default `apply { ... }` block, which executes BEFORE `ChangePackageNamePatch`'s `afterDependents { ... }`, so it reads the original `manifest@package` value before the variant rename happens. The wake-up activity's `android:taskAffinity` (`com.xiaoji.egggame.MTDataFilesWakeUp`) is frozen the same way.

Practical impact: Android allows one app per provider authority globally. Installing a second variant alongside a first fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`.

### Branch
`fix/file-manager-per-variant-authority` off `gamehub-600-build` (per branch-per-patch policy).

### Change
`patches/src/main/kotlin/app/revanced/patches/gamehub/filemanager/FileManagerAccessPatch.kt`: `apply { ... }` → `afterDependents { ... }` (and `return@apply` → `return@afterDependents`). Patch body unchanged. Now reads `manifest@package` after `ChangePackageNamePatch` has rewritten it, so each variant gets:

- `<provider android:authorities="<variant-pkg>.app.revanced.extension.gamehub.filemanager.MTDataFilesProvider">`
- wake-up activity `android:taskAffinity="<variant-pkg>.MTDataFilesWakeUp"`

### Why this over `-O updateProviders=true`
- Single-file change vs workflow edit (and CLI option that has to be remembered on every future build).
- `updateProviders=true` only rewrites `<provider android:authorities>` — it does NOT touch `taskAffinity` on the wake-up activity, which would still collide across variants.
- `updateProviders=true` also rewrites authorities on **all** existing providers in the base APK (not just ours), which the option's own description warns can break features.

### Status
- CI compile green run 25379952428 (`build_pull_request.yml`).
- **Round 1** (commit 6d329bd, `apply{} → afterDependents{}`): release run 25382219259 success, but verifying APKs with `aapt dump xmltree | grep MTDataFiles` showed authority + taskAffinity STILL frozen at `com.xiaoji.egggame.*` for every variant. afterDependents alone wasn't sufficient.
- **Round 2** (commit 8f4a8fc, added `dependsOn(changePackageNamePatch)`): release run 25382742203 success, same negative result — patcher schedule still didn't put our manifest write after the rename.
- **Round 3 — verified working** (commit fadeaab, read `packageNameOption.value` directly): release run 25383226328 success. APKs now show per-variant authorities (`banner.hub.app.…MTDataFilesProvider`, `gamehub.lite.app.…MTDataFilesProvider`, …) and per-variant taskAffinities (`banner.hub.MTDataFilesWakeUp`, etc.). `Original` correctly stays at `com.xiaoji.egggame.*`. Branch ready to merge after device-side install test.

### Lesson
For revanced-patcher resource patches that need to react to "Change package name", do not rely on `afterDependents` + `dependsOn(changePackageNamePatch)` to read the post-rename `manifest@package` value — the scheduling guarantees aren't there. Instead, read `packageNameOption.value` directly (with a fallback to `packageNameOption.default`). The CLI option is set before any patch applies, so it's the only reliable source of the variant package within other patches.

### Round 4 — upstream literals also per-variant (commit f306a48)
After round 3 fixed MTDataFiles, manifest inspection of alt-AnTuTu showed pre-existing upstream literals still stuck at `com.xiaoji.egggame.*`:
- `<permission>`/`<uses-permission>` `com.xiaoji.egggame.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — `signature` protectionLevel, would have blocked side-by-side install of variants with different signing certs
- 10 `<provider android:authorities="com.xiaoji.egggame.*">` declarations from MobProvider / fileprovider / FlyProvider / utilcode / firebaseinit / AndroidContext / androidx-startup / filekit / fileprovider / wbsdk — same authority, different package names → install conflict
- `<permission>` `com.xiaoji.egggame.permission.C2D_MESSAGE` — `normal` protectionLevel, NOT a blocker (Android allows multi-declaration of normal perms across apps)

Fix: added `-O 'updatePermissions=true'` and `-O 'updateProviders=true'` to the Change package name CLI invocation in `.github/workflows/release.yml`. Release run 25385421598 verified all 10 upstream provider authorities and the `signature` permission are now per-variant. C2D_MESSAGE intentionally untouched — `updatePermissions` only rewrites the hardcoded `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` per upstream patch source, and C2D_MESSAGE is harmless cosmetically.

### Final state for branch fix/file-manager-per-variant-authority
Verified APKs at `/storage/emulated/0/bannerhub-revanced-test-25385421598/`. All 9 variants now have fully decoupled manifests:
- `manifest@package` per-variant (existing)
- `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` per-variant (round 4)
- All 10 upstream provider authorities per-variant (round 4)
- MTDataFiles provider authority + wake-up taskAffinity per-variant (round 3)

Ready to merge into `gamehub-600-build` once device install test confirms two variants install alongside each other without `INSTALL_FAILED_CONFLICTING_PROVIDER`.

### Round 5 — C2D_MESSAGE permission per-variant (commit 91436af)
User reported the install dialog still said "package conflicts with a current package" when trying to install one variant alongside another, even on package names they didn't have installed. Re-audited all `com.xiaoji.egggame.*` literals in the alt-AnTuTu manifest from run 25385421598 — only one globally-unique identifier was still shared across variants:

```xml
<permission android:name="com.xiaoji.egggame.permission.C2D_MESSAGE" />
<uses-permission android:name="com.xiaoji.egggame.permission.C2D_MESSAGE" />
```

This is the install blocker. Android 7+ rejects any install that declares a `<permission>` whose name another installed package already declares — regardless of `protectionLevel`, regardless of signing cert match. The package manager reports it as `INSTALL_FAILED_DUPLICATE_PERMISSION`, surfaced in the UI as the unhelpfully-vague "package conflicts with a current package" dialog. (Earlier I'd called this permission "harmless" on the grounds that `normal`-protection allows multi-declaration. That was wrong — the multi-declaration restriction was tightened to apply to all custom permissions in API 24, regardless of protection level.)

Other `com.xiaoji.egggame.*` literals in the manifest are NOT install blockers:
- `<activity android:name="...">` and `<service android:name="...">` — these are fully-qualified class names scoped to the app, not globally unique
- `taskAffinity="com.xiaoji.egggame"` — affinity is a task-grouping hint, not a unique system identifier; multiple apps can share it
- `<data android:scheme/host>` in intent-filters — multiple apps can register the same scheme

Fix: new `RewriteCustomPermissionsPatch` (resource patch, GameHub-specific). Iterates the manifest's `<permission>` and `<uses-permission>` elements; any element whose `android:name` starts with `com.xiaoji.egggame.permission.` gets the prefix rewritten to the variant package. Reads `packageNameOption.value` directly (same pattern as the MTDataFiles fix from round 3) — does not rely on patcher ordering against ChangePackageNamePatch. Confirmed via grep that no smali in the 6.0 decompile references `C2D_MESSAGE` literally, so renaming the manifest declaration doesn't break runtime broadcasts (the SDK either computes the name from `BuildConfig.APPLICATION_ID` at runtime, or doesn't use the permission at all).

Release run 25387394484 verified: each of the 9 variants now declares its own per-variant `<permission android:name="<variantPkg>.permission.C2D_MESSAGE">`. APKs at `/storage/emulated/0/bannerhub-revanced-test-25387394484/`.

### How to actually test
1. Uninstall ALL previously-installed BannerHub-ReVanced variants from the device (including any v1.0.0-600 builds and any earlier test builds from this branch). They still declare `com.xiaoji.egggame.permission.C2D_MESSAGE` and will block fresh installs from run 25387394484.
2. Install Variant A (e.g. `Normal` = `banner.hub`).
3. Install Variant B (e.g. `PuBG` = `com.tencent.ig`) without uninstalling A.
4. Both should now coexist on the launcher. If step 3 still fails with the same "package conflicts with a current package" message, run `adb logcat -d | grep -iE 'install_failed|already declared'` immediately after the failed install and paste the output — there's another globally-unique declaration to track down.

## 2026-05-05 — v1.0.1-600 stable cut

### Branch operation
`fix/file-manager-per-variant-authority` (8 commits — 5 patch fixes + 3 progress-log updates) fast-forward merged into `gamehub-600-build` (commit range `7108634..2113683`). Branch deleted from local + origin. `gamehub-600-build` advanced from `7108634` → `2113683` → `220a204` (after the docs/release-notes prep commit).

### Stable release
Run **25389334422** (`gh workflow run release.yml --ref gamehub-600-build -f tag=v1.0.1-600 -f stable=true`) — all 10 jobs green (build + 9-variant patch matrix + Create GitHub Release). Published as https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.1-600.

Title: **"Gamehub 6.0 - BannerHub API - Multi-Install"** (rebrand from v1.0.0-600's "Gamehub 6.0 - Bannerhub API - No Login - Muted UI" to highlight the headline fix).

### Release notes structure
1. Lead with the side-by-side install fix being the headline.
2. New `What's new vs v1.0.0-600` section explaining the three independent install-blockers stacked behind the single Android dialog (MTDataFiles authority + DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION + C2D_MESSAGE), each with its own root cause.
3. Migration warning: must uninstall **all** prior BannerHub-ReVanced variants — the legacy C2D_MESSAGE declaration on any single one of them blocks fresh installs from this release.
4. Full patch table updated to include the new `Rewrite custom permissions per variant` row and the per-variant `File manager access` callout.

### README
Bumped latest-stable line to v1.0.1-600 with the new title and link, added the "What's new" callouts and migration instruction, updated `File manager access` and `Change package name` patch sections, added new `Rewrite custom permissions per variant` section.

### Pre-release policy now in effect
Per `feedback_bannerhub_revanced_prerelease.md`, every workflow run from now on returns to artifact-only prerelease mode (no `stable=true`) until the user explicitly says "stable" again.

### Open follow-ups (unchanged from v1.0.0-600)
- Persistent keystore in Actions secrets so cross-release upgrades stop hitting `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- Bump `versionCode` per release (cosmetic, but proper).
- Component Manager port resume — branch `component-manager-injection` still pinned at `5b89073`.

## 2026-05-07 — base APK bump to GameHub 6.0.1

### Goal
Verify existing patch bundle still applies cleanly against the new XiaoJi GameHub 6.0.1 base APK.

### What changed
- New base APK on hand: `GameHub_6.0.1.apk` — `com.xiaoji.egggame` versionCode `111` (was `110`), versionName `6.0.1`. Same signing cert (`gamesir`), same `targetSdkVersion=36`.
- Branch: `gamehub-601-build` cut from `gamehub-600-build` per branch-per-patch workflow.
- Commit `ab70d25`: `Constants.kt` `GAMEHUB_VERSION` `6.0.0` → `6.0.1`; `release.yml` source release `base-apk-600` → `base-apk-601` and asset/staged/CLI filenames `GameHub_beta_6.0.0_global.apk` / `GameHub_6.0.0.apk` → `GameHub_6.0.1.apk`. Variant output filenames left at `GameHub-6.0-Patched-*.apk` (version-agnostic enough for now).
- New release `base-apk-601` created with `GameHub_6.0.1.apk` (133 MB) attached.

### Result — CI run [25517417367](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25517417367)
**All 9 variants green** in ~3 min. No fingerprint or smali repair needed; every patch (BypassLogin, DisableCrashlytics, DebugLog, FileManagerAccess, RewriteCustomPermissions, MuteUiSounds, RedirectCatalogApi, PrefixApiPath, ChangePackageName, ChangeAppName) still applies untouched against versionCode 111.

### Implication
6.0.1 is a minor base bump only — no fingerprint targets moved. `gamehub-601-build` is shippable as-is once release notes are written; release-body text + variant output filenames in `release.yml` should be updated before the first stable v1.0.0-601 cut, but those are cosmetic, not functional.

### Next
- Device-test one variant (likely Original) installed alongside or replacing v1.0.1-600.
- If install + login-bypass + import flow OK, draft v1.0.0-601 release notes (or v1.0.2-600 if we treat as a refresh).
- Decide whether to merge `gamehub-601-build` → `gamehub-600-build` after stable, or keep them parallel.

## 2026-05-07 — v1.0.0-601 stable shipped

### Tag
[`v1.0.0-601`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-601) — "Gamehub 6.0.1 - BannerHub API - Multi-Install" — 9 APKs + `.rvp` bundle + `.rve` extensions

### Build
- Branch: `gamehub-601-build` (kept separate from `gamehub-600-build` per user direction; not merged back)
- Final commit on branch: `990e30e` (release prep — release.yml/README rewrites)
- Stable CI run: [25518201750](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25518201750), all 9 variants green, release job published successfully

### What shipped
- Base APK: `GameHub_6.0.1.apk` (versionCode 111) from `base-apk-601` release
- Same 9 patches as v1.0.1-600 — bypass login, disable Crashlytics, debug logging, file manager (per-variant), rewrite custom permissions (per-variant), mute UI sounds, redirect catalog API, prefix /v6/, change package name (per-variant), change app name (per-variant)
- Functional delta from v1.0.1-600: zero. This is a base APK refresh only.
- Variant filenames bumped from `GameHub-6.0-Patched-*.apk` to `GameHub-6.0.1-Patched-*.apk` so users can tell which base version they're running.

### Awaiting
- Device test of any v1.0.0-601 variant. v1.0.0-601 should be drop-in compatible with v1.0.1-600 device test results; only base APK changed.

## 2026-05-07 — v1.0.0-601 BROKEN: BypassLoginPatch is a no-op on 6.0.1

### Symptom
Device test of v1.0.0-601 (Original variant, `com.xiaoji.egggame`): app launches, opening game library shows the login wall. Logcat captured 7× `SHOW_SOFT_INPUT_BY_INSETS_API` consistent with login-form text fields gaining focus.

### Diagnosis (proven)
**All four targets in `BypassLoginPatch.kt` silently no-op'd against 6.0.1.** R8 in 6.0.1 renumbered class letters; the patch's hardcoded `Los0;`/`Lxm7;`/`Lis0;`/`Lg8e;` literals still resolve to *some* class in 6.0.1, but those classes have completely different roles:

| Class | 6.0.0 role | 6.0.1 reality |
|---|---|---|
| `Los0;` | DB-backed auth session impl with `h()`/`e()` StateFlow getters | Tiny `Flow.emit` operator wrapper — only `<init>` + `emit()` |
| `Lxm7;` | `GameLibraryRepository.f()` returning userId | A `Lbf3;` SuspendLambda — only `<init>` + `invokeSuspend` |
| `Lis0;` | Auth interface with default `f()` returning `Ll4m;` | The **actual** auth/user repository now (has `UserDao` + `AuthTokenDao` fields); `f()` is `(String, Continuation)→Object` — wrong signature |
| `Lg8e;` | Navigator class with `i(rh0)`/`r(rh0)` Login gates | Kotlin lambda factory `<init>` + `invoke()` only |

**Hard proof:**
- `os0.smali` MD5 identical between base 6.0.1 and patched 6.0.1: `50bb1fc9ea86ab180234aed6fe1e1cd4`
- `FakeAuthToken`/`FakeUserAccount` extension classes ARE bundled in the patched APK (`smali_classes7/app/revanced/extension/gamehub/login/`) but **no smali outside DebugTrace string literal calls them** — the patch never injected the calls.
- CI run 25517417367 + stable run 25518201750 both passed because patcher v22's `firstMethod { ... }` is lenient — when no method matches, it silently no-ops. The "all 9 variants green" was a false positive on 6.0.1.

### Artifacts available on resume
- 6.0.1 base APK decompile: `/tmp/gh601_smali/` (apktool, --no-res)
- 6.0.1 patched APK decompile: `/tmp/gh601_patched_smali/`
- Logcat from device repro: `/home/claude-user/logcat-com.xiaoji.egggame-20260507-170151.txt`
- 6.0.0 (working) decompile may need to be regenerated: `~/GameHub_beta_6.0.0_global.apk` is on disk
- Patch source: `bannerhub-revanced/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/login/BypassLoginPatch.kt`

### Chosen fix path: Option A — string-literal refingerprinting
User picked re-fingerprinting with stable anchors (vs. quick-fix hardcoding new 6.0.1 letters) so the patch survives future minor versions (6.0.2, 6.0.3, …).

### What "string-literal refingerprinting" means here
Instead of `definingClass == "Los0;" && name == "h"`, anchor on things R8 *can't* mangle:
- **String literals** the class references (e.g., SharedPreferences keys like `"auth_token"`, screen route names like `"login"`/`"home"`, error messages)
- **DAO/Entity types** at instance-field level (`com.xiaoji.egggame.core.database.dao.UserDao`, `AuthTokenDao` — these names are stable, kept by R8 keep-rules)
- **Return-type shape** (e.g., method returning `Lf3k;` or kotlinx StateFlow)
- **Method-call sequences** (e.g., a method that calls both `UserDao.observeCurrent()` and `AuthTokenDao.observeCurrent()` is the auth-state combiner)

Concrete fingerprint targets to find in 6.0.1:
1. **Auth-session class** — has `UserDao` + `AuthTokenDao` instance fields; in 6.0.1 this is `Lis0;` (verified). Find via field-type fingerprint, not class name.
2. **isLoggedIn StateFlow getter** — method on the auth class returning `Lf3k;`/StateFlow over `Ljava/lang/Boolean;`. Find via return-type + a string-literal anchor.
3. **User-account StateFlow getter** — method returning StateFlow over the user entity (likely `Lcom/xiaoji/egggame/core/database/entity/UserEntity;` based on 6.0.1 smali).
4. **Auth token getter** — method returning the token wrapper class (was `Ll4m;` in 6.0.0 — verify shape in 6.0.1; probably renamed).
5. **GameLibraryRepository userId getter** — method returning `Ljava/lang/String;` keyed off the auth token.
6. **Navigator gate** — method that contains both `iget Lis0;->...` (or its 6.0.1 equiv) AND a string literal "login"/"home" in proximity. The instruction-pattern match (iget+invoke-interface+if-nez+new-instance) is what we re-anchor on.

### Resume checklist
1. Re-decompile current 6.0.0 base APK (`~/GameHub_beta_6.0.0_global.apk`) to confirm what role each renamed class held → use as cross-reference for what semantics we're trying to match in 6.0.1.
2. Find the auth-session class in 6.0.1 by field-type fingerprint (UserDao + AuthTokenDao). Confirmed candidate: `Lis0;`.
3. For each of the 6 targets above, write a fingerprint that resolves to the right method in 6.0.1.
4. Update `BypassLoginPatch.kt` to use ReVanced patcher's `MethodFingerprint` API instead of literal `definingClass ==` checks.
5. Add a runtime sanity log inside the patched method (one Log.i per gate fires) so future device tests immediately tell us if the patch ran.
6. Build, deploy, device-test on Original variant. Pull logcat and confirm the new sentinel logs fire AND the library opens without login.
7. Cut a v1.0.0-601 hotfix (probably v1.0.1-601) once verified.

### Decision parking lot
- v1.0.0-601 release is BROKEN (all installs hit login wall). Did not roll it back per user direction (we'll fix forward).
- 5.x stable v1.0.1-600 (gamehub-600-build) is unaffected — those patches still work against the 6.0.0 base.

## 2026-05-07 (cont.) — BypassLoginPatch rewritten for 6.0.1

### Mapping derived from base APK decompile
| 6.0.0 letter | 6.0.1 letter | Role |
|---|---|---|
| `Los0;` | `Lrs0;` | Auth-session impl (3 StateFlow fields, UserDao+AuthTokenDao ctor) |
| `Lis0;` | `Lls0;` | Auth-session interface (`a/b/c/d/e/f/g/h` methods) |
| `Lxm7;` | `Lhp7;` | GameLibraryRepository (`b:AUTH_INTERFACE` field, `f()String`) |
| `Lg8e;` | `Lade;` | Navigator (`b:AUTH_INTERFACE` field, `i(Lph0;)V` + `r(Lph0;)V` gates) |
| `Lga0;` | `Lca0;` | Login navigation intent (referenced from gates; not patched directly) |
| `Lrh0;` | `Lph0;` | Navigator i/r param type |
| `Ll4m;` | `Lfdm;` | Auth token wrapper (10-field data class, identical shape) |
| `Lf4m;` | `Ladm;` | User account (27-field data class, identical shape) |
| `Lf3k;` | `Lr8k;` | StateFlow read interface |
| `Lr8o;->r(Object)Lf3k;` | `Lumn;->h(Object)Lt8k;` | MutableStateFlow factory |

### What changed in code (commit pending)
- **`BypassLoginPatch.kt`**: full rewrite with all class letters extracted to a single named const block at top, accompanied by structural anchors (decompile recipes) for each. Patch body unchanged in semantics — same six targets — just sourcing names from the const block. Verified `Lt8k;` IS-A `Lr8k;` via `Lx6e;` so the synthetic flow returned from `rs0.h/e` type-checks against the declared return type.
- **NEW patch on `Lar0;->a(...)`**: 6.0.1 introduced a separate NavigationInterceptor (`getOrder()==10`, `Llxb;` interface) that gates on `Lls0;->a()Z` independently of the navigator. Same iget+invoke-interface+if-nez+new-instance pattern as `ade.i/r`; bypassed identically with `const/4 vN, 0x1`.
- **`FakeAuthToken.java`**: `Class.forName("l4m")` → `Class.forName("fdm")`, hoisted to `AUTH_TOKEN_CLASS` const for one-line bumps next time. Same 10-arg ctor (verified shape identical between `Ll4m;` and `Lfdm;`).
- **`FakeUserAccount.java`**: `Class.forName("f4m")` → `Class.forName("adm")`, hoisted to `USER_ACCOUNT_CLASS` const. Same 27-arg ctor (verified shape identical).
- **No inline `Log.i` sentinels** in the patched method bodies: `rs0.h/e` and `ls0.f` are `.locals 0`, can't accommodate two free registers without growing locals (which patcher v22's `addInstructions` doesn't auto-do). Sentinel signal is provided by the existing `DebugTrace.write` calls inside `FakeAuthToken.get()` and `FakeUserAccount.get()` — the next device test's logcat will show "FakeAuthToken.get() called" / "FakeUserAccount.get() called" lines tagged GH600-DEBUG, which only fire if the `ls0.f()` / `rs0.e()` patches landed.

### Why this still requires letter updates next minor version
Even with the const block, the letters `Lrs0;`/`Lls0;`/`Lhp7;`/`Lade;`/`Lar0;`/`Lumn;`/`Lt8k;`/`Lfdm;`/`Ladm;` will all rotate again on the next R8 build. The improvement vs. the original patch is:
- All letters are in ONE place (const block + 2 Java strings), so re-deriving a new mapping is a 9-string PR instead of a 4-file scavenger hunt.
- Each const has a structural anchor comment so the resolver recipe is recorded.
- True version-independence (no manual updates ever) would require resolving classes by structural fingerprint at patch time. Deferred — that's a bigger refactor and the const-block approach is sufficient for the 6.0.x series.

### Next: CI prerelease + device test
Trigger Release workflow (no `stable=true`) on `gamehub-601-build`. Pull artifact APK, install Original variant, repro library tap. Logcat must show:
- `GH600-DEBUG: FakeAuthToken.get() called`  → `ls0.f()` patch fired
- `GH600-DEBUG: FakeUserAccount.get() called` → `rs0.e()` patch fired
- Library opens without login wall → `rs0.h()` + navigator + interceptor patches all working

## 2026-05-07 (cont. 2) — API redirect patches also broken on 6.0.1

### Symptom
Device test of bypass-fix prerelease: login bypass + mute UI work, but BannerHub catalog API redirect doesn't take effect. App still hits upstream landscape-api.vgabc.com instead of the Worker.

### Diagnosis
Same R8 letter-shuffle pattern as BypassLoginPatch. Verified by inspecting prerelease patched APK: `zhj.smali` (the new Environment enum) still contains `"landscape-api-cn.vgabc.com"` and `"landscape-api-oversea.vgabc.com"` literals — `RedirectCatalogApiPatch.kt` silently no-op'd because it's hunting `Lmcj;` which is a different class in 6.0.1.

### Mapping
| 6.0.0 | 6.0.1 | Role |
|---|---|---|
| `Lmcj;` | `Lzhj;` | Catalog Environment enum (Online/Beta/Test); contains the host string literals |
| `Lzdb;` | `Lohb;` | Static URL-path helper (Ktor pipeline) |
| `Lqx9;` | `Lj1a;` | URL builder param type (Ktor HttpRequestBuilder.url) |
| `Lm1l;->t1` | `Lu9l;->s1` | String trim helper (referenced inside the body, not patched directly) |

### Fix
- **`RedirectCatalogApiPatch.kt`**: `MCJ_CLASS = "Lmcj;"` → `ENV_ENUM_CLASS = "Lzhj;"` with structural anchor comment ("the unique class containing both `landscape-api-cn.vgabc.com` and `landscape-api-oversea.vgabc.com`"). String literals are R8-stable so the anchor survives future minor versions; only the ONE letter `Lzhj;` needs updating per bump (or could be auto-derived).
- **`PrefixApiPathPatch.kt`**: `ZDB_CLASS = "Lzdb;"` → `URL_HELPER_CLASS = "Lohb;"`, plus new `URL_BUILDER_TYPE = "Lj1a;"` const replacing the hardcoded `"Lqx9;"` in the parameterTypes match. Body shape is byte-stable across versions; the anchor comment records the structural recipe (static method `(LBuilder;String)V` whose body starts iget-object from the builder's URL field then calls a string-trim helper).

### Re-verify after this fix
After CI: pull the new artifact, decompile, confirm `zhj.smali` now shows `"bannerhub-api.the412banner.workers.dev"` in BOTH host slots; confirm `ohb.b(Lj1a;String)V` opens with `invoke-static {p1}, V6PathPrefix->prefix(...)`.

## 2026-05-07 (cont. 3) — Device test ALL GREEN; cutting v1.0.1-601 hotfix

User confirmed prerelease build (run 25526407710, commit 3f81890) on Original variant: login bypassed, library opens, BannerHub catalog API redirect working, mute UI working. All 9 patches now actually applying as intended on 6.0.1.

### Cut as v1.0.1-601 stable
- Tag: v1.0.1-601
- Title: "Gamehub 6.0.1 - BannerHub API - Multi-Install" (same as v1.0.0-601)
- release.yml body rewritten with "Hotfix vs v1.0.0-601" section explaining the 3 patches that no-op'd and now don't
- README "Latest stable" pointer + What's-new block updated; broken-build warning added at the top

## 2026-05-07 (cont. 4) — v1.0.1-601 SHIPPED + cleanup

### Live release
[`v1.0.1-601` — Gamehub 6.0.1 - BannerHub API - Multi-Install](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.1-601). 9 APKs + .rvp bundle + .rve extensions. CI run 25526848503, branch head `9ea01d4`.

### Cleanup
- Deleted v1.0.0-601 release entirely (was already de-listed; tag also removed from origin + local).
- v1.0.1-601 is now Latest.
- Memory file `project_bypass_login_601_refingerprint.md` deleted (obsolete now that the fix shipped).
- Memory `project_bannerhub_revanced.md` updated: gamehub-601-build entry rewritten, "Active next step" block rewritten to reflect shipped state, MEMORY.md index entry updated.

### What we know works on 6.0.1 (per device test of prerelease run 25526407710)
1. ✅ Bypass login (rs0.h/e + ls0.f + hp7.f + ade.i/r + ar0.a)
2. ✅ Redirect catalog API (zhj Online enum hosts → Worker)
3. ✅ Prefix API path with /v6 (ohb.b inserts V6PathPrefix call)
4. ✅ Mute UI sounds
5. (untested but applied per CI) Disable Crashlytics, Debug logging, File manager, Rewrite custom permissions, Change package name, Change app name

### Known follow-up
None blocking. Possible future work: migrate the const-block letter mapping to true MethodFingerprint-based auto-discovery so future minor-version bumps don't require any source edits at all. Deferred — current setup is good enough for the 6.0.x series.

## 2026-05-07 — vjoy cloud-share Worker proxy (cross-repo) + docs

User reported the new 6.0.1 cloud-share vjoy/Scheme screen still showed "log in first" even with bypass-login active. Diagnosed via temporary KV-debug intercept on the Worker: the 401 is server-side (upstream rejects unauthenticated requests on `vcontroller/recommendMapList`); the client sends GETs with `clientparams`/`sign`/`time` headers but no token at all. Existing Worker fall-through stripped all original headers and never injected a token, so upstream got an anonymous request → 401 → "Please login first".

Fixed in `bannerhub-api` repo (commit `0792400` on master + main): new dedicated handler covering `vcontroller/*`, `simulator/{configList,getConfigById,shareConfig,deleteShareConfig,reportConfigApply}`, `readLayoutType/*`, `writeLayoutType/*`. Forwards original request headers verbatim, drops only hop-by-hop and CF-injected ones, injects `token: <bannerhub_token>` from KV, and recomputes `sign` for POST bodies that contain a token field. Verified live → device-confirmed: full upstream catalog visible (GTA5专用按键, Gamehub 2, etc.). Side-effect: every BannerHub user authenticates as the same shared `bannerhub_token` user — acceptable for now.

### Documentation updates
- `gamehub_reports/GAMEHUB_600_MASTER_MAP.md` — added § 26 (6.0.0 → 6.0.1 deltas) covering APK identity bump, full R8 letter-remap table with structural anchors, new vjoy/Scheme cloud-share subsystem (NavKeys, data model, repository, ViewModel, on-device storage), new API endpoint family, new `Lar0;` NavigationInterceptor, firmware 1.3.4 → 1.3.5, upstream feature highlights. Map grew 2556 → 2702 lines.
- `gamehub_reports/BANNERHUB_API_6.0_INTEGRATION.md` — added § 14 (2026-05-07 6.0.1 changes) covering v1.0.1-601 hotfix, R8 letter remap, firmware bump, new endpoint family, captured request-shape table, Worker proxy implementation, verification, files-touched manifest.
- Memory: `project_gamehub_600_master_map.md`, `project_bannerhub_api_60_integration_report.md`, `project_bannerhub_api_worker.md`, `MEMORY.md` index — all updated to reflect the new sections + line counts + the "on the next 6.0.x bump" instructions for re-deriving R8 letters via structural anchors.

## 2026-05-10 — gamehub-602-build (GameHub 6.0.2 base bump)

### Goal
Bump base from GameHub 6.0.1 (versionCode 111) to 6.0.2 (versionCode 112). Re-anchor every bytecode patch against the new R8 letter map, mirror what we did for the 6.0.0 → 6.0.1 jump.

### Branch + base APK
- Branch: `gamehub-602-build`, forked from `gamehub-601-build` head (`9ea01d4`).
- Base APK: `GameHub_6.0.2.apk` (135 MB, package `com.xiaoji.egggame`, versionCode 112).
- Hosted as release tag [`base-apk-602`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-602) (target = `gamehub-602-build`). Workflow downloads from there during patch-build.
- Decompile: `apktool d --no-res` against the 6.0.2 APK lands in `/tmp/gh602_smali/` across `smali/`, `smali_classes2/` … `smali_classes6/` (6 dex shards, up from 5 on 6.0.1). r8-map-id `032c299c671f291b037da144c04f4b9bdf25a0ddc75c43b14ff2382d5f50d1fa` is the unique build identifier — every smali file's `.source` line carries it, useful as a single-grep verification that the decompile is actually 6.0.2 and not a 6.0.1 leftover.

### Fingerprint verification — every single bytecode anchor moved

Same situation as 6.0.0 → 6.0.1: a sweeping R8 letter reshuffle. None of the 6.0.1 letter classes still hold the same shape in 6.0.2 (some keep their letter but the body is now an unrelated coroutine continuation). New mappings derived via the structural-anchor recipes recorded in each patch source:

| Patch | Anchor | 6.0.1 | 6.0.2 | How re-derived |
|---|---|---|---|---|
| BypassLogin | AUTH_IMPL | `Lrs0;` | `Lit0;` | Class with ctor `(UserDao, AuthTokenDao, Lu70;)V` + 3 same-type StateFlow fields |
| BypassLogin | AUTH_INTERFACE | `Lls0;` | `Lct0;` | `.implements` line on AUTH_IMPL — interface with abstract `d/e/h()Lrjk;` |
| BypassLogin | AUTH_TOKEN | `Lfdm;` | `Lkpm;` | 10-field data class returned by `ct0.f()`; ctor sig `(S,S,S,S,Long,Long,J,Z,J,J)V` matches exactly |
| BypassLogin | GAME_LIB_REPO | `Lhp7;` | `Luu7;` | Class with `b:Lct0;` field + ctor `(GameLibraryDatabase, Lct0;)V`. ⚠ userId getter renamed `f()` → `e()` between 6.0.1 and 6.0.2 — patch updated to filter by `parameterTypes.isEmpty() && returnType == "Ljava/lang/String;"` to avoid matching unrelated overloads |
| BypassLogin | NAVIGATOR | `Lade;` | `Lxle;` | Class with `b:Lct0;` field + `i(Lgi0;)V` and `r(Lgi0;)V` methods that contain the `iget b:ct0` + `invoke-interface ct0->a()Z` + `new-instance Lsa0;` (Login intent — was Lca0; in 6.0.1) gate pattern at xle.i:270 / xle.r:79 |
| BypassLogin | NAV_INTERCEPTOR | `Lar0;` | `Lrr0;` | Class with `<init>(Lct0;)V` ctor + `a(Lp4c;Ls2c;Lzh3;)Object` that calls `ct0.a()Z` then builds `Lo5c;` redirect |
| RedirectCatalogApi | ENV_ENUM_CLASS | `Lzhj;` | `Lxrj;` | Unique class containing both `landscape-api-cn.vgabc.com` and `landscape-api-oversea.vgabc.com` string literals (in xrj's `<clinit>` at lines 41/45) |
| PrefixApiPath | URL_HELPER_CLASS | `Lohb;` | `Lvob;` | Static method `b(L<short>;Ljava/lang/String;)V` whose body starts with `iget-object` from the builder's URL field then `Lpll;->s1(CharSequence)CharSequence` trim |
| PrefixApiPath | URL_BUILDER_TYPE | `Lj1a;` | `Lm7a;` | First param of vob.b — the Ktor URLBuilder analog |
| DebugLog | y2d-impl | `Lodb;` | `Li86;` | Concrete class implementing `pgd.e(Throwable, Lmw6;)V` whose body delegates to `pgd.e` then writes to a sink |
| DebugLog | save method | `Lxm7;->u` | `Luu7;->v` | 3-arg `(GameInfo, LaunchMethod, Continuation)` method on uu7 that calls `pgd.e` in catch (line 322) |
| DebugLog | retro upsert wrapper | `Ly4i;->b` | `Lyji;->b` | Single class referencing `RetroGameDao;->upsert` (yji.smali:97) |
| DebugLog | y2d-interface | `Ly2d;` | `Lpgd;` | Abstract method `e(Ljava/lang/Throwable;Lmw6;)V` (`Lmw6;` is the Function0 type) |
| DebugLog | inner Room txn | `Lel7;` | `Lvs7;` | Continuation class with `invokeSuspend.locals 70` (closest to original `el7` `.locals 69`) and both `GameLaunchMethodDao.insert` + `GameLibraryBaseDao.insert` |

Patches keyed on full Android / Firebase / asset-path / manifest names — Disable Crashlytics (anchored on `Lcom/xiaoji/egggame/BaseAndroidApp;->onCreate` + `FirebaseCrashlytics->getInstance` / `setCrashlyticsCollectionEnabled`), Mute UI sounds (asset path `assets/composeResources/com.xiaoji.egggame.core/files/sound`), File manager access (manifest), Rewrite custom permissions (manifest), Change package name / Change app name (revanced built-ins) — apply byte-for-byte without any source change.

### MutableStateFlow factory surgery

The 6.0.0 / 6.0.1 patch could call `Lumn;->h(Object)Lt8k;` inline — a one-arg StateFlow factory whose return type was directly assignable to `AUTH_INTERFACE.h()` / `.e()`'s declared return type. In 6.0.2 the only one-arg factory is `Ltwo;->l(Object)Ltjk;`, and `Ltjk;` does NOT implement the abstract StateFlow interface (`Lrjk;`) that `h()`/`e()` declare; the host wraps it in `Lhzh;` (which DOES implement Lrjk;) before exposing it via `Leuo;->e0` (stateIn).

Doing the same wrap inline from smali would require growing the patched method's `.locals` from 0 to 2 (need scratch registers for the inner Ltjk; instance and the outer Lhzh; instance). To avoid that, added a `FakeStateFlow` Java extension that does the wrap via reflection (`Class.forName("tjk")` → `<init>(Object)` → `Class.forName("hzh")` → `<init>(vfe)` where `Class.forName("vfe")` is the Lhzh; ctor's interface arg). Both factories cached after first build. Smali edit stays a single `invoke-static`:

```smali
invoke-static {}, FakeStateFlow->boolTrue()Ljava/lang/Object;
move-result-object p0
return-object p0
```

Updated FakeAuthToken (`adm` → `kpm`) and FakeUserAccount (`fdm` → `fpm`) extension class refs. `FakeUserAccount`'s 27-arg ctor signature stayed byte-identical between 6.0.1 and 6.0.2.

### Workflow + release notes
`.github/workflows/release.yml` rewritten for 6.0.2: base APK download tag, all 9 variant filenames (`GameHub-6.0.2-Patched-*.apk`), release name + body. Release narrative documents the 6.0.1 → 6.0.2 letter remap so future readers don't have to guess.

### CI verification
First test build kicked off as run [`25619647877`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25619647877) on `gamehub-602-build` via `gh workflow run release.yml --ref gamehub-602-build -f tag=v0.1.0-602-test` (artifact-only prerelease — not a stable cut). Watching for green; if patches build cleanly the next step is device-test the 9 APKs to confirm bypass-login + catalog-redirect + /v6 prefix all fire end-to-end.

### CI verification result
Run [`25619647877`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25619647877) **green in 2m49s** (started 04:20:19 UTC, finished 04:23:08 UTC):
- ✅ Build patches bundle
- ✅ Patch Normal / Normal-GHL / PuBG / AnTuTu / alt-AnTuTu / PuBG-CrossFire / Ludashi / Genshin / Original (all 9)
- ⏭ Create GitHub Release (intentionally skipped — `stable=false` on this test run)

Every re-anchored fingerprint matched at patcher time. All 9 APK artifacts (~111 MB each) on the run, 14-day retention. **Next step: device test** — install the `Original` artifact (or any of the variants) and confirm bypass-login + catalog redirect + /v6 prefix all fire on 6.0.2 the same as they did on 6.0.1, then cut a stable release.

### Stable release — v1.0.0-602 (2026-05-10 14:59 UTC)

[`v1.0.0-602` — Gamehub 6.0.2 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-602) is live. Stable cut as run [`25631854018`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25631854018) — all 9 variant patch jobs succeeded plus the `release` job (`stable=true`), 12 assets attached: the 9 patched APKs + the `.rvp` patch bundle + the 2 `.rve` extension files. Device-tested by user before stable cut. Release notes ship the 6.0.1 → 6.0.2 R8 letter remap table and the `FakeStateFlow` rationale verbatim.

This is the first stable on the `gamehub-602-build` branch and the first one in the project's history that didn't require a `.0` → `.1` hotfix because the structural-anchor recipes recorded after the 6.0.0 → 6.0.1 fiasco let every fingerprint be re-derived correctly on the first try. README updated to reflect 6.0.2 as the latest stable; v1.0.1-601 / v1.0.0-601 / v1.0.1-600 sections moved into "historical" sections at the top.

## 6.0.4 base port — 2026-05-12

### Setup

User requested 6.0.4 base bump from 6.0.2 stable. `GameHub_6.0.4.apk` (versionCode 114, versionName 6.0.4) staged from Downloads → home → released as `base-apk-604`. Decompile to `/tmp/gh604_smali` with the standard recipe. New branch `gamehub-604-build` cut off `gamehub-602-build` head `abf1eac` per the branch-per-patch rule.

### R8 letter delta (6.0.2 → 6.0.4)

R8 map id `6a5cde6143fc8cf76f6f3a447d0fececd4794d83066e6ead7a9537e6527b057b` (vs 6.0.2's `032c299c…`). Every anchor reshuffled again. Full per-anchor verification + structural-recipe trace lives in `gamehub_reports/GH604_LETTER_MAP.md`.

| Patch | Anchor | 6.0.2 | 6.0.4 | Structural verification |
|---|---|---|---|---|
| BypassLogin | AUTH_IMPL | `Lit0;` | `Ljt0;` | 3× `Lozh;` fields, ctor `(UserDao, AuthTokenDao, Lv70;)V`, implements `Ldt0;` |
| BypassLogin | AUTH_INTERFACE | `Lct0;` | `Ldt0;` | Abstract `d/e/h()Lyjk;` + `f()Lwpm;` + `b()Lrpm;` |
| BypassLogin | AUTH_TOKEN | `Lkpm;` | `Lwpm;` | 10-field data class returned by `dt0.f()`, ctor sig `(S,S,S,S,Long,Long,J,Z,J,J)V` matches exactly |
| BypassLogin | GAME_LIB_REPO | `Luu7;` | `Lvu7;` | Class with `b:Ldt0;` field + ctor `(GameLibraryDatabase, Ldt0;)V`. User-id getter still `e()` |
| BypassLogin | NAVIGATOR | `Lxle;` | `Lgme;` | `b:Ldt0;` field + `i(Lhi0;)V` and `r(Lhi0;)V` methods carrying iget+invoke+if-nez+new-instance `Lta0;` (Login intent — was `Lsa0;` in 6.0.2) |
| BypassLogin | NAV_INTERCEPTOR | `Lrr0;` | **`Liod;` (skipped)** | Inline auth check moved into coroutine continuation `Lhod;->invokeSuspend` (lines 255/259/267). Apply block commented out; option C TODO recorded for later |
| RedirectCatalogApi | ENV_ENUM_CLASS | `Lxrj;` | `Lesj;` | Unique class containing both `landscape-api-cn.vgabc.com` + `landscape-api-oversea.vgabc.com` |
| PrefixApiPath | URL_HELPER_CLASS | `Lvob;` | `Lcpb;` | Static `b(Ln7a;Ljava/lang/String;)V` body: iget URL field → invoke trim → toString → length |
| PrefixApiPath | URL_BUILDER_TYPE | `Lm7a;` | `Ln7a;` | First param of cpb.b; field `a:Lokm;` (Ktor builder shape preserved). Trim helper moved from `Lpll;->s1` to `Lbml;->s1` (patch doesn't reference it directly) |
| DebugLog | y2d-impl | `Li86;` | `Lj86;` | Concrete class with `e(Throwable, Lnw6;)V` delegating to `Lxgd;->e` |
| DebugLog | y2d-interface | `Lpgd;` | `Lxgd;` | Abstract `e(Throwable, Lnw6;)V` + 9 other methods. `Lnw6;` is the Function0 type (was `Lmw6;`) |
| DebugLog | save method | `Luu7;->v` | `Lvu7;->v` | Same method name; new owning class follows GAME_LIB_REPO |
| DebugLog | retro upsert wrapper | `Lyji;->b` | `Lfki;->b` | Single field `a:RetroGameDao`, method `b(RetroGameEntity, Ci3)Object` is the only meaningful upsert wrapper |
| DebugLog | inner Room txn | `Lvs7;` | `Lws7;` | `.locals 70`, calls both `GameLaunchMethodDao;->insert` and `GameLibraryBaseDao;->insert` |
| OfflineComponentCache | ECI_CLASS | `Leci;` | `Lmci;` | `a(RepoCategory, Lci3;)Ljava/io/Serializable;` — unique match in dex tree |
| OfflineComponentCache | CONTINUATION_TYPE | `Lai3;` | `Lci3;` | Inferred from mci.a's second parameter |
| OfflineComponentCache | KOTLIN_EMPTY_LIST_CLASS | `Lz85;` | `Lw85;` | `implements List, Serializable, RandomAccess` (kotlin.collections.EmptyList) — sget'd at `:goto_2` in mci.a |
| FakeAuthToken ext | AUTH_TOKEN_CLASS | `kpm` | `wpm` | Same as BypassLogin AUTH_TOKEN |
| FakeUserAccount ext | USER_ACCOUNT_CLASS | `fpm` | `rpm` | 27-field class matching reflective 27-arg ctor lookup; `dt0.b()` returns `Lrpm;` |
| FakeStateFlow ext | STATE_FLOW_IMPL_CLASS | `tjk` | `akk` | `<init>(Object)V`, implements `Ldge;` (the holder iface) |
| FakeStateFlow ext | STATE_FLOW_WRAPPER_CLASS | `hzh` | `ozh` | `<init>(Ldge;)V`, implements `Lyjk;` (the abstract StateFlow interface) |
| FakeStateFlow ext | STATE_FLOW_HOLDER_INTERFACE | `vfe` | `dge` | Inferred from ozh ctor + akk `.implements` |

Patches unchanged (no R8-renamed anchors): Disable Crashlytics, Mute UI sounds, File manager access, Rewrite custom permissions, Change package/app name. Constants.GAMEHUB_VERSION bumped `6.0.2` → `6.0.4`.

### NAV_INTERCEPTOR architectural change (the "fingerprint problem")

In 6.0.0–6.0.2 the NAV_INTERCEPTOR's `a(...)` method body held the auth check inline — patch hooked on `iget AUTH_INTERFACE` + `invoke-interface a()Z` + `if-nez` + `new-instance Lredirect;`. In 6.0.4 `Liod;->a(Lrdb;Lzzn;Laem;)V` no longer iget's its `a:Ldt0;` field directly — it constructs a coroutine continuation `Lhod;` and dispatches to it. The pattern the patch looked for now lives at `Lhod;->invokeSuspend` (lines 255/259/267), reading the AUTH_INTERFACE from `p1` (the outer iod reference) not `p0` (this is now the continuation).

Three options documented:
- **A (chosen for v1)**: Skip NAV_INTERCEPTOR entirely. Other 6 BypassLogin hooks (AUTH_IMPL fake StateFlows + NAVIGATOR i/r gates + GAME_LIB_REPO user-id getter + is0.f → FakeAuthToken) may cover the user-facing surface. Cheapest.
- **B**: Patch `Liod;->a` body wholesale (return-void or passthrough). Risk: unclear what downstream navigation expects.
- **C**: Hook `Lhod;->invokeSuspend` directly — most surgical but needs continuation-state-machine-aware edits.

If device testing reveals a login-redirect leak, implement option C. The `NAV_INTERCEPTOR` constant value is kept (`@Suppress("unused")` `"Liod;"`) and the apply block left commented in `BypassLoginPatch.kt` for archaeology.

### CI verification result

Run [`25747297755`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25747297755) on `gamehub-604-build` head `147d63c` triggered via `gh workflow run release.yml --ref gamehub-604-build -f tag=v0.0.1-604-test` (artifact-only prerelease). All 11 jobs green:

- ✅ Build patches bundle
- ✅ Patch Normal / Normal-GHL / PuBG / AnTuTu / alt-AnTuTu / PuBG-CrossFire / Ludashi / Genshin / Original (all 9 variants)
- ⏭ Create GitHub Release (intentionally skipped — `stable=false`)

Every re-anchored fingerprint matched at patcher time. 9 APK artifacts on the run (14-day retention). **Next step: device test** — install any variant, confirm launch lands on home screen (bypass login working), confirm game-import save persists (library DB writes working), confirm catalog redirect + /v6 prefix all fire. If no login-redirect leak from the skipped NAV_INTERCEPTOR, cut stable. If a leak surfaces, implement option C.

### Known caveat — release notes

`.github/workflows/release.yml` body text still narrates the 6.0.1 → 6.0.2 migration. Workflow + variant filenames + `base-apk-604` download + release title were swapped, but the long-form release-page markdown remains 6.0.2-flavored. Only relevant if `stable=true` is flipped on the next dispatch; rewrite before cutting a 6.0.4 stable release.

### Device-test pass — 2026-05-12

User installed and verified one of the run 25747297755 artifacts on real hardware. End-to-end working: bypass-login lands on home screen, catalog redirect to BannerHub Worker + /v6 prefix both firing, game-import path persists rows correctly. The skipped NAV_INTERCEPTOR (Option A) had no observable effect — the remaining six BypassLogin hooks (AUTH_IMPL h/e/d + NAVIGATOR i/r gates + GAME_LIB_REPO.e + is0.f → FakeAuthToken) cover the user-facing surface fully on 6.0.4. Option C (hook `Lhod;->invokeSuspend`) is **not needed**.

**Status:** 6.0.4 patch port is feature-complete and verified. Ready for stable cut (`v1.0.0-604`) once release.yml's body text is rewritten from 6.0.1→6.0.2 narrative to 6.0.2→6.0.4 narrative.

### Stable release — v1.0.0-604 (2026-05-12 17:56 UTC)

[`v1.0.0-604` — Gamehub 6.0.4 - BannerHub API - Patched](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.0.0-604) is live. Stable cut as run [`25752321469`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25752321469) — all 9 variant patch jobs succeeded + the `release` job (`stable=true`), 12 assets attached (9 APKs ~114 MB each + the `.rvp` bundle/sources). Branch head at release: `508cede` on `gamehub-604-build`.

Release notes ship the 6.0.2 → 6.0.4 R8 letter remap table, the `NAV_INTERCEPTOR` skip rationale (Liod;->a's inline auth check moved into the `Lhod;` coroutine continuation in 6.0.4 — left commented in the patch source as a starting point for option C if a future regression requires it), the `FakeStateFlow` letter-trio update (`tjk/hzh/vfe` → `akk/ozh/dge`), the `Offline component cache fallback` patch's first stable shipping (was on `gamehub-602-build` post-602 stable but never made it into a release), and — at the user's call-out — the **server-side Steam-launch fix** in the BannerHub API Worker. The previous "use Lightweight Steam client only" warning is dropped from both `release.yml` and `README.md`; both standard and Lightweight Steam clients now launch games end-to-end (server-side, retroactive to existing patched builds).

Second consecutive base bump (after v1.0.0-602) where the structural-anchor recipes caught every fingerprint on the first patcher run — no .0 → .1 hotfix needed.

README updates: header bumped to 6.0.4 latest stable, v1.0.0-602 moved to historical, offline-cache "unreleased" markers dropped, Source + Build sections point at `base-apk-604` and `gamehub-604-build`, Variants table file names refresh, Known limitations drops the standard-Steam-client warning.

### Post-release tweak — xtask first-launch hint (2026-05-12)

User-flagged caveat for v1.0.0-604: if a user's **first** Steam game launch errors with `xtask install components failed`, the workaround is to open that title's PC game settings, set components manually (Wine prefix, DXVK, VKD3D, container, etc.), confirm the correct Steam client is selected, then retry — it's a one-time setup hiccup, not a fatal regression. Added to both `release.yml` body (commit `b9397c8`) and README's "what's new" Steam bullet; live `v1.0.0-604` release body refreshed via `gh release edit --notes-file` so the in-flight published page already carries the hint.

### Post-release release-page trim (2026-05-12)

User feedback: the v1.0.0-604 release page is too long; the per-patch R8 letter-by-letter delta table and the per-patch smali-edit walkthrough in the "Patches applied" table aren't useful for end users. Trimmed both:

- The base-APK-refresh section dropped its six bullet-pointed letter-by-letter remap and now just says the patches were re-anchored, with a pointer line to `gamehub_reports/GH604_LETTER_MAP.md` + the patch sources for anyone who wants the gritty detail. (commit `2771684`)
- The "Patches applied" table rows are now one-line user-facing descriptions of what each patch does, instead of the patcher-side mechanics. A single sentence below the table links to the README's Patches applied section + the patch source directory for the deeper breakdown. (commit `3b89575`)

Both updates pushed to `release.yml` for future re-cuts and mirrored onto the live `v1.0.0-604` release body via `gh release edit --notes-file`. README is intentionally untouched — its long-form section is the canonical deep-dive and the release page now links to it.

### Offline component cache fallback — flagged broken on 6.0.4 (2026-05-12)

User reported the offline component cache fallback patch isn't delivering the intended behavior at runtime on 6.0.4 despite patcher-side success (CI green, the patch applies, structural anchors all resolved correctly). Flagged as **currently broken** in `release.yml` body, the README's "what's new" bullet AND its detailed `### Offline component cache fallback` section, and at the top of `OfflineComponentCachePatch.kt` itself. Live `v1.0.0-604` release body refreshed via `gh release edit --notes-file` so users see the warning. Patch stays enabled per user direction.

Investigation angles to check on the next pass (recorded at top of the patch source):
- Has `mci.a(RepoCategory, Continuation)` itself changed shape beyond the `:goto_2` sentinel? Walk the full method body and compare against the 6.0.2 `eci.a` body the patch was designed around.
- Did `xxo`'s field layout / `xxo.c` `ConcurrentHashMap` type change? `PickerCacheFallback.fromXxo` uses single-letter field lookups (`a`, `c`) plus a runtime type sanity check; if `c`'s declared type is no longer assignable to `Map`, the sanity check returns the empty ArrayList silently — visible only via `DebugTrace`.
- Is `u6o.<init>` still the disk-hydrator? If 6.0.4 renamed/restructured the hydrator the map could simply be empty at the time the picker consults it, in which case the patch IS firing but has nothing to return.

## Vibration port to 6.0.4 — feature/vibration branch (2026-05-12)

User requested porting BannerHub PR #80 (TideGear's PC-accurate XInput rumble support, shipped in BannerHub v3.7.0 stable on 5.3.5) as a ReVanced patch for our 6.0.4 build. TideGear had already done the legwork to port it to GameHub 6.0.2 at https://github.com/TideGear/GameHub-Vibration-Fix — only 4 smali hooks on 6.0.2 (vs 5 on 5.3.5; 6.0 fixed the lazy-attach issue natively so the GamepadManager.B0 wake-up hook is unnecessary).

### Feasibility verification

Verified each of TideGear's 4 smali anchors against the 6.0.4 decompile. Trap caught: the 6.0.2 letters `Lza8;` (Physical) and `Ldg5;` (EnvBuilder) both still exist as class names in 6.0.4, but R8 reassigned them to completely unrelated classes (an empty marker interface and a coroutine continuation respectively). Naive name matching would have patched the wrong code; structural matching by method shapes + field layouts found the true 6.0.4 equivalents.

### 6.0.2 → 6.0.4 vibration-anchor delta

| Symbol | 6.0.2 (TideGear) | 6.0.4 (re-derived) | Recipe |
|---|---|---|---|
| `GamepadServerManager.onRumble(III)V` | same | same | Annotated `@Keep`, `:cond_4` label preserved |
| Physical class | `Lza8;` | `Lab8;` | `public final` extends `Lcb8;`, `g(II)V`/`f()V` shapes preserved |
| Physical.k field type | `Llrl;` | `Lxrl;` | Motor manager |
| EnvBuilder class | `Ldg5;` | `Lbg5;` | `a(...)V` `.locals 35`, anchor block lines 458-465 byte-identical |
| Join helper class | `Lns2;` | `Lps2;` | CollectionsKt joinToString$default |
| Join method name | `I0` | `I0` | **survived R8** |
| Function1 lambda type | `Low6;` | `Lpw6;` | |

### Branch state

`feature/vibration` cut off `gamehub-604-build` head `65e6902` 2026-05-12. Head: `4b25858`.

### Stage 1 — bytecode hooks + manifest registration (commit `0ae2228` → `248f7bd`)

- `extensions/gamehub/.../com/xj/winemu/vibration/BhVibrationController.java` (1106 lines, TideGear's package preserved verbatim — only Android SDK imports, no host references)
- `extensions/gamehub/.../com/xj/winemu/vibration/BhVibrationSettingsActivity.java` (266 lines)
- `patches/.../gamehub/vibration/VibrationPatch.kt` — 4 bytecode hooks with the 6.0.4 letters above
- `patches/.../gamehub/vibration/VibrationManifestPatch.kt` — registers BhVibrationSettingsActivity (exported=false, translucent theme)
- `extensions/gamehub/build.gradle.kts` — added lint suppression for `MissingPermission` / `NewApi` / `WrongConstant` (false positives — host APK declares VIBRATE permission and host targets Android 14, but extension lint runs in isolation against compile-only stubs).

CI run [`25761322965`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25761322965) green on commit `248f7bd`. Bytecode patches all applied across the 9 variants — but the LD_PRELOAD inject was inert (no .so to find at runtime).

### Stage 2 — NDK build + native-shim injection (commit `d9b9c96` → `4b25858`)

- `native/evshim/evshim.c` + `CMakeLists.txt` (TideGear's source copied verbatim — 698 lines of C, patches `winebus.so`'s `pSDL_JoystickRumble` + `pSDL_JoystickClose` .bss pointers via `LD_PRELOAD`)
- `patches/.../gamehub/vibration/VibrationLibPatch.kt` — resource patch that reads `libevshim.so` from the .rvp's classloader resources and writes it into the staged APK's `lib/arm64-v8a/`. Sentinel class (`private object VibrationLibResources`) used as classloader anchor to dodge Kotlin's self-referential type inference (can't reference `vibrationLibPatch::class` inside its own initializer body).
- `.github/workflows/release.yml` — new "Build libevshim.so" step inserted before the gradle build: locates the runner's NDK, builds via cmake/ninja for arm64-v8a android-29, drops the output under `patches/src/main/resources/lib/arm64-v8a/` so gradle bakes it into the .rvp.

CI run [`25761713424`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25761713424) green on commit `4b25858`. Now end-to-end: NDK builds .so → gradle bakes it into .rvp → revanced-cli applies → resource patch copies .so into APK's lib dir → bytecode hook injects LD_PRELOAD at runtime → libevshim re-issues SDL rumble every 500ms to defeat the 1s auto-stop.

### Pending

**Device test.** Pull any variant from run 25761713424 artifacts (14-day retention) or trigger a fresh run with a named tag. Install on a phone with at least one Bluetooth rumble-capable controller (DualSense / DS4 / 8BitDo Pro 2 in XInput mode). Launch any Wine PC game that uses XInput rumble (Brawlhalla, Diablo, etc.). Expected: heavy/light motors driven independently, sustained holds last as long as the in-game rumble effect, instant release on let-go.

If the device test passes, merge `feature/vibration` → `gamehub-604-build` and cut a follow-up release (e.g. `v1.1.0-604` for the feature bump).

### Stage 3 — Hook 4 VerifyError fix (2026-05-12)

**Device crash on first game launch.** User installed a variant from run 25761713424, opened a game, and `banner.hub:wine` immediately died at `WineActivity.onCreate` with:

```
java.lang.VerifyError: Verifier rejected class bg5:
  void bg5.a(eco, java.lang.String, boolean):
  [0x1F2] target dex pc 0x28 is not at instruction start.
```

Logcat: `/data/data/com.termux/files/home/log_2026_05_12_19_54_05.log`.

**Root cause.** Hook 4 (the EnvBuilder LD_PRELOAD inject) used `addInstructions` with a label `:bh_skip_evshim_preload` placed at the END of the inserted block:

```smali
if-eqz v15, :bh_skip_evshim_preload
const/4 v15, 0x0
invoke-virtual {v12, v15, v13}, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
:bh_skip_evshim_preload
```

When `addInstructions` parses the snippet, smali assigns the trailing label an offset of *block-length-in-bytes* relative to the snippet start. The inserted block is exactly 18 instructions = 40 bytes = **0x28** — matching the verifier error target verbatim. The patcher embeds that absolute 0x28 in the resulting method, instead of resolving it to the original `invoke-static/range` that follows the injection. The `if-eqz` then branches to absolute offset 0x28 of `bg5.a`, which lands mid-instruction in the original prologue → VerifyError.

**Why Hooks 1 + 2 didn't crash with the same shape.** They insert at index 0. Snippet-relative offset *equals* absolute offset in the destination method when the shift is zero, so the bug doesn't surface. Hook 3 has no labels at all.

**Fix.** Switched Hook 4 to `addInstructionsWithLabels` + `ExternalLabel`, capturing the original `invoke-static/range` instruction at `joinIdx` *before* insertion. The patcher resolves the label by Instruction identity and tracks it correctly after insertion shifts the target index down by 18. Trailing `:bh_skip_evshim_preload` line removed from the snippet.

**Imports added.** `ExternalLabel`, `addInstructionsWithLabels` (both from `app.revanced.patcher.extensions`).

**Lesson for future bytecode patches.** When inserting at index > 0 with a forward branch that needs to skip past the inserted block, always use ExternalLabel pointing to the original instruction at the insertion index. Trailing-label-in-snippet is a footgun that only surfaces when insertion shifts > 0.

### Stage 3b — Hook 4 v14-type-mismatch fix (2026-05-12, after pre2)

**Second device crash.** v1.1.0-604-pre2 installed and the VerifyError shape changed:

```
java.lang.VerifyError: Verifier rejected class bg5:
  void bg5.a(eco, java.lang.String, boolean):
  [0x1F8] register v14 has type Reference: java.io.File
  but expected Reference: java.lang.String
```

Crash log at `/data/data/com.termux/files/home/log_2026_05_12_20_07_32_crash.log` (PID 19846, `banner.hub:wine`).

**Root cause.** Hook 4 inserts at `joinIdx` = the `invoke-static/range` of `JOIN_HELPER->I0`. The 5 instructions immediately preceding the invoke are Kotlin's joinToString$default arg setup:

```
const/16 v16, 0x0
const/16 v17, 0x3e
const-string v13, ":"
const/4 v14, 0x0       ← v14 set to ConstZero (null CharSequence)
const/4 v15, 0x0
invoke-static/range {v12..v17}, JOIN_HELPER->I0(...)
```

So inserting AT joinIdx places our File-path code *after* the setup. Our `new-instance v14, Ljava/io/File;` then overwrites v14 with `File`, and the verifier rejects the subsequent invoke with `expected Reference: java.lang.String`.

**Fix.** Move the insertion point 5 instructions earlier, to the start of the setup block (`setupStartIdx = joinIdx - 5`). Now both the fall-through and branch-taken paths from our `if-eqz` flow into the setup, which cleanly re-initializes v13..v17 to the types `invoke-static/range` expects. ExternalLabel target updated to the original `const/16 v16` instruction at `setupStartIdx`. Added a `require()` for the setup-block lookback in case a future R8 reshuffle inlines or reorders the setup.

Insertion ordering matters: when inserting `addInstructionsWithLabels` at an index, our snippet is placed *before* the existing instruction at that index. So `setupStartIdx` (= joinIdx - 5) puts our injection just before the setup; the setup then runs after our injection, before the invoke.

### Stage 3b device test — DOOMBLADE clean launch, no rumble triggered (2026-05-12 ~21:46)

v1.1.0-604-pre3 (`9681b60`) installed as `banner.hub` (Normal variant). User launched **DOOMBLADE** via DirectLaunch (Wine Proton 10 arm64x-2, FEX Game Presets, Turnip v25.0.0 R1). Wine session ran clean 21:46:12 → 21:48:36, no VerifyError, no crash. Stage 3b fix verified at the verifier level.

Initial verdict from logcat: zero `BhVibration` log lines, zero gamepad-source InputDevice events. Hooks didn't fire during this session. Hypotheses recorded at the time: either no real controller was paired, or DOOMBLADE didn't issue rumble during the playthrough (2D metroidvania, rumble fires only on specific hits).

Hook insertion verified correct in the installed APK by apktool-decompiling `/data/app/~~8kz5yy-HOJCA8DhNk4duGQ==/banner.hub-JC7NoskjYKMoBofYk4cZ7g==/base.apk`:
- Hook 1: `smali_classes7/com/winemu/core/gamepad/GamepadServerManager.smali:298` — `invoke-static {p1, p2, p3}, Lcom/xj/winemu/vibration/BhVibrationController;->onRumble(III)Z` ✅
- Hook 2: `smali_classes2/ab8.smali:511` — `dispatchToController(III)Z` ✅
- Hook 3: `smali_classes2/ab8.smali:438` — `onStop(I)V` ✅
- Hook 4: `smali_classes8/bg5.smali:985,993` — `nativeLibraryDir` + `/libevshim.so` injection ✅
- `lib/arm64-v8a/libevshim.so` shipped, 41,384 bytes ✅

### Stage 3b — GTA 5 Enhanced device test: VIBRATION CONFIRMED (2026-05-12)

User retested v1.1.0-604-pre3 (`9681b60`) with **GTA 5 Enhanced** and a real controller. **Rumble works.** Device-confirmed end-to-end:

- `GamepadServerManager.onRumble` → `BhVibrationController.onRumble` invoke path active
- Per-controller dispatch via Hook 2 (`ab8.g(II)V`) firing
- libevshim.so LD_PRELOAD keepalive holding rumble past SDL2's 1s auto-stop
- Stop hook (Hook 3, `ab8.f()V`) releasing cleanly

This unblocks the merge: `feature/vibration` ready to land on `gamehub-604-build`.

### Merged into gamehub-604-build 2026-05-12

Pre-merge commit `7d149f1` (docs: GTA 5 confirmation + stable-release-pipeline spec section) pushed to `origin/feature/vibration`, then:

```
git checkout gamehub-604-build
git merge --no-ff feature/vibration
git push origin gamehub-604-build
```

Merge commit: **`222730a`** (`Merge feature/vibration into gamehub-604-build`). `--no-ff` preserves the 8-commit feature history under the merge commit so the staged Verifier-error debugging trail (Stage 1 → 3b) stays readable in `git log --graph`.

`gamehub-604-build` head 65e6902 → 222730a on origin. `feature/vibration` left at `7d149f1` on origin (not deleted — kept as a reference for the verifier-fix post-mortem).

## 2026-05-13 — Stable release pipeline implemented on feature/stable-release-pipeline

User chose option (a): make `v1.1.0-604` itself the new-cert anchor instead of shipping it on the old ephemeral key first. **One uninstall, ever.**

### Keystore

Generated `keystore/bannerhub.keystore` via:

```bash
keytool -genkeypair -v \
  -keystore keystore/bannerhub.keystore \
  -alias bannerhub \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass bannerhub -keypass bannerhub \
  -dname "CN=BannerHub, OU=ReVanced, O=The412Banner, C=US"
```

Cert fingerprints (LOCKED IN — CI must print this SHA-256 on every release):

- **SHA-256:** `10:89:5A:31:1F:E0:4F:95:F8:2E:4D:A5:C9:A6:C0:41:BA:92:82:BF:21:1F:1B:57:8F:E1:CB:EB:89:4C:E0:BA`
- **SHA-1:** `1F:51:B2:5E:5C:9F:58:08:E0:CF:45:17:4F:CC:B3:8D:67:CA:6D:E5`
- **Serial:** `5ee03b1e340fd1ac`
- **Validity:** 2026-05-13 → 2126-04-19 (100 years)
- **Signature algorithm:** SHA384withRSA
- **Schemes used at sign time:** v1 + v2 + v3 (v4 disabled — no `.idsig` sidecar)

Passwords (`bannerhub`/`bannerhub`) and full security model documented in `keystore/README.md`.

### release.yml changes

- **Hybrid trigger**: kept `push: tags: ["v*", "GameHub-*"]`; replaced the `tag` workflow_dispatch input with a `version` input (e.g. `1.1.0-604-pre1`, strip leading `v`). The workflow derives `version` (and `tag` = `v${version}`) from whichever source fired, in a new build-job step `Derive version` that exposes job-level outputs.
- **Filename**: drop the hardcoded `variant.file:` matrix column; compute filename as `BannerHub-V6-${{ needs.build.outputs.version }}-Patched-${{ matrix.variant.name }}.apk` in both the patch step and the artifact upload path.
- **Labels**: matrix `variant.label:` rewritten to "BannerHub v6 …" — three variants share the bare "BannerHub v6" label (Normal, Normal-GHL, Original); AnTuTu and alt-AnTuTu share "BannerHub v6 AnTuTu"; rest are unique.
- **Patch job checkout**: added `actions/checkout@v5` to the patch job so apksigner can read the keystore from the repo.
- **Re-sign step**: new step right after `Apply patches`. Uses `${ANDROID_HOME}/build-tools/<latest>/apksigner` with `--ks keystore/bannerhub.keystore --ks-pass pass:bannerhub --ks-key-alias bannerhub --key-pass pass:bannerhub --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`. Followed by `apksigner verify --print-certs` so the cert SHA-256 surfaces in CI logs each run.
- **Release job**: now needs both `build` and `patch`; dropped the standalone "Get tag name" step (was reading `inputs.tag`); uses `${{ needs.build.outputs.tag }}` for the release tag and `${{ needs.build.outputs.version }}` for body interpolation; release body rewritten — title is now `BannerHub v6 ${{ version }}`, replaced the 6.0.2→6.0.4 base-bump section with a "Stable signing — in-place updates from this release onward" section, updated variant table with new filenames + labels, updated migration note, file glob changed `GameHub-6.0.4-Patched-*.apk` → `BannerHub-V6-*.apk`.

### README + keystore/README.md

- README banner rewritten from "fresh install required" to "In-place updates — from v1.1.0-604 onward". Variant table updated with new filenames + labels. New `## Signing` section after `## Variants` with cert SHA-256 + SHA-1 fingerprints.
- `keystore/README.md` written: full security model (public test key, anyone can re-sign), keystore fields table, fingerprints, generation command, CI usage, one-time migration note.

### Validated 2026-05-13

- Branch pushed at `67b65ed` (commit `feat(release): stable test-keystore signing + BannerHub-V6 naming`)
- Validation run [`25775495418`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25775495418) — all 9 patch jobs green, release job correctly skipped (stable=false)
- Verified all 9 artifacts:
  - Filename pattern `BannerHub-V6-1.1.0-604-pre1-Patched-{variant}.apk` rendered correctly for every variant (Normal-GHL uses the hyphen form; no parentheses needed)
  - apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` for every variant — matches `keystore/README.md` byte-for-byte
  - apksigner found at `/usr/local/lib/android/sdk/build-tools/37.0.0/apksigner` (auto-discovered via the `ls -d "${ANDROID_HOME}/build-tools"/* | sort -V | tail -1` lookup)
- Artifacts available for 14 days under run 25775495418's artifacts tab

### Merged 2026-05-13

`feature/stable-release-pipeline` (head `7344420`, 2 commits) merged into `gamehub-604-build` at **merge commit `41a2b27`** with `--no-ff` so the feature history is preserved under the merge commit. Pushed to origin.

`gamehub-604-build` head e26529b → 41a2b27. `feature/stable-release-pipeline` left at `7344420` on origin (not deleted — kept as a reference branch).

### Pending

- ☐ Pre2 rebuild on `gamehub-604-build` to verify in-place updates: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604-pre2 -f stable=false`. Expected: same cert SHA-256 `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba`. User will install pre1 (from run 25775495418 artifacts), then install pre2 on top to confirm Android accepts the upgrade with no uninstall.
- ☐ When ready, cut `v1.1.0-604` stable: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604 -f stable=true`. Release notes should call out the one-time uninstall for users on v1.0.0-604 or older.

## 2026-05-13 — feature/app-icon: launcher icon + wine_logo rebrand

New branch `feature/app-icon` off `gamehub-604-build`. Single resource patch (`ChangeAppIconPatch`) that swaps two drawables in the staged APK without touching bytecode.

### Source

`/storage/emulated/0/Download/BannerHub v6_icon.png` — user-provided, 918×903 RGBA with alpha. Centered logo content, transparent surround.

### Generated patch resources

Both checked in to `patches/src/main/resources/bannerhub-icon/`:

| File | Dimensions | Purpose |
| --- | --- | --- |
| `ic_launcher_foreground.png` | 432×432 RGBA | Adaptive-icon foreground at xxxhdpi (108 dp). BannerHub logo content fit to the inner 288×288 safe zone, outer 18 dp margin reserved for launcher masking + parallax. |
| `wine_logo.png` | 240×72 RGBA | Drop-in replacement for the original `drawable-xxhdpi/wine_logo.png`. Square BannerHub icon resized to 72×72 and centered with transparent left/right padding so the 80×24 dp intrinsic measure stays identical and no ImageView layouts regress. |

Generated via ImageMagick:
```
magick "$SRC" -resize "288x288" -gravity center -background transparent -extent "432x432" ic_launcher_foreground.png
magick "$SRC" -resize "72x72" -gravity center -background transparent -extent "240x72" wine_logo.png
```

### Patch source

`patches/src/main/kotlin/app/revanced/patches/gamehub/icon/ChangeAppIconPatch.kt`. Resource patch only (no bytecode, no manifest). Apply block:

1. Stream `bannerhub-icon/ic_launcher_foreground.png` → `res/drawable-xxxhdpi/ic_launcher_foreground.png` (creates the file)
2. **Delete** `res/drawable/ic_launcher_foreground.xml` — the stock GameHub vector. Without this delete, aapt2 keeps both definitions and lower-density devices fall back to the vector (= still GameHub). Deleting forces every density bucket to use the xxxhdpi raster.
3. Stream `bannerhub-icon/wine_logo.png` → `res/drawable-xxhdpi/wine_logo.png` (overwrites stock)

Uses the same sentinel-object classloader pattern as `VibrationLibPatch` (Kotlin's self-referential type-inference snag).

### Background drawable

Intentionally left alone. Adaptive-icon backgrounds are mostly masked away by launcher shapes (circle/squircle/rounded-rect); only a sliver shows at the edge of the foreground. The default GameHub background works fine behind the new foreground content.

### wine_logo usage

R.drawable.wine_logo (resource ID `0x7f080180`, declared in `res/values/public.xml:1273`) is referenced from one place in code: `smali_classes2/ego.smali:1218` via `sget v0, Lyqh;->wine_logo:I` — looks like a Wine-container header/splash logo. Replacing the bitmap content keeps the resource ID stable, so no smali edit is needed.

### Validated 2026-05-13

- Branch + commit: `feature/app-icon` @ `022f10f`, pushed to origin
- Validation run [`25776533760`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25776533760) on `feature/app-icon` with `version=1.1.0-604-pre3 stable=false` — all 9 patch jobs green, release job correctly skipped
- Confirmed per-job:
  - `"Change app icon" succeeded` log line on every variant
  - Output filename `BannerHub-V6-1.1.0-604-pre3-Patched-{variant}.apk` (icon patch did not break the stable-release-pipeline naming)
  - apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` on every variant — byte-for-byte identical to pre1 (run 25775495418) and pre2 (run 25775755966), so an in-place upgrade install of pre3 over pre2 should be accepted by Android without uninstall
- Artifacts live 14 days under run 25776533760

### Pre4 — added 2 Compose Multiplatform auth-screen logos to same patch (2026-05-13)

User asked to additionally rebrand:
- `assets/composeResources/com.xiaoji.egggame.features.auth/drawable/features_auth_ic_logo_landscape.png` (stock 96×96 square — "landscape" refers to auth-screen orientation, not image aspect) — replaced with BannerHub icon scaled to 96×96 with transparent padding
- `assets/composeResources/com.xiaoji.egggame.features.auth/drawable/features_auth_ic_logo_overseas.png` (stock 366×72, 5.08:1 wide) — replaced with user-supplied 2277×448 RGB source `/storage/emulated/0/Download/ADM/features_auth_ic_logo_overseas.png` direct-downscaled (aspect ratio matched exactly, no padding). RGB→RGB transition acceptable since auth screen has opaque background.

Extended `ChangeAppIconPatch` (still ONE patch, one entry in `revanced-cli list-patches`) with two more `copy()` calls. Refactored apply block to factor out the classloader-load + parent-mkdirs + stream-copy pattern into a local helper, eliminating four near-identical blocks.

CN-locale auth logo (`features_auth_ic_logo_cn.png`, 270×72) intentionally left alone — not shown on overseas builds.

Branch head: `718d241`. Validation [run 25777014627](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777014627) all 9 patch jobs green, `"Change app icon" succeeded` on every variant, apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged across pre1 → pre2 → pre3 → pre4 → upgrades between any pair should be in-place).

### Pre5 — added splash_logo to same patch (2026-05-13)

User asked to additionally rebrand `assets/composeResources/com.xiaoji.egggame.features.splash/drawable/splash_logo.png` (stock 996×200, 4.98:1 aspect, RGBA) using the same overseas-banner artwork source. Same 5.08:1 aspect on the source; resolved by resizing to 996×196 to preserve proportions exactly, then `-extent 996x200` to pad 2 px of transparency top + bottom. Output is RGBA so a future splash background change (e.g. dark mode) can bleed through cleanly.

ImageMagick produces RGBA automatically when an RGB input is `-extent`'d with a transparent background — useful pattern.

ChangeAppIconPatch (still ONE patch) now ships **five** drawables in its apply block: launcher foreground (+ vector delete), wine_logo, auth landscape, auth overseas, splash. CN-locale `drawable-zh-rCN/splash_logo.png` left alone — same policy as `features_auth_ic_logo_cn.png` (not displayed on overseas builds).

Branch head: `0d55adf`. Validation [run 25777391685](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777391685) all 9 patch jobs green, `"Change app icon" succeeded` on every variant, cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged pre1 → pre5).

### Merged 2026-05-13

`feature/app-icon` (head `46a1a6e`, 6 commits — 3 feat + 3 docs) merged into `gamehub-604-build` at **merge commit `bf2882e`** with `--no-ff` so the per-pre stages stay readable in `git log --graph`. Pushed to origin. **No CI triggered** by the branch push — `release.yml` fires on tag push or workflow_dispatch only.

`gamehub-604-build` head e3c708a → bf2882e. `feature/app-icon` left at `46a1a6e` on origin as a reference branch.

### Post-merge sanity build 2026-05-13 (pre6 on gamehub-604-build)

User triggered an artifacts-only build of `gamehub-604-build` @ `841a0ba` after the icon-patch merge to verify the merged tree builds clean: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604-pre6 -f stable=false`.

[Run 25777687347](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25777687347) — all 9 patch jobs green, `Create GitHub Release` correctly skipped (stable=false). Per-job:
- `"Change app icon" succeeded` on every variant
- apksigner cert SHA-256 = `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged pre1 → pre6)

Confirms vibration patch + stable-release-pipeline + 5-drawable icon patch all coexist cleanly. Artifacts live 14 days at the run URL.

### Pending

- ☐ User device-tests pre6 (or pre5/pre4) Normal installed on top of any earlier new-cert build:
  1. Android accepts the upgrade with no uninstall
  2. Launcher tile shows BannerHub icon
  3. wine_logo rebrand visible somewhere in-app
  4. Auth-screen logos rebranded on login flow
  5. Splash screen on app launch shows BannerHub banner
- ☐ When user gives the go-ahead, cut `v1.1.0-604` stable: `gh workflow run release.yml --ref gamehub-604-build -f version=1.1.0-604 -f stable=true`. First new-cert release; release notes should call out the one-time uninstall for users on v1.0.0-604 or older.

### Per-game hamburger-menu Vibration Settings option — NOT in this build

User asked whether the per-game hamburger-menu "Vibration Settings" item from BannerHub 3.7.2 stable also ships in the ReVanced build. **No.** The ReVanced patch set only registers `com.xj.winemu.vibration.BhVibrationSettingsActivity` in the manifest with `exported="false"` and no `<intent-filter>` (`VibrationManifestPatch.kt:32-37`). There is no patch under `patches/.../gamehub/` that injects a menu item into the XJ Java/XML UI to launch that activity — that would be a separate bytecode patch (find the per-game menu adapter R8 class, inject a row that fires an explicit Intent to `BhVibrationSettingsActivity --es gameId <gid>`).

Functionally rumble still works without the UI: `BhVibrationController.java:98-99` defaults to `MODE_CONTROLLER` at intensity 100. The settings activity only adjusts per-game mode/intensity overrides.

Follow-up task (after rumble is confirmed working in GTA 5): port the menu-item injection as a new bytecode patch.

## 2026-05-12 — Stable release pipeline spec approved (NOT executed yet)

User-approved spec for the next-but-one release cycle. Execution is **gated on**: (1) GTA 5 + real-controller retest of `feature/vibration` head `9681b60` confirming rumble, (2) merging `feature/vibration` → `gamehub-604-build`, (3) cutting `v1.1.0-604` stable on the **old ephemeral key** (final release before the cert switch). Only after that do we branch `feature/stable-release-pipeline` off the updated `gamehub-604-build` and apply the changes below.

### Goal

Replace revanced-cli's per-run ephemeral keystore with a checked-in test keystore so the signing cert is stable across releases. After a one-time uninstall break for users on the v1.0.0-604 ephemeral-key build, every future stable updates in-place (no uninstall, no `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

### Spec

- **Tag format:** `vX.Y.Z-{branch-base#}` — e.g. `v1.1.0-604`. `604` derived from `patches/.../gamehub/Constants.kt:GAMEHUB_VERSION = "6.0.4"` and the `base-apk-604` GitHub release. Pre-releases: `vX.Y.Z-{branch-base#}-preN`.

- **APK filename:** `BannerHub-V6-{version}-Patched-{variant}.apk` where `version = ${GITHUB_REF_NAME#v}`. Drops the hardcoded `variant.file:` column from the matrix; computed at workflow level.

- **App labels (9-variant table):** three variants share the bare "BannerHub v6" label (Normal, Normal-GHL, Original) and install side-by-side via different package names — same pattern as the two AnTuTu variants which share "BannerHub v6 AnTuTu".

  | variant.name | variant.pkg | variant.label |
  |---|---|---|
  | Normal | banner.hub | BannerHub v6 |
  | Normal-GHL | gamehub.lite | BannerHub v6 |
  | PuBG | com.tencent.ig | BannerHub v6 PuBG |
  | AnTuTu | com.antutu.ABenchMark | BannerHub v6 AnTuTu |
  | alt-AnTuTu | com.antutu.benchmark.full | BannerHub v6 AnTuTu |
  | PuBG-CrossFire | com.tencent.tmgp.cf | BannerHub v6 PuBG CrossFire |
  | Ludashi | com.ludashi.aibench | BannerHub v6 Ludashi |
  | Genshin | com.miHoYo.GenshinImpact | BannerHub v6 Genshin |
  | Original | com.xiaoji.egggame | BannerHub v6 |

- **Test keystore at `keystore/bannerhub.keystore`** (RSA 2048, 100-year validity, alias `bannerhub`, store and key password both `bannerhub`, DN `CN=BannerHub, OU=ReVanced, O=The412Banner, C=US`). Public test key — committed to repo, documented in `keystore/README.md` alongside cert SHA-256.

- **apksigner post-sign step** with `--v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false`, runs after revanced-cli `--out`. Followed by `apksigner verify --print-certs` so CI logs surface the cert SHA-256 every run for eyeball verification.

- **`build_pull_request.yml`** untouched — PR test artifacts continue with revanced-cli's ephemeral keystore.

### Sequencing checklist

1. ☐ GTA 5 + real controller retest of `feature/vibration` head `9681b60`
2. ☐ Merge `feature/vibration` → `gamehub-604-build` if green
3. ☐ Cut `v1.1.0-604` stable on the **old ephemeral key** (final release before cert switch)
4. ☐ Branch `feature/stable-release-pipeline` off updated `gamehub-604-build`
5. ☐ Generate + commit keystore at `keystore/bannerhub.keystore`, write `keystore/README.md`
6. ☐ Update `release.yml` (matrix, filename template, label table, apksigner step)
7. ☐ Update README "Signing" section + release-notes copy
8. ☐ Validate via non-stable `workflow_dispatch`; inspect filenames + cert SHA-256 in logs
9. ☐ Merge `feature/stable-release-pipeline` → `gamehub-604-build`
10. ☐ Tag `v1.1.1-604` (or `v1.2.0-604`) — **new-cert anchor**; release notes call out the one-time uninstall
11. ☐ Lock cert SHA-256 into memory + README for permanent verification reference

Full spec in `[[project_bannerhub_revanced_stable_release_pipeline]]` memory file.

## 2026-05-13 — Per-game menu integration recon

User wants a 5th menu row "PC Vibration Settings" in the per-game library popup (PC Game Settings / Add to Desktop / Remove from Library / Edit Cover). Pure Compose Multiplatform, heavy R8 obfuscation. Full architecture mapped this session before the patch implementation begins.

### Menu Composable host

**`smali_classes4/x57.smali`** (18,783 lines), method `a(Lf37;Lpo7;Lv83;I)V` (line 214 → ~7807). Per-game menu rows built lines ~3120-3300.

### Row data class

`Liae;` (file `smali/iae.smali`):
| Field | Type | Meaning |
|---|---|---|
| `a` | `Lo05;` | Icon (Painter / vector) |
| `b` | `Ljava/lang/String;` | Resolved label string |
| `c` | `Lpw6;` | onClick (Function1<Object, Object>) |
| `d` | `Z` | Enabled boolean |

Constructor: `<init>(Lo05;Ljava/lang/String;Lpw6;)V` (3-arg overload defaults d=true).

### Click handler interface

`Lpw6;` = Compose's `Function1` — single abstract method `invoke(Ljava/lang/Object;)Ljava/lang/Object;`.

### Label resolver lookup

`Lwhl;` is the only ComposableSingletons holder containing all four menu labels:
- `Lwhl;->S:Lxrl;` — `Lwgl(23)` = `common_game_remove_from_library` ✓ verified
- `Lwhl;->e0:Lxrl;` — `Ldhl(13)` = `game_cover_edit_title` ✓ verified
- Two more (PC Game Settings + Add to Desktop) in the same singleton

### Compose label int values (verified by walking pswitch tables)

- `Lghl(25)` → `features_winemu_entrance_setting` ("PC Game Settings")
- `Ltfl(20)` → `features_game_add_to_desktop` ("Add to Desktop")
- `Lwgl(23)` → `common_game_remove_from_library` ("Remove from Library")
- `Ldhl(13)` → `game_cover_edit_title` ("Edit Cover")

### Canonical row construction pattern (x57 lines ~3130-3210)

```smali
:goto_30
if-eqz v36, :cond_66                                 ; row-visible state guard
const v2, -0x3f27e2da                                ; Compose state-group key
invoke-virtual {v7, v2}, Ln49;->g0(I)V               ; Composer.startReplaceableGroup
sget-object v2, Lzz4;->m:Lxrl;                       ; ICON ref
invoke-virtual {v2}, Lxrl;->getValue()Ljava/lang/Object;
move-result-object v2
check-cast v2, Lo05;                                 ; v2 = Lo05 icon
sget-object v3, Lwhl;->S:Lxrl;                       ; LABEL ref
invoke-virtual {v3}, Lxrl;->getValue()Ljava/lang/Object;
move-result-object v3
check-cast v3, Lell;
const/4 v9, 0x0
invoke-static {v3, v7, v9}, Lxd3;->l1(Lell;Lv83;I)Ljava/lang/String;
move-result-object v3                                ; v3 = resolved string
invoke-virtual {v7, v10}, Ln49;->i(Ljava/lang/Object;)Z   ; Composer.changed
move-result v13
invoke-virtual {v7}, Ln49;->S()Ljava/lang/Object;    ; Composer.rememberedValue
move-result-object v9
if-nez v13, :cond_64
if-ne v9, v15, :cond_65                              ; reuse remembered if not Empty
:cond_64
new-instance v9, Lb47;
const/4 v13, 0x0
invoke-direct {v9, v10, v0, v6, v13}, Lb47;-><init>(Lpo7;Lcge;Lcge;I)V
invoke-virtual {v7, v9}, Ln49;->p0(Ljava/lang/Object;)V   ; Composer.updateRememberedValue
:cond_65
check-cast v9, Lpw6;                                 ; v9 = onClick Function1
new-instance v13, Liae;
invoke-direct {v13, v2, v3, v9}, Liae;-><init>(Lo05;Ljava/lang/String;Lpw6;)V
invoke-virtual {v4, v13}, Lx9d;->add(Ljava/lang/Object;)Z   ; list builder v4 ← row
invoke-virtual {v7}, Ln49;->u()V                     ; Composer.endReplaceableGroup
```

### Implementation plan

Two artifacts:

1. **Java helper** `extensions/gamehub/src/main/java/com/xj/winemu/vibration/BhMenuRowClick.java` implementing `kotlin.jvm.functions.Function1<Object, Object>`:
   - Walks `ActivityThread` (reflection — same pattern `BhVibrationController.maybeResolveContainerFromActivityStack` already uses) to find current top Activity
   - Reads gameId Intent extra from the active WineActivity if present
   - Fires `currentActivity.startActivity(Intent(currentActivity, BhVibrationSettingsActivity.class).putExtra("gameId", gameId))`

2. **Bytecode patch** `patches/.../gamehub/vibration/VibrationMenuRowPatch.kt`:
   - Structural anchor: find a method with `sget-object .*Lwhl;->S:Lxrl;` AND `Lx9d;->add(Object)Z` AND `Liae;-><init>(Lo05;Ljava/lang/String;Lpw6;)V`
   - Just before the method's final return (or at the end of the row-construction block), inject the smali to construct a new `Liae("PC Vibration Settings", icon, BhMenuRowClick(), true)` and append to `v4` via `Lx9d;->add`
   - Use ExternalLabel pattern via `addInstructionsWithLabels`; reserve fresh free registers; preserve Composer state-group balance with paired `Ln49.g0` / `Ln49.u`

### Risk factors / iteration expectations

- Compose `Composer.startReplaceableGroup` / `endReplaceableGroup` pairs must match — wrong boundary = ART verifier crash on app start
- Compose's `Ln49.i` / `Ln49.S` / `Ln49.p0` remember-state slot tracking has strict invariants
- Register reuse: `v7` (Composer), `v4` (list builder), `v10` / `v15` (constants) must not clobber
- Expect 2-4 CI cycles to pass verifier + 1-2 device-test cycles to confirm row renders cleanly without crashing the popup

### Recon files referenced

| File | Role |
|---|---|
| `x57.smali` | Menu Composable host (18,783 lines, method `a()` builds rows) |
| `iae.smali` | Row data class `Liae(icon, label, onClick, enabled)` |
| `b47.smali` | Compose-emitted onClick closure (`Lpw6` impl, 4-field captured state) |
| `cge.smali` | State delegate interface (NOT click handler — `MutableState`-like) |
| `pw6.smali` | Function1 interface — actual click-handler type |
| `whl.smali` | ComposableSingletons holder for menu labels |
| `ghl.smali` / `tfl.smali` / `wgl.smali` / `dhl.smali` | Label string resolvers (packed-switch on int → string key) |
| `vhl.smali` / `shl.smali` / `zhl.smali` | Other singletons (NOT the per-game menu) |
| `jfd.smali` | ViewModel (game detail), 16-way Lhed sealed dispatch |
| `ddd.smali` | FlowCollector handling 13 sealed event types |
| `ycd.smali` | Edit Cover confirm closure |
| `j47.smali` | Composable lambda factory (5 different ctors, 14-callback variant) |
| `igg.smali:21668` | Builds j47 with `Ljava/util/List;` of menu rows |

### AppNavKey concrete names found

| AppNavKey class | Obfuscated |
|---|---|
| `AppNavKey$PcGameSettingEntrance` | `Lff0;` |
| `AppNavKey$GamepadVibrationSetting` | `Ltd0;` (built-in 6.0.4 — different from our BhVibrationSettingsActivity) |

## 2026-05-13 — Patch iterations (pre7 → pre11)

Branch `feature/menu-vibration-row`. Iteration trail captured in memory file `project_bannerhub_revanced_vibration.md`:

| Pre | Commit | Outcome |
|---|---|---|
| pre7 | `2d0c85c` | Kotlin compile fail (`Pair<...>.addInstructions` doesn't exist — use `firstMethod`) |
| pre8 | `8b1cb3f` | ART verifier reject — in-line smali clobbered v9 with `BhMenuRowClick` type, breaking downstream type-flow merge at `:goto_35` |
| pre9 | `28c5bd3` | Helper called repeatedly but failed `!pw6Cls.isInstance(click)` — R8 renamed `kotlin.jvm.functions.Function1` → `Lpw6;` so our extension's Function1 impl is a different JVM class |
| pre10 | `7eb024e` | ✅ Row appears in game-details More Menu — `java.lang.reflect.Proxy` implementing Lpw6 satisfies the Iae ctor type check |
| pre11 | `19080af` | Added 2nd injection in `ted.smali f()` for library tile popup — but logcat confirms helper never called; ted.f() is NOT the library tile popup |

### Confirmed working surface

`x57.smali` method `a(Lf37;Lpo7;Lv83;I)V` injection at lastAddIdx+1:
```
invoke-static {v4}, Lcom/xj/winemu/vibration/BhMenuRowClick;->appendVibrationRowTo(Ljava/lang/Object;)V
```
Helper reflectively constructs `Liae(icon, "PC Vibration Settings", Proxy<Lpw6>)` and appends to list builder `Lx9d` at `v4`. Renders as 5th item in the game-details screen "More Menu" popup. Device-confirmed via screenshot 2026-05-13 08:04.

## 2026-05-13 — BOTH menus working at pre17 🎉

After 10 iterations (pre7 → pre17), the library tile popup row now ALSO works.

### What got it across the line

Pre15-16 silently had a `PatchException: classDef is null` in the resolver short-circuit's `addInstructionsWithLabels`-via-`ExternalLabel` path. revanced-cli reported the OVERALL job as success because the per-patch SEVERE error doesn't fail the CI run. The 3 other injections landed but the resolver patch silently no-op'd, leaving rows in pzc.j0's output pointing at unresolvable Lell keys that crashed at render time.

Pre17 switched to plain `addInstructions` (no labels) at index 0 — works because the trailing-label-in-snippet footgun only applies mid-method. Index 0 lets the snippet-relative offset equal the absolute offset.

### Final injection pattern (3 sites + 1 resolver patch)

1. **Game-details "More Menu"** (`Lx57.a()`): `addInstructions(lastAddIdx+1, "invoke-static {v4}, ...->appendVibrationRowTo(Object)V")` → Java helper builds `Liae(icon, label-as-String, Proxy<Lpw6>)` and `list.add()`s.
2. **Library tile popup** (`Lpzc.j0()`): hook the return — `addInstructions(returnIdx, "invoke-static {vN}, ...->appendLibraryPopupRow(Object)List; \n move-result-object vN")` → Java helper builds `Lz4e(Lell-via-Unsafe, Proxy<Lnw6>, 0)` and returns augmented ArrayList.
3. **Resolver short-circuit** (`Lxd3.l1()`): `addInstructions(0, "invoke-static {p0}, ...->maybeResolveCustomLabel(Object)String; \n move-result-object v0 \n if-eqz v0, :tail \n return-object v0 \n :tail")` → Java helper returns "PC Vibration Settings" for our sentinel key, null for everything else.
4. **Compose resource entry** (`VibrationMenuLabelPatch`): appends to `assets/composeResources/com.xiaoji.egggame.features.home/values*/strings.commonMain.cvr` for documentation/future-use (actual mechanism is #3 since Compose Multiplatform's runtime needs a manifest registration the bare .cvr doesn't provide).

### Three architectural challenges solved

- **R8 renamed kotlin.jvm.functions.Function0/1** to `Lnw6;`/`Lpw6;`. Extension's `implements Function1` doesn't satisfy `pw6Cls.isInstance()`. Fix: `java.lang.reflect.Proxy.newProxyInstance` implementing the renamed interface.
- **Lell is an empty Kotlin subclass** of abstract `Ltdi(String, Set)`. Lell.smali has NO `.method`, NO `.field`. JVM-level the host does `new-instance + invoke-direct Ltdi.<init>`. `Lell.class.getDeclaredConstructor` returns nothing. Fix: `sun.misc.Unsafe.allocateInstance` + reflect-set the inherited fields `Ltdi.a` (key) and `Ltdi.b` (Set).
- **Compose resource keys** for library popup labels go through `Lxd3.l1` resolver which throws on unknown keys. Just appending to the .cvr isn't enough — runtime needs a manifest. Fix: bytecode short-circuit the resolver at its head.

### Full engineering reference

The reusable playbook (with smali patterns, register conventions, common pitfalls, full code snippets) lives in `project_bannerhub_revanced_menu_injection_playbook.md`. Future menu-row additions should start there.

### Critical CI anti-pattern caught

`revanced-cli` reports per-patch failures as `SEVERE:` log lines but the OVERALL CI job exits 0. Always grep CI logs for SEVERE after every iteration — wasted pre15 and pre16 by assuming "all 9 variants green" meant all patches landed.

```bash
gh run view --log --job <id> | grep -E "SEVERE|INFO.*<patch name>"
```

### Device confirmation 2026-05-13 12:51

Library tile 3-dot popup screenshot shows 5 rows in vertical text-only list:
- PC Game Settings
- Add to Desktop
- Remove from Library
- Edit Cover
- **PC Vibration Settings** ← OUR row

Game-details "More Menu" still has its own PC Vibration row from pre10. Both surfaces working independently.

### Pending

- ☐ Tap-test "PC Vibration Settings" in the library popup → confirm it opens BhVibrationSettingsActivity dialog (haven't device-tested the click yet, just the row rendering)
- ☐ When ready, cut `v1.1.0-604` stable — first release with full menu integration

### Merged 2026-05-13

`feature/menu-vibration-row` (14 commits, head `f472868`) merged into `gamehub-604-build` at **merge commit `91947fe`** with `--no-ff` so the pre7→pre17 iteration trail is preserved under the merge commit. Pushed to origin. `gamehub-604-build` head `4d609f0` → `91947fe`.

Will trigger an artifacts-only build to verify the merged tree builds clean.

### 2026-05-13 — README "What's new" trim

Per user: "under the what's new section of the read me only show the latest release." Removed the five historical "What's new" sections from `README.md` (v1.0.0-604 historical, v1.0.0-602, v1.0.1-601 hotfix, v1.0.0-601, v1.0.1-600) — collectively ~45 lines. Left a one-line pointer note directing readers to the per-release pages for past notes, then kept the existing `---` separator and `## What this is` header in place. Top-of-README "What's new" now shows only `v1.1.0-604`.

### 2026-05-13 — README "Known limitations" section removed

Per user: "remove the known limitatiin section." Dropped the `## ⚠ Known limitations — please read` block from `README.md`. Both bullets in that section were already strikethroughs of fixed issues (standard Steam client launches + missing cover-art-on-import), both fixed server-side in the BannerHub Worker and retroactively applied to existing patched builds. README now flows `## What this is` → `## Source` directly.

### 2026-05-13 — README "Patches applied" wrapped in collapsible `<details>`

Per user: "make the patches applied collapsed with a button/link to expand." Kept the `## Patches applied` header (so the `#patches-applied` anchor referenced from the top-of-README nav still resolves) and wrapped the entire body (intro paragraph through "Disabled-by-default options") in a `<details><summary><strong>📦 Click to expand the full patch list (17 patches + disabled-by-default options)</strong></summary>` block. Closing `</details>` sits immediately before `## Build it yourself` to keep section boundaries clean. Matches the same collapsible pattern used in `release.yml`'s release-notes template.

### 2026-05-13 — README Discord badge + AI Disclaimer added

Per user: "add the discord server badge and ai disclaimer at the top also please" + verbatim disclaimer text. Two README changes:

1. **Discord shield badge** — centered `<p>` with a Shields.io for-the-badge style discord badge (`https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?logo=discord&logoColor=white&style=for-the-badge`) linking to `discord.gg/n8S4G2WZQ4` (the The412Banner community invite, per `feedback_discord_link_new_repos.md`). Placed between the subtitle paragraph and the existing in-page nav bar.
2. **AI Disclaimer section** — new `## AI Disclaimer` H2 inserted right after the in-place-updates callout and before `## What's new in v1.1.0-604`. Two paragraphs verbatim from the user, with the model name bolded and `logcat` set as inline code. Also added an `· AI disclaimer` entry to the in-page nav bar so readers can jump straight to it from the top.

### 2026-05-13 — Branch 1 MERGED to `gamehub-604-build` (Plans 8a + 8b)

`feature/strip-privacy-permissions-ota` (head `7302aae`) merged into `gamehub-604-build` at merge commit `6817568` (`--no-ff` so the patch-add history stays under the merge). Post-merge sanity build queued as run 25830638192.

Status after this merge: Plans 4 + 5 + 8a + 8b all live on `gamehub-604-build`. Remaining: Plan 8c (heartbeat strip, recon done — Branch 2 next), Plans 1+7 (analytics-event redirect via Worker), Plan 9 (PRIVACY.md). Plans 2 + 3 + 6 deliberately skipped. Privacy series inventory: `project_bannerhub_revanced_privacy_hardening.md`.

### 2026-05-13 — feature/strip-privacy-permissions-ota — Branch 1 of Plan 8 ports

User: "begin" — kicking off Branch 1 (Plan 8a + 8b together) after the Plan 6 N/A finding and Plan 8 inventory.

**Patches written (2 files on `feature/strip-privacy-permissions-ota`):**

1. **`misc/analytics/StripAdIdPermissionsPatch.kt`** — `resourcePatch`. Removes the three `<uses-permission>` declarations for `com.google.android.gms.permission.AD_ID`, `android.permission.ACCESS_ADSERVICES_ATTRIBUTION`, `android.permission.ACCESS_ADSERVICES_AD_ID` from the manifest root. Collect-then-remove pattern (avoid live-NodeList iteration issues). Idempotent. Strengthens Plan 4 — that one disables collection via `<meta-data>` kill-switches but the declared permissions still flag privacy scanners.

2. **`misc/ota/DisableOtaUpdatesPatch.kt`** — `bytecodePatch` + private `otaCleanupResourcePatch` dependency.
   - **Bytecode layer**: anchors structurally on any method containing a `const-string` whose value starts with `https://www.xiaoji.com/firmware/update`. In 6.0.4 this resolves to `smali_classes4/ki4.smali` method `ki4.d(String, String, I, ci3)Object` at instruction `const-string v2, "https://www.xiaoji.com/firmware/update/x1"`. After the const-string load, inserts `const-string v$urlReg, "http://127.0.0.1"` so the URL register holds the loopback before downstream HTTP code reads it. No control-flow changes, no try/catch label disruption.
   - **Resource layer (`otaCleanupResourcePatch`)**: strips `libJieLiUsbOta.so` and `libjl_ota_auth.so` (1 arch dir each in 6.0.4) — JieLi gamepad-firmware native libs that are dead weight on a phone install.
   - **5.3.5 → 6.0.4 delta**: the original 5.3.5 patch's URL anchor used a trailing slash (`...update/x1/`); 6.0.4 dropped it. Port uses prefix-match on `...firmware/update` to survive both shapes plus any future minor adjustments. Also dropped the `dependsOn(creditsPatch)` from the 5.3.5 source — the `creditsPatch` is one of the 34 missing patches we didn't carry forward.

**Expected behaviour:**

- No user-visible UI change.
- `adb logcat` should not show the OTA URL being contacted on cold launch (or the launch should show a connection-refused on 127.0.0.1).
- Manifest dump (`aapt dump permissions`) should report zero ad-ID permission declarations.

**Risk:** Low. Both patches are anchored on string contents (manifest attribute values + smali const-string), neither touches R8-mangled class letters. Resource patches don't have ART verifier complications.

**Verification chain (per `project_bannerhub_revanced_privacy_hardening.md`):** CI green → grep SEVERE = 0 → "succeeded" line count = 9 → artifact-grep on `apk-Normal` (manifest has 0 ad-ID permissions, smali has the loopback URL override at the right index, lib/ has no JieLi sos) → user device test.

### 2026-05-13 — Plan 6 N/A + Plan 8 inventory complete (3 portable findings)

**Plan 6 (Bugly) is not applicable to 6.0.4.** Recon against `gamehub_604_decompile/`: no `com/tencent/bugly/` smali tree, no Bugly manifest entries, no `initCrashReport` call sites. XiaoJi appears to rely entirely on Firebase Crashlytics for crash reporting (already neutralized by the existing `DisableCrashlyticsPatch`). My earlier "Bugly is likely bundled" was a guess from the typical "Chinese app bundles Bugly" assumption — recon refuted it. Plan 6 is now marked N/A in the privacy-hardening memory file.

**Plan 8 inventory complete.** Diff of `origin/playday-build` (5.3.5, 51 patches) vs `origin/gamehub-604-build` (6.0.4, 31 patches) showed **34 patches not carried forward**. Walked through the 5 most privacy-flavored candidates and verified each against the 6.0.4 decompile:

| 5.3.5 patch | 6.0.4 verdict |
|---|---|
| `DisableAnalyticsPatch` (native-lib stripping for Umeng/Alibaba crash/Alibaba phone-auth + Ad-ID perm strip) | ❌ Native libs `libumeng-spy.so` / `libucrash*.so` / `libumonitor.so` / `libalicomphonenumberauthsdk_core.so` all gone in 6.0.4 (XiaoJi swapped analytics backends in KMP rewrite). ✅ But the 3 `<uses-permission>` declarations for `AD_ID` / `ACCESS_ADSERVICES_ATTRIBUTION` / `ACCESS_ADSERVICES_AD_ID` are STILL declared in 6.0.4 manifest — strip subset is **directly portable** and strengthens Plan 4. |
| `DisableHeartbeatPatch` (returns `WineGameUsageTracker.start/update/end Heartbeat` early) | ⚠️ `WineGameUsageTracker` class gone, BUT the heartbeat code was split into 4-5 obfuscated single-purpose classes in 6.0.4: `smali_classes4/feo.smali` carries `"heartbeat/game/start"`, `heo.smali` carries `"heartbeat/game/update"`, `aeo.smali` carries `"heartbeat/game/end"`, `se7.smali` carries `"heartbeat/game/getUserPlayTimeList"`, plus `smali_classes5/b30.smali` (probable cloud-game variant). **Heartbeat-string anchor still resolves** — can rewrite the patch with structural body-contains-`heartbeat/game/*`-string anchor. **High-value privacy port** (kills periodic per-game telemetry beacons during gameplay). |
| `DisablePushPatch` (strips JPush — `cn.jpush.*`) | ❌ JPush not bundled in 6.0.4 (XiaoJi switched from JPush to Mob Push in the 5.x → 6.x rewrite). Already killed by Plan 5. Naturally obsolete. |
| `DisableCloudTimerPatch` (cloud-gaming timer check skip) | ⚠️ Not privacy-relevant — out of scope. |
| `DisableOtaUpdatesPatch` (replaces OTA URL register with `http://127.0.0.1`) | ✅ URL `https://www.xiaoji.com/firmware/update/x1` still present at `smali_classes4/ki4.smali:6451` inside `ki4.d(String, String, I, ci3)Ljava/lang/Object;` (suspending fn). **Direct port viable** with one caveat: 5.3.5 anchor used trailing slash (`...update/x1/`), 6.0.4 string omits it — port needs `firstMethod("https://www.xiaoji.com/firmware/update/x1")` with no trailing slash. Also surfaced gamepad-firmware OTA path (`smali_classes4/ej3.smali` / `GamepadOtaIntent`) for optional separate neutralization. |

**Net result: 3 portable findings** (Ad-ID strip, OTA URL kill, heartbeat strip), grouped into 2 branches:

- **Branch 1 — `feature/strip-privacy-permissions-ota`** (8a + 8b together) — both fast direct ports, ~60 min total.
  - 8a `StripAdIdPermissionsPatch.kt` (resourcePatch) — removes 3 ad-ID `<uses-permission>` declarations.
  - 8b `DisableOtaUpdatesPatch.kt` (bytecodePatch) — port of 5.3.5 patch with prefix-without-slash fix.
- **Branch 2 — `feature/disable-heartbeat`** (8c) — recon de-risked it from 1-3h to ~60-90 min since all 5 target classes are mapped. Port pending.

After both: Plans 1+7 (analytics-event redirect through Worker) → Plan 9 (`PRIVACY.md`).

Plans 2 + 3 still skipped (low value vs. effort).

Detailed plan inventory, methodology, and the full 34-patch missing list (including non-privacy QoL patches for future workstreams) lives in auto-memory at `project_bannerhub_revanced_privacy_hardening.md`.

### 2026-05-13 — Plan 5 MERGED to `gamehub-604-build`

`feature/disable-mob-push` (head `503204a`) merged into `gamehub-604-build` at merge commit `282c9ea` (`--no-ff` so the pre1 → pre2 anchor-fix history is preserved under the merge). Post-merge sanity build queued as run 25825313855.

Status after this merge: Plans 4 + 5 of the privacy hardening series are both live on `gamehub-604-build`. Remaining: Plans 1+7 (analytics-event redirect), Plan 6 (Bugly), Plan 8 (5.3.5 inventory), Plan 9 (PRIVACY.md). Plans 2 + 3 deliberately skipped (low value vs effort). Cross-cutting inventory + decisions log: auto-memory `project_bannerhub_revanced_privacy_hardening.md`.

### 2026-05-13 — Plan 4 artifact-grep verification (post-merge sanity)

Downloaded `apk-Normal` artifact from CI run 25821952000 (the original `feature/disable-firebase-analytics` artifact build), decoded with apktool, grepped `AndroidManifest.xml`. All three injected `<meta-data>` entries confirmed present under `<application>`:

- `firebase_analytics_collection_deactivated = "true"`
- `google_analytics_adid_collection_enabled = "false"`
- `google_analytics_ssaid_collection_enabled = "false"`

Plan 4 verification chain now complete: source → CI green → device-verified by user → merge sanity build green (run 25822790159) → artifact manifest-grep confirmed patch landed.

### 2026-05-13 — Plan 5 pre1 → pre2 fix (BaseAndroidApp anchor)

`feature/disable-mob-push` pre1 (CI run 25823321334) reported overall CI green but had **0/9 variants actually apply the patch** — all 9 SEVERE-failed silently with `app.revanced.patcher.patch.PatchException: Could not find instruction index`. The CI summary read green because revanced-cli's per-patch SEVERE failures don't propagate to the overall job exit code (same anti-pattern as the menu-injection playbook). **Lesson: always grep CI logs for `SEVERE` even when conclusion is success.**

Root cause: Mob init calls in `BaseAndroidApp.smali` live in the helper method `a()V` (called from `onCreate`), not in `onCreate` itself. The pre1 anchor used `name == "onCreate"` which matched the empty 5-line delegating `onCreate` at line 350 of the smali. `indexOfFirstInstructionOrThrow` then found no `submitPolicyGrantResult` invoke in that method and bailed the entire patch.

Fix in `503204a`: switched the BaseAndroidApp anchor to the same structural body-contains-invoke pattern already used for the nt5 hook. Method name (`a`/`b`/etc.) becomes irrelevant; survives R8 reshuffles on minor bumps.

Pre2 build (CI run 25824684180) verification:

- ✅ 0 SEVERE lines across all 9 variant jobs
- ✅ `"Disable Mob Push tracking" succeeded` on 9/9 variants
- ✅ Manifest in apk-Normal: all 12 `com.mob.*` / `cn.fly.*` components carry `android:enabled="false"` (MobProvider, FlyProvider, MobIDActivity, MobIDService, FlyIDActivity, FlyIDService, MobPushJobService, MobPushActivity, MobLReceiver, NotifyActionReceiver, FCMFirebaseInstanceIdService, FCMFireMessagingReceiver)
- ✅ `BaseAndroidApp.smali` has **0** residual `Lcom/mob/` references (both `submitPolicyGrantResult` and `addPushReceiverInMain` removed)
- ✅ `nt5.smali` has 0 `submitPolicyGrantResult` references; the 4 intentionally-preserved downstream calls (`setClickNotificationToLaunchMainActivity`, `getRegistrationId`, `restartPush` ×2) are still present, ready to no-op against the dormant SDK
- ⚠ 2 `submitPolicyGrantResult` invokes remain inside `com/mob/MobSDK.smali` itself — these are Mob's own internal recursive calls (MobSDK methods calling each other). They're dead code since nothing external can reach MobSDK anymore. Correct behaviour, not a leak.

### 2026-05-13 — feature/disable-mob-push — Plan 5 of the privacy hardening list

Plan 4 (`feature/disable-firebase-analytics`) device-confirmed and merged to `gamehub-604-build` at merge commit `178c5ec` (--no-ff). Post-merge sanity build queued as run 25822790159.

**Plan 5 recon (gamehub_604_decompile/):**

- Mob SDK bundled at `smali_classes3/com/mob/` — full surface: core, pushsdk, plugins (fcm/honor/huawei/meizu/oppo/vivo/xiaomi), commons, tools, mgs. Plus `cn.fly.commons` (Mob's analytics submodule, same vendor).
- XiaoJi-side init call sites found:
  - `smali/com/xiaoji/egggame/BaseAndroidApp.smali` line 29 — `Lcom/mob/MobSDK;->submitPolicyGrantResult(Z)V` (consent gate, `v2=true`)
  - `smali/com/xiaoji/egggame/BaseAndroidApp.smali` line 247 — `Lcom/mob/pushsdk/MobPush;->addPushReceiverInMain(Context, MobPushReceiver)V`
  - `smali_classes4/nt5.smali` method `N(Landroid/content/Context;)V` line 3352 — second `submitPolicyGrantResult` call followed by 4 downstream Mob calls (`setClickNotificationToLaunchMainActivity`, `getRegistrationId`, two `restartPush` inside a `:try_start_0 .. .catchall :catchall_0` wrapper)
- Manifest auto-init surface: `<provider android:name="com.mob.MobProvider">` is the critical one — ContentProviders bootstrap before `Application.onCreate`, so bytecode-only neutralization is insufficient. Manifest layer is required.

**Patch:** `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableMobPushPatch.kt`. Single user-facing patch ("Disable Mob Push tracking") with two layers:

- **Layer B — `disableMobPushManifestPatch` (private `resourcePatch`)**: scans `<application>` for `<provider>/<service>/<receiver>/<activity>` whose `android:name` starts with `com.mob.` or `cn.fly.` and sets `android:enabled="false"`. Removes Mob/cn.fly `<meta-data>` outright (no enabled attribute supported).
- **Layer A — `disableMobPushPatch` (`bytecodePatch`, depends on the manifest patch)**: removes the 3 init invocations in reverse-index order, verifier-safe because all three are void-returning singles with no `move-result`. `BaseAndroidApp.onCreate` is anchored by stable class name. The nt5 helper is anchored **structurally** (single-arg `Context` parameter, void return, contains a `submitPolicyGrantResult` invoke, NOT `BaseAndroidApp`) so the patch survives R8 reshuffles on future minor bumps.
- Downstream calls in `nt5.N` (`setClickNotificationToLaunchMainActivity`, `getRegistrationId`, `restartPush` x2) intentionally left in place — without the policy grant the SDK stays dormant and these calls either no-op or throw the kind of NPE the existing `:try_start_0/.catchall` already eats. Surgically removing them mid-method would break the try-catch label structure for no functional gain.

**Expected behavior:** Mob Push delivery dies (no inbound notifications from XiaoJi). MobID device-ID collection dies. Mob's `cn.fly` analytics dies. FCM (used by Mob as a delivery layer) is also disabled at the `FCMFirebaseInstanceIdService` registration — pure Firebase FCM is untouched if anything else uses it, but XiaoJi doesn't appear to. No user-facing UI change.

**Verification plan:** post-patch device test should show `adb logcat | grep -iE 'mob|pushsdk'` empty on cold launch, and `tcpdump` should show zero egress to Mob endpoints.

### 2026-05-13 — feature/disable-firebase-analytics — Plan 4 of the privacy hardening list

User asked for the privacy hardening plan; Plan 4 (Disable Firebase Analytics manifest kill-switch) selected as first action because it's the highest ROI per hour.

**Recon (against `/data/data/com.termux/files/home/gamehub_604_decompile/`):**

- `com/google/firebase/analytics`, `crashlytics`, `messaging`, `installations`, `sessions`, `auth`, `datatransport` smali trees all present.
- ⚠ **`com/google/firebase/remoteconfig` present** — but `grep -rE 'Lcom/google/firebase/remoteconfig/' --include='*.smali'` against everything *outside* the Firebase SDK tree returns **0 hits**. Remote Config is a transitive dependency that XiaoJi's own code never invokes — safe to ship the strong `firebase_analytics_collection_deactivated=true` flag without breaking anything.
- Firebase In-App Messaging is **not bundled** (no `inappmessaging` smali path).
- FCM is bundled but used by **Mob Push** as a delivery layer (`com.mob.pushsdk.plugins.fcm.FCMFirebaseInstanceIdService`) — Analytics-deactivation flag does not affect FCM behavior. Mob Push neutralization is a separate Plan-5 work item.

**Patch:** `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableFirebaseAnalyticsPatch.kt`. Resource patch (manifest-only, no bytecode), modeled on `VibrationManifestPatch` for the DOM-edit pattern. Adds three `<meta-data>` entries to `<application>`:

1. `firebase_analytics_collection_deactivated = true` — Firebase's strongest kill switch (stops SDK init entirely, not just data emission).
2. `google_analytics_adid_collection_enabled = false` — kills Google Ads ID (gAID) collection.
3. `google_analytics_ssaid_collection_enabled = false` — kills SSAID (Android ID) collection.

Each is guarded by a duplicate-check so the patch is idempotent across re-runs and won't collide with any upstream-declared key. ReVanced auto-discovers the new `@Suppress("unused") val disableFirebaseAnalyticsPatch = ...` via Kotlin reflection at patcher-build time — no patch-registry edit required.

**Expected behavior:** zero user-facing change. Background: no events sent to `app-measurement.com`; XiaoJi's Firebase Analytics dashboard loses all patched-APK telemetry. All gameplay, library, components, Wine, Steam launches, controller input, framegen, vibration, etc. are unaffected (none touch Analytics).

**Reversibility:** delete the patch file, rebuild. No bytecode, no state to migrate.

### 2026-05-13 — README top-of-page "separate projects + use at own risk" disclaimer

Per user: warning text covering "Does not replace current Bannerhub 3.7.x (built from Gamehub revanced 5.3.5 by PlayDay) or Bannerhub Lite (built from Gamehub Lite 5.1.4 by Producdevity); Bannerhub, Bannerhub Lite and Bannerhub v6 are SEPARATE projects! NOT to be updated over by any of the other projects! Keep in mind Bannerhub v6 is still a work in progress and will frequently re-release as new base Gamehub versions come out from the original developers. Compatibility is different, so don't expect all games that work on one to work on v6, it uses a new component system and steam clients, thus far, barely tested in general! USE AT YOUR OWN RISK!"

Rendered as a prominent `> ## ⚠️ Important — please read before installing` blockquote with an embedded H2 header inside the quote so the warning is impossible to miss. Structure:

1. Lead sentence — "BannerHub v6 does NOT replace BannerHub 3.7.x or BannerHub Lite — they are SEPARATE projects."
2. 3-row bullet list — BannerHub 3.7.x (PlayDay / 5.3.5), BannerHub Lite (Producdevity / Lite 5.1.4), BannerHub v6 (this repo / 6.0.x).
3. Update-incompatibility paragraph — each ships its own package names + keystore + component/Steam backend; Android rejects in-place updates between them; uninstall first.
4. Work-in-progress paragraph — frequent re-releases as XiaoJi pushes new bases; new component system + Steam clients; barely tested in general.
5. Closing **USE AT YOUR OWN RISK.**

Placed between the `---` separator (line 27) and the `**What it does**` paragraph so it's the first body content readers hit after the hero (logo + title + Discord + nav). Preserved user's emphatic capitalisation (SEPARATE, NOT, USE AT YOUR OWN RISK) and brand-name spelling (PlayDay, Producdevity).

### 2026-05-13 — README Credits flattened into a single table

Per user: "Tighten up the credits as a table, neat and tidy."

Collapsed the five sub-sections (Translation & emulation layers, Graphics drivers, Host app, Patching framework, BannerHub-specific upstream) into one flat 3-column markdown table — columns: **Project** (linked) | **Role** | **Maintainer(s)**. Same 8 entries (DXVK, VKD3D-Proton, Box64, FEX-Emu/FEXCore, Mesa Turnip, XiaoJi GameHub, ReVanced, TideGear vibration fix). Mesa Turnip row also lists the three Adreno forks BannerHub serves (Banners-Turnip, StevenMXZ, whitebelyash) inline rather than in a separate paragraph. Lead-in shortened to 1 paragraph; Discord callout retained; closing italic correction-request line retained.

### 2026-05-13 — README Credits section added at bottom

Per user: "now we need to add a credit section at the bottom, listing developers for dxvk, vkd3d, mesa turnip drivers, box64, fexcore, The Gamehub Team, Revanced project to start, if you do not have links I will provide them later we just need a section laid out to start"

New `## Credits` section inserted between `## Releases` and `## License`, plus `10. Credits` entry added to TOC (License bumped to 11). Section is grouped into five sub-headings — Translation & emulation layers (DXVK, VKD3D-Proton, Box64, FEX-Emu/FEXCore), Graphics drivers (Mesa Turnip + Banners-Turnip/StevenMXZ/whitebelyash forks), Host app (XiaoJi GameHub), Patching framework (ReVanced), BannerHub-specific upstream (TideGear/GameHub-Vibration-Fix). Each entry has the project name, a one-line role description, the maintainer(s), and a GitHub / project link.

Confidence on the links I baked in:
- HIGH: doitsujin/dxvk, HansKristian-Work/vkd3d-proton, ptitSeb/box64, FEX-Emu/FEX, FEX-Emu/FEX-ppa, mesa3d.org, gitlab.freedesktop.org/mesa/mesa, revanced.app, github.com/revanced, gamehubglobal.com (already linked elsewhere in README), TideGear/GameHub-Vibration-Fix (also already linked).
- The412Banner-internal: Banners-Turnip, StevenMXZ, whitebelyash GH org links pulled from memory `feedback_bannerhub_api_driver_prefixes.md` — confident but the user can correct if any are wrong.

Opening paragraph keeps tone humble ("almost nothing under the hood here is our work"); closing italic line invites corrections; Discord invite callout invites people to ask to be added/corrected/removed. Designed to be extensible — user will provide more names/links in future turns and they slot into the existing sub-headings.

### 2026-05-13 — README Repo layout removed, Table of contents added at top

Per user: "can the repo layout section be removed and replaced at the top with a table of contents?"

- Dropped the entire `## Repo layout` section (~36 lines of per-file annotations under `patches/`, `extensions/`, `native/`, `keystore/`, `assets/`, `.github/workflows/`). The same info is reachable via `git ls-files` + the per-patch source comments and is one more thing to keep in lockstep with reality on every feature add — better not to claim it from the README.
- New `## Table of contents` section inserted between the in-place-updates callout and `## AI Disclaimer`. 10 entries with GitHub auto-generated anchors: `#ai-disclaimer`, `#whats-new-in-v110-604`, `#what-this-is`, `#source`, `#variants`, `#signing`, `#patches-applied`, `#build-it-yourself`, `#releases`, `#license`. The existing quick-jump nav bar at the very top (Discord badge + Patches/Signing/Build/AI-disclaimer chips) is unchanged — TOC is the full structured index, nav bar is the discovery shortcut for the most popular sections.

### 2026-05-13 — README AI Disclaimer expanded with pipeline detail

Per user: "rewrite the disclaimer to explain it is used to help decompile and analyze Game Hub release apks using termux and termux package tool, the map out the apk contents and help write/rewrite new/old revanced patches from Gamehub 5.3.5 revanced project. I am sure anything in that disclaimer you can correct for me I missed"

Rewrote the disclaimer into a lead-in paragraph + three bulleted pipeline stages + a closing manual-verification paragraph:

- **Lead-in:** GameHub is closed-source; all work happens at bytecode level.
- **Decompile & analyse:** Termux + `pkg`-installed apktool on the Android phone; Claude maps R8 letters, Compose resources, manifest deltas; analysis lives in `gamehub_reports/`.
- **Write / rewrite patches:** new patches + ports forward from the **GameHub 5.3.5 ReVanced project**; R8 reshuffles every minor bump (6.0.0 → 6.0.1 → 6.0.2 → 6.0.4) so structural anchors get re-derived per release; patches in Kotlin against ReVanced patcher API + `.rve` Java extensions for fiddly edits.
- **Build & iterate:** GitHub Actions CI only, never local builds (per `feedback_build_method.md`); 9-variant matrix; artefacts pulled to phone for device test.
- **Manual verification (closing paragraph):** rooted + unrooted devices; logcat via the `getlog` Magisk helper (linked to The412Banner/logcat-bridge) on rooted, `adb logcat` on unrooted, plus in-app debug log files from the `Debug logging` patch. No stable cut until verified on hardware.

Kept the user's first-person voice ("by me", "my Android phone") and preserved "Claude AI Sonnet 4.6" verbatim from the user's disclaimer text (the model the user specified — not autocorrected to the runtime model).

### 2026-05-13 — Plan 8c local-tracker SHELVED → pure-stub variant on feature/disable-heartbeat

User reported in-game perf cost from pre3 local tracker even though privacy goal was met. Path-1 (local tracker) preserves the in-app playtime UI by recording sessions to `bh_playtime_prefs.xml` with reflection-based `Lekf;` construction; that's per-tick JSON encode + SharedPreferences disk write + a warm reflection cache. Path-2 (pure stub) throws the UI feature away in exchange for zero per-tick cost.

**Archive:** tagged `archive/plan8c-local-tracker-pre3` at `975c4b1` (push acknowledged by origin). Branch `feature/disable-heartbeat-local-tracker` left in place; the tag is the durable anchor.

**New patch:** `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableHeartbeatPatch.kt` on fresh branch `feature/disable-heartbeat` off `gamehub-604-build` @ `2d4e779`. Sibling to other privacy patches (matches Plans 4/5/8a/8b convention). Four `firstMethod {}` blocks reuse the body-contains-string anchors from the recon (`heartbeat/game/start`, `…/update`, `…/end`, `…/getUserPlayTimeList`) so anchor stability across R8 reshuffles is preserved. Smali snippets:

```smali
# invokeSuspend bodies (Lfeo / Lheo / Laeo)
sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
return-object v0

# Lse7;->c  (getUserPlayTimeList)
new-instance v0, Ljava/util/ArrayList;
invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
new-instance v1, Ln55;
invoke-direct {v1, v0}, Ln55;-><init>(Ljava/lang/Object;)V
return-object v1
```

No extension classes, no resource patch, no dependencies on `sharedGamehubExtensionPatch`. UI iterator over the empty list runs zero passes → no ClassCastException risk (the failure mode that bit pre1/pre2 of the local-tracker variant).

Trade-off accepted by user: in-app playtime display will be empty. Local-tracker tag stays available for revival if users request the feature back.

### 2026-05-13 — Plan 8c Path 2 (pure stub) installed on device; verification in progress

User installed `BannerHub-V6-1.1.0-604-stub-pre1-Patched-Normal.apk` (pre1 from `feature/disable-heartbeat` @ `a050b33`, [run 25837778671](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25837778671)) **over** the existing Path 1 local-tracker install (`1.3.0-604-playtime-pre2`). APK is in `/storage/emulated/0/Download/apk-Normal (2)/`, SHA-256 `5df7f80f09b83ad70a0c41d76c494997430ca98ef591f691b6a06b80726b2018`.

**Install evidence (without root-side `/data/app` access):**
- `/data/data/banner.hub/files/profileinstaller_profileWrittenFor_lastUpdateTime.dat` mtime → `2026-05-13 22:24` — ProfileInstaller writes this once per APK install/update, so it's a reliable install timestamp.
- `bh_playtime_prefs.xml` last mtime → `2026-05-13 22:05` (DOOMBLADE session `dur:517`, started before update). **Frozen** since the Path 2 install; no further heartbeats written.
- Post-update launch at 22:26 wrote `pc_g_setting63362.xml`, `sp_winemu_unified_resources.xml`, `com.google.android.gms.measurement.prefs.xml` — but **NOT** `bh_playtime_prefs.xml`. First positive signal.

**Cross-check on the APK on disk** (Downloads copy, assumed identical to installed):
- All 9 classesN.dex scanned — **zero hits** for `BhPlayTimeTracker` or `bh_playtime_prefs` strings, confirming Path 1's extension class is gone.
- Compare against Path 1 pre2 APK on the same device: 4 dex files contain `Lapp/revanced/extension/gamehub/playtime/BhPlayTimeTracker;`. Clear delta.

**Pending definitive test** (cannot complete from PRoot side):
1. User launches a Wine game from BannerHub.
2. Plays for ≥60s (Path 1 ticked heartbeat every 30s; Path 2 should tick zero).
3. Re-check `getlog --ls /data/data/banner.hub/shared_prefs/bh_playtime_prefs.xml` mtime.
4. **Pass:** mtime still `2026-05-13 22:05`. **Fail:** mtime advances (would mean Path 1 still resident somehow).

**Why root-side `/data/app/.../base.apk` isn't readable**: logcat-bridge allowlist excludes `/data/app/`. The Downloads copy + ProfileInstaller timestamp + prefs-mtime delta are the workable triangulation when the installed APK itself is out of reach. Recording this as the reference recipe for future "what's installed?" checks.

Branch state unchanged: `feature/disable-heartbeat` @ `a050b33` still the head. Once verification passes, the merge to `gamehub-604-build` is the only remaining step before this plan ships.

### 2026-05-13 — Plan 8c Path 2 (pure stub) DEVICE-CONFIRMED + merging to gamehub-604-build

User ran DOOMBLADE for several minutes after the 22:24 install. Re-checked `bh_playtime_prefs.xml` at ~23:02:

- **mtime: `2026-05-13 22:05`** — unchanged from pre-install. Stale Path 1 data preserved (last session `dur:517`), zero new writes during gameplay.
- Meanwhile the rest of the app was clearly active:
  - `pc_g_setting63362.xml` (DOOMBLADE config) → 22:26 (2 min after install)
  - `sp_winemu_unified_resources.xml` (Winlator registry) → 22:59
  - `com.google.android.gms.measurement.prefs.xml` → 23:02
  - Directory `.` mtime → 23:02

That's the definitive signal: heartbeat start/update/end paths produce zero side effects during a real gameplay session. Pure stub fully neutralizes the telemetry without the per-tick JSON encode + SharedPreferences write + reflection cost of Path 1.

**Merging `feature/disable-heartbeat` → `gamehub-604-build` (--no-ff).**

#### Path 1 (local tracker) — preserved for future revival

The Path 1 variant (which keeps the in-app playtime UI working by routing heartbeat ticks into a local `BhPlayTimeTracker` instead of the XiaoJi network call) is **NOT being deleted**. It remains fully recoverable:

- **Branch:** `feature/disable-heartbeat-local-tracker` @ `975c4b1` (local + origin)
- **Tag:** `archive/plan8c-local-tracker-pre3` (durable anchor — branches can be force-pushed, tags shouldn't be)
- **Files it ships** (entirely separate from Path 2's filenames, so the two never collide on the filesystem):
  - `extensions/.../gamehub/playtime/BhPlayTimeTracker.java` (300 lines — the runtime tracker)
  - `patches/.../gamehub/playtime/DisableHeartbeatLocalTrackerPatch.kt` (162 lines — the patch wiring it in)

If a user (or batch of users) later requests the in-app playtime UI back, the path is: revert the Path 2 merge, then merge `feature/disable-heartbeat-local-tracker`. Or cherry-pick its two source files onto a fresh branch if we want both variants offered as separate patches users can toggle in `revanced-cli --include`. Either way, no rebuild from scratch needed.

### 2026-05-13 — Legacy GLES2 renderer toggle — DRAFTED, NOT STARTED (shelved pending perf data)

User asked whether a ReVanced patch could re-implement GameHub 6.0.2's GLES2 renderer as a toggle in 6.0.4, after we walked through the byte-level renderer rewrite documented in `gamehub_reports/GAMEHUB_600_MASTER_MAP.md` § 26.23. Scoping captured here so we can pick it back up later.

**Background.** 6.0.2 ran an OpenGL ES 2.0 + EGL renderer in `libxserver.so` with an `ASurfaceTransaction` plane compositor for the cursor in `libwinemu.so`. 6.0.4 replaced both with a Vulkan compositor (four backends registered: `winemu-xserver`, `winemu-flip`, `winemu-vk`, `lorie-vk`; cursor folded into the Vulkan path under `g.cursor.ds`). The Vulkan path is what makes AI frame-gen and the libGameScopeVK ICD chain work — the SPIR-V HDR tone-map shaders shipped as dead-weight assets in 6.0.2 and only went live in 6.0.4 once the consumer existed.

**Architectural verdict: feasible.** Every individual technique required has shipped on `gamehub-604-build` already:

| Step | Existing precedent |
|---|---|
| Bundle additional `.so` files in `lib/arm64-v8a/` | Vibration patch ships `libevshim.so` |
| Smali `System.loadLibrary` hook to route by SharedPreferences flag | Standard smali patching |
| Restore deleted `DirectRendering` Java class as smali stubs | Unsafe.allocateInstance + Proxy patterns from menu-injection playbook (pre7→pre17 trail) |
| Conditionally short-circuit `XServer.setFlipEnabled(Z)V` + `onFlipStateChanged(Z)V` | Standard smali branch insertion |
| Settings UI row for the toggle | Menu-injection playbook (`Lx57.a()` + `Lpzc.j0()`) |

**Real blockers (technical, not patcher-side).**

1. **libwinemu pairing.** The 6.0.2 GLES2 path depends on the `ASurfaceTransaction*` plane-compositor symbols that live in 6.0.2's `libwinemu.so` — but that libwinemu also contains unrelated 6.0.3/6.0.4 fixes (input, audio, controller, etc.). Running it may regress those. **Unanswered until a load-test build runs.**
2. **JNI symbol drift.** Restored `DirectRendering` smali stubs must match the exact JNI signatures the 6.0.2 libxserver expects to call back into. Each missing method = crash class.
3. **Frame-gen + libGameScopeVK + HDR tone-mapping go inert** in legacy mode. Release notes would need to explain the trade-off; users opting in lose the Vulkan-only features.
4. **APK size +~5 MB** for the two bundled libs.
5. **revanced-cli SEVERE-doesn't-fail-CI anti-pattern.** Per menu-injection playbook — CI step would need explicit log scanning so a partial patch failure can't ship green.

**Proposed first milestone if/when this resumes.**

Before building the toggle, prove the 6.0.2 lib pair even loads under 6.0.4's Kotlin/runtime:

- Branch: `feature/legacy-gles2-renderer` off `gamehub-604-build` (per branch-per-patch workflow).
- Patch class: `LegacyGles2RendererPatch.kt`.
- Asset drop + smali loadLibrary hook with a **hardcoded "always legacy" flag** (no SharedPreferences, no UI).
- CI build → device install → does it launch? Does any game render?
- If it crashes on launch or every game black-screens → toggle work is moot, close the branch.
- Only if step above passes: add SharedPreferences toggle + Settings UI row + per-game override.

**Why it's on the shelf.** No perf data yet showing GLES2 would actually win on any device class. Vulkan-on-Adreno is generally lower-overhead; the legitimate revisit triggers are (a) a device class reports clear Vulkan-renderer regressions (Mali-G57-class, pre-Adreno-6xx, Helio-G99 territory), or (b) a specific game family demonstrably runs better under GLES2.

**Fallback if the toggle isn't worth the effort.** Ship a separate "BannerHub Legacy GLES2" variant built off the 6.0.2 APK base — same pattern as the PuBG variant pinned at 5.3.5. No dual-lib bundling, no JNI shim work, no toggle UI. Trade-off: separate install, not a setting.

**Status.** No branch created. No code written. Memory entry at `project_bannerhub_revanced_legacy_gles2_renderer.md` carries the same scope so the concept survives across sessions.

### 2026-05-13 — Plans 1+7+GMS Measurement recon (work scheduled for tomorrow)

After merging Plan 8c Path 2, ran recon for the three remaining privacy items so tomorrow's session opens with concrete patch shapes.

#### Plan 1 — analytics-event host redirect (APK side)

Source of the host list: `RedirectCatalogApiPatch.kt:51-53` already documents what it deliberately left untouched. The analytics-event hosts are `landscape-api-*-*.vgabc.com/events`. Grepped the 6.0.4 decompile at `/data/data/com.termux/files/home/gamehub_604_decompile/` and found **two** smali files with these strings:

**File 1: `smali_classes4/cx5.smali`** — general analytics events. Standard if-eqz environment switch:

```
if (BuildConfig.DEBUG)               host = "https://dev2-gamehub-api.vgabc.com/events"               (line 630)
else if (Lz40;->b == Lesj;->d /*Beta*/)
                                     host = "https://landscape-api-beta.vgabc.com/events"             (line 650)
else                                 host = "https://statistic-gamehub-api.vgabc.com/events"          (line 658)  ← PRODUCTION
```

**File 2: `smali_classes4/nh4.smali`** — device-performance-config sub-endpoint. Same switch shape:

```
                                     "https://dev2-gamehub-api.vgabc.com/events/device-performance-config"               (line 183)
                                     "https://landscape-api-beta.vgabc.com/events/device-performance-config"             (line 203)
                                     "https://statistic-gamehub-api.vgabc.com/events/device-performance-config"          (line 211)  ← PRODUCTION
```

**The production analytics host is `statistic-gamehub-api.vgabc.com`** — distinct from the catalog hosts (`landscape-api-{cn,oversea}.vgabc.com`) that the existing catalog redirect already swapped. So this is genuinely new traffic; not double-covered.

**Patch shape.** Two `bytecodePatch { ... }` blocks (one per file), each using the same `indexOfFirstInstructionOrThrow { CONST_STRING && StringReference == X }` find-and-replace as `RedirectCatalogApiPatch`. Belt-and-braces option: swap all 6 const-strings (3 in each file) — that way even if a future Beta-flag flip occurred, traffic stays inside the Worker. Cheap; recommended.

R8 letters to track for future base bumps: `Lcx5;` and `Lnh4;` will rename. Anchor by string content (`statistic-gamehub-api.vgabc.com/events`), not by class letter — same pattern as the privacy-hardening playbook.

#### Plan 7 — Worker `/events/*` route (Cloudflare Worker side)

Inspected `/data/data/com.termux/files/home/bannerhub-api/bannerhub-worker.js` (1203 lines).

- Entry point: `async fetch(request, env, ctx)` at line 495.
- 6.0 client gate strips `/v6/` prefix to `is60=true` (line 505-508).
- OPTIONS preflight handler at line 521.
- Routes follow `if (url.pathname === '/foo') return handleFoo(...)` early-return pattern.
- Catch-all fallback at line 1167: `fetch(${GAMEHUB_API}${url.pathname}${url.search}, ...)` — this is the layer that would forward analytics events to XiaoJi if we did nothing.

**Insertion point.** Between line 525 (OPTIONS return) and line 528 (`const time = ...`). Insert:

```js
// Analytics events — Plan 7. Patched APK redirects statistic-gamehub-api.vgabc.com
// here; silently drop with 204 to keep the app's fire-and-forget call from
// retrying. Matches /events and /events/<anything> for current + future paths.
if (url.pathname === '/events' || url.pathname.startsWith('/events/')) {
  return new Response(null, { status: 204, headers: corsHeaders })
}
```

Place BEFORE the catch-all so the path never reaches the GAMEHUB_API forward. corsHeaders is in scope at the insertion point.

**Deploy mechanics.** Per [[project_bannerhub_api_worker]] there's a no-wrangler deploy recipe. Doc cross-link will go into the Plan 7 commit message.

#### Plan 10 — GMS Measurement kill (NEW finding, was not in original plan list)

Pulled `/data/data/banner.hub/shared_prefs/com.google.android.gms.measurement.prefs.xml` via getlog. **Active and writing locally** despite Plan 4 having shipped (Plan 4 disables Firebase Analytics SDK init, but GMS Measurement is a separate Google Play Services component):

```
measurement_enabled_from_api=true
measurement_enabled=true
deferred_analytics_collection=false
session_id=1778690101
app_instance_id=6db38be8b76e59c8d22db2e059ee472c   ← persistent per-install identifier
gmp_app_id=1:304891727788:android:e27ed4a7a22bdbc9adb409
non_personalized_ads=true
consent_settings=G101
dma_consent_settings=-20:0
use_service=true                                     ← routes via AppMeasurementService
```

`use_service=true` means the SDK ships data out via the system-level `AppMeasurementService`. AndroidManifest declares three components (all currently `android:enabled="true"`):

```xml
<receiver android:enabled="true" android:exported="false"
          android:name="com.google.android.gms.measurement.AppMeasurementReceiver"/>
<service  android:enabled="true" android:exported="false"
          android:name="com.google.android.gms.measurement.AppMeasurementService"/>
<service  android:enabled="true" android:exported="false"
          android:name="com.google.android.gms.measurement.AppMeasurementJobService"
          android:permission="android.permission.BIND_JOB_SERVICE"/>
```

**Patch shape (analogous to Plan 5's manifest Layer B).** Resource patch that walks `<application>` and sets `android:enabled="false"` on every `<receiver>`/`<service>` whose name starts with `com.google.android.gms.measurement.`. Three components total in 6.0.4. Same DOM-walk pattern as `DisableMobPushPatch.kt`'s manifest companion.

**Side-finding to verify tomorrow before writing Plan 10.** Grepping `pre2-decoded/AndroidManifest.xml` (last installed APK before today's Path 2) shows **none** of Plan 4's three meta-data entries (`firebase_analytics_collection_deactivated`, `google_analytics_adid_collection_enabled`, `google_analytics_ssaid_collection_enabled`) actually present. Two possibilities:
1. `feature/disable-heartbeat-local-tracker` (the source branch for pre2) branched off `gamehub-604-build` *before* Plan 4's merge `178c5ec` landed, so Plan 4's manifest changes simply weren't carried into this build.
2. Plan 4 silently failed in the build pipeline (the revanced-cli `SEVERE`-without-failure anti-pattern from the menu-injection playbook).

Tomorrow: re-decode the freshly-installed Plan 8c Path 2 APK (downloaded today) and re-check. If still missing, Plan 4 needs a re-verification pass before Plan 10 lands so we don't ship "Plan 10 kills GMS" without confirming Plan 4 actually killed Firebase Analytics.

#### Suggested order tomorrow

1. Verify Plan 4 manifest entries in the freshly-built Path 2 APK (decode `BannerHub-V6-1.1.0-604-stub-pre1-Patched-Normal.apk` from Downloads, grep manifest). If absent → fix Plan 4 first; if present → proceed.
2. Plan 1 + Plan 7 together — they're coupled (Plan 1 alone would let the Worker proxy events to XiaoJi via its catch-all; Plan 7 alone has nothing pointed at it). Branch `feature/disable-analytics-events` for the APK side; Worker change pushed to `bannerhub-api` separately. Coordinated deploy.
3. Plan 10 (GMS Measurement) — separate branch `feature/disable-gms-measurement`. Pure resource patch, no bytecode.
4. Then Plan 9 (PRIVACY.md) — written against the actually-shipped state, including the bigeyes.com image CDN honesty note discussed today.

#### EOD checkpoint — session ends 2026-05-13 evening

State to resume from tomorrow:

- `gamehub-604-build` HEAD = `7f2f851` (this recon commit, pushed).
- Last merged feature = `feature/disable-heartbeat` → merge commit `519ba65` (Plan 8c Path 2, device-confirmed).
- No open branches in flight — all recon lives on `gamehub-604-build` itself.
- **First action tomorrow:** decode `/storage/emulated/0/Download/apk-Normal (2)/BannerHub-V6-1.1.0-604-stub-pre1-Patched-Normal.apk` and grep its `AndroidManifest.xml` for `firebase_analytics_collection_deactivated`. If present → Plan 4 is good, start Plans 1+7. If absent → Plan 4 silently failed in build, fix it first before anything else lands.
- Both Plans 1+7 and Plan 10 have full recon notes above; tomorrow is implementation, not investigation.


### [docs] — README badges expansion (2026-05-14)
**Commit:** `970fa12` on `gamehub-604-build`

Added GitHub downloads badges (total + latest-release) alongside the existing Discord badge in the centered header block. All three badges already in `for-the-badge` style — this was the visual reference used to standardize BannerHub and Bannerhub-Lite the same day. No code changes.


## 2026-05-14 — Privacy series resumes: Plan 4 re-verified, Plan 10 implemented

### Plan 4 re-verification (cleared yesterday's blocker)

Pulled the device-confirmed Plan 8c Path 2 APK from CI run [25837778671](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25837778671) artifact `apk-Normal`. SHA-256 `5df7f80f09b83ad70a0c41d76c494997430ca98ef591f691b6a06b80726b2018` matches the merge-time hash in memory — same APK that was sitting on banner.hub yesterday.

Decoded with `apktool d` and grepped `AndroidManifest.xml`:

| Plan 4 meta-data | Manifest line | Result |
| --- | --- | --- |
| `firebase_analytics_collection_deactivated="true"` | 324 | present |
| `google_analytics_adid_collection_enabled="false"` | 325 | present |
| `google_analytics_ssaid_collection_enabled="false"` | 326 | present |

All three entries land correctly. CI log confirms `"Disable Firebase Analytics" succeeded` on all 9 variants, zero `SEVERE`. Yesterday's "Plan 4 missing" reading was a false negative — most likely a stale decode dir (the user grepped `pre2-decoded/` which was an older artifact decode, not the freshly-built Path 2 APK).

**Bonus spot-checks on the same APK:**
- Plan 5 (Mob Push) — 13 `enabled="false"` entries in manifest, matches expected count for Layer B.
- Plan 8a (Ad-ID perms) — 0 `AD_ID`/`ADSERVICES` permissions present, stripped.
- Plan 10 confirmed needed — lines 252–254 still show `AppMeasurementReceiver`/`Service`/`JobService` with `android:enabled="true"`. GMS is genuinely a separate kill path.

### Plan 10 — Disable GMS Measurement (implementation)

**Branch:** `feature/disable-gms-measurement` off `gamehub-604-build@9e1930e`.

**Patch:** `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableGmsMeasurementPatch.kt` (57 lines).

Pure resource patch, no bytecode. Walks `<application>` and sets `android:enabled="false"` on exactly three FQCN-matched components:

- `<receiver android:name="com.google.android.gms.measurement.AppMeasurementReceiver">`
- `<service  android:name="com.google.android.gms.measurement.AppMeasurementService">`
- `<service  android:name="com.google.android.gms.measurement.AppMeasurementJobService">`

Shape modeled on Plan 5's `disableMobPushManifestPatch` Layer B, but FQCN-exact instead of prefix-based since the GMS Measurement surface is a fixed three-component set, not an arbitrarily-nested SDK namespace.

**Why pure manifest is sufficient (no bytecode layer):** Unlike Mob Push, GMS Measurement does NOT auto-init via a `<provider>`. The two services are bound on demand by other GMS code (PackageManager registration query); the receiver fires on broadcasts. `android:enabled="false"` makes PackageManager treat each as not-present, so the bound-service lookups return null and broadcasts are filtered out before delivery. No call-site removal needed.

### Plan 10 — pre1 verification + device test

**CI run [25881040284](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25881040284)** — 3m17s, conclusion=success, 0 SEVERE, `"Disable GMS Measurement"` succeeded on all 9 variants, Plan 4 still succeeding (no cross-patch regression). APK SHA-256: `4b330e12261c710f0ded068b2421618fa7809af9a3dfde021b18e2a2c6402d6c`.

Manifest grep on decoded `apk-Normal` (lines 252-254):

```
<receiver android:enabled="false" ... AppMeasurementReceiver/>
<service  android:enabled="false" ... AppMeasurementService/>
<service  android:enabled="false" ... AppMeasurementJobService ... permission=BIND_JOB_SERVICE/>
```

All three flipped at the same line numbers as pre-patch. Surgical edit, no other manifest churn. Plan 4 entries still at 324-326.

### Plan 10 — device test (banner.hub)

**Install:** 2026-05-14 16:19 (`profileinstaller_profileWrittenFor_lastUpdateTime.dat`).

**Usage between baseline and post-test:** game launched + quit (Wine container exercised), app backgrounded for work session, reopened, cycled tabs (home/library), browsed Steam games, online topics, leaderboards. ~30+ minutes of mixed activity including foreground/background cycles.

**Result — `/data/data/banner.hub/shared_prefs/com.google.android.gms.measurement.prefs.xml`:**

| Metric | Baseline | Post-test | Note |
| --- | --- | --- | --- |
| `session_id` | 1778690101 | 1778690101 | frozen |
| `last_pause_time` | 1778692645533 | 1778692645533 | **frozen — decodes to 2026-05-13 22:37 UTC (yesterday's pause)** |
| `health_monitor:start` | 1778748219665 | 1778748219665 | frozen |
| `app_instance_id` | 6db38be8…472c | 6db38be8…472c | unchanged |

**The decisive signal:** `last_pause_time` would normally advance on every pause if GMS Measurement were alive. It hasn't moved despite real pause/resume activity today. **GMS Measurement is no longer recording session events.** ✅

**One mtime curiosity:** file mtime is 16:51 (~30 min after install), but all values are unchanged from baseline. Almost certainly a no-op `SharedPreferences.apply()` during the install transition that touched mtime without changing data. Worth noting but not a failure — data isn't advancing.

**Side-effect check:** game launch, online topics, leaderboards, Steam cards, tab navigation all worked normally. No GMS consumer broke.

### Plan 10 — MERGED to gamehub-604-build

**Merge commit:** `d4675ec` (`--no-ff` of `feature/disable-gms-measurement` into `gamehub-604-build`), 2026-05-14.

`gamehub-604-build` HEAD now `d4675ec`. Privacy plans 4 + 5 + 8a + 8b + 8c-pure-stub + **10** all shipped. Plans 1+7 (analytics-event Worker redirect) next — recon already complete from yesterday.


## 2026-05-14 — Plan 1 reframed as pure client-side stub (Plan 7 dropped)

### Design pivot

Original Plan 1+7 design (yesterday's recon) was to redirect `statistic-gamehub-api.vgabc.com/events*` to the BannerHub Cloudflare Worker and 204 it. User asked whether a simpler local stub would work — the answer is yes, and it's strictly better:

| | Worker redirect (original) | Local stub (chosen) |
| --- | --- | --- |
| Repos to touch | 2 (revanced + bannerhub-api) | 1 |
| Trust shift | XiaoJi → CF+Me | none |
| Worker invocations | every event burns one | zero |
| Battery / radio wake | one failed connection per event | zero |
| Deploy coordination | yes | none |

The 5.3.5 `DisableOtaUpdatesPatch` (shipped as Plan 8b) already uses a similar `127.0.0.1` URL-rewrite technique, but going one level deeper — stubbing the entire send method to early-return — eliminates even the connection attempt. Plan 7 (Worker `/events/*` route) deleted from the inventory.

### Recon (Lcx5; / Lnh4; / Loh4;)

**Lcx5; (general events `/events`)**
- Single public method: `Lcx5;->a(Ljava/util/Collection;Lci3;)Ljava/lang/Object;` (suspend send-batch).
- Sole external caller: `Lazi;` at smali line 444 — does `check-cast … Lyw5;` on the result.
- Return contract: caller expects `Lyw5;` data class — `(boolean success, Integer code, String msg, Throwable err, int defaultMask)` constructor.
- URLs in body: 3 const-strings (dev2/beta/production); production string is `"https://statistic-gamehub-api.vgabc.com/events"` (no trailing path) — unique enough to anchor on.

**Loh4; (device-performance-config `/events/device-performance-config`)**
- Public method: `Loh4;->b(IJLci3;)Ljava/lang/Object;` — called by 5+ classes (lh4 zz3 xz3 uz3 b04).
- `Loh4;->b` calls `Loh4;->c` which constructs `Lnh4;` (the lambda body) which holds the actual URL strings + HTTP send.
- Caller of `b` (e.g. zz3) does `check-cast … Lxnm;` — `(int, LinkedHashSet)` constructor.
- URLs not directly in `b`'s body — must anchor by class + name + signature `(IJLci3;)Object`.

**Why stub at public methods, not at the URL-containing lambda body** — callers' `check-cast` contracts force a specific concrete return type. Returning Unit.INSTANCE from `Lnh4;->invokeSuspend` would propagate up to `Loh4;->b` which would then crash trying to build a `Lxnm` from a Unit.

### Patch — `StubAnalyticsEventsPatch.kt`

**Branch:** `feature/stub-analytics-events` off `gamehub-604-build@d4675ec`.

Both methods get an `addInstructions(0, …)` prefix that allocates the expected return type and returns immediately. Hardcoded class letters (`Lcx5`, `Loh4`, `Lyw5`, `Lxnm`) with structural anchors (URL-string-in-body for cx5; class+name+signature for oh4). Recipes for re-deriving each letter on a future base bump are in the patch source header comment.

### pre1 — silent assembly bug

CI run [25890397139](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25890397139) reported 0 SEVERE and `"Stub analytics events" succeeded` 9/9. **But the decoded APK revealed only 5 of the 7 stub instructions landed for Lcx5;->a** — the `invoke-direct` constructor call and `return-object v0` were silently dropped.

**Root cause:** Dalvik's `invoke-direct` standard form (format 35c) is capped at **5 registers**. The Lyw5 constructor takes 6 args (Z, Integer, String, Throwable, I + implicit `this` = 6 regs). The smali assembler bailed on the bad instruction at assembly time without raising a SEVERE, and dropped both that line and the subsequent `return-object`. Net result: v0 was left half-initialized (new-instance only, no ctor call) before the original method body's `move-object/from16 v0, p0` clobbered it — so the entire original send path executed.

**Fix in commit `1a2b588`:** swap to `invoke-direct/range {v0 .. v5}` (format 3rc, range form, no register cap). The Lxnm stub on Loh4;->b was correctly assembled in pre1 because that constructor only uses 3 registers — within the format-35c cap.

### pre2 — clean

CI run [25890683302](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25890683302). 0 SEVERE, 9/9 succeeded.

APK SHA-256: `83f52c597faccbdc0f5d2e5d3d36e11811a330247ad0690db23510399b973cda`. Both stubs landed complete in the decoded smali — `new-instance` → const loads → `invoke-direct[/range]` → `return-object v0`, followed by the now-unreachable original method body.

### Active state (2026-05-14 19:17 EDT — checkpoint)

- Branch `feature/stub-analytics-events` at `1a2b588`, pushed.
- pre2 APK installed on banner.hub at 19:12 EDT (`profileinstaller_profileWrittenFor_lastUpdateTime.dat` mtime).
- App not yet exercised — `banner.hub` not running, logcat empty (`getlog -n 10000 banner.hub` returns 0 lines from the app process).
- Awaiting device test: open app, launch a game, browse Steam cards / leaderboards / online topics for ≥5 minutes. Success signal = zero hits for `statistic-gamehub-api.vgabc.com` or `vgabc.com/events` in logcat during the active session.
- Side observation (Plan 10 territory, not Plan 1): `com.google.android.gms.measurement.prefs.xml` mtime advanced from yesterday's 16:51 → today 19:17, even with app not running. Likely GMS system process touching the file. Need to check if values actually changed or just mtime — pending fresh `getlog --cat` after device test.

### Resume checklist if session is lost mid-test

1. Pull latest: `cd /data/data/com.termux/files/home/bannerhub-revanced && git pull && git checkout feature/stub-analytics-events`.
2. APK already installed; if user lost it, re-download via `gh run download 25890683302 --repo The412Banner/bannerhub-revanced --name apk-Normal` (artifacts expire 2026-05-28).
3. Pull a fresh logcat trace: `getlog -n 20000 banner.hub` → output goes to `/home/claude-user/logcat-banner.hub-<timestamp>.txt`.
4. Verify: `grep -c "vgabc.com\|statistic-gamehub\|/events" <logfile>` must be 0.
5. Verify GMS prefs frozen: `getlog --cat /data/data/banner.hub/shared_prefs/com.google.android.gms.measurement.prefs.xml` — `last_pause_time` should still be 1778692645533 (yesterday).
6. If both green → merge `feature/stub-analytics-events` → `gamehub-604-build` with `--no-ff`, push, update memory + progress log + MEMORY.md privacy hook line.
7. Privacy series state post-merge: Plans 4 + 5 + 8a + 8b + 8c-pure-stub + 10 + 1 all shipped. Only Plan 9 (PRIVACY.md) left.

### Plan 1 — device test result + merge (2026-05-14 ~19:35 EDT)

User did a clean install of pre2 APK then ran a full session: launch app, launch a game, play, quit game, exit app. Captured a DNS recorder trace (started before opening the app, stopped after quit) plus a logcat dump in `log_2026_05_14_19_32_26.log` (1029 lines, spans 19:25:56 → 19:32:17 = ~6.5 minutes of post-install activity).

**DNS recorder evidence** (screenshot `Screenshot_20260514-193241.png`): 12 hosts resolved during the session. **`statistic-gamehub-api.vgabc.com` did NOT appear**, nor did the dev2/beta variants. Hosts that did resolve are all expected non-XiaoJi: `firebase-settings.crashlytics.com` (Crashlytics config fetch — its own DNS path, not analytics), `firebaselogging-pa.googleapis.com` (🚫 blocked marker, not from us), `galaxy-log.gog.com` (GOG), `shared.akamai.steamstatic.com` (Steam image CDN), `play.googleapis.com` + `android.apis.google.com` + `firebaseinstallations.googleapis.com` (Play Services + install ID), plus a few system/browser/Claude-side hosts unrelated to BannerHub. Cleanest possible signal — the stub returns before any URL string is allocated, so the HTTP client never asks DNS to resolve those hosts.

**Logcat evidence**: 0 grep hits for `vgabc.com`/`statistic-gamehub`/`/events` across all 1029 log lines. 0 crashes (no FATAL / AndroidRuntime / ClassCastException / ClassNotFound). 414 banner.hub-tagged lines = real heavy in-app activity. `Lazi`'s `check-cast Lyw5;` succeeded silently (our stub-allocated Lyw5 instance accepted); all 5+ callers of `Loh4;->b` got their expected `Lxnm;` back without cast crashes.

**Merge commit:** `b043f8c` (`--no-ff` of `feature/stub-analytics-events` into `gamehub-604-build`).

`gamehub-604-build` HEAD now `b043f8c`. **Privacy plans 4 + 5 + 8a + 8b + 8c-pure-stub + 10 + 1 ALL SHIPPED.** Only Plan 9 (PRIVACY.md) remains — write-up against the actually-shipped state including bigeyes.com / GOG-telemetry / Steam-CDN / Firebase-Settings honesty notes.


## 2026-05-14 — Plan 9 SHIPPED: PRIVACY.md + README link

### What landed

- `PRIVACY.md` at repo root — the public-facing privacy doc covering the full hardening stack.
- `README.md` header — added `Privacy` link to the centered navigation row between `Patches` and `Signing`.

### Structure

1. **What we kill** — table of 8 telemetry channels (Firebase Analytics, Mob Push, AD-ID perms, OTA URL, heartbeat tracker, GMS Measurement, `/events`, `/events/device-performance-config`) with one-line mechanism + merge-commit link per row. All commit hashes verified against `git log --merges` before citing per [[always-verify-never-assume-hard-rule]].
2. **What we deliberately did NOT touch** — `bigeyes.com` (image CDN, Plan 3 deliberately skipped for cost), `firebase-settings.crashlytics.com` (Crashlytics config-fetch leftover, no events upload), `firebaselogging-pa.googleapis.com` (separate logging path; suggested future Plan 11), GOG telemetry, Steam CDN, BannerHub Cloudflare Worker. Each with explicit "what it does / what it leaks / why we kept it" paragraph.
3. **Trust-shift acknowledgement** — explicitly calls out that catalog API still flows through the Worker (so users see CF+The412Banner instead of XiaoJi for that surface), and that Plan 1's redesign closed the analytics half so telemetry has zero trust shift now.
4. **Out of scope** — Steam Cloud, GOG online, EOS, anti-cheat, user save data, Windows games themselves.
5. **Verification recipe** — DNS recorder + logcat + decoded manifest + smali head checks. The same recipe used internally during dev, exposed publicly so users can reproduce.
6. **Issues link** — explicit invitation to report disclosure gaps as bugs.

### Empirical claim cited

The DNS-recorder evidence captured during the Plan 1 device test (full 6.5-min session, zero queries for `statistic-gamehub-api.vgabc.com` or dev2/beta variants) is cited as the empirical confirmation that the table's claims hold on a real device.

### Doc lives directly on `gamehub-604-build`

No feature branch — doc-only changes follow the precedent of `970fa12` (README badges) landing directly on the active branch.

### Privacy hardening series — COMPLETE

All 8 plans done: 4, 5, 8a, 8b, 8c-pure-stub, 10, 1, 9. Plan 7 dropped at Plan 1 redesign. Plan 6 N/A. Plan 8c local-tracker shelved+preserved at `archive/plan8c-local-tracker-pre3`.


## 2026-05-14 — Investigation: missing "PC Game Settings" in Explorer-view More Menu for Steam games

### User report

In Explorer view (not Handheld), opening the game-detail page's More Menu for a Steam-linked game (Doomblade screenshot `Screenshot_20260514-195135.png` shows DOOMBLADE detail page with the bottom-sheet "More Menu"). Visible rows: Add to Desktop / Remove from Libr… / Edit Cover / PC Vibration… **PC Game Settings is missing.** Our injected PC Vibration row IS present.

### Trace

The More Menu Composable is `Lx57;->a(Lf37;Lpo7;Lv83;I)V` (already structurally anchored in `VibrationMenuRowPatch.kt` injection 1). Tracing the rows:

| Row | Smali line | Label source |
| --- | --- | --- |
| **PC Game Settings** (FIRST row in the original list) | 2421-2531 (gated `if-eqz v17, :cond_50` at line 2421) | `Lmil;->U:Lxrl;` → `Lggl(15)` → packed-switch `:pswitch_d` (line 4848) → const-string `"string:features_game_pc_settings"` (line 5134) → CVR-resolved to "PC Game Settings" |
| Other rows | 2526, 2648, 2738, 2821, 2939, 3021, 3109, 3203, 3294, 3503 (10 total `Lx9d;->add` calls in the method) | various Lwhl;/Lmil; label refs |

### Why PC Game Settings is hidden

`if-eqz v17, :cond_50` at line 2421 skips the entire row block when `v17 == 0`. `v17` is set at line 2055 (`move/from16 v17, v2`) from `v2`, which carries the AND-combined result of an 8-deep stacked check at lines 2200-2299 — each step does `invoke-interface ... Lxjk;->getValue()` (Compose-state reads), check-casts to Boolean/`Lj67;`/`Lg67;`, and `if-nez ... :cond_4b` short-circuit on mismatch.

Practical meaning: XiaoJi designed the More Menu's row visibility to filter out items that don't apply to the current launch method. PC Game Settings only makes sense for **plain Wine PC executables** — Steam-launched games go through Steam Lightweight Client (which has its own settings panel), so the raw DXVK/VKD3D/Box64/Wine prefix dialog would be a no-op for them. The 8 state reads are functionally "this game uses the direct PC pipeline" — at least one returns false for Steam-linked games, so v17 = 0, so the row is skipped.

This is **GameHub-native UX safety filtering**, not a BannerHub regression. Our Plan 1/4/5/8/10 patches don't touch this code path.

### Why our PC Vibration row IS visible

`VibrationMenuRowPatch.kt` injection 1 appends a Java-helper `appendVibrationRowTo(...)` call AFTER the LAST existing `Lx9d;->add` (per the patch comment, "after the last existing add() call"), so it sits OUTSIDE every gating block. It runs unconditionally for every game and every view. (PC Vibration actually works for Steam games via our `libevshim.so` shim, so showing it is appropriate.)

### Why this varies by view

Explorer view (game-detail page) uses `Lx57;->a()`. Handheld view (library tile popup) uses `Lpzc;->j0()` and `ted.smali::f()` per the menu-injection playbook. Different composables, different filter rules — so the same game can show PC Game Settings in one view but not the other.

### Decision

User direction: **remove all option gating in both menus** so every option shows for every game type and every view. UX safety filtering off. Implementing as a new bytecode patch — see next PROGRESS_LOG entry.

### Scope narrowed during planning

User narrowed the ask: "all I really care about is PC Game Settings, let the rest do whatever whenever". Only the PC Game Settings row in `Lx57;->a` (Explorer view) gets ungated. Other rows (PC Uninstall, Online Update, Instant Settings, Version Switch) keep their native gating. The Handheld-view `Lpzc;->j0` doesn't need patching since PC Game Settings already shows there.

## 2026-05-14 — `ShowPcGameSettingsRowPatch` shipped

### Patch

- File: `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/ShowPcGameSettingsRowPatch.kt` (139 lines).
- Mechanism: single bytecode patch that finds the `Lmil;->U:Lxrl;` sget in `Lx57;->a()` (the PC Game Settings label load), scans backward up to 40 instructions for the nearest `if-eqz`/`if-nez`, and removes it. Control then falls through unconditionally into the row's `new-instance Liae` / ctor / `Lx9d;->add` sequence.
- Anchor fully structural — reuses `VibrationMenuRowPatch`'s menu-method predicate (`(Lf37;Lpo7;Lv83;I)V` + body constructs Liae rows + references Lwhl;->S). Then `sget Lmil;->U:Lxrl;` (single occurrence in the method) + backward scan for the gate. No hardcoded line numbers. Re-derivation recipe for future base bumps in the patch header.

### pre1 verification

- CI run [25895440581](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25895440581): 0 SEVERE, `"Show PC Game Settings row" succeeded` 9/9 variants.
- APK SHA-256: `1479a034e6235cd328462fdacf6b5123ff5b34ff741483863bd3a4ffbf44de41`.
- Decoded smali confirms: control now flows from `Lqs2;->y()` (line 2417) → `move-result-object v4` → `const v5, -0x3fa8c8e6` (line 2421, was line 2423 pre-patch). The `if-eqz v17, :cond_50` at the original line 2421 is gone.

### Merge

**Merge commit:** `656736e` (`--no-ff` of `feature/show-pc-game-settings` into `gamehub-604-build`).

`gamehub-604-build` HEAD now `656736e`. Artifact-only build triggered at run [25895723303](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25895723303) with version label `1.1.0-604-pcgs-merged-pre1`. No device-test gate before merge per user direction — patch is single-instruction-removal, low risk, CI + smali verified.


## 2026-05-14 — v1.2.0-604 STABLE shipped

### Release

- **Tag:** [`v1.2.0-604`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/v1.2.0-604)
- **CI run:** [25896000438](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25896000438) (workflow_dispatch with `stable=true`, `version=1.2.0-604`). 0 SEVERE; 45/45 key-patch successes (5 new patches × 9 variants). 9 APKs + `.rvp` + `.rve` attached.
- **Cert SHA-256:** `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` (unchanged from v1.1.0-604 — installs in place on top of v1.1.0-604).

### Headline changes vs `v1.1.0-604`

- **Privacy hardening stack** — 7 functional patches (Plans 4/5/8a/8b/8c/10/1) + 1 public doc (Plan 9 `PRIVACY.md`). Empirically verified: zero DNS queries to `statistic-gamehub-api.vgabc.com` during a 6.5-min full session.
- **PC Game Settings always visible in Explorer view** — single-instruction bytecode patch removes the if-eqz gate before the row.

### Device-test status at cut time

- ✅ Plan 1 (analytics-event stub) — DNS-recorder verified 2026-05-14
- ✅ Plan 10 (GMS Measurement) — `last_pause_time` frozen verified 2026-05-13
- ✅ ShowPcGameSettingsRowPatch — user-confirmed "PC Game Settings option works"
- ✅ Plans 4 / 5 / 8a / 8b / 8c / 9 — manifest/smali grep verified; standard CI verification sufficient

### Post-cut steps completed

- README.md: header version `v1.1.0-604` → `v1.2.0-604`, TOC link updated, "What's new" section rewritten for the privacy stack + PC Game Settings ungate.
- Release description: rewrote auto-generated body (stale "v1.1.0-604" content from previous template) with curated v1.2.0-604 notes via `gh release edit --notes-file`. 8 new patches added to the per-patch table with ⭐ markers.
- Privacy series memory + menu-gating memory + MEMORY.md index hook all in sync from earlier commits.
- Pre-release policy [[bannerhub-prerelease]] re-engages: from this point until the user says "stable" again, all builds default to artifact-only with `stable=false`.

`gamehub-604-build` HEAD at v1.2.0-604 cut: `195fbbd` (README docs commit prior to triggering the workflow). Tag points at the same commit per release.yml workflow behavior.


## 2026-05-15 — BannerHub V6 Lite Tier 6 spec (strip neutralized telemetry SDK trees) + reference-safety recon

### Context

User asked, in the spirit of how gamehub-lite was originally built (physical deletion of telemetry SDKs), whether more malware/spyware/telemetry can be removed from BannerHub V6 Lite. Finding: the **telemetry hunt is already complete** — the 8-plan privacy hardening series is shipped on `gamehub-604-build`, and `feature/lite-variant-tier1` branches off it, so Lite inherits every kill (Firebase Analytics, Mob Push init, Ad-ID perms, OTA, heartbeat, GMS Measurement, vgabc `/events`). The only telemetry **native lib** in 6.0.4 (`libpns`, 499 KB) is already physically deleted by Lite Tier 1. gamehub-lite's other deleted telemetry `.so`s (`libalicomphonenumberauthsdk_core`, `libumeng-spy`) do **not exist** in 6.0.4 (5.1.0 stack).

### The genuinely new lever

Our privacy series **neutralizes** telemetry SDKs (init disabled, manifest off) but **ships the classes in the dex**. gamehub-lite **deletes** them. A neutralized-but-present SDK can be re-activated by anyone who repacks the APK; a deleted one cannot. Tier 6 = convert neutralize→delete, **Lite-only**. Payoff is tamper-resistance + ethos, **not megabytes** (smali deflates to sub-MB on disk).

### Reference-safety recon (grep-verified in `gamehub_604_decompile`)

Cluster map:
- **Mob Push:** `smali_classes3/com/mob` (828) + `smali/cn/fly` (671 — Mob's renamed alias, easy to miss). ≈1,499 files.
- **Aliyun NumberAuth aggregator:** `smali_classes3/com/mobile/auth` (568) + `smali_classes3/com/nirvana` (94) + `smali/com/cmic` (15, China Mobile) + `smali_classes3/com/unicom` (4, China Unicom). ≈681 files.

Decisive finding: **the two clusters are not equally deletable.**

**Tier 6a — Mob Push (FEASIBLE, mirrors gamehub-lite).** 7 external referencers, classified:
- Plan-5 dormant no-op sites: `BaseAndroidApp.smali:29/247`, `nt5.smali:2863/3352/3360/3374/3380/3490` (Plan 5's `nt5.N(Context)` anchor — submitPolicyGrantResult / setClickNotificationToLaunchMainActivity / getRegistrationId / restartPush, deliberately left as dormant no-ops).
- Hard `.implements` glue (must rewrite, not orphan): `smali/li0.smali` `.implements Lcom/mob/pushsdk/MobPushReceiver;` and `smali_classes4/coh.smali` `.implements Lcom/mob/pushsdk/MobPushCallback;`. A class implementing a deleted interface fails verification at load → crash.
- Stray invoke-static (safe to remove line): `o1.smali` ×3 stopPush, `at0.smali:14688` setAlias, `hi5.smali:2753` stopPush, `coh.smali:165` addTags.
- Patch: `StripMobPushPatch.kt` (`use=false`). Layer A bytecode (excise 7 referencers' Mob refs + rewrite `li0`/`coh` to drop `.implements` + stub interface methods, structural anchors). Layer B optional manifest cleanup (delete dangling disabled `com.mob.*`/`cn.fly.*` components). Layer C resourcePatch dep deletes `smali_classes3/com/mob/**` + `smali/cn/fly/**`. Verify per privacy-series recipe + no `NoClassDefFoundError`/`VerifyError`.

**Tier 6b — Aliyun NumberAuth + operator SDKs (GATED — defer).** Unlike Mob, this cluster is **NOT dead code.** 17 external referencers are *live* carrier one-tap login UI: `fxo` (4068 ln, implements `TokenResultListener`), `myo` (5360 ln, implements `TokenResultListener`), `y2o` (implements `TokenResultListener`), `wsm` (active `getInstance`/`getReporter`/`setAuthSDKInfo`), `wd`/`vd`/`xd`/`zd`/`jxe`/`b1`/`p1` (manipulate `PhoneNumberAuthHelper`/`TokenRet`). Tier 1's `DisableNumberAuthPatch` stubbed only the *native* `k7e.a()` + deleted `libpns`; the Java login path is still live. Deleting these trees crashes login unless first proven unreachable under `BypassLogin` AND the ~17 glue classes' `.implements TokenResultListener` rewritten. **Deferred** because Tier 6b is login-path surgery and Lite Tiers 1–4 are still in beta device-testing for exactly the Steam/Epic login path — wrong sequencing to stack it now.

### Recommended sequencing

Ship **Tier 6a alone** as the next Lite increment (clean, low-risk, full gamehub-lite-style Mob deletion, zero feature cost). Keep **Tier 6b shelved** with this recon as the durable anchor until: (1) Lite beta clears the Steam/Epic launch gate, (2) a recon proves BypassLogin makes the `PhoneNumberAuthHelper` path unreachable, (3) the ~17 glue classes are rewritten. Full spec recorded in memory `bannerhub-revanced-lite-variant` (tier table rows 6a/6b + "Tier 6 spec" section). **Not yet implemented — awaiting user go-ahead on Tier 6a / beta merge gate.**


## 2026-05-15 — Lite branch state review + user beta-testing pass begins

### State verified against live repo (not just memory)

`feature/lite-variant-tier1` head = `895f289` (Tier 4 CI-verified, "corrected size to -34.52 MB"). **Not merged** into `gamehub-604-build` (confirmed via `git branch --merged`). Tiers 1(+2)/3/4 all CI-green; Tiers 1+3 device-confirmed; Tier 4 image-rendering device-confirmed (cover art/avatars fine, keeper). Final size locked at **−34.52 MB vs Normal** (114.54 → 78.35 MB, −31.6%). Tier 5 permanently rejected by user (Lite keeps full Steam+Epic). No newer commits beyond what memory recorded — repo and `bannerhub-revanced-lite-variant` memory are in sync.

### What changed this session

User is now **starting the beta device-testing pass** on Beta1 (`banner.hub.lite`, 78.4 MB, hand-attached to v1.2.0-604 stable release). The single open merge-gate is the **Steam/Epic login + game-launch path under Tiers 1/3/4**. Status moves from "awaiting user device test" → **"user testing in progress"**. Merge to `gamehub-604-build` and Tier 6a kickoff both remain blocked on the user's verdict from this pass. No code changes — status/tracking update only.

### Also recorded this session (separate workstream)

New PLANNED patch memory `bannerhub-revanced-firmware-update-gate`: neutralize the launch-time forced-imagefs-update gate (verified at `c4o.smali:895‑931` → `Lf4o;->a(IMAGE_FS)` vs MMKV `firmware_ver`) while leaving the manual components-menu update (`getAllComponentList`→`Lj7o;`) intact. Build trigger = next firmware release pushed to BannerHub-API, then device-test launch-on-old-firmware + manual-update-still-works. Not started.


## 2026-05-16 — Lite beta-testing pass: DiRT 3 launch-fail root-caused = Box64-path resolution bug (game-launch merge-gate blocker)

### Context
First concrete failure from the 2026-05-15 Lite beta device-testing pass (`banner.hub.lite`, the single open merge-gate = Steam/Epic login + **game-launch** path). User reported DiRT 3 fails to launch / immediately falls back to the game library. Initial hypothesis (user's): the BannerHub 3.7.x evshim/Box64 vibration regression. **Disproven.**

### Method
Side-by-side log capture of the same game (DiRT 3, gameId 131962, SD exe `/storage/6B68-39AB/Winlator/Games/DiRT 3 Complete Edition/.../dirt3_game.exe`, steamAppId 321040) on Lite `banner.hub.lite` vs **vanilla GameHub 6.0.4** (`com.miHoYo.GenshinImpact` pkg) via logcat-bridge (`getlog` ring-buffer pull + `--ls`/`--cat` root verbs; app does not persist box64/wine stderr and `pcLaunchLog` is params-only — known gotcha).

### Findings
- **Lite `:wine` (pid 12020): 15:38:21→15:38:33 (~12 s), emitted ZERO `winemu`/`wine`/`box64`/`ProcessHelper`/`services.exe`/`plugplay` tags.** Dies *before* Wine init. No tombstone / linker / `evshim:` line (silent). → back to library.
- **Vanilla `:wine` (pid 12473): ~61 s**, full boot — dbus → `winemu` → `gamepad` → SteamKit → `services.exe`/`plugplay` → loads `kernel32/kernelbase/ntdll` from `wine_proton10.0-x64-1/x86_64/lib/wine/x86_64-windows/`.
- **Decisive diff = `launchLog131962.txt` param dumps:**
  - Lite: `box64转译器路径 = ` **(EMPTY)** ; `cpuTranslatorConfig id=local_Performance, box64Path=""`
  - Vanilla: `box64转译器路径 = .../components/Box64-0.4.1-2/box64` ; `cpuTranslatorConfig id=local_Extreme, box64Path=""`
- DiRT 3 is x86_64 (`isArm64X=false`) → with no Box64 translator path the wine proc dies pre-init. Both: Proton 10 x64, DirectLaunch, same exe.
- **Box64 IS installed on Lite** — `usr/home/components/{Box64-0.4.1-2,Box64-0.4.1-fix,Box64-0.4.3,Box64-Hybrid-Bionic}` + `usr/bin/box64`. Wine DLL tree + rootfs **byte-identical to vanilla (757 files each)**. So NOT a missing/partial install.

### Root cause
**Per-game Box64-component RESOLUTION bug specific to the ReVanced Lite build.** The per-game CPU profile `id=local_Performance` (box64Path empty) is not mapped to an installed Box64 directory at launch, so the launcher passes an empty `box64转译器路径`. Vanilla's `id=local_Extreme` resolves correctly. Definitively NOT the evshim/vibration regression (box64 never spawns; zero `evshim:` tags) and NOT GFWL (dies long before game code).

### Status / next
- Confirm + workaround handed to user: explicitly select **Box64-0.4.1-2** in DiRT 3's per-game CPU translator (instead of the "Performance" preset) and relaunch → expected to boot Wine.
- Real fix lives in the Lite line's box64-path resolver / default-profile (`local_Performance`) mapping — likely a Lite-variant strip. Code dig **not yet started** (awaiting user go-ahead). This is a **game-launch merge-gate blocker** for the Lite → `gamehub-604-build` merge.
- Memory updated: `bannerhub-evshim-breaks-x86-64-box64-launches-v3-7-0-regression` (root-cause paragraph) + `bannerhub-revanced-lite-variant` (Box64-resolution-bug pointer). No code changes this session.

### UPDATE same session — root cause CONFIRMED, and it is NOT a Lite-build bug

Deeper diagnosis (user reported "custom box64 components aren't working in v6", + asked to audit app component files):
- On-device `usr/home/components/` audit: **empty (0-file) dirs** = `Box64-0.4.3`, `Box64-0.4.1-fix`, `Box64-Hybrid-Bionic`, `Fex_20260428`, `FEXCore-2603`, `FEXCore-2605`, `vkd3d-proton-3.0.1`. Working = `Box64-0.4.1-2`, `Fex-20251025`, `Fex_20260509`, `dxvk-2.3.1-async`, `vkd3d-2.12`, drivers, mono, etc.
- `curl`+`tar -tf` of release assets: working `Box64-0.4.1-2.tzst` = flat `./box64`; the 3 custom Box64 `.tzst` = `./` + `profile.json` + `box64` (Winlator `.wcp` internal layout). v6 type=1 extractor expects flat bare binary → `.wcp`-layout archive yields an empty component dir → x86_64 `:wine` dies pre-Wine-init.
- **Conclusion: this is a BannerHub-API release-asset packaging defect, NOT a Lite-build code bug, NOT the box64-path resolver, NOT evshim/vibration, NOT GFWL.** The earlier "Lite resolver / `local_Performance` mapping" hypothesis is **withdrawn** — `local_Performance` resolves fine; the binary is just absent because the archive never unpacks. The Lite line needs **no code change** for this; the fix is entirely in bannerhub-api (repackage flat + bump md5/size/url lockstep + re-upload + `npm run build`).
- Records: bannerhub-api `PROGRESS_LOG.md` `## 2026-05-16` full fix recipe; memory `bannerhub-api-box64-tzst-flat-layout` (new durable rule), `bannerhub-revanced-lite-variant` blocker entry rewritten to confirmed cause, MEMORY.md index. Merge-gate for x86_64 titles stays blocked until bannerhub-api assets are repackaged. Interim user workaround: select `Box64-0.4.1-2`.

### FIX LANDED (API side) — same session

bannerhub-api commit `983fd47` (pushed main+master): scope corrected 7→**5** on recursive re-audit (`vkd3d-proton-3.0.1` was a first-audit false-positive — extracts fine into `system32/`+`syswow64/`; `Fex_20260428` archive already correct, empty on-device for an unrelated reason — both left untouched). 5 repackaged flat + re-uploaded + `custom_components.json` md5/size/url + `version_code` 1→2 + `npm run build`. Prevention tooling shipped same commit (`scripts/wcp2tzst.sh`, `scripts/check_component_layout.sh`, ADDING_NEW_COMPONENTS.md). **Lite line unchanged (correctly — zero code needed).** Merge-gate now waits only on: GitHub Pages rebuild (~1-2 min + CDN) → user device re-test (`banner.hub.lite`, pick e.g. Box64-0.4.3 for DiRT 3, confirm Wine boots) → if green, the x86_64-launch blocker is cleared.

**VERIFIED ON DEVICE 2026-05-16:** post-propagation, `Box64-0.4.3`/`Box64-Hybrid-Bionic` extract `box64` (files=1, were 0); box64 exec'd from component dir; **Wine fully boots** (`esync up and running`, ProcessHelper Wine-debug, ntoskrnl/init_peb — native tree 100% absent pre-fix). **Packaging blocker for x86_64 game launch on Lite = CLEARED on the API side; Lite line needs no change.** Separate residual (does NOT re-block the gate): DiRT 3 is 32-bit, `onStopGame`s ~8 s with `err:wow:load_64bit_module c000007b` under x64-Proton experimental wow64 — a 32-bit-game/container/wow64 compat matter tracked apart (user retesting with regular Box64-0.4.3).

### 2026-05-16 — DiRT 3 32-bit/WoW64: wrong hypothesis withdrawn, root cause refined

Box64-0.4.3 AND Box64-0.4.1-fix retested → identical `c000007b`/experimental-wow64 stop (all 3 fixed Box64 builds verified extracting/running; packaging 100% confirmed). I had concluded "32-bit needs arm64x/FEX; box64+x64 is a dead end" — **user challenged it** (DiRT 3 runs on BannerHub 3.7.3 PuBG/`com.tencent.ig` on Proton-10-x64+Box64). Verified → **conclusion was wrong, withdrawn** (instance of always-verify-never-assume). On-device: `wine_proton10.0-x64-1`, its `x86_64/lib/wine/{i386-windows,x86_64-unix,x86_64-windows}` tree, and `box64` (19,380,160 B) are **byte-identical** in `com.tencent.ig` (3.7.3) vs `banner.hub.lite` (v6); 3.7.3 ran DiRT 3 cleanly ~26 min on that exact stack (x86_64 winebus.so mapped → 64-bit WoW64 side came up). So box64+new-WoW64 *can* run 32-bit DiRT 3. **Refined root cause:** same Wine+box64 → differentiator is **v6's app-level launch orchestration** (per-game container/prefix 131962 mis-set-up or DLL-mismatch / residue; empty launch ENV vs Winlator-Cmod's `BOX64_*`/`WINEDLLOVERRIDES`/WoW64 exports; v6 `run.exe` shim forcing experimental wow64). NOT packaging, NOT FEX-vs-box64, NOT Lite code. Decisive next step: capture a working 3.7.3 (`com.tencent.ig`) DiRT 3 launch and diff vs v6 (container/prefix/env/wow64-mode). Full detail in memory `bannerhub-revanced-lite-variant` CORRECTION block.

**RESOLVED same session — differentiator pinned.** Captured working 3.7.3 DiRT 3 (`com.tencent.ig` pid 22133): **30–40 fps, game running.** Byte-identical inputs vs v6: same `wine_proton10.0-x64-1`, `isArm64X=false`, `Box64(local_Extreme)`, same `run.exe_v16045985`, both prefixes system32+syswow64. **Only difference:** `experimental wow64 mode` = **0 occurrences** in the 3.7.3 capture (no `load_64bit_module`/`c000007b`); v6 prints it every launch → dies. ⇒ GameHub 6.0.4 (v6) forces Wine **experimental NEW WoW64**; Winlator-Cmod (3.7.3) runs the SAME Proton in **classic WoW64** → identical box64 stack, opposite result. Structural tell: v6 container 131962 = layered base+delta prefix (`.base`,`*.reg.base` — GH6.0 virtual-container overlay); 3.7.3 = flat prefix. **Root cause = upstream GameHub-6.0.4 launcher forcing experimental WoW64, which fails the 64-bit thunk under box64 for 32-bit titles.** NOT box64/Proton/config/packaging/FEX/Lite-code. Fix is app/container-side only (stop forcing experimental WoW64 / use classic-WoW64 container template). Open follow-up: locate the exact env/prefix-bootstrap knob in GH6.0.4 winemu selecting new-vs-classic WoW64. Full detail: memory `bannerhub-revanced-lite-variant` RESOLVED block.

**Follow-up investigation — narrowed to launcher ENV; exact knob still open.** Proved EVERY binary/config identical v6↔3.7.x: Proton-10-x64 wine (`ntdll.so`/`wow64*.dll`/i386 `ntdll.dll` sizes all match), box64 (19,380,160 B), **`run.exe_v16045985` byte-identical md5 `834f2c84…`, 32-bit PE (0x14c)**, both prefixes `#arch=win64`+syswow64, identical per-game config. "experimental wow64 mode" is Wine's own `err:environ:init_peb` string (in NO app code); both apps must use new-WoW64 (win64 prefix, no i386-unix) — real diff = v6 `load_64bit_module` **fails `c000007b`** vs 3.7.x **succeeds**. ⇒ sole remaining variable = the **box64+wine runtime env/cmdline** built by the two different launcher frontends (v6 GH-6.0.4 `com.xiaoji.egggame` winemu vs 3.7.x Winlator-Cmod `com/winemu`); `c000007b` under box64 = box64 can't resolve a 64-bit ELF dep → `BOX64_LD_LIBRARY_PATH`/`BOX64_PATH`/`LD_LIBRARY_PATH`/`BOX64_EMULATED_LIBS`/wine-loader-path class of env. No env-wrapper file written to tmp/container (in-process env). Exact knob needs (a) live `/proc/<wine-pid>/environ`+`cmdline` diff (bridge may block /proc; ~8 s window) or (b) read env-builder in `gamehub_604_jadx` winemu vs `bannerhub-370-pubg-decoded/.../com/winemu/core/{Wine,WineHelper$Companion,DependencyManager}`. Awaiting user pick of (a)/(b).

### Decompile dig (option b) — partial; lead raised & DISPROVEN, pivot to (a)

3.7.x env literals owned by `com/winemu/core/controller/EnvironmentController` + `com/winemu/core/trans_layer/Box64Config` + ReVanced injection `app/revanced/extension/gamehub/BhWineLaunchHelper` (src `~/bannerhub/extension/BhWineLaunchHelper.java`; patches `~/bannerhub/patches/smali_classes16/com/xj/winemu/sidebar/{BhExeLaunchListener,BhInitLaunchRunnable,BhTaskManagerFragment}.smali`). v6/`bannerhub-revanced` confirmed has **no** BhWineLaunchHelper/WINELOADER/wow64 patch — looked like the answer. **Disproven by the .java:** BhWineLaunchHelper = in-session **“Launch tab”** utility (BhTaskManagerFragment) that *reads* WINELOADER/WINEPREFIX/environ from an already-running wine proc and launches extra exes into a live session — NOT the primary game-launch / WoW64 bootstrap. Not the differentiator (always-verify: the obvious-looking helper was a red herring). Primary-launch env build is GameHub winemu core (`EnvironmentController`/`Box64Config` vs obfuscated GH-6.0.4 KMP) — RE of obfuscated KMP for the exact var is error-prone. **Net positive: BhWineLaunchHelper.java itself reads `/proc/<pid>/environ`+`/comm` → /proc/environ IS readable on this device → option (a) is feasible and now the higher-confidence path.** Recommending pivot to (a): capture & diff `/proc/<wine-pid>/{environ,cmdline}` of a working 3.7.x vs failing v6 DiRT 3 launch. Memory `bannerhub-revanced-lite-variant` updated.


### Static/bridge avenues exhausted — honest dead-end; pivot to live --ps

(a) `/proc` env diff BLOCKED: `getlog --ps` works but `getlog --cat /proc/<pid>/{environ,cmdline}` → bridge allowlist rejects /proc. BhWineLaunchHelper reads /proc only as the app's own uid (not via root bridge). (b) Static env-literal-set diff v6↔3.7.x launchers ≈ identical; sole delta `WINELOADER` is present **only in BhWineLaunchHelper.smali** (the already-ruled-out in-session Launch-tab reader), NOT in the `com/winemu/core/*` primary launcher → 2nd disproven lead, both trace back to the same red-herring helper. Conclusion: env-var NAME sets identical; differentiator is dynamic env VALUES / exec argv / process structure — not extractable via static literals or the /proc-blocked bridge; obfuscated GH-6.0.4 KMP control-flow RE proven error-prone twice. Only remaining feasible probe: live `getlog --ps` during both launches (box64/wine process tree + NAME/args + PPID chain) for working 3.7.x vs failing v6 — ground-truth for invoked-loader/argv difference, though exact env-value may stay out of reach without root shell/strace (unavailable). Honest scope set with user. Detail: memory `bannerhub-revanced-lite-variant`.


### (A)-vs-(B) RESOLVED — it's (B): BannerHub/ReVanced-introduced, NOT upstream

Decisive test: **stock GameHub 6.0.4** (`com.miHoYo.GenshinImpact`, unmodified; box64 neutralized = 0.4.3-hybrid dropped in as `Box64-0.4.1-2`; identical settings isArm64X=false / Box64-Extreme / Proton-10-x64) ran DiRT 3 (131962): `experimental wow64`/`load_64bit_module`/`c000007b` = **0 occurrences**, `isBooted=true`, **game rendered** (FPS 0→15.2→8.5 over ~5 s), `Idle→HandleByDestroy normalExit=true` clean exit (user-closed ~19 s). Corroborated by earlier cap2 stock run (isBooted=true, 44 s, normalExit). ⇒ stock 6.0.4 runs DiRT 3 on the EXACT stack that makes `banner.hub.lite` die at c000007b. **Conclusion: the WoW64/c000007b failure is introduced by the `bannerhub-revanced` (GameHub-6.0.4 ReVanced) patch layer — NOT a stock-GameHub-6.0.4 upstream defect, NOT Box64/packaging/Proton.** Not an upstream bug to report (also: closed-source XiaoJi, reporter is an auth-bypassing fork — impractical regardless). Earlier offhand "vanilla also died" was imprecise — stock was a normal boot+exit, never the crash. **Fix scope now = find which bannerhub-revanced patch breaks the winemu launch/container/env.** Working refs (boot DiRT 3 Wine, no c000007b): stock GH-6.0.4 (`com.miHoYo.GenshinImpact`) + BannerHub-3.7.x (`com.tencent.ig`); sole failing build = `banner.hub.lite`. Next: diff stock-6.0.4 vs banner.hub.lite launch path; suspects = patches touching winemu launch / layered container provisioning / env / stripped class/component (privacy-hardening, login-bypass, menu-injection, Lite strips). Full detail: memory `bannerhub-revanced-lite-variant`.


### Correction — stock 6.0.4 also bounces; TWO distinct problems

User observed stock `com.miHoYo.GenshinImpact` DiRT 3 also "launched, started, immediately exited to library." Re-read pid 6118: Wine booted → engine Running+GameLoadComplete → **DXVK presented (960x540, FPS~15)** → 8× WindowRealizedCallback + onWindowStop/StartPresent churn → `winemu Client disconnected` (guest exe self-exited) → Idle/normalExit→onStopGame→library (~14 s). Prior "stock runs it fine" was OVERSTATED — stock renders briefly then DiRT 3 self-exits; not playable. **Two separate problems:** (1) `c000007b` WoW64 pre-Wine death = ONLY `banner.hub.lite`, BannerHub-V6/ReVanced-introduced (verdict (B) stands; not upstream); (2) DiRT 3 self-exits after ~10 s render = present in **stock GH-6.0.4 too** = a GH-6.0.x-generation issue, absent only on BannerHub-3.7.x/GH-5.x (sustained 30-40 fps). Fixing #1 only brings banner.hub.lite to parity with stock (launch→render→exit), NOT playable; #2 is a separate GH-6.0.x-vs-5.x regression (suspect DiRT 3 GFWL/xlive or 6.0 engine/window handling — no explicit GFWL line captured). Only sustained-playable ref = BannerHub-3.7.x. Detail: memory `bannerhub-revanced-lite-variant`.


### PROBLEM 1 CULPRIT FOUND — VibrationPatch LD_PRELOAD libevshim (the v3.7.0 regression, unported)

Patch-culprit diff: stock GH-6.0.4 (`com.miHoYo.GenshinImpact`) vs `banner.hub.lite` are byte-identical (Proton/wine .so sizes, `run.exe` md5 `834f2c84c35396e35619db9abb24a217`, embedded files, container prefix overlay incl. `#arch=win64`+`.reg.base`, box64) ⇒ culprit = a code patch, not runtime/API/container/packaging. **Culprit: `patches/src/main/kotlin/app/revanced/patches/gamehub/vibration/VibrationPatch.kt` Hook 4 (~line 155) "ENV_BUILDER.a(...)V — prepend libevshim.so to LD_PRELOAD"** — injects 13 smali insns into GameHub-6.0.4's Wine env-builder (`dg5`) to prepend `<nativeLibraryDir>/libevshim.so` to `LD_PRELOAD` on every launch; `VibrationLibPatch.kt` ships `lib/arm64-v8a/libevshim.so`. This IS the v3.7.0 evshim/box64 regression (memory `bannerhub-evshim-breaks-x86-64-box64-launches-v3-7-0-regression`): libevshim LD_PRELOAD destabilizes box64/WoW64 → here manifests as `experimental wow64`→`err:wow:load_64bit_module c000007b` pre-Wine. Stock has no VibrationPatch ⇒ no preload ⇒ Wine boots + DXVK renders (Problem 1 absent). **The preload-free winebus on-disk fix shipped in BannerHub 3.7.4 (merge `9d9a62821`, ref `reference_gamehub_vibration_fix_preloadfree`) was never ported to bannerhub-revanced** — gamehub-604-build VibrationPatch still uses old LD_PRELOAD-libevshim. Per-game vibration toggle does NOT disable it. Closes user's turn-1 question: banner.hub.lite DiRT3 failure = the same vibration/evshim issue, masked earlier by the (now-fixed) Box64 packaging bug. **FIX:** branch off gamehub-604-build (per branch-per-patch rule); rip out Hook-4 LD_PRELOAD + libevshim ship; reimplement preload-free (static winebus.so SDL-duration disk patch, aarch64+x86_64, à la TideGear PR#91 / BannerHub 3.7.4) — see memory `project_bannerhub_revanced_vibration`. Problem 2 (stock GH-6.0.x DiRT3 self-exits after ~10 s render) separate/secondary, not this. Detail: memory `bannerhub-revanced-lite-variant`.


### Recon: TideGear/GameHub-Vibration-Fix adoption (investigate-only)

User relayed explicit dev permission to use https://github.com/TideGear/GameHub-Vibration-Fix for our 6.0.4. Cloned + inspected. Repo = canonical **preload-free** fix, README explicitly **targets stock 6.0.4** (our base). Artifacts: `extension/BhVibrationController.java` (in-process winebus.so disk-patcher: `ensureWinebusDurationPatchOnce(Context)` + `patchWinebusDurationFile`, AtomicBoolean gate, aarch64 `ldur w3,[x29,#-0x14]`→`mov w3,#-1` / x86_64 11-byte clang window → `or ecx,-1`, `winebus_dump_x86_64.so` miss-fallback), `BhVibrationSettingsActivity.java`, `scripts/apply_vibration_patches.py` (4 smali hooks, ProGuard `ab8`=Physical/`bg5`=envbuilder/`ps2.I0`=join). **1:1 structural match with our `VibrationPatch.kt`** (same 4 hooks, same pkg `Lcom/xj/winemu/vibration/BhVibrationController;`, same anchors `Lab8;`/`Lbg5;`/`Lps2;`). Hooks 1-3 identical; **only Hook 4 differs** — ours prepends libevshim.so to LD_PRELOAD (the bug), TideGear calls `ensureWinebusDurationPatchOnce(ctx)` at the same bg5 env-builder site (the fix, no LD_PRELOAD). **→ Answer to user's "1 patch not 2": YES, it's a REWORK of the existing single VibrationPatch, not a second fixer.** Plan (NOT yet done): branch off gamehub-604-build → in VibrationPatch.kt swap Hook-4 libevshim/LD_PRELOAD block for the `ensureWinebusDurationPatchOnce` invoke → delete VibrationLibPatch.kt (stop shipping libevshim.so) → replace extension Bh*.java with TideGear's (attribution kept). Precedent = BannerHub 3.7.4 did identical for Winlator line (PR #91). Caveats: fixes Problem 1 only (→ stock-6.0.4 parity; unblocks all x86_64/32-bit titles w/o Problem 2); Problem 2 (DiRT3 self-exit, in stock too, not preload-caused) separate; ignore repo test keys + login-bypass script; verify base-APK R8 map at impl. Detail: memory `tidegear-gamehub-vibration-fix-preload-free-winebus-patch-reference`.


### IMPLEMENTED — preload-free vibration rework (branch `fix/vibration-preload-free`)

Branched off `gamehub-604-build` @ `9fa3c53`. Single coherent rework (NOT a 2nd patch):
- **`extensions/.../vibration/BhVibrationController.java`**: ported TideGear's preload-free disk-patcher verbatim (constants block + `ensureWinebusDurationPatchOnce`/`ensureWinebusDurationPatch`/`scanWinebusFiles`/`patchWinebusDurationFile`/`patchAarch64Sites`/`patchX86_64Sites`/`collectWildcardHits`/`dumpForOfflineAnalysis`/`readElfMachine`/`startsWith`/`collectHits`/`indexOf`) + 2 imports (`java.io.IOException`,`java.io.RandomAccessFile`). Verified zero name collisions; brace balance 201/201; existing onRumble/dispatchToController/onStop + settings/menu API untouched (BhMenuRowClick / BhVibrationSettingsActivity unchanged → menu patches unaffected). Attribution comment added.
- **`VibrationPatch.kt`**: Hook 4 swapped from libevshim `LD_PRELOAD` `addInstructionsWithLabels` block → 2-instruction `addInstructions` (`iget-object v13, v0, ENV_BUILDER->a:Landroid/content/Context;` + `invoke-static {v13}, BhVibrationController->ensureWinebusDurationPatchOnce(Landroid/content/Context;)V`) at the same join-setup anchor (no labels/branch; v13 consumed before join setup re-inits it). Dropped `vibrationLibPatch` from `dependsOn`; removed now-unused imports (`ExternalLabel`/`getInstruction`/`addInstructionsWithLabels`); rewrote description. Hooks 1–3 unchanged.
- **Deleted**: `VibrationLibPatch.kt` (stops shipping `libevshim.so`), `native/evshim/{CMakeLists.txt,evshim.c}` (dead).
- **`release.yml`**: removed the "Build libevshim.so" NDK step; rewrote 2 release-notes blocks to describe preload-free.
- **`README.md`**: rewrote the vibration + (removed) native-shim sections to preload-free.
Only stale residue: a one-line *comment* in `icon/ChangeAppIconPatch.kt:97` ("same trick as VibrationLibPatch") — harmless, left as-is.
Net: ONE working `PC-accurate vibration` patch, preload-free, rumble retained, no libevshim/LD_PRELOAD/extra-mapping. Fixes Problem 1 (c000007b launch death) for all x86_64/32-bit titles; brings banner.hub.lite to stock-6.0.4 parity. Problem 2 (DiRT3 self-exit, stock-6.0.x too) separate/unaffected. NOT locally built (per CI-only rule) — push branch → CI build is the validator. Refs: memory `tidegear-gamehub-vibration-fix-preload-free-winebus-patch-reference`, `bannerhub-revanced-lite-variant`.

**CI branch-compile GREEN** — run [25974552956](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25974552956) `Build pull request` on `fix/vibration-preload-free` = success. Confirms the ported TideGear disk-patcher Java + reworked VibrationPatch.kt compile, and that deleting VibrationLibPatch/native/evshim + the release.yml edits didn't break the patch bundle. NEXT (user-driven): trigger a Release build off this branch for a testable `banner.hub.lite` APK → device-test DiRT 3 + other x86_64/32-bit titles (expect Problem 1 cleared, rumble retained). Not yet merged to gamehub-604-build (per branch-per-patch + await device confirmation).

**Release build GREEN (artifact-only)** — run [25974755558](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25974755558), `version=1.1.0-604-vibpf-pre1`, all 9 variants patched success, `Create GitHub Release` skipped (pre-release policy). Confirms edited `release.yml` builds end-to-end without the libevshim NDK step and the preload-free VibrationPatch + ported extension apply cleanly to the real GH-6.0.4 base across every variant. APK pulled to device: `/storage/emulated/0/Download/BannerHub-vibpf-pre1-Normal-GHL.apk` (114,551,047 B; Normal-GHL variant — branch is off gamehub-604-build so NO Lite size-strips, but FULL preload-free vibration fix). Awaiting user device test: DiRT 3 + other x86_64/32-bit titles boot (no c000007b) + rumble incl. sustained hold; then merge `fix/vibration-preload-free`→`gamehub-604-build`.


## 2026-05-16 — SESSION STATE SUMMARY (consolidated checkpoint)

Long investigation chain, resolved end-to-end. For a future session, the state in one place:

**Reported symptom:** DiRT 3 (and x86_64/32-bit titles) fail to launch on `banner.hub.lite` (BannerHub V6 Lite), bounce to library.

**Resolution chain (all root-caused, not guessed):**
1. **BannerHub-API packaging bug (FIXED, shipped):** custom Box64/FEX `.tzst` were repackaged from `.wcp` with `./`+`profile.json` layout → extracted EMPTY on v6. Repacked flat (3 Box64 + 2 FEXCore), re-uploaded, catalog bumped, `npm run build`; converter+validator+docs added. Commit `983fd47` on `bannerhub-api` main+master. Device-verified: components now extract, Wine boots.
2. **Residual `c000007b` root cause (root-caused):** with box64 fixed, DiRT 3 still died `experimental wow64`→`err:wow:load_64bit_module c000007b` pre-Wine. Proved NOT box64/Proton/config/packaging/FEX, NOT upstream — **stock GH-6.0.4 (`com.miHoYo.GenshinImpact`, unmodified) launches DiRT 3 fine** on the byte-identical stack. Patch-culprit diff → **`VibrationPatch.kt` Hook 4 (libevshim.so → LD_PRELOAD)** = the v3.7.0 evshim regression, unported to the ReVanced line.
3. **Preload-free rework (IMPLEMENTED, CI+Release green, awaiting device test):** branch `fix/vibration-preload-free` off `gamehub-604-build`@`9fa3c53`. Ported TideGear's on-disk winebus duration patcher into `BhVibrationController.java`; Hook 4 → `ensureWinebusDurationPatchOnce(ctx)`; deleted `VibrationLibPatch.kt`+`native/evshim/`+CI libevshim step; README/release-notes refreshed. ONE coherent patch, rumble retained, settings/menu API untouched. Commits `5fe95a8`/`2d2b5a0`/`72630f5`. CI branch-compile `25974552956` ✅; Release `25974755558` ✅ (artifact-only, 9 variants). Test APK: `/storage/emulated/0/Download/BannerHub-vibpf-pre1-Normal-GHL.apk`.

**NEXT:** device-test that APK → DiRT 3 + another x86_64/32-bit title boot (no c000007b) + controller rumble incl. sustained hold. If green → merge `fix/vibration-preload-free`→`gamehub-604-build` (branch-per-patch). 

**KNOWN SEPARATE — Problem 2 (NOT addressed, NOT this fix):** DiRT 3 self-exits ~10 s after it renders — reproduces on **stock GameHub 6.0.4 too** (a GH-6.0.x-generation issue; only the 5.x-lineage BannerHub 3.7.x sustains DiRT 3). The preload-free fix brings `banner.hub.lite` to stock-6.0.4 parity (launch+render), not full DiRT 3 playability. Pursue separately if needed (suspect GFWL/xlive or 6.0 engine/window handling).

Memory updated: `bannerhub-revanced-vibration-port-feature-vibration-branch` (CURRENT STATE block prepended), `tidegear-...-preload-free...` (IMPLEMENTED), `bannerhub-revanced-lite-variant`, MEMORY.md index.


## 2026-05-16 — MERGED to gamehub-604-build

Device test PASSED (user-confirmed: DiRT 3 boots, no c000007b; rumble incl. sustained hold works). `fix/vibration-preload-free` (`dc30275`) merged into `gamehub-604-build` via **`--no-ff` merge commit `72bb018`** (identity The412Banner, no Claude trailer), pushed `9fa3c53..72bb018`. Brought: preload-free `VibrationPatch` Hook 4 + ported winebus disk-patcher in `BhVibrationController.java`; deletions of `VibrationLibPatch.kt`, `native/evshim/{evshim.c,CMakeLists.txt}`, CI libevshim step; README/release-notes refreshed. Clean merge (604-build was ancestor of the fix branch; no conflicts). `fix/vibration-preload-free` retained on origin as history. Post-merge branch-compile CI triggered for sanity.

**FOLLOW-UP REQUIRED (user-flagged):** `feature/lite-variant-tier1` was branched off gamehub-604-build BEFORE this rework → still carries the old libevshim LD_PRELOAD VibrationPatch and will hit the same c000007b. Must bring `72bb018` into the Lite branch (merge/rebase gamehub-604-build → feature/lite-variant-tier1) and rebuild the Lite variant before any new `banner.hub.lite` Lite build. Until then Lite builds still have the broken vibration patch.


## 2026-05-16 — v1.3.0-604 STABLE shipped (9 full APKs + GitHub Release)

Dispatched `release.yml` on `gamehub-604-build` with `version=1.3.0-604` `stable=true` → run [25977976554](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25977976554) ✅ → 9 full variant APKs + GitHub Release `BannerHub v6 1.3.0-604` (tag `v1.3.0-604`). Built off `ed355d3` (preload-free vibration in place; matrix unchanged — canonical 9 full variants incl. `Normal-GHL`).

**Dual-build flow (first use):** the separate, never-merged `feature/lite-variant-tier1` branch (@`13ac017`) was dispatched in parallel with `version=1.3.0-604` `stable=false` → run [25977995330](https://github.com/The412Banner/bannerhub-revanced/actions/runs/25977995330) ✅ → 9 Lite APKs as Actions artifacts (its `release` job auto-skipped by the `stable==true` gate). The 9 Lite APKs (≈78.3 MB each) were manually attached to the v1.3.0-604 release via `gh run download … -p 'apk-*-Lite'` + `gh release upload`. **Release now carries 21 assets** = 9 full + 9 Lite + 3 `.rvp`.

Release-description edit (user request): the dedicated `### ✨ What's new in 1.1.0-604` section was removed from the notes (the workflow's notes template still carried it); 0 occurrences remain. Inline `⭐ *new in 1.1.0-604*` patch-table provenance markers left as-is (out of scope).

**Resolves the prior FOLLOW-UP:** the Lite branch already carries the preload-free vibration (`72bb018` merged in via `1b567ae`); these Lite APKs are libevshim-free.

**Stable-release-checklist note (NOT yet done — awaiting user direction):** README on this branch still shows "Latest stable: v1.2.0-604" + a "What's new in v1.2.0-604" section + ToC entry; release notes are still the stale 1.1.0/1.2.0 template (only the 1.1.0 section was pulled per request). README bump + a real 1.3.0-604 "What's new" + a release-notes rewrite were not requested and were not done unilaterally.


## 2026-05-16 — v1.3.0-604 stable-release-checklist completed

Followed up the prior entry's flagged item (user approved). Release notes on the v1.3.0-604 GitHub Release rewritten via `gh release edit --notes-file`: added a real **✨ What's new in 1.3.0-604** section (preload-free vibration / x86_64 c000007b fix + 9 Lite APKs), added a **🪶 Lite variants** table (9 rows, same-pkg replace-on-install semantics), fixed the stale `1.1.0-604` naming/versioning examples → `1.3.0-604`, stripped all 5 stale `⭐ *new in 1.1.0-604*` inline tags from the patches table (verified 0 remain), added a "Lite size-reduction strips" patch-table row + a full-vs-Lite Notes bullet, intro now states 18 APKs.

README on `gamehub-604-build` bumped (`c61f44d`, pushed `317fef0..c61f44d`): latest-stable badge link + ToC entry + What's new section all → v1.3.0-604; new section covers the two headline changes and demotes the v1.2.0-604 privacy-stack/PC-Game-Settings detail to a concise carryover note (per the established "latest release only in README" convention; full history stays on the release pages).


## 2026-05-16 — README accuracy pass + vibration-fix credits (v1.3.0-604)

README (`gamehub-604-build`, `313caa6`): "What it does" now says preload-free vibration + notes the per-variant ~34.5 MB Lite counterpart; new **🪶 Lite variants** subsection under Variants (strip list, same-pkg replace-on-install, built from never-merged `feature/lite-variant-tier1`); `PC-accurate vibration` patch heading flags the v1.3.0-604 preload-free rework; Credits row rewritten — TideGear PR #80 (original port) + PR #91 (preload-free winebus rework, used with explicit permission) + a new **GameNative** row (verified upstream `github.com/utkarshdalal/GameNative`, PR #1214 lineage; my first-pass `github.com/GameNative` guess was wrong — verified via the local GameNative checkout's `upstream` remote per the always-verify rule).

Release notes (v1.3.0-604 GitHub release, via `gh release edit`): added a **🙏 Credits** section before Notes crediting the controller vibration fix — TideGear/GameHub-Vibration-Fix (PR #80 + #91, with permission) + GameNative (utkarshdalal, PR #1214 lineage), linking the README Credits for the full list.


## 2026-05-16 — v1.3.0-604 release-notes trims (user-requested)

Two further `gh release edit` passes on the v1.3.0-604 GitHub release (notes only; no APK/repo change):
1. Removed the entire **🔐 Stable signing — in-place updates from this release onward** section (header + 3 paragraphs). The brief "(stable keystore unchanged — installs in place)" parenthetical in the What's new intro, the **Signing** bullet in Notes, and the README signing callout were intentionally left as-is (out of scope).
2. Removed the **body** of the **✅ Steam game launches work end-to-end** section (the "v1.0.0-602's release notes…" paragraph + the ⚠ `xtask install components failed` blockquote) but **kept the header line** (with the ✅ check mark) per request — it now sits directly above `### Source`.

Final published v1.3.0-604 notes structure: title intro → ✨ What's new in 1.3.0-604 → ✅ Steam game launches work end-to-end (header only) → Source → Naming/versioning → Variants (full + 🪶 Lite tables) → Patches applied (details) → 🙏 Credits → Notes. No stale `1.1.0` references or `⭐ new in 1.1.0-604` tags remain.


## 2026-05-17 — Per-game GPU spoof feature (branch `feature/gpu-spoof-menu`)

### Motivation
Crysis 2 (CryEngine 3) shows *"Unsupported video card detected!"* then crashes after OK. The engine reads the adapter as `"GameFusion Driver" [vendor id = 0x5143, device id = 0x43051401]` — 0x5143 is Qualcomm (the Adreno), which CryEngine's GPU whitelist (NVIDIA 0x10DE / AMD 0x1002 / Intel 0x8086 only) rejects. Verified from a user screenshot. DXVK's `dxgi/d3d9/dxvk.customVendorId/customDeviceId` overrides exactly these fields.

### Feature — "GPU Spoof" per-game menu row + dialog
Direct structural clone of the vibration feature (4 patches + 3 extension classes), so it inherits the proven menu-injection trail from `[[bannerhub-revanced-menu-injection-playbook]]`.

New extension classes (`extensions/gamehub/.../com/xj/winemu/gpuspoof/`):
- `BhGpuSpoofController.java` — per-game persistence in stock `pc_g_setting<gameId>` SharedPreferences under `bh_gpuspoof_*` keys (export/import compatible, mirrors `BhVibrationController`); global fallback `bh_gpuspoof_prefs`. `applyGpuSpoof(EnvVars)` writes `<filesDir>/bh_gpuspoof_dxvk.conf` (dxgi+d3d9+dxvk customVendorId/DeviceId/DeviceDesc) and force-sets `DXVK_CONFIG_FILE` via reflection. Mode 0 = Off = stock, zero regression.
- `BhGpuSpoofSettingsActivity.java` — dialog: Off / GTX 1060 / GTX 1080 / RX 580 / UHD 630 / Custom (hex vendor+device+name fields shown for Custom). Saves immediately.
- `BhGpuSpoofMenuRowClick.java` — Function1/Function0 proxies + 3 row-append helpers + `maybeResolveCustomLabel`, mirroring `BhMenuRowClick`.

New patches (`patches/.../gamehub/gpuspoof/`):
- `GpuSpoofMenuRowPatch.kt` — 3 injections (Lx57;->a More Menu, ted.f, Lpzc;->j0) + Lxd3;->l1 resolver short-circuit (distinct `:bh_gpuspoof_resolve_fallthrough` label so it coexists with the vibration patch's index-0 head block).
- `GpuSpoofMenuLabelPatch.kt` — appends `bh_gpuspoof_label` = `GPU Spoof` (b64 `R1BVIFNwb29m`) to features.home CVR.
- `GpuSpoofManifestPatch.kt` — registers the Activity (exported=false).
- `GpuSpoofPatch.kt` — launch plumbing. Hooks `Lbg5;->a` (.locals 35, env builder). **Anchor verified by reading bg5.smali:** the app's sole `DXVK_CONFIG_FILE` write is at smali ~2472 inside the `:cond_15` (max-device-memory) conditional; `EnvVars` receiver is stably `v11`; "last EnvVars.a" is a trap (smali ~3099, after the main `return-void` at 3078, in a conditional tail). Correct anchor = the unconditional `ZINK_DESCRIPTORS` set right after `:cond_16` (past both the DXVK and MANGOHUD conditional merges) — inject `invoke-static {v11}, applyGpuSpoof` after it so our `DXVK_CONFIG_FILE` always wins.

Branched off `gamehub-604-build` per branch-per-patch workflow. Pushed (`ec5bc11`).

**CI:** `build_pull_request.yml` run 25994438414 → `BUILD SUCCESSFUL in 1m 34s` (2026-05-17). This workflow only runs `./gradlew build` — it confirms the Kotlin patches + Java extension **compile cleanly** but does NOT run revanced-cli, so the `firstMethod {}` anchors resolving in the real 6.0.4 APK and the smali injections applying are **NOT yet verified** (per the playbook anti-pattern: patcher SEVERE failures wouldn't even fail a CI run, and this run didn't invoke the patcher at all). **Patch-apply VERIFIED:** artifact-only `release.yml` dispatch (run 25994546875, version `1.3.0-604-gpuspoof-pre1`, stable unticked → 9 full-variant APK artifacts, no GH Release) → all 4 gpuspoof patches report `succeeded` **36/36 (4 patches × 9 variants)**, zero SEVERE/WARNING/skip. Confirms the `firstMethod {}` anchors resolve in the real 6.0.4 APK and every smali injection applies — including the `GpuSpoofPatch` `bg5` plumbing hook (ZINK_DESCRIPTORS anchor + v11 EnvVars register), all 3 menu-row injections, and the Lxd3;->l1 resolver short-circuit coexisting with the vibration patch. 9 APKs at run 25994546875 artifacts (`BannerHub-V6-1.3.0-604-gpuspoof-pre1-Patched-{Normal,Original,AnTuTu,alt-AnTuTu,Genshin,Ludashi,Normal-GHL,PuBG,PuBG-CrossFire}.apk`, ~114 MB).

Remaining: device test (Crysis 2 → GPU Spoof row → GTX 1060 → relaunch). Not merged. NB: user's daily driver is `banner.hub` (V6 **Lite**, never-merged `feature/lite-variant-tier1`); this branch builds the 9 **full** variants (different package) — to test in-place on their install, gpuspoof must later be brought into the Lite branch (as the preload-free vibration fix was). For now: sideload a full variant alongside Lite to validate the feature itself.

### 2026-05-17 — ANR-on-launch fix (strip l1/pzc/CVR from gpuspoof)

Device test of `…-Patched-alt-AnTuTu.apk` (pkg `com.antutu.benchmark.full`): "isn't responding" on launch. Verified via full (non-pkg-filtered) logcat: `ActivityTaskManager: Force finishing activity com.antutu.benchmark.full/com.xiaoji.egggame.MainActivity` → `ActivityManager: Killing … user request after error` → `Window{Application Not Responding}`. **ANR on MainActivity, not a crash** — crash buffer had no entry for the pkg (only stale 05-16 com.tencent.ig), no native tombstone, gpuspoof classes never logged.

Root-cause hypothesis (strong; exact ANR main-thread stack unobtainable — logcat-bridge blocks `/data/anr/`): the only gpuspoof code on the startup path was `GpuSpoofMenuRowPatch`'s `Lxd3;->l1` resolver short-circuit — uncached reflection (`Class.forName`+`getDeclaredField`+`get`) on the **main thread for every Compose string resolve**, stacked as a *second* such head-block atop the in-production vibration patch's identical one. First capture showed the freeze starting immediately after that resolver fired on the splash dialog. Vibration's single hook ships fine → the doubling is the delta.

Fix (also a sound scope cut): removed Injection 3 (library-list popup `Lpzc;->j0`/`Lz4e`), the `Lxd3;->l1` hook, `GpuSpoofMenuLabelPatch` (+ its `dependsOn`), and `BhGpuSpoofMenuRowClick.appendLibraryPopupRow`/`maybeResolveCustomLabel`/`LABEL_KEY`. Kept Injection 1 (More Menu `Lx57;->a`) + Injection 2 (tile popup `ted.f`) — both raw-String labels, **no l1, zero startup cost** — which already cover the per-game GPU-settings entry (where the Crysis 2 fix is reached). Net: gpuspoof now contributes nothing to MainActivity cold start. If the ANR persists after this build, gpuspoof is exonerated and it's a variant/device cold-start issue (next: stock-alt-AnTuTu comparison build).

### 2026-05-17 — pre2 device test: ANR fixed; spoof not applied → DXVK_CONFIG inline (pre3)

pre2 (`com.antutu.benchmark.full`): **app launches** (ANR fix confirmed — the l1 double-hook was the cause). Row appears in the More Menu (Injection 1; tile-popup Injection 2 not observed but More Menu is the relevant entry). Crysis 2 still showed "Unsupported video card / GameFusion 0x5143".

On-disk + logcat verification: `BhGpuSpoof: GPU spoof active: 8086:3e92 (Intel UHD 630)` logged at Wine launch; `bh_gpuspoof_dxvk.conf` written; `bh_gpuspoof_prefs.xml` mode=4. So our entire chain (bg5 hook → conf write → `EnvVars#a` reflection) **succeeds** — the failure is downstream. `pc_g_setting3939` shows the container = **Proton 10 ARM64EC + FEX + DXVK-2.4.1 + Turnip** (DXVK fully supports customVendorId). Root cause: the conf was written to `ctx.getFilesDir()` (`/data/user/0/<pkg>/files/...`) which is **not visible inside the Proton/FEX guest filesystem**, so DXVK could never open the file (GameHub writes its own dxvk.conf to a guest-visible dir `v0` it computes; we bypassed that).

Fix (`BhGpuSpoofController`): switch primary mechanism to DXVK's **inline `DXVK_CONFIG` env var** (DXVK ≥2.1; ';'-separated entries) — no file, no path/mount-namespace dependency, rides the same env channel as the working `DXVK_HUD`/`DXVK_ASYNC`. File + `DXVK_CONFIG_FILE` kept as belt-and-braces fallback. Diagnostic log now prints the full `DXVK_CONFIG` string. Rebuild = pre3. If Crysis 2 STILL sees 0x5143 after this, next hypothesis = the title's D3D9 goes through wined3d (no customVendorId) rather than DXVK d3d9 — would need the DXVK log (enable DXVK_LOG_LEVEL) to confirm.

### 2026-05-17 — pre4: library-popup parity (O(1) resolver) + inline DXVK_CONFIG

User requested GPU Spoof in the library per-game popup like PC Vibration. Re-added Injection 3 (`Lpzc;->j0`/`Lz4e`), `GpuSpoofMenuLabelPatch` (CVR `bh_gpuspoof_label`), the `Lxd3;->l1` resolver short-circuit, and `appendLibraryPopupRow` — **without** re-triggering the ANR: `maybeResolveCustomLabel` is now O(1) (tdi.a `Field` resolved once into a `volatile` static; every call = one `Field.get` + `String.equals`, no per-call `Class.forName`/`getDeclaredField`). pre3's inline-`DXVK_CONFIG` spoof fix is also in this build. pre4 (run 25995687479) green: all 4 patches incl. label resource apply 9/9, zero SEVERE. APK → `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-gpuspoof-pre4-Patched-alt-AnTuTu.apk`. Retest matrix: (1) still launches (O(1) resolver didn't regress ANR), (2) row in library popup like PC Vibration, (3) Crysis 2 accepts spoofed GPU via inline DXVK_CONFIG.

### 2026-05-17 — pre4 ANR'd; DECISION: ship More-Menu-only (revert parity)

pre4 device test: ANR again. `ActivityManager` Reasons logged = `executing service …steam.cloud.SteamCloudSaveService` and `No response to onStartJob` — stock background components (which dispatch on the **main thread**), no "input dispatching timed out". Empirical isolation across builds is unambiguous: **l1 hook present (pre1 uncached / pre4 O(1)) → ANR; l1 hook absent (pre2) → launches**; user confirms other BannerHub v6 builds launch fine on this device. So a *second* `Lxd3;->l1` `addInstructions(0,…)` head-block stacked on the vibration patch's breaks startup regardless of `maybeResolveCustomLabel` cost (cost was a red herring; mechanism consistent with the [[revanced-trailing-label-footgun]] — the trailing-`:label`-at-index-0 workaround only holds when it's the *sole* index-0 injection; the stock service/job is just the ANR *victim* of the disrupted main thread).

User decision: **GPU Spoof = More Menu + tile popup only** (no library-list popup, no l1). Full PC-Vibration-parity would need a single shared l1 resolver (touching the shipping vibration patch — regression risk) and is dropped. Reverted the parity commit (`git revert 1e1fabd` → `b9dbf05`): GpuSpoofMenuLabelPatch deleted, Injection 3 + l1 + appendLibraryPopupRow/maybeResolveCustomLabel gone — **inline-`DXVK_CONFIG` Crysis 2 fix retained**. Net state = the working menu + the Crysis 2 fix, combined (never tested together before). Rebuild = pre5. Retest: (1) launches (no ANR), (2) Crysis 2 accepts spoofed GPU.

### 2026-05-17 — pre5: launch OK + spoof set, but Crysis 2 unchanged → pre6 DXVK-log diagnostic

pre5 device test: **app launches clean (no ANR)** — More-menu-only is stable. Spoof fully applied (`GPU spoof active 10de:1c03 (GTX 1060)`, DXVK_CONFIG + DXVK_CONFIG_FILE + conf file all confirmed on disk). Crysis 2 still "GameFusion 0x5143", no DX11/bin64 exe (SteamRIP Maximum Edition, bin32 only). **Two earlier inferences disproven by inspecting prefix `files/usr/home/virtual_containers/3939`:** `syswow64/d3d9.dll` → symlink to **DXVK-2.4.1-gplasync d3d9.dll**, so Crysis 2 DX9 runs on **DXVK d3d9, not wined3d**; and that symlink target is a `/data/user/0/<pkg>/files/...` path DXVK loads from, so that path **is** guest-visible (the pre3 file-path-invisibility theory was wrong too). DXVK d3d9 is the renderer, path reachable, spoof set — yet not applied. Real remaining suspects: env vars not propagating into the Proton-ARM64EC+FEX game process, or DXVK_CONFIG format. pre6 (`BhGpuSpoofController`): also set `DXVK_LOG_LEVEL=info` + `DXVK_LOG_PATH=<filesDir>` via the same EnvVars hook — DXVK log presence/absence is the conclusive test (propagation works vs not) and shows what DXVK does with our config. Stop inferring the failure layer; get the log.

### 2026-05-17 — pre7: full GameNative/Winlator GPU catalog + cascading-spinner UX

User asked to extend the preset list "as many as possible like GameNative/Winlator". Source identified locally (no APK decompile needed): `GameNative/app/src/main/assets/gpu_cards.json` — the same file Winlator ships. 289 entries → 286 after dropping 3 with overflowed (>0xFFFF) deviceIDs (`GeForce Go 7300/8200/GTX 860M`). Added a curated modern set the ~2021-era upstream list predates (RTX 30/40, RX 6000/7000, Intel Arc/B-series) → **313 cards total: 155 NVIDIA / 72 AMD / 86 Intel**.

- **New generated `BhGpuCards.java`** — `String[][][] CARDS` grouped by vendor, name-sorted, dup names suffixed ` (0xXXXX)`; helpers `locate()` (restore selection from stored hex) and `modelNames()`.
- **`BhGpuSpoofController`** — modes collapsed `OFF/GTX1060..UHD630/CUSTOM(5)` → `MODE_OFF=0 / MODE_SPOOF=1 / MODE_CUSTOM=2`; `PRESETS[][]` deleted; SPOOF & CUSTOM both apply the stored `bh_gpuspoof_{vendor,device,name}` triplet (storage/export keys unchanged).
- **`BhGpuSpoofSettingsActivity`** — Option 1 cascading-spinner UX: Mode (Off/Spoof a GPU/Custom) → Vendor spinner (NVIDIA/AMD/Intel + counts) → Model spinner repopulating per vendor; reopen restores via `locate()`. Native Spinner popups only — no ListView/eager inflation, ANR-safe per the pre1–pre4 lesson.

Zero patcher/CVR/asset/anchor change: the 3 patches reference only the menu row + activity (verified no preset coupling); `applyGpuSpoof(Object)` signature unchanged. All 3 Java files lint-compile clean vs android-34 android.jar (exit 0). **Caveat:** modern-card device IDs are well-known refs — sanity-check vs `pci.ids` before any *stable* ship; the 286 GameNative entries are upstream-clean. Build = artifact-only `release.yml` pre7. Carries pre5/pre6's inline-`DXVK_CONFIG` + DXVK-log diagnostic unchanged.

### 2026-05-17 — pre8: shrink the GPU Spoof dialog (user request, pre7 confirmed working)

pre7 device test: **works**. User asked to make the settings dialog smaller. `BhGpuSpoofSettingsActivity` only (pure extension Java, zero patcher/anchor change — same risk class as pre7):

- **Width:** fixed `dp(480)` → `Math.min(dp(340), screenW * 0.92)` so the card no longer spans edge-to-edge / overflows on phones.
- **Compact vertically:** root padding `dp(20/14)` → `dp(16/12)`, corner radius `12→10`, title bottom-margin `10→8`, desc top-margin `8→6`, btnRow top-margin `8→6`, label vertical padding `6/4 → 4/2`.
- **Smaller text:** title `16→14`, subtitle `12→11` (+ maxWidth `160→140`), label `13→12`, desc `11→10`, hexField & nameIn `13→12`.

No mode/preset/storage/anchor logic touched. Build = artifact-only `release.yml` pre8. Retest: dialog noticeably smaller, all 3 modes (Off/Spoof/Custom) + cascading spinners still usable.

### 2026-05-17 — pre9: compact spinner options + shorter dialog (user request, pre8 installed)

pre8 installed; user asked for smaller spinner *options* and a shorter dialog. The pre8 shrink left the spinners on Android's stock `simple_spinner_dropdown_item` (chunky ~48dp rows, big text). `BhGpuSpoofSettingsActivity` only (still pure ext Java, zero patcher/anchor):

- **`smallAdapter(String[])`** — new helper returning an `ArrayAdapter` that overrides `getView` (collapsed control: 12sp, `dp(3)` v-padding, single-line ellipsized) and `getDropDownView` (list rows: 12sp, `dp(10/5)` padding, single-line ellipsized), `setDropDownViewResource(simple_spinner_dropdown_item)`. Applied to all 3 spinners (Mode, Vendor, Model) — Mode/Vendor/Model dropdowns and the 313-entry Model list are far tighter.
- **Shorter:** spoofBox/customBox top-margin `10→6`, height cap `0.85→0.78` of screen.

No mode/preset/storage/anchor logic touched. Build = artifact-only `release.yml` pre9. Retest: spinner rows + dropdown list visibly smaller, dialog shorter, all 3 modes + cascading pickers still work.

### 2026-05-17 — pre10: fix invisible Custom-mode input fields (screenshot-confirmed)

pre9 device screenshot (`Screenshot_20260517-163400.png`): in **Custom** mode the 3 input fields (Vendor ID / Device ID / Adapter name) showed as blank white boxes. Root cause: the host theme renders `EditText` with a light/white background, but the dialog set white text + gray hint → **white-on-white, invisible**. `BhGpuSpoofSettingsActivity` only (pure ext Java, zero patcher/anchor):

- **New `styleField(EditText)`** — `GradientDrawable` dark fill `0xFF2A2A2A`, `dp(6)` corner, `dp(1)`/`0xFF4A4A4A` border; white text, hint `0xFF8A8A8A`; `dp(10/8)` padding; `dp(6)` top-margin. Applied to all 3 Custom fields (`hexField()` now calls it; `nameIn` styled inline, its redundant color setters removed).

No mode/preset/storage/anchor logic touched. Build = artifact-only `release.yml` pre10. Retest: Custom-mode fields legible (dark boxes, visible text/hint), spaced.

**Result:** pre10 run 26002042380 → **success**; `apk-alt-AnTuTu` delivered to `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-gpuspoof-pre10-Patched-alt-AnTuTu.apk`. UI-polish chain pre7→pre10 (full preset catalog → dialog shrink → compact spinners → legible Custom fields) all green; pre7 functionally device-confirmed, pre8/9/10 UI-only on top awaiting visual device test. Branch HEAD `e0853f4` `feature/gpu-spoof-menu`, NOT merged. Crysis-2-spoof-not-applied (pre6 DXVK-log diagnostic) remains open & orthogonal.

### 2026-05-17 — pre11: API-coverage expansion — wined3d + DX12/Vulkan prongs

Research (see [[gpu-spoof-api-coverage]]) established the DXVK-only feature covers DX9/10/11-on-DXVK only. User approved building **both** remaining prongs. **Extension-only, NO new smali patch** (the bg5 VK_ICD_FILENAMES set at decompile line 2233 uses the same `EnvVars.a(String,Object)` setter our ZINK-anchored hook runs *after* → last-write-wins, like DXVK_CONFIG_FILE):

- **Prong B — wined3d (`BhGpuSpoofController.upsertWineRegistry`)**: reads `WINEPREFIX` from `EnvVars`' public `LinkedHashMap a` (new `readEnv()` reflection helper), upserts `[Software\\Wine\\Direct3D]` `VideoPciVendorID`/`VideoPciDeviceID` (`dword:%08x`) + `VideoDescription` into `user.reg` in place — atomic temp+rename, one-time `user.reg.bhgpuspoof.bak`, fully non-fatal. Mirrors GameNative/Winlator `ContainerUtils`. Applied whenever a spoof is active (harmless to DXVK titles; no toggle).
- **Prong C — DX12/VKD3D + native Vulkan (`applyVulkanSpoof`)**: opt-in `KEY_DEEP` per-game pref. When on, reflectively reads `VK_ICD_FILENAMES`, swaps suffix `home/steamuser/.config/vulkan/icd.d/GameScopeVK_icd.json` → `share/vulkan/GameScopeVK_icd.json` (libGameScopeVK→libGameScopeV2, both ship in imagefs 1.4.1, base-agnostic), sets `GAMESCOPE_SPOOF_VENDOR_ID/DEVICE_ID` (`0x`+hex for strtoul base-0) + `DEVICE_NAME`. V2's `vkGetPhysicalDeviceProperties2` hook ⇒ covers all Vulkan-backed APIs at once; **cost: disables frame-gen direct rendering** for that game.
- **UI (`BhGpuSpoofSettingsActivity`)**: new CheckBox "Also spoof DX12 / Vulkan games (turns off frame-gen for this game)", visible when mode≠Off, persists live via `ctl.setDeep()` + on Close; restores from `ctl.getDeep()`.

Pure ext Java (controller +234/−13, activity +24); no `.kt` patch / CVR / asset / anchor change; brace-balanced. Build = artifact-only `release.yml` pre11 → **run 26002591052 success**; `apk-alt-AnTuTu` delivered to `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-gpuspoof-pre11-Patched-alt-AnTuTu.apk`. Branch HEAD `b253894`. Retest: (1) DX9/10/11-on-DXVK still works (regression check), (2) toggle on → a DX12/VKD3D title sees the spoofed GPU, (3) wined3d-renderer title sees it, (4) frame-gen indeed off when deep on.

### 2026-05-17 — Reference: GameHub "GPU Passthrough" = renamed Native Rendering

User asked what the app's "GPU Passthrough" does. Verified in `gamehub_604_decompile/res/values/strings.xml:466`: `<string name="native_rendering_plus">GPU Passthrough</string>` — it is the **UI rename of the existing `NativeRendering` setting** (enum `NativeRenderingMode` Auto/Never/Always, MMKV slot 3), not a new feature. 6.0.4 also re-implemented it: old `DirectRendering` ASurfaceTransaction plane compositor deleted, absorbed into the Vulkan path; toggle now `XServer.setFlipEnabled` (libwinemu.so −25 KB). Function = direct flip/scan-out (GPU→display surface, skipping the X-server copy) → higher FPS / lower latency; Auto/Always/Never because the direct path is less flexible. **Relevance to this feature:** the flip path runs through `libGameScopeVK`; our pre11 deep DX12/Vulkan spoof swaps to `libGameScopeV2` which has DirectRendering removed — so GPU-Passthrough-Always + deep-spoof on the same game forces the slow copy path. That subsystem IS the "turns off frame-gen" cost on the deep-spoof checkbox. Full note in memory `reference_gamehub_gpu_passthrough.md` + master map § 26.23 / § 3.3.

### 2026-05-17 — CORRECTION: 6.0.4 GPU Passthrough is on/off ONLY (not Auto/Never/Always)

User flagged that on their 6.0.4 install GPU Passthrough is a plain on/off — no Auto/Never/Always, and no "Native Rendering" wording. Decompile trace confirms the user, corrects the prior entry's master-map-sourced enum claim: UI label `native_rendering_plus`=`GPU Passthrough` (`strings.xml:466`, id `0x7f1101ca`; `native_rendering_plus` is only the legacy resource *name*) → one MMKV bool **`key_native_rendering_enabled`, default false/OFF** (`tco.smali:407` `MMKV.decodeBool(...,false)`) → straight to **`XServer.setFlipEnabled(Z)`** at launch (`tco.smali:452`) and in-game sidebar live (`jk9.smali:839`). The `NativeRendering` bean has `enable:Z`+`mode:NativeRenderingMode`(Auto/Never/Always) but the factory builds `(enable=false, mode=null)` (`leo.smali` pswitch_6) and the flip decision reads only the boolean — the enum is vestigial in 6.0.4. memory `reference_gamehub_gpu_passthrough.md` + MEMORY.md index corrected.

### 2026-05-17 — 6.0.2→6.0.4 side-by-side saved + DiRT3/Crysis2 black-screen regression localized

(1) Binary-re-verified the GLES2→Vulkan rewrite against the exact libs (md5 match); consolidated 6.0.2-vs-6.0.4 side-by-side saved to master map § 26.23.8 + memory `reference_gamehub_602_vs_604.md`. Precision fix: 6.0.4 libxserver retains 121 `gl*` strings = X-server **GLX** dispatch (in both versions), NOT a surviving GLES2 renderer. Scope rule: renderer/native = byte-exact 6.0.2v6.0.4; broader app deltas = 6.0.1→6.0.4 only (6.0.2/6.0.3 never decompiled).

(2) User reports **DiRT 3 + Crysis 2 = black screen + audio on 6.0.4, worked on 6.0.2-era, GPU Spoof OFF/never used** (issue predates the spoof feature). Spoof + our patches excluded by the user's own account → only changed variable is the renderer rewrite; mechanism = removed libwinemu `ASurfaceTransaction` plane-compositor fallback → single Vulkan-only present path can't catch titles whose swapchain won't AHB-import on Turnip (black + sound = exact signature). NOT yet log-confirmed (need `getlog` during repro: libxserver `renderer_init failed`/`swapchain returned no images`/`no surface formats`/`vkAcquireNextImageKHR returned invalid image index`/`dlopen libvulkan.so failed` + DXVK d3d9.log). This is the concrete regression that fires the shelved **legacy-GLES2 renderer** plan's "revisit when" trigger — memory `project_bannerhub_revanced_legacy_gles2_renderer.md` + MEMORY.md index updated (trigger FIRED). Stock mitigations to try first: GPU Passthrough on/off, different Turnip+DXVK, force `winemu-xserver` backend if exposed. Real fix if unfixable in-app = legacy-GLES2 toggle or a separate 6.0.2-base BannerHub variant. No code change this entry — research/triage only.

### 2026-05-17 — DiRT3 black-screen ROOT-CAUSED: our own global deep-spoof (prior triage RETRACTED)

Log-server (`http://…:8080/events` SSE) + root logcat-bridge (`getlog --cat/--ls`) capture on the **pre11 alt-AnTuTu (non-Lite)** build. Prior entry's "spoof excluded → stock-6.0.4 renderer-rewrite regression" is **WRONG and retracted**. Evidence: `shared_prefs/bh_gpuspoof_prefs.xml` GLOBAL default = `bh_gpuspoof_mode=1` + **`bh_gpuspoof_deep=true`** (RTX 4080 / 10de:2704); DiRT 3 `pc_g_setting131962.xml` has no gpuspoof keys → inherits global → spoof+deep ON despite user believing it off (global-vs-per-game confusion). `dirt3_game_d3d11.log`: DXVK init **fully clean** (device "NVIDIA GeForce RTX 4080", swapchain B8G8R8A8 800x600 ×3 immediate, **zero errors**) then silence = textbook libGameScopeV2 signature (deep swaps `VK_ICD_FILENAMES`→libGameScopeV2, which has DirectRendering/present REMOVED). So **deep-spoof globally on bricks presentation for any game lacking a per-game override → black screen + audio.** Definitive test pending: spoof Off *globally* + deep off, relaunch. Side findings: DiRT3 & Crysis2 are **D3D11**-on-DXVK (their `*_d3d9.log`=0B, `*_d3d11.log` populated) — old "Crysis2=DX9" note corrected; both FEX + DXVK-2.4.1-gplasync + SMXZ_Turnip_v26.2.0_R4 + proton10-arm64x. **Design bug to fix: global-default `deep=true` is dangerous — deep should be per-game-only / not inherit from global, or global spoof must not silently apply the libGameScopeV2 swap.** Memory `project_bannerhub_revanced_gpu_spoof.md` + legacy-gles2 (trigger RETRACTED) + MEMORY.md corrected. No code change this entry.

### 2026-05-17 — DiRT3 spoof-off clean repro: pre-renderer hang; renderer-rewrite + spoof BOTH exonerated

Clean spoof-off run captured live (root bridge + log-server SSE), game left running. Verified `bh_gpuspoof_mode=0`, spoof conf NOT rewritten (hook didn't fire). `launchLog131962.txt` fresh (18:13) but **`dirt3_game_d3d11.log` never created** (stale 18:00) and SSE frozen 30s+ immediately after `dirt3_game.exe` PE load. ⇒ With spoof OFF, **DiRT 3 hangs pre-renderer — never loads d3d11.dll/DXVK**. Exonerates the 6.0.4 GLES2→Vulkan rewrite, the plane-compositor removal, AND our gpuspoof for the spoof-off case (none reached). Deep-spoof/libGameScopeV2 black-screen (18:00 run) remains a separate confirmed bug. DiRT3 spoof-off = longstanding pre-renderer early-exec hang, leading hypothesis GFWL (title needs it; `XLiveRedist` in prefix; needs `WINEDEBUG=+loaddll,+module,+seh` to pin). Fixes are GFWL-class (xliveless/disable GFWL/Box64-not-FEX). Memory (gpu-spoof, legacy-gles2 trigger NOT-fired, MEMORY.md) corrected. No code change.

### 2026-05-17 — DiRT3 ROOT CAUSE: ARM64EC+FEX vs x86_64+Box64 (clean 5.3.5-vs-6.0.4 A/B)

User ran DiRT3 on BannerHub 5.3.5 PuBG (`com.tencent.ig`) where it works; diffed its `launchLog131962.txt` vs the broken 6.0.4 alt-AnTuTu one (same game/exe/gameId 131962, same DXVK-2.4.1-gplasync/vkd3d-3.0.1/Pulse). Decisive delta: **WORKS = `wine_proton10.0-x64-1`, isArm64X=false, Box64 (Hybrid-Bionic "Extreme"); HANGS = `wine_proton10.0-arm64x-2`, isArm64X=true, FEX (ARM64EC)**. So DiRT3's pre-renderer hang = **ARM64EC+FEX failing on the 32-bit GFWL early-init**, not the GameHub version, not the GLES2→Vulkan renderer rewrite, not gpuspoof (all exonerated by this A/B). **Fix is a per-game container setting (no build): on 6.0.4 set DiRT3 (likely Crysis2 + other GFWL/old titles too) to a `wine_proton10.0-x64` base + Box64 translator.** Memory `project_bannerhub_revanced_gpu_spoof.md` updated; supersedes the GFWL-only hypothesis (FEX/ARM64EC is the proven lever).

### 2026-05-17 — Legacy-GLES2 THROWAWAY load test (go/no-go)

Per user request to test feasibility cheaply. New `LegacyGles2RendererTestPatch.kt` (resourcePatch, MuteUiSounds-pattern): force-overwrites 6.0.4 `lib/arm64-v8a/libxserver.so` + `libwinemu.so` with the 6.0.2 GLES2-era pair (bundled in `patches/src/main/resources/legacygles2/`, md5 libxserver e8eb89…/libwinemu 407f27…), always-on, NO toggle/pref/UI/DirectRendering stubs. Sole purpose: learn on-device whether the 6.0.2 pair loads/renders on the 6.0.4 Kotlin runtime or hard-crashes on JNI package drift (6.0.2 com.winemu.ui.XServer vs 6.0.4 com.winemu.core.server.XServer + deleted DirectRendering callbacks). Outcomes: UnsatisfiedLinkError→needs JNI shim; launches-but-blackscreen→pair coupling dead; renders→viable. Artifact-only release.yml test build; deliver alt-AnTuTu, capture launch logcat. Not for merge.

### 2026-05-17 — Legacy-GLES2 load test RESULT: JNI drift confirmed (outcome #1, feasible-with-shim)

test1 build (`b35d258`) device-tested: every game **instant SIGABRT on launch**. Abort: `JNI DETECTED ERROR ... java.lang.NoSuchMethodError: no ... method "Lcom/winemu/core/server/XServer;.setRenderingEnabled(Z)V"` at `XServer.<clinit>` → `libxserver.so JNI_OnLoad` → abort (pid `…:wine`). The 6.0.2 libxserver's JNI_OnLoad binds to 6.0.2's `XServer.setRenderingEnabled(Z)V`; 6.0.4 renamed it `setFlipEnabled(Z)V` (DirectRendering→flip). So **NOT package-drift (it found `com.winemu.core.server.XServer` fine — JNI_OnLoad uses FindClass+RegisterNatives) — it's METHOD-surface drift.** Verdict = predicted outcome #1: lib-pair coupling NOT disproven; legacy toggle is **feasible but requires a smali JNI shim** re-exposing the 6.0.2 native-method surface on 6.0.4's XServer (≥`setRenderingEnabled(Z)V` + the rest of 6.0.2 libxserver's RegisterNatives table + DirectRendering callbacks). Next scoping step: statically enumerate 6.0.2 libxserver.so's full JNI_OnLoad RegisterNatives {name,sig} list. Throwaway patch + bundled libs REVERTED from branch (would brick all subsequent gpuspoof builds). Memory `project_bannerhub_revanced_legacy_gles2_renderer.md` updated.

### 2026-05-17 — Legacy-GLES2 JNI surface enumerated; scope is two-tier

Static analysis of 6.0.2 libxserver.so (md5 e8eb89…, 0 Java_ exports, all-dynamic RegisterNatives) + libwinemu.so (407f27…, 36 static Java_ exports). libxserver = 11 natives on com.winemu.core.server.XServer; **10/11 name+sig identical to 6.0.4; only mismatch = `setRenderingEnabled(Z)V` (6.0.4 renamed → setFlipEnabled)**. libwinemu statically exports `Java_com_winemu_core_DirectRendering_00024Companion_{nativeInitialize,nativeSetSurfaceFormat,nativeStartTestClient}` against the 6.0.4-DELETED `com.winemu.core.DirectRendering`. **Scope: (1) trivial — +1 smali method `setRenderingEnabled(Z)V` on XServer fixes the load crash; (2) real work — restore deleted DirectRendering(+Companion) + port the 6.0.2 Java orchestration that drives it, to actually composite (lib-pair runtime still unproven).** Next = test2 (libs + tier-1 shim only, incremental). Memory `project_bannerhub_revanced_legacy_gles2_renderer.md` updated with the full surface.

### 2026-05-17 — Legacy-GLES2 test2 (tier-1 JNI shim) build

Added `BytecodePatchContext.addNativeMethod()` helper to BytecodeUtils (ImmutableMethod+ImmutableMethodParameter, `MutableMethod.Companion.toMutable as toMutableMethod`, modeled on addStaticFieldToExtension). `LegacyGles2RendererTestPatch.kt` now = 2 throwaway patches: (1) `legacyGles2XServerShimPatch` (bytecodePatch) adds native `setRenderingEnabled(Z)V` to `Lcom/winemu/core/server/XServer;`; (2) `legacyGles2LibSwapPatch` (resourcePatch, dependsOn the shim) overwrites libxserver.so+libwinemu.so with the 6.0.2 pair. Always-on, no toggle. Answers: does the app get PAST the JNI_OnLoad abort, and what next (render / next missing symbol / black via deleted DirectRendering orchestration). Artifact-only release.yml test2; deliver alt-AnTuTu; capture launch logcat. Not for merge.

### 2026-05-17 — Legacy-GLES2 test2b RESULT: load wall PASSED; next = setFlipEnabled call-direction

test2b (lib swap + added native `setRenderingEnabled(Z)V` on XServer) device result: **JNI_OnLoad no longer aborts** — 6.0.2 libxserver.so now loads (shim worked, RegisterNatives resolves). New crash, further along: `java.lang.UnsatisfiedLinkError: No implementation found for void com.winemu.core.server.XServer.setFlipEnabled(boolean)` — 6.0.4's Java calls `setFlipEnabled` at launch (GPU-Passthrough flip), but the 6.0.2 lib only provides `setRenderingEnabled` (the 6.0.2 name for the same fn). Confirms it's ONE rename hit from BOTH directions (lib exposes setRenderingEnabled / app calls setFlipEnabled). Next (test3): redirect 6.0.4's ~2 `XServer->setFlipEnabled(Z)V` call sites (tco/jk9) → `setRenderingEnabled` (tiny method-ref swap). Then expect the real tier-2 wall (deleted DirectRendering orchestration / does it render). Throwaway now visibly = a 6.0.2↔6.0.4 JNI compat layer. Memory updated. Branch still has the throwaway patches (revert when done).

### 2026-05-17 — Legacy-GLES2 test3 (setFlipEnabled redirect) build

Added `BytecodePatchContext.redirectVirtualCalls(definingClass,fromName,toName,proto)` helper (BytecodeUtils; mirrors forEachLiteralValueInstruction iteration, matches by MethodReference so R8-letter-resilient, rebuilds invoke-virtual preserving regs via FiveRegisterInstruction + remove/addInstructions — no replaceInstruction in this patcher set). New `legacyGles2SetFlipRedirectPatch` bytecodePatch redirects the 2 `Lcom/winemu/core/server/XServer;->setFlipEnabled(Z)V` call sites (jk9.smali:839, tco.smali:452) → `setRenderingEnabled`. legacyGles2LibSwapPatch now dependsOn(shim, redirect). test3 = shim + redirect + lib swap. Artifact-only release.yml. Expected next: past both JNI walls → the real tier-2 question (deleted DirectRendering orchestration / does it composite). Throwaway; not for merge.

### 2026-05-17 — Legacy-GLES2 test3 RESULT: BOTH JNI walls cleared; old engine loads + game executes

test3 (lib swap + setRenderingEnabled shim + setFlipEnabled→setRenderingEnabled redirect) device run: **NO crash, NO JNI error** (no NoSuchMethodError, no UnsatisfiedLinkError anywhere). Logcat 20:46: `gamescope: vkCreateInstance app_name: dirt3_game.exe engine_name: DXVK` ×2, loads syswow64/d3d8.dll + wine i386 comdlg32.dll — i.e. **6.0.2 libxserver.so loaded successfully and dirt3_game.exe actually executed into DXVK Vulkan-instance bring-up** (~14s real run) before `:wine` died at 20:46:35 → WineActivity→MainActivity, user switched to Termux 20:46:43. JNI compat bridge (addNativeMethod + redirectVirtualCalls) WORKS. Inconclusive on render: not left running long enough, no DXVK device/render log written before it ended (same launch→switch-away pattern as prior runs; no logged crash for the :wine death). Next: left-running ≥90s capture (live SSE + DXVK log) to see render-or-DirectRendering-wall. Memory updated.

### 2026-05-17 — Legacy-GLES2 test3 RENDERS (milestone: toggle proven viable)

Background SSE auto-capture + logcat. Two runs: (1) **20:58 arm64x+FEX** → `err:wow:load_64bit_module failed ... c000007b` + `Could not find memory region containing address /wine` at run.exe wow64 bootstrap = **6.0.2 libwinemu can't do the ARM64EC/wow64 path** (predicted libwinemu-pairing blocker, real but path-specific; no JNI crash). (2) **21:01 DiRT3 on wine_proton10.0-x64-1 + Box64 (isArm64X=false)** → `WinEmuModule DevicePerformanceReporter fps=29` sustained durationSec 80→90→100→110, :wine pid 31363 alive, no crash = **THE 6.0.2 GLES2 libxserver+libwinemu pair LOADS AND RENDERS on 6.0.4 with the JNI bridge.** ⇒ **Legacy-GLES2 toggle is VIABLE.** Constraint: must pair with **x64/Box64** containers (arm64x/FEX bootstrap dies on 6.0.2 libwinemu). Tier-2 "deleted DirectRendering" did NOT block rendering on this path. Pending: user visual confirm (fps=29 telemetry says rendering; eyes = tiebreaker). Helpers `addNativeMethod`+`redirectVirtualCalls` are the proven shim primitives for the real feature. Memory updated.

### 2026-05-17 — Remove throwaway legacy-GLES2 patches (pre GPU-Spoof merge)

git rm the 3 throwaway files (LegacyGles2RendererTestPatch.kt + 6.0.2 libxserver.so/libwinemu.so) off feature/gpu-spoof-menu so GPU Spoof can merge clean to gamehub-604-build. Reusable BytecodeUtils helpers `addNativeMethod`/`redirectVirtualCalls` KEPT (generic; needed for the upcoming real renderer toggle). Learnings preserved in memory project_bannerhub_revanced_legacy_gles2_renderer.md (test1→test3: JNI bridge works, x64+Box64 runs but black-screen+sound = DirectRendering present path still required = tier-2 crux).

### 2026-05-17 — CORRECTION: legacy renderer DOES display (GoW on Proton-10-arm); DiRT3 is the outlier

User: God of War ran fine on the legacy renderer on wine_proton10.0-arm64x-2 (arm64x+FEX). Telemetry corroborates (session hit fps=310/299 @ gpuPercent=80 = heavy real rendering, not black). Both my prior swings were over-generalised from DiRT 3 (session-long problem child: GFWL/32-bit-wow64/c000007b): "PROVEN VIABLE" (telemetry-fooled) AND "black-screen, DirectRendering crux unsolved" (DiRT3 visual) were BOTH wrong. Corrected: **legacy GLES2 renderer DISPLAYS and works for real games (GoW end-to-end); deleted-DirectRendering is NOT a universal present blocker; arm64x not broken for legacy (c000007b was DiRT3 32-bit wow64).** Real toggle scope shrinks to gating the already-proven lib-swap+JNI-bridge behind a per-game pref. Plan doc + memory corrected. Lesson: never generalise DiRT 3 to the renderer; validate with a clean title.

### 2026-05-17 — Legacy-renderer-toggle Milestone 1: toggle plumbing (UI + pref + menu row)

On `feature/legacy-renderer-toggle`. Cloned the GPU-Spoof scaffold → renderer toggle (no swap yet; that is M2):
- `extensions/.../renderer/BhRendererController.java` — per-game+global pref `bh_renderer_mode` (0=New default / 1=Legacy) in `pc_g_setting<id>` + `bh_renderer_prefs`; get/setMode, reload, `isLegacyForGame/isLegacyForLaunchingGame` (M2 entry), sniffGameIdFromStack.
- `BhRendererSettingsActivity.java` — compact dialog (gpuspoof styling): Mode spinner New(Vulkan)/Legacy(GLES2) + inert-features/GFWL warning; persists on select + Close.
- `BhRendererMenuRowClick.java` — faithful clone of BhGpuSpoofMenuRowClick (More Menu Lx57;->a + tile popup ted.f; raw-String label, NO l1; Proxy over R8 Function1/0). ROW_LABEL "Renderer".
- `RendererMenuRowPatch.kt` — clone of GpuSpoofMenuRowPatch (Inj1 Lx57;->a + Inj2 ted.f); additive to the GPU-Spoof row (distinct label, same proven-safe append). `dependsOn(sharedGamehubExtensionPatch, rendererManifestPatch)`.
- `RendererManifestPatch.kt` — registers BhRendererSettingsActivity (exported=false, translucent).
All brace-balanced; auto-discovered patches. M1 = the row+dialog+persistence work and are device-testable now (selecting Legacy won't change rendering until M2 wires the conditional 6.0.2 lib-swap+JNI-bridge). Artifact-only build to verify compile + that the renderer menu injections resolve alongside gpuspoof's identical anchors.

### 2026-05-17 — Per-game-from-menu investigation: GameInfo.getServerGameId() is the source

User-confirmed: Vibration is global from BOTH More Menu and library popup too — the per-game gap is universal/pre-existing (all 3 use sniffGameIdFromStack = needs a running WineActivity; none at pre-launch menu → global). Decompile trace result: real per-game id = **`com.xiaoji.egggame.game.di.model.game.GameInfo` (kept-name, non-R8) → `getServerGameId()I`** (== pc_g_setting<id> / launchLog gameId). In scope in both `Lx57;->a(Lf37;…)` (More Menu) and `Lpzc;->j0(Laub;…)` (library popup); stock rows read it (pzc.j0 ~2146/2180/2192); Laub/Lf37 carry libraryGameId. **Shared fix:** capture in-scope GameInfo at each injection site, getServerGameId(), thread into click→settings Intent (replace sniffGameIdFromStack). Also recorded: GPU-Spoof & Renderer were never added to the library popup (Lpzc;->j0/Lz4e) — only Vibration has it (needs Lxd3;->l1; gpuspoof dropped it over the l1-ANR) → adding them must avoid a 2nd l1 head-block. Plan doc updated. Next: implement the shared GameInfo-capture mechanism (Renderer first, then retrofit GpuSpoof+Vibration), then add GPU-Spoof/Renderer library-popup rows w/o a 2nd l1 hook.

### 2026-05-17 — Step 1: shared per-game gameId-from-menu mechanism (Renderer)

`BhRendererMenuRowClick`: + `captureGameId(menuData)` (resolves gameId from menuData.toString() via the R8-stable token `ServerGameId\(value=(-?\d+)\)` / fallback `gameId=(\d+)` — == pc_g_setting<id>); + `volatile static sCapturedGameId`; handler instances bind it at construction; `invoke()` prefers boundGameId, falls back to sniffGameIdFromStack (in-game sidebar). `RendererMenuRowPatch`: + index-0 `invoke-static/range {p0 .. p0} -> captureGameId(Object)V` on BOTH builders (Lx57;->a More Menu p0=Lf37 GameDetailArgs; ted;->f tile popup p0=Lued — both verified `static final`). Single no-label once-per-menu-open invoke = no l1/ANR/trailing-label footgun. Mechanism is generic & R8-letter-proof → retrofit target for GpuSpoof+Vibration next. Artifact-only build to verify compile + smali (pN range invoke at idx0) + per-game on device.

### 2026-05-17 — Shared BhMenuGameId refactor (per-game for all 3 menu features)

Extracted capture into `extensions/.../common/BhMenuGameId.java` (captureGameId/getCaptured/resolve via R8-stable ServerGameId/gameId toString token) + `patches/.../common/MenuGameIdCapturePatch.kt` (ONE index-0 `invoke-static/range {p0..p0}` into BOTH builders Lx57;->a + Lted;->f, dependsOn sharedGamehubExtensionPatch). Renderer de-duped (BhRendererMenuRowClick → BhMenuGameId.getCaptured; RendererMenuRowPatch dropped its own capture, dependsOn menuGameIdCapturePatch). Retrofitted GPU Spoof (BhGpuSpoofMenuRowClick getCaptured-else-sniff; GpuSpoofMenuRowPatch dependsOn menuGameIdCapturePatch) + Vibration (BhMenuRowClick getCaptured-else-sniff; VibrationMenuRowPatch dependsOn += menuGameIdCapturePatch). One shared capture, three small handler consumers — no triplicate index-0 stacking. Build verifies all 3 coexist + compile.

### 2026-05-17 — Per-game fix #2: cover the library-LIST popup (Vibration's 3rd entry)

Device: GPU Spoof + Renderer now per-game (More Menu/tile popup) but Vibration still global — because Vibration also has a row in the library-LIST popup `Lpzc;->j0(Laub;Z…)` which MenuGameIdCapturePatch didn't cover (GPU Spoof/Renderer have no row there yet). Fix: (1) MenuGameIdCapturePatch + 3rd index-0 capture into `Lpzc;->j0` (static, p0=Laub; mirrors vibration's exact j0 predicate). (2) `BhMenuGameId.resolve()` now 2-pronged: toString token (Lf37 GameDetailArgs) THEN kept-name `com.xiaoji.egggame.game.di.model.game.GameInfo.getServerGameId()` located by VALUE type on the param or its declared fields (Laub.a:GameInfo) — R8-field-name-proof. Build to verify + device-test Vibration per-game from the library popup.

### 2026-05-17 — Vibration per-game ROOT CAUSE: Intent extra key mismatch (1-line fix)

Capture mechanism was fine (GPU Spoof/Renderer per-game proved it). Vibration stayed global because of a pre-existing key mismatch: `BhMenuRowClick` put the id under `"gameId"` but `BhVibrationSettingsActivity` reads `EXTRA_GAME_ID = "bh_vibration.gameId"` → settings activity always got null → `setContainerForSettings(null)` → global. Fixed: putExtra now uses `BhVibrationSettingsActivity.EXTRA_GAME_ID`. (GPU Spoof/Renderer were unaffected — their handler/activity keys already matched.) Build to verify Vibration now per-game from all 3 menus.

### 2026-05-17 — STATE SNAPSHOT (per-game saga) + memory synced

Branch `feature/legacy-renderer-toggle` HEAD `4ab5230`. Done: shared `BhMenuGameId` + single `MenuGameIdCapturePatch` (index-0 capture into all 3 builders Lx57;->a / Lted;->f / Lpzc;->j0; R8-proof via ServerGameId/gameId toString token + kept-name GameInfo.getServerGameId by value-type); Renderer de-duped; GPU Spoof + Vibration retrofitted to read getCaptured(); Vibration extra-key bug fixed (`4ab5230`, build 26010126663 verification pending). Device-confirmed per-game: GPU Spoof + Renderer (More Menu/tile popup). Pending: (a) verify Vibration per-game post-`4ab5230`; (b) Task #20 — add GPU Spoof + Renderer ROWS to library-list popup `Lpzc;->j0` without a 2nd l1 head-block; (c) Milestone 2 — Renderer toggle still UI/pref-only, conditional 6.0.2 lib-swap+JNI-bridge not wired. Caveat recorded in memory: `gamehub-604-build` gpuspoof (merged `792ae69`) lacks the per-game mechanism — only this branch has it; needs later merge. Memory updated: project_bannerhub_revanced_legacy_gles2_renderer.md + project_bannerhub_revanced_gpu_spoof.md.

### 2026-05-17 — Vibration per-game CONFIRMED WORKING on device

User confirmed: Vibration per-game settings now work end-to-end on device after the extra-key fix `4ab5230` (`putExtra` → `BhVibrationSettingsActivity.EXTRA_GAME_ID`, build 26010126663). All three per-game menu features are now device-verified per-game: **GPU Spoof + Renderer** (More Menu / tile popup) and **Vibration** (all three menus incl. the library-list popup `Lpzc;->j0`). The shared `BhMenuGameId` + single `MenuGameIdCapturePatch` mechanism is fully proven end-to-end. Branch `feature/legacy-renderer-toggle` HEAD `28de6c4`. Pending item (a) CLOSED. Remaining: (b) Task #20 — add GPU Spoof + Renderer ROWS to the library-list popup `Lpzc;->j0` without a 2nd `Lxd3;->l1` head-block (STARTING NOW); (c) Milestone 2 — Renderer conditional 6.0.2 lib-swap+JNI-bridge. Memory updated: project_bannerhub_revanced_legacy_gles2_renderer.md + project_bannerhub_revanced_gpu_spoof.md.

### 2026-05-17 — Task #20 IMPLEMENTED: GPU Spoof + Renderer rows in library-list popup (shared-resolver, no 2nd l1)

Added the missing library-list popup (`Lpzc;->j0`, `Lz4e(Lell,Lnw6,int)` rows) entry for **GPU Spoof** and **Renderer** — previously only Vibration had a row there. Hard constraint honoured: **NO 2nd `Lxd3;->l1` head-block** (that stacking ANR'd cold start, 2026-05-17 gpuspoof saga). Approach = **single shared l1 resolver**:
- `BhMenuRowClick.maybeResolveCustomLabel` (the method the ONE existing vibration-injected l1 head-block calls) extended from 1→3 sentinel keys: `string:bh_pc_vibration_label`→"PC Vibration Settings", `string:bh_gpuspoof_label`→"GPU Spoof", `string:bh_renderer_label`→"Renderer". Null-safe early-out unchanged.
- `BhGpuSpoofMenuRowClick` + `BhRendererMenuRowClick`: added `appendLibraryPopupRow(Object)List` (Unsafe-allocate `Lell`, reflect-set `Ltdi.a`=sentinel key / `Ltdi.b`=emptySet, `Lz4e(Lell,Lnw6,int)` ctor, Function0 proxy → own `invoke()`), exact mirror of the proven vibration helper. Stale "removed due to ANR" NOTE comments replaced with the shared-resolver rationale.
- `GpuSpoofMenuRowPatch` + `RendererMenuRowPatch`: added **Injection 3** into `Lpzc;->j0` — `return-object`-anchored `invoke-static appendLibraryPopupRow + move-result` (mirrors vibration Injection 3; this is a row-append before return, NOT an l1 head-block — the proven-safe coexistence pattern). Both now `dependsOn(vibrationMenuRowPatch)` so the single shared l1 hook is guaranteed present + applied first; header/dependsOn comments updated. No new `Lxd3;->l1` injection anywhere → zero ANR regression risk.

5 files edited, all brace-balanced. Chaining across the 3 patches into the same `Lpzc;->j0` return path is the same proven additive pattern as the More Menu Injection 1. Next: commit + push + artifact-only CI build to verify compile + 3-patch coexistence + smali resolves in real 6.0.4 APK, then device-test all 3 rows in the library-list popup (no ANR).

Committed `d57bd78`, pushed to `feature/legacy-renderer-toggle`. Artifact-only build (stable unticked) **run 26011229146** (`1.3.0-604-libpoprows-pre1`) → ✅ **GREEN**. Patches bundle compiled; all 9 variants patched; `Create GitHub Release: skipped` (artifact-only as intended). Patch-log scan (per the SEVERE-doesn't-fail-CI anti-pattern): **0 SEVERE**, and `"GPU Spoof menu row"`, `"Renderer menu row"`, `"PC Vibration Settings menu row"`, `"Per-game menu id capture (shared)"` all `succeeded` in the real 6.0.4 APK — the 3 `Lpzc;->j0` Injection-3 row-appends + the shared 3-key l1 resolver resolve cleanly and coexist. Task #20 implementation CI-verified.

### 2026-05-17 — Task #20 DEVICE-CONFIRMED (full variant)

User installed `BannerHub-V6-1.3.0-604-libpoprows-pre1-Patched-alt-AnTuTu.apk` and confirmed: **all 3 rows (GPU Spoof, Renderer, PC Vibration Settings) appear in the library-LIST popup**. App launched and reached the menus → **no cold-start ANR** (the entire point of the single-shared-l1-resolver design — validated). Task #20 functionally proven on the full variant. Click-behaviour (each row opens its per-game settings dialog scoped to the right game) not yet separately reported but the handlers are the same proven code already device-confirmed from the More Menu/tile popup. Remaining: bring this to the user's ACTUAL install (V6 **Lite** `banner.hub`), which needs the whole per-game feature set (this branch's delta), not just `d57bd78` — Lite (`feature/lite-variant-tier1`, off `gamehub-604-build`) lacks `BhMenuGameId`/`MenuGameIdCapturePatch`/Renderer patches/the extended l1 hook. Decision point surfaced to user (merge route).

### 2026-05-17 — Merge-delta analysis: feature/legacy-renderer-toggle → gamehub-604-build (decision PENDING)

Computed exact git delta `origin/gamehub-604-build...feature/legacy-renderer-toggle` = **10 commits, 13 files, +1434/−34** (2 docs-only: PROGRESS_LOG.md, docs/LEGACY_RENDERER_TOGGLE_PLAN.md). What a main merge would add:
1. **Shared per-game mechanism** (new): `BhMenuGameId.java` + `MenuGameIdCapturePatch.kt` → GPU Spoof becomes per-game *from the menu* (currently global-from-menu on main since gpuspoof merge `792ae69`; per-game only worked from the in-game sidebar).
2. **Entire new Renderer feature** (new): BhRendererController/SettingsActivity/MenuRowClick + RendererMenuRowPatch + RendererManifestPatch. ⚠️ **Milestone 2 NOT wired → the Renderer row+dialog are visible but INERT** (picking Legacy does nothing). This is the sole real trade-off of a main merge.
3. **Existing-feature mods**: BhMenuRowClick (+48: vibration EXTRA_GAME_ID per-game fix + shared 3-key l1 resolver), BhGpuSpoofMenuRowClick (+62), GpuSpoofMenuRowPatch (+83), VibrationMenuRowPatch (+3) → Vibration + GPU Spoof go per-game; all 3 get the Task #20 library-list-popup row.
Clarified: the gpuspoof global-default `deep=true` black-screen bug is ALREADY on gamehub-604-build (came with `792ae69`) — this merge does NOT introduce it; the new per-game mechanism is actually a prerequisite for the proper per-game-`deep` fix. Confirmed to user that GPU Spoof itself already shipped to main at `792ae69` (verified via git, not memory). **Routing decision still OPEN** — options on the table: (A) merge → gamehub-604-build then Lite; (B) merge → Lite branch only; (C) fix deep-spoof first; (D) verify row clicks on full variant before any merge. Mitigations for the inert-Renderer concern noted: hold merge until M2, or strip/hide the Renderer row in a main-merge variant. Verify: patches `succeeded` 9/9 each, 0 SEVERE, the 3 Lpzc;->j0 Injection-3 row-appends + the shared 3-key l1 resolver all resolve in the real 6.0.4 APK. Then device-test: GPU Spoof + Renderer + Vibration rows all appear in the library-LIST popup AND no MainActivity ANR on cold start (the whole point of the shared-resolver design).

### 2026-05-18 — Milestone 2 IMPLEMENTED (xserver-only-first): conditional 6.0.2 libxserver swap + JNI bridge, per-game gated

User picked **xserver-only first** for the libwinemu timing risk (7 early clinit loaders lock libwinemu to first-loaded copy; libxserver loads only at `XServer.<clinit>` = game-session time, so it is the clean, ordering-proof first cut; GoW rendered fine on the full pair so xserver-only may already suffice).

Wired the proven test1→test3 mechanism (GoW device-confirmed 2026-05-17) behind the per-game pref so New mode is provably stock (zero regression):

1. **`RendererLibBundlePatch.kt`** (resource, additive) — bundles 6.0.2 `libxserver.so` (md5 `e8eb894825da66cca0fc59b242ac0ad5`, recovered from throwaway commit `cee31f4`) into the APK as `lib/arm64-v8a/libxserver_legacy.so` ALONGSIDE the untouched stock 6.0.4 lib. Anchors on the stock lib's existing ABI dir; asserts stock present, never overwrites it.
2. **`BhRendererController` M2 methods** — `loadXserver(name)` (resolves launching gameId; Legacy → `System.load(libxserver_legacy.so)` via nativeLibraryDir or APK-zip fallback, else stock `System.loadLibrary` bit-identically; any legacy failure falls back to stock so it can never brick); `flip(xserver, flag)` reflective dispatcher → `setRenderingEnabled` (legacy lib) vs `setFlipEnabled` (stock); decision FROZEN at load time (`legacyActive`/`legacyDecided`) so flip never invokes an unbound native; `resolveLegacyLib` (nativeLibDir → cache-extract fallback for `extractNativeLibs=false`).
3. **`redirectVirtualToStatic`** helper added to `BytecodeUtils.kt` next to `redirectVirtualCalls` — rewrites `invoke-virtual {recv,args} cls->fn` → `invoke-static {recv,args} target`; needed because `redirectVirtualCalls` is a *global by-name* swap and a virtual target on XServer would self-recurse.
4. **`RendererSwapPatch.kt`** (bytecode) — `addNativeMethod` XServer `setRenderingEnabled(Z)V` (so legacy lib's RegisterNatives binds; harmless unbound under stock); rewrites `XServer.<clinit>`'s single `System.loadLibrary` → `BhRendererController.loadXserver`; `redirectVirtualToStatic` on the exactly-2 `setFlipEnabled(Z)V` call sites (`jk9:839 {v0,v1}`, `tco:452 {v8,v6}`) → `BhRendererController.flip`. `dependsOn(sharedGamehubExtensionPatch, rendererManifestPatch, rendererLibBundlePatch)`.

libwinemu deliberately NOT gated (xserver-only-first). Next: CI artifact-only build to verify compile + patches resolve 9/9 / 0 SEVERE in real 6.0.4 APK, then device-test Legacy toggle on a known-good title (GoW).

### 2026-05-18 — M2 pre1 CI: bundle+manifest+menu-row ✅, swap ✗ (CME) → fixed

Run 26022546201 green-but-not-applied (SEVERE-doesn't-fail-CI). Scan: "Legacy renderer libxserver bundle" / "Renderer settings activity" / "Renderer menu row" `succeeded` 9/9; **"Legacy renderer conditional swap" SEVERE 9/9** — `Caused by: java.util.ConcurrentModificationException at BytecodeUtils.redirectVirtualToStatic`. Root cause: `classDefs.getOrReplaceMutable(classDef)` structurally replaces the entry in the live `classDefs` set while the enclosing `classDefs.forEach` iterates it (latent in the copied `redirectVirtualCalls`, which no shipping patch actually invokes — `redirectVirtualToStatic` is the first real use). Fix: snapshot candidate classDefs via `classDefs.filter { … }` before the mutate loop + `classDef.methods.toList()`. Left the unused original `redirectVirtualCalls` untouched (minimal blast radius). Re-triggering pre2.

### 2026-05-18 — M2 pre2 CI ✅ VERIFIED

Run 26022795219: **0 SEVERE**; all 4 renderer patches `succeeded` 9/9 in real 6.0.4 APK across every variant — "Legacy renderer conditional swap", "Legacy renderer libxserver bundle", "Renderer menu row", "Renderer settings activity". CME fix confirmed. Milestone 2 is implemented + CI-verified (xserver-only-first). Plan doc updated with Status block. **Milestone 3 next:** device-test Legacy toggle on a known-good title (GoW); requires the feature on the user's actual install (V6 Lite `banner.hub`) → cherry-pick into `feature/lite-variant-tier1` (same as preload-free vibration landing).

### 2026-05-18 — M3 diagnostic build (Patches A+B) + FULL-PAIR libwinemu swap

Device-pulled the last 2 GoW launches under M2 pre2 (alt-AnTuTu `com.antutu.benchmark.full`, `bh_renderer_mode=1`): both bootstrapped, ran ~40 s @ 600 %% CPU, `:wine` died fg-TOP, **no tombstone, GoW_d3d11.log stale (05-17 15:56), no wine_debug.log** → never reached D3D11; died pre-render. Same shape as test3.

Built a THROWAWAY diagnostic (not for merge; gated by `BhRendererDiag.DIAG`):
- **Patch A** — `BhRendererDiag` fsync'd per-line file logger (`<files>/bh_renderer_diag.log`) threaded through `BhRendererController.loadXserver`/`loadWinemu`/`flip` (entry, resolveLegacyLib path+len+md5-vs-expected, LOAD_OK/FAIL, stock fallback, XSERVER_CLINIT_DONE, flip branch/flag/caller/result) + 1 Hz heartbeat (how far past the swap before `:wine` death).
- **Patch B** — `RendererDiagEnvPatch` injects `BhRendererDiag.applyDiagEnv` at GpuSpoofPatch's proven `Lbg5;->a` ZINK_DESCRIPTORS anchor; reflective EnvVars#a sets `WINEDEBUG=+loaddll,+module,+process,+seh,+pid,+timestamp,fixme-all` + `FEX_SILENTLOG=0` + `FEX_OUTPUTLOG=<files>/fex_diag.log` ONLY when Legacy active for the launching game (FEX log survives kill -9, unlike host-swallowed wine stderr).
- **FULL-PAIR (user decision)** — also swap 6.0.2 libwinemu (test3's proven pair). `redirectStaticLibLoad("winemu", loadWinemu)` (new BytecodeUtils helper) rewrites every `System.loadLibrary("winemu")` early loader; `RendererLibBundlePatch` now bundles BOTH `libxserver_legacy.so` (md5 e8eb89…) + `libwinemu_legacy.so` (md5 407f27…, verified from GameHub_6.0.2.apk) additively; `loadWinemu` idempotent + falls back to stock, decision ownership stays with loadXserver so flip()'s frozen contract is unchanged. Concession: libwinemu can't be strictly per-game (early loaders) — resolves per `:wine` launch via sniff+global.

`rendererSwapPatch` dependsOn `rendererDiagEnvPatch` so the diag patch is pulled in. CI: artifact-only `1.3.0-604-rendererm2-fullpair-diag-pre1`. Next: deliver alt-AnTuTu APK, GoW launch left running ≥90 s with Legacy set, pull the 3 logs via root bridge → decision table resolves which of the 4 hypotheses holds.

#### CI ✅ + APK delivered (post-build, 2026-05-18)

Run [26025275469](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26025275469) success; log-scanned (SEVERE-doesn't-fail-CI guard): **no real `SEVERE:`** (earlier grep hits = an echoed code-comment string, not patch failures); all 5 renderer/diag patches `succeeded` **9/9** — "Legacy renderer conditional swap" (holds the `redirectStaticLibLoad("winemu",…)` call), "Legacy renderer libxserver bundle" (now both 6.0.2 libs), "Legacy renderer diagnostic env (THROWAWAY)", "Renderer menu row", "Renderer settings activity". 9 variant APKs produced.

**Delivered:** `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-rendererm2-fullpair-diag-pre1-Patched-alt-AnTuTu.apk` (md5 `72bca817d6b7d1b298ea23566158ee82`, pkg `com.antutu.benchmark.full`). Same workflow/keystore as M2 pre2 → in-place update expected; if Android refuses on signature, uninstall first (wipes `bh_renderer_mode` + game library → re-set Legacy + re-import GoW).

**Caveat:** green "conditional swap succeeded" only proves RendererSwapPatch ran without throwing; `redirectStaticLibLoad` silently no-ops on 0 `loadLibrary("winemu")` matches (doesn't fail CI). `loadWinemu` wiring is confirmable ONLY from the device `bh_renderer_diag.log` `WINEMU`/`loadWinemu entry` lines.

**Run procedure (awaiting user):** install → confirm Renderer=Legacy for GoW → launch GoW, screen on, **left running ≥90 s** (`:wine` self-dies ~40 s; staying distinguishes fixed-timeout watchdog kill vs real progress) → pull (no manual capture): `getlog --cat /data/data/com.antutu.benchmark.full/files/bh_renderer_diag.log`, `…/files/fex_diag.log`, `getlog -n 40000 com.antutu.benchmark.full`.

**Decision table:** `WINEMU_LOAD_OK`+`LOAD_OK legacy libxserver`→GoW renders = full pair fixes it, xserver-only WAS the cause (pairing hypothesis confirmed); loads but still dies = deeper than lib pairing; no `WINEMU` lines = winemu redirect found 0 sites (rework redirect); md5 `MISMATCH!` = bundle/extract corruption; `LOAD_OK`+heartbeats stop ~40 s + FEX log clean = loaded/no-composite → host watchdog kill = deleted-DirectRendering tier-2 wall; `fex_diag.log` fatal `c000007b`/wow64 = FEX/arm64x death, renderer is a red herring for GoW.

## 2026-05-18 — M3 full-pair device result + forced-enable experiment (branch `feature/legacy-renderer-toggle`)

### Full-pair diag device run — root cause found
User ran 3 GoW launches on `…rendererm2-fullpair-diag-pre1` (pkg `com.antutu.benchmark.full`): L1 Legacy=black, L2 Vulkan/stock=working gameplay, L3 Legacy=black. Logcat buffer rolled (AnTuTu floods it); `bh_renderer_diag.log` fsync'd so it survived; `fex_diag.log` 0 B; `GoW_d3d11.log` stale (05-17 15:56) → game never reached DXVK/FEX under Legacy.

**Decisive findings from the diag log:**
1. **Full-pair bundle correct & loads** — `WINEMU_LOAD_OK legacy libwinemu` md5 `407f274d…` MATCHES 6.0.2; `LOAD_OK legacy libxserver` md5 `e8eb8948…` MATCHES 6.0.2; `XSERVER_CLINIT_DONE (legacy)`. The "`redirectStaticLibLoad` may no-op on 0 sites" caveat is **resolved — it fired**.
2. **Process does NOT die** — heartbeats unbroken n=0→n=42+, no tombstone/SIGABRT. **Full pair fixed the M2-pre2 ~40 s crash** (the missing lib *was* libwinemu, as hypothesised).
3. **Black + alive-forever = the deleted-DirectRendering tier-2 wall, confirmed.**

**Root cause:** `flip()` logs exactly ONCE per legacy launch — `branch=setRenderingEnabled enabled=false … OK` — and is never called again. The test2b "`setFlipEnabled`↔`setRenderingEnabled` = name-drift rename" was a *signature* match, not *semantic*: 6.0.4 `setFlipEnabled(Z)` = GPU-passthrough flip (default OFF → false); 6.0.2 `setRenderingEnabled(Z)` = master switch that turns the GLES2 renderer ON, formerly driven by the **6.0.4-deleted `com.winemu.core.DirectRendering`**. We pass 6.0.4's passthrough flag (false) into 6.0.2's renderer-enable switch → libs load, compositor present, renderer never enabled/driven → black, alive forever.

### Forced-enable experiment (user chose cheap test over full port)
Commit `55b3422` (off `ea2eef9`): one-line THROWAWAY in `BhRendererController.flip()` — `effEnabled = legacy ? true : enabled`; legacy branch now always invokes `setRenderingEnabled(true)` regardless of the 6.0.4-side flag; diag logs `FORCED->true (M3 experiment)` + `eff=`. Full-pair + all diag logging retained. **REVERT before any M2 ship.**

**CI ✅** run [26027685582](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26027685582) completed/success, log-scanned **0 SEVERE**, all renderer patches `succeeded` 9/9 ("Legacy renderer conditional swap" / "libxserver bundle" / "diagnostic env (THROWAWAY)" / "Renderer menu row" / "Renderer settings activity").

**Delivered:** `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-rendererm3-forceenable-pre1-Patched-alt-AnTuTu.apk` (md5 `7b298d4a932a24d0c298cd1e24484ed7`, 116 MB, pkg `com.antutu.benchmark.full`). Same workflow/keystore → in-place update expected; signature refusal fallback = uninstall (wipes `bh_renderer_mode` + library).

**Caveat for reading the result:** flip() fires ONCE at clinit time, surface almost certainly not yet ready. Still-black could mean EITHER the lib pair can't self-drive (→ full DirectRendering port) OR `setRenderingEnabled(true)` was called too early and needs re-assertion post-`surfaceChanged` (→ deferred re-call, still cheaper than full port). Lit screen → 6.0.2 renderer self-drives once enabled → M2 reduces to wiring the enable correctly.

**Run procedure (awaiting user):** install → Renderer=Legacy for GoW → launch, screen on, **left running ≥90 s** → pull `getlog --cat /data/data/com.antutu.benchmark.full/files/bh_renderer_diag.log` (confirm `FORCED->true (M3 experiment)` + `eff=true` on the FLIP line), `…/fex_diag.log`, `getlog -n 40000 com.antutu.benchmark.full` — capture promptly before the buffer rolls.

---

## 2026-05-18 (cont.) — M3 forced-enable DEVICE-CONFIRMED, cleanup shipped, verification blocked → root-bridge v1.3.0 (branch `feature/legacy-renderer-toggle`)

### M3 forced-enable result — ✅ GAME SCREEN (decisive positive)
`forceenable-pre1` device run, `bh_renderer_diag.log` 06:53: both 6.0.2 libs md5-MATCH (`libwinemu_legacy.so` `407f274d…`, `libxserver_legacy.so` `e8eb8948…`), `LOAD_OK legacy libxserver -> legacyActive=true`, `XSERVER_CLINIT_DONE (legacy)`, then `[FLIP] … FORCED->true (M3 experiment) … legacyActive=true` → `flip(setRenderingEnabled) OK eff=true`; heartbeats unbroken, no tombstone. User confirmed **game screen**. **PROVEN: the 6.0.2 GLES2 renderer self-drives once `setRenderingEnabled(true)` is called — no DirectRendering RE port needed.** Root cause was the identified semantic mismatch (6.0.4 fed its passthrough `false` into the 6.0.2 renderer master-switch).

### Cleanup shipped (commit `44b2e4e`)
Deleted `BhRendererDiag.java` + `RendererDiagEnvPatch.kt`; stripped all diag log/heartbeat/md5/flipCaller + the `rendererDiagEnvPatch` dependsOn; promoted the forced-true to the permanent documented rule in `BhRendererController.flip()` (javadoc explains 6.0.2 `setRenderingEnabled` = renderer master-switch ≠ 6.0.4 `setFlipEnabled` passthrough). Kept the proven full 6.0.2 pair bundling + swap. Fixed stale "xserver-only-first" comments. Author The412Banner, no Claude co-author. **NO merge to gamehub-604-build / NO Lite port yet** (user: clean+rebuild on branch first).

### Clean build — ✅ CI green
Artifact-only `release.yml` run **26029782656** (`--ref feature/legacy-renderer-toggle`, `version=1.3.0-604-renderer-clean-pre1`, `stable=false`): completed/success, log-scanned **0 SEVERE**, renderer patches `succeeded` all 9 variants, throwaway diag-env patch correctly ABSENT. APK delivered `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-renderer-clean-pre1-Patched-alt-AnTuTu.apk` md5 `19415351da8a3b46692c4dddf2d4e805`.

### Verification of the CLEAN build — BLOCKED, not yet proven
Device: Legacy SELECTED (`bh_renderer_mode=1` global + per-game 131962/3939/49908). But 6.0.2-libs-loaded NOT durably provable for the clean build: logcat flooded by AnTuTu (`getlog` logcat 0 lines); GameHub Log Server (`http://192.168.12.242:8080/` SSE `/events`) carries ONLY wine stdout/stderr — not Android logcat, so the kept `BhRenderer` `Log.i` markers don't appear; `bh_renderer_diag.log` stale (06:53, old forceenable run); `/proc` + `/data/app` outside root-bridge allowlist; app cache empty by design (native-extract path). GoW/d3d logs stale (05-17 15:56). Honest status: selection proven, load NOT proven for clean build (strong inference only — flip()/load logic byte-identical to the proven forceenable build).

### Root-bridge fix → logcat-bridge v1.3.0 (built, awaiting user flash+reboot)
Added read-only `proc <pid> <leaf>` verb (whitelist maps|cmdline|status|comm|smaps_rollup|stat|wchan|oom_score|cgroup|environ; numeric-pid; cat-only; `/proc` kept out of write allowlist). Client `getlog --proc <pid> <leaf>` (symlinked client already live). Zip `/storage/emulated/0/Download/logcat-bridge-magisk-v1.3.0.zip` (supersedes never-flashed v1.2.0). User WILL flash + reboot, relaunch GoW on Legacy, then return.

### RESUME (next session, after user returns)
1. `getlog --ping`→pong; `getlog --proc 1 comm`→ not "unknown verb" (confirms v1.3.0 live).
2. `getlog --ps` → find `com.antutu.benchmark.full` `:wine`/`:winemu` child pid.
3. `getlog --proc <pid> maps` → grep `libxserver_legacy.so` + `libwinemu_legacy.so`.
   - both present = DEFINITIVE proof clean build loads 6.0.2 pair → cleanup validated → MERGE to `gamehub-604-build` (NOT Lite-first), test there, then refresh Lite.
   - only stock libs = cleanup regressed the load path → investigate, do NOT merge.
4. Then decide optional permanent breadcrumb (one line to `getFilesDir()/bh_renderer.log` in legacy branch — prod observability, not the stripped diag harness).
Mergeable delta vs `gamehub-604-build`: 18 commits/18 files/+2073−34, no junk; only conscious change = GPU Spoof global→per-game-from-menu; +4.6 MB (2 .so).

## 2026-05-18 — Clean build BIDIRECTIONALLY verified LIVE → MERGED to gamehub-604-build → artifact build

### Verification (logcat-bridge v1.3.0 `--proc` verb, live GoW, full pkg `com.antutu.benchmark.full`)
RESUME plan executed. Bridge v1.3.0 proc verb confirmed live post-reboot. Both renderer modes proven against the live running GoW `:wine` process — DEFINITIVE, not inferred from the (stale) diag log:
- **Legacy run** (pid 8652): `/proc/8652/maps` = `libxserver_legacy.so` + `libwinemu_legacy.so`, NO stock. `bh_renderer_diag.log` correctly NOT freshly written (diag stripped) — corroborates this is the clean build.
- **Vulkan run** (pid 15133, same APK install hash `XkQ1IwHRAfdeZx0zIdv8OA==`): `/proc/15133/maps` = stock `libxserver.so` + `libwinemu.so` + `/vendor/lib64/hw/vulkan.adreno.so` + `/system/lib64/libvulkan.so`, NO `_legacy`.
Same APK toggles both ways cleanly, no regression either direction. Cleanup (`44b2e4e`) validated. (Capture-method note: `getlog --proc` sometimes writes a `/home/*.txt` file, sometimes prints maps to stdout — handle both.)

### Merge
`git checkout gamehub-604-build` (was `792ae69`, == origin) → `git merge --no-ff feature/legacy-renderer-toggle` → merge commit **`01b2f4d`** (18 files, +2101−34, 2 bundled 6.0.2 .so, no conflicts — gamehub-604-build was fully contained in the feature branch). Author The412Banner, no Claude trailer. Pushed `792ae69..01b2f4d gamehub-604-build`. Brings: per-game Vulkan↔legacy renderer swap (forced-enable permanent, diag stripped); shared `BhMenuGameId`+`MenuGameIdCapturePatch` (conscious change: GPU Spoof global-from-menu → per-game-from-menu); Task #20 library-list-popup rows.

### Artifact-only CI
`gh workflow run release.yml --ref gamehub-604-build -f version=1.3.0-604-renderer-merged-pre1` (stable defaulted false = artifact-only prerelease). Run **26035851236** (https://github.com/The412Banner/bannerhub-revanced/actions/runs/26035851236), queued.

### NEXT
- Watch run 26035851236 → green + 0 SEVERE + renderer/menu patches succeeded 9/9.
- Then refresh dependent Lite branch `feature/lite-variant-tier1` off the new `gamehub-604-build` (NOT Lite-first) and re-cut the Lite artifacts.
- M3 device-test on the user's actual **Lite** (`banner.hub`) install — the only open device-test gap (full-pkg is proven bidirectionally).

### CI RESULT — run 26035851236 ✅ GREEN
`conclusion=success`; all 9 Patch jobs success, `Create GitHub Release` skipped (correct for stable=false). Log-scanned (125 files, 950K): **0 `SEVERE`**, 0 patch failures. alt-AnTuTu job confirms applied: "Legacy renderer conditional swap" / "Legacy renderer libxserver bundle" / "Renderer menu row" / "Renderer settings activity" / "Per-game menu id capture (shared)" + GPU Spoof (DXVK plumbing/menu row/settings) + PC-accurate vibration (label/menu row/settings) — all `succeeded`. APK delivered: `/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-renderer-merged-pre1-Patched-alt-AnTuTu.apk` (116,042,150 B, md5 `75b4cf73def5f71435bbda812542f717`). Merge `01b2f4d` validated on `gamehub-604-build`. **NEXT: refresh Lite branch `feature/lite-variant-tier1` off new gamehub-604-build → M3 device-test on `banner.hub`.**

---

## 2026-05-18 — Offline component picker fix (components + ordering + Wine/Proton containers) — DEVICE-CONFIRMED, MERGED

**Problem:** On 6.0.4, offline, the per-game pickers (GPU driver, DXVK, VKD3D, FEX/Box64 translators, Wine/Proton container) showed only the server-recommended/built-in set — never the user's already-downloaded components, despite them being on disk + catalogued in `sp_winemu_unified_resources.xml`. The prior `OfflineComponentCachePatch` (mci.a hook) was forensically proven inert on 6.0.4 (wrong subsystem).

**Root cause (model-free, sink-verified after ~8 refuted hypotheses):** pickers are fed by `gof.a(ComponentType,…)` (components, via `simulator/v2/getComponentList`) and `gof.c(Continuation)` (containers, via `getContainerList`); both run offline but the network fetch fails → empty. `zxf.a`/`zxf.c` unwrap a uniform sealed result `Lo55;` → `Ln55;`(success).a = `List<EnvLayerEntity>` (NOT the `gof.b`-internal `BaseResult`/`Lc91;`). Not `myo`/`j7o`/`api_cache`/`so7` (all empirically eliminated).

**Fix (`offlineComponentListPatch`):** replace `gof.a` body → `OfflineComponentList.dispatch` (offline → `n55(List<EnvLayerEntity>)` synthesised from `sp_winemu_unified_resources` `COMPONENT:` entries filtered by `ComponentType.type`, `Unsafe.allocateInstance` + reflective field-set; online/fail → reflective `gof.b` passthrough). Add `gof.c` index-0 conditional short-circuit → `OfflineComponentList.getContainers()` (`CONTAINER:` entries; Wine/Proton). Stable-sort by `OfflineComponentOrder` (generated canonical catalog order) so DXVK/GPU match online exactly (newest-at-bottom, curated interleaving). Fully fail-safe → original path on any error. Removed dead `OfflineComponentCachePatch` + `PickerCacheFallback`.

**Verification:** device-confirmed on `banner.hub` Normal-Lite offline — `bh_offline_list.log`: `getContainers built=10 OK`, `getList type=1 built=36 / type=2 built=263 / type=3 built=46 / type=4 built=7 OK`; pickers populate with downloads + built-ins, DXVK/GPU correctly ordered; online byte-identical; no regression.

**Merge:** `fix/offline-picker-merge` → `gamehub-604-build` `--no-ff` (`dbd7554`). README patch-catalog section rewritten (was "⚠ currently broken"). NEXT: refresh `feature/lite-variant-tier1` off new `gamehub-604-build`; CI-validate (release.yml prerelease, expect 0 SEVERE).

---

## 2026-05-18 — Per-game settings isolation: GPU Spoof model STILL resets to first entry (pre3)

**Branch:** `fix/per-game-settings-isolation` (off `gamehub-604-build`).

**Symptom (device, pre2 build `9ccbf47`):** GPU Spoof — pick vendor + model → Save → reopen → mode/vendor restore correctly but the **Model spinner snaps back to the first/top entry**. pre2's fix (move spinner listeners to *after* the restore block) did not resolve it.

**Root cause:** `Spinner.setSelection(int)` does **not** invoke `onItemSelected` synchronously — AdapterView defers the selection callback to the next layout pass. So the restore-time `vendorSpinner.setSelection(vSel)` queues a callback that fires *after* the (already-attached) vendor listener is live, runs `rebuildModels(modelSpinner, vIdx, 0)`, and clobbers the just-restored model back to index 0. Listener-attach ordering can't fix an async callback.

**Fix (pre3):** make the vendor listener idempotent. Added field `lastVendorIdx`, set inside `rebuildModels()` to the vendor it just built for. The vendor listener early-returns when `vIdx == lastVendorIdx` (the deferred restore callback → no-op, model preserved); only an actual vendor change (different index) rebuilds the model list with sel=0. Robust regardless of listener-attach timing. Renderer/Vibration unaffected (no cascading model spinner).

**Lesson:** `Spinner.setSelection()` callbacks are asynchronous (next layout) — guarding by listener-attach order is insufficient; guard the listener body on a real value change instead.

### Artifact-only CI
`gh workflow run release.yml --ref fix/per-game-settings-isolation -f version=1.3.0-604-pergame-pre3` (stable defaulted false = artifact-only prerelease). Expect green + 0 SEVERE; deliver alt-AnTuTu APK. Retest: GPU Spoof Spoof-mode → vendor+model → Save → reopen shows the exact card; per-game isolation + in-game apply intact.

---

## 2026-05-18 — GPU Spoof not applied at launch + customDeviceDesc truncation (pre4)

**Branch:** `fix/per-game-settings-isolation`.

**Symptom (device):** God of War (gameId 49908) set to NVIDIA RTX 4080 via per-game menu, but in-game still reports the real Adreno identity "GameFusion".

**Diagnosis (root-bridge forensics):** per-game store is *correct* — `bh_gpuspoof_prefs.xml` (mtime 22:04 = launch) has `bh_gpuspoof_mode__49908=1`, `device__49908=2704`, `name__49908=NVIDIA GeForce RTX 4080`; the new system correctly does NOT write `pc_g_setting49908.xml` (its `bh_gpuspoof_*` keys are orphaned legacy, unsuffixed, mode=0, file mtime 09:24). But the launch-time apply did not fire: `bh_gpuspoof_dxvk.conf` + `GoW_*.log` stale from 05-17, zero `BhGpuSpoof` logcat. Cause: `applyGpuSpoofImpl()` resolved gameId via `sniffGameIdFromStack()` *only*; the `Lbg5;->a` env builder runs before `WineActivity` is in `ActivityThread.mActivities`, so sniff → null → `containerGameId=null` → mode OFF → silent no-op (no conf write). The menu-row click already resolves via `BhMenuGameId.getCaptured()` (→ correct store keying), but the controller's launch path never consulted it.

Secondary bug (from the one prior successful 05-17 GoW_dxgi.log): DXVK's inline `DXVK_CONFIG` value parser splits on whitespace, so `dxgi.customDeviceDesc = NVIDIA GeForce RTX 4080` was truncated to `NVIDIA`. IDs (space-free) applied fine.

**Fix (pre4):**
1. **Launch gameId capture.** `applyGpuSpoofImpl()` now resolves `gid = BhMenuGameId.getCaptured()` first, falling back to `sniffGameIdFromStack()` — identical order to `BhGpuSpoofMenuRowClick`. The shared `MenuGameIdCapturePatch` (already a dependency of the GPU Spoof menu row) stashes the id when the user opens the per-game menu, so the launch path now scopes to the right game even though WineActivity isn't yet on the stack.
2. **customDeviceDesc truncation.** Inline `DXVK_CONFIG` env now carries ONLY the space-free numeric IDs (dxgi/d3d9/dxvk customVendorId/customDeviceId). The space-containing `dxgi.customDeviceDesc` is written only into the dxvk.conf file body (file parser handles spaces); `DXVK_CONFIG_FILE` points at it and DXVK keeps the file value since the env no longer overrides that key.

### Artifact-only CI
`gh workflow run release.yml --ref fix/per-game-settings-isolation -f version=1.3.0-604-pergame-pre4` (stable false). Retest: GoW set NVIDIA → launch → in-game GPU reads NVIDIA (not GameFusion); `bh_gpuspoof_dxvk.conf` rewritten at launch with full `customDeviceDesc`; `BhGpuSpoof: GPU spoof active 10de:2704` in logcat; per-game isolation + model-persistence (pre3) intact.

---

## 2026-05-18 — GPU Spoof: ROOT CAUSE = main↔`:wine` process boundary (pre5)

**Branch:** `fix/per-game-settings-isolation`. Commit `292fba6`. CI run **26072733452** GREEN, 0 SEVERE.

**pre4 device test FAILED — same symptom:** GoW (49908) set NVIDIA RTX 4080, launched 22:23. Forensics: `bh_gpuspoof_prefs.xml`@22:23 CORRECT (`mode__49908=1 device__49908=2704 name__49908=NVIDIA GeForce RTX 4080`); `bh_gpuspoof_dxvk.conf` STALE 05-17 18:00 (NOT rewritten at launch); `launchLog49908.txt`@22:23 has NO DXVK_CONFIG/VK_ICD/GAMESCOPE env.

**ROOT CAUSE (decompiled AndroidManifest):** `com.xj.winemu.WineActivity` is declared `android:process=":wine"`. The pre4 fix (`BhMenuGameId.getCaptured()` at launch) **cannot work by construction**: `captureGameId()` fires on menu-open in the MAIN UI process and sets the `static volatile sCapturedGameId`; the env builder `Lbg5;->a` runs inside WineActivity in the **separate `:wine` process** where that static is unset. `getCaptured()`→null, `sniffGameIdFromStack()`→null (pre-registration) ⇒ `containerGameId=null` ⇒ strict-per-game store yields `MODE_OFF` ⇒ early-return BEFORE the unconditional conf write ⇒ stale conf, stock adapter. The disk-backed store crosses processes fine; only the gameId KEY was lost.

**Confirmed against the user's "worked fine" build** (run 26064254882 = branch `test/offline-picker-merge-lite` @ `06fdd05`): that commit does NOT contain the strict-per-game rework; its `reloadSettings()` still reads the **GLOBAL** `bh_gpuspoof_prefs` fallback. So it "worked" by applying the spoof globally at launch — the `:wine` defect existed there too, just masked by the global fallback. Removing that fallback for true per-game isolation (kills app-wide leak + DiRT3 deep-blackscreen) UNMASKED the latent defect. Not a regression; the old design never truly scoped per-game at launch.

**Fix (pre5, `292fba6`):** `BhMenuGameId` now mirrors the captured id to SharedPreferences (`bh_menu_gameid`/`id`, synchronous `.commit()`) on every `captureGameId`, and `getCaptured()` falls back to that disk value (caching into the static) when the in-process static is empty. SharedPreferences cross `:wine` exactly like the per-game store. Extension-only, no patch/anchor change; lint-compiles clean vs android-34. Fixes GPU Spoof + Renderer + Vibration in one shared place (all three share this capture).

**Lesson:** `WineActivity` = `:wine` process. ANY Java static set in the UI/menu process is invisible to launch-time code (`Lbg5;->a` / `applyGpuSpoof`). Cross the process boundary via disk (SharedPreferences/file), never a static.

### Delivered
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre5-Patched-alt-AnTuTu.apk` (116,095,398 B, md5 `034051d627d0a58905d3e5223eaa1ee8`).

**Retest (GoW 49908):** set NVIDIA → launch → in-game reads NVIDIA not GameFusion; `bh_gpuspoof_dxvk.conf` mtime updates at the launch; new `shared_prefs/bh_menu_gameid.xml` has `<string name="id">49908</string>` (proves gameId crossed into `:wine`); `BhGpuSpoof: GPU spoof active 10de:2704` logged. **NOT merged** — hold until device-confirmed.

### pre5 ✅ DEVICE-CONFIRMED (2026-05-18 22:44, GoW 49908 → NVIDIA RTX 4090)
Verified chain: `shared_prefs/bh_menu_gameid.xml` = `<string name="id">49908</string>` (gameId crossed the main→`:wine` boundary — the decisive proof the disk-bridge works); `bh_gpuspoof_dxvk.conf` rewritten at launch (mtime 22:44; was stale 05-17 on pre4) with body `10de:2684` + `customDeviceDesc = NVIDIA GeForce RTX 4090` (full, untruncated — pre4 secondary fix holds); **in-game GPU reads NVIDIA** (previously GameFusion). No `BhGpuSpoof` logcat line (AnTuTu floods/rolls the buffer) — the fresh conf + in-game identity are hard proof the launch apply fired. Root cause (`:wine` process boundary defeats Java statics) and the SharedPreferences disk-bridge fix are both validated. The shared `BhMenuGameId` bridge also corrects Renderer + Vibration launch-scoping (same boundary).

**NOT merged — pending user go.** On go: `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` (author The412Banner, no Claude trailer) → refresh `feature/lite-variant-tier1` off new 604 → update README + master-map (note the `:wine`-boundary lesson + that it fixes all three per-game features).

---

## 2026-05-18 — Renderer: SAME `:wine` boundary bug, fixed (pre6)

**Branch:** `fix/per-game-settings-isolation`. Commit pushed; CI run **26073340887** GREEN, 0 SEVERE; renderer/menu-id/gpu-spoof patches all succeeded.

**Device evidence (GoW 49908 left running on Legacy):** `bh_renderer_prefs.xml` correct (`bh_renderer_mode__49908=1`; leftover unsuffixed `bh_renderer_mode=1` = the OLD global that used to mask this), `bh_menu_gameid.xml`=49908, `libxserver_legacy.so`+`libwinemu_legacy.so` present in APK lib dir — yet `/proc/4566/maps` (running `:wine` pid) showed **STOCK** `libxserver.so`+`libwinemu.so`, NOT `_legacy`. Legacy silently no-op'd to stock.

**Root cause = identical to GPU Spoof.** `loadXserver`/`loadWinemu` (replace `System.loadLibrary` in `XServer.<clinit>`, run in the `:wine` process) resolved gameId via `sniffGameIdFromStack()` ONLY → null because the lib swap fires before WineActivity is registered in `:wine`'s mActivities. Pre-rework this was masked by the global fallback (the leftover unsuffixed key); the strict-per-game rework removed the fallback and unmasked it. The earlier PROGRESS/table claim "Renderer sniff works post-WineActivity" was WRONG — disproven by /proc maps.

**Fix (pre6):** new `BhRendererController.launchGameId()` = `BhMenuGameId.getCaptured()` (pre5 SharedPreferences disk-bridge crosses the main↔`:wine` boundary) then `sniffGameIdFromStack()` fallback — identical order to `BhGpuSpoofController.applyGpuSpoofImpl`. All three launch-resolution sites repointed: `isLegacyForLaunchingGame`, `loadXserver`, `loadWinemu`. Imports `BhMenuGameId`; lint-compiles clean vs android-34.

**Corrected rule:** ANY launch/clinit-time per-game resolver running in the `:wine` process MUST use `BhMenuGameId.getCaptured()` first (disk-bridge), never sniff-only. Sniff-only is safe ONLY for genuinely mid-gameplay hooks (e.g. Vibration's rumble-dispatch).

### Delivered
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre6-Patched-alt-AnTuTu.apk` (116,054,438 B, md5 `06e4ca64e657e0a30ae96e6cd78e2c11`).

**Retest:** GoW set Legacy → launch → leave running → `/proc/<:wine pid>/maps` must show `libxserver_legacy.so`+`libwinemu_legacy.so` (NOT stock); a game NOT set Legacy stays stock (no leak). **Vibration still needs its own device check** (per-game rumble; sniff-at-rumble-time probably OK but verify, don't assume). GPU Spoof pre5 already device-confirmed. **NOT merged** — hold until renderer + vibration device-confirmed.

### pre6 RENDERER ✅ DEVICE-CONFIRMED (2026-05-18 23:07, GoW 49908 Legacy, running)
Live `:wine` process (pid 17125) `/proc/17125/maps`: `libxserver_legacy.so` + `libwinemu_legacy.so` loaded, **NO stock `libxserver.so`/`libwinemu.so` mapped** (pure 6.0.2 legacy pair); wineserver + full wine guest tree alive → sustained, not crashing. pre6 `launchGameId()` (getCaptured disk-bridge → sniff) correctly resolves gameId 49908 in `:wine` at `XServer.<clinit>`; `isLegacyForGame(49908)`→true. The `:wine` boundary defect is fixed for Renderer exactly as for GPU Spoof.

**Confirmed status:** GPU Spoof (pre5) ✅ + Renderer (pre6) ✅ device-confirmed. **Vibration** still needs its own device check (per-game rumble) — sniff-at-rumble-time is expected OK but unverified; do not assume. **NOT merged** — hold until Vibration device-confirmed, then: `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` (The412Banner, no Claude trailer) → refresh `feature/lite-variant-tier1` → README/master-map.

---

## 2026-05-18 — Vibration durable breadcrumb (pre7)

**Branch:** `fix/per-game-settings-isolation`. CI run **26073891283** GREEN, 0 SEVERE; vibration/renderer/gpu-spoof/menu-id patches all succeeded.

**Why:** Vibration has no forensic fingerprint — no per-launch file, no distinct lib, and the AnTuTu logcat flood rolls the `Log.i` markers before they can be read. So unlike GPU Spoof (`dxvk.conf`) and Renderer (`_legacy.so` in `/proc/maps`), there was no on-disk way to prove the rumble-dispatch path resolved the correct per-game scope. (The store itself was verified correct & strict: `bh_vibration_{mode,intensity}__<gid>`; leftover unsuffixed `mode/intensity` = dead old global, not read.)

**pre7 (`feat(vibration)`):** `BhVibrationController.breadcrumb()` appends two one-shot lines per process to `<filesDir>/bh_vibration.log`:
- `RESOLVE gid=<g> mode=<m> intensity=<i>` — at `maybeResolveContainerFromActivityStack` container resolve
- `RUMBLE#1 gid=<g> mode=<m> intensity=<i> low=<l> high=<h>` — at first `handleRumble`

Append-only, `appContext`-guarded, never throws; gated by static volatile `sBcResolved`/`sBcRumble`. Production-safe observability (not the stripped diag harness). Lint-clean vs android-34.

### Delivered
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre7-Patched-alt-AnTuTu.apk` (116,107,686 B, md5 `41149852f9982fb23114be1a2084d455`).

**Verify on pre7:** set per-game vibration on the game to be launched (e.g. GoW 49908 mode=1 int=100) AND a *different* value on another game → launch → trigger controller rumble in-game → read `bh_vibration.log`. RESOLVE/RUMBLE#1 `gid` must equal the launched game and `mode`/`intensity` must equal *that game's* per-game value (proves dispatch scoped correctly + no app-wide leak).

**Confirmed:** GPU Spoof (pre5) ✅ + Renderer (pre6) ✅ device-confirmed. Vibration store correct; dispatch awaiting pre7 breadcrumb read. **NOT merged** — hold until Vibration confirmed, then merge `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` → refresh `feature/lite-variant-tier1` → README/master-map.

---

## 2026-05-18 — Vibration ~2s-cutoff investigation: durable keepalive/guest trace (pre8)

**Branch:** `fix/per-game-settings-isolation`. CI run **26074175298** GREEN, 0 SEVERE; vibration/menu-id patches succeeded.

**Question:** user testing with GameConTest.exe — rumble stops ~2 s. **Verified by code:** rumble is engineered continuous — `CONTROLLER_RUMBLE_MS=2000` effect re-armed by `RUMBLE_KEEPALIVE_MS=1500` keepalive (runnable 60 ms tick) → must outlast 2 s while the guest holds a non-zero amplitude. A clean ~2 s stop ⇒ exactly one of: (a) guest itself sends (0,0) ~1 s after non-zero = SDL/winebus auto-expiry (keepalive map cleared by the (0,0), correctly no re-arm) → fix is guest-side (winebus duration patch / evshim keepalive); or (b) guest stays non-zero but keepalive isn't re-firing host-side. `DEFAULT_MODE=MODE_CONTROLLER`; keepalive map populated only for CONTROLLER/BOTH via `recordKeepalive`.

**pre8 (`feat(vibration)`):** two capped (`BC_TRACE_CAP=40`) channels appended to `<filesDir>/bh_vibration.log`, independent of the DIAG-stripped `logGuestTransition`:
- `GUEST slot=<s> <pl>,<ph> -> <nl>,<nh> gap=<ms>` — each real XInput transition; flags non-zero→(0,0) in 800–1300 ms as `[SDL_AUTO_EXPIRY?]`
- `KEEPALIVE dev=<id> low=<l> high=<h> mode=<m>` — each controller re-arm fire
Append-only, `appContext`-guarded, never throws; counters cap so the file can't grow unbounded.

### Delivered
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre8-Patched-alt-AnTuTu.apk` (116,062,630 B, md5 `502498644dfc0dae67b23e1096306490`).

**Read on pre8 after the cutoff:** `GUEST` non-zero→(0,0) gap≈1000 ms `[SDL_AUTO_EXPIRY?]` ⇒ guest-side stop (winebus/SDL); `GUEST` stays non-zero + sparse/no `KEEPALIVE` ⇒ host keepalive not firing. Also doubles as the Vibration per-game scope proof (RESOLVE/RUMBLE#1 gid + mode/intensity). GameConTest input mode (held slider vs timed test button) must be noted. **Confirmed:** GPU Spoof (pre5) ✅ + Renderer (pre6) ✅. Vibration scope + 2 s behavior pending pre8 read. **NOT merged.**

---

## 2026-05-18 — Vibration: two bugs found from pre8 trace; per-game scope fixed (pre9)

**Branch:** `fix/per-game-settings-isolation`. CI run **26074438727** GREEN, 0 SEVERE; vibration/menu-id patches succeeded.

**pre8 `bh_vibration.log` (GameConTest.exe, 23:27):** `RUMBLE#1 gid=(global) mode=1 intensity=100`, **NO RESOLVE line**, repeated `GUEST slot=0 65535,0 -> 0,0 gap≈1000ms [SDL_AUTO_EXPIRY?]`, **ZERO KEEPALIVE lines**.

**Bug 1 — per-game scope broken (same `:wine` defect, 3rd feature):** no RESOLVE + `gid=(global)` ⇒ `maybeResolveContainerFromActivityStack()` never resolved (sniff null in `:wine`); vibration ran on strict defaults (`DEFAULT_MODE=MODE_CONTROLLER=1`, `DEFAULT_INTENSITY=100`), NOT the per-game 49908 value (values coincide but unscoped — an Off-set game would still rumble). The earlier "vibration resolves at rumble-time post-WineActivity" reasoning was wrong — device disproved it (same as the renderer surprise). **Fixed (pre9):** `maybeResolveContainerFromActivityStack()` now prefers `BhMenuGameId.getCaptured()` (pre5 SharedPreferences disk-bridge) before the stack sniff; imports `BhMenuGameId`; emits `RESOLVE … src=menuid`. Identical fix to GPU Spoof pre5 / Renderer pre6. Lint-clean. **Final rule: every per-game resolver runs in `:wine` where sniff is null — all three needed the disk-bridge; no `:wine`-side sniff-only path is ever safe.**

**Bug 2 — ~2 s cutoff = guest-side SDL/winebus ~1 s auto-expiry, NOT host keepalive:** GUEST trace shows the guest itself sending `(0,0)` ~1000 ms after each non-zero; zero KEEPALIVE is *correct* (`recordKeepalive(…,0,0)` clears the entry when the guest zeroes, so the host rightly doesn't re-arm — re-arming would rumble during intended silence). Host keepalive cannot fix this; the stop originates in Wine ⇒ the preload-free `winebus.so` duration patch is not effective in this Proton10-arm64x GoW (49908) container. **PARKED by user** — separate track, revisit after the per-game work is merged. Not a host-side change.

### Delivered
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre9-Patched-alt-AnTuTu.apk` (116,037,995 B, md5 `8b7d24e1c077fc5171a040844266ae7e`).

**Verify on pre9:** set GoW 49908 vibration to **Off (mode 0)** (distinct from default MODE_CONTROLLER) → launch → trigger rumble. Pass = `bh_vibration.log` has `RESOLVE gid=49908 src=menuid` AND no rumble (per-game scope actually applied, not coincidental defaults). The ~1 s stutter (Bug 2) will still be present — parked, ignore.

**Confirmed:** GPU Spoof (pre5) ✅ + Renderer (pre6) ✅. Vibration per-game scope pending pre9 re-read. **NOT merged** — hold until Vibration scope confirmed, then merge `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` → refresh `feature/lite-variant-tier1` → README/master-map; Bug 2 stays a parked separate track.

---

## 2026-05-19 — Vibration scope pre9 DEVICE-CONFIRMED; Bug 2 read-only inspection (root cause corrected); pre10 winebus breadcrumbs

**Branch:** `fix/per-game-settings-isolation`. CI run **26075313929** (pre10), artifact-only (stable=false).

### pre9 device read — Vibration per-game scope ✅ CONFIRMED
`bh_vibration.log` (GoW 49908, 2026-05-18 23:37):
```
2026-05-18 23:37:08 RESOLVE gid=49908 src=menuid mode=1 intensity=100
2026-05-18 23:37:08 RUMBLE#1 gid=49908 mode=1 intensity=100 low=2048 high=2048
```
vs pre8 (23:27): `RUMBLE#1 gid=(global)`, no RESOLVE. The pre5 disk-bridge resolve fired in `:wine`; per-game vibration scope works. **All three per-game features now device-confirmed: GPU Spoof pre5 ✅ · Renderer pre6 ✅ · Vibration pre9 ✅.** Branch is at the merge gate — **HELD pending user merge-go** (user explicitly said no merge yet).

### Bug 2 — read-only winebus inspection (user-authorized); earlier hypothesis DISPROVEN
Replicated `patchWinebusDurationFile()` checks read-only on the live container via the root bridge. The arm64x winebus.so GoW 49908 uses — `…/files/usr/opt/wine_proton10.0-arm64x-2/arm64-v8a/lib/wine/aarch64-unix/winebus.so` (the only arm64 winebus in the 53,279-file tree):

| Gate | Value | Verdict |
|---|---|---|
| ELF / `e_machine` | `0xb7` AARCH64 | aarch64 patch path |
| "SDL_JoystickRumble" | present (×4) | passes pre-gate |
| ORIGINAL `a3c35eb8 00013fd6` | **exactly 2** | **pattern matches perfectly** |
| PATCHED `03008012 00013fd6` | **0** | file unpatched |
| depth / files / skip-dir | 8 / 53279 / none | < 16 / < 100k / not skipped |
| mtime | 04:51 (= proton-extract) | never rewritten at 23:37 launch |

`patchAarch64Sites()` with `originalCount==2, patchedCount==0` would **succeed** — the aarch64 byte-pattern is correct for Proton10-arm64x. The old note ("pattern not effective on Proton10-arm64x") is **wrong**. Both winebus.so (arm64x + x64) retain extraction mtimes with 0 patched sites ⇒ **`ensureWinebusDurationPatchOnce()` never executes its write**. Hook 4's `invoke-static` at the `Lbg5;->a` env-builder anchor either isn't firing in the 6.0.4 base or throws and is swallowed by the method's `catch(Throwable)`→`Log.w` (rolled out of AnTuTu logcat). **⇒ Bug 2 IS host-side-fixable** — the disk-patcher is simply a no-op; not a guest-side dead end as previously parked. Same *family* as the `:wine`-boundary bugs (env builder runs in `:wine`) but a DISTINCT bug.

### pre10 — durable WINEBUS breadcrumbs (commit 848ba1f)
Added static `bcWinebus(ctx, …)` (mirrors instance `breadcrumb`) → `<filesDir>/bh_vibration.log`, prefixed `WINEBUS`, at every patcher decision point: hook-fired (ctx + pid), scan guard outcomes, scan summary (root/files/winebus/patched/already/ms), per-file elf/rumble/machine, aarch64+x86_64 original/patched counts + APPLIED/MISMATCH/ALREADY-PATCHED. Log-only, zero patch-behaviour change; `patchAarch64Sites`/`patchX86_64Sites` gained an internal `Context` param (both call sites updated). Brace-balanced, 16 breadcrumb calls.

### MORNING CONTINUATION — exactly where we left off
1. **Install** the delivered pre10 full alt-AnTuTu APK (pkg `com.antutu.benchmark.full`).
2. **Launch GoW 49908** (any vibration setting). Then read `bh_vibration.log` and grep `WINEBUS`:
   - **No `WINEBUS hook fired` line** ⇒ Hook 4's `invoke-static` at `Lbg5;->a` is not executing → re-anchor the patch (R8 map drift / wrong join-setup index / `:wine` proc).
   - `hook fired` present but **no `scan` line** / `scan EXCEPTION` ⇒ ctx issue or thrown+swallowed.
   - `scan … winebus=0` ⇒ scan ran but didn't reach the file (tree-root / listFiles perms / timing vs extraction).
   - `aarch64 MISMATCH` ⇒ (won't happen — inspection proved match, but covered).
   - `aarch64 APPLIED original=2 …` then **still** ~1 s cutoff ⇒ patch wrote but a *different* winebus.so is loaded at runtime (check live `:wine` `/proc/<pid>/maps`).
3. Fix whichever branch the breadcrumb pinpoints (most likely: re-anchor Hook 4). Keep Bug 2 a separate track from the per-game merge.
4. **Per-game merge still HELD** awaiting explicit user go: `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` (author The412Banner, no Claude trailer) → refresh `feature/lite-variant-tier1` → README/PROGRESS_LOG/master-map.

### Delivered
CI run **26075313929** GREEN, **0 SEVERE**; "PC-accurate vibration" + "Per-game menu id capture (shared)" + GPU Spoof/Renderer/Vibration patches all `succeeded` on alt-AnTuTu (artifact-only, stable=false).
`/storage/emulated/0/Download/BannerHub-V6-1.3.0-604-pergame-pre10-Patched-alt-AnTuTu.apk` (116,070,822 B, md5 `3547fe9d7ace930562a74fce84519b07`). Full pkg `com.antutu.benchmark.full` (installs alongside `banner.hub` Lite).

### pre10 DEVICE READ (2026-05-19 04:51, GoW 49908) — Hook 4 DEAD-CODE injection found
Installed APK md5 `3547fe9d…` = pre10 confirmed (`/data/app/.../base.apk`). `bh_vibration.log` 04:51:24 block has `RESOLVE gid=49908 src=menuid` + `RUMBLE#1 gid=49908` (controller breadcrumbs fire; pre9 per-game scope re-confirmed) but **`grep -c WINEBUS` = 0** — not even the entry breadcrumb.
Decompiled the delivered pre10 APK (`apktool d -r`): the injected pair landed in `smali_classes5/bg5.smali`, the sole `a(Leco;Ljava/lang/String;Z)V` `.locals 35` env builder (correct method) — but **immediately after an unconditional `goto :goto_4` and before a `:cond_9` label, with no label of its own** ⇒ unreachable dead code ⇒ `ensureWinebusDurationPatchOnce` never called. Root cause = the fragile `setupStartIdx = joinIdx - 5` anchor: in the 6.0.4 base (versionCode 114) `joinIdx - 5` falls inside the ArrayList-building loop, not the `:`-separator arg-setup block. Not a pattern miss, not an R8 letter shift — purely a wrong relative offset. Resolves the prior "patcher never executes" hypothesis with proof.

### pre11 — Hook 4 re-anchored to method entry (index 0)
Dropped the `joinIdx - 5` arithmetic and the now-dead join-helper machinery (`indexOfFirstInstructionOrThrow`/`getReference`/`Opcode`/`MethodReference` imports, `JOIN_HELPER`/`JOIN_METHOD`/`JOIN_LAMBDA` consts + doc). Hook 4 now injects at **index 0** of `Lbg5;->a` — unconditionally reached every launch, same guaranteed spot Hooks 1–3 use:
```
move-object/from16 v0, p0
iget-object v0, v0, Lbg5;->a:Landroid/content/Context;
invoke-static {v0}, Lcom/xj/winemu/vibration/BhVibrationController;->ensureWinebusDurationPatchOnce(Landroid/content/Context;)V
```
`p0`=this (high reg under `.locals 35`); v0 clobbered but the method's own first instr `move-object/from16 v0, p0` re-inits it immediately. `ensureWinebusDurationPatchOnce` is AtomicBoolean-gated so an at-entry call is correct + self-deduplicating. No labels added (trailing-label footgun N/A at index 0). 1 file, +23/−37.

### MORNING CONTINUATION (updated)
1. Install pre11 full alt-AnTuTu APK; launch GoW 49908.
2. `grep WINEBUS bh_vibration.log` — now the entry/scan/per-file/aarch64 breadcrumbs MUST appear. Then follow the same branch table as pre10 (expect `aarch64 APPLIED original=2`; if so and rumble still cuts, check live `:wine` `/proc/<pid>/maps` for which winebus.so is mapped).
3. Per-game merge still HELD awaiting explicit user go (unchanged).

## 2026-05-19 — 🚀 v1.4.0-604 STABLE SHIPPED

### pre11 DEVICE-CONFIRMED + Bug 2 cured
pre11 (Hook 4 re-anchored to `Lbg5;->a` index 0) device-confirmed 05:05 (GoW 49908): `bh_vibration.log` shows `WINEBUS hook fired`, `x86_64 APPLIED original=2`, `aarch64 APPLIED original=2`, `scan patched=2`; later `:wine` procs report `ALREADY-PATCHED` (disk patch persists + survives the main↔`:wine` boundary). Bug 2 (~2 s rumble cutoff) **cured** — GameConTest.exe 05:14 run: heavy motor held ~5 s, light motor ~7 s, **zero `[SDL_AUTO_EXPIRY?]` flags** (vs the unpatched `gap≈1000ms` signature), KEEPALIVE lines now present. User confirmed "longer than 2 seconds."

### Merge + stable cut
- `fix/per-game-settings-isolation` → `gamehub-604-build` `--no-ff` merge `0dcd910` (19 commits `3d85d45`→`1bb5156`; author The412Banner, no Claude trailer). Lite refreshed via `--no-ff` `c3f4619`; 4 strips verified intact.
- README stale vibration-storage paragraph corrected (`7add007`); v1.4.0-604 release notes + README What's-new authored (`6800fdc`): GPU Spoof / Legacy renderer / strict per-game store / rumble-cutoff fix, `release.yml` "On top of" bumped v1.2.0→v1.3.0 (was stale), version literal parameterized.
- Stable cut: `release.yml` dispatch `--ref gamehub-604-build -f version=1.4.0-604 -f stable=true` → run [`26089552265`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26089552265) **0 SEVERE**, cert SHA-256 `10895a311fe04f95f82e4da5c9a6c041ba9282bf211f1b578fe1cbeb894ce0ba` **unchanged** (in-place updates intact). Lite artifact-only run [`26089555381`](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26089555381) **0 SEVERE**, 9 Lite APKs `gh release upload`ed.
- **21 release assets**: 9 full `BannerHub-V6-1.4.0-604-Patched-*.apk` + 9 `…-Lite.apk` + 3 `.rvp`. Release live, not draft/prerelease.

### Offline component picker omission — corrected
The Offline component picker (merged `dbd7554` 2026-05-18, ancestor of the v1.4.0-604 tag `6800fdc`) was missing from the initial v1.4.0 What's-new (drafted scoped to per-game work). Added on all three surfaces: live release notes (`gh release edit`), `release.yml` template + README subsection (`9574522`), re-synced to Lite (`b689a1a`). README headline "Four"→"Five".

### Final branch state
`gamehub-604-build` @ `9574522`; `feature/lite-variant-tier1` @ `b689a1a`. Pre-release policy resumes — all builds artifact-only until user says "stable" again.

## 2026-05-19 — Release notes: "separate projects / use at your own risk" warning added

User noticed the live `v1.4.0-604` release description did **not** carry the README's `⚠️ Important — please read before installing` block (README.md:33–45: BannerHub v6 ≠ 3.7.x ≠ Lite, separate package/keystore/backend, no in-place cross-update, WIP / barely tested, USE AT YOUR OWN RISK). Verified absent via grep of the live body (zero matches for `own risk` / `SEPARATE projects` / `read before installing` / etc.); confirmed present only in README.

Fix on both surfaces:
- **Live `v1.4.0-604` notes** — block spliced verbatim directly under the `## BannerHub v6 1.4.0-604` header (before "A patched build of…") via `gh release edit --notes-file`; re-verified present (lines 3/5/15).
- **Going-forward template** — same blockquote inserted into `release.yml`'s slim `body: |` block right after the version header, so every future v6 stable emits it automatically. Commit `27d98d4` on `gamehub-604-build` (`4c5ad1a..27d98d4`, 1 file +14), authored The412Banner / the412banner@users.noreply.github.com, no Claude trailer, pushed. No CI trigger (notes-template-only edit).

Branch head: `gamehub-604-build` @ `27d98d4`. (Not added to `gamehub_reports/GAMEHUB_600_MASTER_MAP.md` — that map documents upstream GameHub 6.0 internals, not BannerHub release/doc changes.)

## 2026-05-20 — External launcher (Beacon / ES-DE / Daijishou) port to 6.0.4 (branch `feature/external-launcher`)

Ports PlayDay's 5.3.5 `ExternalLauncherPatch` ("External launcher support") to GameHub 6.0.4. The 5.3.5 hook (`Lcom/xj/landscape/launcher/ui/gamedetail/GameDetailActivity;->initView` + `<intent-filter>` on `GameDetailActivity`) is dead — 6.0.4 has no `GameDetailActivity` (game detail is a Compose screen reached via Compose navigation).

**Discovery — 6.0.4 already has the dispatch natively.** `com.xiaoji.egggame.DeepLinkActivity.onCreate` consumes:
- `app_nav_target` = `"game_detail"` (sswitch_8 hash `-0x19542ac2`, at ~line 3507 of the 6.0.4 smali)
- `app_nav_game_id` (String → int via `Liml;->t0`)
- `app_nav_steam_app_id` (int)
- `app_nav_auto_start_game` (boolean — already powers auto-launch!)
- plus `app_nav_source_id` / `_type` / `_slug` / `app_nav_epic_app_name` as optional metadata

So the port is purely extras-translation glue — no need to rewrite the dispatch.

**New files (branch `feature/external-launcher` off `gamehub-604-build@27d98d4`):**

- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/launcher/ExternalLauncher.java`
  - `static void rewriteIntent(Activity activity, Intent intent)`
  - Matches action via `activity.getPackageName() + ".LAUNCH_GAME"` (per-variant) PLUS the literal `gamehub.lite.LAUNCH_GAME` as a forgiveness fallback for users who copy/paste old 5.3.5-Lite-style Beacon configs against a renamed BannerHub variant.
  - Translates `localGameId` / `steamAppId` / `autoStartGame` → `app_nav_game_id` / `app_nav_steam_app_id` / `app_nav_auto_start_game` + sets `app_nav_target=game_detail` + `target_type=game_detail`.
  - localGameId wins over steamAppId; fall back to steamAppId if localGameId is missing.
  - `type` extra (5.3.5-only) ignored — 6.0.4 dispatcher doesn't need it.
  - Logs to tag `BhExternalLauncher` for getlog verification.

- `patches/src/main/kotlin/app/revanced/patches/gamehub/misc/launcher/ExternalLauncherPatch.kt`
  - Private `externalLauncherManifestPatch` (resourcePatch, `afterDependents`, depends on `changePackageNamePatch`): finds `com.xiaoji.egggame.DeepLinkActivity` in `AndroidManifest.xml`, sets `android:exported="true"` defensively (already true in 6.0.4), and appends an `<intent-filter>` with `<action android:name="$variantPackage.LAUNCH_GAME"/>` + `DEFAULT` category. Variant package read via `packageNameOption.value?.takeIf { it != packageNameOption.default } ?: manifestPackage` (same idiom as `FileManagerAccessPatch`). Idempotency-checked by suffix `.LAUNCH_GAME`.
  - Public `externalLauncherPatch` (bytecodePatch): depends on `sharedGamehubExtensionPatch` + `externalLauncherManifestPatch`. Injects 3 instructions at index 0 of `Lcom/xiaoji/egggame/DeepLinkActivity;->onCreate(Landroid/os/Bundle;)V`:
    ```smali
    invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
    move-result-object v0
    invoke-static {p0, v0}, Lapp/revanced/extension/gamehub/launcher/ExternalLauncher;->rewriteIntent(Landroid/app/Activity;Landroid/content/Intent;)V
    ```
    v0 reuse is safe — the original method's next instruction (`sget-object v0, Lejm;->a:Lghd;`) writes v0 but its result is never read.

**Beacon instructions delta for 6.0.4.** The per-variant `<package>.LAUNCH_GAME` action names in `/storage/emulated/0/Download/beacon instructions.txt` stay correct. The only change is the activity component — `com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity` → `com.xiaoji.egggame.DeepLinkActivity` for ALL variants (the activity FQN sits in the `com.xiaoji.egggame.*` namespace and is unaffected by `ChangePackageNamePatch`, which only rewrites `manifest@package`). Example for the default Lite variant:

```
am launch -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity \
  -a gamehub.lite.LAUNCH_GAME \
  --es localGameId {file_content} --es steamAppId {file_content} \
  --ez autoStartGame true
```

**Re-derivation on future base bumps.** `DeepLinkActivity` is at the package root (not behind R8 letter renames) — stable. Native `app_nav_*` extra names appear as const-string literals in `onCreate`'s smali — grep to verify they still feed the same dispatch on a future base.

Pending: CI dispatch on `feature/external-launcher`, per-patch SEVERE check on the run log per `[[feedback_revanced_verify_patch_applied]]`, device test (Lite variant first via Beacon).

## 2026-05-20 (cont.) — `extlaunch-pre1` Release SEVERE'd → smali register-limit fix

`Build pull request` compiled green ([run 26159954724](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26159954724)) but that workflow does NOT invoke revanced-cli, so it only validates Kotlin compilation. Dispatched artifact-only Release ([run 26160401818](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26160401818), version `1.4.0-604-extlaunch-pre1`, `stable=false`) to get the actual patch-apply check. All 4 patch jobs reported `SEVERE: "External launcher support" failed` with `app.revanced.patcher.patch.PatchException: Collection is empty` — classic silent-no-apply per `[[feedback_revanced_verify_patch_applied]]`. Underlying smali error (printed above the SEVERE line in the log):

```
[5,16] The maximum allowed register in this context is list of registers is v15
[5,0] Cannot invoke "Object.hashCode()" because "key" is null
[5,56] mismatched tree node: UP expecting I_CATCHES
[3,0] A non-abstract/non-native method must have at least 1 instruction
[7,24] mismatched tree node: Lapp/revanced/extension/gamehub/launcher/ExternalLauncher; expecting I_FIELDS
```

Root cause: `DeepLinkActivity.onCreate` declares `.locals 34`, which aliases `p0` to `v34`. The injected `invoke-static {p0, v0}, ...` uses the non-range invoke form, which only accepts 4-bit register references (`v0`–`v15`). `v34` blew that limit on the FIRST register slot, the assembler bailed, the rest of the cascade followed.

**Fix:** drop the `Activity` parameter entirely from the extension. The Intent's action string already contains the variant package (`<pkg>.LAUNCH_GAME`), so we can check `action.endsWith(".LAUNCH_GAME")` without ever calling `getPackageName()`. That collapses the smali to two single-register invokes (`{p0}` alone, then `{v0}` alone), both of which are valid even with `.locals 34`:

```smali
invoke-virtual {p0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
move-result-object v0
invoke-static {v0}, Lapp/revanced/extension/gamehub/launcher/ExternalLauncher;->rewriteIntent(Landroid/content/Intent;)V
```

Behavioral equivalence preserved — `endsWith(".LAUNCH_GAME")` matches every per-variant action AND the literal `gamehub.lite.LAUNCH_GAME` PlayDay-compatibility fallback in one expression.

**Lesson captured for memory:** when injecting into a method with `.locals >= 16`, every `invoke-…` that uses the non-range form must be checked — any `p0` reference becomes `v(locals)` and silently exceeds the 4-bit register cap. Solutions: (a) drop the receiver/Activity parameter and pull state from the Intent or other low-register sources; (b) `move-object/from16 vLow, p0` first; (c) use `/range` form with a contiguous register window. Option (a) is cleanest when feasible.

## 2026-05-20 (cont.) — `extlaunch-pre2` SEVERE'd again; `{p0}` ALSO violates 4-bit limit

pre2 ([run 26161013383](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26161013383)) still SEVERE'd on all 9 variants. The `[5,16] maximum register v15` line was gone (so the `{p0, v0}` → `{v0}` simplification worked), but a more subtle cascade remained:

```
[5,0] Cannot invoke "Object.hashCode()" because "key" is null
[5,56] mismatched tree node: UP expecting I_CATCHES
[3,0] A non-abstract/non-native method must have at least 1 instruction
[7,20] mismatched tree node: Lapp/revanced/extension/gamehub/launcher/ExternalLauncher; expecting I_FIELDS
```

The smali assembler NPE'd on `invoke-virtual {p0}` because `{p0}` is ALSO the non-range form (format 35c), and `p0 = v34` blows the 4-bit register field. Unlike the `{p0, v0}` case, this didn't get a clean "register out of range" error — the assembler crashed internally on a null `key.hashCode()` lookup mid-emit, then the parser tried to recover and emitted nonsense follow-ups. The fix is the canonical move-down dance — same recipe as `VibrationPatch`'s `ENV_BUILDER->a` hook:

```smali
move-object/from16 v0, p0
invoke-virtual {v0}, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
move-result-object v0
invoke-static {v0}, Lapp/revanced/extension/gamehub/launcher/ExternalLauncher;->rewriteIntent(Landroid/content/Intent;)V
```

`move-object/from16 vA, vBBBB` accepts any 16-bit source register, so it can read `p0=v34` into `v0`. Then every subsequent invoke operates on `v0` (low register) and the 4-bit limit never bites. v0 reuse remains safe because the original `onCreate`'s first instruction (`sget-object v0, Lejm;->a:Lghd;`) overwrites it on the very next instruction without ever reading the prior value.

**Lesson reinforced:** when `.locals >= 16`, every reference to `p0` in a non-range smali instruction is suspect — not just multi-register invokes. The minimal-cost cure is one `move-object/from16 vLow, p0` at the head of the injected block, then operate exclusively on `vLow`. Doc'd as a follow-up pattern next to `VibrationPatch`'s precedent.

## 2026-05-20 (cont.) — `extlaunch-pre3` applied cleanly + user-driven discovery of extra-type bug (pre4)

`extlaunch-pre3` ([run 26161429594](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26161429594)) green across all 9 variants: `"External launcher support" succeeded`, 0 SEVERE. Lite (Normal-GHL) APK at `/storage/emulated/0/Download/extlaunch-pre3/BannerHub-V6-1.4.0-604-extlaunch-pre3-Patched-Normal-GHL.apk` md5 not captured.

**User-driven device-side bug surfaced while building the test command.** User wired a Beacon entry with `--es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true`. The `--es` form puts STRING extras, but `ExternalLauncher.rewriteIntent` was reading via `intent.getIntExtra("localGameId", -1)`. `getIntExtra` returns the default when the actual extra type is String → both ids resolved to `-1` → patch bailed with the "no usable id" log → no rewrite → DeepLinkActivity finished without navigating.

This explains why a correct numeric `server_game_id` would also have failed against pre3 — the extra-type mismatch is on the read side, independent of what the user puts in the file.

**Schema-driven discovery alongside.** The user's library DBs were dumped via the root bridge (`getlog --cat` for `.db` + `.db-wal` + `.db-shm`, then `python3 -m sqlite3`-style query because `/system/bin/sqlite3` isn't present on the device):

- `gamehub.lite/databases/db_game_library.db` → Dead Cells (server_game_id `10417`), God of War (`49908`), Gunslugs (`0` — GOG, not deep-link-addressable)
- `banner.hub/databases/db_game_library.db` → Blur (`-1` — not addressable), Dirt 3 (`131962`), God of War (`49908`), PRAGMATA (`135805`)

Important consequence: 6.0.4's `t_game_library_base.id` is TEXT with prefixes like `local_*` / `gog_*` — those cannot be parsed by the dispatch's `Liml;->t0(radix 10, String)` Integer-parse step. The 5.3.5-style numeric `localGameId` arg only maps onto 6.0.4's INTEGER `server_game_id` column. The "Show game IDs" menu-row patch (queued) must surface `server_game_id` as the user-facing "Local Game ID", not the raw `id` TEXT.

**Fix in pre4 (commit pending).** `ExternalLauncher.readIdExtra(intent, key)` now reads `getStringExtra` first and `Integer.parseInt(trim())` it; falls back to `getIntExtra` for any future caller using `--ei`. Bad String values are logged but treated as missing (no crash). Same idea for `autoStartGame` via `readBoolExtra`, since `--ez` and `--es "true"` are both plausible — `--ez` is preferred but the String form is tolerated.

## 2026-05-20 (cont.) — `extlaunch-pre4` device-confirmed end-to-end + Epic gap → pre5

`extlaunch-pre4` ([run 26162931869](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26162931869)) green across all 9 variants. md5 of Normal-GHL APK = `a78178e0d42a8b3b3b554ab48b40e32b`.

**Device-confirmed paths (1.4.0-604-extlaunch-pre4 on `gamehub.lite`):**
1. **PC-imported (`source_type=0`)** — God of War (`server_game_id=49908`, `id="local_YigsP7W-…"`). Beacon `--es localGameId 49908 --ez autoStartGame true` → `ActivityTaskManager: START act=gamehub.lite.LAUNCH_GAME cmp=…/com.xiaoji.egggame.DeepLinkActivity` → `GameDetailViewModel: loadGameDetail rendered from local library after server/steam/epic failed. id=, sourceId=49908, sourceType=1` → `WinEmuModule: startGame(null-49908-1593500)` → Wine pipeline launched `GoW.exe`. End-to-end works.
2. **Steam-library (`source_type=1`)** — Brawlhalla (`id == server_game_id == steam_app_id == 291550`). Beacon `--es localGameId 291550` resolved via server-lookup branch (not fallback). User-confirmed working.
3. **Epic-library (`source_type=2`)** — DOOMBLADE (`id == epic_app_name == "818572d480784b9a904e54aab004d1c4"`, `server_game_id=0`). **Pre4 cannot launch this** — the 32-char hex UUID fails `Liml;->t0` Integer parse on `app_nav_game_id`, and `server_game_id=0` is the only numeric handle (a "no catalog id" sentinel, not addressable).

**Cosmetic note:** the `Log.i(TAG="BhExternalLauncher")` line didn't surface in the captured logcat for the pre4 test even with `-n 20000` unfiltered. System-side `ActivityTaskManager` log + GameHub's own `GameDetailViewModel` log together prove the rewrite happened (without our extension, `app_nav_game_id` wouldn't be populated from the Beacon `--es localGameId` extra). Possibly device-level Log.i filter; not gating anything.

**Pre5 extension change for Epic support.** Same Beacon command template; the user just puts the right handle in the per-game `.iso/.txt` file:
- PC-import / Steam-library → numeric `server_game_id`
- Epic-library → 32-char hex `epic_app_name` UUID

`ExternalLauncher.rewriteIntent` now:
1. Reads `localGameId` as String, tries `Integer.parseInt`.
2. If parse fails AND the string matches the Epic UUID shape (hex, ≥8 chars), treats it as `epicAppName`.
3. Explicit `--es epicAppName <uuid>` extra is also accepted (preferred for callers that want to be explicit).
4. Epic branch sets:
   - `target_type = app_nav_target = "game_detail"`
   - `app_nav_game_id = "0"` (the dispatch's "no catalog id" sentinel — required because the Integer parse must succeed; without it the dispatch bails)
   - `app_nav_epic_app_name = <uuid>`
   - `app_nav_source_type = 2`
   - `app_nav_auto_start_game = <bool>`
5. PC / Steam branch unchanged.

This is still partly speculative — the `game_id=0` + `epic_app_name=<uuid>` combination needs device verification against DOOMBLADE. If the dispatch ignores `epic_app_name` when `game_id` is 0, the fix is more invasive (e.g. construct the navigation route manually rather than going through the int-game-id dispatch).

## 2026-05-20 (cont.) — `extlaunch-pre5` Epic device test FAILED → ship as PC+Steam only

`extlaunch-pre5` ([run 26165219171](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26165219171)) applied cleanly across all 9 variants (0 SEVERE). Normal-GHL APK md5 `a3b329f6b32e777a66b48693c2327d3e`.

**Device test result for DOOMBLADE (Epic-library):** `BhExternalLauncher: rewrote gamehub.lite.LAUNCH_GAME → epic game_detail epicAppName=818572d480784b9a904e54aab004d1c4 autoStart=true` — extension fired correctly and set the Epic extras. But `GameDetailViewModel` logged: `[Issue#1753] loadGameDetail fallback exhausted → show empty. id=0, sourceId=, sourceType=-1, apiError=Business error: code=401, message=Please login first`. The dispatch's `app_nav_source_type` and `app_nav_epic_app_name` extras were ignored by the ViewModel — only `app_nav_game_id` (which we set to `"0"` as the Epic sentinel) propagated, and the resulting `loadGameDetail(0)` failed all branches (server lookup 401'd against the fake-login; steam/epic/local-fallback all failed because no row matched `_id`/`server_game_id`/`id` against `0`).

**Architectural conclusion.** The 6.0.4 `DeepLinkActivity` dispatch is fundamentally Integer-game-id-based, and the server-resolution branch hits Steam's catalog via `server_game_id` / Steam appid. Epic-library entries have no usable numeric handle (their unique identifier is the 32-char hex `epic_app_name` UUID, stored in `t_game_library_base.id` AND `epic_app_name` columns; `server_game_id=0`). For PC and Steam games the catalog API bridges `server_game_id` → the right local row, so Beacon works. For Epic that bridge doesn't exist in the deep-link surface at all.

**Evidence the in-app library-tile path goes elsewhere.** When the user tapped DOOMBLADE in GameHub Lite's library, the launch chain was `MainActivity → (Compose nav, NO DeepLinkActivity) → WineActivity` — taskId 10286, no DeepLinkActivity in the trace. The launch method's `extension_data` JSON drives the launch:
```json
{
  "gameId": "818572d480784b9a904e54aab004d1c4",
  "name": "DOOMBLADE",
  "startType": 1408,
  "exePath": "/data/user/0/gamehub.lite/files/xj_winemu/xj_install/game/<uuid>",
  "gameDir": "/data/user/0/gamehub.lite/files/xj_winemu/xj_install/game/<uuid>"
}
```
`start_type=1408` is the Epic launch flow; `start_type=1407` is Steam; `start_type=1403` is PC-import. The library-tile code path reads `t_game_launch_method.extension_data` (NOT the deep-link extras) and invokes `WineActivity` with the right args.

**Side-discovery: `_id=10` resolves to Counter-Strike on Steam.** When testing with DOOMBLADE's `_id` (`10`), Beacon dispatched to CS:GO's storefront. Steam appid `10` IS Counter-Strike, so the server-lookup branch hit Steam's catalog directly. Confirms the dispatch is Steam-appid-centric for its primary lookup, with local-fallback only used when server lookup fails AND the row has a matching `server_game_id`.

**Ship decision.** Pre5 is final for the External-launcher feature. Three Epic-support paths exist (hook MainActivity / direct-to-WineActivity / patch GameDetailViewModel) but each is a multi-iteration patch project on the scale of `VibrationMenuRowPatch` — not worth blocking the PC+Steam-supported feature. Ship pre5 as the External-launcher feature; document Epic + GOG as library-tile-only in release notes and the beacon instructions txt.

**Beacon contract (final for this branch):**

| Game type | What goes in the per-game `.iso/.txt` file |
|---|---|
| PC-imported (`source_type=0`) | numeric `server_game_id` from `t_game_library_base` (e.g. `49908` for God of War) |
| Steam-library (`source_type=1`) | numeric `server_game_id`, which equals the Steam appid for these rows (e.g. `291550` for Brawlhalla) |
| Epic-library (`source_type=2`) | **Not supported via Beacon** — launch from GameHub's library tile directly |
| GOG-imported (`server_game_id=0`) | **Not supported via Beacon** — same reason as Epic |

`beacon instructions.txt` updated in lockstep at `/storage/emulated/0/Download/beacon instructions.txt`.

## 2026-05-20 (cont.) — Show Game ID menu row + View All Games dialog (`gameid-pre1`, MERGED to 604 + Lite)

Companion to External Launcher (above): users wiring Beacon / ES-DE / Daijishou had no in-app way to read the `server_game_id` GameHub uses — they had to grep logcat. New menu row surfaces it directly in all three per-game popup menus, and a "View All Games" button on the dialog walks the entire library DB so any game's id can be copied without browsing per-tile.

### Patch layout — clone of the vibration / gpuspoof menu-row family

- `patches/.../gameid/GameIdDisplayMenuRowPatch.kt` — three injection sites (`Lx57;->a` More Menu / `Lted;->f` library-tile popup / `Lpzc;->j0` library-list popup), each a single `invoke-static {vN}, BhGameIdDisplayMenuRowClick;->append…(Object)…` that hands row construction to a Java helper (no register clobbering, no verifier risk). `dependsOn(menuGameIdCapturePatch, vibrationMenuRowPatch, gameIdDisplayMenuLabelPatch)` — explicitly reuses the shared `Lxd3;->l1` resolver hook owned by `vibrationMenuRowPatch`. **No second `Lxd3;->l1` head-block** (the 2026-05-17 GpuSpoof saga proved stacking a 2nd one ANRs MainActivity cold-start); the resolver entry is added as one `else if` line in `BhMenuRowClick.maybeResolveCustomLabel` mapping `"string:bh_gameid_label" → "Show Game ID"`.
- `patches/.../gameid/GameIdDisplayMenuLabelPatch.kt` — appends `bh_gameid_label = "Show Game ID"` to `features.home`'s Compose Multiplatform `.cvr` resource bundle across the 6 locale variants (`values`, `values-en`, `values-zh-rCN`, `values-ja-rJP`, `values-pt-rBR`, `values-ru-rRU`). Resource is reachable through any future manifest-aware resolver; runtime lookup currently goes through the shared `l1` short-circuit.
- `extensions/.../gameid/BhGameIdDisplayMenuRowClick.java` — Function1 click handler + three `append*` helpers + an `AlertDialog` with `Close / Copy / View All Games` buttons. Reuses the existing `BhMenuGameId.getCaptured()` channel and the same `ActivityThread.mActivities` walk pattern every other menu-row click handler uses, so no new Context resolution surface. View All flow opens the GameHub library DB read-only and renders an `ArrayAdapter` list; tap row → copy that game's `server_game_id`.

### View All Games — DB-backed library browser

Sources data from GameHub's own Room database. Schema confirmed live via `getlog --ls /data/data/banner.hub/databases/` + a python `sqlite3` read of the copied `.db` (no on-device `sqlite3` binary; PRoot can't `apt install` without root):

```
db_game_library.db → t_game_library_base
  server_game_id INTEGER  (the integer gameId external launchers expect; matches MMKV pc_g_setting<id>)
  game_name      TEXT
  steam_app_id   TEXT     (when source_type = 1)
  epic_app_name  TEXT     (when source_type = 2, the 32-char UUID — see External Launcher pre5 note)
  source_type/source_id/last_launch_time + several catalog fields
```

Read-only open with `SQLiteDatabase.OPEN_READONLY | NO_LOCALIZED_COLLATORS` works alongside the host Room writer's WAL connection — no lock contention. `getApplicationContext().getDatabasePath("db_game_library.db")` resolves relative to the variant's data dir (`banner.hub` / `com.antutu.benchmark.full` / `gamehub.lite` / etc.) so the same code reads the right DB across every variant. Absent file (Room creates it lazily on first write — fresh install with no library yet) toasts "open a game once to initialise it"; missing table / any SQLite error toasts "Couldn't open library DB". Empty library renders its own "No games yet" sub-dialog. No crash path.

### Reusable findings captured

1. **`BhMenuGameId.GAMEINFO_CLS = "com.xiaoji.egggame.game.di.model.game.GameInfo"` is dead code on 6.0.4.** `find smali* -path "*xiaoji*game*"` returns nothing under that prefix; the class doesn't exist in the base APK at all. The toString-regex path (`ServerGameId(value=<int>)` / `gameId=<int>`) is the only one that ever fires. Don't rely on the GameInfo fallback for new features — the comment in `BhMenuGameId.java` referencing kept-name `GameInfo.getServerGameId()` is from a different GameHub generation.
2. **GameHub's library catalog lives in `db_game_library.db`** — not in any of the three `*_Impl` Room classes visible in smali (`psplay/AppDatabase`, `movingrtc/SpeedTurnDatabase`, `movingrtc/RtcStateDatabase`); the GameLibrary Room class is generated under an R8-renamed `_Impl` that doesn't ship its raw name. Search by file rather than by class.
3. **Rows can have `server_game_id = -1`** (locally added non-server games — e.g. `Blur` on the device dump). The patch surfaces them anyway since that's what GameHub stores; users who pass `-1` to an external launcher get the same "no usable id" behavior they would have got otherwise.

### CI proof

| Stage | Run ID | Result | Notes |
|---|---|---|---|
| Feature branch (`feature/menu-gameid-display`) | [26183057664](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26183057664) | FAILED (validation) | Forgot `-f version=…` on `workflow_dispatch` — the Release workflow's `Derive version` step requires it. |
| Feature branch — corrected | [26183100272](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26183100272) | ✅ all 9 patch jobs green, 0 SEVERE | version `1.5.0-604-gameid-pre1` (Normal + alt-AnTuTu device-tested by user; "works great") |
| `gamehub-604-build` back-merge (no-artifact) | [26184783602](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26184783602) | ✅ `./gradlew build --no-daemon`, 0 errors | Build pull request workflow — patch DSL compiles cleanly on the integration branch with no Release sign-off. |
| `feature/lite-variant-tier1` back-merge (Lite refresh) | [26184541960](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26184541960) | ✅ all 9 Lite jobs green, 0 SEVERE | version `1.5.0-604-Lite-gameid-pre1` — Lite absorbed cleanly, no Tier-1–4 strip regression. |

### Merge ledger

- Feature branch `feature/menu-gameid-display`: HEAD `e928da5` (4 files, +741 LOC).
- Merged into `gamehub-604-build` at merge commit **`090706e`** (`--no-ff`).
- Back-merged into `feature/lite-variant-tier1` at merge commit **`09fa1d8`** (one-way pattern, mirrors `2d04579` for External Launcher).
- GOG branch (`feature/gog-explore-tab` HEAD `0114f75`) verified isolated — `git branch --contains 0114f75` returns nothing under 604 / Lite / main per user directive ("leave it separate until we figure out the add-game-to-GameHub-library issues").

Ships in v1.5.0-604 alongside External Launcher when the next stable is cut. Pre-release policy resumes per the BannerHub pre-release rule.

