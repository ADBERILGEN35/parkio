# BRR-01 evidence: backup and restore readiness (2026-09-23)

Base: `origin/api` @ `1fd02394`. Branch: `docs/backup-restore-readiness`.
Deliverables: `docs/operations/backup-restore-readiness.md`,
`docs/operations/restore-drill-01-isolated-database.md`, and three helpers under `scripts/lib/restore-*.py`.

## Production access

**UNAVAILABLE.** The agent's permission policy denied a single bounded read-only
`ssh parkio-civo-prod 'hostname; id -un'` probe, and denied reading local SSH configuration.
No command reached the production host, the Azure backup account or any production
database. Host facts cited in the docs are dated and come from the 2026-09-22 read-only
inventory (waitlist release prep 02).

## Commands run (all local or GitHub read-only)

- `git fetch`, `git worktree add` (new worktree `parkio-wt-backup-restore`)
- repository reads (`grep`, `sed`, `cat`) of compose files, backup/restore scripts, runbooks,
  relay and New Relic gate code
- `gh api …/actions/workflows/backup-restore-drill.yml/runs?event=schedule|workflow_dispatch`
  and `gh api …/actions/jobs/<id>/logs`, filtered to error lines
- `python3 scripts/test_restore_readiness.py -v`: 31/31 OK ([log](test-restore-readiness.txt))
- `bash -n` on every bash block in the drill runbook: 6/6 OK

Not run: Docker (the shared daemon was deliberately untouched), any restore, any backup, any
Azure call. The drill §5 loop is syntax-checked only; it has not been executed against Postgres.

## CI drill facts

| Run | Event | Ref | Conclusion | Note |
|---|---|---|---|---|
| 35586606384 | schedule | master `e5692428` | failure | `container 'parkio-postgres-gateway' not found` |
| 34830729802 | schedule | master | failure | 2026-09-14 |
| 34106158282 | schedule | master | failure | 2026-09-07 |
| 33384730828 | schedule | master | failure | 2026-08-31 |
| 35865382825 … 35827667462 | pull_request / push | api and PR branches | success (9) | 2026-09-22/23 |
| 31798454426 … 31736617072 | workflow_dispatch | api | success (5) | 2026-08-13/14; `azure_offsite` input not checked |
