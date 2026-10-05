#!/usr/bin/env bash
# CL-F12: the hosted-beta profile's deploy and rollback, run for real against a fake docker.
#
#   - parkio_hosted_beta_image_plan splits the app services into built and digest-pinned ones, and
#     fails closed on a missing service or an image that is neither built nor pinned;
#   - deploy builds only the built services (with the OCI build-args), tags each sha-<git> and
#     beta-latest, pulls a missing pin, starts with `up --no-build`, and records both kinds;
#   - a pin that cannot be pulled stops the deploy before anything starts;
#   - rollback points each built service's model image at the tag the target manifest records,
#     keeps this checkout's pins, and starts with `up --no-build`, also for a manifest written
#     before CL-F12 that recorded all eleven services;
#   - rollback refuses, before it re-points or starts anything, a target that did not build a
#     service the model builds, a recorded image that is not present, and a pin it cannot pull.
#
# The scripts under test run from a temporary tree. The fake docker answers from a canned compose
# model and records every call. The preflight, the smoke test and the web guards have their own
# tests and are stubs here. Nothing real is built, pulled or started.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
failures=0
pass() { echo "PASS: $*"; }
bad() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

command -v jq >/dev/null 2>&1 || { echo "FAIL: jq is required by the deploy scripts" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
tree="$work/tree"
state="$work/state"
mkdir -p "$tree/scripts/lib" "$tree/docker" "$work/bin" "$state/images"

cp "$ROOT/scripts/deploy-hosted-beta.sh" "$ROOT/scripts/rollback-hosted-beta.sh" "$tree/scripts/"
cp "$ROOT/scripts/lib/deploy-common.sh" "$ROOT/scripts/lib/dark-gateway-url.sh" "$ROOT/scripts/lib/invite-edge-mode.sh" \
  "$ROOT/scripts/lib/disk-space.sh" "$ROOT/scripts/lib/runtime-release.sh" "$ROOT/scripts/lib/rollback_schema_gate.py" \
  "$tree/scripts/lib/"
cp "$ROOT/docker/compose.production.files" "$tree/docker/"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tree/scripts/preflight-hosted-beta.sh"
printf '#!/usr/bin/env bash\nexit 0\n' > "$tree/scripts/smoke-hosted-beta.sh"
chmod +x "$tree/scripts/"*.sh
cat > "$tree/scripts/lib/web-map-guard.sh" <<'EOF'
# Stub: the web map guard has its own tests (scripts/test-guard-web-synthetic-map-deploy.sh).
parkio_web_guard_split_args() { :; }
parkio_web_guard_decide() { PWG_DECISION=skip; }
EOF
printf '# Stub: the conf.d check has its own tests.\n' > "$tree/scripts/lib/web-conf-d-guard.sh"
git -C "$tree" init -q
git -C "$tree" add -A
git -C "$tree" -c user.name=hosted-beta-image-plan-test -c user.email=test@example.invalid \
  -c commit.gpgsign=false commit -q -m "fixture"
GIT_SHA="$(git -C "$tree" rev-parse HEAD)"
TAG="sha-$GIT_SHA"
printf 'PARKIO_DOMAIN=example.invalid\n' > "$work/env"

digest() { printf 'sha256:%064d' "$1"; }
PINNED=(gateway-service auth-service parking-service media-service web)
BUILT=(user-service gamification-service notification-service moderation-service ai-validation-service analytics-service)
write_model() { # write_model [SERVICE=JSON ...] overrides one service's model entry; SERVICE= drops it
  python3 - "$state/model.json" "${PINNED[*]}" "${BUILT[*]}" "$@" <<'PY'
import json, sys
out, pinned, built = sys.argv[1], sys.argv[2].split(), sys.argv[3].split()
services = {"postgres-auth": {"image": "postgis/postgis:16-3.4"},
            # The edge, with beta hostnames: the hosted-beta profile reads them from the model (G4).
            "caddy": {"image": "caddy:2", "environment": {"PARKIO_DOMAIN": "api.beta.example.com",
                      "PARKIO_WEB_DOMAIN": "app.beta.example.com", "PARKIO_MEDIA_DOMAIN": "media.beta.example.com"}}}
for i, name in enumerate(pinned, 1):
    services[name] = {"image": f"ghcr.io/example/parkio/{name}@sha256:{i:064d}",
                      "build": {"context": ".."}, "platform": "linux/amd64"}
for name in built:
    services[name] = {"build": {"context": ".."}, "platform": "linux/amd64"}
for override in sys.argv[4:]:
    name, _, value = override.partition("=")
    if value:
        services[name] = json.loads(value)
    else:
        services.pop(name, None)
json.dump({"name": "parkio", "services": services}, open(out, "w"))
PY
}

cat > "$work/bin/docker" <<'EOF'
#!/usr/bin/env bash
# Fake docker: images are files under $FAKE_STATE/images; every mutating call is logged.
set -u
S="$FAKE_STATE"
log() { printf '%s\n' "$*" >> "$S/calls.log"; }
key() { printf '%s' "$1" | tr '/:@' '___'; }
present() { [ -f "$S/images/$(key "$1")" ]; }
last() { local a; for a in "$@"; do :; done; printf '%s' "$a"; }
case "${1:-}" in
  compose)
    shift
    while [ $# -gt 0 ]; do
      case "$1" in
        --env-file|-f|--file|-p|--project-name|--profile|--project-directory) shift 2 ;;
        *) break ;;
      esac
    done
    sub="${1:-}"
    shift || true
    case "$sub" in
      config)
        case " $* " in
          *" --format json "*) cat "$S/model.json" ;;
          *" --quiet "*) : ;;
          *) echo "services: {}" ;;
        esac
        ;;
      build)
        svc="$(last "$@")"
        log "BUILD $*"
        echo "id-built-$svc-$(date +%s%N)" > "$S/images/$(key "parkio-$svc")"
        ;;
      up) log "UP $*" ;;
      ps) printf 'cid-%s\n' "$(last "$@")" ;;
      *) log "REFUSED compose $sub $*"; exit 97 ;;
    esac
    ;;
  image)
    [ "${2:-}" = inspect ] || { log "REFUSED $*"; exit 97; }
    ref="$(last "$@")"
    present "$ref" || { echo "Error: No such image: $ref" >&2; exit 1; }
    case " $* " in
      *" --format "*) cat "$S/images/$(key "$ref")" ;;
      *) printf '[{"Id": "%s"}]\n' "$(cat "$S/images/$(key "$ref")")" ;;
    esac
    ;;
  inspect) echo healthy ;;
  tag)
    log "TAG $2 $3"
    if grep -qxF "$3" "$S/tag-fails" 2>/dev/null; then echo "Error: injected tag failure: $3" >&2; exit 1; fi
    present "$2" || { echo "Error: No such image: $2" >&2; exit 1; }
    cp "$S/images/$(key "$2")" "$S/images/$(key "$3")"
    ;;
  pull)
    log "PULL ${*:2}"
    ref="$(last "$@")"
    if grep -qxF "$ref" "$S/pull-fails" 2>/dev/null; then echo "Error: denied: $ref" >&2; exit 1; fi
    echo "id-pulled-$(key "$ref")" > "$S/images/$(key "$ref")"
    ;;
  *) log "REFUSED $*"; exit 97 ;;
