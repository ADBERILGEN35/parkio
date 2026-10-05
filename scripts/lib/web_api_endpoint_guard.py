#!/usr/bin/env python3
"""Refuse to start a web image built for another API than the one this deploy intends (owner decision
2026-10-05, H2: hosted-beta must not silently use production's API endpoint).

    web_api_endpoint_guard.py --compose-config-json MODEL [--binding-override FILE]

The intended endpoint comes from the rendered Compose model, so from the env file it was rendered
with: web's VITE_API_BASE_URL build argument. When the model also carries PARKIO_DOMAIN (Caddy's
environment), the endpoint's host must be that domain. The web image is the one the map guard bound
(the binding override), or the model's web image when there is no binding. Its bundle is copied out
with `docker create --pull never --network none` and `docker cp`, never run, and the
VITE_API_BASE_URL that Vite inlined into it is read without executing anything.

The guard passes only when the bundle bakes exactly one API base URL and it equals the intended one,
compared by scheme, host, port and path (case of scheme and host, default ports and a trailing slash
ignored). It refuses, fail-closed, when the endpoints differ, when the model sets no endpoint, when
PARKIO_DOMAIN disagrees with it, and when the bundle, its inlined env or its API base URL cannot be
read or is ambiguous. A model without a web service passes: there is nothing to start.

There is no break-glass for this check. Exit codes: 0 = pass, 1 = refused, 2 = usage.
"""
from __future__ import annotations

import argparse
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Callable, Optional
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))
import web_bundle_map_config as bundle_config  # noqa: E402

PREFIX = "web-api-endpoint-guard"
WEB_ROOT_IN_IMAGE = "/usr/share/nginx/html"
DEFAULT_PORTS = {"https": 443, "http": 80}

Runner = Callable[[list], subprocess.CompletedProcess]


def run_docker(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, check=False)


class Refused(Exception):
    """The guard refuses the deploy; the message says why."""


def normalise(url: str) -> Optional[str]:
    """scheme://host[:port]/path with case, default ports and a trailing slash normalised; None if not a URL."""
    try:
        parts = urlsplit(url.strip())
        port = parts.port
    except ValueError:
        return None
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    if scheme not in DEFAULT_PORTS or not host or parts.query or parts.fragment or parts.username:
        return None
    netloc = host if port in (None, DEFAULT_PORTS[scheme]) else f"{host}:{port}"
    return f"{scheme}://{netloc}{parts.path.rstrip('/')}"


def _mapping(value) -> dict:
    """Compose renders environment and build args as a mapping or as a list of KEY=VALUE strings."""
    if isinstance(value, dict):
        return {k: ("" if v is None else str(v)) for k, v in value.items()}
    if isinstance(value, list):
        return dict(item.split("=", 1) for item in value if isinstance(item, str) and "=" in item)
    return {}


def web_service(model: dict) -> Optional[dict]:
    web = (model.get("services") or {}).get("web")
    return web if isinstance(web, dict) else None


def intended_endpoint(model: dict) -> str:
    """The normalised API base URL this model's env intends for web; raises Refused if it cannot tell."""
    web = web_service(model) or {}
    args = _mapping((web.get("build") or {}).get("args"))
    raw = args.get("VITE_API_BASE_URL", "").strip()
    if not raw:
        raise Refused("the compose model sets no VITE_API_BASE_URL for web, so the API this deploy intends "
                      "is unknown. Set VITE_API_BASE_URL in the env file.")
    intended = normalise(raw)
    if intended is None:
        raise Refused(f"the env's VITE_API_BASE_URL {raw!r} is not an http(s) URL with a host")
    domain = _mapping(((model.get("services") or {}).get("caddy") or {}).get("environment")).get("PARKIO_DOMAIN", "")
    if domain.strip() and urlsplit(intended).hostname != domain.strip().lower():
        raise Refused(f"the env's VITE_API_BASE_URL {raw} does not point at its PARKIO_DOMAIN {domain.strip()}")
    return intended


def web_image(model: dict, binding_override: Optional[Path]) -> str:
    """The image the web container will run: the map guard's binding, else the model's image."""
    if binding_override is not None and binding_override.is_file() and binding_override.stat().st_size > 0:
        try:
            bound = (((json.loads(binding_override.read_text()) or {}).get("services") or {}).get("web") or {}).get("image")
        except ValueError:
            bound = None
        if not bound:
            raise Refused(f"the web binding {binding_override} names no image")
        return bound
    web = web_service(model) or {}
    image = web.get("image") or (f"{model.get('name')}-web" if model.get("name") and web.get("build") else "")
    if not image:
        raise Refused("the compose model names no web image")
    return image


