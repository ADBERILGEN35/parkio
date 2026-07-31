# DATA-WP-09 — Public Facility Provenance Publication

> **Naming:** This package is **DATA-WP-09** (municipal/parking data). It is unrelated to
> repository WP-05 / WP-06 / WP-07. Document filenames follow `wp-data-09-*`.

## 1. Executive summary

DATA-WP-01 through DATA-WP-07 delivered municipal sources, OSM publication, dark IZELMAN
tooling, canonical registry/provenance storage, bounded candidate generation (dark),
source SLA, and nearby duplicate-presentation safety.

Public nearby/detail responses already carried stable facility identity, attribution, and
availability semantics, but field-level "which public source supplied this information?"
remained hidden behind `provenance-publication-enabled=false`.

**DATA-WP-09** enables bounded, read-only public provenance enrichment for:

- `GET /api/v1/parking/facilities/nearby`
- `GET /api/v1/parking/facilities/{id}`

It publishes provenance. It does **not** publish registry internals, enable linking, or
mutate storage. Flag default remains **false**. DATA-WP-09A (hosted-beta gate) is separate
and not started by this package.

DATA-WP-08 (Izmir administrative-boundary clip) remains externally blocked and is out of
scope here.

## 2. Goals

1. Expose allow-listed field to public `source_key` summaries when the flag is on.
2. Expose distinct contributing public source keys derived only from those fields.
3. Keep DTO shape backward compatible (additive fields already present; confidence status
   remains present but always null).
4. Keep mutation, linking, IZELMAN publication, and geography-clip work untouched.

## 3. Non-goals

Never expose:

- registry / candidate / review IDs
- reviewer or operator identity
- confidence scores or review state
- link state, eligibility, generation runs
- source diagnostics or internal provenance history
- internal timestamps beyond already-public facility timestamps

Never:

- create / infer links or imply canonical equivalence
- imply trust ranking or data-quality scores
- write registry, provenance, links, reviews, candidates, occupancy, or tariffs
- start DATA-WP-09A, deploy, or flip production flags

## 4. Public contract

| Field | Flag off | Flag on |
|-------|----------|---------|
| `contributingSourceKeys` | `null` | ordered distinct publishable `source_key` values |
| `selectedFieldProvenanceSummary` | `null` | map of allow-listed field to `source_key` only |
| `registryConfidenceOrReviewStatus` | `null` | always `null` (retained for JSON compatibility) |

Allow-listed fields (public DTO-aligned):

`NAME`, `COORDINATES`, `ADDRESS`, `OPERATOR`, `FACILITY_TYPE`, `STATIC_CAPACITY`, `ATTRIBUTION`

Omitted even if stored: e.g. `ACCESS`, `DISTRICT`, `OPENING_STATUS`, `TARIFF_ASSIGNMENT`.

Unpublished sources (e.g. IZELMAN while facility publication is false) are omitted.

## 5. Data semantics

Publication answers only: **which public source supplied this information?**

It does not create links, merge identities, or change availability authority
(IZUM-only live occupancy; OSM null availability preserved).

## 6. Feature flag

| Flag | Default | Purpose |
|------|---------|---------|
| `parkio.municipal.registry.provenance-publication-enabled` | **true** (DATA-WP-11) | Master switch |

Env: `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED` (mapped in
`docker/docker-compose.azure-hosted-beta.yml` with Compose default `:-true`).

Production (`application-prod.yml`): env default re-pinned to **false** until a separate
production rollout approval. See [DATA-WP-11](wp-data-11-engineering-specification.md).

## 7. Metrics

Bounded counters:

- `parkio.municipal.registry.provenance.publication` — labels `outcome`, `source_family`, `policy_version`
- `parkio.municipal.registry.provenance.publication.fields` — labels `field_name`, `policy_version`
- `parkio.municipal.registry.provenance.publication.field_count` — labels `outcome`, `source_family`, `policy_version`

Never label with facility ID, name, address, coordinates, or request ID.

## 8. Migration

None. Reuses V32 `municipal_facility_field_provenance`.

## 9. Tests

- Unit: allow-list / unpublished-source projection; flag-off no-query; flag-on enrichment
- DTO: flag-off nulls; flag-on source-key-only JSON; OSM null availability compatibility
- Architecture: publication sources must not reference confidence/review/link/candidate columns
- Postgres IT: nearby + detail enrichment; IZELMAN/internal fields omitted; mutation counts unchanged; linking flags false

## 10. Hosted-beta

**DATA-WP-09A** (complete): temporary enable then restore false (pre-DATA-WP-11).
**DATA-WP-11A** (separate, not started): leave publication on after DATA-WP-11 default-on.

## 11. Rollback

Set `provenance-publication-enabled=false`. No schema rollback.