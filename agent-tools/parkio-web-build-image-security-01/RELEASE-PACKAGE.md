# WEB-BUILD-IMAGE-SECURITY-01 release package

Status: focused source candidate in draft PR #87. No merge, registry publication, production pin/configuration change, deployment, migration, message send, or service restart.

## Source and image identities

- Base source: `origin/api` at `bf45d0c7b908f221e1049a6fe533b170f97b94ed` when the worktree was created. Initial builder-base source commit `355e15ec0527dec2c54bf841aef2262f16045c5b`; focused tool/lockfile source commit `5b1f8f3f21b4ab8b206ccddeb69c3d7d5c06f952`. Refresh and reconcile `origin/api` before release.
- Builder base remains `node:22-alpine3.24@sha256:b6f26b36c8ff49624cfdac716b8ea1138d606df02586a77d364bb5536a634f85`, the prior PR improvement. It contains Node `22.23.2`, npm `10.9.8`, Corepack `0.34.6`, Alpine `3.24.2`. The former `node:22-bookworm-slim` resolved to `sha256:48e4b67d85f87bd551df43704e24d252f56cc5f8e9718841aace50f19948f0f9`.
- The builder installs exact npm `11.20.0` globally before dependency installation and invokes Corepack pnpm `10.34.5` for frozen installation and build. The repository-wide `packageManager: pnpm@9.15.0` and application dependency declarations remain unchanged.
- Root `pnpm.overrides` adds only `vite@6.4.3>esbuild: 0.27.4`. The lockfile records Vite's esbuild binary and platform packages; mobile's direct `esbuild@0.25.9` remains pinned. No package-manager migration, broad application update, or CI/Compose change was made. The final lockfile SHA-256 is `0db5805bceb6ea3d55acd31e6c9c1e4af7febbb06f815353cf5389ce82fde47a`.
- Nginx runtime base and stage are unchanged: `nginx:1.30.5-alpine3.24@sha256:a5f2157a0302eb0c5e300415effb63a9e70ed1eb9c107283819bf6d149ab607c`. Final local `linux/amd64` builder image ID: `sha256:e580d56aef0115ad42f405183841109854dc5b6c0a6e65f61b53118b97f44190`; final runtime image ID: `sha256:90353374f61892a870dc7bba39e6c08475cfe96d1fd073c2206729e0c0bc94d9`. These are local OCI index identities, not registry digests.

