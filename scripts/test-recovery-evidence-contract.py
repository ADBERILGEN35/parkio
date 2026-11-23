#!/usr/bin/env python3
"""Isolated synthetic proof for the recovery evidence contract.

Classification:
- This file: model/unit tests (in-process table, directory store, HMAC fixture).
- scripts/test-recovery-evidence-pg-share-lock.py: real PostgreSQL locking.
- IsolatedObjectStore tests: directory fixture, not a real object-store service.
- scripts/test-restore-safe-preflight.sh: real restore-entrypoint with stubs.
  Replay here is modeled; it is not end-to-end production-entrypoint acceptance.

Disposable fixtures only. Does not download, decrypt, or restore production
backups. Does not enable verifiedCoverage.
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
    AUTH_ERASURE_SERVICE,
    AckRefused,
    CaptureAborted,
    CONFIGURED_PARTICIPANTS,
    ContractError,
    DurableAckGate,
    EvidenceConsumer,
    ExposeRefused,
    IsolatedAuthTable,
    IsolatedMediaBucket,
    IsolatedObjectStore,
    PersistFailed,
    PROTOCOL_LOCK,
    REQUIRED_PARTICIPANTS,
    ReplayCompletion,
    configured_participants_from_auth,
    isolated_replay,
    persist_before_ack_is_not_implemented,
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
ATTEMPT_1 = "recovery-attempt-1"
ATTEMPT_2 = "recovery-attempt-2"
DATASET_1 = "dataset-stamp-s"
DATASET_2 = "dataset-stamp-l"


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
        """model/unit: source guard on restore-erasure-ledger.py."""
        self.assertTrue(restore_erasure_ledger_still_unverified(ROOT))

    def test_persist_before_ack_is_not_implemented(self):
        """model/unit: current API returns IN_PROGRESS after auth TX, not persist-ACK."""
        self.assertTrue(persist_before_ack_is_not_implemented(ROOT))

    def test_configured_participants_match_auth_source(self):
        """model/unit: inventory is derived from auth DEFAULT_PARTICIPANTS."""
        self.assertEqual(configured_participants_from_auth(ROOT), CONFIGURED_PARTICIPANTS)
        self.assertEqual(REQUIRED_PARTICIPANTS[0], "auth")
        self.assertEqual(REQUIRED_PARTICIPANTS[1:], CONFIGURED_PARTICIPANTS)

    def test_claim_file_alone_is_rejected(self):
        """model/unit: claim file without store object is REJECT."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, body = self._publish()
        claim_path = Path(self.tmp.name) / "claim.json"
        claim = write_claim_file(claim_path, body, receipt["publicationId"])
        empty = IsolatedObjectStore(Path(self.tmp.name) / "empty")
        with self.assertRaises(ContractError):
            verify_evidence(empty, DB, self.keys, CUTOFF_OK, claim=claim)

    def test_store_backed_isolated_accept_does_not_certify_production(self):
        """model/unit: directory-store HMAC accept is ACCEPT_ISOLATED only."""
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
        self.assertIn("producer-authenticity", verified["trustProved"])
        self.assertIn("completeness-through-boundary", verified["trustNotProved"])

    def test_concurrent_erasure_waits_for_share_lock_model(self):
        """model/unit: in-process SHARE simulation. Not a real PostgreSQL locking test."""
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
        """model/unit: aborted capture writes no store object."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        self.table.fail_next_capture = True
        with self.assertRaises(CaptureAborted):
            self.table.capture()
        self.assertEqual(self.store.put_attempts, 0)
        self.assertEqual(list(Path(self.store.root).rglob("*")), [])

    def test_offhost_persist_failure_does_not_ack(self):
        """model/unit: proposed DurableAckGate; directory store persist failure."""
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
        """model/unit: missing object, tampered bytes, wrong expected DB."""
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
        """model/unit: unknown tail stays BLOCKED; older watermark cannot authorize expose."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, _body = self._publish()
        verified = verify_evidence(
            self.store, DB, self.keys, "2026-09-27T10:00:00Z",
            publication_id=receipt["publicationId"],
        )
        gate = DurableAckGate()
        self.assertEqual(gate.acknowledge(ERASED, verified)["status"], "ACK")
        self.table.insert(LATER, "2026-09-27T11:00:00Z")
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

    def test_unknown_missing_tail_does_not_preserve_unacked_request(self):
        """model/unit: calling a lost request unacknowledged does not recreate it."""
        self.table.insert(LATER, "2026-09-27T11:00:00Z")
        lost = IsolatedAuthTable(DB)
        self.assertNotIn(LATER, lost.committed_ids())
        gate = DurableAckGate()
        self.assertEqual(gate.status(LATER)["status"], "ERASURE_PENDING_DURABLE")
        self.assertIsNone(gate._acks.get(LATER))

    def test_hmac_and_receipt_do_not_prove_completeness(self):
        """model/unit: a signed incomplete snapshot still ACCEPT_ISOLATED."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        self.table.insert(LATER, "2026-09-27T09:30:00Z")
        incomplete = {
            "captureProtocol": PROTOCOL_LOCK,
            "databaseIdentity": DB,
            "coveredThrough": CUTOFF_OK,
            "entries": [{"authUserId": ERASED, "erasedAt": "2026-09-27T09:00:00Z"}],
        }
        receipt, _body = publish_snapshot(self.store, incomplete, PRODUCER, KEY)
        verified = verify_evidence(
            self.store, DB, self.keys, CUTOFF_OK,
            publication_id=receipt["publicationId"],
        )
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])
        self.assertIn(ERASED, verified["ids"])
        self.assertNotIn(LATER, verified["ids"])
        self.assertIn("completeness-through-boundary", verified["trustNotProved"])

    def test_older_valid_evidence_is_rejected_for_freshness(self):
        """model/unit: older valid HMAC object cannot replace a newer accepted watermark."""
        early = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 9, 0, 0, 0, 0, 0)))
        early.insert(ERASED, "2026-09-27T08:00:00Z")
        old_receipt, _ = publish_snapshot(self.store, early.capture(), PRODUCER, KEY)
        late = IsolatedAuthTable(DB, clock=lambda: calendar.timegm((2026, 9, 27, 10, 0, 0, 0, 0, 0)))
        late.insert(ERASED, "2026-09-27T08:00:00Z")
        late.insert(LATER, "2026-09-27T09:30:00Z")
        new_receipt, _ = publish_snapshot(self.store, late.capture(), PRODUCER, KEY)
        consumer = EvidenceConsumer()
        consumer.accept(self.store, DB, self.keys, CUTOFF_OK, publication_id=new_receipt["publicationId"])
        with self.assertRaises(ContractError) as ctx:
            consumer.accept(self.store, DB, self.keys, "2026-09-27T09:00:00Z",
                            publication_id=old_receipt["publicationId"])
        self.assertIn("freshness failed", str(ctx.exception))

    def test_rotated_key_rejects_old_producer_and_retired_key_still_verifies(self):
        """model/unit: keys are trusted independently; rotation without not-before still verifies old objects."""
        self.table.insert(ERASED, "2026-09-27T09:00:00Z")
        receipt, _ = self._publish()
        with self.assertRaises(ContractError):
            verify_evidence(self.store, DB, {PRODUCER: b"rotated-key-not-prod"}, CUTOFF_OK,
                            publication_id=receipt["publicationId"])
        both = {PRODUCER: KEY, "fixture-producer-v2": b"rotated-key-not-prod"}
        verified = verify_evidence(self.store, DB, both, CUTOFF_OK,
                                   publication_id=receipt["publicationId"])
        self.assertEqual(verified["verdict"], "ACCEPT_ISOLATED")
        self.assertFalse(verified["verifiedCoverage"])

    def test_missing_participant_ack_refuses_expose(self):
        """model/unit: eight configured participants plus auth are required."""
        completion = ReplayCompletion(ATTEMPT_1, DATASET_1, "digest-1")
        for name in ("auth", "user", "parking", "media"):
            completion.ack(name, ATTEMPT_1, DATASET_1, "digest-1")
        with self.assertRaises(ExposeRefused) as ctx:
            completion.expose()
        self.assertIn("moderation", str(ctx.exception))

    def test_prior_recovery_attempt_acks_cannot_authorize_new_restore(self):
        """model/unit: ACKs from an earlier attempt/dataset cannot complete a new restore."""
        current = ReplayCompletion(ATTEMPT_2, DATASET_2, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("auth", ATTEMPT_1, DATASET_2, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("user", ATTEMPT_2, DATASET_1, "digest-new")
        with self.assertRaises(ExposeRefused):
            current.ack("parking", ATTEMPT_2, DATASET_2, "digest-old")
        for name in REQUIRED_PARTICIPANTS:
            current.ack(name, ATTEMPT_2, DATASET_2, "digest-new")
        self.assertEqual(current.expose()["replayComplete"], True)

    def test_successful_isolated_replay_preserves_unrelated(self):
        """model/unit: isolated replay model, not production-entrypoint acceptance."""
        auth_status = {ERASED: "ACTIVE", UNRELATED: "ACTIVE"}
        media = IsolatedMediaBucket()
        media.put("media/erased.jpg", ERASED)
        media.put("media/keep.jpg", UNRELATED)
        result = isolated_replay(
            auth_status, media, [ERASED], UNRELATED, "digest-ok", ATTEMPT_1, DATASET_1,
        )
        self.assertTrue(result["erasedAbsent"])
        self.assertTrue(result["unrelatedPreserved"])
        self.assertEqual(result["publicTraffic"], "not-enabled")
        self.assertEqual(auth_status[ERASED], "ERASED")
        self.assertEqual(auth_status[UNRELATED], "ACTIVE")
        self.assertNotIn("media/erased.jpg", media.objects)
        self.assertIn("media/keep.jpg", media.objects)


IN_PROGRESS = 'return new AccountDeletionStatusView(requestId, "IN_PROGRESS");'
DELEGATING = """\
    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password) {
        return requestDeletion(principalUserId, password, null);
    }
