#!/usr/bin/env bash
# Fail closed on every host executable required by the production runner path.
# This script never installs packages and never reads or prints production env.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

fail() {
  echo "ERROR: $*" >&2
  exit 3
}

required=(
  bash git docker python3 jq openssl curl timeout az getent
  awk sed grep cut tr find stat install date mktemp tee
)
missing=()
for tool in "${required[@]}"; do
  command -v "$tool" >/dev/null 2>&1 || missing+=("$tool")
done
if [ "${#missing[@]}" -ne 0 ]; then
  fail "invite-production runner toolchain is incomplete (missing: ${missing[*]})."
fi

"$ROOT/scripts/verify-node-runtime.sh"
docker version --format 'Docker server {{.Server.Version}}' >/dev/null \
  || fail "Docker server is unavailable to the production runner."
docker compose version >/dev/null \
  || fail "Docker Compose is unavailable to the production runner."

echo "Invite-production runner toolchain: PASS"
