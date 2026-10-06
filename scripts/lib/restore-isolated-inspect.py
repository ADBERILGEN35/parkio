#!/usr/bin/env python3
"""Live topology checks for destination-bound isolated restore tickets.

A CLI flag, env var, container name, or marker file is not isolation proof.
The orchestrator records docker context/host/engine plus container, network
and volume identities; apply and teardown must use those same identities after
a live re-inspect. This prevents accidental/misrouted supported-script use. It
does not stop a malicious root operator who can edit these scripts.

Supported fixture volume topology: Docker local driver, Scope=local, empty
Options, fixture labels, and consumers limited to ticket container IDs.
Local-driver bind/device/NFS/CIFS options are rejected. Ticket bodyDigest is
SHA-256 integrity of the ticket body, not producer authentication.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path


def _load_ticket_mod():
    path = Path(__file__).resolve().parent / "restore-isolated-ticket.py"
    spec = importlib.util.spec_from_file_location("restore_isolated_ticket", path)
    if spec is None or spec.loader is None:
        raise ValueError("cannot load restore-isolated-ticket.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_ticket = _load_ticket_mod()
ISSUER = _ticket.ISSUER
PRODUCTION_CONTAINERS = _ticket.PRODUCTION_CONTAINERS
SERVICE_CREDS = _ticket.SERVICE_CREDS
add_digest = _ticket.add_digest
load_ticket = _ticket.load_ticket
verify_digest = _ticket.verify_digest
verify_schema = _ticket.verify_schema

PROJECT_RE = re.compile(r"^parkio-iso-[0-9a-f]{12}$")
PG_NAME_RE = re.compile(r"^/parkio-iso-[0-9a-f]{12}-pg(?:-[a-z0-9-]+)?$")
MINIO_NAME_RE = re.compile(r"^/parkio-iso-[0-9a-f]{12}-minio$")
VOLUME_RE = re.compile(r"^parkio-iso-[0-9a-f]{12}-vol(?:-[a-z0-9-]+)?$")
NETWORK_RE = re.compile(r"^parkio-iso-[0-9a-f]{12}$")
RECORDED_ID_RE = re.compile(r"^[0-9a-f]{64}$")
FORBIDDEN_NETWORKS = {
    "parkio-backend",
    "backend",
    "host",
    "none",
    "bridge",
    "docker_gwbridge",
    "ingress",
    "rd-net",
}
FORBIDDEN_ALIASES = PRODUCTION_CONTAINERS | {"minio", "postgres", "parkio-minio"}
FORBIDDEN_VOLUME_HINT = re.compile(
    r"(parkio-postgres|minio-data|parkio_postgres|parkio_minio|rd-postgres)",
    re.I,
)
FIXTURE_LABEL = "parkio.isolated.fixture"
PROJECT_LABEL = "parkio.isolated.project"
ABSENT_NEEDLES = (
    "no such object",
    "no such container",
    "no such volume",
    "no such network",
)
UNAVAILABLE_NEEDLES = (
    "permission denied",
    "cannot connect",
    "connection refused",
    "daemon",
    "access denied",
    "operation not permitted",
)


def fail(message: str) -> None:
    raise ValueError(message)


def docker_argv(args: tuple[str, ...]) -> list[str]:
    override = os.environ.get("PARKIO_RESTORE_DOCKER")
    if override:
        return [sys.executable, override, *args]
    return ["docker", *args]


def docker(*args: str, allow_absent: bool = False) -> str:
    argv = docker_argv(args)
    try:
        completed = subprocess.run(
            argv,
            check=False,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError as exc:
        raise ValueError("docker CLI is not available") from exc
    if completed.returncode == 0:
        return completed.stdout
    err = (completed.stderr or completed.stdout or "").strip()
    err_l = err.lower()
    if allow_absent and _is_absent_error(err_l):
        return ""
    raise ValueError(f"docker {' '.join(args)} failed: {err or completed.returncode}")


def _is_absent_error(err: str) -> bool:
    if any(needle in err for needle in ABSENT_NEEDLES):
        return True
    return re.search(r"\b(network|volume|container) \S+ not found\b", err) is not None


def _is_unavailable_error(err: str) -> bool:
    return any(needle in err for needle in UNAVAILABLE_NEEDLES)


def docker_json(*args: str):
    raw = docker(*args).strip()
    if not raw:
        fail(f"docker {' '.join(args)} returned empty output")
    data = json.loads(raw)
    if isinstance(data, list):
        if len(data) != 1:
            fail(f"docker {' '.join(args)} returned an ambiguous list")
        return data[0]
    return data


def norm_id(value: str) -> str:
    value = (value or "").strip()
    if value.startswith("sha256:"):
        value = value[len("sha256:"):]
    return value.lower()


def container_basename(name: str) -> str:
    return (name or "").lstrip("/")


def daemon_identity() -> dict:
    context = docker("context", "show").strip()
    if not context:
        fail("docker context is empty; refusing ambiguous daemon")
    inspected = docker_json("context", "inspect", context)
    endpoints = inspected.get("Endpoints") or {}
    docker_ep = endpoints.get("docker") or {}
    host = docker_ep.get("Host")
    if not host:
        fail("docker context has no Host endpoint; refusing ambiguous daemon")
    info = docker_json("info", "--format", "{{json .}}")
    engine_id = info.get("ID")
    if not engine_id:
        fail("docker info has no engine ID; refusing ambiguous daemon")
    swarm = info.get("Swarm") or {}
    local_state = (swarm.get("LocalNodeState") or "inactive").lower()
    if local_state not in ("", "inactive"):
        fail(f"unsupported docker topology: swarm LocalNodeState={local_state}")
    return {
        "dockerContext": context,
        "dockerHost": host,
        "dockerEngineId": engine_id,
    }


def inspect_container(ref: str) -> dict:
    return docker_json("inspect", "--type=container", ref)


def inspect_network(ref: str) -> dict:
    return docker_json("network", "inspect", ref)


def inspect_volume(ref: str) -> dict:
    return docker_json("volume", "inspect", ref)


def inspect_maybe(kind: str, ref: str) -> dict | None:
    if not (ref or "").strip():
        fail(f"refusing {kind} operation without a recorded identity")
    if kind == "network":
        argv = docker_argv(("network", "inspect", ref))
    elif kind == "volume":
        argv = docker_argv(("volume", "inspect", ref))
    elif kind == "container":
        argv = docker_argv(("inspect", "--type=container", ref))
    else:
        fail(f"unsupported inspect kind {kind}")
    try:
        completed = subprocess.run(
            argv,
            check=False,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError as exc:
        raise ValueError("docker CLI is not available") from exc
    err = (completed.stderr or completed.stdout or "").strip()
    err_l = err.lower()
    if completed.returncode != 0:
        if _is_absent_error(err_l):
            return None
        fail(f"docker inspect {kind} {ref} failed: {err or completed.returncode}")
    raw = (completed.stdout or "").strip()
    if not raw:
        fail(f"docker inspect {kind} {ref} returned empty output")
    data = json.loads(raw)
    if isinstance(data, list):
        if not data:
            return None
        if len(data) != 1:
            fail(f"docker inspect {kind} {ref} returned an ambiguous list")
        return data[0]
    return data


def assert_not_production_name(name: str) -> None:
    base = container_basename(name)
    if base in PRODUCTION_CONTAINERS or base.startswith("parkio-postgres-"):
        fail(f"destination '{base}' is a production/default container name")


def assert_network(net: dict, project: str, expected_id: str | None = None) -> str:
    name = net.get("Name") or ""
    net_id = norm_id(net.get("Id") or "")
    if expected_id and net_id != norm_id(expected_id):
        fail("live network id drifted from the ticket")
    if name != project or not NETWORK_RE.match(name or ""):
        fail(f"network '{name}' is not the fixture network {project}")
    if name in FORBIDDEN_NETWORKS:
        fail(f"network '{name}' is a production/shared network")
    if (net.get("Driver") or "") != "bridge":
        fail("fixture network driver is not bridge")
    if not net.get("Internal"):
        fail("fixture network must be internal")
    if net.get("Ingress"):
        fail("fixture network must not be ingress")
    assert_fixture_labels(net, project, f"network '{name}'")
    return net_id


def object_labels(obj: dict) -> dict:
    labels = obj.get("Labels")
    if isinstance(labels, dict) and labels:
        return labels
    cfg = obj.get("Config") or {}
    nested = cfg.get("Labels")
    return nested if isinstance(nested, dict) else {}


def assert_fixture_labels(obj: dict, project: str, kind: str) -> None:
    labels = object_labels(obj)
    if str(labels.get(FIXTURE_LABEL) or "") != "1":
        fail(f"{kind} is not labeled as a parkio isolated fixture")
    if str(labels.get(PROJECT_LABEL) or "") != project:
        fail(f"{kind} project label is not {project}")


def assert_volume(name: str, project: str, allowed_consumer_ids: set[str] | None = None) -> None:
    if not VOLUME_RE.match(name or "") or not name.startswith(project):
        fail(f"volume '{name}' is not a fixture volume")
    if FORBIDDEN_VOLUME_HINT.search(name or ""):
        fail(f"volume '{name}' resembles a production volume")
    vol = inspect_volume(name)
    if (vol.get("Name") or "") != name:
        fail(f"volume name drifted for {name}")
    if (vol.get("Driver") or "") != "local":
        fail(f"volume '{name}' uses unsupported driver")
    scope = (vol.get("Scope") or "local").lower()
    if scope != "local":
        fail(f"volume '{name}' has unsupported scope {scope}")
    options = vol.get("Options")
    if options in (None, "", []):
        options = {}
    if not isinstance(options, dict) or options:
        fail(
            f"volume '{name}' uses unsupported local-driver options {options!r}; "
            "supported topology is Driver=local with empty Options "
            "(no bind/device/NFS/CIFS or other storage opts)"
        )
    assert_fixture_labels(vol, project, f"volume '{name}'")
    consumers = volume_consumer_ids(name)
    allowed = allowed_consumer_ids if allowed_consumer_ids is not None else None
    if allowed is not None:
        extra = [cid for cid in consumers if cid not in allowed]
        if extra:
            fail(f"volume '{name}' is attached to unrelated containers {extra}")


def volume_consumer_ids(name: str) -> list[str]:
    raw = docker("ps", "-aq", "--no-trunc", "--filter", f"volume={name}")
    return [norm_id(line.strip()) for line in raw.splitlines() if line.strip()]


def live_container_state(
    container: dict,
    project: str,
    name_re: re.Pattern,
    *,
    require_running: bool = True,
    expected_name: str | None = None,
    expected_id: str | None = None,
) -> dict:
    name = container.get("Name") or ""
    assert_not_production_name(name)
    assert_fixture_labels(container, project, f"container {name}")
    if not name_re.match(name):
        fail(f"container name '{name}' is not a supported fixture identity")
    if not container_basename(name).startswith(project):
        fail(f"container '{name}' does not belong to project {project}")
    if expected_name and container_basename(name) != expected_name:
        fail(f"container '{name}' is not the ticket identity {expected_name}")
    cid = norm_id(container.get("Id") or "")
    if expected_id and cid != norm_id(expected_id):
        fail(f"container '{name}' id drifted from the ticket")
    state = container.get("State") or {}
    if require_running and not state.get("Running"):
        fail(f"container {name} is not running")
    host_config = container.get("HostConfig") or {}
    if host_config.get("NetworkMode") in ("host", "none", "container"):
        fail(f"unsupported NetworkMode for {name}")
    if str(host_config.get("NetworkMode") or "").startswith("container:"):
        fail(f"container network mode is unsupported for {name}")
    bindings = host_config.get("PortBindings") or {}
    if any(bindings.values()):
        fail(f"container {name} publishes ports; fixture must not")
    published = (container.get("NetworkSettings") or {}).get("Ports") or {}
    if any(published.values()):
        fail(f"container {name} publishes ports; fixture must not")
    mounts = container.get("Mounts") or []
    volume_names = []
    for mount in mounts:
        mtype = (mount.get("Type") or "").lower()
        if mtype == "bind":
            fail(f"container {name} bind-mounts {mount.get('Source')}")
        if mtype != "volume":
            fail(f"container {name} has unsupported mount type {mtype}")
        vol_name = mount.get("Name") or ""
        assert_volume(vol_name, project, allowed_consumer_ids={cid})
        volume_names.append(vol_name)
    if len(volume_names) != 1:
        fail(f"container {name} must mount exactly one fixture volume (found {len(volume_names)})")
    networks = (container.get("NetworkSettings") or {}).get("Networks") or {}
    if len(networks) != 1:
        fail(f"container {name} is attached to {len(networks)} networks; expected exactly one")
    net_name, net_cfg = next(iter(networks.items()))
    if net_name in FORBIDDEN_NETWORKS or net_name != project:
        fail(f"container {name} is attached to unsupported network '{net_name}'")
    aliases = set(net_cfg.get("Aliases") or [])
    aliases.add(container_basename(name))
    for alias in aliases:
        if alias in FORBIDDEN_ALIASES:
            fail(f"container {name} has production DNS alias '{alias}'")
    return {
        "id": cid,
        "name": container_basename(name),
        "networkName": net_name,
        "networkId": norm_id(net_cfg.get("NetworkID") or ""),
        "volumeName": volume_names[0],
        "volumeNames": volume_names,
        "env": _env_map(container),
    }


def _env_map(container: dict) -> dict:
    env = {}
    for item in (container.get("Config") or {}).get("Env") or []:
        if "=" in item:
            key, value = item.split("=", 1)
            env[key] = value
    return env


def stamp_realpath(stamp: str) -> str:
    return str(Path(stamp).resolve())


def verify_stamp(ticket: dict, stamp_dir: str) -> None:
    ticket_stamp = ticket.get("stamp")
    if not ticket_stamp:
        fail("isolated-fixture ticket is missing stamp")
    if stamp_realpath(ticket_stamp) != stamp_realpath(stamp_dir):
        fail("isolated-fixture ticket stamp does not match the selected stamp")


def verify_daemon(ticket: dict, live: dict) -> None:
    for key in ("dockerContext", "dockerHost", "dockerEngineId"):
        if ticket.get(key) != live.get(key):
            fail(f"docker {key} drifted from the ticket")


def system_identifier(container_name: str) -> str:
    """The isolated cluster's pg_control_system() system_identifier (U02 recovery target binding)."""
    out = docker("exec", container_name, "psql", "-U", "postgres", "-d", "postgres", "-At", "-c",
                 "SELECT system_identifier::text FROM pg_control_system()").strip()
    if not out.isdigit():
        fail("isolated postgres system_identifier is unreadable")
    return out


