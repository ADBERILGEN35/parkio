#!/usr/bin/env python3

from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "scripts" / "assert-invite-production-artifacts-safe.py"


class ArtifactScannerTest(unittest.TestCase):
    def run_scan(self, env: Path, evidence: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(SCANNER), "--env-file", str(env), str(evidence)],
            check=False,
            text=True,
            capture_output=True,
        )

    def test_accepts_names_only_structure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / "input.env"
            env.write_text("POSTGRES_PASSWORD=SECRET_SENTINEL_DB_PASSWORD\n")
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "manifest.json").write_text(
                '{"environmentNames":["POSTGRES_PASSWORD"],"gitSha":"abc"}\n'
            )
            result = self.run_scan(env, evidence)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_rejects_a_sentinel_without_printing_it(self) -> None:
        sentinel = "SECRET_SENTINEL_SLACK_URL"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / "input.env"
            env.write_text(f"PARKIO_ALERT_SLACK_WEBHOOK_URL={sentinel}\n")
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "bad.log").write_text(f"leaked={sentinel}\n")
            result = self.run_scan(env, evidence)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("PARKIO_ALERT_SLACK_WEBHOOK_URL", result.stdout)
            self.assertNotIn(sentinel, result.stdout + result.stderr)

    def test_rejects_legacy_resolved_compose_artifact_by_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / "input.env"
            env.write_text("POSTGRES_PASSWORD=safe-fixture-password\n")
            evidence = root / "evidence"
            evidence.mkdir()
            (evidence / "compose-config.rendered.yml").write_text("services: {}\n")
            result = self.run_scan(env, evidence)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("forbidden resolved Compose artifact", result.stdout)

    def test_direct_scanner_still_errors_on_missing_path(self) -> None:
        """Workflow must skip missing evidence via finalize; scanner stays strict."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = root / "input.env"
            env.write_text("POSTGRES_PASSWORD=safe-fixture-password\n")
            missing = root / "deploy-artifacts" / "invite-production"
            result = self.run_scan(env, missing)
            self.assertNotEqual(result.returncode, 0)
            combined = result.stdout + result.stderr
            self.assertIn("evidence path does not exist", combined)


if __name__ == "__main__":
    unittest.main()
