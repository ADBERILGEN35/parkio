#!/usr/bin/env python3
"""Regression: JWT PEM preflight must not false-fail on base64 TODO/FIXME tokens."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PREFLIGHT = ROOT / "scripts" / "preflight-hosted-beta.sh"
TEMPLATE = ROOT / "docker" / ".env.invite-production.example"


def _write_env(target: Path, jwt_body: str) -> None:
    exact = {
        "PARKIO_PG_HOST": "pg-invite-ci.postgres.database.azure.com",
        "PARKIO_JWT_PRIVATE_KEY_PEM": f'"{jwt_body}"',
        "PARKIO_ACME_EMAIL": "ops@parkio.dev",
        "VITE_MAPTILER_KEY": "SECRET_SENTINEL_MAPTILER_PUBLIC_KEY",
        "PARKIO_RESEND_API_KEY": "re_SECRET_SENTINEL_RESEND_KEY",
        "PARKIO_PUSH_DELIVERY_ENABLED": "false",
        "PARKIO_PUSH_DELIVERY_PROVIDER": "noop",
        "PARKIO_EXPO_ACCESS_TOKEN": "",
        "PARKIO_ALERT_SLACK_WEBHOOK_URL": (
            "https://hooks.slack.com/services/SECRET_SENTINEL_SLACK_URL/fixture/fixture"
        ),
        "PARKIO_ALERT_WEBHOOK_URL": "https://alerts.parkio.dev/test-receiver",
        "KAFKA_CLUSTER_ID": "Q0lJbnZpdGVQcm9kMDFBQQ",
    }
    lines: list[str] = []
    for line in TEMPLATE.read_text(encoding="utf-8").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            lines.append(line)
            continue
        key, value = line.split("=", 1)
        if key in exact:
            lines.append(f"{key}={exact[key]}")
        elif "REPLACE_ME" in value:
            lines.append(f'{key}="SECRET_SENTINEL_{key}_0123456789abcdef"')
        else:
            lines.append(line)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    target.chmod(0o600)


def _run_preflight(env_file: Path) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["PARKIO_ENV_FILE"] = str(env_file)
    env["PARKIO_DEPLOYMENT_PROFILE"] = "invite-production"
    env["PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK"] = "1"
    return subprocess.run(
        ["bash", str(PREFLIGHT), "--skip-compose", "--deployment-profile", "invite-production"],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


class PreflightJwtPlaceholderTests(unittest.TestCase):
    def test_template_replace_me_still_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env_path = Path(tmp) / "ci.env"
            _write_env(env_path, "REPLACE_ME_pkcs8_pem_with_newline_escapes")
            result = _run_preflight(env_path)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("JWT private key is a placeholder", result.stdout + result.stderr)

    def test_pem_with_todo_substring_passes_jwt_check(self) -> None:
        # Deterministic body containing TODO/FIXME/YOUR_/DUMMY/SAMPLE_ which the
        # broad is_placeholder heuristic would reject, but a real PKCS#8 PEM
        # structure must still pass the JWT-specific check.
        body = (
            "-----BEGIN PRIVATE KEY-----\\n"
            "fixture-TODO-FIXME-YOUR_DUMMY_SAMPLE_only\\n"
            "-----END PRIVATE KEY-----"
        )
        with tempfile.TemporaryDirectory() as tmp:
            env_path = Path(tmp) / "ci.env"
            _write_env(env_path, body)
            result = _run_preflight(env_path)
            combined = result.stdout + result.stderr
            self.assertNotIn("JWT private key is a placeholder", combined)
            # Other preflight failures may remain; JWT placeholder must not.
            self.assertNotRegex(combined, r"FAIL PARKIO_JWT_PRIVATE_KEY_PEM: JWT private key is a placeholder")


if __name__ == "__main__":
    unittest.main()
