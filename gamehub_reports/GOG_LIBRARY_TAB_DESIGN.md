# GOG Library Tab — Patch Design Doc

**Status:** ⚑ **GREENLIT — full GOG integration scoped in §17 (CURRENT).** Real goal = GOG account login + owned-library + install + launch in v6, delivered as a **standalone GOG screen via a Profile-screen "GOG" account row** (NOT a Library tab — no enum surgery; not the per-game menu). Next = **Phase 0 traces P-A..P-D (P-B priority), no code.** §1–§16 are the trace record that led here (§13 cheap-path / §12 tab both superseded — they solved the wrong layer; backend never existed in 6.0.4, see §14). Branch `feature/gog-explore-tab`.
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

**Phase 1 entry criterion:** ~~answer 12.5~~ — **DONE, see §12.7.**

### 12.7 §12.5 RESOLVED (2026-05-19) — verdict: MODERATE (not trivial, not huge)

Traced the tab→grid path end to end. **The game grid is ONE parameterized screen — the "build a whole new GOG screen" worst case is OFF the table.** [CONFIRMED]

- `y2d` field `$filterTabTypeByContentTab : Map<Lwrc;, Ls6d;>` — content-tab → `s6d` screen-enum lookup. One grid, selected by `s6d`.
- Grid filters games via a source classifier in `a5d` (`~:1560-1612`): a `when` returning the source slug — full case set is **`retro / mobile / epic / steam / pc`**. **No `gog` case.** Zero `const-string "gog"` anywhere in the grid/filter classes (`a5d/y2d/otc/po7/dp7`).
- `s6d` enum = exactly 4 (`PC/STEAM/EPIC/RETRO_GAMES`), no GOG (re-confirmed). s6d-ordinal branch consumers: `isc`, `rtc`.

**So the parameterization exists but its vocabulary has no GOG.** A GOG tab = **3 mechanical bytecode edits + 1 flag**, no screen-building, no Unsafe/Proxy:

1. **`y6d`** — inject `x9d.add(new tuc("gog", <ell from `features_home_profile_platform_tab_gog`>, <s6d gog>))`, gated on the new flag.
2. **`s6d` enum** — add a GOG constant (Kotlin-enum smali surgery: new `enum` field + `$VALUES` array entry + `valueOf`/`values` upkeep). Mechanical but it *is* enum surgery; the filter map is keyed by `s6d` so a distinct value is the clean route. Wire it into the `$filterTabTypeByContentTab` build + the `isc`/`rtc` ordinal switches' default-safe fallthrough.
3. **`a5d` source classifier** — add a `gog` case so a game with `getGogAppId()!=null` (or `getSourceType()==<gog int>`) classifies into the GOG tab's grid. (GOG-source int value: still UNVERIFIED — Phase-1 first task, one grep of the `getSourceType`/`getGogAppId` callers.)
4. **API flag** — `gog_games_visible` in `bannerhub-api/base/getBaseInfo` (default off), read in `q21` + persisted in `r21` alongside the existing 3. API kill-switch, zero risk to the hide-3 semantics.

**Effort tier:** MODERATE — bigger than the §12.6 single-`tuc` hope (enum + classifier extension added), smaller than the §12.5 worst case (no screen built). Reuses the working `GogGameByPcEmulator` launch, GOG icons, `platform_tab_gog` string, parameterized grid. Volatile bits = the `s6d` enum extension + `a5d` classifier edit + the `isc`/`rtc` ordinal switches (must add default-safe handling so an unknown `s6d` never crashes — house fail-safe rule).

### 12.8 Last UNVERIFIED item RESOLVED (2026-05-19) — GOG predicate is trivial [CONFIRMED]

The GOG `getSourceType()` int turned out to be a non-issue: there is no GOG sourceType int. `ul5:~4141` is the canonical source discriminator — an **app-id precedence chain**: `getSteamAppId()` non-empty → steam; else `getEpicAppId()` non-empty → epic; else **`getGogAppId()` non-empty → gog**; else fallback. (`getSourceType()` int compares in `a5d:37312` etc. are a secondary signal only.)

