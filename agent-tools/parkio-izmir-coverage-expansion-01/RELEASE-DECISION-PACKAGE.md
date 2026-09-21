# İzmir parking coverage — release decision package (prep complete)

**Date:** 2026-09-22  
**PR:** [#71](https://github.com/ADBERILGEN35/parkio/pull/71) `feat/izmir-parking-coverage-expansion`  
**Production mutation:** **not executed** — reversible preparation only.  
**Out of scope:** PR #68, New Relic, Hostinger gateway flips.

---

## 1. Exact code / image identities

| Identity | Value | Notes |
| --- | --- | --- |
| Merged PR #70 (IZUM incomplete-snapshot guard) | `f0abf765` on `api` | **Merged ≠ deployed** |
| Live production parking pin | `ghcr.io/adberilgen35/parkio/parking-service@sha256:e353baed3f464849208ef8852314ad8c469663d20ad8c2f755ac236bd1452dfe` | Pre-#70; still in `docker/docker-compose.gmp-release-pins.yml` |
| Candidate branch HEAD | `4abbf326` (contains `f0abf765` / #70 + coverage prep) | PR #71 tip |
| Local RC bootJar SHA-256 | `F0EACE14E426AD44000E848A3709EC57BA4A73F23BB54521F6B99D851589E1D5` | `parking-service-0.0.1-SNAPSHOT.jar` |
| Local RC image (buildx manifest) | `sha256:c0b4d858cedbfacc122ea29fd422ddd838d652fa18911ac1050fa15dae668f86` | Tag `parkio/parking-service:izmir-coverage-rc-4abbf326`; **not pushed to ghcr** |
| OSM clip asset | `izmir-admin-izbb-2024-10-18-v1` | Boundary SHA match in `osm-ops/boundary/validation-report.json` |
| OSM GeoJSON SHA-256 | `2344db92b21d72ebe252d02b858837ccc964db222faca17236588fdf7eabd81c` | 1634 features → 1442 publishable |

**Verdict:** Production does **not** include PR #70. The release candidate **must** ship a parking image built from this PR (which already contains #70). Do not claim #70 is live until the pin moves off `e353baed…`.

---

## 2. Before / after unique publishable counts

### Production today (live pin `e353baed…`)

| Source | Unique publishable (approx) | Occupancy authority |
| --- | --- | --- |
| IZUM live poll | ~6 active rows (incomplete vs historical inventory) | LIVE when snapshot valid |
| İZELMAN | not published | n/a |
| OSM | not published | n/a |

### Isolated candidate stack (Testcontainers — not production)

#### İZELMAN (all four datasets) — evidence `izelman-all4-system-out.txt`

| Dataset | Raw rows | Unique accepted | Publishable facilities / segments | 2nd import |
| --- | --- | --- | --- | --- |
| open | 11 | 11 | 11 facilities | 11 unchanged/updated, 0 inserted |
| closed | 23 | 23 | 23 facilities | 23 unchanged/updated, 0 inserted |
| barrier | 17 | 17 | 17 facilities (RESTRICTED) | 17 unchanged/updated, 0 inserted |
| roadside | 48 | 48 | 48 roadside segments | 48 unchanged/updated, 0 inserted |
| **Facility total** | **51** | **51** | **51 unique facility ids** | idempotent |
| Occupancy snapshots | — | — | **0** | historical CSV never invents occupancy |

Closed CSV stable identities verified: **KONAK KATLI**, **HATAY PAZAR YERİ KATLI** (external_id + coords, not name-only).

#### OSM İzmir clip — evidence `osm-ops/data/data-wp-02a-controlled-import-report.json`

| Metric | Count |
| --- | --- |
| Raw GeoJSON features | 1634 |
| Rejected (`access_not_publishable`) | 192 |
| Accepted / inserted (1st import) | 1442 |
| 2nd import unchanged | 1442 (idempotent) |
| Auto-match vs IZUM seed | 1 |
| Review-required | 2 |
| OSM occupancy snapshots | 0 |
| Missing OSM `access` tag | mapped to **UNKNOWN** (not PUBLIC); still publishable for discovery |

#### District-oriented center probes (İZELMAN facilities only, 5 km, candidate DB)

| Center | Explore total / visible (limit 6) | Authenticated map count (limit 100) | Capped at 100? |
| --- | --- | --- | --- |
| Hatay / Karantina / Göztepe | 34 / 6 | 34 | no |
| Konak | 42 / 6 | 42 | no |
| Alsancak | 29 / 6 | 29 | no |
| Karşıyaka | 20 / 6 | 20 | no |
| Bornova | 3 / 3 | 3 | no |
| Buca | 8 / 6 | 8 | no |

With **OSM published**, geometric density at 5 km exceeds 100 at every listed center (see `limit-100-density-assessment.txt`). UX must treat the first 100 as a bounded nearest set, not complete coverage.

---

## 3. Source dates and publication criteria

| Source | Source date / clip | Age class | Publication criteria (candidate → prod flip) |
| --- | --- | --- | --- |
| IZUM | live feed | CURRENT when poll succeeds | Already reviewed Explore family; incomplete SUCCESS must not mass soft-deactivate (#70) |
| İZELMAN open/closed/barrier | 2022-11-25/28 CSVs | HISTORICAL | Facility publication flags; Explore family `IZELMAN` when allowlisted; no occupancy |
| İZELMAN roadside | 2022-11-25 | HISTORICAL | Roadside publication separate; **not** Explore facility rows |
| İZELMAN tariffs | — | unsuitable | No geometry / not CURRENT — leave off |
| OSM Geofabrik Turkey × İzmir clip | clip `izmir-admin-izbb-2024-10-18-v1` | CURRENT map extract, static inventory | ODbL attribution; publication flag; Explore family `OSM`; access PRIVATE/CUSTOMERS/NO/PERMIT rejected; missing access → UNKNOWN |

Criteria reference: `PUBLICATION-CRITERIA.md`, `SOURCE-INVENTORY.md`.

---

## 4. Source-specific import / publication order (when approved)

1. **Backup** `municipal_*` (+ related links/snapshots/runs).
2. Deploy parking image containing **#70 + #71** (replace `e353baed…`).
3. IZUM sync → confirm Hatay/Konak recovery path (reactivate by stable IZUM id if still missing).
4. İZELMAN import **publication=false** → record checksums → flip facility publication.
5. OSM import **publication=false** → review conflation (1 auto / 2 review from isolated run) → flip publication.
6. Set Explore allowlist to intended families, e.g. `IZUM,IZELMAN,OSM` (preview limit stays 6; AuthGate unchanged).
7. Post-deploy acceptance: six centers Explore + `/map`; Hatay/Konak by stable ids; access chips visible; capped banner when count==100.

---

## 5. Backups, rollback, post-deploy acceptance

**Backups:** full `municipal_*` dump before any import/publication flip.

**Rollback:**
- Re-pin parking to previous digest (`e353baed…` only if #70 never needed for recovered inventory — prefer forward pin with publication flags false).
- Publication flags → false per source.
- Explore allowlist → previous value (fail-closed empty or IZUM-only).

**Post-deploy acceptance (not run in this prep):**
- Health + municipal nearby at six centers.
- Explore returns multi-family inventory with limit=6 preview and AuthGate on detail.
- No fabricated occupancy on İZELMAN/OSM.
- accessClassification visible on list, preview, and detail.
- When map returns 100 rows, `municipal-results-capped` messaging shown (zoom / reduce radius).

---

## 6. Product prep completed on candidate (code)

- Authenticated nearby default/max **100**; web `/map` requests 100.
- Explore preview **limit 6** remains; source family allowlist can include **IZELMAN + OSM** (not IZUM-only).
- `accessClassification` on municipal + Explore DTOs; shown on list, selected preview, and detail (PUBLIC / RESTRICTED / UNKNOWN distinguishable; missing ≠ PUBLIC).
- Truncation UX: `municipal.resultsCapped` (en/tr) when `totalCount >= limit`.

---

## 7. Unresolved blockers / decisions still required

| Item | Status |
| --- | --- |
| Push RC image to ghcr + update GMP pin | **Blocked on release decision** (prep image built locally only) |
| Production İZELMAN/OSM import + publication | **Not executed** |
| Registry IZUM↔İZELMAN auto-pair | Still disabled in code — manual review for duplicates |
| Live Hatay/Konak presence on prod IZUM | Depends on post-#70 deploy + sync; İZELMAN closed CSV is recovery inventory |
| Dense OSM at 5 km | Will hit limit 100 — continuation is zoom/radius, not pagination |
| PR #68 / New Relic | Kept separate |
| Security CI summary on tip `f526df3d` | Failed only because unrelated `ai-validation` / `gamification` Trivy **artifact upload 403**; parking container scan, Backend unit, Integration, Frontend, Mobile-v2 all **pass** |

---

## Decision ask

Approve (1) parking pin update to RC containing #70+#71, then (2) staged imports/publication per §4. Until then: **no production deploy, import, or publication.**
