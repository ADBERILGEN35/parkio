#!/usr/bin/env bash
# Provider-neutral official-source reachability probe.
# Run from local, CI, hosted-beta host, and parking-service container.
# Does not follow unofficial mirrors, disable TLS, or retry-storm.
set -euo pipefail

if [ "${1:-}" = "" ]; then
  echo "usage: $0 <official-https-url>" >&2
  exit 2
fi

URL="$1"
UA="${PARKIO_EGRESS_PROBE_UA:-ParkioMunicipalEgressProbe/1.0}"

echo "probe_url=${URL}"
echo "probe_host=$(hostname -f 2>/dev/null || hostname)"
echo "probe_time=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

curl -sS -D - -o /tmp/parkio-muni-egress.body \
  -w "http=%{http_code} ip=%{remote_ip} connect=%{time_connect} tls=%{time_appconnect} total=%{time_total} err=%{errormsg}\n" \
  --connect-timeout 8 --max-time 15 -A "$UA" "$URL" | head -n 25

python3 - <<'PY' || true
import json, sys
path = "/tmp/parkio-muni-egress.body"
try:
    raw = open(path, "rb").read()
except OSError:
    sys.exit(0)
print(f"body_bytes={len(raw)}")
try:
    text = raw.decode("utf-8")
    print("utf8=ok")
except UnicodeDecodeError:
    print("utf8=FAIL")
    sys.exit(0)
try:
    data = json.loads(text)
except json.JSONDecodeError:
    print("json=no")
    sys.exit(0)
print("json=ok")
if isinstance(data, dict) and data.get("type") == "FeatureCollection":
    print(f"geojson_features={len(data.get('features') or [])}")
PY
