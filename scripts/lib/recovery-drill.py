#!/usr/bin/env python3
"""Helpers of the U02 recovery drill (scripts/recovery-drill.sh). Disposable, synthetic data only.

  recovery-drill.py env-file --example FILE --out FILE [--jwt-key] [--set KEY=VALUE ...]
  recovery-drill.py trust --identity IDENTITY --key-id ID --out FILE
  recovery-drill.py variants --bundle BUNDLE --out DIR
  recovery-drill.py register|login|delete --container NAME --email EMAIL [--token-file FILE]
  recovery-drill.py report --evidence DIR

Secrets (generated passwords, the trust document's HMAC key, access tokens) are written only to
files of mode 600 in the drill's work directory and are never printed. The synthetic user's
password (DRILL_USER_PASSWORD) and the gateway secret (DRILL_GATEWAY_SECRET) reach the auth
container on stdin, not argv.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import secrets
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from recovery_evidence_bundle import encode_bundle  # noqa: E402

AUTH_URL = "http://localhost:8081"
ZERO_REQUEST = "00000000-0000-0000-0000-000000000000"


def write_private(path, text):
    path = Path(path)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as out:
        out.write(text)


def env_file(args):
    """The example env file with every non-empty *_PASSWORD / *_SECRET / *_HMAC_KEY replaced by a random value."""
    overrides = {}
    for item in args.set or []:
        key, _, value = item.partition("=")
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise SystemExit(f"--set needs KEY=VALUE, not {item!r}")
        overrides[key] = value
    if args.jwt_key:
        # A disposable PKCS#8 key for auth, on one double-quoted line with \n escapes.
        pem = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"],
                             capture_output=True, text=True, check=True).stdout.strip()
        overrides["PARKIO_JWT_PRIVATE_KEY_PEM"] = '"' + pem.replace("\n", "\\n") + '"'
    lines = []
    seen = set()
    for line in Path(args.example).read_text(encoding="utf-8").splitlines():
        match = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
        if not match:
            lines.append(line)
            continue
        key, value = match.groups()
        seen.add(key)
        if key in overrides:
            value = overrides[key]
        elif (key.endswith("_PASSWORD") or key.endswith("_SECRET") or key.endswith("_HMAC_KEY")) and value:
            value = secrets.token_hex(24)
        lines.append(f"{key}={value}")
    for key, value in overrides.items():
        if key not in seen:
            lines.append(f"{key}={value}")
    write_private(args.out, "\n".join(lines) + "\n")


def trust(args):
    """A disposable trust document pinned to the primary auth database, with one producer key."""
    not_before = (datetime.now(timezone.utc) - timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    document = {
        "format": "parkio-erasure-evidence-trust",
        "version": 1,
        "databaseIdentity": args.identity,
        "keys": [{
            "keyId": args.key_id,
            "producerId": "auth-service-recovery-drill",
            "keyHex": secrets.token_hex(32),
            "notBefore": not_before,
        }],
    }
    write_private(args.out, json.dumps(document, indent=2) + "\n")


def variants(args):
    """Negative bundles from the drill's real bundle, re-encoded so only the named defect remains."""
    bundle = json.loads(Path(args.bundle).read_text(encoding="utf-8"))
    objects = {key: base64.b64decode(value) for key, value in bundle["objects"].items()}
    versions = [(item["versionId"], base64.b64decode(item["data"])) for item in bundle["frontierVersions"]]
    source = bundle.get("source", "drill")
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    markers = []
    for key, raw in objects.items():
        if key.startswith("sequences/"):
            marker = json.loads(raw)
            if marker.get("erasureRequestId") != ZERO_REQUEST:
                markers.append((marker["sequence"], marker["erasureRequestId"]))
    if not markers:
        raise SystemExit("the bundle holds no erasure record")
    markers.sort()
    newest = f"records/{markers[-1][1]}.json"
    if newest not in objects:
        raise SystemExit(f"the bundle has no {newest}")

    # gap: the newest record is missing below the frontier.
    gap = dict(objects)
    del gap[newest]
    # corrupt: one record names another user; its signature no longer matches its bytes.
    corrupt = dict(objects)
    record = json.loads(corrupt[newest])
    user = record["authUserId"]
    other = user[:-1] + ("0" if user[-1] != "0" else "1")
    corrupt[newest] = corrupt[newest].replace(user.encode("ascii"), other.encode("ascii"), 1)
    written = {
        "gap": encode_bundle(gap, versions, source),
        "corrupt": encode_bundle(corrupt, versions, source),
        # frontier-missing: no frontier version at all, so the verified boundary is unknown.
        "frontier-missing": encode_bundle(objects, [], source),
    }
    for name, document in written.items():
        (out / f"{name}.json").write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(" ".join(sorted(written)))


def curl(container, config, expect):
    """Runs curl inside the auth container with its config (headers, body) on stdin."""
    result = subprocess.run(
        ["docker", "exec", "-i", container, "curl", "-sS", "-K", "-", "-o", "-", "-w", "\n%{http_code}"],
        input=config, capture_output=True, text=True, check=False)
    body, _, status = result.stdout.rpartition("\n")
    if result.returncode != 0 or status not in expect:
        # A success body can hold tokens; an error body names the error, so only its code and
        # message are reported.
        detail = ""
        if status.startswith("4"):
            try:
                error = json.loads(body)
                detail = " " + json.dumps({key: error[key] for key in ("code", "error", "message", "detail", "errors")
                                           if key in error})
            except (ValueError, TypeError):
                detail = ""
        raise SystemExit(f"auth answered HTTP {status or '?'} (curl exit {result.returncode}){detail}")
    return body


