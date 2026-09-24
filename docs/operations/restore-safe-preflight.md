# F-03 safe restore preflight - source draft and release decision

Draft only. Not authorization to merge, install, or run a real restore.
Baseline: origin/api b5a86ed8d432d0840e94796ee63781ddf2c4420c (includes #105/#106/#107).
#104 remains HOLD at c24f4f3de07d31c359c33971aba457041714705c.

## Changed entrypoints
- scripts/restore-hosted-beta.sh
- scripts/restore-database.sh
- scripts/lib/restore-safe-preflight.sh (new)

Reuses restore-stamp-preflight.py, restore-erasure-ledger.py, restore-client-compat.py,
restore-dump-profile.py, erasure-tombstones.sh, backup-common.sh.

## 1. Coverage trust model

`restore-erasure-ledger.py` compares `--recovery-cutoff` to certified coverage,
which is only the newest stamp ledger `backup-manifest.json` timestamp.

It does not use file mtime, offsite upload time, an empty ledger, a caller
timestamp, or `--supplemental-covered-through` as certification.

Supplemental identifiers may merge into the replay set. `assertedCoveredThrough`
is recorded as operator assertion only. An incomplete supplemental with an
apparently sufficient timestamp stays BLOCKED (exit 3). Never lower the cutoff.

Commit-visibility limitation (preserved). Nightly export is an unlocked
SELECT from erased_user_tombstones. That snapshot cannot prove every
transaction committed before a claimed watermark is included. A newer nightly
ledger therefore cannot independently certify completeness. Tool PASS means
declared stamp clocks reach the cutoff. It is not production attestation.

Trustworthy certified coverage: absent. Production recovery remains BLOCKED.

## 2. Erasure application vs ledger membership

- Merged ledger identifiers (#109 set-membership test): IDs are in the
  reconstructed set. This is not target DB state after restore.
- restore-hosted-beta.sh replay: calls parkio_replay_erasure_tombstones after
  DB restore for all/databases (not minio). #109 stubs do not prove ACTIVE=0.
- restore-database.sh: coverage gate then apply. No replay.
- Isolated drill 01 (reused exact-head CI): shared replay helper plus
  active_in_erasure_set_after_replay=0 on synthetic isolated Postgres.
  Not a production stamp. Not app-path PII purge.

Replay failure in hosted-beta exits non-zero. Failed DB apply stops later
databases. --only minio does not replay. Standalone restore-database.sh does
not replay. POST /internal/erasure/replay is still required before serving
traffic and is not exercised here.

## 3. Installation dependency inventory

A three-file copy of the two entrypoints plus restore-safe-preflight.sh is
not sufficient. Do not assume host copies of helpers match reviewed bytes.

Required repository files (source identity = path at the reviewed #109 head):

- scripts/restore-hosted-beta.sh — documented full/partial production entrypoint
- scripts/restore-database.sh — documented single-DB entrypoint
- scripts/lib/restore-safe-preflight.sh — shared fail-closed preflight
- scripts/lib/restore-stamp-preflight.py — COMPLETE / checksum / scope / path safety
- scripts/lib/restore-erasure-ledger.py — cutoff vs certified stamp-ledger coverage
- scripts/lib/restore-client-compat.py — dump / restore-client / server / PostGIS
- scripts/lib/restore-dump-profile.py — sidecar-less dump profile after confirm
- scripts/lib/erasure-tombstones.sh — replay helper (hosted-beta + drill 01)
- scripts/lib/backup-common.sh — env load, profile, MinIO unseal, network

Runtime (record live versions at a later install; none pinned here):

- bash — entrypoints
- python3 — preflight / coverage / compat / realpath
- jq — manifest fields
- openssl — decrypt after preflight
- gzip / sha256sum — artifact handling / later hash check
- docker + identified psql — apply / replay / version probes

Later read-only preflight (not authorized now): sha256sum each required file
against the reviewed tree and refuse on mismatch. Probe python3, jq, openssl,
bash, and docker versions. Probe dump-client / restore-client / target-server /
PostGIS separately. Do not treat CI psql 16.10 as sufficient for a 16.15 dump.

## 4. Release decision

- Merge-readiness: source/CI only. Draft. Not authorized to merge.
- Production-readiness: BLOCKED. No certified coverage mechanism. No host
  install. No real restore.
- #104: HOLD at c24f4f3de07d31c359c33971aba457041714705c

Synthetic fixtures and exact-head CI are source acceptance, not live restore
acceptance. Rollback of a later install would restore the known F-03 defect.
No host install is authorized.

## Remaining blockers
- Real restore of any production stamp is unauthorized.
- Certified off-host erasure completeness is missing (commit-visibility and
  no independent remote ledger).
- restore-database.sh does not replay; hosted-beta replay is not isolated-DB
  proven in #109 tests.
- App-path participant erase is not exercised.
- Do not assume CI psql 16.10 matches a 16.15 dump.
