# F-05 web map deploy guard: release package

- **Baseline:** `origin/api` = `b5a86ed8d432d0840e94796ee63781ddf2c4420c`
- **Draft PR:** #108
- **Round 1 head:** `2119ffe4bc558ec4f7df19d5255afbed52568f13`. Round 2, the final enforcement
  review, is described in this file. The exact final head and CI results are in the PR.

Boundaries:
- Not touched: #104 (HOLD), #109, and all restore, backup and DR files.
- Nothing was done to production, images, pins, secrets or any live provider.
- This package does not claim the running web map is broken.

---

## 1. Reproduced defect (baseline, isolated `git archive` export, index modes kept)

| # | Reproduction | Result |
|---|---|---|
| 1 | `bash scripts/test-guard-web-synthetic-map-deploy.sh` | **5/5 FAIL**, exit 126 "Permission denied". The guard was committed `100644`, is called by path, and its test was in no workflow. |
| 2 | `scripts/parkio-prod-compose.sh up -d --no-build --no-deps web` from a fresh checkout, with docker faked | **exit 126 before any docker call.** The same happens for **every** `up`, including gateway-only ones. |
| 3 | The same call with `PARKIO_SKIP_WEB_MAP_GUARD=1` | exit 0. `compose up` runs with **no check**. |
| 4 | Baseline guard run through `bash`, with the synthetic key quoted, prefixed with `export`, or ending in CRLF, and a mutable-tag pin | **PASS** in all three forms. The guard was text-only and never inspected an image. |
| 5 | Inventory of `deploy-hosted-beta.sh`, `deploy-invite-production.sh`, `rollback-hosted-beta.sh` and `rollback-invite-production.sh` | All four start web through `parkio_compose_up`, which had no image check at all. |

## 2. Evidence model

The web `Dockerfile` sets `VITE_MAPTILER_KEY` only in its build stage. The runtime image
has no label, env var or annotation for the key. The only evidence of the baked
configuration is the Vite bundle under `/usr/share/nginx/html`, which inlines
`import.meta.env` as an object literal. Every image built from the current Dockerfile
already passed `verify-bundle-env.mjs`, and that check requires the object.

Host `.env` values do not determine what a prebuilt image contains. The guard checks them
only as an early warning and never accepts an image because of them.

## 3. What the guard checks, per deploy

The guard takes the image selected by `services.web.image` in the rendered Compose model,
or `--image`, then:

1. **Known-bad manifest.** It blocks manifest `sha256:8d9bfca4…` when the requested
   reference names it. This happens before any docker call.
2. **Local image and identity.** It resolves the **local** image, fails closed if the image
   is absent, and never pulls. It records the config ID, RepoDigests and OS/arch.
3. **Known-bad config.** It blocks config ID `sha256:985fd8a7…`, and any RepoDigest that
   equals the bad manifest.
4. **Digest references.** A digest reference must be present in the local image's
   RepoDigests.
5. **Platform.**
   - The image OS/arch must equal the daemon platform (or `--expected-platform`).
   - The model's `services.web.platform`, when set, must also equal it.
   - So must `DOCKER_DEFAULT_PLATFORM`, when set.
6. **Bundle extraction.** It creates a container from that exact config ID but never starts
   it: no network, entrypoint never run. It copies the web root out, then removes the
   container.
7. **Classification.** `web_bundle_map_config.py` rejects:
   - a missing bundle, missing JS or missing env object;
   - a missing or empty `VITE_MAPTILER_KEY`, or conflicting keys across chunks;
   - the class `ci-web-build-security-synthetic` (exact, or followed by `-`, `_` or `.`);
   - the repository's exact CI and fixture placeholders;
   - any of those values quoted elsewhere in the JS.
8. **Optional fingerprint.** If a fingerprint is supplied, the baked key's fingerprint must
   equal it (see section 6).
9. **Output.** It never prints the key. It prints statuses, identities and a 12-hex SHA-256
   fingerprint. `--evidence-out` writes a JSON record, whose `result`, `fingerprintCheck`
   and `boundImage` are final.
