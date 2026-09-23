# PR #102 release decision (draft â€” do not merge or enable)

**PR:** https://github.com/ADBERILGEN35/parkio/pull/102
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**First head:** `c86fd69b825691427a0fa19ee39b56b7b3a6c0bc`
**Prior review head:** `c50f2a5fe9a9048baaef4f1b34c93ebb1d664bfa`
**Closeout head:** `7dda96723f2d6e64f8790f6fd38aa92663f950d7`
**Decision:** **HOLD** â€” standalone tools only. Production disabled.
Keep draft. No merge, deploy, enablement, or real recovery.

Sibling Codex PR #101 (`fix/slack-nr-operational-state-backup`) and NR
guard work are out of scope. Those files were not modified.

## 1. Observed CI

### Final prior head `c50f2a5f` (reused)

| Check | Result | Run |
|---|---|---|
| Build & unit tests (required) | **pass** 3m59s | https://github.com/ADBERILGEN35/parkio/actions/runs/35913496303 |
| Secret scan (required) | **pass** 8s | https://github.com/ADBERILGEN35/parkio/actions/runs/35913496387 |
| Synthetic off-host erasure tests (dedicated, including Postgres concurrency) | **pass** 41s | https://github.com/ADBERILGEN35/parkio/actions/runs/35913496358 |

`api` protection remains `strict` required contexts `Build & unit tests` +
`Secret scan`. Closeout-head checks are recorded after the follow-up push
in the PR conversation / this fileâ€™s tip SHA.

### First head `c86fd69b` (reused, pre-Postgres-workflow)

Required checks and the original 13-test dedicated job passed. That suite
did not yet include lock-timeout / rollback tests.

## 2. Commit-visibility guarantee

`requestDeletion` assigns `erased_at = clock.instant()` at request start
and commits the tombstone later in the same transaction. An unlocked
SELECT plus a source-query or wall-clock stamp is **not** coverage.

A verified `table-share-lock` seal means: every tombstone whose inserting
transaction **committed before** the lock-held auth-DB `clock_timestamp()`
is in the snapshot. Isolation is explicit READ COMMITTED. Timeouts are
bounded (`lock_timeout` 12s, `statement_timeout` 20s). Errors abort; they
cannot produce publishable coverage. The DB transaction is released
before any remote persist.

It does **not** mean every row with `erased_at â‰¤ coveredThrough` is present.

## 3. Recovery limitation

Periodic snapshots cannot certify erasures committed after the last
verified watermark. A 15-minute cadence does **not** close that tail.
Recover stays BLOCKED when the requested cutoff is later than
`coveredThrough`. **Do not lower the recovery cutoff to obtain PASS.**

## 4. Tested tooling (not durability)

Synthetic publication/retry/protocol tests, plus isolated Postgres tests
for the in-flight commit race, lock release before persist, long-running
writer timeout, and failed-snapshot rollback. Directory `FileStore` is
test/local. SHA-256 is integrity of a present object, not authenticity
or deletion/rollback protection.

## 5. Next integration decisions (not implemented)

1. **Remote storage and authentication** â€” off-host prefix; already
   authorized identity; no secret retrieval here. Container/WORM: blocker.
2. **Publication integrity** â€” signed seals or immutable versions.
3. **Exporter scheduling** â€” independent lock-protocol job, not
   nightly-backup-only.
4. **Freshness monitoring** â€” watermark-age alert; backup COMPLETE â‰  coverage.
5. **Uncovered tail after host loss** â€” refuse restore; do not lower cutoff.

## 6. Acceptance layers

| Layer | Status |
|---|---|
| Standalone-tool acceptance | Tools + tests exist; `c50f2a5f` dedicated+required CI PASS |
| Real off-host durability | **Not accepted** |
| Production enablement | **Not accepted** |
| Actual recovery acceptance | **Not accepted** |

**HOLD.** Keep #102 draft.

