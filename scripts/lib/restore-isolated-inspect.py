#!/usr/bin/env python3
"""Live topology checks for destination-bound isolated restore tickets.

A CLI flag, env var, container name, or marker file is not isolation proof.
The orchestrator records docker context/host/engine plus container, network
and volume identities; apply must use those same identities after a live
re-inspect. This prevents accidental/misrouted supported-script use. It does
not stop a malicious root operator who can edit these scripts.
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


def fail(message: str) -> None:
    raise ValueError(message)


def docker_argv(args: tuple[str, ...]) -> list[str]:
    override = os.environ.get("PARKIO_RESTORE_DOCKER")
    if override:
        return [sys.executable, override, *args]
    return ["docker", *args]


def docker(*args: str) -> str:
    argv = docker_argv(args)
    try:
        completed = subprocess.run(
            argv,
            check=True,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
        )
    except FileNotFoundError as exc:
        raise ValueError("docker CLI is not available") from exc
    except subprocess.CalledProcessError as exc:
        err = (exc.stderr or exc.stdout or "").strip()
        raise ValueError(f"docker {' '.join(args)} failed: {err or exc.returncode}") from exc
    return completed.stdout


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
    return docker_json("inspect", "--type=network", ref)


def inspect_volume(ref: str) -> dict:
    return docker_json("inspect", "--type=volume", ref)


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
    return net_id


def assert_volume(name: str, project: str) -> None:
    if not VOLUME_RE.match(name or "") or not name.startswith(project):
        fail(f"volume '{name}' is not a fixture volume")
    if FORBIDDEN_VOLUME_HINT.search(name or ""):
        fail(f"volume '{name}' resembles a production volume")
    vol = inspect_volume(name)
    if (vol.get("Name") or "") != name:
        fail(f"volume name drifted for {name}")
    if (vol.get("Driver") or "local") != "local":
        fail(f"volume '{name}' uses unsupported driver")


def live_container_state(container: dict, project: str, name_re: re.Pattern) -> dict:
    name = container.get("Name") or ""
    assert_not_production_name(name)
    if not name_re.match(name):
        fail(f"container name '{name}' is not a supported fixture identity")
    if not container_basename(name).startswith(project):
        fail(f"container '{name}' does not belong to project {project}")
    state = container.get("State") or {}
    if not state.get("Running"):
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
        assert_volume(vol_name, project)
        volume_names.append(vol_name)
    if not volume_names:
        fail(f"container {name} has no fixture volume")
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
        "id": norm_id(container.get("Id") or ""),
        "name": container_basename(name),
        "networkName": net_name,
        "networkId": norm_id(net_cfg.get("NetworkID") or ""),
        "volumeName": volume_names[0],
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
    return {
        "containerId": dest["containerId"],
        "containerName": dest["containerName"],
        "user": dest["user"],
        "database": dest["database"],
    }


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
    for service in args.services.split(","):
        service = service.strip()
        user, database = _pg_user(service)
        postgres[service] = {
            "containerId": pg_state["id"],
            "containerName": pg_state["name"],
            "user": user,
            "database": database,
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