Official references: [Node release status](https://nodejs.org/en/about/previous-releases), [Node image variants](https://github.com/nodejs/docker-node), [Alpine support](https://alpinelinux.org/releases/), [pnpm support policy](https://github.com/pnpm/pnpm/security), [pnpm parent-scoped overrides](https://pnpm.io/settings/dependency-resolution#overrides), [esbuild release notes](https://github.com/evanw/esbuild/releases).

## Same-database vulnerability comparison

Trivy `0.74.0`, vulnerability DB v2 updated `2026-09-23T07:12:28.724391795Z`, downloaded `2026-09-23T07:36:07.224909537Z`. Every comparable image scan used `trivy image --skip-db-update --scanners vuln --list-all-pkgs --format json`; no `--ignore-unfixed` or new suppression. Raw complete reports are in `evidence/*.json.gz`. A record is scanner-fixable when `FixedVersion` is populated; compatibility is assessed separately. Counts represent finding records, including repeated CVEs on distinct package copies.

| Scope, same DB | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |
| --- | ---: | ---: | ---: | ---: | ---: |
| Old Debian Node base only | 5 | 62 | 103 | 73 | 1 |
| Old installed Debian builder | 7 | 112 | 142 | 76 | 2 |
| Alpine Node base only | 1 | 10 | 7 | 1 | 0 |
| Previous Alpine installed builder | 3 | 60 | 46 | 4 | 1 |
| Final installed builder | **0** | **4** | **2** | **1** | **0** |
| Old / previous / final nginx runtime | 0 | 0 | 0 | 0 | 0 |

The historical `5/62/99/73/1` report was a base-only Node image scan on 2026-09-22, with a different DB snapshot. It had Debian OS `4/52/92/72/1` plus npm `1/10/7/1/0`. The current same-DB rescan of that exact base has four more Debian MEDIUM records (`4/52/96/72/1`) and the same npm records. The installed builder additionally includes Corepack pnpm and the esbuild Go binary, so comparing its totals to the historical base-only total would mix scopes.

The previous Alpine installed builder's 63 CRITICAL/HIGH records were: pnpm 9.15.0 and its bundled dependencies `1/29`, npm 10.9.8 and its bundled dependencies `1/10`, and Vite's esbuild 0.25.9 Go binary `1/21`. The final scan resolves **all three CRITICAL and 56 HIGH** records. Four pnpm bundle HIGH records persist under newer installed versions. `HIGH-CRITICAL-DELTA.csv` classifies each original CRITICAL/HIGH record, with installed version, CVE, scanner fixed version, dependency origin and exact path. `RESIDUAL-ALL.csv` preserves all previous-severity findings; `FINAL-RESIDUAL-ALL.csv` gives every final finding and exact path. No CRITICAL/HIGH web application JavaScript dependency or Node runtime binary finding was reported. The Go binary is a Vite build tool, not an application runtime package.

| Final origin and dependency path | Finding | Installed | Scanner fixed version | Release classification |
| --- | --- | --- | --- | --- |
| Corepack pnpm 10.34.5 → bundled `brace-expansion` | `CVE-2026-14257`, HIGH | 2.1.2 | 2.1.3 (also 3.0.3 / 5.0.8 / 1.1.17) | Blocked by pnpm 10 bundle |
| Same | `CVE-2026-69152`, HIGH | 2.1.2 | 2.1.4 (also 3.0.6 / 5.0.9 / 1.1.18) | Blocked by pnpm 10 bundle |
| Corepack pnpm 10.34.5 → bundled `ip-address` | `CVE-2026-69192`, HIGH; `CVE-2026-54272` and `CVE-2026-69198`, MEDIUM | 10.2.0 | 10.3.1; 10.2.1; 10.2.2 | Blocked by pnpm 10 bundle |
| Corepack pnpm 10.34.5 → bundled `tar` | `CVE-2026-73566`, HIGH | 7.5.19 | 7.5.21 | Blocked by pnpm 10 bundle |
| Vite 6.4.3 → esbuild 0.27.4 | `GHSA-g7r4-m6w7-qqqr`, LOW | 0.27.4 | 0.28.1 | Blocked by Vite target compatibility |

All seven final findings have scanner fixed versions; **none is classified “no fix available.”** pnpm 10.34.5 is the latest published 10.x package at the time of this test, but its own distributed bundle still contains those versions. A pnpm 11.27.1 probe stopped before install: it rejects the repository's `packageManager: pnpm@9.15.0` and ignores `package.json` `pnpm.overrides`. Making pnpm 11 work requires a repository-wide package-manager/configuration migration that this web-only PR does not own. Patching files inside pnpm's distributed bundle is not a supported version update. esbuild 0.27.4 is built with Go 1.25.7; the scanner reports no Go CRITICAL/HIGH finding on the installed binary. An exact Vite-scoped esbuild 0.28.2 probe failed the existing Vite 6 browser target transform (`Transforming destructuring ... is not supported yet`), so the LOW fix is not accepted as compatible. The old mobile esbuild version appears in the workspace lockfile but is not installed in this web builder image. Builder tooling is not copied into final nginx.

## Acceptance on the final candidate

- `linux/amd64` build used `docker/web-hosted-beta.release-bake.env`, existing Dockerfile arguments, `VERIFY_REQUIRE_PUBLIC_EXPLORE=true`, `VERIFY_REQUIRE_MUNICIPAL=true`, registration `closed`, and a synthetic non-production MapTiler key. Frozen pnpm installation, TypeScript build, Vite build and pre/post bundle guards passed. The release profile enables Explore, municipal discovery, roadside and smart return; assistant remains disabled and intake is `api`.
- Focused Vitest on the installed builder: 8 files / 79 tests passed, including registration TR/EN/resend, waitlist admin name fallback, Explore, municipal facility, verification and account preparation. Registration form tests used `VITE_REGISTRATION_MODE=OPEN`; the production-shaped bundle itself was built CLOSED.
- A derived BuildKit runtime stage started nginx and passed localhost HTTP for `/`, `/login`, `/explore`, `/map`, `/admin/waitlist`, and both `/register?lang=tr|en`. It checked JS/CSS MIME types, immutable asset cache, HTML `no-cache`, all five security headers, 0644/0755 permissions, and absence of `/workspace`, Node and npm. This is an ephemeral build container check, not `docker run` health-status acceptance.
- The final runtime's 108 extracted asset files were served locally to Chromium with API calls answered by synthetic 401 and other external origins blocked. Seven SPA/deep routes mounted with visible content and no page errors. esbuild changed compiled asset hashes, so byte identity with the previous builder candidate is not claimed. Nginx configuration and runtime Dockerfile stage are source-identical.
- Local Docker can build and scan, but both verified task-owned temporary containers from the earlier actual-image acceptance attempt remain `Created`: `332199f4d2f216a9c2b81b88a04cb27278847fd04ee57096d9cadd331eda6180` and `4a039b2d1ff23e6241b580296d24698b325846b197798c50591589a40539a189`, each named for this task and referencing the previous candidate runtime tag. Targeted `docker rm -f` via Linux and Windows Docker clients timed out; no shared Docker restart or prune was attempted. Docker health **status**, published-port HTTP, and browser smoke against a container-served final image remain unverified. Extracted-file Chromium and BuildKit nginx checks do not close that gap. Retry targeted removal and actual-container acceptance only when the local daemon permits it.

Applicable terminal CI for the exact final PR head is recorded in [PR #87 checks and body](https://github.com/ADBERILGEN35/parkio/pull/87). Treat the PR head SHA and its terminal check conclusions together; earlier-head results are historical. The release decision remains open if any applicable check is pending or failing.

## Future release and rollback

1. Obtain applicable terminal CI on the final PR head and review the four HIGH builder residuals and unresolved actual-container acceptance gap. Merge only by separate release decision. Rebuild with the real public MapTiler build value in the existing release workflow, publish only after separate authorization, record the immutable registry digest, and rescan that exact digest with the release-time DB.
2. Record the then-current web runtime digest as rollback. In a separately authorized deployment change, update only the web image pin, recreate only web, and verify health, SPA/deep routes, browser mount, MIME/cache/security headers, permissions and compiled flags at the intended edge. Keep registration, Slack, Alertmanager, New Relic, gateway/auth/parking and production Compose settings as they are.
3. If acceptance fails, restore the recorded web digest and recreate only web. This source change has no migration or data rollback.

No production SSH or real accounts/messages were used. Publication, merge and rollout remain separate decisions.
