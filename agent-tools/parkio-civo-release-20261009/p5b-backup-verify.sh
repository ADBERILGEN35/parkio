#!/usr/bin/env bash
# P5b - verify one fresh production backup stamp locally and against its encrypted offsite copy (owner decision 6; plan v2.1
# section 6). No restore: nothing is decrypted, and nothing is written to the backup, the live checkout or any container.
#
# Run on parkio-civo-prod right after P5a, as root, with the stamp that P5a reported:
#   sudo bash /home/civo/parkio-release-20261009/tools/p5b-backup-verify.sh <stamp as YYYY-MM-DDTHH-MM-SSZ>
#
# 0. The P5a record: ~civo/parkio-release-20261009/P5a-start.txt names the last P5a run; its record P5a-run-<start>.json must
#    say result PASS, exit 0 and COMPLETE present, and must name exactly this stamp.
# 1. The backup job's environment: the cron file /etc/cron.d/parkio-backup is checked as P5a checked it (exactly one job line
#    for the entry point, as root, on "30 3 * * *", with the approved command; no malformed line; no timezone variable), and
#    its variable names must be the ones P5a applied. Every backup-library call below runs in that environment: env -i with
#    cron's defaults (SHELL=/bin/sh, PATH=/usr/bin:/bin, HOME=/root, LOGNAME=root), then the cron file's variables (values
#    never printed or placed on a command line; LOGNAME and PARKIO_BACKUP_FIRE_MARKER are not applied, as in P5a), then what
#    the entry point sets (cd /opt/parkio; PARKIO_ENV_FILE default docker/.env.azure-hosted-beta; BACKUP_PRODUCTION_MODE
#    default 1), then the live library /opt/parkio/scripts/lib/backup-common.sh as it is and parkio_backup_load_env with that
#    env file - the order the nightly job uses. The caller's environment never reaches the library.
# A. Local: the stamp exists under BACKUP_DIR; COMPLETE names it and the SHA-256 of SHA256SUMS; `sha256sum -c --strict`
#    passes; SHA256SUMS lists every other file; every *.enc file starts with the OpenSSL salted header; no plaintext dump
#    (the library's own check plus *.sql / *.sql.gz / *.dump files and a minio/ tree).
# B. Textfile metrics in the scope azure-hosted-beta (it must be the env file's deployment profile): last_success 1,
#    databases_failed 0, offsite_last_success 1, encryption_enabled 1, production_mode 1, last_timestamp_seconds not before
#    the P5a start.
# C. Offsite: free space for a temporary copy is checked first (stamp size + 2 GiB under /root). The library's
#    parkio_backup_offsite_pull fetches exactly this stamp into a private directory under /root (mode 700) and runs its own
#    checksum verification. SHA256SUMS and COMPLETE must be byte-identical to the local ones, the file names the same,
#    `sha256sum -c --strict` must pass there, every *.enc file must start with the OpenSSL salted header and no plaintext
#    dump may be present. The copy is deleted afterwards, also on interruption. The pull's output can name the account,
#    bucket or container, so it is never shown; a failed pull prints only a fixed category.
# D. Receipt ~civo/parkio-release-20261009/backup/P5b-receipt-<stamp>.json (mode 600, owner civo). T1 reads it for the D2a
#    two-hour gate. It holds the stamp, file count, bytes, SHA-256 of SHA256SUMS and COMPLETE, the offsite kind and the
#    results - no account, bucket, container, key or URL.
# Exit 0 only when every check passes.
set -uo pipefail
STAMP="${1:-}"
LIVE="${P5B_LIVE:-/opt/parkio}"
ENVF_DEFAULT="${P5B_ENV_FILE:-docker/.env.azure-hosted-beta}"   # the entry point's default, relative to the live checkout
OUT="${P5B_OUT:-/home/civo/parkio-release-20261009}"
OWNER="${P5B_OWNER:-civo}"
SCOPE="${P5B_SCOPE:-azure-hosted-beta}"
TMPROOT="${P5B_TMPROOT:-/root}"
CRONF="${P5B_CRON_FILE:-/etc/cron.d/parkio-backup}"
LOCK="${P5B_LOCK:-/var/lock/parkio-backup.lock}"
ENTRY="${P5B_ENTRY:-/opt/parkio/scripts/run-production-backup.sh}"
LOG="${P5B_LOG:-/var/log/parkio-backup.log}"
ENV_HOME="${P5B_HOME:-/root}"        # cron's HOME and LOGNAME for a root job; only the self-test changes them
ENV_USER="${P5B_LOGNAME:-root}"
CRON_PATH="${P5B_CRON_PATH:-/usr/bin:/bin}"   # cron's default PATH; only the self-test changes it (mawk run)
MARGIN="${P5B_MARGIN:-2147483648}"   # bytes that must stay free under TMPROOT after the temporary copy
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'
APPROVED_SCHEDULE='30 3 * * *'
WINDOW=7200
fails=0
pass() { echo "  PASS $*"; }
bad() { echo "  FAIL $*"; fails=$((fails + 1)); }
stop() { echo "STOP: $*"; echo "P5B NOT VERIFIED - do not run D2a or any deployment step"; exit 1; }
epoch_of_stamp() { date -u -d "$(echo "$1" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" +%s 2>/dev/null; }
enc_count() {  # dir -> "<*.enc files> <of them with the OpenSSL salted header>"
  local n=0 s=0 f
  while IFS= read -r -d '' f; do
    n=$((n + 1))
    [ "$(head -c 8 "$f" 2>/dev/null)" = "Salted__" ] && s=$((s + 1))
  done < <(find "$1" -type f -name '*.enc' -print0 2>/dev/null)
  echo "$n $s"
}
plain_count() { find "$1" \( -type f \( -name '*.sql' -o -name '*.sql.gz' -o -name '*.dump' \) ! -name '*.enc' \) -o \( -type d -name minio \) 2>/dev/null | wc -l; }

