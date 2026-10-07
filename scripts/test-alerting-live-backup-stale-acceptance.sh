#!/usr/bin/env bash
# Tests for scripts/alerting-live-backup-stale-acceptance.sh (U06): fake Prometheus/Alertmanager
# answers through a fake curl, a fake hostname, and a temporary textfile directory. No network,
# no docker, no host change.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/alerting-live-backup-stale-acceptance.sh"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/parkio-live-stale.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
ok() { echo "PASS $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL $1"; FAIL=$((FAIL + 1)); }

FAKE_BIN="$TMP/bin"; mkdir -p "$FAKE_BIN" "$TMP/textfile" "$TMP/evidence"
# Fake curl: answers by URL. Behaviour knobs come from FAKE_* variables.
cat > "$FAKE_BIN/curl" <<'FAKE'
#!/usr/bin/env bash
url=""; query=""; out=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --data-urlencode) query="${2#query=}"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
case "$url" in
  */-/ready) [ "${FAKE_READY:-1}" = 1 ] || exit 22; if [ -n "$out" ]; then echo OK > "$out"; else echo OK; fi; exit 0 ;;
  */api/v1/rules*) echo '{"status":"success","data":{"groups":[{"name":"parkio-critical","rules":[{"name":"BackupFailed"},{"name":"'"${FAKE_RULE:-BackupStale}"'"}]}]}}' ;;
  */api/v1/query)
    case "$query" in
      *node_textfile_scrape_error*) echo '{"status":"success","data":{"result":[{"metric":{},"value":[0,"'"${FAKE_SCRAPE_ERR:-0}"'"]}]}}' ;;
      *'ALERTS{alertname=~"Backup.*"}'*) if [ "${FAKE_BACKUP_ACTIVE:-0}" = 1 ]; then echo '{"status":"success","data":{"result":[{"metric":{"alertname":"BackupStale","alertstate":"pending"},"value":[0,"1"]}]}}'; else echo '{"status":"success","data":{"result":[]}}'; fi ;;
      *'ALERTS{alertname="BackupStale"'*) echo '{"status":"success","data":{"result":'"${FAKE_PROM_ALERTS:-[]}"'}}' ;;
      *notifications_failed_total*) echo '{"status":"success","data":{"result":[{"metric":{},"value":[0,"'"${FAKE_FAILED:-0}"'"]}]}}' ;;
      *notifications_total*) echo '{"status":"success","data":{"result":[{"metric":{},"value":[0,"'"${FAKE_SENT:-7}"'"]}]}}' ;;
      *) echo '{"status":"success","data":{"result":[]}}' ;;
    esac ;;
  */api/v2/status) printf '{"config":{"original":"global:\\n  resolve_timeout: 5m\\nroute:\\n  receiver: \\"warning\\"\\n  group_wait: 30s\\n  group_interval: 5m\\nreceivers:\\n  - name: \\"critical\\"\\n    slack_configs:\\n      - api_url: %s\\n        channel: %s\\n  - name: \\"warning\\"\\n    slack_configs:\\n      - api_url: %s\\n        channel: %s\\n"}}\n' "'https://hooks.slack.com/services/FAKE/NOT/ASECRET'" "'#parkio-alert'" "'https://hooks.slack.com/services/FAKE/NOT/ASECRET'" "'#parkio-alert'" ;;
  */api/v2/alerts*) echo "${FAKE_AM_ALERTS:-[]}" ;;
  *) echo "fake curl: unexpected url $url" >&2; exit 1 ;;
esac
FAKE
printf '#!/usr/bin/env bash\necho "${FAKE_HOSTNAME:-parkio-civo-prod}"\n' > "$FAKE_BIN/hostname"
chmod +x "$FAKE_BIN/curl" "$FAKE_BIN/hostname"
printf '# HELP parkio_backup_last_success x\n# TYPE parkio_backup_last_success gauge\nparkio_backup_last_success{scope="azure-hosted-beta"} 1\nparkio_backup_last_timestamp_seconds{scope="azure-hosted-beta"} 1790739036\n' > "$TMP/textfile/parkio_backup.prom"

