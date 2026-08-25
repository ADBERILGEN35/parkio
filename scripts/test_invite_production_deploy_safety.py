#!/usr/bin/env python3
"""End-to-end dry-run regression for secret-free invite deployment evidence."""

from __future__ import annotations

import json
import os
import shutil
import shlex
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from lib.assert_invite_production_feature_config_fixture import valid_model


ROOT = Path(__file__).resolve().parents[1]
DEPLOY = ROOT / "scripts" / "deploy-invite-production.sh"
TEMPLATE = ROOT / "docker" / ".env.invite-production.example"
SENTINELS = (
    "SECRET_SENTINEL_DB_PASSWORD",
    "SECRET_SENTINEL_SLACK_URL",
    "SECRET_SENTINEL_RESEND_KEY",
)


def write_fixture_env(target: Path) -> None:
    exact = {
        "PARKIO_PG_HOST": "pg-invite-ci.postgres.database.azure.com",
        "PARKIO_JWT_PRIVATE_KEY_PEM": (
            '"-----BEGIN PRIVATE KEY-----\\nfixture-only\\n-----END PRIVATE KEY-----"'
        ),
        "PARKIO_ACME_EMAIL": "ops@parkio.dev",
        "VITE_MAPTILER_KEY": "SECRET_SENTINEL_MAPTILER_PUBLIC_KEY",
        "PARKIO_RESEND_API_KEY": f"re_{SENTINELS[2]}",
        "PARKIO_PUSH_DELIVERY_ENABLED": "false",
        "PARKIO_PUSH_DELIVERY_PROVIDER": "noop",
        "PARKIO_EXPO_ACCESS_TOKEN": "",
        "PARKIO_ALERT_SLACK_WEBHOOK_URL": (
            f"https://hooks.slack.com/services/{SENTINELS[1]}/fixture/fixture"
        ),
        "PARKIO_ALERT_WEBHOOK_URL": "https://alerts.parkio.dev/test-receiver",
        "KAFKA_CLUSTER_ID": "Q0lJbnZpdGVQcm9kMDFBQQ",
    }
    lines: list[str] = []
    for line in TEMPLATE.read_text().splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            lines.append(line)
            continue
        key, value = line.split("=", 1)
        if key in exact:
            lines.append(f"{key}={exact[key]}")
        elif "REPLACE_ME" in value:
            marker = (
                f"{SENTINELS[0]}_{key}_0123456789abcdef"
                if "POSTGRES" in key or "PASSWORD" in key
                else f"SECRET_SENTINEL_{key}_0123456789abcdef"
            )
            lines.append(f'{key}="{marker}"')
        else:
            lines.append(line)
    target.write_text("\n".join(lines) + "\n")
    target.chmod(0o600)


def write_node_wrapper(target: Path) -> None:
    real_node = shutil.which("node")
    if real_node is None:
        raise RuntimeError("Node is required to exercise the Compose sanitizer")
    expected = (ROOT / ".node-version").read_text().strip()
    target.write_text(
        "#!/usr/bin/env sh\n"
        f"if [ \"${{1:-}}\" = --version ]; then printf '%s\\n' 'v{expected}'; exit 0; fi\n"
        f"exec '{real_node}' \"$@\"\n"
    )
    target.chmod(target.stat().st_mode | stat.S_IXUSR)


