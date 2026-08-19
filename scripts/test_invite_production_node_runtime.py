#!/usr/bin/env python3
"""Regression coverage for the PROD-DEPLOY-01A runner Node contract."""

from __future__ import annotations

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "scripts" / "verify-node-runtime.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "invite-production-deploy.yml"
EXPECTED = (ROOT / ".node-version").read_text().strip()


def fake_node(directory: Path, version: str) -> Path:
    executable = directory / "node"
    executable.write_text(f"#!/usr/bin/env sh\nprintf '%s\\n' 'v{version}'\n")
    executable.chmod(executable.stat().st_mode | stat.S_IXUSR)
    return executable


def job_block(workflow: str, job: str, next_job: str | None) -> str:
    start = workflow.index(f"  {job}:\n")
    end = workflow.index(f"  {next_job}:\n", start) if next_job else len(workflow)
    return workflow[start:end]


class NodeRuntimeContractTest(unittest.TestCase):
    def run_verify(self, binary: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PARKIO_NODE_BINARY"] = binary
        return subprocess.run(
            [str(VERIFY)],
            cwd=ROOT,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_exact_version_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_verify(str(fake_node(Path(directory), EXPECTED)))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"PASS (v{EXPECTED})", result.stdout)

    def test_missing_runtime_fails_closed(self) -> None:
        result = self.run_verify("/nonexistent/parkio-node")
        self.assertEqual(result.returncode, 3)
        self.assertIn("unavailable", result.stderr)

    def test_wrong_runtime_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_verify(str(fake_node(Path(directory), "22.23.1")))
        self.assertEqual(result.returncode, 3)
        self.assertIn(f"expected v{EXPECTED}", result.stderr)

    def test_version_contract_is_an_exact_supported_node_22_release(self) -> None:
        self.assertRegex(EXPECTED, r"^22\.\d+\.\d+$")

    def test_workflow_sets_up_and_verifies_node_before_production_secrets(self) -> None:
        workflow = WORKFLOW.read_text()
        jobs = (
            ("runner-acceptance", "deploy"),
            ("deploy", "rollback"),
            ("rollback", None),
        )
        for name, next_name in jobs:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                setup = block.index("uses: actions/setup-node@v4")
                version_file = block.index(
                    "node-version-file: ${{ env.PARKIO_SOURCE_DIR }}/.node-version"
                )
                verify = block.index("./scripts/verify-invite-production-toolchain.sh")
                cleanup = block.index("if: ${{ always() }}")
                self.assertLess(setup, version_file)
                self.assertLess(version_file, verify)
                self.assertLess(verify, cleanup)
                if "Materialize secrets into tmpfs" in block:
                    self.assertLess(verify, block.index("Materialize secrets into tmpfs"))

    def test_github_hosted_dry_run_uses_same_exact_node_contract(self) -> None:
        workflow = WORKFLOW.read_text()
        build = job_block(workflow, "build-images", "runner-acceptance")
        self.assertIn("uses: actions/setup-node@v4", build)
        self.assertIn("node-version-file: .node-version", build)
        self.assertLess(
            build.index("./scripts/verify-node-runtime.sh"),
            build.index("Dry-run invite-production deploy"),
        )


if __name__ == "__main__":
    unittest.main()
