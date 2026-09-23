#!/usr/bin/env python3
"""Off-host erasure journal: complete snapshots, coverage seals, recover gate.

Authoritative live record remains auth.erased_user_tombstones
({auth_user_id, erased_at} only). This module persists a complete-table
snapshot off-host and a separate coverage seal. A persist timestamp is not
coverage. Incremental appends may be stored for inspection but never advance
coveredThrough.

Production stays disabled unless PARKIO_OFFHOST_ERASURE_ENABLED=1.
"""
from __future__ import annotations

import calendar
import hashlib
import json
import os
from pathlib import Path
import re
import time

UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.I
)
TS_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2})[:-](\d{2})[:-](\d{2})(?:\.\d+)?Z$"
)
ALLOWED_KEYS = frozenset({"authUserId", "erasedAt"})


class OffhostError(Exception):
    """Invalid evidence or configuration."""


class DisabledError(OffhostError):
    """Exporter/recover called while the feature is off."""


class RemoteWriteError(OffhostError):
    """Remote persist failed; coverage must not advance."""


class CoverageBlocked(OffhostError):
    """Cutoff is not covered by a verified seal."""


def parse_ts(value, label):
    match = TS_RE.match(value or "")
    if not match:
        raise OffhostError(f"{label}: not an ISO-8601 UTC timestamp")
    parts = tuple(int(g) for g in match.groups())
    return calendar.timegm(parts + (0, 0, 0))


def iso(epoch):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def enabled(env=None):
    env = os.environ if env is None else env
    return env.get("PARKIO_OFFHOST_ERASURE_ENABLED", "0") == "1"


def normalize_entries(raw):
    if not isinstance(raw, list):
        raise OffhostError("ledger is not a JSON array")
    entries = {}
    for item in raw:
        if (not isinstance(item, dict) or not set(item) <= ALLOWED_KEYS
                or "authUserId" not in item
                or not UUID_RE.match(str(item.get("authUserId", "")))):
            raise OffhostError("entry is not {authUserId: uuid, erasedAt}")
        extra = set(item) - ALLOWED_KEYS
        if extra:
            raise OffhostError("entry contains disallowed fields")
        user_id = item["authUserId"].lower()
        erased_at = item.get("erasedAt")
        if erased_at is not None:
            parse_ts(str(erased_at), "erasedAt")
        # Duplicates: keep the earliest erasedAt so order does not change the set.
        previous = entries.get(user_id)
        if previous is None:
            entries[user_id] = erased_at
        elif erased_at and (previous is None or str(erased_at) < str(previous)):
            entries[user_id] = erased_at
    return [{"authUserId": key, "erasedAt": entries[key]} for key in sorted(entries)]


