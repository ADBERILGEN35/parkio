#!/usr/bin/env python3
"""Scripts that are run by path are committed executable (H-14, #549).

A checkout with core.fileMode=false (Windows, the WSL development checkout) records a new script as
100644. A fresh clone then cannot run it by path (`Permission denied`, exit 126), and a workflow or a
test that chmods the file first hides that. This check reads the mode git recorded in the index and
the content git recorded, never the file bit on disk:

  1. Every tracked scripts/**/*.sh whose first line is a shebang is 100755, except the sourced
     libraries: everything under scripts/lib/ and the files in SOURCED_ONLY.
  2. Every file in RUN_BY_PATH is tracked and 100755. These are scripts outside rule 1 that a runbook,
     a workflow, a container or another script runs by path.
  3. Every file in SOURCED_ONLY is tracked and stays 100644. These are libraries outside scripts/lib/
     that are only sourced. When something starts running one by path, make it executable and move
     it from SOURCED_ONLY to rule 1.

Fix a finding with `git update-index --chmod=+x <path>` (rules 1 and 2) and commit the mode change.
Usage: check_script_modes.py   (exit 1 lists every finding)
"""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
EXECUTABLE = "100755"
REGULAR = "100644"
LIBRARY_DIR = "scripts/lib/"

# Libraries outside scripts/lib/. They are only sourced; nothing runs them by path.
SOURCED_ONLY = (
    "scripts/prod-muni-01/lib/common.sh",
    "scripts/staging/lib/evidence-common.sh",
    "scripts/staging/lib/json-helper.sh",
    "scripts/staging/lib/safety-guards.sh",
)

# Run by path, but not covered by rule 1 (not a scripts/**/*.sh, or under scripts/lib/).
RUN_BY_PATH = (
    "docker/alertmanager/render-config.sh",  # scripts/observability-validate.sh runs it inside the container
    "frontend/apps/mobile-v2/android/gradlew",  # mobile-v2 run-android-release.mjs spawns it on Linux and macOS
    "gradlew",  # ./gradlew in the workflows and the runbooks
    "scripts/lib/assert-compose-hardening.mjs",  # docs/operations/container-hardening-inventory.md
    "scripts/lib/recovery-expose-gate.py",  # docs/operations/restore-safe-preflight.md, step 4
)


def index_entries(root: Path) -> dict[str, tuple[str, str]]:
    """Every tracked path with the (mode, object id) the index recorded for it."""
    out = subprocess.run(["git", "-C", str(root), "ls-files", "--stage", "-z"],
                         check=True, capture_output=True).stdout
    entries = {}
    for record in out.split(b"\0"):
        if not record:
            continue
        meta, path = record.split(b"\t", 1)
        mode, oid, _stage = meta.decode("ascii").split(" ")
        entries[path.decode("utf-8", errors="surrogateescape")] = (mode, oid)
    return entries


def shebang_blobs(root: Path, oids: list[str]) -> set[str]:
    """The object ids, among `oids`, whose content starts with `#!`."""
    if not oids:
        return set()
    out = subprocess.run(["git", "-C", str(root), "cat-file", "--batch"], check=True, capture_output=True,
                         input="".join(f"{oid}\n" for oid in oids).encode("ascii")).stdout
    found, pos = set(), 0
    for oid in oids:
        end = out.index(b"\n", pos)
        header = out[pos:end].split()
        if len(header) != 3:
            raise RuntimeError(f"git cat-file cannot read {oid}: {out[pos:end].decode(errors='replace')}")
        size = int(header[2])
        if out[end + 1:end + 1 + size].startswith(b"#!"):
            found.add(oid)
        pos = end + 1 + size + 1
    return found


def in_rule_1(path: str, sourced_only: tuple[str, ...]) -> bool:
    return (path.startswith("scripts/") and path.endswith(".sh") and not path.startswith(LIBRARY_DIR)
            and path not in sourced_only)


def check(root: Path = ROOT, run_by_path: tuple[str, ...] = RUN_BY_PATH,
          sourced_only: tuple[str, ...] = SOURCED_ONLY) -> tuple[list[str], dict[str, int]]:
    """The findings, and the counts the summary prints."""
    entries = index_entries(root)
    # Symlinks (120000) and gitlinks (160000) are not scripts; a symlink's target is checked on its own.
    candidates = sorted(path for path, (mode, _) in entries.items()
                        if mode in (REGULAR, EXECUTABLE) and in_rule_1(path, sourced_only))
    shebang = shebang_blobs(root, [entries[path][1] for path in candidates])
    scripts = [path for path in candidates if entries[path][1] in shebang]
    problems = [f"{path}: recorded {entries[path][0]}; a script with a shebang outside {LIBRARY_DIR} "
                f"must be {EXECUTABLE}" for path in scripts if entries[path][0] != EXECUTABLE]
    for path in run_by_path:
        if path not in entries:
            problems.append(f"{path}: listed in RUN_BY_PATH but not tracked")
        elif entries[path][0] != EXECUTABLE:
            problems.append(f"{path}: recorded {entries[path][0]}; it is run by path, so it must be {EXECUTABLE}")
    for path in sourced_only:
        if path not in entries:
            problems.append(f"{path}: listed in SOURCED_ONLY but not tracked")
        elif entries[path][0] != REGULAR:
            problems.append(f"{path}: recorded {entries[path][0]} but listed in SOURCED_ONLY; if something "
                            "runs it by path now, remove it from SOURCED_ONLY")
    counts = {"scripts": len(scripts), "run_by_path": len(run_by_path), "sourced_only": len(sourced_only)}
    return problems, counts


def main(argv: list[str], root: Path = ROOT) -> int:
    if argv:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    problems, counts = check(root)
    if problems:
        print("Script mode check FAILED. A fresh clone cannot run these by path (Permission denied, exit 126):\n  "
              + "\n  ".join(problems)
              + "\nFix: git update-index --chmod=+x <path>, then commit the mode change.")
        return 1
    print(f"Script mode check passed: {counts['scripts']} script(s) under scripts/ and {counts['run_by_path']} "
          f"listed file(s) run by path are recorded {EXECUTABLE}; {counts['sourced_only']} sourced-only "
          f"librar{'y' if counts['sourced_only'] == 1 else 'ies'} outside {LIBRARY_DIR} stay {REGULAR}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
