#!/usr/bin/env python3
"""Focused tests for the isolated persist-before-durable-ACK slice.

Classification:
- This file: model/unit (directory store). Survives process crash in-tempdir.
  Not a real object-store service and not off-host/WORM.
- scripts/test-restore-safe-preflight.sh: real restore-entrypoint with stubs.
- Production COMPLETE path is asserted unchanged and disabled by default.

Depends on #118 contract helpers. Does not enable verifiedCoverage.
"""
from __future__ import annotations

import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/lib"))

from recovery_evidence_contract import (  # noqa: E402
    AckRefused,
    ContractError,
    ExposeRefused,
    PersistFailed,
    REQUIRED_PARTICIPANTS,
    persist_before_ack_is_not_implemented,
    restore_erasure_ledger_still_unverified,
)
from recovery_persist_protocol import (  # noqa: E402
    FRONTIER_KEY,
    IsolatedErasureCoordinator,
    IsolatedVersionedStore,
    SequenceAllocator,
    erasure_record_id,
    production_durable_recording_is_disabled,
    recover_latest_trusted,
)

ERASED = "00000000-0000-4000-a000-0000000000a1"
OTHER = "00000000-0000-4000-a000-0000000000b2"
REQ1 = "11111111-1111-4111-8111-111111111111"
REQ2 = "22222222-2222-4222-8222-222222222222"
REQ3 = "33333333-3333-4333-8333-333333333333"
THIRD = "00000000-0000-4000-a000-0000000000c3"
DB = "auth-db:isolated-fixture"
PRODUCER = "fixture-producer"
KEY = b"parkio-isolated-persist-slice-not-prod"
ATTEMPT = "recovery-attempt-1"
DATASET = "dataset-stamp-s"
DIGEST = "digest-ok"


class PersistProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = IsolatedVersionedStore(Path(self.tmp.name) / "store")
        self.keys = {PRODUCER: KEY}
        self.coord = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY)

    def tearDown(self):
        self.tmp.cleanup()

    def _ack_all(self, request_id):
        expected = {
            "recoveryAttemptId": ATTEMPT,
            "restoredDatasetId": DATASET,
            "erasureSetDigest": DIGEST,
        }
        for name in REQUIRED_PARTICIPANTS:
            self.coord.ack_participant(request_id, name, ATTEMPT, DATASET, DIGEST, expected)
        return self.coord.try_complete(request_id, DIGEST)

    def test_production_defaults_unchanged(self):
        self.assertTrue(restore_erasure_ledger_still_unverified(ROOT))
        self.assertTrue(persist_before_ack_is_not_implemented(ROOT))
        self.assertTrue(production_durable_recording_is_disabled(ROOT))

    def test_public_status_stays_in_progress_until_complete(self):
        view = self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(view["publicStatus"], "IN_PROGRESS")
        self.assertEqual(view["recording"], "DURABLY_RECORDED")
        self.assertEqual(self.coord.internal(REQ1)["publicStatus"], "IN_PROGRESS")

    def test_stable_identity_and_persist_before_durable(self):
        view = self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(view["recording"], "DURABLY_RECORDED")
        self.assertEqual(view["sequence"], 1)
        self.assertTrue(self.store.exists(erasure_record_id(REQ1)))
        again = self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(again["sequence"], 1)

    def test_persist_failure_stays_pending_and_cannot_complete(self):
        failing = IsolatedVersionedStore(Path(self.tmp.name) / "fail", fail_on_prefix="records/")
        coord = IsolatedErasureCoordinator(failing, DB, PRODUCER, KEY)
        with self.assertRaises(PersistFailed):
            coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        row = coord.internal(REQ1)
        self.assertEqual(row["publicStatus"], "IN_PROGRESS")
        self.assertEqual(row["recording"], "PENDING_DURABLE")
        self.assertFalse(failing.exists(erasure_record_id(REQ1)))
        recovered = recover_latest_trusted(failing, DB, self.keys, required_through_sequence=None)
        self.assertIsNone(recovered["latestTrustedSequence"])
        self.assertEqual(recovered["verdict"], "BLOCKED")
        self.assertEqual(recovered["abandonedReservations"], [1])
        expected = {
            "recoveryAttemptId": ATTEMPT,
            "restoredDatasetId": DATASET,
            "erasureSetDigest": DIGEST,
        }
        for name in REQUIRED_PARTICIPANTS:
            coord.ack_participant(REQ1, name, ATTEMPT, DATASET, DIGEST, expected)
        with self.assertRaises(AckRefused):
            coord.try_complete(REQ1, DIGEST)

    def test_ambiguous_retry_reuses_identity_without_conflict(self):
        view = self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.coord.crash_forget_memory()
        with self.assertRaises(ContractError):
            self.coord.internal(REQ1)
        retry = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY)
        again = retry.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(again["sequence"], view["sequence"])
        self.assertEqual(again["recording"], "DURABLY_RECORDED")
        self.assertEqual(len(self.store.list_prefix("records/")), 1)

    def test_host_loss_recovers_only_store_evidence(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.coord.publish_checkpoint(1, [{"authUserId": ERASED, "erasedAt": "2026-09-27T10:00:00Z"}])
        self.coord.crash_forget_memory()
        recovered = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY).recover_from_store(self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["latestTrustedSequence"], 1)
        self.assertEqual(recovered["expectedThrough"], 1)
        self.assertFalse(recovered["certifiedOffHostWorm"])
        self.assertEqual(recovered["durabilityClass"], "process-crash-local")
        self.assertEqual(recovered["pending"][0]["authUserId"], ERASED)
        lost = IsolatedVersionedStore(Path(self.tmp.name) / "empty-host")
        empty = recover_latest_trusted(lost, DB, self.keys)
        self.assertEqual(empty["verdict"], "UNKNOWN")
        self.assertIsNone(empty["latestTrustedSequence"])
        self.assertFalse(empty["completenessEstablished"])
        with self.assertRaises(ContractError) as ctx:
            recover_latest_trusted(lost, DB, self.keys, required_through_sequence=1)
        self.assertIn("BLOCKED", str(ctx.exception))

    def test_sequence_allocation_and_concurrent_publication(self):
        allocated = []

        def publish(user, req):
            view = self.coord.request_deletion(user, req, "2026-09-27T10:00:00Z")
            allocated.append(view["sequence"])

        workers = [
            threading.Thread(target=publish, args=(ERASED, REQ1)),
            threading.Thread(target=publish, args=(OTHER, REQ2)),
        ]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertEqual(sorted(allocated), [1, 2])
        self.assertEqual(len(set(allocated)), 2)
        allocator = SequenceAllocator(self.store)
        self.assertEqual(allocator.highest_allocated(), 2)

    def test_gap_is_not_completeness_and_unknown_tail_blocks(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        other_store = IsolatedVersionedStore(Path(self.tmp.name) / "gapped")
        other = IsolatedErasureCoordinator(other_store, DB, PRODUCER, KEY)
        other.allocator.allocate(REQ1)
        other.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        self.assertEqual(other.internal(REQ2)["sequence"], 2)
        recovered = recover_latest_trusted(other_store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "BLOCKED")
        self.assertEqual(recovered["gaps"], [1])
        self.assertEqual(recovered["expectedThrough"], 2)
        self.assertIsNone(recovered["latestTrustedSequence"])
        self.assertEqual(other.internal(REQ2)["sequence"], 2)
        with self.assertRaises(ContractError) as ctx:
            recover_latest_trusted(other_store, DB, self.keys, required_through_sequence=2)
        self.assertIn("BLOCKED", str(ctx.exception))

    def test_complete_requires_durable_and_all_participant_acks(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        with self.assertRaises(ExposeRefused):
            self.coord.try_complete(REQ1, DIGEST)
        done = self._ack_all(REQ1)
        self.assertEqual(done["publicStatus"], "COMPLETE")
        self.assertEqual(done["recording"], "DURABLY_RECORDED")

    def test_disabled_protocol_matches_production_complete_without_persist(self):
        coord = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY, enabled=False)
        view = coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(view["publicStatus"], "IN_PROGRESS")
        self.assertEqual(view["recording"], "PENDING_DURABLE")
        self.assertFalse(self.store.exists(erasure_record_id(REQ1)))
        expected = {
            "recoveryAttemptId": ATTEMPT,
            "restoredDatasetId": DATASET,
            "erasureSetDigest": DIGEST,
        }
        for name in REQUIRED_PARTICIPANTS:
            coord.ack_participant(REQ1, name, ATTEMPT, DATASET, DIGEST, expected)
        last = coord.try_complete(REQ1, DIGEST)
        self.assertEqual(last["publicStatus"], "COMPLETE")

    def test_missing_highest_record_is_not_certified_by_contiguous_prefix(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.coord.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        self.coord.request_deletion(THIRD, REQ3, "2026-09-27T12:00:00Z")
        (self.store.root / erasure_record_id(REQ3)).unlink()
        recovered = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "BLOCKED")
        self.assertFalse(recovered["completenessEstablished"])
        self.assertEqual(recovered["expectedThrough"], 3)
        self.assertEqual(recovered["listedMaximumSequence"], 2)
        self.assertEqual(recovered["gaps"], [3])
        self.assertIsNone(recovered["latestTrustedSequence"])
        self.assertEqual(recovered["pending"], [])
        with self.assertRaises(ContractError) as ctx:
            recover_latest_trusted(self.store, DB, self.keys, required_through_sequence=3)
        self.assertIn("BLOCKED", str(ctx.exception))

    def test_lost_reservation_before_publication_blocks_required_sequence(self):
        failing = IsolatedVersionedStore(Path(self.tmp.name) / "lost-res", fail_on_prefix="records/")
        coord = IsolatedErasureCoordinator(failing, DB, PRODUCER, KEY)
        with self.assertRaises(PersistFailed):
            coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertTrue(failing.exists("sequences/0000000000000001.json"))
        self.assertFalse(failing.exists(erasure_record_id(REQ1)))
        recovered = recover_latest_trusted(failing, DB, self.keys)
        self.assertEqual(recovered["verdict"], "BLOCKED")
        self.assertEqual(recovered["abandonedReservations"], [1])
        self.assertEqual(recovered["expectedThrough"], 0)
        with self.assertRaises(ContractError) as ctx:
            recover_latest_trusted(failing, DB, self.keys, required_through_sequence=1)
        self.assertIn("BLOCKED", str(ctx.exception))

    def test_concurrent_writers_and_conflicting_retry_payloads(self):
        allocated = []
        errors = []

        def publish(user, req, erased_at):
            try:
                view = self.coord.request_deletion(user, req, erased_at)
                allocated.append((req, view["sequence"]))
            except Exception as exc:  # noqa: BLE001 — capture worker failures
                errors.append(exc)

        workers = [
            threading.Thread(target=publish, args=(ERASED, REQ1, "2026-09-27T10:00:00Z")),
            threading.Thread(target=publish, args=(OTHER, REQ2, "2026-09-27T11:00:00Z")),
        ]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertEqual(errors, [])
        self.assertEqual(sorted(seq for _, seq in allocated), [1, 2])
        retry = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY)
        again = retry.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.assertEqual(again["sequence"], dict(allocated)[REQ1])
        recovered = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["expectedThrough"], 2)
        with self.assertRaises(ContractError) as ctx:
            IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY).request_deletion(
                ERASED, REQ1, "2026-09-27T13:00:00Z",
            )
        self.assertIn("conflicting", str(ctx.exception))

    def test_losing_local_high_water_marks_uses_only_store_frontier(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.coord.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        self.coord.crash_forget_memory()
        recovered = IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY).recover_from_store(self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["expectedThrough"], 2)
        self.assertEqual(recovered["latestTrustedSequence"], 2)
        (self.store.root / FRONTIER_KEY).unlink()
        unknown = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(unknown["verdict"], "UNKNOWN")
        self.assertFalse(unknown["completenessEstablished"])
        self.assertEqual(unknown["listedMaximumSequence"], 2)
        self.assertIsNone(unknown["latestTrustedSequence"])
        self.assertEqual(unknown["pending"], [])

    def test_crash_after_record_before_frontier_does_not_durably_ack(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        original = self.coord.advance_frontier

        def fail_expected_through(*args, **kwargs):
            if kwargs.get("expected_through") is not None:
                raise PersistFailed("frontier update failed")
            return original(*args, **kwargs)

        self.coord.advance_frontier = fail_expected_through
        with self.assertRaises(PersistFailed):
            self.coord.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        row = self.coord.internal(REQ2)
        self.assertEqual(row["recording"], "PENDING_DURABLE")
        self.assertTrue(self.store.exists(erasure_record_id(REQ2)))
        recovered = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["expectedThrough"], 1)
        self.assertEqual(recovered["listedMaximumSequence"], 2)
        self.assertEqual([item["erasureRequestId"] for item in recovered["pending"]], [REQ1])
        with self.assertRaises(AckRefused):
            expected = {
                "recoveryAttemptId": ATTEMPT,
                "restoredDatasetId": DATASET,
                "erasureSetDigest": DIGEST,
            }
            for name in REQUIRED_PARTICIPANTS:
                self.coord.ack_participant(REQ2, name, ATTEMPT, DATASET, DIGEST, expected)
            self.coord.try_complete(REQ2, DIGEST)

    def test_old_signed_frontier_does_not_certify_listing_max(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        old_frontier = (self.store.root / FRONTIER_KEY).read_bytes()
        self.coord.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        (self.store.root / FRONTIER_KEY).write_bytes(old_frontier)
        recovered = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["expectedThrough"], 1)
        self.assertEqual(recovered["listedMaximumSequence"], 2)
        self.assertEqual(recovered["latestTrustedSequence"], 1)
        self.assertEqual(len(recovered["pending"]), 1)

    def test_stale_and_concurrent_frontier_updates_never_decrease(self):
        self.coord.request_deletion(ERASED, REQ1, "2026-09-27T10:00:00Z")
        self.coord.request_deletion(OTHER, REQ2, "2026-09-27T11:00:00Z")
        errors = []

        def stale_retry():
            try:
                IsolatedErasureCoordinator(self.store, DB, PRODUCER, KEY).advance_frontier(
                    expected_through=1, highest_reserved=1,
                )
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        workers = [threading.Thread(target=stale_retry) for _ in range(8)]
        for worker in workers:
            worker.start()
        for worker in workers:
            worker.join()
        self.assertEqual(errors, [])
        recovered = recover_latest_trusted(self.store, DB, self.keys)
        self.assertEqual(recovered["verdict"], "ACCEPT_ISOLATED")
        self.assertEqual(recovered["expectedThrough"], 2)
        frontier = json.loads((self.store.root / FRONTIER_KEY).read_text(encoding="utf-8"))
        self.assertEqual(frontier["expectedThrough"], 2)
        self.assertGreaterEqual(frontier["highestReserved"], 2)


if __name__ == "__main__":
    unittest.main()