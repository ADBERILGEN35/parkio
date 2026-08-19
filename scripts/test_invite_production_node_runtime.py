#!/usr/bin/env python3
"""Regression coverage for the PROD-DEPLOY-01A runner Node contract."""

from __future__ import annotations

import os
import posixpath
import re
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VERIFY = ROOT / "scripts" / "verify-node-runtime.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "invite-production-deploy.yml"
EXPECTED = (ROOT / ".node-version").read_text().strip()

# The real self-hosted invite-production topology, as reported by run
# 32269510229. Regression coverage has to resolve action inputs against this
# exact workspace, not a synthetic one.
RUNNER_WORKSPACE = "/opt/actions-runner/parkio-invite-production/_work/parkio/parkio"
RUN_ID = "32269510229"
RUN_ATTEMPT = "1"

SELF_HOSTED_JOBS = (
    ("runner-acceptance", "deploy"),
    ("deploy", "rollback"),
    ("rollback", None),
)


def fake_node(directory: Path, version: str) -> Path:
    executable = directory / "node"
    executable.write_text(f"#!/usr/bin/env sh\nprintf '%s\\n' 'v{version}'\n")
    executable.chmod(executable.stat().st_mode | stat.S_IXUSR)
    return executable


def job_block(workflow: str, job: str, next_job: str | None) -> str:
    start = workflow.index(f"  {job}:\n")
    end = workflow.index(f"  {next_job}:\n", start) if next_job else len(workflow)
    return workflow[start:end]


def node_path_join(base: str, segment: str) -> str:
    """Reproduce Node's path.posix.join, which actions/setup-node uses.

    path.join concatenates and normalizes; unlike path.resolve it does NOT
    let an absolute second argument override the first. That is precisely why
    an absolute node-version-file duplicated the workspace prefix.
    """
    return posixpath.normpath(posixpath.join(base + "/", "./" + segment))


def render(expression: str, source_path: str) -> str:
    """Resolve the workflow expressions used by the self-hosted job inputs."""
    rendered = expression
    for token, value in (
        ("${{ env.PARKIO_SOURCE_PATH }}", source_path),
        ("${{ github.workspace }}", RUNNER_WORKSPACE),
        ("${{ github.run_id }}", RUN_ID),
        ("${{ github.run_attempt }}", RUN_ATTEMPT),
    ):
        rendered = rendered.replace(token, value)
    return rendered


def job_env_value(block: str, name: str) -> str:
    match = re.search(rf"^      {re.escape(name)}: (.+)$", block, re.MULTILINE)
    assert match is not None, f"{name} is not declared at job level"
    return match.group(1).strip()


def with_input(block: str, name: str) -> list[str]:
    return [
        match.group(1).strip()
        for match in re.finditer(rf"^ +{re.escape(name)}: (.+)$", block, re.MULTILINE)
    ]


