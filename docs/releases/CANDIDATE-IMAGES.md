# Candidate images from a pinned api revision

Owner decision 2026-10-07, item 3: new candidates are built from a pinned api revision in CI and
accepted in isolation; obsolete locally built candidates are not published; publication is a
separate approval. This page describes the two workflows that implement that and what each step
proves. Pinning, deploying and rolling back stay separate steps with their own authorization
(`docs/operations/hosted-beta-deploy.md`, the release pin files under `docker/`).

## 1. Build and accept: `Candidate images` (`.github/workflows/candidate-images.yml`)

Dispatch on `api` with `source_sha` = the full commit id to build (it must be reachable from
`origin/api`; anything else is refused), `services` = `all` (default) or a subset, `acceptance` =
true (default; needs `all`). Every job checks out the workflow's own revision as the workspace root
(helper scripts, acceptance stack, composite action) and the source at `source_sha` under
`source/`, which is also the Docker build context, so an older api commit that predates the
helpers can still be built and no tracked path of the source is touched. On pull requests that touch these files only the
helper script tests run (`scripts/ci/test-candidate-scripts.sh`); the other jobs are allowed skips
in `.github/ci-gate-policy.json`.

| Job | What it does | What it proves |
|---|---|---|
| Resolve | checks the id, its ancestry in `api`, selects services | the candidate is a pinned api revision |
| Build (one job per service, one for web) | checks out the exact commit, verifies a clean tree, and runs the composite action `.github/actions/candidate-image-build`: the pinned `docker/setup-buildx-action` and `docker/build-push-action` release.yml uses, the same Dockerfile and build arguments (`IMAGE_VERSION=candidate-<sha12>`, `IMAGE_REVISION`, `IMAGE_CREATED`; for web the release bake arguments from `scripts/ci/candidate-web-build-args.sh` and the `WEB_MAPTILER_KEY` secret, which lives on the `release` environment, so the `Candidate web image` job runs on that environment), `push: false`, `load: true`; records `docker image inspect`; scans with Security CI's container policy verbatim (Trivy 0.64.1, the source revision's `.trivyignore.yaml`, fixed-only report at HIGH/CRITICAL, gate 1 = CRITICAL fixed over every package type, gate 2 = HIGH/CRITICAL fixed over application libraries); saves the image as `image.tar.gz` with `SHA256SUMS` | release-equivalent bytes exist, with their identity (image id, labels, platform) and scan on record |
| Full stack acceptance | loads every candidate, checks each loaded id against its record, starts the Compose stack from `docker-compose.images.yml` tags with `pull_policy: never` on the services, proves every container runs the recorded image id (`scripts/ci/compose_image_identity.py`), waits for all healthchecks, runs the runtime-validation checks (readiness per service, gateway JWKS, protected routes 401, direct service call 401 `GATEWAY_AUTH_REQUIRED`, traversal 400; `scripts/ci/candidate-stack-checks.sh`) | the candidate set boots and guards the ingress as the source-built stack does |
| Auth+gateway artifact acceptance | the U03 disposable stack (`scripts/ci/candidate-acceptance/`, reused from the September release package where it passed 22/22): privileged-token revocation inside the configured epoch-cache bound (fixture `PT3S`), revoke-all, fail-closed epoch lookups (null, malformed, mismatch, empty, missing → 503), direct auth admin ingress without or with a wrong gateway secret → 401, single-session revocation semantics | the U03 rollout properties hold on these exact images |
| Web suites | `scripts/web-candidate-evidence.sh` in prebuilt mode against the candidate web image: a11y-web (axe, keyboard walk, TR/EN) and cxf11-acceptance | the web candidate passes the same suites the source-built evidence runs |
| Manifest | `scripts/ci/candidate-manifest.py` collects every identity record, scan summary and acceptance result into `candidate-manifest.json` + `SUMMARY.md`; verdict `accepted-candidate` only when every selected service has a record with a scan summary, every revision label equals `source_sha`, every platform is linux/amd64, every Trivy gate passed and every requested acceptance passed | one document the owner reviews before approving publication |

