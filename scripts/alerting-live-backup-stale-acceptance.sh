#!/usr/bin/env bash
# Live backup-stale alert acceptance tooling (U06, CL-F04; Asana 1219001731640694).
#
# Proves, on the real hosted-beta host and under separate authorization, that a synthetic stale
# backup condition fires BackupStale, that one notification reaches the operator receiver, that
# the resolve is delivered, and that nothing is delivered twice. It does so WITHOUT touching the
# real backup telemetry: it writes a second node-exporter textfile that carries the eight
# parkio_backup_* gauges for a production scope the host does not use (the rule matches
# hosted-beta|azure-hosted-beta|invite-production), with a timestamp 26 h old and every status
# gauge healthy, so only BackupStale can fire for that scope. The real parkio_backup.prom is
# never read for writing, never moved and never changed.
#
#   alerting-live-backup-stale-acceptance.sh preflight [--any-host]
#       Read-only. Checks the host name, the textfile directory, the real scopes, the synthetic
#       scope it would use, Prometheus and Alertmanager readiness, the loaded BackupStale rule,
#       node_textfile_scrape_error == 0, no active Backup* alert, and records the receiver type
#       and the notification counters as the baseline. Prints no URL or secret.
#   alerting-live-backup-stale-acceptance.sh arm --yes
#       Runs the preflight, then writes the synthetic textfile atomically. Needs
#       PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE in the environment.
#   alerting-live-backup-stale-acceptance.sh observe [--until firing|resolved] [--timeout S] [--interval S]
#       Read-only polling. Appends one JSON line per poll to the evidence file and stops when the
#       alert has fired and a notification was counted (firing), or when it is gone everywhere and
#       the resolved notification was counted (resolved). BackupStale holds for 1h, so --until
#       firing takes a little over an hour.
#   alerting-live-backup-stale-acceptance.sh disarm --yes
#       Removes the synthetic textfile (only a file carrying this tool's marker). Same confirmation.
#   alerting-live-backup-stale-acceptance.sh status
#       One read-only poll.
#
# Environment: PARKIO_PROMETHEUS_TEXTFILE_DIR (default docker/prometheus/textfile under the repo;
# on the Civo host /opt/parkio/docker/prometheus/textfile), PARKIO_LIVE_PROM_URL
# (http://127.0.0.1:9090), PARKIO_LIVE_AM_URL (http://127.0.0.1:9093), PARKIO_LIVE_EXPECTED_HOSTNAME
# (parkio-civo-prod), PARKIO_LIVE_SYNTHETIC_SCOPE (chosen automatically), PARKIO_LIVE_EVIDENCE_DIR
# (./live-backup-stale-acceptance). Slack message timestamps are not readable from here; they are
# recorded by the operator from the Slack client beside this evidence.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROM_URL="${PARKIO_LIVE_PROM_URL:-http://127.0.0.1:9090}"
AM_URL="${PARKIO_LIVE_AM_URL:-http://127.0.0.1:9093}"
TEXTFILE_DIR="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-$ROOT/docker/prometheus/textfile}"
EXPECTED_HOST="${PARKIO_LIVE_EXPECTED_HOSTNAME:-parkio-civo-prod}"
EVIDENCE_DIR="${PARKIO_LIVE_EVIDENCE_DIR:-$PWD/live-backup-stale-acceptance}"
EVIDENCE="$EVIDENCE_DIR/events.jsonl"
REAL_FILE="$TEXTFILE_DIR/parkio_backup.prom"
SYNTHETIC_FILE="$TEXTFILE_DIR/parkio_backup_synthetic_stale.prom"
MARKER="# parkio-live-backup-stale-acceptance synthetic series; remove with: scripts/alerting-live-backup-stale-acceptance.sh disarm --yes"
CONFIRM_TOKEN="ALERTING-LIVE-BACKUP-STALE"
PRODUCTION_SCOPES="invite-production hosted-beta azure-hosted-beta"
STALE_AGE_SECONDS=93600
PYTHON="${PYTHON:-python3}"

usage() { sed -n '2,36p' "$0" >&2; exit 2; }
COMMAND="${1:-}"; [ -n "$COMMAND" ] || usage; shift
YES=0; ANY_HOST=0; UNTIL=firing; TIMEOUT=""; INTERVAL=30
while [ "$#" -gt 0 ]; do
  case "$1" in
    --yes) YES=1; shift ;;
    --any-host) ANY_HOST=1; shift ;;
    --until) UNTIL="$2"; shift 2 ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage ;;
  esac
done

