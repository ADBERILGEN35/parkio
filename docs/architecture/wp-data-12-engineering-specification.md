# DATA-WP-12 — Enable Nearby Duplicate-Presentation by Default

> **Naming:** This package is **DATA-WP-12** (municipal/parking data). It is unrelated to
> repository WP-05 / WP-06 / WP-07. Document filenames follow `wp-data-12-*`.

## 1. Status

**Implementation complete.** DATA-WP-12A (hosted-beta leave-on gate) is **complete**
(ACCEPT WITH NON-BLOCKING NOTES): hosted-beta runtime left with
`duplicate-presentation=true`; production profile remains pinned `false`.

DATA-WP-07 / DATA-WP-07A remain closed (policy implemented and live-tested). This package
only flips the existing master switch defaults.

## 2. Executive summary

DATA-WP-07 shipped a deterministic nearby-only duplicate-presentation policy behind a
default-false flag. **DATA-WP-12** enables that existing policy **by default** without
changing matching thresholds, source-pair rules, winner policy, overfetch bounds, DTO
shape, detail endpoint behavior, or any write path.

## 3. Profile behavior

| Surface | Duplicate-presentation default | Notes |
|---------|--------------------------------|-------|
| Canonical `application.yml` | **true** (`PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED:true`) | Local / generic runtime |
| `application-prod.yml` (`prod` profile) | **false** (env default re-pinned) | Production remains explicit false until separate approval |
| Azure hosted-beta Compose | **true** (`:-true`) | Prepares leave-on gate; DATA-WP-12A validates |
| Test `src/test/resources/application.yml` | **false** | Isolates non-discovery SpringBootTests |

Property: `parkio.municipal.discovery.duplicate-presentation-enabled`  
Env: `PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED`

Independent of: provenance publication, candidate generation, review API,
reviewed/automatic linking, İZELMAN publication.

Radius / overfetch / supported pairs remain unchanged from DATA-WP-07:

- `duplicate-radius-meters` default **100**
- `overfetch-factor` default **2**
- `overfetch-absolute-max` default **200**
- supported pairs: **IZUM_OSM** only

## 4. Behavior contract (unchanged from DATA-WP-07)

Only `GET /api/v1/parking/facilities/nearby` may suppress presentation duplicates.

Never affect `GET /api/v1/parking/facilities/{id}` — suppressed IDs remain directly
retrievable.

Preserve:

- IZUM↔OSM only
- multi-signal evidence required
- distance-only / name-only never suppress
- hard conflicts veto suppression
- uncertainty shows both
- winner: LIVE/AGING IZUM → STALE IZUM → OSM → stable ID
- suppression before final limit; bounded overfetch/refill; deterministic ordering

**Zero suppressions is valid.** False positives are avoided conservatively; do not lower
thresholds to force suppression.

## 5. No mutation

Read-only query behavior. No writes to facilities, source links, candidates, reviews,
aliases, occupancy, tariffs, provenance, publication state, or registry lifecycle.

## 6. Kill-switch / rollback

Set `PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED=false` (or property false).
Nearby immediately restores legacy result set/order (exact limit fetch, no suppression).
No migration or DB rewrite.

## 7. Migration

None.

## 8. Hosted-beta leave-on procedure (DATA-WP-12A — not started)

1. Deploy image that includes DATA-WP-12 defaults (`:-true` on Compose).
2. Confirm env/Compose leave presentation **on**.
3. Capture representative nearby queries before/after (baseline may already be on).
4. Prove only strong IZUM↔OSM duplicates suppress; hard-conflict / distance-only remain.
5. Prove detail lookup for suppressed-from-nearby IDs.
6. Prove mutation checksums unchanged; linking + İZELMAN remain false.
7. Canonical smoke; leave flag on (do not restore false unless incident).

## 9. Production warning

Do **not** enable duplicate-presentation in production without a separate rollout
approval. `application-prod.yml` keeps the env default **false** when the `prod`
profile is active. Production rollout state is unchanged by this package.