**So the §12.7-step-3 GOG filter predicate = `!GameInfo.getGogAppId().isEmpty()`** — parallel to steam/epic, and the exact precedence pattern is **already coded in `ul5`** to copy verbatim. Best-case form: no enum-of-sourcetypes work, no int to discover.

**Phase 0 status: 100% CLOSED — nothing left UNVERIFIED.** Net build = the 3 mechanical edits + API flag in §12.7, with step-3's predicate now pinned to the `ul5` `getGogAppId`-non-empty pattern.

**Phase 1 entry:** none pending — but see §12.9, which corrects the edit count/risk before any code.

### 12.9 Pre-implementation trace (2026-05-19) — CORRECTS "3 edits"; no shortcut [CONFIRMED]

User chose **APK-only, always-on** (no API flag — drop §12.7-step-4). Tracing the live filter binding before coding revealed the full discriminator chain:

`tuc.a` (string key) → **`kg5.n(String)→wrc`** (`smali_classes4/kg5.smali:494`, hashCode sparse-switch: `steam→wrc.c, retro→wrc.f, epic→wrc.d, pc→wrc.b, mobile→wrc.e`) → `a5d.I0(wrc)→slug` → game-list filter (`a5d`, I0 callers `19993/20001/22345/22568/52604/52612`).

**Every layer is a fixed 5-way structure with no GOG**, and they interlock:
- `kg5.n`: no `"gog"` sparse-switch case.
- `wrc`: 5 constants (b=pc,c=steam,d=epic,e=mobile,f=retro) + synthetic `g:[Lwrc;`, `h:Lff5;`. No GOG.
- `I0`: 5 slugs. No GOG.
- `s6d`: 4 screen constants. No GOG.

**No low-risk shortcut.** The only wrc not surfaced as a tab is `e`/"mobile"; repurposing it hijacks mobile-game classification app-wide — rejected. A correct GOG tab therefore needs **~5 interlocked edits**, not 3:

1. `kg5.n` — add `"gog"` sparse-switch case → new wrc GOG constant.
2. **`wrc` enum** — add 6th constant (`enum` field + `g:[Lwrc;` `$VALUES` entry + `Lff5;` EnumEntries + ordinal/`valueOf`/`values`).
3. **`s6d` enum** — add GOG screen constant + wire `$filterTabTypeByContentTab : Map<wrc,s6d>` + `isc`/`rtc` ordinal-switch default-safe fallthrough.
4. `a5d.I0` — add `"gog"` slug case; extend the slug→game filter with the `!getGogAppId().isEmpty()` predicate (pattern from `ul5`).
5. `y6d` — inject `tuc("gog", <ell from `features_home_profile_platform_tab_gog`>, <s6d gog>)` unconditionally (always-on).

**Revised effort: MODERATE→HIGH.** This is the project's highest-risk patch class — **dual obfuscated-Kotlin enum extension** (`wrc`+`s6d`: VALUES/EnumEntries/ordinal, VerifyError-prone, runtime-only failure) **+ Compose injection**, in a **CI-only build (no local test) where per-patch SEVERE does not fail CI** (silent-ship footgun, see `[[bannerhub-revanced-menu-injection-playbook]]`). Project precedent for a *simpler* single-Compose injection (menu-injection) = pre7→pre17, ~10 device iterations. **This cannot be one-shot-verified by inspection; it must enter the push→CI→device-test→fix loop.** Strong fingerprint-migration candidate (anchor on the `kg5.n` 5-string sparse-switch, the `s6d` `PC/STEAM/EPIC/RETRO_GAMES` names, `LaunchType.GogGameByPcEmulator`, `GameInfo.getGogAppId`).

**Phase 1 reality:** first-cut patches are writable now, but "implemented" ≠ "working" until the CI+device loop validates the dual-enum surgery. Recommend treating this as a normal multi-iteration feature (like vibration/menu-injection), not a drop-in.

---

