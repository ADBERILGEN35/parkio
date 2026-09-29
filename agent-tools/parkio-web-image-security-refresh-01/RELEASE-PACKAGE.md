# Web image security refresh release package

Status: draft release candidate prepared locally; not published or deployed.

## Scope and identities

- Fresh base: `origin/api` at `9e95fa64c3327c3f96c146453522a0e231497bf0`.
- Candidate runtime source: `748dd950697e2d369b523d49e258b541188f8a8b`.
- Candidate: `parkio/web@sha256:84767078ab08517c636fb276fb45f3a727442e50e7713dc7dc4c3198c3e10e19`, local-only OCI index, `linux/amd64`.
- Candidate platform manifest: `sha256:3fd448b599600fddab049cd19ed996ab5899580b029b8883fba75d27ac36f333`.
- Candidate config: `sha256:31eaa1dd3ade8ecee812e5535ff17e8ad9a156009d68323fd5481ae1384c7f04`.
- Reported production/rollback image: `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be`, `linux/amd64`, source label `21e150e5965e`.
- This evidence package is a later documentation/test artifact. It does not change the candidate runtime source or image.

The candidate has not been pushed to a registry. Its local digest is not a deployable registry identity until a separately authorized publication step records the resulting GHCR digest.

## Narrow remediation

- Runtime base changed from floating `nginx:1.27-alpine` (resolved in the reported image to Nginx `1.27.5` on Alpine `3.21.3`) to explicit supported stable `nginx:1.30.5-alpine3.24`.
- The resolved candidate base is `nginx:1.30.5-alpine3.24@sha256:a5f2157a0302eb0c5e300415effb63a9e70ed1eb9c107283819bf6d149ab607c`, Alpine `3.24.2`.
- The superseded targeted `apk upgrade libcrypto3 libssl3` layer was removed; the refreshed base already contains the current package set.
- `add_header_inherit merge` was added so location-specific cache headers no longer suppress the already-declared CSP, Referrer-Policy, X-Content-Type-Options, X-Frame-Options, and Permissions-Policy headers.
- Build base remains `node:22-bookworm-slim`; no SPA package, workspace lockfile, shared workflow, runtime server, or product dependency changed.

Compatibility basis:

- Nginx publishes `1.30.5` as the current stable release and its official image catalog supplies `1.30.5-alpine3.24`.
- Nginx supports Alpine `3.24`, and Alpine lists the `3.24` branch as supported until `2028-06-01`.
- Nginx documents `add_header_inherit merge` from `1.29.3`; it preserves parent headers alongside location-level cache headers.

Official references:

- https://nginx.org/en/download.html
- https://github.com/docker-library/official-images/blob/master/library/nginx
- https://nginx.org/en/linux_packages.html
- https://alpinelinux.org/releases/
- https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header_inherit

## Preserved release inputs

The candidate was built through `scripts/build-web-from-bake.sh` using the unchanged canonical `docker/web-hosted-beta.release-bake.env`. The current public MapTiler value was reused from the reported production bundle without printing or storing it. Compiled-bundle verification recorded:

| Input | Candidate value |
| --- | --- |
| `VITE_APP_ENV` | `hosted-beta` |
| `VITE_API_BASE_URL` | `https://api.parkio.dev/api/v1` |
| `VITE_PUBLIC_EXPLORE_ENABLED` | `true` |
| `VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED` | `true` |
| `VITE_SMART_RETURN_ENABLED` | `true` |
| `VITE_SMART_PARKING_ASSISTANT_ENABLED` | `false` |
| `VITE_WAITLIST_INTAKE_MODE` | `api` |
| `VITE_MAPTILER_STYLE` | `streets-v2` |
| `VITE_FRONTEND_ERROR_REPORTING` | `disabled` |
| `VITE_REGISTRATION_MODE` | `closed` |
| `VITE_MAPTILER_KEY` | present; value not recorded |

The canonical server-side Explore settings remain `PARKIO_PUBLIC_EXPLORE_ENABLED=true` and `PARKIO_PUBLIC_EXPLORE_ALLOWED_SOURCE_FAMILIES=izum`. No Compose file or production pin was changed.

## Scan evidence

Scanner: Trivy `0.74.0`; vulnerability DB v2 updated `2026-09-22T02:00:05.774028462Z`, downloaded `2026-09-22T07:30:26.479615916Z`. Both shipped-image scans used `--skip-db-update --scanners vuln --list-all-pkgs` without `--ignore-unfixed`, thereby using the same DB snapshot. Baseline scan began `2026-09-22T18:10:16Z`; candidate scan began `2026-09-22T18:25:29Z`.

| Shipped image | Critical | High | Medium | Low | Fixed-version findings | Unfixed findings |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Reported production digest | 0 | 23 | 40 | 10 | 73 | 0 |
| Candidate | 0 | 0 | 0 | 0 | 0 | 0 |

No new candidate runtime finding was reported. This is an exact-image result under the recorded DB snapshot, not a claim that future databases cannot report findings.