def postgres_destinations(ticket: dict) -> dict:
    dest = ticket.get("postgres") or {}
    if not isinstance(dest, dict) or not dest:
        fail("isolated-fixture ticket has no postgres destinations")
    return dest


def validate_ticket_live(ticket_path: str, stamp_dir: str) -> dict:
    ticket = load_ticket(ticket_path)
    verify_schema(ticket)
    verify_digest(ticket)
    verify_stamp(ticket, stamp_dir)
    live = daemon_identity()
    verify_daemon(ticket, live)
    project = ticket.get("project")
    if not PROJECT_RE.match(project or ""):
        fail("ticket project is not parkio-iso-<12-hex>")
    net = inspect_network(ticket["network"]["id"])
    net_id = assert_network(net, project, ticket["network"]["id"])
    if (ticket.get("network") or {}).get("name") != project:
        fail("ticket network name is not the fixture project")
    if norm_id(ticket["network"]["id"]) != net_id:
        fail("ticket network id drifted")

    for service, dest in postgres_destinations(ticket).items():
        if service not in SERVICE_CREDS:
            fail(f"ticket lists unknown postgres service '{service}'")
        if not isinstance(dest, dict):
            fail(f"ticket postgres.{service} is invalid")
        container = inspect_container(dest.get("containerId") or "")
        state = live_container_state(container, project, PG_NAME_RE)
        if state["id"] != norm_id(dest.get("containerId") or ""):
            fail(f"postgres.{service} container id drifted")
        if state["name"] != dest.get("containerName"):
            fail(f"postgres.{service} container name drifted")
        if state["networkId"] != net_id or state["networkName"] != project:
            fail(f"postgres.{service} is not on the fixture network")
        if dest.get("volumeName") != state["volumeName"]:
            fail(f"postgres.{service} volume drifted")
        want_user, want_db = SERVICE_CREDS[service]
        if dest.get("user") != want_user or dest.get("database") != want_db:
            fail(f"postgres.{service} user/database is not the fixture identity")
        by_name = inspect_container(state["name"])
        if norm_id(by_name.get("Id") or "") != state["id"]:
            fail(f"postgres.{service} name now points at a different container")
        if "databaseIdentity" in dest:
            live_identity = f"postgresql:{system_identifier(state['name'])}:{want_db}"
            if dest.get("databaseIdentity") != live_identity:
                fail(f"postgres.{service} database identity drifted")

    minio = ticket.get("minio")
    if minio:
        if not isinstance(minio, dict):
            fail("ticket minio destination is invalid")
        container = inspect_container(minio.get("containerId") or "")
        state = live_container_state(container, project, MINIO_NAME_RE)
        if state["id"] != norm_id(minio.get("containerId") or ""):
            fail("minio container id drifted")
        if state["name"] != minio.get("containerName"):
            fail("minio container name drifted")
        if state["networkId"] != net_id:
            fail("minio is not on the fixture network")
        if minio.get("aliasHost") != state["name"]:
            fail("minio aliasHost is not the verified container name")
        if minio.get("endpoint") != f"http://{state['name']}:9000":
            fail("minio endpoint is not the verified fixture hostname")
        bucket = minio.get("bucket") or ""
        if not bucket.startswith(project) or "parkio-media" == bucket:
            fail("minio bucket is not a fixture bucket")
        if minio.get("volumeName") != state["volumeName"]:
            fail("minio volume drifted")
    return ticket



