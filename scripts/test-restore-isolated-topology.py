#!/usr/bin/env python3
"""Write synthetic docker inspect fixtures and a destination-bound ticket.

Used only by restore isolation tests. Does not talk to a docker daemon.
"""
from __future__ import annotations

import argparse
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
NET_ID = "d" * 64
PG_ID = "a" * 64
MINIO_ID = "b" * 64
UNRELATED_ID = "e" * 64
PG_VOL = f"{PROJECT}-vol-pg"
MINIO_VOL = f"{PROJECT}-vol-minio"
SERVICES = list(ticket_mod.SERVICE_CREDS)
FIXTURE_LABELS = {
    "parkio.isolated.fixture": "1",
    "parkio.isolated.project": PROJECT,
}


def container(name: str, cid: str, vol: str, extra_network=None, published=False, env=None, aliases=None,
              running=True, extra_mounts=None):
    networks = {
        PROJECT: {"NetworkID": NET_ID, "Aliases": aliases or [name.lstrip("/")]},
    }
    if extra_network:
        networks[extra_network] = {"NetworkID": "cafecafe" * 8, "Aliases": []}
    ports = {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]} if published else {}
    bindings = {"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]} if published else {}
    mounts = [{
        "Type": "volume",
        "Name": vol,
        "Destination": "/var/lib/postgresql/data",
        "Source": f"/var/lib/docker/volumes/{vol}/_data",
    }]
    if extra_mounts:
        mounts.extend(extra_mounts)
    labels = dict(FIXTURE_LABELS)
    return {
        "Id": cid,
        "Name": name if name.startswith("/") else f"/{name}",
        "State": {"Running": running, "Status": "running" if running else "exited"},
        "Labels": labels,
        "HostConfig": {
            "NetworkMode": PROJECT if not extra_network else extra_network,
            "PortBindings": bindings,
            "Binds": None,
        },
        "NetworkSettings": {"Networks": networks, "Ports": ports},
        "Mounts": mounts,
        "Config": {"Env": env or [], "Labels": labels},
    }


def network_obj():
    return {
        "Id": NET_ID,
        "Name": PROJECT,
        "Driver": "bridge",
        "Internal": True,
        "Ingress": False,
        "Labels": dict(FIXTURE_LABELS),
    }


def volume_obj(name: str, options=None, labels=True):
    return {
        "Name": name,
        "Driver": "local",
        "Scope": "local",
        "Options": options,
        "Labels": dict(FIXTURE_LABELS) if labels else {},
    }


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data), encoding="utf-8")


