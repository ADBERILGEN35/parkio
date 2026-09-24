# PR #104 release decision (draft -- do not merge or enable)

**PR:** https://github.com/ADBERILGEN35/parkio/pull/104
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**Reviewed head:** `e7a29c73d24feefdd75a2da4cbeeaa778ed1457c`
**Earlier synthetic CI head (still valid evidence):** `126727c6c41fd54b8b5b1ad7979bbc1aabbaa31a`
**Decision:** **HOLD**. Production disabled. Keep draft. Do not open another
integration PR. Do not merge or close #101/#102/#103/#104.

Production integration (preflight, pause file, NR guards, deploy scope,
remote storage) is recorded in
`PR104-PRODUCTION-INTEGRATION-DECISION.md`. Isolated implementation is
complete. Production remains HOLD.

Preparation worktrees and PRs are unchanged:
#101 `2f04d01636ab23f2e0881bb2f8018fe3eb0ed815`,
#102 `15b697fb047f53c7acb88bbcc9fea3a9befd6942`,
#103 `cdc0d13c76c9c3a249c9e165d08f9c4cdde20983`.

Accepted web and Alertmanager deployments are out of scope.

## 1. Required CI

On reviewed head `e7a29c73`: Build & unit tests PASS
(https://github.com/ADBERILGEN35/parkio/actions/runs/35920746085);
Secret scan PASS
(https://github.com/ADBERILGEN35/parkio/actions/runs/35920746129);
Synthetic coordination lifecycle PASS
(https://github.com/ADBERILGEN35/parkio/actions/runs/35920746192).

On earlier head `126727c6` (reused dedicated evidence):

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

Writers the isolated adapter pauses, only when already running:

`slack_worker`, `fluent_bit`, `gateway_exporter`, `inbox_consumer`,
`nr_source`, `nr_gate`.

Identities come from repository unit/compose files. Isolated process and
compose backends use those names. Production projects
`parkio-nr-log-continuous` and `parkio` are refused.

User-facing HTTP is not paused. Export pause is a file gate
(`export-pause-file` / `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE`).
It does not flip `ops-notifications.enabled` and does not block admission,
confirmation, or durable outbox writes.

Plaintext capture happens during pause. Encryption runs after resume.
Remote Azure upload is **NOT IMPLEMENTED**.

Isolated process-backend wall pause on this workstation was **>0s and <60s**
(`measuredWallPauseSeconds`). That is not a production measurement and is
not an injected Clock value. Compose-backend smoke is attempted in CI;
this Windows Docker host hung creating a disposable network and was not
used as evidence.

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
| Coordinator + isolated allowlisted adapter | **Executable** on disposable processes; compose backend when Docker works |
| Catalog derived from repo unit/compose files | **Executable**; refuses unresolved and production projects |
| Gateway exporter-only pause file | **Executable** in gateway tests; later deploy must set the env |
| `state_backup.py` capture then encrypt-after-resume | **Executable** offline |
| Event-level Slack classification | Executable, offline |
| #102 recover + cutoff refusal | Executable on FileStore/MemoryStore |
| #103 NR recovery-mode guard | Executable in tests; default-off |
| DR release via real control paths + mock upstreams | **Executable** isolated; Fluent Bit/Slack stay down until review |
| `parkio_ordinary_ops_snapshot_after_complete` | Still **refuses** production units |
| Production systemd of Civo Slack/NR units | **Not authorized** |
| Azure container / real backup / Slack/NR send | **Refused** |
| Auto-replay of delivery-ambiguous Slack events | **Refused** |

Exact isolated deploy scope: dummy allowlisted names under
`parkio-writer-control-isolated-*` or child processes. Not
`parkio-nr-log-continuous`, not `/opt/parkio` units.

Production-only prerequisites still open: host systemd/compose of the
documented Civo/NR units; gateway deploy of
`PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE`; measured host
pause; remote storage; real restore.

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
| Production systemd/compose of documented Civo/NR units | Keep hook refusal; isolated adapter only | Host operator | Authorized non-prod host; never this PR | Same unit names as catalog; user-facing HTTP stays up |
| Gateway export-pause-file not deployed | Set `PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_PAUSE_FILE` on a later gateway release | Gateway owner | Gateway deploy decision | Pause file stops export only; admission/outbox still work |
| Production pause duration unmeasured | Timed drill on a non-prod host | Host operator | Non-prod host; no prod access | Wall-clock start/stop; 900s budget vs 1200s ceiling vs actual |
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
