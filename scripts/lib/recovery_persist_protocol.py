#!/usr/bin/env python3
"""Isolated persist-before-durable-ACK protocol.

Depends on the #118 recovery-evidence contract. This is the proposed
enabled protocol. Production auth still returns IN_PROGRESS after commit
and completes after participant acks only; that path stays default.

A local directory or container volume can survive application crash. It
is not off-host WORM durability.
"""
from __future__ import annotations

import hashlib
import json
import threading
from datetime import datetime, timezone
from pathlib import Path

from recovery_evidence_contract import (
    REQUIRED_PARTICIPANTS,
    AckRefused,
    ContractError,
    ExposeRefused,
    PersistFailed,
    canonical_bytes,
    sha256_hex,
    sign,
    verify_hmac,
)

SCHEMA_VERSION = 2
TRUST_FORMAT = "parkio-erasure-evidence-trust"
TRUST_VERSION = 1
MIN_KEY_BYTES = 32
KIND_PENDING = "erasure-pending-record"
KIND_CHECKPOINT = "erasure-checkpoint"
KIND_FRONTIER = "erasure-expected-frontier"
PUBLIC_IN_PROGRESS = "IN_PROGRESS"
PUBLIC_COMPLETE = "COMPLETE"
INTERNAL_PENDING = "PENDING_DURABLE"
INTERNAL_RECORDED = "DURABLY_RECORDED"
FRONTIER_KEY = "frontier/expected-through.json"
SIGNED_PENDING = (
    "schemaVersion", "kind", "erasureRecordId", "erasureRequestId",
    "authUserId", "sequence", "databaseIdentity", "producerId", "keyId", "bodyDigest",
)
SIGNED_CHECKPOINT = (
    "schemaVersion", "kind", "sequence", "databaseIdentity", "producerId", "keyId",
    "ledgerDigest", "captureProtocol",
)
SIGNED_FRONTIER = (
    "schemaVersion", "kind", "expectedThrough", "highestReserved",
    "databaseIdentity", "producerId", "keyId", "frontierDigest",
)
# Sequence markers that reserve a checkpoint's sequence carry this erasureRequestId (the format
# has one marker shape; the nil UUID is never a request id). The auth-service producer fills a
# checkpoint reservation left without its checkpoint with its next checkpoint.
CHECKPOINT_RESERVATION_ID = "00000000-0000-0000-0000-000000000000"
# The only auth-service main sources that may mention the checkpoint producer or publish a
# checkpoint: the producer, its default-off wiring, the store port and the store adapter.
CHECKPOINT_PRODUCER_SOURCES = {
    "ErasureCheckpointProducer": (
        "application/ErasureCheckpointProducer.java",
        "infrastructure/durable/ErasureCheckpointConfig.java",
    ),
    "publishCheckpoint(": (
        "application/ErasureCheckpointProducer.java",
        "application/port/DurableErasureCheckpointStore.java",
        "infrastructure/durable/ObjectLockDurableErasureRecordStore.java",
    ),
}


def erasure_record_id(erasure_request_id):
    """Stable identity: one request, one record key. Retry-safe."""
    return f"records/{erasure_request_id.lower()}.json"


def sequence_key(sequence):
    return f"sequences/{int(sequence):016d}.json"


def checkpoint_key(sequence):
    return f"checkpoints/{int(sequence):016d}.json"


def signed_subset(body, fields):
    return {key: body[key] for key in fields}


def parse_instant(value, label):
    """ISO-8601 UTC instant ('Z' suffix, optional fraction) as an aware datetime."""
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ContractError(f"{label} must be an ISO-8601 UTC instant")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ContractError(f"{label} must be an ISO-8601 UTC instant") from exc
    return parsed


def utc_now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


class TrustedKey:
    """One producer key: HMAC secret plus its id, owner, signing window and retirement."""

    def __init__(self, key_id, producer_id, key, not_before, not_after=None, retired=False):
        self.key_id = key_id
        self.producer_id = producer_id
        self.key = bytes(key)
        self.not_before = not_before
        self.not_after = not_after
        self.retired = bool(retired)
        parse_instant(not_before, f"key {key_id} notBefore")
        if not_after is not None and parse_instant(not_after, f"key {key_id} notAfter") <= parse_instant(
                not_before, f"key {key_id} notBefore"):
            raise ContractError(f"key {key_id} notAfter must be after notBefore")

    def signs_at(self, at):
        """Producer rule: sign only with a key that is not retired and inside [notBefore, notAfter)."""
        moment = parse_instant(at, "signing instant")
        if self.retired or moment < parse_instant(self.not_before, "notBefore"):
            return False
        return self.not_after is None or moment < parse_instant(self.not_after, "notAfter")

    def __repr__(self):
        return f"TrustedKey(key_id={self.key_id!r}, producer_id={self.producer_id!r}, key=<redacted>)"


