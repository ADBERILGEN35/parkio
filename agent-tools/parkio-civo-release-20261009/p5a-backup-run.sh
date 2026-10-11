#!/usr/bin/env bash
# P5a - fresh production backup through the scheduled entry point (owner decision 6; plan v2.1 section 6).
#
# Run as civo on parkio-civo-prod after P6 (T1 plan); sudo asks for the password once:
#   bash /tmp/p5a-backup-run.sh
#
# What it does:
#   1. pre-checks: the P6 planned record exists; the time is outside 03:00-04:30 UTC (the nightly backup window); no backup or
#      pg_dump process is running (the guard excludes its own process and cannot match itself); the entry point is executable;
#      sudo works;
#   2. writes the start time (UTC) to ~/parkio-release-20261009/P5a-start.txt (mode 600) - P5b reads it;
#   3. runs the approved entry point exactly as the nightly job does, as root, with the same lock and log:
#        flock -n -E 75 /var/lock/parkio-backup.lock /opt/parkio/scripts/run-production-backup.sh >> /var/log/parkio-backup.log 2>&1
#      inside a root shell (so the redirection into the root-owned log is done by root) and a cron-like environment
#      (env -i with PATH, HOME, LOGNAME, SHELL only), so nothing from this terminal session reaches the backup;
#   4. on success: finds the new stamp (name not older than the start) under BACKUP_DIR and requires its COMPLETE file;
#      on failure: exit 75 means the lock is held and this run did not start; otherwise it shows at most 10 error lines that
#      this run wrote to the log, each shortened, with URLs, bucket/container paths and secret-like values masked - never
#      the raw log - and stops;
#   5. writes ~/parkio-release-20261009/P5a-run-<start>.json (mode 600): start, end, exit code, duration, stamp, COMPLETE.
# It changes nothing else: no container, checkout, unit or offset. Exit 0 only when the new stamp has COMPLETE; otherwise
# stop - do not run P5b or any deployment step.
set -uo pipefail
R="${P5A_OUT:-$HOME/parkio-release-20261009}"
LOCK="${P5A_LOCK:-/var/lock/parkio-backup.lock}"
LOG="${P5A_LOG:-/var/log/parkio-backup.log}"
ENTRY="${P5A_ENTRY:-/opt/parkio/scripts/run-production-backup.sh}"
ENVF="${P5A_ENV_FILE:-/opt/parkio/docker/.env.azure-hosted-beta}"
LIVE="${P5A_LIVE:-/opt/parkio}"
SUDO="${P5A_SUDO-sudo}"   # the self-test runs without sudo
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'

stop() { echo "STOP: $*"; echo "P5A NOT COMPLETE - do not run P5b or any deployment step"; exit 1; }
as_root() { if [ -n "$SUDO" ]; then sudo "$@"; else "$@"; fi; }
mask() {
  sed -E 's#[A-Za-z][A-Za-z0-9+.-]*://[^ ]*#<url>#g; s#(offsite|azure|s3)/[^ ]*#\1/<path>#g;
          s#((key|secret|token|sig|password|passphrase|sas|credential)[A-Za-z_]*[=:] *)[^ ]*#\1<hidden>#gI' | cut -c1-160
}

