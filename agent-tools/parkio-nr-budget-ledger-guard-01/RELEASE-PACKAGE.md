# NR existing-ledger startup guard — draft release preparation

## Decision and scope

This is a separate narrow follow-up to draft PR #101, based on `origin/api` at `aa865a255564464bed207a9061244af2641edd3d`. PR #101 remains unchanged and draft; Cursor's #102 and off-host erasure recovery are outside this branch. Only the real `budget_gate.py`, a synthetic focused test, this new runbook and this package change. Production Compose, pins, systemd units, env files, mounts, shared backup scripts/workflows and live state are unchanged.

**Source-only preparation: PASS. Production integration, real ledger restoration and forwarding release: NOT PERFORMED.** Keep both PRs draft. Integration order is PR #101 snapshot tooling, this gate change, then a separately approved coordinator/configuration/restore drill. Neither draft should be described as a production backup or active guard.

## Why this change

The existing `Budget.__init__()` in `scripts/newrelic_log_pilot/budget_gate.py` creates a new SQLite file and zeroed accounting if its path is missing. A recovered ledger staged by #101 is closed with `exhausted=1`, and `Budget.reserve()` already respects that bit. But a missing or wrong bind path could silently grant fresh daily/monthly budget. The new default-off `PARKIO_NR_BUDGET_RECOVERY_MODE=on` makes startup require an existing compatible, intact, exhausted ledger and blocks all forwarding until an explicit later release. Normal first-install behavior remains unchanged with the flag `off`.

On an active guarded process, every database open uses SQLite `mode=rw` and checks the original file identity. Missing/replaced state, SQLite failure or a cleared exhausted bit returns HTTP 503 before upstream forwarding. Day/month rollover and restart leave the persistent total exhausted bit in effect. An already in-flight request during external file removal cannot be recalled; the later coordinator must stop the collector and account for unknown attempts before ledger manipulation. This PR does not alter that service ordering.

## Focused evidence

`PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest scripts/newrelic_log_pilot/test_budget_recovery_guard.py -v`: **5 tests passed** on synthetic SQLite fixtures and a localhost mock upstream. Cases: default-off first install and one mock forward; startup refusal for missing/unreadable/corrupt/incompatible/nonexhausted/symlink state; exhausted guard across restart and UTC day/month rollover; disappearing/replaced ledger while running returns 503; external exhausted-bit clear and test reset cannot release the guard. Guarded cases recorded **zero mock upstream requests**.

`PYTHONDONTWRITEBYTECODE=1 python3 scripts/newrelic_log_pilot/run_continuous_unit_validation.py`: **7/7 passed**, covering existing daily/monthly budget behavior and the local mock gate path. No Docker or real New Relic call was used. Final-head CI status is recorded in the draft PR once terminal.

## Later integration, rollback and remaining gates

The dedicated [recovery guard runbook](../../docs/operations/new-relic-budget-recovery-guard.md) specifies opt-in configuration, exact bind-path validation, accounting reconciliation, closed-state verification, explicit release and rollback. A later authorized integration must set the flag on the dedicated gate only after placing the verified #101 ledger at the actual mount, prove a missing mount fails before forwarding, and run an isolated restore drill. Unknown post-snapshot spending means the guard remains on and forwarding remains disabled. To release after reconciliation, preserve the exhausted ledger, adjust only an audited offline copy with proven total/day/month headroom, verify it, preflight the exact mount, then disable recovery mode and start under the approved procedure. Normal mode retains first-install creation; the release coordinator must reject a missing file before switching it off.

Rollback of this source-only PR is to leave the opt-in flag unset or revert this change. If a future guarded deployment fails, keep the collector stopped and return to the preserved exhausted ledger with the flag on. Do not clear the ledger or rely on a fresh empty database. No merge, deploy, service restart, production access, backup or external notification occurred in this preparation.