Artifacts (90 days): `candidate-image-<service>-<sha12>` (image.tar.gz, inspect.json, trivy.json,
trivy.txt, trivy-critical.txt, trivy-library.txt, trivy-summary.json, SHA256SUMS), `candidate-image-<service>-<sha12>-meta`,
`candidate-acceptance-{stack,auth-gateway,web}`, `candidate-manifest-<sha12>`. Nothing is pushed.
The images are kept as bytes rather than rebuilt at publication time because service jars carry
build timestamps: the accepted image and the published image must be the same bytes.

Known limitation at the time of writing: the `Web candidate image evidence` workflow is red on `api`
since the U16 fan-out merge (five a11y tests of the dense car-park scenarios fail only against the
nginx image; Frontend CI's dev-server runs pass), so the web suites job will fail on the current api
until that is fixed; the manifest then reports the web candidate as not accepted. Supply-chain parity
with release.yml (SBOM, attestations, cosign) is not part of the candidate flow; the release workflow
remains the path that produces those.

What the acceptance does not prove: production configuration and data (synthetic env, synthetic
accounts, `PT3S` fixtures), the MapTiler style (third-party hosts are blocked in the suites), and
anything a live acceptance task names (U03/U04 rollout acceptance remains a live, separately
authorized step after publication and pinning).

## 2. Publish: `Candidate publish` (`.github/workflows/candidate-publish.yml`)

Dispatch with `run_id` (the Candidate images run), `source_sha`, `confirm` = `PUBLISH <sha12>`,
`services`. Three independent, owner-controlled gates (they constrain who approves; a collaborator
with write access to the repository could still change the workflows, which is GitHub's model, so
the pin PR's digest review stays the final control):

1. **Environment `candidate-publication`.** Create it once (Settings → Environments) with the owner
   as required reviewer; every run then waits for an explicit approval. Until it exists GitHub
   creates it without protection on first use, so create it before the first dispatch.
2. **Repository variable `PARKIO_CANDIDATE_PUBLISH_APPROVED_SHA`** must equal `source_sha`; the job
   does not start otherwise. Set it when approving; clear it afterwards.
3. **`confirm`** must read `PUBLISH <first 12 hex of source_sha>`.

The job first checks that `run_id` is a completed, successful `workflow_dispatch` of
`candidate-images.yml` on `api` whose revision is on `api` (no other workflow can feed it) and that
the run's manifest records all three acceptance jobs as passed, then downloads the run's manifest
and image artifacts, refuses a manifest whose source, run id or verdict do not match, verifies each `SHA256SUMS` and each artifact's image id against the
manifest, loads, tags `ghcr.io/<repo>/<service>:candidate-<sha12>` and `:sha-<sha>`, pushes, and
verifies the registry: a single linux/amd64 manifest whose config digest equals the image id. It
records `published-digests.json` (pin lines `ghcr.io/<repo>/<service>@sha256:…`, config digest,
platform, revision, scan summary) as `candidate-published-<sha12>`.

## 3. After publication (separate steps)

- Pin PR: image lines only, from `published-digests.json` (auth and web pin files, GMP pins, and a
  new pin file for the six services that still deploy from mutable local tags; U13 1219001778931476).
- Deploy and rollback rehearsal through the production wrapper, then the live acceptance tasks
  (U03 1219001664236540, U04 1219001664327469).

## Relation to the earlier local candidates

The September candidates (auth `e0007c3c…`, gateway `a6900165…`, web `7486e0b8…`) are not
published: they predate #198/#261 (web) and #143/#161/#170 (gateway) and could not pass today's
deploy guards or close CL-F28's live check. Their acceptance method lives on in the auth+gateway
job above.
