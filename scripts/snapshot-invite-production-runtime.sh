#!/usr/bin/env bash
#
# Runtime no-mutation snapshot (PROD-DEPLOY-01A-R8.4).
#
# Why this replaces the old check
# -------------------------------
# runner-acceptance used to prove it changed nothing with:
#
#     test -z "$(docker ps -q)"
#
# That encoded "acceptance started nothing" as "nothing is running at all". It
# held only while the invite-production VM was empty. The VM now legitimately
# hosts the preserved dark runtime, so the assertion became structurally
# unsatisfiable: the only way to pass was to destroy the runtime it exists to
# protect. Run 32476161001 failed on exactly that.
#
# The intended invariant is topology-relative, not a magic count: capture the
# runtime before and after, and require it identical. That still catches
# everything the old rule was reaching for — and considerably more, since a
# recreate or a restart is now visible where `docker ps -q` being non-empty
# never was.
#
# Scope
# -----
# Protected: every container whose name starts with the Parkio prefix, in ANY
# state. Exited containers are included deliberately, so acceptance cannot
# quietly start one of the six intentionally stopped containers.
#
# Containers outside that prefix are listed under a CLASSIFIED- marker instead of
# being compared. That is explicit rather than an ambiguous `docker ps` rule, and
# it keeps the gate's own short-lived `docker run --rm` probes from being
# mistaken for runtime mutation.
#
# Sanitization
# ------------
# Only structural identity is captured — name, id, image, status, start time,
# restart count. No environment, no command line, no labels, no mounts, nothing
# secret-bearing. The snapshot is written to the ephemeral job workspace.
#
# Usage:
#   snapshot-invite-production-runtime.sh capture <file>
#   snapshot-invite-production-runtime.sh compare <before> <after>

set -euo pipefail

PREFIX="${PARKIO_SNAPSHOT_PREFIX:-parkio-}"

usage() { sed -n '2,12p' "$0"; exit 2; }

capture() {
  local out="${1:-}"
  [ -n "$out" ] || usage
  local ids cid line name
  if ! ids="$(docker ps -aq)"; then
    echo "ERROR: unable to list containers" >&2
    return 2
  fi
  {
    for cid in $ids; do
      # Explicit field list: never `docker inspect` wholesale, which would drag
      # environment and other secret-bearing sections into an artifact.
      if ! line="$(docker inspect \
            -f '{{slice .Name 1}}|{{slice .Id 0 12}}|{{.Config.Image}}|{{.State.Status}}|{{.State.StartedAt}}|{{.RestartCount}}' \
            "$cid")"; then
        echo "ERROR: unable to inspect container $cid" >&2
        return 2
      fi
      name="${line%%|*}"
      case "$name" in
        "$PREFIX"*) printf 'PROTECTED-%s\n' "$line" ;;
        *)          printf 'CLASSIFIED-%s|%s\n' "$name" "non-parkio (not compared)" ;;
      esac
    done
  } | LC_ALL=C sort > "$out"
  chmod 0644 "$out"
  printf 'protected=%s classified=%s\n' \
    "$(grep -c '^PROTECTED-' "$out" || true)" \
    "$(grep -c '^CLASSIFIED-' "$out" || true)"
}

compare() {
  local before="${1:-}" after="${2:-}"
  { [ -n "$before" ] && [ -n "$after" ]; } || usage
  [ -f "$before" ] || { echo "ERROR: missing before snapshot: $before" >&2; return 2; }
  [ -f "$after" ]  || { echo "ERROR: missing after snapshot: $after" >&2; return 2; }

  local b a
  b="$(grep '^PROTECTED-' "$before" || true)"
  a="$(grep '^PROTECTED-' "$after" || true)"

  if [ "$b" = "$a" ]; then
    printf 'Runtime unchanged: %s protected containers identical.\n' \
      "$(printf '%s' "$b" | grep -c . || true)"
    return 0
  fi

  echo "ERROR: runner-acceptance mutated the protected runtime." >&2
  echo "--- structural diff (name|id|image|status|startedAt|restarts) ---" >&2
  # Structural fields only; nothing here can carry a secret.
  diff <(printf '%s\n' "$b") <(printf '%s\n' "$a") >&2 || true
  return 1
}

case "${1:-}" in
  capture) shift; capture "$@" ;;
  compare) shift; compare "$@" ;;
  *) usage ;;
esac