def canonical_snapshot(entries):
    body = {"schemaVersion": 1, "kind": "erasure-snapshot", "entries": normalize_entries(entries)}
    return json.dumps(body, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def snapshot_sha256(entries):
    return sha256_bytes(canonical_snapshot(entries))


def build_seal(entries, covered_through, previous_seal_sha256=None, persisted_at=None):
    parse_ts(covered_through, "coveredThrough")
    blob = canonical_snapshot(entries)
    seal = {
        "schemaVersion": 1,
        "kind": "erasure-coverage",
        "source": "complete-table",
        "snapshotSha256": sha256_bytes(blob),
        "recordCount": len(normalize_entries(entries)),
        "coveredThrough": covered_through,
        "previousSealSha256": previous_seal_sha256,
        "persistedAt": persisted_at or covered_through,
    }
    encoded = json.dumps(seal, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return seal, sha256_bytes(encoded), encoded


class FileStore:
    """Directory-backed object store. Keys are relative POSIX paths."""

    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key):
        path = Path(key)
        if path.is_absolute() or ".." in path.parts:
            raise OffhostError("unsafe store key")
        return self.root / path

    def put(self, key, data):
        dest = self._path(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)

    def get(self, key):
        dest = self._path(key)
        if not dest.is_file():
            raise OffhostError(f"missing object {key}")
        return dest.read_bytes()

    def exists(self, key):
        return self._path(key).is_file()

    def list(self, prefix=""):
        root = self.root
        found = []
        for path in root.rglob("*"):
            if path.is_file():
                rel = path.relative_to(root).as_posix()
                if rel.startswith(prefix):
                    found.append(rel)
        return sorted(found)


class MemoryStore:
    def __init__(self):
        self.objects = {}

    def put(self, key, data):
        self.objects[key] = data

    def get(self, key):
        if key not in self.objects:
            raise OffhostError(f"missing object {key}")
        return self.objects[key]

    def exists(self, key):
        return key in self.objects

    def list(self, prefix=""):
        return sorted(k for k in self.objects if k.startswith(prefix))


class FailingStore:
    """Fails the next fail_times writes, then delegates. Used by tests."""

    def __init__(self, inner, fail_times=1):
        self.inner = inner
        self.fail_times = fail_times
        self.attempts = 0

    def put(self, key, data):
        if key == STATE_KEY:
            return self.inner.put(key, data)
        self.attempts += 1
        if self.attempts <= self.fail_times:
            raise RemoteWriteError(f"remote write failed for {key}")
        return self.inner.put(key, data)

    def get(self, key):
        return self.inner.get(key)

    def exists(self, key):
        return self.inner.exists(key)

    def list(self, prefix=""):
        return self.inner.list(prefix)


SNAPSHOT_PREFIX = "snapshots/"
SEAL_PREFIX = "seals/"
STATE_KEY = "state.json"


def load_state(store):
    if not store.exists(STATE_KEY):
        return {"lastSealSha256": None, "pending": None}
    try:
        return json.loads(store.get(STATE_KEY).decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise OffhostError(f"state.json corrupt: {type(exc).__name__}") from exc


def save_state(store, state):
    store.put(STATE_KEY, json.dumps(state, indent=2, sort_keys=True).encode("utf-8"))


def persist_complete_snapshot(store, entries, query_time, env=None):
    """Persist a complete-table snapshot and advance coverage only if both writes succeed.

    query_time is the time of the source table read, not the persist clock.
    Incremental records must not call this.
    """
    if not enabled(env):
        raise DisabledError("PARKIO_OFFHOST_ERASURE_ENABLED is not 1")
    parse_ts(query_time, "query_time")
    normalized = normalize_entries(entries)
    state = load_state(store)
    pending = {"entries": normalized, "queryTime": query_time}
    state["pending"] = pending
    try:
        save_state(store, state)
    except RemoteWriteError:
        raise

    blob = canonical_snapshot(normalized)
    digest = sha256_bytes(blob)
    snapshot_key = f"{SNAPSHOT_PREFIX}{digest}.json"
    try:
        if state.get("lastSealSha256"):
            previous = load_seal(store, state["lastSealSha256"])
            previous_snap = load_snapshot(store, previous["snapshotSha256"])
            missing = {e["authUserId"] for e in previous_snap} - {e["authUserId"] for e in normalized}
            if missing:
                raise OffhostError(
                    f"new snapshot lacks {len(missing)} identifiers from previous coverage"
                )
        store.put(snapshot_key, blob)
        seal, seal_sha, seal_blob = build_seal(
            normalized, query_time, state.get("lastSealSha256")
        )
        seal_key = f"{SEAL_PREFIX}{seal_sha}.json"
        store.put(seal_key, seal_blob)
    except RemoteWriteError:
        # Coverage stays at lastSealSha256. Pending remains for retry.
        raise

    state["lastSealSha256"] = seal_sha
    state["pending"] = None
    save_state(store, state)
    return {
        "snapshotSha256": digest,
        "sealSha256": seal_sha,
        "recordCount": len(normalized),
        "coveredThrough": query_time,
    }


def retry_pending(store, env=None):
    if not enabled(env):
        raise DisabledError("PARKIO_OFFHOST_ERASURE_ENABLED is not 1")
    state = load_state(store)
    pending = state.get("pending")
    if not pending:
        return {"retried": False}
    return persist_complete_snapshot(store, pending["entries"], pending["queryTime"], env)


def load_snapshot(store, digest):
    raw = store.get(f"{SNAPSHOT_PREFIX}{digest}.json")
    if sha256_bytes(raw) != digest:
        raise OffhostError("snapshot digest mismatch")
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise OffhostError("snapshot is not JSON") from exc
    if body.get("kind") != "erasure-snapshot":
        raise OffhostError("object is not an erasure-snapshot")
    return normalize_entries(body.get("entries"))


def load_seal(store, digest):
    raw = store.get(f"{SEAL_PREFIX}{digest}.json")
    if sha256_bytes(raw) != digest:
        raise OffhostError("seal digest mismatch")
    try:
        seal = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise OffhostError("seal is not JSON") from exc
    if seal.get("kind") != "erasure-coverage" or seal.get("source") != "complete-table":
        raise OffhostError("object is not a complete-table coverage seal")
    parse_ts(seal.get("coveredThrough"), "seal coveredThrough")
    return seal


def _verify_pair(store, digest):
    seal = load_seal(store, digest)
    snapshot = load_snapshot(store, seal["snapshotSha256"])
    if len(snapshot) != seal["recordCount"]:
        raise OffhostError("seal recordCount does not match snapshot")
    if snapshot_sha256(snapshot) != seal["snapshotSha256"]:
        raise OffhostError("seal snapshotSha256 does not match snapshot")
    return {
        "seal": seal,
        "sealSha256": digest,
        "entries": snapshot,
        "epoch": parse_ts(seal["coveredThrough"], "coverageThrough"),
    }


def latest_verified_coverage(store):
    """Return the newest verified (seal, snapshot) or None if none exist.

    Prefers state.json, then scans seals/. A lone persist timestamp is ignored.
    If seals exist but none verify, this is FAIL (corrupt), not empty coverage.
    """
    candidates = []
    state = load_state(store)
    if state.get("lastSealSha256"):
        candidates.append(state["lastSealSha256"])
    for key in store.list(SEAL_PREFIX):
        candidates.append(Path(key).stem)
    verified = []
    errors = []
    seen = set()
    for digest in candidates:
        if digest in seen:
            continue
        seen.add(digest)
        try:
            verified.append(_verify_pair(store, digest))
        except OffhostError as exc:
            errors.append(str(exc))
    if verified:
        return max(verified, key=lambda item: item["epoch"])
    if errors and store.list(SEAL_PREFIX):
        raise OffhostError("off-host seals present but none verified: " + errors[0])
    return None


def recover(store, cutoff, stamp_entries=None):
    """Build the recoverable erasure set through cutoff.

    Coverage is the seal's coveredThrough, never the current clock and never
    the persist time alone. Missing/corrupt off-host evidence is FAIL.
    Uncovered cutoff is BLOCKED (exit 3).
    """
    cutoff_epoch = parse_ts(cutoff, "recovery-cutoff")
    stamp = normalize_entries(stamp_entries or [])
    try:
        covered = latest_verified_coverage(store)
    except OffhostError as exc:
        return {
            "verdict": "FAIL",
            "reason": str(exc),
            "merged": stamp,
            "coverageThrough": None,
            "uncoveredSeconds": None,
        }
    if covered is None:
        coverage_epoch = None
        extra = []
        blocked = True
        reason = "no verified off-host coverage seal"
    else:
        coverage_epoch = parse_ts(covered["seal"]["coveredThrough"], "coverageThrough")
        extra = covered["entries"]
        blocked = coverage_epoch < cutoff_epoch
        reason = (
            "erasures between coverageThrough and recoveryCutoff are unknown"
            if blocked
            else None
        )

    merged = {}
    for item in stamp + extra:
        merged.setdefault(item["authUserId"], item.get("erasedAt"))
    report = {
        "verdict": "BLOCKED" if blocked else "PASS",
        "reason": reason,
        "coverageThrough": None if coverage_epoch is None else iso(coverage_epoch),
        "recoveryCutoff": iso(cutoff_epoch),
        "uncoveredSeconds": None if coverage_epoch is None else max(cutoff_epoch - coverage_epoch, 0),
        "stampTombstones": len(stamp),
        "offhostTombstones": len(extra),
        "mergedTombstones": len(merged),
        "erasedAfterStamp": len(set(merged) - {e["authUserId"] for e in stamp}),
        "merged": [{"authUserId": k, "erasedAt": v} for k, v in sorted(merged.items())],
    }
    return report


def write_supplement(path, entries):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(entries, handle)
        handle.write("\n")