class EvidenceTrust:
    """Pre-distributed consumer trust: the pinned database identity and the producer keys.

    Never read from the evidence itself. Holds HMAC secrets, so a trust document is secret material.
    """

    def __init__(self, database_identity, keys):
        if not isinstance(database_identity, str) or not database_identity.strip():
            raise ContractError("trust databaseIdentity must not be blank")
        self.database_identity = database_identity
        self.keys = {}
        for key in keys:
            if key.key_id in self.keys:
                raise ContractError(f"duplicate keyId {key.key_id} in trust")
            self.keys[key.key_id] = key

    @classmethod
    def from_document(cls, document):
        """Parses a trust document; errors name fields and key ids, never key values."""
        if not isinstance(document, dict) or document.get("format") != TRUST_FORMAT:
            raise ContractError(f"trust document format must be {TRUST_FORMAT}")
        if document.get("version") != TRUST_VERSION:
            raise ContractError(f"trust document version must be {TRUST_VERSION}")
        entries = document.get("keys")
        if not isinstance(entries, list) or not entries:
            raise ContractError("trust document needs at least one key")
        keys = []
        for index, entry in enumerate(entries):
            if not isinstance(entry, dict):
                raise ContractError(f"trust key #{index} must be an object")
            key_id = entry.get("keyId")
            if not isinstance(key_id, str) or not key_id.strip():
                raise ContractError(f"trust key #{index} needs a keyId")
            producer_id = entry.get("producerId")
            if not isinstance(producer_id, str) or not producer_id.strip():
                raise ContractError(f"trust key {key_id} needs a producerId")
            try:
                secret = bytes.fromhex(entry.get("keyHex") or "")
            except (TypeError, ValueError) as exc:
                raise ContractError(f"trust key {key_id} keyHex must be hex") from exc
            if len(secret) < MIN_KEY_BYTES:
                raise ContractError(f"trust key {key_id} must be at least {MIN_KEY_BYTES} bytes")
            retired = entry.get("retired", False)
            if not isinstance(retired, bool):
                raise ContractError(f"trust key {key_id} retired must be true or false")
            keys.append(TrustedKey(key_id, producer_id, secret, entry.get("notBefore"),
                                   entry.get("notAfter"), retired))
        return cls(document.get("databaseIdentity"), keys)

    def key(self, key_id):
        return self.keys.get(key_id)

    def verifying_key(self, body, at):
        """The trusted key for a signed object, or ContractError (unknown, foreign, retired, early)."""
        key = self.keys.get(body.get("keyId"))
        if key is None:
            raise ContractError("unknown producer key")
        if key.producer_id != body.get("producerId"):
            raise ContractError("producer key belongs to another producer")
        if key.retired:
            raise ContractError("retired producer key")
        if parse_instant(at, "verification instant") < parse_instant(key.not_before, "notBefore"):
            raise ContractError("producer key not yet valid")
        return key


def verify_signed_object(body, kind, kind_error, fields, trust, at, signature_error):
    """Checks shared by every signed kind, in the order both verifiers use."""
    if body.get("kind") != kind:
        raise ContractError(kind_error)
    version = body.get("schemaVersion")
    if type(version) is not int or version != SCHEMA_VERSION:
        raise ContractError("unsupported schema version")
    if body.get("databaseIdentity") != trust.database_identity:
        raise ContractError("database identity mismatch")
    key = trust.verifying_key(body, at or utc_now())
    if not verify_hmac(signed_subset(body, fields), key.key, body.get("signature")):
        raise ContractError(signature_error)
    return key


