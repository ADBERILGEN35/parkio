#!/usr/bin/env python3
"""Write synthetic docker inspect fixtures and a destination-bound ticket.

Used only by restore isolation tests. Does not talk to a docker daemon.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
import importlib.util

spec = importlib.util.spec_from_file_location(
    "restore_isolated_ticket", ROOT / "scripts" / "lib" / "restore-isolated-ticket.py"
)
ticket_mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ticket_mod)

PROJECT = "parkio-iso-abcdef012345"
ENGINE = "stub-engine"
CONTEXT = "default"
HOST = "unix:///var/run/docker.sock"
NET_ID = "n" * 64
PG_ID = "a" * 64
MINIO_ID = "b" * 64
PG_VOL = f"{PROJECT}-vol-pg"
MINIO_VOL = f"{PROJECT}-vol-minio"
SERVICES = list(ticket_mod.SERVICE_CREDS)


def container(name: str, cid: str, vol: str, extra_network=None, published=False, env=None, aliases=None):
    networks = {
        PROJECT: {"NetworkID": NET_ID, "Aliases": aliases or [name.lstrip("/")]},
    }
    if extra_network:
        networks[extra_network] = {"NetworkID": "deadbeef" * 8, "Aliases": []}
    ports = {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]} if published else {}
    bindings = {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]} if published else {}
    return {
        "Id": cid,
        "Name": name if name.startswith("/") else f"/{name}",
        "State": {"Running": True},
        "HostConfig": {
            "NetworkMode": PROJECT if not extra_network else extra_network,
            "PortBindings": bindings,
            "Binds": None,
        },
        "NetworkSettings": {"Networks": networks, "Ports": ports},
        "Mounts": [{
            "Type": "volume",
            "Name": vol,
            "Destination": "/var/lib/postgresql/data",
            "Source": f"/var/lib/docker/volumes/{vol}/_data",
        }],
        "Config": {"Env": env or []},
    }


def network_obj():
    return {
        "Id": NET_ID,
        "Name": PROJECT,
        "Driver": "bridge",
        "Internal": True,
        "Ingress": False,
    }


def volume_obj(name: str):
    return {"Name": name, "Driver": "local", "Options": None}


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data), encoding="utf-8")


def payload(stamp: str, postgres_name=None, pg_id=None, include_minio=True, services=None):
    postgres_name = postgres_name or f"{PROJECT}-pg"
    pg_id = pg_id or PG_ID
    dests = {}
    for service in (services or SERVICES):
        user, database = ticket_mod.SERVICE_CREDS[service]
        dests[service] = {
            "containerId": pg_id,
            "containerName": postgres_name,
            "user": user,
            "database": database,
            "volumeName": PG_VOL,
        }
    body = {
        "schemaVersion": 2,
        "kind": "parkio-isolated-fixture",
        "issuer": ticket_mod.ISSUER,
        "stamp": str(Path(stamp).resolve()),
        "project": PROJECT,
        "dockerContext": CONTEXT,
        "dockerHost": HOST,
        "dockerEngineId": ENGINE,
        "network": {"id": NET_ID, "name": PROJECT},
        "postgres": dests,
    }
    if include_minio:
        body["minio"] = {
            "containerId": MINIO_ID,
            "containerName": f"{PROJECT}-minio",
            "aliasHost": f"{PROJECT}-minio",
            "endpoint": f"http://{PROJECT}-minio:9000",
            "bucket": f"{PROJECT}-media",
            "volumeName": MINIO_VOL,
        }
    return body


def write_inspect(inspect_dir: Path, pg_name=None, pg_id=None, extra_network=None,
                  published=False, vol=None, minio=True, aliases=None, minio_env=None):
    inspect_dir.mkdir(parents=True, exist_ok=True)
    pg_name = pg_name or f"{PROJECT}-pg"
    pg_id = pg_id or PG_ID
    vol = vol or PG_VOL
    pg = container(
        f"/{pg_name}", pg_id, vol,
        extra_network=extra_network, published=published, aliases=aliases,
        env=["POSTGRES_USER=postgres", "POSTGRES_DB=postgres"],
    )
    write_json(inspect_dir / f"{pg_id}.json", pg)
    write_json(inspect_dir / f"{pg_name}.json", pg)
    write_json(inspect_dir / f"{NET_ID}.json", network_obj())
    write_json(inspect_dir / f"{PROJECT}.json", network_obj())
    write_json(inspect_dir / f"{vol}.json", volume_obj(vol))
    if minio:
        env = minio_env or ["MINIO_ROOT_USER=parkioiso", "MINIO_ROOT_PASSWORD=stub-minio-pass"]
        mc = container(
            f"/{PROJECT}-minio", MINIO_ID, MINIO_VOL,
            env=env, aliases=[f"{PROJECT}-minio"],
        )
        mc["Mounts"][0]["Destination"] = "/data"
        write_json(inspect_dir / f"{MINIO_ID}.json", mc)
        write_json(inspect_dir / f"{PROJECT}-minio.json", mc)
        write_json(inspect_dir / f"{MINIO_VOL}.json", volume_obj(MINIO_VOL))
    write_json(inspect_dir / "info.json", {
        "ID": ENGINE, "Name": "stub", "Swarm": {"LocalNodeState": "inactive"},
    })
    write_json(inspect_dir / "context.json", {
        "Name": CONTEXT,
        "Endpoints": {"docker": {"Host": HOST}},
    })


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inspect-dir", required=True)
    parser.add_argument("--stamp", required=True)
    parser.add_argument("--out-ticket", required=True)
    parser.add_argument("--mode", default="ok")
    args = parser.parse_args(argv)
    inspect_dir = Path(args.inspect_dir)
    stamp = args.stamp
    mode = args.mode

    if mode == "ok":
        write_inspect(inspect_dir)
        body = payload(stamp)
    elif mode == "production-name":
        write_inspect(inspect_dir, pg_name="parkio-postgres-auth")
        body = payload(stamp, postgres_name="parkio-postgres-auth")
    elif mode == "misleading-name":
        write_inspect(inspect_dir, aliases=["parkio-postgres-auth", "minio"])
        body = payload(stamp)
    elif mode == "wrong-stamp":
        write_inspect(inspect_dir)
        body = payload(stamp)
        body["stamp"] = str(Path(stamp).resolve().parent / "other-stamp")
    elif mode == "forged":
        write_inspect(inspect_dir)
        body = payload(stamp)
        ticket = ticket_mod.add_digest(body)
        ticket["postgres"]["auth"]["containerName"] = "parkio-postgres-auth"
        Path(args.out_ticket).write_text(json.dumps(ticket, indent=2), encoding="utf-8")
        os.chmod(args.out_ticket, 0o600)
        return 0
    elif mode == "published-port":
        write_inspect(inspect_dir, published=True)
        body = payload(stamp)
    elif mode == "extra-network":
        write_inspect(inspect_dir, extra_network="parkio-backend")
        body = payload(stamp)
    elif mode == "prod-volume":
        write_inspect(inspect_dir, vol="parkio-postgres-auth-data")
        body = payload(stamp)
        body["postgres"]["auth"]["volumeName"] = "parkio-postgres-auth-data"
        for dest in body["postgres"].values():
            dest["volumeName"] = "parkio-postgres-auth-data"
    elif mode == "drift":
        write_inspect(inspect_dir, pg_id="c" * 64)
        body = payload(stamp, pg_id=PG_ID)
    elif mode == "old-marker":
        Path(args.out_ticket).write_text(
            f"parkio-isolated-fixture=1\nstamp={Path(stamp).resolve()}\n",
            encoding="utf-8",
        )
        os.chmod(args.out_ticket, 0o600)
        write_inspect(inspect_dir)
        return 0
    elif mode == "wrong-target":
        write_inspect(inspect_dir)
        body = payload(stamp, services=["auth"])
    else:
        raise SystemExit(f"unknown mode {mode}")

    ticket = ticket_mod.add_digest(body)
    Path(args.out_ticket).write_text(json.dumps(ticket, indent=2) + "\n", encoding="utf-8")
    os.chmod(args.out_ticket, 0o600)
    return 0


if __name__ == "__main__":
    sys.exit(main())
