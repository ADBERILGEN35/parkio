# Hosted-beta web bake flags and Explore recurrence guard

Live web images bake `VITE_*` at **image build time**. Compose runtime env cannot
flip `VITE_PUBLIC_EXPLORE_ENABLED` or `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` after
the fact. Dockerfile / compose defaults remain `false` (fail-closed).

## Canonical live profile

| File | Role |
| --- | --- |
| `docker/web-hosted-beta.release-bake.env` | Intended live bake (Explore ON, municipal OFF) |
| `docker/docker-compose.web-release-pin.yml` | Source-controlled digest pin for `web` (`services.web.image` only) |
| `docker/compose.production.files` | Includes the web pin **last** (overlay precedence) |
| `docker/web-hosted-beta.municipal-on.bake.env` | Prepared next bake (Explore ON + municipal ON) — not the live pin |

Live pin digest is whatever appears on the effective `services.web.image` line.
Update that single line for a reviewed digest change; comments are ignored by the guard.

## Release build consumption

`.github/workflows/release.yml` loads `docker/web-hosted-beta.release-bake.env` for
web build-args (including `VITE_PUBLIC_EXPLORE_ENABLED`) and sets
`VERIFY_REQUIRE_PUBLIC_EXPLORE=true` so the Dockerfile post-build gate checks the
**compiled** bundle. Local equivalent:

```bash
VITE_MAPTILER_KEY=... ./scripts/build-web-from-bake.sh docker/web-hosted-beta.release-bake.env
```

## Required bake values (live)

- `VITE_APP_ENV=hosted-beta`
- `VITE_API_BASE_URL=https://api.parkio.dev/api/v1`
- `VITE_PUBLIC_EXPLORE_ENABLED=true`
- `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=false` (until municipal-on bake is reviewed)
- Server: `PARKIO_PUBLIC_EXPLORE_ENABLED=true` with `izum` source family

## Recurrence guard

`frontend/apps/web/scripts/verify-bundle-env.mjs` fails when a **hosted-beta**
bundle targets `https://api.parkio.dev/api/v1` but bakes public Explore off.
That is the P01F failure mode (API-base rebuild that omitted Explore).

Pin coherence uses effective `services.web.image` parsing (not substring/grep):

```bash
node --test frontend/apps/web/scripts/assert-hosted-beta-web-bake.test.mjs
./scripts/guard-hosted-beta-web-bake.sh
```

Explicit checks:

```bash
node frontend/apps/web/scripts/verify-bundle-env.mjs \
  --dist dist --app-env hosted-beta \
  --require-public-explore true
```

## Municipal `/map` enablement (prepared, not deployed)

Flipping the example env alone is not enough. Use
`docker/web-hosted-beta.municipal-on.bake.env`, rebuild web, verify with
`--require-municipal true --require-public-explore true`, then update the web
pin after acceptance. `/map` and `/facilities/:id` remain authenticated;
anonymous Explore stays on `/explore` with AuthGate for full detail (intended).

PROD-MUNI-01 continues to reject municipal discovery when `VITE_APP_ENV=production`.
