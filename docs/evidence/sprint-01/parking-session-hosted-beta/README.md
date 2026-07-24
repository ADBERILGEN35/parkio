# ParkingSession hosted-beta smoke evidence (S1-P0-12 / R27)

## Safety prerequisites

- `PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta` or `hosted-beta`
- `PARKIO_SMOKE_CONFIRM_TARGET=beta`
- `PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE`
- Disposable User A credentials (`PARKIO_SMOKE_USER_A_EMAIL` / `PARKIO_SMOKE_USER_A_PASSWORD`)
- Optional User B for owner isolation
- HTTPS to `api.parkio.dev` for azure profile

## Invocation (secrets as placeholders)

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
PARKIO_SMOKE_CONFIRM_TARGET=beta \
PARKIO_SMOKE_ENVIRONMENT=beta \
PARKIO_SMOKE_DISPOSABLE_ACCOUNT=I_CONFIRM_DISPOSABLE \
PARKIO_SMOKE_USER_A_EMAIL=<disposable-a> \
PARKIO_SMOKE_USER_A_PASSWORD=<secret> \
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