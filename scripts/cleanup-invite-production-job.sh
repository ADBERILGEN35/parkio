#!/usr/bin/env bash
# Remove all per-job invite-production source, Azure token cache, and env material.

set -euo pipefail

SOURCE_DIR="${PARKIO_SOURCE_DIR:-}"
ENV_FILE="${PARKIO_ENV_FILE:-}"
AZURE_DIR="${AZURE_CONFIG_DIR:-}"
WORKSPACE_ROOT="${GITHUB_WORKSPACE:-}"
SECURE_TMP_ROOT="${PARKIO_SECURE_TMP_ROOT:-/dev/shm}"

fail() { echo "ERROR: $*" >&2; exit 2; }

[ -n "$WORKSPACE_ROOT" ] || fail "GITHUB_WORKSPACE is required"
[ "$WORKSPACE_ROOT" != "/" ] || fail "refusing workspace root /"
[ -n "$SOURCE_DIR" ] || fail "PARKIO_SOURCE_DIR is required"
[ -n "$ENV_FILE" ] || fail "PARKIO_ENV_FILE is required"
[ -n "$AZURE_DIR" ] || fail "AZURE_CONFIG_DIR is required"

case "$SOURCE_DIR" in
  "$WORKSPACE_ROOT"/source-[0-9]*-[0-9]*) ;;
  *) fail "source directory is outside the per-run workspace pattern" ;;
esac
case "$ENV_FILE" in
  "$SECURE_TMP_ROOT"/parkio-invite-production-[0-9]*-[0-9]*.env) ;;
  *) fail "env file is outside the per-run secure-temp pattern" ;;
esac
case "$AZURE_DIR" in
  "$SECURE_TMP_ROOT"/parkio-azure-[0-9]*-[0-9]*) ;;
  *) fail "Azure config is outside the per-run secure-temp pattern" ;;
esac

rm -f -- "$ENV_FILE"
rm -rf -- "$AZURE_DIR"
if [ -d "$SOURCE_DIR" ]; then
  find "$SOURCE_DIR" -type f \( \
    -name '.env.invite-production' -o \
    -name 'compose-config.rendered.yml' -o \
    -name 'compose-config.rendered.yaml' \
  \) -delete
  rm -rf -- "$SOURCE_DIR"
fi

[ ! -e "$ENV_FILE" ] || fail "env cleanup failed"
[ ! -e "$AZURE_DIR" ] || fail "Azure token-cache cleanup failed"
[ ! -e "$SOURCE_DIR" ] || fail "per-run source cleanup failed"

echo "Invite-production job cleanup passed."
