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
**0 eligible candidates**. Automatic linking remains hard-disabled. Nearby can therefore
present near-duplicate inventory density without any query-time presentation policy.

**DATA-WP-07** adds a **deterministic nearby-only discovery presentation policy** that
may suppress an OSM-only peer from a **list** response when a strong IZUM<->OSM
presentation duplicate is proven. It does **not** enable reviewed linking, invent
spot-facility fusion, publish IZELMAN, merge fields, or relax stale masking.

Hardening contract (this revision):

- Suppression applies only to `GET /api/v1/parking/facilities/nearby`.
- `GET /api/v1/parking/facilities/{id}` remains stable and never applies suppression.
- Source priority alone never suppresses; distance-only and name-only never suppress.
- Hard conflicts veto suppression; uncertainty shows both.
- Matching and winner selection are separate decisions.
- Bounded overfetch/refill preserves requested limit semantics.
- Query-time only: no DB writes; flag default false; DATA-WP-07A separate.

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
| LinkCandidatePolicy (DATA-WP-04) | Distance alone / name alone insufficient; hard conflicts retained |
| Provenance publication | Flag false; enrichment hidden |
| Name collision | Mobile/session WP-07 exists; municipal work uses `DATA-WP-07` / `wp-data-07-*` |

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

### Intentionally deferred / blocked

- Reviewed-linking enablement; IZELMAN publication; admin polygon; provenance UX;
  spot-facility fusion; candidate regeneration scheduler; source-quality marketplace

## 4. Problem statement

Parkio public facility nearby API treats every publishable facility UUID as an
independent discovery hit. With OSM publication enabled and registry linking dark,
unlinked OSM parking objects and IZUM municipal facilities appear together. OSM pins
correctly expose null availability, but their presence still inflates inventory density,
competes for `limit` slots, and invites confusion between mapped amenity and live
municipal occupancy. Enabling linking is not the next step (0 eligible candidates).

This package addresses **nearby presentation safety**, not canonical equivalence.

## 5. Goals

1. Define a bounded, deterministic **nearby-list** discovery presentation policy.
2. Suppress an OSM-only nearby peer only after a **strong multi-signal** IZUM<->OSM
   presentation-duplicate classification with **no hard conflict**.
3. Select a deterministic nearby representative without merging fields or availability.
4. Preserve OSM discoverability when no strong IZUM competitor exists.
5. Keep `GET /facilities/{id}` unchanged by this policy (suppressed-from-nearby IDs remain resolvable).
6. Keep STALE masking, OSM/IZELMAN null availability, and registry/availability separation.
7. Ship behind default-false flag; prove with unit + PostGIS ITs; validate in DATA-WP-07A.
8. Bounded overfetch/refill so suppression does not silently under-fill requested limits.

## 6. Non-goals

- Applying duplicate-presentation suppression to detail lookup
- Creating aliases, redirects, 404s, or identity changes for suppressed peers
- Enabling `reviewed-linking-enabled`, `candidate-generation-enabled`, or
  `provenance-publication-enabled`
- Automatic linking or mutating source links / aliases / candidates / provenance
- IZELMAN publication or IZELMAN presentation pairs
- Replacing `izmir-bbox-v1`; spot-facility fusion; ranking marketplace
- Suppressing merely because one source is IZUM and another is OSM
- Distance-only or name-only suppression
- Merging spaces, metadata, or OSM fields into the IZUM DTO
- Fabricating or relaxing availability / freshness thresholds
- Decision Intelligence WP-05, ops WP-06, mobile/session WP-07
- Production enablement in the implementation package

## 7. Architecture

```text
GET /facilities/nearby
        |
        v
MunicipalFacilityQueryService (publication filter)
        |
        +-- bounded overfetch of discoverable candidates
        +-- DiscoveryPresentationPolicy (query-time only)
        |      1) pair classification (IZUM_OSM only)
        |      2) hard-conflict veto
        |      3) strong multi-signal duplicate? else keep both
        |      4) winner selection (separate from matching)
        |      5) suppress non-winners from list; refill up to limit
        v
final nearby page (deterministic order)

GET /facilities/{id}
        |
        v
existing detail projection (NO presentation suppression)
```

Policy lives in `parking-service` application layer beside
`MunicipalFacilityQueryService` / `MunicipalSourcePublicationPolicy` / freshness
policies. Persistence and sync paths remain unchanged.

## 8. Endpoint boundary

### Primary affected endpoint

`GET /api/v1/parking/facilities/nearby`

Only this discovery/list presentation path may omit a publishable facility that was
classified as a strong presentation duplicate of another returned representative.