def bound_ticket(ticket_path: str, stamp_dir: str):
    ticket = load_ticket(ticket_path)
    verify_schema(ticket)
    verify_digest(ticket)
    verify_stamp(ticket, stamp_dir)
    live = daemon_identity()
    verify_daemon(ticket, live)
    project = ticket.get("project")
    if not PROJECT_RE.match(project or ""):
        fail("ticket project is not parkio-iso-<12-hex>")
    net = inspect_network(ticket["network"]["id"])
    net_id = assert_network(net, project, ticket["network"]["id"])
    if (ticket.get("network") or {}).get("name") != project:
        fail("ticket network name is not the fixture project")
    return ticket, project, net_id


def authorize_postgres_service(ticket_path: str, stamp_dir: str, service: str) -> dict:
    ticket, project, net_id = bound_ticket(ticket_path, stamp_dir)
    dest = postgres_destinations(ticket).get(service)
    if not dest:
        fail(f"isolated ticket does not authorize postgres service '{service}'")
    container = inspect_container(dest.get("containerId") or "")
    state = live_container_state(container, project, PG_NAME_RE)
    if state["id"] != norm_id(dest.get("containerId") or ""):
        fail(f"postgres.{service} container id drifted")
    if state["name"] != dest.get("containerName"):
        fail(f"postgres.{service} container name drifted")
    if state["networkId"] != net_id or state["networkName"] != project:
        fail(f"postgres.{service} is not on the fixture network")
    if "databaseIdentity" in dest:
        live_identity = f"postgresql:{system_identifier(state['name'])}:{dest.get('database')}"
        if dest.get("databaseIdentity") != live_identity:
            fail(f"postgres.{service} database identity drifted")
    if dest.get("volumeName") != state["volumeName"]:
        fail(f"postgres.{service} volume drifted")
    want_user, want_db = SERVICE_CREDS.get(service, (None, None))
    if dest.get("user") != want_user or dest.get("database") != want_db:
        fail(f"postgres.{service} user/database is not the fixture identity")
    by_name = inspect_container(state["name"])
    if norm_id(by_name.get("Id") or "") != state["id"]:
        fail(f"postgres.{service} name now points at a different container")
    return ticket


