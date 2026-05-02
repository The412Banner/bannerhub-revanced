# Component Manager Port — GameHub 6.0 Design Report

Status: design doc, not yet implemented. Purpose: capture the verified component-lifecycle of GameHub 6.0 (`com.xiaoji.egggame`), the architecture decision to keep manager-injected components separate from the official registry, and the patch surface required to wire it all together.

Verified against a live install (`com.mihoyo.genshinimpact` Genshin variant, 13 components downloaded / 4 extracted) on 2026-05-01. Smali references taken from the 6.0 apktool decompile at `/data/data/com.termux/files/home/gamehub-6.0-decompile/`.

---

## 1. Verified storage layout

| Stage | Path | Notes |
| --- | --- | --- |
| Master registry | `shared_prefs/sp_winemu_unified_resources.xml` | Single SharedPreferences file, ~426 KB on a moderately-used install. One entry per known component, key `COMPONENT:<name>`, value is JSON metadata. Populated by `ComponentStrategy.fetchRemoteList` from the API. |
| Downloaded archives | `files/xj_winemu/xj_downloads/component/<ComponentName>/` | Each component gets its own subdirectory. Contains the downloaded `.tzst` (compressed tarball) or `.yml` (manifest-only deps like vcredist, VulkanRT). Sibling: `xj_winemu/xj_downloads/env/` for environment layers. |
| Extracted runtime | `files/usr/home/components/<ComponentName>/` | Decompressed component, ready for the wine runtime to use. Lazy — only populated when a game launch needs it. Asymmetry observed: 13 downloaded vs 4 extracted. |
| Per-game settings | `shared_prefs/pc_g_setting<server_game_id>.xml` | One file per imported game (`pc_g_setting49908.xml` for Genshin server_game_id 49908). Records the user's component picks for that game (which GPU driver, which DXVK version, etc.) plus other launch params. |
| Per-game scheme template | `files/xj_winemu/pc_game_setting_schemes/index.json` | Metadata template defining what fields a per-game XML should hold. Shared across all games. |
| Pre-clear / persistent | `shared_prefs/pc_g_setting.xml`, `shared_prefs/pc_g_setting_not_clear.xml` | Defaults and persistent flags. |

---

## 2. Component metadata schema

Each entry in `sp_winemu_unified_resources.xml` is keyed `COMPONENT:<name>` with this JSON value:

```jsonc
{
  "category": "COMPONENT",
  "depInfo": null,
  "entry": {
    "base": null,
    "blurb": "",
    "displayName": "",
    "downloadUrl": "https://zlyer-cdn-comps-en.bigeyes.com/ux-landscape/pc_zst/<hash>/<file>",
    "fileMd5": "<32-hex md5>",
    "fileName": "<ComponentName>.tzst",          // or .yml for dep-manifest-only
    "fileSize": 24981682,
    "fileType": 4,
    "framework": "",
    "frameworkType": "",
    "id": 267,                                    // server-side numeric id
    "isSteam": 0,
    "logo": "https://cdn-img-uxdl.youwo.com/...",
    "name": "<ComponentName>",
    "state": "None",                              // None | Downloaded | <extracted-state TBD>
    "status": 0,                                  // 0 = clean, 1 = some active state
    "subData": null,
    "type": 3,                                    // see Type table below
    "upgradeMsg": "",
    "version": "1.1.0",
    "versionCode": 1937
  },
  "isBase": false,
  "isDep": false,                                 // true = required-runtime dep, false = user-selectable
  "name": "<ComponentName>",
  "state": "None",                                // mirrors entry.state at the top level
  "version": "1.1.0"
}
```

### Component types (the `type` int)

| `type` | Category | Examples |
| --- | --- | --- |
| 2 | GPU driver | Turnip variants (`Turnip_v26.2.0_R1`, `turnip_v25.2.0_R4_mem`), `qcom-842.1`, `8Elite-800.51` |
| 3 | DXVK | `dxvk-1.10.3-async`, `dxvk-v2.4.1-async`, `dxvk-2.3.1-async`, `dxvk-1.10.3-arm64ec-async` |
| 4 | VKD3D | `vkd3d-proton-2.14.1`, `vkd3d-proton-3.0a`, `vkd3d-proton-3.0b` |
| 5 | Per-game settings pack | `Hzd_Settings`, `DontStarve_Settings`, `Kena_Settings`, `MetroExodus_Settings`, `FINAL FANTASY 7_Settings`, `SKR_Settings` |
| 6 | Runtime dep | `vcredist2008/2010/2012/2013/2015/2019`, `VulkanRT`, `mono`, `quicktime72` |

