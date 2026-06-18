#!/bin/sh
set -eu

BASE_CONFIG=/etc/alertmanager/alertmanager.yml
RUNTIME_CONFIG=/tmp/alertmanager.yml

if [ -z "${PARKIO_ALERT_SLACK_WEBHOOK_URL:-}" ]; then
  cp "$BASE_CONFIG" "$RUNTIME_CONFIG"
else
  cat >"$RUNTIME_CONFIG" <<EOF
global:
  resolve_timeout: 5m

route:
  receiver: "slack-warning"
  group_by: ["alertname", "service", "severity"]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: ${PARKIO_ALERT_REPEAT_WARNING:-4h}
  routes:
    - matchers:
        - severity="critical"
      receiver: "slack-critical"
      repeat_interval: ${PARKIO_ALERT_REPEAT_CRITICAL:-1h}
    - matchers:
        - severity="warning"
      receiver: "slack-warning"
      repeat_interval: ${PARKIO_ALERT_REPEAT_WARNING:-4h}

inhibit_rules:
  - source_matchers:
      - severity="critical"
    target_matchers:
      - severity="warning"
    equal: ["alertname", "service"]

receivers:
  - name: "slack-critical"
    slack_configs:
      - api_url: '${PARKIO_ALERT_SLACK_WEBHOOK_URL}'
        channel: '${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alerts}'
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.severity }}: {{ .CommonLabels.alertname }}'
        text: '{{ range .Alerts }}*{{ .Annotations.summary }}*{{ "\n" }}{{ .Annotations.description }}{{ "\n" }}{{ if .Annotations.runbook_url }}Runbook: {{ .Annotations.runbook_url }}{{ "\n" }}{{ end }}{{ end }}'
  - name: "slack-warning"
    slack_configs:
      - api_url: '${PARKIO_ALERT_SLACK_WEBHOOK_URL}'
        channel: '${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alerts}'
        send_resolved: true
        title: '[{{ .Status | toUpper }}] {{ .CommonLabels.severity }}: {{ .CommonLabels.alertname }}'
        text: '{{ range .Alerts }}*{{ .Annotations.summary }}*{{ "\n" }}{{ .Annotations.description }}{{ "\n" }}{{ if .Annotations.runbook_url }}Runbook: {{ .Annotations.runbook_url }}{{ "\n" }}{{ end }}{{ end }}'
EOF
fi

exec /bin/alertmanager \
  --config.file="$RUNTIME_CONFIG" \
  --storage.path=/alertmanager \
  --web.listen-address=:9093