def authorize_minio(ticket_path: str, stamp_dir: str) -> dict:
    ticket, project, net_id = bound_ticket(ticket_path, stamp_dir)
    minio = ticket.get("minio")
    if not minio:
        fail("isolated ticket does not authorize a MinIO destination")
    container = inspect_container(minio.get("containerId") or "")
    state = live_container_state(container, project, MINIO_NAME_RE)
    if state["id"] != norm_id(minio.get("containerId") or ""):
        fail("minio container id drifted")
    if state["name"] != minio.get("containerName"):
        fail("minio container name drifted")
    if state["networkId"] != net_id:
        fail("minio is not on the fixture network")
    if minio.get("aliasHost") != state["name"]:
        fail("minio aliasHost is not the verified fixture hostname")
    if minio.get("endpoint") != f"http://{state['name']}:9000":
        fail("minio endpoint is not the verified fixture hostname")
    return ticket


def resolve_postgres(ticket_path: str, stamp_dir: str, service: str) -> dict:
    ticket = authorize_postgres_service(ticket_path, stamp_dir, service)
    dest = postgres_destinations(ticket).get(service)
    if not dest:
        fail(f"isolated ticket does not authorize postgres service '{service}'")
    resolved = {
        "containerId": dest["containerId"],
        "containerName": dest["containerName"],
        "user": dest["user"],
        "database": dest["database"],
    }
    if "databaseIdentity" in dest:
        resolved["databaseIdentity"] = dest["databaseIdentity"]
    return resolved


