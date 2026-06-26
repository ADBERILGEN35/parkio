# Supply-Chain Security & Release Integrity (P1.6)

How Parkio builds, bills-of-materials, signs (when enabled), traces, and releases its
artifacts. The goal is **artifact trust**: every jar, image and SBOM is traceable to a
commit and a workflow run, reproducible, and verifiable — without long-lived secrets and
without locking the project to any cloud, Kubernetes, Helm, or a secrets manager.

Scope is build/release integrity only. No business logic, APIs, event contracts, or schemas
are affected.

---

## 1. Components at a glance

| Concern | Mechanism | Where |
| --- | --- | --- |
| Backend SBOM | CycloneDX Gradle plugin, per service (runtime classpath) | `buildSrc` convention plugin → `:services:<svc>:cyclonedxBom` |
| Frontend SBOM | Trivy `fs` → CycloneDX (reads `pnpm-lock.yaml`) | `supply-chain.yml`, `release.yml` |
| Image SBOM | Trivy `image` → CycloneDX | `release.yml` |
| Provenance (portable) | `provenance.json` manifest (commit/run/time/version/branch) | `supply-chain.yml`, attached to releases |
| Provenance (cryptographic) | GitHub Artifact Attestations, keyless OIDC — **gated** | `vars.ATTESTATIONS_ENABLED` |
| Image signing | Cosign keyless (OIDC, GHCR) — **gated** | `vars.PUBLISH_IMAGES` |
| Image identity | OCI labels + `IMAGE_VERSION`/`IMAGE_REVISION`/`IMAGE_CREATED` build-args | every `services/*/Dockerfile` |
| Build integrity | Gradle wrapper validation, reproducible archives | `backend-ci.yml`, `release.yml`, convention plugin |
| Dependency provenance | Dependabot (gradle, npm, docker, actions) + Trivy gates | `dependabot.yml`, `security-ci.yml` |

---

## 2. SBOM (Software Bill of Materials)

All SBOMs are **CycloneDX JSON**, one file per surface — no XML/JSON duplication.

- **Backend (Gradle).** The `parkio.spring-service` convention plugin applies
  `org.cyclonedx.bom`. Each service emits `build/reports/sbom/bom.json` of its
  **runtime classpath only** (what actually ships in the bootJar/image), with a stable BOM
  serial number. Run locally: `./gradlew --no-configuration-cache cyclonedxBom`
  (the plugin is not configuration-cache compatible, so this one task opts out; the rest of
  the build keeps the cache). Verified: 149 components for auth-service.
- **Frontend.** `Trivy fs frontend --format cyclonedx` reads `pnpm-lock.yaml`. Trivy is
  used rather than `cyclonedx-npm` because the latter does not understand pnpm workspaces.
- **Container images.** `Trivy image … --format cyclonedx` captures OS packages **and** the
  app layer — the full runtime BOM. Produced in the release workflow only (building all 10
  images on every push would be wasteful).

CI uploads every SBOM as a build artifact (90-day retention) and attaches the release SBOMs
to the draft GitHub Release.

> **Why three tools?** Gradle for the JVM classpath (most accurate for Java), Trivy for
> lockfile- and image-based BOMs (pnpm + OS layers). Each surface has exactly one producer.

---

## 3. Artifact provenance

Every artifact is traceable to **commit SHA · workflow run · build timestamp · version ·
branch** two ways:

1. **Portable manifest (always on).** `supply-chain.yml` writes `provenance.json` and the
   release attaches it. No external dependency — works on any repo, public or private.
2. **Cryptographic attestation (gated).** `actions/attest-build-provenance` (keyless, OIDC)
   produces signed SLSA provenance for the SBOMs and, when images are published, for the
   image digests. Gated behind `vars.ATTESTATIONS_ENABLED` because it needs a public repo or
   GitHub Advanced Security — flip the variable to enable, no code change.

Images additionally self-describe via OCI labels baked at build time:
`org.opencontainers.image.{title,description,source,url,documentation,vendor,version,revision,created}`.
`version`/`revision`/`created` come from CI build-args (never invented); a `licenses` label
is intentionally omitted until a `LICENSE` file exists in the repo.

---

## 4. Image signing readiness (Cosign)

The release workflow contains **keyless** Cosign signing (`cosign sign --yes <image>@<digest>`)
and OIDC-based image attestation. They run only when `vars.PUBLISH_IMAGES == 'true'`, so:

- **Today:** images build and are SBOM-scanned at the release commit, but are not pushed or
  signed — nothing requires GHCR or any secret. Fully portable.
- **To enable signing:** set `PUBLISH_IMAGES=true`. Images push to GHCR and are signed
  keyless via the workflow's OIDC identity (`GITHUB_TOKEN`, `packages: write`,
  `id-token: write`) — still no long-lived secret. Verify with:
  `cosign verify <image> --certificate-identity-regexp '.*' --certificate-oidc-issuer https://token.actions.githubusercontent.com`.

---

## 5. Release process

Triggered by pushing a semver tag `vX.Y.Z` (or `workflow_dispatch` with a `version` input).
`release.yml` runs:

