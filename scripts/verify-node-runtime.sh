#!/usr/bin/env bash
# Verify the exact repository-supported Node.js runtime without consulting a
# mutable shell profile or downloading anything.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="${PARKIO_NODE_VERSION_FILE:-$ROOT/.node-version}"
NODE_BINARY="${PARKIO_NODE_BINARY:-node}"

fail() {
  echo "ERROR: $*" >&2
  exit 3
}

[ -f "$VERSION_FILE" ] || fail "Node version contract is missing: $VERSION_FILE"
expected="$(tr -d '[:space:]' < "$VERSION_FILE")"
[[ "$expected" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
  || fail "Node version contract must contain one exact semantic version."

command -v "$NODE_BINARY" >/dev/null 2>&1 \
  || fail "required Node.js $expected runtime is unavailable."

actual="$($NODE_BINARY --version 2>/dev/null)" \
  || fail "required Node.js $expected runtime could not be executed."
[ "$actual" = "v$expected" ] \
  || fail "wrong Node.js runtime: expected v$expected, got ${actual:-<empty>}."

echo "Node.js runtime: PASS ($actual)"
