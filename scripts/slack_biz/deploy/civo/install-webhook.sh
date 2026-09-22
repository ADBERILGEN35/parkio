#!/usr/bin/env bash
# Install the slack_biz business webhook on the Civo host via HIDDEN input.
#
#   sudo scripts/slack_biz/deploy/civo/install-webhook.sh            # prompts, no echo
#   sudo scripts/slack_biz/deploy/civo/install-webhook.sh --restart  # + restart worker
#
# The URL is read with `read -rs` from the terminal and handed to the writer on
# stdin only: never echoed, never a command-line argument (not visible in
# /proc/*/cmdline or shell history), never in a temp file outside /etc/parkio.
# It is written atomically to /etc/parkio/slack-biz.secret.env (0600 root:root).
# Refuses a value equal to the Alertmanager webhook. Does NOT enable delivery:
# PARKIO_SLACK_BIZ_ENABLED in slack-biz.conf.env is a separate decision.
#
# Test-only: --stdin reads one line from stdin (still no echo);
# --allow-local-test-endpoint additionally accepts http://127.0.0.1:<port>/…
set -euo pipefail

RESTART=0; FROM_STDIN=0; ALLOW_LOCAL=0
for a in "$@"; do
  case "$a" in
    --restart) RESTART=1 ;;
    --stdin) FROM_STDIN=1 ;;
    --allow-local-test-endpoint) ALLOW_LOCAL=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done
[ "$(id -u)" = 0 ] || { echo "run as root (sudo)" >&2; exit 1; }
SECRET=/etc/parkio/slack-biz.secret.env
ALERT_ENV="${PARKIO_ALERT_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
[ -d /etc/parkio ] || { echo "run install-relay.sh --apply first" >&2; exit 1; }

set +x
if [ $FROM_STDIN = 1 ]; then
  IFS= read -r URL
else
  [ -t 0 ] || { echo "no terminal: use an interactive session (or --stdin for tests)" >&2; exit 2; }
  IFS= read -rs -p "Slack incoming webhook URL (input hidden): " URL; echo
fi

printf '%s' "$URL" | python3 -c '
import os, re, sys, tempfile
url = sys.stdin.read().strip()
secret, alert_env, allow_local = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
ok = re.fullmatch(r"https://hooks\.slack\.com/services/[A-Za-z0-9]+/[A-Za-z0-9]+/[A-Za-z0-9]+", url)
if not ok and allow_local:
    ok = re.fullmatch(r"http://127\.0\.0\.1:[0-9]{2,5}/[A-Za-z0-9/_-]+", url)
if not ok:
    sys.exit("refusing: value is not a Slack incoming webhook URL (value not shown)")
try:
    for line in open(alert_env, encoding="utf-8"):
        if line.startswith("PARKIO_ALERT_SLACK_WEBHOOK_URL=") and line.split("=", 1)[1].strip().strip("\"\x27") == url:
            sys.exit("refusing: identical to the Alertmanager webhook (ownership separation)")
except FileNotFoundError:
    pass
lines, seen = [], False
if os.path.exists(secret):
    for line in open(secret, encoding="utf-8"):
        if line.startswith("PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ="):
            line, seen = "PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ=" + url + "\n", True
        lines.append(line)
if not seen:
    lines.append("PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ=" + url + "\n")
fd, tmp = tempfile.mkstemp(dir=os.path.dirname(secret), prefix=".slack-biz.secret.")
with os.fdopen(fd, "w", encoding="utf-8") as fh:
    fh.writelines(lines)
os.chmod(tmp, 0o600); os.chown(tmp, 0, 0)
os.replace(tmp, secret)
print("slack-biz webhook: SET (value not shown) ->", secret)
' "$SECRET" "$ALERT_ENV" "$ALLOW_LOCAL"
unset URL
stat -c '%n %U:%G %a' "$SECRET"
if [ $RESTART = 1 ]; then
  systemctl restart parkio-slack-biz-worker.service
  echo "worker restarted (delivery still governed by PARKIO_SLACK_BIZ_ENABLED)"
fi
