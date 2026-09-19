# PA-06 / G06 — surface and threat model (baseline f6b91ad9)

## Original finding
- ID: PA-06 (P2, CONFIRMED)
- Source: `agent-tools/parkio-full-system-audit-01/20260915T083111Z/04-findings.md`
- Behavior on current api tip: **still present** — `PublicExploreQueryService` publishes exact `communitySpotCountInScope` for raw count ≥ 3 under client-chosen lat/lng/radiusMeters.

## Public disclosure surface
| Endpoint | Auth | Community | Municipal |
|---|---|---|---|
| `GET /api/v1/public/explore/facilities` | None (gateway allowlist + IP RL) | Exact count if ≥3, else null | Rows ≤6 + totals/hidden |
| `GET /api/v1/public/geocoding/search` | None | None | Destinations only |
| Authenticated nearby/spot APIs | Required | Full authorized access | N/A |

No HTTP public facility-by-id. No bbox/filter/time query params on Explore.

## Protected unit / privacy property
- **Protected unit:** Individual community parking spots (`parking_spots` ACTIVE|VERIFIED, non-expired) that are **not** published as public rows to anonymous clients.
- **Intended property:** An unauthenticated observer must not learn whether a specific additional community spot exists in a chosen spatial annulus/crescent via repeated Explore queries (count differencing), nor observe exact counts that enable that inference.
- Municipal facilities are **public by design** (exact coords + occupancy teasers remain).

## Demonstrated inference (synthetic)
Fixtures in audit `evidence/community-privacy-fixtures.json`:
- Radius 99→101 m with stable anchors: published count 3→4 ⇒ one point in annulus.
- Center −1→+1 m: 3→4 ⇒ crescent membership.
- Before/after single report: 3→4 while k satisfied.
- Sparse null→3: threshold crossing signal.

Rate limit, row suppression, and k=3 alone do **not** close free-geometry differencing.

## Selected remediation
**Always withhold** `communitySpotCountInScope` on the anonymous Explore path (`null`), and do not query community counts for that path.
- Keeps DTO field for older clients (null = unavailable/withheld, already ≠ zero in UI).
- Preserves municipal discovery.
- Does not invent differential privacy / fixed cells in this package (those remain future product work if a community teaser is reintroduced).
