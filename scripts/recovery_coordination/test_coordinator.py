#!/usr/bin/env python3
"""Synthetic lifecycle tests for the recovery coordinator. No live hosts."""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "scripts" / "lib"))
sys.path.insert(0, str(ROOT / "scripts" / "recovery_coordination"))

import offhost_erasure as oe  # noqa: E402
from coordinator import (  # noqa: E402
    HARD_CEILING_SECONDS,
    Coordinator,
    CoordinationError,
    MemoryWriters,
    PauseTimeout,
    Clock,
)
from slack_reconciliation import reconcile_slack_events, ReconciliationError  # noqa: E402

A = "11111111-1111-4111-8111-111111111111"
B = "22222222-2222-4222-8222-222222222222"
T0 = "2026-09-23T03:30:00Z"
T1 = "2026-09-23T18:00:00Z"
CUTOFF = "2026-09-24T04:00:00Z"
STAMP = "2026-09-24T03-30-01Z"


def stamp_entries():
    return [{"authUserId": A, "erasedAt": T0}]


class CoordinatorLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.env = {"PARKIO_RECOVERY_COORDINATOR_ENABLED": "1"}
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.clock = Clock()

    def tearDown(self):
        self.tmp.cleanup()

    def _snapshot_ok(self, backup_id, args):
        dest = Path(args["destination"])
        dest.mkdir(parents=True)
        (dest / "COMPLETE").write_text("ok\n", encoding="utf-8")
        (dest / "id.json").write_text(json.dumps({"gateway_outbox_backup_id": backup_id}),
                                      encoding="utf-8")
        return {"destination": dest, "gateway_outbox_backup_id": backup_id}

    def _verify_ok(self, dest):
        body = json.loads((Path(dest) / "id.json").read_text(encoding="utf-8"))
        return {"status": "verified", "gateway_outbox_backup_id": body["gateway_outbox_backup_id"]}

    def _snapshot_fail(self, backup_id, args):
        dest = Path(args["destination"])
        dest.mkdir(parents=True)
        (dest / "partial").write_text("no\n", encoding="utf-8")
        raise CoordinationError("injected snapshot interruption")

    def coordinator(self, running=("slack_worker", "fluent_bit", "gateway_exporter",
                                   "inbox_consumer", "nr_source", "nr_gate"),
                    snapshot=None, verify=None, prepare=None, erasure=None,
                    writers=None):
        return Coordinator(
            writers or MemoryWriters(running),
            clock=self.clock,
            snapshot=snapshot or self._snapshot_ok,
            verify=verify or self._verify_ok,
            prepare_recovery=prepare,
            erasure_recover=erasure,
            env=self.env,
        )

    def test_disabled_does_not_pause(self):
        writers = MemoryWriters(("slack_worker",))
        coord = Coordinator(writers, snapshot=self._snapshot_ok, env={})
        result = coord.ordinary_snapshot(STAMP, {"destination": self.root / "snap"})
        self.assertEqual(result["verdict"], "DISABLED")
        self.assertEqual(writers.state["slack_worker"], "running")
        self.assertFalse((self.root / "snap").exists())

    def test_successful_ordinary_snapshot(self):
        dest = self.root / "snap"
        coord = self.coordinator()
        result = coord.ordinary_snapshot(STAMP, {"destination": dest})
        self.assertEqual(result["verdict"], "PASS")
        self.assertFalse(result["atomic"])
        self.assertEqual(result["offhostDurability"], "not-established")
        self.assertEqual(result["hardCeilingSeconds"], HARD_CEILING_SECONDS)
        self.assertEqual(result["configuredPauseBudgetSeconds"], 15 * 60)
        self.assertEqual(result["measuredSyntheticPauseSeconds"], 0)
        self.assertIsNone(result["productionPauseSeconds"])
        self.assertFalse(result["productionPauseMeasured"])
        self.assertFalse(result["userFacingRequestsPaused"])
        self.assertFalse(result["remoteUploadImplemented"])
        self.assertEqual(result["liveWriterControl"], "simulated")
        self.assertIn("measuredWallPauseSeconds", result)
        self.assertEqual(result["writersPaused"],
                         ["slack_worker", "fluent_bit", "gateway_exporter",
                          "inbox_consumer", "nr_source", "nr_gate"])
        for name in coord.pre_state:
            self.assertEqual(coord.writers.state[name], "running")

    def test_interrupted_snapshot_restores_pre_state_only(self):
        writers = MemoryWriters(("slack_worker", "gateway_exporter"))
        dest = self.root / "partial-snap"
        coord = self.coordinator(running=("slack_worker", "gateway_exporter"),
                                 snapshot=self._snapshot_fail, writers=writers)
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot(STAMP, {"destination": dest})
        self.assertEqual(writers.state["slack_worker"], "running")
        self.assertEqual(writers.state["gateway_exporter"], "running")
        self.assertEqual(writers.state["fluent_bit"], "stopped")
        self.assertFalse(dest.exists())

    def test_pause_timeout_aborts_and_resumes_pre_state(self):
        clock = self.clock

        class Slow(MemoryWriters):
            def pause(self, name):
                clock.advance(HARD_CEILING_SECONDS + 1)
                super().pause(name)

        writers = Slow(("slack_worker", "nr_gate"))
        dest = self.root / "timeout-snap"
        coord = self.coordinator(running=("slack_worker", "nr_gate"), writers=writers)
        with self.assertRaises(PauseTimeout):
            coord.ordinary_snapshot(STAMP, {"destination": dest})
        self.assertEqual(writers.state["slack_worker"], "running")
        self.assertEqual(writers.state["nr_gate"], "running")
        self.assertEqual(writers.state["fluent_bit"], "stopped")

    def test_safe_resume_does_not_start_previously_stopped(self):
        writers = MemoryWriters(("gateway_exporter",))
        coord = self.coordinator(running=("gateway_exporter",), writers=writers)
        coord.ordinary_snapshot(STAMP, {"destination": self.root / "snap"})
        self.assertEqual(writers.state["gateway_exporter"], "running")
        self.assertEqual(writers.state["slack_worker"], "stopped")
        self.assertEqual(writers.state["fluent_bit"], "stopped")
        self.assertEqual(writers.state["nr_gate"], "stopped")

    def test_mismatched_gateway_identity_fails_closed(self):
        def bad_snapshot(backup_id, args):
            dest = Path(args["destination"])
            dest.mkdir(parents=True)
            return {"destination": dest, "gateway_outbox_backup_id": "other-stamp"}

        coord = self.coordinator(snapshot=bad_snapshot)
        dest = self.root / "mismatch"
        with self.assertRaises(CoordinationError):
            coord.ordinary_snapshot(STAMP, {"destination": dest})
        self.assertFalse(dest.exists())

    def _erasure_pass(self, store, cutoff, stamp):
        return {"verdict": "PASS", "coverageThrough": T1, "reason": None}

    def _erasure_blocked(self, store, cutoff, stamp):
        return {
            "verdict": "BLOCKED",
            "coverageThrough": T1,
            "reason": "erasures that committed after coverageThrough are unknown; "
                      "do not lower recoveryCutoff to obtain PASS",
        }

    def _prepare_ok(self, snapshot_dir, backup_id, events, destination):
        Path(destination).mkdir(parents=True, exist_ok=True)
        return {"status": "manual_reconciliation_required", "resume_slack": False,
                "resume_new_relic": False}

    def test_missing_nr_ledger_blocks_prepare(self):
        def prepare_missing(*_args):
            raise CoordinationError("required state missing")

        coord = self.coordinator(prepare=prepare_missing, erasure=self._erasure_pass)
        with self.assertRaises(CoordinationError):
            coord.disaster_stage(STAMP, [{"eventId": "e1"}], self.root / "snap",
                                 self.root / "staged", "store", T1)

    def test_guarded_recovery_zero_upstream_and_no_fluent_bit(self):
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_pass)
        hold = coord.disaster_stage(STAMP, [{"eventId": "e1"}], self.root / "snap",
                                    self.root / "staged", "store", T1)
        self.assertEqual(hold["verdict"], "HOLD")
        self.assertEqual(hold["nrBudgetRecoveryMode"], "on")
        self.assertEqual(hold["fluentBit"], "stopped")
        self.assertEqual(hold["slackPublishers"], "stopped")
        self.assertFalse(hold["losslessBuffering"])
        self.assertFalse(hold["exposeApplications"])
        ack = coord.acknowledge_without_forward()
        self.assertFalse(ack["forwarded"])
        self.assertEqual(ack["upstreamForwards"], 0)
        with self.assertRaises(CoordinationError):
            coord.release_collection()

    def test_blocked_erasure_cutoff_refuses_exposure(self):
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_blocked)
        hold = coord.disaster_stage(STAMP, [{"eventId": "e1"}], self.root / "snap",
                                    self.root / "staged", "store", CUTOFF)
        self.assertEqual(hold["verdict"], "BLOCKED")
        self.assertFalse(hold["exposeApplications"])
        self.assertTrue(hold["doNotLowerCutoff"])
        self.assertEqual(hold["recoveryCutoff"], CUTOFF)
        self.assertEqual(hold["coverageThrough"], T1)
        self.assertEqual(hold["offhostDurability"], "not-established")
        self.assertEqual(hold["fluentBit"], "stopped")
        self.assertFalse(coord.nr_gate_started)

    def test_offhost_library_cutoff_is_not_lowered(self):
        store = oe.MemoryStore()
        env = {"PARKIO_OFFHOST_ERASURE_ENABLED": "1"}
        oe.persist_complete_snapshot(
            store, stamp_entries() + [{"authUserId": B, "erasedAt": T1}], T1, env,
            visibility_protocol=oe.PROTOCOL_LOCK,
        )
        report = oe.recover(store, CUTOFF, stamp_entries())
        self.assertEqual(report["verdict"], "BLOCKED")
        self.assertEqual(report["recoveryCutoff"], CUTOFF)
        self.assertIn("do not lower recoveryCutoff", report["reason"])

        def recover_lib(unused_store, cutoff, stamp):
            return oe.recover(store, cutoff, stamp)

        coord = self.coordinator(prepare=self._prepare_ok, erasure=recover_lib)
        hold = coord.disaster_stage(STAMP, [{"eventId": "e1"}], self.root / "snap",
                                    self.root / "staged", store, CUTOFF, stamp_entries())
        self.assertEqual(hold["verdict"], "BLOCKED")
        self.assertEqual(hold["recoveryCutoff"], CUTOFF)
        self.assertFalse(hold["exposeApplications"])

    def _clean_slack_report(self, event_id="e1"):
        return reconcile_slack_events([event_id], [(event_id, event_id, "queued")])

    def test_explicit_release_sequence(self):
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_pass)
        coord.disaster_stage(STAMP, [{"eventId": "e1"}], self.root / "snap",
                             self.root / "staged", "store", T1)
        coord.record_reconciliation(nr_spending_reviewed=True)
        with self.assertRaises(CoordinationError):
            coord.release_collection()
        with self.assertRaises(CoordinationError):
            coord.record_reconciliation(nr_spending_reviewed=True, slack_reviewed=True,
                                        release_authorized=True)
        coord.record_reconciliation(nr_spending_reviewed=True, slack_reviewed=True,
                                    release_authorized=True,
                                    slack_event_report=self._clean_slack_report())
        released = coord.release_collection()
        self.assertEqual(released["verdict"], "RELEASED")
        self.assertTrue(coord.fluent_bit_started)
        self.assertTrue(coord.slack_started)

    def test_equal_count_different_event_sets(self):
        report = reconcile_slack_events(["gw-a", "gw-b"],
                                        [("sl-c", "sl-c", "queued"), ("sl-d", "sl-d", "queued")])
        self.assertTrue(report["equal_count_different_sets"])
        self.assertEqual(report["counts"]["gateway_without_queue_or_inbox"], 2)
        self.assertEqual(report["counts"]["slack_without_gateway"], 2)
        self.assertEqual(report["event_ids"]["gateway_without_queue_or_inbox"], ["gw-a", "gw-b"])
        self.assertEqual(report["event_ids"]["slack_without_gateway"], ["sl-c", "sl-d"])
        self.assertFalse(report["identity_and_counts_sufficient"])
        self.assertEqual(report["auto_replay"], [])
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_pass)
        coord.disaster_stage(STAMP, [{"eventId": "gw-a"}], self.root / "snap",
                             self.root / "staged", "store", T1)
        with self.assertRaises(CoordinationError):
            coord.record_reconciliation(slack_reviewed=True, slack_event_report=report)
        accepted = coord.record_reconciliation(slack_reviewed=True, slack_event_report=report,
                                               review_acknowledged=True)
        self.assertTrue(accepted["slack_reviewed"])

    def test_missing_events_and_conflicting_states(self):
        missing = reconcile_slack_events(["keep", "lost"], [("keep", "keep", "queued")])
        self.assertEqual(missing["event_ids"]["gateway_without_queue_or_inbox"], ["lost"])
        self.assertEqual(missing["auto_replay"], [])
        conflict = reconcile_slack_events(
            ["same"],
            [("same", "same", "delivered")],
            pending_ids=["same"],
        )
        self.assertEqual(conflict["event_ids"]["conflicting_states"], ["same"])
        self.assertEqual(conflict["auto_replay"], [])
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_pass)
        coord.disaster_stage(STAMP, [{"eventId": "same"}], self.root / "snap",
                             self.root / "staged", "store", T1)
        with self.assertRaises(CoordinationError):
            coord.record_reconciliation(slack_reviewed=True, slack_event_report=conflict)

    def test_ambiguous_in_flight_is_never_auto_replayed(self):
        report = reconcile_slack_events(
            ["amb-1", "amb-2"],
            [("amb-1", "amb-1", "delivery_unknown"), ("amb-2", "amb-2", "in_flight")],
        )
        self.assertEqual(report["event_ids"]["delivery_unknown"], ["amb-1"])
        self.assertEqual(report["event_ids"]["in_flight"], ["amb-2"])
        self.assertEqual(report["auto_replay"], [])
        self.assertEqual(set(report["replay_refused"]), {"amb-1", "amb-2"})
        self.assertEqual(report["limits"]["dedup_hours"], 168)
        self.assertFalse(report["limits"]["atomic"])
        coord = self.coordinator(prepare=self._prepare_ok, erasure=self._erasure_pass)
        coord.disaster_stage(STAMP, [{"eventId": "amb-1"}], self.root / "snap",
                             self.root / "staged", "store", T1)
        with self.assertRaises(CoordinationError):
            coord.record_reconciliation(slack_reviewed=True, slack_event_report=report,
                                        review_acknowledged=True)
        accepted = coord.record_reconciliation(slack_reviewed=True, slack_event_report=report,
                                               review_acknowledged=True,
                                               ambiguous_acknowledged=True)
        self.assertTrue(accepted["slack_reviewed"])
        self.assertEqual(report["auto_replay"], [])

    def test_dedup_conflict_is_forensic(self):
        with self.assertRaises(ReconciliationError):
            reconcile_slack_events(
                ["a"],
                [("a", "shared", "queued")],
                dedup_rows=[("other", "shared")],
            )

    def test_destroyed_sqlite_header_is_not_masked_by_wal(self):
        from operational_state_backup import state_backup as backup
        db = self.root / "slack_biz.sqlite3"
        db.write_bytes(backup.SQLITE_HEADER + b"\x00" * 84)
        (self.root / "slack_biz.sqlite3-wal").write_bytes(b"leftover-wal")
        db.write_bytes(b"not SQLite")
        with self.assertRaises(backup.SnapshotError):
            backup.assert_sqlite_header(db, "slack")
        dest = self.root / "copy.sqlite3"
        with self.assertRaises(backup.SnapshotError):
            backup.sqlite_backup(db, dest, "slack")
        self.assertFalse(dest.exists())

    def test_staged_sqlite_sidecars_are_not_a_domain(self):
        from operational_state_backup import state_backup as backup
        stage = self.root / "stage"
        (stage / "slack").mkdir(parents=True)
        (stage / "slack" / "slack_biz.sqlite3").write_bytes(backup.SQLITE_HEADER)
        (stage / "slack" / "slack_biz.sqlite3-wal").write_bytes(b"sidecar")
        (stage / "slack" / "slack_biz.sqlite3-shm").write_bytes(b"sidecar")
        names = [path.relative_to(stage).as_posix() for path in backup.staged_regular_files(stage)]
        self.assertEqual(names, ["slack/slack_biz.sqlite3"])
        backup.unlink_sqlite_sidecars(stage / "slack" / "slack_biz.sqlite3")
        self.assertFalse((stage / "slack" / "slack_biz.sqlite3-wal").exists())
        self.assertFalse((stage / "slack" / "slack_biz.sqlite3-shm").exists())

    def test_individual_pr_helpers_still_present(self):
        self.assertTrue((ROOT / "scripts/operational_state_backup/state_backup.py").is_file())
        self.assertTrue((ROOT / "scripts/newrelic_log_pilot/test_budget_recovery_guard.py").is_file())
        self.assertTrue((ROOT / "scripts/test_offhost_erasure_recovery.py").is_file())


if __name__ == "__main__":
    unittest.main()