esac
EOF
chmod +x "$work/bin/docker"

image_key() { printf '%s' "$1" | tr '/:@' '___'; }
have_image() { echo "${2:-id-$(image_key "$1")}" > "$state/images/$(image_key "$1")"; }
image_id() { cat "$state/images/$(image_key "$1")" 2>/dev/null || true; }
reset_state() { rm -rf "$state"; mkdir -p "$state/images"; : > "$state/calls.log"; write_model "$@"; }
calls() { cat "$state/calls.log"; }
run() { # run ARTIFACT_DIR SCRIPT ARGS... ; sets rc and leaves output in $work/out
  local artifacts="$1"
  shift
  rc=0
  (cd "$tree" && FAKE_STATE="$state" PATH="$work/bin:$PATH" PARKIO_ENV_FILE="$work/env" \
    PARKIO_DEPLOY_ARTIFACT_DIR="$artifacts" PARKIO_DEPLOY_MIN_FREE_BYTES=1 PARKIO_DEPLOY_OPERATOR=test \
    PARKIO_DEPLOY_STATE_DIR="${RUN_STATE_DIR:-$work/deploy-state}" \
    PARKIO_DEPLOY_HEALTH_TIMEOUT=5 env -u PARKIO_DEPLOYMENT_PROFILE "$@") > "$work/out" 2>&1 || rc=$?
}
plan_pins_json() { # the pins of the current model's plan, as a manifest's pinnedImages
  plan | jq -R -s '[split("\n")[] | split("\t") | select(.[0] == "pinned") | {key: .[1], value: .[2]}] | from_entries'
}
plan() { # plan [ENV...]: runs the plan function alone in the tree
  (cd "$tree" && FAKE_STATE="$state" PATH="$work/bin:$PATH" bash -c 'set -euo pipefail
    source scripts/lib/deploy-common.sh
    PARKIO_COMPOSE_FILES="$(parkio_canonical_compose_files)"
    parkio_hosted_beta_image_plan "$1"' _ "$work/env")
}

# --- the image plan --------------------------------------------------------------
reset_state
expected_plan="$(
  for svc in gateway-service auth-service user-service parking-service media-service gamification-service \
    notification-service moderation-service ai-validation-service analytics-service web; do
    case " ${PINNED[*]} " in
      *" $svc "*)
        i=0; for p in "${PINNED[@]}"; do i=$((i + 1)); [ "$p" = "$svc" ] && break; done
        printf 'pinned\t%s\tghcr.io/example/parkio/%s@%s\tlinux/amd64\n' "$svc" "$svc" "$(digest "$i")" ;;
      *) printf 'built\t%s\tparkio-%s\tlinux/amd64\n' "$svc" "$svc" ;;
    esac
  done)"
