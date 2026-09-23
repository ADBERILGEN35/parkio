#!/usr/bin/env python3
"""Opt-in, encrypted snapshots of Slack and NR operational state.

No service control, network I/O, production restore, or publisher activation.
The gateway PostgreSQL outbox remains a separate backup domain.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import sqlite3
import stat
import subprocess
import sys
import tarfile
import tempfile
from typing import Iterator

_SCRIPTS = Path(__file__).resolve().parents[1]
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
from recovery_coordination.slack_reconciliation import (  # noqa: E402
    ReconciliationError,
    reconcile_slack_events,
)


FORMAT = 1
SLACK_TABLES = {"delivery_queue", "dedup", "dlt", "incident_threads", "metrics", "worker_lock", "schema_meta"}
NR_COLUMNS = {"max_bytes", "spent_bytes", "attempts", "retry_attempts", "forwarded_attempts",
              "rejected_attempts", "records_attempted", "records_forwarded", "records_rejected",
              "serialized_bytes_attempted", "wire_bytes_attempted", "exhausted", "last_digest",
              "daily_limit", "daily_window", "daily_spent", "daily_exhausted",
              "monthly_limit", "monthly_window", "monthly_spent", "monthly_exhausted"}
DB_NAMES = {"slack/slack_biz.sqlite3", "nr/budget.db"}
SECRET_NAMES = {".env", "env", "secrets", "credentials"}
SQLITE_HEADER = b"SQLite format 3\x00"
SQLITE_SIDECARS = ("-wal", "-shm", "-journal")


class SnapshotError(RuntimeError):
    pass


def utc() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def private_file(path: Path, data: bytes) -> None:
    with path.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(path, 0o600)


def source_regular(path: Path) -> None:
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError as exc:
        raise SnapshotError(f"required state missing: {path}") from exc
    if not stat.S_ISREG(mode):
        raise SnapshotError(f"expected regular file, no symlinks: {path}")


def db_info(db: sqlite3.Connection, kind: str) -> dict:
    result = db.execute("PRAGMA integrity_check").fetchone()
    if result != ("ok",):
        raise SnapshotError(f"{kind}: SQLite integrity_check failed")
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if kind == "slack":
        if not SLACK_TABLES <= tables:
            raise SnapshotError("slack: unsupported or incomplete schema")
        versions = db.execute("SELECT value FROM schema_meta WHERE key='version'").fetchall()
        if versions != [("2",)]:
            raise SnapshotError("slack: schema_meta.version must be 2")
        version = "2"
    elif kind == "nr-budget":
        if "budget" not in tables or not NR_COLUMNS <= {r[1] for r in db.execute("PRAGMA table_info(budget)")}:
            raise SnapshotError("nr-budget: unsupported or incomplete schema")
        accounting = sorted(NR_COLUMNS - {"daily_window", "monthly_window", "last_digest"})
        rows = db.execute(f"SELECT id, {','.join(accounting)} FROM budget").fetchall()
        if len(rows) != 1 or rows[0][0] != 1 or any(value is None or value < 0 for value in rows[0][1:]):
            raise SnapshotError("nr-budget: expected one nonnegative accounting row")
        version = "window-ledger-v1"
    else:
        version = "sqlite-unversioned"
    schema = "\n".join(r[0] for r in db.execute(
        "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type,name"))
    return {"schema_version": version, "user_version": db.execute("PRAGMA user_version").fetchone()[0],
            "schema_sha256": hashlib.sha256(schema.encode()).hexdigest(), "integrity": "ok"}


def assert_sqlite_header(path: Path, kind: str) -> None:
    source_regular(path)
    with path.open("rb") as stream:
        header = stream.read(16)
    if header != SQLITE_HEADER:
        raise SnapshotError(f"{kind}: file is not a SQLite database")


def is_sqlite_sidecar(name: str) -> bool:
    return name.endswith(SQLITE_SIDECARS)


def unlink_sqlite_sidecars(path: Path) -> None:
    for suffix in SQLITE_SIDECARS:
        sidecar = Path(str(path) + suffix)
        try:
            sidecar.unlink()
        except FileNotFoundError:
            continue


def staged_regular_files(stage: Path) -> list[Path]:
    # Sidecars next to a staged Online Backup are not a separate domain and
    # must not enter the archive; committed WAL is already in the main file.
    return [path for path in sorted(stage.rglob("*"))
            if path.is_file() and not is_sqlite_sidecar(path.name)]


def sqlite_backup(source: Path, destination: Path, kind: str) -> dict:
    # Reject a destroyed main file even when a leftover WAL sidecar remains.
    # WAL replay is for a live committed database, not an overwritten header.
    assert_sqlite_header(source, kind)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # mode=ro observes the committed WAL. immutable=1 would incorrectly ignore it.
    uri = source.resolve().as_uri() + "?mode=ro"
    try:
        with sqlite3.connect(uri, uri=True, timeout=15) as src:
            src.execute("PRAGMA busy_timeout=15000")
            with sqlite3.connect(destination) as dst:
                src.backup(dst, pages=128, sleep=0.05)
                info = db_info(dst, kind)
    except sqlite3.Error as exc:
        raise SnapshotError(f"{kind}: SQLite backup failed: {exc.__class__.__name__}") from exc
    os.chmod(destination, 0o600)
    unlink_sqlite_sidecars(destination)
    return info


def copy_stable(source: Path, destination: Path) -> None:
    source_regular(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(source, flags)
    try:
        before = os.fstat(fd)
        with destination.open("xb") as target, os.fdopen(fd, "rb", closefd=False) as stream:
            shutil.copyfileobj(stream, target)
            target.flush()
            os.fsync(target.fileno())
        after = os.fstat(fd)
        if (before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_ino, after.st_size, after.st_mtime_ns):
            raise SnapshotError(f"file changed during capture: {source}")
    finally:
        os.close(fd)
    os.chmod(destination, 0o600)
    if destination.stat().st_size != before.st_size:
        raise SnapshotError(f"file changed during capture: {source}")


def safe_relative(value: str) -> None:
    path = PurePosixPath(value)
    if path.is_absolute() or not value or ".." in path.parts or "\\" in value or value.startswith("./"):
        raise SnapshotError("unsafe archive path")
    if any(part.lower() in SECRET_NAMES or part.lower().endswith((".env", ".pem", ".key"))
           for part in path.parts):
        raise SnapshotError("secret-named file excluded from operational snapshot")


def copy_tree(source: Path, stage: Path, prefix: str, schemas: dict, *, json_only: bool = False) -> None:
    if not source.is_dir() or source.is_symlink():
        raise SnapshotError(f"required state directory missing or unsafe: {source}")
    for root, dirs, files in os.walk(source, followlinks=False):
        for name in dirs:
            if (Path(root) / name).is_symlink():
                raise SnapshotError("symlink in state directory")
        for name in sorted(files):
            path = Path(root) / name
            relative = path.relative_to(source).as_posix()
            archive_name = f"{prefix}/{relative}"
            safe_relative(archive_name)
            if json_only and not (name.endswith(".json") or ".json.bad-" in name):
                raise SnapshotError(f"unexpected inbox file; quiesce producer first: {relative}")
            if is_sqlite_sidecar(name):
                # SQLite's online backup incorporates committed WAL records.
                continue
            target = stage / archive_name
            if name.endswith((".db", ".sqlite3")):
                schemas[archive_name] = sqlite_backup(path, target, "collector")
            else:
                copy_stable(path, target)
                if name.endswith(".json") and ".invalid" not in Path(relative).parts:
                    try:
                        json.loads(target.read_text(encoding="utf-8"))
                    except (UnicodeError, json.JSONDecodeError) as exc:
                        raise SnapshotError(f"invalid JSON state: {relative}") from exc


def inbox_inventory(source: Path) -> dict[str, tuple[int, int, int, int]]:
    """Detect ordinary inbox membership/content changes across the capture window.

    This is a drift check, not a substitute for quiescing every writer.
    """
    if not source.is_dir() or source.is_symlink():
        raise SnapshotError("required inbox directory missing or unsafe")
    root_metadata = source.lstat()
    result = {".": (root_metadata.st_ino, root_metadata.st_size,
                    root_metadata.st_mtime_ns, root_metadata.st_ctime_ns)}
    for root, dirs, files in os.walk(source, followlinks=False):
        for name in dirs + files:
            path = Path(root) / name
            metadata = path.lstat()
            if stat.S_ISLNK(metadata.st_mode):
                raise SnapshotError("symlink in inbox directory")
            result[path.relative_to(source).as_posix()] = (
                metadata.st_ino, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns)
    return result


def openssl(args: list[str], *, input_file=None, output_file=None) -> None:
    if not os.environ.get("BACKUP_ENCRYPT_PASSPHRASE"):
        raise SnapshotError("BACKUP_ENCRYPT_PASSPHRASE is required; no plaintext snapshot")
    result = subprocess.run(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-salt",
                             "-pass", "env:BACKUP_ENCRYPT_PASSPHRASE", *args],
                            stdin=input_file, stdout=output_file, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise SnapshotError("OpenSSL encryption/decryption failed")


def encrypt_stage(stage: Path, target: Path) -> None:
    if not os.environ.get("BACKUP_ENCRYPT_PASSPHRASE"):
        raise SnapshotError("BACKUP_ENCRYPT_PASSPHRASE is required; no plaintext snapshot")
    with target.open("xb") as encrypted:
        with subprocess.Popen(["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-salt",
                               "-pass", "env:BACKUP_ENCRYPT_PASSPHRASE"],
                              stdin=subprocess.PIPE, stdout=encrypted, stderr=subprocess.PIPE) as proc:
            assert proc.stdin is not None and proc.stderr is not None
            try:
                with tarfile.open(fileobj=proc.stdin, mode="w|gz") as archive:
                    for path in staged_regular_files(stage):
                        archive.add(path, arcname=path.relative_to(stage).as_posix(), recursive=False)
            finally:
                proc.stdin.close()
            proc.stderr.read()
            proc.wait()
            if proc.returncode:
                raise SnapshotError("OpenSSL encryption failed")
        encrypted.flush()
        os.fsync(encrypted.fileno())
    os.chmod(target, 0o600)


@contextmanager
def opened_snapshot(snapshot: Path, staging_root: Path | None = None) -> Iterator[tuple[Path, dict]]:
    archive = snapshot / "snapshot.tar.gz.enc"
    checksum = snapshot / "SHA256SUMS"
    marker = snapshot / "COMPLETE"
    for required in (archive, checksum, marker):
        source_regular(required)
    expected = checksum.read_text(encoding="ascii").strip().split()
    if len(expected) != 2 or expected[1] != archive.name or digest(archive) != expected[0]:
        raise SnapshotError("encrypted archive checksum mismatch")
    if marker.read_text(encoding="ascii").strip() != expected[0]:
        raise SnapshotError("incomplete snapshot marker")
    with tempfile.TemporaryDirectory(prefix="parkio-opstate-verify-", dir=staging_root) as temporary:
        root = Path(temporary)
        os.chmod(root, 0o700)
        plain = root / "archive.tar.gz"
        with plain.open("xb") as output:
            openssl(["-d", "-in", str(archive)], output_file=output)
        os.chmod(plain, 0o600)
        extracted = root / "state"
        extracted.mkdir(mode=0o700)
        try:
            with tarfile.open(plain, "r:gz") as bundle:
                for member in bundle:
                    safe_relative(member.name)
                    if not member.isfile():
                        raise SnapshotError("archive contains a non-regular entry")
                    destination = extracted / member.name
                    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    source = bundle.extractfile(member)
                    assert source is not None
                    with destination.open("xb") as output:
                        shutil.copyfileobj(source, output)
                    os.chmod(destination, 0o600)
        except (tarfile.TarError, OSError) as exc:
            raise SnapshotError("invalid encrypted archive") from exc
        plain.unlink()
        manifest = json.loads((extracted / "manifest.json").read_text(encoding="utf-8"))
        if manifest.get("format_version") != FORMAT:
            raise SnapshotError("unsupported snapshot format")
        files = manifest.get("files")
        if not isinstance(files, dict) or set(files) != {
            p.relative_to(extracted).as_posix() for p in extracted.rglob("*") if p.is_file()
        } - {"manifest.json"}:
            raise SnapshotError("archive file list mismatch")
        for name, metadata in files.items():
            path = extracted / name
            if path.stat().st_size != metadata["bytes"] or digest(path) != metadata["sha256"]:
                raise SnapshotError("snapshot content checksum mismatch")
        schemas = manifest["sqlite_schemas"]
        if not DB_NAMES <= schemas.keys() or not schemas.keys() <= files.keys():
            raise SnapshotError("required SQLite snapshots missing")
        for name, prior in schemas.items():
            with sqlite3.connect(f"file:{(extracted / name).resolve()}?mode=ro", uri=True) as db:
                current = db_info(db, "slack" if name.startswith("slack/") else
                                  "nr-budget" if name == "nr/budget.db" else "collector")
            if current != prior:
                raise SnapshotError("SQLite schema/integrity metadata mismatch")
        yield extracted, manifest


def snapshot(args: argparse.Namespace) -> dict:
    sources = [args.slack_db, args.slack_inbox, args.nr_budget_db, args.nr_source_state,
               args.nr_source_spool, args.nr_collector_state]
    destination = args.destination
    if destination.exists() or destination.is_symlink():
        raise SnapshotError("destination already exists")
    if any(destination.resolve().is_relative_to(source.resolve()) for source in sources):
        raise SnapshotError("destination must be outside every source")
    destination.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".parkio-opstate-incomplete-", dir=destination.parent))
    os.chmod(work, 0o700)
    try:
        with tempfile.TemporaryDirectory(prefix="parkio-opstate-stage-", dir=args.staging_root) as temporary:
            stage = Path(temporary)
            os.chmod(stage, 0o700)
            schemas: dict = {}
            started = utc()
            inbox_before = inbox_inventory(args.slack_inbox)
            schemas["slack/slack_biz.sqlite3"] = sqlite_backup(args.slack_db, stage / "slack/slack_biz.sqlite3", "slack")
            schemas["nr/budget.db"] = sqlite_backup(args.nr_budget_db, stage / "nr/budget.db", "nr-budget")
            copy_tree(args.slack_inbox, stage, "slack/inbox", schemas, json_only=True)
            copy_tree(args.nr_source_state, stage, "nr/source-state", schemas)
            copy_tree(args.nr_source_spool, stage, "nr/source-spool", schemas)
            copy_tree(args.nr_collector_state, stage, "nr/collector-state", schemas)
            if inbox_inventory(args.slack_inbox) != inbox_before:
                raise SnapshotError("inbox changed during capture")
            files = {p.relative_to(stage).as_posix(): {"bytes": p.stat().st_size, "sha256": digest(p)}
                     for p in staged_regular_files(stage)}
            manifest = {"format_version": FORMAT, "started_at": started, "finished_at": utc(),
                        "gateway_outbox_backup_id": args.gateway_outbox_backup_id,
                        "consistency": "independent-domains; publishers must remain disabled during recovery",
                        "encryption": "aes-256-cbc-pbkdf2", "sqlite_schemas": schemas, "files": files}
            private_file(stage / "manifest.json", json.dumps(manifest, sort_keys=True).encode())
            encrypt_stage(stage, work / "snapshot.tar.gz.enc")
            if inbox_inventory(args.slack_inbox) != inbox_before:
                raise SnapshotError("inbox changed during sealing")
        archive_hash = digest(work / "snapshot.tar.gz.enc")
        private_file(work / "SHA256SUMS", f"{archive_hash}  snapshot.tar.gz.enc\n".encode())
        # Check the complete encrypted artifact before publishing the marker.
        # Verification cannot use opened_snapshot until COMPLETE exists.
        private_file(work / "COMPLETE", f"{archive_hash}\n".encode())
        with opened_snapshot(work, args.staging_root):
            pass
        work.rename(destination)
        return {"status": "complete", "archive_sha256": archive_hash, "file_count": len(files),
                "gateway_outbox_backup_id": args.gateway_outbox_backup_id}
    except Exception:
        shutil.rmtree(work, ignore_errors=True)
        raise


def envelope_ids(root: Path) -> tuple[set[str], set[str]]:
    pending: set[str] = set()
    acked: set[str] = set()
    for path in (root / "slack/inbox").rglob("*.json"):
        if ".invalid" in path.parts:
            continue
        raw = json.loads(path.read_text(encoding="utf-8"))
        event = raw.get("eventId") or raw.get("event_id")
        if not isinstance(event, str) or not event:
            raise SnapshotError("inbox envelope lacks eventId")
        (acked if ".acked" in path.parts else pending).add(event)
    return pending, acked


def prepare_recovery(args: argparse.Namespace) -> dict:
    if args.destination.exists() or args.destination.is_symlink():
        raise SnapshotError("offline recovery destination already exists")
    with opened_snapshot(args.snapshot, args.staging_root) as (source, manifest):
        gateway = json.loads(args.gateway_outbox_export.read_text(encoding="utf-8"))
        if gateway.get("backup_id") != manifest["gateway_outbox_backup_id"]:
            raise SnapshotError("gateway outbox backup identity mismatch")
        events = gateway.get("events")
        if not isinstance(events, list) or any(not isinstance(e, dict) or
                                               not isinstance(e.get("eventId"), str) for e in events):
            raise SnapshotError("gateway export must contain eventId objects")
        gateway_ids = [e["eventId"] for e in events]
        if len(gateway_ids) != len(set(gateway_ids)):
            raise SnapshotError("duplicate gateway event IDs")
        pending, acked = envelope_ids(source)
        with sqlite3.connect(source / "slack/slack_biz.sqlite3") as db:
            queue = db.execute("SELECT event_id, dedup_key, status FROM delivery_queue").fetchall()
            dedup_rows = db.execute("SELECT event_id, dedup_key FROM dedup").fetchall()
        try:
            slack = reconcile_slack_events(gateway_ids, queue, pending, acked, dedup_rows)
        except ReconciliationError as exc:
            raise SnapshotError(str(exc)) from exc
        counts = slack["counts"]
        args.destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.copytree(source, args.destination)
            (args.destination / "manifest.json").unlink()
            for path in args.destination.rglob("*"):
                os.chmod(path, 0o700 if path.is_dir() else 0o600)
            os.chmod(args.destination, 0o700)
            # Budget._roll_windows resets daily/monthly counters on a new UTC period.
            # The total exhausted bit survives rollover even when max_bytes=0.
            with sqlite3.connect(args.destination / "nr/budget.db") as db:
                db.execute("UPDATE budget SET exhausted=1 WHERE id=1")
                if db.total_changes != 1:
                    raise SnapshotError("could not quarantine NR budget ledger")
                db.commit()
            report = {"status": "manual_reconciliation_required", "resume_slack": False,
                      "resume_new_relic": False, "nr_budget": "offline staged ledger forced exhausted=1",
                      "reconciliation_complete": False,
                      "source_archive_sha256": digest(args.snapshot / "snapshot.tar.gz.enc"),
                      "staged_nr_budget_sha256": digest(args.destination / "nr/budget.db"),
                      "gateway_backup_id_matches": True, "snapshot_started_at": manifest["started_at"],
                      "snapshot_finished_at": manifest["finished_at"], "counts": counts,
                      "event_ids": slack["event_ids"],
                      "equal_count_different_sets": slack["equal_count_different_sets"],
                      "identity_and_counts_sufficient": False,
                      "auto_replay": [],
                      "replay_refused": slack["replay_refused"],
                      "replay_policy": slack["replay_policy"],
                      "limits": slack["limits"],
                      "windows": slack["limits"]["duplicate_windows"] + slack["limits"]["loss_windows"]
                                 + ["NR usage after snapshot is unknown across UTC day/month rollover"]}
            private_file(args.destination / "RECOVERY-PLAN.json", json.dumps(report, indent=2).encode())
            return report
        except Exception:
            shutil.rmtree(args.destination, ignore_errors=True)
            raise


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--staging-root", type=Path, help="restricted temporary filesystem; default system temp")
    sub = p.add_subparsers(dest="command", required=True)
    snap = sub.add_parser("snapshot")
    for name in ("slack-db", "slack-inbox", "nr-budget-db", "nr-source-state", "nr-source-spool",
                 "nr-collector-state", "destination"):
        snap.add_argument("--" + name, type=Path, required=True)
    snap.add_argument("--gateway-outbox-backup-id", required=True,
                      help="identity of separately captured gateway Postgres backup")
    verify = sub.add_parser("verify")
    verify.add_argument("--snapshot", type=Path, required=True)
    recover = sub.add_parser("prepare-recovery")
    recover.add_argument("--snapshot", type=Path, required=True)
    recover.add_argument("--gateway-outbox-export", type=Path, required=True)
    recover.add_argument("--destination", type=Path, required=True)
    return p


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "snapshot":
            result = snapshot(args)
        elif args.command == "verify":
            with opened_snapshot(args.snapshot, args.staging_root) as (_, manifest):
                result = {"status": "verified", "format_version": manifest["format_version"],
                          "file_count": len(manifest["files"]),
                          "gateway_outbox_backup_id": manifest["gateway_outbox_backup_id"]}
        else:
            result = prepare_recovery(args)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (SnapshotError, OSError, ValueError, KeyError, sqlite3.Error) as exc:
        # Never print paths or payloads: they may contain operational identifiers.
        print(f"operational snapshot failed: {exc.__class__.__name__}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
