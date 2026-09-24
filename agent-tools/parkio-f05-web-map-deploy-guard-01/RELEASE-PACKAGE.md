# F-05 web map deploy guard — release package

- **Baseline:** `origin/api` = `b5a86ed8d432d0840e94796ee63781ddf2c4420c`.
- **Audit:** finding F-05 in `agent-tools/parkio-comprehensive-audit-20260924/FINDINGS.md`
  (branch `claude/confident-einstein-68vbka`). The audit has no separate F-05 reproduction
  directory, so every claim below was re-verified against current source.
- **Not changed:** #104 and its source drafts. No restore entrypoints, backup scripts or
  DR runbooks were touched. No pins, images, secrets or production systems were touched.
- **Current production:** this change does not claim the running web map is broken.
  The accepted pin `aacf9dc9…` was not pulled or inspected here.

## 1. Reproduced defect (baseline, isolated `git archive` export, index modes kept)

| # | Reproduction | Result |
|---|---|---|
| 1 | `bash scripts/test-guard-web-synthetic-map-deploy.sh` | **5/5 FAIL**, exit 126 "Permission denied". The guard is `100644` and the test runs it by path. The test was not wired into any workflow. |
| 2 | `scripts/parkio-prod-compose.sh up -d --no-build --no-deps web` from a fresh checkout, with docker faked | **exit 126 before any docker call.** The same happens for **every** `up`, including gateway-only `up -d --no-deps gateway-service`, because the guard was keyed on the word `up`, not on web. |
| 3 | The same call with `PARKIO_SKIP_WEB_MAP_GUARD=1` | exit 0, and `compose up` runs with **no check**. This is the switch operators would be pushed to use after #2. |
| 4 | Baseline guard run through `bash`: synthetic key in quoted, `export` or CRLF form, with the pin pointing at a mutable tag | exit 0 (PASS) in all three forms. The guard is text-only: it matches one exact env line and one digest string in the pin file. It never inspects the image that will run. |

Also found in the inventory:
- `deploy-hosted-beta.sh`, `deploy-invite-production.sh` and `rollback-hosted-beta.sh`
  (and `rollback-invite-production.sh`, which execs it) start web through
  `parkio_compose_up`. That function had **no** image check. The deploy scripts build web
  from source and the rollback restarts previous local images.
- The known-bad runtime was published as linux/amd64 manifest `sha256:8d9bfca4…`
  with config ID `sha256:985fd8a7…`. Both are recorded in the history of
  `docker/docker-compose.web-release-pin.yml` (commit `8329f7ff`).

## 2. What the image format supports

- **Build stage:** the web `Dockerfile` sets `VITE_MAPTILER_KEY` only in the build
  stage.
- **Runtime image:** it carries no record of the key. There is no label or env for it,
  and the OCI revision label is only a source SHA.
- **Bundle:** the only evidence of baked config is the Vite bundle under
  `/usr/share/nginx/html`, which inlines `import.meta.env` as an object literal. Every
  image built from the current Dockerfile passed `verify-bundle-env.mjs`, which requires
  that object. So the object is a reliable, already-enforced contract for reading the
  baked config.
- **Host env:** the host `.env` value does **not** determine a prebuilt image's bundle.
  It is only an early warning.

## 3. Fix scope

