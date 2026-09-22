#!/usr/bin/env bash
# Prepare the Civo host for the slack_biz relay (waitlist consumer + worker).
#
#   install-relay.sh                 # dry run (default): print every action
#   sudo install-relay.sh --apply    # perform the actions (idempotent)
#   sudo install-relay.sh --apply --no-systemd   # files/users only (containers, CI)
#
# Never writes or reads a webhook. Never starts delivery: units are installed
# and enabled, the conf file ships with PARKIO_SLACK_BIZ_ENABLED=false, and the
# secret file is created empty (0600 root:root) only if missing.
set -euo pipefail

APPLY=0; SYSTEMD=1
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    --no-systemd) SYSTEMD=0 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC_ROOT="${PARKIO_RELAY_SRC_ROOT:-/opt/parkio}"
SVC_USER=parkio-slackbiz
INBOX_GROUP=parkio-waitlist-inbox
STATE_DIR=/var/lib/parkio/slack-biz
INBOX_DIR=/var/lib/parkio/waitlist-ops-inbox
ETC=/etc/parkio
UNITS=(parkio-slack-biz-waitlist-consumer.service parkio-slack-biz-worker.service)

run() { echo "+ $*"; if [ $APPLY = 1 ]; then "$@"; fi; }

if [ $APPLY = 1 ] && [ "$(id -u)" != 0 ]; then echo "--apply requires root" >&2; exit 1; fi
python3 - <<'PY' || { echo "python3 >= 3.10 required" >&2; exit 1; }
import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)
PY
[ -f "$SRC_ROOT/scripts/slack_biz/worker.py" ] || { echo "relay source not found under $SRC_ROOT/scripts/slack_biz" >&2; exit 1; }
if command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' 2>/dev/null | grep -q 'slack-biz-worker'; then
  echo "refusing: a docker-compose slack-biz-worker is running on this host (single worker only)" >&2
  exit 1
fi

# Isolation: this package installs ONLY the waitlist consumer + worker. The
# legacy registration consumer (file inbox / Kafka) stays out of scope and OFF.
for f in "$ETC/slack-biz.conf.env" "$ETC/slack-biz.secret.env"; do
  if [ -r "$f" ] && grep -Eq '^(PARKIO_SLACK_BIZ_REGISTRATION_INBOX|PARKIO_SLACK_BIZ_KAFKA_BOOTSTRAP)=.+' "$f"; then
    echo "refusing: $f enables the registration consumer path (out of scope for this package)" >&2
    exit 1
  fi
done
if [ -r "$ETC/slack-biz.conf.env" ] && grep -Eq '^PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS=' "$ETC/slack-biz.conf.env" \
   && ! grep -Eq '^PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS=gateway-waitlist-outbox$' "$ETC/slack-biz.conf.env"; then
  echo "refusing: PARKIO_SLACK_BIZ_TRUSTED_PRODUCERS must be exactly gateway-waitlist-outbox" >&2
  exit 1
fi
if ls /etc/systemd/system/parkio-slack-biz-*.service 2>/dev/null | grep -v -E '/(parkio-slack-biz-waitlist-consumer|parkio-slack-biz-worker)\.service$' | grep -q .; then
  echo "refusing: unexpected parkio-slack-biz unit present (only waitlist consumer + worker are allowed)" >&2
  exit 1
fi

getent group "$INBOX_GROUP" >/dev/null || run groupadd --system "$INBOX_GROUP"
if ! id "$SVC_USER" >/dev/null 2>&1; then
  run useradd --system --user-group --no-create-home --home-dir /nonexistent \
      --shell /usr/sbin/nologin "$SVC_USER"
fi
run usermod -a -G "$INBOX_GROUP" "$SVC_USER"

run install -d -m 0755 -o root -g root /var/lib/parkio
run install -d -m 0700 -o "$SVC_USER" -g "$SVC_USER" "$STATE_DIR"
# setgid: files the gateway writes inherit the inbox group.
run install -d -m 2770 -o "$SVC_USER" -g "$INBOX_GROUP" "$INBOX_DIR"
run install -d -m 0755 -o root -g root "$ETC"

if [ ! -f "$ETC/slack-biz.conf.env" ]; then
  run install -m 0644 -o root -g root "$HERE/slack-biz.conf.env.example" "$ETC/slack-biz.conf.env"
else
  echo "= keep existing $ETC/slack-biz.conf.env"
fi
if [ ! -f "$ETC/slack-biz.secret.env" ]; then
  run install -m 0600 -o root -g root "$HERE/slack-biz.secret.env.example" "$ETC/slack-biz.secret.env"
else
  echo "= keep existing $ETC/slack-biz.secret.env (mode enforced)"
  run chmod 0600 "$ETC/slack-biz.secret.env"
  run chown root:root "$ETC/slack-biz.secret.env"
fi

for u in "${UNITS[@]}"; do
  run install -m 0644 -o root -g root "$HERE/$u" "/etc/systemd/system/$u"
done
if [ $SYSTEMD = 1 ]; then
  run systemctl daemon-reload
  run systemctl enable "${UNITS[@]}"
fi

echo
echo "gid of $INBOX_GROUP (for gateway group_add): $(getent group "$INBOX_GROUP" | cut -d: -f3 || echo '<created on --apply>')"
echo "Next (owner-authorised only): fill $ETC/slack-biz.secret.env, then"
echo "  systemctl start ${UNITS[*]}   # still PARKIO_SLACK_BIZ_ENABLED=false"
[ $APPLY = 1 ] || echo "(dry run — nothing changed)"
