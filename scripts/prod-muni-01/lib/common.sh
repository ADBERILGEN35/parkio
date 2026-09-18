#!/usr/bin/env bash
# Shared helpers for PROD-MUNI-01 executable gates (no deploy, no backend mutation).
# shellcheck shell=bash

prod_muni_root() {
  # scripts/prod-muni-01/lib -> repo root
  cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd
}

prod_muni_pass() { echo "PASS: $*"; }
prod_muni_fail() { echo "FAIL: $*" >&2; return 1; }
prod_muni_info() { echo "INFO: $*"; }

prod_muni_require_file() {
  local path="$1"
  [ -f "$path" ] || { echo "ERROR: missing required file: $path" >&2; return 1; }
}

prod_muni_git_sha() {
  git -C "$(prod_muni_root)" rev-parse HEAD 2>/dev/null || echo "UNKNOWN"
}

prod_muni_utc_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

prod_muni_node() {
  if command -v node >/dev/null 2>&1; then
    echo node
    return 0
  fi
  echo "ERROR: node is required for PROD-MUNI-01 JSON gates" >&2
  return 1
}
