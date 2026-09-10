#!/usr/bin/env bash
# Resolve invite-production dark and public candidate Compose models and enforce
# profile-aware resource budgets plus host-port safety (PROD-DEPLOY-01B-02).
#
# Usage:
#   ./scripts/assert-invite-production-edge-resource-budget.sh \
#     [--env-file FILE] [--evidence FILE]
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-docker/.env.invite-production.example}"
EVIDENCE=""
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"
CANDIDATE_SHA=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --evidence) EVIDENCE="${2:-}"; shift 2 ;;
  --candidate-sha) CANDIDATE_SHA="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# shellcheck source=lib/deploy-common.sh
source "$ROOT/scripts/lib/deploy-common.sh"
cd "$ROOT"

[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

export PARKIO_DEPLOYMENT_PROFILE=invite-production
export PARKIO_IMAGE_TAG="${PARKIO_IMAGE_TAG:-sha-edge-resource}"
export PARKIO_GIT_SHA="${PARKIO_GIT_SHA:-edge-resource}"
export PARKIO_IMAGE_CREATED="${PARKIO_IMAGE_CREATED:-1970-01-01T00:00:00Z}"
CANDIDATE_SHA="${CANDIDATE_SHA:-$(parkio_git_sha)}"

assert_profile_budget() {
  local profile="$1"
  local runtime_list="$2"
  local evidence_file="$3"

  local args=(
    --profile "$profile"
    --runtime-services-file "$runtime_list"
  )
  if [ -n "$evidence_file" ]; then
    args+=(--evidence "$evidence_file")
  fi

  parkio_compose "$ENV_FILE" config --format json \
    | "$NODE_BINARY" "$ROOT/scripts/lib/assert-compose-resource-budget.mjs" "${args[@]}"
}

assert_compose_ports() {
  local mode="$1"
  local compose_files="$2"
  local acme_authorized="$3"

  parkio_compose "$ENV_FILE" config --format json \
    | "$NODE_BINARY" "$ROOT/scripts/lib/assert-invite-production-compose-ports.mjs" \
        --mode "$mode" \
        --compose-files "$compose_files" \
        --acme-authorized "$acme_authorized"
}

dark_runtime="$(mktemp)"
public_runtime="$(mktemp)"
public_candidate_runtime="$(mktemp)"
dark_evidence="$(mktemp)"
public_evidence="$(mktemp)"
staged_evidence="$(mktemp)"
cleanup() {
  rm -f -- "$dark_runtime" "$public_runtime" "$public_candidate_runtime" \
    "$dark_evidence" "$public_evidence" "$staged_evidence"
}
trap cleanup EXIT HUP INT TERM

echo "=== invite-production dark resolved resource model ==="
export PARKIO_INVITE_EDGE_MODE=dark
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
dark_compose_files="$PARKIO_COMPOSE_FILES"
printf '%s\n' "${PARKIO_RUNTIME_SERVICES[@]}" > "$dark_runtime"
assert_profile_budget invite-production "$dark_runtime" "$dark_evidence"
assert_compose_ports dark "$dark_compose_files" false

echo "=== invite-production public candidate resolved resource model ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=true
parkio_configure_deployment_profile "$ENV_FILE"
public_candidate_compose_files="$PARKIO_COMPOSE_FILES"
printf '%s\n' "${PARKIO_RUNTIME_SERVICES[@]}" > "$public_candidate_runtime"
assert_profile_budget invite-production-public "$public_candidate_runtime" "$public_evidence"
assert_compose_ports public-candidate "$public_candidate_compose_files" true

echo "=== invite-production public staged resolved resource model ==="
export PARKIO_INVITE_EDGE_MODE=public
export PARKIO_INVITE_ACME_AUTHORIZED=false
parkio_configure_deployment_profile "$ENV_FILE"
public_staged_compose_files="$PARKIO_COMPOSE_FILES"
printf '%s\n' "${PARKIO_INVITE_RUNTIME_SERVICES[@]}" > "$public_runtime"
assert_profile_budget invite-production "$public_runtime" "$staged_evidence"
assert_compose_ports public-staged "$public_staged_compose_files" false

if printf '%s' "$public_candidate_compose_files" | grep -q invite-dark; then
  echo "ERROR: public candidate compose must not include invite-dark overlay" >&2
  exit 4
fi
if ! printf '%s' "$public_candidate_compose_files" | grep -q invite-public; then
  echo "ERROR: public candidate compose must include invite-public overlay" >&2
  exit 4
fi
if ! printf '%s' "$public_staged_compose_files" | grep -q invite-public-staged; then
  echo "ERROR: public staged compose must include invite-public-staged overlay" >&2
  exit 4
fi

acme_authorized="$(parkio_invite_acme_authorized_from_env "$ENV_FILE")" || exit 2
edge_mode="$(parkio_invite_edge_mode_from_env "$ENV_FILE")" || exit 2

if [ -n "$EVIDENCE" ]; then
  mkdir -p "$(dirname "$EVIDENCE")"
  "$NODE_BINARY" - "$EVIDENCE" "$CANDIDATE_SHA" "$dark_evidence" "$public_evidence" "$staged_evidence" \
    "$dark_compose_files" "$public_candidate_compose_files" "$public_staged_compose_files" \
    "$edge_mode" "$acme_authorized" <<'NODE'
const fs = require('node:fs');

const [
  output,
  candidateSha,
  darkPath,
  publicPath,
  stagedPath,
  darkCompose,
  publicCompose,
  stagedCompose,
  templateEdgeMode,
  templateAcme,
] = process.argv.slice(2);

const dark = JSON.parse(fs.readFileSync(darkPath, 'utf8'));
const pub = JSON.parse(fs.readFileSync(publicPath, 'utf8'));
const staged = JSON.parse(fs.readFileSync(stagedPath, 'utf8'));

const artifact = {
  schemaVersion: 1,
  candidateSha,
  templateEdgeMode,
  templateAcmeAuthorized: templateAcme,
  dark: {
    edgeMode: 'dark',
    acmeAuthorized: false,
    composeFiles: darkCompose.trim(),
    configuredMemoryMiB: dark.configuredMemoryMiB,
    continuousRuntimeMemoryMiB: dark.continuousRuntimeMemoryMiB,
    resourceCeilingMiB: dark.resourceCeilingMiB,
    configuredHeadroomMiB: dark.configuredHeadroomMiB,
    continuousServiceCount: dark.expectedContinuousServiceCount,
    caddyState: 'absent',
    gatewayHostBinding: '127.0.0.1:8080',
    tempoMemoryMiB: dark.tempoMemoryMiB,
    clamavMemoryMiB: dark.clamavMemoryMiB,
    withinCeiling: dark.resourceBudgetWithinCeiling,
  },
  publicCandidate: {
    edgeMode: 'public',
    acmeAuthorized: true,
    composeFiles: publicCompose.trim(),
    configuredMemoryMiB: pub.configuredMemoryMiB,
    continuousRuntimeMemoryMiB: pub.continuousRuntimeMemoryMiB,
    resourceCeilingMiB: pub.resourceCeilingMiB,
    configuredHeadroomMiB: pub.configuredHeadroomMiB,
    continuousServiceCount: pub.expectedContinuousServiceCount,
    caddyMemoryMiB: 256,
    caddyState: 'continuous',
    gatewayHostBinding: 'none',
    tempoMemoryMiB: pub.tempoMemoryMiB,
    clamavMemoryMiB: pub.clamavMemoryMiB,
    withinCeiling: pub.resourceBudgetWithinCeiling,
  },
  publicStaged: {
    edgeMode: 'public',
    acmeAuthorized: false,
    composeFiles: stagedCompose.trim(),
    configuredMemoryMiB: staged.configuredMemoryMiB,
    continuousRuntimeMemoryMiB: staged.continuousRuntimeMemoryMiB,
    resourceCeilingMiB: staged.resourceCeilingMiB,
    configuredHeadroomMiB: staged.configuredHeadroomMiB,
    continuousServiceCount: staged.expectedContinuousServiceCount,
    caddyState: 'staged_disabled',
    gatewayHostBinding: '127.0.0.1:8080',
    tempoMemoryMiB: staged.tempoMemoryMiB,
    clamavMemoryMiB: staged.clamavMemoryMiB,
    withinCeiling: staged.resourceBudgetWithinCeiling,
  },
};

fs.mkdirSync(require('node:path').dirname(output), { recursive: true });
fs.writeFileSync(output, `${JSON.stringify(artifact, null, 2)}\n`, { mode: 0o600 });
NODE
fi

echo "invite_production_edge_resource_budget=PASS"
