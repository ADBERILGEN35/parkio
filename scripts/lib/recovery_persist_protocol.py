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
    "authUserId", "sequence", "databaseIdentity", "producerId", "bodyDigest",
)
SIGNED_CHECKPOINT = (
    "schemaVersion", "kind", "sequence", "databaseIdentity", "producerId",
    "ledgerDigest", "captureProtocol",
)
SIGNED_FRONTIER = (
    "schemaVersion", "kind", "expectedThrough", "highestReserved",
    "databaseIdentity", "producerId", "frontierDigest",
)


def erasure_record_id(erasure_request_id):
    """Stable identity: one request, one record key. Retry-safe."""
    return f"records/{erasure_request_id.lower()}.json"


def sequence_key(sequence):
    return f"sequences/{int(sequence):016d}.json"


def checkpoint_key(sequence):
    return f"checkpoints/{int(sequence):016d}.json"


def signed_subset(body, fields):
    return {key: body[key] for key in fields}


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


def verify_frontier(store, expected_db, trusted_keys):
    raw = store.get_optional(FRONTIER_KEY)
    if raw is None:
        return None
    body = json.loads(raw.decode("utf-8"))
    if body.get("kind") != KIND_FRONTIER:
        raise ContractError("not an expected-boundary frontier")
    if body.get("databaseIdentity") != expected_db:
        raise ContractError("database identity mismatch")
    key_bytes = trusted_keys.get(body.get("producerId"))
    if not key_bytes:
        raise ContractError("unknown producer")
    if not verify_hmac(signed_subset(body, SIGNED_FRONTIER), key_bytes, body.get("signature")):
        raise ContractError("frontier signature mismatch")
    expected = frontier_digest(body["expectedThrough"], body["highestReserved"])
    if expected != body["frontierDigest"]:
        raise ContractError("frontier digest mismatch")
    return body


def verify_pending(store, key, expected_db, trusted_keys):
    raw = store.get(key)
    body = json.loads(raw.decode("utf-8"))
    if body.get("kind") != KIND_PENDING:
        raise ContractError("not a pending record")
    if body.get("databaseIdentity") != expected_db:
        raise ContractError("database identity mismatch")
    key_bytes = trusted_keys.get(body.get("producerId"))
    if not key_bytes:
        raise ContractError("unknown producer")
    if not verify_hmac(signed_subset(body, SIGNED_PENDING), key_bytes, body.get("signature")):
        raise ContractError("producer signature mismatch")
    expected = sha256_hex(canonical_bytes({
        "authUserId": body["authUserId"],
        "erasureRequestId": body["erasureRequestId"],
        "erasedAt": body["erasedAt"],
    }))
    if expected != body["bodyDigest"]:
        raise ContractError("pending body digest mismatch")
    return body


def verify_checkpoint(store, key, expected_db, trusted_keys):
    raw = store.get(key)
    body = json.loads(raw.decode("utf-8"))
    if body.get("kind") != KIND_CHECKPOINT:
        raise ContractError("not a checkpoint")
    if body.get("databaseIdentity") != expected_db:
        raise ContractError("database identity mismatch")
    key_bytes = trusted_keys.get(body.get("producerId"))
    if not key_bytes:
        raise ContractError("unknown producer")
    if not verify_hmac(signed_subset(body, SIGNED_CHECKPOINT), key_bytes, body.get("signature")):
        raise ContractError("producer signature mismatch")
    ledger = canonical_bytes({"kind": "erasure-ledger", "entries": body["entries"]})
    if sha256_hex(ledger) != body["ledgerDigest"]:
        raise ContractError("checkpoint ledger digest mismatch")
    return body


