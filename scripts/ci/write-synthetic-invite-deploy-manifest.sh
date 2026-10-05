#!/usr/bin/env bash
# Write a realistic, synthetic invite-production deploy manifest with this checkout's own manifest
# writer (parkio_write_manifest) for an env file. Its composeFiles, runtime and disabled services,
# migrationVersions and feature configuration are what a deploy from this checkout with that env
# records. Rollback compatibility-guard acceptance uses it for its positive (compatible) path.
# Nothing is built, pulled or started; image digests stay null when the images are absent.
#
#   write-synthetic-invite-deploy-manifest.sh --env-file ENV --out FILE [--git-sha SHA]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ENV_FILE=""
OUT=""
GIT_SHA="5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e"
usage() { echo "usage: $0 --env-file ENV --out FILE [--git-sha SHA]" >&2; exit 2; }
while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --git-sha) GIT_SHA="${2:-}"; shift 2 ;;
    *) usage ;;
  esac
done
{ [ -f "$ENV_FILE" ] && [ -n "$OUT" ]; } || usage
[[ "$GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "ERROR: --git-sha must be 40 lowercase hex characters" >&2; exit 2; }
ENV_FILE="$(cd "$(dirname "$ENV_FILE")" && pwd)/$(basename "$ENV_FILE")"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac

cd "$ROOT"
# shellcheck source=../lib/deploy-common.sh
source scripts/lib/deploy-common.sh
PARKIO_DEPLOYMENT_PROFILE=invite-production
parkio_configure_deployment_profile "$ENV_FILE" >/dev/null
created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export PARKIO_IMAGE_TAG="sha-$GIT_SHA" PARKIO_GIT_SHA="$GIT_SHA" PARKIO_IMAGE_CREATED="$created" \
  PARKIO_IMAGE_VERSION=synthetic
parkio_write_manifest "$OUT" deploy synthetic-rollback-acceptance "$ENV_FILE" \
  "sha-$GIT_SHA" "$GIT_SHA" api "$created" synthetic "" "" >/dev/null
jq -e '.action == "deploy" and .deploymentProfile == "invite-production" and (.composeFiles | length > 0)' \
  "$OUT" >/dev/null || { echo "ERROR: the synthetic manifest is not an invite-production deploy manifest" >&2; exit 1; }
echo "synthetic invite-production deploy manifest: $OUT"