def quote(value):
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def config(method, path, body=None, token=None):
    lines = [f"url = {quote(AUTH_URL + path)}", f"request = {quote(method)}",
             'header = "Content-Type: application/json"']
    # auth accepts API calls only with the gateway's shared secret (GatewayAuthFilter); the drill
    # calls auth directly and adds the disposable secret the gateway would add.
    secret = os.environ.get("DRILL_GATEWAY_SECRET", "")
    if secret:
        lines.append(f"header = {quote('X-Gateway-Auth: ' + secret)}")
    if token:
        lines.append(f"header = {quote('Authorization: Bearer ' + token)}")
    if body is not None:
        lines.append(f"data-binary = {quote(json.dumps(body))}")
    return "\n".join(lines) + "\n"


def password():
    value = os.environ.get("DRILL_USER_PASSWORD", "")
    if len(value) < 12:
        raise SystemExit("DRILL_USER_PASSWORD must hold the synthetic user's password")
    return value


def register(args):
    curl(args.container, config("POST", "/api/v1/auth/register",
                                {"email": args.email, "password": password(), "locale": "en"}), {"200", "201"})


def login(args):
    body = curl(args.container, config("POST", "/api/v1/auth/login",
                                       {"email": args.email, "password": password()}), {"200"})
    token = json.loads(body).get("accessToken")
    if not token:
        raise SystemExit("login answered without an access token")
    write_private(args.token_file, token)


def delete(args):
    token = Path(args.token_file).read_text(encoding="utf-8").strip()
    body = curl(args.container, config("DELETE", "/api/v1/account", {"password": password()}, token),
                {"200", "202"})
    print(json.loads(body).get("status", "?"))


def read_tsv(path):
    if not Path(path).is_file():
        return []
    return [line.split("\t") for line in Path(path).read_text(encoding="utf-8").splitlines() if line]


def report(args):
    """drill-report.json from the phase timings, the checks and the run facts."""
    evidence = Path(args.evidence)
    phases = []
    for name, start, end in read_tsv(evidence / "phases.tsv"):
        phases.append({"phase": name,
                       "start": datetime.fromtimestamp(float(start), timezone.utc).isoformat(timespec="milliseconds"),
                       "end": datetime.fromtimestamp(float(end), timezone.utc).isoformat(timespec="milliseconds"),
                       "seconds": round(float(end) - float(start), 3)})
    checks = [{"check": row[0], "result": row[1], "detail": row[2] if len(row) > 2 else ""}
              for row in read_tsv(evidence / "checks.tsv")]
    facts = {}
    for row in read_tsv(evidence / "facts.tsv"):
        facts[row[0]] = row[1] if len(row) > 1 else ""
    by_name = {phase["phase"]: phase for phase in phases}

    def at(name, edge):
        phase = by_name.get(name)
        return datetime.fromisoformat(phase[edge]) if phase else None

    measured = {}
    loss_end, exposed = at("P4-host-loss", "end"), at("P10-expose", "end")
    if loss_end and exposed:
        measured["rtoSeconds"] = round((exposed - loss_end).total_seconds(), 3)
        measured["rto"] = "end of P4 (host loss) to end of P10 (expose gate OPEN on a COMPLETE replay)"
    backup_at = facts.get("backupManifestTimestamp")
    loss_start = at("P4-host-loss", "start")
    if backup_at and loss_start:
        # backup-hosted-beta.sh stamps read 2026-10-07T12-00-00Z.
        backup = datetime.strptime(backup_at, "%Y-%m-%dT%H-%M-%SZ").replace(tzinfo=timezone.utc)
        measured["rpoDataSeconds"] = round((loss_start - backup).total_seconds(), 3)
        measured["rpoData"] = "host loss minus the backup manifest time: data written after the backup is lost"
    if "erasuresNotReplayed" in facts:
        measured["rpoErasures"] = int(facts["erasuresNotReplayed"])
    failed = [check for check in checks if check["result"] != "PASS"]
    document = {
        "format": "parkio-recovery-drill-report",
        "version": 1,
        "result": "PASS" if checks and not failed and facts.get("completed") == "true" else "FAIL",
        "facts": facts,
        "phases": phases,
        "measured": measured,
        "checks": checks,
        "notCovered": [
            "Slack/operational-notification replay and the NR budget (#101/#103/#104 are HOLD): not exercised",
            "time-based erasure coverage: none is claimed; coverage is the verified sequence only (owner D1)",
        ],
    }
    (evidence / "drill-report.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(document["result"], f"checks={len(checks)} failed={len(failed)}", json.dumps(measured))
    return 0 if document["result"] == "PASS" else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    command = sub.add_parser("env-file")
    command.add_argument("--example", required=True)
    command.add_argument("--out", required=True)
    command.add_argument("--set", action="append")
    command.add_argument("--jwt-key", action="store_true", help="generate a disposable auth JWT signing key")
    command = sub.add_parser("trust")
    command.add_argument("--identity", required=True)
    command.add_argument("--key-id", required=True)
    command.add_argument("--out", required=True)
    command = sub.add_parser("variants")
    command.add_argument("--bundle", required=True)
    command.add_argument("--out", required=True)
    for name in ("register", "login", "delete"):
        command = sub.add_parser(name)
        command.add_argument("--container", required=True)
        command.add_argument("--email", required=name != "delete")
        command.add_argument("--token-file", required=name != "register")
    command = sub.add_parser("report")
    command.add_argument("--evidence", required=True)
    args = parser.parse_args(argv)
    handlers = {"env-file": env_file, "trust": trust, "variants": variants, "register": register,
                "login": login, "delete": delete, "report": report}
    return handlers[args.command](args) or 0


if __name__ == "__main__":
    sys.exit(main())
