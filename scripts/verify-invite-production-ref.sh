#!/usr/bin/env bash
# Verify a manual invite-production job is pinned to the api ref and exact SHA.

set -euo pipefail

EXPECTED_SHA=""
REF_SHA=""
REF_NAME=""
REPO="."

while [ "$#" -gt 0 ]; do
  case "$1" in
    --expected-sha) EXPECTED_SHA="${2:-}"; shift 2 ;;
    --ref-sha) REF_SHA="${2:-}"; shift 2 ;;
    --ref-name) REF_NAME="${2:-}"; shift 2 ;;
    --repo) REPO="${2:-}"; shift 2 ;;
    *) echo "ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if ! [[ "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: requested SHA must be a full lowercase 40-character commit SHA." >&2
  exit 2
fi
if [ "$REF_NAME" != "refs/heads/api" ]; then
  echo "ERROR: invite-production jobs may only be dispatched from refs/heads/api." >&2
  exit 2
fi
if [ "$EXPECTED_SHA" != "$REF_SHA" ]; then
  echo "ERROR: requested SHA does not equal the api ref SHA at dispatch time." >&2
  exit 2
fi

ACTUAL_SHA="$(git -C "$REPO" rev-parse HEAD)"
if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
  echo "ERROR: checked-out SHA does not equal the requested SHA." >&2
  exit 2
fi
if ! git -C "$REPO" diff --quiet || ! git -C "$REPO" diff --cached --quiet; then
  echo "ERROR: tracked checkout is dirty." >&2
  exit 2
fi

echo "Invite-production ref verification passed: sha=$ACTUAL_SHA ref=$REF_NAME"