def baked_endpoints(root: Path) -> set:
    """Every VITE_API_BASE_URL inlined into the bundle's JS (raw values)."""
    values = set()
    for path in bundle_config.js_files(str(root)):
        if os.path.getsize(path) > bundle_config.MAX_JS_BYTES:
            raise Refused("a bundle JS file exceeds the size bound")
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for env in bundle_config.env_literals(fh.read()):
                if "VITE_API_BASE_URL" in env:
                    values.add(env["VITE_API_BASE_URL"])
    return values


def _extract(archive: tarfile.TarFile, member: tarfile.TarInfo, target: Path) -> None:
    try:
        archive.extract(member, target, set_attrs=False, filter="data")
    except TypeError:  # a Python without extraction filters; links and absolute paths are skipped already
        archive.extract(member, target, set_attrs=False)


def extract_bundle(image: str, target: Path, run: Runner) -> Path:
    """Copies the image's web root into target without running the image; returns the copied root."""
    created = run(["create", "--pull", "never", "--network", "none",
                   "--entrypoint", "/parkio-web-api-endpoint-guard-noop", image])
    container = (created.stdout or b"").decode(errors="replace").strip().splitlines()
    if created.returncode != 0 or not container:
        raise Refused(f"cannot create a container from {image} to read its bundle (is the image present locally?)")
    container_id = container[-1].strip()
    try:
        copied = run(["cp", f"{container_id}:{WEB_ROOT_IN_IMAGE}", "-"])
        if copied.returncode != 0 or not copied.stdout:
            raise Refused(f"cannot copy {WEB_ROOT_IN_IMAGE} out of {image}")
        try:
            with tarfile.open(fileobj=io.BytesIO(copied.stdout)) as archive:
                for member in archive.getmembers():
                    if member.issym() or member.islnk() or member.name.startswith("/") or ".." in Path(member.name).parts:
                        continue
                    _extract(archive, member, target)
        except (tarfile.TarError, OSError) as error:
            raise Refused(f"the bundle copied out of {image} is not a readable archive ({error})")
    finally:
        run(["rm", "-f", container_id])
    root = target / Path(WEB_ROOT_IN_IMAGE).name
    if not root.is_dir():
        raise Refused(f"{image} has no {WEB_ROOT_IN_IMAGE}")
    return root


def check(model: dict, binding_override: Optional[Path], run: Runner = run_docker) -> str:
    """The PASS message, or raises Refused."""
    if web_service(model) is None:
        return f"{PREFIX}: SKIP: the compose model has no web service"
    intended = intended_endpoint(model)
    image = web_image(model, binding_override)
    with tempfile.TemporaryDirectory() as tmp:
        root = extract_bundle(image, Path(tmp), run)
        values = baked_endpoints(root)
    if not values:
        raise Refused(f"the bundle of {image} inlines no VITE_API_BASE_URL, so the API it calls is unknown")
    baked = {normalise(value) for value in values}
    if None in baked or len(baked) != 1:
        raise Refused(f"the bundle of {image} inlines an ambiguous API base URL: {sorted(values)}")
    actual = baked.pop()
    if actual != intended:
        raise Refused(f"{image} was built to call {actual}, but this deploy's env intends {intended}. A web image "
                      f"built for another environment would send this deploy's users to that API. "
                      f"Start a web image built for {intended}.")
    return f"{PREFIX}: PASS: {image} calls {actual}, the API this deploy intends"


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--compose-config-json", required=True)
    parser.add_argument("--binding-override")
    args = parser.parse_args(argv)
    try:
        model = json.loads(Path(args.compose_config_json).read_text())
        if not isinstance(model, dict):
            raise ValueError("not a JSON object")
    except (OSError, ValueError) as error:
        print(f"{PREFIX}: BLOCKED: cannot read the compose model: {error}", file=sys.stderr)
        return 1
    try:
        print(check(model, Path(args.binding_override) if args.binding_override else None))
        return 0
    except Refused as refused:
        print(f"{PREFIX}: BLOCKED: {refused}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