## 13. Cheap alternative (2026-05-19) — user chose "cheaper path first"

User declined the dual-enum-surgery tab (§12.9 too risky) and asked to scope a lower-risk way to give GOG access. Decisive trace finding:

**`GameInfo.getGogAppId()` is referenced ZERO times in every game-list classifier** (`hc5/qra/nfj/t2g/vl7/lb3` — all `gog=0`; only `getSteamAppId`/`getEpicAppId` drive categorization). App-wide, `getGogAppId` appears only in `ul5:~4141` (appid-string precedence resolver) and `ajf:878` (launch mapper). [CONFIRMED]

### 13.1 Most likely reality: GOG already works, just unbranded — ZERO code

The game-list classifiers special-case **only** Steam and Epic; a title with no steam/epic id falls to the **PC/Wine default category**. A GOG-only game (`getGogAppId` set, no steam/epic id) therefore **[INFERRED]** already classifies into the **PC Games** tab — and the `LaunchType.GogGameByPcEmulator` path is fully wired **[CONFIRMED]**, so it should launch. Net: GOG import → appears under *PC Games* → launches. No patch, no risk.

**This is INFERRED, not byte-proven** (not traced one game end-to-end through the list filter). It is **cheaply and definitively verifiable on-device** (project norm): import a GOG title, confirm it shows in PC Games and launches. Outcomes:
- **Works** → feature is "already shipped, unlabeled." Zero code. Done. Optionally §13.2.
- **Appears but won't launch** → small launch-mapper edit in `ajf` (the one place GOG launch is mapped). Low risk.
- **Doesn't appear at all** → the PC classifier explicitly excludes gog ids → widen ONE predicate to admit `!getGogAppId().isEmpty()` (the `ul5` pattern). Single-instruction-class edit, ShowPcGameSettingsRow risk tier. Still no enum surgery.

### 13.2 Optional polish (only if 13.1 confirms and a visual cue is wanted)

A "GOG" badge/label on GOG titles *within* the PC tab, or a GOG filter-chip on the PC screen — additive UI, no enum/tab surgery, far below §12.9 risk. Scope separately on demand; not needed for functional access.

### 13.3 Recommendation

**Verify §13.1 on-device first. Most probable result: nothing to build.** This is the rational cheap path — confirm the latent behavior before writing any code; only fall to the single-predicate edit if the import test proves GOG titles are actively excluded. The §12.9 dual-enum tab remains documented if a first-class branded tab is ever wanted, but it is not the cheap path and is not recommended unless the maintenance cost is explicitly accepted.

**Next action:** on-device GOG import test (no code). Branch state unchanged; design doc only.

---

## 14. PIVOTAL (2026-05-19) — real goal = GOG account login + owned library; backend DOES NOT EXIST in 6.0.4

User clarified the actual requirement: **GOG account login + display the user's GOG-owned library** (not "show already-imported GOG games"). This recharacterizes the whole doc. §1–§13 addressed the *tab surface*; the real problem is the *backend*.

### 14.1 Decisive backend audit [CONFIRMED]

| Store | Native SDK | Backend footprint | Acct login + library + download |
|---|---|---|---|
| Steam | `libsteamkit_core.so` | full | ✅ in base |
| Epic | `libepickit_core.so` | 373 epic classes + 289 `uniffi/epickit` | ✅ in base |
| **GOG** | **none** (`libgog*`/`libgalaxy*` absent) | **1 gog-named class total** | ❌ **absent** |

Also CONFIRMED absent in 6.0.4: GOG API hosts (no `gog.com`/`embed.gog.com`/Galaxy API in smali or assets), GOG OAuth, GOG auth deep-link/scheme. Present for GOG = **only** `LaunchType.GogGameByPcEmulator` (run a GOG `.exe` via Wine *if files already on disk*), GOG icons, and the **orphaned** `features_home_profile_gog_{bind,title,desc}` strings (dead UI scaffolding, no login flow behind them).

### 14.2 Consequence

