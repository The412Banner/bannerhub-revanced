# GameHub 6.3.2 vs 6.3.1 — base APK diff

**Date:** 2026-10-27
**Package:** `com.xiaoji.egggame`
**6.3.1:** versionName `6.3.1` / versionCode **141** / 67,122,098 b / sha256 `09b3e387205215ca0d10fe6881fff034939ae2c16a07b0ce470a48c6c51c6763`
**6.3.2:** versionName `6.3.2` / versionCode **143** / 72,522,043 b / sha256 `e51456bd3fb1c041dbdf3c756878ce1ddb37dfdb933b266142b22354b7d50ed2`
(versionCode **142 was skipped** upstream.)

minSdk 29 / targetSdk 36 / compileSdk 37 — unchanged. Same signing certificate (v2 only, cert sha256 `f6dc8925…1c9775`); v1/v3 absent in both.

Method: apktool 3.0.3 + jadx 1.5.6 on both APKs, plus direct diffs of the zip listing (name/size/CRC), manifest tree, dex string tables (all 5 dex), Compose `.cvr` string tables, Room SQL, and `libsteamkit_core.so` strings/exports. The app was not run.

## Headline

6.3.2 is a **feature release on the app side**. Nothing in the Wine/PC runtime ships in the APK (that is the separately downloaded PC Engine plugin), and the only native library that changed is the Rust Steam client `libsteamkit_core.so`.

New:
1. **Task Center + points Reward Store** ("Gaishi Points").
2. **Publisher Plan + Creator Center** (paid video-promotion tasks, China platforms).
3. **Reworked game import**: Auto Scan / Game Folder / Game File (.exe).
4. **Redesigned onboarding.**
5. **Help Center** in Settings, notification prompt, **Hindi** language.
6. **Steam download/update work**: update-size check, download preflight, resume, clearer Steam errors, install-folder conflict detection, safer removal.
7. **Play-time tracking for PC games** (new DB table), reported for points tasks.
8. **Server-side feature switches** (`feature-release/snapshot`).

## New features (from strings, DTOs, endpoints)

### 1. Task Center and Reward Store (`features.points`, new module, 132 EN strings)
- Daily / weekly / monthly / growth tasks: check in (streak bonuses), browse, watch, follow, share, post, "try games". Points expire on 1 March of the following year.
- Store sells cloud-play time (30 min / 3 h / 6 h / 24 h items), GameSir controllers (`gamepad_x5_lite`, `_blue`, `_bundle`) and code cards; physical prizes need a recipient name, phone number and full address, with shipping tracking.
- Points and "coins" are separate balances. Rules text says "China growth tasks award a total of 80 points per account".
- Entry: Mine tab → "Task Center", "Reward Store", "My rewards".
- Endpoints: `activity/mobile/v1/user/task-center/{entries,events,rules,sign}`, `mall/mobile/v1/user/task-mall/{balance,ledgers,orders,products}`, `/cards/reveal`.
- DTOs (kept names): `PointsClientEventRequestDto(eventKey, eventType, objectKey, scopeKey, occurredAt, deviceId, businessDate, deltaSeconds, postId, shareChannel)`, `PointsEventOutboxDto(confirmedNotificationObjects, timezone, pending, dayTotals, sessions)`, `PointsOrderCard(s)Dto`, `PointsClientEventReceiptDto`.

### 2. Publisher Plan and Creator Center (`features.publisher`, new module, 232 EN strings)
- "The Publisher Plan is GameHub's promotional task platform. Claim a task and publish a game promotion video on Douyin, Bilibili, Kuaishou, or Xiaohongshu. Earn GameHub Coins…"
- Create tasks with a prize pool, unit price and per-submission cap; submit videos (≥15 s, gameplay required); wallet, top-up ("GameHub Coins"), redeem store.
- Creator Center: apply for a verified creator badge (follower and post thresholds), benefits "Revenue / Creator tag / Traffic boost". Applying requires **identity (real-name) verification**.
- Guest prompt: "Log in to join the Publisher Plan".
- Endpoints: `v1/user/publisher-creator/{access,applications}`, `v1/user/publisher-info`, `v1/user/publisher-mall/{orders,products}`, `v1/user/publisher-recharge/goods`, `v1/user/publisher-tasks/options`, `v1/user/publisher-wallet/logs`, `resource/v1/user/upload`.
- Gated by the new feature-release switch with feature code `publisher_plan`.

### 3. Server-side feature switches (new)
- `game/mobile/v1/user/feature-release/snapshot` → `FeatureReleaseSnapshotDto(featureCode, masterSwitch, visible, guestVisible, deniedReason, configVersion, issuedAt, expiresAt)`, cached by `core.impl.featurerelease.FeatureReleaseStore`.
- New "coming soon" placeholders fit this: "PC games are coming soon", "Community features are coming soon...", "Save features are coming soon...", "Activity features are coming soon...". Which features are switched off per region/account is decided by the server; not observed here.

