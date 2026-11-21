#!/usr/bin/env python3
"""Generate deterministic GitHub Release notes from Conventional Commits.

Usage: generate_release_notes.py [<previous-tag>] <tag>

An empty previous tag uses the full history reachable from <tag>. Pure stdlib so
it runs on any CI runner. Emits only the five requested sections:
Added / Fixed / Removed / Enhanced / Upgraded.
"""
from __future__ import annotations

import re
import subprocess
import sys

SECTIONS = ("Added", "Fixed", "Removed", "Enhanced", "Upgraded")

TYPE_TO_SECTION = {
    "feat": "Added",
    "fix": "Fixed",
    "revert": "Removed",
    "refactor": "Removed",
    "perf": "Enhanced",
    "build": "Upgraded",
    "deps": "Upgraded",
    "chore": "Upgraded",
    "ci": "Upgraded",
}

SUBJECT = re.compile(r"^([A-Za-z]+)(?:\([^)]*\))?(!)?:\s*(.+)$")
SKIP = re.compile(r"^(?:release|merge)\b", re.IGNORECASE)


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], check=False, capture_output=True, text=True
    )
    return result.stdout if result.returncode == 0 else ""


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print("usage: generate_release_notes.py [<previous-tag>] <tag>", file=sys.stderr)
        return 2
    previous, tag = (args[0], args[1]) if len(args) >= 2 else ("", args[0])

    fmt = "%h%x09%s"
    if previous:
        log = git("log", f"--format={fmt}", f"{previous}..{tag}")
    else:
        log = git("log", f"--format={fmt}", tag)
    if not log.strip():
        log = git("log", f"--format={fmt}", "-n", "20", tag)

    buckets: dict[str, list[str]] = {section: [] for section in SECTIONS}
    for line in log.splitlines():
        sha, sep, subject = line.partition("\t")
        if not sep:
            continue
        subject = subject.strip()
        if not subject or SKIP.match(subject):
            continue
        match = SUBJECT.match(subject)
        if not match:
            continue
        section = TYPE_TO_SECTION.get(match.group(1).lower())
        if section is None:
            continue
        entry = f"- {subject} ({sha.strip()})"
        if entry not in buckets[section]:
            buckets[section].append(entry)

    lines: list[str] = []
    for section in SECTIONS:
        entries = buckets[section]
        if not entries:
            continue
        lines.append(f"### {section}")
        lines.append("")
        lines.extend(entries)
        lines.append("")

    if not lines:
        lines = ["_No user-facing changes in this release._", ""]
    sys.stdout.write("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