The 23 baseline HIGH records are all Alpine runtime findings with fixes available:

| Package | Installed | HIGH CVEs |
| --- | --- | --- |
| `c-ares` | `1.34.5-r0` | CVE-2026-33630 |
| `libexpat` | `2.7.0-r0` | CVE-2025-59375, CVE-2026-25210, CVE-2026-45186, CVE-2026-66046, CVE-2026-76956, CVE-2026-76957 |
| `libpng` | `1.6.47-r0` | CVE-2025-64720, CVE-2025-65018, CVE-2025-66293, CVE-2026-22695, CVE-2026-22801, CVE-2026-25646 |
| `libxml2` | `2.13.4-r5` | CVE-2025-32414, CVE-2025-32415, CVE-2025-49794, CVE-2025-49795, CVE-2025-49796, CVE-2026-6732 |
| `musl`, `musl-utils` | `1.2.5-r9` | CVE-2026-40200 (one record per package) |
| `nghttp2-libs` | `1.64.0-r0` | CVE-2026-27135 |
| `zlib` | `1.3.1-r2` | CVE-2026-22184 |

The complete fixed versions and all MEDIUM/LOW CVEs are retained in `20260922T181016Z/baseline-trivy.json`; the candidate result is in `20260922T181016Z/candidate-trivy.json`.

The discarded build-stage base was scanned separately at `2026-09-22T18:28:12Z`: `5 CRITICAL / 62 HIGH / 99 MEDIUM / 73 LOW / 1 UNKNOWN`. Of these, `1/10/7/1/0` respectively have a Trivy fixed version; the remainder are reported unfixed or deferred. These packages exist in the Node/Debian build environment and are not copied into the Nginx runtime image. This remains build-chain exposure and prevents describing the overall build environment as vulnerability-free; it does not change the shipped-image totals. Full evidence is in `20260922T181016Z/build-stage-base-trivy.json`.

Evidence SHA-256:

- `baseline-trivy.json`: `3756160d0ad1a02331813a34c6e37eb64e02100394ce11afc3eca3843d7d3564`
- `candidate-trivy.json`: `fd846e8546294eaf1ce63b3b7eba58ef394f59db1e23c58a94ff614b21afad85`
- `build-stage-base-trivy.json`: `6c929d5c3011219832916b23fd17b3216895052fde7acaa2b7529ef1d7375bc9`

## Acceptance

- Canonical build: PASS; in-build and extracted-bundle guards require hosted-beta, Explore ON, municipal discovery ON, and non-empty public configuration.
- PR #68/#76 focused Vitest: 4 files, 32 tests PASS (`VerifyEmailPage`, `pendingProfile`, `RegisterPage`, `AccountPreparingPage`).
- Explore/roadside/municipal/smart-return focused Vitest: 4 files, 108 tests PASS. Existing React `act(...)` and unmatched-MSW warnings remain warnings, not failures.
- Release-gate Node tests: 30/30 PASS.
- Existing actual-image Chromium smoke: PASS; static surface, compiled config, login mount, and zero page errors.
- Expanded actual-image acceptance: 36/36 PASS. `/login`, `/explore`, `/map`, and `/verify-email?token=synthetic` return the SPA and mount with visible text and no browser page errors. Production API calls were mocked and all other external browser origins were blocked.
- JS and CSS assets return the expected MIME types and immutable one-year cache policy; HTML remains `no-cache`.
- Security headers are present on HTML and location-specific responses after merge inheritance.
- Docker health reaches `healthy`; static paths are root-owned `0755/0644`. The current privilege model is preserved: root Nginx master, non-root UID 101 workers.
- No production account, profile mutation, email, registry push, deployment, pin change, or service restart occurred.

## Proposed rollout and rollback (not executed)

Rollout requires separate authorization:

1. Publish the exact candidate source as a `linux/amd64` GHCR image and record its immutable registry digest; rescan that digest with the release-time DB.
2. Change only the web release pin in a separately reviewed deployment change. Do not touch gateway, auth, parking, Slack, shared settings, or New Relic.
3. Pull the digest and recreate only the web container with `--no-deps`; do not restart unrelated applications.
4. Verify health, the four deep links, static MIME/cache rules, compiled flags, and security headers at the public edge.

Rollback requires only restoring the web pin to `ghcr.io/adberilgen35/parkio/web@sha256:daba786490be9b6015572fbc2a5ba7efd72a4bcdad860372792c0f12b31b59be` and recreating only the web container with `--no-deps`. No database or data rollback is involved.

## Remaining decision points

- The candidate is local-only; immutable GHCR publication and registry-digest verification remain authorization-gated.
- The discarded build stage has substantial scanner findings, mostly without fixes in the current supported Node 22 Bookworm image. A build-image hardening effort can be tracked separately; changing the SPA toolchain or shared lockfile was intentionally excluded here.
- Production rollout, pin change, merge, and deployment remain unperformed.
