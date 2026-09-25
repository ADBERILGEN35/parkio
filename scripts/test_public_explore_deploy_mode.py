#!/usr/bin/env python3
"""01E-A1: source-only dispatch/render/Compose/manifest contract; never deploy."""
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess
import tempfile
import unittest

from lib.assert_invite_production_feature_config_fixture import MODULE, valid_model

ROOT = Path(__file__).resolve().parents[1]
TOKEN = "GOOGLE-STARTUP-REAPPLY-01E-B"


def on_model():
    model = valid_model()
    for service in ("parking-service", "gateway-service"):
        model["services"][service]["environment"]["PARKIO_PUBLIC_EXPLORE_ENABLED"] = "true"
    model["services"]["parking-service"]["environment"]["PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES"] = "izum"
    model["services"]["web"]["build"]["args"]["VITE_PUBLIC_EXPLORE_ENABLED"] = "true"
    return model


class ExploreDeployModeTest(unittest.TestCase):
    def dispatch_env(self, mode="off", token=""):
        env = {k: v for k, v in os.environ.items() if not k.startswith("PARKIO_DISPATCH_")}
        env.update(PARKIO_DISPATCH_INVITE_EDGE_MODE="public",
                   PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED="true",
                   PARKIO_DISPATCH_REGISTRATION_MODE="closed",
                   PARKIO_DISPATCH_CUTOVER_AUTHORIZATION="PROD-DEPLOY-01B-03E-B",
                   PARKIO_DISPATCH_PUBLIC_EXPLORE_AUTHORIZATION=token)
        if mode is not None:
            env["PARKIO_DISPATCH_PUBLIC_EXPLORE_MODE"] = mode
        return env

    def apply(self, path, env):
        return subprocess.run(["bash", "scripts/apply-invite-production-deploy-dispatch.sh",
                               "--env-file", str(path)], cwd=ROOT, env=env,
                              capture_output=True, text=True)

    def test_missing_off_and_authorized_on_then_off_rollback(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "candidate.env"
            for mode, token, enabled, sources in [(None, "", "false", ""),
                    ("off", "", "false", ""), ("izum-readonly", TOKEN, "true", "izum"),
                    ("off", "", "false", "")]:
                if not path.exists():
                    path.write_text("UNRELATED=value\n")
                result = self.apply(path, self.dispatch_env(mode, token))
                self.assertEqual(result.returncode, 0, result.stderr)
                values = dict(line.split("=", 1) for line in path.read_text().splitlines())
                self.assertEqual(values["PARKIO_PUBLIC_EXPLORE_ENABLED"], enabled)
                self.assertEqual(values["VITE_PUBLIC_EXPLORE_ENABLED"], enabled)
                self.assertEqual(values["PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES"], sources)
                self.assertEqual(values["PARKIO_REGISTRATION_MODE"], "closed")
                self.assertEqual(values["VITE_REGISTRATION_MODE"], "closed")
                self.assertEqual(values["UNRELATED"], "value")

    def test_invalid_dispatch_never_edits_env(self):
        cases = [("izum-readonly", ""), ("izum-readonly", "PROD-DEPLOY-01B-03E-B"),
                 ("all", TOKEN), ("true", TOKEN), ("off", TOKEN)]
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "candidate.env"
            for mode, token in cases:
                path.write_text("UNCHANGED=1\n")
                self.assertNotEqual(self.apply(path, self.dispatch_env(mode, token)).returncode, 0)
                self.assertEqual(path.read_text(), "UNCHANGED=1\n")
            for key, value in [("PARKIO_DISPATCH_REGISTRATION_MODE", "open"),
                               ("PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED", "false")]:
                env = self.dispatch_env("izum-readonly", TOKEN)
                env[key] = value
                self.assertNotEqual(self.apply(path, env).returncode, 0)
                self.assertEqual(path.read_text(), "UNCHANGED=1\n")

    def test_resolved_model_modes_and_safe_evidence(self):
        self.assertEqual(MODULE.validate(valid_model())["publicExploreMode"], "off")
        evidence = MODULE.validate(on_model(), "izum-readonly", TOKEN)
        self.assertEqual(evidence["publicExploreMode"], "izum-readonly")
        self.assertEqual(evidence["registrationMode"], "closed")
        self.assertNotIn("must-never-appear", json.dumps(evidence))
        for mode, token in [("izum-readonly", ""), ("unknown", TOKEN), ("off", TOKEN)]:
            with self.assertRaises(ValueError):
                MODULE.validate(on_model(), mode, token)

    def test_empty_excluded_wildcard_and_multiple_sources_are_rejected(self):
        for source in ("", "ispark", "anpark", "konya", "kayseri", "osm", "izelman",
                       "community", "all", "*", "izum,ispark", "izum,izum", "IZUM"):
            model = on_model()
            model["services"]["parking-service"]["environment"]["PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES"] = source
            with self.subTest(source=source), self.assertRaises(ValueError):
                MODULE.validate(model, "izum-readonly", TOKEN)

    def test_every_split_brain_and_registration_open_is_rejected(self):
        for service in ("parking-service", "gateway-service", "web"):
            for enabled, factory, mode, token in [("false", on_model, "izum-readonly", TOKEN),
                                                  ("true", valid_model, "off", "")]:
                model = factory()
                if service == "web":
                    model["services"][service]["build"]["args"]["VITE_PUBLIC_EXPLORE_ENABLED"] = enabled
                else:
                    model["services"][service]["environment"]["PARKIO_PUBLIC_EXPLORE_ENABLED"] = enabled
                with self.subTest(service=service, mode=mode), self.assertRaises(ValueError):
                    MODULE.validate(model, mode, token)
        for service in ("auth-service", "web"):
            model = on_model()
            if service == "web":
                model["services"][service]["build"]["args"]["VITE_REGISTRATION_MODE"] = "open"
            else:
                model["services"][service]["environment"]["PARKIO_REGISTRATION_MODE"] = "open"
            with self.assertRaises(ValueError):
                MODULE.validate(model, "izum-readonly", TOKEN)

    def test_real_compose_off_on_and_off_rollback(self):
        docker = next((path for name in ("docker", "docker.exe")
                       if (path := shutil.which(name)) and subprocess.run(
                           [path, "compose", "version"], capture_output=True).returncode == 0), None)
        self.assertIsNotNone(docker, "Docker Compose config is required (no daemon needed)")
        files = ["docker-compose.yml", "docker-compose.apps.yml", "docker-compose.images.yml",
                 "docker-compose.hosted-beta.yml", "docker-compose.managed-db.yml", "docker-compose.invite-public.yml"]
        with tempfile.TemporaryDirectory(prefix=".explore-contract-", dir=ROOT) as work:
            path = Path(work) / "candidate.env"
            path.write_bytes((ROOT / "docker/.env.invite-production.example").read_bytes())
            for mode, token in [("off", ""), ("izum-readonly", TOKEN), ("off", "")]:
                env = self.dispatch_env(mode, token)
                result = self.apply(path, env)
                self.assertEqual(result.returncode, 0, result.stderr)
                values = {"PARKIO_IMAGE_TAG": "sha-explore-contract", "PARKIO_GIT_SHA": "0" * 40,
                          "PARKIO_IMAGE_CREATED": "2026-09-05T00:00:00Z"}
                env.update(values)
                env["WSLENV"] = ":".join(filter(None, [env.get("WSLENV", ""), *values]))
                args = [docker, "compose", "--env-file", str(path.relative_to(ROOT))]
                for file in files:
                    args += ["-f", "docker/" + file]
                result = subprocess.run(args + ["config", "--format", "json"], cwd=ROOT,
                                        env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(MODULE.validate(json.loads(result.stdout), mode, token)["publicExploreMode"], mode)

    def test_workflow_pre_reviewer_and_pre_mutation_guards(self):
        workflow = (ROOT / ".github/workflows/invite-production-deploy.yml").read_text()
        build = workflow.split("  build-images:\n", 1)[1].split("  runner-acceptance:\n", 1)[0]
        deploy = workflow.split("  deploy:\n", 1)[1].split("  rollback:\n", 1)[0]
        self.assertIn('default: "off"', workflow)
        self.assertIn("Validate explore dispatch before production reviewer gate", build)
        self.assertIn("./scripts/apply-invite-production-deploy-dispatch.sh --env-file", build)
        self.assertIn("needs: build-images", deploy)
        self.assertIn("environment: invite-production", deploy)
        self.assertIn("PARKIO_DISPATCH_PUBLIC_EXPLORE_MODE: ${{ inputs.public_explore_mode || 'off' }}", deploy)
        script = (ROOT / "scripts/deploy-invite-production.sh").read_text()
        self.assertLess(script.index('parkio_effective_feature_configuration_json "$ENV_FILE"'),
                        script.index('"$ROOT/scripts/stage-invite-production-release.sh"'))
        self.assertLess(script.index('izum-readonly requires the public-cutover deployment profile'),
                        script.index('"$ROOT/scripts/stage-invite-production-release.sh"'))

    def test_manifest_feature_evidence_uses_the_explicit_mode_and_authorization(self):
        for mode, token, model in [("off", "", valid_model()), ("izum-readonly", TOKEN, on_model())]:
            # Execute the canonical manifest helper, replacing only Compose I/O.
            # No env file is rendered and no deployment operation is invoked.
            source = "source scripts/lib/deploy-common.sh; "
            source += "parkio_compose() { printf '%s' " + shlex.quote(json.dumps(model)) + "; }; "
            source += 'parkio_effective_feature_configuration_json unused-test-env'
            result = subprocess.run(["bash", "-c", source], cwd=ROOT,
                                    env=self.dispatch_env(mode, token), capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            evidence = json.loads(result.stdout)
            self.assertEqual(evidence["publicExploreMode"], mode)
            self.assertEqual(evidence["registrationMode"], "closed")
            self.assertNotIn("DATABASE_PASSWORD", result.stdout)


if __name__ == "__main__":
    unittest.main()
