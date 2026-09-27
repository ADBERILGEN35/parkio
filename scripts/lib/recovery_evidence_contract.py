#!/usr/bin/env python3
"""Isolated recovery-evidence contract.

A claim file is never trusted evidence. The consumer recomputes the ledger
digest from durable store bytes, checks protocol, database identity,
producer HMAC, and a publication receipt. Production verifiedCoverage stays
false: this module does not authorize restore-hosted-beta.sh.

#104 SHARE-lock capture is reference only. This file is a disposable
protocol model plus consumer. It does not import or enable #104.
"""
from __future__ import annotations

import calendar
import hashlib
import hmac
import json
import os
import threading
import time
from pathlib import Path

PROTOCOL_LOCK = "table-share-lock"
REQUIRED_PARTICIPANTS = ("auth", "user", "parking", "media")
TS_RE = __import__("re").compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2})[:-](\d{2})[:-](\d{2})(?:\.\d+)?Z$"
)


class ContractError(Exception):
    """Invalid or untrusted evidence."""


class CaptureAborted(ContractError):
    """Capture transaction failed; nothing is publishable."""


class PersistFailed(ContractError):
    """Off-host persist failed; coverage and ACK must not advance."""


class AckRefused(ContractError):
    """Erasure is not durably published; caller must retry."""


class ExposeRefused(ContractError):
    """A required participant ACK is missing or mismatched."""


def parse_ts(value, label="timestamp"):
    match = TS_RE.match(value or "")
    if not match:
        raise ContractError(f"{label}: not an ISO-8601 UTC timestamp")
    return calendar.timegm(tuple(int(g) for g in match.groups()) + (0, 0, 0))


def iso(epoch):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def canonical_bytes(payload):
    return json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8")


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def sign(payload, key):
    return hmac.new(key, canonical_bytes(payload), hashlib.sha256).hexdigest()


def verify_hmac(payload, key, signature):
    expected = sign(payload, key)
    return hmac.compare_digest(expected, signature or "")


