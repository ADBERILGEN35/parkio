#!/usr/bin/env python3
"""Scripts that are run by path are committed executable (H-14, #549).

A checkout with core.fileMode=false (Windows, the WSL development checkout) records a new script as
100644. A fresh clone then cannot run it by path (`Permission denied`, exit 126), and a workflow or a
test that chmods the file first hides that. This check reads the mode git recorded in the index and
the content git recorded, never the file bit on disk:

  1. Every tracked scripts/**/*.sh and infra/**/*.sh whose first line is a shebang is 100755, except
     the sourced libraries: everything under scripts/lib/ and the files in SOURCED_ONLY.
  2. Every file in RUN_BY_PATH is tracked and 100755. These are scripts outside rule 1 that a runbook,
     a workflow, a container or another script runs by path.
  3. Every file in SOURCED_ONLY is tracked and stays 100644. These are libraries outside scripts/lib/
     that are only sourced. When something starts running one by path, make it executable and move
     it from SOURCED_ONLY to rule 1.
  4. Every tracked scripts/lib/**/*.py is classified: it is in RUN_BY_PATH (rule 2), or in
     LIBRARY_PYTHON, which lists the files that are imported or run as `python3 <path>` and stay
     100644. A new one fails until it is listed in one of the two.

Fix a finding with `git update-index --chmod=+x <path>` (rules 1 and 2) or `--chmod=-x` (rules 3 and
4), and commit the mode change.
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
RULE_1_DIRS = ("scripts/", "infra/")

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

# Every other scripts/lib/**/*.py (rule 4). Each is imported, or run as `python3 <path>`; nothing runs
# it by path, so it stays 100644. When something starts running one by path, make it executable and
# move it to RUN_BY_PATH with the caller.
LIBRARY_PYTHON = (
    "scripts/lib/assert-invite-production-feature-config.py",
    "scripts/lib/assert-invite-textfile-mount.py",
    "scripts/lib/assert_invite_production_feature_config_fixture.py",
    "scripts/lib/backup-metrics.py",
    "scripts/lib/check_dark_acme_model.py",
    "scripts/lib/check_public_cutover_model.py",
    "scripts/lib/recovery-drill.py",
    "scripts/lib/recovery-evidence.py",
    "scripts/lib/recovery_evidence_bundle.py",
    "scripts/lib/recovery_evidence_contract.py",
    "scripts/lib/recovery_persist_protocol.py",
    "scripts/lib/restore-client-compat.py",
    "scripts/lib/restore-drill-isolation-preflight.py",
    "scripts/lib/restore-dump-profile.py",
    "scripts/lib/restore-erasure-ledger.py",
    "scripts/lib/restore-isolated-inspect.py",
    "scripts/lib/restore-isolated-ticket.py",
    "scripts/lib/restore-stamp-preflight.py",
    "scripts/lib/rollback_schema_gate.py",
    "scripts/lib/web_api_endpoint_guard.py",
    "scripts/lib/web_bundle_map_config.py",
    "scripts/lib/web_conf_d_guard.py",
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
    return (path.startswith(RULE_1_DIRS) and path.endswith(".sh") and not path.startswith(LIBRARY_DIR)
            and path not in sourced_only)


def in_rule_4(path: str) -> bool:
    return path.startswith(LIBRARY_DIR) and path.endswith(".py")


def check(root: Path = ROOT, run_by_path: tuple[str, ...] = RUN_BY_PATH,
          sourced_only: tuple[str, ...] = SOURCED_ONLY,
          library_python: tuple[str, ...] = LIBRARY_PYTHON) -> tuple[list[str], dict[str, int]]:
    """The findings, and the counts the summary prints."""
    entries = index_entries(root)
    # Symlinks (120000) and gitlinks (160000) are not scripts; a symlink's target is checked on its own.
    candidates = sorted(path for path, (mode, _) in entries.items()
                        if mode in (REGULAR, EXECUTABLE) and in_rule_1(path, sourced_only))
    shebang = shebang_blobs(root, [entries[path][1] for path in candidates])
    scripts = [path for path in candidates if entries[path][1] in shebang]
    problems = [f"{path}: recorded {entries[path][0]}; a script with a shebang outside {LIBRARY_DIR} "
                f"must be {EXECUTABLE}" for path in scripts if entries[path][0] != EXECUTABLE]
    problems += [f"{path}: not classified; list it in RUN_BY_PATH if something runs it by path ({EXECUTABLE}), "
                 f"else in LIBRARY_PYTHON ({REGULAR})" for path in sorted(entries)
                 if in_rule_4(path) and path not in run_by_path and path not in library_python]
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
    for path in library_python:
        if path not in entries:
            problems.append(f"{path}: listed in LIBRARY_PYTHON but not tracked")
        elif entries[path][0] != REGULAR:
            problems.append(f"{path}: recorded {entries[path][0]} but listed in LIBRARY_PYTHON, which nothing runs "
                            f"by path, so it must be {REGULAR}; if something runs it by path now, move it to "
                            "RUN_BY_PATH")
    counts = {"scripts": len(scripts), "run_by_path": len(run_by_path), "sourced_only": len(sourced_only),
              "library_python": len(library_python)}
    return problems, counts


def main(argv: list[str], root: Path = ROOT) -> int:
    if argv:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    problems, counts = check(root)
    if problems:
        print("Script mode check FAILED:\n  " + "\n  ".join(problems)
              + f"\nA fresh clone cannot run a script recorded {REGULAR} by path (Permission denied, exit 126)."
              + "\nFix: git update-index --chmod=+x <path>, or --chmod=-x for a file that must stay "
              f"{REGULAR}, then commit the mode change. List a new {LIBRARY_DIR}*.py in RUN_BY_PATH or "
              "LIBRARY_PYTHON.")
        return 1
    print(f"Script mode check passed: {counts['scripts']} script(s) under scripts/ and infra/ and "
          f"{counts['run_by_path']} listed file(s) run by path are recorded {EXECUTABLE}; "
          f"{counts['sourced_only']} sourced-only librar{'y' if counts['sourced_only'] == 1 else 'ies'} outside "
          f"{LIBRARY_DIR} and {counts['library_python']} Python file(s) under {LIBRARY_DIR} stay {REGULAR}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
