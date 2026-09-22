#!/usr/bin/env bash
# Disposable systemd container check of the Civo relay package:
# install-relay.sh --apply, hardened units start, a gateway-uid (10001) write
# into the shared inbox is delivered to a LOCAL mock webhook, secret file is
# unreadable by the service user, and journals never contain the webhook URL.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$HERE/.." && pwd)"             # scripts/slack_biz
EVIDENCE_DIR="${EVIDENCE_DIR:-$HERE/.evidence}"; mkdir -p "$EVIDENCE_DIR"
IMG="${SYSTEMD_IMAGE:-jrei/systemd-ubuntu:24.04}"
C=parkio-relay-systemd-check
OUT="$EVIDENCE_DIR/civo-systemd-check.log"; : > "$OUT"
PASS=0; FAIL=0
log() { echo "$*" | tee -a "$OUT"; }
check() { if eval "$2"; then PASS=$((PASS+1)); log "PASS $1"; else FAIL=$((FAIL+1)); log "FAIL $1"; fi; }
x() { docker exec "$C" bash -lc "$1"; }
trap 'docker rm -f "$C" >/dev/null 2>&1' EXIT

docker rm -f "$C" >/dev/null 2>&1
docker run -d --name "$C" --privileged --cgroupns=host -v /sys/fs/cgroup:/sys/fs/cgroup:rw \
  --tmpfs /run --tmpfs /run/lock "$IMG" >/dev/null
for _ in $(seq 1 30); do x 'systemctl is-system-running 2>/dev/null' | grep -Eq 'running|degraded' && break; sleep 1; done
x 'apt-get update -qq >/dev/null && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3 >/dev/null' || { log "apt failed"; exit 1; }
x 'mkdir -p /opt/parkio/scripts'
docker cp "$SRC" "$C:/opt/parkio/scripts/slack_biz"
x 'chown -R root:root /opt/parkio && find /opt/parkio -type d -exec chmod 755 {} + && find /opt/parkio -type f -exec chmod 644 {} + && chmod 755 /opt/parkio/scripts/slack_biz/deploy/civo/install-relay.sh'
log "python: $(x 'python3 --version')  systemd: $(x 'systemctl --version | head -1')"

x '/opt/parkio/scripts/slack_biz/deploy/civo/install-relay.sh --apply' >> "$OUT" 2>&1
check "installer apply" "[ $? = 0 ]"
x '/opt/parkio/scripts/slack_biz/deploy/civo/install-relay.sh --apply' >> "$OUT" 2>&1
check "installer idempotent re-run" "[ $? = 0 ]"
perms="$(x 'stat -c "%n %U:%G %a" /var/lib/parkio/slack-biz /var/lib/parkio/waitlist-ops-inbox /etc/parkio/slack-biz.secret.env /etc/parkio/slack-biz.conf.env')"
log "$perms"
check "dir/secret ownership+modes" "echo \"\$perms\" | grep -q 'slack-biz parkio-slackbiz:parkio-slackbiz 700' && echo \"\$perms\" | grep -q 'waitlist-ops-inbox parkio-slackbiz:parkio-waitlist-inbox 2770' && echo \"\$perms\" | grep -q 'secret.env root:root 600'"
check "service user has no login shell" "x 'getent passwd parkio-slackbiz' | grep -q nologin"

# Local mock webhook (never a real Slack host) + activation for the check only.
x 'cat > /tmp/mock.env <<E
MOCK_SLACK_PORT=18099
MOCK_SLACK_BIND=127.0.0.1
MOCK_SLACK_LOG=/root/mock-requests.jsonl
E
systemd-run --unit=mock-slack --property=EnvironmentFile=/tmp/mock.env /usr/bin/python3 /opt/parkio/scripts/slack_biz/waitlist-e2e/mock_slack_server.py' >/dev/null
x "sed -i 's/^PARKIO_SLACK_BIZ_ENABLED=.*/PARKIO_SLACK_BIZ_ENABLED=true/; s/^PARKIO_SLACK_BIZ_ENVIRONMENT=.*/PARKIO_SLACK_BIZ_ENVIRONMENT=civo-check/' /etc/parkio/slack-biz.conf.env"
x "sed -i 's#^PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ=.*#PARKIO_SLACK_BIZ_WEBHOOK_URL_BIZ=http://127.0.0.1:18099/services/CIVOCHECK/MOCK/not-real#' /etc/parkio/slack-biz.secret.env"
x 'systemctl start parkio-slack-biz-waitlist-consumer.service parkio-slack-biz-worker.service'
sleep 4
check "both units active" "[ \"\$(x 'systemctl is-active parkio-slack-biz-waitlist-consumer parkio-slack-biz-worker' | tr '\n' ' ')\" = 'active active ' ]"
check "service user cannot read secret file" "! x 'runuser -u parkio-slackbiz -- cat /etc/parkio/slack-biz.secret.env' >/dev/null 2>&1"

