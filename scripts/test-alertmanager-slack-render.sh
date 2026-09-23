#!/usr/bin/env bash
# Execute Slack title/text templates with the deployed Alertmanager image
# (prom/alertmanager:v0.27.0) against a local mock receiver. Not amtool-only.
# Never points at a real Slack webhook and never touches production.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AM_IMAGE="${ALERTMANAGER_IMAGE:-prom/alertmanager:v0.27.0}"
AM_NAME="parkio-am-render-$$"
RECEIPTS="$(mktemp -d "${TMPDIR:-/tmp}/parkio-am-render.XXXXXX")"
MOCK_PORT="${PARKIO_AM_RENDER_PORT:-18080}"
AM_PORT="${PARKIO_AM_RENDER_AM_PORT:-19093}"
MOCK_PID=""
pass=0
fail=0

ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

cleanup() {
  if [ -n "${MOCK_PID}" ]; then
    kill "${MOCK_PID}" >/dev/null 2>&1 || true
    wait "${MOCK_PID}" >/dev/null 2>&1 || true
  fi
  docker rm -f "${AM_NAME}" >/dev/null 2>&1 || true
  rm -rf "${RECEIPTS}"
}
trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is required for Alertmanager template execution" >&2
  exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 is required for the mock Slack receiver" >&2
  exit 2
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl is required to post isolated alerts" >&2
  exit 2
fi

if grep -E '\|[[:space:]]*default\b' "${ROOT}/docker/alertmanager/render-config.sh"; then
  echo "ERROR: render-config.sh still uses unsupported | default" >&2
  exit 1
fi

export PARKIO_AM_RENDER_RECEIPTS="${RECEIPTS}"
export PARKIO_AM_RENDER_HOST=127.0.0.1
export PARKIO_AM_RENDER_PORT="${MOCK_PORT}"
python3 "${ROOT}/scripts/ci/alertmanager-slack-mock.py" &
MOCK_PID=$!

for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:${MOCK_PORT}/health" >/dev/null 2>&1 && break
  sleep 0.2
done
curl -fsS "http://127.0.0.1:${MOCK_PORT}/health" >/dev/null

docker run -d --name "${AM_NAME}" \
  --add-host=host.docker.internal:host-gateway \
  -p "127.0.0.1:${AM_PORT}:9093" \
  -v "${ROOT}/docker/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  -v "${ROOT}/docker/alertmanager/render-config.sh:/etc/alertmanager/render-config.sh:ro" \
  -e PARKIO_ALERT_SLACK_WEBHOOK_URL="http://host.docker.internal:${MOCK_PORT}/slack" \
  -e PARKIO_ALERT_SLACK_CHANNEL='#am-render-test' \
  -e PARKIO_ALERT_GROUP_WAIT=1s \
  -e PARKIO_ALERT_GROUP_WAIT_CRITICAL=1s \
  -e PARKIO_ALERT_GROUP_INTERVAL=1s \
  -e PARKIO_ALERT_REPEAT_CRITICAL=1h \
  -e PARKIO_ALERT_REPEAT_WARNING=1h \
  --entrypoint /bin/sh \
  "${AM_IMAGE}" \
  /etc/alertmanager/render-config.sh >/dev/null

for _ in $(seq 1 40); do
  curl -fsS "http://127.0.0.1:${AM_PORT}/-/ready" >/dev/null 2>&1 && break
  sleep 0.5
done
curl -fsS "http://127.0.0.1:${AM_PORT}/-/ready" >/dev/null
AM_VERSION="$(docker exec "${AM_NAME}" /bin/alertmanager --version 2>&1 | tr '\n' ' ')"
echo "AM_VERSION ${AM_VERSION}"
echo "AM_IMAGE ${AM_IMAGE}"
docker inspect "${AM_NAME}" --format 'AM_IMAGE_ID={{.Image}}'

post_alerts() {
  curl -fsS -X POST -H 'Content-Type: application/json' \
    --data "$1" \
    "http://127.0.0.1:${AM_PORT}/api/v2/alerts" >/dev/null
}

wait_receipts() {
  local want="$1"
  local i
  for i in $(seq 1 40); do
    if [ "$(find "${RECEIPTS}" -name '*.json' | wc -l | tr -d ' ')" -ge "${want}" ]; then
      return 0
    fi
    sleep 0.5
  done
  echo "receipts so far:" >&2
  ls -la "${RECEIPTS}" >&2 || true
  return 1
}

