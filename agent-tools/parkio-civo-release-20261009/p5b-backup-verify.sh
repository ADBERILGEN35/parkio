#!/usr/bin/env bash
# P5b - verify the fresh production backup locally and against its offsite copy (owner decision 6, plan section 6).
#
# Run on parkio-civo-prod right after P5a, as root, with the P5a start time (UTC) that P5a recorded:
#   sudo bash /tmp/p5b-backup-verify.sh "$(cat ~/parkio-release-20261009/P5a-start.txt)"
#
# Read-only for the backup, the live checkout and every container. It sources the live library
# /opt/parkio/scripts/lib/backup-common.sh as it is (bf9cad51-based, locally modified files are used unchanged) and loads the
# live env file in a subshell, as the nightly job does; no secret is printed or written.
#   A. newest stamp under BACKUP_DIR: newer than the P5a start; COMPLETE names it and the SHA-256 of SHA256SUMS;
#      `sha256sum -c --strict` passes; no plaintext dump (the library's own check plus *.sql / *.sql.gz / *.dump);
#   B. textfile metrics (scope azure-hosted-beta): last_success 1, databases_failed 0, offsite_last_success 1,
#      encryption_enabled 1, production_mode 1, last_timestamp_seconds after the P5a start;
#   C. the offsite copy of exactly that stamp, pulled with the library's parkio_backup_offsite_pull into a private temporary
#      directory under /root (mode 700): SHA256SUMS and COMPLETE byte-identical to the local ones, the same file names,
#      `sha256sum -c --strict` passes there; the temporary copy is deleted afterwards (the pull's own output, which can name
#      the bucket or container, stays out of the report);
#   D. a receipt JSON in ~civo/parkio-release-20261009/backup/ (mode 600, owner civo): stamp, file count, bytes, SHA-256 of
#      SHA256SUMS and COMPLETE, offsite kind, results - no account, bucket, container, key or URL.
# Exit 0 only when A-C pass.
set -uo pipefail
START="${1:-}"
LIVE="${P5B_LIVE:-/opt/parkio}"
ENV_FILE="${P5B_ENV_FILE:-$LIVE/docker/.env.azure-hosted-beta}"
LIB="$LIVE/scripts/lib/backup-common.sh"
OUT="${P5B_OUT:-/home/civo/parkio-release-20261009}"
OWNER="${P5B_OWNER:-civo}"
SCOPE="${P5B_SCOPE:-azure-hosted-beta}"
TMPROOT="${P5B_TMPROOT:-/root}"
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'
fails=0
pass() { echo "  PASS $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
stop() { echo "STOP: $*"; echo "P5B NOT VERIFIED"; exit 1; }

echo "P5b backup verification | $(date -u +%Y-%m-%dT%H:%M:%SZ) | P5a start ${START:-?} | scope $SCOPE"
[ "$(id -u)" = 0 ] || [ -n "${P5B_TEST:-}" ] || stop "run as root (sudo bash ...)"
[[ "$START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || stop "usage: p5b-backup-verify.sh <P5a start as YYYY-MM-DDTHH:MM:SSZ>"
start_epoch="$(date -u -d "$START" +%s 2>/dev/null)" || stop "cannot parse the start time"
[ -r "$LIB" ] || stop "missing $LIB"
[ -r "$ENV_FILE" ] || stop "missing env file"
[ -d "$OUT" ] || stop "missing $OUT"

# Everything that needs the env runs in this subshell; its stdout is the protocol below (key=value lines only).
facts="$(
  set +x
  cd "$LIVE" || exit 3
  # shellcheck disable=SC1090
  . "$LIB" || exit 3
  parkio_backup_load_env "$ENV_FILE" 2>/dev/null
  bdir="${BACKUP_DIR:-./backups}"
  case "$bdir" in /*) ;; *) bdir="$LIVE/${bdir#./}" ;; esac
  tdir="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-docker/prometheus/textfile}"
  case "$tdir" in /*) ;; *) tdir="$LIVE/$tdir" ;; esac
  echo "backup_dir=$bdir"
  echo "textfile_dir=$tdir"
  echo "offsite_kind=$(parkio_backup_offsite_kind)"
  type parkio_backup_offsite_pull >/dev/null 2>&1 && echo "pull_function=present" || echo "pull_function=absent"
  type parkio_backup_assert_encrypted_dumps >/dev/null 2>&1 && echo "assert_function=present" || echo "assert_function=absent"
)" || stop "could not load the live backup library"
get() { printf '%s\n' "$facts" | sed -n "s/^$1=//p" | head -1; }
BDIR="$(get backup_dir)"; TDIR="$(get textfile_dir)"; KIND="$(get offsite_kind)"
echo "  offsite kind: $KIND; pull function $(get pull_function); backup dir and textfile dir resolved from the env file"
[ "$(get pull_function)" = present ] || stop "parkio_backup_offsite_pull is not defined by the live library"
case "$KIND" in azure|s3) ;; *) stop "no offsite configured (kind $KIND)" ;; esac

echo ""
echo "A. Local stamp"
newest="$(find "$BDIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort | tail -1)"
[ -n "$newest" ] || stop "no stamp under the backup dir"
DEST="$BDIR/$newest"
stamp_epoch="$(date -u -d "$(echo "$newest" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" +%s)"
[ "$stamp_epoch" -ge "$start_epoch" ] && pass "newest stamp $newest is not older than the P5a start" || bad "newest stamp $newest is older than the P5a start (P5a made no new stamp)"
[ -f "$DEST/COMPLETE" ] && [ -f "$DEST/SHA256SUMS" ] || stop "stamp $newest has no COMPLETE or SHA256SUMS"
sums_hash="$(sha256sum "$DEST/SHA256SUMS" | cut -d' ' -f1)"
complete_hash="$(sha256sum "$DEST/COMPLETE" | cut -d' ' -f1)"
if grep -qx "stamp=$newest" "$DEST/COMPLETE" && grep -qx "sha256sums=$sums_hash" "$DEST/COMPLETE"; then pass "COMPLETE names the stamp and the SHA-256 of SHA256SUMS"; else bad "COMPLETE does not match the stamp or SHA256SUMS"; fi
(cd "$DEST" && sha256sum -c --strict --quiet SHA256SUMS >/dev/null 2>&1) && pass "local sha256sum -c --strict" || bad "local checksum verification failed"
files="$(cd "$DEST" && find . -type f | wc -l)"
bytes="$(du -sb "$DEST" | cut -f1)"
listed="$(wc -l < "$DEST/SHA256SUMS")"
[ "$listed" -eq $((files - 2)) ] && pass "SHA256SUMS lists every file except itself and COMPLETE ($listed of $files)" || bad "SHA256SUMS lists $listed files, the stamp holds $files"
plain="$(find "$DEST" -type f \( -name '*.sql' -o -name '*.sql.gz' -o -name '*.dump' \) ! -name '*.enc' | wc -l)"
lib_assert="$( cd "$LIVE" && . "$LIB" && parkio_backup_assert_encrypted_dumps "$DEST" >/dev/null 2>&1 && echo ok || echo fail )"
[ "$plain" -eq 0 ] && [ "$lib_assert" = ok ] && pass "no plaintext dump" || bad "plaintext dump present ($plain file(s); library check $lib_assert)"
echo "  stamp $newest: $files files, $bytes bytes, SHA256SUMS sha256 $sums_hash"

echo ""
echo "B. Metrics (scope $SCOPE)"
PROM="$TDIR/parkio_backup.prom"
metric() { sed -n "s/^parkio_backup_$1{scope=\"$SCOPE\"[^}]*} \([0-9.eE+-]*\)$/\1/p" "$PROM" 2>/dev/null | head -1; }
if [ -r "$PROM" ]; then
  for pair in last_success:1 databases_failed:0 offsite_last_success:1 encryption_enabled:1 production_mode:1; do
    name="${pair%%:*}"; want="${pair#*:}"; got="$(metric "$name")"
    [ "$got" = "$want" ] && pass "$name = $got" || bad "$name = ${got:-absent} (want $want)"
  done
  ts="$(metric last_timestamp_seconds)"; ts="${ts%%.*}"
  [ -n "$ts" ] && [ "$ts" -ge "$start_epoch" ] && pass "last_timestamp_seconds after the P5a start" || bad "last_timestamp_seconds ${ts:-absent} is not after the P5a start"
else
  bad "metrics file not readable"
fi

echo ""
echo "C. Offsite copy of $newest"
umask 077
TMP="$(mktemp -d "$TMPROOT/parkio-p5b.XXXXXX")" || stop "mktemp failed"
chmod 700 "$TMP"
PULLED="$TMP/stamp"
( set +x; cd "$LIVE" && . "$LIB" && parkio_backup_load_env "$ENV_FILE" 2>/dev/null && parkio_backup_offsite_pull "$PULLED" "$newest" ) > "$TMP/pull.log" 2>&1
pull_rc=$?
offsite_ok=false
if [ "$pull_rc" -ne 0 ]; then
  bad "offsite pull failed (exit $pull_rc; the library verifies the pulled stamp itself); its output is not shown because it can name the bucket or container"
fi
if [ -f "$PULLED/SHA256SUMS" ]; then
  cmp -s "$DEST/SHA256SUMS" "$PULLED/SHA256SUMS" && pass "offsite SHA256SUMS byte-identical" || bad "offsite SHA256SUMS differs"
  cmp -s "$DEST/COMPLETE" "$PULLED/COMPLETE" && pass "offsite COMPLETE byte-identical" || bad "offsite COMPLETE differs"
  (cd "$PULLED" && sha256sum -c --strict --quiet SHA256SUMS >/dev/null 2>&1) && pass "offsite sha256sum -c --strict" || bad "offsite checksum verification failed"
  lnames="$(cd "$DEST" && find . -type f | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
  pnames="$(cd "$PULLED" && find . -type f | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
  pfiles="$(cd "$PULLED" && find . -type f | wc -l)"
  [ "$lnames" = "$pnames" ] && pass "offsite holds the same $pfiles file names" || bad "offsite file names differ ($pfiles files)"
  [ "$fails" -eq 0 ] && offsite_ok=true
fi
rm -rf "${TMP:?}/stamp" "${TMP:?}/pull.log"; rmdir "$TMP" 2>/dev/null
[ ! -e "$TMP" ] && pass "temporary copy deleted" || bad "temporary directory $TMP still present"

echo ""
mkdir -p "$OUT/backup" && chown "$OWNER:$OWNER" "$OUT/backup" 2>/dev/null; chmod 700 "$OUT/backup"
RECEIPT="$OUT/backup/P5b-receipt-$newest.json"
python3 -I - "$RECEIPT" <<PY
import json, os, sys
r = {"tool": "p5b-backup-verify", "verifiedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)", "p5aStart": "$START", "scope": "$SCOPE",
     "stamp": "$newest", "files": int("$files"), "bytes": int("$bytes"), "sha256sumsSha256": "$sums_hash",
     "completeSha256": "$complete_hash", "offsiteKind": "$KIND", "offsiteIdentical": "$offsite_ok" == "true",
     "failedChecks": int("$fails"), "result": "PASS" if int("$fails") == 0 else "FAIL"}
fd = os.open(sys.argv[1], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as fh:
    json.dump(r, fh, indent=2, sort_keys=True); fh.write("\n")
PY
chown "$OWNER:$OWNER" "$RECEIPT" 2>/dev/null; chmod 600 "$RECEIPT"
echo "receipt: $RECEIPT"
echo "stamp $newest | files $files | bytes $bytes | SHA256SUMS sha256 $sums_hash | offsite copy identical: $offsite_ok"
if [ "$fails" -eq 0 ]; then echo "P5B VERIFIED"; exit 0; else echo "P5B NOT VERIFIED ($fails failed check(s))"; exit 1; fi