10. **Binding.** It emits the binding override (section 4) only on PASS. A stale override
    is deleted first.

## 4. Image-to-deployment binding (review item 1)

**Problem.** Verifying an image and then running the original Compose invocation leaves a
gap. Between verification and container creation, the web image can change in four ways:

- a mutable tag moves;
- `pull_policy` in any overlay triggers a pull (`always`, `newer`, `build`) or an implicit
  build;
- the model files are edited;
- a platform selection differs.

**Mechanism.** Callers never run Compose on the unverified selection. The flow is:

1. Render the merged model once, including operator `-f`/`--profile` globals.
2. Verify the image it selects for web.
3. The guard writes a **binding override**:
   `{"services":{"web":{"image": <BOUND>, "pull_policy": "never"}}}`. It is JSON, which is
   valid YAML, and the file is mode 0600.
   - `<BOUND>` is the verified **`repo@sha256:<manifest>` reference** when the model pinned
     a digest. This is the production shape. A manifest digest addresses exactly one
     config, and the guard proved the local image carries it.
   - Otherwise `<BOUND>` is the verified **config ID** (`sha256:…`).
4. The caller runs Compose with that override as the **last `-f`**:
   - `parkio-prod-compose.sh` places it after the canonical file set **and** after operator
     globals, then runs `<subcommand> <args>`.
   - `parkio_compose_up` (deploy, invite deploy and both rollbacks) appends it to
     `PARKIO_COMPOSE_FILES` for the `up`.
5. The caller removes all temp files. The model contains interpolated env values, so it
   lives in a 0700 directory until then.

**Why this is equivalent to an immutable selection.**
- **Image.** The last override wins for `services.web.image`. Whatever a moved tag or an
  edited model file now says, Compose creates web from `<BOUND>`.
- **Pulls.** `pull_policy: never` overrides every overlay's policy, so Compose never pulls
  web.
- **Builds.** An implicit build needs a missing image or a `build` policy. The bound image
  exists, the policy is `never`, and the CLI `--build` flag is refused.
- **Platform.** Platform selections are verified before binding.
- **Recreates.** Compose excludes `pull_policy` and `build` from the service config hash.
  With a digest-pinned model, the bound `image:` string is unchanged. So installing or
  running the guard causes **no web recreate**. This was verified on a real daemon (§7,
  C8, repeated guarded `up`).
- **Tag-selected images.** A model that selects web by **tag** (for example the local
  builds in `deploy-hosted-beta.sh`) is bound to the config ID. Its first guarded `up`
  recreates web from the same verified image.

**Evidence: real Compose, real daemon** (Part C of the test). A docker shim substitutes an
**unverified** image between verification and the Compose mutation. Each scenario also runs
with break-glass as a control, to show the substitution would otherwise succeed.

| Scenario | Guarded result | Break-glass control |
|---|---|---|
| C1: tag moved after verification (wrapper) | verified image deployed | unverified image deployed |
| C2: model file edited after verification (wrapper) | verified image deployed | unverified image deployed |
| C3: operator overlay `pull_policy: always` | no pull attempted, verified image deployed | Compose pulls |
| C4: operator overlay `pull_policy: build` (implicit build of a synthetic context) | no rebuild, verified image deployed | unverified image rebuilt and deployed |
| C5: model `platform: linux/arm64`, or `DOCKER_DEFAULT_PLATFORM=linux/arm64`, against an amd64 image | blocked **by the guard** before any Compose mutation | — |
| C6: synthetic image selected | blocked before any Compose mutation, never created | — |
| C7: `parkio_compose_up`, tag moved or model edited after verification | verified image deployed | unverified image deployed |
| C8: digest-pinned model through a throwaway local registry | bound to the manifest reference, verified image deployed, a repeated guarded `up` does **not** recreate web, and `config --hash web` is identical with and without the binding (the §9.3 activation check) | — |