The goal is **not** a ReVanced patch / config / enum / tab problem. There is nothing to unhide, surface, or inject — the GOG account/library/download capability **was never built into the XiaoJi GameHub 6.0.4 base**. §12 (tab) and §13 (PC-tab fallthrough) are both moot for the *stated* goal: a tab with no backend lists nothing; PC-tab fallthrough only helps games already side-loaded, not account-owned library.

Achieving login + owned-library = **building a full GOG integration**, comparable in scope to what `libepickit_core.so` (4.6 MB Rust SDK + 289 classes) provides for Epic: (1) GOG OAuth (`auth.gog.com`/`embed.gog.com`), (2) GOG API client for owned-library + metadata, (3) GOG DRM-free/Galaxy-CDN downloader, (4) UI + wiring. That is a major feature, not a bytecode tweak.

### 14.3 Realistic path forward (not in this doc's scope to execute)

GOG integration **already exists in the GameNative / BannerHub-3.7.x lineage** (a *different* codebase from the XiaoJi 6.0.4 base v6 patches): see project memories `[[project_bannerhub_gog_download]]` (multi-CDN GOG download stack, shipped BannerHub v3.7.3) and `[[project_gamenative_store_port_backlog]]` (clean GOG fixes surveyed). So the viable route is a **port of the GameNative/3.7.x GOG stack into the 6.0.4 base**, on the order of the Epic-EOS investigation (`[[project_bannerhub_epic_eos_investigation]]`) — a real integration project with its own scoping. Open sub-question for that effort: whether the 3.7.x/GameNative GOG stack includes *account login + owned-library listing* or only *download-by-known-id* (the 3.7.x memory documents a download stack + picker, not explicitly OAuth/library) — assess before committing.

### 14.4 Status

**Tab work (§12/§13) SHELVED — solves the wrong layer for the stated goal.** Next step is a decision: open a separate "GOG integration port" scoping effort (large), or drop GOG for v6. No code. Branch `feature/gog-explore-tab` holds the full trace record.

---

## 15. GameNative GOG stack audit + port feasibility (2026-05-19)

Audited `/data/data/com.termux/files/home/GameNative` (Kotlin-source lineage; the v6 base is the *unrelated* obfuscated XiaoJi 6.0.4 APK).

### 15.1 Does it have account login + owned library? — YES, full integration [CONFIRMED]

~20 classes under `app/gamenative/service/gog/` + `ui/screen/auth/GOGOAuthActivity.kt` + `ui/screen/library/appscreen/GOGAppScreen.kt`:
- **Login:** `GOGOAuthActivity` (WebView OAuth, captures GOG redirect auth code) + `GOGAuthManager` (`auth.gog.com/token`, refresh-token lifecycle, credential storage, Galaxy creds).
- **Owned library:** `GOGApiClient.getGameIds()` → `embed.gog.com/user/data/games`, parses the `"owned"` array = the user's owned-game IDs; `getGameById()` per-game metadata; `transformGameDetails()`.
- **Plus:** `GOGDownloadManager` (multi-CDN), `GOGManifestParser`, `GOGCloudSavesManager`, Room DAO/entities, full test suite.

Not download-by-id — a complete account → owned-library → download → cloud-save integration.

### 15.2 Port feasibility

**Backend module: portable.** Auth/api/download depend only on standard OkHttp/JSON/Coroutines/Room + ~5 small GameNative utils (`DownloadInfo`, `CdnRankingUtils`, `DownloadSpeedConfig`, `MarkerUtils`, `Net`) — **no coupling to GameNative's Wine/Steam internals**. Bundleable into the 6.0.4 APK as a ReVanced extension package (BannerHub already ships Kotlin/Java extensions: offline-picker, vibration, gpuspoof). `GOGOAuthActivity` addable via manifest patch (BannerHub already manifest-patches).

