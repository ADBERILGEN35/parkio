#!/usr/bin/env python3
"""Isolated synthetic proof for the recovery evidence contract.

Disposable table + directory store only. Does not download, decrypt, or
restore production backups. Does not enable verifiedCoverage.
"""
from __future__ import annotations

import calendar
import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

from recovery_evidence_contract import (  # noqa: E402
    AckRefused,
    CaptureAborted,
    ContractError,
    DurableAckGate,
    ExposeRefused,
    IsolatedAuthTable,
    IsolatedMediaBucket,
    IsolatedObjectStore,
    PersistFailed,
    PROTOCOL_LOCK,
    ReplayCompletion,
    isolated_replay,
    publish_snapshot,
    restore_erasure_ledger_still_unverified,
    verify_evidence,
    write_claim_file,
)

ERASED = "00000000-0000-4000-a000-0000000000a1"
UNRELATED = "00000000-0000-4000-a000-000000000099"
LATER = "00000000-0000-4000-a000-0000000000b2"
DB = "auth-db:isolated-fixture"
PRODUCER = "fixture-producer"
KEY = b"parkio-isolated-recovery-evidence-not-prod"
CUTOFF_OK = "2026-09-27T10:00:00Z"
CUTOFF_LATE = "2026-09-27T18:00:00Z"


class RecoveryEvidenceContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = IsolatedObjectStore(Path(self.tmp.name) / "store")
        self.table = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 10, 0, 0, 0, 0, 0)))
        self.keys = {PRODUCER: KEY}

    def tearDown(self):
        self.tmp.cleanup()

    def _publish(self, store=None, table=None):
        snapshot = (table or self.table).capture()
        return publish_snapshot(store or self.store, snapshot, PRODUCER, KEY)

    def test_production_verified_coverage_stays_false(self):
        self.assertTrue(restore_erasure_ledger_still_unverified(ROOT))

    def test_claim_file_alone_is_rejected(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        claim_path = Path(self.tmp.name) / "claim.json"
        claim = write_claim_file(claim_path, body, receipt["publicationId"])
        empty = IsolatedObjectStore(Path(self.tmp.name) / "empty")
        with self.assertRaises(ContractError):
            verify_evidence(empty, DB, self.keys, CUTOFF_OK, claim=claim)

    def test_store_backed_isolated_accept_does_not_certify_production(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        verified = verify_evidence(
            self.store, DB, self.keys, CUTOFF_OK,
            publication_id=receipt["publicationId"],
        )
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])
        self.assertFalse(verified["certifiedProduction"])
        self.assertEqual(verified["captureProtocol"], PROTOCOL_LOCK)
        self.assertIn(ERASED, verified["ids"])

    def test_concurrent_erasure_waits_for_share_lock(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        release = threading.Event()
        acquired = threading.Event()
        self.table.hold_share = release
        self.table.share_acquired = acquired
        captured = {}

        def run_capture():
            captured["snapshot"] = self.table.capture()

        worker = threading.Thread(target=run_capture)
        worker.start()
        self.assertTrue(acquired.wait(1))
        inserted = {"done": False}

        def run_insert():
            self.table.insert(LATER, "2026-09-27T10:00:01Z")
            inserted["done"] = True

        writer = threading.Thread(target=run_insert)
        writer.start()
        time.sleep(0.05)
        self.assertFalse(inserted["done"], "concurrent insert committed during SHARE")
        release.set()
        worker.join(2)
        writer.join(2)
        self.assertTrue(inserted["done"])
        ids = {item["authUserId"] for item in captured["snapshot"]["entries"]}
        self.assertIn(ERASED, ids)
        self.assertNotIn(LATER, ids)

    def test_capture_transaction_failure_publishes_nothing(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        self.table.fail_next_capture = True
        with self.assertRaises(CaptureAborted):
            self.table.capture()
        self.assertEqual(self.store.put_attempts, 0)
        self.assertEqual(list(Path(self.store.root).rglob("*")), [])

    def test_offhost_persist_failure_does_not_ack(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        failing = IsolatedObjectStore(Path(self.tmp.name) / "fail", fail_puts=1)
        snapshot = self.table.capture()
        with self.assertRaises(PersistFailed):
            publish_snapshot(failing, snapshot, PRODUCER, KEY)
        gate = DurableAckGate()
        with self.assertRaises(ContractError):
            verify_evidence(failing, DB, self.keys, CUTOFF_OK, publication_id="watermarks/missing.json")
        self.assertEqual(gate.status(ERASED)["status"], "ERASURE_PENDING_DURABLE")

    def test_missing_altered_and_wrong_database_evidence(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, self.keys, CUTOFF_OK, publication_id="watermarks/absent.json")
        raw = self.store.get(receipt["publicationId"])
        tampered = json.loads(raw)
        tampered["ledgerDigest"] = "0" * 64
        self.store._path(receipt["publicationId"]).write_bytes(
            json.dumps(tampered, separators=(",", ":"), sort_keys=True).encode()
        )
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, self.keys, CUTOFF_OK, publication_id=receipt["publicationId"])
        self.store.put(receipt["publicationId"], raw)
        with self.assertRaises(ContractError):
            verify_evidence(self.store, "auth-db:other-cluster", self.keys, CUTOFF_OK,
                            publication_id=receipt["publicationId"])

    def test_erasure_after_watermark_then_host_loss_does_not_lower_cutoff(self):
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, _body = self._publish()
        verified = verify_evidence(
            self.store, DB, self.keys, "2026-09-27T10:00:00Z",
            publication_id=receipt["publicationId"],
        )
        gate = DurableAckGate()
        self.assertEqual(gate.acknowledge(ERASED, verified)["status"], "ACK")
        self.table.insert(LATER, "2026-09-27T11:00:00Z")
        # Simulated host loss: live table disappears; only the store remains.
        lost_table = IsolatedAuthTable(DB)
        self.assertNotIn(LATER, lost_table.committed_ids())
        with self.assertRaises(ContractError) as ctx:
            verify_evidence(
                self.store, DB, self.keys, CUTOFF_LATE,
                publication_id=receipt["publicationId"],
            )
        self.assertIn("do not lower the cutoff", str(ctx.exception))
        self.assertEqual(gate.status(LATER)["status"], "ERASURE_PENDING_DURABLE")
        with self.assertRaises(AckRefused):
            gate.acknowledge(LATER, verified)

    def test_missing_participant_ack_refuses_expose(self):
        completion = ReplayCompletion("digest-1")
        completion.ack("auth", "digest-1")
        completion.ack("user", "digest-1")
        completion.ack("parking", "digest-1")
        with self.assertRaises(ExposeRefused):
            completion.expose()

    def test_successful_isolated_replay_preserves_unrelated(self):
        auth_status = {ERASED: "ACTIVE", UNRELATED: "ACTIVE"}
        media = IsolatedMediaBucket()
        media.put("media/erased.jpg", ERASED)
        media.put("media/keep.jpg", UNRELATED)
        result = isolated_replay(auth_status, media, [ERASED], UNRELATED, "digest-ok")
        self.assertTrue(result["erasedAbsent"])
        self.assertTrue(result["unrelatedPreserved"])
        self.assertEqual(result["publicTraffic"], "not-enabled")
        self.assertEqual(auth_status[ERASED], "ERASED")
        self.assertEqual(auth_status[UNRELATED], "ACTIVE")
        self.assertNotIn("media/erased.jpg", media.objects)
        self.assertIn("media/keep.jpg", media.objects)


if __name__ == "__main__":
    unittest.main()
