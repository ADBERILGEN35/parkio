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

## 2. Dependency vulnerability scan — 7 × HIGH — **fixed**

All seven were fixable transitive versions in `frontend/pnpm-lock.yaml`. Each is
raised through a narrow `pnpm.overrides` entry in `frontend/package.json`; no
direct dependency ranges were relaxed and no scanner rule was disabled.

| Advisory | Package | Installed | Fixed in | Override applied |
|---|---|---|---|---|
| `GHSA-gcfj-64vw-6mp9` | `axios` | 1.17.0 | 1.18.0 | `^1.18.1` (resolves 1.18.1) |
| `GHSA-r28c-9q8g-f849` | `postcss` | 8.5.15 | 8.5.18 | `^8.5.23` (resolves 8.5.23) |
| `CVE-2026-59869` | `js-yaml` | 4.2.0 | 4.3.0 | `^4.3.0` (resolves 4.3.0) |
| `CVE-2026-13149`, `CVE-2026-14257` | `brace-expansion` | 1.1.15 | 1.1.16 | `brace-expansion@1: ^1.1.16` |
| `CVE-2026-13149`, `CVE-2026-14257` | `brace-expansion` | 5.0.6 | 5.0.8 | `brace-expansion@5: ^5.0.8` |

Post-change the lockfile resolves exactly one version per package, all at or above
the fixed version.

## 3. Accepted, time-bounded, non-blocking findings

These do **not** fail any gate (image scans block on CRITICAL only). They are
recorded here so that "Security CI green" is not mistaken for "zero findings".

### 3a. pgjdbc SCRAM downgrade — HIGH

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

### 3b. Base-image Go binary findings — HIGH

`usr/bin/pebble` in the Ubuntu 26.04 base layer reports five HIGH `golang.org/x/net`
/ `stdlib` CVEs. Parkio does not invoke pebble; remediation is an upstream base-image
refresh. Owner: Parkio platform. Expiry: **2026-08-31**, reviewed with the next base
image bump.

## Verification

The fixes in sections 1 and 2 are source changes, so they are **not** part of
`v1.0.0-rc2`. They ship in the follow-up commit that is tagged `v1.0.0-rc3`;
`v1.0.0-rc2` remains immutable and is not the publication target. Security CI must
be re-run green on `v1.0.0-rc3` before images are published — see
`docs/operations/parking-session-tag-rollout-2026-07-25.md` for the recorded run
IDs.