def resolve_minio(ticket_path: str, stamp_dir: str) -> dict:
    ticket = authorize_minio(ticket_path, stamp_dir)
    minio = ticket.get("minio")
    if not minio:
        fail("isolated ticket does not authorize a MinIO destination")
    container = inspect_container(minio["containerId"])
    env = _env_map(container)
    user = env.get("MINIO_ROOT_USER") or env.get("MINIO_ACCESS_KEY")
    password = env.get("MINIO_ROOT_PASSWORD") or env.get("MINIO_SECRET_KEY")
    if not user or not password:
        fail("fixture MinIO container is missing root credentials in live inspect")
    return {
        "containerId": minio["containerId"],
        "containerName": minio["containerName"],
        "networkId": ticket["network"]["id"],
        "networkName": ticket["network"]["name"],
        "aliasHost": minio["aliasHost"],
        "endpoint": minio["endpoint"],
        "bucket": minio["bucket"],
        "user": user,
        "password": password,
    }


def require_recorded_id(value: str, label: str) -> str:
    ident = norm_id(value or "")
    if not ident:
        fail(f"{label} is missing a recorded docker id")
    base = container_basename(ident)
    if base in PRODUCTION_CONTAINERS or base.startswith("parkio-postgres-") or base in FORBIDDEN_NETWORKS:
        fail(f"{label} is a production/default identity")
    if not RECORDED_ID_RE.match(ident):
        fail(f"{label} is not a recorded docker id")
    return ident


