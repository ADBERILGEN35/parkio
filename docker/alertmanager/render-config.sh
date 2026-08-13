#!/bin/sh
set -eu

BASE_CONFIG=/etc/alertmanager/alertmanager.yml
RUNTIME_CONFIG=/tmp/alertmanager.yml

SLACK_URL="${PARKIO_ALERT_SLACK_WEBHOOK_URL:-}"
SLACK_CHANNEL="${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alerts}"
WEBHOOK_URL="${PARKIO_ALERT_WEBHOOK_URL:-}"
WEBHOOK_SECRET="${PARKIO_ALERT_WEBHOOK_SECRET:-}"
REPEAT_CRITICAL="${PARKIO_ALERT_REPEAT_CRITICAL:-1h}"
REPEAT_WARNING="${PARKIO_ALERT_REPEAT_WARNING:-4h}"

if [ -z "$SLACK_URL" ] && [ -z "$WEBHOOK_URL" ]; then
  cp "$BASE_CONFIG" "$RUNTIME_CONFIG"
else
  cat > "$RUNTIME_CONFIG" <<EOF
global:
  resolve_timeout: 5m

route:
  receiver: "warning"
  group_by: ["alertname", "service", "severity", "component"]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: ${REPEAT_WARNING}
  routes:
    - matchers:
        - severity="critical"
      receiver: "critical"
      group_wait: 15s
      repeat_interval: ${REPEAT_CRITICAL}
    - matchers:
        - severity="warning"
      receiver: "warning"
      repeat_interval: ${REPEAT_WARNING}

inhibit_rules:
  - source_matchers:
      - severity="critical"
    target_matchers:
      - severity="warning"
    equal: ["alertname", "service", "component"]
  - source_matchers:
      - alertname="GatewayDown"
    target_matchers:
      - alertname=~"ServiceDown|GatewayHigh5xxRate|Gateway5xxElevated|GatewayHighLatencyP95|GatewayLatencyP95High"
    equal: ["service"]
  - source_matchers:
      - alertname="CoreServiceDown"
    target_matchers:
      - alertname="ServiceDown"
    equal: ["service"]
  - source_matchers:
      - alertname="PostgresDown"
    target_matchers:
      - alertname="DatabaseConnectionPoolExhausted"
  - source_matchers:
      - alertname="KafkaBrokerUnavailable"
    target_matchers:
      - alertname=~"KafkaConsumerLagHigh|KafkaConsumerLagSustained|KafkaDltMessagesPresent|KafkaDltGrowing"
  - source_matchers:
      - alertname="HostDiskSpaceCritical"
    target_matchers:
      - alertname=~"HostDiskSpaceLow|HostDiskWillFillSoon"
    equal: ["instance", "mountpoint"]
  - source_matchers:
      - alertname="BackupFailed"
    target_matchers:
      - alertname="BackupStale"
    equal: ["scope"]
  - source_matchers:
      - alertname="BackupOffsiteFailed"
    target_matchers:
      - alertname="BackupOffsiteStale"
    equal: ["scope"]

receivers:
EOF

  if [ -n "$SLACK_URL" ]; then
    cat >> "$RUNTIME_CONFIG" <<EOF
  - name: "critical"
    slack_configs:
      - api_url: '${SLACK_URL}'
        channel: '${SLACK_CHANNEL}'
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.severity }}: {{ .CommonLabels.alertname }}'
        text: 'monitor={{ .CommonLabels.monitor }}{{ "\\n" }}{{ range .Alerts }}*{{ .Annotations.summary }}*{{ "\\n" }}{{ .Annotations.description }}{{ "\\n" }}started={{ .StartsAt }}{{ "\\n" }}{{ if .Annotations.runbook_url }}Runbook: {{ .Annotations.runbook_url }}{{ "\\n" }}{{ end }}{{ end }}'
  - name: "warning"
    slack_configs:
      - api_url: '${SLACK_URL}'
        channel: '${SLACK_CHANNEL}'
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.severity }}: {{ .CommonLabels.alertname }}'
        text: 'monitor={{ .CommonLabels.monitor }}{{ "\\n" }}{{ range .Alerts }}*{{ .Annotations.summary }}*{{ "\\n" }}{{ .Annotations.description }}{{ "\\n" }}started={{ .StartsAt }}{{ "\\n" }}{{ if .Annotations.runbook_url }}Runbook: {{ .Annotations.runbook_url }}{{ "\\n" }}{{ end }}{{ end }}'
EOF
  else
    if [ -n "$WEBHOOK_SECRET" ]; then
      cat >> "$RUNTIME_CONFIG" <<EOF
  - name: "critical"
    webhook_configs:
      - url: '${WEBHOOK_URL}'
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: '${WEBHOOK_SECRET}'
  - name: "warning"
    webhook_configs:
      - url: '${WEBHOOK_URL}'
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: '${WEBHOOK_SECRET}'
EOF
    else
      cat >> "$RUNTIME_CONFIG" <<EOF
  - name: "critical"
    webhook_configs:
      - url: '${WEBHOOK_URL}'
        send_resolved: true
  - name: "warning"
    webhook_configs:
      - url: '${WEBHOOK_URL}'
        send_resolved: true
EOF
    fi
  fi
fi

if [ "${PARKIO_ALERTMANAGER_VALIDATE_ONLY:-}" = "1" ]; then
  exit 0
fi

exec /bin/alertmanager \
  --config.file="$RUNTIME_CONFIG" \
  --storage.path=/alertmanager \
  --web.listen-address=:9093