C8 needs plain-HTTP push to `127.0.0.1`, which a native Linux daemon allows. On Docker
Desktop the daemon runs in a VM and cannot reach a host-loopback registry, so the test
skips C8 there by name. On any other daemon a push failure fails the test. The CI job runs
on native Linux, and the PR records its C8 result.

**Negative controls** (a scratch copy of the branch with one protection removed; each must
turn tests red):

| Mutation | Failing assertions |
|---|---|
| Wrapper runs the original invocation, without the override | 7 (C1–C4, override content and order) |
| `parkio_compose_up` does not append the override | 3 (C7, override order) |
| Override without `pull_policy: never` | 3 |
| Model and `DOCKER_DEFAULT_PLATFORM` platform checks removed | 2 (C5) |

**Binding limits.**
- It binds `services.web` only. Another service definition that runs a synthetic image is
  outside this guard.
- A raw `docker compose`/`docker run` on the host, or a run with the break-glass token,
  bypasses it.
- `image: <config ID>` for tag-selected models relies on Compose accepting image IDs. That
  was verified on Compose v5.5.1 locally and on the CI runner's Compose. Production uses
  digest pins, so the host path binds to the manifest reference and does not depend on
  this.

## 5. Compose argument parsing (review item 2)

`--use-aliases` is a **boolean** `run` flag. In round 1 the parser consumed the next
argument as its value, so `run --no-deps --use-aliases web <cmd>` could be classified as
not targeting web. The parser is now table-driven, per subcommand, from the Compose CLI
reference.

**Recognised options**

| Scope | Boolean flags | Options that take a value |
|---|---|---|
| Global | `--compatibility`, `--dry-run`, `--all-resources` | `-f`, `-p`, `--profile`, `--env-file`, `--project-directory`, `--ansi`, `--progress`, `--parallel` |
| `up` | the full flag set | `--attach`, `--exit-code-from`, `--no-attach`, `--pull`, `--scale`, `-t`/`--timeout`, `--wait-timeout` |
| `create` | its flags | `--pull`, `--scale` |
| `run` | `--use-aliases`, `--rm`, `-T`, `-P`, `-i`, `-d`, `-t`/`--tty`, `--no-deps`, `--build`, and others | `-e`, `--env-from-file`, `--entrypoint`, `-l`, `--name`, `-p`, `--pull`, `-u`, `-v`, `-w`, `--cap-add`, `--cap-drop` |

**Parsing rules**
- **`--opt=value` forms** are handled. A boolean flag written with `=` must be `true` or
  `false`, so `--no-deps=false` keeps dependencies and is gated.
- **`run SERVICE [COMMAND…]`:** parsing stops at the service. Command arguments that look
  like services or options (`web`, `--no-deps`, `--pull=always`) are never interpreted.
- **`--`** ends options.
- **`--scale web=N`** counts as targeting web.
- **Anything unrecognised is ambiguous and runs the guard.** That includes an unknown
  subcommand option, combined short flags such as `-dV`, and an unknown global option
  (whose value could be mistaken for the subcommand). Unsupported syntax can make a
  gateway-only call stricter. It can never make a web-creating call skip.
- **Gateway-only behavior is preserved.** `up -d --no-build --no-deps gateway-service`, and
  its `-p parkio`, `--pull=missing` and trailing `--timeout=30` variants, run unguarded
  with no binding.

**Tested through the real wrapper**

| Invocation | Result |
|---|---|
| `run --no-deps --use-aliases web echo hi` | gated |
| `run --no-deps --use-aliases gateway-service web` | not gated: `web` is the command |
| `run --no-deps gateway-service --no-deps --pull=always web` | not gated |
| `run --rm -T --no-deps -e K=V web sh` | gated |
| `up --no-deps=false gateway-service` | gated |
| `up --no-deps --scale web=2 gateway-service` | gated |
| unknown option, `-dV`, unknown global | gated |
| `--profile=ops up -d` | gated |
| `up --no-deps -- gateway-service` | not gated |
| `up --no-deps -- web` | gated |
| `up --build=true web`, `run --build web` | refused |

