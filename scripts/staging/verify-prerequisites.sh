#!/usr/bin/env bash
set -euo pipefail
for cmd in docker git bash curl; do
  command -v "$cmd" >/dev/null || { echo "missing $cmd"; exit 1; }
done
docker compose version >/dev/null
echo "prerequisites OK"