class IsolatedVersionedStore:
    """Directory store with if-not-exists puts.

    Durability class: process-crash-local. Not off-host, not WORM.
    """

    durability_class = "process-crash-local"

    def __init__(self, root, fail_puts=0, fail_on_prefix=None):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.fail_puts = fail_puts
        self.fail_on_prefix = fail_on_prefix
        self.put_attempts = 0
        self._io = threading.RLock()

    def _path(self, key):
        path = Path(key)
        if path.is_absolute() or ".." in path.parts:
            raise ContractError("unsafe store key")
        return self.root / path

    def _prepare_put(self, key):
        self.put_attempts += 1
        if self.fail_on_prefix and key.startswith(self.fail_on_prefix):
            raise PersistFailed(f"persist failed for {key}")
        if self.put_attempts <= self.fail_puts:
            raise PersistFailed(f"persist failed for {key}")
        dest = self._path(key)
        dest.parent.mkdir(parents=True, exist_ok=True)
        return dest

    def put_if_absent(self, key, data):
        dest = self._prepare_put(key)
        with self._io:
            if dest.is_file():
                existing = dest.read_bytes()
                return {
                    "publicationId": key,
                    "etag": sha256_hex(existing),
                    "created": False,
                    "existed": True,
                }
            dest.write_bytes(data)
        return {
            "publicationId": key,
            "etag": sha256_hex(data),
            "created": True,
            "existed": False,
        }

    def put(self, key, data):
        """Overwrite put used only for the signed expected-boundary frontier."""
        dest = self._prepare_put(key)
        with self._io:
            dest.write_bytes(data)
        return {
            "publicationId": key,
            "etag": sha256_hex(data),
            "created": True,
            "existed": False,
        }

    def get(self, key):
        dest = self._path(key)
        if not dest.is_file():
            raise ContractError(f"missing publication {key}")
        return dest.read_bytes()

    def get_optional(self, key):
        dest = self._path(key)
        if not dest.is_file():
            return None
        return dest.read_bytes()

    def exists(self, key):
        return self._path(key).is_file()

    def list_prefix(self, prefix):
        root = self._path(prefix)
        if not root.exists():
            return []
        if root.is_file():
            return [prefix.rstrip("/")]
        keys = []
        for path in sorted(root.rglob("*")):
            if path.is_file():
                keys.append(path.relative_to(self.root).as_posix())
        return keys


class SequenceAllocator:
    """Allocates exclusive integer sequences via if-not-exists markers."""

    def __init__(self, store):
        self.store = store
        self._lock = threading.Lock()

    def highest_allocated(self):
        keys = self.store.list_prefix("sequences/")
        numbers = []
        for key in keys:
            name = Path(key).stem
            if name.isdigit():
                numbers.append(int(name))
        return max(numbers) if numbers else 0

    def reservation_for(self, erasure_request_id):
        request_id = erasure_request_id.lower()
        for key in self.store.list_prefix("sequences/"):
            raw = self.store.get(key)
            body = json.loads(raw.decode("utf-8"))
            if body.get("erasureRequestId") == request_id:
                return int(body["sequence"])
        return None

    def allocate(self, erasure_request_id):
        request_id = erasure_request_id.lower()
        with self._lock:
            existing = self.reservation_for(request_id)
            if existing is not None:
                return existing
            nxt = self.highest_allocated() + 1
            while True:
                marker = canonical_bytes({
                    "kind": "sequence-allocation",
                    "sequence": nxt,
                    "erasureRequestId": request_id,
                })
                try:
                    receipt = self.store.put_if_absent(sequence_key(nxt), marker)
                except PersistFailed:
                    raise
                if receipt["created"]:
                    return nxt
                nxt += 1


def frontier_digest(expected_through, highest_reserved):
    return sha256_hex(canonical_bytes({
        "kind": KIND_FRONTIER,
        "expectedThrough": int(expected_through),
        "highestReserved": int(highest_reserved),
    }))


def verify_frontier(store, trust, at=None):
    raw = store.get_optional(FRONTIER_KEY)
    if raw is None:
        return None
    body = json.loads(raw.decode("utf-8"))
    verify_signed_object(body, KIND_FRONTIER, "not an expected-boundary frontier", SIGNED_FRONTIER,
                         trust, at, "frontier signature mismatch")
    expected = frontier_digest(body["expectedThrough"], body["highestReserved"])
    if expected != body["frontierDigest"]:
        raise ContractError("frontier digest mismatch")
    return body


