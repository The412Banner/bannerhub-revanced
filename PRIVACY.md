# BannerHub v6 for ReVanced — Privacy

This document tells you what BannerHub v6 actually does to your data flow — both what it **kills** and what it **leaves in place**. The honest list of both matters: anyone running a DNS recorder against this APK can verify both halves, and we'd rather disclose the leftovers up-front than have you discover them yourself.

It only covers the **BannerHub-side patches**. It does not cover GameHub's upstream behavior (refer to [XiaoJi's GameHub site](https://gamehub.xiaoji.com/)), the Wine / Box64 stack, or the individual Windows games you run inside it.

---

## What we kill

Every row below is a real telemetry channel that was active on vanilla GameHub 6.0.8 and is **no longer reachable** in the BannerHub v6 build that ships from `gamehub-608-build`. The "Mechanism" column says how, and "Merge commit" links to the actual code that did it. (These patches were carried forward from the 6.0.4 line and **re-fingerprinted against the 6.0.8 bytecode**. The mechanisms are unchanged **except the two `vgabc.com/events` kills**, which switched from the 6.0.4 fake-success-return to a **loopback URL redirect** — the 6.0.7+ result model made fabricating a fake success object crash-risky, so the senders now point at `http://127.0.0.1` instead. The commit links point at the original 6.0.4 implementation; the current anchors live in the [patch sources](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-608-build/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics).)

| Channel | What it leaked | Mechanism | Merge commit |
| --- | --- | --- | --- |
| **Firebase Analytics** | screen views, session starts, in-app purchases, app opens, custom events → `app-measurement.com` | Manifest `<meta-data>` kill switch (`firebase_analytics_collection_deactivated=true` + AD-ID/SSAID disables) — Analytics data collection never initializes — `app-measurement.com` never fires. (Firebase Crashlytics *was* separately re-enabled at runtime; that hole is now closed by Plan 11 — see below) | [`178c5ec`](https://github.com/The412Banner/bannerhub-revanced/commit/178c5ec) |
| **Mob Push SDK** | device identifiers, push tokens, lifecycle events → Mob (`mob.com`) | Bytecode removes 3 SDK init call sites in `BaseAndroidApp` + helper; manifest disables every `com.mob.*` / `cn.fly.*` provider / service / receiver / activity (Mob's ContentProvider auto-init can't fire either) | [`282c9ea`](https://github.com/The412Banner/bannerhub-revanced/commit/282c9ea) |
| **Google AD-ID / ADSERVICES permissions** | advertising-ID exposure to any caller that queries Play Services | Stripped from `<uses-permission>` declarations (3 perms removed) | [`6817568`](https://github.com/The412Banner/bannerhub-revanced/commit/6817568) |
| **XiaoJi OTA update URL** | firmware-update phone-home to `xiaoji.com/firmware/update/x1` | URL register rewritten to `http://127.0.0.1` immediately after the const-string load; per-arch OTA native libs (`libJieLiUsbOta.so` + `libjl_ota_auth.so`) stripped | [`6817568`](https://github.com/The412Banner/bannerhub-revanced/commit/6817568) |
| **Heartbeat / playtime tracker** | per-game playtime sessions → XiaoJi via `heartbeat/game/{start,update,end}` | Suspend lambda bodies replaced with `return Unit.INSTANCE`; `getUserPlayTimeList` returns an empty wrapped list | [`519ba65`](https://github.com/The412Banner/bannerhub-revanced/commit/519ba65) |
| **Google Play Services Measurement** | persistent `app_instance_id`, active `session_id`, lifecycle pauses → Google | Three GMS manifest components (`AppMeasurementReceiver` / `AppMeasurementService` / `AppMeasurementJobService`) flipped to `android:enabled="false"` — PackageManager treats them as not-present | [`d4675ec`](https://github.com/The412Banner/bannerhub-revanced/commit/d4675ec) |
| **`statistic-gamehub-api.vgabc.com/events`** | general in-app analytics events to XiaoJi | `Lzy5;->a` (the send-batch entry point): every analytics URL const-string is overwritten with `http://127.0.0.1` immediately after it is loaded, so the HTTP client targets a dead local address and the POST fails (connection-refused) — no data leaves the device. Anchored on the stable URL strings (all dev2/beta/prod variants redirected), so it survives R8 reshuffles | [`b043f8c`](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c) |
| **`statistic-gamehub-api.vgabc.com/events/device-performance-config`** | device specs + perf telemetry to XiaoJi | `Lb34;->invokeSuspend` (the device-perf reporter): same loopback redirect — the `…/device-performance-config` URL is rewritten to `http://127.0.0.1` after load; the reporter's own coroutine error path swallows the connection-refused, so nothing crashes | [`b043f8c`](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c) |
| **Firebase Crashlytics runtime re-enable** (Plan 11) | crash reports + the Firebase housekeeping connections `firebase-settings.crashlytics.com`, `firebaselogging-pa.googleapis.com` (datatransport/Firelog) and `firebaseinstallations.googleapis.com`. `AndroidApp` silently **re-enabled Crashlytics collection at runtime** (`firebase_crashlytics_collection_enabled=true`), overriding the manifest kill switch — so Crashlytics was actually live | **Disable Firebase auto-init**: bytecode `return-void` inserted into the app's Firebase-setup helper *after* `FirebaseApp.initializeApp` (kept — other code needs it) but *before* it rewrites the collection prefs, so the manifest `false` finally holds → Crashlytics stays off, killing all three hosts. Device-confirmed gone (6.0.8 pre3 trace). | [patch](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-608-build/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableFirebaseAutoInitPatch.kt) |

Empirical confirmation on a real device (Pixel-class hardware, BannerHub v6 on `gamehub-604-build@53d9ec1`, full 6.5-minute session = install → open → game launch → in-game session → quit): **zero DNS queries** for `statistic-gamehub-api.vgabc.com`, `dev2-gamehub-api.vgabc.com`, or `landscape-api-beta.vgabc.com` recorded across the entire trace. Same trace, zero hits in logcat for any of those hosts. This trace was captured on the 6.0.4 line; the kill patches on `gamehub-608-build` are the same mechanisms re-fingerprinted against the 6.0.8 bytecode.

**6.0.8 device A/B confirmation (2026-06-08):** a side-by-side TLS-SNI capture (root, `iptables` owner-match → NFLOG → `tcpdump`) of **stock GameHub** vs the **patched `banner.hub` 1.1.0-608** build, on the same device, same session shape. Stock reached `statistic-gamehub-api.vgabc.com` (telemetry), `sdk-push.dutils.com` (push), and `tgc/cfgc.zztfly.com` (config). The patched build reached **none** of them — the telemetry-host check (`vgabc | dutils | zztfly | statistic | app-measurement`) returned empty across the trace, and the catalog instead resolved to our own Worker. The only Google hosts the patched build still contacted are the SDK-housekeeping residuals listed below.

---

## Rolled into every build — the former Lite-only strips

On the 6.0.4 line, BannerHub v6 shipped a separate, smaller **Lite** build that stripped a few unused/heavy components. **On the 6.0.7 base there is no separate Lite** — XiaoJi's own −46 % size pass already makes the full build smaller than the old Lite ever was. Two of those strips are **privacy-relevant**, so rather than lose them along with the Lite build, they now apply to **every** variant by default ([`590584f`](https://github.com/The412Banner/bannerhub-revanced/commit/590584f)):

| Removed | What it was | Mechanism |
| --- | --- | --- |
| **Aliyun / Alibaba NumberAuth** — carrier one-tap phone-login SDK (`com.mobile.auth.gatewayauth.*`) | Carrier "one-tap" login that resolves your phone number through your mobile carrier — an identity surface — plus the SDK's bundled anti-tamper / root / emulator / proxy fingerprint checks. Under BannerHub's login bypass the real auth flow never completes, so it is both dead weight and a fingerprint surface. | **Disable Aliyun NumberAuth** patch: stubs the sole `System.loadLibrary("pns-…alijtca_plus")` site to a no-op — anchored on the unique method holding a `pns-` const-string **and** a `loadLibrary` call, so it survives R8 letter reshuffles — then deletes the `libpns-*-alijtca_plus.so` native lib. ~0.5 MB. |
| **Haima cloud-gaming stack** — HMCP / WebRTC | XiaoJi's cloud-gaming feature: streams games from **XiaoJi cloud servers**. Non-functional under BannerHub's catalog redirect anyway, and a live connection to XiaoJi cloud infrastructure. | **Strip cloud gaming** patch: neutralises the two SDK native load sites first (`IjkMediaPlayer.loadLibrariesOnce` → `return-void`; `org.hmwebrtc.NativeLibrary$DefaultLoader.load` → `return false`, the SDK's own designed missing-lib outcome), then strips the 4 Haima native libs + the entire `features.cloud` Compose asset tree. ~21.5 MB. |

The 6.0.4 Lite also stripped a duplicate MiSans font and the AVIF/HEIC image codecs; on 6.0.7 both are **moot** — XiaoJi already deduplicated the font and dropped those codecs in its own size pass, so there's nothing left for BannerHub to strip there.

---

## What we deliberately did NOT touch — and why you'll still see these in a DNS recorder

If you point a DNS recorder (e.g. PCAPdroid, AdGuard, NextDNS) at this APK, you **will** see traffic to the hosts below. None of them carry per-user telemetry, but the connections themselves are real, and we'd rather explain them than have you assume we missed them.

### `bigeyes.com` and adjacent image CDN paths

**What it is**: game cover art, hero capsules, square images for the catalog UI — i.e. the pictures of every game shown in the launcher. Same vendor infrastructure as XiaoJi but **image-only**: a plain HTTPS `GET /path/to/image.jpg`, no analytics payload in the request, no identifiers, no events.

**Why we didn't proxy it**: this was scoped (originally as "Plan 3" in the internal workstream) and **deliberately skipped**. The privacy gain is marginal — your IP would still reach a CDN, just a different one — and proxying every image fetch through the BannerHub Cloudflare Worker would burn real money in invocations and bandwidth on every cover-art render in your library.

**What it leaks**: your IP to the CDN. Same trust shape as loading an image from any other website. Nothing identifying you as a *BannerHub user* specifically.

### Firebase housekeeping hosts — **now killed (Plan 11, shipped)**

`firebase-settings.crashlytics.com`, `firebaselogging-pa.googleapis.com` (datatransport/Firelog), and `firebaseinstallations.googleapis.com` *used* to appear here. They are now killed by the **Disable Firebase auto-init** patch (see "What we kill" above) — **device-confirmed gone** from the app's traffic in the 6.0.8 pre3 trace. They were never analytics *payloads* (Analytics stayed dead), but they were real connections to Google; they're gone now, so you should **not** see them attributed to the app. (You may still see them in a device-wide recorder lane from *other* apps that bundle Firebase — that's not BannerHub.)

### `play.googleapis.com`

The generic **Google Play Services (GMS) API backend** — carries a grab-bag of GMS calls (Phenotype feature-config, FCM registration, Firebase-via-GMS, Play Integrity, etc.; the specific API is in the encrypted path). It was attributed to the app in the pre-Plan-11 trace; **after Plan 11 its Firebase-driven share is gone** (not attributed to the app in the pre3 trace). Any residual is the **GMS client library + system Play Services**, **a separate system app we cannot patch** — app-side patches can't guarantee zero here. It carries no per-user analytics payload of ours. Block it at the network level only with care — it can break push (FCM) and Play Integrity.

### `userlocation.googleapis.com`

A GMS coarse-location endpoint. Seen **resolved** in the 6.0.8 trace but **not** as a confirmed app connection (DNS lookup only, no attributed socket) — likely GMS background or resolved-not-used. Listed here for completeness; same GMS-side caveat as `play.googleapis.com`.

### `api-international-gamehub.xiaoji.com`

XiaoJi's **functional app backend** (account, game library, social) — **not** an analytics host. **Device-confirmed present** in the 6.0.8 trace. The catalog redirect ([Plan 1/component patches](https://github.com/The412Banner/bannerhub-revanced)) re-points the *component/container* calls (`landscape-api-*.vgabc.com`) to our Worker, but the app still talks to this XiaoJi host for account/game operations the Worker doesn't serve. The dedicated analytics endpoint (`statistic-gamehub-api.vgabc.com`) is separately killed, so events don't flow here — but it **is** a live channel to XiaoJi's servers, disclosed for honesty. Blocking it breaks login / library, so we leave it.



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

## Your store logins (Steam / GOG / Epic) stay between you and the store

BannerHub is a launcher and catalog layer — **not** an auth broker. It never asks for, sees, stores, or relays your Steam, GOG, or Epic **password or session token**. Each store login goes straight to that store, exactly as it would in the store's own client:

- **GOG** — "Sign in to GOG" opens GOG's **own** OAuth page (`https://auth.gog.com/auth`) inside a WebView. You type your username and password into **GOG's** form, so they go directly to GOG — BannerHub never sees them. GOG returns an access/refresh token in the redirect, which is stored **only in on-device storage** (`bh_gog_prefs` SharedPreferences) and used **only** against GOG's own endpoints (`embed.gog.com`, `api.gog.com`, `content-system.gog.com`, the GOG CDN). The token is never sent to the BannerHub Worker or to GameHub. *(Verify in [`GogLoginActivity.java`](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-608-build/extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/GogLoginActivity.java) — and grep the `gog/` extension: no GOG token ever leaves for `workers.dev` or `vgabc.com`.)*
- **Steam** — Steam login happens inside the **real Steam client** running under Wine (the genuine Valve binary). Your Steam credentials and Steam Guard code go directly to Valve; BannerHub has no code in that path and never sees them. The only Steam value BannerHub's catalog can ever read is your **public** SteamID64 / public owned-games list — never a password or session token.
- **Epic** — handled entirely by **Epic Online Services**. BannerHub ships **no Epic login or networking code at all**, so your Epic credentials go directly to Epic.

None of BannerHub's patches rewrite a Steam, GOG, or Epic host, and the catalog redirect only ever touches XiaoJi's two `landscape-api-*.vgabc.com` catalog hosts — so your store sign-ins never transit the BannerHub Worker, Cloudflare, or GameHub's servers.

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
4. **Smali check** — in the analytics send-batch and device-performance reporter methods, every `vgabc.com/events` URL `const-string` is immediately followed by a `const-string … "http://127.0.0.1"` into the same register, so the live host is overwritten before the HTTP client runs. (The exact obfuscated class letters differ per base version — R8 reshuffles them on every minor bump — so check the [patch sources](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-608-build/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics) for the current 6.0.8 anchors rather than a hard-coded letter.)
5. **SNI / connection check** — a DNS recorder only shows plaintext `:53` lookups; if your device uses Private DNS (DoT/DoH) the query names are encrypted, so also inspect **TLS SNI** (PCAPdroid decodes it). The patched build's SNI set should contain *none* of `vgabc`, `dutils`, `zztfly`, or `app-measurement`, and the catalog should resolve to `bannerhub-api.the412banner.workers.dev`.

---

## Hardening — Plan 11 (shipped)

The Firebase residuals turned out **not** to be passive SDK auto-init — they were active: `AndroidApp`'s Firebase-setup helper **re-enabled Crashlytics collection at runtime** (writing `firebase_crashlytics_collection_enabled=true` / `firebase_data_collection_default_enabled=true` straight into the SDK prefs), which **silently overrode** our manifest `…collection_enabled=false` kill switch. That runtime override — not a ContentProvider — is why `firebase-settings.crashlytics.com`, `firebaselogging-pa.googleapis.com` and `firebaseinstallations.googleapis.com` were live despite the manifest flags. (6.0.8 ships **no** `FirebaseInitProvider`; Firebase is discovered via `ComponentDiscoveryService` and initialized by the app's own helper.)

- **Plan 11 — Disable Firebase auto-init (shipped).** Bytecode `return-void` inserted into that helper *after* `FirebaseApp.initializeApp` (kept — a later coroutine calls `FirebaseApp.getInstance()`) but *before* the collection re-enable. The manifest `false` defaults finally hold → Crashlytics stays off → no settings fetch, no Firelog, no FID. **Device-confirmed gone** on the 6.0.8 pre3 trace; the app launches and runs normally. Anchored on the app class + the stable string `"FirebaseCrashlytics component is not present."`, cut at the first `monitor-enter`.
- **GMS-side limits.** Any residual `play.googleapis.com` / `userlocation.googleapis.com` originates from Google Play Services — a **separate system app we cannot patch**. App-side patches reduce but cannot guarantee zero. For a hard guarantee, block those two hosts at the network layer (per-app firewall / DNS sink); doing so can disable FCM push and Play Integrity, so weigh it against features you use.
- **`api-international-gamehub.xiaoji.com`** is functional, not telemetry — intentionally left intact.

## Questions / corrections

If you find a leak this document doesn't mention, please open an issue at [github.com/The412Banner/bannerhub-revanced/issues](https://github.com/The412Banner/bannerhub-revanced/issues). Disclosure gaps in this file are bugs.

*Last updated: 2026-06-08. Covers `gamehub-608-build` (GameHub 6.0.8, versionCode 119). The telemetry-kill patches are carried forward from the 6.0.4 line, re-fingerprinted against the 6.0.8 bytecode; the two `vgabc.com/events` kills now use a loopback URL redirect (not the 6.0.4 fake-success return). **Plan 11 (Disable Firebase auto-init) shipped** — it closes a runtime Crashlytics-collection re-enable that was overriding the manifest flag, removing the firebase-settings.crashlytics.com / firebaselogging-pa.googleapis.com / firebaseinstallations.googleapis.com residuals (device-confirmed, 6.0.8 pre3 SNI trace). The former Lite-only NumberAuth + cloud-gaming strips ship in every variant.*
