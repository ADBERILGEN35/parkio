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
        self.assertEqual(
            evidence["gatewayEnvironment"]["PARKIO_PUBLIC_EXPLORE_ENABLED"],
            "false",
        )
        self.assertEqual(
            evidence["webBuildArguments"]["VITE_PUBLIC_EXPLORE_ENABLED"],
            "false",
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

    def test_public_explore_must_remain_off_at_all_three_layers(self) -> None:
        for service, section, key in [
            ("parking-service", "environment", "PARKIO_PUBLIC_EXPLORE_ENABLED"),
            ("gateway-service", "environment", "PARKIO_PUBLIC_EXPLORE_ENABLED"),
            ("web", "args", "VITE_PUBLIC_EXPLORE_ENABLED"),
        ]:
            model = valid_model()
            target = model["services"][service]
            values = target["build"][section] if service == "web" else target[section]
            values[key] = "true"
            result = self.run_assertion(model)
            self.assertNotEqual(result.returncode, 0, service)

    def test_public_source_allowlist_must_be_empty_while_production_is_off(self) -> None:
        model = valid_model()
        model["services"]["parking-service"]["environment"][
            "PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES"
        ] = "izum"
        result = self.run_assertion(model)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
