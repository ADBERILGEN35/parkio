#!/usr/bin/env python3
"""Regression coverage for pre-review invite-production dispatch evidence."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ATTEST = ROOT / "scripts" / "attest-invite-production-dark-gateway-input.sh"
WORKFLOW = ROOT / ".github" / "workflows" / "invite-production-deploy.yml"


def job_block(workflow: str, job: str, next_job: str | None) -> str:
    start = workflow.index(f"  {job}:\n")
    end = workflow.index(f"  {next_job}:\n", start) if next_job else len(workflow)
    return workflow[start:end]


class DispatchInputEvidenceTest(unittest.TestCase):
    def run_attestation(self, raw_input: str) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            github_env = Path(directory) / "github.env"
            environment = os.environ.copy()
            environment.update(
                {
                    "GITHUB_EVENT_NAME": "workflow_dispatch",
                    "PARKIO_DISPATCH_ACTION": "deploy",
                    "PARKIO_DARK_GATEWAY_INPUT": raw_input,
                    "GITHUB_ENV": str(github_env),
                }
            )
            result = subprocess.run(
                [str(ATTEST)],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            published = github_env.read_text() if github_env.exists() else ""
            return result, published

    def test_blank_input_passes_and_publishes_exact_evidence(self) -> None:
        result, published = self.run_attestation("")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("requestedDarkGatewayUrlInput=<blank>", result.stdout)
        self.assertIn("rawDarkGatewayInputBlank=true", result.stdout)
        self.assertIn("effectiveDarkGatewayUrl=http://127.0.0.1:8080", result.stdout)
        self.assertIn("darkGatewayInputSource=workflow_dispatch", result.stdout)
        self.assertIn("PARKIO_RAW_DARK_GATEWAY_INPUT_BLANK=true\n", published)
        self.assertIn("PARKIO_EFFECTIVE_DARK_GATEWAY_URL=http://127.0.0.1:8080\n", published)
        self.assertIn("PARKIO_GATEWAY_URL=http://127.0.0.1:8080\n", published)

    def test_explicit_loopback_url_fails(self) -> None:
        result, published = self.run_attestation("http://127.0.0.1:8080")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(published, "")

    def test_public_api_url_fails(self) -> None:
        result, published = self.run_attestation("https://api.parkio.dev")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(published, "")

    def test_whitespace_only_input_fails_without_trimming(self) -> None:
        result, published = self.run_attestation("  \t")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(published, "")

    def test_workflow_attests_before_dry_run_and_environment_gate(self) -> None:
        workflow = WORKFLOW.read_text()
        build = job_block(workflow, "build-images", "runner-acceptance")
        deploy = job_block(workflow, "deploy", "rollback")

        attestation = "./scripts/attest-invite-production-dark-gateway-input.sh"
        self.assertIn(attestation, build)
        self.assertLess(build.index(attestation), build.index("Dry-run invite-production deploy"))
        self.assertIn(
            "if: ${{ github.event_name == 'workflow_dispatch' && inputs.action == 'deploy' }}",
            build,
        )
        self.assertIn("PARKIO_DARK_GATEWAY_INPUT: ${{ inputs.dark_gateway_url }}", build)
        self.assertIn("needs: build-images", deploy)
        self.assertIn("environment: invite-production", deploy)
        self.assertIn(attestation, deploy)
        self.assertNotIn("continue-on-error:", build)


if __name__ == "__main__":
    unittest.main()
