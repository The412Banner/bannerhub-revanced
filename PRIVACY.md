# BannerHub v6 — Privacy, in plain English

This page explains, without the jargon, **what BannerHub v6 stops your device from sending out — and what it honestly leaves alone.** Both halves matter: anyone can point a network monitor at the app and check us, so we'd rather tell you the leftovers up front than have you find them yourself.

This only covers **what BannerHub changes**. It doesn't cover GameHub itself (that's [XiaoJi](https://gamehub.xiaoji.com/)), the Windows/Wine engine that runs your games, or the games you install.

---

## The short version

- ✅ **We turn off the tracking.** Every analytics, telemetry, push-tracking, ad-ID, and "phone home" channel that the stock app uses is blocked. We checked this on a real device — during a full session (open the app → browse → launch a game → quit) **none of those tracking servers were contacted.**
- 🔁 **A few connections still happen — and they're normal.** Things like game cover-art images, Steam/GOG store images, Google Play Services (a system component we can't change), and our own catalog server. None of them carry tracking about *you*. They're all listed below with a plain explanation.
- 🔐 **Your store logins stay private.** Steam, GOG, and Epic passwords go straight to those stores — BannerHub never sees, stores, or relays them.
- 🔎 **You don't have to trust us.** Everything is open source and you can verify it yourself with a free network-monitor app (steps at the bottom).

> **Want the deep technical version?** Every change below is real, open code. The exact patches live in the [patch sources](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-609-build/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics); the table's "code" links point to the original commits.

---

## What we block

Each of these was a live tracking channel in the stock GameHub app. In BannerHub v6, **none of them can reach the internet anymore.**

