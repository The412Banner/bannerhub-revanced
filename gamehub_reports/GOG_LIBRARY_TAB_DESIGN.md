# GOG Library Tab — Patch Design Doc

**Status:** PHASE 0 COMPLETE (no code). Verdict: **Option A viable** (GOG data identity exists), refined scope. See §12 — it supersedes the speculative §4/§6. Branch `feature/gog-explore-tab` off `gamehub-604-build` @ `e39ce21`.
**Base:** GameHub 6.0.4, R8 map id `6a5cde6143fc8cf76f6f3a447d0fececd4794d83066e6ead7a9537e6527b057b`.
**Author:** The412Banner. **Date:** 2026-05-19.

Confidence tags used below: **[CONFIRMED]** = read directly in 6.0.4 smali this session; **[INFERRED]** = strong convergent evidence, not byte-proven; **[UNVERIFIED]** = needs a trace pass during implementation.

---

## 1. Goal

Surface a **GOG** tab in the Library platform bar (currently `PC Games | Steam Games | Epic Games | Retro Games`), backed by GOG titles, reusing the GOG infrastructure already compiled into the base APK.

## 2. Decision summary

- A GOG **tab** is *not* an API-config job. Unlike Steam/Epic/Retro (toggled by `base_info_*_games_hidden` flags in the BannerHub-served `base/getBaseInfo` DTO), **there is no `gog_games_hidden` flag and no GOG field in the tab model.** This is a **client bytecode/Compose patch** feature. **[CONFIRMED]**
- It is *materially cheaper than a net-new source family*: the GOG **launch engine, account-bind UI, icons, and a tab string resource already exist** in 6.0.4. The work is presentation wiring + data binding, not building a subsystem. **[CONFIRMED for engine/assets; INFERRED for "cheaper"]**
- Difficulty class ≈ the **menu-injection playbook** (R8-obfuscated Compose injection with per-base letter maintenance), plus one new concern: the GOG **data source** (what populates the grid).

## 3. Confirmed evidence map

| Layer | Finding | Anchor | Conf |
|---|---|---|---|
| Launch type | `LaunchType.GogGameByPcEmulator` constructed alongside Steam/Epic | `smali_classes5/com/xiaoji/egggame/launcher/model/LaunchType.smali` (`<clinit>`, `:463` `"GogGameByPcEmulator"`) | CONFIRMED |
| Launch set | `he7.a : Set = {Steam, Epic, Gog}GameByPcEmulator` — GOG is an equal member of the PC-emulator store launch-type set | `smali_classes4/he7.smali` `<clinit>` | CONFIRMED |
| Launch dispatch | GOG handled in parallel switches with Steam/Epic (launch / detail / library-tile) | `rr4.smali:1307`, `v2c.smali:100`, `pzc.smali:21900` | CONFIRMED |
| Assets | GOG icons `GogLogo1`, `GogIconSelected`, drawable `common_game_ic_gog_start_type.png` | const-string sweep | CONFIRMED |
| Strings | `features_home_profile_platform_tab_gog` + `features_home_profile_gog_{bind,title,desc}` authored, translatable | `ujl.smali:1167` (`tdi(key, localeSet)`) | CONFIRMED |
| Tab string is orphaned | `platform_tab_gog` referenced **only** by the generated resource accessor `ujl`; no composable/tab-builder consumes it | repo-wide grep | CONFIRMED |
| Tab-visibility model | `m21(boolean a=retroHidden, b=steamHidden, c=epicHidden, List d)`. Synthetic `<init>(IZZZ)`; `d` defaults to `Lw85;` (Kotlin `EmptyList`) | `smali_classes4/m21.smali:17-94` | CONFIRMED |
| Model populate | `q21` reads MMKV `base_info_{retro,steam,epic}_games_hidden` → `new m21(0x8, retro, steam, epic)`. No GOG read. | `smali_classes4/q21.smali:201-225` | CONFIRMED |
| Tab list consumed | `y22` reads `m21.d` (`Lm21;->d:Ljava/util/List;`) as the tab list | `smali_classes4/y22.smali:187` | CONFIRMED |
| API lever | `/base/getBaseInfo` is BannerHub-served (worker `GITHUB_ROUTES`, static `bannerhub-api/base/getBaseInfo`); carries the 3 `*_games_hidden` flags, **no gog flag** | `bannerhub-api/bannerhub-worker.js:444,1044` | CONFIRMED |

**Net:** GOG is scaffolded upstream at the engine + asset + string layer; the **Library tab is unimplemented** in 6.0.4 and is **not** on the API-flag path.

