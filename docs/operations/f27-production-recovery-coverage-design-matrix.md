# N-02 / F-27 / #365 — Production recovery coverage design matrix

**Status:** design / investigation record only. Does **not** authorize production restore,
real backup transfer/decrypt, or change `verifiedCoverage=false`.
**Preserves:** [#104](https://github.com/ADBERILGEN35/parkio/pull/104) HOLD; D-DP1 boundary.
**Recorded:** 2026-10-15 (owner-authorized D-DP1 sweep).
**api tip reviewed:** `cbfb84813b678f5c1787782a22a2a92ce57fb818`
**Isolated drill (FIXED, not production acceptance):** #296 merge `e97749f0…` / run
[37634422144](https://github.com/ADBERILGEN35/parkio/actions/runs/37634422144)
(`fad96778`, conclusion=success). Measured disposable RTO/RPO apply to that drill only.

## 1. What already exists (do not recreate)

| Piece | Evidence | Scope limit |
|---|---|---|
| Stage-4 isolated recovery + default-off replay | #296 / `recovery-drill.yml` | Synthetic / disposable only |
| Measured RTO 234 s, data RPO 165 s, erasure RPO 0 | DR runbook on api | Not a production commitment |
| Unconditional production refusal without verified coverage | `scripts/lib/restore-safe-preflight.sh` `parkio_restore_refuse_unverified_production` | Must remain |
| Custody procedure format | `docs/operations/erasure-evidence-key-custody.md` | Operator decisions **not recorded** |
| Recovery evidence contract | `docs/operations/recovery-evidence-contract.md` | Isolated / synthetic trust roots |

## 2. Production trust / freshness negative-case matrix (design)

These cases must refuse **before** decrypt/apply/expose. Implementation belongs to #367 only
after #366 design approval. Test vehicle (after design): stub-driven
`scripts/test-production-recovery-evidence.sh` executing the real adapter (not grep-only).

| Case ID | Input defect | Required refusal point | Must not reach | Isolated fixture behavior |
|---|---|---|---|---|
| P-MISS | Missing verified-coverage / erasure evidence | preflight / evidence gate | decrypt, apply, expose | destination-bound isolated ticket path unchanged |
| P-STALE | Stale coverage (cutoff older than required freshness / unknown tail after watermark) | freshness / unknown-tail gate | apply, expose | retain existing isolated cutoff rules |
| P-CORR | Corrupt / tampered evidence bundle or signature | verify before trust | decrypt of payload / apply | fixtures with synthetic trust keys only |
| P-INCOMPLETE | Incomplete participant coverage (not all required erasure participants) | coverage completeness gate | expose | isolated may continue only under validated ticket |
| P-DEFAULT-OFF | Production path invoked without explicit owner enablement | default-off gate | any production apply | n/a |
| P-KEY-CUSTODY | Missing/unnamed custodian, missing trust document, wrong `databaseIdentity` | custody / identity pin | verify-as-trusted | test keys never used outside tests |
| P-EXPOSURE | Evidence claims coverage but exposure decision unset | expose gate stays closed | user-facing traffic | isolated drill does not start apps |
| P-LEDGER-ONLY | Manifest timestamp / merged IDs presented as coverage | refuse (already coded) | treat as verifiedCoverage | unchanged |

## 3. Design inputs the owner must decide (#366 gate)

Evidence must be verifiable **without** trusting restored application data or caller assertions.

| Decision | Why required | Inventing it here? |
|---|---|---|
| Named key custodian + recorded `keyId` windows | Custody doc explicitly unrecorded | **No** — owner/operator fact |
| Production trust-root location and distribution | Producers/consumers need pinned trust docs | **No** |
| Lawful basis / authorization for any production evidence export or off-host handling | D-DP1: general ops permission ≠ lawful basis | **No** — do not invent KVKK/legal grounds |
| Freshness/completeness SLA for “trusted coverage” | Defines P-STALE / P-INCOMPLETE | Owner design approval |
| Unknown-tail policy (block expose vs supplemental ledger) | Already refuse-unverified in code; production export separate | Owner |
| Whether production capability is ever in scope vs isolated-only forever | Avoids false “Done” on F-27 | Owner |

## 4. Child issue split (preserve)

| Issue | Role | This sweep |
|---|---|---|
| #365 | Parent: close remaining production recovery coverage gap | OPEN — design matrix delivered; production path not authorized |
| #366 | Design trusted production recovery inputs | OPEN — matrix above is the design draft; needs owner approval |
| #367 | Implementation after design | Blocked pending #366 approval |
| #296 | Isolated drill + measured RTO/RPO | skipped-FIXED — do not recreate |

## 5. Hard boundaries observed

- No production SSH/deploy/backup/restore against a real host.
- No transfer/decrypt of real production backups onto another host (D-DP1).
- No edits to open PR branches #104 / #101 / #102 / #103.
- `verifiedCoverage` stays false; production restore stays BLOCKED.

## 6. Smallest remaining owner/legal/custody step

Owner records, in #366 (or an attached decision comment): (1) named custodian + trust-root
custody facts, and (2) an explicit lawful-basis / authorization statement for any future
production evidence export path — without which #367 must not start and #365 cannot close.
