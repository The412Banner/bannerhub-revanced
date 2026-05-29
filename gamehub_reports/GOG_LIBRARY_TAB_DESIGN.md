# GOG Library Tab — Patch Design Doc

**Status:** ⚑ **PHASE 1 + WS4 + WS5 (DB-insert bridge) DEVICE-CONFIRMED through pre15; §38 (pre17/18) locks the no-in-GOG-launch UX; §39 (pre20) restores in-session library refresh via REORDER_TO_FRONT MainActivity nudge after pre19 diagnostic proved RoomRefreshHelper reflection is healthy but `tracker.refresh()` alone is insufficient. CURRENT = §39 (pre20, awaiting device test).** GOG login + owned-library + download/install device-confirmed (pre2). WS4 menu row + retired card + `behind` rotation device-confirmed (pre13). **WS5 (this session, §35–§36):** Approach A killed by 6.0.4 bytecode recon (no `B3` equivalent; import flow is pure Compose-internal — confirmed via live capture of the import button); Approach B (programmatic DB insert) built — `GogLaunchHelper.triggerLaunch` now opens `db_game_library.db` directly via `SQLiteDatabase.openDatabase`, self-derives `extension_type`/`user_id` from existing rows (proven retired-seeder pattern), inserts 2 rows (`t_game_launch_method` with `start_type=1409=LaunchType.GogGameByPcEmulator.id`, `t_game_library_base` with FK), then fires `app_nav_target=local_game_launch` intent to MainActivity which already handles the launch via the existing Wine pipeline. Zero third-party deps; pure `android.database.sqlite` + `org.json`. JSON shape byte-verified against your live God of War row. Branch `feature/gog-explore-tab`.
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

---

## 18. SOURCING DECISION (2026-05-19) — reuse the BannerHub 3.7.x GOG extension, NOT a fresh GameNative port. CORRECTS §15/§17.

User asked: GameNative directly, or the BannerHub 3.7.4 stuff? Audited `/data/data/com.termux/files/home/bannerhub/extension/Gog*.java` (shipped in BannerHub v3.7.3, `a477165a8`). **3.7.x already did every hard GameNative→ReVanced adaptation.** [CONFIRMED]

### 18.1 What 3.7.x already has (Java extension, ReVanced-adapted vs raw GameNative Kotlin)

| Class | Confirmed behaviour |
|---|---|
| `GogLoginActivity.java` | Real GOG OAuth2 — WebView `auth.gog.com/auth`, intercepts `embed.gog.com/on_login_success` redirect. Full login. |
| `GogGamesActivity.java` | "Displays the signed-in user's GOG library" — `GET user/data/games` → owned IDs; `gog_library_cache` SharedPrefs cache. Plain `extends Activity` + native Views (no Compose). **Full owned-library, standalone.** |
| `GogGameDetailActivity` / `GogMainActivity` | Detail + hub screens, same standalone-Activity style |
| `GogDownloadManager` / `GogInstallPath` / `GogTokenRefresh` / `GogCloudSaveManager` | Multi-CDN download, install paths, token refresh, cloud saves |
| `GogGame.java` | **Plain POJO — no Room** (`@Entity`/`@Dao` absent). §17.3-D "drop Room" is ALREADY DONE. |
| `GogLaunchHelper.java` | **The bridge — and a low-coupling design that sidesteps the WS5 fear:** install → `triggerLaunch(activity,exePath)` stashes `pending_gog_exe` in SharedPrefs → `LandscapeLauncherMainActivity.onResume()` → `checkPendingLaunch()` reflection-invokes GameHub's `g3(exePath)` → opens GameHub's **own EditImportedGameInfoDialog pre-filled**. It piggybacks GameHub's existing PC-exe import dialog rather than deep-integrating an undocumented game/container model. |

### 18.2 Answer & scope correction

**Use the BannerHub-3.7.x Java GOG extension as the base, not GameNative source.** GameNative remains the *upstream credit/origin*; 3.7.x is the already-translated, already-de-Roomed, already-standalone-Activity, already-shipped form. Re-porting from GameNative Kotlin would redo solved work.

This **collapses §17's workstreams**:
- WS1/WS2/WS3 (port module / OAuth activity / library screen): → **lift the 3.7.x `Gog*.java` extension largely verbatim** (pure OkHttp/JSON/WebView/Android-Views logic — base-agnostic). MED→LOW.
- WS5 (bridge): → **re-anchor `GogLaunchHelper`'s two 5.3.5-specific reflection targets to their 6.0.4 equivalents** — (a) the launcher's onResume pickup point, (b) the `g3(exePath)`→imported-game-dialog opener. Strategy is proven (reuse GameHub's own import dialog); only the anchors change. HIGH→**MED**, with a working reference implementation instead of a from-scratch design.
- WS4 (Profile-row entry): unchanged — still the §16 menu-row-class injection, re-anchored 5.3.5→6.0.4.

### 18.3 Residual base-specific risk (the honest crux — what Phase 0 must still confirm)

6.0.4 is the XiaoJi **KMP rewrite** — very different from 5.3.5. The Java extension classes lift cleanly; the *base-coupled* parts do NOT and must be re-derived:
- **P-B (reframed):** does 6.0.4 have an equivalent of GameHub-5.3.5's `g3(exePath)` / `EditImportedGameInfoDialog` (the launcher-side "import this PC .exe" entry the bridge piggybacks)? 6.0.4 has a PC-import flow + `getLocalGameDetail` (earlier traces) so likely yes — but its method/anchor is the make-or-break for the bridge. Now a *find-the-6.0.4-equivalent* task, not a *design-a-bridge* task.
- **P-A:** Profile-screen renderer anchor (unchanged).
- **P-C:** how 6.0.4's import dialog assigns the Wine container/prefix to the imported GOG exe (was implicit in 5.3.5's `g3`).
- **P-D:** does v6's extension build accept the 3.7.x extension's deps (3.7.x already builds it for the 5.3.5 base — strong positive signal; verify v6 build harness parity).

### 18.4 Net

Project is **materially smaller and lower-risk than §17 stated.** Not "port GameNative + design a novel bridge" — it's **"lift the proven BannerHub-3.7.x GOG Java extension and re-anchor ~3 base-coupled hooks (Profile row, launcher onResume, g3 import-dialog) from 5.3.5 → 6.0.4."** Phase 0 unchanged in spirit; P-B reframed from "design" to "locate 6.0.4's import-dialog equivalent." §17 milestones M1–M3 shrink to porting+re-anchoring; M4 (launch) remains the highest-iteration item but now has a working 5.3.5 reference.

---

## 19. PHASE 0 — P-B RESULT (2026-05-19): bridge viable, but NOT a verbatim 3.7.x reflection port

P-B = locate 6.0.4's equivalent of 5.3.5's `LandscapeLauncherMainActivity.B3(exePath)` (the launcher entry `GogLaunchHelper` piggybacks). The 5.3.5 contract (re-read from `bannerhub/extension/GogLaunchHelper.java`): stash `pending_gog_exe` → on launcher `onResume`, reflection-invoke `B3(String)` on the launcher activity → opens GameHub's own import-edit dialog pre-filled. (Javadoc says `g3`; code calls `B3` — stale comment.)

### 19.1 Findings [CONFIRMED unless noted]

| Half of the bridge | 5.3.5 | 6.0.4 equivalent | Port verdict |
|---|---|---|---|
| Launcher `onResume` hook | `com.xj.landscape.launcher.ui.main.LandscapeLauncherMainActivity` | **`com.xiaoji.egggame.MainActivity`** — non-obfuscated, the sole `android.intent.category.LAUNCHER` activity (manifest:85). | **Ports cleanly.** Inject `GogLaunchHelper.checkPendingLaunch(this)` into `MainActivity.onResume()`; stable non-obf anchor. LOW risk. |
| "Open import pre-filled" entry | single Activity method `B3(exePath)` | **No equivalent.** 6.0.4 is the KMP/Compose rewrite: PC-exe import = `vcd` (import screen/factory) `new x3g(exePath)` → `x3g.m/n` suspend → `simulator/getLocalGameDetail` recognition → import-edit Compose screen → DB write via the `xm7.u(GameInfo,LaunchMethod,Cont)` chain (memory: writes `game_library_base`+`game_launch_method` in `GameLibraryDatabase`). There is **no callable launcher method** to reflect into. | **Does NOT port.** 5.3.5's reflection trick is dead on 6.0.4. |

### 19.2 Consequence — WS5 reshaped

The 3.7.x bridge is **half-portable**: the launcher-onResume hook ports verbatim (just retarget `MainActivity`); the import-trigger half must be **re-implemented** for 6.0.4's KMP architecture. Three candidate mechanisms:

- **(c) Programmatic DB import via the `xm7.u` chain — RECOMMENDED primary.** Construct `GameInfo` + `LaunchMethod` with `LaunchType.GogGameByPcEmulator` and invoke the memory-documented `xm7.u` import (writes `game_library_base`/`game_launch_method`). Most decoupled, most R8-survivable, reuses an *already-understood* chain. Cost: it's a Kotlin **suspend** fn (needs a Continuation shim from the Java extension) and we hand-build the two entities (cover-art enrichment via `getLocalGameDetail` already solved server-side, so acceptable to skip).
- (a) Intent/nav to the `vcd` import screen with the exe path pre-supplied — fallback; no path-carrying Intent extra found yet (normal entry is interactive file-pick). Needs a trace if (c) proves hard.
- (b) Drive `x3g(exePath)` VM directly — rejected: obfuscated + coroutine + Compose-state plumbing, most fragile.

### 19.3 P-B verdict

**Bridge is VIABLE — WS5 stays MED, not HIGH** (a clean non-obf onResume hook + an already-mapped import chain). **But §18's "re-anchor 2 reflection targets" was optimistic for WS5:** reality = re-anchor the onResume hook (trivial) **+ re-implement the import-trigger as a programmatic `xm7.u` call** (moderate — suspend-fn Continuation shim + entity construction). Net project size between §17 (HIGH, novel) and §18 (LOW, verbatim): **MED, with a clear primary mechanism and an understood chain.** No fundamental blocker found — the feature remains tractable.

### 19.4 Feeds the rest of Phase 0

- **P-C** (container/Wine-prefix assignment) is now directly coupled: programmatic `xm7.u` import means we must also set the GOG game's Wine container the way the import-edit screen would — P-C must trace what `xm7.u`/the import path sets for container/prefix so the GOG entry launches correctly. **P-C priority raised.**
- P-A (Profile-row anchor) and P-D (extension build) unaffected by P-B.

**Next:** P-C (now critical — container assignment for a programmatic import), then P-A, then P-D.

---

## 20. PHASE 0 CLOSE-OUT (2026-05-19) — re-scoped per user: prove login/library/download first; defer bridge & entry-point polish

User directive: get **GOG login + library list + downloads** working end-to-end first; defer "how GOG games get added to GameHub's library/launch" (WS5 bridge) until that's proven. Re-prioritisation of Phase 0 follows that logic.

### 20.1 P-A — DEFERRED to WS4 (not a priority-scope blocker)

Profile account-bind rows are the same orphaned-accessor pattern as the tab string: `features_home_profile_{steam,epic}_bind` live only in the 4 large generated resource-accessor classes (`bkl/xjl/vjl/wjl`, ~9.8k L each); no renderer references the keys (renderer uses resolved `Lell;` state slots, cf. §12 `y6d`). Pinning the exact bind-row builder needs a full y6d-class chain trace. **Per the user's own prioritisation:** validating login/library/download only needs the GOG activities to build + launch by *any* entry (temp/dev launcher, `adb am start`, or an existing owned hook). The polished Profile-row injection is **WS4 implementation work, deferred** with WS5 — not Phase-0-blocking. Profile screen composable candidates for later: `bqb/dh0/od0` + `b30` (HomeProfile/ProfileScreen refs).

### 20.2 P-C — DEFERRED with WS5

Wine-container assignment for a programmatic import is bridge work (§19.4). Deferred with WS5 per user.

### 20.3 P-D — GREEN. Dependency-clash risk ELIMINATED. [CONFIRMED]

3.7.x `bannerhub/extension/Gog*.java` dependency surface = **pure platform only**: `android.*` (incl. `android.webkit.WebView` for OAuth), `java.*` (incl. `java.net.HttpURLConnection`, `java.util.zip`), and `org.json` (**Android-built-in**, ships in `android.jar`). **No OkHttp / Gson / Retrofit / Room / AndroidX / Kotlin-stdlib — zero third-party deps.** It was deliberately written platform-only.

bannerhub-revanced extension build (`extensions/gamehub/build.gradle.kts`) = `compileOnly(project(":extensions:gamehub:stub"))` — plain classes compiled against the GameHub API stub, same as the shipped offline-picker/vibration extensions. A zero-third-party-dep Java package drops in with **no reconciliation, no shading, no version clash**. The §17.6 "dep clash MED risk" is **eliminated**, and this is exactly why 3.7.x shipped it cleanly and why it lifts to v6 cleanly.

### 20.4 Re-scoped plan — PHASE 1 (executable, low-risk) vs PHASE 2 (deferred)