**Negative controls:** restoring the value-consuming `--use-aliases` fails 2 assertions.
Letting unknown options skip fails 2.

## 6. Claims (review item 4)

These are four separate claims:

| # | Claim | Established by this change? |
|---|---|---|
| A | Known synthetic values (the `ci-web-build-security-synthetic` class and the repository's exact placeholders) are rejected for the image that will run, by config ID | **Yes**, on every guarded web deploy. |
| B | The baked key matches a supplied fingerprint | **Only when** `--expected-map-key-fingerprint` / `PARKIO_WEB_EXPECTED_MAP_KEY_FINGERPRINT` is supplied. It proves equality to *whatever key the fingerprint was computed from*. It is off by default. |
| C | That fingerprint came from an independently approved release | **No.** Nothing records a fingerprint at release time yet. Establishing this needs a follow-up: the release workflow records `sha256(key)[:12]` from the `release` environment in reviewed release evidence, and operators take it only from there. |
| D | MapTiler accepts that key for the production origin | **No.** No live provider requests are made or authorized. This needs a separately authorized provider-side check (key restrictions, allowed origins). |

The guard also cannot prove that an unseen synthetic value outside the known class is
absent. Only claim B with a trustworthy fingerprint (claim C) closes that.

## 7. Tests

`scripts/test-guard-web-synthetic-map-deploy.sh` has three parts:
- **Part A:** a fake docker drives the real guard, classifier, wrapper and
  `parkio_compose_up`, and logs every Compose mutation and the override content.
- **Part B:** real-daemon checks against `FROM scratch` fixture images.
- **Part C:** the real Compose binding scenarios in §4.

Local result: **135/135**, with C8 skipped on Docker Desktop as described in §4. CI runs the
suite with `PARKIO_GUARD_TEST_REQUIRE_DOCKER=1` in `invite-production-deploy.yml` → "Build
images + secret-safe dry-run manifest". Round 1 negative controls (baseline guard, baseline
wrapper, accept-all classifier, and others) remain valid. Round 2 controls are in §4 and
§5.

## 8. Merge readiness vs production activation

**Merge readiness** covers only:
- code review of #108;
- the required checks ("Build & unit tests", "Secret scan") and the affected job, green on
  the exact head.

Merging changes no running system. Afterwards:
- `deploy-hosted-beta.sh`, `deploy-invite-production.sh` and the rollbacks, run from a
  checkout of the merged SHA, use the guarded `parkio_compose_up`. For invite-production,
  the CI runner stages the exact SHA.
- The Civo production host keeps its current, drifted checkout until the scoped
  installation in §9, which is separately authorized.

**Production activation** is a separate, later, authorized operation (§9). It needs no
image, pin, env or container change, and **no web recreate is required to install the
guard**.

## 9. Scoped installation on the production host (later, separately authorized)

The production checkout is known to have drift. **Do not** run `git pull`, `git reset`,
`git checkout`, `git clean` or `git stash` there. Install only the files below, by content
hash.

### 9.1 Installation set: the Civo wrapper path

Relative to the repository root, e.g. `/opt/parkio`:

| File | Action | Expected pre-image SHA-256 (baseline `b5a86ed8`) | Post-image SHA-256 | Mode |
|---|---|---|---|---|
| `scripts/parkio-prod-compose.sh` | replace | `f8cfa8bec4d979288bfcd80b65c9ff78acb6e683d264903c16e1cac647ff2ea0` | `08492bc5ea9a8a2047ac9835212298998c042166b6ead6ae94262e7942b7e97a` | preserve existing (baseline `0755`) |
| `scripts/guard-web-synthetic-map-deploy.sh` | replace | `940ec1fefdc3f9f1164d1a60f04cb4ad2232a9d7616022c280915849d9c637f5` | `49a21daf3d870ec81f572da62fb79e1cfc8b8eb3a8998f8a1773c398992fc5f0` | preserve existing. It is always invoked through `bash`, so `0644` works. |
| `scripts/lib/web-map-guard.sh` | new (must be absent) | — | `ed2d13474aa473a3341c704837bd9c90f71e9661cdb877d1246caa42f5149a68` | `0644`, owner/group of `scripts/lib/` |
| `scripts/lib/web_bundle_map_config.py` | new (must be absent) | — | `c02c4d4f13a2c20588f6f34cf7cdb85040b15dbd8acd433bdcba7e8acc17bf68` | `0644`, owner/group of `scripts/lib/` |

