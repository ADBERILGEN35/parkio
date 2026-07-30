#!/usr/bin/env bash
# Hosted-beta deployment disk capacity helpers.
# shellcheck shell=bash

# Default minimum free space before image builds: 12 GiB.
PARKIO_DEFAULT_MIN_FREE_GIB=12
PARKIO_GIB_BYTES=$((1024 * 1024 * 1024))

# parkio_parse_min_free_bytes
# Reads PARKIO_DEPLOY_MIN_FREE_BYTES or PARKIO_DEPLOY_MIN_FREE_GIB.
# Prints integer bytes on success; returns 2 on malformed input.
parkio_parse_min_free_bytes() {
  local raw_bytes="${PARKIO_DEPLOY_MIN_FREE_BYTES:-}"
  local raw_gib="${PARKIO_DEPLOY_MIN_FREE_GIB:-}"
  local value

  if [ -n "$raw_bytes" ]; then
    case "$raw_bytes" in
      ''|*[!0-9]*)
        echo "ERROR: PARKIO_DEPLOY_MIN_FREE_BYTES must be a non-negative integer (bytes), got '$raw_bytes'" >&2
        return 2
        ;;
    esac
    echo "$raw_bytes"
    return 0
  fi

  if [ -n "$raw_gib" ]; then
    case "$raw_gib" in
      ''|*[!0-9]*)
        echo "ERROR: PARKIO_DEPLOY_MIN_FREE_GIB must be a non-negative integer (GiB), got '$raw_gib'" >&2
        return 2
        ;;
    esac
    value=$raw_gib
  else
    value=$PARKIO_DEFAULT_MIN_FREE_GIB
  fi

  echo $((value * PARKIO_GIB_BYTES))
}

# parkio_filesystem_avail_bytes <path>
# Uses df -B1. Tests may set PARKIO_DISK_FREE_BYTES_FOR_TEST to inject a value.
parkio_filesystem_avail_bytes() {
  local path="${1:-/}"
  local avail

  if [ -n "${PARKIO_DISK_FREE_BYTES_FOR_TEST:-}" ]; then
    case "${PARKIO_DISK_FREE_BYTES_FOR_TEST}" in
      ''|*[!0-9]*)
        echo "ERROR: PARKIO_DISK_FREE_BYTES_FOR_TEST must be a non-negative integer" >&2
        return 2
        ;;
    esac
    echo "$PARKIO_DISK_FREE_BYTES_FOR_TEST"
    return 0
  fi

  avail="$(df -B1 --output=avail "$path" 2>/dev/null | tail -n 1 | tr -d '[:space:]')"
  case "$avail" in
    ''|*[!0-9]*)
      echo "ERROR: could not read free bytes for filesystem path '$path'" >&2
      return 2
      ;;
  esac
  echo "$avail"
}

# parkio_require_free_disk [<path>]
# Fails before builds when free space is below the configured threshold.
# PARKIO_DEPLOY_ALLOW_LOW_DISK=1 warns and continues (explicit operator override).
# Never silently lowers the threshold.
parkio_require_free_disk() {
  local path="${1:-/}"
  local required avail allow

  required="$(parkio_parse_min_free_bytes)" || return 2
  avail="$(parkio_filesystem_avail_bytes "$path")" || return 2
  allow="${PARKIO_DEPLOY_ALLOW_LOW_DISK:-0}"

  echo "Disk preflight: path=$path freeBytes=$avail requiredBytes=$required (defaultMinGiB=$PARKIO_DEFAULT_MIN_FREE_GIB)"

  if [ "$avail" -ge "$required" ]; then
    echo "Disk preflight: PASS"
    return 0
  fi

  echo "ERROR: insufficient free disk for hosted-beta deploy" >&2
  echo "       freeBytes=$avail requiredBytes=$required path=$path" >&2
  echo "       reclaim Docker build cache / unreferenced images per docs/operations/hosted-beta-disk-cleanup.md" >&2
  echo "       or expand the VM disk; do not auto-prune from deploy" >&2

  if [ "$allow" = "1" ]; then
    echo "WARN: PARKIO_DEPLOY_ALLOW_LOW_DISK=1 set — continuing despite low disk (operator override)" >&2
    return 0
  fi

  return 1
}
