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
if PARKIO_WEB_BAKE="$(realpath --relative-to="$ROOT" "$TMP/bad.env" 2>/dev/null || echo "$TMP/bad.env")" bash "$ROOT/scripts/ci/candidate-web-build-args.sh" >/dev/null 2>&1; then ko "bad bake refused"; else ok "bad bake refused"; fi

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
  printf '{"gate":"%s","high_critical":0,"fixed_high_critical":0}\n' "$5" > "$1/$2/trivy-summary.json"
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

# 6. the workflows parse and the publish job keeps its three gates
python3 - "$ROOT" <<'PY' && ok "workflows parse with the gates in place" || ko "workflows parse with the gates in place"
import sys, yaml
root = sys.argv[1]
ci = yaml.safe_load(open(f"{root}/.github/workflows/candidate-images.yml"))
pub = yaml.safe_load(open(f"{root}/.github/workflows/candidate-publish.yml"))
assert ci["permissions"] == {"contents": "read"}
assert set(ci[True].keys()) == {"workflow_dispatch"}, ci[True].keys()  # dispatch only
assert ci["jobs"]["build"]["steps"][4]["with"]["push"] is False
job = pub["jobs"]["publish"]
assert job["environment"] == "candidate-publication"
assert "PARKIO_CANDIDATE_PUBLISH_APPROVED_SHA" in job["if"]
assert "packages" in job["permissions"] and job["permissions"]["packages"] == "write"
assert pub["permissions"] == {"contents": "read"}
PY

echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ]
