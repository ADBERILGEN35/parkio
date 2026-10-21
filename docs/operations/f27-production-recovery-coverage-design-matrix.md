# N-02 / F-27 / #365 — Production recovery coverage design matrix

**Status:** design record. Does **not** authorize production restore, real backup
transfer/decrypt, or change `verifiedCoverage=false`.
**Preserves:** [#104](https://github.com/ADBERILGEN35/parkio/pull/104) HOLD; D-DP1 boundary.
**Recorded:** 2026-10-15 (owner-authorized D-DP1 sweep); **owner custody + platform preference
updated 2026-10-21**.
**Store compatibility:** `docs/operations/n02-gcs-evidence-store-compatibility.md` (Route B —
narrow GCS Object Retention Lock adapter preferred after real-provider validation; interim
default-off MinIO Object Lock adapter retained for synthetic tests).
**Isolated drill (FIXED, not production acceptance):** #296 — not production acceptance.

## 1. What already exists (do not recreate)

| Piece | Evidence | Scope limit |
|---|---|---|
| Stage-4 isolated recovery + default-off replay | #296 / `recovery-drill.yml` | Synthetic / disposable only |
| Unconditional production refusal without verified coverage | `scripts/lib/restore-safe-preflight.sh` `parkio_restore_refuse_unverified_production` | Must remain |
| Custody procedure + owner identity | `docs/operations/erasure-evidence-key-custody.md` | Custodian = `ADBERILGEN35`; no secrets store claimed |
| Recovery evidence contract | `docs/operations/recovery-evidence-contract.md` | Isolated / synthetic trust roots |
| Default-off production-evidence adapter (validation only) | `scripts/production-recovery-evidence.sh` | Never sets `verifiedCoverage`; never authorizes production apply |

## 2. Production trust / freshness negative-case matrix

These cases must refuse **before** decrypt/apply/expose. Stub-driven vehicle:
`scripts/test-production-recovery-evidence.sh` executing the real adapter (not grep-only).

| Case ID | Input defect | Required refusal point | Must not reach | Isolated fixture behavior |
|---|---|---|---|---|
| P-MISS | Missing verified-coverage / erasure evidence | preflight / evidence gate | decrypt, apply, expose | destination-bound isolated ticket path unchanged |
| P-STALE | Stale coverage (cutoff older than required freshness / unknown tail after watermark) | freshness / unknown-tail gate | apply, expose | retain existing isolated cutoff rules |
| P-CORR | Corrupt / tampered evidence bundle or signature | verify before trust | decrypt of payload / apply | fixtures with synthetic trust keys only |
| P-INCOMPLETE | Incomplete participant coverage / gap in frontier | coverage completeness gate | expose | isolated may continue only under validated ticket |
| P-DEFAULT-OFF | Production-evidence adapter invoked without enablement flag | default-off gate | any production apply | n/a |
| P-KEY-CUSTODY | Missing trust document, wrong `databaseIdentity` | custody / identity pin | verify-as-trusted | test keys never used outside tests |
| P-EXPOSURE | Evidence claims coverage but exposure decision unset | expose gate stays closed | user-facing traffic | isolated drill does not start apps |
| P-LEDGER-ONLY | Manifest timestamp / merged IDs presented as coverage | refuse (already coded) | treat as verifiedCoverage | unchanged |

## 3. Owner decisions recorded (#366)

Evidence must be verifiable **without** trusting restored application data or caller assertions.

| Decision | Recorded value (2026-10-21) |
|---|---|
| Named key custodian | GitHub account **`ADBERILGEN35`** (no legal name) |
| Compromise response owner | Same (`ADBERILGEN35`); no delegate |
| Centralized secrets store | **None claimed** |
| Preferred evidence platform to investigate | **Google Cloud** (see compatibility doc; Route B) |
| Evidence vs backup secrets | Remain separate |
| Production recovery capability | Stays **BLOCKED** / isolated-only until legal + real-provider + enablement |
| Retention duration (production) | **Unset**; no 400-day placeholder; engineering floor guidance only (≥45d from 14d backup+30; recommend ≥90d before counsel) |
| Lawful basis / D-DP1 export | **Not** decided here — remains open under #365 |

## 4. Child issue split (preserve)

| Issue | Role | Closure rule |
|---|---|---|
| #365 | Parent: close remaining production recovery coverage gap | OPEN while production/legal/provider gap remains |
| #366 | Design trusted production recovery inputs | CLOSED (#481) |
| #367 | Bundle-validation default-off wrapper | CLOSED (#481) — layer A, not GCS I/O |
| #482 | Default-off GCS Object Retention Lock I/O adapter | Implementation (#482) |
| #483 | Real GCS provider acceptance (synthetic) | Blocked on #482 + cloud authorization |
| #296 | Isolated drill + measured RTO/RPO | skipped-FIXED — do not recreate |

## 5. Hard boundaries observed

- No production SSH/deploy/backup/restore against a real host.
- No transfer/decrypt of real production backups onto another host (D-DP1).
- No irreversible retention locks or paid GCP provisioning during research.
- Emulators/mocks cannot establish real-provider immutability acceptance.
- `verifiedCoverage` stays false; production restore stays BLOCKED.

## 6. Remaining #365 prerequisites (not satisfied by #366/#367)

1. Lawful basis / counsel for any production evidence export or irreversible COMPLIANCE lock.
2. Real Google Cloud (or contingency S3 Object Lock) provider validation under irreversible-lock discipline.
3. Intentional production enablement of durable-store flags after (1)+(2).
4. Separate exposure decision (P-EXPOSURE) before any restored copy is user-facing.