class NodeRuntimeContractTest(unittest.TestCase):
    def run_verify(
        self, binary: str, version_file: str | None = None
    ) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PARKIO_NODE_BINARY"] = binary
        if version_file is not None:
            environment["PARKIO_NODE_VERSION_FILE"] = version_file
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

    def test_missing_version_contract_fails_closed(self) -> None:
        """A vanished .node-version must stop the job, never fall through."""
        with tempfile.TemporaryDirectory() as directory:
            result = self.run_verify(
                str(fake_node(Path(directory), EXPECTED)),
                version_file=str(Path(directory) / "absent" / ".node-version"),
            )
        self.assertEqual(result.returncode, 3)
        self.assertIn("Node version contract is missing", result.stderr)

    def test_version_contract_is_an_exact_supported_node_22_release(self) -> None:
        self.assertRegex(EXPECTED, r"^22\.\d+\.\d+$")

    def test_version_contract_is_the_certified_pin(self) -> None:
        self.assertEqual(EXPECTED, "22.23.2")

    def test_workflow_sets_up_and_verifies_node_before_production_secrets(self) -> None:
        workflow = WORKFLOW.read_text()
        for name, next_name in SELF_HOSTED_JOBS:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                setup = block.index("uses: actions/setup-node@v4")
                version_file = block.index(
                    "node-version-file: ${{ env.PARKIO_SOURCE_PATH }}/.node-version"
                )
                verify = block.index("./scripts/verify-invite-production-toolchain.sh")
                cleanup = block.index("if: ${{ always() }}")
                self.assertLess(setup, version_file)
                self.assertLess(version_file, verify)
                self.assertLess(verify, cleanup)
                if "Materialize secrets into tmpfs" in block:
                    self.assertLess(verify, block.index("Materialize secrets into tmpfs"))

    def test_setup_node_resolves_to_the_unique_checkout_on_the_real_runner(self) -> None:
        """setup-node joins its input onto GITHUB_WORKSPACE, so it must be relative."""
        workflow = WORKFLOW.read_text()
        for name, next_name in SELF_HOSTED_JOBS:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                source_path = render(
                    job_env_value(block, "PARKIO_SOURCE_PATH"), source_path=""
                )
                self.assertEqual(source_path, f"source-{RUN_ID}-{RUN_ATTEMPT}")

                inputs = with_input(block, "node-version-file")
                self.assertEqual(len(inputs), 1)
                rendered = render(inputs[0], source_path)
                self.assertEqual(rendered, f"source-{RUN_ID}-{RUN_ATTEMPT}/.node-version")
                self.assertFalse(rendered.startswith("/"))

                resolved = node_path_join(RUNNER_WORKSPACE, rendered)
                self.assertEqual(
                    resolved,
                    f"{RUNNER_WORKSPACE}/source-{RUN_ID}-{RUN_ATTEMPT}/.node-version",
                )

    def test_absolute_node_version_file_regression_is_rejected(self) -> None:
        """Guard the exact PROD-DEPLOY-01A-R6 failure: a duplicated workspace prefix."""
        workflow = WORKFLOW.read_text()
        for name, next_name in SELF_HOSTED_JOBS:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                for raw in with_input(block, "node-version-file"):
                    self.assertNotIn("${{ env.PARKIO_SOURCE_DIR }}", raw)
                    self.assertNotIn("${{ github.workspace }}", raw)
                    self.assertFalse(raw.startswith("/"))

        # The rejected form is what run 32269510229 actually supplied.
        absolute = f"{RUNNER_WORKSPACE}/source-{RUN_ID}-{RUN_ATTEMPT}/.node-version"
        self.assertEqual(
            node_path_join(RUNNER_WORKSPACE, absolute),
            f"{RUNNER_WORKSPACE}{RUNNER_WORKSPACE}"
            f"/source-{RUN_ID}-{RUN_ATTEMPT}/.node-version",
        )

    def test_checkout_and_setup_node_share_one_workspace_relative_path(self) -> None:
        """The checkout directory and the version-file path can never drift apart."""
        workflow = WORKFLOW.read_text()
        for name, next_name in SELF_HOSTED_JOBS:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                checkout = with_input(block, "path")
                self.assertIn("${{ env.PARKIO_SOURCE_PATH }}", checkout)
                self.assertIn(
                    "node-version-file: ${{ env.PARKIO_SOURCE_PATH }}/.node-version",
                    block,
                )

    def test_absolute_source_dir_still_drives_shell_working_directory(self) -> None:
        """Shell steps keep the absolute path; only action inputs went relative."""
        workflow = WORKFLOW.read_text()
        for name, next_name in SELF_HOSTED_JOBS:
            with self.subTest(job=name):
                block = job_block(workflow, name, next_name)
                self.assertEqual(
                    job_env_value(block, "PARKIO_SOURCE_DIR"),
                    "${{ github.workspace }}/source-"
                    "${{ github.run_id }}-${{ github.run_attempt }}",
                )
                self.assertIn(
                    "working-directory: ${{ env.PARKIO_SOURCE_DIR }}", block
                )
                self.assertEqual(
                    render(job_env_value(block, "PARKIO_SOURCE_DIR"), source_path=""),
                    f"{RUNNER_WORKSPACE}/source-{RUN_ID}-{RUN_ATTEMPT}",
                )

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
