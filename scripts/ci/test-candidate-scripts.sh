#!/usr/bin/env bash
# Checks for the candidate-images helper scripts (no Docker, no network).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
pass=0; fail=0
ok() { echo "PASS: $1"; pass=$((pass+1)); }
ko() { echo "FAIL: $1"; fail=$((fail+1)); }

# 1. syntax
bash -n "$ROOT/scripts/ci/candidate-stack-checks.sh" && bash -n "$ROOT/scripts/ci/candidate-web-build-args.sh" \
  && python3 -m py_compile "$ROOT/scripts/ci/candidate-stack-env.py" "$ROOT/scripts/ci/candidate-manifest.py" \
     "$ROOT/scripts/ci/candidate-acceptance/run-acceptance.py" "$ROOT/scripts/ci/candidate-acceptance/stubs.py" \
  && ok "scripts parse" || ko "scripts parse"
rm -rf "$ROOT/scripts/ci/candidate-acceptance/__pycache__" "$ROOT/scripts/ci/__pycache__"

# 2. web build args: the bake's values with release.yml's gates, no MapTiler key
args="$(bash "$ROOT/scripts/ci/candidate-web-build-args.sh")"
grep -q '^VITE_APP_ENV=hosted-beta$' <<<"$args" && grep -q '^VERIFY_REQUIRE_PUBLIC_EXPLORE=true$' <<<"$args" \
  && grep -q '^VERIFY_REQUIRE_MUNICIPAL=true$' <<<"$args" && ! grep -q 'MAPTILER_KEY' <<<"$args" \
  && [ "$(wc -l <<<"$args")" -eq 11 ] && ok "web build args from the bake" || ko "web build args from the bake"
printf 'VITE_APP_ENV=hosted-beta\nVITE_API_BASE_URL=https://api.parkio.dev/api/v1\nVITE_PUBLIC_EXPLORE_ENABLED=true\nVITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=maybe\n' > "$TMP/bad.env"
if PARKIO_WEB_BAKE="$TMP/bad.env" bash "$ROOT/scripts/ci/candidate-web-build-args.sh" >/dev/null 2>&1; then ko "bad bake refused"; else ok "bad bake refused"; fi

# 3. stack env: overrides applied, candidate tag in place, no example secret kept for the keys we override
printf -- '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n' > "$TMP/jwt.pem"
python3 "$ROOT/scripts/ci/candidate-stack-env.py" --example "$ROOT/docker/.env.hosted-beta.example" --image-tag candidate-abc123def456 \
  --git-sha 0000000000000000000000000000000000000000 --created 2026-10-07T00:00:00Z --jwt-pem "$TMP/jwt.pem" --out "$TMP/env" >/dev/null
grep -q '^PARKIO_IMAGE_TAG=candidate-abc123def456$' "$TMP/env" && grep -q '^PARKIO_DOMAIN=api.candidate.localhost$' "$TMP/env" \
  && grep -q '^PARKIO_JWT_PRIVATE_KEY_PEM="-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----"$' "$TMP/env" \
  && [ "$(grep -c '^PARKIO_IMAGE_TAG=' "$TMP/env")" -eq 1 ] && ok "stack env overrides" || ko "stack env overrides"

# 4. manifest: accepted and rejected cases
mk() { # dir service id revision gate
  mkdir -p "$1/$2"; cat > "$1/$2/inspect.json" <<JSON
[{"Id":"$3","RepoTags":["parkio/x:candidate-abc123def456"],"Os":"linux","Architecture":"amd64","Size":1,"Created":"2026-10-07T00:00:00Z","Config":{"Labels":{"org.opencontainers.image.revision":"$4","org.opencontainers.image.version":"candidate-abc123def456"}},"RootFS":{"Layers":["sha256:a"]}}]
JSON
  printf '{"gate":"%s","critical_gate":"%s","library_gate":"pass","fixed_critical":0,"fixed_high":0}\n' "$5" "$5" > "$1/$2/trivy-summary.json"
  printf '%s  image.tar.gz\n' "$(printf 'x' | sha256sum | cut -d' ' -f1)" > "$1/$2/SHA256SUMS"
}
SHA=1111111111111111111111111111111111111111
A="$TMP/good"; mk "$A" candidate-image-auth-service-abc123def456-meta sha256:aaa "$SHA" pass
for n in stack auth-gateway web; do mkdir -p "$A/candidate-acceptance-$n"; echo '{"status":"pass"}' > "$A/candidate-acceptance-$n/result.json"; done
python3 "$ROOT/scripts/ci/candidate-manifest.py" --source-sha "$SHA" --artifacts "$A" --out "$TMP/good-out" --acceptance-required >/dev/null \
  && grep -q '"verdict": "accepted-candidate"' "$TMP/good-out/candidate-manifest.json" && grep -q '"service": "auth-service"' "$TMP/good-out/candidate-manifest.json" \
  && ok "manifest accepts a clean candidate" || ko "manifest accepts a clean candidate"