if [ "$(plan 2>&1)" = "$expected_plan" ]; then
  pass "the plan lists 5 pinned and 6 built services under their model names, in app-service order"
else
  bad "the plan is not the expected split"; plan >&2 || true
fi
reset_state "analytics-service="
if out="$(plan 2>&1)"; then bad "a model without analytics-service is planned"; else
  grep -q "analytics-service is not in the model" <<< "$out" && pass "a missing app service fails the plan" \
    || bad "a missing app service fails the plan with the reason: $out"
fi
reset_state 'user-service={"image": "parkio/user-service:latest"}'
if out="$(plan 2>&1)"; then bad "a mutable image the model does not build is planned"; else
  grep -q "user-service runs parkio/user-service:latest that the model neither builds nor pins" <<< "$out" \
    && pass "a mutable image the model does not build fails the plan" \
    || bad "a mutable image the model does not build fails with the reason: $out"
fi

# --- deploy -----------------------------------------------------------------------
reset_state
for i in 1 2 3 4; do have_image "ghcr.io/example/parkio/${PINNED[i - 1]}@$(digest "$i")"; done
run "$work/deploy-a" ./scripts/deploy-hosted-beta.sh --allow-dirty --skip-smoke
if [ "$rc" -eq 0 ]; then pass "deploy succeeds"; else bad "deploy exits $rc"; tail -n 15 "$work/out" >&2; fi
builds="$(calls | grep '^BUILD ' || true)"
[ "$(wc -l <<< "$builds")" -eq 6 ] && pass "deploy builds 6 services" || bad "deploy builds: $builds"
for svc in "${PINNED[@]}"; do
  if grep -q " $svc$" <<< "$builds"; then bad "deploy builds the pinned $svc"; fi
done
tagged=0
for svc in "${BUILT[@]}"; do
  if grep -qx "BUILD --build-arg IMAGE_VERSION=0.0.1-SNAPSHOT --build-arg IMAGE_REVISION=$GIT_SHA --build-arg IMAGE_CREATED=[0-9TZ:-]* $svc" <<< "$builds" \
    && calls | grep -qx "TAG parkio-$svc parkio/$svc:$TAG" && calls | grep -qx "TAG parkio-$svc parkio/$svc:beta-latest"; then
    tagged=$((tagged + 1))
  else
    bad "deploy builds $svc with the OCI build-args and tags it $TAG and beta-latest"
  fi
done
if [ "$tagged" -eq "${#BUILT[@]}" ]; then
  pass "deploy builds each unpinned service with the OCI build-args and tags it $TAG and beta-latest"
fi
if [ "$(calls | grep -c '^PULL ')" -eq 1 ] && calls | grep -qx "PULL --platform linux/amd64 ghcr.io/example/parkio/web@$(digest 5)"; then
  pass "deploy pulls only the pin that is missing, for the model's platform"
else
  bad "deploy pulls: $(calls | grep '^PULL ' || echo none)"
