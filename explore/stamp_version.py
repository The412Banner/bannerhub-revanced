#!/usr/bin/env python3
"""
Stamp the build's version into the Explore version files so the in-app Explore
screen can show "installed vs latest" and surface an update banner — WITHOUT
relying on getPackageInfo().versionName (which returns the host GameHub
version, not our BannerHub release tag).

Writes two files:
  1. patches/src/main/resources/explore/bh_version.json
     → bundled into the APK as assets/bh_version.json by ExploreVersionAssetPatch.
       This is the *INSTALLED* version: frozen at build time, read offline.
  2. explore/bh_explore.json root "version"/"build"
     → attached as the bh_explore.json release asset; installed apps fetch
       releases/latest/download/bh_explore.json. This is the *LATEST* version
       the app compares its installed value against.

Version source: $VERSION env (release.yml's derived version), else the README
"## What's new in <ver>" heading. Build int = MAJOR*1_000_000 + MINOR*1_000 +
PATCH from the leading semver core (ignores the "-604" base / "-preN" suffix),
giving a clean monotonic integer for comparison (avoids semver-string parsing
on-device).

Run by release.yml's build job BEFORE "Build patches bundle" (so the patch
bundle picks up the freshly-stamped asset). Also safe to run by hand.

Fail-safe: never raises; always exits 0 so a release is never blocked by a
notes/version-format change.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
README = os.path.join(REPO, "README.md")
MANIFEST = os.path.join(HERE, "bh_explore.json")
VERSION_ASSET = os.path.join(
    REPO, "patches", "src", "main", "resources", "explore", "bh_version.json")
# Baked-into-APK copy of the manifest (assets/bh_explore.json via
# ExploreManifestAssetPatch). Kept identical to the stamped MANIFEST so the
# offline fallback carries this build's version/build and shipped rails.
MANIFEST_BAKED = os.path.join(
    REPO, "patches", "src", "main", "resources", "explore", "bh_explore.json")


def version_to_build(ver):
    """1.0.0-607 → 607010000.

    The GameHub base (the `-6XX` suffix) is folded ABOVE the semver so a
    fresh-reset semver on a newer base still outranks the old base's builds —
    e.g. 1.0.0-607 (607010000) > 1.8.0-604 (604010800). That keeps the Explore
    "update available" banner firing across a base bump + version reset, since
    the banner is a strict `latest > installed` int compare.

      layout : base*1_000_000 + major*10_000 + minor*100 + patch
      assumes: base <= 999 (3-digit GameHub suffix), major/minor/patch < 100
      ceiling: 999_999_999 — safely inside a signed 32-bit int (the build is
               read as Java optInt), unlike base*1e8 which would overflow.

    Already-shipped builds used the old `major*1e6 + minor*1e3 + patch` scheme
    with no base (e.g. 1.8.0-604 baked 1_008_000); every new base*1e6 value is
    far above those, so a 607 release still reads as newer to an installed 604
    app. No suffix (bare MAJOR.MINOR.PATCH) → base 0. Returns 0 if there's no
    leading MAJOR.MINOR.PATCH."""
    m = re.match(r"v?(\d+)\.(\d+)\.(\d+)(?:-(\d+))?", ver or "")
    if not m:
        return 0
    major, minor, patch = (int(x) for x in m.group(1, 2, 3))
    base = int(m.group(4)) if m.group(4) else 0
    return base * 1_000_000 + major * 10_000 + minor * 100 + patch


def resolve_version():
    v = os.environ.get("VERSION", "").strip()
    if v:
        return v.lstrip("v")
    try:
        readme = open(README, encoding="utf-8").read()
        m = re.search(r"^##\s+What's new in\s+(\S+)\s*$", readme, re.MULTILINE)
        if m:
            return m.group(1).strip().lstrip("v")
    except OSError:
        pass
    return ""


def main():
    ver = resolve_version()
    if not ver:
        print("[stampver] no version resolvable (no $VERSION, no README heading); untouched")
        return 0
    build = version_to_build(ver)
    if build <= 0:
        print(f"[stampver] version '{ver}' has no semver core; untouched")
        return 0

    # 1) Installed-version asset, bundled into the APK before the patch build.
    try:
        os.makedirs(os.path.dirname(VERSION_ASSET), exist_ok=True)
        with open(VERSION_ASSET, "w", encoding="utf-8") as f:
            json.dump({"version": ver, "build": build}, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[stampver] wrote {VERSION_ASSET}: version={ver} build={build}")
    except OSError as e:
        print(f"[stampver] could not write version asset ({e})")

    # 2) Latest-version fields on the remote manifest root (preserves all other
    #    keys, incl. the hero body that gen_whatsnew.py already refreshed in the
    #    preceding release.yml step).
    try:
        manifest = json.load(open(MANIFEST, encoding="utf-8"))
        manifest["version"] = ver
        manifest["build"] = build
        with open(MANIFEST, "w", encoding="utf-8") as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[stampver] stamped manifest root: version={ver} build={build}")
    except (OSError, ValueError) as e:
        print(f"[stampver] could not stamp manifest ({e})")

    # 3) Mirror the stamped manifest into the patch bundle resources so
    #    ExploreManifestAssetPatch bakes assets/bh_explore.json with the same
    #    version/build + shipped rails as the canonical/release manifest.
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            data = json.load(f)
        os.makedirs(os.path.dirname(MANIFEST_BAKED), exist_ok=True)
        with open(MANIFEST_BAKED, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print(f"[stampver] mirrored manifest -> {MANIFEST_BAKED}")
    except (OSError, ValueError) as e:
        print(f"[stampver] could not mirror manifest ({e})")

    return 0


if __name__ == "__main__":
    sys.exit(main())
