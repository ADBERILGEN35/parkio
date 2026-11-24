# Durable erasure store: conditional writes (#494)

Recorded 2026-11-24. Parent: #365 (layer D). Owner decision 2026-11-23: conditional writes.

`ObjectLockDurableErasureRecordStore` writes erasure evidence (format v2,
`docs/operations/recovery-evidence-contract.md`) to a retention-locked bucket, either GCS
Object Retention Lock or S3/MinIO Object Lock. Before this change, every write listed the
recorded history. This note describes the protocol that replaces those listings. The Java
store and the Python model (`scripts/lib/recovery_persist_protocol.py`) follow it step by
step. There is one structural difference: the model store keeps a single frontier version. A
frontier that fails verification therefore fails the model's write, where Java falls back to
another verified version. The store stays off by default on both provider routes.

## Problem

At *n* recorded erasures, one new record cost about *n* + 2*n*/100 list requests, all of them
while the store held its writer lock:

- `reserveSequence` listed every marker under `sequences/` to find this request's earlier
  reservation and the highest sequence. Each marker then needed one more `oldest(key)` list.
- Each frontier read listed every generation of `frontier/expected-through.json`, two per
  record. Every operation read the frontier.

The scale test measured this protocol over the GCS adapter (`ObjectLockDurableErasureRecordStoreScaleTest`,
commit 1 of the PR). With 1,000 recorded erasures it cost:

| Operation | Requests |
|---|---|
| New record | 4,063 |
| Checkpoint | 1,075 |

## Decision

A new sequence starts just above the signed frontier's `highestReserved`. Two kinds of provider
precondition replace the listings:

| Write | GCS | S3 / MinIO | Effect |
|---|---|---|---|
| Create only if absent | `ifGenerationMatch=0` | `If-None-Match: *` | Markers, records, checkpoints and index entries get one owner. |
| Replace only the version read | `ifGenerationMatch=<live generation>` | `If-Match: <ETag>` | The frontier is replaced by compare-and-swap, never from a stale read. |

When a precondition fails, the provider answers 412, nothing is written, and the call returns
empty. `ObjectLockDurableErasureRecordStoreIT` pins this on the MinIO image that the integration
tests use (RELEASE.2024-09-13). Without enforcement, the frontier's compare-and-swap would silently
become last-writer-wins.

## Objects

| Object | Key | Written | Status |
|---|---|---|---|
| Sequence marker | `sequences/<16 digits>.json` | Create only if absent | Format unchanged |
| Pending record | `records/<erasureRequestId>.json` | Create only if absent | Format unchanged |
| Checkpoint | `checkpoints/<16 digits>.json` | Create only if absent | Format unchanged |
| Frontier | `frontier/expected-through.json` | Compare-and-swap on the live version | Format unchanged |
| Request reservation | `reservations/<erasureRequestId>.json` | Create only if absent, only by a walk | **New**, writer state |
| Checkpoint reservation | `reservations/checkpoints/<16 digits>.json` | Create only if absent, only by a walk | **New**, writer state |

The two reservation entries are unsigned canonical JSON:

- request reservation: `{"erasureRequestId", "kind": "sequence-reservation", "sequence"}`;
- checkpoint reservation: `{"kind": "checkpoint-reservation", "sequence"}`.

They are not evidence:

- evidence bundles keep the three evidence prefixes;
- the verifier never reads the entries;
- a forged entry costs availability only and never changes what recovery reads (see
  [Limits](#limits)).

Like a forged marker, a forged entry needs write access to the bucket.

## Protocol

### Record (`putIfAbsent`)

1. If the record exists, settle it: verify it and raise the frontier to its sequence.
2. Read the frontier. If no version is live, first write one with a create-only write:
   - (0, 0) in a new bucket (see [Why the zero boundary](#why-the-zero-boundary));
   - if versions exist but none is live, the highest verified boundary again.
3. Reserve a sequence:
   1. If this request's index entry exists, take its sequence. Its marker must name this
      request; otherwise the call fails closed.
   2. Otherwise walk up from `highestReserved` + 1 and claim each marker with a create-only
      write. Read the canonical (first) version back.
   3. If the marker names this request, keep the sequence. This happens on the first try, or
      when a retry finds the marker of an attempt that stopped.
   4. If it names another owner, write that owner's index entry if it is missing, and go on.
4. Publish the record with a create-only write and read the canonical version back.
5. Settle: verify, read the receipt, and raise `expectedThrough` and `highestReserved` together
   in **one** compare-and-swap.

A checkpoint (`publishCheckpoint`) follows the same steps with the nil-UUID marker, with two
differences:

- Before walking, it takes the lowest indexed checkpoint reservation that has no checkpoint. It
  finds that by listing `reservations/checkpoints/` and `checkpoints/`.
- A checkpoint marker found on the walk without its checkpoint is taken as well.

### Why one index entry per stopped writer is enough

The frontier rises only after publication, so every marker above `highestReserved` belongs to
a writer that stopped before publishing.

A later writer walks up from `highestReserved` + 1, so it meets every such marker below the
sequence it claims. Before its own frontier raise passes a marker, it indexes it.

So for any unpublished marker, one of two things holds:

| Where the marker is | How its request finds it |
|---|---|
| Still above the frontier | Its own retry's walk meets it again. |
| Below the frontier | It has an index entry, written by the writer that passed it. |

The retry fills the gap. The next checkpoint fills an abandoned checkpoint reservation. The
normal path writes no index entry.

| What a writer leaves when it stops | What completes it |
|---|---|
| Marker only, before any other writer passed it | Its retry's walk reaches its own marker again. |
| Marker, then another writer passed it | The retry finds its index entry; recovery is BLOCKED (gap) until then. |
| Marker and record, frontier not raised | The retry finds the record and settles it; a later put also covers it. |
| Checkpoint marker only | The next checkpoint takes it, through the walk or the checkpoint index. |

### Why the zero boundary

Before this change, the first reservation of a bucket raised `highestReserved` before
publishing. A first reservation lost before its record therefore left a frontier (0, 1), and
recovery answered BLOCKED: "expected boundary is present but published records are incomplete".

The frontier now rises only after publication. Without the zero boundary, the same loss would
leave no frontier, and recovery would answer UNKNOWN. Writing (0, 0) once, before the first
reservation, keeps BLOCKED for that case. Both languages keep the existing tests and fixture
verdicts. It costs one write per bucket.

### Frontier reads and writes

- **Reads.** A read is one read of the live version: on GCS, its generation and then its bytes;
  on S3, a GET without a version id. The base of a read never drops below a boundary that this
  process has read or written. If the live version fails verification, or no version is live
  although versions exist, the store reads the highest verified of all versions, as recovery
  does, and logs the ignored versions. The next write then replaces the live version on top of
  that.
- **Writes.** A write names the live version it builds on. If that version is no longer live,
  the store reads again and retries, at most three times. After that the call fails closed with
  `DURABLE_RECORDING_UNAVAILABLE`.
- **Resends.** Only the adapter resends a write.
  - On GCS every insert goes through a copy of the client with one attempt per request. The
    client would otherwise treat a conditional insert as idempotent and retry it for up to its
    50 s budget, before each of the adapter's resends.
  - The adapter resends a write refused with 429 after each of `RATE_LIMIT_PAUSES`. Any other
    failure fails the call at once, to the retry worker.
  - One write therefore makes at most five sends, each within the client's request timeouts, and
    pauses at most 16.5 s while the store's lock is held. `LiveGcsAcceptanceProviderRequestShapeTest`
    pins this for both conditional writes.
  - S3 sends each write once.
- **Lost responses.** A write whose response is lost fails the call. The next call's conditional
  write meets that version (412), and the read-back resolves it. A record therefore reports
  `existing`, and a checkpoint fills the reservation it finds; both are correct for that call.

## Costs

Requests per operation at 1,000 and at 10,000 recorded erasures, from
`ObjectLockDurableErasureRecordStoreScaleTest` over the GCS adapter. A live read counts as two
requests. The history is as long as the earlier protocol left it.

| Operation | Before, at 1,000 | Now, at 1,000 | Now, at 10,000 |
|---|---|---|---|
| New record (`putIfAbsent`) | 4,063 | 10 | 10 |
| Identical retry | 24 | 4 | 4 |
| `findByRequestId` | 23 | 4 | 4 |
| `publishCheckpoint` | 1,075 | 8 | 8 |

For checkpoints, the two index listings grow with the number of checkpoints, one page per 100,
and not with records.

The Python model measures 9 store requests per erasure at both sizes
(`test_requests_per_erasure_do_not_grow_from_1000_to_10000_recorded_erasures`).

The frontier now gets one write per record instead of two. This has two effects:

- One call no longer meets Cloud Storage's write limit of about one write per second on its own;
  two calls in a row still can (case S5 of the #483 plan).
- A record leaves three retained objects (marker, record, frontier) instead of four.

## What does not change

- **Formats.** Signatures, the formats of all evidence objects, and the verifier's rules in
  Java and Python are unchanged. Recovery still reads every frontier version and takes the
  highest verified one.
- **Fixtures.** Only one shared fixture changes. The `abandoned-reservation` frontier is now
  (0, 0) instead of (0, 1); its verdict is still BLOCKED, with abandoned reservation [1].
- **Gate.** The store stays default-off. `verifiedCoverage` stays false, and production restore
  stays refused.
- **One writer per bucket.** The conditional writes keep a second writer from replacing evidence
  or the frontier on a stale read. The index assumes one writer.

## Limits

- **Frontier replay.** A writer with access to the bucket can replay an older valid frontier
  version as the live one. This costs requests, not safety: the walk skips claimed markers, and
  recovery still uses the highest verified version. On MinIO, a backward clock step can make an
  older version current in the same way.
- **No migration, and no mixed writers.** Buckets written by the earlier protocol exist only in
  tests and are not migrated. Their reservations have no index entries, so a request whose old
  reservation lies below the frontier would get a new sequence, and its old marker would stay a
  gap. Old and new writers must never write one bucket either: an old writer raises
  `highestReserved` before it publishes, so a new writer starts above that marker without
  indexing it.
- **Index entries are unsigned.** Forging one costs availability only:
  - A forged request entry that points at another request's marker fails that request's writes.
  - A forged checkpoint entry fails every later checkpoint, for as long as it is retained. This
    covers an entry with no marker, with a request's marker, or with a name that is not a
    sequence.
