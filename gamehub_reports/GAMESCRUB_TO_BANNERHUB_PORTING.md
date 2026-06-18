# Porting GameScrub → BannerHub (and the VJoy export/import case study)

How to adapt a feature from **GameScrub** (`GameHub-Vibration-Fix/`, Python +
apktool text-edits) into **BannerHub** (`bannerhub-revanced`, ReVanced Kotlin
patches + Java extensions). Written after porting the VJoy on-screen-controls
export/import feature onto the **6.0.9** base (commit `f2e79d4`,
branch `feature/vjoy-export-import-609`).

---

## 1. The relationship — and the #1 trap

- The two projects ship the **same Java extension files** (`com.xj.winemu.*`)
  and solve the same problems, but **different delivery mechanisms**:
  GameScrub edits the apktool smali tree with Python; BannerHub authors
  ReVanced `bytecodePatch`/`resourcePatch` against dexlib2.
- GameScrub is usually the **working reference** for a given GameHub version,
  so its Java extensions (R8 anchor constants, reflection logic) are gold —
  **copy them**.
- **TRAP: do NOT trust GameScrub's hardcoded R8 class letters / method names.**
  GameScrub and BannerHub are frequently derived against *different sub-builds*
  of the "same" GameHub version. Concrete example from the 6.0.9 port: the
  string-resolver class is **`Ly99;->c0`** in GameScrub's docstring but
  **`Ly99;->Z`** in BannerHub's device-verified base. Same APK family, different
  letters. **Re-derive every structural anchor against BannerHub's own base
  APK.** Trust *descriptors and structure*, not letters.

## 2. Translating GameScrub Python → ReVanced Kotlin

| GameScrub (Python/apktool)                       | BannerHub (ReVanced/dexlib2)                                  |
|--------------------------------------------------|---------------------------------------------------------------|
| URL-fragment method locator                      | `firstMethod { bodyReferencesString(this, "vcontroller/…") }` |
| "unique caller of method X" locator              | `firstMethod { bodyInvokes(this, "${m.definingClass}->${m.name}") }` |
| hardcoded method name `y99.a0/G/H`               | match by **descriptor shape** + `definingClass`, not name     |
| optional/"may not exist" edit                    | `firstMethodOrNull { … }?.addInstructions(…)` (non-fatal)     |
| `if-eqz vN,:cond` → `goto :cond` (text replace)  | insert `const/4 vN, 0x0` **before** the `if-eqz` (clean insert, same effect, no instruction replacement) |
| manifest text edit                               | `resourcePatch { document("AndroidManifest.xml")… }`          |
| `.cvr` line append for labels                    | `resourcePatch` appending `string\|key\|<b64>` to the `.cvr`  |

**Anchoring principle:** prefer (a) server-stable URL literals, (b) stable
Compose `testTag` literals, (c) call-relationships, (d) descriptor shapes — in
that order. All survive R8 reshuffles; class letters do not.

**Label resolution gotcha:** in ReVanced, prefer `addInstructions` at index 0
with a trailing local label (works because shift is zero) over
`addInstructionsWithLabels` + `ExternalLabel` (hit `classDef is null` on this
patcher version). For forcing a conditional branch, inserting a `const` to
neutralize the test register is more robust than rewriting the branch op.

## 3. 6.0.9 VJoy export/import anchors (re-derive on the next base!)

**Resource keys moved namespace in 6.0.9** — the biggest single gotcha:
`features_vjoy_*` → **`common_vjoy_layout_*`**. Keys live in the Compose-
Multiplatform `.cvr` binary, **not** as smali string literals, so grep of smali
finds nothing — decode the base APK and read the `.cvr` bundles.

| What | 6.0.9 key / anchor |
|------|--------------------|
| Share button (relabel → "Export")        | `common_vjoy_layout_func_share` |
| Prepare-share dialog title ("Publish to Cloud" → "Name Profile") | `common_vjoy_layout_dialog_prepare_share_title` |
| …placeholder                              | `common_vjoy_layout_dialog_prepare_share_placeholder` |
| "Upload original" checkbox                | `common_vjoy_layout_dialog_prepare_share_upload_original` |
| Import dialog title (fires SAF kick)      | `common_vjoy_layout_dialog_import_share_code_title` |
| Import dialog placeholder                 | `common_vjoy_layout_dialog_import_share_code_placeholder` |
| "Operation failed, please try again." toast (suppress → "") | `common_vjoy_layout_toast_operation_failed` |
| Import menu action (still old namespace!) | `features_vjoy_main_action_import` |

**String resolver host (`Ly99`)** — the label-relabel short-circuit rides on these:
- `Z(Llok;Lgm3;I)String` — Compose single-key stringResource (hooked by `vibrationMenuRowPatch`; the **kick** path)
- `a0(Llok;[Ljava/lang/Object;Lgm3;I)String` — Compose + format args
- `G(Llok;Lov3;)Object` — suspend getString (carries the toast)
- `H(Llok;[Ljava/lang/Object;Lov3;)Object` — suspend + format args
- Types: resource descriptor **`Llok`** (extends `Lo4h`; the `string:<key>` is field `a`), Composer **`Lgm3`**, Continuation **`Lov3`**.
- Sibling hooks (`a0`/`G`/`H`) call `maybeResolveCustomLabelNoKick`; only `Z` fires the import kick. Hook siblings **non-fatally** (`firstMethodOrNull`).