### 4. Game import rework
- Home import choices are now **Auto Scan** ("Find installed games in bulk", marked "Top pick"), **Game Folder** ("Find the launcher in a game folder"), **Game File (.exe)** ("Pick an .exe to add one game"), and the Steam library folder ("Bulk import a folder with steamapps").
- The local-game scanner moved out of onboarding into the shared `common.game` module (29 `features_onboarding_scan_*` strings removed, 26 `common_game_local_scan_*` added), so it is reachable from Home, not only first run. New: "Steam library found. Log in to Steam to import", "Wrong match? / Switch game", "Imported" badge.
- PC reference picker renamed "Select Target Game"; picking one now only changes the game info, not the launch file.

### 5. Onboarding redesign
- New intro cards: "Steam Sign-In / AAA Games Galore", "Game Presets / No Setup Needed", "Play Locally / Go Handheld" (iOS variants: Player Community, Game Picks, Game Guides).
- Old cards removed ("Pro key layouts, one-tap apply", "Portrait explore, landscape play", "Steam library, cross-save", "How do you want to start?"). New background art (wall, dots, welcome), old images removed.

### 6. Settings
- **Help Center**: searchable help articles with detail pages (`game/mobile/v1/public/help-center/navigations`); "Feedback & Suggestions" renamed "Help & Feedback", plus "Feedback Center". The old help-screen keys `help_settings_official_website` and `help_settings_feedback_history` are gone.
- **Notification prompt**: "Enable notifications" dialog.
- **Hindi** (`hi-IN`) added to the language list; every module got a `values-hi-rIN` string table. Points/publisher also ship ja, pt-BR, ru, zh-CN.

### 7. Steam downloads, updates and removal
- **Update dialog** shows "Estimated update download" and "Estimated size after update" (`FastUpdateCheckDto`, `SizeEstimateDto`; new Rust command `download.fast_check_update`).
- **Download preflight** ("Could not prepare the download. Check your connection and Steam account, then retry", `DepotPreflightDispositionDto`) and **resume** ("Resuming download…", `DownloadResumeProfileDto`; Rust `resume_profile`, `download.release_paused`).
- **Clearer Steam errors**: the 6.3.1 Rust library already had codes such as `steam.auth.rejected`, `steam.cm.try_another`, `steam.storage.disk_full`, `steam.transport.request_timeout`, `steam.entitlement.app_not_owned`; 6.3.2's Java side now maps them to messages ("Steam is temporarily limiting login attempts…", "Steam could not verify your saved login…", "Timed out waiting for Steam to sign in…"). New code `steam.install_root.conflict`: "This game is registered in another folder. Remove it with Keep game files selected, then download it to the new folder."
- "Not enough storage space" replaced by "Installation storage is unavailable. Check storage access and free space, or choose another folder".
- **Removal**: progress and cancel for removing download tasks; uninstall keeps files when the storage location is unavailable (e.g. disconnected SD card) or cannot be verified.
- **Cloud saves**: "Uploading cloud save..."; "Managed by Steam client"; `SteamCloudSaveService` is now a foreground service of type `specialUse` (permission was already declared).

### 8. Controllers
- Bluetooth: location-permission explanation for scanning on Android ≤ 11, "Location services are off" handling, warning when many Bluetooth devices are connected (replaces the old paired-device-limit dialog).
- New notice: "Due to a service change, GameHub does not currently support controller settings or firmware upgrades. To use these features, please use the GameSir app." When it is shown was not traced.
- Firmware update code was **not** removed — it grew (OTA strings 188 → 204): GameSir **G8 SE** firmware targets, USB firmware info and a server-provided multi-part firmware manifest (`GamepadOtaFirmwareManifest`, `GamepadOtaFirmwarePart`).
- Virtual controller: icon-pack import result counts, remote icon cache table, "Download count" label; gamepad config "Button Settings".

### 9. Play-time tracking
- New table `pc_game_sessions(session_id, game_id, game_type, duration_seconds, started_at, last_updated_at, user_id)`; cloud and retro session tables get `user_id`, retro sessions get `reported`. Daily sums by date (`SUM(duration_seconds) … WHERE started_at >= ? AND started_at < ?`).
- Reported through the points events (`deltaSeconds`, `deviceId`, `businessDate`; outbox with `sessions`, `dayTotals`, `timezone`) for "try games" style tasks.

### 10. Smaller items
- Team Play: "Report" and "Send" buttons in team rooms.
- Date picker labels, linked-game search, rating star moved to `core`.

## Under the hood

### Manifest
- Only change besides version: `SteamCloudSaveService` gains `android:foregroundServiceType="specialUse"` (0x40000000).
- Permissions (66), activities/services/receivers/providers/meta-data (328 entries): **identical**.

### Native libraries
- Only `lib/arm64-v8a/libsteamkit_core.so` changed: 15,508,584 → 17,659,160 b (+2,150,576). All other libs byte-identical.
- Rust Steam client (`steamkit-core`). Same 11 exported JNI functions. Built with **Rust 1.88.0** (6.3.1 used 1.94.0) and with dependency source paths stripped, so a crate-version diff is not possible from strings.
- Most feature markers (QR login, saved-account login, guard codes, family groups, forums data, cloud upload) are present in both builds. New in 6.3.2: `fast_check_update`, `release_paused`, `resume_profile`, `steam.install_root.conflict`.

