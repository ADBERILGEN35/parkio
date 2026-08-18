#!/usr/bin/env bash
#
# Parkio invite-production scheduled backup runner (PROD-DEPLOY-01A-R3 / D2).
#
# The certified deployment path renders production secrets into tmpfs for the
# lifetime of a job and deletes them afterwards. The previous scheduler unit
# instead expected a persistent /opt/parkio/docker/.env.invite-production, which
# that path never creates — so the timer could not have worked, and making it
# work by persisting a plaintext production env would break the secret-residue
# policy it is supposed to uphold.
#
# This wrapper closes that gap the other way round: every run mints its own
# short-lived env from Key Vault through the VM managed identity, uses it, and
# destroys it. Nothing production-secret survives the process.
#
#   systemd timer -> this wrapper -> managed identity -> Key Vault
#                 -> /dev/shm env (0600) -> canonical backup -> shred
#
# Usage (normally invoked by parkio-invite-backup.service):
#   /opt/parkio/invite-production/scripts/azure/invite-production-backup-run.sh
#   ... --dry-run     render + validate wiring, run no backup
#
set -euo pipefail

# Resolve the installed runtime root from this script's own location, so the
# same file works from a repo checkout and from /opt/parkio/invite-production.
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

DRY_RUN=0
ARTIFACT_DIR="${PARKIO_BACKUP_ARTIFACT_DIR:-backup-artifacts/invite-production}"
OPERATOR="${PARKIO_BACKUP_OPERATOR:-systemd:parkio-invite-backup}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --artifact-dir) ARTIFACT_DIR="${2:-}"; shift 2 ;;
    --operator) OPERATOR="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ ! -d /dev/shm ]; then
  echo "ERROR: /dev/shm is unavailable; refusing to render production secrets to disk." >&2
  exit 3
fi

ENV_FILE=""
AZURE_CONFIG_DIR=""

# Fail-closed cleanup: whatever happens — success, backup failure, Key Vault
# outage, SIGTERM from a systemd timeout — the ephemeral env and the Azure token
# cache go away. Backup artifacts are encrypted and are deliberately left alone.
cleanup() {
  local status=$?
  if [ -n "$ENV_FILE" ] && [ -e "$ENV_FILE" ]; then
    rm -f -- "$ENV_FILE"
  fi
  if [ -n "$AZURE_CONFIG_DIR" ] && [ -d "$AZURE_CONFIG_DIR" ]; then
    rm -rf -- "$AZURE_CONFIG_DIR"
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 143' HUP INT TERM

ENV_FILE="$(mktemp /dev/shm/parkio-invite-backup-XXXXXXXX.env)"
chmod 600 "$ENV_FILE"
AZURE_CONFIG_DIR="$(mktemp -d /dev/shm/parkio-invite-backup-az-XXXXXXXX)"
chmod 700 "$AZURE_CONFIG_DIR"
export AZURE_CONFIG_DIR

echo "=== Parkio invite-production scheduled backup ==="
echo "root=$ROOT"
echo "operator=$OPERATOR"
echo "dryRun=$DRY_RUN"

# Managed identity only — no service principal, no stored credential.
if ! az login --identity --allow-no-subscriptions --output none; then
  echo "ERROR: managed-identity login failed; backup fails closed." >&2
  exit 3
fi

if ! "$ROOT/scripts/azure/render-invite-production-env.sh" --output "$ENV_FILE"; then
  echo "ERROR: could not materialize the production env from Key Vault; backup fails closed." >&2
  exit 3
fi

# render-invite-production-env.py writes 0600 via mkstemp+fchmod, but this is the
# security property the whole wrapper exists for, so verify rather than assume.
mode="$(stat -c '%a' "$ENV_FILE")"
if [ "$mode" != "600" ]; then
  echo "ERROR: rendered env has mode $mode, expected 600." >&2
  exit 3
fi

# Record which installed revision produced the backup. Written by the installer;
# the artifact manifest carries it instead of a git SHA, because the runtime root
# is deliberately not a git checkout.
if [ -r "$ROOT/VERSION" ]; then
  PARKIO_BACKUP_GIT_SHA="$(awk -F= '$1 == "gitSha" { print $2 }' "$ROOT/VERSION")"
  export PARKIO_BACKUP_GIT_SHA
  echo "installedRevision=${PARKIO_BACKUP_GIT_SHA:-unknown}"
fi

if [ "$DRY_RUN" -eq 1 ]; then
  # Prove the wiring without touching a database: the env rendered, the mode is
  # right, and the canonical backup entrypoint is present and executable.
  test -x "$ROOT/scripts/backup-hosted-beta.sh"
  echo "DRY-RUN: env rendered (mode 600) and canonical backup entrypoint present."
  echo "DRY-RUN: no database was contacted and no artifact was written."
  exit 0
fi

status=0
PARKIO_ENV_FILE="$ENV_FILE" \
  "$ROOT/scripts/backup-hosted-beta.sh" \
  --env-file "$ENV_FILE" \
  --artifact-dir "$ARTIFACT_DIR" \
  --operator "$OPERATOR" || status=$?

if [ "$status" -ne 0 ]; then
  echo "ERROR: invite-production backup failed (status=$status)." >&2
  exit "$status"
fi

echo "Invite-production scheduled backup completed."