1. **version** — validates and pins the semver version + build timestamp.
2. **verify** — Gradle wrapper validation, `build` (unit), `integrationTest` (Testcontainers).
3. **sbom** — backend + frontend CycloneDX SBOMs, uploaded + (gated) attested.
4. **images** — builds all 10 OCI-labelled images with reproducible `:vX.Y.Z` and
   `:sha-<commit>` tags, generates per-image SBOMs, and (gated) pushes + signs + attests.
5. **release** — `environment: release` (add required reviewers for a hard gate) creates a
   **DRAFT** GitHub Release with all SBOMs + provenance attached.

**Nothing is published automatically.** The draft release is the manual approval gate: a
human reviews the SBOMs/scans and clicks **Publish**. Image publish/signing is a second,
independent gate (`PUBLISH_IMAGES`).

### Release checklist

- [ ] `master` green (Backend CI, Frontend CI, Security CI, Backend integration).
- [ ] Decide version `vX.Y.Z` (semver). Breaking API/event change → major.
- [ ] Tag: `git tag vX.Y.Z && git push origin vX.Y.Z` (or run *Release* via dispatch).
- [ ] Watch `release.yml`: verify + SBOM + image jobs green.
- [ ] Review the **draft** release: SBOM components, Trivy results, provenance manifest.
- [ ] (If publishing images) confirm `PUBLISH_IMAGES=true`, check signatures with `cosign verify`.
- [ ] Click **Publish release**.
- [ ] Record the release in the rollback log (§6).

---

## 6. Rollback strategy

Rollback safety rests on **immutable, content-addressable image tags**:

- Every release image is tagged **both** `:vX.Y.Z` (semver) and `:sha-<commit>` (immutable,
  unique per build). The release flow never moves a bare `:latest`.
- **`latest` policy:** `latest` is a *mutable convenience* set manually (or by a separate,
  post-verification step) only after a release is confirmed healthy — never by the release
  job itself. Deployments should pin a digest or `:vX.Y.Z`, not `:latest`.
- **To roll back:** redeploy the previous `:vX.Y.Z` (or `@sha256:<digest>`) — the artifact is
  immutable and still present in the registry; no rebuild, no guesswork.
- **Database compatibility:** Flyway migrations are forward-only and schema is owned by the
  DB, with `ddl-auto: validate`. Roll image versions back **only** across compatible schema
  versions; a release that adds a non-backward-compatible migration is not image-rollback
  safe on its own — pair with a tested DB restore (see `runtime-sizing.md` §6 and the backup
  restore drill). Prefer expand/contract migrations so image rollback stays safe.
- **Provenance for forensics:** the `:sha-` tag + `provenance.json` + (when enabled)
  attestations tie any deployed image back to its exact commit and build.

> **Recommended improvement:** keep a short, append-only `deploy log` (version, image digest,
> commit, date, who) so "what is running and how do we get back" is answerable in seconds.

---

## 7. GitHub Actions hardening

- All workflows: explicit least-privilege `permissions`, `timeout-minutes`, and
  `concurrency` (added to `backend-ci.yml` in this sprint).
- Elevated scopes (`id-token`, `attestations`, `packages`, `contents: write`) are granted at
  the **job** level only where consumed (SBOM/image/release jobs), never workflow-wide.
- Gradle wrapper validation runs on every backend PR and in the release flow.
- Actions are pinned to major-version tags and **kept current by Dependabot**
  (`github-actions` ecosystem). Pinning to full commit SHAs is the next hardening step — see
  §9; it is deferred because unverifiable SHAs must not be hand-invented.
- The release `concurrency` group uses `cancel-in-progress: false` so a release is never
  interrupted mid-flight.

---

## 8. Verification status (local)

Run on the dev box (WSL); see the sprint report for full output.

- ✅ Backend CycloneDX SBOM generates (auth-service: 149 runtime components, CycloneDX 1.5).
- ✅ `buildSrc` compiles with the CycloneDX plugin; normal `build` unaffected (config cache
  intact — only the explicit `cyclonedxBom` task opts out).
- ✅ All 10 Dockerfiles carry OCI labels + `HEALTHCHECK`; ports/descriptions verified.
- ✅ All workflow YAML valid (`release.yml` 5 jobs, `supply-chain.yml` 3 jobs).
- ⚠️ **Unverified locally (no Docker daemon in this env):** image build with OCI build-args,
  Trivy image SBOM, Cosign signing, GitHub attestations, GHCR push. These are exercised by
  CI on `ubuntu-latest`; run the *Release* workflow once to confirm end-to-end.

---

## 9. Remaining gaps / next steps

- Pin GitHub Actions to commit SHAs (Dependabot can maintain digests).
- Add a `LICENSE` file → populate `org.opencontainers.image.licenses`.
- Enable `ATTESTATIONS_ENABLED` and `PUBLISH_IMAGES` once the repo is on an org/GHAS + GHCR.
- Add a deploy log + (later) automated rollback in the CD layer (out of scope here — no
  Kubernetes/Helm/ArgoCD per constraints).
- Reproducible jars are enabled (stable order, zeroed timestamps); full bit-for-bit image
  reproducibility additionally needs pinned base-image digests (Dependabot-managed).
