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

# Heartbeat to an external dead-man's switch (U06, CL-F04). The always-firing Watchdog alert
# goes only here, never to the operator receivers. Empty URL: its route ends at "null".
HEARTBEAT_URL="${PARKIO_ALERT_HEARTBEAT_URL:-}"
HEARTBEAT_SECRET="${PARKIO_ALERT_HEARTBEAT_SECRET:-}"
HEARTBEAT_REPEAT="${PARKIO_ALERT_HEARTBEAT_REPEAT:-2m}"
HEARTBEAT_RECEIVER="null"
if [ -n "$HEARTBEAT_URL" ]; then
  HEARTBEAT_RECEIVER="heartbeat"
fi

render_watchdog_route() {
  cat <<EOF
    # Heartbeat first: Watchdog never reaches the operator routes below (U06 dead-man's switch).
    - matchers:
        - alertname="Watchdog"
      receiver: "${HEARTBEAT_RECEIVER}"
      group_by: ["alertname"]
      group_wait: 0s
      # Alertmanager re-sends at the first flush after repeat_interval has elapsed, so the
      # period is the repeat rounded up to the next 30 s flush (2m -> 2m to 2m30s).
      group_interval: 30s
      repeat_interval: ${HEARTBEAT_REPEAT}
EOF
}

render_inhibit_rules() {
  cat <<EOF
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
EOF
}

render_heartbeat_receiver() {
  if [ -z "$HEARTBEAT_URL" ]; then
    return 0
  fi
  cat <<EOF
  - name: "heartbeat"
    webhook_configs:
      - url: '${HEARTBEAT_URL}'
        send_resolved: false
EOF
  if [ -n "$HEARTBEAT_SECRET" ]; then
    cat <<EOF
        http_config:
          authorization:
            type: Bearer
            credentials: '${HEARTBEAT_SECRET}'
EOF
  fi
}

# The Civo production overlay sets PARKIO_ALERT_REQUIRE_RECEIVER. A missing
# receiver must stop the process. Unset keeps the committed null receiver so
# local and Azure-disabled paths can still render without a webhook.
case "${PARKIO_ALERT_REQUIRE_RECEIVER:-}" in
  1|true|TRUE|yes|YES)
    if [ -z "$SLACK_URL" ] && [ -z "$WEBHOOK_URL" ]; then
      echo "ERROR: PARKIO_ALERT_REQUIRE_RECEIVER is set but neither PARKIO_ALERT_SLACK_WEBHOOK_URL nor PARKIO_ALERT_WEBHOOK_URL is set" >&2
      exit 1
    fi
    ;;
esac

if [ -z "$SLACK_URL" ] && [ -z "$WEBHOOK_URL" ]; then
  if [ -z "$HEARTBEAT_URL" ]; then
    cp "$BASE_CONFIG" "$RUNTIME_CONFIG"
  else
    # No operator receiver (local or Azure-disabled paths) but a heartbeat: the committed
    # null routing, with the Watchdog route pointed at the heartbeat receiver.
    {
      cat <<EOF
global:
  resolve_timeout: ${RESOLVE_TIMEOUT}

route:
  receiver: "null"
  group_by: ["alertname", "service", "severity", "component"]
  group_wait: ${GROUP_WAIT}
  group_interval: ${GROUP_INTERVAL}
  repeat_interval: ${REPEAT_WARNING}
  routes:
EOF
      render_watchdog_route
      cat <<EOF
    - matchers:
        - severity="critical"
      receiver: "null"
      group_wait: ${GROUP_WAIT_CRITICAL}
      repeat_interval: ${REPEAT_CRITICAL}
    - matchers:
        - severity="warning"
      receiver: "null"
      repeat_interval: ${REPEAT_WARNING}

EOF
      render_inhibit_rules
      cat <<EOF

receivers:
  - name: "null"
EOF
      render_heartbeat_receiver
    } > "$RUNTIME_CONFIG"
  fi