# The cron file reader, byte-identical to P5a's: mode=inspect prints facts and variable names only; mode=env prints NAME=VALUE
# lines for the variables that precede the first backup job (used only inside the environment helper, never shown).
read -r -d '' CRON_AWK <<'AWK'
function trim(s) { sub(/^[ \t]+/, "", s); sub(/[ \t]+$/, "", s); return s }
function norm(c) {
  gsub(/>>/, " >> ", c); gsub(/2>&1/, " & ", c); gsub(/[ \t]+/, " ", c); c = trim(c)
  sub(/^\/usr\/bin\/flock /, "flock ", c); sub(/^\/bin\/flock /, "flock ", c)
  return c
}
BEGIN { approved = "flock -n " lockp " " entryp " >> " logp " 2>&1"; q = sprintf("%c", 39); nv = 0; jobs = 0; other = 0; unknown = 0; jn = 0 }
{ sub(/\r$/, "") }
/^[ \t]*$/ { next }
/^[ \t]*#/ { next }
/^[ \t]*[A-Za-z_][A-Za-z0-9_]*[ \t]*=/ {
  eq = index($0, "="); name = trim(substr($0, 1, eq - 1)); val = trim(substr($0, eq + 1)); f = substr(val, 1, 1)
  if (length(val) >= 2 && (f == "\"" || f == q) && substr(val, length(val), 1) == f) val = substr(val, 2, length(val) - 2)
  if (!(name in has)) { nv++; names[nv] = name; has[name] = 1 }
  vals[name] = val
  next
}
{
  if ($1 ~ /^@/) { if (NF < 3) { unknown++; next }; sched = $1; user = $2; skip = 2 }
  else { if (NF < 7) { unknown++; next }; sched = $1 " " $2 " " $3 " " $4 " " $5; user = $6; skip = 6 }
  cmd = $0
  for (k = 0; k < skip; k++) sub(/^[ \t]*[^ \t]+/, "", cmd)
  cmd = trim(cmd)
  if (index(cmd, entryp) == 0 && index(cmd, "run-production-backup") == 0 && index(cmd, "backup-hosted-beta") == 0) { other++; next }
  jobs++
  if (jobs == 1) {
    jsched = sched; juser = user; jmatch = (norm(cmd) == approved) ? "approved" : "different"
    jlock = (index(cmd, lockp) > 0); jentry = (index(cmd, entryp) > 0); jlog = (index(cmd, logp) > 0)
    jn = nv; for (i = 1; i <= nv; i++) { jname[i] = names[i]; jval[i] = vals[names[i]] }
  }
}
END {
  if (mode == "env") { for (i = 1; i <= jn; i++) print jname[i] "=" jval[i]; exit }
  print "jobs " jobs; print "other " other; print "unknown " unknown
  if (jobs >= 1) {
    print "schedule " jsched; print "user " juser; print "command " jmatch
    print "has_lock " jlock; print "has_entry " jentry; print "has_log " jlog
    for (i = 1; i <= jn; i++) print "var " jname[i]
  }
}
AWK

