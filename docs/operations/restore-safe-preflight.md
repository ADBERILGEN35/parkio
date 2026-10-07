# F-03 safe restore preflight - source draft and release decision

Draft only. Not authorization to merge, install, or run a real restore.
Head reviewed from origin/api b5a86ed8 (includes #105/#106/#107).
#104 remains HOLD at c24f4f3de07d31c359c33971aba457041714705c. #108 untouched.

## Executable production refusal

Production `restore-hosted-beta.sh` and `restore-database.sh` return BLOCKED
(exit 3) before decrypt or apply when verified coverage is absent. A
manifest timestamp, merged identifier set, caller cutoff, or
`--supplemental-covered-through` is not verified coverage. This includes
cutoffs equal to or earlier than the stamp clock.

Exact refusal: `parkio_restore_refuse_unverified_production` in
`scripts/lib/restore-safe-preflight.sh`. Additional production refusals:
standalone `restore-database.sh` (`parkio_restore_refuse_standalone_database`)
and `--only minio` (`parkio_restore_refuse_unsupported_production_scope`).

`PARKIO_RESTORE_ISOLATED_DRILL` and `PARKIO_RESTORE_PREFLIGHT_DONE` alone
do not bypass those refusals. Isolation requires `--isolated-fixture` plus a
destination-bound ticket issued by `scripts/restore-isolated-fixture.sh` after
that orchestrator has created the allowed PostgreSQL/MinIO targets. The restore
entrypoints will not issue their own ticket from a CLI flag. The ticket binds
the selected stamp to live docker context/host/engine and to container, network
and volume identities; apply uses those same identities after a live re-inspect.
A container name, environment flag, or marker file is not isolation proof.
Ticket `bodyDigest` is SHA-256 integrity of the ticket body. It is not
producer authentication: a process that can write a well-formed ticket can
also compute a matching digest.

Supported fixture volume topology is narrow: Docker `Driver=local`,
`Scope=local`, empty `Options`, labels `parkio.isolated.fixture=1` and
`parkio.isolated.project=<parkio-iso-12-hex>`, and consumers limited to the
ticket container IDs. Each fixture container must mount exactly one such
volume. Local-driver bind options (`type=none` / `device` / `o=bind`), NFS,
CIFS, or any other storage options that can reference host or remote data
are rejected on apply authorization and on teardown. Unrelated containers
attached to a fixture-named volume are rejected.

`restore-isolated-fixture.sh down` validates schema, digest, project, and
the current docker daemon/context, inspects every remaining target, and
authorizes fixture ownership before any `rm`. It deletes only recorded
container/network IDs (never an unchecked name fallback) and verified
fixture volumes. Stopped containers and already-absent objects are
idempotent; inspect/permission/daemon errors fail closed. Incomplete
cleanup returns nonzero and retains the ticket. The ticket file is removed
only after verified cleanup.

Remaining limits: a malicious root operator can edit these scripts, forge
docker labels, or point the CLI at another daemon they control. Isolation
does not survive a compromised docker engine. Production restore remains
refused while `verifiedCoverage` is false.

This closes accidental/misrouted use of the supported restore scripts. It does
not stop a malicious root operator who can edit the scripts.

Dry-run is unchanged (no decrypt/apply of databases). Restore drill 01 does not
call these production entrypoints; it uses the helper tools and its own apply
path. Isolated-fixture acceptance drives `restore-hosted-beta.sh` and
`restore-database.sh` against disposable targets.

## Coverage model

Separated fields in `restore-erasure-ledger.py`:
- merged identifiers (`--out`)
- declared snapshot time (`declaredSnapshotThrough` / snapshotClockVerdict)
- verified coverage (`verifiedCoverage` is always false)

No attestation mechanism is implemented. The cutoff is never lowered.

## Erasure application

- restore-hosted-beta.sh all/databases: replay only on the isolated-fixture path,
  against ticket-bound auth identities
- restore-hosted-beta.sh --only minio: refused in production; isolated fixture
  applies only to the ticket MinIO identity, never `parkio-minio` / `http://minio:9000`
- restore-database.sh: refused in production; isolated apply uses ticket
  container IDs, never production `parkio-postgres-*` defaults
- Restore drill 01: separate path; proves helper replay + ACTIVE=0 on synthetic isolated Postgres

## Stage-4 isolated recovery (U02)

Isolated only; a production restore stays refused whatever these options say. The steps, with
the synthetic drill (`scripts/recovery-drill.sh`) as the worked example:

1. `scripts/restore-isolated-fixture.sh up --stamp STAMP --with-minio` issues the ticket. The
   ticket pins the auth target's database identity and the backup's media bucket
   (`minio.sourceBucket`). For parking, the target image must carry PostGIS, and its psql must
   read the stamp's dumps (one PostgreSQL client version for dump and restore; pg_dump 16.10 and
   later writes `\restrict`, which older clients refuse). The parking dump creates the PostGIS
   extension, which needs a superuser: give the target's `parkio_parking` role SUPERUSER for the
   restore only, and take it back before any application starts (the drill does both and checks).
