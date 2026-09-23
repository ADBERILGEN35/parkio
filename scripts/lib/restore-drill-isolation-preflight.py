#!/usr/bin/env python3
"""Refuse to start a restore drill unless the destination is isolated and inert.

Run on the disposable drill host immediately before decrypting or restoring.
It inspects the process environment plus an optional drill env file, the host
name, optionally the running container names, and optionally outbound TCP.
Only variable NAMES and check ids are printed — never values.

Usage:
  restore-drill-isolation-preflight.py [--env-file FILE] [--containers-file FILE]
      [--probe-egress host:port[,host:port...] | --probe-default-egress]
      [--timeout SECONDS]

--containers-file takes `docker ps --format '{{.Names}}'` output, so the check
itself never talks to a Docker daemon.

Exit: 0 = PASS, 1 = FAIL, 2 = usage error.
"""
import argparse
import json
import os
import re
import socket
import sys

FORBIDDEN_HOSTS = {"parkio-civo-prod", "vm-parkio-hosted-beta", "vm-parkio-invite-prod"}

# Outbound credentials that must be absent (or an obvious dummy) during a drill.
OUTBOUND_SECRETS = (
    "PARKIO_RESEND_API_KEY", "PARKIO_WAITLIST_RESEND_API_KEY",
    "PARKIO_ALERT_SLACK_WEBHOOK_URL", "PARKIO_ALERT_WEBHOOK_URL", "PARKIO_ALERT_WEBHOOK_SECRET",
    "PARKIO_SLACK_BIZ_WEBHOOK_URL", "PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ", "PARKIO_SLACK_BIZ_WEBHOOK_URL_OPS",
    "PARKIO_AI_VISION_GEMINI_API_KEY", "PARKIO_EXPO_ACCESS_TOKEN",
    "NEW_RELIC_LICENSE_KEY", "NEW_RELIC_API_KEY", "PARKIO_NR_LICENSE_KEY",
    "BACKUP_AZURE_SAS_TOKEN", "AZURE_STORAGE_SAS_TOKEN", "BACKUP_AZURE_STORAGE_KEY",
    "AZURE_STORAGE_KEY", "BACKUP_MC_DEST",
)
DUMMY_RE = re.compile(r"^(|dummy.*|drill-.*|disabled|none|placeholder.*)$", re.I)

# Flags that must be false/absent: anything that would send or poll.
TRUE_VALUES = {"1", "true", "yes", "on"}
MUST_BE_OFF_RE = re.compile(
    r"^(PARKIO_SLACK_BIZ_ENABLED|PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED"
    r"|PARKIO_MUNICIPAL(_[A-Z]+)*_(ENABLED|SCHEDULER_ENABLED)"
    r"|PARKIO_MUNICIPAL_MANUAL_SYNC_ENABLED|BACKUP_PRODUCTION_MODE)$")
EXPECTED_VALUES = {
    "PARKIO_EMAIL_PROVIDER": {"", "logging"},
    "PARKIO_WAITLIST_EMAIL_PROVIDER": {"", "logging"},
    "PARKIO_PUSH_DELIVERY_PROVIDER": {"", "noop"},
}
REQUIRED_VALUES = {"PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER": "1"}

# Credential-shaped values anywhere in the environment are refused by name.
SECRET_SHAPES = (
    re.compile(r"hooks\.slack\.com/(services|workflows)/", re.I),
    re.compile(r"\bre_[A-Za-z0-9_]{16,}\b"),          # Resend API key shape
    re.compile(r"\bNRAK-[A-Z0-9]{20,}\b"),             # New Relic user key shape
    re.compile(r"\b[0-9a-f]{36}NRAL\b", re.I),         # New Relic license key shape
    re.compile(r"[?&]sig=[A-Za-z0-9%+/=]{20,}"),       # Azure SAS signature
    re.compile(r"AIza[0-9A-Za-z_-]{30,}"),             # Google API key shape
)

# During the first drill only databases (and optionally MinIO) may run.
ALLOWED_CONTAINER_RE = re.compile(r"(^|[-_])(postgres|postgis|minio)([-_]|$)")

DEFAULT_EGRESS = ("hooks.slack.com:443", "api.resend.com:443", "log-api.eu.newrelic.com:443",
                  "generativelanguage.googleapis.com:443", "exp.host:443", "1.1.1.1:443")


def read_env_file(path):
    values = {}
    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key.strip().removeprefix("export ").strip()] = value.strip().strip("'\"")
    return values


