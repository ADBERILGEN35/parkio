# WEB-BUILD-IMAGE-SECURITY-01 release package

Status: source candidate prepared in draft PR; local image only. No registry publication, merge, production pin change, deployment, migration, message send, or service restart.

## Change and identities

- Base source: `origin/api` at `bf45d0c7b908f221e1049a6fe533b170f97b94ed` (2026-09-23). Web Dockerfile source commit: `355e15ec0527dec2c54bf841aef2262f16045c5b`.
- Only product source change: `frontend/apps/web/Dockerfile` builder `FROM node:22-bookworm-slim` to `node:22-alpine3.24@sha256:b6f26b36c8ff49624cfdac716b8ea1138d606df02586a77d364bb5536a634f85`. No application manifest, lockfile, build argument, build command, nginx stage, nginx configuration, CI, or production configuration changed.
- Old builder base resolved on 2026-09-23 to `node:22-bookworm-slim@sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9` (Debian 12.15). New base is Alpine 3.24.2. Both had Node `22.23.2`, npm `10.9.8`, and Corepack `0.34.6`; the repository specifies pnpm `9.15.0` and `engines.node >=20`. The lockfile SHA-256 is `6859df67e76f60a3ca7dadd63b5100f7207ccc6c1a4b2f6967e6e7cde593aaf8`.
- The official Node image catalog lists `22-alpine3.24` for `amd64`; Node 22 remains LTS, and Alpine 3.24 has support through 2028-06-01. Node's official image documentation notes that Alpine uses musl, so build compatibility must be checked rather than assumed. We built and ran the exact amd64 candidate with the unchanged Vite toolchain.
- Existing runtime base remains `nginx:1.30.5-alpine3.24@sha256:a5f2157a0302eb0c5e300415effb63a9e70ed1eb9c107283819bf6d149ab607c`.

