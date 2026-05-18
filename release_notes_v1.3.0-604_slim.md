## BannerHub v6 1.3.0-604

A patched build of **XiaoJi GameHub 6.0.4** that removes the login requirement (import and play games with no account) and redirects the catalog to the BannerHub Cloudflare Worker. Ships **9 variants that install side-by-side**, each with an optional **Lite** build (~34.5 MB / ≈32% smaller).

**18 APKs attached** — 9 full + 9 Lite. Steam game launches work end-to-end.

🔒 **Privacy-hardened** — upstream telemetry/analytics channels stripped at the bytecode level. Full list of killed channels (and the honest leftovers) in [`PRIVACY.md`](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/PRIVACY.md).

<details>
<summary><strong>✨ Everything BannerHub v6 offers (click to expand)</strong></summary>

- **No login required** — launch straight to the home screen and import/play games without a XiaoJi account.
- **Custom catalog API** — the catalog is redirected to the BannerHub Cloudflare Worker for **curated component delivery** (hand-picked DXVK, VKD3D-Proton, Box64, FEX, Mesa Turnip driver builds, etc.) instead of the stock upstream set.
- **Privacy-hardened** — upstream telemetry/analytics channels stripped at the bytecode level (see the `PRIVACY.md` link above for the full list).
- **PC-accurate controller vibration** — real XInput rumble routed into Android's VibratorManager with independent dual-motor support, configurable **per-game** (mode + intensity) via the **PC Vibration Settings** menu row. Preload-free — no `libevshim` / `LD_PRELOAD`.
- **9 side-by-side variants** — different package names + launcher labels so multiple builds coexist on one device (`Original` replaces stock GameHub; the rest install alongside it).
- **Optional Lite builds** — a ~34.5 MB-smaller counterpart of every variant.
- **Muted UI sounds** — menu/button click sounds silenced; in-game audio untouched.
- **Root-free file manager access** — a content provider lets MT Manager and similar apps browse GameHub's data dir without root.
- **Portrait PC-settings** — per-game PC settings render in both landscape and portrait (side benefit of the API redirect).
- **Installs in place** — stable signing keystore; future BannerHub v6 stables upgrade without uninstalling.
- **BannerHub v6 branding** — custom app icon, Wine container header, and auth/splash artwork.
- **Triage-friendly** — debuggable build with logcat markers along the import path.

</details>

### What's new in 1.3.0-604

On top of `v1.2.0-604` (same keystore — installs in place):

- **🎮 Preload-free vibration — fixes the x86_64 / Box64 launch crash.** PC-accurate XInput rumble no longer ships `libevshim.so` or touches `LD_PRELOAD`; SDL2's ~1 s auto-stop is now defeated by an on-disk `winebus.so` patch instead. This resolves the `c000007b` crash that blocked x86_64 / Box64 game launches on older builds. Rumble behaviour is unchanged (per-game via **PC Vibration Settings**). Device-confirmed.
- **🪶 Lite builds for every variant.** Each Lite APK is ~34.5 MB smaller (≈114.5 → ≈78.3 MB) — it strips a dead duplicate font, the Aliyun carrier-login lib, the Haima cloud-gaming stack, and the bundled AVIF/HEIC codecs. Removed: cloud gaming (non-functional under the catalog redirect anyway) and the bundled AVIF/HEIC decoder (Android still renders these via the platform decoder; JPEG/PNG/WebP unaffected). Otherwise byte-identical to the full build. Strip-by-strip breakdown in [`bannerhub-v6-lite.md`](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/bannerhub-v6-lite.md).

### Variants

Each variant has its own package name + launcher label, so the variants install **side-by-side** with each other. `Original` replaces an installed GameHub; the rest install alongside it.

**Full vs Lite:** a Lite APK shares its full counterpart's package name and cert — installing Lite **replaces** the matching full build (and vice versa). They do **not** coexist; pick one per row.