## 4. The real patch surface (injection points)

Pipeline (confirmed direction): `q21` (API flags → `m21` booleans, `m21.d=EmptyList`) → **[transform that turns flags into the actual tab list `m21.d`]** → `y22` (reads `m21.d`) → tab-strip composable.

| # | Injection point | What | Conf |
|---|---|---|---|
| P1 | **Tab-list transform** (between `q21` populate and `y22` read) — the code that materialises `m21.d` from the hide-booleans | The decisive site. Add a GOG entry to the produced list when a GOG-enabled predicate holds. **Exact class/method UNVERIFIED** — `q21` builds `m21` with `d=EmptyList`; something downstream copies/maps into `d`. Must trace `m21` `copy`/builder + who writes a non-empty `d` before `y22:187` reads it. | UNVERIFIED |
| P2 | **Tab item type** | `y22` constructs `Lx22;`/`Lz22;` near the `m21` read — likely the per-tab descriptor (title res, icon, source key). A GOG entry must be the same type, pointed at `platform_tab_gog` + GOG icon. | INFERRED |
| P3 | **Per-tab data query** | Whatever filters the game grid by source for Steam/Epic/Retro. GOG entries must resolve via `GameInfo` source / `LaunchType.GogGameByPcEmulator`. Source field exists (`GameInfo` referenced w/ `getEpicAppId` etc. in `pzc`); GOG equivalent **UNVERIFIED**. | UNVERIFIED |
| P4 | **Enable predicate** | Decide gating: (a) always-on, (b) gated on GOG account bound (the `gog_bind` profile UI implies an account state flag), or (c) a new BannerHub-controlled flag we add to `base/getBaseInfo` and read in `q21` mirroring the 3 existing ones. **(c) is the cleanest** — keeps parity with Steam/Epic/Retro and gives us an API kill-switch. | DESIGN CHOICE |
| P5 | **Resource reuse** | No new strings/icons needed — `platform_tab_gog`, `GogLogo1`/`GogIconSelected`, `common_game_ic_gog_start_type` all present. ReVanced extension can reference by resource name. | CONFIRMED |

## 5. Approach options

**Option A — Full client patch, GOG as a real 5th tab (recommended).**
Inject a GOG descriptor into the P1 transform, typed per P2, data-bound per P3, gated per P4(c). Reuses all GOG engine + assets.
*Pros:* true feature; API kill-switch via P4(c); no new resources.
*Cons:* P1/P3 require a deeper trace; R8-fragile (per-base letter map); Compose-injection class of risk.

**Option B — Minimal: API flag only, assuming a latent renderer.**
Add `base_info_gog_games_hidden`-style handling and hope a GOG tab renders when un-hidden.
*Verdict: NOT VIABLE.* **[CONFIRMED]** — `m21` has no GOG field and no consumer reads GOG; there is nothing for a flag to un-hide. Rejected.

**Option C — Defer / out of scope.**
Document and stop. Valid if the P3 data source (a working GOG library feed) turns out absent or depends on GOG account auth we can't satisfy offline.

## 6. Open questions — must resolve before coding

1. **P1:** Which class/method materialises `m21.d`? (Trace `m21` Kotlin `copy`/builder and all writers of a non-empty `d` reaching `y22:187`.) — *blocking.*
2. **P3:** Is there a source-filtered game query that already accepts a GOG/`GogGameByPcEmulator` key, or does the grid have no GOG feed at all? — *blocking; determines A vs C.*
3. **P4:** Does a GOG-account-bound state flag exist (from the `gog_bind` UI), and is GOG library data gated behind GOG auth? Affects whether the tab is useful without sign-in.
4. **Data origin:** Does the GOG list come from on-device imported titles, the existing BannerHub GOG download stack, or an upstream GOG API? Determines whether the BannerHub Worker/offline-synthesis layer is involved.

## 7. R8 fragility & maintenance

Every anchor here is R8-mangled (`q21`, `m21`, `y22`, `he7`, `Lx22`/`Lz22`) and **re-breaks on each base-APK bump**. This patch must ship with a per-version letter map (cf. `GH604_LETTER_MAP.md`) and is a prime candidate for the **fingerprint migration** track (`[[project_bannerhub_revanced_fingerprint_migration]]`) — anchor by structural fingerprint (the `base_info_*_games_hidden` string triple in `q21`; the `m21(ZZZList)` shape; the `he7` launch-type Set) rather than letters. Non-obf anchors that *are* stable and should be the structural roots: `com/xiaoji/egggame/launcher/model/LaunchType;->GogGameByPcEmulator`, the `base_info_*_games_hidden` literals, and the `features_home_profile_platform_tab_gog` resource key.

