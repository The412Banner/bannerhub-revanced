# GameHub 6.0.9 vs 6.0.8 — base APK diff

**Date:** 2026-06-18
**Package:** `com.xiaoji.egggame`
**6.0.8:** versionName `6.0.8` / versionCode **119** / 75,980,526 b
**6.0.9:** versionName `6.0.9` / versionCode **121** / 86,643,386 b / md5 `ae6f18b57c111d3c4e5ce7c9932b5a66`
(versionCode **120 was skipped** upstream — likely an internal/region build.)

minSdk 29 / targetSdk 36 / compileSdk 36 — unchanged.

## Headline: native "Team Room" multiplayer + in-room Tencent voice chat

6.0.9 is a **feature release**, not a runtime change. The big addition is a native **Team Room / "Team Play"** social co-op system with **in-room voice chat** built on the **Tencent TRTC / IM SDK**.

Evidence:

### New native libraries (+~10 MB, accounts for the APK size bump)
| lib | what it is |
|-----|------------|
| `lib/arm64-v8a/libImSDK.so` | Tencent IM SDK (V2TIM) — instant messaging / room signalling |
| `lib/arm64-v8a/libliteavsdk.so` | Tencent LiteAV / TRTC — real-time audio/video |
| `lib/arm64-v8a/libtxffmpeg.so` | Tencent ffmpeg (LiteAV dependency) |
| `lib/arm64-v8a/libtxsoundtouch.so` | Tencent SoundTouch — audio pitch/tempo processing |

### New manifest permissions
- `android.permission.RECORD_AUDIO` (room voice)
- `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` (screen-share/stream in room)
- `com.tencent.liteav.audio2.permission.PermissionActivity`
- `com.oplus.ocs.permission.third` (OPPO/OnePlus audio fast-path)

### New manifest components (Tencent RTMP/TRTC)
- `com.tencent.rtmp.video.ScreenCaptureService`
- `com.tencent.rtmp.video.TXScreenCapture$TXScreenCaptureAssistantActivity`
- `com.tencent.liteav.audio2.permission.PermissionActivity`

### New drawables
- `features.home/`: `features_home_teamroom_ic_{dismiss,kick,room_id,start}`, `features_home_teamroom_status_prepare`, `ic_room_status_{playing,waiting}`, `ic_play_info`, `slot_p1..slot_p8` (8 player slots)
- `features.cloud/`: `ic_team_chat`, `ic_team_chat_send`, `ic_team_mic`, `ic_team_mic_off`, `ic_team_speaker`, `ic_team_speaker_off`
- `cardsystem/`: `ic_room_lock`, `ic_room_status_{playing,waiting}`

### Feature strings (decoded from `features.home` Compose resources)
- `features_home_play_tab_teamplay` = "Team Play" (new home tab)
- `features_home_create_room_title` = "Create Team Room"
- `features_home_create_room_confirm` = "Create and Enter Room"
- `features_home_create_room_description` = "Once created, you become the host and can share the room link. The room remains open to matched players until it's full."
- `features_home_create_room_name_label` = "Room Name"; `..._password_label` = "Password (optional)"
- Billing tie-in: `features_home_create_room_recharge_guide_*` = "Insufficient Instant Play time" / "Less than 10 minutes remain. Please recharge first." / "Recharge now" / "Later" → Team Room is gated behind the cloud-play / Instant-Play minute budget.

String-table growth concentrates in `features.home` (teamroom, biggest delta — e.g. EN 31,888 → 44,164 b), `features.cloud` (team chat), `core`, and `features.search`.

## Where Team Room / team voice actually lives in the app (and why it looks "missing")

Investigated 2026-06-18 — there is **no standalone "voice chat room" menu**. The feature is bolted onto GameHub's **cloud "Instant Play"** service and is **locked by default**. Two surfaces:

1. **Entry point = "Team Play" tab on Home → Play screen.** The Play tab bar is a 4-tab enum **Instant · PC · Retro · Team Play** (`features_home_play_tab_{instant,pc,retro,teamplay}`, built in `emk.java`/`fmk.java`). Team Play is where you create/join a room.
2. **The voice room itself = an in-game cloud OVERLAY, not a page.** It lives in the `features.cloud` module and only renders while inside a cloud Instant-Play session. It is **not openable cold**. Evidence — `cloud_team_*` prefs (read in `dq2.java`, `yr2.java`):
   - `cloud_team_overlay_locked` → **default `true`** (locked out of the box)
   - movable overlay offsets: `cloud_team_avatar_offset_*`, `cloud_team_controls_offset_*`, `cloud_team_messages_offset_*`, `cloud_team_input_offset_*`, `cloud_team_overlay_alpha`
   - voice toggles: `cloud_team_voice_mic_enabled`, `cloud_team_voice_speaker_enabled`
   - lock UI: `cloud_team_lock_guide_*` (`fjk.java`/`gjk.java`), `cloud_team_position_locked`, `cloud_popup_content_team_lock`, `cloud_team_drawer_guide_message_full`