### State machine (observed values)

`None` → user has never downloaded this component  
`Downloaded` → archive sits in `xj_downloads/component/<name>/`, awaiting extraction  
*(presumably)* extracted-state → fully extracted under `usr/home/components/<name>/`, ready to use

---

## 3. Architecture decision — sidecar XML for manager-injected components

The Component Manager being ported from BannerHub 5.x lets users inject **custom** components: community Turnip builds, custom DXVK versions, sideloaded settings packs, etc. — anything outside what the official API ships.

**Decision: keep injected components in a separate sidecar XML, not in `sp_winemu_unified_resources.xml`.**

### Why a sidecar instead of writing into the unified registry

| Concern | Writing into unified XML | Separate sidecar XML |
| --- | --- | --- |
| API resync overwrites our entries | High risk — `fetchRemoteList` likely rewrites the whole file | Sidecar is untouched; survives any number of API syncs |
| Mixed ownership / hard to audit | Official + custom entries indistinguishable; harder to "remove all manager-injected" cleanly | Clear separation; backup/restore/wipe works on the sidecar in isolation |
| Md5 mismatch handling | Real risk — app probably re-downloads or removes entries whose Md5 fails verification, which would happen for our injected entries that don't have a server-issued Md5 | Sidecar entries can have an opt-out flag `bh_skip_md5: true` and our patched merge logic respects it |
| App-update breakage | Schema changes to the official XML break us silently | Sidecar schema is ours; app updates don't touch it |

### Proposed sidecar location and shape

**File:** `shared_prefs/sp_bh_components.xml`  
**Format:** standard SharedPreferences XML, mirrors the unified-registry shape so existing parsers can consume it with no schema translation.

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="COMPONENT:my-custom-turnip">{
        "category": "COMPONENT",
        "depInfo": null,
        "entry": {
            "downloadUrl": "file:///storage/emulated/0/Download/my-custom-turnip.tzst",
            "fileMd5": "",
            "fileName": "my-custom-turnip.tzst",
            "fileSize": 3145728,
            "fileType": 4,
            "id": -1,
            "name": "my-custom-turnip",
            "state": "None",
            "status": 0,
            "type": 2,
            "version": "1.0.0",
            "versionCode": 1,
            "_bh_injected": true,
            "_bh_skip_md5": true,
            "_bh_source_uri": "content://...../my-custom-turnip.tzst"
        },
        "isBase": false,
        "isDep": false,
        "name": "my-custom-turnip",
        "state": "None",
        "version": "1.0.0"
    }</string>
</map>
```

Three additional fields under `entry` are namespaced `_bh_*` so they're unmistakably ours:
- `_bh_injected: true` — marker so our merge logic knows this is sidecar-sourced
- `_bh_skip_md5: true` — disables the Md5 integrity check (we don't have a server-issued hash)
- `_bh_source_uri` — where the user's local file lives (so we can re-import after factory reset / device migration)

`id` is set to `-1` to distinguish from server-assigned positive IDs.

---

## 4. How the sidecar feeds per-game settings dropdowns

User's hypothesis confirmed: when `pc_g_setting<server_game_id>.xml` is created (or the per-game settings menu is opened), the dropdown lists for "GPU driver", "DXVK version", "VKD3D version", etc. are populated from the unified registry filtered by `type`. To make injected components appear there, the dropdown-population code must consult **both** the unified registry **and** the sidecar.

### Required patch surface

**One bytecode patch** on the method that builds the component-picker list. Smali archaeology starting points (still TBD — needs `apktool d` + grep for the dropdown code):
- Classes that already use `ComponentStrategy.fetchRemoteList` or read `sp_winemu_unified_resources.xml` (candidates: `b1.smali`, `b23.smali`, `b55.smali`, `e9k.smali`, `ic3.smali`, `j13.smali`, `k13.smali`, `ou2.smali`, `x45.smali`, `zhn.smali`)
- Composables that render component-pick dropdowns in the per-game settings menu

Patch logic:
```kotlin
// Pseudocode for the ReVanced patch:
firstMethod {
    /* the method that returns List<Component> for a given type */
}.apply {
    // Inject a tail-call that appends our sidecar's matching entries.
    // After the existing `return-object v0` (the unified-registry list),
    // intercept v0, pass it through ComponentInjector.append(v0, type),
    // then return the merged list.
}
```

`ComponentInjector` would be a small Java extension class shipped in the patch's `.rve`:
```java
package app.revanced.extension.gamehub.components;

