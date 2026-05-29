# External launcher setup for BannerHub v6

Launch any game from **Beacon**, **ES-DE**, **RetroHRAI**, **NeoStation**, or **Daijishou** directly into BannerHub v6 — game starts playing without a stop in the GameHub UI.

> 📺 **Video walkthroughs:**
> • **Beacon** — <https://youtu.be/hyjjs-ffpw4?si=Lp6CCGhwFKGR0tAA> (also covers creating PC-import game `.txt` / `.iso` files with GameID numbers)
> • **RetroHRAI** — <https://youtu.be/tcYGLLRtCPY?si=0oEWYZo-8QopFQey>
> • **NeoStation** — <https://youtu.be/mTn7La43LpQ?si=4PPV_gpKl_AwTchM>

| Front-end | Status | Placeholder |
| --- | --- | --- |
| **Beacon** | ✅ verified | `{file_content}` |
| **ES-DE** | ✅ verified | `{file_content}` |
| **RetroHRAI** | ✅ verified (v1.5.1-604) | `{tags.localgameid}` |
| **NeoStation** | ✅ verified (v1.5.1-604) | `{tags.localgameid}` |
| **Daijishou** | ⚠️ untested (should work) | `{tags.localgameid}` |

> 🛰️ **NeoStation users — no copying needed.** Drop a `<game>.localgameid` file (content = numeric game ID) in your `windows/` folder and pick **Standalone BannerHub v6 (Normal)** (or your matching variant) from the emulator picker. Requires upstream [`miguelsotobaez/neostation-systems` PR #9](https://github.com/miguelsotobaez/neostation-systems/pull/9) (on top of the BannerHub v6 launch rows from [PR #7](https://github.com/miguelsotobaez/neostation-systems/pull/7)); trigger NeoStation's systems-sync once both have landed.

---

## Step 1 — Get your game's ID

In BannerHub: open the game → tap **3-dot menu** → **Banner Tools** → **Show Game ID** → **Copy**.

Or, for setting up many games at once on a rooted device:

```sh
sqlite3 /data/data/<variant_pkg>/databases/db_game_library.db "SELECT id, server_game_id, steam_app_id, game_name FROM t_game_library_base;"
```

The ID must be a **positive integer** (e.g. `49908`). Text-like ids (`local_…`, `gog_…`) won't work — the Show Game ID dialog returns the right integer; use it.

## Step 2 — Save the ID

Put the integer in a plain text file under your launcher's ROM folder. File extension depends on the launcher:

- **Beacon / ES-DE** → `.txt` or `.iso`
- **RetroHRAI / Daijishou** → `.txt` or `.iso` (same)
- **NeoStation** → `.localgameid`

The file's content is just the number (e.g. `49908`). Nothing else.

## Step 3 — Paste the command

Find your installed BannerHub variant below. Use the **For Beacon / ES-DE** block if you're on Beacon or ES-DE; use the **For RetroHRAI / NeoStation / Daijishou** block otherwise. Both produce the same intent on the BannerHub side.

### Normal — `banner.hub`
*For Beacon / ES-DE:*
```
am start -n banner.hub/com.xiaoji.egggame.DeepLinkActivity -a banner.hub.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n banner.hub/com.xiaoji.egggame.DeepLinkActivity -a banner.hub.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Normal-GHL — `gamehub.lite`
*For Beacon / ES-DE:*
```
am start -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity -a gamehub.lite.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n gamehub.lite/com.xiaoji.egggame.DeepLinkActivity -a gamehub.lite.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### PuBG — `com.tencent.ig`
*For Beacon / ES-DE:*
```
am start -n com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.ig.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.tencent.ig/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.ig.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### AnTuTu — `com.antutu.ABenchMark`
*For Beacon / ES-DE:*
```
am start -n com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.ABenchMark.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.antutu.ABenchMark/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.ABenchMark.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### alt-AnTuTu — `com.antutu.benchmark.full`
*For Beacon / ES-DE:*
```
am start -n com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.benchmark.full.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.antutu.benchmark.full/com.xiaoji.egggame.DeepLinkActivity -a com.antutu.benchmark.full.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### PuBG-CrossFire — `com.tencent.tmgp.cf`
*For Beacon / ES-DE:*
```
am start -n com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.tmgp.cf.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.tencent.tmgp.cf/com.xiaoji.egggame.DeepLinkActivity -a com.tencent.tmgp.cf.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Ludashi — `com.ludashi.aibench`
*For Beacon / ES-DE:*
```
am start -n com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity -a com.ludashi.aibench.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.ludashi.aibench/com.xiaoji.egggame.DeepLinkActivity -a com.ludashi.aibench.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Genshin — `com.miHoYo.GenshinImpact`
*For Beacon / ES-DE:*
```
am start -n com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity -a com.miHoYo.GenshinImpact.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.miHoYo.GenshinImpact/com.xiaoji.egggame.DeepLinkActivity -a com.miHoYo.GenshinImpact.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

### Original — `com.xiaoji.egggame`
*For Beacon / ES-DE:*
```
am start -n com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity -a com.xiaoji.egggame.LAUNCH_GAME --es localGameId {file_content} --ez autoStartGame true
```
*For RetroHRAI / NeoStation / Daijishou:*
```
am start -n com.xiaoji.egggame/com.xiaoji.egggame.DeepLinkActivity -a com.xiaoji.egggame.LAUNCH_GAME --es localGameId {tags.localgameid} --ez autoStartGame true
```

> 💡 **Steam-only platform?** Swap `--es localGameId <placeholder>` for `--es steamAppId <placeholder>` (and put the Steam appid in your ROM file). Don't mix Steam and PC-import games on the same launcher platform — the Steam appid lookup can mistarget if values collide.

> 💡 **Lite APKs** share their full counterpart's package — use the same command. Launcher label has " Lite" appended (e.g. **BannerHub v6 Lite**).

---

## Gotchas

- **`[localgameid]` in brackets is NOT a command placeholder.** It's a Daijishou-style in-file marker that may optionally appear inside the ROM file's content (e.g. `[localgameid]49908`). Putting `[localgameid]` in the `am start` command itself does nothing — the literal text passes through to BannerHub, which logs `BhExternalLauncher: ignoring non-numeric localGameId=[localgameid]` and aborts.
- **alt-AnTuTu over AnTuTu for external launchers.** The original AnTuTu variant (`com.antutu.ABenchMark`) is finicky with Beacon — use the **alt-AnTuTu** variant (`com.antutu.benchmark.full`) for external-launcher setups.
- **One line, no `\` continuations.** Beacon / RetroHRAI / NeoStation fields treat the whole input as one command — split lines will break the launch.
- **String wins over int.** The patch reads both `--es` (String → parsed to int) and `--ei` (int). String form matches what every front-end actually sends.

## Game type coverage

| Game type | Stock `server_game_id` | Works? |
| --- | --- | --- |
| Steam-library | positive int | ✅ |
| PC-imported (catalog match) | positive int | ✅ |
| PC-imported (no match) | `-1` | ✅ since v1.5.1-604 |
| Epic-imported | `0` | ✅ since v1.5.1-604 |
| GOG-imported | `0` | ✅ since v1.5.1-604 |

BannerHub `v1.5.1-604` rewrites `0` / `-1` sentinels to stable synthetic 32-bit IDs so each row becomes individually addressable. The Show Game ID dialog reports the synthetic — copy it into your ROM file.

## References

- Intent contract: `com.xiaoji.egggame.DeepLinkActivity` (same FQN across all 9 variants), action `<variant_pkg>.LAUNCH_GAME`, extras `localGameId` / `steamAppId` (String→int) + `autoStartGame` (bool).
- Patch source: [`patches/src/main/kotlin/app/revanced/patches/gamehub/misc/launcher/ExternalLauncherPatch.kt`](patches/src/main/kotlin/app/revanced/patches/gamehub/misc/launcher/ExternalLauncherPatch.kt)
- Extension source (reads extras, dispatches to `app_nav_target=game_detail`): [`extensions/gamehub/src/main/java/app/revanced/extension/gamehub/launcher/ExternalLauncher.java`](extensions/gamehub/src/main/java/app/revanced/extension/gamehub/launcher/ExternalLauncher.java)
- NeoStation upstream PRs: [#7](https://github.com/miguelsotobaez/neostation-systems/pull/7) (BannerHub v6 launch rows) · [#9](https://github.com/miguelsotobaez/neostation-systems/pull/9) (`.localgameid` extension)