- **`scripts/guard-web-synthetic-map-deploy.sh`** (rewritten, now `100755`, and always
  run through `bash`). It takes the web image the deploy will actually start, either
  `--image` or `services.web.image` from the rendered Compose model, then:
  1. Blocks the known-bad manifest if it is requested by digest. This happens before any
     docker call.
  2. Resolves the **local** image, and fails closed if it is absent. It never pulls.
     It records the immutable **config ID**, RepoDigests and OS/arch.
  3. Blocks the known-bad **config ID**, and any RepoDigest equal to the known-bad
     manifest. That covers tag aliases, retags and re-wraps.
  4. For a digest reference, requires the local image to actually carry that digest.
  5. Requires the image OS/arch to equal the daemon platform, or `--expected-platform`.
  6. Creates, **but never starts**, a `--network none` container from that exact config
     ID. It copies the web root out, then removes the container.
  7. Runs `scripts/lib/web_bundle_map_config.py`. It rejects any of the following:
     - a missing bundle, no JS or no inlined env object;
     - a missing or empty key, or conflicting keys across chunks;
     - the class `ci-web-build-security-synthetic` (exact, or followed by `-`, `_` or
       `.`);
     - the repository's own exact CI/fixture placeholders (`synthetic-non-production-value`,
       `SECRET_SENTINEL_MAPTILER_PUBLIC_KEY`, `ci-public-map-key`, `fixture-public-map-key`,
       `fixture-public-map-key-never-use-in-production`);
     - any of those values quoted anywhere else in the JS.
  8. Optionally, `--expected-map-key-fingerprint`, or
     `PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT`, requires the baked key's 12-hex SHA-256
     fingerprint to equal the authorized release key's fingerprint (see section 6).
  9. The host env file is still checked as an early signal. Its parser accepts quoted,
     `export`, spaced and CRLF forms, and the value never leaves the Python process.
     A clean env **never** rescues a synthetic image.
  - It prints only statuses, identities and the fingerprint, **never the key**.
    `--evidence-out` writes a JSON record.
- **`scripts/lib/web-map-guard.sh`:** parses Compose arguments. It separates global
  options, including operator `-f` and `--profile`, from the subcommand. The guard runs
  for any `up`, `create` or `run` that can create web. It skips only an explicit
  `--no-deps` service list without `web`.
  - Refuses `--build` and `--pull always|newer|build` when web is targeted, because either
    could swap the image after verification.
  - The break-glass switch now needs the explicit token `I_ACCEPT_UNVERIFIED_WEB_IMAGE`.
    The old value `1` is **refused**.
- **`scripts/parkio-prod-compose.sh`:** renders the merged model with the operator's
  global args and guards it before `exec docker compose`. Gateway-only and read-only
  subcommands are not gated.
- **`scripts/lib/deploy-common.sh`:** `parkio_compose_up` guards the rendered model
  before `up`. This covers `deploy-hosted-beta.sh`, `deploy-invite-production.sh` and
  `rollback-hosted-beta.sh` (plus `rollback-invite-production.sh`). A model without a
  web service is skipped.
