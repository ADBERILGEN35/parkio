#!/usr/bin/env bash
# Install operator Slack webhook into Civo hosted-beta env WITHOUT echoing the secret.
# Usage (on parkio-civo-prod as civo, from a local tty — never paste the URL into chat):
#   umask 077
#   printf '%s' 'https://hooks.slack.com/services/...' > /tmp/parkio-slack-webhook.in
#   sudo bash scripts/install-civo-alert-slack-webhook.sh /tmp/parkio-slack-webhook.in
#   shred -u /tmp/parkio-slack-webhook.in 2>/dev/null || rm -f /tmp/parkio-slack-webhook.in
set -euo pipefail

SRC="${1:-}"
ENVF="${PARKIO_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
CHANNEL="${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alert}"

if [ -z "$SRC" ] || [ ! -f "$SRC" ]; then
  echo "Usage: $0 /path/to/webhook-file" >&2
  echo "File must contain only the Slack incoming webhook URL (no quotes)." >&2
  exit 2
fi

URL="$(tr -d '\r\n' < "$SRC")"
case "$URL" in
  https://hooks.slack.com/services/*) ;;
  *)
    echo "ERROR: webhook file does not look like a Slack incoming webhook URL." >&2
    exit 2
    ;;
esac

if [ ! -f "$ENVF" ]; then
  echo "ERROR: env file missing: $ENVF" >&2
  exit 2
fi

python3 - "$ENVF" "$URL" "$CHANNEL" <<'PY'
import re, sys
from pathlib import Path
envf, url, channel = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
text = envf.read_text()
updates = {
    "PARKIO_ALERT_SLACK_WEBHOOK_URL": url,
    "PARKIO_ALERT_SLACK_CHANNEL": channel,
}
for k, v in updates.items():
    line = f"{k}={v}"
    if re.search(rf"^{re.escape(k)}=.*$", text, re.M):
        text = re.sub(rf"^{re.escape(k)}=.*$", line, text, flags=re.M)
    else:
        text += ("\n" if not text.endswith("\n") else "") + line + "\n"
envf.write_text(text)
print(f"updated {envf} keys={[k for k in updates]} channel={channel} webhook=SET")
PY

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/docker"
COMPOSE=(docker compose --env-file "$ENVF"
  -f docker-compose.yml
  -f docker-compose.apps.yml
  -f docker-compose.hosted-beta.yml
  -f docker-compose.azure-hosted-beta.yml)

# Alertmanager is profile-disabled in azure overlay; start it explicitly.
"${COMPOSE[@]}" --profile azure-disabled-observability up -d --no-deps --force-recreate alertmanager
echo "alertmanager recreated; confirm receiver is slack via sanitized grep (api_url REDACTED)."
