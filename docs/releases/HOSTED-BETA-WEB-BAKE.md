# Hosted-beta web bake flags and Explore recurrence guard

Live web images bake `VITE_*` at **image build time**. Compose runtime env cannot
flip `VITE_PUBLIC_EXPLORE_ENABLED` or `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` after
the fact. Dockerfile / compose defaults remain `false` (fail-closed).

## Canonical live profile

| File | Role |
| --- | --- |
| `docker/web-hosted-beta.release-bake.env` | Intended live bake (Explore ON, municipal OFF) |
| `docker/docker-compose.web-release-pin.yml` | Source-controlled digest pin for `web` |
| `docker/compose.production.files` | Includes the web pin after GMP pins |
| `docker/web-hosted-beta.municipal-on.bake.env` | Prepared next bake (Explore ON + municipal ON) — not deployed |

Live pin (P01F recovery):

`ghcr.io/adberilgen35/parkio/web@sha256:d9999a020376cc89b86a92410ceb784a7290258f4d1c0c0d968a6a78d62c443a`

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

Explicit checks:

```bash
node frontend/apps/web/scripts/verify-bundle-env.mjs \
  --dist dist --app-env hosted-beta \
  --require-public-explore true

./scripts/guard-hosted-beta-web-bake.sh
```

## Municipal `/map` enablement (prepared, not deployed)

Flipping the example env alone is not enough. Use
`docker/web-hosted-beta.municipal-on.bake.env`, rebuild web, verify with
`--require-municipal true --require-public-explore true`, then update the web
pin after acceptance. `/map` and `/facilities/:id` remain authenticated;
anonymous Explore stays on `/explore` with AuthGate for full detail (intended).

PROD-MUNI-01 continues to reject municipal discovery when `VITE_APP_ENV=production`.