def ticket_destinations(ticket: dict) -> list[tuple[str, dict, re.Pattern]]:
    dests: list[tuple[str, dict, re.Pattern]] = []
    for service, dest in postgres_destinations(ticket).items():
        dests.append((f"postgres.{service}", dest, PG_NAME_RE))
    minio = ticket.get("minio")
    if minio is not None:
        if not isinstance(minio, dict):
            fail("ticket minio destination is invalid")
        dests.append(("minio", minio, MINIO_NAME_RE))
    if not dests:
        fail("isolated-fixture ticket has no destinations")
    return dests


def authorize_ticket_dest(label: str, dest: dict, project: str, name_re: re.Pattern) -> tuple[str, str, str]:
    if not isinstance(dest, dict):
        fail(f"{label} is invalid")
    cid = require_recorded_id(dest.get("containerId") or "", f"{label} containerId")
    cname = dest.get("containerName") or ""
    assert_not_production_name(cname)
    inspect_name = cname if cname.startswith("/") else f"/{cname}"
    if not name_re.match(inspect_name):
        fail(f"{label} containerName is not a supported fixture identity")
    if not container_basename(cname).startswith(project):
        fail(f"{label} containerName does not belong to project {project}")
    vol = dest.get("volumeName") or ""
    if not VOLUME_RE.match(vol) or not vol.startswith(project):
        fail(f"{label} volume is not a fixture volume")
    if FORBIDDEN_VOLUME_HINT.search(vol):
        fail(f"{label} volume resembles a production volume")
    return cid, container_basename(cname), vol


