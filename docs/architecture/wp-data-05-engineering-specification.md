# DATA-WP-05 — Bounded Registry Candidate Generation and Review Operations

> **Naming:** This package is **DATA-WP-05** (municipal/parking data). It is unrelated to
> repository **WP-05** (Decision Intelligence Shadow Stack). Document filename follows the
> existing `wp-data-0N-*` convention. Do not use `wp-05-*` for this work.

## 1. Executive summary

DATA-WP-01 through DATA-WP-04 delivered İzmir municipal source foundations, OSM
publication, gated İZELMAN import tooling, and a dark-by-default canonical registry
with provenance and human link review. The remaining **repository-backed** gap is
operational: candidate generation is pair-only (`LinkCandidateGenerationService.generate`),
fixture dry-runs intentionally reject live input, and live hosted-beta shadow remains an
explicit operator gate outside DATA-WP-04.

**DATA-WP-05** closes that gap with a **bounded, opt-in, non-applying** candidate
generation orchestrator for official source families already present on hosted-beta
(primarily İZUM ↔ OSM), plus the controls needed for a separate hosted-beta review
operations gate. It does **not** enable automatic linking, does **not** publish İZELMAN,
does **not** fabricate availability, and does **not** reopen Decision Intelligence WP-05.

Phasing:

| Sub-package | Kind | Deliverable |
|-------------|------|-------------|
| **DATA-WP-05** | Implementation | Bounded generation orchestrator, ADMIN invoke path, metrics/tests/docs |
| **DATA-WP-05A** | Hosted-beta gate | Deploy + temporary shadow + least-privilege restore (ops only) |

## 2. Repository evidence

| Source | Evidence |
|--------|----------|
| `docs/architecture/wp-data-04-canonical-registry-provenance-link-review.md` | Does not start DATA-WP-05; candidate generation invoked explicitly; scheduling and live hosted-beta dry-runs out of scope until a separate opt-in gate |
| `docs/operations/municipal-registry-review-runbook.md` | Live hosted-beta shadow remains an operator gate outside WP-04; fixture dry-run live input intentionally unsupported |
| `docs/architecture/README.md` | Indexes DATA-WP-01..04 under municipal sources; WP-05 Decision Intelligence is a separate section |
| `docs/architecture/wp-data-03-izelman-inventory-tariffs.md` | Publication must remain disabled until legal, freshness, and product review |
| `docs/architecture/wp-data-02-osm-izmir-facility-import.md` | Temporary izmir-bbox-v1; admin polygon deferred |
| `docs/architecture/wp-data-01-municipal-parking-source-foundation.md` | Next-municipality checklist; İZUM lacks observation timestamp |
| `docs/operations/kill-switch-catalogue.md` | DATA-WP-01..04 kill switches; registry flags default OFF |
| Code | `LinkCandidateGenerationService` is pair-wise only; no bulk ADMIN generate endpoint |

No committed product roadmap names a different DATA-WP-05 scope. Candidates without repository evidence are rejected.

## 3. Current state (accepted baseline)

Application image tip for hosted-beta parking-service:
`parkio/parking-service:sha-fde55882a697ebd99bee491563007eafe37ee68a`
(source-control baseline for this specification: `bdf73a4` disk-guard docs commit).

| Capability | State |
|------------|-------|
| İZUM live occupancy + scheduler | Enabled on hosted-beta |
| OSM static facility publication | Enabled; `availableSpaces` always null |
| İZELMAN import/publication | Disabled; inventory HISTORICAL; tariffs AGING/non-CURRENT |
| Registry vs availability | Separated |
| V32 provenance / candidates / audit / aliases | Present |
| Registry flags | All false |
| Automatic linking | Hard-prohibited |
| Hosted-beta disk guard | Enabled (`scripts/lib/disk-space.sh`) |
| Production | Unchanged |

## 4. Problem statement

Operators cannot run a **repository-supported**, bounded candidate generation pass
against live hosted-beta İZUM/OSM facilities without inventing endpoints or offline
policy reimplementations. DATA-WP-04B therefore could only prove dark defaults and an
offline policy shadow. Without an explicit generation orchestrator:

- Review tooling cannot be exercised on real İzmir pairs safely.
- Hard-conflict and multi-signal distributions remain unmeasured on live data.
- Enabling reviewed-linking later would lack an auditable generation path.

## 5. Scope

### In scope (DATA-WP-05 implementation)

1. **Bounded candidate generation orchestrator** inside `parking-service` that:
   - Selects facility pairs by source-family rules (İZUM↔OSM first; İZELMAN pairs only when published/linkable inventory exists — expected zero on current hosted-beta).
   - Builds `LinkCandidateEvidence` from persisted facility + source-link fields.
   - Calls existing `LinkCandidateGenerationService.generate` / `LinkCandidatePolicy`.
   - Honors `candidate-generation-enabled` (default false).
   - Never applies links, never moves ownership, never writes occupancy/tariffs.
2. **Explicit ADMIN invocation** (HTTP and/or documented ops command) with:
   - hard caps (max pairs, max candidates, radius, timeout);
   - dry-run mode that returns aggregates without persistence when requested;
   - idempotent re-runs via existing candidate uniqueness keys.