public final class ComponentInjector {
    public static List<?> append(List<?> serverList, int type) {
        // Read sp_bh_components.xml from SharedPreferences
        // Parse each COMPONENT:* entry's JSON
        // Filter to entries whose type matches `type`
        // Deserialize into the same concrete Component class as serverList's elements
        // (use reflection to discover the class — it's R8-renamed, no static reference)
        // Return new ArrayList<>(serverList + sidecarMatches)
    }
}
```

Reading `sp_bh_components.xml` is straightforward (`Context.getSharedPreferences("sp_bh_components", MODE_PRIVATE)`). Constructing the right Component object via reflection is the same pattern we already use in `FakeAuthToken.java` and `FakeUserAccount.java` for the login bypass — find the class via `Class.forName(...)`, find the canonical constructor by parameter count, instantiate.

---

## 5. Component Manager UI port — operations

The ported `ComponentManagerActivity` (and `ComponentDownloadActivity`) need these primitives, all available in the current 6.0 surface:

| Operation | How |
| --- | --- |
| List all components | Read both `sp_winemu_unified_resources.xml` AND `sp_bh_components.xml`. Group by `type`. |
| Show state | Read `entry.state` field. Cross-check against filesystem (`xj_downloads/component/<name>/` exists? `usr/home/components/<name>/` exists?) for accuracy. |
| Add (official) | Set `entry.state` to triggering value, call `ComponentStrategy.downloadComponentOnDemand(name)` (need to map the singleton class — likely in `com.winemu.openapi`). App handles download → moves archive into `xj_downloads/component/<name>/` → updates state. |
| Add (custom — sidecar) | User picks a local `.tzst` via file picker → write a new `COMPONENT:*` entry in `sp_bh_components.xml` with `_bh_injected=true`, `state="None"`, `_bh_source_uri=<picked uri>`. Optionally pre-copy the file into `xj_downloads/component/<name>/` and flip `state` to `Downloaded` so it's ready for first launch. |
| Extract on demand | Either let next game launch trigger via `ComponentStrategy.extractComponent`, or call it directly to pre-extract. |
| Remove | Clear `entry.state` to `None`, `rm -rf xj_downloads/component/<name>/` and `usr/home/components/<name>/`. For sidecar entries, also delete the `COMPONENT:<name>` key from `sp_bh_components.xml` if user wants permanent removal. |
| Rebuild dependency graph | Call `ComponentStrategy.alignDepComponentsWithRepo` after add/remove (smali contains string `"alignDepComponentsWithRepo updated count="` so this is a real method we can invoke). |

---

## 6. Required changes summary

### New files (extension JAR)

- `extensions/gamehub/.../components/ComponentInjector.java` — reads sidecar XML, merges entries into server-supplied lists by type, handles JSON deserialization via reflection
- `extensions/gamehub/.../components/SidecarRegistry.java` — read/write helpers for `sp_bh_components.xml`
- `extensions/gamehub/.../components/ComponentManagerActivity.java` — the ported UI Activity (adapted from 5.x BannerHub's `com.xj.landscape.launcher.ui.menu.ComponentManagerActivity`, with the `EmuComponents` HashMap calls re-routed to read from the merged registry)
- `extensions/gamehub/.../components/ComponentDownloadActivity.java` — companion UI for adding components (file picker, paste-URL, etc.)

### New patch sources

- `patches/.../components/ComponentManagerPatch.kt` — registers the Activities in AndroidManifest, optionally adds a launcher icon (separate `MAIN`/`LAUNCHER` activity-alias) so users can open the manager from the home screen
- `patches/.../components/ComponentInjectionPatch.kt` — bytecode patch on the dropdown-population method that calls `ComponentInjector.append(list, type)` before returning, so sidecar entries appear in per-game settings menus

### No new manifest permissions required

The Activities only need `Context.getSharedPreferences` and standard file I/O within the app's own data dir — both already granted by the base APK.

---

## 7. Implementation plan — discrete jobs

Twelve numbered jobs in dependency order. Each is independently doable, has explicit inputs / outputs / acceptance criteria, and tells the next agent exactly what "done" looks like. Open archaeology questions from prior drafts are folded in — the job that resolves each is annotated.

### Phase A — Smali archaeology (no code, just findings)

#### Job 1 — Map the `ComponentStrategy` API ⏱ ~1h

**Resolves:** what method to call to download / extract / align components.

**Inputs:**
- Decompiled APK at `/data/data/com.termux/files/home/gamehub-6.0-decompile/` (rebuild via `java -jar ~/apktool.jar d ~/GameHub_beta_6.0.0_global.apk -o /tmp/gh600_smali -f --no-res` if stale)

**Steps:**
1. `grep -rln "ComponentStrategy(" smali_classes*/` — find the class that builds the `ComponentStrategy(...)` log lines from §3
2. Inspect the class's methods — look for `downloadComponentOnDemand`, `extractComponent`, `alignDepComponentsWithRepo`, `fetchRemoteList`. Names may be R8-renamed; use the literal log-string method as an anchor (the method that prints `"alignDepComponentsWithRepo updated count="` IS that method).
3. Find the singleton accessor — Koin DI pattern almost certainly (`Lorg/koin/core/component/KoinComponent;->getKoin()`). Document the static getter or the Koin scope key.
4. Read each method's signature including parameter classes (the entity class that represents a Component is what `ComponentInjector` will need to instantiate via reflection).

**Output:** Append to this report a table of `Method | Smali class | Signature | Returns | Notes` covering at minimum: `downloadComponentOnDemand`, `extractComponent`, `alignDepComponentsWithRepo`, `fetchRemoteList`, plus the canonical Component-entity class (the type held by `entry` in the JSON schema in §2).

**Acceptance:** for any given component name string, we know exactly which static method to call (with what arg shape) to trigger a download. No "TBD" left in the report's call-shape notes.

---

#### Job 2 — Identify the dropdown-population method ⏱ ~2h

**Resolves:** the single bytecode patch surface for `ComponentInjectionPatch`.

**Inputs:** Job 1's findings; the smali decompile.

**Steps:**
1. Grep for class members reading `sp_winemu_unified_resources`: `grep -rln "sp_winemu_unified_resources" smali_classes*/`
2. From those classes, trace methods that return a list / map of components filtered by `type`. The candidates from §4 are starting points: `b1.smali`, `b23.smali`, `b55.smali`, `e9k.smali`, `ic3.smali`, `j13.smali`, `k13.smali`, `ou2.smali`, `x45.smali`, `zhn.smali`.
3. Cross-reference: which of these methods are called from per-game-settings Composables? Grep for `pc_g_setting` and trace upward to find the call site that requests a component list.
4. Confirm the return type (`Ljava/util/List;` or a Kotlin Flow/StateFlow), and the parameter shape (does the caller pass `type: Int`, or are there separate per-type methods?).

**Output:** append to report: full smali signature of the target method, plus any siblings (e.g., one method per type number 2/3/4/5/6 vs one generic method taking `type` as arg). Decide whether the merge is one-method-patch or multi-method-patch.

**Acceptance:** report names the exact method(s) to patch with full descriptor (`Lcls;->name(arg)RetType`). Anyone can write the bytecode patch from this entry alone.

---

#### Job 3 — Confirm the component state literals ⏱ ~30min

**Resolves:** complete state machine for `entry.state` so the UI can render correct status badges.

**Inputs:** smali decompile + the live Genshin install at `/data/data/com.mihoyo.genshinimpact/`.

**Steps:**
1. Grep all string literals fed into the field write for `entry.state`: `grep -rh "\"state\"" smali_classes*/ | grep -E "const-string|put"` — narrow to the relevant component classes from Jobs 1-2.
2. Check the live install: `getlog --cat .../sp_winemu_unified_resources.xml | grep -oE '"state":"[^"]*"' | sort -u` — gives empirical state values.
3. Cross-reference smali with empirical values. Settle the canonical extracted state name (likely `"Extracted"` or `"Ready"` or `"Installed"`).

**Output:** append to report: complete enumeration of states with semantic meaning of each. Update the §2 state-machine line.

**Acceptance:** every state observed in either smali or live data is documented with its meaning.

---

### Phase B — Extension classes (Java, no patcher integration yet)

#### Job 4 — Build `SidecarRegistry` extension ⏱ ~2h

**Resolves:** the read/write surface for `sp_bh_components.xml`.

**Path:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/components/SidecarRegistry.java`

