#!/usr/bin/env bash
# Run a single Parkio service locally.
# Usage: scripts/run-service.sh <service-name>   e.g. scripts/run-service.sh auth-service
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <service-name>" >&2
  echo "Example: $0 auth-service" >&2
  exit 1
fi

service="$1"
shift
cd "$(dirname "$0")/.."

if [[ ! -d "services/${service}" ]]; then
  echo "Unknown service: ${service}" >&2
  echo "Available services:" >&2
  ls services >&2
  exit 1
fi

./gradlew ":services:${service}:bootRun" "$@"
