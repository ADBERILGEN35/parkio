#!/usr/bin/env bash
# Wrapper for the Java Kafka DLT redrive tool. Dry-run is the default unless
# --execute is passed through.
set -euo pipefail
cd "$(dirname "$0")/.."
args="$(printf '%q ' "$@")"
./gradlew -q :tools:dlt-redrive:run --args="${args}"
