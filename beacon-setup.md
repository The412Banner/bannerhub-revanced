# External launcher setup for BannerHub v6

How to configure **Beacon**, **ES-DE**, **RetroHRAI**, and **Daijishou** to launch games into any of BannerHub v6's nine variants on the 6.0.4 base. All four share the same Android intent contract — only the per-launcher **placeholder syntax** for the ROM-file value differs (Beacon-family vs. Daijishou-family — see [Placeholder syntax by front-end](#placeholder-syntax-by-front-end) below).

> ✅ **Beacon — device-confirmed working** (the412banner).
> ✅ **ES-DE — device-confirmed working** (slogik, v1.5.1-604).
> ✅ **RetroHRAI — device-confirmed working** (the412banner, v1.5.1-604, 2026-05-22).
> ⚠️ **Daijishou — untested but should work** (shares RetroHRAI's `{tags.localgameid}` placeholder convention). Please report results.
> ℹ️ **Epic Games library** — as of `v1.5.1-604`, Epic-imported games launch end-to-end via the synthetic-ID rewrite (the previous `app_nav_epic_app_name` route was upstream-blocked at `GameDetailViewModel`; this is the workaround). GOG-imported games launch the same way.

The intent contract is identical across all 9 variants — only the package name and the action prefix change. The `DeepLinkActivity` class FQN is the same everywhere because `ChangePackageNamePatch` only rewrites the manifest `package=` attribute, not class names.

> 📺 **Video walkthrough (Bannerhub v6(Lite))** — covers creating PC-import game txt/iso files with GameID numbers: <https://youtu.be/hyjjs-ffpw4?si=JBpeFCMjNtzFlbY9>

> 💡 **Why "alt-AnTuTu" exists.** The original **AnTuTu** variant (`com.antutu.ABenchMark`) is finicky with Beacon — historically it didn't work, which is why the **alt-AnTuTu** variant (`com.antutu.benchmark.full`) was created and is the recommended AnTuTu-flavored package for external-launcher setups.

---

## Beacon in-app walkthrough

1. **Settings** → tap the **+ icon** to add a new platform.
2. Fill in:
   - **Platform Type**: `Windows`
   - **Player app**: select the BannerHub v6 variant you have installed. The patch sets the Android launcher label, so the picker shows e.g. **BannerHub v6** for the Normal / Normal-GHL / Original variants, **BannerHub v6 PuBG** for PuBG, **BannerHub v6 AnTuTu** for both AnTuTu variants, etc. (full launcher-label column in the [variant table](#per-variant-configuration) below). Lite APKs append "Lite" → **BannerHub v6 Lite**, **BannerHub v6 PuBG Lite**, etc.
   - **ROMs folder**: use the Android file picker to select the folder containing your game `.txt` / `.iso` files (each file's content is a single `localGameId` or `steamAppId` number — see [How to find a game's `localGameId`](#how-to-find-a-games-localgameid) below).
3. Expand **Advanced**:
   - **File handling**: `Default`
   - **Use custom launch**: `True`
   - **am start command**: paste the per-variant block from [Shell reference](#shell-reference-per-variant-am-examples) below — picking the row matching your installed variant.
4. Tap **Save**.
5. **Scan** the folder for your games.
6. **Launch** a game — it should hand off into BannerHub and (with `autoStartGame true`) start playing directly.

> ⚠️ **If you have multiple variants installed**, three of them share the launcher label **"BannerHub v6"** (Normal / Normal-GHL / Original) and two share **"BannerHub v6 AnTuTu"** (AnTuTu / alt-AnTuTu). Beacon's picker usually shows the package name underneath each entry — match the package against the [variant table](#per-variant-configuration) below to pick the right one. The `am start command` you paste in step 3 already encodes the package, so it's the authoritative selector — the Player-app picker mostly just decides which icon Beacon shows on the platform card.

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

| Variant | Launcher label | Package | Component | Action |
|---|---|---|---|---|
| Normal | BannerHub v6 | `banner.hub` | `banner.hub/com.xiaoji.egggame.DeepLinkActivity` | `banner.hub.LAUNCH_GAME` |
| Normal-GHL | BannerHub v6 | `gamehub.lite` | `gamehub.lite/com.xiaoji.egggame.DeepLinkActivity` | `gamehub.lite.LAUNCH_GAME` |
| PuBG | BannerHub v6 PuBG | `com.tencent.ig` | `com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity` | `com.tencent.ig.LAUNCH_GAME` |
| AnTuTu | BannerHub v6 AnTuTu | `com.antutu.ABenchMark` | `com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity` | `com.antutu.ABenchMark.LAUNCH_GAME` |
| alt-AnTuTu | BannerHub v6 AnTuTu | `com.antutu.benchmark.full` | `com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity` | `com.antutu.benchmark.full.LAUNCH_GAME` |
| PuBG-CrossFire | BannerHub v6 PuBG CrossFire | `com.tencent.tmgp.cf` | `com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity` | `com.tencent.tmgp.cf.LAUNCH_GAME` |
| Ludashi | BannerHub v6 Ludashi | `com.ludashi.aibench` | `com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity` | `com.ludashi.aibench.LAUNCH_GAME` |
| Genshin | BannerHub v6 Genshin | `com.miHoYo.GenshinImpact` | `com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity` | `com.miHoYo.GenshinImpact.LAUNCH_GAME` |
| Original | BannerHub v6 | `com.xiaoji.egggame` | `com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity` | `com.xiaoji.egggame.LAUNCH_GAME` |

> **Lite variants** (`-Lite` filenames) share their full counterpart's package and action — use the same row as the matching full variant. The launcher label has " Lite" appended (e.g. **BannerHub v6 Lite**, **BannerHub v6 PuBG Lite**, **BannerHub v6 AnTuTu Lite**).

---

## Placeholder syntax by front-end

The 4 supported front-ends substitute the ROM-file's content into the `am start` command using **different placeholder tokens**. Use the syntax matching your front-end — anything else will pass through as a literal string and break the launch.

| Front-end | Placeholder | Why |
| --- | --- | --- |
| **Beacon** | `{file_content}` | Beacon's own template variable. |
| **ES-DE** | `{file_content}` | Compatible with Beacon's syntax. |
| **RetroHRAI** | `{tags.localgameid}` | RetroHRAI's `UnifiedEmulatorLauncher.TAGS_PATTERN = \{tags\.(\w+)\}`. Valid `<name>` set: `localgameid`, `steamappid`, `gog`, `epicgame`, `epic`, `customgame`, `pcgame`, `vita_game_id`. |
| **Daijishou** | `{tags.localgameid}` (expected — untested) | RetroHRAI inherits its `[localgameid]`-in-file marker convention from Daijishou, so the command-line tag form is expected to match too. |

> ⚠️ **`[localgameid]` (square brackets) is NOT a command-line placeholder.** It's a Daijishou-style **in-file marker** that may optionally appear inside the ROM file's content (e.g. file contents `[localgameid]49908` instead of just `49908`). Putting `[localgameid]` in the `am` command itself does **not** get substituted — RetroHRAI's TAGS_PATTERN won't match it, and the literal text goes through to BannerHub, which logs `BhExternalLauncher: ignoring non-numeric localGameId=[localgameid]` and aborts.

> ⚠️ Do **not** split commands across multiple lines with `\` continuations — Beacon/RetroHRAI fields treat the whole thing as one command and the backslashes/newlines will break the launch. Paste as a single line.

The shell-reference blocks below come in two flavors:

- **Beacon / ES-DE** — `{file_content}` placeholder
- **RetroHRAI / Daijishou** — `{tags.localgameid}` placeholder

Both flavors emit the same intent on the BannerHub side; only the launcher's substitution layer differs.

---

## Shell reference (per-variant `am` examples) — Beacon / ES-DE

Each block is the literal **single-line** `am launch` command to paste into Beacon's **am start command** field (or run on-device for validation). Use `localGameId` (preferred — works for any PC-import or Steam game) and/or `steamAppId` (Steam games only). `{file_content}` is Beacon's own template-variable placeholder — at scan time it's replaced with the content of the `.txt` / `.iso` file for that game.

### Normal — `banner.hub`
```
am launch -n banner.hub/com.xiaoji.egggame.DeepLinkActivity -a banner.hub.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### Normal-GHL — `gamehub.lite`
```
am launch -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity -a gamehub.lite.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### PuBG — `com.tencent.ig`
```
am launch -n com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.ig.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### AnTuTu — `com.antutu.ABenchMark`
```
am launch -n com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.ABenchMark.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### alt-AnTuTu — `com.antutu.benchmark.full`
```
am launch -n com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.benchmark.full.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### PuBG-CrossFire — `com.tencent.tmgp.cf`
```
am launch -n com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.tmgp.cf.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### Ludashi — `com.ludashi.aibench`
```
am launch -n com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity -a com.ludashi.aibench.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### Genshin — `com.miHoYo.GenshinImpact`
```
am launch -n com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity -a com.miHoYo.GenshinImpact.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

### Original — `com.xiaoji.egggame`
```
am launch -n com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity -a com.xiaoji.egggame.LAUNCH_GAME --es localGameId {file_content} --es steamAppId {file_content} --ez autoStartGame true
```

---

## Shell reference (per-variant `am` examples) — RetroHRAI / Daijishou

Each block is the literal **single-line** `am start` command to paste into RetroHRAI's **Custom Player → am start arguments** field. `{tags.localgameid}` is RetroHRAI's template-variable placeholder — at launch time it's replaced with the integer extracted from the ROM file's content (`extractTagId` returns the first non-blank line that doesn't start with `#` or `[`, so a bare-integer file like `49908` works directly; the explicit Daijishou form `[localgameid]49908` inside the file also works).

> 💡 **Setting up the RetroHRAI Custom Player** — Settings → Players → Custom Players → tap **+**. Set `name` (e.g. "BannerHub V6"), `emulatorPackage` (the per-variant package — e.g. `banner.hub`), and paste the matching command into `am start arguments`. Save. Then create a Custom Platform pointing at the ROM folder and link this player as primary.

> 📁 **ROM folder + file contents are identical to Beacon's** — each `.txt` / `.iso` file's content is the integer `localGameId` for that game (read via Banner Tools → Show Game ID). Beacon and RetroHRAI can share the same folder.

### Normal — `banner.hub`
```
am start -n banner.hub/com.xiaoji.egggame.DeepLinkActivity -a banner.hub.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Normal-GHL — `gamehub.lite`
```
am start -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity -a gamehub.lite.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### PuBG — `com.tencent.ig`
```
am start -n com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.ig.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### AnTuTu — `com.antutu.ABenchMark`
```
am start -n com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.ABenchMark.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### alt-AnTuTu — `com.antutu.benchmark.full`
```
am start -n com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.benchmark.full.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### PuBG-CrossFire — `com.tencent.tmgp.cf`
```
am start -n com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.tmgp.cf.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Ludashi — `com.ludashi.aibench`
```
am start -n com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity -a com.ludashi.aibench.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Genshin — `com.miHoYo.GenshinImpact`
```
am start -n com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity -a com.miHoYo.GenshinImpact.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Original — `com.xiaoji.egggame`
```
am start -n com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity -a com.xiaoji.egggame.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

> 🧪 For a **Steam-only** RetroHRAI platform, swap `--es localGameId {tags.localgameid}` for `--es steamAppId {tags.steamappid}` — but don't mix this with PC imports on the same platform (the Steam appid lookup can mistarget if the value collides with a Steam catalog entry).

---

## How to find a game's `localGameId`

> ⚠️ **CRITICAL — `localGameId` must be the INTEGER `server_game_id`**, not a prefixed text id. The 6.0.4 deep-link dispatch parses `localGameId` as an `Integer` and rejects anything that isn't a positive integer. If you see ids that look like `local__sUXtKCeS_...` or `gog_1709371377` somewhere else, those **will not work** — you need the numeric `server_game_id` (e.g. `49908` for God of War). The built-in **Show Game ID** dialog returns the correct integer value; use it.

### Method 1 (recommended) — in-app Show Game ID dialog

1. Open BannerHub v6 and navigate to the game's **Game Details** page (tap the game tile → View Details).
2. Tap the **3-dot More Menu** button.
3. Tap **Show Game ID** — a dialog pops up with the integer `server_game_id` GameHub uses internally.
4. Tap **Copy** to copy the id.
5. Save it into your `.txt` / `.iso` file. Beacon / ES-DE substitute the file's content for `{file_content}` in the launch command; RetroHRAI substitutes for `{tags.localgameid}` (see [Placeholder syntax by front-end](#placeholder-syntax-by-front-end)).

The same dialog has a **View All Games** button that opens the full library (backed by `db_game_library.db`) — tap any row to copy that game's id.

### Method 2 (rooted devices) — bulk dump via sqlite3

If you have many games to set up and a rooted device, you can dump the whole library at once:

```sh
sqlite3 /data/data/<variant_pkg>/databases/db_game_library.db "SELECT id, server_game_id, steam_app_id, game_name FROM t_game_library_base;"
```

Replace `<variant_pkg>` with the package matching your installed variant (e.g. `gamehub.lite` for Normal-GHL — see the [variant table](#per-variant-configuration) above).

---

## Game type coverage

The 6.0.4 deep-link dispatch parses `localGameId` as an `Integer` and only handles rows with a positive-integer `server_game_id`. Stock GameHub stamps `0` or `-1` for several game classes, which on a pre-v1.5.1 build all collided on the same nominal target and broke external launching. **As of `v1.5.1-604` the BannerHub patch rewrites those sentinels to stable synthetic 32-bit IDs** derived from the row's `local_<UUID>`, so each game becomes individually addressable.

| Game type | Stock `server_game_id` | Addressable from front-ends? |
| --- | --- | --- |
| Steam-library | positive int | ✅ Yes (always worked) |
| PC-imported (catalog match) | positive int | ✅ Yes (always worked) |
| PC-imported (no match) | `-1` | ✅ **Yes — since v1.5.1-604** |
| Epic-imported | `0` | ✅ **Yes — since v1.5.1-604** |
| GOG-imported | `0` | ✅ **Yes — since v1.5.1-604** |

The Show Game ID dialog (Banner Tools → Show Game ID) reports the rewritten synthetic for sentinel rows — copy that value into your front-end's `.txt` / `.iso` file (the same file then serves both Beacon-style `{file_content}` and RetroHRAI-style `{tags.localgameid}` substitution). The synthetic is deterministic (survives renames, library refreshes, install path moves), idempotent (re-runs are no-ops), and self-healing (rows GameHub later re-matches to a real catalog ID are left alone).

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