### Dex
- Still 5 dex. 10.1 + 8.8 + 14.0 + 14.3 + 9.5 MB → 11.1 + 8.9 + 11.6 + 16.7 + 11.0 MB (+2.57 MB).
- **R8 reshuffled every obfuscated name** (map id `e2596808…` → `cd9c3f9a…`); same short names now belong to unrelated classes (e.g. `Lkee;` checkout-key DTO → `t_game_launch_method` query).
- New kept-name classes: `common.steam_sdk.impl.{DepotPreflightDispositionDto, DownloadResumeProfileDto, FastUpdateCheckDto, SizeEstimateDto}`, `core.network.model.points.dto.*`, `core.navigation.model.gamepadota.GamepadOtaFirmwareManifest`, `core.impl.featurerelease.FeatureReleaseStore`. okio classes now keep their names (see plugin host).
- String table: 225,580 → 231,452 unique strings (+8,439 / −2,498, mostly R8 renames).

### Database (Room, `AppDatabase`)
- Version **17 → 20**. The migration class (`ja5`, `androidx.room3` Migration) carries the SQL for the new tables and columns; whether a destructive fallback is also configured was not checked.
- New tables `pc_game_sessions`, `pending_points_command` (+ indexes, + `product_revision`); `user_id` added to `cloud_game_sessions`, `retro_game_sessions`, `pc_game_sessions`; `reported` added to `retro_game_sessions`.
- `user_account` and `auth_token` columns unchanged.
- Separate cloud-session and retro DBs also changed identity hash (same column additions).

### PC Engine plugin host
- Plugin manifest endpoint unchanged (`game/mobile/v1/plugin/latest`), same host actions (`INSTALL_PLUGIN`, `COMMIT_PLUGIN_INSTALL`, `ROLLBACK_PLUGIN_INSTALL`, `HEALTH_PROBE`…), same `min/maxHostVersionCode` model.
- Parent-first class list shared with plugins gained **`okio.`**: `java.,javax.,android.,dalvik.,com.xiaoji.egggame.plugin.pcengine.host.,kotlin.,kotlinx.,okio.,androidx.,…`. A plugin built for 6.3.2 may expect the host's okio.

### Resources
- `assets/composeResources`: +108 files (Hindi tables for every module, points/publisher modules, new onboarding/points/publisher/import art), −13 (old onboarding images, community search/star icons), 65 changed (62 string tables, 3 onboarding images). EN UI strings 4,463 → 4,914 (+483 / −32 / 10 reworded).
- `resources.arsc`: no new resource names; `hi-IN` added to locales.

### Network
- No new or removed hosts. New paths listed above (task center, task mall, publisher, help center, feature-release snapshot, resource upload).

## Impact on BannerHub v6

1. **Version gate:** `Constants.kt` `GAMEHUB_VERSION = "6.3.1"` → must become `"6.3.2"`, or every patch skips as incompatible.
2. **String anchors:** of 184 strings the patches reference that exist in 6.3.1, **183 still exist in 6.3.2**; only `"6.3.1"` is gone.
3. **Hardcoded obfuscated names:** about 333 short class descriptors (`Laub;`, `Lpo7;`, `Lpe0;`, …) appear in the patch sources (some may be in comments). All need re-deriving because R8's map changed — keystone `MenuGameIdCapturePatch` first, then BypassLogin, DebugLog, RedirectCatalogApi/PrefixApiPath, PcEnginePluginManifestRedirect, Explore, Vibration, OfflineComponentList, analytics strips.
4. **Login bypass / DB:** `AppDatabase` 17 → 20. The seeded `user_account` table is unchanged, but installing over a 631 build runs the new migrations; 631 → 632 upgrade with the seeded user needs a device test.
5. **Plugin:** our re-signed plugin (build 111-7 for 631) under a 6.3.2 host is untested. The host now shares okio with plugins; upstream may serve a newer plugin for vc143 (not checked; it needs a call to upstream `plugin/latest`).
6. **Privacy backlog:** the Worker (`bannerhub-worker.js` @ `4b09d909`) forwards any path it does not handle to `landscape-api.vgabc.com` with the shared real token. If these new calls reach the Worker, they would go upstream under the shared account: `task-center/events` (deviceId, play-time deltas), `feature-release/snapshot`, task-mall, publisher, help-center, `resource/v1/user/upload`. Candidates to stub (decide per feature; the snapshot may need a canned "visible" answer if we want features shown).
7. **No renderer/Wine work from the APK side** — runtime lives in the plugin.

## Not checked
- The app was not installed or run; nothing here is device-tested.
- Which features the server switches off for our accounts/region.
- When the GameSir "service change" notice appears.
- The upstream PC Engine plugin version served to vc143.