STUB_SYSTEM_IDENTIFIER = "7000000000000000099"


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
                  published=False, vol=None, minio=True, aliases=None, minio_env=None,
                  volume_options=None, extra_mounts=None, running=True, volume_labels=True,
                  volume_consumers=None, engine=ENGINE):
    inspect_dir.mkdir(parents=True, exist_ok=True)
    pg_name = pg_name or f"{PROJECT}-pg"
    pg_id = pg_id or PG_ID
    vol = vol or PG_VOL
    pg = container(
        f"/{pg_name}", pg_id, vol,
        extra_network=extra_network, published=published, aliases=aliases,
        env=["POSTGRES_USER=postgres", "POSTGRES_DB=postgres"],
        running=running, extra_mounts=extra_mounts,
    )
    write_json(inspect_dir / f"{pg_id}.json", pg)
    write_json(inspect_dir / f"{pg_name}.json", pg)
    write_json(inspect_dir / f"{NET_ID}.json", network_obj())
    write_json(inspect_dir / f"{PROJECT}.json", network_obj())
    write_json(inspect_dir / f"{vol}.json", volume_obj(vol, options=volume_options, labels=volume_labels))
    consumers = volume_consumers if volume_consumers is not None else [pg_id]
    write_json(inspect_dir / f"volume-consumers-{vol}.json", consumers)
    if minio:
        env = minio_env or ["MINIO_ROOT_USER=parkioiso", "MINIO_ROOT_PASSWORD=stub-minio-pass"]
        mc = container(
            f"/{PROJECT}-minio", MINIO_ID, MINIO_VOL,
            env=env, aliases=[f"{PROJECT}-minio"],
            running=running,
        )
        mc["Mounts"][0]["Destination"] = "/data"
        write_json(inspect_dir / f"{MINIO_ID}.json", mc)
        write_json(inspect_dir / f"{PROJECT}-minio.json", mc)
        write_json(inspect_dir / f"{MINIO_VOL}.json", volume_obj(MINIO_VOL, labels=volume_labels))
        write_json(inspect_dir / f"volume-consumers-{MINIO_VOL}.json", [MINIO_ID])
    write_json(inspect_dir / "info.json", {
        "ID": engine, "Name": "stub", "Swarm": {"LocalNodeState": "inactive"},
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
    elif mode in ("ok-recovery", "identity-drift"):
        # U02 stage 4: the ticket pins each target's identity; the stub docker answers
        # pg_control_system() with STUB_SYSTEM_IDENTIFIER.
        write_inspect(inspect_dir)
        body = payload(stamp)
        cluster = STUB_SYSTEM_IDENTIFIER if mode == "ok-recovery" else "7000000000000000777"
        for dest in body["postgres"].values():
            dest["databaseIdentity"] = f"postgresql:{cluster}:{dest['database']}"
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
    elif mode == "bind-volume":
        write_inspect(
            inspect_dir,
            volume_options={"type": "none", "device": "/var/lib/parkio-postgres-auth", "o": "bind"},
        )
        body = payload(stamp)
    elif mode == "device-volume":
        write_inspect(inspect_dir, volume_options={"device": "/dev/loop0"})
        body = payload(stamp)
    elif mode == "nfs-volume":
        write_inspect(
            inspect_dir,
            volume_options={"type": "nfs", "o": "addr=10.0.0.1,rw", "device": ":/export/pg"},
        )
        body = payload(stamp)
    elif mode == "cifs-volume":
        write_inspect(
            inspect_dir,
            volume_options={"type": "cifs", "device": "//fileserver/share", "o": "username=parkio"},
        )
        body = payload(stamp)
    elif mode == "unlabeled-volume":
        write_inspect(inspect_dir, volume_labels=False)
        body = payload(stamp)
    elif mode == "extra-mount":
        write_inspect(inspect_dir, extra_mounts=[{
            "Type": "bind",
            "Source": "/etc",
            "Destination": "/host-etc",
        }])
        body = payload(stamp)
    elif mode == "extra-volume":
        extra = f"{PROJECT}-vol-extra"
        write_inspect(inspect_dir, extra_mounts=[{
            "Type": "volume",
            "Name": extra,
            "Destination": "/extra",
            "Source": f"/var/lib/docker/volumes/{extra}/_data",
        }])
        write_json(inspect_dir / f"{extra}.json", volume_obj(extra))
        write_json(inspect_dir / f"volume-consumers-{extra}.json", [PG_ID])
        body = payload(stamp)
    elif mode == "unrelated-consumer":
        write_inspect(inspect_dir, volume_consumers=[PG_ID, UNRELATED_ID])
        body = payload(stamp)
    elif mode == "stopped":
        write_inspect(inspect_dir, running=False)
        body = payload(stamp)
    elif mode == "wrong-daemon":
        write_inspect(inspect_dir, engine="other-engine")
        body = payload(stamp)
    elif mode == "mixed-unrelated":
        write_inspect(inspect_dir)
        body = payload(stamp)
        body["minio"] = {
            "containerId": "parkio-minio",
            "containerName": "parkio-minio",
            "aliasHost": "parkio-minio",
            "endpoint": "http://parkio-minio:9000",
            "bucket": f"{PROJECT}-media",
            "volumeName": "parkio_minio",
        }
    else:
        raise SystemExit(f"unknown mode {mode}")

    ticket = ticket_mod.add_digest(body)
    Path(args.out_ticket).write_text(json.dumps(ticket, indent=2) + "\n", encoding="utf-8")
    os.chmod(args.out_ticket, 0o600)
    return 0


if __name__ == "__main__":
    sys.exit(main())