"""
IMPLEMENTING = f"""\
    @Transactional
    public AccountDeletionStatusView requestDeletion(UUID principalUserId, String password, String clientKey) {{
        verifyPassword(principalUserId, password, clientKey);
        UUID requestId = UUID.randomUUID();
        log.info("erasure requested requestId={{}} status=IN_PROGRESS", requestId);
        {IN_PROGRESS}
    }}
"""


class PersistBeforeAckScanTest(unittest.TestCase):
    """model/unit: the requestDeletion source guard on synthetic auth sources, both overload orders."""

    def scan(self, *methods):
        with tempfile.TemporaryDirectory() as raw:
            path = Path(raw) / AUTH_ERASURE_SERVICE
            path.parent.mkdir(parents=True)
            path.write_text(
                "package com.parkio.auth.application;\n\nclass AccountErasureApplicationService {\n\n"
                + "\n".join(methods) + "}\n",
                encoding="utf-8",
            )
            return persist_before_ack_is_not_implemented(raw)

    def assert_both_orders_raise(self, pattern, delegating, implementing):
        for order, methods in (("delegation first", (delegating, implementing)),
                               ("implementation first", (implementing, delegating))):
            with self.subTest(order=order), self.assertRaisesRegex(ContractError, pattern):
                self.scan(*methods)

    def test_one_implementation_and_pure_delegations_pass(self):
        self.assertTrue(self.scan(IMPLEMENTING))
        self.assertTrue(self.scan(DELEGATING, IMPLEMENTING))
        self.assertTrue(self.scan(IMPLEMENTING, DELEGATING))
        # Braces in literals and comments do not end a body early.
        tricky = IMPLEMENTING.replace(
            "        UUID requestId",
            "        char open = '{'; // }\n        String close = \"}\"; /* { */\n        UUID requestId")
        self.assertTrue(self.scan(DELEGATING, tricky))
        self.assertTrue(self.scan(tricky, DELEGATING))

    def test_implementation_without_in_progress_return_fails(self):
        complete = 'return new AccountDeletionStatusView(requestId, "COMPLETE");'
        for implementing in (IMPLEMENTING.replace(IN_PROGRESS, complete),
                             IMPLEMENTING.replace(IN_PROGRESS, f"// {IN_PROGRESS}\n        {complete}")):
            self.assert_both_orders_raise("no longer returns IN_PROGRESS", DELEGATING, implementing)

    def test_overload_that_does_work_fails(self):
        working = DELEGATING.replace(
            "        return requestDeletion", "        audit(principalUserId);\n        return requestDeletion")
        computed = DELEGATING.replace("password, null)", "password, resolveClient())")
        for delegating in (working, computed):
            self.assert_both_orders_raise("does work instead of delegating", delegating, IMPLEMENTING)

    def test_second_implementation_fails(self):
        second = IMPLEMENTING.replace("String clientKey)", "String clientKey, boolean retry)")
        self.assert_both_orders_raise("more than one", second, IMPLEMENTING)

    def test_persist_ack_vocabulary_in_any_overload_fails(self):
        for needle in ("offhost", "watermark", "persist-ack", "publicationId"):
            noted_delegation = DELEGATING.replace("        return", f"        // {needle}\n        return")
            noted_implementation = IMPLEMENTING.replace(
                "        UUID requestId", f"        // {needle}\n        UUID requestId")
            with self.subTest(needle=needle):
                self.assert_both_orders_raise(f"mentions {needle.lower()}", noted_delegation, IMPLEMENTING)
                self.assert_both_orders_raise(f"mentions {needle.lower()}", DELEGATING, noted_implementation)

    def test_missing_method_fails(self):
        renamed = IMPLEMENTING.replace("requestDeletion(UUID principalUserId, String password, String",
                                       "startDeletion(UUID principalUserId, String password, String")
        commented = "    // public AccountDeletionStatusView requestDeletion(UUID id) { return null; }\n"
        for methods in ((), (renamed,), (commented,)):
            with self.subTest(methods=len(methods)), self.assertRaisesRegex(ContractError, "requestDeletion missing"):
                self.scan(*methods)


if __name__ == "__main__":
    unittest.main()