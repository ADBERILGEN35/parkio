# N-02 — Real GCS provider acceptance plan (#483)

**Status:** reviewable plan only. **No** project/bucket/IAM/retention lock is created by this document.  
**Parent:** #365 · **Blocked by:** #482 (adapter merged) + explicit cloud authorization.  
**Data:** synthetic evidence bytes only. **Production retention duration:** remains unset.

## Distinction: object retention vs bucket lock

| Mechanism | What it does | This plan |
|-----------|--------------|-----------|
| **Object Retention Lock** | Per-object retain-until; Unlocked=GOVERNANCE, Locked=COMPLIANCE; feature enable on bucket is irreversible | **Required** for #483 |
| **Bucket Lock** (bucket retention policy) | Uniform retention for all objects in the bucket | **Do not** enable for the test bucket unless separately authorized |

Enabling Object Retention Lock on a bucket applies a project-delete lien (best effort) and **cannot be disabled**. Treat that as irreversible for the test project.

## Missing cloud facts (ask once before execution)

Do **not** invent these. Required before any paid resource:

1. Google Cloud **project id** approved for Parkio migration / test use.
2. **Region** for the dedicated test bucket.
3. Whether a **service account** (or workload identity) already exists for evidence-store tests, or authorization to create one with least privilege.
4. Explicit **yes** to: create one dedicated test bucket, enable Object Retention Lock on it, apply **short** Locked/Unlocked retain-until on synthetic objects, accept objects may be undeletable until retain-until, and accept estimated cost below.

Inspected for approved migration config: none assumed present in-repo for a live GCS evidence project. If an owner later points at a read-only Terraform/env file, reconcile this plan against it before execution.

## Dedicated test resources (proposed shape)

| Resource | Proposal (not provisioned) |
|----------|----------------------------|
| Project | Owner-named test or migration sandbox — **not** production |
| Bucket | `parkio-erasure-evidence-accept-<yyyyMmDd>` — versioning on, Object Retention Lock enabled, no public access |
| IAM (least privilege) | `storage.objects.create`, `get`, `list`; retention get/set as required by API; **no** `storage.buckets.delete` until retain-until passes; separate from app-host SA |
| Trust HMAC | Synthetic fixture keys only; never production trust document |
| Storage auth | Distinct SA / ADC; never the erasure trust secret |

## Short test retention (synthetic)

| Mode | Suggested retain-until | Consequence |
|------|------------------------|-------------|
| GOVERNANCE (Unlocked) | now + **2 hours** | May be shortened/removed by authorized principal after test |
| COMPLIANCE (Locked) | now + **24 hours** | **Cannot** shorten/remove; object (and often bucket delete) blocked until expiry |

Justify 24h Locked: enough to run the matrix once and re-check overnight; short enough to bound cost. **Do not** infer production duration from this.

## Operations, duration, cost (estimate)

- Ops: ~20–40 API calls (feature check, puts, lists with pagination, gets, retention inspect, negative IAM).
- Wall time: &lt; 30 minutes active + wait only if re-verifying Locked objects next day.
- Storage: &lt; 1 MiB synthetic JSON.
- Cost: negligible GCS Class A/B ops + storage; dominant risk is **irreversible Object Retention Lock** and Locked object undeletability until retain-until — not dollars.

## Acceptance cases (pass/fail)

| Case | Pass |
|------|------|
| Feature discovery | Startup/`requireObjectRetentionEnabled` succeeds only when Object Retention Lock is enabled |
| Put + retention | New generation with retain-until; bytes readable by generation id |
| Retention inspect | Reported mode/retain-until match |
| Pagination | Multi-page list still yields complete generation set; **oldest = min(generation)** |
| Concurrent / retry | Second put new generation; canonical remains first; identical retry settles existing |
| Least privilege | SA without list/create fails closed (`DURABLE_RECORDING_UNAVAILABLE`) |
| Unavailable | Fault injection / wrong bucket fails closed |
| COMPLIANCE shorten | Shorten attempt refused |
| Incomplete coverage | Gap/missing frontier still refused by existing verifier (synthetic fixtures) |

Emulators/mocks: **fail** this issue by definition.

## Cleanup

1. GOVERNANCE objects: remove retention (if allowed) then delete generations.  
2. COMPLIANCE objects: **wait until retain-until**; then delete.  
3. Bucket delete only after all retained objects are gone.  
4. Remove test SA keys; leave project lien notes in the #483 comment.  
5. Never upload production evidence; never copy production trust files into the test project.

## Harness

Prefer driving the merged `#482` adapter (`GcsObjectRetentionBucket` / store) with ADC against the dedicated bucket. Optional thin script under `scripts/` may be added in the #483 implementation PR — not required to approve this plan.