B="$TMP/bad"; mk "$B" candidate-image-auth-service-abc123def456-meta sha256:aaa "$SHA" fail
mkdir -p "$B/candidate-acceptance-stack"; echo '{"status":"fail","detail":"x"}' > "$B/candidate-acceptance-stack/result.json"
if python3 "$ROOT/scripts/ci/candidate-manifest.py" --source-sha "$SHA" --artifacts "$B" --out "$TMP/bad-out" --acceptance-required >/dev/null; then ko "manifest rejects gate/acceptance failures"; else
  grep -q '"verdict": "not-accepted"' "$TMP/bad-out/candidate-manifest.json" && grep -q 'Trivy gate fail' "$TMP/bad-out/candidate-manifest.json" \
    && grep -q 'acceptance-web: absent' "$TMP/bad-out/candidate-manifest.json" && ok "manifest rejects gate/acceptance failures" || ko "manifest rejects gate/acceptance failures"; fi
C="$TMP/rev"; mk "$C" candidate-image-web-abc123def456-meta sha256:bbb 2222222222222222222222222222222222222222 pass
if python3 "$ROOT/scripts/ci/candidate-manifest.py" --source-sha "$SHA" --artifacts "$C" --out "$TMP/rev-out" >/dev/null; then ko "manifest rejects a foreign revision label"; else ok "manifest rejects a foreign revision label"; fi

# 5. the acceptance compose renders with the documented variables and binds loopback only
if command -v docker >/dev/null 2>&1 && AUTH_IMAGE=parkio/auth-service:x GATEWAY_IMAGE=parkio/gateway-service:x PARKIO_JWT_PRIVATE_KEY_PEM=k \
   docker compose -f "$ROOT/scripts/ci/candidate-acceptance/docker-compose.yml" config > "$TMP/acc.yml" 2>/dev/null; then
  grep -q 'parkio/auth-service:x' "$TMP/acc.yml" && [ "$(grep -c 'host_ip: 127.0.0.1' "$TMP/acc.yml")" -eq 3 ] && ok "acceptance compose renders, loopback only" || ko "acceptance compose renders, loopback only"
else
  echo "SKIP: docker compose not available for the acceptance compose render"
fi

# 6. the workflows and the composite action parse; the publish job keeps its gates; builds never push
python3 - "$ROOT" <<'PY' && ok "workflows parse with the gates in place" || ko "workflows parse with the gates in place"
import json, sys, yaml
root = sys.argv[1]
ci = yaml.safe_load(open(f"{root}/.github/workflows/candidate-images.yml"))
pub = yaml.safe_load(open(f"{root}/.github/workflows/candidate-publish.yml"))
act = yaml.safe_load(open(f"{root}/.github/actions/candidate-image-build/action.yml"))
assert ci["permissions"] == {"contents": "read"}
assert set(ci[True].keys()) == {"workflow_dispatch", "pull_request"}, ci[True].keys()
for name in ("resolve", "build", "build_web", "acceptance-stack", "acceptance-auth-gateway", "acceptance-web", "manifest"):
    assert "workflow_dispatch" in ci["jobs"][name]["if"], name   # dispatch-only jobs
assert "if" not in ci["jobs"]["script-tests"]                      # runs on pull requests too
assert ci["jobs"]["build_web"]["environment"] == "release"
assert "environment" not in ci["jobs"]["build"]
build_step = [st for st in act["runs"]["steps"] if "build-push-action" in str(st.get("uses"))][0]
assert build_step["with"]["push"] is False and build_step["with"]["load"] is True
scan = [st for st in act["runs"]["steps"] if st.get("name", "").startswith("Scan")][0]["run"]
assert "--severity CRITICAL --ignore-unfixed" in scan and "--pkg-types library --severity HIGH,CRITICAL --ignore-unfixed" in scan
assert '--ignorefile "/work/${SRC}/.trivyignore.yaml"' in scan       # the source revision's exceptions
job = pub["jobs"]["publish"]
assert job["environment"] == "candidate-publication"
assert "PARKIO_CANDIDATE_PUBLISH_APPROVED_SHA" in job["if"]
assert job["permissions"]["packages"] == "write" and pub["permissions"] == {"contents": "read"}
download = [st for st in job["steps"] if st.get("name", "").startswith("Download")][0]["run"]
for needle in ('.github/workflows/candidate-images.yml', '"workflow_dispatch"', '"api"', '"success"', "merge-base --is-ancestor",
               "acceptance-stack", "acceptance-auth-gateway", "acceptance-web"):
    assert needle in download, needle                                # accepted candidate run, on api, acceptance passed
text = open(f"{root}/.github/workflows/candidate-images.yml").read()
assert "path: tools" not in text and "tools/" not in text            # no checkout into a tracked directory
assert "path: source" in text and "source_dir: source" in text
assert "source_dir" in act["inputs"] and act["runs"]["steps"][2]["with"]["context"] == "${{ inputs.source_dir }}"
policy = json.load(open(f"{root}/.github/ci-gate-policy.json"))
skips = {e["job"] for e in policy["allowed_skips"] if e["workflow"] == "candidate-images.yml"}
names = {ci["jobs"][j]["name"] for j in ci["jobs"] if j != "script-tests"}
assert skips == names, skips ^ names                                 # every dispatch-only job is an allowed skip
PY

