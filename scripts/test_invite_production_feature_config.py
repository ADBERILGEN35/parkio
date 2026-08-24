#!/usr/bin/env python3
"""Focused regression tests for the R9K resolved-feature assertion."""

from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path

from lib.assert_invite_production_feature_config_fixture import valid_model


ROOT = Path(__file__).resolve().parents[1]
ASSERT = ROOT / "scripts" / "lib" / "assert-invite-production-feature-config.py"


class InviteProductionFeatureConfigTest(unittest.TestCase):
    def run_assertion(self, model: dict) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["python3", str(ASSERT), "--evidence"],
            input=json.dumps(model),
            text=True,
            capture_output=True,
            check=False,
        )

    def test_certified_matrix_passes_and_emits_only_allowlisted_values(self) -> None:
        result = self.run_assertion(valid_model())
        self.assertEqual(result.returncode, 0, result.stderr)
        evidence = json.loads(result.stdout)
        self.assertEqual(evidence["source"], "resolved-compose-model")
        self.assertEqual(
            evidence["parkingEnvironment"]["PARKIO_SPA_RANKING_STRATEGY"],
            "DETERMINISTIC_V1",
        )
        self.assertNotIn("DATABASE_PASSWORD", result.stdout)

    def test_missing_required_on_key_fails_closed(self) -> None:
        model = valid_model()
        del model["services"]["parking-service"]["environment"]["PARKIO_MUNICIPAL_IZUM_ENABLED"]
        result = self.run_assertion(model)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("PARKIO_MUNICIPAL_IZUM_ENABLED is absent", result.stderr)

    def test_disabled_required_feature_fails(self) -> None:
        model = valid_model()
        model["services"]["parking-service"]["environment"]["PARKIO_SPA_RECOMMENDATIONS_ENABLED"] = "false"
        result = self.run_assertion(model)
        self.assertNotEqual(result.returncode, 0)

    def test_frontend_backend_parity_failure_is_explicit(self) -> None:
        model = valid_model()
        model["services"]["parking-service"]["environment"]["PARKIO_MUNICIPAL_ENABLED"] = "false"
        result = self.run_assertion(model)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("frontend/backend parity is broken", result.stderr)


if __name__ == "__main__":
    unittest.main()
