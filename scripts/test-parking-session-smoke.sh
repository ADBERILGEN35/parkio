#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec node --test "$ROOT/scripts/lib/parking-session-smoke-runner.test.cjs"