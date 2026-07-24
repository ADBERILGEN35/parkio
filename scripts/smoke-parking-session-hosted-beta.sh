#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if ! command -v node >/dev/null 2>&1; then echo "ERROR: node required" >&2; exit 2; fi
exec node "$ROOT/scripts/lib/parking-session-smoke-runner.cjs"