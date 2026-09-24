# F-03 safe restore preflight - source draft

Draft only. Not authorization to merge, install, or run a real restore.
Baseline: origin/api b5a86ed8d432d0840e94796ee63781ddf2c4420c (includes #105/#106/#107).
#104 remains HOLD.

## Changed entrypoints
- scripts/restore-hosted-beta.sh
- scripts/restore-database.sh
- scripts/lib/restore-safe-preflight.sh (new)

Reuses restore-stamp-preflight.py, restore-erasure-ledger.py, restore-client-compat.py.

## Guarantees (source acceptance only)
Missing COMPLETE, failed DB/MinIO, malformed manifest, checksum mismatch,
missing file and SHA256SUMS path traversal fail before decrypt/apply.
DB-only stamps cannot be used as full-system restores.
--recovery-cutoff is required. Stamp-time or empty ledgers do not prove later erasures.
offsite.uploaded=false is warned, not rewritten, and is not remote proof.
Dump/restore/target/PostGIS compatibility is checked separately.
Restore does not start applications, Slack, or Fluent Bit.

## Future install / rollback
No host install is authorized. Later install would replace the two entrypoints
plus restore-safe-preflight.sh. Rollback restores the known F-03 defect.

## Remaining blockers
Real restore of any production stamp is still unauthorized.
Off-host erasure coverage still needs newer stamp ledgers or a supplemental ledger
with an explicit covered-through time.
Do not assume CI psql 16.10 matches a 16.15 dump.
Source/CI acceptance is not live restore acceptance.
