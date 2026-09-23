# WEB-BUILDER-PNPM-SECURITY-01 — dependent release package

## Scope and integration

PR #87's reviewed head was `94745782fd7e5b9d7a331f8318d8d1d84bf18b91`.
After a normal merge of PR #90's `api` head into #87, its source head
`59c9e02bf578b69a4ef9b28ea45b5170d59ae512` passed required and web
checks. PR #87 was marked ready and merged normally into `api` as
`fecd338f3e2ef0d8d9936b3694da8ebc1ea40c3d`. No intermediate image was
published or deployed. This draft PR #91 then merged that `api` head normally
(`feeb6a17f6ba947b9e228d0e7d45b1149279c4ef`) and targets `api`.
Its comparison with `api` contains only the web build toolchain follow-up.
PR #90's configuration reconciliation and all production pins are preserved.
PR #91 remains draft and unmerged pending the final release decision.

The reconciled candidate supersedes #87's candidate only if final exact-head
CI, same-database scans, and actual-container acceptance pass. The release
decision also requires explicit review of the residual LOW finding.

## Vulnerability and toolchain delta

PR #87's installed builder contained 0 CRITICAL, 4 HIGH, 2 MEDIUM, 1 LOW;
its nginx runtime had zero findings. All four HIGH records were inside
Corepack's installed `pnpm@10.34.5` distribution, not app dependencies:

| CVE | pnpm bundled path/version | Upstream fixed version |
| --- | --- | --- |
| CVE-2026-14257 | `dist/node_modules/brace-expansion` 2.1.2 | 2.1.3 |
| CVE-2026-69152 | `dist/node_modules/brace-expansion` 2.1.2 | 2.1.4 |
| CVE-2026-69192 | `dist/node_modules/ip-address` 10.2.0 | 10.3.1 |
| CVE-2026-73566 | `dist/node_modules/tar` 7.5.19 | 7.5.21 |

`pnpm@10.34.5` is the latest 10.x release. Inspecting official npm pnpm
release tarballs found that 11.17.0 still bundled tar 7.5.20, while 11.18.0
bundles tar 7.5.22 and no longer bundles the two flagged brace-expansion and
ip-address paths. Thus 11.18.0 is the smallest stable pnpm release verified
to address these exact bundled paths. The installed-image scan, rather than
tarball inspection, is the acceptance authority for all findings.

Changes: pin `pnpm@11.18.0` in the root package manifest and web Dockerfile;
require Node >=22.13 and run Node 22 in applicable CI; move the existing
hoisted linker and virtual-store setting from `.npmrc` into
`pnpm-workspace.yaml`; move the 16 existing override entries from the
package manifest into the same workspace settings file. Explicitly deny
`esbuild`, `msw`, and `unrs-resolver` dependency lifecycle scripts and set
`strictDepBuilds: true`, so unreviewed scripts fail. This preserves the
prior install-script restriction; no dependency scripts are enabled.
`pnpm-lock.yaml` remains byte-identical (lockfile v9): a Windows
`pnpm@11.18.0 install --lockfile-only` completed with no lockfile diff, and
the 11 importers, 1,431 package records, and 1,444 snapshots are unchanged.
The nginx stage and all runtime configuration remain untouched.

The first same-DB CI scan on `a547d76b188509fbdcfb98ee1675d0cafc509899`
reproduced the baseline exactly (0/4/2/1/0) and found 1 CRITICAL,
21 HIGH, 21 MEDIUM, 3 LOW, 1 UNKNOWN in the candidate builder; both runtimes
had zero. The added CRITICAL/HIGH records all came from one Go binary at
`/root/.local/share/pnpm/store/v11/files/e8/fe5565...-exec`, compiled with
Go 1.23.12. Its SHA-512 matched the official `@esbuild/linux-x64@0.25.9`
package binary exactly. That version is a direct **mobile-v2** development
dependency in the shared lockfile; it is not in the web dependency graph.
The initial pnpm 11 web builder installed every lockfile workspace, including
mobile development tools. A first attempt to filter `@parkio/web...` still
installed all locked packages because the migrated hoisted linker setting
applied inside Docker; the scan remained 1/21/21/3/1. The reviewed #87
Dockerfile did not copy `.npmrc`, so its builder used pnpm's default isolated
linker. This follow-up explicitly restores the isolated linker for the web
builder and uses pnpm's frozen filtered install for `@parkio/web...`.
Developer and CI installs retain their former hoisted setting. No package is
removed after installation or before scanning. The isolated filtered install
fetched 523 packages, versus
1,410 under the hoisted all-workspace install. pnpm 11 then tried an implicit
unfiltered reinstall before `pnpm build`, which failed on an absent mobile
peer snapshot. The Dockerfile disables that redundant pre-run reinstall for
this build command only. The preceding explicit frozen install remains
mandatory and unchanged; no script restriction or scan gate is relaxed.

The legacy mobile advisory CI job needed one invocation adjustment under
pnpm 11: `pnpm ... test -- --ci` forwarded the literal `--` to Jest and
treated `--ci` as a test filename. It now invokes `pnpm ... exec jest --ci`.
No mobile source or dependency changed.

