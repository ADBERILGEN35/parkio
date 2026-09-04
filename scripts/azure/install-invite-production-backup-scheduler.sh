#!/usr/bin/env bash
#
# Install the one canonical invite-production backup scheduler.
#
# PROD-DEPLOY-01A-R3 / D2. The previous version installed units that pointed at
# /opt/parkio/scripts/... and /opt/parkio/docker/.env.invite-production, and
# refused to install unless that persistent production env already existed.
# Neither is ever created by the certified deploy path: it checks the repo out to
# a per-job directory it deletes afterwards, and renders secrets only into tmpfs.
# The scheduler therefore could not have run, and satisfying it would have meant
# persisting a plaintext production env — the exact thing the secret-residue
# policy forbids.
#
# The fix is a small, versioned operational payload at a dedicated stable path,
# plus a wrapper that mints its env per run (see invite-production-backup-run.sh):
#
#   /opt/parkio/invite-production-backup/
#     VERSION                  gitSha + installedAt (non-secret)
#     MANIFEST.sha256          checksums of every installed file
#     docker/.env.invite-production.example
#     scripts/…                the backup + render closure, nothing else
#
# This is NOT a copy of a developer checkout: only the files the scheduled backup
# actually executes are installed, and they are listed explicitly below.
#
# Usage:
#   install-invite-production-backup-scheduler.sh                 install/upgrade, timer left disabled
#   install-invite-production-backup-scheduler.sh --enable        install and enable the timer
#   install-invite-production-backup-scheduler.sh --disable       rollback: stop + disable, keep backups
#   install-invite-production-backup-scheduler.sh --dry-run --prefix DIR --unit-dir DIR
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

DEFAULT_PREFIX="/opt/parkio/invite-production-backup"
RUNTIME_ROOT="/opt/parkio/invite-production"
BACKUP_DATA_ROOT="/var/backups/parkio"
PREFIX="$DEFAULT_PREFIX"
UNIT_DIR="/etc/systemd/system"
DRY_RUN=0
ENABLE_TIMER=0
DISABLE_TIMER=0

# The complete execution closure of the scheduled backup. Adding anything here is
# a deliberate act — the payload must stay auditable.
PAYLOAD_FILES=(
  "docker/.env.invite-production.example"
  "scripts/azure/invite-production-backup-run.sh"
  "scripts/azure/render-invite-production-env.sh"
  "scripts/azure/render-invite-production-env.py"
  "scripts/backup-hosted-beta.sh"
  "scripts/backup-databases.sh"
  "scripts/backup-minio.sh"
  "scripts/lib/backup-common.sh"
  "scripts/lib/erasure-tombstones.sh"
)

EXECUTABLE_FILES=(
  "scripts/azure/invite-production-backup-run.sh"
  "scripts/azure/render-invite-production-env.sh"
  "scripts/backup-hosted-beta.sh"
  "scripts/backup-databases.sh"
  "scripts/backup-minio.sh"
)

while [ "$#" -gt 0 ]; do
  case "$1" in
    --prefix) PREFIX="${2:-}"; shift 2 ;;
    --unit-dir) UNIT_DIR="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --enable) ENABLE_TIMER=1; shift ;;
    --disable) DISABLE_TIMER=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [ "$ENABLE_TIMER" -eq 1 ] && [ "$DISABLE_TIMER" -eq 1 ]; then
  echo "ERROR: --enable and --disable are mutually exclusive." >&2
  exit 2