class InviteProductionDeploySafetyTest(unittest.TestCase):
    def test_dry_run_persists_only_sanitized_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            artifact_dir = root / "artifacts"
            fake_bin = root / "bin"
            fake_bin.mkdir()
            write_fixture_env(env_file)

            fake_node = fake_bin / "node"
            write_node_wrapper(fake_node)

            fake_docker = fake_bin / "docker"
            compose_model = valid_model()
            compose_model["name"] = "parkio"
            memory_mib = {
                "ai-validation-service": 640,
                "alertmanager": 128,
                "analytics-service": 640,
                "auth-service": 768,
                "blackbox-exporter": 64,
                "caddy": 256,
                "clamav": 3072,
                "gamification-service": 640,
                "gateway-service": 640,
                "grafana": 384,
                "kafka": 1280,
                "kafka-exporter": 128,
                "loki": 512,
                "media-service": 768,
                "minio": 512,
                "moderation-service": 640,
                "node-exporter": 64,
                "notification-service": 640,
                "parking-service": 768,
                "prometheus": 1024,
                "promtail": 128,
                "redis": 384,
                "tempo": 512,
                "user-service": 640,
                "web": 128,
            }
            for service, memory in memory_mib.items():
                compose_model["services"].setdefault(service, {})
                compose_model["services"][service]["mem_limit"] = memory * 1024**2
            compose_model["services"]["minio-setup"] = {
                "depends_on": {"minio": {}}
            }
            compose_model["services"]["grafana"]["depends_on"] = {
                "loki": {},
                "prometheus": {},
                "tempo": {},
            }
            compose_model["services"]["media-service"]["depends_on"] = {
                "clamav": {},
                "minio": {},
                "minio-setup": {},
            }
            compose_model["services"]["ai-validation-service"].update({
                "image": "parkio/api:sha-test",
                "environment": {
                    "POSTGRES_PASSWORD": SENTINELS[0],
                    "PARKIO_ALERT_SLACK_WEBHOOK_URL": SENTINELS[1],
                    "PARKIO_RESEND_API_KEY": SENTINELS[2],
                },
                "ports": [
                    {"host_ip": "127.0.0.1", "published": "8080", "target": 8080}
                ],
                "healthcheck": {
                    "test": ["CMD-SHELL", f"probe {SENTINELS[0]}"]
                },
            })
            compose_model_json = shlex.quote(
                json.dumps(compose_model, separators=(",", ":"))
            )
            fake_docker.write_text(
                "#!/usr/bin/env sh\n"
                "case \"$*\" in\n"
                "  *\"config --quiet\"*) exit 0 ;;\n"
                "  *\"config --format json\"*)\n"
                f"    printf '%s\\n' {compose_model_json} ;;\n"
                "  *\"image inspect\"*) exit 1 ;;\n"
                "  \"version --format Docker server {{.Server.Version}}\") exit 0 ;;\n"
                "  \"compose version\") exit 0 ;;\n"
                "  *) echo \"unexpected fake docker invocation\" >&2; exit 9 ;;\n"
                "esac\n"
            )
            fake_docker.chmod(fake_docker.stat().st_mode | stat.S_IXUSR)

            sha = subprocess.check_output(
                ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True
            ).strip()
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{fake_bin}:{environment['PATH']}",
                    "PARKIO_ENV_FILE": str(env_file),
                    "PARKIO_DEPLOY_ARTIFACT_DIR": str(artifact_dir),
                    "PARKIO_DEPLOY_OPERATOR": "test",
                    "PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK": "1",
                    "PARKIO_DISK_FREE_BYTES_FOR_TEST": str(50 * 1024**3),
                    "PARKIO_IMAGE_TAG": f"sha-{sha}",
                    "PARKIO_IMAGE_CREATED": "1970-01-01T00:00:00Z",
                    "PARKIO_NODE_BINARY": str(fake_node),
                    "PARKIO_REQUESTED_DARK_GATEWAY_URL_INPUT_EVIDENCE": "<blank>",
                    "PARKIO_RAW_DARK_GATEWAY_INPUT_BLANK": "true",
                    "PARKIO_EFFECTIVE_DARK_GATEWAY_URL": "http://127.0.0.1:8080",
                    "PARKIO_DARK_GATEWAY_INPUT_SOURCE": "workflow_dispatch",
                }
            )
            result = subprocess.run(
                [
                    str(DEPLOY),
                    "--dry-run",
                    "--allow-dirty",
                    "--expected-sha",
                    sha,
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

            evidence = result.stdout + result.stderr
            for file in artifact_dir.rglob("*"):
                if file.is_file():
                    evidence += file.read_text(errors="replace")
            for sentinel in SENTINELS:
                self.assertNotIn(sentinel, evidence)
            self.assertFalse((artifact_dir / "compose-config.rendered.yml").exists())

            resource_budget = json.loads(
                (artifact_dir / "resource-budget.json").read_text()
            )
            self.assertEqual(resource_budget["resourceBudgetProfile"], "invite-production")
            self.assertEqual(resource_budget["configuredMemoryMiB"], 15360)
            self.assertEqual(resource_budget["continuousRuntimeMemoryMiB"], 14976)
            self.assertEqual(resource_budget["resourceCeilingMiB"], 16384)
            self.assertEqual(resource_budget["configuredHeadroomMiB"], 1024)
            self.assertIs(resource_budget["resourceBudgetWithinCeiling"], True)
            self.assertEqual(resource_budget["clamavMemoryMiB"], 3072)
            self.assertEqual(resource_budget["expectedContinuousServiceCount"], 23)
            self.assertEqual(resource_budget["resolvedContinuousServiceCount"], 23)

            structure = json.loads((artifact_dir / "compose-structure.json").read_text())
            self.assertEqual(structure["services"][0]["environmentNames"], [
                "PARKIO_ALERT_SLACK_WEBHOOK_URL",
                "PARKIO_RESEND_API_KEY",
                "POSTGRES_PASSWORD",
            ])
            manifest = next(artifact_dir.glob("deploy-*.json"))
            manifest_json = json.loads(manifest.read_text())
            self.assertEqual(manifest_json["gitSha"], sha)
            self.assertEqual(manifest_json["composeStructure"], structure)
            self.assertEqual(
                manifest_json["effectiveFeatureConfiguration"]["source"],
                "resolved-compose-model",
            )
            self.assertEqual(
                manifest_json["effectiveFeatureConfiguration"]["parkingEnvironment"]
                ["PARKIO_MUNICIPAL_ENABLED"],
                "true",
            )
            self.assertEqual(manifest_json["requestedDarkGatewayUrlInput"], "<blank>")
            self.assertIs(manifest_json["rawDarkGatewayInputBlank"], True)
            self.assertEqual(
                manifest_json["effectiveDarkGatewayUrl"], "http://127.0.0.1:8080"
            )
            self.assertEqual(manifest_json["darkGatewayInputSource"], "workflow_dispatch")

    def test_public_gateway_target_refuses_before_artifact_creation(self) -> None:
        """PROD-DEPLOY-01A / D1: the deploy itself must fail closed on a target
        that is not the dark endpoint, before it builds or starts anything.

        Every URL below satisfied the old `!= https://api.parkio.dev` check while
        resolving to the live hosted-beta VM or somewhere else entirely.
        """
        refused = (
            "https://api.parkio.dev",
            "https://api.parkio.dev/",
            "https://api.parkio.dev:443",
            "http://api.parkio.dev",
            "https://app.parkio.dev",
            "https://media.parkio.dev",
            "http://localhost:8080",
            "http://127.0.0.1:80",
            "http://127.0.0.1:8081",
            "http://10.0.0.5:8080",
            "http://user:pass@127.0.0.1:8080",
            "https://evil.example.com",
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            write_fixture_env(env_file)
            fake_node = root / "node"
            write_node_wrapper(fake_node)

            for url in refused:
                artifact_dir = root / f"artifacts-{abs(hash(url))}"
                environment = os.environ.copy()
                environment.update(
                    {
                        "PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK": "1",
                        "PARKIO_DISK_FREE_BYTES_FOR_TEST": str(50 * 1024**3),
                        "PARKIO_GATEWAY_URL": url,
                        "PARKIO_NODE_BINARY": str(fake_node),
                    }
                )
                result = subprocess.run(
                    [
                        str(DEPLOY),
                        "--dry-run",
                        "--allow-dirty",
                        "--env-file",
                        str(env_file),
                        "--artifact-dir",
                        str(artifact_dir),
                    ],
                    cwd=ROOT,
                    env=environment,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(result.returncode, 0, f"deploy accepted {url!r}")
                # The guard — not some unrelated error — must be what refused.
                # Credential-bearing URLs get a distinct message that never echoes
                # the userinfo back, so match the shared prefix.
                self.assertIn(
                    "dark gateway url",
                    (result.stderr + result.stdout).lower(),
                    f"deploy did not explain its refusal of {url!r}",
                )
                self.assertFalse(artifact_dir.exists(), f"artifacts written for {url!r}")

    def test_dark_gateway_target_is_accepted(self) -> None:
        """The one intended endpoint must still get through the same guard."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            artifact_dir = root / "artifacts"
            write_fixture_env(env_file)
            fake_node = root / "node"
            write_node_wrapper(fake_node)
            environment = os.environ.copy()
            environment.update(
                {
                    "PARKIO_PREFLIGHT_ALLOW_NO_ALERT_WEBHOOK": "1",
                    "PARKIO_DISK_FREE_BYTES_FOR_TEST": str(50 * 1024**3),
                    "PARKIO_GATEWAY_URL": "http://127.0.0.1:8080",
                    "PARKIO_IMAGE_CREATED": "1970-01-01T00:00:00Z",
                    "PARKIO_NODE_BINARY": str(fake_node),
                }
            )
            result = subprocess.run(
                [
                    str(DEPLOY),
                    "--dry-run",
                    "--allow-dirty",
                    "--env-file",
                    str(env_file),
                    "--artifact-dir",
                    str(artifact_dir),
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotIn("refusing dark gateway url", result.stderr.lower())

    def test_wrong_sha_refuses_before_artifact_creation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            artifact_dir = root / "artifacts"
            write_fixture_env(env_file)
            fake_node = root / "node"
            write_node_wrapper(fake_node)
            result = subprocess.run(
                [
                    str(DEPLOY),
                    "--dry-run",
                    "--allow-dirty",
                    "--expected-sha",
                    "0" * 40,
                    "--env-file",
                    str(env_file),
                    "--artifact-dir",
                    str(artifact_dir),
                ],
                cwd=ROOT,
                env={**os.environ, "PARKIO_NODE_BINARY": str(fake_node)},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 2)
            self.assertFalse(artifact_dir.exists())

    def test_missing_node_refuses_before_artifact_creation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            artifact_dir = root / "artifacts"
            write_fixture_env(env_file)
            result = subprocess.run(
                [
                    str(DEPLOY),
                    "--dry-run",
                    "--allow-dirty",
                    "--env-file",
                    str(env_file),
                    "--artifact-dir",
                    str(artifact_dir),
                ],
                cwd=ROOT,
                env={**os.environ, "PARKIO_NODE_BINARY": "/nonexistent/parkio-node"},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 3)
            self.assertIn("runtime is unavailable", result.stderr)
            self.assertFalse(artifact_dir.exists())

    def test_wrong_node_refuses_before_artifact_creation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / "input.env"
            artifact_dir = root / "artifacts"
            wrong_node = root / "node"
            write_fixture_env(env_file)
            wrong_node.write_text("#!/usr/bin/env sh\nprintf '%s\\n' 'v22.23.1'\n")
            wrong_node.chmod(wrong_node.stat().st_mode | stat.S_IXUSR)
            result = subprocess.run(
                [
                    str(DEPLOY),
                    "--dry-run",
                    "--allow-dirty",
                    "--env-file",
                    str(env_file),
                    "--artifact-dir",
                    str(artifact_dir),
                ],
                cwd=ROOT,
                env={**os.environ, "PARKIO_NODE_BINARY": str(wrong_node)},
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertEqual(result.returncode, 3)
            self.assertIn("wrong Node.js runtime", result.stderr)
            self.assertFalse(artifact_dir.exists())


if __name__ == "__main__":
    unittest.main()