run() {  # run [ENV=VAL ...] -- args... ; output in $TMP/out, rc returned
  local envs=()
  while [ "$1" != "--" ]; do envs+=("$1"); shift; done; shift
  if env "${envs[@]}" PATH="$FAKE_BIN:$PATH" PARKIO_PROMETHEUS_TEXTFILE_DIR="$TMP/textfile" PARKIO_LIVE_EVIDENCE_DIR="$TMP/evidence" "$SCRIPT" "$@" > "$TMP/out" 2>&1; then return 0; else return $?; fi
}

if run -- preflight; then
  grep -q 'synthetic scope: invite-production' "$TMP/out" && grep -q 'real backup scopes: azure-hosted-beta' "$TMP/out" && grep -q 'PREFLIGHT: PASS' "$TMP/out" \
    && ok "preflight passes and picks the first free production scope" || { bad "preflight output"; cat "$TMP/out"; }
else bad "preflight failed on a healthy fake"; cat "$TMP/out"; fi
if grep -q 'FAKE/NOT/ASECRET' "$TMP/out"; then bad "preflight printed the receiver URL"; else ok "preflight never prints the receiver URL"; fi
grep -q 'slack_blocks=2' "$TMP/out" && grep -q 'channel=#parkio-alert' "$TMP/out" && ok "preflight reports receiver type and channel" || bad "preflight receiver summary"
grep -q 'baseline counters: notifications_total=7' "$TMP/out" && ok "preflight records the notification baseline" || bad "preflight baseline"

if run FAKE_HOSTNAME=other-host -- preflight; then bad "wrong host accepted"; else grep -q "FAIL host is 'other-host'" "$TMP/out" && ok "preflight refuses another host" || bad "host refusal message"; fi
if run FAKE_HOSTNAME=other-host -- preflight --any-host; then ok "--any-host allows an isolated stack"; else bad "--any-host refused"; cat "$TMP/out"; fi
if run FAKE_BACKUP_ACTIVE=1 -- preflight; then bad "active Backup* alert accepted"; else grep -q 'Backup\* alert series active' "$TMP/out" && ok "preflight refuses a noisy baseline" || bad "noisy baseline message"; fi
if run FAKE_SCRAPE_ERR=1 -- preflight; then bad "scrape error accepted"; else grep -q 'node_textfile_scrape_error is 1' "$TMP/out" && ok "preflight refuses a textfile scrape error" || bad "scrape error message"; fi
if run FAKE_RULE=SomethingElse -- preflight; then bad "missing rule accepted"; else grep -q 'BackupStale rule is not loaded' "$TMP/out" && ok "preflight refuses when BackupStale is not loaded" || bad "rule message"; fi
if run PARKIO_LIVE_SYNTHETIC_SCOPE=azure-hosted-beta -- preflight; then bad "collision with the real scope accepted"; else grep -q "is the host's real backup scope" "$TMP/out" && ok "a synthetic scope equal to the real scope is refused" || bad "collision message"; fi

if run -- arm --yes; then bad "arm without the confirmation token succeeded"; else grep -q 'REFUSED' "$TMP/out" && [ ! -e "$TMP/textfile/parkio_backup_synthetic_stale.prom" ] && ok "arm refuses without the confirmation token" || bad "arm refusal"; fi
if run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- arm; then bad "arm without --yes succeeded"; else ok "arm refuses without --yes"; fi
if run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- arm --yes; then
  f="$TMP/textfile/parkio_backup_synthetic_stale.prom"
  if [ -f "$f" ] && [ "$(grep -c '^parkio_backup_' "$f")" = 8 ] && [ "$(grep -c 'scope="invite-production"' "$f")" = 8 ] && grep -q 'parkio-live-backup-stale-acceptance synthetic series' "$f" \
     && grep -q 'parkio_backup_production_mode{scope="invite-production"} 0' "$f" && grep -q 'parkio_backup_last_success{scope="invite-production"} 1' "$f"; then ok "arm writes the eight gauges for the synthetic scope with the marker"; else bad "synthetic file content"; cat "$f"; fi
  stale="$(grep -oE 'parkio_backup_last_timestamp_seconds\{[^}]*\} [0-9]+' "$f" | awk '{print $2}')"; age=$(( $(date +%s) - stale ))
  if [ "$age" -ge 93540 ] && [ "$age" -le 93700 ]; then ok "the synthetic timestamp is 26 h old"; else bad "synthetic age $age"; fi
  grep -q 'parkio_backup_last_success{scope="azure-hosted-beta"} 1' "$TMP/textfile/parkio_backup.prom" && ok "the real textfile is untouched" || bad "real textfile changed"
  [ "$(stat -c %a "$f")" = 644 ] && ok "synthetic file mode 0644" || bad "synthetic file mode $(stat -c %a "$f")"