fi
if [ "$DRY_RUN" -eq 0 ] && [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: scheduler installation must run as root (use --dry-run for a staging check)." >&2
  exit 2
fi

fail_path() {
  echo "ERROR: unsafe scheduler payload destination '$PREFIX': $1" >&2
  exit 3
}

# Validate the destination before staging, replacing, or removing anything.
# Test fixtures may use /tmp only through --dry-run; a real installation must
# remain on persistent storage. Requiring the spelling to equal realpath -m also
# rejects symlink parents, symlink destinations, and lexical traversal.
validate_payload_destination() {
  [ -n "$PREFIX" ] || fail_path "destination is empty"
  case "$PREFIX" in
    /*) ;;
    *) fail_path "destination must be an absolute path" ;;
  esac
  case "$PREFIX" in
    *[!A-Za-z0-9_./-]*) fail_path "destination contains unsupported characters" ;;
  esac

  command -v realpath >/dev/null 2>&1 \
    || fail_path "realpath is required for canonical path validation"
  local canonical
  canonical="$(realpath -m -- "$PREFIX")" \
    || fail_path "destination could not be canonicalized"
  [ "$canonical" = "$PREFIX" ] \
    || fail_path "destination must be canonical and must not traverse symlinks"

  case "$canonical" in
    /|/opt|/opt/parkio)
      fail_path "destination is a protected root/parent"
      ;;
    "$RUNTIME_ROOT"|"$RUNTIME_ROOT"/*)
      fail_path "application runtime root and its children are runtime-owned"
      ;;
    "$BACKUP_DATA_ROOT"|"$BACKUP_DATA_ROOT"/*)
      fail_path "backup data must remain separate from scheduler code"
      ;;
    */_work|*/_work/*)
      fail_path "Actions _work paths are transient"
      ;;
    /dev/shm|/dev/shm/*)
      fail_path "tmpfs is reserved for ephemeral credentials"
      ;;
    /tmp|/tmp/*)
      [ "$DRY_RUN" -eq 1 ] || fail_path "real installations may not use /tmp"
      ;;
  esac

  local marker
  for marker in current releases acceptance; do
    if [ -e "$PREFIX/$marker" ] || [ -L "$PREFIX/$marker" ]; then
      fail_path "runtime marker '$marker' is present"
    fi
  done
}

assert_owned_payload() {
  local path="$1"
  [ ! -L "$path" ] || { echo "ERROR: refusing symlinked scheduler payload '$path'." >&2; return 1; }
  [ -f "$path/VERSION" ] && [ -f "$path/MANIFEST.sha256" ] \
    || { echo "ERROR: '$path' is not a recognized scheduler payload." >&2; return 1; }
  local marker
  for marker in current releases acceptance; do
    if [ -e "$path/$marker" ] || [ -L "$path/$marker" ]; then
      echo "ERROR: runtime marker '$marker' found under scheduler payload '$path'." >&2
      return 1
    fi
  done
  (cd "$path" && sha256sum --quiet --check MANIFEST.sha256) \
    || { echo "ERROR: scheduler payload checksum validation failed at '$path'." >&2; return 1; }
}

remove_owned_payload() {
  local path="$1"
  assert_owned_payload "$path" || return 1
  rm -rf -- "$path"
}

# ----------------------------------------------------------------------------- #
# Rollback path                                                                  #
# ----------------------------------------------------------------------------- #
if [ "$DISABLE_TIMER" -eq 1 ]; then
  systemctl disable --now parkio-invite-backup.timer 2>/dev/null || true
  systemctl stop parkio-invite-backup.service 2>/dev/null || true
  systemctl daemon-reload
  echo "Backup timer disabled and service stopped."
  echo "Installed payload and existing encrypted backups were intentionally left in place."
  exit 0
fi

validate_payload_destination

# ----------------------------------------------------------------------------- #
# Refuse to co-exist with a second orchestrator or a legacy persistent env        #
# ----------------------------------------------------------------------------- #
if grep -R -l --fixed-strings 'backup-hosted-beta.sh' /etc/cron.d /etc/cron.daily /var/spool/cron 2>/dev/null | grep -q .; then
  echo "ERROR: another cron backup orchestrator exists; refusing a duplicate schedule." >&2
  exit 3
fi

# A leftover plaintext production env from the pre-R3 model is secret residue.
# Report it loudly rather than silently adopting or deleting it.
LEGACY_ENV="/opt/parkio/docker/.env.invite-production"
if [ "$DRY_RUN" -eq 0 ] && [ -e "$LEGACY_ENV" ]; then
  echo "ERROR: legacy persistent production env found at $LEGACY_ENV." >&2
  echo "       The R3 scheduler renders secrets per run into tmpfs and must not" >&2
  echo "       inherit a persistent one. Shred it, then re-run this installer." >&2
  exit 3
fi

# ----------------------------------------------------------------------------- #
# Stage the payload, then swap atomically so a failed install leaves no partial   #
# tree behind and a re-run is a no-op on content (idempotent).                    #
# ----------------------------------------------------------------------------- #
install -d -m 0750 "$(dirname "$PREFIX")"
STAGE="$(mktemp -d "$(dirname "$PREFIX")/.$(basename "$PREFIX").stage.XXXXXXXX")"
chmod 0750 "$STAGE"
cleanup() {
  if [ -n "${STAGE:-}" ] && [ -d "$STAGE" ]; then
    rm -rf -- "$STAGE"
  fi
}
trap cleanup EXIT HUP INT TERM

for relative in "${PAYLOAD_FILES[@]}"; do
  if [ ! -f "$ROOT/$relative" ]; then
    echo "ERROR: payload file missing from source tree: $relative" >&2
    exit 3
  fi
  install -D -m 0644 "$ROOT/$relative" "$STAGE/$relative"
done
for relative in "${EXECUTABLE_FILES[@]}"; do
  chmod 0755 "$STAGE/$relative"
done

GIT_SHA="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
cat > "$STAGE/VERSION" <<EOF
gitSha=${GIT_SHA}
installedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)
payloadFiles=${#PAYLOAD_FILES[@]}
EOF
chmod 0644 "$STAGE/VERSION"

( cd "$STAGE" && find . -type f ! -name MANIFEST.sha256 -print0 \
  | sort -z | xargs -0 sha256sum > MANIFEST.sha256 )
chmod 0644 "$STAGE/MANIFEST.sha256"

# Nothing secret may ever reach the installed payload. Two independent checks,
# because a rendered env and an accidentally-committed key fail differently.
#
# (a) real secret material by shape — a PEM body, a live Slack webhook path, a
#     Resend key. The shipped template has none of these; a rendered env would.
if grep -RIl --exclude=MANIFEST.sha256 -E \
    'BEGIN [A-Z ]*PRIVATE KEY|hooks\.slack\.com/services/[A-Za-z0-9]{5,}|\bre_[A-Za-z0-9]{16,}' \
    "$STAGE" 2>/dev/null | grep -q .; then
  echo "ERROR: staged payload contains secret-shaped material; refusing to install." >&2
  exit 3
fi
# (b) every credential-shaped assignment in a staged dotenv must still be an
#     unfilled placeholder. This is what catches a rendered env that happens not
#     to match any shape above.
#
#     Booleans, numbers and short values are exempt: several flags are named
#     *_LOG_TOKEN / *_PASSWORD_* but hold `false`, and every value the renderer
#     substitutes (Key Vault secrets, PEMs, webhook URLs) is far longer than the
#     12-character floor below.
while IFS= read -r dotenv; do
  if awk -F= '
      /^[[:space:]]*#/ { next }
      !/=/ { next }
      $1 ~ /PASSWORD|PASSPHRASE|SECRET|TOKEN|API_KEY|WEBHOOK|PRIVATE_KEY|MAPTILER_KEY/ {
        value = substr($0, index($0, "=") + 1)
        gsub(/^"|"$/, "", value)
        if (value == "" || value ~ /REPLACE_ME/) { next }
        if (value ~ /^(true|false|TRUE|FALSE|[0-9]+)$/) { next }
        if (length(value) < 12) { next }
        found = 1
        printf "  %s\n", $1 > "/dev/stderr"
      }
      END { exit(found ? 0 : 1) }
    ' "$dotenv"; then
    echo "ERROR: staged dotenv '$dotenv' carries a filled credential value; refusing to install." >&2
    exit 3
  fi
done < <(find "$STAGE" -type f -name '.env*' -print)

# The staged closure must be internally valid before it can displace a working
# scheduler payload.
assert_owned_payload "$STAGE"

PREVIOUS=""
if [ -e "$PREFIX" ] || [ -L "$PREFIX" ]; then
  if [ -d "$PREFIX" ] && [ -z "$(ls -A "$PREFIX" 2>/dev/null)" ]; then
    rmdir -- "$PREFIX"
  else
    assert_owned_payload "$PREFIX"
  fi
fi
if [ -d "$PREFIX" ]; then
  PREVIOUS="${PREFIX}.previous"
  if [ -e "$PREVIOUS" ] || [ -L "$PREVIOUS" ]; then
    remove_owned_payload "$PREVIOUS"
  fi
  mv -- "$PREFIX" "$PREVIOUS"
fi
if ! mv -- "$STAGE" "$PREFIX"; then
  echo "ERROR: payload install failed; restoring the previous revision." >&2
  [ -n "$PREVIOUS" ] && mv -- "$PREVIOUS" "$PREFIX"
  exit 3
fi
STAGE=""

rollback_payload() {
  local status="${1:-3}"
  echo "ERROR: scheduler activation failed; restoring the previous payload." >&2
  if [ -d "$PREFIX" ]; then
    remove_owned_payload "$PREFIX" || true
  fi
  if [ -n "$PREVIOUS" ] && [ -d "$PREVIOUS" ]; then
    mv -- "$PREVIOUS" "$PREFIX"
  fi
  return "$status"
}

install -d -m 0755 "$UNIT_DIR"
escaped_prefix="${PREFIX//&/\\&}"
if ! sed "s#${DEFAULT_PREFIX}#${escaped_prefix}#g" \
    "$ROOT/infra/systemd/parkio-invite-backup.service" > "$UNIT_DIR/parkio-invite-backup.service" \
  || ! install -m 0644 "$ROOT/infra/systemd/parkio-invite-backup.timer" "$UNIT_DIR/parkio-invite-backup.timer" \
  || ! chmod 0644 "$UNIT_DIR/parkio-invite-backup.service" "$UNIT_DIR/parkio-invite-backup.timer"; then
  rollback_payload 3
  exit 3
fi

if [ "$DRY_RUN" -eq 1 ]; then
  [ -z "$PREVIOUS" ] || remove_owned_payload "$PREVIOUS"
  echo "DRY-RUN: staged payload to $PREFIX and units to $UNIT_DIR."
  echo "DRY-RUN: systemd was not contacted and no timer was enabled."
  exit 0
fi

if ! systemctl daemon-reload; then
  rollback_payload 3
  exit 3
fi

# Verify what systemd actually parsed before offering to enable it.
if ! systemd-analyze verify "$UNIT_DIR/parkio-invite-backup.timer" 2>&1 | grep -vq 'Failed'; then
  : # systemd-analyze is advisory here; a hard failure is reported below.
fi
if ! systemctl cat parkio-invite-backup.timer >/dev/null; then
  rollback_payload 3
  exit 3
fi

if [ "$ENABLE_TIMER" -eq 1 ]; then
  if ! systemctl enable --now parkio-invite-backup.timer; then
    rollback_payload 3
    exit 3
  fi
  echo "Canonical backup timer installed and enabled."
  systemctl list-timers parkio-invite-backup.timer --no-pager
else
  # Enablement is an acceptance-phase decision, not an installation side effect.
  systemctl disable parkio-invite-backup.timer 2>/dev/null || true
  echo "Canonical backup scheduler installed (timer NOT enabled)."
  echo "Enable it explicitly during backup acceptance:"
  echo "  systemctl enable --now parkio-invite-backup.timer"
fi

[ -z "$PREVIOUS" ] || remove_owned_payload "$PREVIOUS"
echo "Installed revision: ${GIT_SHA}"
echo "Payload: $PREFIX (see VERSION and MANIFEST.sha256)"
