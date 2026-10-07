# Coordinated integration -- draft release package

**PR target:** `api` (draft only). **Decision: HOLD. Production disabled.**
**Reviewed head:** `e7a29c73d24feefdd75a2da4cbeeaa778ed1457c`
See `PR104-PRODUCTION-INTEGRATION-DECISION.md` for the production HOLD
decision. See `PR104-RELEASE-DECISION.md` for the earlier blocker table.

## Included source identities

| Input | SHA | Draft PR | How present on #104 |
|---|---|---|---|
| Operational-state backup | `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815` | #101 | merge ancestry `03c6a078` |
| Off-host erasure recovery | `15b697fb047f53c7acb88bbcc9fea3a9befd6942` | #102 | merge ancestry `14654128` |
| NR existing-ledger recovery guard | `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983` | #103 | merge ancestry `66e6f7b5` |
| Base | `aa865a255564464bed207a9061244af2641edd3d` | `origin/api` | ancestor |

Preparation worktrees and PRs are preserved and were not rewritten.

## Exact scope

Coordination layer, default-off hooks, event-level Slack reconciliation,
fail-closed SQLite header/sidecar handling, this package, and
`docs/operations/ops-erasure-nr-coordination.md`.

Does not activate production, provision Azure, retrieve secrets, restart
production services, or weaken #102 cutoff refusal. Isolated allowlisted
writer control is executable. The production backup hook still refuses
host units. Directory FileStore is not off-host storage.

Later gateway deploy: `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE`.

## Merge strategy (not authorized)

Merge **only #104** into `api` if a human later authorizes a repository
merge. Do not merge #101/#102/#103 independently. Close them afterward as
already included by ancestry. Integration-only commits must not be
duplicated by copying files.

## Disabled defaults

`PARKIO_RECOVERY_COORDINATOR_ENABLED`, `PARKIO_OPS_STATE_BACKUP_ENABLED`,
`PARKIO_OFFHOST_ERASURE_ENABLED`, `PARKIO_NR_BUDGET_RECOVERY_MODE` all
unset/`0`/`off`.

Configured pause budget: **900s**. Hard ceiling: **1200s**.
Measured synthetic success-path: **0s** injected clock.
Production pause: **unmeasured**.

## Rollout / rollback

Rollout is not authorized. Rollback: leave flags off or revert this PR.
#101/#102/#103 stay draft. Accepted web and Alertmanager deployments are
out of scope.

## Remaining real-storage / real-restore gates

See the decision package blocker table. None performed here.
