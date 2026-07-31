# DATA-WP-07 - Public Facility Discovery Duplicate-Presentation Safety

> **Naming:** This package is **DATA-WP-07** (municipal/parking data). It is unrelated to
> repository **WP-07** (Mobile Application Foundation & Sprint 01 closure under
> `docs/architecture/wp-07-*` and `frontend/architecture/sprint-3/WP-07-MOBILE.md`).
> Document filename follows `wp-data-07-*`. Do not reopen Decision Intelligence `wp-05-*`,
> ops `wp-06-*`, or mobile/session `wp-07-*`.

## 1. Executive summary

DATA-WP-01 through DATA-WP-06 delivered municipal sources, IZUM live occupancy with
stale masking, OSM Izmir publication (null availability), dark IZELMAN import tooling,
canonical registry/provenance/review machinery, bounded candidate generation (dark),
and municipal source SLA observability.

Hosted-beta currently publishes **both** IZUM facilities (LIVE/AGING availability when
fresh) and OSM facilities (`availableSpaces=null`, freshness `UNAVAILABLE`) through the
same nearby discovery surface. Registry linking remains disabled; DATA-WP-05A produced
**0 eligible candidates**. Automatic linking remains hard-disabled. As a result, the map
can present near-duplicate inventory density: OSM pins without availability sit beside
IZUM pins with live occupancy for the same real-world parking contexts, without any
query-time presentation policy.

**DATA-WP-07** adds a **deterministic discovery presentation policy** that reduces
false density and source confusion **without** enabling reviewed linking, inventing
spot-facility fusion, publishing IZELMAN, or relaxing stale masking.

**DATA-WP-07A** (separate) validates the policy on hosted-beta against live nearby results.

## 2. Repository evidence

| Evidence | Finding |
|----------|---------|
| Deployed hosted-beta commit | `f4faf279fe9608463c19ba533a81ad8bc852e00b` (V33) |
| DATA-WP-05A | 14 IZUM / 6 pairs / **0 eligible** / 0 candidates; no link mutation |
| Registry flags | All false; `automatic-linking-enabled` hard-false |
| OSM publication | Enabled; occupancy snapshots for OSM = 0; null availability |
| IZUM scheduler | Enabled; intermittent upstream (DNS/timeout) with SLA recovery |
| Public nearby (DATA-WP-06A) | Mixed IZUM `LIVE` + OSM `UNAVAILABLE` in one radius sample |
| Query path | `MunicipalFacilityQueryService.nearby` has no near-duplicate suppression |
| Nearby SQL | PostGIS `ST_DWithin` + `DISTINCT ON (f.id)`; no source-priority demotion |
| Provenance publication | Flag false; `RegistryPublicationService` returns hidden enrichment |
| Deferred lists (WP-02/05/06) | Admin polygon, reviewed-linking, IZELMAN publication, provenance UX, ranking/fusion deferred |
| Name collision | Mobile/session WP-07 exists; municipal work must use `DATA-WP-07` / `wp-data-07-*` |

## 3. Current state

### Implemented and deployed

- Municipal source registry and IZUM live sync
- Occupancy freshness + STALE `availableSpaces=null` masking
- OSM import tooling + hosted-beta publication with null availability
- Source SLA metrics, alerts, recovery counters (DATA-WP-06 / 06A)
- Kill switches, disk preflight, rollback manifests

### Implemented but dark

- IZELMAN facility/roadside/tariff import pipelines (publication false)
- Canonical registry field provenance storage
- Link candidates + review/audit APIs (flags false)
- Bounded candidate generation ADMIN API (flags false; 0 eligible on 05A)
- Public provenance enrichment (`provenance-publication-enabled=false`)

### Operationally validated

- STALE masking under outage
- Source health taxonomy / streak / recovery metric
- OSM null-availability semantics
- Registry non-mutation under dark flags

### Intentionally deferred

