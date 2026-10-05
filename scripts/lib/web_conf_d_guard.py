#!/usr/bin/env python3
"""Refuse a web image that would lose its server config under a tmpfs at /etc/nginx/conf.d (B8b).

Since B9 (#198) the web image renders /etc/nginx/conf.d at start from
/etc/nginx/templates/default.conf.template. Earlier images ship their server config in
/etc/nginx/conf.d itself. A tmpfs there hides that config, so nginx starts without a server,
the healthcheck fails and the edge answers 502.

The check runs only when the rendered Compose model mounts a tmpfs at /etc/nginx/conf.d for
services.web, so models without one are unaffected. It never runs the image, the same way the web
map guard inspects it:
- ``docker create --pull never --network none --entrypoint <noop>`` makes a container that is
  never started;
- ``docker cp CONTAINER:TEMPLATE -`` streams the template as a tar archive, which is read in
  memory only to confirm it holds a non-empty regular file;
- ``docker rm -fv`` removes the container and any anonymous volume the image declares.
The template's content is never written or printed.

Usage:
    web_conf_d_guard.py --config-json MODEL.json (--binding WEB-BINDING.json | --image REF | --model-image)

MODEL.json is ``docker compose config --format json``. WEB-BINDING.json is the binding override
that scripts/guard-web-synthetic-map-deploy.sh writes; its services.web.image is the image the
deploy starts. --image names the image directly. --model-image inspects the image that the model's
services.web names. The callers use it when the web map guard's own break-glass skipped the binding,
so that break-glass does not skip this check.

Exit codes: 0 = no tmpfs at /etc/nginx/conf.d (skip), or the image renders its config at start
(pass); 1 = refused (an image built before #198, an unreadable model or binding, or an image
that could not be inspected); 2 = usage.
"""

from __future__ import annotations

import argparse
import io
import json
import subprocess
import sys
import tarfile
from typing import Callable, Optional

CONF_D = "/etc/nginx/conf.d"
TEMPLATE = "/etc/nginx/templates/default.conf.template"
FIX = (
    "move the web pin (docker/docker-compose.web-release-pin.yml) to an image built from #198 or "
    "later, which renders /etc/nginx/conf.d at start"
)

NOOP_ENTRYPOINT = "/parkio-web-conf-d-guard-noop"

Runner = Callable[[list], subprocess.CompletedProcess]


def run_docker(args: list) -> subprocess.CompletedProcess:
    """Run docker with stdout and stderr captured as bytes (``docker cp ... -`` streams a tar)."""
    return subprocess.run(["docker", *args], capture_output=True, check=False)


def _text(data) -> str:
    return data.decode("utf-8", "replace") if isinstance(data, bytes) else (data or "")


def _target(entry) -> Optional[str]:
    """The mount target of a short tmpfs entry ("/path:opts") or a long-form tmpfs volume."""
    if isinstance(entry, str):
        return entry.split(":", 1)[0]
    if isinstance(entry, dict) and entry.get("type") == "tmpfs":
        target = entry.get("target")
        return target if isinstance(target, str) else None
    return None


def mounts_conf_d_tmpfs(model: dict) -> Optional[bool]:
    """Whether services.web mounts a tmpfs at /etc/nginx/conf.d; None when the model is unreadable."""
    if not isinstance(model, dict) or not isinstance(model.get("services"), dict):
        return None
    web = model["services"].get("web")
    if web is None:
        return False
    if not isinstance(web, dict):
        return None
    tmpfs = web.get("tmpfs") or []
    entries = list(tmpfs) if isinstance(tmpfs, list) else [tmpfs]
    entries += [v for v in (web.get("volumes") or []) if isinstance(v, dict)]
    targets = {_target(entry) for entry in entries}
    return any(isinstance(t, str) and t.rstrip("/") == CONF_D for t in targets)


def model_image(model: dict) -> Optional[str]:
    """services.web.image of the rendered model, or None."""
    try:
        image = model["services"]["web"]["image"]
    except (KeyError, TypeError):
        return None
    return image if isinstance(image, str) and image.strip() else None


