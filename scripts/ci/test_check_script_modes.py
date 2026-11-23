#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_script_modes.py (H-14, #549). Each test builds a throwaway git index."""
from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_script_modes as check  # noqa: E402

SCRIPT = "#!/usr/bin/env bash\nset -euo pipefail\necho ok\n"
FRAGMENT = "# sourced fragment, no shebang\nPARKIO_X=1\n"


class Repo:
    """A git index with the files a test adds, recorded the way core.fileMode=false records them."""

    def __init__(self, root: Path):
        self.root = root
        self.git("init", "-q")

    def git(self, *args: str, stdin: bytes | None = None) -> str:
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True,
                              input=stdin).stdout.decode()

    def add(self, path: str, text: str = SCRIPT, executable: bool = False, disk_bit: bool = False) -> None:
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8", newline="\n")
        if disk_bit:
            os.chmod(file, 0o755)
        # core.fileMode=false: git ignores the bit on disk and records a new file as 100644.
        self.git("-c", "core.fileMode=false", "add", "--", path)
        if executable:
            self.git("update-index", "--chmod=+x", "--", path)

    def add_symlink(self, path: str, target: str) -> None:
        oid = self.git("hash-object", "-w", "--stdin", stdin=target.encode()).strip()
        self.git("update-index", "--add", "--cacheinfo", f"120000,{oid},{path}")

    def mode(self, path: str) -> str:
        return self.git("ls-files", "--stage", "--", path).split(" ", 1)[0]


class CheckScriptModes(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="parkio-script-modes-")
        self.addCleanup(self._tmp.cleanup)
        self.repo = Repo(Path(self._tmp.name))

    def problems(self, run_by_path: tuple[str, ...] = (), sourced_only: tuple[str, ...] = ()) -> list[str]:
        return check.check(self.repo.root, run_by_path=run_by_path, sourced_only=sourced_only)[0]

    def test_a_new_script_recorded_100644_fails_and_is_named(self):
        self.repo.add("scripts/restore-something.sh")
        problems = self.problems()
        self.assertEqual(len(problems), 1, problems)
        self.assertTrue(problems[0].startswith("scripts/restore-something.sh: recorded 100644"), problems)

    def test_the_bit_on_disk_does_not_count_only_the_recorded_mode(self):
        self.repo.add("scripts/backup-something.sh", disk_bit=True)
        self.assertEqual(self.repo.mode("scripts/backup-something.sh"), "100644")
        self.assertEqual(len(self.problems()), 1)

    def test_a_script_recorded_100755_passes(self):
        self.repo.add("scripts/backup-something.sh", executable=True)
        problems, counts = check.check(self.repo.root, run_by_path=(), sourced_only=())
        self.assertEqual(problems, [])
        self.assertEqual(counts["scripts"], 1)

    def test_every_directory_under_scripts_outside_scripts_lib_is_covered(self):
        for path in ("scripts/ci/new-gate.sh", "scripts/azure/new-host-step.sh", "scripts/staging/lib/new-helper.sh"):
            self.repo.add(path)
        named = sorted(problem.split(":", 1)[0] for problem in self.problems())
        self.assertEqual(named, ["scripts/azure/new-host-step.sh", "scripts/ci/new-gate.sh",
                                 "scripts/staging/lib/new-helper.sh"])

    def test_sourced_libraries_under_scripts_lib_may_stay_100644(self):
        self.repo.add("scripts/lib/backup-common.sh")
        self.assertEqual(self.problems(), [])

    def test_a_sh_file_without_a_shebang_and_files_outside_scripts_are_not_rule_1(self):
        self.repo.add("scripts/fragment.sh", text=FRAGMENT)
        self.repo.add("tools/helper.sh")
        self.repo.add("scripts/tool.py", text="#!/usr/bin/env python3\n")
        self.assertEqual(self.problems(), [])

    def test_a_shebang_must_be_the_first_line(self):
        self.repo.add("scripts/late.sh", text="\n" + SCRIPT)
        self.assertEqual(self.problems(), [])

    def test_symlinks_are_skipped(self):
        self.repo.add("scripts/real.sh", executable=True)
        self.repo.add_symlink("scripts/alias.sh", "real.sh")
        self.assertEqual(self.problems(), [])

    def test_a_file_run_by_path_must_be_recorded_100755(self):
        self.repo.add("scripts/lib/recovery-expose-gate.py", text="#!/usr/bin/env python3\n")
        self.repo.add("gradlew", text="#!/bin/sh\n", executable=True)
        problems = self.problems(run_by_path=("gradlew", "scripts/lib/recovery-expose-gate.py"))
        self.assertEqual(len(problems), 1, problems)
        self.assertTrue(problems[0].startswith("scripts/lib/recovery-expose-gate.py: recorded 100644"), problems)

    def test_a_listed_file_that_is_not_tracked_fails(self):
        self.assertEqual(self.problems(run_by_path=("gone.sh",)), ["gone.sh: listed in RUN_BY_PATH but not tracked"])
        self.assertEqual(self.problems(sourced_only=("scripts/x/lib/gone.sh",)),
                         ["scripts/x/lib/gone.sh: listed in SOURCED_ONLY but not tracked"])

    def test_a_sourced_only_library_is_exempt_from_rule_1_and_must_stay_100644(self):
        self.repo.add("scripts/staging/lib/safety-guards.sh")
        self.assertEqual(self.problems(sourced_only=("scripts/staging/lib/safety-guards.sh",)), [])
        self.repo.git("update-index", "--chmod=+x", "--", "scripts/staging/lib/safety-guards.sh")
        problems = self.problems(sourced_only=("scripts/staging/lib/safety-guards.sh",))
        self.assertEqual(len(problems), 1, problems)
        self.assertIn("listed in SOURCED_ONLY", problems[0])

    def test_main_names_the_file_and_the_fix_and_exits_1(self):
        self.repo.add("scripts/verify-something.sh")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = check.main([], root=self.repo.root)
        self.assertEqual(code, 1)
        self.assertIn("scripts/verify-something.sh: recorded 100644", out.getvalue())
        self.assertIn("git update-index --chmod=+x <path>", out.getvalue())

    def test_the_lists_name_distinct_tracked_paths(self):
        self.assertEqual(len(set(check.RUN_BY_PATH)), len(check.RUN_BY_PATH))
        self.assertEqual(len(set(check.SOURCED_ONLY)), len(check.SOURCED_ONLY))
        self.assertFalse(set(check.RUN_BY_PATH) & set(check.SOURCED_ONLY))
        for path in check.SOURCED_ONLY:
            self.assertTrue(check.in_rule_1(path, ()), f"{path} would not need to be listed")


if __name__ == "__main__":
    unittest.main()