def verify_pending(store, key, trust, at=None):
    raw = store.get(key)
    body = json.loads(raw.decode("utf-8"))
    verify_signed_object(body, KIND_PENDING, "not a pending record", SIGNED_PENDING,
                         trust, at, "producer signature mismatch")
    expected = sha256_hex(canonical_bytes({
        "authUserId": body["authUserId"],
        "erasureRequestId": body["erasureRequestId"],
        "erasedAt": body["erasedAt"],
    }))
    if expected != body["bodyDigest"]:
        raise ContractError("pending body digest mismatch")
    return body


def verify_checkpoint(store, key, trust, at=None):
    raw = store.get(key)
    body = json.loads(raw.decode("utf-8"))
    verify_signed_object(body, KIND_CHECKPOINT, "not a checkpoint", SIGNED_CHECKPOINT,
                         trust, at, "producer signature mismatch")
    ledger = canonical_bytes({"kind": "erasure-ledger", "entries": body["entries"]})
    if sha256_hex(ledger) != body["ledgerDigest"]:
        raise ContractError("checkpoint ledger digest mismatch")
    return body


class IsolatedErasureCoordinator:
    """Proposed enabled protocol. Public status stays IN_PROGRESS until COMPLETE.

    Signs with ``signing_key_id`` from ``trust`` and verifies what it reads back with the whole
    trust, so objects signed with an earlier key still verify after a rotation.
    """

    def __init__(self, store, trust, signing_key_id,
                 enabled=True, required=REQUIRED_PARTICIPANTS, clock=utc_now):
        signing_key = trust.key(signing_key_id)
        if signing_key is None:
            raise ContractError("signing key is not in the trust")
        self.store = store
        self.trust = trust
        self.signing_key = signing_key
        self.expected_db = trust.database_identity
        self.producer_id = signing_key.producer_id
        self.clock = clock
        self.enabled = enabled
        self.required = required
        self.allocator = SequenceAllocator(store)
        self._lock = threading.Lock()
        self._frontier_lock = threading.Lock()
        self._requests = {}

    def request_deletion(self, auth_user_id, erasure_request_id, erased_at):
        user_id = auth_user_id.lower()
        request_id = erasure_request_id.lower()
        with self._lock:
            existing = self._requests.get(request_id)
            if existing:
                return self._public_view(existing)
            row = {
                "erasureRequestId": request_id,
                "authUserId": user_id,
                "erasedAt": erased_at,
                "publicStatus": PUBLIC_IN_PROGRESS,
                "recording": INTERNAL_PENDING,
                "sequence": None,
                "acks": {},
                "committed": True,
            }
            self._requests[request_id] = row
        if self.enabled:
            self._persist_pending(row)
        return self._public_view(row)

    def retry_persist(self, erasure_request_id):
        row = self._require(erasure_request_id)
        if not self.enabled:
            return self._public_view(row)
        self._persist_pending(row)
        return self._public_view(row)

    def ack_participant(self, erasure_request_id, participant, recovery_attempt_id,
                        restored_dataset_id, erasure_set_digest, expected):
        if recovery_attempt_id != expected["recoveryAttemptId"]:
            raise ExposeRefused("ACK recoveryAttemptId mismatch")
        if restored_dataset_id != expected["restoredDatasetId"]:
            raise ExposeRefused("ACK restoredDatasetId mismatch")
        if erasure_set_digest != expected["erasureSetDigest"]:
            raise ExposeRefused("participant ACK digest mismatch")
        row = self._require(erasure_request_id)
        row["acks"][participant] = erasure_set_digest
        return self._public_view(row)

    def try_complete(self, erasure_request_id, erasure_set_digest):
        row = self._require(erasure_request_id)
        if self.enabled and row["recording"] != INTERNAL_RECORDED:
            raise AckRefused("COMPLETE requires durable recording")
        missing = [
            name for name in self.required
            if row["acks"].get(name) != erasure_set_digest
        ]
        if missing:
            raise ExposeRefused(f"incomplete participant ACKs: {','.join(missing)}")
        row["publicStatus"] = PUBLIC_COMPLETE
        return self._public_view(row)

    def crash_forget_memory(self):
        """Simulate primary application/host memory loss. Store is untouched."""
        self._requests = {}

    def recover_from_store(self, trust, required_through_sequence=None, at=None):
        recovered = recover_latest_trusted(
            self.store, trust, at=at,
            required_through_sequence=required_through_sequence,
        )
        if recovered["verdict"] != "ACCEPT_ISOLATED":
            return recovered
        for item in recovered["pending"]:
            self._requests[item["erasureRequestId"]] = {
                "erasureRequestId": item["erasureRequestId"],
                "authUserId": item["authUserId"],
                "erasedAt": item["erasedAt"],
                "publicStatus": PUBLIC_IN_PROGRESS,
                "recording": INTERNAL_RECORDED,
                "sequence": item["sequence"],
                "acks": {},
                "committed": True,
            }
        return recovered

    def advance_frontier(self, expected_through=None, highest_reserved=None):
        """Write the independently durable expected boundary. Not derived from listing.

        Concurrent updates take a lock and never decrease either field. Signing
        and monotonic writes do not prevent replacing the whole visible store
        with an older snapshot.
        """
        with self.store._io:
            existing = verify_frontier(self.store, self.trust, at=self.clock())
            old_expected = 0 if existing is None else int(existing["expectedThrough"])
            old_reserved = 0 if existing is None else int(existing["highestReserved"])
            new_expected = old_expected if expected_through is None else max(old_expected, int(expected_through))
            new_reserved = old_reserved if highest_reserved is None else max(old_reserved, int(highest_reserved))
            new_reserved = max(new_reserved, new_expected)
            body = {
                "schemaVersion": SCHEMA_VERSION,
                "kind": KIND_FRONTIER,
                "expectedThrough": new_expected,
                "highestReserved": new_reserved,
                "databaseIdentity": self.expected_db,
                "producerId": self.producer_id,
                "keyId": self.signing_key.key_id,
                "frontierDigest": frontier_digest(new_expected, new_reserved),
            }
            self._sign(body, SIGNED_FRONTIER)
            self.store.put(FRONTIER_KEY, canonical_bytes(body))
            return body

    def internal(self, erasure_request_id):
        return dict(self._require(erasure_request_id))

    def _require_signing_key(self):
        """Producer rule: only a key that is not retired and inside its signing window signs."""
        if not self.signing_key.signs_at(self.clock()):
            raise ContractError("signing key is retired or outside its signing window")

    def _sign(self, body, fields):
        self._require_signing_key()
        body["signature"] = sign(signed_subset(body, fields), self.signing_key.key)

    def _require(self, erasure_request_id):
        row = self._requests.get(erasure_request_id.lower())
        if row is None:
            raise ContractError("request is not in coordinator memory")
        return row

    def _persist_pending(self, row):
        # Refuse before reserving anything, so an unusable key leaves no reservation behind.
        self._require_signing_key()
        record_id = erasure_record_id(row["erasureRequestId"])
        payload = {
            "authUserId": row["authUserId"],
            "erasureRequestId": row["erasureRequestId"],
            "erasedAt": row["erasedAt"],
        }
        body_digest = sha256_hex(canonical_bytes(payload))
        if self.store.exists(record_id):
            existing = verify_pending(self.store, record_id, self.trust, at=self.clock())
            if existing["bodyDigest"] != body_digest:
                raise ContractError("ambiguous retry would create a conflicting record")
            self.advance_frontier(expected_through=existing["sequence"], highest_reserved=existing["sequence"])
            row["sequence"] = existing["sequence"]
            row["recording"] = INTERNAL_RECORDED
            return existing
        sequence = self.allocator.allocate(row["erasureRequestId"])
        self.advance_frontier(highest_reserved=sequence)
        body = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": KIND_PENDING,
            "erasureRecordId": record_id,
            "erasureRequestId": row["erasureRequestId"],
            "authUserId": row["authUserId"],
            "erasedAt": row["erasedAt"],
            "sequence": sequence,
            "databaseIdentity": self.expected_db,
            "producerId": self.producer_id,
            "keyId": self.signing_key.key_id,
            "bodyDigest": body_digest,
        }
        self._sign(body, SIGNED_PENDING)
        try:
            receipt = self.store.put_if_absent(record_id, canonical_bytes(body))
        except PersistFailed:
            row["recording"] = INTERNAL_PENDING
            row["sequence"] = sequence
            raise
        if not receipt["created"]:
            existing = verify_pending(self.store, record_id, self.trust, at=self.clock())
            if existing["bodyDigest"] != body_digest:
                raise ContractError("ambiguous retry would create a conflicting record")
            self.advance_frontier(expected_through=existing["sequence"], highest_reserved=existing["sequence"])
            row["sequence"] = existing["sequence"]
            row["recording"] = INTERNAL_RECORDED
            return existing
        try:
            self.advance_frontier(expected_through=sequence, highest_reserved=sequence)
        except PersistFailed:
            row["recording"] = INTERNAL_PENDING
            row["sequence"] = sequence
            raise
        row["sequence"] = sequence
        row["recording"] = INTERNAL_RECORDED
        return body

    def publish_checkpoint(self, sequence, entries, capture_protocol="table-share-lock"):
        ledger = canonical_bytes({"kind": "erasure-ledger", "entries": entries})
        body = {
            "schemaVersion": SCHEMA_VERSION,
            "kind": KIND_CHECKPOINT,
            "sequence": sequence,
            "databaseIdentity": self.expected_db,
            "producerId": self.producer_id,
            "keyId": self.signing_key.key_id,
            "ledgerDigest": sha256_hex(ledger),
            "captureProtocol": capture_protocol,
            "entries": entries,
        }
        self._sign(body, SIGNED_CHECKPOINT)
        receipt = self.store.put_if_absent(checkpoint_key(sequence), canonical_bytes(body))
        if not receipt["created"]:
            verify_checkpoint(self.store, checkpoint_key(sequence), self.trust, at=self.clock())
        return body

    @staticmethod
    def _public_view(row):
        return {
            "publicStatus": row["publicStatus"],
            "recording": row["recording"],
            "erasureRequestId": row["erasureRequestId"],
            "sequence": row["sequence"],
        }


