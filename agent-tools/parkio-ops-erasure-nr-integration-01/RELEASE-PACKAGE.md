# Coordinated integration -- draft release package

**PR target:** `api` (draft only). **Decision: HOLD. Production disabled.**

## Included source identities

| Input | SHA | Draft PR |
|---|---|---|
| Operational-state backup | `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815` | #101 |
| Off-host erasure recovery | `15b697fb047f53c7acb88bbcc9fea3a9befd6942` | #102 |
| NR existing-ledger recovery guard | `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983` | #103 |
| Base | `aa865a255564464bed207a9061244af2641edd3d` | `origin/api` |

Preparation worktrees and PRs are preserved and were not rewritten.

## Exact scope

New coordination layer (`scripts/recovery_coordination/`),
`scripts/lib/recovery-coordination.sh`, default-off hooks in
`backup-hosted-beta.sh` and `restore-drill-01.sh`, this package, and
`docs/operations/ops-erasure-nr-coordination.md`.

Does not activate production, provision Azure, retrieve secrets, restart
services, or weaken #102 cutoff refusal. Directory FileStore is not
off-host storage.

## Dependency / merge order

1. #101 snapshot helper
2. #102 erasure recover
3. #103 NR existing-ledger guard
4. This integration branch (coordinator + hooks)

Do not merge #101/#102/#103 independently after this draft exists without
re-integrating.

## Disabled defaults

`PARKIO_RECOVERY_COORDINATOR_ENABLED`, `PARKIO_OPS_STATE_BACKUP_ENABLED`,
`PARKIO_OFFHOST_ERASURE_ENABLED`, `PARKIO_NR_BUDGET_RECOVERY_MODE` all
unset/`0`/`off`.

Expected writer pause: **15 minutes**. Hard ceiling: **20 minutes**
(abort, discard incomplete artifacts, resume pre-existing running set only).

## Rollout / rollback

Rollout is not authorized. Rollback: leave flags off or revert this PR.
#101/#102/#103 stay draft. Accepted web and Alertmanager deployments are
out of scope.

## Remaining real-storage / real-restore gates

See the coordination doc: dedicated container/prefix decision, identity,
WORM/versioning, freshness SLA, lock-protocol attestation, isolated
restore drill, unknown NR spending bound, Slack reconciliation. None
performed here.
