# N-02 — Production recovery coverage: decision memo (#365)

**Recorded:** 2026-11-23. **Parent issue:** [#365](https://github.com/ADBERILGEN35/parkio/issues/365).
**Audience:** the owner, and counsel for the legal and custody questions.

This memo lists the decisions that block production recovery coverage, with options and consequences. It
decides nothing legal and sets no retention period or custody arrangement. It changes no code, flag or
infrastructure. `verifiedCoverage` stays `false`, and production restore stays refused.

## 1. What production recovery coverage means here

A backup restore can bring back users who asked for erasure after the backup was taken. So Parkio refuses a
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
- The restore entrypoints refuse a production data restore with `parkio_restore_refuse_unverified_production`
  (exit 3).
- The durable-recording and durable-store flags are off by default.
- No production RPO or RTO is offered.

The isolated drill of #296 measured RTO 234 s, data RPO 165 s and erasure RPO 0 on synthetic data. That is not
production acceptance ([disaster-recovery-runbook.md](../operations/disaster-recovery-runbook.md), "RPO / RTO").

### Layer status (2026-11-23)

| Layer | Scope | Status |
|---|---|---|
| A | Bundle-validation wrapper (`scripts/production-recovery-evidence.sh`, default-off) | Done (#481; #366 and #367 closed) |
| B | GCS Object Retention Lock adapter (default-off) | Merged (#484, #491). [#493](https://github.com/ADBERILGEN35/parkio/issues/493) (pagination bound) and [#494](https://github.com/ADBERILGEN35/parkio/issues/494) (per-write history cost) are being implemented; both block production enablement |
| C | Real-provider acceptance on a dedicated bucket with synthetic data | Approved by the owner; the run ([#483](https://github.com/ADBERILGEN35/parkio/issues/483)) is pending |
| D | Production export, enablement, exposure and legal retention | **Open: this memo** |

**Provider route: Google.**
- On 2026-11-23 production moved from Civo to Google Compute Engine (europe-west4), and offsite backups are
  moving to Cloud Storage (europe-west1).
- The owner lifted the 2026-10-29 deferral of the GCS work.
- The S3 Object Lock contingency stays available only if #483 shows that the GCS adapter cannot keep its
  contract ([n02-gcs-evidence-store-compatibility.md](../operations/n02-gcs-evidence-store-compatibility.md)
  §4, route C).

### What the evidence store holds

This matters for the legal questions in D1.

| Object | Content ([recovery-evidence-contract.md](../operations/recovery-evidence-contract.md), "Durable record format v2") |
|---|---|
| Pending record `records/<erasureRequestId>.json` | `authUserId` (random UUID), `erasureRequestId`, `erasedAt`, sequence, database identity, producer and key ids, HMAC signature |
| Checkpoint `checkpoints/<sequence>.json` | One `{authUserId, erasedAt}` pair for every erased-user tombstone at that point, plus the signature |
| Frontier `frontier/expected-through.json` | Sequence counters, signature |
| Sequence markers | Sequence reservations, unsigned |

There are no e-mail addresses, names or user content. After erasure, the auth row stays only as an `ERASED`
tombstone without a recoverable e-mail
([priv-001-account-data-erasure.md](priv-001-account-data-erasure.md)). Backups keep their own 14-day window.

## 2. Decisions needed

Each decision is listed with its options and the technical consequence of each. Legal and custody items are
questions for the owner and counsel; this memo answers none of them.

### D1. Legal retention duration for durable erasure evidence

**Facts that bind the choice:**
- The evidence must outlive every backup it may have to correct. Engineering guidance already on record:
  - with the 14-day offsite backup window, the technical floor is at least 45 days (14 + 30);
  - the recommended engineering floor before counsel is at least 90 days.

  These are engineering inputs, not a legal period
  ([erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md)). The 400-day placeholder
  stays discarded.
- COMPLIANCE (Locked) is the intended mode for the real store (owner decision B2, 2026-10-03, in the contract).
  Under Locked mode nobody, the project owner included, can shorten, remove or delete a retained generation
  before its retain-until. Under Unlocked (GOVERNANCE) mode, a principal with `overrideUnlockedRetention` can
  shorten, remove or lock it later ([n02-gcs-provider-acceptance-plan.md](../operations/n02-gcs-provider-acceptance-plan.md) §3).
- Every write is retained for the chosen period, including frontier rewrites and markers. Storage volume is
  tiny. The cost comes from operations and retained generations (#494).

| Option | Consequence |
|---|---|
| R1. Period tied to the longest backup retention plus a margin (for example the engineering floor) | Shortest exposure and lowest cost. Any later increase of backup retention must raise the evidence period first, or older backups lose their proof |
| R2. A fixed period set by counsel | Follows counsel exactly. If it is longer than R1, the extra period cannot be shortened later for objects already written under Locked mode |
| R3. Start Unlocked with a short period while counsel decides, then lock | Evidence starts recording sooner. Until locked, integrity rests on IAM: a principal with override permission can remove it. Locking later is a separate, recorded change |

**Questions for counsel (KVKK and GDPR).** These are questions only:
1. **Applicable law.** Do KVKK (Law No. 6698) and the GDPR both apply to Parkio's processing? Parkio is offered
   in Türkiye, and production and backups are hosted in Google Cloud EU regions (Netherlands, Belgium). For the
   GDPR, see its territorial scope (Art. 3).
2. **Erasure obligation.** After a user's erasure, is it compatible with the erasure obligations (KVKK Art. 7
   and the regulation on erasure, destruction or anonymization; GDPR Art. 17) to keep that user's random
   `authUserId` and erasure time? The purpose is to prove and re-apply the erasure after a disaster restore.
3. **Lawful basis.** If keeping it is permitted, which lawful basis applies, if any? What documentation does
   that basis require (for example a balancing test, or an entry in the retention and destruction policy)?
4. **Personal data.** Is `authUserId` still personal data once the account is erased? Backups keep the account
   for up to 14 days, and other records, such as logs, may carry the same id within their own retention.
5. **Maximum period.** What is the longest acceptable retention period for this evidence? Must it follow the
   backup retention?
6. **Irreversible lock.** Is an irreversible lock acceptable? A Locked object cannot be deleted before its
   retain-until, even on a data subject request, a KVKK Board decision or a court order. If it is not
   acceptable, is Unlocked mode with restricted override access required instead?
7. **Transfers abroad.** Does storing this evidence, and the backups (#520), in EU regions count as a transfer
   abroad under KVKK Art. 9? If so, which instrument is needed?
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

**Still open** ([erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md), "Still open"):
- rotation cadence;
- escrow;
- the distribution channel;
- the host path of the trust document.

**Technical facts:**
- The keys are HMAC secrets. The same secret signs and verifies, so every verifier's trust document is secret
  material too.
- If every copy of a key is lost, the evidence it signed can no longer be verified, and recovery through it is
  `BLOCKED`. The evidence is write-once and cannot be re-signed.
- After a compromise, the key is retired, and every object it signed is refused. That period becomes an unknown
  tail.
- A new database cluster gets a new database identity, for example after a fresh initdb, a logical restore or a
  move to a managed database. Its evidence starts a new lineage and needs a trust document pinned to the new
  identity.

| Option | Consequence |
|---|---|
| K1. Single custodian; trust document mounted on the production host (read-only for the service user); offline escrow copy kept by the owner outside Google Cloud | Simplest. One person can lose or reveal the keys (bus factor 1). Escrow is what keeps the evidence verifiable when the host and the custodian's workstation are both lost |
| K2. Google Secret Manager in a separate project with narrow IAM; the producer reads the key at startup; offline escrow copy as in K1 | Access is audited, and rotation works through secret versions. It adds a startup dependency, which fails closed: no key means no durable recording, and requests stay `PENDING_DURABLE`. Needs a provisioned project and IAM |
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

**D3.1 Approval.** Enabling durable recording in production is a production change. It means turning on these
flags, all off by default under `parkio.privacy.account-erasure.`:

| Property | Environment variable |
|---|---|
| `durable-recording-enabled` | `PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_ENABLED` |
| `durable-recording-retry-worker-enabled` | `PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_RETRY_WORKER_ENABLED` |
| `durable-store.gcs.enabled` | `PARKIO_ERASURE_STORE_GCS_ENABLED` |
| `durable-store.checkpoint.enabled` | `PARKIO_ERASURE_CHECKPOINT_ENABLED` |

| Option | Consequence |
|---|---|
| A1. Owner approval recorded on #365, after counsel's D1 answers, with baseline, rollback and acceptance like other production changes | One accountable approver. The legal inputs are in place before the first irreversible write |
| A2. Owner approval before counsel answers, under Unlocked mode and a short period (R3) | Earlier operational experience. The irreversible part waits for counsel. The evidence written meanwhile is IAM-protected only |

**D3.2 Project.**

| Option | Consequence |
|---|---|
| P1. A dedicated evidence project | IAM stays separate from production. The best-effort lien that Object Retention Lock places on project deletion stays out of production's lifecycle. Billing and alerts are separate. Costs one more project to operate |
| P2. The production project | Fewer projects. Production administrators can manage the evidence bucket, and the lien also blocks deleting the production project |
| P3. The backup project | Shares IAM with the backups. Evidence and backups fail or survive together |

**D3.3 Region.**
- The contract requires the store to be off the primary host. Any Cloud Storage bucket meets that.
- The remaining question is correlated failure. Evidence must be available whenever a backup is restorable, and
  must survive the loss of production.

| Option | Consequence |
|---|---|
| G1. Same region as the backups (europe-west1), different from production (europe-west4) | Evidence and backups survive together, apart from production. Single-region price |
| G2. Same region as production (europe-west4) | A regional outage takes production and the evidence together. Backups in europe-west1 might then be restorable while the evidence is not |
| G3. Dual-region or EU multi-region | Survives a single-region outage. Higher storage and operation prices, and different replication semantics to check against #483's findings |

Jurisdiction is part of counsel's question 7.

**D3.4 Bucket and retention mode.** Configure the bucket as in the #483 test bucket, but with the production
retention. Object Retention Lock is enabled at creation and cannot be disabled afterwards.

| Setting | Value |
|---|---|
| Object Retention Lock | Enabled at creation (irreversible) |
| Object Versioning | On |
| Soft delete | Off |
| Uniform bucket-level access | On |
| Public access prevention | Enforced |
| Bucket Lock retention policy | None |

- Retention mode: confirm COMPLIANCE (Locked) from the first production write, or use R3. The duration comes
  from D1.
- Store authentication: the production VM's attached service account through ADC, with custom roles modelled
  on the #483 plan (§7.4).
- Who may hold `overrideUnlockedRetention`: only relevant under Unlocked mode.

**D3.5 Exposure after a restore (P-EXPOSURE).** Who decides that a restored copy may serve users, and on which
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
| F3b. Keep the current behaviour: a lost response converges through a new sequence | No change. Each lost response leaves extra retained generations, which adds to #494's cost growth |

Recommendation: decide after #483's S5/S5s show how often responses are lost and #494 settles the cost model.

**Finding 4: the production writer holds `storage.objects.delete`.** A versioned overwrite of the frontier needs
that permission, so retention protects the evidence, not IAM. #483 measures this (L2) and proves it (L3, G5).

| Option | Consequence |
|---|---|
| F4a. Accept: under Locked mode the provider refuses deleting retained generations whatever the role (**recommended with Locked mode**) | No redesign. Protection depends on the lock mode. Under Unlocked mode, an identity with override permission plus delete could remove evidence |
| F4b. Redesign so the store never rewrites a name, for example a frontier written as new immutable objects | The writer loses delete. A larger change to the store, and #483 would have to run again |
| F4c. F4a plus Cloud Audit Logs data-access logging on the bucket | Detects delete attempts. Adds log volume and cost |

**Finding 7: versioning is checked only at startup.** If versioning is later suspended, rewrites fail closed
(`PENDING_DURABLE`) until someone notices.

| Option | Consequence |
|---|---|
| F7a. Periodic re-check of the bucket features, exported as a metric with an alert (**recommended, with F7b**) | Catches configuration drift before erasures pile up. A few extra metadata reads |
| F7b. Alert on `PENDING_DURABLE` growth and recording failures only | No extra calls. Detects the drift only after erasures start waiting |

**Before enablement:**
- #493 and #494 must be merged.
- #483 must pass, and its cleanup must be recorded.

## 3. Recommended sequence once the decisions exist

Steps 1 to 3 can run in parallel. The rest are ordered.

1. Run #483 (approved) and record its cleanup. It confirms the GCS route or refutes it; if refuted, D3 moves to
   the S3 contingency.
2. Merge #493 and #494.
3. Counsel answers D1. The owner records on #365:
   - the retention duration, the mode and the lawful-basis outcome (D1);
   - the custody choices (D2);
   - the project, region, approval and exposure owner (D3).
4. Implement the D4 decisions, default-off, with red then green tests. Production-path changes need stub-driven
   end-to-end refusal tests, as #365's acceptance criteria require.
5. The owner provisions:
   - the evidence project and bucket (irreversible Object Retention Lock; confirm cost and lien first);
   - the production signing key, the trust documents and the escrow copy.
6. A production change, approved under D3.1: turn on the four durable-recording flags.
   - **Baseline:** the four flags are off. Record the erasure request statuses before the change.
   - **Rollback:** turn the flags off. Erasures then complete without durable evidence again, so the
     evidence has a gap from that moment (an unknown tail for any later restore). Evidence already written
     stays locked.
   - **Acceptance:** a consumer-side, read-only verification of the production evidence chain with the
     verifier's trust document.
7. A burn-in period with the finding 2 and 7 alerts.
8. Only then, as a separate decision:
   - a production verification gate that refuses every case in the
     [f27 design matrix](../operations/f27-production-recovery-coverage-design-matrix.md) §2 (P-MISS, P-STALE,
     P-CORR, P-INCOMPLETE, P-DEFAULT-OFF, P-KEY-CUSTODY, P-EXPOSURE, P-LEDGER-ONLY);
   - the P-EXPOSURE decision;
   - any change to `verifiedCoverage`.

**What stays refused until then:**
- Production data restore: `restore-hosted-beta.sh` and `restore-database.sh` refuse with exit 3, and the
  MinIO-only scope stays unsupported.
- `verifiedCoverage` stays `false`. No evidence-file bypass is added.
- The durable-recording and durable-store flags stay off by default in every environment.
- No production RPO or RTO is offered.
- No production evidence is exported. #104 stays on HOLD.

## 4. References

- [n02-gcs-provider-acceptance-plan.md](../operations/n02-gcs-provider-acceptance-plan.md): #483 plan, provider facts (§3), findings (§8)
- [erasure-evidence-key-custody.md](../operations/erasure-evidence-key-custody.md): custody record, rotation, retirement
- [disaster-recovery-runbook.md](../operations/disaster-recovery-runbook.md): refusals, RPO/RTO status
- [recovery-evidence-contract.md](../operations/recovery-evidence-contract.md): evidence format, unknown tail, operator inputs (§9)
- [f27-production-recovery-coverage-design-matrix.md](../operations/f27-production-recovery-coverage-design-matrix.md): negative cases, recorded owner decisions
- [n02-gcs-evidence-store-compatibility.md](../operations/n02-gcs-evidence-store-compatibility.md) and [n02-gcs-evidence-adapter-design.md](../operations/n02-gcs-evidence-adapter-design.md): route and adapter contract
- Issues: [#365](https://github.com/ADBERILGEN35/parkio/issues/365), [#483](https://github.com/ADBERILGEN35/parkio/issues/483), [#493](https://github.com/ADBERILGEN35/parkio/issues/493), [#494](https://github.com/ADBERILGEN35/parkio/issues/494), [#520](https://github.com/ADBERILGEN35/parkio/issues/520)

## 5. Türkçe özet (sahip için)

**Ne durumdayız.**
- Üretimde veri geri yüklemesi bilerek kapalı (`verifiedCoverage=false`). Bir yedek, yedekten sonra silinmesini
  isteyen kullanıcıları geri getirebilir. Bunu önleyecek kalıcı silme kanıtı üretimde henüz çalışmıyor.
- A ve B katmanları hazır. C, yani gerçek GCS kabul koşusu (#483), onaylandı ve koşulmayı bekliyor.
  #493 ve #494 yapılıyor.
- D katmanı açık: üretimde açma, hukuki saklama süresi, anahtar saklama ve açığa çıkarma kararları. Sağlayıcı
  yolu artık Google.

**Senden ve hukuk danışmanından beklenen kararlar:**
1. **D1, saklama süresi.** Kanıt kayıtları silinen kullanıcının rastgele kimliğini (`authUserId`) ve silinme
   zamanını içeriyor. E-posta ya da isim yok. Locked modda bu kayıtlar süre dolana kadar kimse tarafından
   silinemiyor. Süreyi ve modu danışman yanıtlarına göre sen belirleyeceksin. KVKK ve GDPR soruları 2. bölümde.
2. **D2, anahtar saklama.** Saklayıcı `ADBERILGEN35` olarak kayıtlı. Açık konular:
   - anahtarların yedek (escrow) kopyasının nerede duracağı;
   - döndürme sıklığı;
   - ikinci bir saklayıcı olup olmadığı.

   Bütün kopyalar kaybolursa o anahtarla imzalanmış kanıt doğrulanamaz ve geri yükleme kapalı kalır.
3. **D3, üretimde açma.**
   - Onayı kimin vereceği.
   - Proje: ayrı bir kanıt projesi öneriliyor.
   - Bölge: yedeklerle aynı bölge (europe-west1) öneriliyor.
   - Mod: Locked mı, önce Unlocked mı.
   - Geri yüklenen bir kopyanın kullanıcıya ne zaman açılabileceği (P-EXPOSURE).
4. **D4, mühendislik kararları (bulgu 2, 3, 4 ve 7).** Öneriler 2. bölümde işaretli. Hepsi açmadan önce
   uygulanacak.

**Sıra:**
1. #483'ü koş, #493 ve #494'ü merge et, danışman yanıtlarını al.
2. Kararları #365'e yaz.
3. D4'ü varsayılan kapalı olarak uygula.
4. Projeyi, bucket'ı ve anahtarları hazırla.
5. Onaylı bir değişiklikle kanıt kaydını aç ve bir süre izle.
6. Üretim geri yüklemesi en son, ayrı bir kararla gelir.

O zamana kadar üretim geri yüklemesi kapalı kalır.
