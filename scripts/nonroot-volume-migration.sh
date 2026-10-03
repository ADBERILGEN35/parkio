#!/usr/bin/env bash
# Non-root volume ownership migration (CL-F29.3, owner decision B8). Tooling only: B8 authorizes
# preparing the migration, not running it on a live host.
#
# The root-start services (docs/operations/container-hardening-inventory.md, exception 3) write
# their volumes as root or as an image user. Running them with a fixed non-root `user:` needs
# each volume owned by that uid:gid first. This script plans the change, applies it to stopped
# volumes, and restores the exact previous owners and modes from a manifest it writes first.
#
#   plan    --project P [--service S]...
#   apply   --project P --service S [--service S]... --confirm-project P --evidence-dir D
#   restore --project P --service S [--service S]... --confirm-project P --evidence-dir D
#
# Targets come from scripts/lib/nonroot-volume-map.tsv (PARKIO_NONROOT_VOLUME_MAP overrides it).
# Volumes are found by their Compose labels, never by name. apply and restore refuse: a missing
# or different --confirm-project, a volume that a running container mounts, a row marked blocked,
# and a path containing a newline (the manifest is line based). apply also refuses an evidence
# directory that already holds a manifest for the volume, so the original owners are never lost. Every docker step runs in a
# throwaway helper container with no network, a read-only root and only the capabilities it
# needs. Exit codes: 0 done, 1 verification failed, 2 usage, 3 refused.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAP="${PARKIO_NONROOT_VOLUME_MAP:-$ROOT/scripts/lib/nonroot-volume-map.tsv}"
HELPER_IMAGE="${PARKIO_NONROOT_HELPER_IMAGE:-busybox@sha256:fd7dc98638c8e305f4dc34e979f1c0fdfdcaeb0fbf8fcff77ae834b6da3d7e6e}"
HELPER=(docker run --rm --network none --read-only --security-opt no-new-privileges:true --cap-drop ALL)

usage() { sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; }
die() { echo "nonroot-volume-migration: $*" >&2; exit 2; }
refuse() { echo "nonroot-volume-migration: REFUSED: $*" >&2; exit 3; }

mode="${1:-}"
[ $# -gt 0 ] && shift
project=""
confirm=""
evidence=""
services=()
while [ $# -gt 0 ]; do
  case "$1" in
    --project) project="${2:-}"; shift 2 || die "--project needs a value" ;;
    --service) services+=("${2:-}"); shift 2 || die "--service needs a value" ;;
    --confirm-project) confirm="${2:-}"; shift 2 || die "--confirm-project needs a value" ;;
    --evidence-dir) evidence="${2:-}"; shift 2 || die "--evidence-dir needs a value" ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$mode" in
  plan|apply|restore) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "--project must be a Compose project name"