def teardown_ticket(ticket_path: str) -> None:
    """Fail-closed fixture teardown. Mutates only after a complete authorized plan.

    bodyDigest is integrity of the ticket bytes, not producer authentication.
    """
    ticket = load_ticket(ticket_path)
    verify_schema(ticket)
    verify_digest(ticket)
    live = daemon_identity()
    verify_daemon(ticket, live)
    project = ticket.get("project")
    if not PROJECT_RE.match(project or ""):
        fail("ticket project is not parkio-iso-<12-hex>")
    net = ticket.get("network") or {}
    if not isinstance(net, dict):
        fail("ticket network is invalid")
    net_id = require_recorded_id(net.get("id") or "", "ticket network id")
    if (net.get("name") or "") != project:
        fail("ticket network name is not the fixture project")
    if project in FORBIDDEN_NETWORKS:
        fail("ticket network is a production/shared network")

    dests = ticket_destinations(ticket)
    ticket_container_ids: set[str] = set()
    ticket_names: dict[str, tuple[str, re.Pattern]] = {}
    ticket_volumes: list[str] = []
    seen_volumes: set[str] = set()
    for label, dest, name_re in dests:
        cid, cname, vol = authorize_ticket_dest(label, dest, project, name_re)
        ticket_container_ids.add(cid)
        prev = ticket_names.get(cid)
        if prev and prev[0] != cname:
            fail(f"{label} reuses a container id with a different name")
        ticket_names[cid] = (cname, name_re)
        if vol not in seen_volumes:
            ticket_volumes.append(vol)
            seen_volumes.add(vol)

    planned: list[tuple[str, str]] = []
    seen_plan: set[tuple[str, str]] = set()

    def plan(kind: str, ident: str) -> None:
        item = (kind, ident)
        if item not in seen_plan:
            planned.append(item)
            seen_plan.add(item)

    for cid, (cname, name_re) in ticket_names.items():
        obj = inspect_maybe("container", cid)
        if obj is None:
            continue
        live_container_state(
            obj,
            project,
            name_re,
            require_running=False,
            expected_name=cname,
            expected_id=cid,
        )
        plan("container", cid)

    for vol in ticket_volumes:
        obj = inspect_maybe("volume", vol)
        if obj is None:
            continue
        assert_volume(vol, project, allowed_consumer_ids=ticket_container_ids)
        plan("volume", vol)

    net_obj = inspect_maybe("network", net_id)
    if net_obj is None:
        by_name = inspect_maybe("network", project)
        if by_name is not None:
            other_id = norm_id(by_name.get("Id") or "")
            if other_id != net_id:
                fail("fixture network name exists with a different id; refusing name-based delete")
            assert_network(by_name, project, net_id)
            plan("network", net_id)
    else:
        assert_network(net_obj, project, net_id)
        plan("network", net_id)

    for kind, ident in planned:
        try:
            if kind == "container":
                docker("rm", "-f", ident)
            elif kind == "volume":
                docker("volume", "rm", ident)
            else:
                docker("network", "rm", ident)
        except ValueError as exc:
            fail(f"failed to delete {kind} {ident}: {exc}")

    for cid in ticket_container_ids:
        if inspect_maybe("container", cid) is not None:
            fail(f"container {cid} still exists after teardown")
    for vol in ticket_volumes:
        if inspect_maybe("volume", vol) is not None:
            fail(f"volume {vol} still exists after teardown")
    if inspect_maybe("network", net_id) is not None:
        fail("fixture network still exists after teardown")


def _pg_user(service: str) -> tuple[str, str]:
    if service not in SERVICE_CREDS:
        fail(f"unknown service '{service}'")
    return SERVICE_CREDS[service]


