#!/usr/bin/env python3
"""No secret value may be interpolated outside `environment` in a rendered production model (2026-10-13 incident).

Compose interpolates `${VAR}` everywhere in a service, including healthcheck tests, commands, entrypoints and labels.
A value that lands there leaves the environment: `docker inspect` shows it in Config, `docker compose config` prints
it, and a healthcheck or command passes it in process arguments. On 2026-10-13 the redis healthcheck rendered the live
REDIS_PASSWORD this way, and a read-only facts script printed it.

This test renders the models the guarded deploy paths start from, with their example env files and every Compose
profile those files define enabled (profile-gated services are rendered too):
  - hosted-beta: the default deploy-common profile (docker/.env.hosted-beta.example);
  - civo-wrapper: scripts/parkio-prod-compose.sh (docker/.env.azure-hosted-beta.example);
  - invite-production: deploy-common with docker/.env.invite-production.example.
Every secret-like variable (PASSWORD, PASSPHRASE, SECRET, TOKEN, KEY, PRIVATE, CREDENTIAL, WEBHOOK, HEARTBEAT_URL, DSN)
gets a unique sentinel value: those of the example, and those the model's Compose files reference but the example
leaves out. The rendered services must not contain any sentinel outside `environment` and `build` (build arguments
are build-time only, and the sanitized deploy evidence leaves them out). A service that needs a secret at run time
reads it from its own environment with Compose's `$$VAR` escape, so the model and `docker inspect` show only the name.
Nothing is started.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from test_web_api_endpoint_models import clean_env, file_args, hosted_beta_files, wrapper_files  # noqa: E402

SECRET_NAME = re.compile(r"(PASSWORD|PASSPHRASE|SECRET|TOKEN|_KEY|KEY_|PRIVATE|CREDENTIAL|WEBHOOK|HEARTBEAT_URL|DSN)")
LINE = re.compile(r"^\s*([A-Z0-9_]+)\s*=")
REFERENCE = re.compile(r"(?<!\$)\$\{?([A-Za-z_][A-Za-z0-9_]*)")
MISSING = re.compile(r"required variable ([A-Z0-9_]+) is missing")
ALLOWED_PATHS = ("environment", "build")
MODELS = {
    "hosted-beta": "docker/.env.hosted-beta.example",
    "civo-wrapper": "docker/.env.azure-hosted-beta.example",
    "invite-production": "docker/.env.invite-production.example",
}


def sentinel_env(example: Path, files: list) -> tuple[str, dict]:
    """The example's text with every secret-like variable set to a unique sentinel, plus a sentinel for each
    secret-like variable the Compose files reference that the example does not set; and {sentinel: variable}."""
    lines, sentinels, seen = [], {}, set()

    def sentinel(i, name):
        value = f"Sntnl{i:03d}x{name.replace('_', '')[:24]}z"
        sentinels[value] = name
        seen.add(name)
        return f"{name}={value}"
    for i, line in enumerate(example.read_text().splitlines()):
        m = LINE.match(line)
        if m and SECRET_NAME.search(m.group(1)):
            line = sentinel(i, m.group(1))
        lines.append(line)
    referenced = sorted({n for f in files for n in REFERENCE.findall((ROOT / f).read_text()) if SECRET_NAME.search(n)})
    lines += [sentinel(900 + i, n) for i, n in enumerate(referenced) if n not in seen]
    return "\n".join(lines) + "\nPARKIO_IMAGE_TAG=sha-secret-interpolation-test\n", sentinels


def profile_files(example: Path, profile: str) -> list:
    done = subprocess.run(
        ["bash", "-c", 'set -euo pipefail; source scripts/lib/deploy-common.sh; '
                       'parkio_configure_deployment_profile "$1" >/dev/null; '
                       'printf "%s\\n%s" "$PARKIO_DEPLOYMENT_PROFILE" "$PARKIO_COMPOSE_FILES"',
         "_", str(example)], cwd=ROOT, env=clean_env(), capture_output=True, text=True)
    got, _, files = done.stdout.partition("\n")
    if done.returncode != 0 or got != profile:
        raise SystemExit(f"FAIL: deploy-common selected {got!r} for {example.name}, expected {profile}: {done.stderr.strip()[:300]}")
    return file_args(files.split())


def compose(files: list, env_text: str, command: list) -> tuple[str, str]:
    """`docker compose <files> <command>` with env_text; each missing required variable gets a placeholder.
    Returns (stdout, the env text used)."""
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        text = env_text
        args = [a for f in files for a in ("-f", f)]
        for _ in range(80):
            env_file.write_text(text)
            done = subprocess.run(["docker", "compose", "--env-file", str(env_file), *args, *command],
                                  cwd=ROOT, capture_output=True, text=True, env=clean_env())
            if done.returncode == 0:
                return done.stdout, text
            missing = MISSING.search(done.stderr)
            if not missing:
                raise SystemExit(f"FAIL: compose {command[0]} failed: {done.stderr.strip()[:400]}")
            text += f"\n{missing.group(1)}=example.invalid"
        raise SystemExit("FAIL: compose render still misses required variables")


def render(files: list, env_text: str) -> tuple[dict, list]:
    """The model with every profile its files define enabled, and the profile names."""
    out, env_text = compose(files, env_text, ["config", "--profiles"])
    profiles = sorted(p for p in out.split() if p)
    out, _ = compose(files, env_text, [a for p in profiles for a in ("--profile", p)] + ["config", "--format", "json"])
    return json.loads(out), profiles


def leaks(model: dict, sentinels: dict) -> list:
    """(service, path, variable) for every sentinel found outside the allowed paths."""
    found = []

    def walk(node, path, service):
        if isinstance(node, dict):
            for k, v in node.items():
                if not path and k in ALLOWED_PATHS:
                    continue
                walk(v, path + [str(k)], service)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, path + [str(i)], service)
        elif isinstance(node, str):
            for value, name in sentinels.items():
                if value in node:
                    found.append((service, ".".join(path), name))
    for service, definition in sorted((model.get("services") or {}).items()):
        walk(definition, [], service)
    for section in ("networks", "volumes", "configs", "secrets"):
        for name, definition in (model.get(section) or {}).items():
            walk(definition, [section, name], f"<{section}>")
    return found


def main() -> int:
    problems, checked = [], 0
    for name, example in MODELS.items():
        path = ROOT / example
        if name == "civo-wrapper":
            files = wrapper_files(path)
        elif name == "hosted-beta":
            files = hosted_beta_files(path)
        else:
            files = profile_files(path, "invite-production")
        env_text, sentinels = sentinel_env(path, files)
        model, profiles = render(files, env_text)
        rendered = json.dumps(model)
        present = sorted(n for v, n in sentinels.items() if v in rendered)
        checked += len(model.get("services") or {})
        print(f"{name}: {len(model.get('services') or {})} services (profiles: {', '.join(profiles) or 'none'}); "
              f"{len(sentinels)} secret variables; {len(present)} reach the model "
              "(in `environment` or `build` only, if this passes)")
        for service, where, var in leaks(model, sentinels):
            problems.append(f"{name}: {service}.{where} interpolates {var}")
    for p in problems:
        print("FAIL " + p)
    print(f"=== {'FAIL' if problems else 'PASS'}: {checked} rendered services, {len(problems)} secret value(s) outside "
          "environment/build ===")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