**Required public API:**
```java
public final class SidecarRegistry {
    public static List<JSONObject> getAllByType(Context ctx, int type);
    public static JSONObject get(Context ctx, String name);
    public static void put(Context ctx, String name, JSONObject entry);
    public static void remove(Context ctx, String name);
    public static Set<String> allNames(Context ctx);
}
```

**Steps:**
1. SharedPreferences-backed: `ctx.getSharedPreferences("sp_bh_components", Context.MODE_PRIVATE)`.
2. Each entry is a JSON string under key `COMPONENT:<name>`. Use `org.json.JSONObject` (always available on Android).
3. Add `_bh_injected`, `_bh_skip_md5`, `_bh_source_uri` namespaced fields per the §3 schema.
4. Unit-testable via plain Robolectric, but for our purposes a manual install-and-poke is fine.

**Acceptance:** can write a test entry from a one-shot helper (e.g., a static `seedTest()` method we can call from `ComponentManagerActivity.onCreate` for first-pass verification), then read it back via `getAllByType`. No dependence on Job 1 findings yet.

---

#### Job 5 — Build `ComponentInjector` extension ⏱ ~2h

**Resolves:** how the injected entries get reshaped into the same Component-entity class the host app's UI expects.

**Path:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/components/ComponentInjector.java`

**Required public API:**
```java
public final class ComponentInjector {
    public static List<Object> append(Context ctx, List<Object> serverList, int type);
}
```

**Steps:**
1. Determine the Component-entity class via `serverList.isEmpty() ? null : serverList.get(0).getClass()` (avoids hard-coding R8-renamed names).
2. For each sidecar JSONObject of matching `type`, build a same-class instance via reflection. Pattern: `Class.forName("...")::getDeclaredConstructor(...)::newInstance(...)`. Same as `FakeUserAccount.java`.
3. Return `new ArrayList<>(serverList)` with sidecar matches appended.
4. Guard against the empty-server-list case (we can't infer the class). For v1 if `serverList` is empty, return a copy of `serverList` and log a warning to `GH600-DEBUG` — no sidecar merge possible without a class anchor. Document this as a known limitation; can be fixed in a follow-up by hard-coding the class once Job 1 names it.

**Output:** working extension class.

**Acceptance:** depends on Job 1 (need the entity class to instantiate). Verifiable in the device test (Job 11) — check that injected entries actually appear in the dropdown.

---

### Phase C — Bytecode patch (the integration point)

#### Job 6 — Build `ComponentInjectionPatch` ⏱ ~2-3h

**Resolves:** wires the sidecar into the live dropdown UI.

**Path:** `patches/src/main/kotlin/app/revanced/patches/gamehub/components/ComponentInjectionPatch.kt`

**Steps:**
1. From Job 2, target the dropdown-population method.
2. Find the existing `return-object v0` (the unified-list result) at method end via `findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT)`.
3. Insert before each return:
   ```smali
   invoke-static {<ctx-reg>, v0, <type-reg>}, Lapp/revanced/extension/gamehub/components/ComponentInjector;->append(Landroid/content/Context;Ljava/util/List;I)Ljava/util/List;
   move-result-object v0
   ```
   The `<ctx-reg>` source depends on Job 2's findings (might need to grab Application context via static accessor). The `<type-reg>` likewise depends on the method signature.
4. Add the patch to the source tree but do NOT enable in the matrix `-e` list (it's enabled by default since `use=true`).

**Acceptance:** patch applies cleanly in CI (`INFO: "Component injection" succeeded`). Smoke test: install build, open per-game settings on any imported game, dropdowns are non-empty (if anything, dropdowns show all the same content as before this patch — sidecar is empty, so merge is a no-op until Job 4/7/8 land entries into the sidecar).

---

### Phase D — UI port

#### Job 7 — Port `ComponentManagerActivity` ⏱ ~1 day

**Resolves:** the main user-facing UI surface.

**Path:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/components/ComponentManagerActivity.java` (and inner classes — port from BannerHub 5.x's smali but rewrite as Java for maintainability).

**Steps:**
1. From the BannerHub 5.x smali (`/data/data/com.termux/files/home/bannerhub/patches/smali_classes16/com/xj/landscape/launcher/ui/menu/ComponentManagerActivity*.smali`), reverse-engineer the activity's behavior into Java. ~1270 LOC of smali maps to roughly ~600 LOC of Java.
2. Replace every reference to `Lcom/xj/winemu/EmuComponents;` (the 5.x singleton + HashMap) with calls to:
   - `ComponentStrategy.<getter>` (Job 1) for the live unified list
   - `SidecarRegistry.getAllByType(ctx, type)` for the injected list
   - Combined and de-duped before display (sidecar entry with same name as a server entry: prefer server entry, hide sidecar; or show both if names differ).
3. Operations:
   - **Add (official)**: invoke `ComponentStrategy.downloadComponentOnDemand(name)` (Job 1)
   - **Add (custom)**: launch `ComponentDownloadActivity` (Job 8)
   - **Remove**: write state to "None" if server-side, delete from sidecar XML if injected. Plus delete from `xj_downloads/component/<name>/` and `usr/home/components/<name>/`.
   - **Force extract**: invoke `ComponentStrategy.extractComponent(name)` (Job 1)
4. Resources: drop the `bh_game_card_focus_bg.xml` drawable into `extensions/gamehub/src/main/res/drawable/`.

**Acceptance:** activity launches via `am start -n com.xiaoji.egggame/app.revanced.extension.gamehub.components.ComponentManagerActivity` (or via the launcher icon from Job 9). Lists are populated correctly. Add/remove/extract operations succeed.

---

#### Job 8 — Port `ComponentDownloadActivity` ⏱ ~4h

**Resolves:** the file-picker / URL-paste UI for adding custom components.

**Path:** `extensions/gamehub/src/main/java/app/revanced/extension/gamehub/components/ComponentDownloadActivity.java`

**Steps:**
1. Port the 5.x download activity to Java (~400 LOC of smali → ~200 Java).
2. Two input modes:
   - **File picker**: `Intent(Intent.ACTION_OPEN_DOCUMENT)` for `.tzst` and `.yml`. Copy the picked file into `xj_downloads/component/<derived-name>/`. Write a sidecar entry with `state="Downloaded"`.
   - **URL paste**: download to a temp file, validate it's a tzst/yml, move to `xj_downloads/component/<name>/`. Write a sidecar entry.
3. Component name derivation: filename minus extension, sanitized.
4. Persist the source URI in `_bh_source_uri` so the manager can show "imported from <path>" in the UI.

**Acceptance:** can pick a `.tzst` from external storage; new entry appears in `sp_bh_components.xml` and in the manager's dropdown for the matching `type`. (Type detection from the filename is heuristic — see open question Q1 below.)

---

### Phase E — Patch glue + ship

#### Job 9 — Build `ComponentManagerPatch` ⏱ ~1h

**Resolves:** AndroidManifest registration + launcher icon path.

**Path:** `patches/src/main/kotlin/app/revanced/patches/gamehub/components/ComponentManagerPatch.kt` (resourcePatch, since it modifies the manifest XML)

**Steps:**
1. Inject `<activity>` entries for `ComponentManagerActivity` and `ComponentDownloadActivity` into the AndroidManifest (same pattern as `FileManagerAccessPatch`).
2. Add an `<activity-alias>` with `MAIN`/`LAUNCHER` intent filter and a distinct icon so the manager appears as its own launcher entry.
3. Decision pending (Q2 below): whether to also do Compose-injection of a button next to the profile icon. Defer to v2.

**Acceptance:** patch applies, AndroidManifest contains the new entries, launcher icon appears on the home screen after install.

---

#### Job 10 — v1 prerelease build ⏱ ~30min

**Resolves:** end-to-end CI integration.

**Steps:**
1. Tag `v0.3.0-component-manager-test` (artifact-only per the prerelease policy).
2. Verify all 7 patches succeed in the apply-patches log:
   - Bypass login
   - Disable Firebase Crashlytics
   - Debug logging
   - File manager access
   - **Component injection** (new)
   - **Component manager** (new — manifest patch)
   - Per-variant Change package name + Change app name
3. Download Original variant to `/storage/emulated/0/Download/bannerhub-v0.3.0-cm-Original.apk`.

**Acceptance:** CI green, no SEVEREs, APK downloads.

---

#### Job 11 — Device test ⏱ ~2h

**Resolves:** end-to-end functional verification.

**Test plan:**
1. Install on top of existing `Original` install.
2. Verify launcher icon appears.
3. Open Component Manager.
   - List populated from `sp_winemu_unified_resources.xml`: ✓ each component visible.
   - Component states (None/Downloaded/Extracted) render correctly.
4. Tap "Add" → ComponentDownloadActivity → file picker → pick a known-good `.tzst` from Downloads.
   - Verify entry appears in `sp_bh_components.xml` (pull via `getlog --cat`).
   - Verify entry shows in the manager's list.
5. Open the imported Genshin (or any other) game's PC settings.
   - Open the GPU driver / DXVK / VKD3D dropdown matching the injected component's `type`.
   - Verify the injected component appears alongside official entries. ← this is the integration point that proves Jobs 4-6 work end-to-end.
6. Pick the injected component for the game; save settings.
7. Launch the game. Confirm the chosen component is actually used (winemu logs should reference it).
8. Remove the injected component via the manager. Verify it disappears from the sidecar XML and the dropdowns.
9. Add an official component (one that's currently `state=None`). Verify the download flow runs and `state` flips to `Downloaded`.

**Acceptance:** every step passes on a clean install. Logcat captured to `/data/data/com.termux/files/home/component-manager-v1-test.log` for any anomalies.

---

#### Job 12 — Iterate / polish ⏱ variable

Bug fixes from Job 11; UX polish; optional enhancements:
- Compose-inject the launcher button next to the profile icon (Q2)
- Cloud-backup of the sidecar XML (Q3)
- Type auto-detection in `ComponentDownloadActivity` (Q1)

---

## 8. Open questions (after the plan)

These are questions the plan does NOT pre-resolve and that need empirical answers during execution:

- **Q1: Component type auto-detection in `ComponentDownloadActivity`**: when the user picks a `.tzst`, how do we infer its `type` (GPU driver vs DXVK vs settings pack)? Options: filename heuristic (`turnip*` → 2, `dxvk*` → 3, `vkd3d*` → 4, `*_Settings*` → 5), tzst-content scan (extract & inspect), or just ask the user. Recommend filename heuristic with a fallback dropdown.

- **Q2: Launcher placement v2**: Compose-inject a button next to the profile icon vs. stick with the separate launcher alias from Job 9. Defer until Job 11 device test confirms separate-launcher feels acceptable.

- **Q3: Sidecar XML backup/restore**: factory reset wipes `sp_bh_components.xml`. Worth shipping cloud-backup support (write to `/sdcard/Android/data/com.xiaoji.egggame/files/banner-components/` for adb-pull preservation)? Not blocking v1.

---

## 9. Effort estimate (sum of jobs)

| Phase | Jobs | Effort |
| --- | --- | --- |
| A — Smali archaeology | 1, 2, 3 | ~3.5h |
| B — Extension classes | 4, 5 | ~4h |
| C — Bytecode patch | 6 | ~2-3h |
| D — UI port | 7, 8 | ~1.5d |
| E — Glue + ship | 9, 10, 11 | ~3.5h |
| (12 — iterate, optional/variable) | | ~half day |

**Total: ~3 working days for v1 (Jobs 1–11).** Compose-injected launcher button (Q2) would add ~1 more day if scoped in.
