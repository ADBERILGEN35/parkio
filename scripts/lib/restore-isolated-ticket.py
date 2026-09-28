#!/usr/bin/env python3
"""Destination-bound isolated-fixture tickets.

Restore entrypoints must not issue these. Only the disposable orchestrator
writes a ticket after it has created the targets. Digest covers every field
except bodyDigest itself.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path

SCHEMA_VERSION = 2
KIND = "parkio-isolated-fixture"
ISSUER = "scripts/restore-isolated-fixture.sh"

SERVICE_CREDS = {
    "auth": ("parkio_auth", "parkio_auth"),
    "gateway": ("parkio_gateway", "parkio_gateway"),
    "user": ("parkio_user", "parkio_user"),
    "parking": ("parkio_parking", "parkio_parking"),
    "media": ("parkio_media", "parkio_media"),
    "gamification": ("parkio_gamification", "parkio_gamification"),
    "notification": ("parkio_notification", "parkio_notification"),
    "moderation": ("parkio_moderation", "parkio_moderation"),
    "analytics": ("parkio_analytics", "parkio_analytics"),
    "ai-validation": ("parkio_aivalidation", "parkio_aivalidation"),
}

PRODUCTION_CONTAINERS = {
    "parkio-postgres-auth",
    "parkio-postgres-gateway",
    "parkio-postgres-user",
    "parkio-postgres-parking",
    "parkio-postgres-media",
    "parkio-postgres-gamification",
    "parkio-postgres-notification",
    "parkio-postgres-moderation",
    "parkio-postgres-analytics",
    "parkio-postgres-ai-validation",
    "parkio-minio",
    "parkio-offsite-minio",
    "parkio-minio-setup",
    "rd-postgres",
}


def canonical_body(ticket: dict) -> str:
    body = {key: value for key, value in ticket.items() if key != "bodyDigest"}
    return json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=True)


def add_digest(ticket: dict) -> dict:
    ticket = dict(ticket)
    ticket.pop("bodyDigest", None)
    ticket["bodyDigest"] = hashlib.sha256(canonical_body(ticket).encode("utf-8")).hexdigest()
    return ticket


def load_ticket(path: str) -> dict:
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ValueError(f"isolated-fixture ticket is not readable JSON: {type(exc).__name__}") from exc
    if not isinstance(data, dict):
        raise ValueError("isolated-fixture ticket must be a JSON object")
    return data


def verify_digest(ticket: dict) -> None:
    digest = ticket.get("bodyDigest")
    if not isinstance(digest, str) or len(digest) != 64:
        raise ValueError("isolated-fixture ticket bodyDigest missing")
    expected = hashlib.sha256(canonical_body(ticket).encode("utf-8")).hexdigest()
    if digest != expected:
        raise ValueError("isolated-fixture ticket bodyDigest mismatch")


def verify_schema(ticket: dict) -> None:
    if ticket.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("isolated-fixture ticket schemaVersion is not 2")
    if ticket.get("kind") != KIND:
        raise ValueError("isolated-fixture ticket kind is invalid")
    if ticket.get("issuer") != ISSUER:
        raise ValueError("isolated-fixture ticket issuer is not the disposable orchestrator")
    project = ticket.get("project")
    if not isinstance(project, str) or not project.startswith("parkio-iso-"):
        raise ValueError("isolated-fixture ticket project is not a parkio-iso-* identity")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Issue or inspect destination-bound restore tickets")
    sub = parser.add_subparsers(dest="cmd", required=True)
    issue = sub.add_parser("issue", help="Sign a payload (orchestrator only)")
    issue.add_argument("--out", required=True)
    creds = sub.add_parser("service-creds", help="Print service user/database map")
    args = parser.parse_args(argv)

    if args.cmd == "service-creds":
        json.dump({name: {"user": user, "database": db} for name, (user, db) in SERVICE_CREDS.items()},
                  sys.stdout, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0

    if os.environ.get("PARKIO_RESTORE_FIXTURE_ORCHESTRATOR") != "1":
        print("ERROR: ticket issue is reserved for scripts/restore-isolated-fixture.sh", file=sys.stderr)
        return 2
    try:
        payload = json.load(sys.stdin)
        if not isinstance(payload, dict):
            raise ValueError("payload must be a JSON object")
        payload["schemaVersion"] = SCHEMA_VERSION
        payload["kind"] = KIND
        payload["issuer"] = ISSUER
        verify_schema(payload)
        ticket = add_digest(payload)
        verify_digest(ticket)
        out = Path(args.out)
        out.write_text(json.dumps(ticket, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.chmod(out, 0o600)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2
    print(str(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