fi
if [ "$(calls | tail -n 1)" = "UP -d --no-build" ]; then pass "deploy starts last, with up -d --no-build"; else
  bad "deploy's last call is '$(calls | tail -n 1)', not 'UP -d --no-build'"; fi
manifest_a="$(find "$work/deploy-a" -maxdepth 1 -name 'deploy-*.json' | head -n 1)"
# F-INV-3: the deploy records its manifest as the deployed release's, outside the checkout.
if [ -n "$manifest_a" ] && cmp -s "$manifest_a" "$work/deploy-state/deployed-manifest.json"; then
  pass "deploy records its manifest as the deployed release's (PARKIO_DEPLOY_STATE_DIR)"
else
  bad "deploy did not record its manifest in the deploy state directory"
fi
if [ -n "$manifest_a" ] && python3 - "$manifest_a" "$TAG" "${PINNED[*]}" "${BUILT[*]}" <<'PY'
import json, sys
m, tag = json.load(open(sys.argv[1])), sys.argv[2]
pinned, built = sys.argv[3].split(), sys.argv[4].split()
ok = (m["images"] == {s: f"parkio/{s}:{tag}" for s in built}
      and sorted(m["pinnedImages"]) == sorted(pinned)
      and all("@sha256:" in ref for ref in m["pinnedImages"].values())
      and sorted(m["imageDigests"]) == sorted(built)
      and m["disabledServices"] == ["alertmanager", "loki", "promtail", "tempo"])
sys.exit(0 if ok else 1)
PY
then
  pass "the deploy manifest records the built images, the pins, their digests and the disabled services"
else
  bad "the deploy manifest does not record the plan"; cat "$manifest_a" >&2 2>/dev/null || true
fi

# #290 review B2: the deploy records in the host's deploy state directory; when it cannot be
# written, the deploy stops before any pull, build or start.
reset_state
: >"$work/not-a-directory"
RUN_STATE_DIR="$work/not-a-directory/state" run "$work/deploy-nostate" ./scripts/deploy-hosted-beta.sh --allow-dirty --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "is not writable by" "$work/out" && ! calls | grep -Eq '^(BUILD|PULL|TAG|UP) '; then
  pass "a deploy state directory that cannot be written stops the deploy (exit 3) before any pull, build or start"
else
  bad "unwritable deploy state directory: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# A pin that cannot be pulled stops the deploy before any build or start.
reset_state
echo "ghcr.io/example/parkio/web@$(digest 5)" > "$state/pull-fails"
for i in 1 2 3 4; do have_image "ghcr.io/example/parkio/${PINNED[i - 1]}@$(digest "$i")"; done
run "$work/deploy-b" ./scripts/deploy-hosted-beta.sh --allow-dirty --skip-smoke
if [ "$rc" -ne 0 ] && ! calls | grep -Eq '^(BUILD|TAG|UP) ' && grep -q "cannot pull the pinned web image" "$work/out"; then
  pass "a pin that cannot be pulled stops the deploy before any build or start (exit $rc)"
else
  bad "a pin that cannot be pulled: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# --- rollback ------------------------------------------------------------------------
# The rollback runs from a fresh artifact directory, as the workflow's rollback job does.
rollback_state() { # every pin present, and the recorded and the current image of each built service
  reset_state "$@"
  local i=0 svc
  for svc in "${PINNED[@]}"; do i=$((i + 1)); have_image "ghcr.io/example/parkio/$svc@$(digest "$i")"; done
  for svc in "${BUILT[@]}"; do
    have_image "parkio/$svc:$TAG" "id-old-$svc"
    have_image "parkio-$svc" "id-new-$svc"
  done
}
rollback_state
run "$work/rollback-a" ./scripts/rollback-hosted-beta.sh --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 0 ]; then pass "rollback succeeds"; else bad "rollback exits $rc"; tail -n 15 "$work/out" >&2; fi
repointed=0
for svc in "${BUILT[@]}"; do
  calls | grep -qx "TAG parkio/$svc:$TAG parkio-$svc" && [ "$(image_id "parkio-$svc")" = "id-old-$svc" ] \
    && repointed=$((repointed + 1))
done
[ "$repointed" -eq 6 ] && pass "rollback points all 6 built services' model images at the recorded tags" \
  || bad "rollback re-pointed $repointed of 6 built services"