# Write an envelope exactly as the gateway container would (uid 10001 + inbox group).
gid="$(x 'getent group parkio-waitlist-inbox | cut -d: -f3')"
env_json="$(python3 -c 'import json,uuid,hashlib;print(json.dumps({"contractVersion":1,"eventId":str(uuid.uuid4()),"eventType":"waitlist.subscription_confirmed","occurredAt":"2026-09-22T12:00:00Z","environment":"civo-check","producer":"gateway-waitlist-outbox","dedupKey":"waitlist:subscription_confirmed:"+hashlib.sha256(b"civo").hexdigest()}))')"
x "setpriv --reuid 10001 --regid 10001 --groups $gid bash -c 'umask 022; printf %s \"\$0\" > /var/lib/parkio/waitlist-ops-inbox/waitlist-civo.json' '$env_json'"
check "gateway uid 10001 can write inbox" "x 'test -f /var/lib/parkio/waitlist-ops-inbox/waitlist-civo.json -o -f /var/lib/parkio/waitlist-ops-inbox/.acked/waitlist-civo.json'"
check "outsider uid 20000 cannot write inbox" "! x 'setpriv --reuid 20000 --regid 20000 --clear-groups touch /var/lib/parkio/waitlist-ops-inbox/x.json' 2>/dev/null"
for _ in $(seq 1 30); do [ "$(x 'wc -l < /root/mock-requests.jsonl 2>/dev/null' | tr -d ' ')" = 1 ] && break; sleep 1; done
check "delivered to local mock through hardened units" "[ \"\$(x 'wc -l < /root/mock-requests.jsonl' | tr -d ' ')\" = 1 ]"
x 'cat /root/mock-requests.jsonl' > "$EVIDENCE_DIR/civo-systemd-mock-request.jsonl"
x 'journalctl -u parkio-slack-biz-waitlist-consumer -u parkio-slack-biz-worker --no-pager' > "$EVIDENCE_DIR/civo-systemd-journal.log"
check "journal has no webhook URL" "! grep -q 'CIVOCHECK' \"$EVIDENCE_DIR/civo-systemd-journal.log\""
check "consumer has no network (PrivateNetwork)" "x 'systemctl show -p PrivateNetwork parkio-slack-biz-waitlist-consumer' | grep -q yes"
# Isolation: only the waitlist consumer + worker exist; registration path OFF.
units="$(x "systemctl list-unit-files 'parkio-slack-biz*' --no-legend | awk '{print \$1\":\"\$2}' | sort | paste -sd, -")"
log "slack-biz units: $units"
check "only waitlist consumer + worker installed/enabled" "[ \"\$units\" = 'parkio-slack-biz-waitlist-consumer.service:enabled,parkio-slack-biz-worker.service:enabled' ]"
reg='{"eventId":"11111111-1111-4111-8111-111111111111","eventType":"UserRegistered","payload":{"userId":"22222222-2222-4222-8222-222222222222"}}'
check "registration events rejected by trusted-producer allow-list" "! x \"cd /opt/parkio/scripts && printf '%s' '\$reg' | runuser -u parkio-slackbiz -- env \\\$(grep -v '^#' /etc/parkio/slack-biz.conf.env | xargs) python3 slack_biz/enqueue.py --kind registration\" >/dev/null 2>&1"
x "cp /etc/parkio/slack-biz.conf.env /root/conf.bak && echo PARKIO_SLACK_BIZ_REGISTRATION_INBOX=/tmp/x >> /etc/parkio/slack-biz.conf.env"
check "installer refuses registration-consumer config" "! x '/opt/parkio/scripts/slack_biz/deploy/civo/install-relay.sh --apply' >/dev/null 2>&1"
x "cp /root/conf.bak /etc/parkio/slack-biz.conf.env"
x 'systemd-analyze security parkio-slack-biz-waitlist-consumer.service parkio-slack-biz-worker.service --no-pager' > "$EVIDENCE_DIR/civo-systemd-security.txt" 2>&1
log "$(grep -E 'Overall exposure' "$EVIDENCE_DIR/civo-systemd-security.txt")"
x 'systemctl stop parkio-slack-biz-worker parkio-slack-biz-waitlist-consumer'
log "SUMMARY PASS=$PASS FAIL=$FAIL"
[ $FAIL = 0 ]
