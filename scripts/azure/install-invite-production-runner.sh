#!/usr/bin/env bash
# Install the repository-scoped invite-production GitHub Actions runner.
# The short-lived registration token must be supplied in memory through
# GITHUB_RUNNER_REGISTRATION_TOKEN and is never written by this script.

set -euo pipefail
umask 077

RUNNER_VERSION="2.336.0"
RUNNER_ARCHIVE="actions-runner-linux-x64-${RUNNER_VERSION}.tar.gz"
RUNNER_URL="https://github.com/actions/runner/releases/download/v${RUNNER_VERSION}/${RUNNER_ARCHIVE}"
RUNNER_SHA256="04cf0be1aff4c3ec3554466c39124ca250e3effd8873bb7e8d68535aa9505d5d"
RUNNER_USER="parkio-runner"
RUNNER_HOME="/var/lib/parkio-runner"
RUNNER_DIR="/opt/actions-runner/parkio-invite-production"
RUNNER_NAME="parkio-invite-prod-01"
REPOSITORY_URL="https://github.com/ADBERILGEN35/parkio"

# Azure Managed Run Command supplies protected parameters positionally. Accept
# exactly one only when the environment form is absent; never echo either form.
if [ -z "${GITHUB_RUNNER_REGISTRATION_TOKEN:-}" ] && [ "$#" -eq 1 ]; then
  GITHUB_RUNNER_REGISTRATION_TOKEN="$1"
  export GITHUB_RUNNER_REGISTRATION_TOKEN
  set --
fi

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: runner installation requires root." >&2
  exit 2
fi
if [ "$(hostname)" != "vm-parkio-invite-prod" ]; then
  echo "ERROR: refusing to install outside vm-parkio-invite-prod." >&2
  exit 2
fi
if [ -z "${GITHUB_RUNNER_REGISTRATION_TOKEN:-}" ]; then
  echo "ERROR: short-lived GitHub runner registration token is required in memory." >&2
  exit 2
fi
if [ -e "$RUNNER_DIR/.runner" ]; then
  echo "ERROR: runner is already configured; use the audited rotation procedure." >&2
  exit 2
fi

if ! getent group docker >/dev/null; then
  echo "ERROR: Docker group is missing; host bootstrap is incomplete." >&2
  exit 2
fi
if ! id "$RUNNER_USER" >/dev/null 2>&1; then
  useradd --system --create-home --home-dir "$RUNNER_HOME" \
    --shell /usr/sbin/nologin --groups docker "$RUNNER_USER"
fi
passwd -l "$RUNNER_USER" >/dev/null

install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0750 "$RUNNER_HOME"
install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0750 "$RUNNER_DIR"

# The stable runtime root must exist before the first deploy: the runner has no
# sudo and cannot create it later (PROD-DEPLOY-01A-R8). Kept in its own script so
# an already-registered host can be provisioned without re-running registration.
PARKIO_RUNNER_USER="$RUNNER_USER" \
  "$(dirname "$0")/install-invite-production-runtime-root.sh"

archive="$(mktemp "/tmp/${RUNNER_ARCHIVE}.XXXXXX")"
cleanup() {
  rm -f -- "$archive"
  unset GITHUB_RUNNER_REGISTRATION_TOKEN
}
trap cleanup EXIT HUP INT TERM

curl --proto '=https' --tlsv1.2 --fail --silent --show-error --location \
  "$RUNNER_URL" --output "$archive"
printf '%s  %s\n' "$RUNNER_SHA256" "$archive" | sha256sum --check --status
echo "Official actions/runner archive checksum: PASS"

tar -xzf "$archive" -C "$RUNNER_DIR"
chown -R "$RUNNER_USER:$RUNNER_USER" "$RUNNER_DIR"

runuser -u "$RUNNER_USER" -- env \
  HOME="$RUNNER_HOME" \
  GITHUB_RUNNER_REGISTRATION_TOKEN="$GITHUB_RUNNER_REGISTRATION_TOKEN" \
  "$RUNNER_DIR/config.sh" \
    --unattended \
    --url "$REPOSITORY_URL" \
    --token "$GITHUB_RUNNER_REGISTRATION_TOKEN" \
    --name "$RUNNER_NAME" \
    --labels parkio-invite-production \
    --work _work \
    --replace

unset GITHUB_RUNNER_REGISTRATION_TOKEN
(
  cd "$RUNNER_DIR"
  ./svc.sh install "$RUNNER_USER"
)

service_name="$(systemctl list-unit-files --type=service --no-legend \
  | awk '$1 ~ /^actions\.runner\.ADBERILGEN35-parkio\.parkio-invite-prod-01\.service$/ {print $1}')"
if [ -z "$service_name" ]; then
  echo "ERROR: installed runner systemd service could not be identified." >&2
  exit 3
fi

override_dir="/etc/systemd/system/${service_name}.d"
install -d -m 0755 "$override_dir"
install -m 0644 /dev/null "$override_dir/parkio-hardening.conf"
printf '%s\n' \
  '[Service]' \
  'Restart=always' \
  'RestartSec=5s' \
  'NoNewPrivileges=true' \
  'PrivateTmp=true' \
  'ProtectHome=true' \
  'UMask=0077' \
  > "$override_dir/parkio-hardening.conf"

systemctl daemon-reload
systemctl enable --now "$service_name"
systemctl is-active --quiet "$service_name"
systemctl is-enabled --quiet "$service_name"

echo "Invite-production runner service installation: PASS"
echo "runnerName=$RUNNER_NAME"
echo "runnerVersion=$RUNNER_VERSION"
echo "runnerUser=$RUNNER_USER"
echo "runnerPath=$RUNNER_DIR"
echo "service=$service_name"
