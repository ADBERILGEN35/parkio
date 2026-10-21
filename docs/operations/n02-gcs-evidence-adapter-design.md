# N-02 — GCS Object Retention Lock adapter design (#482)

**Recorded:** 2026-10-21  
**Parent:** #365 · **Implementation:** #482 · **Real-provider acceptance:** #483  
**Not claimed:** production enablement, paid provisioning, `verifiedCoverage=true`.

## Layers (do not conflate)

| Layer | Status |
|-------|--------|
| A. Bundle-validation wrapper (`production-recovery-evidence.sh`) | Done (#367 / #481) |
| B. Provider I/O adapter (this document + code) | Default-off GCS + extracted `DurableEvidenceBucket` |
| C. Real-provider acceptance | #483 only |
| D. Production export / enablement / exposure / legal retention | Remains on #365 |

## Contract preserved

Same as MinIO `ObjectLockBucket`, via `DurableEvidenceBucket`:

1. **Retention feature discovery** — refuse startup unless Object Retention Lock is enabled on the bucket (not merely Bucket Lock).
2. **Put + retention** — every write creates a new generation with GOVERNANCE (Unlocked) or COMPLIANCE (Locked) retain-until.
3. **Retention inspect** — receipt requires a lock on the canonical generation.
4. **List + pagination** — transport pages generations; adapter aggregates pages.
5. **Canonical oldest** — **lowest generation number**, never list encounter order (GCS may return newest-first).
6. **Frontier chains** — all generations of `frontier/expected-through.json` via `allVersions` / `findAll`.
7. **Concurrent / retry** — first generation wins; identical body → existing; different body → conflict.
8. **Auth** — ADC / workload identity for storage; trust HMAC keys remain separate (custody doc).
9. **Failures** — missing perms / unavailable → `DURABLE_RECORDING_UNAVAILABLE` (no secret leakage).
10. **Mutual exclusion** — `durable-store.gcs.enabled` XOR `durable-store.object-lock.enabled`.
11. **Recovery replay** — GCS writer is in `RecoveryReplayPreflight.WRITERS` (must be off on restored copies).

## Explicit non-translations from S3

| S3 assumption | GCS reality | Adapter rule |
|---------------|-------------|--------------|
| `versionId` opaque string; list newest-first; oldest = last list item | Generations are monotonic ints; list order not write order | Canonical = **min(generation)** |
| `GetObjectLockConfiguration` | Bucket `objectRetention` mode ENABLED | `requireObjectRetentionEnabled` |
| `x-amz-object-lock-*` | `x-goog-object-lock-*` / JSON Retention | SDK `BlobInfo.Retention` (Locked/Unlocked) |
| Delete markers | Soft-delete / noncurrent differ | Transport ignores S3 delete-marker semantics |

If a future real-provider run proves min-generation ≠ first-write under a documented GCS edge case, stop and revise — do not weaken canonicalization.

## Enablement (default off)

```yaml
parkio.privacy.account-erasure.durable-store.gcs.enabled: false  # PARKIO_ERASURE_STORE_GCS_ENABLED
```

Operator inputs when enabled: `project-id`, `bucket`, `retention-mode`, `retention`, `trust-file`, `producer-key-id`. Production retention duration remains **unset** in policy; test retention must stay short and synthetic.

## Local vs real evidence

| Evidence | Counts for |
|----------|------------|
| `GcsObjectRetentionBucketTest` + config tests (in-memory transport) | #482 behavioural / wiring |
| Emulator | **Does not** satisfy #483 |
| Dedicated real GCS bucket under #483 plan | #483 only |
