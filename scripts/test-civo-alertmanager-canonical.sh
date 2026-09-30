#!/usr/bin/env bash
# Canonical Civo observability set: Alertmanager is in the production wrapper
# and fail-closed. The shared file list and the Azure profile stay unchanged.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILES="$ROOT/docker/compose.production.files"
OVERLAY="docker/docker-compose.civo-alertmanager.yml"
fail() { echo "FAIL: $*" >&2; exit 1; }

listed="$(grep -vE '^[[:space:]]*#' "$FILES" || true)"
printf '%s\n' "$listed" | grep -q 'waitlist-ops-inbox.yml' && fail "activation overlay is inside compose.production.files"
printf '%s\n' "$listed" | grep -q 'civo-alertmanager.yml' && fail "Civo overlay is inside the shared production file list"
grep -q "$OVERLAY" "$ROOT/scripts/parkio-prod-compose.sh" || fail "parkio-prod-compose.sh does not append the Civo overlay"
grep -q "grep -qx 'docker/docker-compose.azure-hosted-beta.yml'" "$ROOT/scripts/parkio-prod-compose.sh" \
  || fail "wrapper appends the Civo overlay for every file list"
test -f "$ROOT/$OVERLAY" || fail "missing $OVERLAY"

azure="$(awk '/azure-hosted-beta\)/,/;;/' "$ROOT/scripts/lib/deploy-common.sh")"
printf '%s\n' "$azure" | grep -q 'civo-alertmanager' && fail "Azure profile list includes the Civo overlay"
printf '%s\n' "$azure" | grep -q 'PARKIO_DISABLED_SERVICES=(alertmanager loki promtail tempo)' \
  || fail "Azure profile no longer disables alertmanager loki promtail tempo"

ENV_FILE="$ROOT/docker/.env.example"
test -f "$ENV_FILE" || fail "missing synthetic env docker/.env.example"
args=()
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  args+=(-f "$ROOT/$line")
done < "$FILES"
args+=(-f "$ROOT/$OVERLAY")

out="$(mktemp)"
synth="$(mktemp)"
err="$(mktemp)"
trap 'rm -f "$out" "$synth" "$err"' EXIT
cp "$ENV_FILE" "$synth"
# Required interpolations get non-secret placeholders. Values are not printed.
rendered=0
for _ in $(seq 1 40); do
  if docker compose --env-file "$synth" "${args[@]}" config --format json >"$out" 2>"$err"; then
    rendered=1
    break
  fi
  name="$(sed -n 's/.*required variable \([A-Z0-9_]*\) is missing.*/\1/p' "$err" | head -n 1)"
  if [ -z "$name" ]; then
    echo "FAIL: compose render failed before a missing-variable message" >&2
    exit 1
  fi
  printf '%s=example.invalid\n' "$name" >>"$synth"
done
[ "$rendered" -eq 1 ] || fail "compose render still missing required variables"

python3 - "$out" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
services = doc.get("services") or {}
missing = [name for name in ("alertmanager", "prometheus") if name not in services]
if missing:
    raise SystemExit("FAIL: canonical render omitted " + ", ".join(missing))
for name in ("loki", "promtail", "tempo"):
    if name in services:
        raise SystemExit("FAIL: " + name + " is in the default Civo render")
am = services["alertmanager"]
profiles = am.get("profiles") or []
if profiles:
    raise SystemExit("FAIL: alertmanager still has profiles " + ",".join(profiles))
env = am.get("environment") or {}
if isinstance(env, list):
    env = dict(item.split("=", 1) for item in env if "=" in item)
value = str(env.get("PARKIO_ALERT_REQUIRE_RECEIVER", ""))
if value.lower() not in {"true", "1", "yes"}:
    raise SystemExit("FAIL: PARKIO_ALERT_REQUIRE_RECEIVER is not enabled")
print("canonical Civo render: alertmanager present, loki/promtail/tempo absent, receiver required")
PY

# A caller-supplied file list that is not the hosted-beta production set must
# still render. The web-map guard fixtures depend on that.
fixdir="$ROOT/docker/.alerting-acceptance-receipts"
mkdir -p "$fixdir"
cat >"$fixdir/civo-guard-fixture.yml" <<'EOF'
services:
  web:
    image: busybox:1.36
EOF
printf '%s\n' 'docker/.alerting-acceptance-receipts/civo-guard-fixture.yml' >"$fixdir/files.list"
services="$(PARKIO_ENV_FILE="$ENV_FILE" PARKIO_COMPOSE_FILES_LIST="$fixdir/files.list" \
  bash "$ROOT/scripts/parkio-prod-compose.sh" config --services)"
printf '%s\n' "$services" | grep -qx web || fail "custom file list did not render its web service"
printf '%s\n' "$services" | grep -qx alertmanager && fail "custom file list grew an Alertmanager service"
