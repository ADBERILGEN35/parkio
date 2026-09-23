#!/usr/bin/env python3
"""Offline integrity preflight for a retrieved Parkio backup stamp.

Reads only file names, sizes, checksums, the first eight bytes of encrypted
artifacts, the manifest and the shape of the erasure ledger. It never decrypts,
never prints ledger identifiers and never prints absolute paths, so the JSON
report is safe to keep as drill evidence.

Usage:
  restore-stamp-preflight.py STAMP_DIR [--max-age-hours N] [--allow-plaintext]
                             [--databases a,b,...] [--now EPOCH]

Exit: 0 = PASS (warnings allowed), 1 = FAIL, 2 = usage error.
"""
import argparse
import calendar
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
import time

DEFAULT_DATABASES = (
    "auth", "gateway", "user", "parking", "media",
    "gamification", "notification", "moderation", "analytics", "ai-validation",
)
OPENSSL_SALT_MAGIC = b"Salted__"
STAMP_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2})-(\d{2})-(\d{2})Z$")
LEDGER_KEYS = {"authUserId", "erasedAt"}
CONTROL_FILES = {"SHA256SUMS", "COMPLETE"}


class Report:
    def __init__(self):
        self.checks = []

    def add(self, check_id, status, detail=""):
        self.checks.append({"id": check_id, "status": status, "detail": detail})

    @property
    def failed(self):
        return any(c["status"] == "FAIL" for c in self.checks)


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def relative_files(stamp):
    found = set()
    for base, _dirs, files in os.walk(stamp):
        for name in files:
            rel = Path(base, name).relative_to(stamp).as_posix()
            found.add(rel)
    return found


def safe_relative(name):
    path = PurePosixPath(name[2:] if name.startswith("./") else name)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        return None
    return path.as_posix()


def check_integrity(stamp, report):
    sums = stamp / "SHA256SUMS"
    complete = stamp / "COMPLETE"
    if not complete.is_file():
        report.add("complete-marker", "FAIL", "COMPLETE missing: stamp is incomplete")
        return {}
    if not sums.is_file():
        report.add("sha256sums", "FAIL", "SHA256SUMS missing")
        return {}
    fields = dict(
        line.split("=", 1) for line in complete.read_text().splitlines() if "=" in line
    )
    if fields.get("sha256sums") != sha256_file(sums):
        report.add("complete-marker", "FAIL", "COMPLETE does not match SHA256SUMS")
    else:
        report.add("complete-marker", "PASS", "COMPLETE binds SHA256SUMS")

    listed = {}
    for number, line in enumerate(sums.read_text().splitlines(), 1):
        match = re.match(r"^([0-9a-f]{64})\s+\*?(.+)$", line.strip())
        rel = safe_relative(match.group(2)) if match else None
        if not match or rel is None:
            report.add("sha256sums", "FAIL", f"line {number} is malformed or unsafe")
            return {}
        listed[rel] = match.group(1)

    present = relative_files(stamp) - CONTROL_FILES
    missing = sorted(set(listed) - present)
    unlisted = sorted(present - set(listed))
    mismatched = sorted(rel for rel in set(listed) & present
                        if sha256_file(stamp / rel) != listed[rel])
    if missing or mismatched:
        report.add("sha256sums", "FAIL",
                   f"missing={missing} mismatched={mismatched}")
    else:
        report.add("sha256sums", "PASS", f"{len(listed)} files verified")
    if unlisted:
        report.add("unlisted-files", "FAIL", f"not covered by SHA256SUMS: {unlisted}")
    return listed


def check_encrypted(path):
    with open(path, "rb") as handle:
        return handle.read(len(OPENSSL_SALT_MAGIC)) == OPENSSL_SALT_MAGIC


def check_dumps(stamp, databases, allow_plaintext, report):
    sizes = {}
    for name in databases:
        enc = stamp / f"{name}.sql.gz.enc"
        plain = stamp / f"{name}.sql.gz"
        if enc.is_file():
            if enc.stat().st_size <= len(OPENSSL_SALT_MAGIC) or not check_encrypted(enc):
                report.add(f"dump:{name}", "FAIL", "not an openssl salted ciphertext")
                continue
            sizes[name] = enc.stat().st_size
            report.add(f"dump:{name}", "PASS", "encrypted dump present")
        elif plain.is_file() and allow_plaintext:
            sizes[name] = plain.stat().st_size
            report.add(f"dump:{name}", "WARN", "plaintext dump (dev mode only)")
        else:
            report.add(f"dump:{name}", "FAIL", "dump missing")
    plaintext = sorted(p.name for p in stamp.glob("*.sql*") if not p.name.endswith((".enc", ".sha256")))
    if plaintext and not allow_plaintext:
        report.add("no-plaintext", "FAIL", f"plaintext dumps present: {plaintext}")
    if (stamp / "minio").is_dir() and not allow_plaintext:
        report.add("no-plaintext", "FAIL", "plaintext minio/ tree present")
    if not any(c["id"] == "no-plaintext" for c in report.checks):
        report.add("no-plaintext", "PASS", "no plaintext dumps or object tree")
    return sizes