**"Upload original" row hide** — `dl7.smali`, method `invoke(Object,Object)Object`:
Compose skip pattern `Ljy8;->Y(IZ)Z` → `move-result vN` → `if-eqz vN, :cond_N`
→ row body → `:cond_N` runs skipToGroupEnd. Force the skip by inserting
`const/4 vN, 0x0` before the `if-eqz`. Anchor structurally: method references
testTag `vjoy_share_upload_check` **and** has the unique `IF_EQZ`→`XOR_INT_LIT8`
adjacency (disambiguates `dl7` from the checkbox-state composable that shares
the testTag but has no `xor-int/lit8`).

**`BhVjoyImporter` 6.0.9 R8 reflection anchors** (in the Java file, re-derive each bump):
`WITH_CONTEXT=g8i.L`, `DISPATCHER=u90.a`, `COROUTINE_CTX_IF=yy3`,
`FUNCTION2=h57`, `CONTINUATION=ov3`, `SAVE_BLOCK=qpm`. Keep-stable host FQNs
(`AppDatabase`, `VirtualKeyLayoutDao/Entity`, `KoinJavaComponent`,
`VJoyLayout`, `VJoyLayoutJson`) are used for the DB insert + Room nudge.

## 4. Build / patch / install toolchain (Windows + Google Drive)

The repo lives under **Google Drive**, which causes most of the friction.

- **JDK 17+ required.** `JAVA_HOME=C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot`.
- **Android SDK required** for the extension modules:
  `ANDROID_HOME=C:/Users/Tideg/AppData/Local/Android/Sdk`.
- **Google Drive locks `build/` dirs** → gradle `Failed to delete some children`
  / `AccessDeniedException`. Also defeats incremental compile (edits look
  up-to-date). **Fix:** `./gradlew --stop` then `rm -rf */build` before
  building. Don't use `clean` + `--rerun-tasks` (fights the locks harder).
- **`.rvp` is a ZIP** — `grep -a` can't see strings in compressed entries.
  To verify a build picked up a change: `unzip` the `.rvp` and grep the
  extracted `extensions/gamehub.rve` / dex.
- **revanced-cli v6.0.0 signer only reads BKS keystores**, not the project's
  JKS (`java.io.IOException: Wrong version of key store`). Either omit
  `--keystore` (CLI generates `<out>.keystore`) and **reuse that generated
  file** on later runs for in-place updates, or convert JKS→BKS.
- **Install conflict:** `INSTALL_FAILED_DUPLICATE_PERMISSION` for
  `com.xiaoji.egggame.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` — the
  "Change package name" patch does **not** rewrite this auto-generated
  permission, so a renamed `banner.hub` build can't co-exist with stock
  GameHub. Uninstall stock GameHub first.

### Exact commands (single "Normal" variant)

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export ANDROID_HOME="C:/Users/Tideg/AppData/Local/Android/Sdk"

# build the bundle (clear build dirs first to dodge Drive's incremental cache)
rm -rf patches/build extensions/*/build extensions/gamehub/stub/build
./gradlew buildAndroid           # -> patches/build/libs/patches-1.0.0.rvp

# patch + sign (reuse the CLI-generated keystore for in-place updates) + install
java -jar revanced-cli.jar patch GameHub_6.0.9.apk \
  -p patches/build/libs/patches-1.0.0.rvp --bypass-verification --purge \
  --keystore GameHub-6.0.9-Patched-Normal.keystore \
  -e "Change package name" -O 'packageName="banner.hub"' \
  -e "Change app name"     -O 'appName="GameHub"' \
  -o GameHub-6.0.9-Patched-Normal.apk -i <device-serial>
```

## 5. Deriving anchors + verifying on device

- **Decode the base APK** to find keys/anchors:
  `apktool d -r -f -o decoded GameHub_6.0.9.apk` (the `-r` skips resources, faster
  for smali-only; drop it when you need the `.cvr` files).
- **Find a relabeled string's key:** decode the `.cvr` values
  (`assets/composeResources/<module>/values/strings.commonMain.cvr`, lines are
  `string|key|<base64>`) and `base64 -d` to match against the on-screen text.
- **Logcat tags** for the runtime side: `BhMenuRowClick`, `BhVjoyShareHook`,
  `BhVjoyImporter`, `BhSafProxy`. `adb logcat -d -s <tags>` scans the whole
  buffer (a small `-t N` window is too short for a busy app).
  `maybeResolveCustomLabel key=… → '…'` lines tell you which keys actually flow
  through the resolver — the fastest way to spot a renamed-key miss.
