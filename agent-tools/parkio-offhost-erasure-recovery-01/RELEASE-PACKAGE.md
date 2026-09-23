# Off-host erasure recovery — draft release package

**PR:** https://github.com/ADBERILGEN35/parkio/pull/102 (draft, **HOLD**)
**Branch:** `feat/offhost-erasure-recovery`
**Base:** `origin/api` `aa865a255564464bed207a9061244af2641edd3d`
**See:** `PR102-RELEASE-DECISION.md`

Production stays disabled. Directory store is test/local, not off-host
durability. #101 / NR guard files are untouched.

Coverage is a lock-held **commit-visibility** watermark on the auth DB
clock, not a query-time or persist-clock stamp. Periodic export does not
certify the tail after the last verified watermark. Do not lower the
recovery cutoff to obtain PASS.

Next decisions (not implemented): remote storage/auth, publication
integrity, exporter scheduling, freshness monitoring, treatment of the
uncovered tail after host loss.