3. **Bounded run metrics/logs** (pair counts, skip/conflict/candidate buckets, algorithm version, duration) — no raw PII dumps.
4. **Tests**: unit + Postgres ITs for caps, flag gate, non-mutation of links/occupancy, uniqueness, and hard-conflict retention.
5. **Docs**: update registry runbook with generation invoke procedure; kill-switch notes.

### In scope (DATA-WP-05A hosted-beta gate — separate package)

1. Deploy implementation SHA with flags dark.
2. Temporary enable **only** `candidate-generation-enabled` for one bounded run.
3. Optional temporary `review-api-enabled` for list/detail only; mutations forbidden unless a later package explicitly enables `reviewed-linking-enabled`.
4. Restore all registry flags false; smoke; disk/rollback checks.

## 6. Out of scope

- Decision Intelligence WP-05 (engines, authority, canary, calibration)
- Automatic linking / binding `automatic-linking-enabled=true`
- Enabling `reviewed-linking-enabled` for production-like apply on hosted-beta (later package after shadow evidence)
- İZELMAN publication or mutating canonical import
- Labeling AGING/HISTORICAL tariffs as CURRENT
- OSM/İZELMAN occupancy fabrication or summing
- Provenance publication enablement as a product launch
- Continuous scheduler for candidate generation (optional future; default remains off)
- İzmir admin-boundary polygon replacement for `izmir-bbox-v1`
- Next municipality adapters
- User-reported spot ↔ municipal facility fusion product
- Search ranking / recommendation engines
- Source-quality scoring marketplace / SLA product catalog
- Production deploy

## 7. Architecture

```
                    ┌─────────────────────────────┐
 ADMIN invoke       │ LinkCandidateGeneration     │
 (flag-gated) ─────►│ Orchestrator (NEW)          │
                    │  - pair select (PostGIS)    │
                    │  - evidence build           │
                    │  - caps / dry-run           │
                    └─────────────┬───────────────┘
                                  │ per pair
                                  ▼
                    ┌─────────────────────────────┐
                    │ LinkCandidateGeneration     │  (EXISTING)
                    │ Service.generate(...)       │
                    └─────────────┬───────────────┘
                                  │
                                  ▼
                    ┌─────────────────────────────┐
                    │ municipal_link_candidates   │  PENDING only
                    │ (no source-link mutation)   │
                    └─────────────────────────────┘
```

Preserve:

- Registry = durable identity/metadata/links/provenance
- Availability = İZUM occupancy snapshots only
- Review mutations remain behind `review-api-enabled` + `reviewed-linking-enabled`

## 8. Data model

**No new Flyway migration expected** if existing V32 tables suffice:

- `municipal_link_candidates`
- `municipal_link_review_audit` (unchanged; generation does not write accept/reject)
- source links / occupancy / tariffs / aliases / provenance selection unchanged

Optional additive columns **only if** needed for run correlation (e.g. `generation_run_id`)
— prefer metrics/logs first; add schema only with evidence that ops cannot correlate runs.

## 9. Source and trust semantics

| Rule | Requirement |
|------|-------------|
| Live municipal availability | İZUM only |
| OSM availability | Always null / UNAVAILABLE |
| İZELMAN | Remain unpublished |
| Distance-only / name-only | Never persist as review candidates |
| Hard conflicts | Persist for human review; never auto-accept |
| Automatic linking | Remains hard-false |
| Tariffs | AGING/HISTORICAL never become CURRENT via this package |

## 10. APIs

### Proposed ADMIN (exact path to match existing admin municipal namespace)

`POST /api/v1/parking/admin/municipal/registry/link-candidates/generate`

- Auth: ADMIN / SUPER_ADMIN only
- Requires `candidate-generation-enabled=true`
- Body (illustrative; finalize in implementation to match DTO conventions):
  - `sourceFamilyPair` (e.g. `IZUM_OSM`)
  - `maxPairs`, `radiusMeters` (≤ policy candidate radius)
  - `dryRun` boolean
  - optional bbox / facility ID allowlist for further bounding
- Response: aggregate counts + bounded representative examples (truncated IDs)
- Does **not** enable review mutations

Existing review routes remain unchanged and default-absent when review API is false.

Public facility/spot contracts unchanged.

## 11. Feature flags

| Flag | Default | DATA-WP-05 change |
|------|---------|-------------------|
| `parkio.municipal.registry.candidate-generation-enabled` | false | Still default false; required true for invoke |
| `review-api-enabled` | false | Unchanged |
| `reviewed-linking-enabled` | false | Unchanged |
| `automatic-linking-enabled` | false (hard) | Unchanged |
| `provenance-publication-enabled` | false | Unchanged |
| All İZELMAN gates | false | Unchanged |

No new master flag required unless implementation introduces a distinct
`candidate-generation-scheduler-enabled` (default false, out of initial scope).

## 12. Observability

Reuse/extend `parkio.municipal.registry.*` metrics with bounded labels:

- `outcome` = inserted | duplicate_suppressed | skipped | hard_conflict | error
- `reason_category`, `source_family_pair`, `algorithm_version`
- run summary: pairs_considered, duration

Health component `municipalRegistry` remains non-failing for liveness.

## 13. Security / privacy

- ADMIN-only generate endpoint
- No raw source payloads, addresses, or reviewer notes in public responses
- Evidence logs: truncated facility IDs, source keys, distances, categories only
- Unauthenticated → 401; USER → 403
- Do not log tokens or env secrets in generation reports

## 14. Migration strategy

Prefer **migration-free** if V32 is sufficient. If a run-id column is proven necessary:

- additive nullable column + index only
- forward-only; no destructive rewrite
- hosted-beta apply exactly once; production not in this package

## 15. Testing

| Layer | Must cover |
|-------|------------|
| Unit | Caps, dry-run, disabled flag, pair ordering, family filters |
| Postgres IT | Persist candidates without link/occupancy/alias/tariff mutation; uniqueness; hard-conflict rows |
| Controller | Authz matrix; 409/403 when disabled; aggregate response shape |
| Regression | Automatic linking still fails closed; İZUM/OSM publication semantics unchanged |
| Fixture dry-run | Remains supported; live input still not accepted by fixture script |

## 16. Hosted-beta rollout (DATA-WP-05A)

1. Disk preflight ≥12 GiB; protect current+rollback images.
2. Deploy implementation SHA; Compose validate without `--skip-compose`.
3. Confirm registry flags false; V32 unchanged or new migration once.
4. Backup env; enable **only** candidate-generation; one bounded ADMIN run.
5. Prove: candidates may increase; links/occupancy/aliases/supersede unchanged.
6. Optional review-api list/detail; no accept/reject/distinct/reopen.
7. Restore all registry flags false; smoke pass=12/fail=0; rollback command recorded.
8. Do not enable reviewed linking or provenance publication in 05A unless a later package explicitly expands scope after human approval.

## 17. Rollback

- Disable `candidate-generation-enabled` (and review flags if temporarily enabled).
- Do not drop V32 tables.
- Candidate rows may remain as internal evidence with no public effect.
- Image rollback via existing hosted-beta rollback script/manifest.

## 18. Risks

| Risk | Mitigation |
|------|------------|
| Broad pair explosion | Hard caps + radius + family filter |
| Accidental link apply | reviewed-linking stays false; no apply in generate path |
| Offline policy drift | Must call Java `LinkCandidatePolicy`, not reimplement |
| Name collision with WP-05 | Document naming; never touch decision packages |
| Disk pressure from rebuilds | Existing disk preflight |

## 19. Acceptance criteria

### DATA-WP-05 (implementation)

1. Bounded generate path exists and is repository-supported (no guessed shapes).
2. Defaults remain dark; disabled invoke fails closed.
3. Generation never mutates source links, occupancy, aliases, tariffs, or public publication state.
4. Automatic linking remains impossible.
5. Unit + Postgres IT evidence recorded.
6. Runbook documents invoke + kill order.
7. No Decision Intelligence WP-05 files modified except unavoidable shared docs index.

### DATA-WP-05A (hosted-beta)

1. One bounded live run completes with explainable aggregates.
2. Safety fingerprints for links/occupancy unchanged aside from allowed candidate rows.
3. Flags restored false; smoke passes; production unchanged.

## 20. Deferred work

| Item | Class | Why deferred |
|------|-------|--------------|
| Reviewed-linking enablement on hosted-beta | Later DATA package / product gate | Needs shadow evidence + human policy |
| Provenance publication UX | Product/ops gate | Flag exists; not required for generation |
| İZELMAN publication | Legal/freshness/product | Explicit WP-03 gate |
| `izmir-bbox-v1` → admin polygon | Ops/geo licensing | Explicit WP-02 deferral |
| Candidate generation scheduler | Optional | Prefer explicit invoke first |
| Next municipality | Product decision | Checklist only |
| Spot ↔ facility fusion / ranking | Unsupported as DATA-WP-05 | No DATA roadmap evidence |

## 21. Name-collision and document convention

| Existing WP-05 | DATA-WP-05 |
|----------------|------------|
| Decision/Trust/Reward/Fraud/Exposure shadows | Municipal registry candidate operations |
| Docs: `wp-05-*.md`, `ADR-WP05-*` | Docs: `wp-data-05-*.md`, ops under `municipal-*` |
| Package: decision/availability (spot TTL) | Package: `externalsource.registry` / municipal |

**Convention:** always prefix data packages `DATA-WP-NN` in prose and `wp-data-NN-` in filenames. Never assign municipal data work to `wp-05-*`.

## 22. Conflict minimization

| Overlap | Guidance |
|---------|----------|
| WP-05 Decision | Do not edit decision application services or authority flags |
| WP-06 ops | Reuse runbook/kill-switch patterns; disk guard already present |
| WP-07 mobile/session | No session API changes |
| Frontend | No public contract change in 05; optional later provenance UX deferred |
| parking-service registry | Own changes under application/presentation registry packages only |