def _abandoned_reservations(store, published):
    abandoned = []
    for key in store.list_prefix("sequences/"):
        raw = store.get(key)
        body = json.loads(raw.decode("utf-8"))
        sequence = int(body["sequence"])
        if sequence not in published:
            abandoned.append(sequence)
    return sorted(abandoned)


def recover_latest_trusted(store, trust, required_through_sequence=None, at=None):
    """Completeness uses the signed store frontier, never listing max.

    A contiguous listed prefix is not evidence that the highest records exist.
    If the independently durable expected boundary is missing, the verdict is
    UNKNOWN. If the frontier is present but any 1..expectedThrough record is
    missing, the verdict is BLOCKED. Local high-water marks are not consulted.
    """
    pending = []
    at = at or utc_now()
    for key in store.list_prefix("records/"):
        pending.append(verify_pending(store, key, trust, at=at))
    checkpoints = []
    for key in store.list_prefix("checkpoints/"):
        checkpoints.append(verify_checkpoint(store, key, trust, at=at))
    published = {item["sequence"] for item in pending + checkpoints}
    frontier = verify_frontier(store, trust, at=at)
    abandoned = _abandoned_reservations(store, published)
    listed_max = max(published) if published else None

    def result(verdict, latest=None, gaps=None, trusted_pending=None, reason=None,
               expected_through=None):
        trusted = trusted_pending if trusted_pending is not None else []
        trusted_checkpoints = [
            item for item in checkpoints
            if latest is not None and item["sequence"] <= latest
        ]
        latest_checkpoint = None
        if trusted_checkpoints:
            latest_checkpoint = max(trusted_checkpoints, key=lambda item: item["sequence"])
        payload = {
            "verdict": verdict,
            "completenessEstablished": verdict == "ACCEPT_ISOLATED",
            "verifiedCoverage": False,
            "certifiedOffHostWorm": False,
            "durabilityClass": getattr(store, "durability_class", "unknown"),
            "latestTrustedSequence": latest,
            "latestTrustedCheckpoint": None if latest_checkpoint is None else latest_checkpoint["sequence"],
            "expectedThrough": expected_through,
            "listedMaximumSequence": listed_max,
            "gaps": gaps or [],
            "abandonedReservations": abandoned,
            "pending": trusted,
            "expose": False,
            "reason": reason,
        }
        if required_through_sequence is not None and verdict != "ACCEPT_ISOLATED":
            raise ContractError(
                f"{verdict}: missing records or unknown tail; recovery BLOCKED"
            )
        if required_through_sequence is not None:
            expected = 0 if expected_through is None else expected_through
            if required_through_sequence > expected or required_through_sequence in (gaps or []):
                raise ContractError(
                    "missing records or unknown tail; recovery BLOCKED"
                )
        return payload

    if frontier is None:
        return result(
            "UNKNOWN",
            reason="independently durable expected boundary is missing",
        )

    expected_through = int(frontier["expectedThrough"])
    required = list(range(1, expected_through + 1)) if expected_through else []
    gaps = [n for n in required if n not in published]
    if gaps or (expected_through == 0 and abandoned):
        return result(
            "BLOCKED",
            gaps=gaps,
            expected_through=expected_through,
            reason="expected boundary is present but published records are incomplete",
        )
    if expected_through == 0:
        return result(
            "ACCEPT_ISOLATED",
            expected_through=0,
            reason="expected boundary is zero and no abandoned reservation remains",
        )
    trusted_pending = [item for item in pending if item["sequence"] <= expected_through]
    return result(
        "ACCEPT_ISOLATED",
        latest=expected_through,
        expected_through=expected_through,
        trusted_pending=trusted_pending,
    )