class IsolatedErasureCoordinator:
    """Proposed enabled protocol. Public status stays IN_PROGRESS until COMPLETE."""

    def __init__(self, store, expected_db, producer_id, producer_key,
                 enabled=True, required=REQUIRED_PARTICIPANTS):
        self.store = store
        self.expected_db = expected_db
        self.producer_id = producer_id
        self.producer_key = producer_key
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

    def recover_from_store(self, trusted_keys, required_through_sequence=None):
        recovered = recover_latest_trusted(
            self.store, self.expected_db, trusted_keys,
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
            existing = verify_frontier(
                self.store, self.expected_db, {self.producer_id: self.producer_key},
            )
            old_expected = 0 if existing is None else int(existing["expectedThrough"])
            old_reserved = 0 if existing is None else int(existing["highestReserved"])
            new_expected = old_expected if expected_through is None else max(old_expected, int(expected_through))
            new_reserved = old_reserved if highest_reserved is None else max(old_reserved, int(highest_reserved))
            new_reserved = max(new_reserved, new_expected)
            body = {
                "schemaVersion": 1,
                "kind": KIND_FRONTIER,
                "expectedThrough": new_expected,
                "highestReserved": new_reserved,
                "databaseIdentity": self.expected_db,
                "producerId": self.producer_id,
                "frontierDigest": frontier_digest(new_expected, new_reserved),
            }
            body["signature"] = sign(signed_subset(body, SIGNED_FRONTIER), self.producer_key)
            self.store.put(FRONTIER_KEY, canonical_bytes(body))
            return body

    def internal(self, erasure_request_id):
        return dict(self._require(erasure_request_id))

    def _require(self, erasure_request_id):
        row = self._requests.get(erasure_request_id.lower())
        if row is None:
            raise ContractError("request is not in coordinator memory")
        return row

    def _persist_pending(self, row):
        record_id = erasure_record_id(row["erasureRequestId"])
        payload = {
            "authUserId": row["authUserId"],
            "erasureRequestId": row["erasureRequestId"],
            "erasedAt": row["erasedAt"],
        }
        body_digest = sha256_hex(canonical_bytes(payload))
        if self.store.exists(record_id):
            existing = verify_pending(
                self.store, record_id, self.expected_db,
                {self.producer_id: self.producer_key},
            )
            if existing["bodyDigest"] != body_digest:
                raise ContractError("ambiguous retry would create a conflicting record")
            self.advance_frontier(expected_through=existing["sequence"], highest_reserved=existing["sequence"])
            row["sequence"] = existing["sequence"]
            row["recording"] = INTERNAL_RECORDED
            return existing
        sequence = self.allocator.allocate(row["erasureRequestId"])
        self.advance_frontier(highest_reserved=sequence)
        body = {
            "schemaVersion": 1,
            "kind": KIND_PENDING,
            "erasureRecordId": record_id,
            "erasureRequestId": row["erasureRequestId"],
            "authUserId": row["authUserId"],
            "erasedAt": row["erasedAt"],
            "sequence": sequence,
            "databaseIdentity": self.expected_db,
            "producerId": self.producer_id,
            "bodyDigest": body_digest,
        }
        body["signature"] = sign(signed_subset(body, SIGNED_PENDING), self.producer_key)
        try:
            receipt = self.store.put_if_absent(record_id, canonical_bytes(body))
        except PersistFailed:
            row["recording"] = INTERNAL_PENDING
            row["sequence"] = sequence
            raise
        if not receipt["created"]:
            existing = verify_pending(
                self.store, record_id, self.expected_db,
                {self.producer_id: self.producer_key},
            )
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
            "schemaVersion": 1,
            "kind": KIND_CHECKPOINT,
            "sequence": sequence,
            "databaseIdentity": self.expected_db,
            "producerId": self.producer_id,
            "ledgerDigest": sha256_hex(ledger),
            "captureProtocol": capture_protocol,
            "entries": entries,
        }
        body["signature"] = sign(signed_subset(body, SIGNED_CHECKPOINT), self.producer_key)
        receipt = self.store.put_if_absent(checkpoint_key(sequence), canonical_bytes(body))
        if not receipt["created"]:
            verify_checkpoint(
                self.store, checkpoint_key(sequence), self.expected_db,
                {self.producer_id: self.producer_key},
            )
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


def recover_latest_trusted(store, expected_db, trusted_keys, required_through_sequence=None):
    """Completeness uses the signed store frontier, never listing max.

    A contiguous listed prefix is not evidence that the highest records exist.
    If the independently durable expected boundary is missing, the verdict is
    UNKNOWN. If the frontier is present but any 1..expectedThrough record is
    missing, the verdict is BLOCKED. Local high-water marks are not consulted.
    """
    pending = []
    for key in store.list_prefix("records/"):
        pending.append(verify_pending(store, key, expected_db, trusted_keys))
    checkpoints = []
    for key in store.list_prefix("checkpoints/"):
        checkpoints.append(verify_checkpoint(store, key, expected_db, trusted_keys))
    published = {item["sequence"] for item in pending + checkpoints}
    frontier = verify_frontier(store, expected_db, trusted_keys)
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