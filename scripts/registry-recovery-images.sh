#!/usr/bin/env bash
# Registry pull recovery (U14, CL-N03).
#
# After a host loss, a new host must pull the private images that the production file set pins
# before it can run the stack. This script is the inventory of those images and the pull check a
# fresh host or runner performs with nothing but the recovery credential it was given.
#
#   registry-recovery-images.sh list [--root DIR]
#       One digest reference per line: every ghcr.io/adberilgen35/parkio image that an `image:`
#       line of a file in docker/compose.production.files pins by digest (`${VAR:-default}`
#       defaults included), plus MINIO_IMAGE and MINIO_MC_IMAGE from docker/.env.example.
#       Refuses with exit 3 when a pin file (*release-pin*.yml, *release-pins*.yml) has an
#       `image:` line without a digest, so a mutable tag cannot slip into the recovery inventory.
#   registry-recovery-images.sh pull [--root DIR] [--expect-denied]
#       `docker pull` every listed reference with the current docker login, then verify that the
#       pulled image carries exactly that digest and is linux/amd64. With --expect-denied every
#       pull must be refused instead (a credential without read access to the packages); exit 0
#       only when all of them are.
#
# Reads only. Never prints a credential: it does not log in and does not read the docker config.
set -euo pipefail

usage() {
  sed -n '2,20p' "$0" >&2
  exit 2
}

COMMAND="${1:-}"
[ -n "$COMMAND" ] || usage
shift
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EXPECT_DENIED=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --root) ROOT="$(cd "$2" && pwd)"; shift 2 ;;
    --expect-denied) EXPECT_DENIED=1; shift ;;
    -h|--help) usage ;;
    *) echo "ERROR: unknown argument '$1'" >&2; usage ;;
  esac
done

FILE_SET="$ROOT/docker/compose.production.files"
ENV_EXAMPLE="$ROOT/docker/.env.example"
REGISTRY_RE='ghcr\.io/adberilgen35/parkio/[a-z0-9._-]+@sha256:[0-9a-f]{64}'

# strip_comment LINE -> the line without a trailing "# ..." comment.
strip_comment() {
  printf '%s\n' "$1" | sed -E 's/[[:space:]]+#.*$//; s/^[[:space:]]*#.*$//'
}

list_images() {
  [ -f "$FILE_SET" ] || { echo "ERROR: missing $FILE_SET" >&2; exit 2; }
  [ -f "$ENV_EXAMPLE" ] || { echo "ERROR: missing $ENV_EXAMPLE" >&2; exit 2; }
  local status=0 refs=()
  while IFS= read -r raw || [ -n "$raw" ]; do
    local entry
    entry="$(strip_comment "$raw")"
    [ -n "$entry" ] || continue
    local file="$ROOT/$entry"
    [ -f "$file" ] || { echo "ERROR: compose.production.files names a missing file: $entry" >&2; exit 2; }
    local is_pin=0
    case "$entry" in
      *release-pin*.yml|*release-pins*.yml) is_pin=1 ;;
    esac
    while IFS= read -r line || [ -n "$line" ]; do
      local code
      code="$(strip_comment "$line")"
      printf '%s\n' "$code" | grep -qE '^[[:space:]]*image:' || continue
      local ref
      ref="$(printf '%s\n' "$code" | grep -oE "$REGISTRY_RE" | head -n 1 || true)"
      if [ -n "$ref" ]; then
        refs+=("$ref")
      elif [ "$is_pin" -eq 1 ]; then
        echo "ERROR: $entry pins an image without a digest: $(printf '%s' "$code" | sed -E 's/^[[:space:]]+//')" >&2
        status=3
      fi
    done < "$file"
  done < "$FILE_SET"
  local key
  for key in MINIO_IMAGE MINIO_MC_IMAGE; do
    local value
    value="$(grep -E "^${key}=" "$ENV_EXAMPLE" | tail -n 1 | cut -d= -f2- || true)"
    local ref
    ref="$(printf '%s\n' "$value" | grep -oE "$REGISTRY_RE" | head -n 1 || true)"
    if [ -n "$ref" ]; then
      refs+=("$ref")
    else
      echo "ERROR: docker/.env.example ${key} is not a ghcr.io digest pin" >&2
      status=3
    fi
  done
  [ "$status" -eq 0 ] || exit "$status"
  printf '%s\n' "${refs[@]}" | sort -u
}

summary_row() {
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '| %s | %s | %s |\n' "$1" "$2" "$3" >> "$GITHUB_STEP_SUMMARY"
  fi
}

pull_images() {
  local refs
  refs="$(list_images)"
  local total=0 ok=0 failed=0
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    if [ "$EXPECT_DENIED" -eq 1 ]; then
      printf '## Registry pull recovery: pulls that must be denied\n\n| Image | Result | Detail |\n|---|---|---|\n' >> "$GITHUB_STEP_SUMMARY"
    else
      printf '## Registry pull recovery: pinned private images\n\n| Image | Result | Platform |\n|---|---|---|\n' >> "$GITHUB_STEP_SUMMARY"
    fi
  fi
  local ref
  while IFS= read -r ref; do
    [ -n "$ref" ] || continue
    total=$((total + 1))
    if [ "$EXPECT_DENIED" -eq 1 ]; then
      if docker pull --quiet "$ref" >/dev/null 2>&1; then
        echo "FAIL: pulled $ref although this credential must have no read access" >&2
        summary_row "$ref" "PULLED (unexpected)" "credential has read access"
        failed=$((failed + 1))
      else
        echo "OK denied: $ref"
        summary_row "$ref" "denied" "as expected"
        ok=$((ok + 1))
      fi
      continue
    fi
    if ! docker pull --quiet "$ref" >/dev/null 2>&1; then
      echo "FAIL: could not pull $ref" >&2
      summary_row "$ref" "pull FAILED" "-"
      failed=$((failed + 1))
      continue
    fi
    local digests platform
    digests="$(docker image inspect --format '{{join .RepoDigests "\n"}}' "$ref")"
    platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$ref")"
    if ! printf '%s\n' "$digests" | grep -qxF "$ref"; then
      echo "FAIL: pulled image does not carry the pinned digest: $ref (RepoDigests: $(printf '%s' "$digests" | tr '\n' ' '))" >&2
      summary_row "$ref" "digest MISMATCH" "$platform"
      failed=$((failed + 1))
      continue
    fi
    if [ "$platform" != "linux/amd64" ]; then
      echo "FAIL: $ref is $platform, expected linux/amd64" >&2
      summary_row "$ref" "platform MISMATCH" "$platform"
      failed=$((failed + 1))
      continue
    fi
    echo "OK pulled and verified: $ref ($platform)"
    summary_row "$ref" "pulled, digest verified" "$platform"
    ok=$((ok + 1))
  done <<< "$refs"
  if [ "$total" -eq 0 ]; then
    echo "FAIL: the inventory is empty" >&2
    exit 1
  fi
  if [ "$EXPECT_DENIED" -eq 1 ]; then
    echo "registry pull recovery: ${ok}/${total} pulls denied as required, ${failed} unexpectedly allowed"
  else
    echo "registry pull recovery: ${ok}/${total} images pulled with the pinned digest on linux/amd64, ${failed} failed"
  fi
  [ "$failed" -eq 0 ]
}

case "$COMMAND" in
  list) list_images ;;
  pull) pull_images ;;
  *) usage ;;
esac
