# BannerHub v6 for ReVanced — Privacy

This document tells you what BannerHub v6 actually does to your data flow — both what it **kills** and what it **leaves in place**. The honest list of both matters: anyone running a DNS recorder against this APK can verify both halves, and we'd rather disclose the leftovers up-front than have you discover them yourself.

It only covers the **BannerHub-side patches**. It does not cover GameHub's upstream behavior (refer to [XiaoJi's GameHub site](https://gamehub.xiaoji.com/)), the Wine / Box64 stack, or the individual Windows games you run inside it.

---

## What we kill

Every row below is a real telemetry channel that was active on vanilla GameHub 6.0.4 and is **no longer reachable** in the BannerHub v6 build that ships from `gamehub-604-build`. The "Mechanism" column says how, and "Merge commit" links to the actual code that did it.

| Channel | What it leaked | Mechanism | Merge commit |
| --- | --- | --- | --- |
| **Firebase Analytics** | screen views, session starts, in-app purchases, app opens, custom events → `app-measurement.com` | Manifest `<meta-data>` kill switch (`firebase_analytics_collection_deactivated=true` + AD-ID/SSAID disables) — the SDK never initializes | [`178c5ec`](https://github.com/The412Banner/bannerhub-revanced/commit/178c5ec) |
| **Mob Push SDK** | device identifiers, push tokens, lifecycle events → Mob (`mob.com`) | Bytecode removes 3 SDK init call sites in `BaseAndroidApp` + helper; manifest disables every `com.mob.*` / `cn.fly.*` provider / service / receiver / activity (Mob's ContentProvider auto-init can't fire either) | [`282c9ea`](https://github.com/The412Banner/bannerhub-revanced/commit/282c9ea) |
| **Google AD-ID / ADSERVICES permissions** | advertising-ID exposure to any caller that queries Play Services | Stripped from `<uses-permission>` declarations (3 perms removed) | [`6817568`](https://github.com/The412Banner/bannerhub-revanced/commit/6817568) |
| **XiaoJi OTA update URL** | firmware-update phone-home to `xiaoji.com/firmware/update/x1` | URL register rewritten to `http://127.0.0.1` immediately after the const-string load; per-arch OTA native libs (`libJieLiUsbOta.so` + `libjl_ota_auth.so`) stripped | [`6817568`](https://github.com/The412Banner/bannerhub-revanced/commit/6817568) |
| **Heartbeat / playtime tracker** | per-game playtime sessions → XiaoJi via `heartbeat/game/{start,update,end}` | Suspend lambda bodies replaced with `return Unit.INSTANCE`; `getUserPlayTimeList` returns an empty wrapped list | [`519ba65`](https://github.com/The412Banner/bannerhub-revanced/commit/519ba65) |
| **Google Play Services Measurement** | persistent `app_instance_id`, active `session_id`, lifecycle pauses → Google | Three GMS manifest components (`AppMeasurementReceiver` / `AppMeasurementService` / `AppMeasurementJobService`) flipped to `android:enabled="false"` — PackageManager treats them as not-present | [`d4675ec`](https://github.com/The412Banner/bannerhub-revanced/commit/d4675ec) |
| **`statistic-gamehub-api.vgabc.com/events`** | general in-app analytics events to XiaoJi | `Lcx5;->a` (the public send-batch entry point) early-returns a fake success result before any URL string is allocated. Coroutine state machine, HTTP client, and DNS resolver never run | [`b043f8c`](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c) |
| **`statistic-gamehub-api.vgabc.com/events/device-performance-config`** | device specs + perf telemetry to XiaoJi | `Loh4;->b` (the public report entry point) early-returns a fake success result with the same shape callers expect. Lambda body holding the URL is unreachable dead code | [`b043f8c`](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c) |

Empirical confirmation on a real device (Pixel-class hardware, BannerHub v6 on `gamehub-604-build@53d9ec1`, full 6.5-minute session = install → open → game launch → in-game session → quit): **zero DNS queries** for `statistic-gamehub-api.vgabc.com`, `dev2-gamehub-api.vgabc.com`, or `landscape-api-beta.vgabc.com` recorded across the entire trace. Same trace, zero hits in logcat for any of those hosts.

---

## What we deliberately did NOT touch — and why you'll still see these in a DNS recorder

If you point a DNS recorder (e.g. PCAPdroid, AdGuard, NextDNS) at this APK, you **will** see traffic to the hosts below. None of them carry per-user telemetry, but the connections themselves are real, and we'd rather explain them than have you assume we missed them.

### `bigeyes.com` and adjacent image CDN paths

**What it is**: game cover art, hero capsules, square images for the catalog UI — i.e. the pictures of every game shown in the launcher. Same vendor infrastructure as XiaoJi but **image-only**: a plain HTTPS `GET /path/to/image.jpg`, no analytics payload in the request, no identifiers, no events.

**Why we didn't proxy it**: this was scoped (originally as "Plan 3" in the internal workstream) and **deliberately skipped**. The privacy gain is marginal — your IP would still reach a CDN, just a different one — and proxying every image fetch through the BannerHub Cloudflare Worker would burn real money in invocations and bandwidth on every cover-art render in your library.

**What it leaks**: your IP to the CDN. Same trust shape as loading an image from any other website. Nothing identifying you as a *BannerHub user* specifically.

### `firebase-settings.crashlytics.com`

**What it is**: Firebase Crashlytics's settings-config fetch endpoint. The SDK reaches out on startup to check whether crash reporting should be enabled and at what sampling rate.

**Why it's still visible**: BannerHub ships the `Disable Firebase Crashlytics` patch that removes the crash-reporting code path — so even though the Settings GET happens, **nothing acts on the response**, and no crash bodies are ever uploaded. The DNS query is a vestigial part of SDK initialization that runs before the patched call sites would have fired.

**What it leaks**: the fact that an Android app with the Crashlytics SDK linked is running. No crashes, no identifiers in the request beyond what every Firebase-linked app sends.

### `firebaselogging-pa.googleapis.com`

This hits when Firebase's logging-API client probes. Several DNS recorders (and the one used to verify this build during testing) already block it by default; if yours doesn't, it's worth adding to your local blocklist. Out of scope for these patches — Plan 10 disabled the GMS Measurement service path, not this separate logging-API path. Worth a follow-up "Plan 11" if there's appetite.

### `galaxy-log.gog.com` and other GOG endpoints

If you've configured BannerHub to use GOG as a game source, GOG Galaxy's own telemetry will fire during game detection / launch. That's **GOG-side**, completely outside the BannerHub patch surface. Block it at the network level if you want it gone; we won't patch it client-side because it's not our code path and changes to it would silently break GOG functionality.

### `shared.akamai.steamstatic.com` and other Steam CDN hosts

Steam cover art for the Steam game integration. Same shape as `bigeyes.com`: image-only, no PII in the request. Connecting to Steam's CDN leaks your IP to Akamai/Steam — the exact same leak you'd get visiting the Steam Store in a browser. Out of scope for BannerHub's privacy patches.

### The BannerHub Cloudflare Worker (`bannerhub-api.the412banner.workers.dev`)

**What it does**: serves the `/v6/` catalog API — the list of games, their cover art URLs, Wine/DXVK/Box64/Steam-client component metadata, and per-game install scripts. This is the layer that replaces XiaoJi's own catalog endpoint and is what lets BannerHub function at all.

**The honest trust-shift acknowledgement**: every catalog browse / game-list refresh / component-install request goes through this Worker. We didn't move XiaoJi-side trust to *nothing* — we moved part of it to **The412Banner + Cloudflare**. Cloudflare sees the traffic by virtue of operating the edge, regardless of what the Worker code itself does with it.

**What the Worker doesn't do**: it does not run analytics, it does not log per-user request bodies, it does not have any identifier of "who" you are beyond the source IP that Cloudflare sees on every HTTPS request. The Worker source is in [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api) — read it.

**Why this matters specifically for Plan 1**: the original design for the analytics-events kill (internally tracked as "Plans 1+7") was going to route analytics traffic through the Worker and 204-discard it server-side. That would have shifted *more* trust to Cloudflare. The redesigned Plan 1 stubs the methods client-side instead — analytics traffic doesn't reach the Worker at all. The Worker's trust footprint is now scoped to *catalog data*, not telemetry.

---

## What is intentionally out of scope

These exist, and we did not touch them, because they're not part of the XiaoJi / Firebase / Mob / Google telemetry surface that this series targeted:

- **Steam Cloud, Steam Web API, Steam friends/community** — Valve-side, runs when you launch a Steam game through BannerHub.
- **GOG online services, Galaxy social, achievement sync** — GOG-side, runs when you launch a GOG title.
- **Epic Online Services** — Epic-side; some Epic games will phone home regardless of BannerHub. BannerHub does ship a separate `EpicPortal` injection patch but that's a *compatibility* feature, not a privacy one.
- **Anti-cheat phone-homes** (BattlEye, Easy Anti-Cheat, etc.) — embedded in the games themselves.
- **The user's own Wine / Box64 prefix data, save files, screenshots, captures** — all local to your device and managed by you.
- **Anything inside the Windows games you run** — those are sovereign processes inside the Wine container; their telemetry is entirely their own concern.

---

## How to verify any of this yourself

Don't trust the table — verify it. The patches are open source; the artifacts are reproducible.

1. **DNS recorder** — install [PCAPdroid](https://emanuele-f.github.io/PCAPdroid/) or any equivalent on the same device. Start recording, install + open a BannerHub v6 APK, do whatever you'd normally do (browse the library, launch a game, quit), stop recording. The hostnames in the recorder should match the "still visible" list above, with **none** of the killed channels appearing.
2. **Logcat** — `adb logcat | grep -E "vgabc.com|statistic-gamehub|/events"` during the same session should return zero hits.
3. **Decoded manifest** — `apktool d` the APK and grep `AndroidManifest.xml`:
   - `firebase_analytics_collection_deactivated="true"` should be present
   - All `com.mob.*` and `cn.fly.*` components should have `android:enabled="false"`
   - `com.google.android.gms.measurement.AppMeasurement{Receiver,Service,JobService}` should be `android:enabled="false"`
   - `AD_ID`, `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION` should be absent from `<uses-permission>` declarations
4. **Smali check** — `Lcx5;->a` (general events) and `Loh4;->b` (perf-config) should begin with a `new-instance` → const loads → `invoke-direct[/range] … <init>` → `return-object v0` sequence before any URL strings or HTTP calls.

---

## Questions / corrections

If you find a leak this document doesn't mention, please open an issue at [github.com/The412Banner/bannerhub-revanced/issues](https://github.com/The412Banner/bannerhub-revanced/issues). Disclosure gaps in this file are bugs.

*Last updated: 2026-05-14. Covers `gamehub-604-build` at HEAD `53d9ec1` and later.*
