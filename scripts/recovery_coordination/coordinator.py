#!/usr/bin/env python3
"""Coordinate ordinary operational snapshots and disaster-recovery holds.

Production activation stays off unless an operator sets the documented flags.
This module does not talk to Slack, New Relic, Azure, or live hosts.

Pause writers (in-process simulation only), pair a gateway PostgreSQL backup
identity with an operational snapshot, and reconcile event IDs. SQLite, inbox
files, and the gateway outbox are not one atomic cut. Aggregate counts are
not sufficient.

Ordinary backup: on failure, restore only the pre-existing running set. Never
start a writer that was already stopped.

Disaster recovery: Fluent Bit and Slack stay stopped. Stage and verify state,
then start only a guarded NR budget gate (PARKIO_NR_BUDGET_RECOVERY_MODE=on).
An exhausted gate may acknowledge logs without forwarding; that is not lossless
buffering. Collection and Slack resume only after explicit reconciliation and
a documented release. Erasure cutoff refusal is not weakened: unknown commits
after the last verified watermark block exposing recovered applications.
Directory FileStore is never treated as off-host durability.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import sys

try:
    from recovery_coordination.slack_reconciliation import (
        ReconciliationError,
        assert_no_automatic_replay,
    )
except ImportError:  # script / test path layout
    from slack_reconciliation import (  # type: ignore
        ReconciliationError,
        assert_no_automatic_replay,
    )

# Design budget and abort ceiling only. Production pause duration is unmeasured.
# Synthetic tests use an injected Clock; elapsed success-path time is 0 unless
# a test advances it. Do not treat 15 minutes as a measured expectation.
CONFIGURED_PAUSE_BUDGET_SECONDS = 15 * 60
HARD_CEILING_SECONDS = 20 * 60
EXPECTED_PAUSE_SECONDS = CONFIGURED_PAUSE_BUDGET_SECONDS  # alias; not a measurement

WRITERS = (
    "slack_worker",
    "fluent_bit",
    "gateway_exporter",
    "inbox_consumer",
    "nr_source",
    "nr_gate",
)
PAUSE_ORDER = (
    "slack_worker",
    "fluent_bit",
    "gateway_exporter",
    "inbox_consumer",
    "nr_source",
    "nr_gate",
)
RESUME_ORDER = (
    "nr_gate",
    "fluent_bit",
    "nr_source",
    "slack_worker",
    "inbox_consumer",
    "gateway_exporter",
)
DR_MUST_STAY_STOPPED = frozenset({"fluent_bit", "slack_worker"})


class CoordinationError(Exception):
    """Fail-closed coordination."""


class PauseTimeout(CoordinationError):
    """Writer pause exceeded the hard ceiling."""


def enabled(env=None):
    env = os.environ if env is None else env
    return env.get("PARKIO_RECOVERY_COORDINATOR_ENABLED", "0") == "1"


class MemoryWriters:
    """In-process writer inventory for tests. No systemd, no Docker."""

    def __init__(self, running=None):
        self.state = {name: "stopped" for name in WRITERS}
        for name in running or ():
            self.state[name] = "running"
        self.paused = []
        self.resumed = []

    def inventory(self):
        return dict(self.state)

    def pause(self, name):
        if name not in WRITERS:
            raise CoordinationError("unknown writer")
        self.state[name] = "stopped"
        self.paused.append(name)

    def resume(self, name):
        if name not in WRITERS:
            raise CoordinationError("unknown writer")
        self.state[name] = "running"
        self.resumed.append(name)


class Clock:
    def __init__(self, start=0):
        self.now = start

    def time(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def _public(payload):
    blocked = {"entries", "merged", "events", "payload", "authUserId"}
    return {key: value for key, value in payload.items() if key not in blocked}


class Coordinator:
    def __init__(self, writers, clock=None, pause_seconds=CONFIGURED_PAUSE_BUDGET_SECONDS,
                 hard_ceiling=HARD_CEILING_SECONDS, snapshot=None, verify=None,
                 prepare_recovery=None, erasure_recover=None, env=None):
        self.writers = writers
        self.clock = clock or Clock()
        self.pause_seconds = pause_seconds
        self.hard_ceiling = hard_ceiling
        self.snapshot = snapshot
        self.verify = verify
        self.prepare_recovery = prepare_recovery
        self.erasure_recover = erasure_recover
        self.env = os.environ if env is None else env
        self.pre_state = {}
        self.artifacts = []
        self.reconciliation = {
            "complete": False,
            "nr_spending_reviewed": False,
            "slack_reviewed": False,
            "release_authorized": False,
        }
        self.nr_recovery_mode = "off"
        self.nr_gate_started = False
        self.fluent_bit_started = False
        self.slack_started = False
        self.upstream_forwards = 0

    def _record_pre_state(self):
        self.pre_state = self.writers.inventory()
        return dict(self.pre_state)

    def _pause_running(self, started_at):
        for name in PAUSE_ORDER:
            if self.pre_state.get(name) == "running":
                if self.clock.time() - started_at > self.hard_ceiling:
                    raise PauseTimeout("writer pause exceeded hard ceiling")
                self.writers.pause(name)
        elapsed = self.clock.time() - started_at
        if elapsed > self.hard_ceiling:
            raise PauseTimeout("writer pause exceeded hard ceiling")
        return elapsed

    def _resume_preexisting(self):
        """Resume only writers that were running. Never enable a stopped one."""
        for name in RESUME_ORDER:
            if self.pre_state.get(name) == "running":
                self.writers.resume(name)

    def _discard_artifacts(self):
        for path in self.artifacts:
            target = Path(path)
            if target.is_file():
                target.unlink(missing_ok=True)
            elif target.is_dir():
                for child in sorted(target.rglob("*"), reverse=True):
                    if child.is_file():
                        child.unlink()
                    elif child.is_dir():
                        child.rmdir()
                if target.exists():
                    target.rmdir()
        self.artifacts = []

    def ordinary_snapshot(self, gateway_backup_id, snapshot_args):
        """Pair a gateway stamp identity with an operational snapshot. Default-off."""
        if not enabled(self.env):
            return {"verdict": "DISABLED", "coverageAdvanced": False}
        started = self.clock.time()
        self._record_pre_state()
        try:
            self._pause_running(started)
            result = self.snapshot(gateway_backup_id, snapshot_args)
            if result.get("destination"):
                self.artifacts.append(result["destination"])
            if result.get("gateway_outbox_backup_id") != gateway_backup_id:
                raise CoordinationError("operational snapshot backup identity mismatch")
            verified = self.verify(result["destination"]) if self.verify else {"status": "verified"}
            if verified.get("status") != "verified":
                raise CoordinationError("operational snapshot verify failed")
            if verified.get("gateway_outbox_backup_id") not in (None, gateway_backup_id):
                raise CoordinationError("verified snapshot backup identity mismatch")
            self._resume_preexisting()
            return _public({
                "verdict": "PASS",
                "mode": "ordinary",
                "gateway_outbox_backup_id": gateway_backup_id,
                "atomic": False,
                "domains": ["gateway-postgres", "slack-sqlite", "slack-inbox", "nr-budget",
                            "nr-source", "nr-collector"],
                "configuredPauseBudgetSeconds": self.pause_seconds,
                "hardCeilingSeconds": self.hard_ceiling,
                "measuredSyntheticPauseSeconds": self.clock.time() - started,
                "productionPauseSeconds": None,
                "productionPauseMeasured": False,
                "writersPaused": [n for n in PAUSE_ORDER if self.pre_state.get(n) == "running"],
                "userFacingRequestsPaused": False,
                "encryptionDuringPause": True,
                "remoteUploadImplemented": False,
                "writesAfterResumeExcludedFromArchive": True,
                "resumed": [n for n in RESUME_ORDER if self.pre_state.get(n) == "running"],
                "leftStopped": [n for n in WRITERS if self.pre_state.get(n) != "running"],
                "offhostDurability": "not-established",
                "liveWriterControl": "NOT_IMPLEMENTED",
            })
        except Exception:
            dest = snapshot_args.get("destination")
            if dest:
                self.artifacts.append(dest)
            self._discard_artifacts()
            self._resume_preexisting()
            raise

    def disaster_stage(self, gateway_backup_id, outbox_events, snapshot_dir, destination,
                       erasure_store, recovery_cutoff, stamp_entries=None):
        """Stage DR hold. Does not start Fluent Bit or Slack."""
        if not enabled(self.env):
            return {"verdict": "DISABLED"}
        if self.erasure_recover is None:
            raise CoordinationError("erasure recover is required for disaster staging")
        erasure = self.erasure_recover(erasure_store, recovery_cutoff, stamp_entries or [])
        if erasure.get("verdict") != "PASS":
            return _public({
                "verdict": "BLOCKED",
                "exposeApplications": False,
                "reason": erasure.get("reason") or "erasure cutoff coverage not established",
                "recoveryCutoff": recovery_cutoff,
                "coverageThrough": erasure.get("coverageThrough"),
                "doNotLowerCutoff": True,
                "offhostDurability": "not-established",
                "fluentBit": "stopped",
                "slackPublishers": "stopped",
            })
        prepared = self.prepare_recovery(
            snapshot_dir, gateway_backup_id, outbox_events, destination
        )
        self.nr_recovery_mode = "on"
        self.nr_gate_started = True
        self.fluent_bit_started = False
        self.slack_started = False
        self.reconciliation = {
            "complete": False,
            "nr_spending_reviewed": False,
            "slack_reviewed": False,
            "release_authorized": False,
        }
        return _public({
            "verdict": "HOLD",
            "mode": "disaster",
            "exposeApplications": False,
            "nrBudgetRecoveryMode": "on",
            "nrGate": "started-guarded",
            "fluentBit": "stopped",
            "slackPublishers": "stopped",
            "exhaustedAckWithoutForward": True,
            "losslessBuffering": False,
            "upstreamForwards": self.upstream_forwards,
            "reconciliationComplete": False,
            "gateway_outbox_backup_id": gateway_backup_id,
            "erasureVerdict": "PASS",
            "recoveryCutoff": recovery_cutoff,
            "coverageThrough": erasure.get("coverageThrough"),
            "doNotLowerCutoff": True,
            "offhostDurability": "not-established",
            "prepareStatus": prepared.get("status"),
        })

    def acknowledge_without_forward(self):
        """Exhausted guarded gate may ack; this is not a buffer and not a forward."""
        if self.nr_recovery_mode != "on" or not self.nr_gate_started:
            raise CoordinationError("guarded NR gate is not in recovery hold")
        if self.fluent_bit_started:
            raise CoordinationError("Fluent Bit must stay stopped during the hold")
        return {"accepted": False, "budget_exhausted": True, "forwarded": False,
                "losslessBuffering": False, "upstreamForwards": self.upstream_forwards}

    def record_reconciliation(self, nr_spending_reviewed=False, slack_reviewed=False,
                              release_authorized=False, slack_event_report=None,
                              review_acknowledged=False, ambiguous_acknowledged=False):
        """Operator marks review complete. Event-level Slack report is required.

        Never treats backup identity or aggregate counts as sufficient.
        Never automatically replays delivery-ambiguous events.
        """
        if slack_reviewed:
            if not slack_event_report:
                raise CoordinationError("event-level Slack report is required; counts are not enough")
            try:
                assert_no_automatic_replay(slack_event_report)
            except ReconciliationError as exc:
                raise CoordinationError(str(exc)) from exc
            if slack_event_report.get("identity_and_counts_sufficient"):
                raise CoordinationError("backup identity and aggregate counts are not sufficient")
            ids = slack_event_report.get("event_ids") or {}
            if slack_event_report.get("equal_count_different_sets") and not review_acknowledged:
                raise CoordinationError("equal-count different event sets require explicit review")
            if (ids.get("gateway_without_queue_or_inbox") or ids.get("slack_without_gateway")) \
                    and not review_acknowledged:
                raise CoordinationError("missing or extra event IDs require explicit review")
            if ids.get("conflicting_states") and not review_acknowledged:
                raise CoordinationError("conflicting Slack states require explicit review")
            if slack_event_report.get("replay_refused") and not ambiguous_acknowledged:
                raise CoordinationError(
                    "delivery-ambiguous events cannot be auto-replayed; acknowledge them first"
                )
        self.reconciliation["nr_spending_reviewed"] = bool(nr_spending_reviewed)
        self.reconciliation["slack_reviewed"] = bool(slack_reviewed)
        self.reconciliation["release_authorized"] = bool(release_authorized)
        self.reconciliation["complete"] = all((
            self.reconciliation["nr_spending_reviewed"],
            self.reconciliation["slack_reviewed"],
            self.reconciliation["release_authorized"],
        ))
        return dict(self.reconciliation)

    def release_collection(self):
        """Documented release after reconciliation. Still default-off in production."""
        if not self.reconciliation["complete"]:
            raise CoordinationError("spending reconciliation is incomplete")
        if self.nr_recovery_mode != "on":
            raise CoordinationError("release requires a prior guarded recovery hold")
        self.nr_recovery_mode = "off"
        self.fluent_bit_started = True
        self.slack_started = True
        return {
            "verdict": "RELEASED",
            "fluentBit": "started",
            "slackPublishers": "started",
            "nrBudgetRecoveryMode": "off",
            "note": "synthetic release only; production remains disabled",
        }


def public_result(payload):
    return _public(payload)


def main(argv=None):
    parser_env = os.environ
    if not enabled(parser_env):
        print(json.dumps({"verdict": "DISABLED", "reason": "PARKIO_RECOVERY_COORDINATOR_ENABLED is not 1"}))
        return 2
    print(json.dumps({"verdict": "FAIL", "reason": "use the Python API or tests; no live control"}))
    return 1


if __name__ == "__main__":
    sys.exit(main())
