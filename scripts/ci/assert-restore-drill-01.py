#!/usr/bin/env python3
"""CI assertions for restore drill 01 on synthetic fixtures.

Usage: assert-restore-drill-01.py EVIDENCE_DIR EXPECTED_ENV CONTAINER

Checks the secret-free evidence written by scripts/restore-drill-01.sh and queries
the restored isolated container directly. Synthetic identifiers only.
"""
import json
from pathlib import Path
import subprocess
import sys

SERVICES = ("auth", "gateway", "user", "parking", "media",
            "gamification", "notification", "moderation", "analytics", "ai-validation")
failures = []


def check(condition, message):
    print(("PASS " if condition else "FAIL ") + message)
    if not condition:
        failures.append(message)


def sql(container, db, query):
    out = subprocess.run(["docker", "exec", "-i", container, "psql", "-X", "-At", "-U", "rd_admin",
                          "-d", db, "-c", query], capture_output=True, text=True, check=True)
    return out.stdout.strip()


def main(evidence, expected_env, container):
    ev = Path(evidence)
    exp = dict(line.split("=", 1) for line in Path(expected_env).read_text().splitlines() if "=" in line)

    summary = json.loads((ev / "summary.json").read_text())
    check(summary["verdict"] == "PASS", f"summary verdict PASS (got {summary['verdict']})")
    for svc in SERVICES:
        parity = json.loads((ev / f"{svc}.parity.json").read_text())
        check(parity["verdict"] == "PASS" and parity["tables"] > 0,
              f"{svc}: row-count parity PASS over {parity['tables']} tables / {parity['rowsInDump']} rows")
        profile = json.loads((ev / f"{svc}.profile.json").read_text())
        head = exp.get("FLYWAY_HEAD_" + svc.replace("-", "_"))
        check(profile["flywayHead"] == head and profile["flywayFailedRows"] == 0,
              f"{svc}: Flyway head {profile['flywayHead']} == {head}")
    parking = json.loads((ev / "parking.profile.json").read_text())
    check("postgis" in parking["extensions"], "parking dump declares postgis")
    check(sql(container, "parkio_parking", "select count(*) from pg_extension where extname='postgis'") == "1",
          "restored parking has the postgis extension")
    check(sql(container, "parkio_parking",
              "select count(*) from parking_spots where ST_DWithin(location::geography, "
              "ST_SetSRID(ST_MakePoint(27.1287,38.4192),4326)::geography, 50)") == "1",
          "restored PostGIS spatial query finds the synthetic spot")

    for name in ("stamp-preflight.json", "ledger-stamp-1-preflight.json"):
        check(json.loads((ev / name).read_text())["verdict"] == "PASS", f"{name} PASS")
    erasure = json.loads((ev / "erasure-set.json").read_text())
    check(erasure["verdict"] == "PASS", "erasure set reaches the recovery cutoff")
    check(erasure["erasedAfterDataStamp"] == 1, "exactly one erasure is newer than the data stamp")
    replay = dict(l.split("=", 1) for l in (ev / "erasure-replay.txt").read_text().splitlines())
    check(int(replay["active_in_erasure_set_before_replay"]) == 1,
          "before replay the post-backup-erased account is ACTIVE in the restored copy")
    check(replay["active_in_erasure_set_after_replay"] == "0", "after replay no erased account is ACTIVE")

    def status(uid):
        return sql(container, "parkio_auth", f"select status from auth_users where id='{uid}'")
    check(status(exp["ERASE_AFTER_ID"]) != "ACTIVE", "account erased AFTER the backup is not ACTIVE")
    check(status(exp["ERASE_BEFORE_ID"]) != "ACTIVE", "account erased BEFORE the backup is not ACTIVE")
    check(status(exp["KEEP_ID"]) == "ACTIVE", "unrelated account stays ACTIVE (no over-erasure)")

    rows = {tuple(l.split("|")[:3]): int(l.split("|")[3])
            for l in (ev / "outbox-pending.txt").read_text().splitlines() if l}
    for svc in ("auth", "user", "parking"):
        want = int(exp[f"EXPECT_OUTBOX_UNPUBLISHED_{svc.upper()}"])
        got = rows.get((svc, "outbox_events", "unpublished"))
        check(got == want, f"{svc}: unpublished outbox rows as of stamp S = {want} (got {got})")
    for state in ("PENDING", "EXPORTED"):
        want = int(exp[f"EXPECT_WAITLIST_{state}"])
        got = rows.get(("gateway", "waitlist_ops_notification_outbox", state))
        check(got == want, f"gateway waitlist outbox {state} = {want} (got {got})")

    running = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True,
                             text=True, check=True).stdout.split()
    check(running == [container], f"only the isolated database container is running ({running})")

    leaks = [exp[k] for k in ("KEEP_ID", "ERASE_BEFORE_ID", "ERASE_AFTER_ID")] + ["@parkio.test"]
    files = [p for p in ev.rglob("*") if p.is_file()]
    leaked = sorted({p.name for p in files for t in leaks if t in p.read_text(errors="replace")})
    check(not leaked, f"no identifier or email in {len(files)} evidence files (leaked: {leaked})")
    return 1 if failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