def check_minio(stamp, manifest, allow_plaintext, report):
    sealed = stamp / "minio.tar.gz.enc"
    if sealed.is_file():
        status = "PASS" if check_encrypted(sealed) else "FAIL"
        report.add("minio-artifact", status, "sealed object archive" if status == "PASS"
                   else "minio.tar.gz.enc is not openssl ciphertext")
        return
    if (stamp / "minio").is_dir() and allow_plaintext:
        report.add("minio-artifact", "WARN", "plaintext object tree (dev mode only)")
        return
    objects = (manifest.get("minio") or {}).get("objectCount")
    report.add("minio-artifact", "FAIL", f"object archive missing (manifest objectCount={objects})")


def check_manifest(stamp, databases, allow_plaintext, report):
    path = stamp / "backup-manifest.json"
    try:
        manifest = json.loads(path.read_text())
    except (OSError, ValueError):
        report.add("manifest", "FAIL", "backup-manifest.json missing or not JSON")
        return {}
    problems = []
    if int(manifest.get("schemaVersion", 0)) < 3:
        problems.append("schemaVersion<3")
    if manifest.get("databasesFailed") != 0:
        problems.append("databasesFailed!=0")
    if manifest.get("minioOk") != 1:
        problems.append("minioOk!=1")
    if set(manifest.get("databases") or []) != set(databases):
        problems.append("database list differs from expected inventory")
    if not allow_plaintext and not (manifest.get("encryption") or {}).get("enabled"):
        problems.append("encryption disabled")
    report.add("manifest", "FAIL" if problems else "PASS", "; ".join(problems) or "schema v3, no failures")
    if not (manifest.get("offsite") or {}).get("uploaded"):
        # Known defect: the stamp copy is written before the offsite upload
        # (docs/operations/gmp-release-pins.md). Presence offsite is the proof.
        report.add("manifest-offsite-flag", "WARN",
                   "stamp manifest says offsite.uploaded=false; known stale-copy defect")
    return manifest


def check_ledger(stamp, report):
    path = stamp / "erasure-tombstones.json"
    try:
        ledger = json.loads(path.read_text())
    except (OSError, ValueError):
        report.add("erasure-ledger", "FAIL",
                   "erasure-tombstones.json missing or not JSON; production restore fail-closes")
        return None
    if not isinstance(ledger, list) or any(
            not isinstance(e, dict) or not set(e) <= LEDGER_KEYS or "authUserId" not in e
            for e in ledger):
        report.add("erasure-ledger", "FAIL", "ledger is not an array of {authUserId, erasedAt}")
        return None
    report.add("erasure-ledger", "PASS", f"{len(ledger)} tombstones (identifiers not printed)")
    return len(ledger)


def stamp_epoch(value):
    match = STAMP_RE.match(value or "")
    if not match:
        return None
    return calendar.timegm(tuple(int(g) for g in match.groups()) + (0, 0, 0))


def run(stamp, databases, allow_plaintext=False, max_age_hours=None, now=None):
    report = Report()
    stamp = Path(stamp)
    if not stamp.is_dir():
        report.add("stamp-dir", "FAIL", "stamp directory not found")
        return report, {}
    check_integrity(stamp, report)
    manifest = check_manifest(stamp, databases, allow_plaintext, report)
    sizes = check_dumps(stamp, databases, allow_plaintext, report)
    check_minio(stamp, manifest, allow_plaintext, report)
    tombstones = check_ledger(stamp, report)

    taken = stamp_epoch(manifest.get("timestamp"))
    age_hours = None
    if taken is None:
        report.add("stamp-age", "FAIL", "manifest timestamp missing or malformed")
    else:
        age_hours = round(((now if now is not None else time.time()) - taken) / 3600, 2)
        if max_age_hours is not None and age_hours > max_age_hours:
            report.add("stamp-age", "FAIL", f"age {age_hours}h exceeds {max_age_hours}h")
        else:
            report.add("stamp-age", "PASS", f"age {age_hours}h")
    summary = {
        "stamp": manifest.get("timestamp"),
        "gitSha": manifest.get("gitSha"),
        "ageHours": age_hours,
        "dumpBytes": sizes,
        "dumpBytesTotal": sum(sizes.values()),
        "minioObjectCount": (manifest.get("minio") or {}).get("objectCount"),
        "erasureTombstones": tombstones,
    }
    return report, summary


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("stamp_dir")
    parser.add_argument("--databases", default=",".join(DEFAULT_DATABASES))
    parser.add_argument("--allow-plaintext", action="store_true")
    parser.add_argument("--max-age-hours", type=float)
    parser.add_argument("--now", type=float, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    databases = [d for d in args.databases.split(",") if d]
    report, summary = run(args.stamp_dir, databases, args.allow_plaintext,
                          args.max_age_hours, args.now)
    verdict = "FAIL" if report.failed else "PASS"
    json.dump({"tool": "restore-stamp-preflight", "schemaVersion": 1, "verdict": verdict,
               "summary": summary, "checks": report.checks}, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