def issue_from_live(args) -> dict:
    if os.environ.get("PARKIO_RESTORE_FIXTURE_ORCHESTRATOR") != "1":
        fail("issue-from-live is reserved for scripts/restore-isolated-fixture.sh")
    daemon = daemon_identity()
    project = args.project
    if not PROJECT_RE.match(project):
        fail("project must match parkio-iso-<12-hex>")
    network = inspect_network(args.network_name)
    net_id = assert_network(network, project)
    postgres = {}
    pg_container = inspect_container(args.postgres_name)
    pg_state = live_container_state(pg_container, project, PG_NAME_RE)
    if pg_state["networkId"] != net_id:
        fail("postgres container is not on the fixture network")
    # The recovery-replay command reads the connected database's identity and refuses any
    # target other than this one, so the ticket pins it from the live cluster.
    cluster = system_identifier(pg_state["name"])
    for service in args.services.split(","):
        service = service.strip()
        user, database = _pg_user(service)
        postgres[service] = {
            "containerId": pg_state["id"],
            "containerName": pg_state["name"],
            "user": user,
            "database": database,
            "databaseIdentity": f"postgresql:{cluster}:{database}",
            "volumeName": pg_state["volumeName"],
        }
    payload = {
        "schemaVersion": 2,
        "kind": "parkio-isolated-fixture",
        "issuer": ISSUER,
        "stamp": stamp_realpath(args.stamp),
        "project": project,
        "dockerContext": daemon["dockerContext"],
        "dockerHost": daemon["dockerHost"],
        "dockerEngineId": daemon["dockerEngineId"],
        "network": {"id": net_id, "name": project},
        "postgres": postgres,
    }
    if args.minio_name:
        minio_container = inspect_container(args.minio_name)
        minio_state = live_container_state(minio_container, project, MINIO_NAME_RE)
        if minio_state["networkId"] != net_id:
            fail("minio container is not on the fixture network")
        payload["minio"] = {
            "containerId": minio_state["id"],
            "containerName": minio_state["name"],
            "aliasHost": minio_state["name"],
            "endpoint": f"http://{minio_state['name']}:9000",
            "bucket": f"{project}-media",
            "volumeName": minio_state["volumeName"],
        }
    ticket = add_digest(payload)
    out = Path(args.out)
    out.write_text(json.dumps(ticket, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(out, 0o600)
    validate_ticket_live(str(out), args.stamp)
    return ticket


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Validate isolated restore destinations")
    parser.add_argument("--ticket", "--check-ticket", dest="ticket")
    parser.add_argument("--stamp")
    parser.add_argument("--resolve-postgres")
    parser.add_argument("--resolve-minio", action="store_true")
    parser.add_argument("--issue-from-live", action="store_true")
    parser.add_argument("--teardown", action="store_true")
    parser.add_argument("--out")
    parser.add_argument("--project")
    parser.add_argument("--network-name")
    parser.add_argument("--postgres-name")
    parser.add_argument("--minio-name")
    parser.add_argument("--services")
    args = parser.parse_args(argv)
    try:
        if args.issue_from_live:
            if not all([args.out, args.stamp, args.project, args.network_name, args.postgres_name, args.services]):
                fail("--issue-from-live requires --out --stamp --project --network-name --postgres-name --services")
            issue_from_live(args)
            print(args.out)
            return 0
        if args.teardown:
            if not args.ticket:
                fail("--teardown requires --ticket")
            teardown_ticket(args.ticket)
            return 0
        if not args.ticket or not args.stamp:
            fail("--ticket/--check-ticket and --stamp are required")
        if args.resolve_postgres:
            json.dump(resolve_postgres(args.ticket, args.stamp, args.resolve_postgres), sys.stdout)
            sys.stdout.write("\n")
            return 0
        if args.resolve_minio:
            json.dump(resolve_minio(args.ticket, args.stamp), sys.stdout)
            sys.stdout.write("\n")
            return 0
        validate_ticket_live(args.ticket, args.stamp)
        return 0
    except (OSError, ValueError, json.JSONDecodeError, KeyError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