| Variant | Package | Full APK / Lite APK |
| --- | --- | --- |
| Normal | `banner.hub` | `…-Normal.apk` / `…-Normal-Lite.apk` |
| Normal-GHL | `gamehub.lite` | `…-Normal-GHL.apk` / `…-Normal-GHL-Lite.apk` |
| PuBG | `com.tencent.ig` | `…-PuBG.apk` / `…-PuBG-Lite.apk` |
| AnTuTu | `com.antutu.ABenchMark` | `…-AnTuTu.apk` / `…-AnTuTu-Lite.apk` |
| alt-AnTuTu | `com.antutu.benchmark.full` | `…-alt-AnTuTu.apk` / `…-alt-AnTuTu-Lite.apk` |
| PuBG-CrossFire | `com.tencent.tmgp.cf` | `…-PuBG-CrossFire.apk` / `…-PuBG-CrossFire-Lite.apk` |
| Ludashi | `com.ludashi.aibench` | `…-Ludashi.apk` / `…-Ludashi-Lite.apk` |
| Genshin | `com.miHoYo.GenshinImpact` | `…-Genshin.apk` / `…-Genshin-Lite.apk` |
| Original | `com.xiaoji.egggame` | `…-Original.apk` / `…-Original-Lite.apk` |

Filenames follow `BannerHub-V6-1.3.0-604-Patched-{variant}[-Lite].apk`. The version string is `BannerHub v6` (product) + `1.3.0` (our semver) + `-604` (the GameHub base, `6.0.4`).

### Install notes

- **Signing**: stable BannerHub test keystore. A one-time uninstall is only needed if upgrading from `v1.0.0-604` or older (cert changed then); every release since installs in place. Fingerprints in [`keystore/README.md`](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/keystore/README.md).
- Base APK is unmodified XiaoJi GameHub 6.0.4 (versionCode 114) — only the bytecode/resources the patches need are touched.
- The `.rvp` / `.rve` bundles are attached for use with `revanced-cli` directly. Built with ReVanced CLI 6.0.0; full APKs from `gamehub-604-build`, Lite from `feature/lite-variant-tier1`.

<details>
<summary><strong>📦 Patches applied (click to expand)</strong></summary>

| Patch | What it does |
| --- | --- |
| **Bypass login** | Launch straight to the home screen; import and play games with no account. |
| **Disable Firebase Crashlytics** | Prevents a launch-time `VerifyError` crash. |
| **Debug logging** | Debuggable APK + logcat markers along the import path for triage. |
| **File manager access** | Content provider so external file managers (MT Manager etc.) can browse the data dir without root. |
| **Rewrite custom permissions per variant** | Stops two installed variants colliding on Android 7+. |
| **Mute UI sounds** | Silences menu/button clicks; game audio untouched. |
| **Redirect catalog API** | Points the catalog at the BannerHub Worker; also fixes PC-settings rendering in portrait. |
| **Prefix API path with /v6** | Lets the Worker serve 6.0-only responses without affecting 5.x clients. |
| **Offline component cache fallback** ⚠ *broken on 6.0.4* | Online behaviour unaffected; offline pickers just fall back to built-in versions. Safe to ignore unless you rely on offline picker contents. |
| **PC-accurate vibration** | Routes XInput rumble into Android's VibratorManager (independent dual-motor). Preload-free: an on-disk `winebus.so` patch defeats SDL2's ~1 s auto-stop with no `libevshim.so` / `LD_PRELOAD` (fixes the x86_64/box64 launch-death). Default on at 100%. |
| **PC Vibration Settings (activity + menu row)** | Per-game mode (off / device / controller / both) and intensity (0–100%), plus a **PC Vibration Settings** row in both per-game popups. |
| **Change app icon** | BannerHub v6 branding — adaptive icon, Wine container header, auth/splash assets. |
| **Change package & app name** *(per variant)* | Per-variant package + launcher label so all 9 variants install side-by-side (`Original` keeps `com.xiaoji.egggame`). |
| **Lite size-reduction strips** *(Lite only)* | Strips the dead 20 MB font, Aliyun carrier-login lib, Haima cloud-gaming stack, and AVIF/HEIC codecs. ~34.5 MB smaller; no effect on full variants. |

*Per-patch mechanics, smali anchors, and version-by-version letter maps are in the [README's Patches applied section](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/README.md#patches-applied) and the [patch sources](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-604-build/patches/src/main/kotlin/app/revanced/patches/gamehub).*

</details>

### Credits

PC-accurate controller vibration (including the preload-free rework) is built on **[TideGear](https://github.com/TideGear)**'s [GameHub-Vibration-Fix](https://github.com/TideGear/GameHub-Vibration-Fix) (PR #80 + PR #91), adapted with explicit permission, deriving from [GameNative](https://github.com/utkarshdalal/GameNative) (PR #1214). Full project credits (DXVK, VKD3D-Proton, Box64, FEX, Mesa Turnip, ReVanced, etc.) are in the [README Credits section](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-604-build/README.md#credits).
