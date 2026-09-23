#!/usr/bin/env bash
# PR #87 only: build and test the exact image on an isolated Actions runner.
set -euo pipefail

source_sha="$(git rev-parse HEAD)"
test "$source_sha" = "${EXPECTED_SOURCE_SHA:?expected PR head required}"
test "$(uname -m)" = x86_64
out="${RUNNER_TEMP:?}/web-build-security-01"
mkdir -p "$out"

set -a
# shellcheck disable=SC1091
source docker/web-hosted-beta.release-bake.env
set +a
VITE_MAPTILER_KEY=ci-web-build-security-synthetic
created="$(git show -s --format=%cI HEAD)"
builder_tag="parkio-web-build-security-01:builder-${source_sha:0:12}"
runtime_tag="parkio-web-build-security-01:runtime-${source_sha:0:12}"
build_args=(
  --build-arg "VITE_API_BASE_URL=$VITE_API_BASE_URL"
  --build-arg "VITE_APP_ENV=$VITE_APP_ENV"
  --build-arg "VITE_MAPTILER_KEY=$VITE_MAPTILER_KEY"
  --build-arg "VITE_PUBLIC_EXPLORE_ENABLED=$VITE_PUBLIC_EXPLORE_ENABLED"
  --build-arg "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=$VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED"
  --build-arg "VITE_SMART_PARKING_ASSISTANT_ENABLED=$VITE_SMART_PARKING_ASSISTANT_ENABLED"
  --build-arg "VITE_SMART_RETURN_ENABLED=$VITE_SMART_RETURN_ENABLED"
  --build-arg "VITE_WAITLIST_INTAKE_MODE=$VITE_WAITLIST_INTAKE_MODE"
  --build-arg "VITE_MAPTILER_STYLE=$VITE_MAPTILER_STYLE"
  --build-arg "VITE_FRONTEND_ERROR_REPORTING=$VITE_FRONTEND_ERROR_REPORTING"
  --build-arg VITE_REGISTRATION_MODE=closed
  --build-arg VERIFY_REQUIRE_PUBLIC_EXPLORE=true
  --build-arg VERIFY_REQUIRE_MUNICIPAL=true
)

cat > "$out/build-profile.txt" <<EOF
source_sha=$source_sha
frontend_tree=$(git rev-parse HEAD:frontend)
profile=docker/web-hosted-beta.release-bake.env
profile_sha256=$(sha256sum docker/web-hosted-beta.release-bake.env | cut -d' ' -f1)
platform=linux/amd64
VITE_APP_ENV=$VITE_APP_ENV
VITE_API_BASE_URL=$VITE_API_BASE_URL
VITE_MAPTILER_KEY=synthetic-non-production-value
VITE_PUBLIC_EXPLORE_ENABLED=$VITE_PUBLIC_EXPLORE_ENABLED
VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=$VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED
VITE_SMART_PARKING_ASSISTANT_ENABLED=$VITE_SMART_PARKING_ASSISTANT_ENABLED
VITE_SMART_RETURN_ENABLED=$VITE_SMART_RETURN_ENABLED
VITE_WAITLIST_INTAKE_MODE=$VITE_WAITLIST_INTAKE_MODE
VITE_MAPTILER_STYLE=$VITE_MAPTILER_STYLE
VITE_FRONTEND_ERROR_REPORTING=$VITE_FRONTEND_ERROR_REPORTING
VITE_REGISTRATION_MODE=closed
VERIFY_REQUIRE_PUBLIC_EXPLORE=true
VERIFY_REQUIRE_MUNICIPAL=true
IMAGE_REVISION=$source_sha
IMAGE_CREATED=$created
EOF

docker build --platform linux/amd64 --target build -f frontend/apps/web/Dockerfile \
  -t "$builder_tag" "${build_args[@]}" .
docker build --platform linux/amd64 --target runtime -f frontend/apps/web/Dockerfile \
  -t "$runtime_tag" "${build_args[@]}" \
  --build-arg "IMAGE_REVISION=$source_sha" --build-arg "IMAGE_CREATED=$created" .