- Reviewed-linking enablement (product + shadow evidence)
- IZELMAN publication (legal + HISTORICAL/AGING freshness)
- `izmir-bbox-v1` to administrative polygon (licensing/geo)
- Provenance publication UX (product/frontend)
- Spot-facility fusion / ranking marketplace (no DATA roadmap mandate)
- Candidate regeneration scheduler

### Blocked by external/legal/product prerequisites

- Licensed Izmir admin boundary polygon
- IZELMAN official CURRENT inventory/tariff approval
- Human policy for accept/reject linking on live pairs

## 4. Problem statement

Parkio public facility nearby API treats every publishable facility UUID as an
independent discovery hit. With OSM publication enabled and registry linking dark,
unlinked OSM parking objects and IZUM municipal facilities appear together. OSM pins
correctly expose null availability, but their presence still:

1. Inflates perceived parking inventory density.
2. Competes for limited `limit` slots against live IZUM results.
3. Invites user confusion between mapped parking amenity and live municipal occupancy.
4. Cannot be fixed by enabling linking yet (0 eligible candidates; linking is a separate product/policy package).

This is a presentation/correctness gap created by completed foundations, not a
reason to turn on reviewed linking or invent fusion of community spots.

## 5. Goals

1. Define a bounded, deterministic discovery presentation policy for multi-source municipal facility nearby/detail results.
2. Prefer facilities that may contribute live municipal occupancy (IZUM) when an OSM-only facility is a near-duplicate under explicit geometry rules.
3. Preserve OSM discoverability where no competing IZUM facility exists nearby.
4. Keep STALE masking, null-availability for OSM/IZELMAN, and registry/availability separation unchanged.
5. Ship behind a default-false feature flag until DATA-WP-07A validates hosted-beta.
6. Add bounded metrics for suppressions; keep liveness independent of presentation.
7. Prove behavior with unit + Postgres ITs; validate on hosted-beta in DATA-WP-07A.

## 6. Non-goals

- Enabling `reviewed-linking-enabled`, `candidate-generation-enabled`, or `provenance-publication-enabled`
- Automatic linking or mutating source links / aliases / candidates
- IZELMAN publication or tariff CURRENT promotion
- Replacing `izmir-bbox-v1` with an administrative boundary
- Spot-facility fusion, search ranking marketplace, or recommendation engine
- Fabricating or relaxing availability / freshness thresholds
- Changing IZUM timeouts/retries to clear alerts
- Decision Intelligence WP-05, ops WP-06, mobile/session WP-07
- Production enablement in the implementation package (07A / later gate)

## 7. Architecture

```text
Public nearby/detail
        |
        v
MunicipalFacilityQueryService
        |
        +-- existing publication filter (isDiscoverable)
        +-- NEW: DiscoveryPresentationPolicy (post-fetch, deterministic)
        |         - group near-duplicates by geography
        |         - keep IZUM-capable facility; suppress OSM-only peers
        |         - never suppress last IZUM-capable row in a group
        |
        v
MunicipalFacilityResponse (+ optional enrichment still gated)
```

Policy lives in `parking-service` application layer beside
`MunicipalFacilityQueryService` / `MunicipalSourcePublicationPolicy`. Persistence
and sync paths remain unchanged.

### Presentation rules (normative sketch)

Given candidate discoverable facilities after publication filter:

1. Build near-duplicate groups where two facilities are within `duplicate-radius-meters`
   (default small; exact default locked by implementation fixtures) and do not already
   share canonical identity via active alias.
2. Within a group:
   - If any member `mayContributeLiveOccupancy` (IZUM-linked/primary), keep the
     IZUM-capable member(s); suppress OSM-only members from the nearby list.
   - If only OSM-only members exist, keep them (no IZUM competitor).
3. Detail-by-id: prefer still returning a publishable OSM facility by UUID so deep
   links and attribution remain honest, while nearby omits suppressed peers. Exact
   choice locked by tests.
4. Never invent links, never change occupancy, never upgrade OSM freshness.

## 8. Data model

