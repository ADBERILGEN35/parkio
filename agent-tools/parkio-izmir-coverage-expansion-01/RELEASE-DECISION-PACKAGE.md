# İzmir parking coverage — release decision package

**Date:** 2026-09-22  
**PR:** [#71](https://github.com/ADBERILGEN35/parkio/pull/71) `feat/izmir-parking-coverage-expansion`  
**Final tip (this package):** `3b7fdc8f59d1d091be7b5d3c75aec4434c4e6b17`  
**Production mutation:** **not executed** — reversible preparation only.  
**Out of scope:** PR #68, New Relic, Hostinger gateway flips.

---

## 1. Production vs candidate identities

### 1.1 Verified runtime (SSH, `StrictHostKeyChecking=yes`)

Probed `2026-09-22T06:54:27Z` as `civo@api.parkio.dev` on host `parkio-civo-prod`  
Evidence: `runtime-image-identities.txt`

| Service | Running identity | Status |
| --- | --- | --- |
| parking-service | `ghcr.io/adberilgen35/parkio/parking-service@sha256:e353baed3f464849208ef8852314ad8c469663d20ad8c2f755ac236bd1452dfe` | **verified running** |
| web | `ghcr.io/adberilgen35/parkio/web@sha256:7202058c2d75537a5578acd07f65c645d6237e3eaaef89e5be49a6b785e82761` | **verified running** |
| Host compose pin file | `/opt/parkio/docker/docker-compose.gmp-release-pins.yml` parking pin = same `e353baed…` | matches runtime |

**Wording:** The local repo pin is the **expected configuration**. Runtime was separately verified and currently matches that pin for parking. Do not treat a pin file alone as runtime proof without the SSH probe.

Merged PR #70 (`f0abf765` on `api`) is **not** in the running parking image (`e353baed…` is pre-#70).

### 1.2 Candidate artifacts (local; not on ghcr) — tip `3b7fdc8f`

| Artifact | Identity | Notes |
| --- | --- | --- |
| Source | `3b7fdc8f59d1d091be7b5d3c75aec4434c4e6b17` | Roadside Explore+/map UI + radius continuation + count wording |
| Parking RC (local) | `parkio/parking-service:izmir-coverage-rc-3b7fdc8f59d1` `@sha256:4f35e1eddb9c6f74ca312255d2d9a86fbed8e998c2cd2545b8b80642715582c4` | Rebuilt for this tip; **not on ghcr** |
| Web RC (local) | `parkio/web:izmir-coverage-rc-3b7fdc8f59d1` `@sha256:054349a32374c7d0cd03d3056d437a6fbffbd1ad4816c105dc5df6a11b25772b` | Explore+municipal ON; MapTiler present; roadside inventory chunk baked; **not on ghcr** |
| Web bake profile | `web-izmir-coverage.candidate-bake.env` | Server families at flip: `IZUM,IZELMAN,OSM` |
| OSM clip | `izmir-admin-izbb-2024-10-18-v1` | GeoJSON SHA `2344db92…` |

Evidence: `parking-rc-image.txt`, `web-rc-image.txt`, `parking-rc-docker-build-roadside-ui.txt`, `web-rc-docker-build-roadside-ui.txt`.

**ghcr publish of parking+web RC images = remaining release action** (not done in this step).

---

## 2. Combined candidate inventory (IZUM + İZELMAN×4 + OSM)

Evidence: `combined-candidate-coverage-report.json`  
IT: `CombinedIzmirCoverageCandidateIT` (`auto-match-enabled=false`) — log `combined-candidate-it-roadside-ui.txt`

| Layer | Count | Discoverability |
| --- | --- | --- |
| IZUM active facilities (fixture sync) | 12 | Facility nearby + Explore |
| İZELMAN facilities (open+closed+barrier) | **51** | Facility nearby + Explore |
| OSM accepted facilities | **1442** (1634 raw − 192 access rejects) | Facility nearby + Explore |
| **Distinct facility table records (no auto-merge applied)** | **1505** (= 12+51+1442) | Authenticated `/facilities/nearby` |
| İZELMAN roadside segments | **48** | **Separate count** — `/parking/roadside/nearby` **and** product UI (Explore + `/map`) when IZELMAN allowlisted |
| OSM↔IZUM link merges applied | **0** | Auto-match proposals recorded only |

### 2.0 Count wording (do not overclaim)

**1505 is not proof of 1505 unique physical parking locations.** It is the count of distinct `municipal_parking_facilities` rows across sources after isolated candidate import with `auto-match-enabled=false`. Unresolved cross-source duplicates may remain; automatic matching is intentionally not enabled. Keep the **48 roadside records counted separately** (not facility rows).

### 2.1 How 99 İZELMAN rows become 51 + 48

| Dataset | Raw rows | Product surface |
| --- | --- | --- |
| open | 11 | Facility inventory |
| closed | 23 | Facility inventory |
| barrier | 17 | Facility inventory (RESTRICTED access) |
| **Facility subtotal** | **51** | Authenticated `/map` facilities + Explore (when family allowlisted) |
| roadside | 48 | Roadside API + Explore merge + `/map` merge (ON_STREET, UNKNOWN access, UNAVAILABLE occupancy) |
| **Total CSV rows** | **99** | 51 + 48 |

Roadside is **not linked into** `municipal_parking_facilities`. With `roadside-publication-enabled=true`, import sets `publication_status=PUBLISHED`. Product UX now consumes them:

- **Anonymous Explore:** `PublicExploreQueryService` merges published roadside when `IZELMAN` is allowlisted.
- **Authenticated `/map`:** FE calls `GET /parking/roadside/nearby` (limit 50, r≤5 km) and merges onto municipal markers/list.
- **Detail:** anonymous View details → AuthGate; roadside has no facility detail route (AuthGate to `/map`; authenticated preview hides facility-detail CTA).

### 2.2 Roadside product-UI candidate evidence (representative IDs)

Evidence: `roadside-ui-candidate-evidence.json`

**Alsancak** (`38.438, 27.142`, Explore `limit=6`, `radiusMeters=5000`):

| Field | Value |
| --- | --- |
| exploreTotal | **335** (facilities + roadside in radius; previously ~287 facilities-only) |
| exploreVisible | 6 |
| exploreRoadsideVisibleIds | `3611ffcb-2205-4894-9fe3-f14d6e518e9d`, `8e16b9a2-1dec-42a3-a032-514cfdc27480` |
| representativeRoadsideIdsInRadius | above + `b9ec702b-f40a-4f12-acd1-1b4b43962ebc` |
| exploreFamiliesSample | IZELMAN, OSM |
| roadsideWithGeometryInRadius | 48 |

FE visible UI (AuthGate + access/occupancy chips): `web-explore-roadside-vitest.txt` (`PublicExplorePage` roadside marker → AuthGate).

### 2.3 Six-center combined probes (5 km)

| Center | Explore total / visible | Map facilities@100 / capped | Roadside in radius | Explore roadside visible IDs |
| --- | --- | --- | --- | --- |
| Hatay | 296 / 6 | 100 / yes | 46 | (none in nearest 6; reps available) |
| Konak | 346 / 6 | 100 / yes | 48 | (none in nearest 6; reps available) |
| Alsancak | 335 / 6 | 100 / yes | 48 | **2 IDs above** |
| Karşıyaka | 354 / 6 | 100 / yes | 48 | (none in nearest 6) |
| Bornova | 189 / 6 | 100 / yes | 0 | — |
| Buca | 211 / 6 | 100 / yes | 1 | (rep `352cb99f-…`) |

Access labels observed: PUBLIC, UNKNOWN, RESTRICTED, PERMISSIVE.  
İZELMAN/OSM occupancy spaces stay null. Attribution present on roadside projections.

### 2.4 Capped search continuation

When facility nearby returns the limit (100):

- UI shows `municipal-results-capped` and a **Tighten search** control that changes `radiusMeters` on the next API request (`municipal-radius-reduce` / radius `<select>`).
- Query keys include `radiusMeters` (`normalizeMunicipalNearbyFilters`) — radius change refetches.
- **Map zoom alone does not change the API search** (copy corrected; evidence: `web-roadside-radius-vitest.txt`).

---

## 3. Conflation disposition (conservative)

| Decision | OSM id | IZUM id | Score | Production disposition |
| --- | --- | --- | --- | --- |
| AUTO_MATCHED (proposal) | `way/601644098` | `CPS-TR-IZM-M2-04` | 0.95 | **Proposal only** — `auto-match-enabled=false`; no link reassignment |
| REVIEW_REQUIRED | `way/1559185892` | `NEDAP-TR-IZM-008` | 0.50 | Manual review queue; do not auto-merge |
| REVIEW_REQUIRED | `way/1557918177` | `NEDAP-TR-IZM-024` | 0.45 | Manual review queue; do not auto-merge |

**Do not** enable broad automatic production matching because the isolated IT recorded these proposals.

---

## 4. CI status (tip `3b7fdc8f`)

Terminal capture: `ci-71-roadside-ui.txt` (filled when `gh pr checks 71 --watch` completes).

Expected required gates for this tip: Backend unit, Integration, Frontend, Security (incl. parking Trivy scan). Legacy mobile remains advisory. Mobile-v2 status recorded from the same tip run.

---

## 5. Deploy → import → publish → acceptance → rollback

**Still unexecuted.** Exact sequence when approved:

1. **Backup** `municipal_*` (+ roadside + conflation tables).
2. **Deploy parking** RC `@sha256:4f35e1ed…`; update GMP pin off `e353baed…`.
3. **Deploy web** RC `@sha256:054349a3…` (Explore+municipal bake; roadside merge + radius continuation).
4. Set parking Explore allowlist still **IZUM-only** until inventories imported.
5. **Import (publication false for facilities where applicable):** IZUM → İZELMAN×4 → OSM (auto-match **disabled**).
6. Manual review of 2 REVIEW_REQUIRED pairs; optional accept of the 1 high-score proposal.
7. **Publish flips:** facility İZELMAN → OSM → widen Explore to `IZUM,IZELMAN,OSM`; roadside PUBLISHED when flag on at import.
8. **Acceptance:** six centers Explore + `/map` + roadside markers/list; Alsancak Explore shows representative roadside IDs; AuthGate on anonymous detail; capped radius control changes API search; no fake occupancy.
9. **Rollback:** re-pin previous digests; publication flags false; Explore allowlist previous; roadside can be bulk-set UNPUBLISHED.

---

## 6. Remaining release actions (not this step)

- Push parking + web RC images to **ghcr** and update compose pins.
- Production import / publication / Explore family widen.
- Manual conflation review of the two REVIEW_REQUIRED pairs.

---

## Decision ask

Approve ghcr publish + pin update for parking and web RCs (`4f35e1ed…` / `054349a3…`), then staged import/publish per §5. Until then: **no production deploy, import, or publication.**