**Not in the Civo installation set.** `scripts/lib/deploy-common.sh` (post-image
`6c300a9774084fcb8316996142a772945b9c28c51dda2d7bd359dd48f94fa8a3`) is used only by the deploy and rollback scripts, which run from a synchronized
checkout of the merged SHA. It is a large shared file and may be drifted on the host. If
the host ever runs those scripts in place, install it separately after reviewing a diff
against its current content.

**Transitive dependencies.** Nothing else in the repository changes: the wrapper still
reads the unchanged `docker/compose.production.files` and pin files. The host must provide:

| Dependency | Why |
|---|---|
| `bash` 4.4 or later | `set -u` with empty arrays |
| `python3` 3.8 or later | classifier, JSON and env parsing |
| Docker 20.10 or later | `docker create --pull never` |
| Docker Compose v2 with `config --format json` and `config --hash` | model rendering and the hash check |
| `tar`, `mktemp`, `sed`, `grep`, `head`, `cut`, `dirname`, `basename` | used by the scripts |

The check-only step below proves each of these on the host before installation.

### 9.2 Obtain and verify the files (workstation)

1. `git archive <merged api SHA> scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py > f05-guard.tar`
2. Transfer the archive to the host through the approved channel.
3. On the host, extract it into a **private staging directory** outside the checkout:
   `umask 077; STAGE=$(mktemp -d); tar -x -C "$STAGE" -f f05-guard.tar`.
4. `sha256sum "$STAGE"/scripts/parkio-prod-compose.sh "$STAGE"/scripts/guard-web-synthetic-map-deploy.sh "$STAGE"/scripts/lib/web-map-guard.sh "$STAGE"/scripts/lib/web_bundle_map_config.py`
   The output must equal the **post-image** column. **Stop** on any mismatch.

### 9.3 Check-only validation against the accepted web pin (before installing)

This runs the **staged** guard. Nothing in the checkout changes and no container is
started. The guard creates and removes one stopped, network-less inspection container.

```bash
cd /opt/parkio
bash --version | head -1; python3 --version; docker version --format '{{.Server.Version}}'; docker compose version
# current wrapper: `config` was never gated, so this is read-only
PARKIO_ENV_FILE=docker/.env.azure-hosted-beta scripts/parkio-prod-compose.sh config --format json > "$STAGE/model.json"
bash "$STAGE/scripts/guard-web-synthetic-map-deploy.sh" \
  --compose-config-json "$STAGE/model.json" --env-file docker/.env.azure-hosted-beta \
  --evidence-out "$STAGE/guard.json" --bind-override-out "$STAGE/web-binding.yml"
```

Expected result:

| Field | Expected value |
|---|---|
| Result | `PASS` |
| `requestedImage` and `bound` | `ghcr.io/adberilgen35/parkio/web@sha256:aacf9dc9ab8ef412dee01429da2b2bbc33099c4560a7904f181f9fe6db381f6e` |
| `configId` | `sha256:de405e587f8da14b70d8046d3ba9d14250ff8a705e3ffef8881d3d555d01d8be`, the "Image/config ID" recorded in the pin file comment. This is an independent cross-check. |
| `platform` | `linux/amd64` |
| `fingerprintCheck` | `not-requested` |

If the result is BLOCKED or any field differs: **stop**. Do not install and do not use
break-glass. The accepted deployment keeps running either way, and it is the unchanged
baseline.

**No-recreate check** (read-only):

```bash
FILES=$(grep -v '^#' docker/compose.production.files | sed '/^$/d; s/^/-f /' | tr '\n' ' ')
docker compose --env-file docker/.env.azure-hosted-beta $FILES config --hash web
docker compose --env-file docker/.env.azure-hosted-beta $FILES -f "$STAGE/web-binding.yml" config --hash web
```

