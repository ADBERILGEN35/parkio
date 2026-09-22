# Hatay Katlı Pazaryeri + Konak Katlı Otopark — recovery plan

**Status:** reversible prep only — **no production data mutation** until the coverage-expansion release step.  
**Depends on:** PR #70 (`fix/izum-incomplete-snapshot-no-mass-deactivate`) merged and gateway/parking image rolled with the IZUM incomplete-snapshot guard.

## Identities (last known production)

| Facility | External ID (IZUM `ufid`) | Facility UUID (prod link) | Notes |
| --- | --- | --- | --- |
| Hatay Katlı Pazaryeri | `CPS-TR-IZM-B2-01` | `81d999ab-…` (see HATAY-DISAPPEARANCE evidence) | Soft-deactivated by incomplete SUCCESS 8→6 |
| Konak Katlı Otopark | `CPS-TR-IZM-M1-01` | `06558cfb-…` | Same incomplete-snapshot event |

Exact UUID/link rows: re-query after #70 deploy — do not trust stale copies if ops re-imported.

## Why they disappeared

IZUM `AUTHORITATIVE_FULL_SET` treated a **smaller SUCCESS** payload as a complete inventory and soft-deactivated missing `ufid`s. Facility existence was coupled to “present in this occupancy poll.”

PR #70 separates that for IZUM: `accepted > 0 && accepted < previouslyActive && authoritativeValid <= accepted` → **skip mass deactivate**. ANPARK/ISPARK intentional shrinks unchanged.

## Recovery steps (post-#70, still gated)

### A. Prefer natural reactivation (no SQL)

1. Confirm parking-service build includes #70 guard.
2. Confirm `parkio.municipal.izum.enabled=true` only in the **intended** env (prod or isolated staging).
3. Trigger one IZUM sync (scheduler or admin sync).
4. If both `ufid`s appear in the live feed → upsert reactivates inactive links (`recordsReactivated`).
5. Verify:
   - `municipal_facility_source_links.active = true` for both external ids
   - `municipal_parking_facilities.active = true`
   - Authenticated `GET /api/v1/parking/facilities/nearby` near Hatay/Konak returns stable facility ids
   - Occupancy: `LIVE`/`AGING` only when snapshot fresh; else facility still listed with `UNAVAILABLE`/`STALE` (spaces not shown as current)

### B. If feed still omits them (incomplete IZUM)

Do **not** invent locations. Options in order:

1. **Wait / re-poll** — IZUM has oscillated 5–8 rows; another complete poll may restore them.
2. **Historical inventory backfill** — if İZELMAN CSV / prior Azure parity still lists the same operators at matching coords, import İZELMAN in **isolated** DB and **review-link** (do not auto-merge) to the inactive IZUM facility row; publication remains gated.
3. **Manual ops reactivate** (last resort, audited):
   ```sql
   -- EXAMPLE ONLY — run against isolated clone first; never invent lat/lng
   UPDATE municipal_facility_source_links l
   SET active = true, updated_at = now()
   FROM municipal_data_sources d
   WHERE d.id = l.source_id
     AND d.source_key = 'izmir-izum-otoparklar'
     AND l.external_id IN ('CPS-TR-IZM-B2-01', 'CPS-TR-IZM-M1-01');
   UPDATE municipal_parking_facilities f
   SET active = true, updated_at = now()
   FROM municipal_facility_source_links l
   WHERE l.facility_id = f.id
     AND l.external_id IN ('CPS-TR-IZM-B2-01', 'CPS-TR-IZM-M1-01');
   ```
4. After manual reactivate, incomplete IZUM polls must **not** re-hide them (#70). Equal-cardinality ID swaps can still deactivate.

### C. Rollback

- Image rollback to pre-#70 re-enables mass deactivate on shrink — **avoid** until recovery verified.
- Manual SQL: set `active=false` only with written ops approval.

## Acceptance

- [ ] Both facilities `active` in DB after recovery path A or B
- [ ] Nearby + Explore (if IZUM family allowlisted) show markers **without** requiring live free-space
- [ ] Incomplete IZUM SUCCESS smaller than prior active set does **not** deactivate them again
- [ ] Availability copy distinguishes live vs stale vs static/unavailable