now_utc() { date -u +%Y-%m-%dT%H:%M:%SZ; }
log() { printf '%s %s\n' "$(now_utc)" "$*"; }
evidence() {  # evidence TYPE key=value...
  mkdir -p "$EVIDENCE_DIR"
  "$PYTHON" - "$EVIDENCE" "$(now_utc)" "$@" <<'PY'
import json, sys
path, ts, kind, *pairs = sys.argv[1:]
entry = {"ts": ts, "type": kind}
for pair in pairs:
    key, _, value = pair.partition("=")
    entry[key] = value
with open(path, "a", encoding="utf-8") as fh:
    fh.write(json.dumps(entry, separators=(",", ":")) + "\n")
PY
}

http_get() { curl -fsS --max-time 10 "$@"; }
prom_result() {  # prom_result EXPR -> JSON result array (or [] on error)
  http_get "$PROM_URL/api/v1/query" --data-urlencode "query=$1" 2>/dev/null \
    | "$PYTHON" -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(d.get("data",{}).get("result",[])))' 2>/dev/null || echo '[]'
}
prom_sum() {  # prom_sum EXPR -> integer sum of sample values (0 when none)
  prom_result "$1" | "$PYTHON" -c 'import json,sys; r=json.load(sys.stdin); print(int(sum(float(x["value"][1]) for x in r)))'
}
prom_count() { prom_result "$1" | "$PYTHON" -c 'import json,sys; print(len(json.load(sys.stdin)))'; }

real_scopes() {
  [ -f "$REAL_FILE" ] || return 0
  grep -ohE 'scope="[^"]+"' "$REAL_FILE" | cut -d'"' -f2 | sort -u
}

choose_scope() {
  local real; real="$(real_scopes)"
  if [ -n "${PARKIO_LIVE_SYNTHETIC_SCOPE:-}" ]; then
    case " $PRODUCTION_SCOPES " in *" ${PARKIO_LIVE_SYNTHETIC_SCOPE} "*) ;; *)
      echo "ERROR: PARKIO_LIVE_SYNTHETIC_SCOPE must be one of: $PRODUCTION_SCOPES" >&2; return 3 ;; esac
    if printf '%s\n' "$real" | grep -qx "$PARKIO_LIVE_SYNTHETIC_SCOPE"; then
      echo "ERROR: PARKIO_LIVE_SYNTHETIC_SCOPE '$PARKIO_LIVE_SYNTHETIC_SCOPE' is the host's real backup scope; the synthetic series must not collide with it" >&2; return 3
    fi
    echo "$PARKIO_LIVE_SYNTHETIC_SCOPE"; return 0
  fi
  local scope
  for scope in $PRODUCTION_SCOPES; do
    if ! printf '%s\n' "$real" | grep -qx "$scope"; then echo "$scope"; return 0; fi
  done
  echo "ERROR: every production scope is in use on this host; no synthetic scope is free" >&2
  return 3
}

read -r -d '' PY_RECEIVERS <<'PY' || true
import json, re, sys
d = json.loads(sys.argv[1])
cfg = (d.get("config") or {}).get("original") or ""
slack = len(re.findall(r"^\s*slack_configs:", cfg, re.M))
webhook = len(re.findall(r"^\s*webhook_configs:", cfg, re.M))
channel = re.search(r"channel:\s*'?([#@][^'\s]+)", cfg)
names = re.findall(r"^\s*-\s*name:\s*\"?([A-Za-z0-9_-]+)\"?", cfg, re.M)
gw = re.search(r"group_wait:\s*(\S+)", cfg)
gi = re.search(r"group_interval:\s*(\S+)", cfg)
print("receivers=%s slack_blocks=%d webhook_blocks=%d channel=%s group_wait=%s group_interval=%s" % (
    ",".join(names), slack, webhook, channel.group(1) if channel else "-",
    gw.group(1) if gw else "-", gi.group(1) if gi else "-"))
PY

read -r -d '' PY_AM_STATE <<'PY' || true
import json, sys
alerts = json.loads(sys.argv[1])
items = []
for a in alerts:
    state = (a.get("status") or {}).get("state", "?")
    receivers = "/".join(r.get("name", "?") for r in a.get("receivers", []))
    items.append(state + ":" + receivers)
print(",".join(sorted(items)) or "absent")
PY

read -r -d '' PY_OTHER_ACTIVE <<'PY' || true
import json, sys
alerts = json.loads(sys.argv[1])
names = {(a.get("labels") or {}).get("alertname", "?") for a in alerts}
names.discard("BackupStale")
print(",".join(sorted(names)) or "none")
PY