**The "5th tab next to PC/Steam/Epic/Retro" constraint is the expensive part — two compounding blockers:**
1. The literal tab still requires the §12.9 **dual obfuscated-Kotlin enum surgery** (`wrc`+`s6d`) the user already rejected.
2. The in-tab grid is GameHub's **obfuscated Compose**; GameNative's `GOGAppScreen` cannot be dropped in (different Compose tree, DI, game model entirely). The library UI would have to be rebuilt inside GameHub's obfuscated UI.

### 15.3 Tractable shape (drops the literal-tab constraint)

Bundle the GOG backend module + ship `GOGOAuthActivity` and a **standalone GOG library Activity** (reuse GameNative's own `GOGAppScreen` Compose *as a self-contained screen*, not injected into GameHub's UI), reached via a **menu-row injection** (BannerHub-proven: vibration/gpuspoof/renderer menu-row playbooks) instead of a tab. Then bridge installed GOG titles into GameHub's library so the existing `LaunchType.GogGameByPcEmulator` launches them — **the GameNative `GOGGame`/Room ↔ GameHub `GameInfo`/install-model bridge is the genuinely hard, novel piece** (no precedent; needs its own scoping).

**Verdict:** login+library *exists and the backend ports cleanly*; the **literal in-strip GOG tab is the costly constraint** (dual-enum + UI rebuild). Standalone-screen-via-menu-row avoids both rejected/expensive blockers and is the realistic shape — scope ≈ a BannerHub-API/Epic-EOS-class multi-iteration project, dominated by the game-model bridge, not the GOG code. **No code; this is the decision point: hold the literal-tab requirement (expensive) vs accept a standalone GOG screen entry point (tractable).**

---

## 16. Placement (2026-05-19) — entry point ≠ library screen; the designed-for home exists

User: the per-game menu (vibration/gpuspoof/renderer rows) is game-scoped and wrong for an account-level GOG login. Correct.

### 16.1 Decisive finding [CONFIRMED]

GameHub authored a **symmetric** set: `features_home_profile_{steam,epic,gog}_{bind,title,desc}` — Steam and Epic account-binding rows live on the **Profile (account) screen** (`HomeProfile`/`ProfileScreen` Compose route), and a **GOG row was scaffolded with the identical string pattern but never wired** (orphaned, same as `platform_tab_gog`: present only in the large generated resource-accessor classes `bkl/xjl/vjl/wjl`, no renderer consumes it). Secondary: GameHub also has a Library-screen account-bind button surface (`features_home_library_epic_bind_button`, rendered in `sgl.smali`).

### 16.2 Recommended placement

**Entry point = a "GOG" account row on the Profile screen, next to the existing Bind-Steam / Bind-Epic rows.** Why it's the right home:
- Semantically correct: account-level/global (the opposite of the per-game menu).
- *Designed-for*: GameHub's own devs put a GOG slot there (the `gog_{bind,title,desc}` strings already exist, ready to reference — no new resources).
- **Risk class = menu-row injection** — the exact pattern BannerHub has shipped 3× (vibration/gpuspoof/renderer menu-row playbooks). It does **NOT** require the §12.9 dual-enum tab surgery. This is the key payoff of abandoning the literal tab.

Secondary option: a "Bind GOG" button on the Library screen mirroring Epic's (`sgl`). More visible; same injection class. Either works; Profile is the cleaner primary.

### 16.3 Architecture: separate the two concerns

- **Entry point** → injected GOG row on the Profile screen (low-risk, proven pattern, mirrors the Steam/Epic rows as template).
- Tap → **`GOGOAuthActivity`** (bundled from GameNative, added via manifest patch — BannerHub already manifest-patches).
- Post-login → **standalone GOG library Activity** reusing GameNative's `GOGAppScreen` as a self-contained screen (no rebuild in GameHub's obfuscated Compose).
- Installed GOG title → the GameNative↔GameHub `GOGGame`/`GameInfo` bridge → existing `LaunchType.GogGameByPcEmulator` launches it (still the hard novel piece, unchanged from §15.3).

### 16.4 Honest caveat

