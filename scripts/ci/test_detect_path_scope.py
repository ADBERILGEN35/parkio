#!/usr/bin/env python3
"""detect_path_scope.sh: non-PR always runs; PR matches PATH_REGEX against the merge-base range."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "detect_path_scope.sh"


def init_repo(root: Path):
    subprocess.run(["git", "init", "-b", "api"], cwd=root, check=True, capture_output=True)
    subprocess.run(["git", "config", "user.email", "t@example.com"], cwd=root, check=True)
    subprocess.run(["git", "config", "user.name", "t"], cwd=root, check=True)
    (root / "README").write_text("a\n")
    subprocess.run(["git", "add", "README"], cwd=root, check=True)
    subprocess.run(["git", "commit", "-m", "base"], cwd=root, check=True)
    subprocess.run(["git", "branch", "master"], cwd=root, check=True)
    subprocess.run(["git", "remote", "add", "origin", root.as_uri()], cwd=root, check=True)
    subprocess.run(["git", "fetch", "origin"], cwd=root, check=True, capture_output=True)


def run_detect(cwd: Path, *, event: str, base_ref: str, regex: str, out: Path):
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "EVENT_NAME": event,
        "BASE_REF": base_ref,
        "PATH_REGEX": regex,
        "GITHUB_OUTPUT": str(out),
    }
    return subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )


class DetectPathScopeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        init_repo(self.root)
        self.out = self.root / "out.txt"

    def test_non_pr_always_true(self):
        run_detect(self.root, event="workflow_dispatch", base_ref="", regex="^services/", out=self.out)
        self.assertIn("run=true", self.out.read_text())

    def test_pr_with_matching_path_true(self):
        (self.root / "services").mkdir()
        (self.root / "services" / "x.txt").write_text("x\n")
        subprocess.run(["git", "add", "services"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-m", "svc"], cwd=self.root, check=True)
        run_detect(
            self.root,
            event="pull_request",
            base_ref="api",
            regex="^services/",
            out=self.out,
        )
        self.assertIn("run=true", self.out.read_text())

    def test_pr_without_matching_path_false(self):
        (self.root / "docs").mkdir()
        (self.root / "docs" / "x.md").write_text("x\n")
        subprocess.run(["git", "add", "docs"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-m", "docs"], cwd=self.root, check=True)
        run_detect(
            self.root,
            event="pull_request",
            base_ref="api",
            regex="^services/",
            out=self.out,
        )
        self.assertIn("run=false", self.out.read_text())


if __name__ == "__main__":
    unittest.main()