### Stable identity lookup

`GET /api/v1/parking/facilities/{id}`

Required behavior:

- A facility suppressed from a nearby result remains directly retrievable by its
  existing public facility ID when otherwise publishable.
- Suppression does **not** return 404 for an otherwise publishable facility.
- Suppression does **not** create an alias or redirect.
- Suppression does **not** mutate lifecycle state, source links, or canonical identity.
- Saved links/bookmarks remain valid.
- Existing detail DTO remains backward-compatible.
- Duplicate-presentation suppression is **not** applied to detail lookup.

## 9. Conservative duplicate evidence

Source priority alone must never suppress a facility.

Do **not** suppress merely because:

- one source is IZUM and one is OSM
- coordinates are nearby
- normalized names match
- operators match
- capacities are similar
- records appear inside the same query radius

Suppression requires **all** of:

1. An explicitly supported source-family pair
2. Strong multi-signal evidence (at least **two independent strong signals**)
3. No hard conflict
4. Deterministic policy outcome `PRESENTATION_DUPLICATE`

### Initial supported pair

- **IZUM <-> OSM** only

IZELMAN pairs remain **unsupported** for presentation suppression.

### Positive evidence (normative sketch)

A safe combination must include close compatible geometry **plus** at least one
additional independent strong signal from:

- strong normalized-name similarity
- compatible facility type
- compatible operator **or** address/district evidence

and must include no contradictory access/capacity/geometry evidence.

Exact numeric thresholds are locked in implementation tests. Specification requires:

- **Distance-only must never suppress**
- **Name-only must never suppress**
- Reuse DATA-WP-04 hard-conflict semantics where applicable

## 10. Hard-conflict veto

Any material hard conflict prevents presentation suppression. At minimum evaluate:

- incompatible facility type
- off-street vs roadside/zone mismatch
- materially contradictory coordinates
- contradictory operator identity
- incompatible access class
- clearly different address or district
- capacity evidence indicating separate facilities
- separate entrances or independently operated facilities where represented
- multiple legitimately distinct parking facilities in one complex

When uncertain: **show both facilities; do not suppress**.

False duplicates in the list are preferable to hiding distinct parking facilities.

## 11. Winner-selection policy

Matching and winner selection are separate decisions.

Only after a pair is classified as a strong presentation duplicate may one record be
selected for the nearby response.

Deterministic preference:

1. Publishable IZUM-backed facility with current LIVE/AGING occupancy
2. Publishable IZUM-backed facility with stale/missing occupancy
3. Publishable OSM-only facility
4. Stable facility-ID tie-break where source authority is equal

Rules:

- Occupancy may influence which duplicate is **displayed**
- Occupancy must **not** be evidence that two records are the same facility
- STALE IZUM availability remains null
- OSM availability remains null
- No spaces are summed
- No metadata is merged at query time
- No unpublished source field leaks
- No OSM field is copied into the IZUM response merely because the OSM peer was suppressed
- Use central publication and freshness policies

## 12. Result-limit and overfetch semantics

Problem: a request for `limit=50` may fetch 50 rows then suppress several, returning
fewer results even when additional safe results exist nearby.

Required behavior:

- Requested public `limit` remains the final maximum
- Implementation may perform **bounded overfetch**
- Overfetch has a strict hard maximum
- No unbounded retry/fetch loop
- Deterministic ordering is preserved
- Suppression happens **before** the final limit is applied
- The response attempts to refill up to the requested limit
- Fewer results are acceptable only when the bounded candidate set is exhausted

Conceptual bounds (exact values follow repository conventions in implementation):

- overfetch factor: about 2x requested limit
- absolute query maximum: repository-approved bounded value (must remain finite)
- at most one bounded query, or a strictly bounded page strategy with a hard max

## 13. Ordering stability

Specify deterministic order before and after suppression.

Ordering preserves existing nearby semantics such as:

- distance
- existing relevance/order rules
- stable facility ID tie-break

Required:

- `flag=false` produces existing behavior
- `flag=true` changes only strongly identified duplicate presentation
- unrelated facility ordering remains stable
- repeated identical requests return the same representatives and order
- database physical row order must not affect the result

## 14. Attribution and provenance

- Returned OSM facilities retain OpenStreetMap contributor attribution
  (`sourceLabel` / `attribution` as today; typically `OpenStreetMap contributors`)
- Suppressed OSM records are **not** merged into the selected IZUM projection
- Suppression does **not** create a source link
- Suppression does **not** imply canonical equivalence
- Attribution text never controls matching
- Publisher/sourceLabel text never controls matching
- Stable `source_key` / source-family identity is used
- No internal duplicate evidence or score is exposed publicly
- Global frontend/map attribution obligations remain unchanged when OSM data is displayed