receiver_summary() {  # parses Alertmanager's status; prints facts only, never URLs
  local body
  body="$(http_get "$AM_URL/api/v2/status" 2>/dev/null)" || { echo "receivers=unknown"; return 0; }
  "$PYTHON" -c "$PY_RECEIVERS" "$body" 2>/dev/null || echo "receivers=unknown"
}

rule_loaded() {
  http_get "$PROM_URL/api/v1/rules?type=alert" 2>/dev/null | "$PYTHON" -c '
import json, sys
d = json.load(sys.stdin)
names = {r.get("name") for g in d.get("data", {}).get("groups", []) for r in g.get("rules", [])}
sys.exit(0 if "BackupStale" in names else 1)' 2>/dev/null
}

preflight() {
  local failures=0
  log "preflight: read-only checks on $(hostname) (textfile dir $TEXTFILE_DIR)"
  if [ "$ANY_HOST" -eq 0 ] && [ "$(hostname)" != "$EXPECTED_HOST" ] && [ "$(hostname -s 2>/dev/null || true)" != "$EXPECTED_HOST" ]; then
    log "FAIL host is '$(hostname)', expected '$EXPECTED_HOST' (set PARKIO_LIVE_EXPECTED_HOSTNAME or pass --any-host for an isolated stack)"; failures=$((failures+1))
  fi
  if [ ! -d "$TEXTFILE_DIR" ]; then log "FAIL textfile directory missing: $TEXTFILE_DIR"; failures=$((failures+1)); fi
  if [ ! -f "$REAL_FILE" ]; then log "FAIL real backup textfile missing: $REAL_FILE (the acceptance needs a healthy real series next to the synthetic one)"; failures=$((failures+1)); fi
  if [ -e "$SYNTHETIC_FILE" ]; then log "FAIL synthetic textfile already present: $SYNTHETIC_FILE (disarm first)"; failures=$((failures+1)); fi
  local real; real="$(real_scopes | tr '\n' ' ')"
  log "real backup scopes: ${real:-none}"
  local scope
  if scope="$(choose_scope)"; then log "synthetic scope: $scope"; else failures=$((failures+1)); scope="-"; fi
  if http_get -o /dev/null "$PROM_URL/-/ready" 2>/dev/null; then log "ok Prometheus ready"; else log "FAIL Prometheus not ready at $PROM_URL"; failures=$((failures+1)); fi
  if http_get -o /dev/null "$AM_URL/-/ready" 2>/dev/null; then log "ok Alertmanager ready"; else log "FAIL Alertmanager not ready at $AM_URL"; failures=$((failures+1)); fi
  if rule_loaded; then log "ok BackupStale rule loaded"; else log "FAIL BackupStale rule is not loaded in Prometheus"; failures=$((failures+1)); fi
  local scrape_err; scrape_err="$(prom_sum 'node_textfile_scrape_error{job="node-exporter"}')"
  if [ "$scrape_err" = 0 ]; then log "ok node_textfile_scrape_error is 0"; else log "FAIL node_textfile_scrape_error is $scrape_err"; failures=$((failures+1)); fi
  local active; active="$(prom_count 'ALERTS{alertname=~"Backup.*"}')"
  if [ "$active" = 0 ]; then log "ok no Backup* alert pending or firing"; else log "FAIL $active Backup* alert series active; the acceptance needs a quiet baseline"; failures=$((failures+1)); fi
  local recv; recv="$(receiver_summary)"; log "alertmanager: $recv"
  case "$recv" in *slack_blocks=0*webhook_blocks=0*|receivers=unknown) log "FAIL no operator receiver rendered (null config?)"; failures=$((failures+1)) ;; esac
  local sent failed
  sent="$(prom_sum 'alertmanager_notifications_total{integration=~"slack|webhook"}')"
  failed="$(prom_sum 'alertmanager_notifications_failed_total{integration=~"slack|webhook"}')"
  log "baseline counters: notifications_total=$sent notifications_failed_total=$failed"
  evidence preflight "host=$(hostname)" "textfile_dir=$TEXTFILE_DIR" "real_scopes=${real:-none}" "synthetic_scope=$scope" "receivers=$recv" "notifications_total=$sent" "notifications_failed_total=$failed" "failures=$failures"
  if [ "$failures" -ne 0 ]; then log "PREFLIGHT: FAIL ($failures)"; return 1; fi
  log "PREFLIGHT: PASS"
}

require_confirmation() {
  if [ "${PARKIO_LIVE_ALERT_ACCEPTANCE:-}" != "$CONFIRM_TOKEN" ] || [ "$YES" -ne 1 ]; then
    echo "REFUSED: this changes the live host. Set PARKIO_LIVE_ALERT_ACCEPTANCE=$CONFIRM_TOKEN and pass --yes, under a recorded authorization." >&2
    exit 3
  fi
}

