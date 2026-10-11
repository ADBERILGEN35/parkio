#!/usr/bin/env bash
# P5a - fresh production backup through the scheduled entry point (owner decision 6; plan v2.1 section 6).
#
# Run as civo on parkio-civo-prod after P6 (T1 plan); sudo asks for the password once:
#   bash ~/parkio-release-20261009/tools/p5a-backup-run.sh
#
# 1. Pre-checks: the P6 planned record exists; the host clock is UTC (cron schedules use local time); the time is outside
#    03:00-04:30 UTC; no backup or pg_dump process is running (one ps snapshot; this script, every ancestor - the shell or
#    command that started it - and every descendant are excluded, so the guard cannot match its own invocation); the entry
#    point is executable; sudo works.
# 2. The cron file /etc/cron.d/parkio-backup, read as root and never printed: exactly one job line runs the entry point, as
#    root, on the approved schedule "30 3 * * *", with the approved command
#      flock -n /var/lock/parkio-backup.lock /opt/parkio/scripts/run-production-backup.sh >> /var/log/parkio-backup.log 2>&1
#    no other line is malformed and no timezone variable is set. The lock is free (checked as root).
# 3. Writes the start time (UTC) to ~/parkio-release-20261009/P5a-start.txt (mode 600); P5b reads it.
# 4. Runs that command as root in the environment cron gives the job: env -i with cron's defaults (SHELL=/bin/sh,
#    PATH=/usr/bin:/bin, HOME=/root, LOGNAME=root), then the variable lines that precede the job in the cron file (values never
#    printed or placed on a command line; only names are shown), working directory HOME, umask 022, stdin /dev/null; the
#    redirection into the root-owned log is done by the root shell; flock gets -E 75 only so that a held lock can be told
#    apart. Not applied: PARKIO_BACKUP_FIRE_MARKER (it records scheduler fires; a manual run must not add one) and LOGNAME
#    (cron does not let the file change it).
# 5. Success: the stamp comes from this run's own "destination=" log line (fallback: BACKUP_DIR from the env file, newest stamp
#    not older than the start); it must be new and hold a COMPLETE file that names it.
#    Failure: lock held -> nothing ran; otherwise at most 10 error lines that this run wrote to the log (the first 6 and the
#    last 4), each shortened, with URLs, Azure hosts, bucket/container paths, e-mail addresses, option values and secret-like
#    values masked - never the raw log - then STOP.
# 6. Writes ~/parkio-release-20261009/P5a-run-<start>.json (mode 600). Nothing else changes: no container, checkout, unit,
#    cron file or offset. Exit 0 only when the new stamp has COMPLETE; otherwise do not run P5b or any deployment step.
set -uo pipefail
R="${P5A_OUT:-$HOME/parkio-release-20261009}"
CRONF="${P5A_CRON_FILE:-/etc/cron.d/parkio-backup}"
LOCK="${P5A_LOCK:-/var/lock/parkio-backup.lock}"
PRELOCK="${P5A_TEST_PRECHECK_LOCK:-$LOCK}"   # only the self-test points the pre-check at another file
LOG="${P5A_LOG:-/var/log/parkio-backup.log}"
ENTRY="${P5A_ENTRY:-/opt/parkio/scripts/run-production-backup.sh}"
ENVF="${P5A_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
LIVE="${P5A_LIVE:-/opt/parkio}"
SUDO="${P5A_SUDO-sudo}"   # the self-test runs without sudo
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'
APPROVED_SCHEDULE='30 3 * * *'

stop() { echo "STOP: $*"; echo "P5A NOT COMPLETE - do not run P5b or any deployment step"; exit 1; }
as_root() { if [ -n "$SUDO" ]; then sudo "$@"; else "$@"; fi; }
mask() {
  sed -E \
    -e 's#[A-Za-z][A-Za-z0-9+.-]*://[^ ]*#<url>#g' \
    -e 's#[A-Za-z0-9.-]+\.(blob|dfs|file|queue|table)\.core\.windows\.net[^ ]*#<azure-host>#g' \
    -e 's#(offsite|azure|s3)/[^ ]*#\1/<path>#g' \
    -e 's#[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}#<email>#g' \
    -e 's#(Bearer|Basic) +[^ ]+#\1 <hidden>#gI' \
    -e 's#(--?[A-Za-z0-9-]*(key|secret|token|pass|sas|sig|credential|auth|account|container|bucket)[A-Za-z0-9-]*)( +|=)[^ ]+#\1\3<hidden>#gI' \
    -e "s#((key|secret|token|sig|pass|sas|credential|account|container|bucket)[A-Za-z_]*[\"']?[=:] *[\"']?)[^ \"',;&]*#\1<hidden>#gI" \
    -e 's#[A-Za-z0-9+/]{40,}={0,2}#<long>#g' \
  | cut -c1-160
}

