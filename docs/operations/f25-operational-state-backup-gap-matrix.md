# F-25 / #376 — Operational-state backup coverage gap matrix

**Status:** investigation record only. Does **not** lift [#104](https://github.com/ADBERILGEN35/parkio/pull/104) HOLD.
**Recorded:** 2026-10-15 (owner-authorized D-DP1 sweep).
**api tip reviewed:** `cbfb84813b678f5c1787782a22a2a92ce57fb818`
**Held heads (read-only; branches not edited):**

| PR | Head | Role |
|---|---|---|
| #101 | `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815` | Opt-in Slack/NR operational-state snapshot tooling |
| #102 | `15b697fb047f53c7acb88bbcc9fea3a9befd6942` | Off-host erasure recover refusal (via #104 ancestry) |
| #103 | `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983` | Existing-ledger NR budget guard (via #104 ancestry) |
| #104 | `c24f4f3de07d31c359c33971aba457041714705c` | Integrate ops-state + erasure refusal + NR recovery (**HOLD**) |

## 1. api inventory (current contract)

Source: `docs/operations/backup-restore-readiness.md` §2 / §6 and
`docs/operations/disaster-recovery-runbook.md` (U02 drill scope).

| Component | Class | In nightly `backup-hosted-beta` on api | Live/observed note |
|---|---|---|---|
| 10 PostgreSQL DBs + MinIO | A/O | yes | Synthetic restore evidence only |
| Erasure ledger in stamp | A | yes (export failure tolerated) | Production restore still BLOCKED without verified coverage |
| NR budget ledger (`budget.db`) | O | **no** | Host bind observed 2026-09-23; still excluded |
| NR source cursors / Fluent Bit checkpoints | O | **no** | Loss re-ships logs against budget |
| Slack relay queue / dedup / DLT (`/var/lib/parkio/slack-biz`) | O | **no** | Directory located; SQLite file existence unproven |
| Slack relay inbox | O | **no** | Envelopes not yet consumed |
| Host env / backup passphrase | S | no (by design) | Separate custody |

U02 / #296 measured drill (run [37634422144](https://github.com/ADBERILGEN35/parkio/actions/runs/37634422144), PASS) explicitly excludes held Slack/NR work from production acceptance.

## 2. What #104 would cover if HOLD were lifted (not claimed live)

Read from #104 docs/scripts only (`ops-erasure-nr-coordination.md`,
`operational_state_backup/*`, `new-relic-budget-recovery-guard.md`,
`backup-hosted-beta.sh` hook delta). Default flags remain off:
`PARKIO_RECOVERY_COORDINATOR_ENABLED`, `PARKIO_OPS_STATE_BACKUP_ENABLED`,
`PARKIO_OFFHOST_ERASURE_ENABLED`, `PARKIO_NR_BUDGET_RECOVERY_MODE`.

| Capability on #104 head | Covers | Still not proven / separate |
|---|---|---|
| Opt-in encrypted Slack SQLite + inbox + NR budget/source/collector snapshot (#101 tooling) | Synthetic snapshot/verify/prepare-recovery for those O-class paths | Production schedule, off-host transfer, real restore |
| Exporter-only pause file + isolated writer control | Safer quiesce than flipping `ops-notifications.enabled` | Later gateway deploy must set pause-file path; production projects refused by adapter |
| Coordinated pause → plaintext capture → resume → encrypt | Ordinary backup pairing with gateway stamp id | Not atomic across inbox/relay/outbox; event-ID reconciliation required |
| NR existing-ledger recovery mode (#103) | Refuse fresh empty ledger; honor exhausted bit across day/month | Must be authorized + tested before any real restore |
| Off-host erasure recover refusal (#102) | Unknown-tail blocks expose | Does not authorize production restore; D-DP1 still applies |
| Production host units via backup hook | Still **refused** on #104 | HOLD preserved |

## 3. Gap matrix (api vs held)

| Gap | On api today | Covered by held #104 if enabled | Still needs separately scoped task |
|---|---|---|---|
| NR budget in consistent backup | excluded | yes (opt-in ops snapshot) | Owner authorize schedule + off-host handling; existing-ledger startup guard live proof |
| NR cursors/checkpoints | excluded | yes (in ops snapshot) | Cursor/spool/Fluent Bit reconcile decision after restore |
| Slack queue/dedup/DLT | excluded | yes (SQLite online backup) | Host file existence proof; delivery reconciliation (no auto-replay) |
| Slack inbox | excluded | yes | Same pause/resume authorization |
| Duplicate Slack suppression after restore | documented risk only | synthetic fixture coverage on #101 | Live paired stamp + event-ID reconcile under HOLD lift |
| Budget non-reset / exhausted preservation | documented risk only | synthetic + #103 guard on held heads | Production integration test of recovery-mode guard |
| Nightly stamp includes ops-state | no | optional post-COMPLETE hook on #104 (default-off) | Explicit owner enablement; do not duplicate #104 on a new branch |
| Production restore with ops-state | BLOCKED | still default-off / HOLD | Lawful basis + custody + HOLD lift (not inventable under D-DP1) |

## 4. Synthetic evidence (non-destructive)

- Held tooling location: `scripts/operational_state_backup/` on #101/#104 heads (absent from api tree).
- Ten focused fixture tests in `test_state_backup.py` executed from archived #101 head `2f04d016` with `PYTHONPATH=scripts` (includes `slack_biz` + `newrelic_log_pilot`): **Ran 10 tests in 6.745s — OK** (2026-10-15). Log: `agent-tools/parkio-overnight-20261014/F25-SYNTHETIC-OPSTATE-TESTS.txt`. Cases include WAL concurrent writes, corrupt/missing/schema mismatch, interrupted snapshot, inbox mutation, outbox/queue mismatch + duplicates, NR day/month rollover + missing-ledger fail-closed, wrong password/path traversal/symlink/no-overwrite, secret-named rejection.
- No production access, no real backup transfer/decrypt, no Slack/NR send, #104 branch not edited.

## 5. Decision record

**Finding:** F-25 is **reproducible** as a documentation/inventory gap on api: readiness correctly lists NR/Slack O-class state as excluded; open #101/#104 are not evidence of live backup coverage.

**Not a duplicate-implementation task.** Closing live coverage requires owner action on #104 HOLD and production integration gates listed above — out of scope for this issue.

**Smallest remaining owner step:** decide whether to (a) keep api inventory as the accepted contract and track HOLD-lift work only under #104, or (b) authorize a follow-on after HOLD to wire default-off ops-state into the nightly stamp with synthetic CI only. Do not invent KVKK/legal basis or external account ownership for off-host handling.