## CI acceptance and artifacts

The dedicated GitHub Actions workflow checks out the exact PR source and
performs a clean frozen install. It builds linux/amd64 builder and runtime
images with the production web build profile and synthetic map key. It also
builds the exact #87 baseline on the same runner. Trivy 0.74.0 scans all four
installed images with one downloaded database and no suppressions or
`--ignore-unfixed`; full JSON, DB metadata, image inspect records, build
profile, and all-severity tables are uploaded. The gate requires zero
candidate builder CRITICAL/HIGH and zero candidate runtime findings.

The same candidate runtime image ID is started in Docker for health, HTTP,
SPA/deep-route, assets, permissions, security/cache headers, and the existing
Playwright browser smoke with mocked external APIs. The exact tested runtime
image is uploaded as a compressed Docker image archive with checksum. Source,
builder, runtime, baseline identities, and acceptance results are recorded in
the CI artifact. No real accounts, messages, or provider calls are used.

The first fully accepted candidate was source
`5a73c0130f424a6ab97267da8871d5c640dea49a`, tested in
[Actions run 35859542055](https://github.com/ADBERILGEN35/parkio/actions/runs/35859542055).
Its baseline builder/runtime IDs were
`sha256:ed4a05e9494dd9977d6c9821b3d9354014a7630adc20871527d0595d6433f944`
and `sha256:297aef7b9aca9cab087c22df2c520385b908c1e343f673c70a42e4de2f7be474`.
The candidate builder/runtime IDs were
`sha256:b83c993e9d7cfdfb3de1780c4f8240a7e9f14b927995dc4d75e827e4814c52f2`
and `sha256:5452aeeb04b89927ae06607c197a309c5cb45ab888ae380d3524f0030b5f470c`.
All four original HIGH records were present in the same-DB baseline and
absent from the candidate. The DB was updated
`2026-09-23T07:12:28.724391795Z`.

| Installed stage | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |
| --- | ---: | ---: | ---: | ---: | ---: |
| #87 baseline builder | 0 | 4 | 2 | 1 | 0 |
| #87 baseline runtime | 0 | 0 | 0 | 0 | 0 |
| pnpm 11.18.0 web builder | 0 | 0 | 0 | 1 | 0 |
| pnpm 11.18.0 nginx runtime | 0 | 0 | 0 | 0 | 0 |

The sole residual is build-only `esbuild@0.27.4`,
`GHSA-g7r4-m6w7-qqqr` (LOW), from the existing
`vite@6.4.3>esbuild:0.27.4` override. Trivy lists 0.28.1 as fixed. That
available application build-tool update needs separate Vite compatibility
review; it is neither suppressed nor labelled unfixable. The runtime has no
builder tooling and scans at zero.

The accepted image passed Docker health, HTTP and SPA/deep routes, assets,
permissions, cache/security headers, and the existing mocked browser smoke
against the running container. The uploaded artifact
`web-build-security-01-5a73c0130f424a6ab97267da8871d5c640dea49a`
includes the image archive and full scans. Archive SHA-256:
`a0a5d3ff0a6b3f39b11769e9f1b3886152995c6c1694767c553a50089e8bde5a`;
its manifest config matches runtime ID
`sha256:5452aeeb04b89927ae06607c197a309c5cb45ab888ae380d3524f0030b5f470c`.

The source and acceptance results above predate the `api` reconciliation.
The final exact-head source, artifact checksum, image/config IDs, and verdict
will be recorded in PR #91 after CI finishes. The prior image IDs above are
historical evidence, not exact-image claims for the reconciled head.

## Release and rollback

If final-head CI passes, PR #91 is ready for a separate source-merge and
release decision. Keep it draft and unmerged until that decision. This is one
combined web release: do not publish or deploy PR #87's intermediate image.
The source-configured current web rollback identity is
`ghcr.io/adberilgen35/parkio/web@sha256:32f03b4401d655ae76d465587604a6cc900bafb7938eea4ea718ba21f60316a6`
(`docker/docker-compose.web-release-pin.yml`); live production identity has
not been reverified by this task. A later authorized release should merge #91,
build and publish an immutable web image from the approved combined source,
record its index and linux/amd64 manifest digests, and change only
`services.web.image` in a separate production-pin change. Apply that pin with
`scripts/parkio-prod-compose.sh up -d --no-build --no-deps --force-recreate web`,
then verify web health, routes, assets, security/cache headers, and the intended
flags. Roll back by restoring the verified pre-release web pin and recreating
only `web` with the same command. No publication, pin edit, production access,
or deployment occurs in this PR.

The LOW `esbuild` advisory has an upstream fix but needs a separate narrow
Vite compatibility review. It is disclosed for the release decision; this
package does not silently accept or suppress it. The legacy mobile advisory
subjob's unchanged Expo SDK mismatches are separate from required and web
specific CI and do not authorize unrelated mobile dependency changes here.
