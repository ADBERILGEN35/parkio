# Security CI disposition — 2026-07-25 (Parking Session RC)

**Triaged run:** Security CI [`30169660521`](https://github.com/ADBERILGEN35/parkio/actions/runs/30169660521)
(tag `v1.0.0-rc2`, commit `17fa98ac1b4fb3056e0028da0bbf1a9f506f4910`)
**Owner:** Parkio release engineering
**Scope:** the two failing jobs — *Dependency vulnerability scan* and
*Container scan (media-service)*. Secret scan and the other nine container scans
were green.

None of the findings below originate in the Parking Session RC. All are transitive
dependency versions that pre-date the RC; the RC introduced **no new security
findings**. Two of the three groups are fixed outright rather than waived.

## Gate thresholds (for context)

| Job | Trivy severity gate | `--ignore-unfixed` |
|---|---|---|
| Dependency vulnerability scan (`frontend/pnpm-lock.yaml`) | `HIGH,CRITICAL` | yes |
| Container scan (service images) | `CRITICAL` | yes |

## 1. Container scan (media-service) — CRITICAL — **fixed**

| Field | Value |
|---|---|
| Finding | `CVE-2025-14813` — Bouncy Castle GOSTCTR cannot correctly process more than 255 blocks |
| Component | `org.bouncycastle:bcprov-jdk18on` **1.81**, inside `app/app.jar` |
| How it got there | transitive dependency of the MinIO client (`io.minio:minio` 8.6.0), only in `media-service` |
| Classification | **true vulnerability in a dependency** (fixable upstream), *not* reachable from Parkio code: Parkio never uses BC's GOST cipher paths, it only ships BC because MinIO does |
| Disposition | **fixed, not waived** |

Fix: pin the patched release of the same 1.81 line.

- `gradle/libs.versions.toml` — `bouncycastle = "1.81.1"` plus a
  `bouncycastle-bcprov` library entry.
- `services/media-service/build.gradle.kts` — a dependency `constraints` block
  raising `bcprov-jdk18on` to 1.81.1 with a `because(...)` reason. MinIO's own
  version is unchanged.

Trivy lists 1.81.1 as a fixed version for this CVE, so the CRITICAL gate clears
without touching the scanner configuration or suppression files.

## 2. Dependency vulnerability scan - 7 HIGH - **6 fixed, 1 dispositioned**

Six of the seven were fixable transitive versions in `frontend/pnpm-lock.yaml`, each
raised through a narrow `pnpm.overrides` entry in `frontend/package.json`; no direct
dependency ranges were relaxed and no scanner rule was disabled. The seventh
(`CVE-2026-14257` against the 1.x line of `brace-expansion`) has no fix on that line
at all and is dispositioned in section 3a.

| Advisory | Package | Installed | Fixed in | Override applied |
|---|---|---|---|---|
| `GHSA-gcfj-64vw-6mp9` | `axios` | 1.17.0 | 1.18.0 | `^1.18.1` (resolves 1.18.1) |
| `GHSA-r28c-9q8g-f849` | `postcss` | 8.5.15 | 8.5.18 | `^8.5.23` (resolves 8.5.23) |
| `CVE-2026-59869` | `js-yaml` | 4.2.0 | 4.3.0 | `^4.3.0` (resolves 4.3.0) |
| `CVE-2026-13149`, `CVE-2026-14257` | `brace-expansion` | 1.1.15 | 1.1.16 | `brace-expansion@1: ^1.1.16` |
| `CVE-2026-13149`, `CVE-2026-14257` | `brace-expansion` | 5.0.6 | 5.0.8 | `brace-expansion@5: ^5.0.8` |

Post-change the lockfile resolves exactly one version per package. The rescan on
`v1.0.0-rc3` (run `30171255383`) dropped the job from seven findings to the single
`brace-expansion@1.1.16` entry covered by section 3a.

## 3. Accepted, time-bounded, non-blocking findings

None of these fail a gate: image scans block on CRITICAL only, and 3a carries an
expiring .trivyignore.yaml exception. They are
recorded here so that "Security CI green" is not mistaken for "zero findings".

### 3a. brace-expansion 1.x DoS - HIGH - **narrow suppression with expiry**

| Field | Value |
|---|---|
| Finding | `CVE-2026-14257` - denial of service via crafted brace patterns |
| Component | `brace-expansion` **1.1.16** in `frontend/pnpm-lock.yaml` |
| Fixed in | 5.0.8 only - **no fix exists for the 1.x line** (1.1.16 is the newest 1.x release) |
| Why not upgraded | brace-expansion 5.x exports a named `expand`, while its only consumer here, `minimatch@3`, calls the module itself; forcing 5.0.8 into that path would break at runtime |
| Reachability | that `minimatch@3` copy comes only from lint/coverage tooling (eslint 9 internals, `eslint-plugin-import`, `eslint-plugin-react`, `glob@7`, `test-exclude`). It is a devDependency: never bundled into the web app, never present in a container image, and it only expands repo-controlled glob patterns |
| Classification | **accepted pre-existing risk**, unfixable in our tree until upstream moves |
| Owner | Parkio release engineering |
| Expiry | **2026-08-31** (encoded as `expired_at` in `.trivyignore.yaml`, so the suppression fails the gate again once it lapses) |
| Remediation | drop the exception when eslint's transitive `minimatch` reaches >= 10, or when a 1.1.x backport ships |

The suppression is the narrowest form the scanner supports: one CVE ID, scoped to one
file path, with an expiry date. `--severity HIGH,CRITICAL`, `--ignore-unfixed` and every
other rule stay in force, and the dependency job now passes `--ignorefile
.trivyignore.yaml` explicitly so the exception is visible in the workflow log.
### 3b. pgjdbc SCRAM downgrade — HIGH

| Field | Value |
|---|---|
| Finding | `CVE-2026-54291` — man-in-the-middle protection bypass via `SCRAM-SHA-256-PLUS` downgrade |
| Component | `org.postgresql:postgresql` **42.7.11** (Spring Boot BOM-managed), present in all ten service images |
| Fixed in | 42.7.12 |
| Exposure | Parkio services reach Postgres over the internal Docker/compose network; hosted beta does not expose the database, and no client is on an untrusted path between app and database |
| Owner | Parkio platform (backend dependencies) |
| Rationale for deferral | patch is trivial but touches the JDBC driver of every service; bumping it during image publication of an already-tagged RC would invalidate the verification the RC just passed |
| Expiry | **2026-08-31** — bump to ≥ 42.7.12 (via BOM upgrade or a `libs.versions.toml` pin) in the first non-RC change after the hosted beta opens |
| Remediation | tracked as release-engineering follow-up "bump pgjdbc to 42.7.12" |

### 3c. Base-image Go binary findings — HIGH

`usr/bin/pebble` in the Ubuntu 26.04 base layer reports five HIGH `golang.org/x/net`
/ `stdlib` CVEs. Parkio does not invoke pebble; remediation is an upstream base-image
refresh. Owner: Parkio platform. Expiry: **2026-08-31**, reviewed with the next base
image bump.

## Verification

These are source changes, so they are **not** part of `v1.0.0-rc2`. Sections 1 and 2
ship in `v1.0.0-rc3`; the section 3a exception ships in `v1.0.0-rc4`, the publication
target. `v1.0.0-rc2` and `v1.0.0-rc3` both remain immutable. Security CI must be green
on `v1.0.0-rc4` before images are published — see
`docs/operations/parking-session-tag-rollout-2026-07-25.md` for the recorded run
IDs.