else
  {
    cat <<EOF
global:
  resolve_timeout: ${RESOLVE_TIMEOUT}

route:
  receiver: "warning"
  group_by: ["alertname", "service", "severity", "component"]
  group_wait: ${GROUP_WAIT}
  group_interval: ${GROUP_INTERVAL}
  repeat_interval: ${REPEAT_WARNING}
  routes:
EOF
    render_watchdog_route
    cat <<EOF
    - matchers:
        - severity="critical"
      receiver: "critical"
      group_wait: ${GROUP_WAIT_CRITICAL}
      repeat_interval: ${REPEAT_CRITICAL}
    - matchers:
        - severity="warning"
      receiver: "warning"
      repeat_interval: ${REPEAT_WARNING}

EOF
    render_inhibit_rules
    cat <<EOF

receivers:
  - name: "null"
EOF
  } > "$RUNTIME_CONFIG"

  if [ -n "$SLACK_URL" ]; then
    cat >> "$RUNTIME_CONFIG" <<EOF
  - name: "critical"
    slack_configs:
      - api_url: '${SLACK_URL}'
        channel: '${SLACK_CHANNEL}'
        send_resolved: true
        title: '{{ if eq .Status "resolved" }}✅ Sorun çözüldü{{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }} — İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }} — İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }} — OSM{{ else if .CommonLabels.source_key }} — {{ .CommonLabels.source_key }}{{ else if .CommonLabels.service }} — {{ .CommonLabels.service }}{{ end }}{{ else if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}⚠️ Karışık grup ({{ len .Alerts.Firing }} aktif / {{ len .Alerts.Resolved }} çözüldü){{ else if match "ConsecutiveFailures" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} ardışık hatalar{{ else if match "SecondsSinceSuccess" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} verileri güncellenemiyor{{ else if match "StaleRunning" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} senkron işlemi bitmedi{{ else if match "Municipal(Source|Ispark|Osm)Recovered" .CommonLabels.alertname }}✅ {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} toparlandı{{ else if eq .CommonLabels.alertname "GatewayDown" }}🔴 Kritik — Gateway kapalı{{ else if eq .CommonLabels.severity "critical" }}🔴 Kritik — {{ if .CommonAnnotations.summary }}{{ .CommonAnnotations.summary }}{{ else }}bilinmeyen uyarı{{ end }}{{ else }}⚠️ Uyarı — {{ if .CommonAnnotations.summary }}{{ .CommonAnnotations.summary }}{{ else }}bilinmeyen uyarı{{ end }}{{ end }}'
        text: '{{ if .CommonLabels.environment }}Ortam: {{ .CommonLabels.environment }}{{ "\\n" }}{{ end }}{{ if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}Grup özeti: {{ len .Alerts.Firing }} aktif, {{ len .Alerts.Resolved }} çözüldü (tamamen çözülmüş değil).{{ "\\n" }}{{ end }}{{ range .Alerts }}{{ if .Labels.source_key }}Kaynak: {{ if eq .Labels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .Labels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .Labels.source_key "osm-geofabrik-turkey" }}OSM{{ else }}{{ .Labels.source_key }}{{ end }} ({{ .Labels.source_key }}){{ "\\n" }}{{ else if .Labels.service }}Servis: {{ .Labels.service }}{{ "\\n" }}{{ end }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}{{ if match "ConsecutiveFailures" .Labels.alertname }}Durum: ardışık hatalar sürüyor.{{ "\\n" }}{{ else if match "SecondsSinceSuccess" .Labels.alertname }}Durum: başarılı güncelleme penceresi aşıldı.{{ "\\n" }}{{ else if match "StaleRunning" .Labels.alertname }}Durum: bir senkron işlemi hâlâ RUNNING görünüyor.{{ "\\n" }}{{ else if match "Municipal(Source|Ispark|Osm)Recovered" .Labels.alertname }}Durum: kaynak toparlandı.{{ "\\n" }}{{ else if .Annotations.summary }}Özet: {{ .Annotations.summary }}{{ "\\n" }}{{ end }}{{ if match "Municipal(Source|Ispark|Osm)Recovered" .Labels.alertname }}{{ else if or (eq .Labels.source_key "izmir-izum-otoparklar") (eq .Labels.source_key "istanbul-ispark-parks") }}Etki: Canlı doluluk bilgileri güncel olmayabilir.{{ "\\n" }}{{ else if eq .Labels.source_key "osm-geofabrik-turkey" }}Etki: Harita veya içe aktarma tarafı etkilenebilir; bu kaynak canlı doluluk kaynağı değildir.{{ "\\n" }}{{ else if .Labels.source_key }}Etki: Kaynak etkilenebilir; kullanıcı etkisi bu kaynağa göre değişir.{{ "\\n" }}{{ else if .Labels.service }}Etki: {{ .Labels.service }} servisi etkilenebilir.{{ "\\n" }}{{ else }}Etki: Kullanıcı etkisi bu uyarının etiketlerine göre değişir.{{ "\\n" }}{{ end }}{{ if .Annotations.operator_action }}İlk kontrol: {{ .Annotations.operator_action }}{{ "\\n" }}{{ else if .Labels.source_key }}İlk kontrol: Runbook’u açın; parking-service sağlık uçlarını doğrulayın.{{ "\\n" }}{{ else }}İlk kontrol: runbook ve izleme bağlantılarını kullanın.{{ "\\n" }}{{ end }}{{ end }}Başlangıç (UTC): {{ .StartsAt.UTC.Format "2006-01-02 15:04" }}{{ "\\n" }}{{ if .Annotations.runbook_url }}İzleme / müdahale rehberi: {{ if match "^https?://" .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.runbook_url }}{{ end }}{{ if .Annotations.dashboard_url }} · {{ if match "^https?://" .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.dashboard_url }}{{ end }}{{ end }}{{ "\\n" }}{{ else if .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if match "^https?://" .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ if .Labels.alertname }}Tanı: {{ .Labels.alertname }}{{ "\\n" }}{{ end }}{{ "\\n" }}{{ end }}'
  - name: "warning"
    slack_configs:
      - api_url: '${SLACK_URL}'
        channel: '${SLACK_CHANNEL}'
        send_resolved: true
        title: '{{ if eq .Status "resolved" }}✅ Sorun çözüldü{{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }} — İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }} — İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }} — OSM{{ else if .CommonLabels.source_key }} — {{ .CommonLabels.source_key }}{{ else if .CommonLabels.service }} — {{ .CommonLabels.service }}{{ end }}{{ else if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}⚠️ Karışık grup ({{ len .Alerts.Firing }} aktif / {{ len .Alerts.Resolved }} çözüldü){{ else if match "ConsecutiveFailures" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} ardışık hatalar{{ else if match "SecondsSinceSuccess" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} verileri güncellenemiyor{{ else if match "StaleRunning" .CommonLabels.alertname }}{{ if eq .CommonLabels.severity "critical" }}🔴 Kritik{{ else }}⚠️ Uyarı{{ end }} — {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} senkron işlemi bitmedi{{ else if match "Municipal(Source|Ispark|Osm)Recovered" .CommonLabels.alertname }}✅ {{ if eq .CommonLabels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .CommonLabels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .CommonLabels.source_key "osm-geofabrik-turkey" }}OSM{{ else if .CommonLabels.source_key }}{{ .CommonLabels.source_key }}{{ else }}belediye kaynağı{{ end }} toparlandı{{ else if eq .CommonLabels.alertname "GatewayDown" }}🔴 Kritik — Gateway kapalı{{ else if eq .CommonLabels.severity "critical" }}🔴 Kritik — {{ if .CommonAnnotations.summary }}{{ .CommonAnnotations.summary }}{{ else }}bilinmeyen uyarı{{ end }}{{ else }}⚠️ Uyarı — {{ if .CommonAnnotations.summary }}{{ .CommonAnnotations.summary }}{{ else }}bilinmeyen uyarı{{ end }}{{ end }}'
        text: '{{ if .CommonLabels.environment }}Ortam: {{ .CommonLabels.environment }}{{ "\\n" }}{{ end }}{{ if and (gt (len .Alerts.Firing) 0) (gt (len .Alerts.Resolved) 0) }}Grup özeti: {{ len .Alerts.Firing }} aktif, {{ len .Alerts.Resolved }} çözüldü (tamamen çözülmüş değil).{{ "\\n" }}{{ end }}{{ range .Alerts }}{{ if .Labels.source_key }}Kaynak: {{ if eq .Labels.source_key "izmir-izum-otoparklar" }}İZUM{{ else if eq .Labels.source_key "istanbul-ispark-parks" }}İSPARK{{ else if eq .Labels.source_key "osm-geofabrik-turkey" }}OSM{{ else }}{{ .Labels.source_key }}{{ end }} ({{ .Labels.source_key }}){{ "\\n" }}{{ else if .Labels.service }}Servis: {{ .Labels.service }}{{ "\\n" }}{{ end }}{{ if eq .Status "resolved" }}Durum: sorun çözüldü — koşul artık tetiklenmiyor.{{ "\\n" }}{{ else }}{{ if match "ConsecutiveFailures" .Labels.alertname }}Durum: ardışık hatalar sürüyor.{{ "\\n" }}{{ else if match "SecondsSinceSuccess" .Labels.alertname }}Durum: başarılı güncelleme penceresi aşıldı.{{ "\\n" }}{{ else if match "StaleRunning" .Labels.alertname }}Durum: bir senkron işlemi hâlâ RUNNING görünüyor.{{ "\\n" }}{{ else if match "Municipal(Source|Ispark|Osm)Recovered" .Labels.alertname }}Durum: kaynak toparlandı.{{ "\\n" }}{{ else if .Annotations.summary }}Özet: {{ .Annotations.summary }}{{ "\\n" }}{{ end }}{{ if match "Municipal(Source|Ispark|Osm)Recovered" .Labels.alertname }}{{ else if or (eq .Labels.source_key "izmir-izum-otoparklar") (eq .Labels.source_key "istanbul-ispark-parks") }}Etki: Canlı doluluk bilgileri güncel olmayabilir.{{ "\\n" }}{{ else if eq .Labels.source_key "osm-geofabrik-turkey" }}Etki: Harita veya içe aktarma tarafı etkilenebilir; bu kaynak canlı doluluk kaynağı değildir.{{ "\\n" }}{{ else if .Labels.source_key }}Etki: Kaynak etkilenebilir; kullanıcı etkisi bu kaynağa göre değişir.{{ "\\n" }}{{ else if .Labels.service }}Etki: {{ .Labels.service }} servisi etkilenebilir.{{ "\\n" }}{{ else }}Etki: Kullanıcı etkisi bu uyarının etiketlerine göre değişir.{{ "\\n" }}{{ end }}{{ if .Annotations.operator_action }}İlk kontrol: {{ .Annotations.operator_action }}{{ "\\n" }}{{ else if .Labels.source_key }}İlk kontrol: Runbook’u açın; parking-service sağlık uçlarını doğrulayın.{{ "\\n" }}{{ else }}İlk kontrol: runbook ve izleme bağlantılarını kullanın.{{ "\\n" }}{{ end }}{{ end }}Başlangıç (UTC): {{ .StartsAt.UTC.Format "2006-01-02 15:04" }}{{ "\\n" }}{{ if .Annotations.runbook_url }}İzleme / müdahale rehberi: {{ if match "^https?://" .Annotations.runbook_url }}{{ .Annotations.runbook_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.runbook_url }}{{ end }}{{ if .Annotations.dashboard_url }} · {{ if match "^https?://" .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.dashboard_url }}{{ end }}{{ end }}{{ "\\n" }}{{ else if .Annotations.dashboard_url }}İzleme / müdahale rehberi: {{ if match "^https?://" .Annotations.dashboard_url }}{{ .Annotations.dashboard_url }}{{ else }}https://github.com/ADBERILGEN35/parkio/blob/api/{{ reReplaceAll "^/+" "" .Annotations.dashboard_url }}{{ end }}{{ "\\n" }}{{ end }}{{ if .Labels.alertname }}Tanı: {{ .Labels.alertname }}{{ "\\n" }}{{ end }}{{ "\\n" }}{{ end }}'
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
  render_heartbeat_receiver >> "$RUNTIME_CONFIG"
fi

if [ "${PARKIO_ALERTMANAGER_VALIDATE_ONLY:-}" = "1" ]; then
  exit 0
fi

exec /bin/alertmanager \
  --config.file="$RUNTIME_CONFIG" \
  --storage.path=/alertmanager \
  --web.listen-address=:9093