Like `platform_tab_gog`, the profile `gog_*` strings are orphaned in resource accessors only; the precise Profile-renderer injection anchor needs a Phase-0-class trace (find the composable that builds the Steam/Epic rows; mirror a GOG row) — same method as the §12 `y6d` trace, but the *class of work is the proven menu-row injection*, materially lower risk than the rejected tab. Net: placement question resolved — **Profile account screen, not a tab, not the per-game menu**. Scope unchanged from §15 (dominated by the game-model bridge); the entry-point risk drops from "dual-enum surgery" to "menu-row injection."

---

## 17. FULL INTEGRATION SCOPE — GREENLIT (2026-05-19)

Decision: build GOG account login + owned-library + install + launch in BannerHub v6, **as a standalone GOG screen reached from a Profile-screen "GOG" account row** (§16), reusing the GameNative GOG module (§15). **Not a Library tab** (no §12.9 dual-enum surgery). Project class ≈ BannerHub-API / Epic-EOS: multi-iteration, CI-build + device-test loop, no local test.

### 17.1 Architecture

```
GameHub 6.0.4 APK (obfuscated; ReVanced-patched)
 ├─ [bundled extension] app.revanced.extension.gamehub.gog.*  ← ported GameNative GOG module
 │     GOGAuthManager · GOGApiClient · GOGDownloadManager · GOGManifestParser · GOGDataModels
 │     (Room → REPLACED with JSON-on-disk store, see 17.3-D)
 ├─ [manifest patch] GOGOAuthActivity (GameNative, WebView OAuth)
 ├─ [manifest patch] GogLibraryActivity (GameNative GOGAppScreen, self-contained — NO inject into GameHub Compose)
 ├─ [bytecode inject] "GOG" row on Profile screen → starts GOGOAuthActivity / GogLibraryActivity
 └─ [bridge] GogGameRegistrar: installed GOG dir → GameHub's PC-game library+launch (GogGameByPcEmulator)
```

### 17.2 Workstreams

| WS | Deliverable | Pattern precedent | Risk |
|---|---|---|---|
| WS1 | **Port GOG backend module** as a ReVanced extension package (auth/api/download/manifest/datamodels + ~5 utils: DownloadInfo, CdnRankingUtils, DownloadSpeedConfig, MarkerUtils, Net) | offline-picker / vibration extension bundling | MED — dep/version reconciliation (17.3-D) |
| WS2 | **GOGOAuthActivity** added via manifest patch; GOG client_id/redirect from GameNative; capture auth code | VibrationManifestPatch / GpuSpoofManifestPatch | LOW |
| WS3 | **GogLibraryActivity** = GameNative `GOGAppScreen` as standalone activity (own Compose, own theme) | new activity, self-contained | MED — Compose/Material deps in extension |
| WS4 | **Profile-row injection** — "GOG" row next to Bind-Steam/Epic on the Profile screen, opens WS2/WS3 | menu-row playbook (vibration/gpuspoof/renderer) | MED — needs P-A trace; proven class |
| WS5 | **GogGameRegistrar bridge** — installed GOG game dir → GameHub PC-game record so `LaunchType.GogGameByPcEmulator` launches it in a Wine container | **NONE — novel** | **HIGH — critical path** |
| WS6 | Build/CI: extension deps, R8/proguard keep rules, APK-size, default-off safety; docs/letter-map/memory | stable-release-pipeline | MED |

**Critical path = WS5.** Everything else is proven-pattern or self-contained; the bridge has no precedent and gates the feature's value (login+list without launch = useless).

### 17.3 Key scope decisions

