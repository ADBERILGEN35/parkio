# ParkingSession hosted-beta smoke evidence (S1-P0-12 / R27)

## Safety prerequisites

- `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta` or `hosted-beta`
- `PARKIO_SMOKE_CONFIRM_TARGET=beta`
- `PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE`
- Disposable User A credentials (`PARKIO_SMOKE_USER_A_EMAIL` / `PARKIO_SMOKE_USER_A_PASSWORD`)
- Optional User B credentials (`PARKIO_SMOKE_USER_B_EMAIL` / `PARKIO_SMOKE_USER_B_PASSWORD`)
- HTTPS to `api.parkio.dev` for azure profile

## Hosted request controls

- `PARKIO_SMOKE_REQUEST_DELAY_MS`: delay between logical HTTP requests. Defaults to `150`;
  set to `0` to disable pacing.
- HTTP `429` responses are retried up to three times with fixed backoffs of 250 ms, 500 ms,
  and 1000 ms. Other HTTP statuses are never retried.
- `PARKIO_SMOKE_USER_B_EMAIL` and `PARKIO_SMOKE_USER_B_PASSWORD`: when both are present,
  PS-HB-23 executes owner-isolation checks. Otherwise it remains `NOT_EXECUTED`.

## Invocation (secrets as placeholders)

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_SMOKE_CONFIRM_TARGET=beta \
PARKIO_SMOKE_ENVIRONMENT=beta \
PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE \
PARKIO_SMOKE_USER_A_EMAIL=<disposable-a> \
PARKIO_SMOKE_USER_A_PASSWORD=<secret> \
PARKIO_SMOKE_USER_B_EMAIL=<optional-disposable-b> \
PARKIO_SMOKE_USER_B_PASSWORD=<optional-secret> \
PARKIO_SMOKE_REQUEST_DELAY_MS=150 \
./scripts/smoke-parking-session-hosted-beta.sh
```

Optional hook from general smoke:

```bash
PARKIO_SMOKE_PARKING_SESSION=1 ./scripts/smoke-hosted-beta.sh
```

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | All checks PASS (or only NOT_EXECUTED / NOT_OBSERVABLE) |
| 1 | Assertion / cleanup failure |
| 2 | Safety gate refusal (missing confirm, credentials, wrong host) |

## Unit tests

```bash
node --test scripts/lib/parking-session-smoke-runner.test.cjs
# or
./scripts/test-parking-session-smoke.sh
```

## Evidence files

This directory stores per-run JSON/Markdown plus `latest.json` / `latest.md`.
Coordinates, tokens, and passwords are redacted.