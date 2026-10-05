#!/usr/bin/env python3
"""Registration settings are off by default, and explicit ones reach auth, in every rendered model
(owner decisions 2026-10-05: H1, and F-INV-1 option (a)).

For each model a deploy path renders:
  - local: docker-compose.yml + apps + images (docker/.env.example);
  - hosted-beta: the default deploy-common profile, docker/compose.production.files exactly;
  - civo-wrapper: scripts/parkio-prod-compose.sh, the list plus the Civo Alertmanager overlay;
  - azure-hosted-beta: the deprecated Azure profile, the list with images.yml;
  - invite-dark, invite-public-staged, invite-public: the invite-production edge modes,
it renders `docker compose config` and reads auth-service's five PARKIO_REGISTRATION_* settings and,
where the model can build web, web's VITE_REGISTRATION_MODE build argument. It requires:
  * with the example env file as shipped: registration closed, and invite creation and the PRIV-001
    synthetic bypass off;
  * with every registration setting removed: auth gets the defaults (closed, false, empty, P7D,
    false), or for invite-production, whose edge overlays require the mode, a refused render;
  * with only PARKIO_REGISTRATION_MODE=closed left: auth gets the other four defaults;
  * with explicit values for all five: auth gets exactly those, so opting in works when asked for.
Every model but local must pass auth all five settings. Local passes none, so auth's own defaults
apply there, and its explicit case is not checked. Other variables that a render requires get
non-secret placeholders; the registration settings never do. Nothing is started, and no token value
is printed.
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
TOKEN = "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
SETTINGS = {  # auth-service's registration settings and their off values (its application.yml defaults)
    KEY: "closed",
    "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED": "false",
    TOKEN: "",
    "PARKIO_REGISTRATION_INVITE_TTL": "P7D",
    "PARKIO_REGISTRATION_PRIV001A_SYNTHETIC_BYPASS": "false",
}
SWITCHES = ("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED", "PARKIO_REGISTRATION_PRIV001A_SYNTHETIC_BYPASS")
OPT_IN = {  # synthetic, non-secret values that differ from every default
    KEY: "open",
    "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED": "true",
    TOKEN: "synthetic-operator-token-registration-test-0123",
    "PARKIO_REGISTRATION_INVITE_TTL": "P3D",
    "PARKIO_REGISTRATION_PRIV001A_SYNTHETIC_BYPASS": "true",
}
MISSING = re.compile(r"required variable ([A-Z0-9_]+) is missing")
REFUSED = "refused"
OMITTED = "registration settings omitted"

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
    """The rendered model, or REFUSED when it needs a registration setting; placeholders fill the rest."""
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        text = env_text + "".join(f"\n{k}={v}" for k, v in extra_env.items()) + "\nPARKIO_IMAGE_TAG=sha-registration-test\n"
        args = [a for f in files for a in ("-f", f)]
        for _ in range(60):
            env_file.write_text(text)
            done = subprocess.run(["docker", "compose", "--env-file", str(env_file), *args, "config", "--format", "json"],
                                  cwd=ROOT, capture_output=True, text=True,
                                  env={k: v for k, v in os.environ.items() if k not in SETTINGS})
            if done.returncode == 0:
                return json.loads(done.stdout)
            missing = MISSING.search(done.stderr)
            if not missing:
                raise SystemExit(f"FAIL: compose render failed: {done.stderr.strip()[:400]}")
            if missing.group(1) in SETTINGS:
                return REFUSED
            text += f"\n{missing.group(1)}=example.invalid"
        raise SystemExit("FAIL: compose render still misses required variables")


def mapping(value) -> dict:
    if isinstance(value, list):
        return dict(item.split("=", 1) for item in value if "=" in item)
    return value or {}


def registration(model: dict) -> dict:
    """{"auth": {setting: value or None}, "web-build": value or None} of a rendered model."""
    services = model.get("services") or {}
    env = mapping((services.get("auth-service") or {}).get("environment"))
    args = mapping(((services.get("web") or {}).get("build") or {}).get("args"))
    return {"auth": {k: env.get(k) for k in SETTINGS}, "web-build": args.get("VITE_REGISTRATION_MODE")}


def shown(key: str, value) -> str:
    if key == TOKEN and value:
        return "<set>"
    return repr(value)


def without(text: str, keys) -> str:
    pattern = re.compile(r"\s*(" + "|".join(map(re.escape, keys)) + r")\s*=")
    return "\n".join(line for line in text.splitlines() if not pattern.match(line))


def check(model: str, case: str, result: object, expected: str) -> list:
    """Problems for one render. `expected` is "shipped", "defaults" or "opt-in"."""
    if result == REFUSED:
        if case == OMITTED and model.startswith("invite-"):
            return []
        return [f"{model} / {case}: the render was refused for a missing registration setting"]
    values = registration(result)
    want = {"shipped": {KEY: "closed", **{k: "false" for k in SWITCHES}}, "defaults": SETTINGS, "opt-in": OPT_IN}[expected]
    problems = []
    for key in SETTINGS:
        got = values["auth"][key]
        if got is None:
            if model != "local":  # local passes auth no registration setting; its defaults apply
                problems.append(f"{model} / {case}: auth-service gets no {key}")
        elif key in want and got != want[key]:
            problems.append(f"{model} / {case}: auth-service {key}={shown(key, got)}, expected {shown(key, want[key])}")
    # web: a build argument, where the model has one, must follow the registration mode.
    web_want = OPT_IN[KEY] if expected == "opt-in" else "closed"
    if values["web-build"] is not None and values["web-build"] != web_want:
        problems.append(f"{model} / {case}: web build VITE_REGISTRATION_MODE={values['web-build']!r}, expected {web_want!r}")
    return problems


def summary(result: object) -> str:
    if result == REFUSED:
        return REFUSED
    values = registration(result)
    auth = ", ".join(f"{k.removeprefix('PARKIO_REGISTRATION_')}={shown(k, v)}" for k, v in values["auth"].items())
    return f"auth {{{auth}}}, web-build {values['web-build']!r}"


def main() -> int:
    problems, lines = [], []
    for model, example in EXAMPLES.items():
        example_path = ROOT / example
        text = example_path.read_text()
        files = model_files(model, example_path)
        extra = INVITE_EDGE.get(model, {})
        omitted = without(text, SETTINGS)
        cases = [("example as shipped", text, "shipped"),
                 (OMITTED, omitted, "defaults"),
                 (f"only {KEY}=closed set", omitted + f"\n{KEY}=closed", "defaults")]
        if model != "local":  # the local model passes auth no registration setting at all
            cases.append(("explicit opt-in to all five", omitted + "".join(f"\n{k}={v}" for k, v in OPT_IN.items()), "opt-in"))
        for case, env_text, expected in cases:
            result = render(files, env_text, extra)
            found = check(model, case, result, expected)
            problems += found
            lines.append(f"{'FAIL' if found else 'PASS'}: {model} / {case} -> {summary(result)}")
    print("\n".join(lines))
    if problems:
        print("FAIL: registration settings are not off by default, or do not reach auth, everywhere:", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        return 1
    print(f"=== registration settings off by default and explicit ones reach auth: PASS ({len(lines)} renders) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