**PHASE 1 — GOG login + library + download (priority, now well-understood, LOW risk):**
- WS1 port GOG module: lift `bannerhub/extension/Gog{Login,Games,GameDetail,Main}Activity.java` + `Gog{DownloadManager,InstallPath,TokenRefresh,Game,CloudSaveManager}.java` ~verbatim into `bannerhub-revanced/extensions/gamehub/` (pure-platform Java; compile vs stub).
- WS2 OAuth: `GogLoginActivity` added via manifest patch (pattern: VibrationManifestPatch / GpuSpoofManifestPatch).
- WS3 library/download: `GogGamesActivity` (plain Activity+Views, owned-library via `user/data/games`, `gog_library_cache`) + `GogDownloadManager` multi-CDN.
- Temp entry for dev/validation (not the production Profile row).
- **Milestones:** M1 device login (OAuth → token stored). M2 owned library lists in-app. M3 a title downloads+installs to disk. Each device-gated, CI-only.

**PHASE 2 — DEFERRED (per user, after Phase 1 works):** WS4 production Profile-row entry (P-A trace), WS5 bridge = launcher-onResume hook on `MainActivity` (ports, §19) + programmatic `xm7.u` import + P-C container assignment, M4 in-GameHub launch.

### 20.5 Phase 0 verdict

**No blocker anywhere.** Priority scope (Phase 1: login/library/download) is **LOW-risk and fully understood** — pure-platform Java lift + manifest-patched activities + stub build, all proven patterns. All uncertainty (bridge KMP re-implementation, Profile-row anchor) is explicitly isolated in deferred Phase 2. Phase 0 complete.

**Next action:** begin **Phase 1 / WS1** — port the `Gog*.java` set into `extensions/gamehub/` and stand up the OAuth + library activities behind a temp entry. First code of the project. CI-build + device-test loop from M1.

---

## 21. WS1 SCOPE CORRECTION (2026-05-19) — port closure is 21 files / ~8k LOC, not "~9"

Starting WS1 revealed the GOG stack depends on a shared `Bh*` download/storage/install infra layer (also used by the Epic/Amazon stacks). Precise GOG-only transitive closure (Epic/Amazon stacks pruned; comments/strings stripped from ref-scan):

- **GOG core (10):** GogLoginActivity·GogGamesActivity·GogGameDetailActivity·GogMainActivity·GogDownloadManager·GogCloudSaveManager·GogInstallPath·GogTokenRefresh·GogGame·GogLaunchHelper.
- **Shared infra (11):** BhCdnHelper·BhDownloadConfig·BhDownloadService·BhDownloadsActivity·BhInstallConfirmDialog·BhStorageHelper·BhStorageMigration·BhStoragePath·FolderPickerActivity·BhEpicEosDetector·BhEpicSidecar (last two = generic install-path EOS checks the shared service touches; small, kept verbatim for fidelity).

**Total: 21 files / ~8,013 LOC.** Zero third-party deps across the whole closure (P-D holds) — uniform package move is mechanically safe (intra-set refs are same-package, no imports; closure is complete by construction). §20's "~9 files" was the GOG core only; real WS1 ≈ 2× that. Still a faithful lift (shipped/proven 3.7.x code), not a rewrite — risk class unchanged, volume corrected.

**Phase-1 adaptations during the lift:** (a) package `app.revanced.extension.gamehub` → `…gamehub.gog` (all 21 uniformly); (b) `GogLaunchHelper` → Phase-1 stub (same API, body logs "bridge deferred to Phase 2" — keeps GogGames/GogGameDetail compiling without the deferred WS5 bridge); (c) `GogMainActivity.onResume` `pending_gog_exe`→finish() handoff neutralized (Phase-2 bridge coupling); (d) Kotlin manifest patch registers the activities; (e) temp dev entry = `GogMainActivity` exported for `adb am start` (production Profile-row = deferred WS4/P-A).

**Verification reality:** CI-only build, no local compile, silent-SEVERE footgun. An 8k-LOC faithful lift cannot be inspection-verified — **first CI build is the M1 gate**; treat as unverified until CI-green + device login test.

---

## 22. WS1 FIRST-CUT DONE (2026-05-19) — ported, decoupled, manifest-wired; UNVERIFIED pending CI

§21's "21-file verbatim lift" hit a real complication caught by inspection (not blind-committed): the shared `Bh*` infra is **store-entangled** — `BhDownloadService` (`switch(store)` → runEpic/runGog/runAmazon) and `BhDownloadsActivity` (`openDetailScreen` per-store routing) hard-reference the Epic/Amazon stacks as code. v6 is GOG-only, so verbatim lift was wrong; surgical GOG-only decoupling was required (faithful-lift principle yields to "Phase 1 is GOG-only by design"). Decisive precheck: `runGog` and all GOG-set files use **none** of the Epic/Amazon-named helpers → clean excision possible.

**Done this session (branch `feature/gog-explore-tab`, `extensions/gamehub/.../gamehub/gog/`):**
- 19 files ported, uniform package `app.revanced.extension.gamehub.gog`; **closure self-contained, zero unresolved cross-refs, braces balanced** (inspection-verified).
- `BhDownloadService`: `case "EPIC"`/`"AMAZON"` + `runEpic`/`runAmazon` methods excised via brace-matching; `runGog` + GOG path intact; 0 Epic/Amazon refs.
- `BhDownloadsActivity`: `openDetailScreen` EPIC/AMAZON routing branches excised; GOG branch intact.
- `BhEpicSidecar`/`BhEpicEosDetector` dropped (orphaned once `runEpic` gone) → 21→19 files.
- `GogLaunchHelper` → Phase-1 stub (API preserved; bridge deferred §19/§21).
- `GogMainActivity.onResume` → Phase-2 `pending_gog_exe`→finish() hand-off neutralized.
- `patches/.../gamehub/gog/GogManifestPatch.kt` (resourcePatch, mirrors `vibrationManifestPatch`) registers 6 activities; `GogMainActivity` exported **Phase-1-only** as the temp `adb am start` dev entry (production Profile-row = deferred WS4/P-A). Extension classes ride into the APK via the always-applied `sharedGamehubExtensionPatch` (whole `extensions/gamehub` module → `.rve`).

**Verification boundary (honest):** all checks are **inspection-level** (self-containment, brace balance, uniform package, no unresolved symbols). CI-only build, no local compile, silent-SEVERE footgun — **this is a first-cut, NOT verified working.** Residual risks: subtle javac errors, unused-private warnings from the excisions, stub Toast threading, patch auto-discovery. **First CI build = the M1 gate**; treat WS1 as unverified until CI-green, then device login test (M1).

**Next:** trigger a CI build of `feature/gog-explore-tab`; on green, device-test M1 (`adb shell am start -n <pkg>/app.revanced.extension.gamehub.gog.GogMainActivity` → GOG login). Iterate fixes per the CI+device loop.

---

## 23. WS1 CI GREEN (2026-05-19) — both gates pass first attempt; M1 device test pending

