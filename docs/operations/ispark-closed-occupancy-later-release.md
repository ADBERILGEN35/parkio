# Later release note -- ISPARK closed-occupancy publication

This note is for a **later, separately authorized** `parking-service` release. The source
correction and draft PR do **not** authorize an image publish, a compose pin, a host
deploy, a production sync trigger, or any production setting change.

#104 remains HOLD. #109 remains unchanged and registry-blocked.

## Affected service and deployment scope

| Item | Scope |
|---|---|
| Service | `parking-service` only |
| Public path | `GET /api/v1/public/explore/facilities` |
| Authenticated path | `GET /api/v1/parking/facilities/nearby` and `GET /api/v1/parking/facilities/{id}` |
| Ingest / scheduler | Unchanged. Snapshots still store list emptyCapacity. |
| IZUM, OSM, source flags, registration | Unchanged |
| Web / mobile | Unchanged. ISPARK stale-as-static UI remains a separate follow-up. |

Query-time policy reads already-stored `municipal_facility_source_links.source_metadata_json`.
A re-sync is not required for existing closed rows after the new `parking-service` revision
is running.

## Establish the candidate image source revision

Do this only after an authorized image publish of this change. Do not treat the current
production image as this PR.

1. Record the merge commit on `api` (or the draft-PR head if accepting a pre-merge candidate).
2. Inspect the published image labels with `docker image inspect` and require
   `org.opencontainers.image.revision` to equal the accepted source SHA. If the label is
   missing, do not infer the revision from the tag name.
3. Confirm the hosted-beta / production compose pin for `parking-service` names that digest
   (`ghcr.io/adberilgen35/parkio/parking-service@sha256:...`).
4. After a later authorized recreate, confirm the running container image ID matches the pin
   (`docker inspect` on `parking-service`). StartedAt of other services must be unchanged
   unless that recreate was separately authorized.

## Read-only post-deploy acceptance

Use public Explore and, if an authenticated municipal session is available, the facility-by-id
path. Do not trigger `IsparkMunicipalSyncJob` or change `PARKIO_MUNICIPAL_ISPARK_*`.

Closed examples confirmed in the 2026-09-25 read-only investigation (IBB isOpen=0, public
Explore then published LIVE spaces):

| Facility | Parkio id | Investigation occupancy (before fix) |
|---|---|---|
| Avcilar Ido | `81279bd3-5c60-42a1-81bc-8255e22a1a48` | 263/270 LIVE |
| Ogretmen Evi | `d1b866b1-fe41-4ac3-b91b-601a07b57b2a` | 41/80 LIVE |

Public Explore request:

`GET /api/v1/public/explore/facilities?lat=40.9712&lng=28.7185&radiusMeters=5000&limit=6`

Pass:

- Both ids remain in `facilities` (still discoverable; not deactivated).
- `availabilityFreshness` is `UNAVAILABLE`.
- `availableSpaces` is JSON null (no published count).
- `capacityTotal` may remain the inventory figure.

Open control (must still be isOpen=1 on the current IBB list at acceptance time; do not
infer from workHours):

`GET /api/v1/public/explore/facilities?lat=41.037&lng=28.985&radiusMeters=5000&limit=6`

Pass: at least one ISPARK row with explicit open status keeps `LIVE` or `AGING` and a
numeric `availableSpaces`, including `0` when the lot is full.

Authenticated check for each closed id: `freshness=UNAVAILABLE`, `availableSpaces` null,
`occupiedSpaces` null. Public and authenticated views must not disagree on a published
count.

Optional read-only corroboration: IBB `GET https://api.ibb.gov.tr/ispark/Park` still shows
`isOpen: 0` for those two names. Do not write Parkio state from that call.