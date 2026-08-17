#!/usr/bin/env bash
# Install the one canonical invite-production backup scheduler.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: scheduler installation must run as root." >&2
  exit 2
fi
if [ ! -f /opt/parkio/docker/.env.invite-production ]; then
  echo "ERROR: production env has not been materialized; scheduler remains disabled." >&2
  exit 3
fi
if grep -R -l --fixed-strings 'backup-hosted-beta.sh' /etc/cron.d /etc/cron.daily /var/spool/cron 2>/dev/null | grep -q .; then
  echo "ERROR: another cron backup orchestrator exists; refusing a duplicate schedule." >&2
  exit 3
fi

install -m 0644 "$ROOT/infra/systemd/parkio-invite-backup.service" /etc/systemd/system/parkio-invite-backup.service
install -m 0644 "$ROOT/infra/systemd/parkio-invite-backup.timer" /etc/systemd/system/parkio-invite-backup.timer
systemctl daemon-reload
systemctl enable --now parkio-invite-backup.timer

echo "Canonical backup timer installed."
systemctl list-timers parkio-invite-backup.timer --no-pager
