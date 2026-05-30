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


def version_to_build(ver):
    """1.6.0-604 → 1006000. Returns 0 if there's no leading MAJOR.MINOR.PATCH."""
    m = re.match(r"v?(\d+)\.(\d+)\.(\d+)", ver or "")
    if not m:
        return 0
    major, minor, patch = (int(x) for x in m.groups())
    return major * 1_000_000 + minor * 1_000 + patch


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
    #    keys; gen_whatsnew.py later edits only the hero body).
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

    return 0


if __name__ == "__main__":
    sys.exit(main())
