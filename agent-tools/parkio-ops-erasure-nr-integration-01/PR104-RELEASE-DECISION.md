# PR #104 release decision (draft -- do not merge or enable)

**PR:** https://github.com/ADBERILGEN35/parkio/pull/104
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**Reported head (CI reused below):** `126727c6c41fd54b8b5b1ad7979bbc1aabbaa31a`
**Decision:** **HOLD**. Production disabled. Keep draft. Do not open another
integration PR. Do not merge or close #101/#102/#103/#104.

Preparation worktrees and PRs are unchanged:
#101 `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815`,
#102 `15b697fb047f53c7acb88bbcc9fea3a9befd6942`,
#103 `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983`.

Accepted web and Alertmanager deployments are out of scope.

## 1. Required CI on reported head `126727c6`

`api` protection: `strict` required contexts `Build & unit tests` + `Secret scan`.

| Check | Result | Run |
|---|---|---|
| Secret scan (required) | **PASS** 12s | https://github.com/ADBERILGEN35/parkio/actions/runs/35917904119/job/107374692848 |
| Build & unit tests (required) | **PASS** 5m30s | https://github.com/ADBERILGEN35/parkio/actions/runs/35917903958 |
| Synthetic coordination lifecycle (dedicated) | **PASS** 17s | https://github.com/ADBERILGEN35/parkio/actions/runs/35917903946 |
| Synthetic off-host erasure tests | **PASS** 1m36s | https://github.com/ADBERILGEN35/parkio/actions/runs/35917903966 |

Dedicated integration evidence on `126727c6` is reused. It already includes
coordinator lifecycle plus reused #101/#102/#103 Python tests. No production
job ran. Invite-production deploy stayed dry-run/skipped.

## 2. Pause duration (corrected)

Do **not** call 15 minutes an expected production pause. Nothing has been
measured on a host.

| Kind | Value | Status |
|---|---|---|
| Measured synthetic duration | 0s on the success-path injected `Clock`; timeout fixture advances `HARD_CEILING_SECONDS + 1` | Measured in-process only |
| Unmeasured production duration | unknown | **Unmeasured.** Hook refuses live writer control |
| Configured pause budget | 900s (15 minutes) | Design budget in `CONFIGURED_PAUSE_BUDGET_SECONDS` |
| Hard ceiling | 1200s (20 minutes) | Abort, discard incomplete artifacts, resume pre-existing running set only |

Writers the coordinator **would** pause, if an adapter existed, and only when
already running:

`slack_worker`, `fluent_bit`, `gateway_exporter`, `inbox_consumer`,
`nr_source`, `nr_gate`.

User-facing HTTP (park, waitlist admission, account) is **not** in that list
and is **not** paused by this code. `WaitlistOpsNotificationExporter` still
has no exporter-only pause; flipping `ops-notifications.enabled` would also
disable outbox admission and is not a substitute.

Encryption of the operational archive is designed to happen **during** the
simulated pause. Remote upload/encryption to Azure is **NOT IMPLEMENTED**.
After writers resume, new writes are excluded from the already-sealed
archive. If a later adapter uploads after resume, that upload is of the
sealed artifact only; upload time is not pause time.

## 3. Event-level Slack reconciliation

Backup identity and aggregate counts are **not** sufficient. `prepare-recovery`
and `reconcile_slack_events` classify **event IDs**.

Covered synthetic cases:

- equal count, different event sets
- missing gateway IDs
- extra Slack IDs
- conflicting states (queued/delivered vs still pending)
- `delivery_unknown` / `in_flight` (ambiguous)
- dedup-key mapped to a different event ID (forensic; fail closed)

`auto_replay` is always `[]`. Ambiguous and conflicting IDs are
`replay_refused`. The coordinator will not mark `slack_reviewed` without the
event-level report, and will not accept ambiguous IDs without an explicit
`ambiguous_acknowledged`.

Documented limits (unchanged honesty):

- not atomic across outbox / Slack SQLite / inbox
- default dedup window **168 hours**; replay after expiry can duplicate
- HTTP success or timeout after snapshot can be absent from the queue
- gateway retention can drop rows Slack still holds
- an event ID match is not proof of Slack delivery

## 4. Implementation boundaries

| Surface | Status |
|---|---|
| Coordinator Python API + `MemoryWriters` | Executable, in-process **simulation** |
| `state_backup.py` snapshot / verify / prepare-recovery on fixtures | Executable, offline |
| Event-level Slack classification | Executable, offline |
| #102 recover + cutoff refusal | Executable on FileStore/MemoryStore; directory is **not** off-host |
| #103 `PARKIO_NR_BUDGET_RECOVERY_MODE=on` existing-ledger guard | Executable in tests; default-off |
| Restore-drill #102 supplement when flags+dir set | Executable in isolated drill scripts |
| `release_collection` starting Fluent Bit / Slack | **Simulated** in-memory flags only |
| Pause timeout via injected `Clock` | **Simulated** |
| `parkio_ordinary_ops_snapshot_after_complete` live pause | **NOT IMPLEMENTED** (deliberate refusal; returns 0 so COMPLETE is not retracted) |
| systemd/docker unit inventory and pause/resume adapter | **NOT IMPLEMENTED** |
| Azure container, credentials, remote write/download | **NOT IMPLEMENTED** (plan only) |
| Real Slack / NR / email send | **Refused** |
| Auto-replay of delivery-ambiguous Slack events | **Refused** |
| Production flag enablement | **Refused** |

