# N-02 — Production recovery coverage: decision memo (#365)

**Recorded:** 2026-11-23; revised 2026-11-24 after the independent review of PR #573.
**Parent issue:** [#365](https://github.com/ADBERILGEN35/parkio/issues/365).
**Audience:** the owner, and counsel for the legal and custody questions.

This memo lists the decisions that block production recovery coverage, with options and consequences. It
decides nothing legal and sets no retention period or custody arrangement. It changes no code, flag or
infrastructure. `verifiedCoverage` stays `false`, and production restore stays refused.

## 1. What production recovery coverage means here

A restore can bring back users who asked for erasure after the restored copy was taken. So Parkio refuses a
production data restore unless it can prove which erasures the restored copy must replay. That proof is the
**durable erasure evidence**:

1. **Recorded off the primary host before it counts.** auth-service writes a signed record of each erasure to a
   write-once store (Object Retention Lock). The request becomes durably recorded only after that write.
   Until then it stays `PENDING_DURABLE` for the retry worker.
2. **Complete and fresh.** The evidence frontier covers every sequence up to the restore cutoff. A cutoff past
   the last durable watermark is an unknown tail: the verdict is `BLOCKED`, and the copy stays unexposed
   ([recovery-evidence-contract.md](../operations/recovery-evidence-contract.md) §2).
3. **Trusted without the restored data.** Verifiers pin the signing keys and the database identity. They never
   take either from the evidence or from the restored copy.
4. **Enabled and gated.** The production producer runs, and a separate exposure decision (P-EXPOSURE) keeps
   any restored copy away from users until it is made
   ([f27 design matrix](../operations/f27-production-recovery-coverage-design-matrix.md) §2).

Today none of this runs in production:
- The restore scripts refuse a production data restore with `parkio_restore_refuse_unverified_production`
  (exit 3).
- The durable-recording and durable-store flags are off by default.
- No production RPO or RTO is offered.

The isolated drill of #296 measured RTO 234 s, data RPO 165 s and erasure RPO 0 on synthetic data. That is not
production acceptance ([disaster-recovery-runbook.md](../operations/disaster-recovery-runbook.md), "RPO / RTO").

**The refusal covers the scripted restore path only.**
- The production VM's disk has daily snapshots, kept for 14 days. The Docker volumes are on that disk, and with
  them the personal data on the host.
- Restoring a snapshot is a full data restore that no repository script refuses (D5).

### Layer status (2026-11-23)

| Layer | Scope | Status |
|---|---|---|
| A | Bundle-validation wrapper (`scripts/production-recovery-evidence.sh`, default-off) | Done (#481; #366 and #367 closed) |
| B | GCS Object Retention Lock adapter (default-off) | Merged (#484, #491), **not ready for production**: [#493](https://github.com/ADBERILGEN35/parkio/issues/493) (unbounded pagination) blocks enablement |
| Store | `ObjectLockDurableErasureRecordStore`, shared by every provider route | [#494](https://github.com/ADBERILGEN35/parkio/issues/494) (per-write history cost) blocks enablement on any route |
| C | Real-provider acceptance on a dedicated bucket with synthetic data | Plan approved by the owner; the run ([#483](https://github.com/ADBERILGEN35/parkio/issues/483)) is pending |
| D | Production export, enablement, exposure and legal retention | **Open: this memo** |

#494 is provider-neutral, after the owner's 2026-10-29 correction: it sits in the store class and also applies
to the S3 contingency. #493 is in the GCS adapter only, so it drops out if D3 moves to S3.

**Owner decisions of 2026-11-23**, recorded on #365 and #483:
- The 2026-10-29 Google Cloud postponement is lifted. Production moved to Google Compute Engine (europe-west4),
  and offsite backups are moving to Cloud Storage (#520).
- The bounded real-GCS acceptance (#483) is approved as planned.
- #494 is resolved with conditional writes: the next sequence comes from the frontier's last reserved value, and
  it is written with a generation-match condition.
- Layer D is handled through this decision memo. The owner or counsel approves the legal part.

**Provider route: Google.** The S3 Object Lock contingency stays available only if #483 shows that the GCS
adapter cannot keep its contract
([n02-gcs-evidence-store-compatibility.md](../operations/n02-gcs-evidence-store-compatibility.md) §4, route C).

### Copies that can bring an erased account back

Each of these copies binds D1 and the counsel questions.

| Copy | How long it can hold an erased account (2026-11-23) |
|---|---|
| Offsite backups: a Cloud Storage bucket in the same Google Cloud project as production | A 14-day bucket retention policy (unlocked) keeps objects from being deleted or overwritten for 14 days. A lifecycle rule deletes them at 35 days, and a 7-day soft delete keeps a deleted object recoverable. So a copy can exist for up to about 42 days **after upload**. The outage-period stamps were uploaded on 2026-11-23, the oldest from 2026-09-30, and stay until about 2027-01-04. So the bucket holds data up to about 96 days old (#520) |
| Local backup stamps on the production host | Pruned after `BACKUP_RETENTION_DAYS` (default 14, `scripts/backup-hosted-beta.sh`), but only after a successful offsite step. The old offsite target failed for weeks, so no local prune has run, and older stamps remain. Pruning resumes once the reviewed `gcs` offsite kind (PR #575, Refs #520) is live |
| Production VM disk snapshots | Daily, kept 14 days. They contain every Docker volume |
| Live tombstone tables (`erased_user_tombstones` in auth and the participants' tombstones) | `{authUserId, erased_at}` kept with no expiry; no service deletes tombstones |

### What the evidence store holds

This matters for the legal questions in D1.

| Object | Content ([recovery-evidence-contract.md](../operations/recovery-evidence-contract.md), "Durable record format v2") |
|---|---|
| Pending record `records/<erasureRequestId>.json` | `authUserId` (random UUID), `erasureRequestId`, `erasedAt`, sequence, database identity, producer and key ids, HMAC signature |
| Checkpoint `checkpoints/<sequence>.json` | One `{authUserId, erasedAt}` pair for every erased-user tombstone at that point, plus the signature |
| Frontier `frontier/expected-through.json` | Sequence counters, signature |
| Sequence marker `sequences/<sequence>.json` | `sequence` and the `erasureRequestId` it is reserved for (the nil UUID for a checkpoint's marker); unsigned |

- There are no e-mail addresses, names or user content.
- After erasure, the auth row stays only as an `ERASED` tombstone without a recoverable e-mail
  ([priv-001-account-data-erasure.md](priv-001-account-data-erasure.md)).
- **Nothing deletes evidence.** The bucket has no lifecycle rule (D3.5), and the adapters never delete. Both
  verifiers return `BLOCKED` on any gap in sequences `1..expectedThrough` (`DurableErasureEvidenceVerifier`,
  `scripts/lib/recovery_persist_protocol.py`). So every record, checkpoint and marker is kept indefinitely,
  and the D1 period is only a lock period: how long an object cannot be deleted. Deleting evidence after its
  lock expires would break the chain and block every later recovery.
- **Checkpoints re-copy every erased id.** Every checkpoint copies the whole tombstone ledger, and tombstones
  are never deleted. So each new checkpoint writes and locks every erased user's id again. With periodic
  checkpoints, every erased id is also under a live lock at all times.

## 2. Decisions needed

Each decision is listed with its options and the technical consequence of each. Legal and custody items are
questions for the owner and counsel; this memo answers none of them.

### D1. Lock period and retention of durable erasure evidence

**Facts that bind the choice:**
- **The period is a lock period, and retention is indefinite.** Nothing deletes evidence (§1).
  - During the lock, nobody can delete an object.
  - After it, the object stays in the bucket, protected only by IAM: the production writer holds
    `storage.objects.delete` (finding 4). With soft delete off, a deletion would be final, and it would block
    every later recovery.
  - Deleting evidence after a maximum period is therefore not possible in the current design. It would need a
    design change to the store and both verifiers. Whether a maximum is required is for the owner and counsel
    (question 5); this memo cannot offer it as an option.
- **The lock should cover every copy a restore may have to correct** (the table in §1).
  - The engineering floors on record were derived from the old 14-day offsite window, as offsite retention plus
    30 days to investigate and recover (#366,
    [erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md)). They are:
    - a technical floor of at least 45 days;
    - a recommended engineering floor of at least 90 days.
  - Those floors predate the current settings, so engineering must re-derive them before D1. With the same
    formula, today's 35-day lifecycle gives 65 days (35 + 30), and 72 days if soft delete counts (42 + 30).
  - These are engineering inputs, not legal periods. The 400-day placeholder stays discarded.
- **Lock behaviour.** COMPLIANCE (Locked) is the intended mode for the real store (owner decision B2,
  2026-10-03, in the contract).
  - Under Locked mode nobody, the project owner included, can shorten, remove or delete a retained generation
    before its retain-until ([n02-gcs-provider-acceptance-plan.md](../operations/n02-gcs-provider-acceptance-plan.md) §3).
  - After the retain-until passes, any identity with `storage.objects.delete` can delete the object, the
    production writer included (finding 4).
  - With soft delete off, that deletion is final.
- **Checkpoints keep every erased id under a live lock.** Periodic checkpoints re-copy and re-lock every erased
  id (§1).
- **Cost.** Every write is kept indefinitely, frontier rewrites and markers included. Storage volume is tiny;
  the cost comes from operations and the growing number of retained generations (#494).

| Option (lock period) | Consequence |
|---|---|
| R1. A lock tied to the longest restorable copy plus a margin, re-derived by engineering from today's settings | The lock protects the evidence while a restorable copy may need it. After that the evidence stays, protected only by IAM. Any later increase of backup or snapshot retention must raise the lock first, or older copies lose their protected proof |
| R2. A lock period set by counsel | Follows counsel exactly. If it is longer than R1, the extra lock time cannot be shortened later for objects already written under Locked mode, so a deletion demand in that time cannot be met |

**Questions for counsel (KVKK and GDPR).** These are questions only:
1. **Applicable law.** Do KVKK (Law No. 6698) and the GDPR both apply to Parkio's processing? Parkio is offered
   in Türkiye, and production, backups and snapshots are hosted in Google Cloud EU regions. For the GDPR, see
   its territorial scope (Art. 3).
2. **Erasure obligation.** After a user's erasure, is it compatible with the erasure obligations (KVKK Art. 7
   and the regulation on erasure, destruction or anonymization; GDPR Art. 17) to keep that user's random
   `authUserId` and erasure time? The purpose is to prove and re-apply the erasure after a disaster restore.
   Note that the evidence is kept indefinitely, and periodic checkpoints write and lock the same id again and
   again.
3. **Lawful basis.** If keeping it is permitted, which lawful basis applies, if any? What documentation does
   that basis require (for example a balancing test, or an entry in the retention and destruction policy)?
4. **Personal data.** Is `authUserId` still personal data once the account is erased? These copies still hold
   the account or the id:
   - offsite backups, for up to about 42 days after upload; the outage-period stamps hold data up to about
     96 days old;
   - local backup stamps, longer while local pruning is paused;
   - VM disk snapshots, for 14 days;
   - the live tombstone tables, with no expiry;
   - the evidence store, indefinitely;
   - logs, within their own retention.
5. **Retention and lock period.** The current design keeps the evidence, and so the erased ids,
   indefinitely; only the lock period can be set.
   - Is indefinite retention acceptable?
   - If not, what maximum applies? Meeting it needs a design change, which is a decision for the owner.
   - How long must the lock last at least, and must it follow the backup and snapshot retention?
6. **Irreversible lock.** Is an irreversible lock acceptable? A Locked object cannot be deleted before its
   retain-until, even on a data subject request, a KVKK Board decision or a court order. If counsel rejects
   it, owner decision B2 and D3.5 must be revisited.
7. **Transfers abroad.** Does hosting these in EU regions count as a transfer abroad under KVKK Art. 9: the
   production host, the backups (#520), the VM disk snapshots and this evidence? If so, which instrument is
   needed?
8. **Registers and notices.** Does this processing need to appear in:
   - the VERBIS registry and the personal data processing inventory (KVKK Art. 16);
   - the record of processing activities (GDPR Art. 30);
   - the privacy notice (aydınlatma metni, KVKK Art. 10)?
9. **Processor terms.** Does Google act as processor for this store under its cloud data processing terms? Is
   anything further needed?

### D2. Signing-key custody

**Recorded on 2026-10-21 (#366):**
- The key custodian and compromise owner is the GitHub account `ADBERILGEN35`, with no delegate.
- No centralized secrets store is claimed.
- Evidence keys stay separate from backup encryption secrets and storage credentials.

**Still open:**
- rotation cadence, the distribution channel and the host path of the trust document
  ([erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md), "Still open");
- escrow, which this memo adds as a decision. The compatibility doc (§3, "Key access after host loss") and #366
  raise it, but the custody doc's list does not.

**Technical facts:**
- **Shared secret.** The keys are HMAC secrets. The same secret signs and verifies, so every verifier's trust
  document is secret material too.
- **Startup dependency.** auth-service reads the trust document only from the `trust-file` path. With the store
  enabled, a missing, unreadable or invalid trust document stops auth-service at startup, so logins stop too,
  not only erasures. If durable recording is on without a store, new deletion requests are refused with
  `DURABLE_RECORDING_UNAVAILABLE`.
- **Lost keys.** If every copy of a key is lost, the evidence it signed can no longer be verified, and recovery
  through it is `BLOCKED`. The evidence is write-once and cannot be re-signed.
- **Compromise.** The key is retired, and every object it signed is refused. That period becomes an unknown
  tail.
- **New database cluster.** A new cluster gets a new database identity, for example after a fresh initdb, a
  logical restore or a move to a managed database. Its evidence starts a new lineage and needs a trust document
  pinned to the new identity.

| Option | Consequence |
|---|---|
| K1. Single custodian; trust document as a file mounted read-only into the auth-service container; offline escrow copy kept by the owner outside Google Cloud | Simplest, and supported by today's code once the mount exists (D3.1). The file is a startup dependency of the whole auth service. One person can lose or reveal the keys (bus factor 1). Escrow keeps the evidence verifiable when the host and the custodian's workstation are both lost |
| K2. Google Secret Manager in a separate project with narrow IAM; offline escrow copy as in K1 | **Needs new code**, since auth-service reads only a file today, or a deploy-time step that fetches the secret into the trust file. Access is audited, and rotation works through secret versions. The startup dependency stays: if the fetch fails, auth-service does not start. Needs a provisioned project and IAM |
| K3. A second custodian or split knowledge for escrow | Removes the single point of failure. Needs a named second person; none is recorded today |

**Questions for the owner:**
- Rotation cadence. Annual plus on compromise is the design recommendation, not yet owner-locked.
- Where the escrow copy lives, and who may open it.
- Whether a delegate or second custodian exists.
- Where the recovery operator's verifier copy lives.

### D3. Production export and enablement

"Production export" here means production erasure evidence leaving the primary host. That happens only through
the producer's writes to the evidence store. No other export of user data is proposed. Any extra copy, for
example to a second provider, would be a separate decision.

**D3.1 Approval and the source change it needs.**

The recorded order puts enablement after counsel:
- the f27 matrix (§6) lists lawful basis and counsel first, and enablement after both;
- #365's D-DP1 comments require an explicit lawful-basis or authorization statement for any production evidence
  export path;
- owner decision B2 sets COMPLIANCE for the real store.

This memo keeps that order. The decision here is who approves enablement after counsel's answers, and how it is
recorded: on #365, with baseline, rollback and acceptance like other production changes.

Enabling durable recording means turning on these flags, all off by default under
`parkio.privacy.account-erasure.`:

| Property | Environment variable |
|---|---|
| `durable-recording-enabled` | `PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_ENABLED` |
| `durable-recording-retry-worker-enabled` | `PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_RETRY_WORKER_ENABLED` |
| `durable-store.gcs.enabled` | `PARKIO_ERASURE_STORE_GCS_ENABLED` |
| `durable-store.checkpoint.enabled` | `PARKIO_ERASURE_CHECKPOINT_ENABLED` |

**Today none of them reaches the auth-service container:**
- Of the erasure settings, the production compose files pass only `PARKIO_ACCOUNT_ERASURE_ENABLED`, and unlisted
  keys never reach the container.
- The same holds for the six store inputs: project id, bucket, retention mode, retention, trust file and
  producer key id. They have no defaults, and a missing one stops startup.
- The trust document needs a mount, and the container's root filesystem is read-only.

So enablement first needs a reviewed source change to the env examples, the compose overlay and the preflight,
as #365's rules require.

**D3.2 Checkpoint caller and cadence.**
- Turning on `durable-store.checkpoint.enabled` publishes nothing by itself. The producer exists, but nothing in
  the service calls it and no schedule is configured. Its cadence is an operator decision
  (`ErasureCheckpointProducer`, `application.yml`).
- Erasures from before enablement have no pending record, so checkpoints are their only coverage (contract,
  "Erasure set"). The first checkpoint at enablement is what brings them into the evidence.

Decisions needed:
- the caller: a schedule inside auth-service, or an operator-run command. Either needs new code.
- the cadence, including that first checkpoint.

Every recovery verifies the full chain and replays the full erasure set, so the cadence does not shorten a
replay. What it changes:

| Cadence | Consequence |
|---|---|
| Higher | Unrecorded erasures, from before enablement or from a rollback, get covered sooner. The capture's SHARE lock on the tombstone table holds back new deletion requests more often; each capture is bounded by the 12 s lock timeout and the 20 s statement timeout. More retained generations, more re-locking of erased ids (D1), and more exposure to finding 3's extra sequences |
| Lower | Fewer captures, generations and re-locks. Unrecorded erasures wait longer for coverage |

**D3.3 Project.**

| Option | Consequence |
|---|---|
| P1. A dedicated evidence project | IAM stays separate from production. The best-effort lien that Object Retention Lock places on project deletion stays out of production's lifecycle. Billing and alerts are separate. Costs one more project to operate |
| P2. The production project | Fewer projects. Production administrators can manage the evidence bucket, and the lien also blocks deleting the production project |
| P3. A backup project | Today the offsite backup bucket is in the production project, so P3 is the same as P2 unless a separate backup project is created |

**D3.4 Region.**
- The contract requires the store to be off the primary host. Any Cloud Storage bucket meets that.
- The remaining question is correlated failure. Evidence must be available whenever a backup is restorable, and
  must survive the loss of production.

| Option | Consequence |
|---|---|
| G1. Same region as the offsite backups (europe-west1), different from production (europe-west4) | Evidence and backups survive together, apart from production. Single-region price |
| G2. Same region as production (europe-west4) | A regional outage takes production and the evidence together. Backups in europe-west1 might then be restorable while the evidence is not |
| G3. Dual-region or EU multi-region | Survives a single-region outage. Higher storage and operation prices, and different replication semantics to check against #483's findings |

Jurisdiction is part of counsel's question 7.

**D3.5 Bucket and retention mode.** Configure the bucket like the #483 test bucket, but with the production
retention:

| Setting | Value |
|---|---|
| Object Retention Lock | Enabled at creation; cannot be disabled afterwards |
| Object Versioning | On |
| Soft delete | Off |
| Uniform bucket-level access | On |
| Public access prevention | Enforced |
| Bucket Lock retention policy | None |
| Lifecycle rules | None: nothing deletes evidence (§1) |

- Retention mode: COMPLIANCE (Locked), per owner decision B2. The lock period comes from D1.
- Store authentication: the production VM's attached service account through ADC, with custom roles modelled on
  the #483 plan (§7.4).

**D3.6 Public deletion status.** This is contract §9 item 2, still an open operator input. Today the API
returns only `IN_PROGRESS`, `COMPLETE` and `FAILED_RETRYING`. With durable recording on, a request waiting for
its record shows as `IN_PROGRESS`.

| Option | Consequence |
|---|---|
| S1. Add `PENDING_DURABLE` / `DURABLY_RECORDED` to the public status | Clients see the recording state. This is an API change for the clients |
| S2. Keep `IN_PROGRESS` until persist-ack | No API change. Document that `IN_PROGRESS` covers both states |

**D3.7 Exposure after a restore (P-EXPOSURE).** Who decides that a restored copy may serve users, and on which
evidence? This stays separate from enablement. Recording evidence does not make restores acceptable.

### D4. Remaining design items for layer D

These are findings 2, 3, 4 and 7 from #365's 2026-10-22 comment and
[n02-gcs-provider-acceptance-plan.md](../operations/n02-gcs-provider-acceptance-plan.md) §8. They are
engineering decisions; recommendations are marked.

**Finding 2: the resend pause.** On HTTP 429 the adapter resends a write. That can pause up to 16.5 s per write
while holding the store lock, on the request thread for records.

| Option | Consequence |
|---|---|
| F2a. Bound the total pause per operation, then hand over to the retry worker (`PENDING_DURABLE`) (**recommended**) | Request latency and lock hold time stay bounded. Under sustained throttling, more requests wait for the worker |
| F2b. Fail fast on the first 429 and let the worker resend | Shortest request path. More `PENDING_DURABLE` time and more worker load |
| F2c. Keep as is | Under throttling, request threads stall up to 16.5 s per write, and lock contention blocks other erasures |

**Finding 3: no checkpoint resume path.** `publishCheckpoint` cannot resume a checkpoint that was published but
not yet covered by the frontier, and every call writes the frontier twice.

| Option | Consequence |
|---|---|
| F3a. Add a resume path: cover published checkpoints above `expectedThrough` before reserving a new sequence | Fewer sequences and retained generations, which matters more as retention grows. More store code to test. #483's S5s requires only convergence, so it keeps passing |
| F3b. Keep the current behaviour: a lost response converges through a new sequence | No change. Each lost response leaves extra retained generations |

Recommendation: decide after #483's S5/S5s show how often responses are lost, and after #494's conditional
writes settle the per-write cost.

**Finding 4: the production writer holds `storage.objects.delete`.** A versioned overwrite of the frontier needs
that permission, so retention protects the evidence, not IAM. #483 measures this (L2) and proves it (L3, G5).

| Option | Consequence |
|---|---|
| F4a. Accept: under Locked mode the provider refuses deleting retained generations whatever the role (**recommended**) | No redesign. Protection depends on the lock mode and lasts until each retain-until (D1) |
| F4b. Redesign so the store never rewrites a name, for example a frontier written as new immutable objects | The writer loses delete. The frontier key and the way its versions are read are part of format v2, so this also changes the Python model, both verifiers, the fixtures and the recovery tooling, and #483 would have to run again |
| F4c. F4a plus Cloud Audit Logs data-access logging on the bucket | Detects delete attempts. Adds log volume and cost |

**Finding 7: versioning is checked only at startup.** If versioning is later suspended, rewrites fail closed
(`PENDING_DURABLE`) until someone notices.

| Option | Consequence |
|---|---|
| F7a. Periodic re-check of the bucket features, exported as a metric with an alert (**recommended, with F7b**) | Catches configuration drift before erasures pile up. A few extra metadata reads |
| F7b. Alert on `PENDING_DURABLE` growth and recording failures only | No extra calls. Detects the drift only after erasures start waiting |

**Before enablement:**
- #493 must be merged (GCS route only).
- #494 must be merged (any route), with conditional writes per the owner's decision.
- #483 must pass, and its cleanup must be recorded.

### D5. Disk snapshot restores

- No script refuses restoring a production VM disk snapshot.
- Such a restore brings back erased accounts exactly like a backup. The snapshots are also another copy of
  personal data (questions 4 and 7).

| Option | Consequence |
|---|---|
| S-A. An owner rule in the disaster recovery runbook: restoring data from a snapshot counts as a production data restore and stays refused until coverage exists | No infrastructure change. The rule is procedural, since no script enforces it. Restoring onto another host, even an isolated one, is not an exception: D-DP1 forbids moving real production data to another host ([f27 design matrix](../operations/f27-production-recovery-coverage-design-matrix.md) §5) |
| S-B. Exclude the data from snapshots, for example by moving the Docker volumes to a separate disk without a snapshot schedule, and rely on backups | One copy fewer, and the scripted refusal then covers data restores. Loses the fast whole-disk recovery path, and needs a migration on the production host |
| S-C. Keep snapshots and restrict who may restore them (IAM), with S-A's rule | Narrows who can take the unrefused path. Still procedural |

## 3. Recommended sequence once the decisions exist

Steps 1 to 3 can run in parallel. The rest are ordered.

1. Run #483 (approved) and record its cleanup. It confirms the GCS route or refutes it; if refuted, D3 moves to
   the S3 contingency.
2. Merge #493 and #494.
3. Counsel answers D1. The owner records on #365:
   - D1: the lock period, the answer on indefinite retention, and the lawful-basis outcome;
   - D2: the custody choices;
   - D3: approval, checkpoint caller and cadence, project, region, public status and exposure owner;
   - D5: snapshot restores.
4. Implement the decided changes, default-off, with red then green tests. Production-path changes need
   stub-driven end-to-end refusal tests, as #365's acceptance criteria require. This step covers:
   - D4;
   - the checkpoint caller (D3.2);
   - the source change that lets the flags, the store inputs and the trust-file mount reach auth-service (D3.1).
5. The owner provisions:
   - the evidence project and bucket (irreversible Object Retention Lock; confirm cost and lien first);
   - the production signing key, the trust documents and the escrow copy.
6. A production change, approved under D3.1: turn on the four durable-recording flags, then publish the first
   checkpoint.
   - **Baseline:** the four flags are off. Record the erasure request statuses before the change.
   - **Rollback:** turn the flags off. Erasures then complete without durable evidence again, so the evidence
     has a gap from that moment (an unknown tail for any later restore). Evidence already written stays locked.
   - **Acceptance:** a consumer-side, read-only verification of the production evidence chain with the
     verifier's trust document, covering the first checkpoint.
7. A burn-in period with the finding 2 and 7 alerts.
8. Only then, as a separate decision:
   - a production verification gate that refuses every case in the
     [f27 design matrix](../operations/f27-production-recovery-coverage-design-matrix.md) §2 (P-MISS, P-STALE,
     P-CORR, P-INCOMPLETE, P-DEFAULT-OFF, P-KEY-CUSTODY, P-EXPOSURE, P-LEDGER-ONLY);
   - the P-EXPOSURE decision;
   - any change to `verifiedCoverage`.

**What stays refused until then:**
- Production data restore through the scripts: `restore-hosted-beta.sh` and `restore-database.sh` refuse with
  exit 3, and the MinIO-only scope stays unsupported.
- Disk snapshot restores: no script refuses them, and D5 decides how they are held back.
- `verifiedCoverage` stays `false`. No evidence-file bypass is added.
- The durable-recording and durable-store flags stay off by default in every environment.
- No production RPO or RTO is offered.
- Before step 6, no production evidence leaves the host. #104 stays on HOLD.

## 4. References

- [n02-gcs-provider-acceptance-plan.md](../operations/n02-gcs-provider-acceptance-plan.md): #483 plan, provider facts (§3), findings (§8)
- [erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md): custody record, rotation, retirement
- [disaster-recovery-runbook.md](../operations/disaster-recovery-runbook.md): refusals, RPO/RTO status
- [recovery-evidence-contract.md](../operations/recovery-evidence-contract.md): evidence format, erasure set, unknown tail, operator inputs (§9)
- [f27-production-recovery-coverage-design-matrix.md](../operations/f27-production-recovery-coverage-design-matrix.md): negative cases, recorded owner decisions, order (§6)
- [n02-gcs-evidence-store-compatibility.md](../operations/n02-gcs-evidence-store-compatibility.md) and [n02-gcs-evidence-adapter-design.md](../operations/n02-gcs-evidence-adapter-design.md): route and adapter contract
- Issues: [#365](https://github.com/ADBERILGEN35/parkio/issues/365), [#483](https://github.com/ADBERILGEN35/parkio/issues/483), [#493](https://github.com/ADBERILGEN35/parkio/issues/493), [#494](https://github.com/ADBERILGEN35/parkio/issues/494), [#520](https://github.com/ADBERILGEN35/parkio/issues/520); offsite `gcs` kind: [PR #575](https://github.com/ADBERILGEN35/parkio/pull/575)
