#!/usr/bin/env bash
# Apply workflow dispatch inputs to a rendered invite-production env file.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${PARKIO_ENV_FILE:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --env-file) ENV_FILE="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

[ -n "$ENV_FILE" ] || { echo "ERROR: --env-file is required" >&2; exit 2; }
[ -f "$ENV_FILE" ] || { echo "ERROR: env file not found: $ENV_FILE" >&2; exit 2; }

# shellcheck source=lib/invite-deploy-profile.sh
source "$ROOT/scripts/lib/invite-deploy-profile.sh"

edge_mode="${PARKIO_DISPATCH_INVITE_EDGE_MODE:-}"
acme_authorized="${PARKIO_DISPATCH_INVITE_ACME_AUTHORIZED:-}"
registration_mode="${PARKIO_DISPATCH_REGISTRATION_MODE:-}"

profile="$(parkio_validate_invite_dispatch_inputs)" || exit 4

python3 - "$ENV_FILE" "$edge_mode" "$acme_authorized" "$registration_mode" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
updates = {
    "PARKIO_INVITE_EDGE_MODE": sys.argv[2],
    "PARKIO_INVITE_ACME_AUTHORIZED": sys.argv[3],
    "PARKIO_REGISTRATION_MODE": sys.argv[4],
}
lines = path.read_text().splitlines()
seen = set()
out = []
for line in lines:
    if not line or line.lstrip().startswith("#") or "=" not in line:
        out.append(line)
        continue
    key, _ = line.split("=", 1)
    if key in updates:
        out.append(f"{key}={updates[key]}")
        seen.add(key)
    else:
        out.append(line)
for key, value in updates.items():
    if key not in seen:
        out.append(f"{key}={value}")
path.write_text("\n".join(out) + "\n")
PY

echo "invite_dispatch_profile=$profile"
echo "invite_edge_mode=$edge_mode"
echo "invite_acme_authorized=$acme_authorized"
echo "registration_mode=$registration_mode"
echo "invite_production_dispatch_env_applied=PASS"
