# Isolated persist-before-ACK slice

Draft implementation. Depends on reviewed #118
(`70ee73a00d455f76a2b739fa08204eda89cd382d`). Not a merge of #118.
#104 remains HOLD and is not imported.

This slice implements persist-before-COMPLETE in isolated Python **and**
wires the same default-off flag into auth-service. Production defaults
keep `parkio.privacy.account-erasure.durable-recording-enabled=false`.
When the flag is off, `completeLocal` still completes from participant
ACKs only. When the flag is on, COMPLETE requires durable evidence for
that request plus every required participant ACK.

`verifiedCoverage` stays false. Restore-hosted-beta refusal is unchanged.

## Public vs internal status

| Surface | Values |
|---|---|
| Public API | `IN_PROGRESS` until `COMPLETE` (unchanged) |
| Internal recording | `NULL` (flag off), `PENDING_DURABLE`, or `DURABLY_RECORDED` |

V24 adds `erasure_requests.durable_recording_status`. Existing V21 rows
are not rewritten. Public `status` is unchanged.

## Expected boundary (missing tail)

Recovery does **not** treat a contiguous listed prefix, or the maximum
sequence returned by listing, as completeness.

The independently durable expected boundary is the signed store object
`frontier/expected-through.json`:

- `expectedThrough` advances only after a record is published.
- `highestReserved` advances when a sequence reservation is allocated.
- Concurrent reservations use if-not-exists sequence markers; the
  frontier is overwritten monotonically under the coordinator lock.
- An abandoned reservation (sequence allocated, record never published)
  does not increase `expectedThrough`. If a later sequence publishes,
  `1..expectedThrough` contains a hole and recovery is `BLOCKED`.
- Total loss of local high-water / coordinator memory is irrelevant:
  recovery reads only the store frontier and published records.
- If the frontier is missing, completeness cannot be established:
  verdict `UNKNOWN`.
- If the frontier is present and any `1..expectedThrough` record is
  missing, verdict `BLOCKED`.

## Auth lifecycle (flag on)

`requestDeletion` still commits tombstone, request, revoke, and outbox
in one auth TX and still returns public `IN_PROGRESS`. External persist
runs **after commit** (or immediately when no TX is active). The status
column is then marked `DURABLY_RECORDED` in a short new TX.

Arrival orders:

1. Durable record first, then participant ACKs → COMPLETE on last ACK.
2. Participant ACKs first, then durable record → COMPLETE on persist
   retry. ACKs alone cannot COMPLETE.

Failed or ambiguous persist leaves `PENDING_DURABLE` / `IN_PROGRESS`.
Enabled without a `DurableErasureRecordStore` bean fails with
`DURABLE_RECORDING_UNAVAILABLE`. There is no local-directory production
adapter and no silent fallback.

The Java ITs use a test-only in-memory adapter plus disposable
PostgreSQL. They prove integration behavior, not off-host WORM.

## What this store is

`IsolatedVersionedStore` is a directory with if-not-exists puts. It can
outlive an application process in the same filesystem. That is
**process-crash-local** persistence. It is **not** off-host durability
and **not** WORM. A local container volume does not change that class.
Do not use it as a production durability provider.

## External store requirements (not provisioned)

Do not create Azure resources or set immutability from this slice. The
existing Azure storage account is only a **future candidate**.

Required properties, not a selected production config:

1. Not on the primary application host.
2. Conditional write (`If-None-Match` / if-not-exists) for
   `records/{erasureRequestId}.json`.
3. Conditional write for the signed frontier
   `frontier/expected-through.json` so `expectedThrough` cannot silently
   move backwards.
4. List-by-prefix for `records/`, `sequences/`, `checkpoints/`,
   `frontier/`.
5. Versioning so an overwrite cannot silently replace a record.
6. Deletion resistance (object lock / legal hold / equivalent) before
   any production enablement.
7. Least-privilege identity: put/get/list only on the erasure prefix.
8. Clock independence: consumers order by signed `sequence` and the
   signed frontier, not blob last-modified.
9. Operator recovery after total local-state loss must still read the
   frontier from this store; listing max is not a substitute.

Until those exist, recovery after true primary-host loss cannot be
certified. `certifiedOffHostWorm` stays false. Do not set
`PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_ENABLED=true` in production.
