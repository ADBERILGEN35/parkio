#!/usr/bin/env bash
# Build and test every Parkio service.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew build "$@"
