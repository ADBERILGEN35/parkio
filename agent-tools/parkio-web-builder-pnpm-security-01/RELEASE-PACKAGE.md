# WEB-BUILDER-PNPM-SECURITY-01 — dependent release package

## Scope and integration

This draft PR is based on the reviewed, unmerged PR #87 head
`94745782fd7e5b9d7a331f8318d8d1d84bf18b91`. It changes the web build
toolchain only. PR #87 remains unchanged and draft. Current `origin/api`
includes PR #90 (`5b8cb938cf96bbc7c9e0efddbc50bb86b938f7c9`); its
production pin and reconciliation files have no overlap with this follow-up.
Integrate #87 first, then reconcile this branch with current `api` by the
repository's normal merge workflow and revalidate the resulting source before
merging this follow-up. No merge, image publication, or deployment is authorized.

The candidate in this follow-up supersedes #87's candidate only if its final
exact-head CI, same-database scans, and actual-container acceptance pass. The
release decision also requires review of every residual finding.

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
The previous web Dockerfile installed every lockfile workspace, including
mobile development tools. A first attempt to filter `@parkio/web...` still
installed all locked packages because the migrated hoisted linker setting
applied inside Docker; the scan remained 1/21/21/3/1. The reviewed #87
Dockerfile did not copy `.npmrc`, so its builder used pnpm's default isolated
linker. This follow-up explicitly restores the isolated linker for the web
builder and uses pnpm's frozen filtered install for `@parkio/web...`.
Developer and CI installs retain their former hoisted setting. No package is
removed after installation or before scanning. The next CI scan must verify
that the actual install scope excludes the unrelated binary and introduces
no new findings. The isolated filtered install fetched 523 packages, versus
1,410 under the hoisted all-workspace install. pnpm 11 then tried an implicit
unfiltered reinstall before `pnpm build`, which failed on an absent mobile
peer snapshot. The Dockerfile disables that redundant pre-run reinstall for
this build command only. The preceding explicit frozen install remains
mandatory and unchanged; no script restriction or scan gate is relaxed.

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

**Pending CI:** Add final run URL, source SHA, baseline/candidate full counts,
residual package/CVE/fix status, builder/runtime image IDs, artifact name and
checksum, and terminal applicable check results after the workflow finishes.

## Release and rollback

If CI passes and residual findings are reviewed, merge #87 first, reconcile
this follow-up against current `api` by a normal merge, and rerun exact-head
acceptance. A separately authorized release may then build and publish that
reconciled source and update the web pin. Rollback would restore the prior
reviewed web image pin; production pins are untouched here.