- **Compile gate** `build_pull_request.yml` run [26111173073](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26111173073) — **success**. The ~7.5k-LOC WS1 port + Epic/Amazon decoupling + `GogManifestPatch` compile clean first pass.
- **Artifact build** `release.yml` run [26111421796](https://github.com/The412Banner/bannerhub-revanced/actions/runs/26111421796), `version=1.4.0-604-gog-pre1`, `stable=false` — **success**; 9 variant APKs (~108MB) as artifacts; "Create GitHub Release" **skipped** (correct — pre-release policy, artifact-only). No code fixes were needed (notable for a port this size in a no-local-test project).

**Status:** WS1 first-cut **builds clean**. Structural/compile risk retired. Still UNVERIFIED at runtime — M1 = on-device GOG login, user-driven (interactive WebView OAuth).

**M1 device test (next):** install `apk-alt-AnTuTu` (pkg `com.antutu.benchmark.full` — the canonical verification variant) from run 26111421796, then:
`adb shell am start -n com.antutu.benchmark.full/app.revanced.extension.gamehub.gog.GogMainActivity`
→ tap "Login with GOG" → complete GOG web login → expect the card to flip to the signed-in state (username shown). Capture `getlog com.antutu.benchmark.full` for `BannerHub`/`GogLaunchHelper`/GOG-auth traces. Pass = signed-in card + token persisted (`bh_gog_prefs/access_token`). Then M2 (View Game Library → owned list) and M3 (download a title) in the same loop.

---

## 24. M1 + M2 DEVICE-CONFIRMED (2026-05-19) — login + owned library work, first build, zero fixes

Variant **Normal-GHL** (`gamehub.lite`), build `1.4.0-604-gog-pre1` (run 26111421796), user-installed; `GogMainActivity` launched via `am start`.

- **M1 login — PASS.** Full GOG OAuth completed in `GogLoginActivity` WebView (logcat: `static-login.gog-statics.com` page load → `inputFocused`/`formSubmission` → redirect). `bh_gog_prefs` persisted: `access_token`, `refresh_token`, `user_id`, `bh_gog_expires_in=3600`, `username=The412Banner`.
- **M2 owned library — PASS.** `bh_gog_prefs` holds **20 distinct GOG game IDs** with `gog_size_*`/`gog_release_*`/`gog_gen_*` metadata — `GogGamesActivity` fetched the real owned library (`embed.gog.com/user/data/games`) + per-game detail.
- **Stability — PASS.** 0 FATAL/AndroidRuntime/NoClassDefFound/VerifyError since launch. The ~7.5k-LOC port + Epic/Amazon decoupling runs clean at runtime.

**Significance:** WS1 first-cut needed **zero code fixes** through compile, 9-APK build, and M1+M2 runtime — the faithful-lift of the proven BannerHub-3.7.x extension + the GOG-only decoupling surgery held end-to-end. Only **M3 (download+install a title)** remains for Phase 1; then Phase 2 (bridge §19 / production Profile-row WS4-P-A / P-C).

**Next:** M3 — in the GOG library, pick a small title → download → verify install to disk + the Phase-1 `GogLaunchHelper` toast ("installed; in-app launch in a later update"). User-driven (download time); I capture `getlog`+install-path verification.

---

## 25. M3 BUG FOUND+FIXED (2026-05-19) — GogManifestPatch missed the BhDownloadService <service>

User reported "download is not starting." Root cause (caught by M3 testing, exactly its purpose): `GogManifestPatch` registered the 6 **activities** but **not** `BhDownloadService`, which `extends android.app.Service` (foreground, `startForeground`, `dataSync`). An unregistered Service silently fails to start → no download. Bug in the WS1 first-cut manifest patch (§22).

**Fix:** `GogManifestPatch.kt` now also appends `<service android:name="app.revanced.extension.gamehub.gog.BhDownloadService" android:exported="false" android:foregroundServiceType="dataSync"/>` (idempotent), mirroring the proven BannerHub-3.7.x decl (`bannerhub/patches/AndroidManifest.xml:166`). No permission patch needed — base GameHub 6.0.4 manifest already declares INTERNET / FOREGROUND_SERVICE / POST_NOTIFICATIONS / FOREGROUND_SERVICE_DATA_SYNC. Verified only `BhDownloadService` needs registration (no other Service/Receiver/Provider in the ported set).

**Note:** M1/M2 still valid (login/library don't touch the service). Rebuild `1.4.0-604-gog-pre2` for M3 retest.

---

## 26. M3 PASS — PHASE 1 COMPLETE (2026-05-19)

pre2 (`1.4.0-604-gog-pre2`, run 26112532244) installed over pre1 in place (login/library persisted — still `The412Banner`, 19 cached). User downloaded **GunSlugs** (GOG id `1709371377`) via the in-app library UI (the only step needing a human tap — service is correctly `exported=false`).

**M3 verified on disk** (`getlog --ls`, durable evidence; logcat buffer had rolled past the download):
- `bh_gog_prefs`: `gog_dir_1709371377` + `gog_exe_1709371377` both set ⇒ **INSTALLED** per `GogInstallPath.java`'s own state contract.
- `/data/user/0/gamehub.lite/files/gog_games/Gunslugs/` = **86 MB, 580 files**: `gunslugs.exe` (349 696 B), `gunslugs.dat` (22.6 MB), `goggame-1709371377.hashdb`/`.info`, `_gog_manifest.json`, `config.json`, `jre/`. The GOG manifest+hashdb+bundled JRE prove the real GOG installer pipeline ran (CDN fetch → manifest → extract → install), not a stub. 0 crashes.

**Phase 1 COMPLETE — M1 (login) + M2 (owned library) + M3 (download+install) all device-confirmed.** Total Phase-1 cost: a ~7.5k-LOC faithful lift of the BannerHub-3.7.x GOG extension + GOG-only decoupling, with exactly **one** bug (missing `<service>` registration, §25) across compile→build→M1→M2→M3. The "lift the proven 3.7.x extension, don't re-port GameNative" decision (§18) is vindicated.

**Deferred Phase 2 (unchanged):** WS5 GameHub-library/launch bridge (§19, programmatic `xm7.u`), production Profile-row entry (WS4/P-A), P-C container assignment. Until then, an installed GOG game shows the Phase-1 `GogLaunchHelper` "installed — in-app launch in a later update" toast (by design). Pre-release: artifact-only.

---

## 27. WS4 STARTED — P-A is the §12-class trace, no string shortcut (2026-05-19)

WS4 = production in-app entry to `GogMainActivity` (the hub that is login pre-auth / "View Game Library" post-auth — already built+verified Phase 1). P-A = pin the Profile-screen injection anchor.

**Trace finding [CONFIRMED]:** `features_home_profile_{steam,epic,gog}_{bind,title,desc}` exist only as `"string:..."` literals wrapped by `Ltdi;-><init>(String,Set)` in the **generated Compose resource-accessor classes** `vjl`/`wjl` (e.g. `vjl:4236` for `steam_bind`) — the exact orphaned-accessor pattern as `platform_tab_gog` in `ujl` (§12). No renderer references the keys; the Profile composable consumes resolved `Lell;` objects from static slots (the `pjl.<slot>` pattern `y6d` used for the tab). `gog_{bind,title,desc}` are present-but-orphaned (scaffolded, unwired) — same as the tab string.

**Consequence:** pinning the Profile-row injection point is a **multi-pass obfuscated-Compose trace, same difficulty class as the §12 tab chain** (kg5→y6d→s6d→a5d, ~8 passes): `tdi(steam_bind)` → its `Lell;` static slot → the composable consuming that slot → the Steam/Epic bind-row construction pattern to mirror a GOG row. It is the **menu-row-injection risk tier** (proven: vibration/gpuspoof/renderer), NOT enum surgery — but it is real multi-pass RE + a new bytecode/Compose injection patch + the CI/device loop. No quick string anchor exists.

**Decision fork (same pattern as prior forks):**
- **(A) Deep P-A** — full Profile-row trace + injection. Highest-fidelity, "designed-for" placement next to Bind-Steam/Epic, reuses the scaffolded `gog_*` strings. Cost: ~§12-scale trace + injection patch + iterations.
- **(B) Interim cheap entry** — ship Phase 1 reachable *now* via a lower-effort global hook (a simpler reachable surface than the deep Profile-Compose tree), defer the polished Profile row. Gets "GOG library + downloads" into users' hands sooner; revisit (A) later.

Phase 1 stays a clean stopping point either way. No code yet for WS4.

---

## 28. PERMANENT GOG CARD — FEASIBILITY TRACE: GREEN, beats deep P-A (2026-05-19)

User idea: a permanent synthetic "GOG" card in the library grid (among imported/Steam/Epic games) that, when tapped, opens `GogMainActivity` instead of launching a Wine game. Scoped trace verdict: **feasible, lower-risk than the §27 deep P-A Profile-Compose trace, and the work is reusable toward Phase-2 WS5.**

**(a) Renders as a card — YES [CONFIRMED].** Full Room schema captured: `t_game_library_base` (`id`,`user_id`,`server_game_id`,`extension_type`,`launch_method_id`,`game_name`,`logo`/`cover_image`/`square_image`,`source_type`,… most `DEFAULT ''`) + `t_game_launch_method` (`linked_game_id`,`start_type`,…). Grid feed = `SELECT * FROM t_game_library_base WHERE extension_type = ? AND user_id = ?`. A minimal sentinel (`id="bh_gog_launcher"`, `game_name="GOG"`, `logo`→bundled `GogLogo1` asset, `server_game_id=0`, linked launch-method row) is a structurally valid card. *Impl unknowns (not blockers):* exact `extension_type` int + the bypass `user_id` (known territory — FakeAuthToken/import-flow).

**(b) Persistence — YES, strong [CONFIRMED].** Only deletes on `t_game_library_base` are single-row: `WHERE _id=?` / `WHERE id=?`. **No bulk delete, no "delete NOT IN server list" reconcile.** Server sync only enriches rows with `server_game_id>0`; a `server_game_id=0` sentinel is invisible to sync. ⇒ effectively permanent (survives launches/restarts/sync), removable only by explicit user delete — and an idempotent app-start re-seed hook makes it self-healing/un-removable. (This was the make-or-break risk → resolved.)

**(c) Launch intercept — YES, tractable [CONFIRMED feasible].** Launch dispatch concentrated in `po7.smali` (18 start_type/LaunchType refs; the card-tap→launch path). Pre-launch exe/container validation lives downstream (`b8o/csh/h8o`, `validator_error_exe_file_not_found`). Inject a sentinel short-circuit at the **launch-entry top**: `if (gameId == "bh_gog_launcher") { startActivity(GogMainActivity); return; }` — fires before any exe/container validation. Reuses P-B's `MainActivity` knowledge + the existing `MenuGameIdCapturePatch`/`BhMenuGameId` id-capture pattern. *Impl unknown:* exact `po7` launch-entry method signature (a y6d-class anchor trace, but po7 is start_type-anchored & non-orphaned ⇒ tractable).

**Why this beats deep P-A (§27):** avoids the §12-class obfuscated-Compose injection entirely (reuses GameHub's own card renderer — just seed a DB row); persistence structurally safe; the DB-seed + launch-intercept is **shared with Phase-2 WS5** (the bridge needs the same), so not throwaway like a P-A Profile trace would be. Delivers exactly the user's ask: a permanent always-there GOG entry beside the real games.

**Recommendation: adopt the permanent-card approach as WS4 instead of deep P-A.** Net WS4 = (1) extension app-start hook seeding/maintaining the sentinel row in `GameLibraryDatabase`; (2) `po7` launch-entry short-circuit patch → `GogMainActivity`; (3) reuse bundled GOG logo for the card art. Implementation traces remaining: `extension_type`+bypass-`user_id` value; `po7` launch-entry signature. No code yet.

---

## 29. WS4 BUILT (first-cut, 2026-05-19) — permanent GOG card; seed high-confidence, intercept iterate-prone

Adopted §28 permanent-card over deep P-A. Traces resolved: `user_id`="99999" (FakeUserAccount, authoritative); `extension_type` self-derived at runtime from a real row (fallback 2) — base-bump-resilient, no hardcoded obf int; full schema known; launch dispatch = `po7`.

**Code (branch `feature/gog-explore-tab`):**
- `extensions/.../gog/GogLibraryCard.java` — pure `android.database` SQLite (no Room): `ensureSeeded(ctx)` idempotent/self-healing sentinel insert into `t_game_library_base`(+`t_game_launch_method`), `id="bh_gog_launcher"`, `server_game_id=0` (§28 sync-invisible → permanent), self-derives ext_type/user_id; `maybeOpenHubById(String)`/`maybeOpenHub(ctx,Object)` launch-intercept helpers (Context via `ActivityThread.currentApplication()` since po7 has none). Fail-safe throughout.
- `patches/.../gog/GogLibraryCardPatch.kt` — (1) **seed hook** at `com.xiaoji.egggame.MainActivity.onCreate(Bundle)` (exact non-obf anchor, P-B) → `ensureSeeded(p0)`; (2) **intercept** at po7 by-id launch dispatch (structural fingerprint: static `(<self>,String,Lci3;)→Object` body refs kept-name `LaunchType`) → `maybeOpenHubById(p1)`; on hit completes the suspend fn with `kotlin.Unit;->INSTANCE` (correct "completed Unit", not null) so no Wine launch / no exe-validation.

**Verification boundary (honest, per §22):** seed hook = high-confidence (exact anchor, pure-Context, idempotent). Intercept = **iterate-prone** — obfuscated suspend method-pick + Unit-return are inspection-unverifiable; structural anchor + Unit.INSTANCE is the best first-cut. If intercept misses, the card still appears (seeder proves the bulk of WS4) and only tap-behaviour iterates — same shape as WS1's one-bug loop. The seed+intercept work is reusable toward Phase-2 WS5. Next: CI compile gate → artifact pre3 → device (card appears? tap → GOG hub?).

---

## 30. WS4 pre3 DEVICE TEST → fixes (2026-05-19): card appears (seed ✓), tap missed; pre4

pre3 (`1.4.0-604-gog-pre3`) on Normal-GHL: **card appears = seeder CONFIRMED working** (the high-confidence half). Two issues, both expected-class:
- **Blank card art** — seeded no image. Fix: seed `cover_image/cover_ver_image/logo/icon_url/square_image` = stable GOG.com logo URL; seeder now **delete-then-reinsert every app start** (self-healing — newer-build art/schema/fixes auto-apply without clearing data).
- **Tap → "No strategy found: type=Unknown, methodId=1"** (screenshot). Root cause: the `po7.G` intercept anchor was WRONG — the card tap dies in the launch-**strategy resolver** `wel.b(Lwel;Lw4c;Lci3;)Object` *before* any per-type dispatch (start_type=0 → Unknown). Re-anchored hook (2) on the **stable non-obf string `"No strategy found: type="`** (uniquely finds `wel.b`); inject at head → `maybeOpenHubFromLaunchCtx(p1)` where p1=`w4c` (holds kept-name `GameInfo` field `c`). New reflective helper `maybeOpenHubFromLaunchCtx` pulls the id obfuscation-proof (own getId() / scan fields for a `…GameInfo` or String id), sentinel → GogMainActivity + suspend-complete `Unit.INSTANCE` so the resolver/error never runs.

Seeder remains high-confidence; the re-anchored intercept is now on a *stable string* (not an obfuscated method guess) so materially more robust than pre3's. Next: compile gate → pre4 artifact → device (art shows? tap → GOG hub?).

---

## 31. WS4 pre4 DEVICE TEST → VerifyError root-caused; pre5 (2026-05-19)

pre4 (`gamehub.lite`, PID 19963, logcat 14:27:54): tapping the GOG card **crashed the app** with:

```
FATAL EXCEPTION: main
java.lang.VerifyError: Verifier rejected class wel:
  java.lang.Object wel.b(wel, w4c, ci3) failed to verify:
  [0x0] copyRes1 v0 <- result0 type=Undefined  (wel in classes4.dex)
```

**Root cause (not the long Koin/Compose stack underneath — that's just the
tap→resolution chain that loads `wel`):** `wel.b(Lwel;Lw4c;Lci3;)Object` is a
Kotlin **`suspend`** function (`Lci3;` = Continuation; continuation class
`Lsel;`). pre4's hook (2) re-anchored there and did `addInstructionsWithLabels(0, …)`.
Kotlin compiles a suspend body as a coroutine state machine whose head is a
label/dispatch and whose resume paths branch *backward into the method*. After
the index-0 prepend, the verifier finds a path reaching our `move-result v0`
**without** flowing through our `invoke-static` (suspend-resume restores regs
from the Continuation) → `result0` is `Undefined` → the whole class is
rejected → instant crash the moment the tap resolves `wel`. This is the
suspend-function sibling of the `[[feedback_revanced_trailing_label]]` /
`[[feedback_gh600_port_lessons]]` index-0 footgun. **Rule: never prepend raw
instructions at index 0 of a Kotlin suspend method.** (pre3's `po7.G` and the
strategy entry `wel.a`/`vl7.l` are *all* suspend — every coroutine anchor here
is unsafe.)

**pre5 fix (Option 1 — hook the non-suspend caller).** Decompile trace
(`gamehub_604_decompile`): the card tap is dispatched by `vl7.l(...Lci3;)`
(suspend) via synthetic trampolines `po7.K`(9315)/`po7.B`→`G0`(9325)/
`po7.A`→`F0`(9330) into GameHub's two **non-suspend** launch orchestrators on
the launch VM:

| anchor | shape | unique structural marker |
|---|---|---|
| `po7.F0(GameInfo)V` | `public final`, 321-line full path | refs `GameInfo.getHasAchievements` (+`getGameSource`) |
| `po7.G0(GameInfo)V` | `public final`, 195-line lean variant | refs `GameInfo.getSteamAppId`, **no** `getHasAchievements` |

Both reference the kept-name `LaunchType` enum + `GameInfo.getSteamAppId`.
Hook (2) now uses two **letter-free** structural fingerprints (kept-name
classes + the getHasAchievements presence/absence discriminator — base-bump
resilient, per the §27/§14 fingerprint-migration guidance) and head-guards
**both** (which branch the synthetic sentinel routes through is not
inspection-determinable):

```
invoke-static {p1}, …GogLibraryCard;->maybeOpenHubFromLaunchCtx(Ljava/lang/Object;)Z
move-result v0
if-eqz v0, :bhOrig
return-void
:bhOrig <original head: move-object/from16 v0, p0>
```

Verifier-safe: non-suspend `()V`, early `return-void`, single v0 clobber
before the original head re-inits its own regs (the proven OfflineComponentList
`gof.c` / seed-hook technique). p1 = the `GameInfo` (only declared param);
the **existing** `maybeOpenHubFromLaunchCtx` already extracts the id from a
GameInfo via `deepExtractId`→`extractId`→`getId()`, so **no extension change**.
Seeder unchanged (still CONFIRMED working). Next: compile gate → pre5 artifact
→ device (tap → GOG hub, no crash?).

**pre5 CI outcome → pre6 (2026-05-19).** pre5 (run 26118758523) built green
but `gh run --log` showed `SEVERE: "GOG library card (permanent)" failed:
app.revanced.patcher.patch.PatchException: Collection is empty.` on **all 9
variants** — the F0/G0 `firstMethod` matched zero methods, and because a
`firstMethod` miss throws, the **entire patch silently failed to apply** (no
card at all — strictly worse than pre4's crash; the playbook's documented
CI-doesn't-fail-on-per-patch-SEVERE silent-ship footgun). Smali re-verified:
F0 (po7:21832) and G0 (po7:23114) DO carry the exact refs (getSteamAppId /
`launcher/model/LaunchType;` / F0-only getHasAchievements), and other shipped
patches prove `parameterTypes` excludes the implicit `this` for instance
methods — so the fingerprint *content* was right. Suspected cause: the pre5
predicate routed its instruction scans through a **local `bodyRefs` helper
called from inside the `firstMethod {}` lambda**, unlike every working anchor
(hook 1 / pre3 / pre4) which inlines `implementation?.instructions?.any { } ==
true` with explicit opcode guards. **pre6 fixes:** (a) inline the scans with
the exact proven opcode-guarded idiom, no helper in the predicate; (b)
`parameterTypes == listOf(gameInfoT)` (the proven seed-hook form, not
`size==1 && [0]==`); (c) direct `firstMethod → getInstruction(0) →
addInstructionsWithLabels` (byte-for-byte the pre4 sequence that resolved); (d)
**each anchor wrapped in its own try/catch** so any future fingerprint miss
degrades to "card still appears, tap-behaviour iterates" (§22) instead of
nuking the whole patch — the silent-ship footgun is now structurally
neutralised. Next: compile gate → pre6 artifact → device (card appears? tap →
GOG hub, no crash?).

---

## 32. WS4 pre6 DEVICE TEST → real launch path found; pre7 (yv3.invoke) (2026-05-19)

pre6 (run 26119508288, `eed0c8a`) **GOG patch applied cleanly** (0 SEVERE, all
variants — the pre5 footgun confirmed fixed) and the card + seeder are
**CONFIRMED** on device: the "GOG" card renders in the library; tapping it
opens GameHub's game-detail dialog (title "GOG", "Launch Game"). **No crash**
(pre4 VerifyError gone). But hitting **Launch Game** shows
`No strategy found: type=Unknown, methodId=4` in the dialog.

`getlog gamehub.lite` (15:23) — decisive: the launch is an **interceptor
chain**, and our F0/G0 guard never ran (zero `GogLibraryCard` log lines):

```
CommonGame: buildLibraryInfoWithContext GOG , startType = 0
CommonGame: op=typeFilteredStrategies type=Unknown strategies=        (empty)
CommonGame: interceptor=wel order=1000 …
r5c_CommonGame: shouldPatchSteamPaths result=Failure(No strategy found: type=Unknown, methodId=4)
CommonGame: interceptor=o3h order=990 … no4 930 … nyk 920 … h8h 25 … esk 12 … lsa 12 … l8l 11 … sr0 10 … nga 0
CommonGame: op=LaunchRouter.launch type=Unknown costMs=2 result=t5c
```

**Root cause:** the GOG-card tap → game-detail dialog → "Launch Game" does NOT
go through `po7.F0/G0` (the §31 pre5/pre6 anchor was simply the wrong path —
that's a different launch surface). It goes through GameHub's **LaunchRouter**
ordered interceptor chain; `wel` (order 1000) resolves strategies first and
fails because the sentinel row has `startType=0 → LaunchType Unknown` and
`typeFilteredStrategies type=Unknown` is empty. pre6's try/catch correctly
degraded (card still works) but masked that F0/G0 matched nothing on this path.

**pre7 anchor (decompile-confirmed).** The non-obf log strings
(`buildLibraryInfoWithContext`, `shouldPatchSteamPaths`) localise it:
`buildLibraryInfoWithContext ` is a CONST_STRING in `smali_classes4/yv3.smali`,
inside `yv3.invoke()Ljava/lang/Object;` — a synthetic multiplexed
**Function0** lambda (`implements Lnw6;`, fields `a:I` selector + `b:Lt07;`;
`t07.a = …game.GameInfo`, `t07.b = LaunchMethod`). `invoke()` is **NOT a
coroutine** (no Continuation) → index-0 prepend is verifier-safe (the precise
property the §31 suspend `wel.b` lacked), and it runs on the launch path with
the game in hand (logged ~6 ms before the failure). Fingerprint = pre4-proven
stable CONST_STRING match (`"buildLibraryInfoWithContext "`) + no-arg
`invoke()Object` shape — letter-free.

**Intercept = minimal side-effect.** One instruction at index 0:
`invoke-static {p0}, GogLibraryCard;->openHubIfSentinel(Ljava/lang/Object;)V`
(p0 = the lambda; no move-result, no branch, no return change, zero register
clobber). New extension `openHubIfSentinel` does a bounded/fail-safe/identity-
cycle-guarded reflective walk (`findSentinel`, depth ≤3, stops at any
`…GameInfo` with an exact `getId()` == `bh_gog_launcher` check, skips
java/kotlin/android) from the lambda → `t07` → `GameInfo`; on the sentinel it
opens `GogMainActivity` (de-duplicated 4 s; `yv3.invoke` fires repeatedly per
tap). The original launch then proceeds and harmlessly logs "No strategy
found" *behind* the now-foregrounded GOG hub — so no return-value/abort logic
is needed and **real game launches are byte-for-byte unaffected** (id mismatch
→ immediate `false`). Seed hook (1) unchanged; hook (2) still try/catch'd
(§22). Files: `GogLibraryCard.java` (+openHubIfSentinel/findSentinel),
`GogLibraryCardPatch.kt` (hook 2 rewrite). Next: compile gate → **verify 0
SEVERE on the GOG patch** → pre7 artifact → device (tap "Launch Game" → GOG
hub opens, real games still launch?).

### 32a. pre7 device → intercept WORKS, but premature auto-open; pre8 (main-thread gate)

pre7 (run 26120382093, `8657a9b`, 0 SEVERE — gated) device: **the intercept
works** — manually (back → library → tap card → Launch Game) **opens the GOG
sign-in / library**. ✅ The yv3.invoke anchor + reflective sentinel walk +
GogMainActivity launch is correct. **But** it also opens **automatically right
after app start**, before the user can press Launch. Re-reading the §32 logcat
pins it precisely by **TID**: `buildLibraryInfoWithContext GOG` runs for the
sentinel BOTH at library precompute — **background thread** (`20830/20894`,
15:22:01, ~3 s before any tap) — and on the user's Launch press — **main/UI
thread** (`20830/20830`, 15:22:04 & :06). yv3.invoke is multiplexed and the
buildLibraryInfoWithContext case itself runs in both contexts, so case/selector
scoping can't separate them; the **thread** does, robustly (thread identity is
not obfuscated, no smali fragility).

**pre8 fix (extension-only, no patch/build-fragility change):** first line of
`openHubIfSentinel` —
`if (Looper.myLooper() != Looper.getMainLooper()) return;`. The background
library-precompute call is suppressed; the main-thread user Launch press still
fires. Card-tap→dialog open logs no buildLibraryInfoWithContext (only the
bg startup one + the main launch ones), so the dialog still opens normally and
the hub fires only on the actual Launch action. Head injection / fingerprint /
seed hook all unchanged. Files: `GogLibraryCard.java` (+main-thread guard).
Next: compile gate → grep GOG SEVERE → pre8 → device (no auto-open on start;
Launch Game → GOG hub; real games unaffected).

### 32b. pre8 device → WS4 FUNCTIONALLY COMPLETE; pre9 (orientation polish)

pre8 (run 26121495276, `b01d286`, 0 SEVERE — gated) device: **all three pass.**
No auto-open on app start (main-thread gate works); GOG card → Launch Game →
GOG sign-in/library opens; real games unaffected. **WS4 core is functionally
complete**: permanent seeded GOG card → tap → "Launch Game" → GOG hub, with
the launch-router fallthrough failing harmlessly behind it and zero impact on
real launches. Seeder + intercept + dedupe + thread-gate all confirmed.

Sole remaining nit: `GogMainActivity` (and the other GOG activities) open in
**portrait** while GameHub runs **landscape**. Cause: `GogManifestPatch`
registered them with a theme + `configChanges` but **no
`android:screenOrientation`** → `unspecified` → portrait. GameHub locks its
real content screens to `sensorLandscape` (sub-screens use `behind` to inherit,
but the GOG hub is launched in a NEW_TASK from app context — nothing below to
inherit, so `behind` would still fall to portrait). **pre9 (manifest-only):**
add `android:screenOrientation="sensorLandscape"` to all 6 GOG activities in
`GogManifestPatch` — matches GameHub's content-screen value and the user's
landscape usage; no bytecode/fingerprint risk. Next: compile gate → grep GOG
SEVERE → pre9 → device (GOG flow opens landscape, matching the app).

---

## 33. Explore-mode investigation → PIVOT to menu-row entry; pre10

pre9 device + explore-mode probe (logcat `…163030`, user in explore mode +
library): seeder ran (`GogLibraryCard: seeded sentinel`) but **zero**
`buildLibraryInfoWithContext` / GOG / library activity for our row — vs
handheld where it fires every interaction. **Conclusion: explore mode is a
separate library surface that never surfaces the local sentinel row.** Making
the card appear there = the `s6d`/`wrc` dual-enum + Compose-grid (or
server-feed) work the doc flagged as the project's highest-risk class (§12,
§13.x) — the path the card approach was chosen to avoid; it is structurally
mode-bound.

**Decision (user, 2026-05-19): PIVOT to the menu-row entry.** The design
doc's own recommended production entry (§16/§300/§319) — the BannerHub-proven
menu-row injection (vibration/gpuspoof/renderer playbook, shipped 3×) — lives
in the per-game popup, which exists in **both** handheld and explore modes.
Mode-independent, sidesteps the library surface entirely, opens the already
device-confirmed `GogMainActivity`.

**pre10 implementation:**
- `extensions/.../com/xj/winemu/gog/BhGogMenuRowClick.java` — trimmed clone of
  `BhGpuSpoofMenuRowClick` (Menu A only): `appendGogRowTo(Object)` builds an
  `Liae(Lo05 icon, "GOG", Proxy<Lpw6> click)` and appends it; click →
  `resolveTopActivity()` → `startActivity(GogMainActivity)` with
  NEW_TASK|CLEAR_TOP. Raw String label (no resolver / Unsafe / Lell), no
  per-game id (hub is global), Proxy-wrapped Function1 for the R8-rename.
- `patches/.../gog/GogMenuRowPatch.kt` — clone of GpuSpoof **Injection 1
  only**: fingerprint `Lx57;->a(Lf37;Lpo7;Lv83;I)V` (Iae 3-arg ctor +
  `Lwhl;->S:Lxrl;`), inject `invoke-static {v4},
  BhGogMenuRowClick;->appendGogRowTo(Object)V` after the last
  `Lx9d;->add(Object)Z`. No `dependsOn` (Menu A needs no resolver) → minimal
  surface. Library-tile popups (ted.f / pzc.j0) deferred — add later only if
  the More-Menu entry isn't enough.
- Seeded card + yv3 intercept **kept** (handheld second entry, harmless).
- pre9 `sensorLandscape` **reverted** (§32c) — user uses explore/portrait
  too; orientation back to `unspecified` so each GOG screen follows the
  current mode.

Risk: menu-injection is the playbook's iterate-prone class (pre7→pre17 last
time), but we are cloning a *device-confirmed* injection 1:1 and starting
with the single simplest menu. Files: `BhGogMenuRowClick.java`,
`GogMenuRowPatch.kt`, `GogManifestPatch.kt` (revert). Next: compile gate →
**grep SEVERE for BOTH `GOG menu row` and `GOG library card`** → pre10 →
device (any game → More Menu → "GOG" row → GogMainActivity, in both modes).

### 33a. pre10 device CONFIRMED → pre11 (all 3 menus, matching GPU/Renderer/Vib)

pre10 (run 26124180311, `7418f47`, 0 SEVERE — gated) device: **the "GOG"
row works** in the game-details More Menu and opens GogMainActivity. User
asks for it in the other per-game menu locations too — same set as
Renderer / GPU Spoof / Vibration (3 menus). **pre11** promotes
GogMenuRowPatch from Injection-1-only to a full 1:1 clone of
GpuSpoofMenuRowPatch:

- **Injection 2** — library-tile popup `ted.f` (7-arg, ≥4 `Lscd` ctors +
  `Lqs2;->H([Object])List`): rebuild list with an `Lscd(actionId, icon[zz4.k],
  "GOG", Proxy<Lnw6>)` row appended (raw String label, no resolver). Extension
  `appendScdRowToTedList` + `newFunction0Proxy` + `safeReturn`.
- **Injection 3** — library-list popup `Lpzc;->j0` (11-arg → List, has
  `Lx9d;->i()`): append an `Lz4e(Lell,Lnw6,int)` before the post-finalize
  return-object. `Lell` Unsafe-allocated, `Ltdi.a`="string:bh_gog_label",
  `Ltdi.b`=∅. Extension `appendLibraryPopupRow`.
- **Shared resolver** — added `"string:bh_gog_label" → "GOG"` to
  `BhMenuRowClick.maybeResolveCustomLabel` (the single `Lxd3;->l1`
  head-block owned by vibrationMenuRowPatch). `GogMenuRowPatch` now
  `dependsOn(vibrationMenuRowPatch)` so that one hook is present; **no 2nd
  l1 block** (stacked one ANR'd cold start, playbook 2026-05-17).

All three injections are byte-identical to the shipped GpuSpoof patch
(only helper-method names + the sentinel key differ), so risk is low
despite menu-injection's historical iterate-prone reputation. Files:
`GogMenuRowPatch.kt` (+Inj 2/3 +dependsOn), `BhGogMenuRowClick.java`
(+appendScdRowToTedList/appendLibraryPopupRow/newFunction0Proxy/safeReturn),
`BhMenuRowClick.java` (+GOG key). Next: compile gate → grep SEVERE for
`GOG menu row` + `GOG library card` → pre11 → device (any game → BOTH
popup menus + More Menu show "GOG" → GogMainActivity, both modes).

---

## 34. Card retired + GOG screens auto-rotate; pre12

pre11 device-confirmed the "GOG" row in all 3 per-game menus. User: (a) drop
the now-redundant seeded library card, (b) make the GOG screens auto-rotate
to fit handheld (landscape) / explore (portrait).

**(a) Card retired.** The card only ever rendered in the handheld library
surface (§33 — explore is a different surface that never queries the local
sentinel); the menu row is the mode-independent entry, so the card is dead
weight. `gogLibraryCardPatch` repurposed: the proven `MainActivity.onCreate`
anchor now calls **`ensureRemoved`** (not `ensureSeeded`) — a guarded,
idempotent DELETE of the `bh_gog_launcher` rows from
`t_game_library_base`/`t_game_launch_method`, so the card disappears on
existing (seeding-build) installs too, not just fresh ones. The **yv3.invoke
launch-intercept (old hook 2) is removed entirely** — it only served the
card; its purpose is now covered by `BhGogMenuRowClick`. Patch name kept
stable ("GOG library card (permanent)") to avoid disturbing any letter-map /
dependency wiring; `ensureSeeded` left in the extension (dead, harmless).
Net surface reduction: one fewer bytecode injection, no more reflective
launch-router walk.

**(b) Auto-rotate.** `GogManifestPatch` orientation history: pre9
`sensorLandscape` (broke explore) → pre10 `unspecified` (didn't actively
follow the mode) → **pre12 `fullSensor`** on all 6 GOG activities = free
sensor-driven rotation through all 4 orientations, ignoring the OS
auto-rotate lock so it reliably matches however the device is held in each
mode. `configChanges=orientation|screenSize|keyboardHidden` (already set)
keeps the activity from recreating on rotation → smooth in-place re-layout.

Files: `GogLibraryCardPatch.kt` (hook→ensureRemoved, hook 2 deleted, name/
desc), `GogLibraryCard.java` (+ensureRemoved), `GogManifestPatch.kt`
(fullSensor). Menu-row patch + extension unchanged. Next: compile gate →
grep SEVERE (`GOG menu row` + `GOG library card`) → pre12 → device (no card
in library; GOG screens rotate to match handheld/explore; menu row still
opens hub in all 3 menus / both modes).

### 34a. pre12 device → `fullSensor` didn't rotate; pre13 → `behind` (inherit GameHub mode)

pre12 device: APK manifest correct (verified via `aapt dump xmltree` — all
6 GOG activities have `screenOrientation=0xa = fullSensor`), no
programmatic orientation locks in the GOG Activity Java code, no theme
lock. But the user reports the GOG hub does NOT auto-rotate when opened.

**Root cause: I picked the wrong target value.** `fullSensor` = follow
the physical device sensor, not the app's mode. The §32b reasoning was
also wrong: I claimed `behind` would fall back to portrait because GOG
launches in a NEW_TASK. But `FLAG_ACTIVITY_NEW_TASK` only spawns a new
task when the target has a different `taskAffinity`. Our GOG activities
have the default taskAffinity (= the app's package, same as GameHub's
MainActivity), so `NEW_TASK` keeps them in **GameHub's same task** —
MainActivity IS the activity below ours.

GameHub's MainActivity has no manifest screenOrientation but the
mode-toggle programmatically `setRequestedOrientation()`s it (landscape
in handheld, portrait in explore). `behind` inherits that RUNTIME
orientation at launch → opening the GOG hub in handheld → landscape, in
explore → portrait. That literally is "fits whatever mode the user is
using", and unlike `fullSensor` it's mode-driven (not sensor-driven), so
it ignores OS rotate-lock interactions entirely.

**pre13 (manifest-only, zero bytecode risk):** change all 6 GOG
activities from `fullSensor` → `behind`. `configChanges` already set, so
if the mode changes while the GOG hub is open the in-place re-layout
still works smoothly. Files: `GogManifestPatch.kt`. Next: compile gate →
grep SEVERE → pre13 → device (open GOG hub in handheld = landscape; open
in explore = portrait; switching modes between opens just works).

---

## 35. WS5 A-vs-B recon — Approach A killed, Approach B greenlit (2026-05-19, post-pre13 device-pass)

Per user direction after pre13 confirmed working: investigate both candidate WS5 bridge mechanisms against 6.0.4 bytecode before committing. Results below; all decompile reads under `/data/data/com.termux/files/home/gamehub_604_decompile/smali*` against R8 map id `6a5cde6143fc8cf76f6f3a447d0fececd4794d83066e6ead7a9537e6527b057b`.

### 35.1 Approach A — mirror 5.3.5 (launcher onResume + reflection into import-dialog opener)

| Half | 6.0.4 finding | Viable? |
|---|---|---|
| Launcher onResume hook | `com.xiaoji.egggame.MainActivity.onResume()` is non-obf, 8-instruction body (`MainActivity.smali:1376–1415`), sole LAUNCHER activity (manifest). Trivial smali inject of `GogLaunchHelper.checkPendingLaunch(this)` after the `invoke-super`. | **YES — LOW risk** |
| `B3(exePath)` equivalent | **None exists.** PC-exe import is a Kotlin ViewModel `Lx3g;` whose constructor `<init>(Ljava/lang/String;)V` takes the exe path. **Only caller of that constructor is `Lvcd;` (Compose VM factory)** at `vcd.smali:826–830`, inside a `pswitch` dispatch driven by a Compose nav-graph state. There is no Activity method, no public entry, **and no Intent extra** carrying an exe path (codebase-wide grep for `putExtra("exePath"` / `"local_game_path"` / `"game_exe"` returns zero matches — only string-literal hits in `Config`/`kv0`/`mu7`/etc. which are *log/debug strings*, not Intent keys). | **NO — reachable only by faking Compose nav state** |

The 5.3.5 trick worked because `LandscapeLauncherMainActivity` *itself* exposed `B3(String)` and the launcher hook could just `getMethod("B3", String.class).invoke(activity, exe)`. The KMP rewrite moved that surface inside a Compose VM behind a factory pswitch — there is no longer an Activity-callable, exe-path-accepting entry to reflect into. Adapting Approach A to 6.0.4 is **not "find the renamed B3"** — it is **"synthesise a Compose nav-graph navigation event with a route arg from a plain Activity onResume hook"**, which is a substantial subproject of unknown scope and high R8 fragility (vcd's pswitch ordinals + the surrounding factory + Compose nav controller acquisition).

**Verdict: A is dead on 6.0.4.** What the §19 trace called "the import-trigger half" turns out to be architecturally unbridgeable from the launcher hook side. Confirmed by direct bytecode read this session; not just inferred from §19.

### 35.2 Approach B — programmatic DB insert into the Room library

Schema and accessors confirmed end-to-end byte-level. **Everything in the data path is non-obfuscated.**

**Database** (`x70.smali:5438–5532` Room DDL):

```
t_game_library_base (game row):
  _id INTEGER PK AUTOINCREMENT,
  id TEXT NOT NULL,                 -- our game ID (we generate, e.g. "gog_<gogId>")
  user_id TEXT NOT NULL,            -- current user
  server_game_id INTEGER NOT NULL,  -- 0 for non-server
  steam_app_id TEXT DEFAULT '',
  extension_type INTEGER NOT NULL,  -- (open Q: which int means GOG / PcEmulator-Gog — see 35.4)
  extension_data TEXT DEFAULT '',
  launch_method_id INTEGER NOT NULL,-- FK → t_game_launch_method.id
  game_name TEXT, cover_image TEXT, cover_ver_image TEXT,
  logo TEXT, icon_url TEXT, description TEXT,
  game_source INTEGER DEFAULT 0,
  create_time INTEGER, modify_time INTEGER, last_launch_time INTEGER,
  back_image, age_rating, ai_desc, company, developer, publisher, release_date,
  release_date_timestamp INTEGER DEFAULT -1,
  game_category, game_tag, game_lang, screenshot, video_url, game_video_list,
  square_image,
  size INTEGER DEFAULT -2,
  remark, other_desc,
  from INTEGER DEFAULT 0, source_type INTEGER DEFAULT 0, source_id TEXT DEFAULT '',
  epic_app_name TEXT, platforms TEXT, game_startup_params TEXT
  (UNIQUE index on (id, user_id))

t_game_launch_method (how-to-launch row):
  id INTEGER PK AUTOINCREMENT,
  linked_game_id TEXT NOT NULL,     -- = t_game_library_base.id
  start_type INTEGER NOT NULL,      -- LaunchType ordinal: GogGameByPcEmulator = 0xb (11)
  start_name, start_icon, start_e_icon, start_s_icon, new_icon, new_c_icon,
  is_auto_game INTEGER DEFAULT 0,
  last_use_time INTEGER,
  extension_data TEXT DEFAULT ''    -- (open Q: JSON shape for exe-path + container — see 35.4)
```

**Non-obfuscated access stack** (all classes/methods stable across base bumps):

| Layer | Symbol | Notes |
|---|---|---|
| LaunchType enum | `Lcom/xiaoji/egggame/launcher/model/LaunchType;->GogGameByPcEmulator:Lcom/xiaoji/egggame/launcher/model/LaunchType;` | `LaunchType.smali:455–472`. Ordinal = `0xb` (11), id `0x581`. Stable. |
| DB class | `Lcom/xiaoji/egggame/game/database/GameLibraryDatabase;` | abstract Room class |
| DB impl | `Lcom/xiaoji/egggame/game/database/GameLibraryDatabase_Impl;` | Room codegen; lazy DAO getters |
| DAO 1 (interface) | `Lcom/xiaoji/egggame/game/database/dao/GameLibraryBaseDao;->insert(Lcom/xiaoji/egggame/game/database/entity/GameLibraryBaseTable;Lbi3;)Ljava/lang/Object;` | **suspend** (`Lbi3;` = `kotlin.coroutines.Continuation`) |
| DAO 2 (interface) | `Lcom/xiaoji/egggame/game/database/dao/GameLaunchMethodDao;->insert(Lcom/xiaoji/egggame/game/database/entity/GameLaunchMethodTable;Lbi3;)Ljava/lang/Object;` | **suspend** |
| DAO accessors | `Lcom/xiaoji/egggame/game/database/GameLibraryDatabase;->gameLibraryBase()...;->gameLaunchMethod()...;` | virtual getters, non-obf |
| Entity 1 | `Lcom/xiaoji/egggame/game/database/entity/GameLibraryBaseTable;` | ~38-arg Kotlin data class |
| Entity 2 | `Lcom/xiaoji/egggame/game/database/entity/GameLaunchMethodTable;-><init>(JLjava/lang/String;ILjava/lang/String;…)V` | 12-arg ctor: `(long id, String linkedGameId, int startType, String startName, …, int isAutoGame, Long lastUseTime, String extensionData)` |

Multiple callers in `aw3.smali`, `dt7.smali`, `pu7.smali`, `zs7.smali`, `au7.smali`, `ot7.smali`, `ju7.smali` use the exact `GameLibraryDatabase->gameLibraryBase()/gameLaunchMethod()` accessors — pattern is well-trodden.

### 35.3 Approach B — engineering shape

```
GogLaunchHelper.triggerLaunch(activity, exePath):
  1. Compute gogId = bh_gog_prefs current selection or manifest primary task
  2. Build GameLaunchMethodTable:
       (id=0,                                              ← AUTOINCREMENT
        linkedGameId="gog_" + gogId,
        startType=11,                                      ← GogGameByPcEmulator
        startName=<basename(exePath)>,
        startIcon="" startEIcon="" startSIcon="" newIcon="" newCIcon="",
        isAutoGame=0,
        lastUseTime=null,
        extensionData=<JSON: exe path + container ref>)    ← see 35.4 open Q
  3. DAO.insert(launchMethod, Continuation) → returns inserted row id (Long)
  4. Build GameLibraryBaseTable:
       id="gog_" + gogId, user_id=<current>, server_game_id=0,
       steam_app_id="", extension_type=<GOG enum>,         ← see 35.4 open Q
       launch_method_id=<from step 3>,
       game_name=<title from GOG manifest>,
       cover_image=<cover_url from GOG manifest>,
       source_type=<GOG enum>, source_id=<gogId>,
       most other fields = "" or default
  5. DAO.insert(libBase, Continuation)
  6. activity.finish() → user returns to GameHub library, GOG title now visible
  7. (Optional) onResume hook still kept: SharedPrefs handoff lets the insert run
      on a coroutine off the GogGamesActivity main thread, with onResume just
      polling for completion. Belt-and-suspenders, not required.
```

**Java→suspend bridge**: write a small `BridgeContinuation implements kotlin.coroutines.Continuation<Object>` with `getContext() = EmptyCoroutineContext.INSTANCE` and `resumeWith(Object) = signal a latch`. Call `dao.insert(entity, bridgeContinuation)`; if return value is `Lz9c;` (COROUTINE_SUSPENDED sentinel), await the latch; otherwise the result is in-hand. Standard, ~30 LOC.

### 35.4 Three open questions before WS5 coding (NOT blockers — each is a 1-grep-sized trace)

1. **`extension_type` value for GOG.** This is an `int` column distinguishing source families. Find by reading a live row from a Steam/Epic imported game, or by grepping `extension_type` writes against `extension_type` setter calls in the import path — the constant should be a small int (0/1/2/…). The retired GogLibraryCardPatch seeder used a specific value; that value's history is in git, recover from the deleted code or DB dump.
2. **`source_type` value for GOG.** Same shape as above, parallel column.
3. **`extension_data` JSON shape.** This is where the exe path + Wine-container/prefix assignment likely lives for PC-emulator launches. Grep `extension_data` putter sites in `dt7`/`pu7`/`au7` for the JSON keys; alternatively read a live Steam/Epic row and reverse-engineer keys (same column, same launch family).

(Also: confirm DB-singleton acquisition path — `aw3`/`dt7`/`pu7` all dereference a parent object's field for the DB. The cleanest Java-side accessor is likely via Hilt's generated component — quick grep against `Hilt_App` / `*_HiltComponents` will surface a static getter. Mechanical follow-up.)

### 35.5 Verdict + recommendation

**Pick B. A is architecturally dead on 6.0.4** — the 5.3.5 `B3(exePath)` reflection target has no equivalent method to reach; the entry to the import flow is a Compose ViewModel factory dispatch (`vcd` → `new x3g(exePath)`) with no Activity-callable shortcut and no Intent path. Approach A would require synthesising a Compose nav event from outside the nav graph, which is a substantial subproject of unknown scope and high R8 fragility.

**B is in much better shape than §19 anticipated.** The §19 trace said "programmatic `xm7.u` import chain" — that was approximate; `xm7` is actually a coroutine SuspendLambda owned by `Lpo7;`, not a callable import method. But that didn't matter, because the *real* data-write surface is two non-obfuscated Room DAOs (`GameLibraryBaseDao.insert`, `GameLaunchMethodDao.insert`) with non-obfuscated entity constructors and a non-obfuscated DB class — i.e. **the most R8-stable layer in the whole APK**. Net WS5 risk **MED→LOW-MED** (down from §19 MED).

Plan:
- **WS5.0** (single session): resolve the three §35.4 open questions (likely 30–90 min decompile work; each is grep-sized).
- **WS5.1**: add `BridgeContinuation` Java helper to the extension (~30 LOC, reusable).
- **WS5.2**: body `GogLaunchHelper.triggerLaunch()` with the two-insert flow above; remove the Phase-1 toast.
- **WS5.3** (defensive): keep the `MainActivity.onResume` SharedPrefs-handoff pattern from §19 as a thread-safety belt — perform the DAO inserts from the launcher's coroutine scope (via `Lhik;`/`Lhsj;` GlobalScope-equivalent), with onResume just being a wake-up point for any deferred error toast. Optional; depends on whether `GogGamesActivity` finishing while a coroutine is mid-insert is safe.
- **WS5.4**: device test M4 — download a GOG title; on completion verify (a) row visible in GameHub library, (b) tap → Wine launches the GOG exe, (c) icon/cover render correctly.

No code yet — these are scoping notes for the next session's WS5 start.

Related memory: [[project-bannerhub-revanced-gog-ws4]], [[project-bannerhub-revanced-gog-backend-audit]], [[reference-gamehub-602-vs-604]].

---

## 36. WS5 BUILT (pre14) — programmatic DB insert + deep-link auto-launch

User-driven recon collapsed §35.4 open questions in one session:

### 36.1 Open questions resolved

1. **DB-singleton access — not needed.** Recovered the retired GogLibraryCard seeder code from git (`16734a5`); it used raw `SQLiteDatabase.openDatabase(ctx.getDatabasePath("db_game_library.db"))` with READ_WRITE flag. No Room/Hilt/Continuation/DAO indirection. Same proven pattern, ships zero new deps.
2. **`extension_type` / `user_id` — self-derive from any existing row** (`SELECT extension_type,user_id FROM t_game_library_base WHERE id<>? LIMIT 1`). Fallbacks `1` / `"99999"` (FakeUserAccount bypass id) when the library is empty. Mirrors the proven retired-seeder fallback that worked in pre4–pre11.
3. **`start_type` int for GOG — `1409` (0x581).** Verified via LaunchType.smali:455–472 (`GogGameByPcEmulator` 2nd ctor arg). NOT the ordinal `0xb` I'd originally assumed — the DB stores the `id` field (2nd ctor arg), not the ordinal. Cross-check via user's live DB row: God of War uses `start_type=1403` which is `PcEmulator.id` (0x57b). Steam/Epic/Gog form a contiguous block 1407/1408/1409 (0x57f/0x580/0x581).
4. **`extension_data` JSON shape — modeled byte-for-byte on God of War row.** User's live DB dumped (via `getlog --cat` → Python sqlite3 — `getlog --sql` unavailable on this device, no `/system/bin/sqlite3`). Shape: `{gameId, isLocalGame:true, coverImage, name, startType, gogId, exePath}`. The `exePath` field is load-bearing; the rest is cosmetic. The PcEmulatorLaunchStrategy reads this via `extension_data` JSON parse — verified by stack trace from the live capture (`x3g.m:438` → `cpb.e:88` chain calls `simulator/getLocalGameDetail` with the exePath, which is the same recognition step our GOG row sets up for).
5. **Auto-launch path — existing infrastructure.** `MainActivity.smali:134-200` already handles `app_nav_target=local_game_launch` + `app_nav_game_id=<id>` extras (line 173 `const-string "local_game_launch"`). No new patch needed — we just fire the Intent from our extension. Same internal-deep-link convention `DeepLinkActivity` uses for push notifications, so we ride a stable, proven channel.

### 36.2 Implementation (this session)

**`GogLaunchHelper.java`** rewritten from 56-line Phase-1 stub → ~200-line WS5 bridge:

| Public API | Used by |
|---|---|
| `triggerLaunch(Activity, GogGame, String exePath)` | `GogGamesActivity` (4 call sites — Add Game / Add to Launcher / install-complete dialog) |
| `triggerLaunch(Activity, String exePath, String gogId, String title, String coverUrl)` | `GogGameDetailActivity` (1 call site; detail activity stores metadata as fields, not as `GogGame`) |
| `triggerLaunch(Activity, String exePath)` | legacy ABI shim — logs+toasts; no row written (kept so a stale call can't break the build) |
| `checkPendingLaunch(Activity)` | retained no-op — the deep-link path is synchronous, no SharedPrefs handoff required |

Flow inside `triggerLaunch(activity, exePath, gogId, title, coverUrl)`:
1. Open `ctx.getDatabasePath("db_game_library.db")` RW (skip + toast if absent — "open GameHub once first").
2. Self-derive `extension_type` and `user_id` from any existing row (excluding our own gameRowId — supports re-install).
3. Build `extension_data` JSON via `JSONObject.put(...)` with manual-escape fallback for any freak failure.
4. Transactional 2-row insert (delete-first for re-install idempotency):
   - `t_game_launch_method` (linked_game_id, start_type=1409, start_name, extension_data) → grab `last_insert_rowid()` as FK.
   - `t_game_library_base` (id="gog_"+gogId, user_id, server_game_id=0 [permanent / invisible to server sync], extension_type, launch_method_id, game_name, game_source=3 [PC imported, matches user's row], source_type=0, from=0, source_id=gogId, cover/logo/icon/square URLs all = the GOG cover).
5. `Intent` to `com.xiaoji.egggame.MainActivity` with `NEW_TASK | CLEAR_TOP | SINGLE_TOP` flags and the `app_nav_target` + `app_nav_game_id` extras → `activity.finish()` to return the user to GameHub for the launch.

Fail-safe: every public-method body is wrapped in `try/catch Throwable` that toasts a short hint and logs the trace; never crashes the GOG flow. The deprecated single-arg overload is harmless (no DB write, just a warning toast).

### 36.3 What pre14 does NOT yet do (deferred)

- **Cover-art enrichment**: we set `cover_image`/`logo`/`icon_url`/`square_image` all to the same GOG cover URL. GameHub may render some cells differently when given separate cover/square art. If the library tile looks off in pre14 device test, we'll split (`cover_image` = wide banner, `square_image` = tile icon) using GOG's own per-asset URLs.
- **`game_startup_params`**: left empty. If a GOG game needs CLI args (e.g. `--no-launcher`), the user can add them in GameHub's per-game settings later, or we add a second extension_data field in pre15.
- **Wine container assignment**: the new row inherits whatever container GameHub picks for `start_type=1409` PC-emulator launches (likely the default Wine prefix). If a GOG game needs a specific container (e.g. one with `dotnet48` installed), we'll add it through GameHub's container picker — not part of WS5.
- **`onResume` polling fallback**: §35 mentioned this as a belt-and-suspenders. Not implemented in pre14 — the synchronous Intent dispatch makes it unnecessary.

### 36.4 Pre14 test plan

1. Download a GOG game in the GOG hub (or open one with a prior install — the "Add to Launcher" button is wired to `triggerLaunch` for the install-complete state too).
2. Tap "Add to Launcher" / "Add Game".
3. **Expected**: brief flash → GameHub's MainActivity comes to front → the GOG title appears in the library AND a Wine launch fires for it. If MainActivity rejects the `local_game_launch` extras for any reason (e.g. needs additional fields), the row should still be in the library and the user can tap it normally.
4. **Fallback observable**: if the deep-link fails silently, the row still exists in `t_game_library_base`/`t_game_launch_method` — verify via `getlog --cat /data/data/<pkg>/databases/db_game_library.db` then Python sqlite3.

### 36.5 Files

- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/GogLaunchHelper.java` — full rewrite
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/GogGamesActivity.java` — 4 call sites pass `game`
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/GogGameDetailActivity.java` — 1 call site passes `(exe, gameId, title, imageUrl)`
- `PROGRESS_LOG.md` — pre14 entry
- This doc — §35 (recon) + §36 (build) + status header update

## 37. pre15 — Add-to-library refresh bug (raw-write bypasses Room's InvalidationTracker)

### 37.1 The symptom

User device-test of pre14 (2026-05-19):
- ✅ Download a GOG game → "Add to library" tap → game installs.
- ✅ The row IS in `t_game_library_base` + `t_game_launch_method` (verified by the fact that on next app launch, the game appears in the library and launches via the Wine pipeline).
- ✅ Wine launch via `LaunchType.GogGameByPcEmulator` works.
- ❌ **The library UI does NOT reflect the new game until the app is closed and reopened.** Returning from the Wine container, navigating around, pulling-to-refresh — none of it makes the new game appear in the running GameHub process.

### 37.2 Root cause — Room/SQLite connection split

GameHub's library is **Room-backed**: `Lcom/xiaoji/egggame/game/database/GameLibraryDatabase;` (un-obfuscated `@Database` class) extends R8-renamed `Llyi;` which is `androidx.room.RoomDatabase` (confirmed by its preserved `getInvalidationTracker()Lhsa;` method and abstract `createInvalidationTracker()` + `internalInitInvalidationTracker(...)`). The library Compose surface observes `Flow`/`LiveData` from `GameLibraryBaseDao` / `GameLaunchMethodDao` via Hilt-scoped repository singletons (~10+ smali holders for the DB instance: `Lvu7;`, `Lmp7;`, `Lt17;`, `Ldw3;`, `Laqc;`, `Lmt7;`, `Lvpc;`, …).

`GogLaunchHelper.registerInLibrary()` (pre14) uses `SQLiteDatabase.openDatabase(..., OPEN_READWRITE)` directly. This works because:
- ✅ It hits the same file (`/data/data/<pkg>/databases/db_game_library.db`).
- ✅ The 2 INSERTs commit cleanly.
- ✅ The SQL triggers Room installed on observed tables fire and write into `room_table_modification_log` — **triggers are SQL-level and fire for any connection that writes to the observed table**, not just Room's. So the log is dirty after our write.

BUT — Room's `InvalidationTracker` (`Lhsa;`) only **polls** `room_table_modification_log` when Room itself initiates a write (it polls at the tail of each Room-driven transaction). Our raw write happens on a SEPARATE SQLite connection that Room doesn't know about, so it never triggers the poll. Observers (`Flow`/`LiveData`) stay on their pre-write snapshot. The library UI is genuinely stale until something tells the tracker to re-check.

Cold restart works because the new process re-builds Room → first DAO query reads from disk → Flow emits the new data.

(Note: even if Room had been configured with `setMultiInstanceInvalidation()`, that uses a separate `MultiInstanceInvalidationService` IPC across processes — useless within the same process across two connections. There's no setting that makes Room's tracker observe an arbitrary SQLite-API write.)

### 37.3 Approach picked — reflective tracker kick

After our raw write, reach a live `GameLibraryDatabase` instance and call its `InvalidationTracker.refresh()`. That makes Room poll the modification log, see the dirty tables, and fan out observer notifications → the library `Flow`/`LiveData` re-emit → Compose recomposes → UI updates.

Alternatives considered and rejected:
- **(a) Write through Room instead of raw SQLite.** Requires reaching the DAO singletons via Hilt (no public accessor), constructing R8-renamed entity types reflectively, AND solving the Kotlin `suspend` Continuation marshalling for the DAO insert. Same reach-the-instance problem with way more code.
- **(b) Spawn our own second `RoomDatabase` against the same file.** Room expressly disallows two instances for the same file in the same process; even if allowed, `InvalidationTracker` is per-instance — refreshing ours doesn't refresh GameHub's.
- **(c) Patch MainActivity to re-query the library when receiving our `local_game_launch` intent.** Adds bytecode-level fragility (R8-renamed Compose ViewModel methods) for what's a one-line behavior change. Worse: we'd need to find a public "reload library" entry that GameHub itself uses — finding it has the same Hilt-walk cost as our chosen approach.
- **(d) `Process.killProcess(myPid())` after launching the Wine container.** Works (cold restart on return), but it kills the user's running GameHub state and is hostile UX.
- **(e) Hilt `EntryPoints.get(...)` for the DB.** Needs a compile-time EntryPoint interface that the Hilt processor generated bindings for. We have no Hilt processor in our extension build — adding one is real engineering and the EntryPoint name would need to match a binding GameHub's Hilt graph already exposes.

### 37.4 Implementation — `RoomRefreshHelper.refreshLibrary(ctx)`

New file: `extensions/gamehub/.../gog/RoomRefreshHelper.java` (~180 LOC, pure JDK reflection + `android.util.Log`, zero new deps).

**Resolution path** (one-shot then cached on first success):
1. **Find a live `GameLibraryDatabase`.** BFS-walk instance fields starting at `Application` (Hilt's `SingletonComponentImpl` is held as a field somewhere in there; we don't care about its renamed class name). At each visited object, check `class.getSimpleName()` against `"GameLibraryDatabase"` (or `..._Impl`) — the simple name survives R8 because Room can't obfuscate `@Database` classes (their fully-qualified name is baked into the generated schema hash and the `*_Impl` lookup). Hit returns the DB instance. Container-aware: walks into `Iterable`, `Map`, and Object arrays (Hilt providers commonly hold things in `LinkedHashMap` or `Lazy[]`). Budget-capped at 4000 visited refs.
2. **Get the tracker.** Reflect for the method named `getInvalidationTracker` on the DB instance's class chain (walks superclasses — the method lives on `Llyi;` = `RoomDatabase`, not on `GameLibraryDatabase` directly). The method name survives R8 because the generated `GameLibraryDatabase_Impl` overrides it.
3. **Find `refresh()` on the tracker.** R8 renamed it to `a()` on `Lhsa;`, but the signature is unique: among the tracker's *declared* methods (not inherited), only one has zero parameters, void return type, and isn't static/synthetic/bridge. That's `refresh()`. We don't depend on the name `"a"` — we filter by signature, which is stable across R8 mappings.
4. **Cache** the tracker reference + the resolved `Method` on success. Subsequent calls skip the walk entirely.

**Wire-in** (`GogLaunchHelper.triggerLaunch`):
```java
registerInLibrary(activity, dbFile, gameRowId, gogId, safeName, safeCover, exePath);
RoomRefreshHelper.refreshLibrary(activity);  // §37
dispatchLaunch(activity, gameRowId);
activity.finish();
```

**Fail-safe.** Any reflection miss — DB not in the graph, method not found, invocation throws — logs a `BannerHub` warn and returns. Behavior degrades to pre14 (restart still works). The cached refs are cleared on any future invoke failure (e.g. if the singleton got GC'd in a backgrounded process), so the next call re-walks.

### 37.5 Why this works across R8 mappings

The fragility is in the resolution step, not the call. Once `(tracker, refresh)` is cached, the `refresh.invoke(tracker)` is a normal direct call — same JIT path as if we'd called `Llyi;->getInvalidationTracker()` followed by `Lhsa;->a()V` directly in smali.

The lookups are anchored on **names that R8 demonstrably can't rename**:
- `GameLibraryDatabase` simple name — preserved (Room schema hash, `*_Impl` name lookup).
- `getInvalidationTracker` — preserved (`_Impl` override).
- `refresh()` no-arg void — identified by signature, not name; the signature is unique on `Lhsa;` (the only other declared method is `b(Continuation)Object`).

If a future GameHub release adds a second no-arg void method to InvalidationTracker, the signature filter could pick the wrong one. Mitigation if that ever happens: walk Room's call sites for `Lhsa;->X()V` and switch to matching the most-called one (real `refresh()` has many internal call sites; any new method would have few).

### 37.6 Test plan

1. Install pre15 alt-AnTuTu APK (`com.antutu.benchmark.full`).
2. Download a GOG game.
3. Tap "Add to library".
4. **WITHOUT closing the app**, navigate to the GameHub library tab.
5. **Pass**: the GOG game is present in the library tile grid immediately.
6. **Fail-fallback**: confirm row in DB via `getlog sql /data/data/com.antutu.benchmark.full/databases/db_game_library.db "SELECT id,game_name FROM t_game_library_base WHERE id LIKE 'gog_%'"` — if row IS there but library is still stale, reflection found something different than expected; pull `getlog com.antutu.benchmark.full | grep RoomRefresh` for the resolved class names.

### 37.7 Files

- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/RoomRefreshHelper.java` — NEW
- `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/gog/GogLaunchHelper.java` — +1 call site, +4 lines comment
- `PROGRESS_LOG.md` — pre15 entry
- This doc — §37 (this section) + status header update

## 38. pre16 + pre17 — UX scope: GOG hub is library-management-only, never launches games

### 38.1 The spec (user, 2026-05-20)

Post-pre15 device-confirm: "**No buttons inside the GOG game library after a game is downloaded launch the game. Only "Add to GameHub library." Launching the game is the user's job, done manually from the GameHub library, like any other game.**"

Two-pass implementation:

**pre16** — split add-only from add+launch in `GogLaunchHelper`. New `addToLibrary(...)` = `registerInLibrary` + `RoomRefreshHelper.refreshLibrary` + toast `Added "<name>" to library` (no `dispatchLaunch`, no `activity.finish()`). `triggerLaunch` kept for the Launch button. The 4 "Add Game" / "Add to Launcher" buttons in `GogGamesActivity` re-pointed to `addToLibrary`. *Partial — left the `GogGameDetailActivity` green "Launch" button alive.*

**pre17 (this section)** — finish the job. Remove all launching from GOG screens.

### 38.2 Why no in-GOG launch (rationale captured for memory)

1. **Mental-model clarity.** "Launch" inside a GOG-themed screen is genuinely ambiguous — is it Galaxy's launcher? The .exe directly under Wine? The GameHub library tile pipeline? Removing the button removes the question.
2. **Single launch surface.** Every game in GameHub's library — Steam, Epic, GOG, Amazon, plain PC import — should launch through the SAME library-tile UI. Anything else fragments the user's habit.
3. **Less code, fewer failure modes.** The auto-launch path used a deep-link Intent to `MainActivity` with `app_nav_target=local_game_launch + app_nav_game_id=<row>`. Even though `MainActivity` does handle this case (smali :173, also used by `DeepLinkActivity`), an intent-based launch is one more thing that can fail or change shape between GameHub releases. The library-tile path is the path GameHub itself maintains.
4. **The §37 fix made auto-launch unnecessary anyway.** Pre15 made the library refresh in-session, so the added GOG game is *immediately visible* in the library tab. The user doesn't have to hunt or restart to find it — they just navigate to the library tab and tap it. The pre14 auto-launch was a convenience that pre-§37 was masking a missing refresh; once refresh worked, the auto-launch was redundant *and* surface-fragmenting.

### 38.3 Changes (pre17)

- `GogGameDetailActivity.java` line 322–327: button label "Launch" → "Add to Library"; `triggerLaunch(this, exe, gameId, title, imageUrl)` → `addToLibrary(this, exe, gameId, title, imageUrl)`. The field name `launchBtn` is left as-is — the visibility/enable logic at lines 110, 427, 573 still applies as written ("shown only when installed", "disabled while downloading", which are correct semantics for the new Add button too). Renaming is pure churn.
- `GogLaunchHelper.java` — strip dead code: `triggerLaunch(Activity, String, String, String, String)`, the 2-arg legacy ABI stub `triggerLaunch(Activity, String)`, `dispatchLaunch(Activity, String)`, the Phase-1 no-op `checkPendingLaunch(Activity)`. Drop the unused `android.content.Intent` import. Rewrite the file-level Javadoc — the prior "fires an Intent to MainActivity / auto-launches" description was stale.
- The 4-arg `addToLibrary(Activity, GogGame, String)` convenience overload + the 5-arg `addToLibrary(Activity, String, String, String, String)` full form are the only public entry points for WS5 now.

### 38.4 Verification

- Compile gate green.
- Grep SEVERE in run log = 0.
- Device check: download a GOG game → both `GogGamesActivity` (Add Game / Add to Launcher dialog buttons) and `GogGameDetailActivity` (the green button now labelled "Add to Library") → only outcome is a toast `Added "<name>" to library`, no Wine launch fires, user stays on the GOG screen.
- Library tab: the game tile appears immediately (§37). Tapping it launches via the existing `LaunchType.GogGameByPcEmulator` GameHub pipeline.

### 38.5 Files (pre17)

- `extensions/.../gog/GogLaunchHelper.java` — −70 LOC dead launch code, +file-Javadoc rewrite, drop `Intent` import
- `extensions/.../gog/GogGameDetailActivity.java` — 1-line button relabel + 1-line call switch
- `PROGRESS_LOG.md` — pre17 entry
- This doc — §38

---

## 39. pre20 — REORDER_TO_FRONT MainActivity nudge for in-session library refresh

### 39.1 Symptom (post-pre18 device report)

User installs pre18, opens GOG, picks a downloaded game, taps **Add to Library** → toast `Added "<name>" to library` fires, DB row IS written (`getlog gamehub.lite` shows `GogLaunchHelper: registered gog_<id>`), but the **GameHub library still does NOT show the game until the app is closed and reopened**. pre15 §37 looked like it had fixed this. pre18 it's back.

### 39.2 pre19 diagnostic build

Hypothesis at the time: either (a) the `RoomRefreshHelper` reflection has been silently failing all along and pre15 only "worked" because it ALSO dispatched a deep-link `Intent` (auto-launch path, killed in §38), OR (b) `tracker.refresh()` plus the recomposition kick work together, but neither works alone.

To find out: pre19 (`bca1cac`, run 26137182820 ✅) added a `diagToast` helper in `RoomRefreshHelper` mirroring every decision branch to both `Log.i("BannerHub", "RR-TOAST: ...")` and a main-thread `Toast.LENGTH_LONG`. Branches: walk start, DB not reachable, DB found = <classname>, getInvalidationTracker not found, get-call failed, tracker = <class>, no no-arg void method, picked method <name>(), invoke OK / invoke FAILED.

### 39.3 Device test results — hypothesis (a) ruled out

`getlog gamehub.lite | grep -E 'BannerHub|RoomRefresh|RR-TOAST'`:

```
22:39:58.096  GogLaunchHelper: registered gog_1679659438 (lm=3 user=99999 ext=1)
22:39:58.098  RR-TOAST: RR: walk start
22:39:58.217  RR-TOAST: RR: DB found = GameLibraryDatabase_Impl
22:39:58.217  RR-TOAST: RR: tracker = hsa
22:39:58.217  RoomRefresh: resolved tracker=hsa refresh=a
22:39:58.217  RR-TOAST: RR: picked method a() on hsa
22:39:58.217  RoomRefresh: notified Room InvalidationTracker
22:39:58.217  RR-TOAST: RR: invoke OK on a
```

Every branch succeeded in ~120 ms. The Room codegen class (`GameLibraryDatabase_Impl`) was reached via BFS field walk from the Application graph, `getInvalidationTracker()` resolved on its supertype, the returned tracker (`Lhsa;`) had its no-arg void method picked (`a()` = R8-renamed `refresh()`), and `a.invoke(tracker)` did not throw. The reflection chain is 100% healthy. Hypothesis (a) ruled out.

### 39.4 Why `a()` alone is insufficient

Hypothesis (b) confirmed: invoking `Hsa.a()` (`InvalidationTracker.refresh()` / `refreshVersionsAsync()`) is necessary but not sufficient to fire observer notifications for an externally-written row.

Mechanism (from Room internals + behavior observed):

1. `a()` enqueues a scan of `room_table_modification_log` via Room's `mQueryExecutor`. The async runnable opens (or reuses) a Room-side connection and reads `room_table_modification_log` to learn which tables changed since the last scan.
2. Our raw `SQLiteDatabase.openDatabase` write committed two row inserts (`t_game_library_base` + `t_game_launch_method`); Room's installed triggers fired at the SQL level (triggers run for any writer on the file) → log rows for both tables ARE present.
3. **But:** the tracker's `notifyObserversByTableNames` step only emits if a per-table **version counter** (`mTableVersions[]`, internal to the tracker, in-process) has actually been incremented vs. the cached snapshot. That counter is bumped only by Room's own write paths (`RoomDatabase.runInTransaction`, generated DAO write methods).
4. Our trigger fires write the log row, but nothing in our path bumps `mTableVersions[]`. The async scan sees log activity, finds no version delta vs. its own state, exits without calling observers. Library `Flow`/`LiveData` stays silent.

This explains why pre15 *looked* like it worked: pre15 ALSO dispatched `Intent app_nav_target=local_game_launch` to MainActivity. That deep-link path made MainActivity recompose, which re-collected the library `Flow` from Room with a fresh query that bypassed the version-counter shortcut and saw the new row. The tracker call was decorative.

### 39.5 Fix — recomposition kick, no auto-launch

Restore the recomposition mechanism, sans the auto-launch payload that §38 explicitly killed. New helper in `GogLaunchHelper`:

```java
private static void dispatchLibraryRefreshNudge(Activity activity) {
    try {
        Intent intent = new Intent();
        intent.setClassName(activity.getPackageName(), "com.xiaoji.egggame.MainActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.putExtra("bh_refresh_only", true);
        activity.startActivity(intent);
        Log.i(TAG, "GogLaunchHelper: dispatched library-refresh nudge to MainActivity");
    } catch (Throwable t) {
        Log.w(TAG, "GogLaunchHelper: refresh-nudge dispatch failed (non-fatal)", t);
    }
}
```

Key design choices:

- **`FLAG_ACTIVITY_REORDER_TO_FRONT`** (not `CLEAR_TOP|NEW_TASK`): brings MainActivity to the top of the existing task **without popping intermediate activities**. The GOG screens stay on the back stack — the user backs out through them and lands on a fresh library.
- **No `app_nav_target=local_game_launch` extra**: this was pre15's auto-launch path that §38 retired. Without that key, MainActivity resumes normally — no Wine container starts, no game window appears.
- **`bh_refresh_only=true` marker extra**: nothing reads this in the bytecode. It's a breadcrumb for future logcat/debugger sessions to confirm that an Intent originated from this helper vs. user navigation.
- **No `activity.finish()`**: the GOG activity that initiated the Add stays alive on the back stack. User can keep adding more games from the same screen if they want; the back navigation is intuitive.
- Wired in `addToLibrary` after `RoomRefreshHelper.refreshLibrary(activity)`. The tracker call stays in — it's harmless, and may help cases where the host's `Flow` is wired to invalidations rather than full recomposition.

### 39.6 Behavior the user will see

1. Tap **Add to Library** in GOG.
2. Brief flicker: MainActivity surfaces on top (library tab is whichever was last viewed; if it's the library tab, the new tile is visible immediately).
3. Toast `Added "<name>" to library` fires.
4. Back-press → returns to the previous GOG activity (`GogGameDetailActivity` / `GogGamesActivity`), where the user can continue browsing.
5. When the user backs out all the way to MainActivity, the library is already fresh. No close + reopen.

### 39.7 pre19 cleanup

The `diagToast` machinery in `RoomRefreshHelper` is stripped in the same commit:

- Removed: `diagToast(Context, String)` method, all 9 call sites, `android.os.Handler` / `android.os.Looper` / `android.widget.Toast` imports.
- Kept: every `Log.i(TAG, ...)` / `Log.w(TAG, ...)` line — future debugging via `getlog gamehub.lite | grep RoomRefresh` still gets the same info, minus the toast spam.

If the recomposition kick stops working on a future GameHub base (e.g. MainActivity adopts `singleInstance` or a different default launch mode that ignores `REORDER_TO_FRONT`), reintroduce a temporary diag along the same lines — or check the logcat for `dispatched library-refresh nudge to MainActivity` to confirm the Intent was sent.

### 39.8 Files (pre20)

- `extensions/.../gog/GogLaunchHelper.java` — +`android.content.Intent` import, +`dispatchLibraryRefreshNudge` helper (~16 LOC), +1 call site in `addToLibrary` between `RoomRefreshHelper.refreshLibrary` and the success toast, file-level Javadoc gains a §39 paragraph
- `extensions/.../gog/RoomRefreshHelper.java` — −`diagToast` method, −9 diag call sites, −3 imports (Handler / Looper / Toast), Log.i/Log.w in each branch retained
- `PROGRESS_LOG.md` — pre20 entry
- This doc — §39

## 42. "Own Explore screen" — BannerHub-owned discovery surface (SCOPE, 2026-05-29)

**Premise:** GameHub's Explore tab is a server-driven discovery feed from xiaoji's backend (`gamehub.xiaoji.com`/`xgp.xiaoji.com`/`clientgsw.vgabc.com`); we do NOT control it. Rather than stub/forge that feed (HTTP interception + RE the feed JSON schema + dead-card problem — see [[reference_gamehub_explore_tab_server_feed]]), **hijack the unused Explore bottom-nav tab to open our own classic-Java screen**, populated from content we own, cards routing to our own handlers. Deliberately avoids all 3 high-risk classes: no Compose authoring (our extension has no Compose compiler plugin), no obfuscated-Kotlin enum surgery, no feed-schema RE.

### 42.1 Verified structure
- Bottom nav = enum `ja` (`sources/defpackage/ja.java`), 6 tabs: `Global(0)`, **`Explore(1)→h9.a`**, `Play(2)`, `Leaderboard(3)`, `Library(4)→h9.d`, `Profile(5)`. Screen-key enum = `h9` (a=Explore … e=Profile).
- Tab-selection dispatch exists: string `home_tab_selection_request`, handlers in `tcn/av9/y80/o77.java`.
- We already ship classic-Java `android.app.Activity` screens (the GOG activities, device-confirmed) registered via a `resourcePatch` injecting `<activity>` nodes into the merged manifest (`GogManifestPatch`). No Compose plugin required.

### 42.2 Design (v1, user-locked 2026-05-29)
1. **Entry = full hijack of Explore tab (smali).** Intercept tab-selection where ordinal 1 / `h9.a` is chosen → `startActivity(BannerExploreActivity)` + consume the event so the NavHost never renders xiaoji's Explore. Mode-independent (handheld + explore), per the shipped menu-row click-interception playbook. **Full hijack** chosen — no fallback to xiaoji's feed. Reuses the existing Explore icon (`ic_nav_explore`) + label (`features_home_nav_explore`).
   - Rejected: adding a NEW tab → `ja`+`h9` enum extension + NavHost Compose injection (§12.7 dual-enum nightmare).
2. **Screen = `BannerExploreActivity`** — classic Java + programmatic `RecyclerView` rails (GOG-screen pattern). exported=false, registered like the GOG activities.
3. **Content = bundled local JSON manifest** (in-APK) → fully offline, zero network. (Future v2 = a `bannerhub-api` `getExploreFeed` endpoint reusing the worker+Pages mechanism — NOT v1.)
4. **v1 rails = GOG only.** Single rail; card → `GogMainActivity` (reuse `BhGogMenuRowClick.resolveTopActivity`/startActivity). Cards never deep-link xiaoji game-detail (no dead grid).
5. **Manifest:** add `BannerExploreActivity` to a manifest patch (new `ExploreManifestPatch` or fold into Gog's), exported=false.

### 42.3 Effort / risk
- Tab-click interception (smali): MODERATE — the one real RE risk = fingerprinting the ordinal→navigate seam in `tcn/av9/y80/o77`; iterate-prone but proven class (menu-row playbook).
- `BannerExploreActivity` + RecyclerView: LOW (pure Java).
- Bundled JSON: LOW.
- GOG card→`GogMainActivity` routing: LOW (reuse existing helper).

### 42.4 SPIKE DONE (2026-05-29) — seam found, clean

`home_tab_selection_request` (handlers `tcn/av9/y80/o77`) turned out to be a SECONDARY nav-result channel (cross-screen "go to tab X" via the `gme` navigator), NOT the primary tap. The real bottom-nav controller is **`w1a.java`** (extends ViewModel `pd1`, smali_classes5) — the only class consuming both tab + screen state.

**Tab enum = `yw9`** (NOT `ja`/`h9` for clicks): `HOME(0)`, `PLAY(1)`, `LEADERBOARD(2)`, `LIBRARY(3)`, `PROFILE(4)`. **The "Explore" bar item = `yw9.a` (HOME, ordinal 0)** — confirmed by `w1a.n()`'s analytics ladder mapping ordinal 0 → `"nav_click" nav_item="explore"`. (`ja`/`h9` are the static tab *descriptors*; `yw9` is the live selected-tab enum. The Explore label maps to the HOME screen.)

**Flow:** UI tap → `n(r1a)` handles a `q1a` (`SelectTab(tab=yw9)`, `q1a.a`=the tab) → logs `nav_click` → calls **`q(yw9)`**. `q(Lyw9;)V` (w1a:197) updates the nav `StateFlow` (`akk` `this.n`, CAS loop) → Compose NavHost swaps screen, then `o(yw9)`.

**🎯 INJECT POINT = top of `w1a.q(Lyw9;)V`**, after `yw9Var.getClass()`:
```
if (yw9Var == yw9.a) {            // ordinal 0 = HOME = the Explore bar item
    BhExploreTabClick.open();     // resolveTopActivity → startActivity(BannerExploreActivity)
    return;                       // skip the StateFlow tab-switch
}
```

**Why `q()` (not `n()`):**
- **Mode-independent for free** — `w1a` is a SINGLE shared VM for both modes; its ctor builds both tab orderings (`[HOME,PLAY,LEADERBOARD,LIBRARY,PROFILE]` portrait/explore + `[LIBRARY,PLAY,HOME,LEADERBOARD,PROFILE]` handheld). One seam = both modes (solves the §33 mode-split).
- **Catches every route** — UI tap (`n`→`q`) AND programmatic deep-links (`zu9.java:108` `home_tab_selection_request` handler routes non-library targets via `w1aVar.q(yw9)`) both converge on `q()`.
- **No startup misfire** — the ctor seeds the default tab directly into `n1a` state, NOT via `q()`, so `q()` only runs on real navigation.
- **No Context needed** — `w1a` is a ViewModel; reuse `BhGogMenuRowClick.resolveTopActivity()` ActivityThread walk for `startActivity`.

**Fingerprint (`ExploreTabHijackPatch`):** `w1a`/`q` names are R8-volatile → anchor via `w1a.n(r1a)`'s stable string ladder `"explore"/"play"/"rank"/"library"/"me"` + `"nav_click"` to locate `w1a`; target the `(Lyw9;)V` method `n` calls after the `nav_click` log (= `q`, recognizable by the `"main_menu"` literal + `yw9.d` compare + the `akk.i(...)` CAS loop). Enum ordinal 0 stable (`$VALUES` order fixed).

**Risk: LOW–MODERATE** (lower than scoped) — one existing method, `invoke-static` + guard + `return-void`, no new type refs (classes5 has headroom), no enum surgery, no Compose. Caveat: early-return leaves the bar highlight on the prior tab while our Activity is on top (acceptable for full hijack). **Spike clears the build — next is implementation (BannerExploreActivity + ExploreTabHijackPatch + bundled GOG-rail JSON).**

### 42.5 Files

**IMPLEMENTED 2026-05-29 (v1, not yet built):**
- `extensions/.../com/xj/winemu/explore/BhExploreTabClick.java` — smali-callable `maybeHijack(Ljava/lang/Object;)Z`: `instanceof Enum` + `ordinal()==0` (HOME/Explore) → `resolveTopActivity()` (ActivityThread walk, cloned from BhGogMenuRowClick) → `startActivity(BannerExploreActivity)`; returns true if opened, **false on anything else → native Explore (fail-safe, never crashes nav)**.
- `extensions/.../app/revanced/extension/gamehub/explore/BannerExploreActivity.java` — classic Java screen (ScrollView + per-rail HorizontalScrollView of cards, dark theme `#0D0D0D`, purple accent). No Compose.
- `…/explore/BhExploreManifest.java` — JSON model (`Rail`/`Card`) + `load(ctx)`: tries asset `bh_explore.json` (not shipped in v1) then falls back to `BUNDLED_JSON` constant (GOG rail only). The JSON wire format is the v2 contract.
- `…/explore/BhExploreActions.java` — `dispatch(activity, action, arg)`: `gog`→GogMainActivity, `url`→ACTION_VIEW, else "Coming soon" toast.
- `patches/.../gamehub/explore/ExploreTabHijackPatch.kt` — bytecodePatch. Fingerprints `q(Lyw9;)V` via `parameterTypes==["Lyw9;"] && returnType=="V" && const-string "main_menu"`. Injects at head:
  `move-object/from16 v0, p1` / `invoke-static {v0}, …BhExploreTabClick;->maybeHijack(…)Z` / `move-result v0` / `if-eqz v0, :continue` / `return-void` / `:continue`(=orig first instr via `ExternalLabel`). `from16` dodges the high-register trap; v0 is a free local in q's CAS-loop body.
- `patches/.../gamehub/explore/ExploreManifestPatch.kt` — resourcePatch, registers `BannerExploreActivity` (exported=false, `behind` orientation per GogManifest §34 rationale, configChanges).

Auto-discovered (patcher 22.0.0, no central registry). FQNs/sig verified consistent across the Java↔Kotlin boundary. **NEXT = CI build (`feature/gog-explore-tab`) → device test: tap Explore tab in both handheld + explore modes → our screen opens; GOG card → GogMainActivity; verify cold-start lands on normal home (no hijack misfire).**
- This doc — §42