def check_environment(env, checks):
    drill_id = env.get("PARKIO_DRILL_ID", "")
    ok = bool(re.fullmatch(r"[a-z0-9][a-z0-9-]{3,39}", drill_id))
    checks.append(("drill-id", "PASS" if ok else "FAIL",
                   "PARKIO_DRILL_ID set" if ok else "PARKIO_DRILL_ID missing or malformed"))

    live = sorted(n for n in OUTBOUND_SECRETS if not DUMMY_RE.match(env.get(n, "")))
    checks.append(("outbound-credentials", "FAIL" if live else "PASS",
                   f"non-dummy values: {live}" if live else "all absent or dummy"))

    shaped = sorted(n for n, v in env.items() if any(p.search(v or "") for p in SECRET_SHAPES))
    checks.append(("credential-shapes", "FAIL" if shaped else "PASS",
                   f"credential-shaped values in: {shaped}" if shaped else "none found"))

    enabled = sorted(n for n, v in env.items()
                     if MUST_BE_OFF_RE.match(n) and (v or "").strip().lower() in TRUE_VALUES)
    checks.append(("senders-and-pollers-off", "FAIL" if enabled else "PASS",
                   f"enabled: {enabled}" if enabled else "no sender/poller flag enabled"))

    wrong = sorted(n for n, allowed in EXPECTED_VALUES.items()
                   if env.get(n, "").strip().lower() not in allowed)
    checks.append(("inert-providers", "FAIL" if wrong else "PASS",
                   f"not inert: {wrong}" if wrong else "email=logging/unset, push=noop/unset"))

    missing = sorted(n for n, v in REQUIRED_VALUES.items() if env.get(n) != v)
    checks.append(("erasure-ledger-required", "FAIL" if missing else "PASS",
                   f"must be set: {missing}" if missing else "restore fail-closes without ledger"))


def check_host(hostname, checks):
    forbidden = hostname in FORBIDDEN_HOSTS or hostname.startswith(("parkio-civo-prod", "vm-parkio-"))
    checks.append(("host", "FAIL" if forbidden else "PASS",
                   "running on a production-class host" if forbidden else "not a known production host"))


def check_containers(names, checks):
    unexpected = sorted(n for n in names if n and not ALLOWED_CONTAINER_RE.search(n))
    checks.append(("containers", "FAIL" if unexpected else "PASS",
                   f"non-database containers running: {unexpected}" if unexpected
                   else f"{len([n for n in names if n])} database/object-store containers only"))


def tcp_reachable(target, timeout):
    host, _, port = target.rpartition(":")
    try:
        with socket.create_connection((host, int(port)), timeout=timeout):
            return True
    except (OSError, ValueError):
        return False


def check_egress(targets, timeout, checks):
    open_targets = [t for t in targets if tcp_reachable(t, timeout)]
    checks.append(("egress-blocked", "FAIL" if open_targets else "PASS",
                   f"reachable: {open_targets}" if open_targets
                   else f"{len(targets)} outbound targets unreachable"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--env-file")
    parser.add_argument("--containers-file")
    parser.add_argument("--probe-egress")
    parser.add_argument("--probe-default-egress", action="store_true")
    parser.add_argument("--timeout", type=float, default=3.0)
    parser.add_argument("--hostname", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    env = dict(os.environ)
    if args.env_file:
        try:
            env.update(read_env_file(args.env_file))
        except OSError:
            print("ERROR: cannot read --env-file", file=sys.stderr)
            return 2
    checks = []
    check_environment(env, checks)
    check_host(args.hostname or socket.gethostname(), checks)
    if args.containers_file:
        with open(args.containers_file) as handle:
            check_containers([line.strip() for line in handle], checks)
    else:
        checks.append(("containers", "WARN", "not checked (pass --containers-file)"))
    targets = []
    if args.probe_default_egress:
        targets.extend(DEFAULT_EGRESS)
    if args.probe_egress:
        targets.extend(t for t in args.probe_egress.split(",") if t)
    if targets:
        check_egress(targets, args.timeout, checks)
    else:
        checks.append(("egress-blocked", "WARN", "not probed"))

    failed = any(status == "FAIL" for _, status, _ in checks)
    json.dump({"tool": "restore-drill-isolation-preflight", "schemaVersion": 1,
               "verdict": "FAIL" if failed else "PASS",
               "checks": [{"id": i, "status": s, "detail": d} for i, s, d in checks]},
              sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