# Reads the cron file. mode=inspect prints facts and variable names only; mode=env prints NAME=VALUE lines for the variables
# that precede the first backup job (used only inside the root runner, never shown). mawk-compatible.
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

# The root runner: builds the job's environment from the cron file and runs the approved command (see 4 above).
read -r -d '' RUNNER <<'RUN'
set -u
cronf=$1; lockp=$2; entryp=$3; logp=$4; prog=$5; ln=$6
assign="$(awk -v mode=env -v lockp="$lockp" -v entryp="$entryp" -v logp="$logp" "$prog" "$cronf")" \
  || { echo "P5A-RUNNER: cannot read the cron file" >&2; exit 70; }
while IFS= read -r kv; do
  [ -n "$kv" ] || continue
  case "${kv%%=*}" in LOGNAME|PARKIO_BACKUP_FIRE_MARKER) continue ;; esac
  export "$kv" || { echo "P5A-RUNNER: cannot apply a cron variable" >&2; exit 70; }
done <<< "$assign"
export LOGNAME="$ln"
umask 022
cd "${HOME:-/}" 2>/dev/null || cd /
exec flock -n -E 75 "$lockp" "$entryp" >> "$logp" 2>&1 < /dev/null
RUN

echo "P5a production backup | $(date -u +%Y-%m-%dT%H:%M:%SZ) | entry $ENTRY | lock $LOCK | log $LOG"
echo "== 1. pre-checks"
ls "$R"/planned/planned-*.json >/dev/null 2>&1 || stop "no P6 planned record in $R/planned (run T1 plan first)"
echo "  P6 planned record present"
tzoff="${P5A_TZ_OFFSET:-$(date +%z)}"
[ "$tzoff" = "+0000" ] || stop "the host clock is not UTC (offset $tzoff); the 03:30 cron job would not fire at 03:30 UTC"
echo "  host clock UTC"
if [ -n "${P5A_NOW_HM:-}" ]; then hm="$P5A_NOW_HM"; else hm=$(( 10#$(date -u +%H) * 60 + 10#$(date -u +%M) )); fi
if [ "$hm" -ge 180 ] && [ "$hm" -lt 270 ]; then stop "03:00-04:30 UTC is the nightly backup window"; fi
echo "  outside 03:00-04:30 UTC"
snapshot="$(ps -eo pid=,ppid=,args= 2>/dev/null)"
[ -n "$snapshot" ] || stop "cannot list processes"
running="$(printf '%s\n' "$snapshot" | awk -v self="$$" '
  { pid = $1; up[pid] = $2; $1 = ""; $2 = ""; args[pid] = $0 }
  END {
    a = self; for (i = 0; i < 64 && a != "" && a > 1; i++) { anc[a] = 1; a = up[a] }
    n = 0
    for (p in args) {
      if (args[p] !~ /run-production-backup|backup-hosted-beta|backup-databases|backup-minio|pg_dump/) continue
      if (p in anc) continue
      q = p; mine = 0
      for (i = 0; i < 64 && q != "" && q > 1; i++) { if (q == self) { mine = 1; break }; q = up[q] }
      if (!mine) n++
    }
    print n
  }')"
[ "${running:-0}" -eq 0 ] || stop "a backup or pg_dump process is already running ($running process(es))"
echo "  no backup or pg_dump process running (this script's own processes excluded)"
[ -x "$ENTRY" ] || stop "$ENTRY is not executable"
if [ -n "$SUDO" ]; then sudo -v || stop "sudo is not available"; fi

echo "== 2. the cron job (read as root; names only)"
facts="$(as_root awk -v mode=inspect -v lockp="$LOCK" -v entryp="$ENTRY" -v logp="$LOG" "$CRON_AWK" "$CRONF" 2>/dev/null)" \
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
applied="$(printf '%s\n' $vars | grep -v -x -E 'LOGNAME|PARKIO_BACKUP_FIRE_MARKER' | tr '\n' ' ' | sed 's/ $//')"
echo "  job: '$(fact schedule)' as root, approved command; other jobs in the file: $(fact other)"
echo "  cron variables applied to this run: ${applied:-none (cron defaults only)}"
case " $vars " in *" PARKIO_BACKUP_FIRE_MARKER "*)
  echo "  PARKIO_BACKUP_FIRE_MARKER: not applied (it records scheduler fires; a manual run must not add one)" ;; esac
as_root flock -n -E 75 "$PRELOCK" true; lrc=$?
[ "$lrc" -ne 75 ] || stop "the backup lock is held (another backup is running); nothing was started"
[ "$lrc" -eq 0 ] || stop "cannot test the backup lock (exit $lrc)"
echo "  backup lock free"
before="$(as_root sh -c '[ -f "$1" ] && wc -l < "$1" || echo 0' sh "$LOG" 2>/dev/null)"
before="${before:-0}"

START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
start_epoch="$(date -u -d "$START" +%s)"
( umask 077; echo "$START" > "$R/P5a-start.txt" ) || stop "cannot write $R/P5a-start.txt"
echo "  start $START recorded in $R/P5a-start.txt"

echo "== 3. the approved command as root in the cron job's environment"
t0="$(date +%s)"
if [ -n "$SUDO" ]; then
  sudo -H env -i SHELL=/bin/sh PATH=/usr/bin:/bin HOME=/root LOGNAME=root \
    /bin/bash -c "$RUNNER" p5a-runner "$CRONF" "$LOCK" "$ENTRY" "$LOG" "$CRON_AWK" root
else
  env -i SHELL=/bin/sh PATH=/usr/bin:/bin HOME="$HOME" LOGNAME="$(id -un)" \
    /bin/bash -c "$RUNNER" p5a-runner "$CRONF" "$LOCK" "$ENTRY" "$LOG" "$CRON_AWK" "$(id -un)"
fi
rc=$?
END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
dur=$(( $(date +%s) - t0 ))
echo "  backup exit=$rc after ${dur} s"
newlog() { as_root sh -c 'tail -n +"$2" "$1"' sh "$LOG" "$((before + 1))" 2>/dev/null; }
added="$(newlog | wc -l)"
record() {  # result stamp complete source
  ( umask 077
    printf '{"tool": "p5a-backup-run", "start": "%s", "end": "%s", "exit": %s, "durationSeconds": %s, "stamp": "%s", "complete": %s, "stampSource": "%s", "cronVariables": "%s", "result": "%s"}\n' \
      "$START" "$END" "$rc" "$dur" "$2" "$3" "$4" "$applied" "$1" > "$R/P5a-run-${START//:/-}.json" )
}
if [ "$rc" -ne 0 ]; then
  record FAIL "" false ""
  if [ "$rc" -eq 75 ] && [ "$added" -eq 0 ]; then
    stop "the backup lock was taken by another backup after the pre-check; this run did not start"
  fi
  echo "  error lines this run wrote to the log ($added new lines; at most 10 shown: the first 6 and the last 4; masked, shortened):"
  newlog | grep -i -E 'error|fail|refus' | mask \
    | awk '{ line[NR] = $0 } END { for (i = 1; i <= NR; i++) if (NR <= 10 || i <= 6 || i > NR - 4) print "    " line[i];
                                   else if (i == 7) print "    ... (" NR - 10 " more) ..." }'
  stop "the backup failed (exit $rc)"