def bound_image(binding: dict) -> Optional[str]:
    try:
        image = binding["services"]["web"]["image"]
    except (KeyError, TypeError):
        return None
    return image if isinstance(image, str) and image.strip() else None


def _missing_path(stderr: str) -> bool:
    # Docker reports a path that is absent from the container's filesystem in either form.
    return "Could not find the file" in stderr or "No such container:path" in stderr


def _holds_nonempty_file(archive: bytes) -> bool:
    """Whether a `docker cp ... -` tar stream holds exactly the template as a non-empty regular file."""
    try:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
            members = tar.getmembers()
    except (tarfile.TarError, EOFError):
        return False
    return len(members) == 1 and members[0].isfile() and members[0].size > 0


def image_renders_conf_d(image: str, run: Runner = run_docker) -> tuple:
    """("yes" | "no" | "error", detail). The container is created, never started, and removed."""
    created = run(["create", "--pull", "never", "--network", "none", "--entrypoint", NOOP_ENTRYPOINT, image])
    lines = _text(created.stdout).strip().splitlines()
    container = lines[-1].strip() if created.returncode == 0 and lines else ""
    if not container:
        return "error", f"docker create failed (exit {created.returncode})"
    try:
        copied = run(["cp", f"{container}:{TEMPLATE}", "-"])
        if copied.returncode == 0:
            if _holds_nonempty_file(copied.stdout if isinstance(copied.stdout, bytes) else b""):
                return "yes", "template present"
            return "error", "the template is not a non-empty regular file"
        if _missing_path(_text(copied.stderr)):
            return "no", f"{TEMPLATE} is absent"
        return "error", f"docker cp failed (exit {copied.returncode})"
    finally:
        removed = run(["rm", "-fv", container])
        if removed.returncode != 0:
            print(f"web-conf-d-guard: WARNING: could not remove inspection container {container}", file=sys.stderr)


def check(model: dict, image: Optional[str], run: Runner = run_docker) -> tuple:
    """(exit code, message) for one rendered model and the image the deploy will start."""
    mounted = mounts_conf_d_tmpfs(model)
    if mounted is None:
        return 1, "web-conf-d-guard: BLOCKED: the rendered compose model is unreadable"
    if not mounted:
        return 0, f"web-conf-d-guard: SKIP (no tmpfs at {CONF_D} for web)"
    if not image:
        return 1, f"web-conf-d-guard: BLOCKED: no web image to inspect; {FIX}"
    verdict, detail = image_renders_conf_d(image, run)
    if verdict == "yes":
        return 0, f"web-conf-d-guard: PASS ({image} renders {CONF_D} at start)"
    if verdict == "no":
        return 1, (
            f"web-conf-d-guard: BLOCKED: {image} ships its server config in {CONF_D} ({detail}: built "
            f"before #198). The tmpfs at {CONF_D} would hide it and nginx would serve nothing; {FIX}"
        )
    return 1, (
        f"web-conf-d-guard: BLOCKED: cannot inspect {image} ({detail}); refusing. The image must be "
        f"present locally (pull it first) and built from #198 or later"
    )


def _load_json(path: str) -> Optional[dict]:
    try:
        with open(path, encoding="utf-8") as fh:
            value = json.load(fh)
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) else None


def main(argv: list, run: Runner = run_docker) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--config-json", required=True)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--binding")
    source.add_argument("--image")
    source.add_argument("--model-image", action="store_true")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return 2 if exc.code else 0
    model = _load_json(args.config_json)
    if model is None:
        print("web-conf-d-guard: BLOCKED: the rendered compose model is unreadable", file=sys.stderr)
        return 1
    image = model_image(model) if args.model_image else args.image
    if args.binding is not None:
        binding = _load_json(args.binding)
        image = bound_image(binding) if binding is not None else None
        if image is None and mounts_conf_d_tmpfs(model):
            print(f"web-conf-d-guard: BLOCKED: the web binding override is unreadable; {FIX}", file=sys.stderr)
            return 1
    code, message = check(model, image, run)
    print(message, file=sys.stderr if code else sys.stdout)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