if calls | grep -q '^TAG ghcr.io'; then bad "rollback re-tags a pin"; fi
if calls | grep -Eq '^(BUILD|PULL) '; then bad "rollback builds or pulls: $(calls | grep -E '^(BUILD|PULL) ' | tr '\n' ';')"; fi
if [ "$(calls | tail -n 1)" = "UP -d --no-build" ]; then pass "rollback starts last, with up -d --no-build"; else
  bad "rollback's last call is '$(calls | tail -n 1)', not 'UP -d --no-build'"; fi
if grep -qF "the digest pins equal the deployed release's" "$work/out" \
  && [ "$(jq -S . "$work/deploy-state/deployed-manifest.json")" = "$(jq -S --argjson pins "$(plan_pins_json)" '.pinnedImages = $pins' "$manifest_a")" ]; then
  pass "with the deployed release's pins, the gate passes and the rollback records the target with the pins that run"
else
  bad "rollback with equal pins: gate or record differs"
fi

# F-INV-3: the schema gate reads the deployed release's record, and refuses, before anything is
# re-pointed or started, without it or when the live schema is ahead of the target.
rollback_state
RUN_STATE_DIR="$work/no-record" run "$work/rollback-no-record" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "the deployed release's manifest is missing" "$work/out" \
  && ! calls | grep -Eq '^(TAG|UP|BUILD|PULL) '; then
  pass "rollback without the deployed release's record is refused (exit 3) before it changes anything"
else
  bad "rollback without a record: exit $rc, calls: $(calls | tr '\n' ';')"
fi
mkdir -p "$work/ahead-state"
jq '.migrationVersions["user-service"] += ["V999__applied_by_a_later_release.sql"]' \
  "$work/deploy-state/deployed-manifest.json" > "$work/ahead-state/deployed-manifest.json"
rollback_state
RUN_STATE_DIR="$work/ahead-state" run "$work/rollback-ahead" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "the live schema is ahead of the rollback target (user-service: V999__applied_by_a_later_release.sql)" "$work/out" \
  && ! calls | grep -Eq '^(TAG|UP|BUILD|PULL) '; then
  pass "rollback with the live schema ahead of the target is refused (exit 3) before it changes anything"
else
  bad "rollback with the schema ahead: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# #290 review B2: the rollback records its target in the host's deploy state directory; when it
# cannot be written, the rollback is refused before the gate and before it changes anything.
rollback_state
RUN_STATE_DIR="$work/not-a-directory/state" run "$work/rollback-nostate" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "is not writable by" "$work/out" && ! grep -qF "rollback schema gate" "$work/out" \
  && ! calls | grep -Eq '^(TAG|UP|BUILD|PULL) '; then
  pass "rollback with a deploy state directory that cannot be written is refused (exit 3) before it changes anything"
else
  bad "rollback with an unwritable deploy state directory: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# #290 review B1: the rollback starts this checkout's pins, whose migrations are recorded nowhere.
# A record whose pins differ, or that records none, is refused before anything changes.
mkdir -p "$work/pin-state" "$work/nopin-state"
jq --arg pin "ghcr.io/example/parkio/auth-service@$(digest 9)" '.pinnedImages["auth-service"] = $pin' \
  "$manifest_a" > "$work/pin-state/deployed-manifest.json"
rollback_state
RUN_STATE_DIR="$work/pin-state" run "$work/rollback-pin-differs" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "would change digest pins the deployed release runs (auth-service: ghcr.io/example/parkio/auth-service@$(digest 9) -> ghcr.io/example/parkio/auth-service@$(digest 2))" "$work/out" \
  && ! calls | grep -Eq '^(TAG|UP|BUILD|PULL) ' && cmp -s "$work/pin-state/deployed-manifest.json" <(jq --arg pin "ghcr.io/example/parkio/auth-service@$(digest 9)" '.pinnedImages["auth-service"] = $pin' "$manifest_a"); then
  pass "rollback whose pins differ from the deployed release's is refused (exit 3) before it changes anything"
else
  bad "rollback with a pin that differs: exit $rc, calls: $(calls | tr '\n' ';')"
fi
jq 'del(.pinnedImages)' "$manifest_a" > "$work/nopin-state/deployed-manifest.json"
rollback_state
RUN_STATE_DIR="$work/nopin-state" run "$work/rollback-no-pins" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -eq 3 ] && grep -qF "records no readable pinnedImages" "$work/out" && ! calls | grep -Eq '^(TAG|UP|BUILD|PULL) '; then
  pass "rollback against a record without pinnedImages is refused (exit 3) before it changes anything"