- **A. Standalone Activity, not Compose injection.** GameNative `GOGAppScreen` ships as its own activity; zero rebuild in GameHub's obfuscated Compose. Avoids the §12.9 / §15.2 UI blocker.
- **B. Profile row, not tab.** Entry point = §16 menu-row-class injection. Eliminates dual-enum surgery entirely.
- **C. Reuse GOG auth/api/download verbatim** where the dep surface allows; treat as vendored upstream (track GameNative SHA for future pulls).
- **D. Drop Room.** GameNative `GOGGameDao`/`@Entity gog_games` → replace with a JSON-on-disk store mirroring the offline-picker pattern (`sp_winemu_*`/file cache). Bundling Room (codegen, schema, DB-version conflict with GameHub's own DBs) into an injected extension is unacceptable risk. This is a real port edit, scoped into WS1.
- **E. Default-off / fail-safe.** Profile row + activities behave inert on any failure; never crash GameHub (house rule; offline-picker precedent).

### 17.4 Phase 0 — pre-work traces (BLOCKING, no code until closed)

| ID | Trace | Why blocking |
|---|---|---|
| P-A | Profile-screen renderer anchor: the composable that builds the Steam/Epic bind rows (mirror target for the GOG row) — same method as the §12 `y6d` trace | WS4 cannot start without the injection anchor |
| **P-B** | **GameHub PC-game registration + launch contract**: exactly what record/path/container makes `GogGameByPcEmulator` launch a game (GameInfo has no path fields → it's the import pipeline + a Wine-container/prefix record). Trace the existing PC `.exe` import → library → launch chain end to end | **Defines WS5; the make-or-break unknown** |
| P-C | Wine-container/prefix model for an installed GOG game (which container, drive mapping, where the bridge writes the exe path) | WS5 correctness; cross-`:wine` boundary |
| P-D | Extension build feasibility: OkHttp / kotlinx-coroutines / kotlinx-serialization versions vs what GameHub already ships; Compose/Material for WS3; R8 keep rules | WS1/WS3 viability; dep-clash is a known APK-merge footgun |

### 17.5 Milestones (each gated by on-device test; CI-only build)

- **M0** Phase-0 traces P-A..P-D closed; this scope refined with concrete anchors.
- **M1** WS1+WS2: GOG login works standalone (OAuth → token stored); owned-library JSON fetched + logged. *Exit:* device login + library dump in logcat.
- **M2** WS3+WS4: Profile "GOG" row → login → GogLibraryActivity lists owned games. *Exit:* device sees own GOG library in-app.
- **M3** WS1 download path: a chosen GOG title downloads+installs to disk. *Exit:* files on device, integrity OK.
- **M4** **WS5 bridge**: installed GOG game appears in GameHub library and **launches** via `GogGameByPcEmulator` in a Wine container. *Exit:* a real GOG game runs. ← highest-iteration milestone.
- **M5** WS6 hardening: fail-safe, default-off, APK-size, docs/letter-map/memory; Lite refresh per branching rule.

### 17.6 Risk register

- **WS5 bridge (HIGH):** no precedent; GameHub's import/container model is undocumented (P-B/P-C). Mitigation: spike P-B first; if launch can't be bridged, the feature degrades to "browse/download only" — decide M0 whether that's acceptable.
- **Dep clash (MED):** extension pulls OkHttp/coroutines/serialization; GameHub ships its own. Mitigation: P-D audit; shade/relocate if needed.
- **R8 fragility (MED):** WS4 anchor + any bytecode site re-break per base bump → letter-map + fingerprint-migration candidate.
- **Silent-SEVERE footgun:** a failed patch ships green. Mitigation: explicit post-build asserts (row present, activity registered) per stable-release-checklist.
- **APK size (LOW-MED):** GOG module + Compose ≈ small vs the 6.0.4 base; Lite must strip or accept.
- **GOG ToS/login (LOW tech):** standard GOG OAuth as GameNative already does; no new surface.

### 17.7 Effort & non-goals

**Effort:** multi-iteration feature, WS5 dominating (expect M4 to take the most device cycles, cf. menu-injection pre7→pre17). M1–M3 are largely vendored-code + proven patterns. **Non-goals (v1):** GOG cloud saves (module exists — defer), GOG Galaxy features, in-app store/purchase, a Library *tab* (explicitly rejected — Profile entry only).

### 17.8 Next action

Execute **Phase 0 (P-A..P-D)** — four traces, no code. P-B is the priority (it decides whether WS5 is tractable and therefore whether the whole feature is viable beyond browse/download). M0 review after.