Preferred: no Flyway migration. Policy is query-time over existing
`municipal_parking_facilities`, `municipal_facility_source_links`,
`municipal_data_sources`, `municipal_occupancy_snapshots`, and optional
`municipal_facility_aliases`.

If implementation proves an index is required for safe SQL-side demotion, a later
additive index-only migration may be proposed as a sub-decision inside DATA-WP-07
implementation—not assumed here. No new entity tables for this package.

## 9. Source/trust semantics

| Rule | Preserved |
|------|-----------|
| IZUM-only live municipal availability | Yes |
| STALE to `availableSpaces=null` | Yes |
| OSM / IZELMAN null availability | Yes |
| Registry is not the availability stream | Yes |
| Automatic linking disabled | Yes |
| Reviewed linking disabled | Yes |
| HISTORICAL/AGING tariffs non-CURRENT | Yes |
| Attribution remains honest for returned rows | Yes |

Suppression is a discovery presentation decision, not a trust-score marketplace
and not a declaration that OSM data is false.

## 10. APIs

### Public

- `GET /api/v1/parking/facilities/nearby` — apply presentation policy before limit trim when flag enabled.
- `GET /api/v1/parking/facilities/{id}` — document detail behavior for suppressed OSM peers (prefer still return when publishable by identity).

No new public fields required for MVP.

### Admin

- No review/link/candidate routes enabled.
- Optional ADMIN-only explain endpoint is out of scope for 07.

### Frontend

- No mandatory frontend change if nearby simply returns fewer OSM peers.
- Map/list should already tolerate null `availableSpaces`.
- Do not require provenance UX.

## 11. Feature flags

| Flag | Default | Purpose |
|------|---------|---------|
| `parkio.municipal.discovery.duplicate-presentation-enabled` | `false` | Master switch for suppression policy |
| `parkio.municipal.discovery.duplicate-radius-meters` | small constant (impl-locked) | Near-duplicate geography threshold |

All existing registry / IZELMAN / OSM import / IZUM timeout flags unchanged.
Hosted-beta Compose may map the new flag but must ship false until 07A explicitly
enables it for validation, then restore false unless a later enablement gate says otherwise.

## 12. Background operations

None. No scheduler. No candidate regeneration. No OSM re-import required.

## 13. Metrics and health

Bounded labels only (`reason`, `source_family` such as `osm_only_near_izum`):

- `parkio.municipal.discovery.duplicates_suppressed` (counter)
- optional gauge of last-nearby suppression count (low cardinality)

Health: do not make liveness depend on suppression. Existing `municipalSources`
and `municipalRegistry` indicators unchanged.

## 14. Security/privacy

- No new PII.
- Do not expose reviewer notes, raw payloads, unpublished IZELMAN fields, or
  suppression reason text containing addresses in metrics/alerts.
- Public responses remain attribution-safe.

## 15. External-data/license considerations

- OSM ODbL attribution remains required for returned OSM rows.
- Suppression does not redistribute OSM bulk extracts.
- No new external dataset required for DATA-WP-07 (unlike admin-boundary work).

## 16. Failure behavior

| Failure | Behavior |
|---------|----------|
| Flag false | Legacy nearby behavior (policy off) |
| Policy exception | Fail closed to unfiltered discoverable set (bounded log); never fabricate availability |
| Empty IZUM set | OSM-only results unchanged |
| Upstream IZUM outage | STALE masking still applies; suppression still prefers IZUM identity rows when present |

## 17. Migration

None required for the specified design. Schema remains V33 unless an
implementation spike proves an additive index is necessary (separate explicit decision
inside the implementation PR, not a planning assumption).

## 18. Testing

| Layer | Coverage |
|-------|----------|
| Unit | Near-duplicate grouping; IZUM preferred; OSM-only retained; STALE still nulls avail; flag off = no-op |
| Postgres IT | Fixture IZUM + nearby OSM within radius; nearby omits OSM when flag on; occupancy unchanged |
| HTTP | Nearby contract with flag on/off; detail behavior locked |
| Regression | Registry/IZELMAN flags false; automatic linking still fails closed; no link mutation |
| Frontend | Not required for 07; smoke map still loads |