2. `restore-hosted-beta.sh --manifest STAMP/backup-manifest.json --yes --recovery-cutoff T
   --isolated-fixture --isolated-ticket TICKET --erasure-evidence BUNDLE --erasure-trust TRUST
   --recovery-attempt UUID --recovery-dir DIR` verifies the off-host evidence bundle before it
   decrypts anything. Untrusted evidence exits 3 with nothing applied. Trusted evidence writes
   `DIR/trusted-erasure-set.json` and a CLOSED `DIR/expose-gate.json`, then the restore runs.
   The time-based ledger check is unchanged.
3. `scripts/recovery-replay.sh up` starts Kafka, Redis and the eight participants on the
   ticket's internal network only. `scripts/recovery-replay.sh run --recovery-dir DIR --trust
   TRUST` runs the auth recovery-replay command once. Its exit code is the verdict: 0 COMPLETE;
   20-26 refused, blocked, failed or timed out. A rerun of the same attempt resumes it.
4. `scripts/lib/recovery-expose-gate.py open --recovery-dir DIR --verdict
   DIR/replay-verdict-<attempt>.json --operator NAME` records OPEN only on that attempt's
   COMPLETE verdict. Exposing the copy to traffic is outside this procedure.
5. `scripts/recovery-replay.sh down`, then `restore-isolated-fixture.sh down --ticket TICKET`.

Coverage is reported only as "erasure coverage verified through sequence N (frontier version
V)". The evidence bundle is exported with a test-scope tool in the drill; a production export
procedure is a later owner decision.

## Restore source inventory

- scripts/restore-hosted-beta.sh
- scripts/restore-database.sh
- scripts/restore-isolated-fixture.sh
- scripts/lib/restore-safe-preflight.sh
- scripts/lib/restore-isolated-ticket.py
- scripts/lib/restore-isolated-inspect.py
- scripts/lib/restore-stamp-preflight.py
- scripts/lib/restore-erasure-ledger.py
- scripts/lib/restore-client-compat.py
- scripts/lib/restore-dump-profile.py
- scripts/lib/erasure-tombstones.sh
- scripts/lib/backup-common.sh
- scripts/lib/recovery-evidence.py
- scripts/lib/recovery-expose-gate.py
- scripts/recovery-replay.sh

Runtime: bash, python3, jq, openssl, gzip, sha256sum, docker, identified psql.
Later host check (not authorized): sha256sum those files against the
reviewed head; probe tool versions. Do not treat CI psql 16.10 as a 16.15 dump.

## Release decision

- Source acceptance: focused entrypoint refusals plus isolated fixtures; CI
  on this draft is source evidence only.
- Host-install readiness: no. Source files plus runtime probes; not authorized.
- Real recovery readiness: BLOCKED. No verified coverage. Production
  entrypoints refuse decrypt/apply. Drill 01 is not an entrypoint E2E.
- Merge: not authorized by this task.
- #104 HOLD. Production unchanged.