| Tracker | What it was sending | Status |
| --- | --- | --- |
| **Firebase Analytics** | which screens you opened, when you started a session, in-app purchases, app opens | 🚫 Blocked — never starts up ([code](https://github.com/The412Banner/bannerhub-revanced/commit/178c5ec)) |
| **Firebase Crashlytics** | crash reports + Firebase background check-ins (the app was secretly switching this back on at startup) | 🚫 Blocked — the secret re-enable is shut off ([code](https://github.com/The412Banner/bannerhub-revanced/blob/gamehub-609-build/patches/src/main/kotlin/app/revanced/patches/gamehub/misc/analytics/DisableFirebaseAutoInitPatch.kt)) |
| **Google Play "Measurement"** | a persistent ID for you, session IDs, lifecycle events → Google | 🚫 Blocked — its components are switched off ([code](https://github.com/The412Banner/bannerhub-revanced/commit/d4675ec)) |
| **Mob Push SDK** | device identifiers, push tokens, app lifecycle events | 🚫 Blocked — removed and disabled ([code](https://github.com/The412Banner/bannerhub-revanced/commit/282c9ea)) |
| **Advertising ID permissions** | exposed your ad-ID to anything that asked | 🚫 Removed from the app's permissions ([code](https://github.com/The412Banner/bannerhub-revanced/commit/6817568)) |
| **XiaoJi analytics** (`statistic-gamehub-api.vgabc.com`) | general in-app usage events to XiaoJi | 🚫 Blocked — the send address is redirected to a dead end on your own device, so the data never leaves ([code](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c)) |
| **XiaoJi device/performance report** | your device specs + performance stats to XiaoJi | 🚫 Blocked — same dead-end redirect ([code](https://github.com/The412Banner/bannerhub-revanced/commit/b043f8c)) |
| **XiaoJi "heartbeat" playtime tracker** | how long you played each game, reported back to XiaoJi | 🚫 Blocked — the reporting is switched off ([code](https://github.com/The412Banner/bannerhub-revanced/commit/519ba65)) |
| **XiaoJi update phone-home** | a firmware-update check-in to XiaoJi | 🚫 Blocked — redirected to a dead end ([code](https://github.com/The412Banner/bannerhub-revanced/commit/6817568)) |

We also **remove two leftover components** from every build (they used to only be removed in the old "Lite" version): a **carrier phone-number login SDK** (Aliyun NumberAuth — an identity surface that BannerHub doesn't even use) and **XiaoJi's cloud-gaming streaming stack** (a live link to XiaoJi's cloud servers that doesn't work under BannerHub anyway). ([code](https://github.com/The412Banner/bannerhub-revanced/commit/590584f))

### We actually checked — on a real device

- **June 18, 2026 (stable `v1.0.0-609`, on GameHub 6.0.9):** using our own [DNSWatch](https://github.com/The412Banner/DNSWatch) app to watch every connection BannerHub made during a real session, **not a single connection to XiaoJi's tracking server happened.** Instead, the tracking data visibly went to a dead end on the phone itself (`127.0.0.1`) — exactly what the block does. The only servers the app talked to were the normal, non-tracking ones listed in the next section.
- **Earlier confirmations** on 6.0.8 (a side-by-side stock-vs-patched capture) and 6.0.4 (a full-session capture) showed the same thing: the stock app reached XiaoJi's tracking/push/config servers; the patched app reached **none** of them.

---

## What still connects — and why that's okay

If you run a network monitor against BannerHub, you **will** see the connections below. None of them send tracking about *you* — but they're real connections, so here's the honest explanation of each.

- **Game cover art** (`bigeyes.com` and similar) — the pictures of games shown in your library. It's just downloading images, no different from any website loading a picture. Routing these through our own server would cost real money on every image and wouldn't actually hide you (your IP still reaches *a* server either way), so we leave them.
- **Steam / GOG store images** (`*.steamstatic.com`, GOG CDNs) — cover art for your Steam/GOG games. Same as above: images only, no personal info.
- **Google Play Services** (`play.googleapis.com`, occasionally `userlocation.googleapis.com`) — this is **Android's own system component**, not BannerHub. We can't patch it. It carries none of *our* tracking. You can block it with a firewall if you want, but doing so can break push notifications and some app-integrity checks — so weigh that.
- **XiaoJi's account/library server** (`api-international-gamehub.xiaoji.com`) — this is the **functional** backend for accounts, your game library, and social features — **not** a tracking server (the tracking one is separately blocked, above). The app needs it to work, so blocking it would break login and your library. We disclose it for honesty and leave it intact.
- **GOG's own services** (e.g. `galaxy-log.gog.com`) — *only* if you use GOG as a game source. That's GOG's own telemetry, outside anything BannerHub controls. Block it at the network level if you like.
- **Our catalog server** (`bannerhub-api.the412banner.workers.dev`) — this is what makes BannerHub work: it serves the game list, cover-art links, and the Wine/DXVK/Box64/Steam component downloads (replacing XiaoJi's catalog). **The honest trade-off:** every catalog browse goes through this server (run by The412Banner on Cloudflare), so we've shifted *some* trust from XiaoJi to us + Cloudflare. What it **doesn't** do: it runs no analytics, keeps no per-user logs, and knows nothing about "who" you are beyond the IP address Cloudflare sees on any HTTPS request. The code is public: [`The412Banner/bannerhub-api`](https://github.com/The412Banner/bannerhub-api).

---

## Your store logins stay between you and the store

BannerHub is a launcher — **not** a login middleman. It never asks for, sees, stores, or forwards your Steam, GOG, or Epic **password or login token.**

- **GOG** — "Sign in to GOG" opens **GOG's own** login page; you type your password into GOG's form, so it goes straight to GOG. The token GOG hands back is stored **only on your device** and used **only** with GOG's own servers — never sent to us.
- **Steam** — login happens inside the **real Steam client** (the genuine Valve program) running under Wine. Your password and Steam Guard code go directly to Valve. The only thing BannerHub can read is your **public** SteamID / public games list — never a password.
- **Epic** — handled entirely by Epic's own software. BannerHub ships no Epic login code at all, so your Epic credentials go straight to Epic.

---

## Not our department (out of scope)

These exist and we don't touch them, because they aren't part of the tracking BannerHub targets:

- **Steam / GOG / Epic online services** — run by Valve / GOG / Epic when you launch one of their games.
- **Anti-cheat** (BattlEye, Easy Anti-Cheat, etc.) — built into the games themselves.
- **Anything inside the Windows games you run** — those are their own programs; their data is their own concern.
- **Your own save files, screenshots, and Wine data** — all local to your device.

---

## Check it yourself

Don't take our word for it — you can verify all of this for free:

1. **Watch the network.** Install a network monitor like [PCAPdroid](https://emanuele-f.github.io/PCAPdroid/) (no root needed) or our own [DNSWatch](https://github.com/The412Banner/DNSWatch) (root). Start it, then open BannerHub, browse, launch a game, and quit. You should see the "still connects" servers above — and **none** of the blocked tracking servers (anything with `vgabc.com`, `statistic-gamehub`, `dutils`, `zztfly`, or `app-measurement`).
2. **Read the code.** Every block above links to the actual open-source change. The whole patch set is in the [repository](https://github.com/The412Banner/bannerhub-revanced).
3. **Inspect the app.** If you're technical, you can unpack the APK and confirm the tracking SDKs are disabled and the analytics addresses are redirected — details and exact locations are in the linked patch sources.

---

## Found something we missed?

If you spot a leak this page doesn't mention, please open an issue at [github.com/The412Banner/bannerhub-revanced/issues](https://github.com/The412Banner/bannerhub-revanced/issues). A gap in this disclosure is a bug, and we'll fix it.

---

*Last updated: 2026-06-18, for stable `v1.0.0-609` (built on GameHub 6.0.9). All tracking blocks were re-verified on this version with a live on-device network capture: zero tracking-server connections, with the analytics data redirected to a dead end on the phone. The detailed technical write-up of each patch lives in the [patch sources](https://github.com/The412Banner/bannerhub-revanced/tree/gamehub-609-build/patches/src/main/kotlin/app/revanced/patches/gamehub).*
