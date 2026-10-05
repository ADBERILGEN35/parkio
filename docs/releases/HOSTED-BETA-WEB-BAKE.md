# Hosted-beta web bake flags and Explore recurrence guard

Live web images bake `VITE_*` at **image build time**. Compose runtime env cannot
flip `VITE_PUBLIC_EXPLORE_ENABLED` or `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` after
the fact. Dockerfile / compose defaults remain `false` (fail-closed if bake args
are omitted).

## Canonical live profile

| File | Role |
| --- | --- |
| `docker/web-hosted-beta.release-bake.env` | Intended live bake (Explore ON, municipal ON; WEB-MUNI-01A hosted-beta leave-on) |
| `docker/docker-compose.web-release-pin.yml` | Source-controlled digest pin for `web` (`services.web.image` only) |
| `docker/compose.production.files` | Includes the web pin **last** (overlay precedence) |
| `docker/web-hosted-beta.municipal-on.bake.env` | Same municipal-on profile for explicit rebuilds; not a future flip away from the live bake file |

Live pin digest is whatever appears on the effective `services.web.image` line.
Update that single line for a reviewed digest change; comments are ignored by the guard.

## Release build consumption

`.github/workflows/release.yml` loads `docker/web-hosted-beta.release-bake.env` for
web build-args (including `VITE_PUBLIC_EXPLORE_ENABLED` and
`VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`) and sets:

- `VERIFY_REQUIRE_PUBLIC_EXPLORE=true`
- `VERIFY_REQUIRE_MUNICIPAL` to the **same bake value** as
  `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED`

so the Dockerfile post-build gate checks the **compiled** bundle. A missing or
invalid municipal setting (`true`/`false` only) fails the bake-load step. Local
equivalent:

```bash
VITE_MAPTILER_KEY=... ./scripts/build-web-from-bake.sh docker/web-hosted-beta.release-bake.env
```

`scripts/build-web-from-bake.sh` already sets `VERIFY_REQUIRE_MUNICIPAL=true` when
the sourced bake municipal flag is `true`.

## Candidate image evidence (CL-F30, CX-F11)

`.github/workflows/web-candidate-evidence.yml` builds the web image with release.yml's web build
arguments and bundle gates (synthetic MapTiler key, never pushed), runs the a11y and CX-F11 suites
against it and uploads `provenance.json`. It runs on api commits that change the web inputs, not on
`v*` tags. To tie a release tag to evidence, dispatch the workflow on the tag
(`gh workflow run web-candidate-evidence.yml --ref vX.Y.Z`), or show that `git rev-parse
vX.Y.Z:<path>` equals each id in an evidence run's `source.web_inputs` (`frontend`, the bake file,
`.dockerignore` and `.github/workflows/release.yml`).

## Required bake values (live)

- `VITE_APP_ENV=hosted-beta`
- `VITE_API_BASE_URL=https://api.parkio.dev/api/v1`
- `VITE_PUBLIC_EXPLORE_ENABLED=true`
- `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=true`
- Server: `PARKIO_PUBLIC_EXPLORE_ENABLED=true` with `izum` source family

`/map` and `/facilities/:id` remain authenticated; anonymous Explore stays on
`/explore` with AuthGate for full detail (intended).

## Recurrence guard

`frontend/apps/web/scripts/verify-bundle-env.mjs` fails when a **hosted-beta**
bundle targets `https://api.parkio.dev/api/v1` but bakes public Explore off.
That is the P01F failure mode (API-base rebuild that omitted Explore). The same
verifier also fails that live-API hosted-beta bundle when municipal discovery is
off (omitting the flag defaults to false and silently drops `/map`).

Pin coherence uses effective `services.web.image` parsing (not substring/grep):

```bash
node --test frontend/apps/web/scripts/assert-hosted-beta-web-bake.test.mjs
./scripts/guard-hosted-beta-web-bake.sh
```

Explicit checks:

```bash
node frontend/apps/web/scripts/verify-bundle-env.mjs \
  --dist dist --app-env hosted-beta \
  --require-public-explore true \
  --require-municipal true
```

PROD-MUNI-01 continues to reject municipal discovery when `VITE_APP_ENV=production`.
Dockerfile/compose defaults stay `false`.