[ -r "$MAP" ] || die "map not readable: $MAP"
if [ "$mode" != plan ]; then
  [ ${#services[@]} -gt 0 ] || die "$mode needs at least one --service"
  [ -n "$confirm" ] || refuse "$mode changes volume ownership; pass --confirm-project $project"
  [ "$confirm" = "$project" ] || refuse "--confirm-project '$confirm' does not match --project '$project'"
  [ -n "$evidence" ] || die "$mode needs --evidence-dir"
  mkdir -p "$evidence"
fi

# Map rows for the selected services (all rows for plan without --service).
rows=()
while IFS=$'\t' read -r svc key owner readiness note; do
  case "$svc" in ''|'#'*) continue ;; esac
  [[ "$owner" =~ ^[0-9]+:[0-9]+$ ]] || die "map row for $svc/$key has an invalid owner '$owner'"
  if [ ${#services[@]} -eq 0 ] || [[ " ${services[*]} " == *" $svc "* ]]; then
    rows+=("$svc"$'\t'"$key"$'\t'"$owner"$'\t'"$readiness"$'\t'"$note")
  fi
done < "$MAP"
for svc in "${services[@]}"; do
  printf '%s\n' "${rows[@]}" | cut -f1 | grep -qxF -- "$svc" || die "service '$svc' has no row in $MAP"
done
[ ${#rows[@]} -gt 0 ] || die "no map rows selected"

resolve_volume() { # key -> volume name, or nothing
  local names
  names="$(docker volume ls -q --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.volume=$1")"
  [ "$(printf '%s' "$names" | grep -c .)" -le 1 ] || die "more than one volume for $project/$1"
  printf '%s' "$names"
}

# "count uid:gid" per owner, sorted, comma separated; then the number of paths with a newline.
owners_of() {
  "${HELPER[@]}" --cap-add DAC_READ_SEARCH -v "$1:/v:ro" --entrypoint sh "$HELPER_IMAGE" -c '
    find /v -xdev -exec stat -c "%u:%g" {} + | sort | uniq -c | sed "s/^ *//" | paste -sd, -
    find /v -xdev -name "*
*" | wc -l'
}

# One line per path: uid gid octal-mode symbolic-mode path. Written to stdout.
manifest_of() {
  "${HELPER[@]}" --cap-add DAC_READ_SEARCH -v "$1:/v:ro" --entrypoint sh "$HELPER_IMAGE" -c \
    'find /v -xdev -exec stat -c "%u %g %a %A %n" {} + | sort -k5'
}

status_of() { # owners-histogram target -> COMPLIANT | NEEDS_CHOWN
  if [ "$1" = "${1%%,*}" ] && [ "${1#* }" = "$2" ]; then echo COMPLIANT; else echo NEEDS_CHOWN; fi
}

# Compares a restored manifest with the one apply wrote: every path the first still lists must
# match exactly, and every path it does not list must carry the volume root's recorded owner.
verify_restore() { # before restored -> "mismatched=N new_wrong_owner=M"
  awk '
    function path(line) { sub(/^[^ ]+ [^ ]+ [^ ]+ [^ ]+ /, "", line); return line }
    NR == FNR { want[path($0)] = $0; if (path($0) == "/v") root = $1 ":" $2; next }
    { p = path($0); if (p in want) { if (want[p] != $0) mismatched++ } else if ($1 ":" $2 != root) wrong++ }
    END { printf "mismatched=%d new_wrong_owner=%d\n", mismatched, wrong }' "$1" "$2"
}

check_volume_free() { # volume
  local users
  users="$(docker ps -q --filter "volume=$1" | paste -sd, -)"
  [ -z "$users" ] || refuse "$1 is mounted by running container(s) $users; stop them first"
}

# Pass 1 inspects every selected row and, for apply and restore, checks all of them before
# any volume changes, so a refusal never leaves a service half migrated.
selected=()
for row in "${rows[@]}"; do
  IFS=$'\t' read -r svc key owner readiness note <<<"$row"
  volume="$(resolve_volume "$key")"
  if [ -z "$volume" ]; then
    echo "service=$svc volume=$project/$key target=$owner readiness=$readiness status=ABSENT"
    [ "$mode" = plan ] || refuse "no volume labelled $project/$key"
    continue
  fi
  inspection="$(owners_of "$volume")"
  histogram="$(sed -n 1p <<<"$inspection")"
  newline_paths="$(sed -n 2p <<<"$inspection")"
  status="$(status_of "$histogram" "$owner")"
  echo "service=$svc volume=$volume target=$owner readiness=$readiness status=$status owners=\"$histogram\""
  [ "$mode" = plan ] && continue

  [ "$readiness" != blocked ] || refuse "$svc is blocked: $note"
  [ "$newline_paths" = 0 ] || refuse "$volume has $newline_paths path(s) containing a newline"
  check_volume_free "$volume"
  if [ "$mode" = apply ] && [ -e "$evidence/$volume.before.manifest" ]; then
    refuse "$evidence already holds the manifest of an earlier apply to $volume; a second apply would record the migrated owners as the originals. Restore from it, or use a new --evidence-dir"
  fi
  if [ "$mode" = restore ]; then
    before="$evidence/$volume.before.manifest"
    [ -s "$before" ] || refuse "no manifest $before; restore needs the one apply wrote"
    (cd "$evidence" && sha256sum -c --quiet "$(basename "$before").sha256") \
      || refuse "$before does not match its .sha256"
  fi
  selected+=("$svc"$'\t'"$volume"$'\t'"$owner")
done
[ "$mode" = plan ] && exit 0

# Pass 2 changes the volumes.
failed=0
for row in "${selected[@]}"; do
  IFS=$'\t' read -r svc volume owner <<<"$row"
  check_volume_free "$volume"
  before="$evidence/$volume.before.manifest"

  if [ "$mode" = apply ]; then
    manifest_of "$volume" > "$before"
    (cd "$evidence" && sha256sum "$(basename "$before")" > "$(basename "$before").sha256")
    "${HELPER[@]}" --cap-add CHOWN --cap-add DAC_READ_SEARCH -v "$volume:/v" \
      --entrypoint chown "$HELPER_IMAGE" -R -h "$owner" /v
    after="$(owners_of "$volume" | sed -n 1p)"
    manifest_of "$volume" > "$evidence/$volume.after.manifest"
    if [ "$(status_of "$after" "$owner")" = COMPLIANT ]; then
      echo "service=$svc volume=$volume applied owners=\"$after\" manifest=$before"
    else
      echo "service=$svc volume=$volume VERIFY FAILED owners=\"$after\"" >&2
      failed=1
    fi
  else
    # Paths the manifest lists get their recorded owner and mode back. Paths created since apply
    # get the owner the volume root had; paths deleted since apply are skipped and counted.
    summary="$("${HELPER[@]}" -i --tmpfs /tmp --cap-add CHOWN --cap-add DAC_READ_SEARCH --cap-add FOWNER \
      -v "$volume:/v" --entrypoint sh "$HELPER_IMAGE" -c '
      set -eu
      cat > /tmp/m
      bad=$(grep -cvE "^[0-9]+ [0-9]+ [0-7]+ [-dlcbps][-rwxsStT]{9} /v(/.*)?$" /tmp/m || true)
      [ "$bad" = 0 ] || { echo "manifest has $bad malformed line(s)" >&2; exit 4; }
      root_owner=$(awk "\$5 == \"/v\" && NF == 5 {print \$1 \":\" \$2}" /tmp/m)
      [ -n "$root_owner" ] || { echo "manifest has no line for /v" >&2; exit 4; }
      find /v -xdev > /tmp/now
      awk "NR == FNR {now[\$0] = 1; next} {p = \$0; sub(/^[^ ]+ [^ ]+ [^ ]+ [^ ]+ /, \"\", p); if (p in now) print}" \
        /tmp/now /tmp/m > /tmp/keep
      cut -d" " -f5- /tmp/m > /tmp/listed
      awk "NR == FNR {listed[\$0] = 1; next} !(\$0 in listed)" /tmp/listed /tmp/now > /tmp/new
      cut -d" " -f1,2 /tmp/keep | sort -u | while read -r u g; do
        grep -E "^$u $g " /tmp/keep | cut -d" " -f5- | tr "\n" "\0" | xargs -0 -r chown -h "$u:$g"
      done
      tr "\n" "\0" < /tmp/new | xargs -0 -r chown -h "$root_owner"
      # chown clears set-id bits, so modes go second; symlinks have no mode of their own.
      awk "\$4 !~ /^l/ {print \$3}" /tmp/keep | sort -u | while read -r m; do
        awk -v m="$m" "\$4 !~ /^l/ && \$3 == m" /tmp/keep | cut -d" " -f5- | tr "\n" "\0" | xargs -0 -r chmod "$m"
      done
      echo "missing=$(( $(wc -l < /tmp/m) - $(wc -l < /tmp/keep) )) new=$(wc -l < /tmp/new) new_owner=$root_owner"' < "$before")"
    restored="$evidence/$volume.restored.manifest"
    manifest_of "$volume" > "$restored"
    check="$(verify_restore "$before" "$restored")"
    if [ "$check" = "mismatched=0 new_wrong_owner=0" ]; then
      echo "service=$svc volume=$volume restored $summary owners=\"$(owners_of "$volume" | sed -n 1p)\""
    else
      echo "service=$svc volume=$volume RESTORE MISMATCH $check $summary; compare $before with $restored" >&2
      failed=1
    fi
  fi
done
exit "$failed"