Production orchestration remaining adapter work (do not remove the hook
refusal to call this ready):

1. Inventory the real host unit/container names, including any extra queue writers.
2. Implement pause/resume that records pre-state, honors 900s budget / 1200s ceiling,
   and resumes only the pre-existing running set.
3. Add exporter-only pause that does **not** disable outbox admission.
4. Measure pause duration on a non-production host.
5. Keep user-facing admission running; prove it.
6. Decide whether sealed-archive upload happens before resume. Upload after
   resume is allowed only for the already-sealed file.

## 5. Merge disposition (do not merge now)

#104 contains #101/#102/#103 **by git merge ancestry**, not by copied file
trees. Verified:

```
git merge-base --is-ancestor 2f04d016 HEAD   # 0
git merge-base --is-ancestor 15b697fb HEAD   # 0
git merge-base --is-ancestor cdc0d13c HEAD   # 0
```

Merge commits: `03c6a078` (#101), `14654128` (#102), `66e6f7b5` (#103).

**Chosen strategy (when a human later authorizes repository merge only):**
merge **only #104** into `api`. Do **not** merge #101/#102/#103 independently.
After #104 lands, close those three drafts as already included by ancestry.
If `api` has moved, update #104 only and re-verify ancestry.

If a source draft were merged first, git would not re-apply those commits;
#104's integration-only commits would remain unique. That path is **not**
chosen, because #104 already changed shared backup files (`state_backup.py`,
`backup-hosted-beta.sh`, `restore-drill-01.sh`) that the source drafts must
not land without.

Repository-only merge readiness is **not** production readiness.

## 6. Blockers

Repository-only work can proceed without production credentials. Production
readiness cannot.

| Blocker | Remedy | Owner | Access / decision | Acceptance |
|---|---|---|---|---|
| Production writer-control adapter **NOT IMPLEMENTED** | Authorized adapter: inventory, pre-state, pause/resume, ceiling; keep hook refusal until then | Integration owner + host operator | Host unit list; no secret change | Non-prod measured pause; user-facing admission still up; failed snapshot resumes pre-state only |
| Production pause duration unmeasured | Timed drill on a non-prod host | Host operator | Non-prod host; no prod access | Recorded start/stop; 900s budget vs 1200s ceiling vs actual |
| No dedicated remote container/prefix or permissions | Written decision: new container vs isolated prefix; do not mix into `BACKUP_AZURE_CONTAINER` | Backup owner | Azure subscription decision; do **not** retrieve `BACKUP_AZURE_*` here | Named container/prefix + identity; no real write in this PR |
| Authenticity vs SHA-256 | Decide signed seals and/or WORM/object-lock | Backup + security | Policy | Provenance + delete protection stated separately from checksums |
| Freshness SLA | Alert on erasure `coveredThrough` and ops-archive age | Ops | Threshold decision | Alert exists; nightly COMPLETE is not coverage |
| Event-level Slack review in a real restore | Operator reviews `event_ids`; never auto-replay `delivery_unknown`/`in_flight` | On-call + integration owner | Isolated restored export, not production query | Report with empty `auto_replay`; ambiguous IDs acknowledged or left unreleased |
| Unknown NR spend since snapshot | External accounting before any new budget | Finance/ops | Approved ledger | Gate stays exhausted until bound is written |
| #102 directory backend is not off-host | Real object store + lock-protocol attestation | Privacy + backup | Off-host identity; cutoff not lowered | Recover PASS only with verified watermark >= cutoff |
| Isolated real restore drill | Offline restore of a **non-prod** sealed pair | Backup owner | Isolated host; no prod restart | Parity + erasure + Slack event report; publishers stay down |
| FU-1 scheduled-backup acceptance | Leave AWAITING; do not poll | Release owner | None | Out of scope for #104 |

### Can proceed without production credentials

- Keep this draft HOLD; update docs/tests on #104 only
- Writer-control adapter design against `MemoryWriters`
- Remote-storage decision writing (no provision)
- Isolated CI and synthetic fixtures (already)
- Event-level Slack classification tests (already)

### Cannot proceed without later authorized access

- Host pause/resume, Azure provision, real backup/download/restore,
  secret retrieval, Slack/NR/email send, scheduled-backup polling,
  production flag enablement

## 7. Rollback

Leave all four flags unset. Revert #104 if it were ever merged. #101/#102/#103
stay draft. No service restart.

## 8. Acceptance layers

| Layer | Status |
|---|---|
| Coordinated draft + synthetic tests | Present; dedicated CI PASS on `126727c6` |
| Required CI on reported head | Secret scan PASS; Build & unit tests PASS on `126727c6` |
| Repository merge | **Not authorized** |
| Production enablement | **Not accepted** |
| Real storage / real restore | **Not accepted** |
