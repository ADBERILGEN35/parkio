#!/usr/bin/env bash
# Stub-driven: TARGET_OVERRIDE works when default target path is absent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HELPER="$ROOT/scripts/ops/r01-verify-real-e2e-accounts-checksum-enforced.sh"
TARGET_SRC="$ROOT/scripts/ops/verify-real-e2e-accounts.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/scripts/ops" "$TMP/docker" "$TMP/bin"
: >"$TMP/docker/.env.azure-hosted-beta"
cp -a "$TARGET_SRC" "$TMP/scripts/ops/verify-real-e2e-accounts.sh.reviewed-r01"

cat >"$TMP/bin/docker" <<'D'
#!/usr/bin/env bash
if [ "${1:-}" = "ps" ]; then exit 0; fi
if [ "${1:-}" = "inspect" ]; then exit 1; fi
if [ "${1:-}" = "compose" ]; then
  if [[ "$*" == *psql* ]]; then
    echo "email | status | created_utc | updated_utc | roles | active_refresh_tokens"
    exit 0
  fi
fi
exit 0
D
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH"

set +e
TARGET_OVERRIDE="$TMP/scripts/ops/verify-real-e2e-accounts.sh.reviewed-r01" \
  PARKIO_ROOT="$TMP" \
  PARKIO_ENV_FILE=docker/.env.azure-hosted-beta \
  bash "$HELPER"
ec=$?
set -e
echo "override_run_exit=$ec"
if [ "$ec" -ne 0 ] && [ "$ec" -ne 10 ]; then
  echo "FAIL: helper did not reach verify (exit $ec)" >&2
  exit 1
fi

set +e
PARKIO_ROOT="$TMP" PARKIO_ENV_FILE=docker/.env.azure-hosted-beta bash "$HELPER"
ec2=$?
set -e
echo "absent_default_exit=$ec2"
if [ "$ec2" -ne 2 ]; then
  echo "FAIL: expected exit 2 when default target absent" >&2
  exit 1
fi
echo "PASS: TARGET_OVERRIDE path works; absent default fails closed"