Deterministic fixtures only—do not depend on live upstream municipal APIs.

## 19. Hosted-beta rollout

### DATA-WP-07 (implementation)

- Land code + tests + flag default false on `api`.
- Update runbook with presentation policy + kill switch.
- No requirement to enable flag on hosted-beta in this phase.

### DATA-WP-07A (hosted-beta gate — separate)

1. Deploy implementation commit.
2. Record baseline nearby composition (IZUM vs OSM counts in fixed bbox).
3. Temporarily enable flag; re-query; compare composition and STALE masking.
4. Confirm no registry/source-link mutation fingerprints.
5. Restore flag false (or leave enabled only if a written enablement decision exists).
6. Smoke + disk preflight; production unchanged.

## 20. Rollback

1. Set `duplicate-presentation-enabled=false` (immediate).
2. Redeploy previous image/manifest if needed via hosted-beta rollback script.
3. No data rewrite required.

## 21. Risks

| Risk | Mitigation |
|------|------------|
| Over-suppression hides legitimate distinct OSM lots | Tight radius + require IZUM-capable competitor; fixtures for adjacent distinct lots |
| Confusion with fusion / linking | Explicit non-goals; no link writes |
| Limit starvation still possible | Prefer IZUM before limit trim; measure suppressed count |
| Name collision with mobile WP-07 | `DATA-WP-07` / `wp-data-07-*` only |
| Scope creep into provenance UX | Non-goal |

## 22. Acceptance criteria

### DATA-WP-07

1. Spec committed; architecture index links DATA-WP-07; mobile WP-07 collision documented.
2. Implementation (later) ships policy behind default-false flag with unit + IT proof.
3. No migration unless explicitly justified as additive index-only in impl PR.
4. No registry/IZELMAN/provenance flags enabled.
5. Automatic linking remains disabled.
6. STALE masking tests still pass.
7. Decision Intelligence WP-05 and mobile WP-07 untouched.

### DATA-WP-07A

1. Deployed image includes DATA-WP-07.
2. With flag on, nearby shows reduced OSM near-duplicates beside IZUM without availability fabrication.
3. Fingerprints for source links / occupancy unchanged by the gate.
4. Flag restored per gate plan; smoke passes; production unchanged.

## 23. Deferred work

| Item | Class | Why deferred |
|------|-------|--------------|
| Reviewed-linking enablement | Product + later DATA package | 0 eligible candidates; policy gate |
| Provenance publication UX | Product/frontend | Flag exists; not required for discovery safety |
| Izmir admin boundary polygon | Ops/geo/legal | External license; WP-02 deferral |
| IZELMAN publication | Legal/freshness/product | HISTORICAL/AGING |
| Spot-facility fusion / ranking | Unsupported invention | No DATA roadmap mandate |
| Candidate regeneration scheduler | Optional | Prefer explicit ADMIN |
| Source-quality marketplace | Product | Beyond operational SLA |
| Sync-run / occupancy retention jobs | Ops/privacy backlog | Not prerequisite for presentation |
| Prom recovery `increase()` alert quirk | Ops/backlog | DATA-WP-06A note; not 07 prerequisite |
| Completion-log recovery flag mismatch | Ordinary backlog | Metric authoritative |
| Disk near 12 GiB | Ops/incident hygiene | Preflight already enforces |

## 24. Package phasing

| Phase | Objective | Commit boundary | Deploy |
|-------|-----------|-----------------|--------|
| **DATA-WP-07** | Spec (this doc) then deterministic implementation + tests + flag | Spec commit; later impl commit(s) on `api` | Not required to claim local 07 complete |
| **DATA-WP-07A** | Hosted-beta presentation validation | Separate ops gate after 07 impl | Required |

## 25. Planning status

**Specification only.** This document does not implement code, add migrations, change
runtime flags, or deploy. Hosted-beta remains on `f4faf279` / V33 until a future
implementation and DATA-WP-07A gate.