## 15. Feature flags

| Flag | Default | Purpose |
|------|---------|---------|
| `parkio.municipal.discovery.duplicate-presentation-enabled` | `true` (DATA-WP-12; prod pins false) | Master switch |
| `parkio.municipal.discovery.duplicate-radius-meters` | impl-locked small constant | Comparison geography bound |
| `parkio.municipal.discovery.overfetch-factor` (optional) | impl-locked (~2) | Bounded refill |
| `parkio.municipal.discovery.supported-pairs` (optional) | `IZUM_OSM` only | Allowlist |

Requirements:

- Default false
- Hosted-beta false after implementation deploy (until 07A temporary enable)
- Production false
- No automatic enabling
- Disabling the flag immediately restores existing nearby presentation
- No database rollback required

Do **not** couple this flag to candidate generation, review API, reviewed linking,
automatic linking, or provenance publication.

## 16. No-mutation guarantee

Duplicate-presentation processing is **query-time only**. It must create no:

- candidate row
- generation-run row
- review audit
- source-link update
- canonical merge
- alias
- supersede state
- field-provenance update
- occupancy snapshot
- tariff assignment
- publication-state change

No Flyway migration is expected. A migration requires a separate justified decision
(for example additive index-only) outside this planning default.

## 17. Data model

Preferred: no schema change. Operates over existing `municipal_parking_facilities`,
`municipal_facility_source_links`, `municipal_data_sources`,
`municipal_occupancy_snapshots`, and optional `municipal_facility_aliases`.

## 18. Source/trust semantics

| Rule | Preserved |
|------|-----------|
| IZUM-only live municipal availability | Yes |
| STALE to `availableSpaces=null` | Yes |
| OSM / IZELMAN null availability | Yes |
| Registry is not the availability stream | Yes |
| Automatic linking disabled | Yes |
| Reviewed linking disabled | Yes |
| HISTORICAL/AGING tariffs non-CURRENT | Yes |
| Presentation suppression != canonical link | Yes |

## 19. Metrics

Bounded internal metrics (examples):

- nearby facilities considered
- strong presentation duplicates found
- facilities suppressed
- hard-conflict vetoes
- distance-only rejected
- name-only rejected
- bounded overfetch exhaustion

Allowed labels: `source_family_pair`, `outcome`, `reason_category`, `policy_version`.

Do **not** label with facility ID, name, address, coordinates, request ID, or source
external ID.

Health/liveness remains unchanged.

## 20. Security/privacy

- No new PII
- No public duplicate score/evidence
- No unpublished IZELMAN fields
- Attribution-safe public responses

## 21. External-data/license considerations

- OSM ODbL attribution required for returned OSM rows
- Suppression does not redistribute OSM bulk extracts
- No new external dataset required

## 22. Failure behavior

| Failure | Behavior |
|---------|----------|
| Flag false | Existing nearby behavior |
| Policy exception | Fail closed to unfiltered discoverable set (bounded log); never fabricate availability |
| Uncertain duplicate | Show both |
| Empty IZUM set | OSM-only results unchanged |
| Upstream IZUM outage | STALE masking still applies; winner rules still prefer IZUM identity when present |

## 23. Migration

None expected. Schema remains V33 unless a separate justified additive index decision
is made in an implementation PR.

## 24. Required implementation test contract

### Feature behavior

1. Flag false returns existing nearby result set and order.
2. Strong IZUM<->OSM duplicate returns one representative.
3. IZUM LIVE representative is preferred over OSM duplicate.
4. IZUM STALE representative remains visible with `availableSpaces=null`.
5. OSM remains visible when the municipal peer is unpublished.
6. OSM availability remains null.

### Evidence safety

7. Distance-only pair is not suppressed.
8. Name-only pair is not suppressed.
9. Close facilities with incompatible types are both shown.
10. Same-name facilities in different districts are both shown.
11. Adjacent distinct parking facilities in one complex are both shown.
12. Capacity/operator/address hard conflict prevents suppression.

### Identity/API

13. Suppressed nearby record remains retrievable through detail endpoint.
14. No 404/redirect/alias is introduced.
15. Source links remain unchanged.
16. Occupancy rows remain unchanged.
17. Candidate/review tables remain unchanged.
18. Tariff assignments remain unchanged.

### Result bounds

19. Suppression occurs before final result limit.
20. Bounded overfetch refills results up to requested limit where possible.
21. Overfetch never exceeds the hard maximum.
22. Ordering is deterministic across repeated runs.

