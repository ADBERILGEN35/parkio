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
