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

1. **Retention feature discovery** — refuse startup unless Object Retention Lock is enabled on the bucket (not merely Bucket Lock) and Object Versioning is on (ORL does not imply versioning; without it a rewrite of a retained name is refused, and after expiry it would replace the live generation).
2. **Put + retention** — every write creates a new generation with GOVERNANCE (Unlocked) or COMPLIANCE (Locked) retain-until.
3. **Retention inspect** — receipt requires a lock on the canonical generation.
4. **List + pagination** — transport pages generations; adapter aggregates pages. One listing fails closed (`DURABLE_RECORDING_UNAVAILABLE`) on a page token it has already followed, when it would need more than `list-max-pages` pages, or when it runs past `list-deadline`, a page in flight included (#493, #616).
5. **Canonical oldest** — **lowest generation number**, never list encounter order (GCS may return newest-first).
6. **Frontier chains** — recovery reads all generations of `frontier/expected-through.json` via `allVersions` / `findAll`; the store reads only the live generation and falls back to all of them when it does not verify (#494).
7. **Concurrent / retry** — first generation wins; identical body → existing; different body → conflict.
8. **Auth** — ADC / workload identity for storage; trust HMAC keys remain separate (custody doc).
9. **Failures** — missing perms / unavailable → `DURABLE_RECORDING_UNAVAILABLE` (no secret leakage).
10. **Mutual exclusion** — `durable-store.gcs.enabled` XOR `durable-store.object-lock.enabled`.
11. **Recovery replay** — GCS writer is in `RecoveryReplayPreflight.WRITERS` (must be off on restored copies).
12. **Conditional writes** (#494, `docs/architecture/erasure-evidence-conditional-writes.md`) — markers, records, checkpoints and reservation index entries are created with `ifGenerationMatch=0`; the frontier is replaced with `ifGenerationMatch=<live generation>`; a failed precondition (412) writes nothing. The live generation is read by name, then its bytes by generation.

## Explicit non-translations from S3

| S3 assumption | GCS reality | Adapter rule |
|---------------|-------------|--------------|
| `versionId` opaque string; list newest-first; oldest = last list item | Generations are monotonic ints; list order not write order | Canonical = **min(generation)** |
| `GetObjectLockConfiguration` | Bucket `objectRetention` mode ENABLED; versioning separate | `requireObjectRetentionEnabled` checks both |
| `x-amz-object-lock-*` | `x-goog-object-lock-*` / JSON Retention | SDK `BlobInfo.Retention` (Locked/Unlocked) |
| Delete markers | Soft-delete / noncurrent differ | Transport ignores S3 delete-marker semantics |

If a future real-provider run proves min-generation ≠ first-write under a documented GCS edge case, stop and revise — do not weaken canonicalization.

## Enablement (default off)

```yaml
parkio.privacy.account-erasure.durable-store.gcs.enabled: false  # PARKIO_ERASURE_STORE_GCS_ENABLED
```

Operator inputs when enabled: `project-id`, `bucket`, `retention-mode`, `retention`, `trust-file`, `producer-key-id`. Production retention duration remains **unset** in policy; test retention must stay short and synthetic.

Listing bounds, optional (#493, #616):

| Setting | Default | Range | Environment variable |
|---------|---------|-------|----------------------|
| `list-max-pages` | `1000` (pages of 100 generations) | 1 to 10000 | `PARKIO_ERASURE_STORE_GCS_LIST_MAX_PAGES` |
| `list-deadline` | `30s`, for the whole listing, a page in flight included | above 0, at most `5m`; a bare number is seconds | `PARKIO_ERASURE_STORE_GCS_LIST_DEADLINE` |

A value outside its range stops startup when the store is enabled. The upper limits are ten times the defaults, so no configured value switches a bound off.

### Sizing the listing bounds (#616)

- **What a listing can hold.** A listing reads 100 generations per page. With pages averaging *t*, it
  completes with up to about 100 × min(`list-max-pages`, `list-deadline` ÷ *t*) generations. With the
  defaults the deadline binds first once pages average more than 30 ms: 100,000 generations at 30 ms a
  page or less, 30,000 at 100 ms, 10,000 at 300 ms.
- **Which listings grow.** Since #494 the store's normal path lists single objects only, one page each.
  The listings that grow with the history are:
  - every version of the frontier, read by recovery and by the store's fallback when the live version
    does not verify (or none is live although versions exist). It has the bucket's first boundary
    (0, 0), then one generation per new record and one per checkpoint; an identical retry adds none
    (`ObjectLockConditionalWriteProtocolTest.theFrontierGainsOneGenerationPerNewRecordAndPerCheckpoint`);
  - recovery's listings of `sequences/` (one marker per record and per checkpoint) and `records/`
    (one per record);
  - each checkpoint's listings of `checkpoints/` (one per checkpoint) and `reservations/checkpoints/`
    (one per abandoned checkpoint reservation).

  So the largest listing holds about *records + checkpoints + 1* generations. At 100 ms a page the
  defaults cover 30,000: with hourly checkpoints (8,760 a year) and 1,000 erasures a year, about three
  years. The checkpoint cadence, still open under #365 (D3), dominates the growth.
- **Early warning.** A listing that completes past half of either bound logs at WARN
  `durable store list <prefix> needed <n> of at most <max> pages and <time> of its <deadline> deadline,
  past half of a bound`. A log alert on it leaves time to raise the bounds (up to 10,000 pages and
  5 minutes) before such listings fail closed and new erasures wait in `PENDING_DURABLE`.
- **A page in flight.** Each page is requested on a virtual thread of its own and waited for at most
  what is left of the deadline. When the deadline passes first, the listing fails closed at once and
  interrupts that thread: the request may run on until the client's own limits end it (by default
  20 s connect and read timeouts, 6 attempts within 50 s), but no longer under the store's lock.
  `GcsObjectRetentionBucketTest` stalls a page with a stub transport, and
  `LiveGcsAcceptanceProviderRequestShapeTest` does it with the real client against a local endpoint.
- **Per listing, not per operation.** The bounds apply to each listing (`keys`, `oldest`,
  `allVersions`, `versionCount`). A store operation that lists several times can hold the store's lock
  for several deadlines. A tool built with an adapter constructor that takes no bounds gets the
  defaults; a recovery tool that reads a longer history must pass its own `ListBounds`.

## Local vs real evidence

| Evidence | Counts for |
|----------|------------|
| `GcsObjectRetentionBucketTest` + config tests (in-memory transport) | #482 behavioural / wiring |
| Emulator | **Does not** satisfy #483 |
| Dedicated real GCS bucket under #483 plan (`gcsProviderAcceptance run`) | #483 only |
| `gcsProviderAcceptance plan`, `GcsAcceptanceToolTest`, request-shape test | Harness self-checks only |