post_alerts '[{"labels":{"alertname":"SyntheticRenderFiring","severity":"warning","service":"gateway-service","component":"render-test"},"annotations":{},"startsAt":"2026-09-23T16:00:00.000Z"}]'
wait_receipts 1
python3 - "${RECEIPTS}" <<'PY'
import json, pathlib, sys
recs=[json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(sys.argv[1]).glob("*.json"))]
blob="\n".join((r.get("title") or "")+"\n"+(r.get("text") or "") for r in recs)
assert 'function "default" not defined' not in blob
assert "SyntheticRenderFiring" in blob
assert "Gateway health check failed." not in blob
assert "Uyarı: SyntheticRenderFiring" in blob
print("firing-ok")
PY
ok "firing warning renders without | default and without raw description"

post_alerts '[{"labels":{"alertname":"SyntheticRenderFiring","severity":"warning","service":"gateway-service","component":"render-test"},"annotations":{},"startsAt":"2026-09-23T16:00:00.000Z","endsAt":"2026-09-23T16:05:00.000Z"}]'
wait_receipts 2
python3 - "${RECEIPTS}" <<'PY'
import json, pathlib, sys
recs=[json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(sys.argv[1]).glob("*.json"))]
blob="\n".join((r.get("title") or "")+"\n"+(r.get("text") or "") for r in recs)
assert "Sorun çözüldü" in blob or "çözüldü" in blob
print("resolved-ok")
PY
ok "resolved notification renders"

post_alerts '[{"labels":{"alertname":"SyntheticRenderGrouped","severity":"warning","service":"user-service","component":"render-test","instance":"a"},"annotations":{},"startsAt":"2026-09-23T16:10:00.000Z"},{"labels":{"alertname":"SyntheticRenderGrouped","severity":"warning","service":"user-service","component":"render-test","instance":"b"},"annotations":{},"startsAt":"2026-09-23T16:10:01.000Z"}]'
wait_receipts 3
python3 - "${RECEIPTS}" <<'PY'
import json, pathlib, sys
recs=[json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(sys.argv[1]).glob("*.json"))]
grouped=[r for r in recs if "SyntheticRenderGrouped" in ((r.get("title") or "")+(r.get("text") or ""))]
assert grouped, recs
text=grouped[-1].get("text") or ""
assert text.count("SyntheticRenderGrouped") >= 2
print("grouped-ok")
PY
ok "grouped firing renders both alerts"

post_alerts '[{"labels":{"alertname":"SyntheticRenderMixed","severity":"critical","service":"parking-service","component":"render-test","instance":"live"},"annotations":{},"startsAt":"2026-09-23T16:20:00.000Z"},{"labels":{"alertname":"SyntheticRenderMixed","severity":"critical","service":"parking-service","component":"render-test","instance":"done"},"annotations":{},"startsAt":"2026-09-23T16:15:00.000Z","endsAt":"2026-09-23T16:18:00.000Z"}]'
wait_receipts 4
python3 - "${RECEIPTS}" <<'PY'
import json, pathlib, sys
recs=[json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(sys.argv[1]).glob("*.json"))]
mixed=[r for r in recs if "SyntheticRenderMixed" in ((r.get("title") or "")+(r.get("text") or "")) or "Karışık" in ((r.get("title") or ""))]
assert mixed, recs
blob=(mixed[-1].get("title") or "")+"\n"+(mixed[-1].get("text") or "")
assert "Karışık" in blob or ("aktif" in blob and "çözüldü" in blob)
print("mixed-ok")
PY
ok "mixed-status group renders"

post_alerts '[{"labels":{"severity":"warning","service":"media-service","component":"render-test"},"annotations":{},"startsAt":"2026-09-23T16:30:00.000Z"}]'
wait_receipts 5
python3 - "${RECEIPTS}" <<'PY'
import json, pathlib, sys
recs=[json.loads(p.read_text(encoding="utf-8")) for p in sorted(pathlib.Path(sys.argv[1]).glob("*.json"))]
blob=(recs[-1].get("title") or "")+"\n"+(recs[-1].get("text") or "")
assert 'function "default" not defined' not in blob
assert "bilinmeyen-uyarı" in blob or "media-service" in blob
print("missing-label-ok")
PY
ok "missing alertname / annotations still render"

failed="$(curl -fsS "http://127.0.0.1:${AM_PORT}/metrics" | awk -F' ' '/alertmanager_notifications_failed_total\{integration="slack"/ {s+=$2} END {print s+0}')"
if [ "${failed}" = "0" ]; then
  ok "Alertmanager slack failed_total remains 0"
else
  bad "Alertmanager recorded slack failures: ${failed}"
fi

echo
echo "=== alertmanager slack render: pass=${pass} fail=${fail} image=${AM_IMAGE} ==="
echo "=== ${AM_VERSION} ==="
if [ "${fail}" -ne 0 ]; then
  exit 1
fi
