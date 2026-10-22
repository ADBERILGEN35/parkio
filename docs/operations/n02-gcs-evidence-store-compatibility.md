# N-02 — Google Cloud evidence-store compatibility (#366 design)

**Recorded:** 2026-10-21  
**Owner facts recorded:** custodian / compromise owner = GitHub account `ADBERILGEN35`; no centralized secrets store claimed; Google Cloud is the preferred platform to **investigate**; production recovery stays BLOCKED.  
**Not done here:** no GCP project/bucket/HMAC/Secret Manager provisioning; no irreversible retention locks; no real production evidence; `verifiedCoverage` stays false.

## 1. Code contract under test (actual `ObjectLockBucket`)

Source: `services/auth-service/.../durable/ObjectLockBucket.java` via MinIO Java SDK.

| Operation | API / semantic assumption |
|-----------|---------------------------|
| Bucket discovery | `getObjectLockConfiguration` — refuse startup if lock absent |
| Put with retention | `putObject` + `Retention(mode, retainUntil)` + `Content-MD5` header |
| Retention inspect | `getObjectRetention(key, versionId)` |
| List versions | `listObjects(..., includeVersions=true)`; skip delete markers |
| Canonical read | **Oldest** non-delete-marker version is authoritative |
| Get by version | `getObject(..., versionId=…)` |
| Overwrite model | Never overwrite/delete; each put is a **new version** under lock |
| Auth | Endpoint + region + access/secret key (S3-style) |
| Failures | Wrapped as `DURABLE_RECORDING_UNAVAILABLE` (no secret leakage) |
| Enablement | `parkio.privacy.account-erasure.durable-store.object-lock.enabled` **default off** |

Retention duration in production remains **unset** until policy/legal basis exists. Tests use short synthetic GOVERNANCE/COMPLIANCE windows only. Discarded placeholder: 400 days. Engineering floor from current ops: offsite backup window **14 days** → technical floor ≥ **45 days** (14+30); recommended engineering floor before counsel **≥ 90 days**.

## 2. Google Cloud documentation evidence

Official docs reviewed:

- [Object Retention Lock](https://docs.cloud.google.com/storage/docs/object-lock) — per-object retain-until; XML modes `GOVERNANCE` / `COMPLIANCE` (`Unlocked` / `Locked`); feature must be enabled on the bucket (existing buckets: Console only); retained live versions may still become **noncurrent** under versioning.
- [Interoperability](https://docs.cloud.google.com/storage/docs/interoperability) — XML API + HMAC at `storage.googleapis.com`.
- [XML headers](https://docs.cloud.google.com/storage/docs/xml-api/reference-headers) — `x-goog-object-lock-mode`, `x-goog-object-lock-retain-until-date`, `?object-lock`, `x-goog-bypass-governance-retention` (not `x-amz-bypass-governance-retention`).
- [Secret Manager overview](https://docs.cloud.google.com/secret-manager/docs/overview) — suitable **future** home for trust-document HMAC material and storage HMAC credentials as **separate secrets**; not a substitute for Object Retention Lock.

Third-party S3-client reports (rclone, encryption gateways): GCS XML **does not fully implement AWS S3 Object Lock** (`GetObjectLockConfiguration` / `x-amz-*` retention paths often 501 or require `x-goog-*`). Retention *semantics* exist under Google’s Object Retention Lock, but **not** as a drop-in for the MinIO S3 Object Lock client.

## 3. Compatibility matrix

| Required behaviour | Doc evidence (GCS) | Local synthetic (MinIO IT) | Real-provider evidence |
|--------------------|--------------------|----------------------------|------------------------|
| Bucket retention-feature discovery | `?object-lock` / Console enable; not identical to S3 `GetObjectLockConfiguration` | Covered by MinIO ITs | **Not run** (no paid bucket) |
| Upload + enforced retention | `x-goog-object-lock-*` on PUT | MinIO `Retention` + Content-MD5 | **Not run** |
| Retention inspect | GET object retention / HEAD headers | `getObjectRetention` IT | **Not run** |
| Version list + pagination | Generations / versions XML; interop list-format quirks | `includeVersions` IT | **Not run** |
| Oldest-version canonical + frontier | Generations ordered differently than S3 version listing assumptions | `ObjectLockFrontierVersionOrderIT` | **Not run** |
| Concurrent / retry puts | New generation each write | Partial-publication IT | **Not run** |
| Auth least-privilege | Route A: HMAC + IAM; route B (selected): JSON API, keyless impersonation, custom roles (#483 plan §7.4) | Synthetic keys | **Not run** |
| Missing perms / objects | Provider errors | Mapped to `DURABLE_RECORDING_UNAVAILABLE` | **Not run** |
| Refuse shorten under COMPLIANCE | Doc: COMPLIANCE cannot shorten/remove | IT refuse paths | **Not run** (irreversible locks forbidden in research) |
| Key access after host loss | Secret Manager / escrow separate from bucket | Custody procedure | **Not run** |

**Verdict:** Route **A** (reuse current MinIO/S3 adapter unchanged against GCS) is **not** demonstrably supported. Emulators cannot establish immutability acceptance.

## 4. Selected technical route

**B — Implemented (default-off, #482):** Narrow Google Cloud Object Retention Lock adapter behind extracted `DurableEvidenceBucket`. Design detail: `docs/operations/n02-gcs-evidence-adapter-design.md`. Canonical generation = **lowest generation number**. MinIO Object Lock remains available and mutually exclusive. Real-provider acceptance: **#483** / `docs/operations/n02-gcs-provider-acceptance-plan.md` (harness ready; real-provider column above stays "Not run" until the approved run).

**C — Contingency:** If real GCS validation (#483) shows the Google adapter cannot preserve oldest-generation / COMPLIANCE semantics at reasonable complexity, retain a **dedicated AWS S3 Object Lock** evidence bucket even if application hosting moves to GCP.

## 5. Secret Manager (future)

Evaluate Secret Manager to hold, as **distinct secrets**:

1. Erasure-evidence trust document (HMAC keys) — custodian `ADBERILGEN35`
2. Cloud Storage HMAC (or workload identity) for the evidence bucket
3. Backup encryption passphrase — **never** the same secret as (1)

Not provisioned in this change.

## 6. Acceptance split

| Issue | Closes when |
|-------|-------------|
| #366 | This design + custody facts recorded; evidence verifiable without trusting restored app data (by contract) |
| #367 | Default-off stub E2E `scripts/test-production-recovery-evidence.sh` red→green; production refusal gates preserved |
| #365 | Remains open until production coverage gap (legal + real-provider validation + enablement) is actually closed |
| Follow-up (later) | Real GCS provider validation under irreversible-lock discipline — **not** opened unless #365 AC requires a separate tracked item |
