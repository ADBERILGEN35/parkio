# DATA-WP-11 — Enable Public Municipal Provenance Publication

> **Naming:** This package is **DATA-WP-11** (municipal/parking data). It is unrelated to
> repository WP-05 / WP-06 / WP-07. Document filenames follow `wp-data-11-*`.

## 1. Status

**Implementation complete (this package).** DATA-WP-11A (hosted-beta leave-on gate) is
**not started** and must not be started by this commit.

## 2. Executive summary

DATA-WP-09 built the bounded public provenance projection (dark by default).
DATA-WP-10 wrote allow-listed field provenance on İZUM/OSM ingest.
**DATA-WP-11** enables that existing projection **by default** without changing
semantics, DTO shape, allow-list, or storage.

## 3. Profile behavior

| Surface | Publication default | Notes |
|---------|---------------------|-------|
| Canonical `application.yml` | **true** (`PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:true`) | Local / generic runtime |
| `application-prod.yml` (`prod` profile) | **false** (env default re-pinned) | Production remains explicit false until separate approval |
| Azure hosted-beta Compose | **true** (`:-true`) | Prepares leave-on gate; DATA-WP-11A validates |
| Test `src/test/resources/application.yml` | **false** | Isolates non-provenance SpringBootTests |

Property: `parkio.municipal.registry.provenance-publication-enabled`  
Env: `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED`

Independent of: ingest-write, candidate generation, review API, reviewed/automatic linking,
İZELMAN publication.

## 4. Public contract (unchanged from DATA-WP-09)

Nearby/detail may expose only:

- `contributingSourceKeys`
- `selectedFieldProvenanceSummary`

`registryConfidenceOrReviewStatus` remains **always null**.

Allow-listed fields: `NAME`, `COORDINATES`, `ADDRESS`, `OPERATOR`, `FACILITY_TYPE`,
`STATIC_CAPACITY`, `ATTRIBUTION`.

Flag off → those two fields are **null** (additive DTO contract).  
Flag on with no publishable rows → **empty** list/map (not null).

## 5. Forbidden

Never expose registry/candidate/review IDs, reviewer identity, confidence/eligibility,
link state, generation runs, unpublished source keys (including İZELMAN while publication
off), internal timestamps/history, or source diagnostics.

## 6. Semantics

Answers only: **which publishable source supplied this selected public field?**  
Does not imply equivalence, trust score, ranking, reviewed link, confidence, or quality.

OSM null availability and İZUM occupancy authority are unchanged. Read-only; no mutations.

## 7. Kill-switch / rollback

Set `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED=false` (or property false).
Nearby/detail provenance fields return to **null** immediately. No migration or DB rewrite.

## 8. Migration

None.

## 9. Hosted-beta

**DATA-WP-11A** (separate): deploy with publication left on; DTO/metrics/mutation/smoke.
Not started here.

## 10. Production warning

Do **not** enable publication in production without a separate rollout approval.
`application-prod.yml` keeps the env default **false** when the `prod` profile is active.