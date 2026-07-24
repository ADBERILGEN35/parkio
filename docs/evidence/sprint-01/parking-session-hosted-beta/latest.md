# ParkingSession hosted-beta smoke evidence (S1-P0-12 / R27)

- Run ID: `ps-s1p012-20260724T212710Z`
- Profile: `azure-hosted-beta`
- Host: `api.parkio.dev`
- Started (UTC): 2026-07-24T18:27:10.199Z
- Ended (UTC): 2026-07-24T18:27:12.415Z
- Total checks: 28
- Counts: PASS=17 FAIL=7 NOT_EXECUTED=2 NOT_OBSERVABLE=2
- Cleanup: PASS — userA active http 204; userA delete ddd3a59f... http 500; userA delete 7f85cf92... http 500; userA delete 392accf3... http 500; userA delete 74f47b5e... http 500; userA bulk-history http 500
- Exit code: 1

## Checks

| ID | Name | Status | HTTP | Detail |
|----|------|--------|------|--------|
| PS-HB-01 | target safety validation | PASS |  | profile=azure-hosted-beta; host=api.parkio.dev |
| PS-HB-02 | gateway health | PASS | 200 |  |
| PS-HB-03 | authenticate User A | PASS | 200 | alias=userA |
| PS-HB-04 | initial cleanup/reconciliation | PASS |  | no active residue |
| PS-HB-04b | deletion API capability probe | FAIL | 500 | expected 204 for foreign UUID, got 500 (deployed image likely lacks S1-P0-07) |
| PS-HB-05 | start session | PASS | 201 | session=ddd3a59f... |
| PS-HB-06 | start idempotent replay | PASS | 201 | same session id |
| PS-HB-07 | active read | PASS | 200 |  |
| PS-HB-08 | second-start conflict | PASS | 409 | ACTIVE_PARKING_SESSION_EXISTS |
| PS-HB-09 | complete | PASS | 200 |  |
| PS-HB-10 | active absent after complete | PASS | 204 |  |
| PS-HB-11 | completed history | PASS | 200 | pageSize=2 |
| PS-HB-12 | single delete | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-13 | repeated single delete | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-14 | start second session | PASS | 201 | session=7f85cf92... |
| PS-HB-15 | cancel | PASS | 200 |  |
| PS-HB-16 | cancelled history | PASS | 200 |  |
| PS-HB-17 | bulk delete | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-18 | repeated bulk delete | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-19 | active-preservation setup | PASS | 201 | active=74f47b5e... |
| PS-HB-20 | active single-delete conflict | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-21 | delete-all preserves ACTIVE | FAIL |  | skipped - deletion API not healthy on deployed revision |
| PS-HB-22 | cleanup active | PASS | 200 |  |
| PS-HB-23 | owner isolation | NOT_EXECUTED |  | User B credentials not provided |
| PS-HB-23b | cursor pagination (page size 1) | NOT_EXECUTED |  | requires healthy deletion for cleanup |
| PS-HB-24 | outbox/relay evidence | NOT_OBSERVABLE |  | no public outbox verification endpoint; covered by parking-service unit/IT |
| PS-HB-25 | analytics ingestion evidence | NOT_OBSERVABLE |  | no public analytics query for lifecycle facts; covered by analytics-service IT |
| PS-HB-26 | final cleanup verification | PASS | 204 | userA active http 204; userA delete ddd3a59f... http 500; userA delete 7f85cf92... http 500; userA delete 392accf3... http 500; userA delete 74f47b5e... http 500; userA bulk-history http 500 |

## Invocation (secrets redacted)

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_SMOKE_CONFIRM_TARGET=beta \
PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE \
PARKIO_SMOKE_USER_A_EMAIL=<disposable-a> \
PARKIO_SMOKE_USER_A_PASSWORD=<secret> \
./scripts/smoke-parking-session-hosted-beta.sh
```

## Limitations

- Mobile UI was not part of this API smoke.
- Outbox/analytics layers are NOT_OBSERVABLE without private ops access.
- Coordinates, tokens, and passwords are redacted from evidence.
