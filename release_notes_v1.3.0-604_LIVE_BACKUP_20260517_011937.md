## BannerHub v6 1.3.0-604

A patched build of XiaoJi GameHub 6.0.4 (`com.xiaoji.egggame`, versionCode 114) that removes the login requirement so games can be imported and used without an account, redirects the catalog API to the BannerHub Cloudflare Worker, and ships **9 variants that install side-by-side on the same device** — each now with an optional **Lite** counterpart (~34.5 MB smaller). **18 APKs attached** (9 full + 9 Lite).

### ✨ What's new in 1.3.0-604

Two headline changes on top of `v1.2.0-604` (stable keystore unchanged — installs in place):

- **🎮 Preload-free vibration — fixes the x86_64 / Box64 launch-death.** The PC-accurate XInput rumble feature no longer ships `libevshim.so` and no longer touches `LD_PRELOAD`. SDL2's ~1 s auto-stop is now defeated by an in-process patch that rewrites every `winebus.so`'s `SDL_JoystickRumble` duration on disk (aarch64 + x86_64), with nothing extra mapped into the Wine subprocess. This **resolves the `c000007b` crash** that prevented x86_64 / Box64 games from launching on builds carrying the old LD_PRELOAD shim. Rumble behaviour is unchanged (defaults to `MODE_CONTROLLER` at 100%; adjust per-game via **PC Vibration Settings**). Device-confirmed.
- **🪶 BannerHub v6 Lite — a slimmer counterpart for every variant.** Alongside the 9 full APKs, this release attaches **9 Lite APKs** (one per variant, `…-Lite.apk`). Each Lite build is ~34.5 MB smaller on disk (≈32%: ~114.5 → ~78.3 MB) — it strips a verified-dead duplicate 20 MB font, the Aliyun carrier-login native lib, the Haima cloud-gaming stack, and the bundled AVIF/HEIC image-codec stack. Removed features: cloud gaming (non-functional under the BannerHub catalog redirect anyway) and the bundled AVIF/HEIC decoder — modern Android still renders HEIF/AVIF via the platform decoder; JPEG/PNG/WebP are unaffected. Everything else is byte-identical to the matching full variant. Each Lite uses the **same package name** as its full counterpart, so a Lite APK **installs over (replaces)** the matching full variant — they don't coexist; pick one per package.

### ✅ Steam game launches work end-to-end