class IsolatedObjectStore:
    """Disposable directory store. Not off-host durability."""

    def __init__(self, root, fail_puts=0):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.fail_puts = fail_puts
        self.put_attempts = 0

    def _path(self, key):
        path = Path(key)
        if path.is_absolute() or ".." in path.parts:
            raise ContractError("unsafe store key")
        return self.root / path

    def put(self, key, data):
        self.put_attempts += 1
        if self.put_attempts <= self.fail_puts:
            raise PersistFailed(f"persist failed for {key}")
        dest = self._path(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        return {"publicationId": key, "etag": sha256_hex(data)}

    def get(self, key):
        dest = self._path(key)
        if not dest.is_file():
            raise ContractError(f"missing publication {key}")
        return dest.read_bytes()

    def exists(self, key):
        return self._path(key).is_file()


class IsolatedAuthTable:
    """In-process SHARE-lock model of READ COMMITTED tombstone capture.

    Writers wait while a snapshot holds SHARE. An aborted capture publishes
    nothing. This is isolated proof, not live Postgres.
    """

    def __init__(self, database_identity, clock=None):
        self.database_identity = database_identity
        self._rows = {}
        self._share = threading.Lock()
        self._clock = clock or (lambda: int(time.time()))
        self.fail_next_capture = False
        self.hold_share = None
        self.share_acquired = None

    def insert(self, auth_user_id, erased_at):
        with self._share:
            self._rows[auth_user_id.lower()] = erased_at
        return {"committed": True, "authUserId": auth_user_id.lower()}

    def committed_ids(self):
        return set(self._rows)

    def capture(self):
        with self._share:
            if self.fail_next_capture:
                self.fail_next_capture = False
                raise CaptureAborted("capture transaction rolled back")
            if self.share_acquired is not None:
                self.share_acquired.set()
            if self.hold_share is not None:
                self.hold_share.wait(2)
            entries = [
                {"authUserId": key, "erasedAt": self._rows[key]}
                for key in sorted(self._rows)
            ]
            covered = iso(self._clock())
            return {
                "captureProtocol": PROTOCOL_LOCK,
                "isolation": "read committed",
                "databaseIdentity": self.database_identity,
                "coveredThrough": covered,
                "entries": entries,
            }


class IsolatedMediaBucket:
    def __init__(self):
        self.objects = {}

    def put(self, key, owner):
        self.objects[key] = owner

    def erase_owner(self, owner):
        self.objects = {key: value for key, value in self.objects.items() if value != owner}


def build_store_object(snapshot, producer_id):
    entries = snapshot["entries"]
    ledger = canonical_bytes({"kind": "erasure-ledger", "entries": entries})
    body = {
        "schemaVersion": 1,
        "kind": "recovery-evidence",
        "ledgerDigest": sha256_hex(ledger),
        "coveredThrough": snapshot["coveredThrough"],
        "captureProtocol": snapshot["captureProtocol"],
        "databaseIdentity": snapshot["databaseIdentity"],
        "producerId": producer_id,
        "entries": entries,
    }
    return body, ledger


def publish_snapshot(store, snapshot, producer_id, producer_key):
    body, ledger = build_store_object(snapshot, producer_id)
    body["signature"] = sign(
        {key: body[key] for key in (
            "schemaVersion", "kind", "ledgerDigest", "coveredThrough",
            "captureProtocol", "databaseIdentity", "producerId",
        )},
        producer_key,
    )
    encoded = canonical_bytes(body)
    publication_id = f"watermarks/{body['ledgerDigest']}.json"
    receipt = store.put(publication_id, encoded)
    store.put(f"ledgers/{body['ledgerDigest']}.json", ledger)
    receipt["ledgerDigest"] = body["ledgerDigest"]
    receipt["coveredThrough"] = body["coveredThrough"]
    receipt["publicationId"] = publication_id
    return receipt, body


def write_claim_file(path, body, publication_id):
    """Local claim. Not trusted by verify_evidence without the store."""
    claim = {
        "schemaVersion": 1,
        "kind": "recovery-evidence-claim",
        "ledgerDigest": body["ledgerDigest"],
        "coveredThrough": body["coveredThrough"],
        "captureProtocol": body["captureProtocol"],
        "databaseIdentity": body["databaseIdentity"],
        "producerId": body["producerId"],
        "signature": body["signature"],
        "publicationId": publication_id,
    }
    Path(path).write_text(json.dumps(claim, indent=2), encoding="utf-8")
    return claim


def verify_evidence(store, expected_db, trusted_keys, cutoff, claim=None, publication_id=None):
    """Reject unless store bytes, HMAC, protocol, and identity independently match."""
    pub_id = publication_id or (claim or {}).get("publicationId")
    if not pub_id:
        raise ContractError("no publication identity; a claim file is not evidence")
    raw = store.get(pub_id)
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise ContractError("store object is not JSON") from exc
    if body.get("kind") != "recovery-evidence":
        raise ContractError("store object is not recovery-evidence")
    if body.get("captureProtocol") != PROTOCOL_LOCK:
        raise ContractError("capture protocol is not table-share-lock")
    if body.get("databaseIdentity") != expected_db:
        raise ContractError("database identity mismatch")
    producer = body.get("producerId")
    key = trusted_keys.get(producer)
    if not key:
        raise ContractError("unknown producer")
    signed = {key_name: body[key_name] for key_name in (
        "schemaVersion", "kind", "ledgerDigest", "coveredThrough",
        "captureProtocol", "databaseIdentity", "producerId",
    )}
    if not verify_hmac(signed, key, body.get("signature")):
        raise ContractError("producer signature mismatch")
    ledger = store.get(f"ledgers/{body['ledgerDigest']}.json")
    if sha256_hex(ledger) != body["ledgerDigest"]:
        raise ContractError("ledger digest mismatch")
    if sha256_hex(canonical_bytes({"kind": "erasure-ledger", "entries": body["entries"]})) != body["ledgerDigest"]:
        raise ContractError("store entries do not match ledger digest")
    if claim:
        for field in ("ledgerDigest", "coveredThrough", "captureProtocol",
                      "databaseIdentity", "producerId", "publicationId"):
            if claim.get(field) and claim[field] != (body[field] if field != "publicationId" else pub_id):
                raise ContractError(f"claim field {field} does not match store")
    covered_epoch = parse_ts(body["coveredThrough"], "coveredThrough")
    cutoff_epoch = parse_ts(cutoff, "recovery-cutoff")
    if cutoff_epoch > covered_epoch:
        raise ContractError(
            "cutoff exceeds durable watermark; do not lower the cutoff"
        )
    return {
        "verdict": "ACCEPT_ISOLATED",
        "verifiedCoverage": False,
        "certifiedProduction": False,
        "ledgerDigest": body["ledgerDigest"],
        "coveredThrough": body["coveredThrough"],
        "captureProtocol": body["captureProtocol"],
        "databaseIdentity": body["databaseIdentity"],
        "erasureSetDigest": body["ledgerDigest"],
        "ids": [item["authUserId"] for item in body["entries"]],
    }


class DurableAckGate:
    """ACK only after a persist-acked watermark includes the identifier."""

    def __init__(self):
        self._acks = {}

    def acknowledge(self, auth_user_id, verified):
        user_id = auth_user_id.lower()
        if user_id not in verified["ids"]:
            raise AckRefused("identifier is not in the durable watermark")
        self._acks[user_id] = {
            "status": "ACK",
            "erasureSetDigest": verified["erasureSetDigest"],
            "coveredThrough": verified["coveredThrough"],
        }
        return self._acks[user_id]

    def status(self, auth_user_id):
        return self._acks.get(auth_user_id.lower(), {"status": "ERASURE_PENDING_DURABLE"})


class ReplayCompletion:
    def __init__(self, erasure_set_digest, required=REQUIRED_PARTICIPANTS):
        self.erasure_set_digest = erasure_set_digest
        self.required = required
        self._acks = {}

    def ack(self, participant, erasure_set_digest):
        if erasure_set_digest != self.erasure_set_digest:
            raise ExposeRefused("participant ACK digest mismatch")
        self._acks[participant] = erasure_set_digest

    def expose(self):
        missing = [name for name in self.required if self._acks.get(name) != self.erasure_set_digest]
        if missing:
            raise ExposeRefused(f"incomplete participant ACKs: {','.join(missing)}")
        return {"expose": False, "replayComplete": True, "publicTraffic": "REFUSED_UNTIL_OPERATOR"}


def isolated_replay(auth_status, media, erased_ids, unrelated_id, digest):
    """Apply isolated erase. Does not start applications."""
    for user_id in erased_ids:
        auth_status[user_id] = "ERASED"
        media.erase_owner(user_id)
    completion = ReplayCompletion(digest)
    for participant in REQUIRED_PARTICIPANTS:
        completion.ack(participant, digest)
    completion.expose()
    if any(auth_status.get(user_id) == "ACTIVE" for user_id in erased_ids):
        raise ExposeRefused("erased account remained ACTIVE")
    if auth_status.get(unrelated_id) != "ACTIVE":
        raise ExposeRefused("unrelated account was not preserved")
    return {
        "erasedAbsent": True,
        "unrelatedPreserved": True,
        "publicTraffic": "not-enabled",
    }


def restore_erasure_ledger_still_unverified(root=None):
    """Guard: production helper must keep verifiedCoverage hardcoded false."""
    root = Path(root or Path(__file__).resolve().parents[2])
    text = (root / "scripts/lib/restore-erasure-ledger.py").read_text(encoding="utf-8")
    if '"verifiedCoverage": False' not in text and '"verifiedCoverage":False' not in text:
        raise ContractError("restore-erasure-ledger.py lost verifiedCoverage=false")
    if "verifiedCoverage\": True" in text or "verifiedCoverage = True" in text:
        raise ContractError("production verifiedCoverage was enabled")
    return True
