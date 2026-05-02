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

## 7. Open questions / next steps

1. **Map `ComponentStrategy` API**: identify the singleton class (likely in `com.winemu.openapi/a..f`), confirm method signatures for `downloadComponentOnDemand`, `extractComponent`, `alignDepComponentsWithRepo`, `fetchRemoteList`. Required before the manager can trigger any of these flows. Effort: ~1 hour of `apktool d` + grep + reading.

2. **Identify the dropdown-population method**: this is the one bytecode patch surface for the merge. Grep for usages of `sp_winemu_unified_resources` string + `getSharedPreferences` callers + classes that reach those candidates listed in §4. Effort: ~2 hours.

3. **Pick the launcher path**: separate launcher icon (trivial, `MAIN`/`LAUNCHER` activity-alias in the manifest patch) or Compose-injected button next to the profile icon (hard, bytecode injection into the home-screen composable). Recommend separate launcher icon for v1.

4. **Decide custom-component download mechanism**: file picker (user chooses a local `.tzst`)? URL paste? Both? Probably both, since the 5.x manager supported URL-driven downloads.

5. **Verify the extracted-state name**: we observe `"None"` and `"Downloaded"` in the data; need to confirm what the post-extraction state literal is by extracting a component and re-reading the XML, or by grepping the smali for `"state"` writes. Affects how we render "fully ready" vs "downloaded but not extracted" in the UI.

6. **Decide UI launching path**: separate launcher icon (cheap, ship in v1) vs Compose-injected button next to profile icon (expensive, defer to v2 if at all).

---

## 8. Effort estimate

- Smali archaeology (open questions 1, 2, 5): **half a day**
- ComponentInjector + SidecarRegistry extensions: **half a day** (reflection patterns are well-trodden — same shape as `FakeAuthToken` / `FakeUserAccount`)
- ComponentInjectionPatch (the merge patch): **2-3 hours** once the target method is found
- Port of ComponentManagerActivity from 5.x with EmuComponents replaced by the merged-registry reads: **1 day** (5.x has 1270 LOC main activity + ~28 inner classes; most of it is UI plumbing that doesn't need to change)
- ComponentDownloadActivity port + URL/file-picker: **half a day**
- ComponentManagerPatch (manifest registration + optional launcher alias): **1 hour**
- Device testing + iteration: **half a day**

**Total: ~3 working days for v1.** Compose-injected launcher button (open question 6) would add ~1 more day if scoped in.