Official sources: [Node release schedule](https://nodejs.org/en/about/previous-releases), [Node image catalog](https://github.com/docker-library/official-images/blob/master/library/node), [Node image variant guidance](https://github.com/nodejs/docker-node), [Alpine support schedule](https://alpinelinux.org/releases/).

## Scan method and findings

Trivy `0.74.0`, DB v2 updated `2026-09-23T07:12:28.724391795Z`, downloaded `2026-09-23T07:36:07.224909537Z`. All six main scans used `trivy image --skip-db-update --scanners vuln --list-all-pkgs --format json`, without `--ignore-unfixed` or a new suppression. `evidence/*.json.gz` holds the complete raw reports. Trivy calls a record fixable when `FixedVersion` is populated; this does not establish that a fix is compatible with the current lockfile. Severity totals are finding records, so one CVE on multiple package copies is counted multiple times.

| Image/stage | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN | With fixed version | No fixed version |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline Node base | 5 | 62 | 103 | 73 | 1 | 19 | 225 |
| Candidate Node base | 1 | 10 | 7 | 1 | 0 | 19 | 0 |
| Baseline **installed builder** | 7 | 112 | 142 | 76 | 2 | 114 | 225 |
| Candidate **installed builder** | 3 | 60 | 46 | 4 | 1 | 114 | 0 |
| Baseline nginx runtime | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Candidate nginx runtime | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

The builder improvement is entirely in OS packages: Debian contributed 4 CRITICAL, 52 HIGH, 96 MEDIUM, 72 LOW, and 1 UNKNOWN record to the actual baseline builder; the Alpine OS result reported zero. The same DB reports 43 OS HIGH records on `node:22-trixie-slim` (`evidence/trixie-base.json.gz`), so a Debian release switch would leave a larger OS backlog. This comparison is specific to this DB snapshot.

The remaining 3 CRITICAL / 60 HIGH builder records are package specific: Corepack-fetched pnpm 9.15.0 and its bundled dependencies (1 CRITICAL / 29 HIGH), Node's bundled npm dependencies (1 / 10), and the esbuild 0.25.9 Go binary compiled with Go stdlib 1.23.12 (1 / 21). The latter binary's SHA-256 matches the binary in the Vite workspace install and actually executes during build. No HIGH/CRITICAL application JavaScript package result was reported; Trivy did not report a separate Node binary finding. All 114 residual records, including exact packages, CVEs/GHSAs, installed versions, scanner fixed versions, and statuses, are in [RESIDUAL-ALL.csv](RESIDUAL-ALL.csv); [FINDINGS.md](FINDINGS.md) highlights every HIGH/CRITICAL record and every baseline Debian HIGH/CRITICAL record.

Those residual fixes require separate review: upgrading pnpm beyond the pinned 9.15.0 involves package-manager and lockfile compatibility; npm is bundled by the Node image; replacing esbuild's Go binary needs a newer compatible Vite/esbuild resolution and lockfile. Merely deleting npm, pnpm cache, or esbuild after the build would hide the environment used to install and compile dependencies. The candidate retains all three. This PR does not claim a clean builder.

## Local amd64 acceptance

Build used the canonical `docker/web-hosted-beta.release-bake.env` values, the existing Dockerfile arguments, `VERIFY_REQUIRE_PUBLIC_EXPLORE=true`, `VERIFY_REQUIRE_MUNICIPAL=true`, `VITE_REGISTRATION_MODE=closed`, and a synthetic non-production MapTiler key. The key is never printed in this package. Frozen pnpm installation, TypeScript build, Vite build, and pre/post bundle guards passed on both builder images.

- Builder IDs: baseline `sha256:f2e42186824cdc421fa50134e9289ffb774024fe74ba5741a8bf34c984bcf380`; candidate `sha256:abd1f48748628ce9ea15c05508ad2d861e13892414ac8948b35bb1ba3f50c210`.
- Runtime IDs: baseline `sha256:18760557225a4ec48db2f39ab57c0471e608d8f19459bd2367a2f09b4177da08`; candidate `sha256:1711dfb4c9a1321548cdfd2eda313e2a9fbaece64f80cbc9a0ece33d8fa4c49f`. Both are local-only `linux/amd64` OCI indexes, not deployable registry digests.
- `verify-bundle-env.mjs` in the candidate confirmed hosted-beta, a present API base and synthetic map key, Explore ON, and municipal discovery ON. The release bake provides roadside/smart-return ON, assistant OFF, intake `api`; Dockerfile defaults keep registration CLOSED.
- Every file under `/usr/share/nginx/html` has the **same path and SHA-256** between baseline and candidate runtimes; the sorted checksum listings have SHA-256 `688030c31012381a2a45d6012828ab11178a19675ec6d23d6662cc0d6ef4cce2`. This covers compiled routes, locales, admin waitlist presentation, resend/phone handling, and static assets. Only image configuration/labels differ.
- Focused Vitest: 8 files / 79 tests passed with `VITE_REGISTRATION_MODE=OPEN` for registration form unit tests. The production-shaped image itself was compiled with `closed`; running those form tests inside the CLOSED builder instead displays the expected closed registration view (10 assertions then fail). The other 69 passed in that first run. The corrected test invocation passed all 79.
- Build-env and bundle-env release gates: 24/24 passed. The fixture-image smoke test is unsuitable inside the builder because it requires the host Docker daemon. Actual-image acceptance is recorded separately below.

Actual-image local acceptance: the candidate image ran nginx in an isolated `--network none` container. Its healthcheck command (`wget -q -O /dev/null http://127.0.0.1:80/`) passed; `/`, `/login`, `/explore`, `/map`, `/verify-email?token=synthetic`, `/admin/waitlist`, and both `/register?lang=tr|en` returned HTML 200. A JS and CSS asset returned the correct MIME types, immutable cache policy, and all five security headers; HTML retained `no-cache`. Static directory/file modes remained 0755/0644 and root owned; nginx had a root master and non-root `nginx` workers. `/workspace`, Node, and npm were absent from the final image. The exact candidate image files were extracted and served to Chromium through a temporary local static server with production API requests fulfilled by a synthetic 401 and all other external origins blocked. Seven SPA routes mounted with visible text and no page errors.

The existing `actual-image-acceptance.mjs` could not complete because Docker's local `-p 127.0.0.1:18192:80` container start remained in `Created`; Docker inspect/remove calls on that container also hung. A second probe container without a published port also remained `Created`. No production service was involved. The two targeted temporary containers are not running; they need targeted removal after the local Docker daemon recovers. Docker health **status** was not observed, although the image's healthcheck command passed against nginx. The inside-container HTTP and extracted-file Chromium checks cover the candidate behavior without claiming a successful published-port acceptance run.

## Rollout and rollback package (future, not executed)

1. Review/merge the focused source PR and obtain terminal applicable CI on its final head. Rebuild `linux/amd64` with the canonical release profile and the real public MapTiler build value through the existing release workflow. Publish only after separate authorization, record the immutable GHCR digest, and rescan that exact registry image with release-time DB.
2. Record the then-current web runtime digest as rollback. In a separately authorized deployment change, update only the web image pin, recreate only web, and verify health, SPA/deep routes, browser mount, static MIME/cache rules, security headers, and compiled flags at the intended edge. Keep registration, Slack, Alertmanager, New Relic, gateway/auth/parking, and production Compose settings as they are.
3. If acceptance fails, restore that recorded web digest and recreate only web. This source change has no migration or data rollback.

No production SSH or real accounts/messages were used. Publication, merge, and rollout remain separate release decisions.
