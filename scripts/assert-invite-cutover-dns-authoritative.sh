#!/usr/bin/env bash
#
# Authoritative DNS precondition for invite-production public cutover (03E-A1).
#
# Before acme=true deploy starts Caddy, every production hostname must resolve
# authoritatively to the invite-production public IP. Recursive resolver checks
# are evidence only; this guard uses the zone's authoritative nameservers.
#
# Usage:
#   assert-invite-cutover-dns-authoritative.sh [--expected-ip IPV4]
#
# Test fixtures:
#   PARKIO_CUTOVER_DNS_FIXTURE_FILE=/path/to.json
#     JSON object mapping hostname -> IPv4 (skips live DNS queries)
#   PARKIO_INVITE_PRODUCTION_PUBLIC_IP=1.2.3.4
#     Overrides Azure lookup for expected IP
#
# Exit 0 = all hostnames match. Exit 4 = mismatch / missing record.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# shellcheck source=lib/resolve-invite-production-public-ip.sh
source "$ROOT/scripts/lib/resolve-invite-production-public-ip.sh"

EXPECTED_IP=""
FIXTURE_FILE="${PARKIO_CUTOVER_DNS_FIXTURE_FILE:-}"
HOSTNAMES=(app.parkio.dev api.parkio.dev media.parkio.dev)
AUTH_NS=(hermes.dns-parking.com artemis.dns-parking.com)

while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-ip) EXPECTED_IP="${2:-}"; shift 2 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

errors=0
note() { echo "  $1"; }
bad() { echo "ERROR: $1" >&2; errors=$((errors + 1)); }

if [ -z "$EXPECTED_IP" ]; then
  EXPECTED_IP="$(parkio_resolve_invite_production_public_ip)" || exit 2
fi

if ! [[ "$EXPECTED_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "ERROR: expected invite-production public IP is not a valid IPv4 address" >&2
  exit 2
fi

echo "=== invite-production cutover authoritative DNS guard ==="
echo "expected_public_ip=$EXPECTED_IP"
echo "resource_group=${PARKIO_INVITE_PRODUCTION_RESOURCE_GROUP}"
echo "public_ip_name=${PARKIO_INVITE_PRODUCTION_PUBLIC_IP_NAME}"

lookup_fixture() {
  local host="$1"
  python3 - "$FIXTURE_FILE" "$host" <<'PY'
import json, sys
fixture = json.load(open(sys.argv[1]))
value = fixture.get(sys.argv[2])
if value is None:
    raise SystemExit(1)
print(value)
PY
}

lookup_authoritative() {
  local host="$1"
  local ns ip found=""
  for ns in "${AUTH_NS[@]}"; do
    if ! command -v dig >/dev/null 2>&1; then
      bad "dig is required for authoritative DNS verification (or set PARKIO_CUTOVER_DNS_FIXTURE_FILE)"
      return 1
    fi
    ip="$(dig +short A "$host" @"$ns" 2>/dev/null | head -n 1 || true)"
    if [ -n "$ip" ]; then
      found="$ip"
      note "authoritative $host @$ns -> $ip"
      break
    fi
  done
  if [ -z "$found" ]; then
    bad "no authoritative A record for $host via ${AUTH_NS[*]}"
    return 1
  fi
  printf '%s' "$found"
}

for host in "${HOSTNAMES[@]}"; do
  if [ -n "$FIXTURE_FILE" ]; then
    if ! resolved="$(lookup_fixture "$host" 2>/dev/null)"; then
      bad "fixture missing authoritative A record for $host"
      continue
    fi
    note "fixture $host -> $resolved"
  else
    resolved="$(lookup_authoritative "$host" || true)"
    if [ -z "$resolved" ]; then
      continue
    fi
  fi

  if [ "$resolved" != "$EXPECTED_IP" ]; then
    bad "$host authoritative A record is $resolved, expected invite-production $EXPECTED_IP"
  else
    note "$host authoritative DNS matches invite-production public IP"
  fi
done

if [ "$errors" -ne 0 ]; then
  echo "CUTOVER DNS GUARD: FAIL ($errors problem(s))" >&2
  echo "dns_guard_would_block_caddy_start=true"
  exit 4
fi

echo "dns_guard_would_block_caddy_start=false"
echo "invite_cutover_dns_authoritative=PASS"