else
  bad "rollback against a record without pins: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# #290 review N4: a step that fails after the record was written, before the start, restores it.
mkdir -p "$work/retag-state"
jq '.gitSha = "previous-release"' "$manifest_a" > "$work/retag-state/deployed-manifest.json"
cp "$work/retag-state/deployed-manifest.json" "$work/retag-previous.json"
rollback_state
echo "parkio-moderation-service" > "$state/tag-fails"
RUN_STATE_DIR="$work/retag-state" run "$work/rollback-retag-fails" ./scripts/rollback-hosted-beta.sh \
  --manifest "$manifest_a" --skip-smoke
if [ "$rc" -ne 0 ] && grep -qF "restoring the deployed release's record" "$work/out" \
  && cmp -s "$work/retag-previous.json" "$work/retag-state/deployed-manifest.json" && ! calls | grep -q '^UP '; then
  pass "a re-pointing that fails after the record was written restores the previous record, and nothing starts (exit $rc)"
else
  bad "re-pointing fails: exit $rc, record gitSha $(jq -r .gitSha "$work/retag-state/deployed-manifest.json"), calls: $(calls | tr '\n' ';')"
fi

# A manifest written before CL-F12 recorded every app service; the pinned ones keep their pins.
legacy="$work/legacy/deploy-legacy.json"
mkdir -p "$work/legacy"
jq --arg tag "$TAG" '.images = ([.images, .pinnedImages] | add | with_entries(.value = "parkio/\(.key):\($tag)"))
  | del(.pinnedImages)' "$manifest_a" > "$legacy"
rollback_state
run "$work/rollback-b" ./scripts/rollback-hosted-beta.sh --manifest "$legacy" --skip-smoke
if [ "$rc" -eq 0 ] && [ "$(calls | grep -c '^TAG ')" -eq 6 ] && ! calls | grep -q '^TAG parkio/web' \
  && grep -q "web keeps its pin ghcr.io/example/parkio/web@" "$work/out"; then
  pass "a rollback to a pre-CL-F12 manifest re-points the built services and keeps the pins"
  # #290 review B1: the record names the pins that run, which the target did not record.
  if [ "$(jq -S . "$work/deploy-state/deployed-manifest.json")" = "$(jq -S --argjson pins "$(plan_pins_json)" '.pinnedImages = $pins' "$legacy")" ]; then
    pass "the rollback records its target's manifest, with the pins that run, as the deployed release's"
  else
    bad "the rollback did not record its target's manifest with the pins that run"
  fi
else
  bad "a rollback to a pre-CL-F12 manifest: exit $rc, calls: $(calls | tr '\n' ';')"
fi

# Refusals happen before anything is re-pointed or started.
refused() { # refused SLUG DESCRIPTION MANIFEST EXPECTED_MESSAGE
  run "$work/rollback-$1" ./scripts/rollback-hosted-beta.sh --manifest "$3" --skip-smoke
  if [ "$rc" -eq 2 ] && ! calls | grep -Eq '^(TAG|UP|BUILD) ' && grep -qF "$4" "$work/out"; then
    pass "rollback refuses $2 before it changes anything"
  else
    bad "rollback $2: exit $rc, calls: $(calls | tr '\n' ';')"
    tail -n 5 "$work/out" >&2
  fi
}
partial="$work/partial/deploy-partial.json"
mkdir -p "$work/partial"
jq 'del(.images["analytics-service"])' "$manifest_a" > "$partial"
rollback_state
refused partial "a target that did not build analytics-service" "$partial" \
  "records '' for analytics-service, not parkio/analytics-service:$TAG"
rollback_state
rm -f "$state/images/$(image_key "parkio/moderation-service:$TAG")"
refused missing "a recorded image that is not present" "$manifest_a" "image not found locally: parkio/moderation-service:$TAG"
rollback_state
rm -f "$state/images/$(image_key "ghcr.io/example/parkio/web@$(digest 5)")"
echo "ghcr.io/example/parkio/web@$(digest 5)" > "$state/pull-fails"
refused pin "a pin that cannot be pulled" "$manifest_a" "cannot pull the pinned web image"

if [ "$failures" -ne 0 ]; then
  echo "=== hosted-beta image plan: $failures FAILED ===" >&2
  exit 1
fi
echo "=== hosted-beta image plan: PASS ==="