builder_id="$(docker image inspect --format '{{.Id}}' "$builder_tag")"
runtime_id="$(docker image inspect --format '{{.Id}}' "$runtime_tag")"
test "$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$runtime_id")" = linux/amd64
test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$runtime_id")" = "$source_sha"
docker image inspect "$builder_id" > "$out/builder-image-inspect.json"
docker image inspect "$runtime_id" > "$out/runtime-image-inspect.json"
printf 'builder_image_id=%s\nruntime_image_id=%s\n' "$builder_id" "$runtime_id" | tee "$out/image-identities.txt"

# One fresh DB snapshot for both installed-stage scans. No ignorefile, severity
# filter, ignore-unfixed option, or package removal is used.
trivy_cache="${RUNNER_TEMP}/web-build-security-01-trivy-cache"
mkdir -p "$trivy_cache"
trivy=(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock
  -v "$trivy_cache:/root/.cache/trivy" -v "$out:/evidence"
  aquasec/trivy:0.74.0)
"${trivy[@]}" image --download-db-only --cache-dir /root/.cache/trivy
"${trivy[@]}" --version > "$out/trivy-version.txt"
cp "$trivy_cache/db/metadata.json" "$out/trivy-db-metadata.json"
"${trivy[@]}" image --skip-db-update --scanners vuln --list-all-pkgs \
  --format json --output /evidence/ci-builder-scan.json "$builder_tag"
"${trivy[@]}" image --skip-db-update --scanners vuln --list-all-pkgs \
  --format json --output /evidence/ci-runtime-scan.json "$runtime_tag"
python3 agent-tools/parkio-web-build-image-security-01/summarize-ci-scans.py "$out"

container="parkio-web-build-security-01-ci-${GITHUB_RUN_ID:?}"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --name "$container" -p 127.0.0.1:18207:80 "$runtime_id" > "$out/container-id.txt"
test "$(docker inspect --format '{{.Image}}' "$container")" = "$runtime_id"
healthy=false
for _ in $(seq 1 45); do
  status="$(docker inspect --format '{{.State.Health.Status}}' "$container")"
  if [ "$status" = healthy ]; then healthy=true; break; fi
  if [ "$status" = unhealthy ]; then break; fi
  sleep 2
done
test "$healthy" = true
docker inspect "$container" > "$out/container-inspect.json"
docker exec "$container" sh -c 'test "$(stat -c %a /usr/share/nginx/html)" = 755 && test "$(stat -c %a /usr/share/nginx/html/index.html)" = 644 && test ! -e /workspace && ! command -v node && ! command -v npm && ! command -v pnpm'
css_asset="$(docker exec "$container" sh -c "find /usr/share/nginx/html/assets -name '*.css' -print -quit" | sed 's#^/usr/share/nginx/html##')"
test -n "$css_asset"
python3 agent-tools/parkio-web-build-image-security-01/check-served-image.py \
  http://127.0.0.1:18207 "$css_asset" | tee "$out/http-acceptance.txt"

# Existing production image browser smoke starts this same immutable image ID
# in a second task-owned container. The opt-in route blocks real API/provider I/O.
SMOKE_MOCK_EXTERNAL=1 node frontend/apps/web/scripts/smoke-image.mjs \
  --image "$runtime_id" --app-env "$VITE_APP_ENV" --port 18208 \
  | tee "$out/browser-smoke.txt"

docker save "$runtime_tag" | gzip -1 > "$out/tested-runtime-image.tar.gz"
sha256sum "$out/tested-runtime-image.tar.gz" > "$out/tested-runtime-image.tar.gz.sha256"
printf 'source=%s\nbuilder=%s\nruntime=%s\nhealth=healthy\nhttp=PASS\nbrowser=PASS\n' \
  "$source_sha" "$builder_id" "$runtime_id" | tee "$out/acceptance-result.txt"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    printf '## Web build image acceptance\n\n'
    printf 'Source: `%s`  \nBuilder: `%s`  \nRuntime: `%s`  \n' "$source_sha" "$builder_id" "$runtime_id"
    printf 'Health, HTTP, browser smoke: **PASS**. See attached scan JSON and image archive.\n'
    cat "$out/scan-summary.md"
  } >> "$GITHUB_STEP_SUMMARY"
fi
