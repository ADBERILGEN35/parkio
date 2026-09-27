# Isolated persist-before-ACK slice

Draft implementation. Depends on reviewed #118
(`70ee73a00d455f76a2b739fa08204eda89cd382d`). Not a merge of #118.
#104 remains HOLD and is not imported.

This slice implements the **proposed enabled protocol** in isolated
Python. Production auth-service still returns public `IN_PROGRESS` after
commit and still marks `COMPLETE` after participant acks only. The new
flag `parkio.privacy.account-erasure.durable-recording-enabled` defaults
to **false** and is not read by Java.

`verifiedCoverage` stays false. Restore-hosted-beta refusal is unchanged.

## Public vs internal status

| Surface | Values |
|---|---|
| Public API | `IN_PROGRESS` until `COMPLETE` (unchanged) |
| Internal recording | `PENDING_DURABLE` or `DURABLY_RECORDED` |

When the isolated protocol is enabled, `COMPLETE` requires durable
recording **and** every required participant ACK bound to
`recoveryAttemptId`, `restoredDatasetId`, and `erasureSetDigest`.

## What this store is

`IsolatedVersionedStore` is a directory with if-not-exists puts. It can
outlive an application process in the same filesystem. That is
**process-crash-local** persistence. It is **not** off-host durability
and **not** WORM. A local container volume does not change that class.

## External store requirements (not provisioned)

Do not create Azure resources or set immutability from this slice. The
existing Azure storage account is only a **future candidate**.

Required properties, not a selected production config:

1. Not on the primary application host.
2. Conditional write (`If-None-Match` / if-not-exists) for
   `records/{erasureRequestId}.json`.
3. List-by-prefix for `records/`, `sequences/`, `checkpoints/`.
4. Versioning so an overwrite cannot silently replace a record.
5. Deletion resistance (object lock / legal hold / equivalent) before
   any production enablement.
6. Least-privilege identity: put/get/list only on the erasure prefix.
7. Clock independence: consumers order by signed `sequence`, not blob
   last-modified.

Until those exist, recovery after true primary-host loss cannot be
certified. `certifiedOffHostWorm` stays false.

## Identity, sequence, and gaps

- `erasureRecordId` is `records/{erasureRequestId}.json`. Retry of the
  same request reuses that key.
- Sequences are reserved per request, then bound into the signed record.
- Recovery walks published pending records and checkpoints from
  sequence 1. A later sequence without 1..n is a gap. A higher number
  is not completeness.
- Missing records or a cutoff beyond the contiguous trusted prefix:
  `BLOCKED`, copy unexposed.

## Next reviewable decision

Whether to wire `durable-recording-enabled` into auth-service
`completeLocal` **after** an off-host store meeting the properties
above exists. Not this PR. Not #104.