# The environment helper (see 1 above): builds the backup job's environment, loads the live library and the env file as the
# entry point and the backup script do, then runs one action: facts (key=value lines, no secret values), pull <dir> <stamp>
# or assert <dir>.
read -r -d '' CRONENV <<'RUN'
set -uo pipefail
cronf=$1; prog=$2; lockp=$3; entryp=$4; logp=$5; live=$6; envdef=$7; ln=$8; action=$9; shift 9
assign="$(awk -v mode=env -v lockp="$lockp" -v entryp="$entryp" -v logp="$logp" "$prog" "$cronf")" \
  || { echo "P5B-ENV: cannot read the cron file" >&2; exit 70; }
while IFS= read -r kv; do
  [ -n "$kv" ] || continue
  case "${kv%%=*}" in LOGNAME|PARKIO_BACKUP_FIRE_MARKER) continue ;; esac
  export "$kv" || { echo "P5B-ENV: cannot apply a cron variable" >&2; exit 70; }
done <<< "$assign"
export LOGNAME="$ln"
umask 022
cd "$live" || { echo "P5B-ENV: cannot enter the live checkout" >&2; exit 71; }
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-$envdef}"
export BACKUP_PRODUCTION_MODE="${BACKUP_PRODUCTION_MODE:-1}"
# shellcheck disable=SC1091
. scripts/lib/backup-common.sh || { echo "P5B-ENV: cannot load the backup library" >&2; exit 72; }
[ -f "$PARKIO_ENV_FILE" ] || { echo "P5B-ENV: the env file is missing" >&2; exit 73; }
parkio_backup_load_env "$PARKIO_ENV_FILE" 2>/dev/null
case "$action" in
  facts)
    bdir="${BACKUP_DIR:-./backups}"; case "$bdir" in /*) ;; *) bdir="$live/${bdir#./}" ;; esac
    tdir="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-docker/prometheus/textfile}"; case "$tdir" in /*) ;; *) tdir="$live/$tdir" ;; esac
    echo "backup_dir=$bdir"
    echo "textfile_dir=$tdir"
    echo "profile=${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}"
    echo "offsite_kind=$(parkio_backup_offsite_kind)"
    if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then echo "encryption=set"; else echo "encryption=unset"; fi
    if command -v az >/dev/null 2>&1; then echo "az=found"; else echo "az=missing"; fi
    if command -v mc >/dev/null 2>&1 || [ -n "${BACKUP_MC_URL:-}" ]; then echo "mc=found"; else echo "mc=missing"; fi
    if type parkio_backup_offsite_pull >/dev/null 2>&1; then echo "pull_function=present"; else echo "pull_function=absent"; fi
    if type parkio_backup_assert_encrypted_dumps >/dev/null 2>&1; then echo "assert_function=present"; else echo "assert_function=absent"; fi
    ;;
  pull) parkio_backup_offsite_pull "$1" "$2" ;;
  assert) parkio_backup_assert_encrypted_dumps "$1" ;;
  *) exit 64 ;;
esac
RUN
cronenv() {
  env -i SHELL=/bin/sh PATH="$CRON_PATH" HOME="$ENV_HOME" LOGNAME="$ENV_USER" \
    /bin/bash -c "$CRONENV" p5b-cronenv "$CRONF" "$CRON_AWK" "$LOCK" "$ENTRY" "$LOG" "$LIVE" "$ENVF_DEFAULT" "$ENV_USER" "$@"
}
pull_hint() {  # a fixed category for a failed pull; the pull's own output is never shown
  local f="$1"
  if grep -q -E 'command not found|requires the Azure CLI|is not installed|No such file or directory: .?(az|mc)' "$f"; then
    echo "the offsite client was not found on the cron PATH"
  elif grep -q -i -E 'unrecognized arguments|invalid choice|the following arguments are required|unknown flag|unknown option' "$f"; then
    echo "the offsite client rejected the library's arguments (client version)"
  elif grep -q -i -E 'SAS token' "$f"; then
    echo "the SAS token failed the library's shape check"
  elif grep -q -i -E 'AuthenticationFailed|AuthorizationFailure|AuthorizationPermissionMismatch|InvalidAuthenticationInfo|az login|AccessDenied|Access Denied|SignatureDoesNotMatch|InvalidAccessKeyId|\b403\b' "$f"; then
    echo "the offsite store refused the credentials"
  elif grep -q -i -E 'Max retries|Connection (aborted|refused|reset)|Name or service not known|Temporary failure in name resolution|timed out|dial tcp|no such host|Network is unreachable|SSL|certificate' "$f"; then
    echo "a network or TLS error reaching the offsite store"
  elif grep -q -i -E 'ContainerNotFound|BlobNotFound|ResourceNotFound|NoSuchBucket|NoSuchKey|no such object|not found|does not exist|\b404\b' "$f"; then
    echo "the stamp, bucket or container was not found offsite"
  elif grep -q -E ': FAILED|did NOT match|FAILED open or read' "$f"; then
    echo "the pulled copy failed the library's checksum verification"
  elif grep -q -E 'incomplete stamp|missing SHA256SUMS' "$f"; then
    echo "the pulled copy has no COMPLETE or SHA256SUMS"
  elif grep -q -E 'Permission denied' "$f"; then
    echo "a local permission error"
  else
    echo "unclassified"
  fi
}
TMP=""
cleanup() {
  if [ -n "$TMP" ] && [ -d "$TMP" ]; then
    rm -rf "${TMP:?}/stamp" "${TMP:?}/pull.log"
    rmdir "$TMP" 2>/dev/null
  fi
}
trap cleanup EXIT
trap 'cleanup; echo "STOP: interrupted"; exit 130' INT TERM

echo "P5b backup verification | $(date -u +%Y-%m-%dT%H:%M:%SZ) | stamp ${STAMP:-?} | scope $SCOPE"
[ "$(id -u)" = 0 ] || [ -n "${P5B_TEST:-}" ] || stop "run as root (sudo bash ...)"
printf '%s\n' "$STAMP" | grep -q -E "$STAMP_RE" \
  || stop "usage: p5b-backup-verify.sh <stamp as YYYY-MM-DDTHH-MM-SSZ, as P5a reported it>"
stamp_epoch="$(epoch_of_stamp "$STAMP")"
[ -n "$stamp_epoch" ] || stop "cannot parse the stamp $STAMP"

echo "== 0. the P5a record"
START="$(head -1 "$OUT/P5a-start.txt" 2>/dev/null)"
[[ "$START" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || stop "no valid P5a start time in $OUT/P5a-start.txt"
start_epoch="$(date -u -d "$START" +%s 2>/dev/null)" || stop "cannot parse the P5a start time"
REC="$OUT/P5a-run-${START//:/-}.json"
[ -r "$REC" ] || stop "no P5a record $REC"
recfacts="$(python3 -I -c '
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
for k in ("result", "exit", "complete", "stamp", "start", "cronVariables"):
    v = r.get(k)
    v = "true" if v is True else "false" if v is False else str(v)
    print(k + "=" + v.replace("\n", " "))
' "$REC" 2>/dev/null)" || stop "cannot read the P5a record"
rec() { printf '%s\n' "$recfacts" | sed -n "s/^$1=//p" | head -1; }
[ "$(rec result)" = PASS ] && [ "$(rec exit)" = 0 ] && [ "$(rec complete)" = true ] \
  || stop "the P5a record does not report a complete backup (result $(rec result), exit $(rec exit), COMPLETE $(rec complete))"
[ "$(rec start)" = "$START" ] || stop "the P5a record's start $(rec start) differs from P5a-start.txt ($START)"
[ "$(rec stamp)" = "$STAMP" ] || stop "the last P5a run made stamp $(rec stamp), not $STAMP"
[ "$stamp_epoch" -ge "$start_epoch" ] || stop "stamp $STAMP is older than the P5a start $START"
REC_VARS="$(rec cronVariables)"
echo "  P5a run $START: result PASS, exit 0, COMPLETE present, stamp $STAMP"

echo "== 1. the backup job's environment (cron file read as root; names only)"
facts="$(awk -v mode=inspect -v lockp="$LOCK" -v entryp="$ENTRY" -v logp="$LOG" "$CRON_AWK" "$CRONF" 2>/dev/null)" \
  || stop "cannot read the cron file $CRONF"
fact() { printf '%s\n' "$facts" | sed -n "s/^$1 //p" | head -1; }
[ "$(fact jobs)" = 1 ] || stop "the cron file has $(fact jobs) job line(s) for the backup entry point (want exactly 1)"
[ "$(fact unknown)" = 0 ] || stop "the cron file has $(fact unknown) line(s) that are neither comments, variables nor jobs"
[ "$(fact schedule)" = "$APPROVED_SCHEDULE" ] || stop "the cron schedule is '$(fact schedule)', not the approved '$APPROVED_SCHEDULE'"
[ "$(fact user)" = root ] || stop "the cron job runs as '$(fact user)', not root"
[ "$(fact command)" = approved ] \
  || stop "the cron command differs from the approved form (lock path $(fact has_lock), entry $(fact has_entry), log path $(fact has_log); 1 = present)"
vars="$(printf '%s\n' "$facts" | sed -n 's/^var //p' | tr '\n' ' ' | sed 's/ $//')"
case " $vars " in *" TZ "*|*" CRON_TZ "*) stop "the cron file sets a timezone variable; the approved window assumes UTC" ;; esac
# shellcheck disable=SC2086
applied="$(printf '%s\n' $vars | grep -v -x -E 'LOGNAME|PARKIO_BACKUP_FIRE_MARKER' | tr '\n' ' ' | sed 's/ $//')"
[ "$applied" = "$REC_VARS" ] \
  || stop "the cron variables (${applied:-none}) differ from those P5a applied (${REC_VARS:-none}); the cron file changed after P5a"
echo "  job: '$(fact schedule)' as root, approved command"
echo "  cron variables applied: ${applied:-none (cron defaults only)} (the same as in P5a)"
envfacts="$(cronenv facts 2>/dev/null)"; erc=$?
[ "$erc" -eq 0 ] || stop "cannot build the backup job's environment (exit $erc: 70 cron file, 71 checkout, 72 library, 73 env file)"
get() { printf '%s\n' "$envfacts" | sed -n "s/^$1=//p" | head -1; }
BDIR="$(get backup_dir)"; TDIR="$(get textfile_dir)"; KIND="$(get offsite_kind)"; PROFILE="$(get profile)"
echo "  live library and env file loaded in that environment; backup and textfile dirs resolved from the env file"
echo "  deployment profile $PROFILE; offsite kind $KIND; encryption passphrase $(get encryption); az $(get az) on the cron PATH"
[ "$(get pull_function)" = present ] || stop "parkio_backup_offsite_pull is not defined by the live library"
[ "$(get assert_function)" = present ] || stop "parkio_backup_assert_encrypted_dumps is not defined by the live library"
case "$KIND" in
  azure) [ "$(get az)" = found ] || stop "offsite kind azure, but az is not on the cron job's PATH" ;;
  s3) [ "$(get mc)" = found ] || stop "offsite kind s3, but mc is not on the cron job's PATH and BACKUP_MC_URL is unset" ;;
  *) stop "no offsite configured (kind $KIND)" ;;
esac
[ -n "$BDIR" ] && [ -n "$TDIR" ] || stop "the backup or textfile directory did not resolve"

echo ""
echo "A. Local stamp $STAMP"
DEST="$BDIR/$STAMP"
[ -d "$DEST" ] || stop "stamp $STAMP is not under the backup dir"
[ -f "$DEST/COMPLETE" ] && [ -f "$DEST/SHA256SUMS" ] || stop "stamp $STAMP has no COMPLETE or SHA256SUMS"
sums_hash="$(sha256sum "$DEST/SHA256SUMS" | cut -d' ' -f1)"
complete_hash="$(sha256sum "$DEST/COMPLETE" | cut -d' ' -f1)"
if grep -qx "stamp=$STAMP" "$DEST/COMPLETE" && grep -qx "sha256sums=$sums_hash" "$DEST/COMPLETE"; then
  pass "COMPLETE names the stamp and the SHA-256 of SHA256SUMS"
else
  bad "COMPLETE does not match the stamp or SHA256SUMS"
fi
(cd "$DEST" && sha256sum -c --strict --quiet SHA256SUMS >/dev/null 2>&1) && pass "local sha256sum -c --strict" || bad "local checksum verification failed"
files="$(cd "$DEST" && find . -type f | wc -l)"
bytes="$(du -sb "$DEST" | cut -f1)"
listed="$(wc -l < "$DEST/SHA256SUMS")"
[ "$listed" -eq $((files - 2)) ] && pass "SHA256SUMS lists every file except itself and COMPLETE ($listed of $files)" \
  || bad "SHA256SUMS lists $listed files, the stamp holds $files"
read -r enc_n enc_s <<< "$(enc_count "$DEST")"
[ "$enc_n" -ge 1 ] && [ "$enc_s" -eq "$enc_n" ] && pass "$enc_n encrypted files (*.enc), each with the OpenSSL salted header" \
  || bad "encrypted files: $enc_n, with the OpenSSL salted header: $enc_s"
plain="$(plain_count "$DEST")"
lib_assert="$(cronenv assert "$DEST" >/dev/null 2>&1 && echo ok || echo fail)"
[ "$plain" -eq 0 ] && [ "$lib_assert" = ok ] && pass "no plaintext dump" || bad "plaintext dump present ($plain file(s) or minio/ tree; library check $lib_assert)"
newer="$(find "$BDIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort | awk -v s="$STAMP" '$0 > s' | wc -l)"
echo "  stamp $STAMP: $files files, $bytes bytes, SHA256SUMS sha256 $sums_hash; newer local stamps: $newer"

echo ""
echo "B. Metrics (scope $SCOPE)"
[ "$PROFILE" = "$SCOPE" ] && pass "the env file's deployment profile is $SCOPE" || bad "the env file's deployment profile is $PROFILE, not $SCOPE"
PROM="$TDIR/parkio_backup.prom"
metric() { sed -n "s/^parkio_backup_$1{scope=\"$SCOPE\"[^}]*} \([0-9.eE+-]*\)$/\1/p" "$PROM" 2>/dev/null | head -1; }
if [ -r "$PROM" ]; then
  for pair in last_success:1 databases_failed:0 offsite_last_success:1 encryption_enabled:1 production_mode:1; do
    name="${pair%%:*}"; want="${pair#*:}"; got="$(metric "$name")"
    [ "$got" = "$want" ] && pass "$name = $got" || bad "$name = ${got:-absent} (want $want)"
  done
  ts="$(metric last_timestamp_seconds)"; ts="${ts%%.*}"
  [ -n "$ts" ] && [ "$ts" -ge "$start_epoch" ] && pass "last_timestamp_seconds not before the P5a start" \
    || bad "last_timestamp_seconds ${ts:-absent} is before the P5a start"
else
  bad "metrics file not readable"
fi

echo ""
echo "C. Encrypted offsite copy of $STAMP (kind $KIND)"
before_c=$fails
offsite_ok=false
pull_rc=""
avail="$(df -P -B1 "$TMPROOT" 2>/dev/null | awk 'NR == 2 { print $4 }')"
if [ -z "$avail" ] || [ "$avail" -lt $((bytes + MARGIN)) ]; then
  bad "not enough free space under $TMPROOT for a temporary copy (need $((bytes + MARGIN)) bytes including the margin, have ${avail:-?}); not pulled"
else
  pass "free space for the temporary copy ($((avail / 1048576)) MiB available)"
  umask 077
  TMP="$(mktemp -d "$TMPROOT/parkio-p5b.XXXXXX")" || stop "mktemp failed"
  chmod 700 "$TMP"
  PULLED="$TMP/stamp"
  cronenv pull "$PULLED" "$STAMP" > "$TMP/pull.log" 2>&1
  pull_rc=$?
  if [ "$pull_rc" -eq 0 ]; then
    pass "pulled with the library's parkio_backup_offsite_pull; its own checksum verification passed"
  else
    bad "offsite pull failed (exit $pull_rc): $(pull_hint "$TMP/pull.log"); the pull's output is not shown"
  fi
  if [ -f "$PULLED/SHA256SUMS" ]; then
    cmp -s "$DEST/SHA256SUMS" "$PULLED/SHA256SUMS" && pass "offsite SHA256SUMS byte-identical" || bad "offsite SHA256SUMS differs"
    cmp -s "$DEST/COMPLETE" "$PULLED/COMPLETE" && pass "offsite COMPLETE byte-identical" || bad "offsite COMPLETE differs"
    (cd "$PULLED" && sha256sum -c --strict --quiet SHA256SUMS >/dev/null 2>&1) && pass "offsite sha256sum -c --strict" || bad "offsite checksum verification failed"
    lnames="$(cd "$DEST" && find . -type f | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
    pnames="$(cd "$PULLED" && find . -type f | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
    pfiles="$(cd "$PULLED" && find . -type f | wc -l)"
    [ "$lnames" = "$pnames" ] && pass "offsite holds the same $pfiles file names" || bad "offsite file names differ ($pfiles files)"
    read -r penc_n penc_s <<< "$(enc_count "$PULLED")"
    [ "$penc_n" -eq "$enc_n" ] && [ "$penc_s" -eq "$penc_n" ] \
      && pass "offsite copy encrypted: $penc_n *.enc files, each with the OpenSSL salted header" \
      || bad "offsite encrypted files: $penc_n (local $enc_n), with the OpenSSL salted header: $penc_s"
    pplain="$(plain_count "$PULLED")"
    plib="$(cronenv assert "$PULLED" >/dev/null 2>&1 && echo ok || echo fail)"
    [ "$pplain" -eq 0 ] && [ "$plib" = ok ] && pass "no plaintext dump offsite" || bad "plaintext dump offsite ($pplain file(s) or minio/ tree; library check $plib)"
  elif [ "$pull_rc" -eq 0 ]; then
    bad "the pull reported success but left no SHA256SUMS"
  fi
  [ "$pull_rc" -eq 0 ] && [ "$fails" -eq "$before_c" ] && offsite_ok=true
  cleanup
  [ ! -e "$TMP" ] && pass "temporary copy deleted" || bad "temporary directory $TMP still present"
fi

echo ""
mkdir -p "$OUT/backup" && chown "$OWNER:$OWNER" "$OUT/backup" 2>/dev/null; chmod 700 "$OUT/backup"
RECEIPT="$OUT/backup/P5b-receipt-$STAMP.json"
deadline="$(date -u -d "@$((stamp_epoch + WINDOW))" +%Y-%m-%dT%H:%M:%SZ)"
result=FAIL; [ "$fails" -eq 0 ] && result=PASS
python3 -I - "$RECEIPT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$START" "$(basename "$REC")" "$SCOPE" "$PROFILE" "$STAMP" "$files" \
  "$bytes" "$sums_hash" "$complete_hash" "$enc_n" "$KIND" "$offsite_ok" "$applied" "$fails" "$result" "$deadline" <<'PY'
import json, os, sys
a = sys.argv[1:]
r = {"tool": "p5b-backup-verify", "version": 2, "verifiedAt": a[1], "p5aStart": a[2], "p5aRecord": a[3], "scope": a[4],
     "deploymentProfile": a[5], "stamp": a[6], "files": int(a[7]), "bytes": int(a[8]), "sha256sumsSha256": a[9],
     "completeSha256": a[10], "encryptedFiles": int(a[11]), "offsiteKind": a[12], "offsiteIdentical": a[13] == "true",
     "environment": "backup cron job (cron defaults, cron file variables, entry point defaults, env file)",
     "cronVariables": a[14], "failedChecks": int(a[15]), "result": a[16], "d2aDeadline": a[17]}
fd = os.open(a[0], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as fh:
    json.dump(r, fh, indent=2, sort_keys=True)
    fh.write("\n")
PY
chown "$OWNER:$OWNER" "$RECEIPT" 2>/dev/null; chmod 600 "$RECEIPT"
echo "receipt: $RECEIPT"
echo "stamp $STAMP | files $files | bytes $bytes | SHA256SUMS sha256 $sums_hash | offsite copy identical: $offsite_ok"
left=$(( (stamp_epoch + WINDOW - $(date -u +%s)) / 60 ))
if [ "$fails" -ne 0 ]; then
  echo "P5B NOT VERIFIED ($fails failed check(s)) - do not run D2a or any deployment step"
  exit 1
fi
if [ "$left" -gt 0 ]; then
  echo "D2a window: start before $deadline (stamp + 2 h; about $left min left) and outside 03:00-04:30 UTC"
else
  echo "D2a window: closed at $deadline - a new P5a backup and P5b verification are needed before D2a"
fi
echo "P5B VERIFIED"
exit 0