# 7. manifest: a missing expected service and a missing scan summary are refused
E="$TMP/expect"; mk "$E" candidate-image-auth-service-abc123def456-meta sha256:aaa "$SHA" pass
if python3 "$ROOT/scripts/ci/candidate-manifest.py" --source-sha "$SHA" --artifacts "$E" --out "$TMP/expect-out" --expect-services auth-service,web >/dev/null; then ko "manifest requires every expected service"; else
  grep -q 'web: no identity record' "$TMP/expect-out/candidate-manifest.json" && ok "manifest requires every expected service" || ko "manifest requires every expected service"; fi
rm -f "$E/candidate-image-auth-service-abc123def456-meta/trivy-summary.json"
if python3 "$ROOT/scripts/ci/candidate-manifest.py" --source-sha "$SHA" --artifacts "$E" --out "$TMP/expect-out2" --expect-services auth-service >/dev/null; then ko "manifest requires a scan summary"; else ok "manifest requires a scan summary"; fi

# 8. web build args read the bake file given by absolute path (the source checkout's), not their own
mkdir -p "$TMP/src/docker"; cp "$ROOT/docker/web-hosted-beta.release-bake.env" "$TMP/src/docker/web-hosted-beta.release-bake.env"
sed -i 's/^VITE_MAPTILER_STYLE=.*/VITE_MAPTILER_STYLE=source-specific-style/' "$TMP/src/docker/web-hosted-beta.release-bake.env"
grep -q '^VITE_MAPTILER_STYLE=' "$TMP/src/docker/web-hosted-beta.release-bake.env" || echo 'VITE_MAPTILER_STYLE=source-specific-style' >> "$TMP/src/docker/web-hosted-beta.release-bake.env"
if PARKIO_WEB_BAKE="$TMP/src/docker/web-hosted-beta.release-bake.env" bash "$ROOT/scripts/ci/candidate-web-build-args.sh" | grep -q '^VITE_MAPTILER_STYLE=source-specific-style$'; then
  ok "web build args use the given (source) bake file"; else ko "web build args use the given (source) bake file"; fi
if PARKIO_WEB_BAKE="$TMP/missing.env" bash "$ROOT/scripts/ci/candidate-web-build-args.sh" >/dev/null 2>&1; then ko "missing bake file refused"; else ok "missing bake file refused"; fi

# 9. stack checks against a fake docker: a healthy stack passes with exactly 8 checks; one wrong answer fails
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
# Minimal docker stand-in for candidate-stack-checks.sh: every service exists and is healthy,
# and each probed URL answers what a correct stack answers (FAKE_BREAK names one URL to break).
args="$*"
if [ "$1" = "inspect" ]; then echo healthy; exit 0; fi
case "$args" in
  *" ps -q "*) echo cid; exit 0 ;;
  *" ps"*) exit 0 ;;
esac
if [[ "$args" == *" exec -T "* ]]; then
  if [[ "$args" == *" cat /tmp/"* ]]; then
    case "$args" in
      *direct-parking-gateway-auth-required.json*) echo '{"code":"GATEWAY_AUTH_REQUIRED"}' ;;
      *gateway-traversal-400.json*) echo '{"code":"INVALID_REQUEST_PATH"}' ;;
      *) echo '{}' ;;
    esac
    exit 0
  fi
  url="${!#}"
  if [[ "$args" == *"-w %{http_code}"* ]]; then
    code=200
    case "$url" in
      *8083*nearby*) code=401 ;;
      *spots/nearby*|*geocode/reverse*) code=401 ;;
      *users/../overview*|*%2e%2e*) code=400 ;;
      *jwks.json) code=200 ;;
    esac
    if [ -n "${FAKE_BREAK:-}" ] && [[ "$url" == *"$FAKE_BREAK"* ]]; then code=500; fi
    printf '%s' "$code"; exit 0
  fi
  echo '{"status":"UP"}'; exit 0
fi
exit 0
FAKE
chmod +x "$TMP/bin/docker"
if PATH="$TMP/bin:$PATH" COMPOSE_ENV_FILE=x COMPOSE_FILES="-f a.yml" bash "$ROOT/scripts/ci/candidate-stack-checks.sh" "$TMP/stack-ok" >/dev/null 2>&1 \
   && [ "$(grep -c '^PASS' "$TMP/stack-ok/checks.tsv")" -eq 8 ]; then ok "stack checks pass a healthy stack with 8 checks"; else ko "stack checks pass a healthy stack with 8 checks"; fi
if PATH="$TMP/bin:$PATH" FAKE_BREAK=geocode COMPOSE_ENV_FILE=x COMPOSE_FILES="-f a.yml" bash "$ROOT/scripts/ci/candidate-stack-checks.sh" "$TMP/stack-bad" >/dev/null 2>&1; then
  ko "stack checks fail on one wrong answer"; else grep -q '^FAIL.gateway-geocoding-401' "$TMP/stack-bad/checks.tsv" && ok "stack checks fail on one wrong answer" || ko "stack checks fail on one wrong answer"; fi

echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