### Attribution/privacy

23. Returned OSM records retain attribution.
24. OSM fields do not leak into selected IZUM DTO.
25. No internal duplicate score/evidence is public.

### Regression

26. IZUM availability authority remains unchanged.
27. OSM/IZELMAN no-availability semantics remain unchanged.
28. Registry automatic linking remains disabled.
29. Existing HTTP error semantics remain unchanged.
30. Public detail and spots APIs remain backward-compatible.

Require PostgreSQL/PostGIS integration coverage for spatial and ordering behavior.

## 25. Hosted-beta rollout

### DATA-WP-07 (implementation)

- Land code + tests + flag default false on `api`.
- Update municipal runbook with presentation policy + kill switch.
- Do not enable flag on hosted-beta in this phase.

### DATA-WP-07A (hosted-beta gate - separate)

1. Deploy with flag **false**.
2. Record existing mixed nearby results.
3. Temporarily enable **only** `duplicate-presentation-enabled`.
4. Run bounded representative queries.
5. Prove only strong duplicate pairs are suppressed.
6. Prove hard-conflict and distance-only pairs remain visible.
7. Prove direct detail lookup still works for suppressed-from-nearby IDs.
8. Prove source-link / occupancy / candidate / alias checksums remain unchanged.
9. Restore flag=false.
10. Run canonical smoke.
11. Retain automatic/reviewed linking false.

**Zero suppressions is an acceptable safe result.** Do not lower evidence thresholds
to force suppression.

## 26. Rollback

1. Set `duplicate-presentation-enabled=false` (immediate).
2. Redeploy previous image/manifest if needed.
3. No data rewrite required.

## 27. Risks

| Risk | Mitigation |
|------|------------|
| Over-suppression | Multi-signal + hard-conflict veto + uncertainty shows both |
| Confusion with linking/fusion | Explicit non-goals; no DB writes |
| Under-filled nearby pages | Bounded overfetch/refill |
| Name collision with mobile WP-07 | `DATA-WP-07` / `wp-data-07-*` only |

## 28. Acceptance criteria

### DATA-WP-07 (planning + later implementation)

1. Spec documents nearby-only suppression and stable detail lookup.
2. Source priority alone cannot suppress; distance-only/name-only cannot suppress.
3. Hard conflicts veto; winner selection deterministic; no field/availability merge.
4. Bounded overfetch/refill specified; query-time no DB mutation; flag default false.
5. Test contract items 1-30 are mandatory for implementation.
6. Automatic linking remains disabled; DI WP-05 and mobile WP-07 untouched.

### DATA-WP-07A

1. Deployed image includes DATA-WP-07.
2. Temporary flag enable proves strong-pair-only suppression (or zero suppressions).
3. Detail lookup and checksums pass; flag restored false; smoke passes; production unchanged.

## 29. Deferred work

| Item | Class | Why deferred |
|------|-------|--------------|
| Reviewed-linking enablement | Product + later DATA package | 0 eligible; policy gate |
| Provenance publication UX | Product/frontend | Flag exists; not required |
| Izmir admin boundary polygon | Ops/geo/legal | External license |
| IZELMAN publication / IZELMAN pairs | Legal/freshness/product | Unsupported here |
| Spot-facility fusion / ranking | Unsupported invention | No DATA roadmap mandate |
| Prom recovery increase() quirk | Ops/backlog | Not 07 prerequisite |
| Disk near 12 GiB | Ops hygiene | Preflight enforces |

## 30. Package phasing

| Phase | Objective | Commit boundary | Deploy |
|-------|-----------|-----------------|--------|
| **DATA-WP-07** | Spec + deterministic implementation | Spec/hardening docs + implementation on `api` (originally flag false) | Not required for local 07 complete |
| **DATA-WP-07A** | Hosted-beta presentation validation | Separate ops gate (complete) | Required |
| **DATA-WP-12** | Enable presentation by default | Defaults flip; prod pins false | Hosted-beta leave-on is DATA-WP-12A |

## 31. Implementation status

**DATA-WP-07 implementation is complete** (policy + PostGIS coverage). **DATA-WP-07A** hosted-beta gate is **complete**.

Default-on enablement is **DATA-WP-12** (canonical/hosted-beta true; production profile explicit false). Matching thresholds and detail behavior remain as specified here.

- Query-time nearby duplicate-presentation policy only
- Detail lookup unchanged
- No Flyway migration
- No registry / automatic-linking mutation
- DATA-WP-12A leave-on gate is separate and not started by DATA-WP-12

Specification ancestry: `6826a09` (scope) then `791e462` (hardening).