fi

echo "== 4. the new stamp (names only)"
src="log destination"
DEST="$(newlog | sed -n 's/^destination=//p' | head -1)"
if [ -n "$DEST" ]; then
  case "$DEST" in /*) ;; *) DEST="$LIVE/${DEST#./}" ;; esac
  NEW="$(basename "$DEST")"
else
  src="env file BACKUP_DIR"
  BD="$(as_root grep -E '^BACKUP_DIR=' "$ENVF" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"'")"
  BD="${BD:-./backups}"
  case "$BD" in /*) ;; *) BD="$LIVE/${BD#./}" ;; esac
  NEW=""
  for name in $(as_root find "$BD" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort); do
    e="$(date -u -d "$(echo "$name" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" +%s 2>/dev/null || echo 0)"
    [ "$e" -ge "$start_epoch" ] && NEW="$name"
  done
  DEST="$BD/$NEW"
fi
if ! printf '%s\n' "$NEW" | grep -q -E "$STAMP_RE"; then record FAIL "" false "$src"; stop "no new stamp found ($src)"; fi
e="$(date -u -d "$(echo "$NEW" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" +%s 2>/dev/null || echo 0)"
if [ "$e" -lt "$start_epoch" ]; then record FAIL "$NEW" false "$src"; stop "stamp $NEW is older than the start $START"; fi
if as_root test -f "$DEST/COMPLETE" && as_root grep -q -x "stamp=$NEW" "$DEST/COMPLETE"; then
  echo "  new stamp $NEW ($src): COMPLETE present and names the stamp"
else
  record FAIL "$NEW" false "$src"
  stop "the new stamp $NEW has no COMPLETE that names it"
fi
record PASS "$NEW" true "$src"
echo "  record: $R/P5a-run-${START//:/-}.json"
echo "P5A STEP COMPLETE - verify with P5b next; D2a must start within 2 hours of $START and outside 03:00-04:30 UTC"
exit 0
