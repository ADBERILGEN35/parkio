# İzmir parking coverage — release decision package

**Date:** 2026-09-22  
**PR:** [#71](https://github.com/ADBERILGEN35/parkio/pull/71) `feat/izmir-parking-coverage-expansion`  
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

**Wording:** The local repo pin (`docker/docker-compose.gmp-release-pins.yml`) is the **expected configuration**. Runtime was separately verified above and currently matches that pin for parking. Do not treat a pin file alone as runtime proof without the SSH probe.

Merged PR #70 (`f0abf765` on `api`) is **not** in the running parking image (`e353baed…` is pre-#70).

### 1.2 Candidate artifacts (local; not on ghcr)

| Artifact | Identity | Notes |
| --- | --- | --- |
| Source | PR #71 tip after this package commit | Includes #70 + Explore families + access/cap UX + roadside PUBLISHED-on-import |
| Parking RC (local) | `parkio/parking-service:izmir-coverage-rc-*` `@sha256:bea7c5501894d8b6faf0ebd449998b25f5989bc588b8affeb5dfb8d6721a6fa0` | Rebuild after tip push; **not on ghcr** |
| Web RC (local) | `parkio/web:izmir-coverage-rc-*` `@sha256:e04d19aa4179ac25ffc2a34e68ea3c71951566656ca033a1fe375f271695bf7e` | Explore+municipal ON; MapTiler present; **not on ghcr** |
| Web bake profile | `web-izmir-coverage.candidate-bake.env` | Server families at flip: `IZUM,IZELMAN,OSM` |
| OSM clip | `izmir-admin-izbb-2024-10-18-v1` | GeoJSON SHA `2344db92…` |

**ghcr publish of parking+web RC images = remaining release action** (not done in this step).

---

## 2. Combined candidate inventory (IZUM + İZELMAN×4 + OSM)

Evidence: `combined-candidate-coverage-report.json`  
IT: `CombinedIzmirCoverageCandidateIT` (`auto-match-enabled=false`)

| Layer | Count | Discoverability |
| --- | --- | --- |
| IZUM active facilities (fixture sync) | 12 | Facility nearby + Explore |
| İZELMAN facilities (open+closed+barrier) | **51** | Facility nearby + Explore |
| OSM accepted facilities | **1442** (1634 raw − 192 access rejects) | Facility nearby + Explore |
| **Unique active facilities (no auto-merge applied)** | **1505** (= 12+51+1442) | Map `/facilities/nearby` |
| İZELMAN roadside segments | **48** | **Separate** `/api/v1/parking/roadside/nearby` |
| OSM↔IZUM link merges applied | **0** | Auto-match proposals recorded only |

### 2.1 How 99 İZELMAN rows become 51 + 48

| Dataset | Raw rows | Product surface |
| --- | --- | --- |
| open | 11 | Facility inventory |
| closed | 23 | Facility inventory |
| barrier | 17 | Facility inventory (RESTRICTED access) |
| **Facility subtotal** | **51** | Authenticated `/map` facilities + Explore (when family allowlisted) |
| roadside | 48 | **Independent roadside API** — not facility rows, not Explore facilities |
| **Total CSV rows** | **99** | 51 + 48 |

Roadside is **not linked into** `municipal_parking_facilities`. With `roadside-publication-enabled=true`, import now sets `publication_status=PUBLISHED` and PostGIS `location` from ENLEM/BOYLAM so `/parking/roadside/nearby` returns them (fix landed in this prep window — previously rows stayed `UNPUBLISHED` and were invisible to the nearby API).

Street-parking coverage is therefore **retained** on the roadside surface; it must be wired in product UX to that endpoint (not assumed inside facility map markers).

### 2.2 Six-center combined probes (5 km)

| Center | Explore total / visible | Map count / capped@100 | Roadside in radius | Families on map |
| --- | --- | --- | --- | --- |
| Hatay | 250 / 6 | 100 / yes | 46 | IZELMAN, IZUM, OSM |
| Konak | 298 / 6 | 100 / yes | 48 | OSM, IZELMAN, IZUM |
| Alsancak | 287 / 6 | 100 / yes | 48 | OSM, IZUM, IZELMAN |
| Karşıyaka | 306 / 6 | 100 / yes | 48 | IZELMAN, OSM |
| Bornova | 189 / 6 | 100 / yes | 0 | OSM, IZELMAN |
| Buca | 210 / 6 | 100 / yes | 1 | OSM, IZELMAN |

Access labels observed: PUBLIC, UNKNOWN, RESTRICTED, PERMISSIVE.  
İZELMAN/OSM occupancy spaces stay null (no LIVE/AGING leak). Attribution present.  
When map returns 100: continuation = **zoom in or reduce radius** — not complete coverage.

---

## 3. Conflation disposition (conservative)

| Decision | OSM id | IZUM id | Score | Production disposition |
| --- | --- | --- | --- | --- |
| AUTO_MATCHED (proposal) | `way/601644098` | `CPS-TR-IZM-M2-04` | 0.95 | **Proposal only** — `auto-match-enabled=false`; no link reassignment |
| REVIEW_REQUIRED | `way/1559185892` | `NEDAP-TR-IZM-008` | 0.50 | Manual review queue; do not auto-merge |
| REVIEW_REQUIRED | `way/1557918177` | `NEDAP-TR-IZM-024` | 0.45 | Manual review queue; do not auto-merge |

**Do not** enable broad automatic production matching because the isolated IT recorded these proposals.

---

## 4. CI status (distinguish scan vs upload)

On tip `ab822004` (docs commit after prior tip):

| Check | Result |
| --- | --- |
| Backend unit / Integration / Frontend / Mobile-v2 | **pass** |
| Container scan (parking-service) | **pass** (actual Trivy scan) |
| All other container scans + Security CI summary | **pass** |
| Legacy mobile (advisory) | fail (advisory only) |

Prior tip had Trivy **artifact-upload 403** on ai-validation/gamification (evidence upload failure, not scan CVE fail). **Resolved on subsequent push** — Security CI summary green. Do not label an earlier tip’s full suite PASS.

---

## 5. Deploy → import → publish → acceptance → rollback

**Still unexecuted.** Exact sequence when approved:

1. **Backup** `municipal_*` (+ roadside + conflation tables).
2. **Deploy parking** RC image (contains #70 + coverage + roadside publish fix); update GMP pin off `e353baed…`.
3. **Deploy web** RC image (Explore+municipal bake; access + resultsCapped UX).
4. Set parking Explore allowlist still **IZUM-only** until inventories imported.
5. **Import (publication false for facilities where applicable):**
   - IZUM sync (confirm Hatay/Konak by stable ids).
   - İZELMAN open → closed → barrier → roadside (checksums).
   - OSM GeoJSON clip (auto-match **disabled**).
6. Manual review of 2 REVIEW_REQUIRED pairs; optional accept of the 1 high-score proposal.
7. **Publish flips:** facility İZELMAN → OSM → widen Explore to `IZUM,IZELMAN,OSM`; roadside already PUBLISHED when flag on at import.
8. **Acceptance:** six centers Explore + `/map` + roadside nearby; access chips; capped banner at 100; no fake occupancy.
9. **Rollback:** re-pin previous digests; publication flags false; Explore allowlist previous; roadside `publication_status` can be bulk-set UNPUBLISHED if needed.

---

## 6. Remaining release actions (not this step)

- Push parking + web RC images to **ghcr** and update compose pins.
- Production import / publication / Explore family widen.
- Product UX for roadside nearby (if not already surfaced on `/map`).
- Manual conflation review of the two REVIEW_REQUIRED pairs.

---

## Decision ask

Approve ghcr publish + pin update for parking and web RCs, then staged import/publish per §5. Until then: **no production deploy, import, or publication.**