else bad "arm with the token failed"; cat "$TMP/out"; fi
if run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- arm --yes; then bad "second arm succeeded"; else grep -q 'synthetic textfile already present' "$TMP/out" && ok "arm refuses while armed" || bad "re-arm message"; fi

FIRING='[{"metric":{"alertname":"BackupStale","alertstate":"firing","scope":"invite-production"},"value":[0,"1"]}]'
AM='[{"status":{"state":"active"},"receivers":[{"name":"critical"}],"labels":{"alertname":"BackupStale","scope":"invite-production"}}]'
if run FAKE_PROM_ALERTS="$FIRING" FAKE_AM_ALERTS="$AM" FAKE_SENT=9 -- status; then grep -q 'prometheus=firing alertmanager=active:critical' "$TMP/out" && ok "status reports Prometheus and Alertmanager state" || { bad "status output"; cat "$TMP/out"; }; else bad "status failed"; cat "$TMP/out"; fi
if run FAKE_PROM_ALERTS="$FIRING" FAKE_AM_ALERTS="$AM" FAKE_SENT=9 -- observe --until firing --timeout 3 --interval 1; then bad "observe declared delivery with no counter increase"; else grep -q 'TIMEOUT' "$TMP/out" && ok "observe does not count a firing alert as delivered without a notification increase" || bad "observe timeout output"; fi
if run FAKE_PROM_ALERTS="$FIRING" FAKE_AM_ALERTS="$AM" -- observe --until bogus; then bad "bad --until accepted"; else ok "observe refuses an unknown --until"; fi

echo "not mine" > "$TMP/textfile/parkio_backup_synthetic_stale.prom"
if run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- disarm --yes; then bad "disarm removed a file without the marker"; else grep -q 'does not carry this tool' "$TMP/out" && [ -f "$TMP/textfile/parkio_backup_synthetic_stale.prom" ] && ok "disarm refuses a file it did not write" || bad "disarm marker refusal"; fi
rm -f "$TMP/textfile/parkio_backup_synthetic_stale.prom"
run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- arm --yes >/dev/null || true
if run PARKIO_LIVE_ALERT_ACCEPTANCE=ALERTING-LIVE-BACKUP-STALE -- disarm --yes; then [ ! -e "$TMP/textfile/parkio_backup_synthetic_stale.prom" ] && grep -q 'DISARMED' "$TMP/out" && ok "disarm removes the marked synthetic file" || bad "disarm result"; else bad "disarm failed"; cat "$TMP/out"; fi
if run -- disarm --yes; then bad "disarm without the token succeeded"; else ok "disarm refuses without the confirmation token"; fi
[ -s "$TMP/evidence/events.jsonl" ] && "${PYTHON:-python3}" -c 'import json,sys; [json.loads(l) for l in open(sys.argv[1])]' "$TMP/evidence/events.jsonl" && ok "evidence file is valid JSON lines" || bad "evidence file"
if grep -rq 'FAKE/NOT/ASECRET' "$TMP/evidence"; then bad "evidence contains the receiver URL"; else ok "evidence never contains the receiver URL"; fi

echo; echo "=== alerting-live-backup-stale-acceptance tests: pass=$PASS fail=$FAIL ==="; [ "$FAIL" -eq 0 ]