The two hashes must be identical, which means a guarded `up` will not recreate web. Then
delete the rendered model, which contains env values: `rm -f "$STAGE/model.json"`. Keep
`guard.json` as activation evidence; it contains no key.

### 9.4 Back up, compare, install (preserving owner/mode)

```bash
cd /opt/parkio
BK=/var/backups/parkio-f05-$(date -u +%Y%m%dT%H%M%SZ); install -d -m 0700 "$BK"
for f in scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh; do
  sha256sum "$f"; stat -c '%U:%G %a %n' "$f"     # must equal the pre-image column
  cp -p "$f" "$BK/"                              # -p keeps owner/mode/timestamps
done
ls scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py   # must NOT exist
sha256sum "$BK"/* > "$BK/SHA256SUMS"
```

- If a **pre-image hash differs**, the host copy has drifted. **Stop.** Do not overwrite;
  escalate for a reviewed merge of that file.
- If a new file already exists, stop.

Install one file at a time, preserving the existing owner and mode:

```bash
for f in scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh; do
  own=$(stat -c '%U' "$f"); grp=$(stat -c '%G' "$f"); mode=$(stat -c '%a' "$f")
  install -o "$own" -g "$grp" -m "$mode" "$STAGE/$f" "$f"
done
own=$(stat -c '%U' scripts/lib); grp=$(stat -c '%G' scripts/lib)
for f in scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py; do
  install -o "$own" -g "$grp" -m 0644 "$STAGE/$f" "$f"
done
sha256sum scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py   # = post-image column
stat -c '%U:%G %a %n' scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py
```

**Post-install, non-mutating checks:**
- `bash -n` on the four files.
- `scripts/parkio-prod-compose.sh ps` (not gated) works.
- Optionally, where Compose supports `--dry-run`:
  `scripts/parkio-prod-compose.sh --dry-run up -d --no-build --no-deps web`. It exercises
  guard and binding, and must show guard PASS with web **not** recreated.

Installing the guard does **not** recreate web or touch any container. `git status` on the
host will now also list these four paths as local changes. Record them in the drift log;
they reconcile when the host is next synchronized under a separate change.

### 9.5 Operator-visible behaviour after installation

- Any web-creating wrapper call (`up`, `create` or `run` that can touch web) runs the guard
  and the binding. Gateway-only and read-only calls are unchanged.
- The web image must already be present locally. Pull first:
  `scripts/parkio-prod-compose.sh pull web`. This is not gated.
- For web, `--build` and `--pull always|newer|build` are refused, and overlay `pull_policy`
  values are neutralised.
- `PARKIO_SKIP_WEB_MAP_GUARD=1` now **fails**. Break-glass needs
  `PARKIO_SKIP_WEB_MAP_GUARD=I_ACCEPT_UNVERIFIED_WEB_IMAGE`, which disables **both**
  verification and binding. Record every use.

### 9.6 Rollback

```bash
cd /opt/parkio
cp -p "$BK/parkio-prod-compose.sh" scripts/parkio-prod-compose.sh
cp -p "$BK/guard-web-synthetic-map-deploy.sh" scripts/guard-web-synthetic-map-deploy.sh
rm -f scripts/lib/web-map-guard.sh scripts/lib/web_bundle_map_config.py
sha256sum scripts/parkio-prod-compose.sh scripts/guard-web-synthetic-map-deploy.sh   # = pre-image column
```

No container, image, pin or env change is involved. Rollback does **not** restore
equivalent protection. It restores **all baseline weaknesses** in §1:

- wherever the guard file is not executable, every `up` through the wrapper fails with 126,
  and the only way through is `PARKIO_SKIP_WEB_MAP_GUARD=1`, which runs no check;
- the text-only env/pin check comes back, and never inspects the image;
- there is no binding, so tag moves, overlay `pull_policy` and model edits after the check
  are not prevented.
