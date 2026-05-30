#!/usr/bin/env python3
"""
Regenerate the Explore hero's "WHAT'S NEW" block in explore/bh_explore.json
from the README's "## What's new in <version>" section, so the in-app What's
New stays in sync with the release notes automatically.

Run by release.yml (build job) before the manifest is staged as the
bh_explore.json release asset. Also safe to run by hand.

What it does:
  - Finds the README "## What's new in <VER>" section (up to the next "## "
    or horizontal rule) and collects its "### <emoji> <Title>" subheadings,
    skipping the "Carryover ..." one.
  - Rebuilds the hero body as:
        WHAT'S NEW IN <VER>
        • <Title 1>
        • <Title 2>
        ...
        <everything from "EVERYTHING WE'VE ADDED" onward, kept verbatim>
  - Writes explore/bh_explore.json back in place.

Fail-safe: if the README section, any heading, or the "EVERYTHING WE'VE ADDED"
tail marker can't be found, it leaves the manifest untouched and exits 0 so a
release is never blocked by a notes-format change.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
README = os.path.join(REPO, "README.md")
MANIFEST = os.path.join(HERE, "bh_explore.json")
TAIL_MARKER = "EVERYTHING WE'VE ADDED"


def main():
    try:
        readme = open(README, encoding="utf-8").read()
    except OSError as e:
        print(f"[whatsnew] README unreadable ({e}); leaving manifest untouched")
        return 0

    m = re.search(r"^##\s+What's new in\s+(\S+)\s*$", readme, re.MULTILINE)
    if not m:
        print("[whatsnew] no '## What's new in <ver>' heading; untouched")
        return 0
    ver = m.group(1).strip()

    # Section body = from after the heading to the next "## " or "---".
    start = m.end()
    rest = readme[start:]
    end = re.search(r"^(##\s|---\s*$)", rest, re.MULTILINE)
    section = rest[: end.start()] if end else rest

    titles = []
    for h in re.findall(r"^###\s+(.+?)\s*$", section, re.MULTILINE):
        title = re.sub(r"^[^\w]+", "", h).strip()        # drop leading emoji
        if title.lower().startswith("carryover"):
            continue
        if title:
            titles.append(title)

    if not titles:
        print("[whatsnew] no feature subheadings found; untouched")
        return 0

    head = f"WHAT'S NEW IN {ver}\n" + "".join(f"• {t}\n" for t in titles)

    try:
        manifest = json.load(open(MANIFEST, encoding="utf-8"))
        card = manifest["rails"][0]["cards"][0]
        body = card["body"]
    except (OSError, KeyError, IndexError, ValueError) as e:
        print(f"[whatsnew] manifest hero unreadable ({e}); untouched")
        return 0

    idx = body.find(TAIL_MARKER)
    if idx == -1:
        print(f"[whatsnew] '{TAIL_MARKER}' marker missing in hero body; untouched")
        return 0

    card["body"] = head + "\n" + body[idx:]
    with open(MANIFEST, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print(f"[whatsnew] hero What's New regenerated for {ver}: {titles}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