## 8. Risk & fail-safe

- **Fail-safe principle (per house style):** any GOG-injection failure must fall through to the stock 4-tab bar, never crash the Library. Mirror the offline-picker pattern — guard the injected path; on any refl/resolve failure, behave exactly as unpatched.
- **Compose-injection risk:** the `Lx22`/`Lz22` descriptor and the lazy tab row are the volatile part; budget pre-iterations (cf. menu-injection playbook pre7→pre17).
- **Empty-tab risk:** if P3 yields no data, an empty GOG tab is worse than none — gate P4 so the tab only appears when the data feed is non-empty (or behind the API flag, default off).

## 9. Scope & phasing

- **Phase 0 (spike, no patch):** resolve OQ#1–4 by tracing P1/P3 in `gamehub_604_decompile`. Exit criterion: a named class/method for the `m21.d` transform and a confirmed GOG-capable game query (or a decision to take Option C).
- **Phase 1:** `GogLibraryTabPatch` — inject descriptor at P1, typed P2, gated P4(c) with a new `gog_games_visible` flag added to `bannerhub-api/base/getBaseInfo` (default off) + read in `q21` alongside the existing three.
- **Phase 2:** data binding P3 + empty-state gating; device test.
- **Phase 3:** Lite refresh (cherry-pick onto `feature/lite-variant-tier1` per branching rule), docs (README *Patches applied*, PROGRESS_LOG, master map), letter-map entry.

## 10. Test plan

- Stock parity: flag off → identical 4-tab bar, byte-equivalent behaviour.
- Flag on, no GOG data → tab absent (or disabled), no crash.
- Flag on, GOG data present → tab renders with `platform_tab_gog` label + GOG icon; selecting it lists GOG titles; launching uses `GogGameByPcEmulator` (already works).
- Cross-`:wine`/process boundary unaffected (this is UI-layer only).
- Fault injection: force each injected resolve to fail → graceful fall-through to 4 tabs.

## 11. Non-goals

- GOG account OAuth / store browsing (separate track if absent).
- Reordering or renaming existing tabs.
- Any change to the Steam/Epic/Retro `*_games_hidden` behaviour.

---

### Next action

~~Phase 0 spike~~ — **DONE 2026-05-19, see §12.**

---

## 12. Phase 0 spike results (2026-05-19) — SUPERSEDES §4 and §6

**Verdict: Option A viable.** GOG data identity is first-class in the model; the only missing piece is a GOG **tab content screen**. Not Option C. All anchors below **[CONFIRMED]** by direct 6.0.4 smali read.

### 12.1 Corrected pipeline (the speculative §4 "P1 = m21.d transform" was WRONG)

`m21.d` is **not** the tab list — it is the `steam_url_replace` list. The real pipeline:

1. **`/base/getBaseInfo` JSON** → deserialized to `BaseInfoDto` = **`o21`** (`smali_classes4/com/xiaoji/egggame/core/network/model/baseinfo/dto/BaseInfoDto$$serializer.smali`). JSON keys, in descriptor order: `GameHubRetroGamesHidden`(o21.a Bool), `GameHubSteamGamesHidden`(o21.b), `GameHubEpicGamesHidden`(o21.c), `steam_url_replace`(o21.d = `List<jal>`, `jal`=`SteamUrlReplaceItemDto`).
2. **`u21:1508`** maps `o21` → **`m21`** = `(a=retroHidden, b=steamHidden, c=epicHidden, d=List<hal> steam-url-replace)`. `m21.d` is steam-url-replace, NOT tabs.
3. **`r21.a(m21)`** (`smali_classes4/r21.smali`, full 102 lines read) = **persister**: writes `m21.a/b/c` to MMKV `base_info_{retro,steam,epic}_games_hidden` + sets `base_info_tab_hidden_cache_ready=1`. Write-only `Lp2k;->c(String,Z)V`.
4. **`q21:201-225`** reads those MMKV booleans back → rebuilds `m21(0x8, retro, steam, epic)` for the UI layer (cache-ready gated).
5. **`y6d`** (`smali_classes5/y6d.smali`) = **the tab-strip builder** (the real P1/P2).

### 12.2 The tab-strip builder — exact injection point [CONFIRMED]

`y6d` builds an `x9d` list-builder, conditionally adding one tab descriptor per family:

