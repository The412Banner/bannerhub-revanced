# Beacon launcher setup for BannerHub v6

How to configure **Beacon** (and the same intent contract is used by **ES-DE** and **Daijishou**) to launch games into any of BannerHub v6's nine variants on the 6.0.4 base.

> ✅ **Beacon — device-confirmed working.**
> ⚠️ **ES-DE / Daijishou — untested but should work** (same intent contract). Please report results.
> ❌ **Epic Games library — unsupported.** The upstream `GameDetailViewModel` ignores the `app_nav_epic_app_name` route. PC + Steam launches work end-to-end.

The intent contract is identical across all 9 variants — only the package name and the action prefix change. The `DeepLinkActivity` class FQN is the same everywhere because `ChangePackageNamePatch` only rewrites the manifest `package=` attribute, not class names.

> 📺 **Video walkthrough (5.3.5-era; flow is identical for v6)** — covers creating PC-import game txt/iso files with localID numbers and Steam-game files with AppID numbers: <https://youtu.be/ENYnJhTvEvw?si=REvcfeCAu4qQyaQX>

> 💡 **Why "alt-AnTuTu" exists.** The original **AnTuTu** variant (`com.antutu.ABenchMark`) is finicky with Beacon — historically it didn't work, which is why the **alt-AnTuTu** variant (`com.antutu.benchmark.full`) was created and is the recommended AnTuTu-flavored package for external-launcher setups.

---

## Beacon in-app walkthrough