def production_durable_recording_is_disabled(root=None):
    """Guard: production default remains off. COMPLETE is persist-gated only when enabled."""
    root = Path(root or Path(__file__).resolve().parents[2])
    yml = (root / "services/auth-service/src/main/resources/application.yml").read_text(encoding="utf-8")
    if "durable-recording-enabled: true" in yml or "DURABLE_RECORDING_ENABLED:true" in yml:
        raise ContractError("durable recording was enabled in production defaults")
    java = (root / "services/auth-service/src/main/java/com/parkio/auth/"
            "application/AccountErasureApplicationService.java").read_text(encoding="utf-8")
    compacted = java.replace(" ", "").replace("\n", "")
    if "durable-recording-enabled:false" not in compacted:
        raise ContractError("durable recording default must remain false")
    main_java = root / "services/auth-service/src/main/java"
    for path in main_java.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if "implements DurableErasureRecordStore" in text and (
                "java.nio.file" in text or "java.io.File" in text or "Paths.get" in text):
            raise ContractError("local directory must not be a production durability provider")
    return True


def fixture_keys_stay_in_tests(root=None):
    """Guard: the shared fixture keys appear only in tests, and the service has no default key.

    Scans service main sources and resources, Compose files and workflows for every fixture
    secret (as text and as hex); requires empty defaults for the trust file and signing key id.
    """
    root = Path(root or Path(__file__).resolve().parents[2])
    producer = root / "services/auth-service/src/test/resources/durable-erasure-evidence/v2/producer.json"
    needles = []
    for key in json.loads(producer.read_text(encoding="utf-8"))["keys"]:
        secret = bytes.fromhex(key["keyHex"])
        needles += [key["keyHex"], secret.decode("utf-8", errors="ignore")]
    places = [path for path in (root / "services").glob("*/src/main") if path.is_dir()]
    places += [root / "docker", root / ".github"]
    for place in places:
        for path in sorted(place.rglob("*")):
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8", errors="ignore")
            for needle in needles:
                if needle and needle in text:
                    raise ContractError(f"{path.relative_to(root).as_posix()} contains a fixture key")
    yml = (root / "services/auth-service/src/main/resources/application.yml").read_text(encoding="utf-8")
    for setting in ("trust-file: ${PARKIO_ERASURE_STORE_TRUST_FILE:}",
                    "producer-key-id: ${PARKIO_ERASURE_STORE_PRODUCER_KEY_ID:}"):
        if setting not in yml:
            raise ContractError("the object-lock store must have no default trust file or signing key")
    return True


def checkpoint_producer_is_disabled(root=None):
    """Guard: checkpoints stay off by default and no service code calls the producer.

    The cadence is an operator decision, so the producer has no schedule and no caller.
    """
    root = Path(root or Path(__file__).resolve().parents[2])
    yml = (root / "services/auth-service/src/main/resources/application.yml").read_text(encoding="utf-8")
    if "enabled: ${PARKIO_ERASURE_CHECKPOINT_ENABLED:false}" not in yml:
        raise ContractError("erasure checkpoints must stay disabled by default")
    main_java = root / "services/auth-service/src/main/java/com/parkio/auth"
    for path in sorted(main_java.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(main_java).as_posix()
        for needle, allowed in CHECKPOINT_PRODUCER_SOURCES.items():
            if needle in text and relative not in allowed:
                raise ContractError(f"{relative} must not call the checkpoint producer ({needle})")
    return True