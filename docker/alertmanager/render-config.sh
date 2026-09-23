#!/bin/sh
set -eu

BASE_CONFIG=/etc/alertmanager/alertmanager.yml
RUNTIME_CONFIG=/tmp/alertmanager.yml

SLACK_URL="${PARKIO_ALERT_SLACK_WEBHOOK_URL:-}"
SLACK_CHANNEL="${PARKIO_ALERT_SLACK_CHANNEL:-#parkio-alert}"
WEBHOOK_URL="${PARKIO_ALERT_WEBHOOK_URL:-}"
WEBHOOK_SECRET="${PARKIO_ALERT_WEBHOOK_SECRET:-}"
REPEAT_CRITICAL="${PARKIO_ALERT_REPEAT_CRITICAL:-1h}"
REPEAT_WARNING="${PARKIO_ALERT_REPEAT_WARNING:-4h}"
GROUP_WAIT="${PARKIO_ALERT_GROUP_WAIT:-30s}"
GROUP_WAIT_CRITICAL="${PARKIO_ALERT_GROUP_WAIT_CRITICAL:-15s}"
GROUP_INTERVAL="${PARKIO_ALERT_GROUP_INTERVAL:-5m}"
RESOLVE_TIMEOUT="${PARKIO_ALERT_RESOLVE_TIMEOUT:-5m}"

if [ -z "$SLACK_URL" ] && [ -z "$WEBHOOK_URL" ]; then
  cp "$BASE_CONFIG" "$RUNTIME_CONFIG"
else
  cat > "$RUNTIME_CONFIG" <<EOF
global:
  resolve_timeout: ${RESOLVE_TIMEOUT}

route:
  receiver: "warning"
  group_by: ["alertname", "service", "severity", "component"]
  group_wait: ${GROUP_WAIT}
  group_interval: ${GROUP_INTERVAL}
  repeat_interval: ${REPEAT_WARNING}
  routes:
    - matchers:
        - severity="critical"
      receiver: "critical"
      group_wait: ${GROUP_WAIT_CRITICAL}
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
        title: '{{ if eq .Status "resolved" }}✅ Sorun çözüldü{{ else if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}⚠️ Karışık grup ({{ len .Alerts.Firing }} aktif / {{ len .Alerts.Resolved }} çözüldü){{ else if eq .CommonLabels.alertname "MunicipalSourceSecondsSinceSuccessCritical" }}🔴 Kritik — İZUM verileri güncellenemiyor{{ else if eq .CommonLabels.severity "critical" }}🔴 Kritik — {{ if .CommonLabels.alertname }}{{ .CommonLabels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ else }}⚠️ Uyarı — {{ if .CommonLabels.alertname }}{{ .CommonLabels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ end }}'
        text: '{{ if .CommonLabels.environment }}Ortam: {{ .CommonLabels.environment }}{{ "\\n" }}{{ end }}{{ if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}Grup özeti: {{ len .Alerts.Firing }} aktif, {{ len .Alerts.Resolved }} çözüldü (tamamen çözülmüş değil).{{ "\\n" }}{{ end }}{{ range .Alerts }}{{ if eq .Labels.alertname "MunicipalSourceSecondsSinceSuccessCritical" }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}Etki: Canlı doluluk bilgileri güncel olmayabilir.{{ "\\n" }}{{ if .Annotations.value }}Son başarılı güncelleme: {{ .Annotations.value }}{{ "\\n" }}{{ end }}{{ end }}Başlangıç: {{ .StartsAt.UTC.Format "2006-01-02 15:04" }} UTC{{ "\\n" }}{{ if eq .Status "resolved" }}{{ else }}İlk kontrol: İZUM senkronunu ve parking-service sağlık uçlarını doğrulayın.{{ "\\n" }}{{ end }}{{ if or .Annotations.runbook_url .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ end }}{{ if and .Annotations.runbook_url .Annotations.dashboard_url }} · {{ end }}{{ if .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ else }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}Uyarı: {{ if .Labels.alertname }}{{ .Labels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ "\\n" }}{{ if .Labels.service }}Servis: {{ .Labels.service }}{{ "\\n" }}{{ end }}{{ if .Labels.source_key }}Kaynak: {{ .Labels.source_key }}{{ "\\n" }}{{ end }}Başlangıç: {{ .StartsAt.UTC.Format "2006-01-02 15:04" }} UTC{{ "\\n" }}İlk kontrol: runbook ve izleme bağlantılarını kullanın.{{ "\\n" }}{{ end }}{{ if or .Annotations.runbook_url .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ end }}{{ if and .Annotations.runbook_url .Annotations.dashboard_url }} · {{ end }}{{ if .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ end }}{{ "\\n" }}{{ end }}'
  - name: "warning"
    slack_configs:
      - api_url: '${SLACK_URL}'
        channel: '${SLACK_CHANNEL}'
        send_resolved: true
        title: '{{ if eq .Status "resolved" }}✅ Sorun çözüldü{{ else if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}⚠️ Karışık grup ({{ len .Alerts.Firing }} aktif / {{ len .Alerts.Resolved }} çözüldü){{ else if eq .CommonLabels.alertname "MunicipalSourceSecondsSinceSuccessCritical" }}🔴 Kritik — İZUM verileri güncellenemiyor{{ else if eq .CommonLabels.severity "critical" }}🔴 Kritik — {{ if .CommonLabels.alertname }}{{ .CommonLabels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ else }}⚠️ Uyarı — {{ if .CommonLabels.alertname }}{{ .CommonLabels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ end }}'
        text: '{{ if .CommonLabels.environment }}Ortam: {{ .CommonLabels.environment }}{{ "\\n" }}{{ end }}{{ if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}Grup özeti: {{ len .Alerts.Firing }} aktif, {{ len .Alerts.Resolved }} çözüldü (tamamen çözülmüş değil).{{ "\\n" }}{{ end }}{{ range .Alerts }}{{ if eq .Labels.alertname "MunicipalSourceSecondsSinceSuccessCritical" }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}Etki: Canlı doluluk bilgileri güncel olmayabilir.{{ "\\n" }}{{ if .Annotations.value }}Son başarılı güncelleme: {{ .Annotations.value }}{{ "\\n" }}{{ end }}{{ end }}Başlangıç: {{ .StartsAt.UTC.Format "2006-01-02 15:04" }} UTC{{ "\\n" }}{{ if eq .Status "resolved" }}{{ else }}İlk kontrol: İZUM senkronunu ve parking-service sağlık uçlarını doğrulayın.{{ "\\n" }}{{ end }}{{ if or .Annotations.runbook_url .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ end }}{{ if and .Annotations.runbook_url .Annotations.dashboard_url }} · {{ end }}{{ if .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ else }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}Uyarı: {{ if .Labels.alertname }}{{ .Labels.alertname }}{{ else }}bilinmeyen-uyarı{{ end }}{{ "\\n" }}{{ if .Labels.service }}Servis: {{ .Labels.service }}{{ "\\n" }}{{ end }}{{ if .Labels.source_key }}Kaynak: {{ .Labels.source_key }}{{ "\\n" }}{{ end }}Başlangıç: {{ .StartsAt.UTC.Format "2006-01-02 15:04" }} UTC{{ "\\n" }}İlk kontrol: runbook ve izleme bağlantılarını kullanın.{{ "\\n" }}{{ end }}{{ if or .Annotations.runbook_url .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ end }}{{ if and .Annotations.runbook_url .Annotations.dashboard_url }} · {{ end }}{{ if .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ end }}{{ "\\n" }}{{ end }}'
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