**Why it's effectively invisible on our builds:** gated behind the cloud Instant-Play product = a **server-side, China-region cloud-gaming service** requiring (a) a logged-in cloud account and (b) Instant-Play **recharge minutes** (`features_home_create_room_recharge_guide_*`). With no cloud account / outside region / no minutes, the Team Play tab is empty and `cloud_team_overlay_locked` never flips → the room never unlocks. Nothing is hidden by us; the feature simply never activates without the paid cloud backend.

**Relevance to v6:** this is upstream's own Tencent-TRTC voice riding on their cloud service, fully independent of our v6 in-game Steam voice overlay (WebRTC via Worker). Because their feature won't even unlock on our builds, the `RECORD_AUDIO`/audio-focus overlap flagged below is moot in practice — our overlay stays the only working in-game voice on a patched 6.0.9.

## What did NOT change (important for our patches)
- **No Wine / DXVK / Turnip / renderer `.so` touched.** The only changed *existing* native lib is `libsteamkit_core.so` (8,502,480 → 8,508,808 b, +6,328 b) — same small Steam-stack bump pattern as 607→608. Wine container format, imagefs, and renderer patches are **unaffected**.
- DEX layout: still **5 dex** (classes.dex + classes2..5). Total bytecode grew (Team Room + Tencent glue); per-dex sizes reshuffled by R8 as usual.
- `resources.arsc` essentially unchanged (+92 b). Legacy `res/values/strings.xml` unchanged — all new UI strings live in the Compose `.cvr` blobs.
- META-INF/services churn (`cm9,dy8,jo,vt3,ygd` → `a0e,ac4,bz3,no,yw9`) = ordinary R8 ServiceLoader-name reshuffle.

## Impact on the BannerHub v6 patch set
1. **Version gate (done):** every patch was pinned to `6.0.8` via `GAMEHUB_VERSION` in `Constants.kt`; on the raw 6.0.9 base all 495 patch applications skipped as "incompatible with 6.0.9". Bumped `GAMEHUB_VERSION` → `6.0.9` (GOG/GPU-spoof/legacy stay hard-pinned to 6.0.4 by design).
2. **Fingerprint re-derivation (TODO):** R8 reshuffled the obfuscated names again, so the manual-nav / obfuscated-anchor patches need re-pinning against 6.0.9 — same exercise as the 607→608 rebase.

   With the gate open (run `27760047512`, **green** — failures don't fail the build, ReVanced skips the failed patch), **14 patches failed**, decomposing into **9 root fingerprint breakages** (the 9 `Required value was null`) + **5 cascade failures** that only fail because a root dependency failed:

   **Root failures — need fingerprint re-derivation (9):**
   1. `Per-game menu id capture (shared)` — **keystone** (the More-Menu row resolver; re-do first, it unblocks 4 others)
   2. `Redirect catalog API` — unblocks `Prefix API path with /v6`
   3. `Bypass login`
   4. `Debug logging`
   5. `Explore tab hijack`
   6. `Offline component picker — local list`
   7. `PC-accurate vibration`
   8. `Show PC Game Settings row`
   9. `Stub analytics events`

   **Cascade failures — should resolve once their root is re-pinned (5):**
   - `Banner Tools menu row`, `PC Vibration Settings menu row`, `Show Game ID menu row`, `GOG menu row` → all depend on `Per-game menu id capture (shared)`
   - `Prefix API path with /v6` → depends on `Redirect catalog API`

   **Everything else applied cleanly on 6.0.9** (Firebase/GMS/MobPush/heartbeat/Ad-ID privacy strips, Aliyun NumberAuth strip, audio, explore drawables/manifest/version-stamp, file-manager access, app-icon, external launcher, GameID label resource, local game-id assignment, etc.). Identical failure set across all 9 variants.
3. **No renderer/Wine work needed** — those patches and the container/imagefs pipeline carry over untouched.
4. **New overlap to note:** upstream now ships its *own* in-room voice chat (Tencent TRTC). BannerHub v6 has its own in-game Steam voice/chat overlay (WebRTC via Worker). They're independent stacks; worth checking they don't fight over `RECORD_AUDIO` / audio focus once the overlay is exercised on a 6.0.9 build.

## Pipeline smoke test (run as-is)
- Run `27759846972` (gate still 6.0.8): **green** — `base-apk-609` downloaded, patches bundle built, all 9 variants patched + signed. But every patch skipped on the version gate → output ≈ stock 6.0.9 re-signed. Confirms pipeline *mechanics* work on the 6.0.9 base.
- Run `27760047512` (gate bumped to 6.0.9): generates the real fingerprint-failure worklist for re-derivation.