echo "P5a production backup | $(date -u +%Y-%m-%dT%H:%M:%SZ) | entry $ENTRY | lock $LOCK | log $LOG"
echo "== 1. pre-checks"
ls "$R"/planned/planned-*.json >/dev/null 2>&1 || stop "no P6 planned record in $R/planned (run T1 plan first)"
echo "  P6 planned record present"
if [ -n "${P5A_NOW_HM:-}" ]; then hm="$P5A_NOW_HM"; else hm=$(( 10#$(date -u +%H) * 60 + 10#$(date -u +%M) )); fi
if [ "$hm" -ge 180 ] && [ "$hm" -lt 270 ]; then stop "03:00-04:30 UTC is the nightly backup window"; fi
echo "  outside 03:00-04:30 UTC"
# One process snapshot; a process counts when its command line names the backup entry, the backup script or pg_dump,
# unless it is this script, its parent or a descendant of this script (command substitutions carry this script's own
# command line). Ancestry is read from the same snapshot, so a short-lived subshell cannot be mistaken for a backup.
snapshot="$(ps -eo pid=,ppid=,args= 2>/dev/null)"
[ -n "$snapshot" ] || stop "cannot list processes"
running="$(printf '%s\n' "$snapshot" | awk -v self="$$" -v parent="$PPID" '
  { pid = $1; up[pid] = $2; $1 = ""; $2 = ""; args[pid] = $0 }
  END {
    n = 0
    for (p in args) {
      if (args[p] !~ /run-production-backup|backup-hosted-beta|pg_dump/) continue
      if (p == self || p == parent) continue
      q = p; mine = 0
      for (i = 0; i < 64 && q != "" && q > 1; i++) { if (q == self) { mine = 1; break }; q = up[q] }
      if (!mine) n++
    }
    print n
  }')"
[ "${running:-0}" -eq 0 ] || stop "a backup or pg_dump process is already running ($running process(es))"
echo "  no backup or pg_dump process running"
[ -x "$ENTRY" ] || stop "$ENTRY is not executable"
if [ -n "$SUDO" ]; then sudo -v || stop "sudo is not available"; fi
before="$(as_root sh -c '[ -f "$1" ] && wc -l < "$1" || echo 0' sh "$LOG" 2>/dev/null)"
before="${before:-0}"

START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
start_epoch="$(date -u -d "$START" +%s)"
( umask 077; echo "$START" > "$R/P5a-start.txt" ) || stop "cannot write $R/P5a-start.txt"
echo "  start $START recorded in $R/P5a-start.txt"

echo "== 2. the entry point as root: cron-like environment, same lock and log"
t0="$(date +%s)"
if [ -n "$SUDO" ]; then
  sudo -H env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME=/root LOGNAME=root SHELL=/bin/sh \
    sh -c 'flock -n -E 75 "$1" "$2" >> "$3" 2>&1' sh "$LOCK" "$ENTRY" "$LOG"
else
  env -i PATH=/usr/sbin:/usr/bin:/sbin:/bin HOME="${HOME}" LOGNAME="$(id -un)" SHELL=/bin/sh \
    sh -c 'flock -n -E 75 "$1" "$2" >> "$3" 2>&1' sh "$LOCK" "$ENTRY" "$LOG"
fi
rc=$?
END="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
dur=$(( $(date +%s) - t0 ))
echo "  backup exit=$rc after ${dur} s"
record() {  # result stamp complete
  ( umask 077
    printf '{"tool": "p5a-backup-run", "start": "%s", "end": "%s", "exit": %s, "durationSeconds": %s, "stamp": "%s", "complete": %s, "result": "%s"}\n' \
      "$START" "$END" "$rc" "$dur" "$2" "$3" "$1" > "$R/P5a-run-${START//:/-}.json" )
}
if [ "$rc" -ne 0 ]; then
  record FAIL "" false
  if [ "$rc" -eq 75 ]; then
    stop "the backup lock is held (another backup is running); this run did not start"
  fi
  echo "  error lines this run wrote to the log (at most 10: the first 6 and the last 4; masked, shortened):"
  as_root sh -c 'tail -n +"$2" "$1"' sh "$LOG" "$((before + 1))" 2>/dev/null | grep -i -E 'error|fail|refus' | mask \
    | awk '{ line[NR] = $0 } END { for (i = 1; i <= NR; i++) if (NR <= 10 || i <= 6 || i > NR - 4) print "    " line[i];
                                   else if (i == 7) print "    ... (" NR - 10 " more) ..." }'
  stop "the backup failed (exit $rc)"
fi

echo "== 3. the new stamp (names only)"
BD="$(grep -E '^BACKUP_DIR=' "$ENVF" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"'")"
BD="${BD:-./backups}"
case "$BD" in /*) ;; *) BD="$LIVE/${BD#./}" ;; esac
NEW=""
for name in $(as_root find "$BD" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort); do
  e="$(date -u -d "$(echo "$name" | sed -E 's/T([0-9]{2})-([0-9]{2})-([0-9]{2})Z$/T\1:\2:\3Z/')" +%s 2>/dev/null || echo 0)"
  [ "$e" -ge "$start_epoch" ] && NEW="$name"
done
if [ -z "$NEW" ]; then record FAIL "" false; stop "no stamp newer than $START under the backup dir"; fi
if as_root test -f "$BD/$NEW/COMPLETE"; then
  echo "  new stamp $NEW: COMPLETE present"
else
  record FAIL "$NEW" false
  stop "the new stamp $NEW has no COMPLETE"
fi
record PASS "$NEW" true
echo "  record: $R/P5a-run-${START//:/-}.json"
echo "P5A STEP COMPLETE - verify with P5b next; D2a must start within 2 hours of $START and outside 03:00-04:30 UTC"
exit 0
