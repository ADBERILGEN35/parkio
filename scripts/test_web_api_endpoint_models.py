#!/usr/bin/env python3
"""The web API endpoint check reads each real model the way it is meant to (owner decision 2026-10-05, H2).

scripts/test_web_api_endpoint_guard.py tests the guard on hand-written models. This test renders the
models that the guarded paths start web from, with their example env files:
  - hosted-beta: the default deploy-common profile, which the deploy and the rollback use
    (docker/.env.hosted-beta.example);
  - civo-wrapper: scripts/parkio-prod-compose.sh (docker/.env.azure-hosted-beta.example).
For each it requires:
  * as shipped: the guard intends the example's VITE_API_BASE_URL, on the model's PARKIO_DOMAIN. It
    reads the web image the model pins, refuses a bundle built for the other environment's API, and
    passes one built for the intended API (synthetic bundles, served by a fake docker);
  * VITE_API_BASE_URL removed: Compose refuses the render, so nothing can start;
  * VITE_API_BASE_URL set to the other environment's API: the guard refuses the model before it
    reads any image.
Other variables that a render requires get non-secret placeholders; VITE_API_BASE_URL and
PARKIO_DOMAIN never do. Nothing is started and no real image is read.
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
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
sys.path.insert(0, str(ROOT / "scripts"))
import web_api_endpoint_guard as guard  # noqa: E402
from test_web_api_endpoint_guard import FakeDocker, bundle_js, web_root_tar  # noqa: E402

KEY = "VITE_API_BASE_URL"
NEVER_PLACEHOLDER = {KEY, "PARKIO_DOMAIN"}
MISSING = re.compile(r"required variable ([A-Z0-9_]+) is missing")
PROD = "https://api.parkio.dev/api/v1"
BETA = "https://api.beta.example.com/api/v1"
MODELS = {  # model: (example env file, the API it intends, the other environment's API)
    "hosted-beta": ("docker/.env.hosted-beta.example", BETA, PROD),
    "civo-wrapper": ("docker/.env.azure-hosted-beta.example", PROD, BETA),
}


class RenderRefused(Exception):
    """Compose refused the render because a variable that never gets a placeholder is missing."""


def file_args(words: list) -> list:
    return [words[i + 1] for i in range(len(words) - 1) if words[i] == "-f"]


def clean_env() -> dict:
    """The process env without values that would override the env file in a render."""
    return {k: v for k, v in os.environ.items() if not k.startswith(("PARKIO_", "VITE_"))}


def hosted_beta_files(env_file: Path) -> list:
    """The -f files of the profile deploy-common selects by default, read from the real library."""
    done = subprocess.run(
        ["bash", "-c", 'set -euo pipefail; source scripts/lib/deploy-common.sh; '
                       'parkio_configure_deployment_profile "$1" >/dev/null; '
                       'printf "%s\\n%s" "$PARKIO_DEPLOYMENT_PROFILE" "$PARKIO_COMPOSE_FILES"',
         "_", str(env_file)], cwd=ROOT, env=clean_env(), capture_output=True, text=True)
    profile, _, files = done.stdout.partition("\n")
    if done.returncode != 0 or profile != "hosted-beta":
        raise SystemExit(f"FAIL: the default profile is not hosted-beta: {profile!r} {done.stderr.strip()}")
    return file_args(files.split())


def wrapper_files(env_file: Path) -> list:
    """The -f files scripts/parkio-prod-compose.sh passes, captured with a docker shim."""
    with tempfile.TemporaryDirectory() as tmp:
        shim = Path(tmp) / "docker"
        shim.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$ARGV_OUT"\n')
        shim.chmod(0o755)
        argv_out = Path(tmp) / "argv"
        env = dict(clean_env(), PATH=f"{tmp}:{os.environ['PATH']}", ARGV_OUT=str(argv_out), PARKIO_ENV_FILE=str(env_file))
        subprocess.run(["bash", "scripts/parkio-prod-compose.sh", "ps"], cwd=ROOT, env=env, check=True,
                       capture_output=True, text=True)
        return [f.removeprefix(f"{ROOT}/") for f in file_args(argv_out.read_text().splitlines())]


def render(files: list, env_text: str) -> dict:
    """The rendered model; raises RenderRefused when it needs a variable that gets no placeholder."""
    with tempfile.TemporaryDirectory() as tmp:
        env_file = Path(tmp) / "env"
        text = env_text + "\nPARKIO_IMAGE_TAG=sha-api-endpoint-test\n"
        args = [a for f in files for a in ("-f", f)]
        for _ in range(60):
            env_file.write_text(text)
            done = subprocess.run(["docker", "compose", "--env-file", str(env_file), *args, "config", "--format", "json"],
                                  cwd=ROOT, capture_output=True, text=True, env=clean_env())
            if done.returncode == 0:
                return json.loads(done.stdout)
            missing = MISSING.search(done.stderr)
            if not missing:
                raise SystemExit(f"FAIL: compose render failed: {done.stderr.strip()[:400]}")
            if missing.group(1) in NEVER_PLACEHOLDER:
                raise RenderRefused(missing.group(1))
            text += f"\n{missing.group(1)}=example.invalid"
        raise SystemExit("FAIL: compose render still misses required variables")


def without_key(text: str) -> str:
    return "\n".join(line for line in text.splitlines() if not re.match(rf"\s*{KEY}\s*=", line))


def refusal(model: dict, image: str, baked: str):
    """The guard's refusal message for model when its web image bakes `baked`, or None if it passes."""
    docker = FakeDocker({image: web_root_tar({"assets/index-a1.js": bundle_js(baked)})})
    try:
        guard.check(model, None, run=docker)
        return None
    except guard.Refused as refused:
        return str(refused)