1. **Settings** → tap the **+ icon** to add a new platform.
2. Fill in:
   - **Platform Type**: `Windows`
   - **Player app**: select the BannerHub variant you have installed (e.g. *GameHub Lite* for the **Normal-GHL** variant).
   - **ROMs folder**: use the Android file picker to select the folder containing your game `.txt` / `.iso` files (each file's content is a single `localGameId` or `steamAppId` number — see [How to find a game's `localGameId`](#how-to-find-a-games-localgameid) below).
3. Expand **Advanced**:
   - **File handling**: `Default`
   - **Use custom launch**: `True`
   - **am start command**: paste the per-variant block from [Shell reference](#shell-reference-per-variant-am-examples) below — picking the row matching your installed variant.
4. Tap **Save**.
5. **Scan** the folder for your games.
6. **Launch** a game — it should hand off into BannerHub and (with `autoStartGame true`) start playing directly.

---

## The intent contract

| Field | Value |
|---|---|
| **Component / Activity** | `com.xiaoji.egggame.DeepLinkActivity` *(same for all 9 variants)* |
| **Action** | `<variant_package>.LAUNCH_GAME` (per-variant — see table below) |
| **Extras** | `localGameId` (String → int, preferred) · `steamAppId` (String → int, optional) · `autoStartGame` (bool) |

### Extras detail

| Key | Type | Purpose |
|---|---|---|
| `localGameId` | String (parsed to int) | XiaoJi game id — **preferred**. Read from the **Show Game ID** menu row dialog in the game-details page (added in v1.5.0-604). |
| `steamAppId` | String (parsed to int) | Steam app id (e.g. Brawlhalla = `291550`). Optional. |
| `autoStartGame` | Boolean | `true` = skip the GameHub detail screen and launch directly into the game. |

`--es` (String) is preferred — it matches Beacon's actual call shape. The patch also accepts `--ei` (int) as a fallback for older 5.3.5-style configs.

---

## Per-variant configuration

Pick the row matching the BannerHub v6 variant you've installed.

| Variant | Package | Component | Action |
|---|---|---|---|
| Normal | `banner.hub` | `banner.hub/com.xiaoji.egggame.DeepLinkActivity` | `banner.hub.LAUNCH_GAME` |
| Normal-GHL | `gamehub.lite` | `gamehub.lite/com.xiaoji.egggame.DeepLinkActivity` | `gamehub.lite.LAUNCH_GAME` |
| PuBG | `com.tencent.ig` | `com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity` | `com.tencent.ig.LAUNCH_GAME` |
| AnTuTu | `com.antutu.ABenchMark` | `com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity` | `com.antutu.ABenchMark.LAUNCH_GAME` |
| alt-AnTuTu | `com.antutu.benchmark.full` | `com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity` | `com.antutu.benchmark.full.LAUNCH_GAME` |
| PuBG-CrossFire | `com.tencent.tmgp.cf` | `com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity` | `com.tencent.tmgp.cf.LAUNCH_GAME` |
| Ludashi | `com.ludashi.aibench` | `com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity` | `com.ludashi.aibench.LAUNCH_GAME` |
| Genshin | `com.miHoYo.GenshinImpact` | `com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity` | `com.miHoYo.GenshinImpact.LAUNCH_GAME` |
| Original | `com.xiaoji.egggame` | `com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity` | `com.xiaoji.egggame.LAUNCH_GAME` |

> Lite variants (`-Lite` filenames) share their full counterpart's package and action — use the same row as the matching full variant.

---

## Shell reference (per-variant `am` examples)

Each block is the literal `am launch` you'd run on-device to validate that variant. Use `localGameId` (preferred — works for any game) or `steamAppId` (Steam games only). `{file_content}` is Beacon's own template-variable placeholder.

### Normal — `banner.hub`
```sh
am launch -n banner.hub/com.xiaoji.egggame.DeepLinkActivity \
    -a banner.hub.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### Normal-GHL — `gamehub.lite`
```sh
am launch -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity \
    -a gamehub.lite.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### PuBG — `com.tencent.ig`
```sh
am launch -n com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity \
    -a com.tencent.ig.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### AnTuTu — `com.antutu.ABenchMark`
```sh
am launch -n com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity \
    -a com.antutu.ABenchMark.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### alt-AnTuTu — `com.antutu.benchmark.full`
```sh
am launch -n com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity \
    -a com.antutu.benchmark.full.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### PuBG-CrossFire — `com.tencent.tmgp.cf`
```sh
am launch -n com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity \
    -a com.tencent.tmgp.cf.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### Ludashi — `com.ludashi.aibench`
```sh
am launch -n com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity \
    -a com.ludashi.aibench.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### Genshin — `com.miHoYo.GenshinImpact`
```sh
am launch -n com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity \
    -a com.miHoYo.GenshinImpact.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

### Original — `com.xiaoji.egggame`
```sh
am launch -n com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity \
    -a com.xiaoji.egggame.LAUNCH_GAME \
    --es localGameId {file_content} --es steamAppId {file_content} \
    --ez autoStartGame true
```

---

## How to find a game's `localGameId`

> ⚠️ **CRITICAL — `localGameId` must be the INTEGER `server_game_id`**, not a prefixed text id. The 6.0.4 deep-link dispatch parses `localGameId` as an `Integer` and rejects anything that isn't a positive integer. If you see ids that look like `local__sUXtKCeS_...` or `gog_1709371377` somewhere else, those **will not work** — you need the numeric `server_game_id` (e.g. `49908` for God of War). The built-in **Show Game ID** dialog returns the correct integer value; use it.

### Method 1 (recommended) — in-app Show Game ID dialog

1. Open BannerHub v6 and navigate to the game's **Game Details** page (tap the game tile → View Details).
2. Tap the **3-dot More Menu** button.
3. Tap **Show Game ID** — a dialog pops up with the integer `server_game_id` GameHub uses internally.
4. Tap **Copy** to copy the id.
5. Save it into your `.txt` / `.iso` file (Beacon reads the file's content as the `{file_content}` value in the launch command).

The same dialog has a **View All Games** button that opens the full library (backed by `db_game_library.db`) — tap any row to copy that game's id.

### Method 2 (rooted devices) — bulk dump via sqlite3

If you have many games to set up and a rooted device, you can dump the whole library at once:

```sh
sqlite3 /data/data/<variant_pkg>/databases/db_game_library.db \
  "SELECT id, server_game_id, steam_app_id, game_name FROM t_game_library_base;"
```

Replace `<variant_pkg>` with the package matching your installed variant (e.g. `gamehub.lite` for Normal-GHL — see the [variant table](#per-variant-configuration) above).

---

## Games that DON'T work via Beacon dispatch

The 6.0.4 deep-link dispatch only handles entries whose `server_game_id` is a **positive integer**. The following game types have `server_game_id = 0` or `-1` and are **not addressable** via this contract — they need to be launched from GameHub's library tile directly:

- **GOG-imported games** — `server_game_id = 0`
- **Epic-imported games** — `server_game_id = 0`, `id` is a 32-char hex UUID
- **Some manual imports** with no `server_game_id` assigned — `server_game_id = -1`

Steam-library and PC-imported games (any game whose `server_game_id` is a positive integer) work fine.

---

## Notes and quirks

- **Forgiveness fallback**: a stale `gamehub.lite.LAUNCH_GAME` action targeted at a different variant's package still resolves — the patch added that literal as a fallback for Beacon configs ported from the 5.3.5 Lite era. You should still configure with the correct per-variant action though.
- **Activity FQN never moves**: `DeepLinkActivity` is at the package root (not behind an R8 letter rename) — stable across base bumps.
- **Carry-forward**: this contract is identical to PlayDay's 5.3.5 contract. Beacon configs that worked on 5.3.5 BannerHub work on v6 with no changes, provided the package name matches the variant.

---

## References

- Patch source: [`patches/src/main/kotlin/app/revanced/patches/gamehub/misc/launcher/ExternalLauncherPatch.kt`](patches/src/main/kotlin/app/revanced/patches/gamehub/misc/launcher/ExternalLauncherPatch.kt)
- Java extension: [`extensions/gamehub/src/main/java/app/revanced/extension/gamehub/launcher/ExternalLauncher.java`](extensions/gamehub/src/main/java/app/revanced/extension/gamehub/launcher/ExternalLauncher.java)
- Native dispatch entry point in GameHub: `com.xiaoji.egggame.DeepLinkActivity.onCreate` — translates `app_nav_target` / `app_nav_game_id` / `app_nav_auto_start_game` into the in-app navigation routes.