- **CI:** `invite-production-deploy.yml`, in the "Build images + secret-safe dry-run
  manifest" job, runs `PARKIO_GUARD_TEST_REQUIRE_DOCKER=1 bash
  scripts/test-guard-web-synthetic-map-deploy.sh`.
- **Mock CI builds are unaffected.** `web-build-image-acceptance.yml` and the invite
  `--dry-run` never call `parkio_compose_up` or the wrapper, so their synthetic keys are
  never guarded. No live provider request exists anywhere in the guard or its tests.

## 4. Tests (`scripts/test-guard-web-synthetic-map-deploy.sh`, 94 assertions locally)

- **Part A** uses a fake `docker` on PATH to run the real guard, the classifier, the
  wrapper and `parkio_compose_up`. Every compose mutation is logged, so "nothing was
  started" is asserted directly. It covers:
  - blocked manifest, config-ID alias, RepoDigest alias and digest mismatch;
  - platform mismatch, the arm64 match and the explicit override;
  - image absent (no pull), unreadable model and missing env file;
  - 10 bad bundle kinds and a valid fixture (key never printed, evidence JSON);
  - mutable tag resolved through the config ID, and a locally built image;
  - 8 host-env parser forms, and a clean env not rescuing a synthetic image;
  - pin file;
  - fingerprint match, mismatch, env var and malformed input;
  - the negative control: a `0644` guard gives 126 by path but runs through `bash`;
  - index modes are `100755`;
  - a fresh `git checkout-index` export runs the wrapper by path;
  - wrapper variants:
    - no gating: gateway-only, `ps`, and a model without web;
    - gated: `up` of all services, `up` without `--no-deps`, `--timeout 30`,
      `--profile`, `create`, `run`, and an operator `-f` overlay;
    - refused: `--build`, `--pull always` and `--pull=always`;
    - fail closed: a Compose config render failure and an image not yet pulled;
    - a synthetic host env;
    - break-glass: the legacy `=1` is refused and the token works;
  - `parkio_compose_up` blocks under `set -e` with nothing started. A local image, no web,
    and a known-bad rollback target are also covered.
  - A static check that the three deploy/rollback scripts start containers only through
    `parkio_compose_up`.
- **Part B** uses the real docker daemon. It builds `FROM scratch` fixture images and
  checks: valid passes, synthetic is blocked, a retagged synthetic image is blocked by
  content, a missing web root is blocked, an absent image is blocked, and no inspection
  containers are left behind.
- **Negative controls**, run in scratch copies of this branch:

  | Mutation | Failing assertions |
  |---|---|
  | Baseline guard | 43/75 |
  | `parkio_compose_up` guard removed | 3/75 |
  | Baseline wrapper | 21/75 |
  | Classifier that accepts everything | 19/75 |
  | Value-option parsing removed | 1/75 |

- **Existing deploy tests** were compared baseline vs branch locally with no regressions.
  Two failures are environmental and identical on both: the NTFS file-mode negative
  control, and `jq` missing locally.

## 5. What the guard proves / cannot prove

**Proves**, for the image the deploy will start, identified by config ID and platform:
- It is not the known-bad runtime, by manifest or by config.
- Its own bundle bakes exactly one non-empty `VITE_MAPTILER_KEY`.
- That key is not the `ci-web-build-security-synthetic` class and not one of the
  repository's exact placeholder values.
- With a fingerprint supplied, the baked key equals the key the fingerprint was recorded
  from.

**Cannot prove:**
- That the key is valid, unrevoked, domain-restricted, or authorized at MapTiler. No
  provider is contacted, by design.
- That a synthetic key using an unseen value outside the known class is absent. Only the
  fingerprint mode closes this.
- Anything about images started outside the supported entrypoints (a raw
  `docker compose` or `docker run` on the host), or after the break-glass token.
- Registry provenance or signatures. All-service signing is out of scope.

## 6. Proposed smallest verifiable contract (not activated here)

Record `sha256(VITE_MAPTILER_KEY)[:12]` from the `release` environment secret when a
production web image is built and published. It goes in the release manifest or the pin
comment. Operators then set `PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT` on the host. With
that, the guard proves the bundle bakes the authorized key without anyone printing it.
The workflow change to emit the fingerprint is a follow-up.

## 7. Scoped activation (later, separately authorized)

The guard activates when this PR merges and the host checkout is updated. No image or pin
changes. Steps on the production host:

1. Update the checkout to the merged `api` SHA. Confirm the modes:
   `git ls-files -s scripts/guard-web-synthetic-map-deploy.sh scripts/parkio-prod-compose.sh`
   should show `100755` for both.
2. **Check-only.** Before any web recreate, run the guard against the currently pinned
   image:
   `scripts/parkio-prod-compose.sh config --format json > /tmp/web-model.json` (not gated), then
   `bash scripts/guard-web-synthetic-map-deploy.sh --compose-config-json /tmp/web-model.json --env-file docker/.env.azure-hosted-beta --evidence-out /tmp/web-guard.json`.
   It creates one stopped container, removes it, and changes nothing else.
   - Expect PASS with the fingerprint.
   - If it BLOCKS, stop and investigate. Do not use break-glass by reflex.
3. Pull before recreating: `scripts/parkio-prod-compose.sh pull web`, then
   `scripts/parkio-prod-compose.sh up -d --no-build --no-deps --force-recreate web`.
4. Gateway-only operations are unchanged:
   `scripts/parkio-prod-compose.sh up -d --no-build --no-deps gateway-service`.
5. **Operator-visible changes:**
   - `PARKIO_SKIP_WEB_MAP_GUARD=1` now **fails**.
   - A web `up` needs the image already pulled.
   - A web `up` refuses `--build` and `--pull always`.
   - The host needs `python3`, which is already required by other host tooling.

## 8. Rollback

This is a script-only change.
- To roll back, revert the merge commit on `api`, or check out the previous SHA on the
  host. Nothing persistent is created: no files, volumes, images or containers remain.
- For emergency use without a revert, set
  `PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE`. It prints a warning and
  should be recorded in the incident log.