write_synthetic() {
  local scope="$1" stale
  stale=$(( $(date +%s) - STALE_AGE_SECONDS ))
  local tmp; tmp="$(mktemp "$TEXTFILE_DIR/.parkio_backup_synthetic_stale.XXXXXX")"
  {
    echo "$MARKER"
    echo "# written $(now_utc); stale timestamp $stale; every status gauge healthy, production_mode 0, so only BackupStale can fire for scope \"$scope\"."
    printf '# HELP parkio_backup_last_success 1 when the last hosted-beta backup succeeded.\n# TYPE parkio_backup_last_success gauge\nparkio_backup_last_success{scope="%s"} 1\n' "$scope"
    printf '# HELP parkio_backup_last_timestamp_seconds Unix epoch of the last completed backup attempt.\n# TYPE parkio_backup_last_timestamp_seconds gauge\nparkio_backup_last_timestamp_seconds{scope="%s"} %s\n' "$scope" "$stale"
    printf '# HELP parkio_backup_databases_failed Number of database dumps that failed in the last run.\n# TYPE parkio_backup_databases_failed gauge\nparkio_backup_databases_failed{scope="%s"} 0\n' "$scope"
    printf '# HELP parkio_backup_minio_objects Object count in the mirrored MinIO bucket when known.\n# TYPE parkio_backup_minio_objects gauge\nparkio_backup_minio_objects{scope="%s",bucket="synthetic"} 0\n' "$scope"
    printf '# HELP parkio_backup_offsite_last_success 1 when the last offsite upload succeeded.\n# TYPE parkio_backup_offsite_last_success gauge\nparkio_backup_offsite_last_success{scope="%s"} 1\n' "$scope"
    printf '# HELP parkio_backup_encryption_enabled 1 when DB dumps were encrypted.\n# TYPE parkio_backup_encryption_enabled gauge\nparkio_backup_encryption_enabled{scope="%s"} 1\n' "$scope"
    printf '# HELP parkio_backup_last_bytes Approximate local stamp size in bytes.\n# TYPE parkio_backup_last_bytes gauge\nparkio_backup_last_bytes{scope="%s"} 0\n' "$scope"
    printf '# HELP parkio_backup_production_mode 1 when BACKUP_PRODUCTION_MODE was set for the last run.\n# TYPE parkio_backup_production_mode gauge\nparkio_backup_production_mode{scope="%s"} 0\n' "$scope"
  } > "$tmp"
  chmod 0644 "$tmp"
  mv -f "$tmp" "$SYNTHETIC_FILE"
  echo "$stale"
}

arm() {
  require_confirmation
  preflight
  local scope; scope="$(choose_scope)"
  local stale; stale="$(write_synthetic "$scope")"
  log "ARMED: $SYNTHETIC_FILE scope=$scope stale_timestamp=$stale (BackupStale needs > 90000 s of staleness, held 1h)"
  evidence arm "synthetic_scope=$scope" "stale_timestamp=$stale" "file=$SYNTHETIC_FILE"
  log "next: $0 observe --until firing   (about 61 to 65 minutes), then record the Slack message, then: $0 disarm --yes"
}

poll_once() {  # poll_once SCOPE -> prints one summary line, appends evidence, sets globals
  local scope="$1" body
  P_STATE="$(prom_result "ALERTS{alertname=\"BackupStale\",scope=\"$scope\"}" | "$PYTHON" -c 'import json,sys; r=json.load(sys.stdin); print(",".join(sorted(x["metric"].get("alertstate","?") for x in r)) or "inactive")')"
  if body="$(http_get "$AM_URL/api/v2/alerts?filter=alertname%3D%22BackupStale%22&filter=scope%3D%22$scope%22" 2>/dev/null)"; then
    AM_STATE="$("$PYTHON" -c "$PY_AM_STATE" "$body" 2>/dev/null || echo unknown)"
  else
    AM_STATE=unknown
  fi
  if body="$(http_get "$AM_URL/api/v2/alerts?active=true" 2>/dev/null)"; then
    OTHER_ACTIVE="$("$PYTHON" -c "$PY_OTHER_ACTIVE" "$body" 2>/dev/null || echo unknown)"
  else
    OTHER_ACTIVE=unknown
  fi
  SENT="$(prom_sum 'alertmanager_notifications_total{integration=~"slack|webhook"}')"
  FAILED="$(prom_sum 'alertmanager_notifications_failed_total{integration=~"slack|webhook"}')"
  log "prometheus=$P_STATE alertmanager=$AM_STATE other_active=$OTHER_ACTIVE notifications_total=$SENT notifications_failed_total=$FAILED"
  evidence poll "synthetic_scope=$scope" "prometheus=$P_STATE" "alertmanager=$AM_STATE" "other_active=$OTHER_ACTIVE" "notifications_total=$SENT" "notifications_failed_total=$FAILED"
}

