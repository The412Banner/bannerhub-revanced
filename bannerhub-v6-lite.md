# BannerHub V6 Lite — What It Is & What Changed

**Build:** `BannerHub-V6-1.2.1-604-Lite-Beta1.apk`
**Package name:** `banner.hub.lite`
**Size:** ~78 MB (the regular BannerHub V6 is ~114 MB — Lite is about **36 MB / ~31% smaller**)

---

## In one sentence

BannerHub V6 Lite is the **same app as the regular BannerHub V6**, just with three chunks of unused or rarely-used "dead weight" cut out to make the download and install much smaller — **without losing Steam, Epic, AI Frame Generation, or any of the gaming features you actually use.**

## You can install it side-by-side

Because Lite uses its own package name (`banner.hub.lite`), it installs **next to** any other BannerHub variant you already have. Nothing gets overwritten — you can keep your normal build and test Lite at the same time on the same device.

---

## What was removed (and why it's safe)

We trimmed the app in three rounds. Here's each one in plain terms.

### 1. Duplicate font + leftover phone-login code (~14 MB saved)

- The app shipped the **exact same 20 MB font file twice** by mistake. We deleted the unused copy. The app looks **100% identical** — same fonts everywhere.
- We also removed an Aliyun "one-tap phone number login" library. BannerHub doesn't use carrier phone login (it uses login-bypass), so this code never ran anyway.
- **What you lose:** Nothing. This is pure dead weight.

### 2. Built-in cloud gaming streaming (~21 MB saved)

- The regular app bundles a **cloud-gaming streaming engine** (Haima) plus its background graphics. This lets you stream certain games from a remote server instead of running them on your device.
- In BannerHub this feature talks to servers that aren't part of how most people use the app, so it's effectively unused for normal local game playing.
- **What you lose:** The in-app "cloud gaming" streaming option. **Running games locally — Steam, Epic, your own games — is completely unaffected.** If you never used the cloud-streaming tiles, you won't notice this is gone.

### 3. Specialized image decoders (AVIF / HEIC / HEIF) (~5 MB saved)

- The app bundled extra libraries just to decode three modern image formats (AVIF, HEIC, HEIF) — mostly for some cover art and avatars.
- We removed them. When the app hits one of those images, **Android's own built-in image decoder handles it instead.**
- **What you lose:** In theory, a small number of images in those specific formats might not render. **In testing, cover art, avatars, and banners all displayed correctly** — regular JPEG, PNG, WebP, and GIF images are 100% unaffected.

---

## What is **fully kept** in Lite

Lite is **not** a stripped-down "local only" build. Everything you actually game with stays:

- ✅ **Steam** — full Steam client support, game launching
- ✅ **Epic** — full Epic / EOS support
- ✅ **AI Frame Generation** — the in-game frame-gen menu and FPS boost
- ✅ **Controller vibration / rumble**
- ✅ All container/Wine/DXVK/driver tooling
- ✅ Every game-launching and library feature of the regular build

The only deliberate decision we made: **Steam and Epic are never removed from Lite**, even though doing so would shrink it further. Keeping them is the whole point — Lite is meant to be a smaller version of the *full* experience, not a cut-down one.

---

## Front-end launcher support (Beacon / ES-DE / Daijishou)

Lite ships the same external-front-end intent contract as the full builds — pick a game in Beacon (or ES-DE / Daijishou), it hands off into Lite, and the game starts playing. The Lite package (`banner.hub.lite`) has its own action prefix; the per-variant `am` commands, the `localGameId` / `steamAppId` / `autoStartGame` extras, and how to find a game's ID are all documented in:

📖 **[`beacon-setup.md`](beacon-setup.md)** — full setup guide covering all 9 variants (full + Lite).

---

## Testing status (Beta 1)

| Area | Status |
|---|---|
| Install & launch | ✅ Confirmed working |
| Login bypass | ✅ Confirmed working |
| Fonts / app appearance | ✅ Confirmed identical |
| Cover art / avatars / banners (image test) | ✅ Confirmed rendering correctly |
| Cloud-gaming tiles don't crash the app | 🔄 Please test |
| Steam & Epic login + game launch | 🔄 Please test and report back |

This is a **Beta** — if you hit any issue (especially with Steam/Epic game launches or any missing images), please report it so we can confirm before promoting Lite to a regular release.

---

## How to install

1. Download `BannerHub-V6-1.2.1-604-Lite-Beta1.apk` from the release assets.
2. Install it — it will appear as a **separate app** alongside your existing BannerHub.
3. Set it up and test your games as you normally would.

Enjoy the smaller footprint. 🎮