def check_model(name: str, example: str, intended: str, other: str) -> list:
    text = (ROOT / example).read_text()
    files = wrapper_files(ROOT / example) if name == "civo-wrapper" else hosted_beta_files(ROOT / example)
    problems = []

    shipped = render(files, text)
    domain = guard._mapping((shipped["services"].get("caddy") or {}).get("environment")).get("PARKIO_DOMAIN")
    if not domain:
        problems.append(f"{name}: the model renders no PARKIO_DOMAIN for caddy, so the domain check is off")
    try:
        got = guard.intended_endpoint(shipped)
        if got != intended:
            problems.append(f"{name}: the guard intends {got}, expected {intended}")
    except guard.Refused as refused:
        problems.append(f"{name}: the example as shipped is refused: {refused}")
    image = shipped["services"]["web"].get("image", "")
    if guard.web_image(shipped, None) != image or "@sha256:" not in image:
        problems.append(f"{name}: the guard does not read the web image the model pins ({image!r})")
    message = refusal(shipped, image, other)
    if not message or f"was built to call {other}, but this deploy's env intends {intended}" not in message:
        problems.append(f"{name}: a web image built for {other} is not refused: {message}")
    if refusal(shipped, image, intended) is not None:
        problems.append(f"{name}: a web image built for {intended} is refused")

    try:
        render(files, without_key(text))
        problems.append(f"{name}: the render succeeds without {KEY}")
    except RenderRefused as refused:
        if str(refused) != KEY:
            problems.append(f"{name}: without {KEY} the render misses {refused} instead")

    crossed = render(files, without_key(text) + f"\n{KEY}={other}")
    docker = FakeDocker({})
    try:
        guard.check(crossed, None, run=docker)
        problems.append(f"{name}: {KEY}={other} with PARKIO_DOMAIN {domain} is not refused")
    except guard.Refused as refused:
        if "does not point at its PARKIO_DOMAIN" not in str(refused) or docker.calls:
            problems.append(f"{name}: {KEY}={other} is refused for another reason or after reading an image: {refused}")
    return problems


def main() -> int:
    problems = []
    for name, (example, intended, other) in MODELS.items():
        found = check_model(name, example, intended, other)
        problems += found
        print(f"{'FAIL' if found else 'PASS'}: {name} ({example}): intends {intended}; refuses {other} images, "
              f"an omitted {KEY} and {KEY}={other}")
    if problems:
        print("FAIL: the web API endpoint check does not read the real models as intended:", file=sys.stderr)
        for problem in problems:
            print("  " + problem, file=sys.stderr)
        return 1
    print(f"=== web API endpoint check on the real models: PASS ({len(MODELS)} models) ===")
    return 0


if __name__ == "__main__":
    sys.exit(main())