### Source
- **Base APK**: `GameHub_6.0.4.apk` — the official 6.0.4 global build (versionCode 114), attached unmodified to [`base-apk-604`](https://github.com/The412Banner/bannerhub-revanced/releases/tag/base-apk-604).
- **Patcher**: ReVanced CLI 6.0.0 + this repo's patch bundle. Full APKs from branch `gamehub-604-build`; Lite APKs from `feature/lite-variant-tier1` (same bundle + 4 size-reduction strip patches).
- **Catalog backend**: [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api) — Cloudflare Worker, deployed at `bannerhub-api.the412banner.workers.dev`.
- **Repo**: https://github.com/The412Banner/bannerhub-revanced

#### Naming & versioning scheme

APK files follow the pattern **`BannerHub-V6-{version}-Patched-{variant}.apk`** — e.g. `BannerHub-V6-1.3.0-604-Patched-Normal.apk` (full) or `BannerHub-V6-1.3.0-604-Patched-Normal-Lite.apk` (Lite). The version string itself has three parts:

- **BannerHub v6** — product name. The `v6` is fixed; it's our branch family aligned with GameHub's 6.x series and stays put across upstream patch-version bumps.
- **`1.3.0`** — BannerHub-side semver (`major.minor.patch`). Tracks our own changes: new patches, infrastructure work, bug fixes. Bumps on every release.
- **`-604`** — GameHub base version with the dots stripped (`6.0.4` → `604`). Tells you which upstream GameHub APK was patched. When GameHub upstream releases `6.0.5`, the suffix becomes `-605` and the same patch set can be retargeted (e.g. `v1.3.0-605`) without otherwise changing.

The release tag (`v1.3.0-604`) is just the version string with a leading `v`. The `{variant}` slot in the filename identifies which of the side-by-side packagings you grabbed; a trailing `-Lite` marks the slimmed build.

### Variants

Nine APKs are produced from the same patch bundle, each with a different package name + launcher label so they install side-by-side. The `Original` variant **replaces** an installed GameHub; the rest install **alongside** the original.

| Variant | APK | Package | Launcher label |
| --- | --- | --- | --- |
| Normal | `BannerHub-V6-1.3.0-604-Patched-Normal.apk` | `banner.hub` | BannerHub v6 |
| Normal-GHL | `BannerHub-V6-1.3.0-604-Patched-Normal-GHL.apk` | `gamehub.lite` | BannerHub v6 |
| PuBG | `BannerHub-V6-1.3.0-604-Patched-PuBG.apk` | `com.tencent.ig` | BannerHub v6 PuBG |
| AnTuTu | `BannerHub-V6-1.3.0-604-Patched-AnTuTu.apk` | `com.antutu.ABenchMark` | BannerHub v6 AnTuTu |
| alt-AnTuTu | `BannerHub-V6-1.3.0-604-Patched-alt-AnTuTu.apk` | `com.antutu.benchmark.full` | BannerHub v6 AnTuTu |
| PuBG-CrossFire | `BannerHub-V6-1.3.0-604-Patched-PuBG-CrossFire.apk` | `com.tencent.tmgp.cf` | BannerHub v6 PuBG CrossFire |
| Ludashi | `BannerHub-V6-1.3.0-604-Patched-Ludashi.apk` | `com.ludashi.aibench` | BannerHub v6 Ludashi |
| Genshin | `BannerHub-V6-1.3.0-604-Patched-Genshin.apk` | `com.miHoYo.GenshinImpact` | BannerHub v6 Genshin |
| Original | `BannerHub-V6-1.3.0-604-Patched-Original.apk` | `com.xiaoji.egggame` | BannerHub v6 |

#### 🪶 Lite variants

Each Lite APK below is the same build as its full counterpart minus ~34.5 MB of stripped weight (dead duplicate font + Aliyun carrier-login lib + Haima cloud-gaming stack + AVIF/HEIC codecs). **It uses the same package name as its full counterpart, so installing a Lite APK replaces the matching full variant** (they do not coexist) — only the launcher label differs (full label + " Lite").

| Lite variant | APK | Package (= full counterpart) | Launcher label |
| --- | --- | --- | --- |
| Normal-Lite | `BannerHub-V6-1.3.0-604-Patched-Normal-Lite.apk` | `banner.hub` | BannerHub v6 Lite |
| Normal-GHL-Lite | `BannerHub-V6-1.3.0-604-Patched-Normal-GHL-Lite.apk` | `gamehub.lite` | BannerHub v6 Lite |
| PuBG-Lite | `BannerHub-V6-1.3.0-604-Patched-PuBG-Lite.apk` | `com.tencent.ig` | BannerHub v6 PuBG Lite |
| AnTuTu-Lite | `BannerHub-V6-1.3.0-604-Patched-AnTuTu-Lite.apk` | `com.antutu.ABenchMark` | BannerHub v6 AnTuTu Lite |
| alt-AnTuTu-Lite | `BannerHub-V6-1.3.0-604-Patched-alt-AnTuTu-Lite.apk` | `com.antutu.benchmark.full` | BannerHub v6 AnTuTu Lite |
| PuBG-CrossFire-Lite | `BannerHub-V6-1.3.0-604-Patched-PuBG-CrossFire-Lite.apk` | `com.tencent.tmgp.cf` | BannerHub v6 PuBG CrossFire Lite |
| Ludashi-Lite | `BannerHub-V6-1.3.0-604-Patched-Ludashi-Lite.apk` | `com.ludashi.aibench` | BannerHub v6 Ludashi Lite |
| Genshin-Lite | `BannerHub-V6-1.3.0-604-Patched-Genshin-Lite.apk` | `com.miHoYo.GenshinImpact` | BannerHub v6 Genshin Lite |
| Original-Lite | `BannerHub-V6-1.3.0-604-Patched-Original-Lite.apk` | `com.xiaoji.egggame` | BannerHub v6 Lite |

<details>
<summary><strong>📦 Patches applied (click to expand)</strong></summary>

| Patch | What it does |
| --- | --- |
| **Bypass login** | Lets you launch straight into the home screen and import games without an account. |
| **Disable Firebase Crashlytics** | Removes the Crashlytics init block so the patched build doesn't crash on launch with `VerifyError`. |
| **Debug logging** | Marks the APK debuggable and adds logcat markers along the import code path for triage. |
| **File manager access** | Exposes a content provider so external file managers (MT Manager etc.) can browse GameHub's data dir without root. |
| **Rewrite custom permissions per variant** | Per-variant `com.xiaoji.egggame.permission.*` rewrite so two installed variants don't collide on Android 7+. |
| **Mute UI sounds** | Replaces the bundled menu/button click sounds with silent PCM. Game audio is untouched. |
| **Redirect catalog API** | Points the catalog API at the BannerHub Cloudflare Worker (`bannerhub-api.the412banner.workers.dev`) for curated component delivery. Side benefit: per-game PC settings now render in both landscape and portrait. |
| **Prefix API path with /v6** | Prepends `v6/` to every relative API call so the Worker can serve 6.0-only response variants without affecting 5.x clients. |
| **Offline component cache fallback** ⚠ *currently broken on 6.0.4* | *Intended to let per-game pickers show your cached components when the device is offline.* Online behavior is unchanged, so this caveat only matters when you're offline — pickers will still show only the embedded built-in versions instead of the cached entries the patch was meant to surface. Investigation pending; safe to ignore unless you specifically rely on offline picker contents. |
| **PC-accurate vibration** | 4 bytecode hooks (GamepadServerManager.onRumble entry + per-controller dispatch + stop + Wine env-builder pre-launch trigger) route XInput rumble into Android's VibratorManager with dual-motor independent dispatch on multi-motor pads. SDL2's ~1 s auto-stop is defeated **preload-free**: `BhVibrationController.ensureWinebusDurationPatchOnce` rewrites every `winebus.so`'s two non-zero `SDL_JoystickRumble` duration loads to `0xffffffff` on disk (aarch64 + x86_64) once per app process — no `libevshim.so`, no `LD_PRELOAD`, no extra mapping in the Wine subprocess (fixes the x86_64/box64 launch-death regression). Defaults to `MODE_CONTROLLER` at 100% intensity — works out of the box. |
| **Vibration settings activity** | Registers `com.xj.winemu.vibration.BhVibrationSettingsActivity` in the manifest. Internal-only (exported=false); launched by the menu rows below via explicit Intent. Dialog lets you pick mode (off / device / controller / both) and intensity (0–100%) per-game, with global defaults. |
| **PC Vibration Settings menu row** | Injects a 5th menu row labelled **PC Vibration Settings** into both per-game popups (game-details "More Menu" + library-tile 3-dot popup). Tapping launches the vibration settings dialog scoped to the active game. Survives Compose Multiplatform's R8-renamed `Function0/Function1` interfaces (proxied at runtime) and the empty-Kotlin-subclass `Lell` resource descriptor (allocated via `Unsafe`). |
| **PC Vibration Settings label resource** | Appends a `bh_pc_vibration_label = "PC Vibration Settings"` entry to `features.home`'s Compose Multiplatform resource bundle (`.cvr` file). Used as documentation; the actual runtime resolution goes via a bytecode-patched short-circuit in `Lxd3.l1` because Compose's resource manifest needs entries the bare `.cvr` doesn't register. |
| **Change app icon** | Replaces 5 in-APK drawables with BannerHub v6 branding: adaptive-icon foreground (deletes the stock GameHub vector so the new raster wins on every device density), in-app `wine_logo` (Wine container header), and three Compose Multiplatform auth/splash assets (auth-screen landscape + overseas logos + splash banner). Launcher background drawable left as-is so launcher masking applies cleanly. |
| **Change package name** *(per variant)* | Renames the APK package per variant so all 9 variants install side-by-side. The `Original` variant keeps `com.xiaoji.egggame`. |
| **Change app name** *(per variant)* | Renames the launcher label per variant. |
| **Lite size-reduction strips** *(Lite APKs only)* | Four `use=false` patches applied only to the `-Lite` builds: strip the dead duplicate 20 MB MiSans font, the Aliyun NumberAuth carrier-login native lib, the Haima cloud-gaming stack, and the avif-coil AVIF/HEIC codec stack. ~34.5 MB smaller on disk; no effect on the full variants. |

*Per-patch mechanics, smali edits, structural anchors, and version-by-version letter maps live in the [README's Patches applied section](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/README.md#patches-applied) and the patch source comments under [`patches/src/main/kotlin/app/revanced/patches/gamehub/`](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-604-build/patches/src/main/kotlin/app/revanced/patches/gamehub).*

</details>

### 🙏 Credits

The **PC-accurate controller vibration** feature — including the preload-free `winebus.so` rework that resolves the x86_64 / Box64 launch-death in this release — is built entirely on the work of **[TideGear](https://github.com/TideGear)**:

- [**TideGear / GameHub-Vibration-Fix**](https://github.com/TideGear/GameHub-Vibration-Fix) — the original rumble port (PR #80) and the **preload-free `winebus.so` disk-patch rework** (PR #91) that `v1.3.0-604` ships. Adapted with the author's **explicit permission**.
- [**GameNative**](https://github.com/utkarshdalal/GameNative) ([@utkarshdalal](https://github.com/utkarshdalal) and contributors) — the upstream Wine-on-Android rumble lineage (PR #1214) that TideGear's fix, and therefore this patch, derives from.

Full project credits (DXVK, VKD3D-Proton, Box64, FEX, Mesa Turnip, ReVanced, etc.) are in the [README Credits section](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/README.md#credits).

### Notes

- The base APK is unmodified XiaoJi GameHub 6.0.4; only the bytecode/resources required by the patches above are touched.
- **Signing**: signed with the stable BannerHub test keystore (`keystore/bannerhub.keystore`). One-time uninstall required if upgrading from `v1.0.0-604` or older (different cert). From this release onward, all future BannerHub v6 stables install in place. Cert fingerprints: see [`keystore/README.md`](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/keystore/README.md).
- **Full vs Lite**: the Lite APKs share their full counterpart's package name and cert, so they install in place over the full variant (and vice versa). Don't try to keep both — Android treats them as the same app.
- Cover-art-on-import was fixed server-side in the BannerHub Worker on 2026-05-11 (worker deploy `5fd6c6a7…`) and applies retroactively to every existing patched build — no APK rebuild needed.
- The `.rvp` patch bundle and `.rve` extension files are also attached for use with `revanced-cli` directly.
</content>
</invoke>




