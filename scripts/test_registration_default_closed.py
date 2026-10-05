#!/usr/bin/env python3
"""Registration stays closed by default in every rendered model (owner decision 2026-10-05, H1).

For each model a deploy path renders:
  - local: docker-compose.yml + apps + images (docker/.env.example);
  - hosted-beta: the default deploy-common profile, docker/compose.production.files exactly;
  - civo-wrapper: scripts/parkio-prod-compose.sh, the list plus the Civo Alertmanager overlay;
  - azure-hosted-beta: the deprecated Azure profile, the list with images.yml;
  - invite-dark, invite-public-staged, invite-public: the invite-production edge modes,
it renders `docker compose config` and reads auth-service's PARKIO_REGISTRATION_MODE and, where the
model can build web, web's VITE_REGISTRATION_MODE build argument. It requires:
  * with the example env file as shipped: closed, or no setting, so auth's default (closed) applies;
  * with PARKIO_REGISTRATION_MODE removed from it: closed, or for invite-production, whose edge
    overlays require the variable, a refused render;
  * with an explicit PARKIO_REGISTRATION_MODE=open: open, so opening still works when asked for.
Other variables that a render requires get non-secret placeholders; PARKIO_REGISTRATION_MODE never
does. Nothing is started.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KEY = "PARKIO_REGISTRATION_MODE"
MISSING = re.compile(r"required variable ([A-Z0-9_]+) is missing")
REFUSED = "refused"

EXAMPLES = {
    "local": "docker/.env.example",
    "hosted-beta": "docker/.env.hosted-beta.example",
    "civo-wrapper": "docker/.env.azure-hosted-beta.example",
    "azure-hosted-beta": "docker/.env.azure-hosted-beta.example",
    "invite-dark": "docker/.env.invite-production.example",
    "invite-public-staged": "docker/.env.invite-production.example",
    "invite-public": "docker/.env.invite-production.example",
}
INVITE_EDGE = {
    "invite-dark": {"PARKIO_INVITE_EDGE_MODE": "dark", "PARKIO_INVITE_ACME_AUTHORIZED": "false"},
    "invite-public-staged": {"PARKIO_INVITE_EDGE_MODE": "public", "PARKIO_INVITE_ACME_AUTHORIZED": "false"},
    "invite-public": {"PARKIO_INVITE_EDGE_MODE": "public", "PARKIO_INVITE_ACME_AUTHORIZED": "true"},
}


def file_args(words: list) -> list:
    return [words[i + 1] for i in range(len(words) - 1) if words[i] == "-f"]


def profile_files(profile: str, env_file: Path, extra: dict) -> list:
    """The -f files parkio_configure_deployment_profile gives a profile, read from the real library."""
    env = {k: v for k, v in os.environ.items() if not k.startswith(("PARKIO_INVITE_", "PARKIO_DEPLOYMENT_PROFILE"))}
    env.update(extra, PARKIO_DEPLOYMENT_PROFILE=profile)
    done = subprocess.run(
        ["bash", "-c", 'set -euo pipefail; source scripts/lib/deploy-common.sh; '
                       'parkio_configure_deployment_profile "$1" >/dev/null; printf "%s" "$PARKIO_COMPOSE_FILES"',
         "_", str(env_file)], cwd=ROOT, env=env, capture_output=True, text=True)
    if done.returncode != 0:
        raise SystemExit(f"FAIL: cannot configure the {profile} profile: {done.stderr.strip()}")
    return file_args(done.stdout.split())


def wrapper_files(env_file: Path) -> list:
    """The -f files scripts/parkio-prod-compose.sh passes, captured with a docker shim."""
    with tempfile.TemporaryDirectory() as tmp:
        shim = Path(tmp) / "docker"
        shim.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$ARGV_OUT"\n')
        shim.chmod(0o755)
        argv_out = Path(tmp) / "argv"
        env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}", ARGV_OUT=str(argv_out), PARKIO_ENV_FILE=str(env_file))
        subprocess.run(["bash", "scripts/parkio-prod-compose.sh", "ps"], cwd=ROOT, env=env, check=True,
                       capture_output=True, text=True)
        return [f.removeprefix(f"{ROOT}/") for f in file_args(argv_out.read_text().splitlines())]


def model_files(model: str, env_file: Path) -> list:
    if model == "local":
        return ["docker/docker-compose.yml", "docker/docker-compose.apps.yml", "docker/docker-compose.images.yml"]
    if model == "civo-wrapper":
        return wrapper_files(env_file)
    if model.startswith("invite-"):
        return profile_files("invite-production", env_file, INVITE_EDGE[model])
    return profile_files(model, env_file, {})


def render(files: list, env_text: str, extra_env: dict) -> object:
    """The rendered model, or REFUSED when it needs PARKIO_REGISTRATION_MODE; placeholders fill the rest."""
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        text = env_text + "".join(f"\n{k}={v}" for k, v in extra_env.items()) + "\nPARKIO_IMAGE_TAG=sha-registration-test\n"
        args = [a for f in files for a in ("-f", f)]
        for _ in range(60):
            env_file.write_text(text)
            done = subprocess.run(["docker", "compose", "--env-file", str(env_file), *args, "config", "--format", "json"],
                                  cwd=ROOT, capture_output=True, text=True,
                                  env={k: v for k, v in os.environ.items() if k != KEY})
            if done.returncode == 0:
                return json.loads(done.stdout)
            missing = MISSING.search(done.stderr)
            if not missing:
                raise SystemExit(f"FAIL: compose render failed: {done.stderr.strip()[:400]}")
            if missing.group(1) == KEY:
                return REFUSED
            text += f"\n{missing.group(1)}=example.invalid"
        raise SystemExit("FAIL: compose render still misses required variables")


def registration(model: dict) -> dict:
    """{"auth": value or None, "web-build": value or None} of a rendered model."""
    services = model.get("services") or {}
    env = (services.get("auth-service") or {}).get("environment") or {}
    if isinstance(env, list):
        env = dict(item.split("=", 1) for item in env if "=" in item)
    args = ((services.get("web") or {}).get("build") or {}).get("args") or {}
    if isinstance(args, list):
        args = dict(item.split("=", 1) for item in args if "=" in item)
    return {"auth": env.get(KEY), "web-build": args.get("VITE_REGISTRATION_MODE")}


def without_key(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not re.match(rf"\s*{KEY}\s*=", line))


def check(model: str, case: str, result: object, expected: str) -> list:
    """Problems for one render. `expected` is "closed", "open" or "closed-or-refused"."""
    if result == REFUSED:
        if expected == "closed-or-refused" and model.startswith("invite-"):
            return []
        return [f"{model} / {case}: the render was refused for a missing {KEY}"]
    values = registration(result)
    want = "open" if expected == "open" else "closed"
    problems = []
    # auth-service: no setting means its default, closed; anything set must be the expected value.
    if values["auth"] is None:
        if want == "open":
            problems.append(f"{model} / {case}: auth-service gets no {KEY}, so an explicit open is lost")
    elif values["auth"] != want:
        problems.append(f"{model} / {case}: auth-service {KEY}={values['auth']!r}, expected {want!r}")
    # web: a build argument, where the model has one, must follow the same value.
    if values["web-build"] is not None and values["web-build"] != want:
        problems.append(f"{model} / {case}: web build VITE_REGISTRATION_MODE={values['web-build']!r}, expected {want!r}")
    return problems


def main() -> int:
    problems, lines = [], []
    for model, example in EXAMPLES.items():
        example_path = ROOT / example
        text = example_path.read_text()
        files = model_files(model, example_path)
        extra = INVITE_EDGE.get(model, {})
        cases = [("example as shipped", text, "closed"),
                 (f"{KEY} omitted", without_key(text), "closed-or-refused")]
        if model != "local":  # the local model passes no registration setting to auth at all
            cases.append((f"explicit {KEY}=open", without_key(text) + f"\n{KEY}=open", "open"))
        for case, env_text, expected in cases:
            result = render(files, env_text, extra)
            found = check(model, case, result, expected)
            problems += found
            shown = REFUSED if result == REFUSED else registration(result)
            lines.append(f"{'FAIL' if found else 'PASS'}: {model} / {case} -> {shown}")
    print("\n".join(lines))
    if problems:
        print("FAIL: registration is not closed by default everywhere:", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        return 1
    print(f"=== registration closed by default: PASS ({len(lines)} renders) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