```
PC    : added (unconditional, before the gated block)
if (!m21.b) x9d.add(new tuc("steam", (ell) pjl.L.getValue(), s6d.b /*STEAM_GAMES*/))
if (!m21.c) x9d.add(new tuc("epic",  (ell) pjl.<slot>.getValue(), s6d.c /*EPIC_GAMES*/))
if (!m21.a) x9d.add(new tuc(retro..., ..., s6d.d /*RETRO_GAMES*/))
```

- **Tab descriptor type:** `Ltuc;-><init>(Ljava/lang/String; key, Lell; title, Ls6d; screen)V`.
- **Title source:** `ell` = resolved Compose string, pulled from a global state slot `Lpjl;->{L,…}:Lxrl;` via `.getValue()`.
- **Screen selector:** `Ls6d;` = an **enum with exactly 4 constants** — `a=PC_GAMES`, `b=STEAM_GAMES`, `c=EPIC_GAMES`, `d=RETRO_GAMES` (`smali*/s6d.smali` `<clinit>`). **No `GOG_GAMES` constant.** ← *the gap.*

### 12.3 GOG data identity — PRESENT [CONFIRMED]

`GameInfo.smali`: `getGogAppId()` (`:9758`) sits right beside `getSteamAppId()` (`:10349`) / `getEpicAppId()` (`:9488`), plus `getSourceType()I` (`:10327`), `getSourceSlug()`, `getSourceId()`, `getPlatforms()`. The game model can distinguish GOG titles → a GOG grid is feedable. This is why the verdict is **A, not C**.

### 12.4 Resolved open questions

- **OQ#1 (P1 site):** RESOLVED → `y6d` `tuc`-add chain. Inject one more `x9d.add(new tuc("gog", <ell>, <screen>))`, ungated or gated on a new flag.
- **OQ#2 (tab type):** RESOLVED → `tuc(String, ell, s6d)`.
- **OQ#3 (data feed exists?):** RESOLVED → yes, `GameInfo.getGogAppId()`/`getSourceType()`.
- **OQ#4 / Option B:** DEAD, re-confirmed — `r21`/`q21` only ever *hide* 3 fixed families via MMKV; the tab set + screens are the hardcoded `s6d` 4-enum. No API path adds a tab.

### 12.5 The one remaining (bounded) question → Phase 1

**Is the per-tab game grid query parameterized by `GameInfo` source, or hardwired per `s6d` value?**

- If **parameterized**: GOG tab = inject `tuc("gog", <ell from `platform_tab_gog`>, s6d.a /*reuse PC_GAMES screen*/)` with a GOG source filter (`getGogAppId()!=null` / `getSourceType()==<gog>`). **Small, no enum surgery.** ← expected, given `getSourceType()` exists.
- If **hardwired per `s6d`**: must add an `s6d` GOG constant (obfuscated Kotlin enum extension — new constant + `$VALUES` + ordinal/name; nasty) or synthesize a GOG grid screen. **Large.**

Resolve by tracing the `getSourceType`/`getGogAppId` callers (`ajf, dp7, bm6, bh4, ckf, gl7, kxf, po7, wl7`) and how `s6d.{a,b,c,d}` selects its grid composable. **Do NOT extend the `s6d` enum** unless 12.5 proves the screen is unparameterizable — prefer screen-reuse + source filter.

### 12.6 Refined scope

- **`GogLibraryTabPatch`** (bytecode): inject one `tuc` into `y6d`, key `"gog"`, title `ell` built from the present `features_home_profile_platform_tab_gog` string, screen = `s6d.a` (PC_GAMES) **+** a source filter so the grid shows GOG titles. Gate on a new BannerHub `base/getBaseInfo` flag (default off) read alongside the 3 existing ones (extend `q21` read + `r21` persist; mirrors the proven pattern) — gives an API kill-switch without touching the hide-3 semantics.
- Reuses: `GogGameByPcEmulator` launch (works), GOG icons, `platform_tab_gog` string, `getGogAppId` filter. **Zero new resources, zero enum surgery (pending 12.5 confirmation).**
- Risk class: single-`tuc`-injection into one Compose builder + a list-filter predicate — **materially smaller than the menu-injection playbook** (no Unsafe, no Proxy, no resolver short-circuit). R8 anchors (`y6d`, `tuc`, `s6d`, `q21`, `r21`) need a letter-map entry + are fingerprint-migration candidates (structural roots: the `base_info_*_games_hidden` literal triple, the `s6d` 4-constant `PC/STEAM/EPIC/RETRO_GAMES` names, `GameInfo.getGogAppId`).

**Phase 1 entry criterion:** answer 12.5 (one trace pass). Then build behind the default-off API flag.