observe() {
  local scope
  if [ -f "$SYNTHETIC_FILE" ]; then scope="$(grep -ohE 'scope="[^"]+"' "$SYNTHETIC_FILE" | head -n 1 | cut -d'"' -f2)"; else scope="$(choose_scope)"; fi
  case "$UNTIL" in firing|resolved) ;; *) echo "ERROR: --until must be firing or resolved" >&2; exit 2 ;; esac
  [ -n "$TIMEOUT" ] || { if [ "$UNTIL" = firing ]; then TIMEOUT=5400; else TIMEOUT=1500; fi; }
  local start; start="$(date +%s)"
  poll_once "$scope"
  local base_sent="$SENT" base_failed="$FAILED"
  log "observe --until $UNTIL (timeout ${TIMEOUT}s, interval ${INTERVAL}s, baseline notifications_total=$base_sent)"
  while true; do
    sleep "$INTERVAL"
    poll_once "$scope"
    if [ "$FAILED" -gt "$base_failed" ]; then log "WARN notifications_failed_total rose from $base_failed to $FAILED: the receiver refused a delivery (AlertmanagerNotificationsFailing fires at two)"; fi
    if [ "$UNTIL" = firing ] && [ "$P_STATE" = firing ] && [ "$AM_STATE" != absent ] && [ "$AM_STATE" != unknown ] && [ "$SENT" -gt "$base_sent" ]; then
      log "FIRING DELIVERED: BackupStale{scope=$scope} firing in Prometheus, held by Alertmanager, notifications_total $base_sent -> $SENT (other active alerts in the window: $OTHER_ACTIVE)"
      evidence milestone "name=firing_delivered" "synthetic_scope=$scope" "notifications_total_before=$base_sent" "notifications_total_after=$SENT" "other_active=$OTHER_ACTIVE"
      log "now record the Slack message timestamp, then run: $0 disarm --yes && $0 observe --until resolved"
      return 0
    fi
    if [ "$UNTIL" = resolved ] && [ "$P_STATE" = inactive ] && [ "$AM_STATE" = absent ] && [ "$SENT" -gt "$base_sent" ]; then
      log "RESOLVED DELIVERED: BackupStale{scope=$scope} inactive in Prometheus, gone from Alertmanager, notifications_total $base_sent -> $SENT (other active alerts in the window: $OTHER_ACTIVE)"
      evidence milestone "name=resolved_delivered" "synthetic_scope=$scope" "notifications_total_before=$base_sent" "notifications_total_after=$SENT" "other_active=$OTHER_ACTIVE"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
      log "TIMEOUT after ${TIMEOUT}s waiting for $UNTIL (prometheus=$P_STATE alertmanager=$AM_STATE notifications_total=$SENT)"
      evidence milestone "name=timeout" "until=$UNTIL" "prometheus=$P_STATE" "alertmanager=$AM_STATE"
      return 1
    fi
  done
}

disarm() {
  require_confirmation
  if [ ! -f "$SYNTHETIC_FILE" ]; then log "nothing to disarm: $SYNTHETIC_FILE is absent"; evidence disarm "result=absent"; return 0; fi
  if ! grep -qF "$MARKER" "$SYNTHETIC_FILE"; then
    echo "REFUSED: $SYNTHETIC_FILE does not carry this tool's marker; not removing a file this tool did not write" >&2; exit 3
  fi
  rm -f "$SYNTHETIC_FILE"
  log "DISARMED: removed $SYNTHETIC_FILE; the alert resolves after the next scrape and Alertmanager sends the resolved notification at its next group flush"
  evidence disarm "result=removed" "file=$SYNTHETIC_FILE"
}

status() {
  local scope
  if [ -f "$SYNTHETIC_FILE" ]; then scope="$(grep -ohE 'scope="[^"]+"' "$SYNTHETIC_FILE" | head -n 1 | cut -d'"' -f2)"; log "synthetic file present (scope $scope)"; else scope="$(choose_scope 2>/dev/null || echo '-')"; log "synthetic file absent"; fi
  [ "$scope" != "-" ] && poll_once "$scope" || true
}

case "$COMMAND" in
  preflight) preflight ;;
  arm) arm ;;
  observe) observe ;;
  disarm) disarm ;;
  status) status ;;
  *) usage ;;
esac
