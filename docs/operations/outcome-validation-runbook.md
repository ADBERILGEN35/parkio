# Outcome Validation Runbook

## Purpose

Operate the internal outcome-validation pipeline introduced by WP-05.10A.

## Signals to watch

- `parkio:outcome:evaluation_success_rate5m`
- `parkio:outcome:history_append_success_rate5m`
- `parkio:outcome:duplicate_trigger_rate5m`
- `parkio_parking_outcome_replay_mismatch_total`
- `parkio_parking_outcome_scheduler_failed_total`

## Triage flow

1. If append failure rate rises, inspect `parking-service` logs around `OutcomeValidationApplicationService.process(...)` and `OutcomeHistoryRepositoryAdapter.append(...)`.
2. If scheduler failures rise, inspect `OutcomeValidationTriggerJob.processPendingTriggers()` and DB health for `outcome_evaluation_triggers` claims.
3. If duplicate-trigger rate spikes, compare lifecycle callers in `ParkingApplicationService.enqueueOutcomeTrigger(...)` to trigger references and cutoff semantics.
4. If replay mismatches appear, treat them as policy/schema drift incidents and inspect the stored `policy_version` and `snapshot_schema_version` before redrive or replay experiments.

## Safety rules

- Do not rewrite `outcome_history` rows.
- Do not delete `outcome_evaluation_triggers` rows to clear symptoms.
- Do not couple outcome failures back into claim/verify/publication transactions.
- Do not expose raw outcome history over public endpoints.