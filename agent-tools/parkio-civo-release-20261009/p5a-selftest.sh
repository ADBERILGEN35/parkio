#!/usr/bin/env bash
# Local self-test of p5a-backup-run.sh without sudo (P5A_SUDO=""), with a fake entry point, cron file, lock, log, env file and
# backup dir in a private temporary directory.
#   S1  success: approved cron line (">>" written without a space), cron variables applied as cron does (quotes stripped, empty
#       MAILTO kept, a variable after the job and PARKIO_BACKUP_FIRE_MARKER not applied, session variables never reach the
#       entry, umask 022, working directory HOME), stamp from the log's destination line, COMPLETE, start file, run record
#   S2  the lock is held -> STOP at the pre-check, no start file, the entry never runs
#   S2b the lock is taken after the pre-check -> exit 75 with no new log line -> "did not start"
#   S3  failure with secrets in error lines -> masked (URL, Azure host, AccountKey/AccountName, offsite path, option values,
#       e-mail, Bearer, JSON password, long key material); no raw non-error line; STOP
#   S3b the entry itself exits 75 after writing to the log -> a backup failure, not "lock held"
#   S3c more than 10 error lines -> the first 6, an elision line and the last 4
#   S4  no COMPLETE / S4b COMPLETE names another stamp -> STOP; S4c no destination line -> env-file fallback; S4d relative
#       destination resolved under the live checkout
#   S5  03:15 UTC -> STOP; S5b host clock not UTC -> STOP; S6 a backup-like process -> STOP; S7 no P6 record -> STOP
#   S8  the tool runs from a path containing "run-production-backup", and S8b under a parent whose command line names the
#       backup scripts: its guard does not match itself or its ancestors
#   S10 cron file checks: other schedule, two backup jobs, different log path, a malformed line, TZ set, missing file, other
#       user -> STOP each, before the start file is written
#   S11 mawk parity: the cron program gives the same output under mawk and gawk; guard, cron check and elision under mawk
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p5a-backup-run.sh"
W="$(mktemp -d)"; BG=""
cleanup() { [ -n "$BG" ] && kill "$BG" 2>/dev/null || true; rm -rf "${W:?}"; }
trap cleanup EXIT
mkdir -p "$W/out/planned" "$W/backups" "$W/live/backups"
echo '{}' > "$W/out/planned/planned-20261011T000000Z.json"
printf 'BACKUP_DIR=%s\nBACKUP_ENCRYPT_PASSPHRASE=SECRETPASS77\n' "$W/backups" > "$W/env"
: > "$W/backup.log"
cat > "$W/entry.sh" <<EOF
#!/bin/sh
mode=\$(cat "$W/mode")
st=\$(date -u +%Y-%m-%dT%H-%M-%SZ)
echo "=== Parkio hosted-beta backup ==="
{ env | sort; echo "umask=\$(umask)"; echo "cwd=\$(pwd)"; } > "$W/entry-env.txt"
[ -n "\${PARKIO_BACKUP_FIRE_MARKER:-}" ] && echo SCHED_FIRE >> "\$PARKIO_BACKUP_FIRE_MARKER"
case "\$mode" in
  ok) echo "destination=$W/backups/\$st"; mkdir -p "$W/backups/\$st"; printf 'stamp=%s\nsha256sums=x\n' "\$st" > "$W/backups/\$st/COMPLETE"; echo "Backup complete"; exit 0 ;;
  okrel) echo "destination=./backups/\$st"; mkdir -p "$W/live/backups/\$st"; printf 'stamp=%s\nsha256sums=x\n' "\$st" > "$W/live/backups/\$st/COMPLETE"; exit 0 ;;
  okquiet) mkdir -p "$W/backups/\$st"; printf 'stamp=%s\nsha256sums=x\n' "\$st" > "$W/backups/\$st/COMPLETE"; exit 0 ;;
  nocomplete) echo "destination=$W/backups/\$st"; mkdir -p "$W/backups/\$st"; exit 0 ;;
  wrongcomplete) echo "destination=$W/backups/\$st"; mkdir -p "$W/backups/\$st"; printf 'stamp=2026-01-01T00-00-00Z\n' > "$W/backups/\$st/COMPLETE"; exit 0 ;;
  failmask)
    echo "dumping parkio_auth RAWLINE that must not be printed"
    echo "ERROR: upload to https://secretacct.blob.core.windows.net/secretcontainer/x?sv=1&sig=SIGSECRET failed AccountKey=SECRETKEY123;AccountName=secretacct"
    echo "ERROR: offsite/secretbucket/\$st refused token=TOK999"
    echo "ERROR: az failed: --account-key KEYARG777 --sas-token SASARG888 --container-name secretcontainer2"
    echo "ERROR: contact admin@example.org failed Authorization: Bearer BEARER999"
    echo "ERROR: json {\"password\": \"JSONPASS1\"} failed"
    echo "ERROR: host secretacct3.blob.core.windows.net refused"
    echo "ERROR: key material AAAAB3NzaC1yc2EAAAADAQABAAABAQDLONGBASE64KEYMATERIAL0123456789xyz failed"
    exit 1 ;;
  failmany) for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do echo "ERROR: retry \$i failed"; done; exit 1 ;;
  fail75) echo "ERROR: own temporary failure"; exit 75 ;;
esac
EOF
chmod +x "$W/entry.sh"
JOB="30 3 * * * root flock -n $W/lock $W/entry.sh >>$W/backup.log 2>&1"
mkcron() {  # variant
  {
    echo "# Parkio nightly backup (comment)"
    echo "SHELL=/bin/sh"
    echo "PATH=/usr/bin:/bin"
    echo 'MAILTO=""'
    echo 'PARKIO_TEST_VAR="hello world"'
    echo "PARKIO_BACKUP_FIRE_MARKER=$W/fire"
    case "$1" in
      tz) echo "TZ=Europe/Istanbul" ;;
      malformed) echo "garbage line" ;;
    esac
    case "$1" in
      schedule) echo "30 19 * * * root flock -n $W/lock $W/entry.sh >> $W/backup.log 2>&1" ;;
      twojobs) echo "$JOB"; echo "0 12 * * * root flock -n $W/lock $W/entry.sh >> $W/backup.log 2>&1" ;;
      otherlog) echo "30 3 * * * root flock -n $W/lock $W/entry.sh >> $W/other.log 2>&1" ;;
      user) echo "30 3 * * * civo flock -n $W/lock $W/entry.sh >> $W/backup.log 2>&1" ;;
      *) echo "$JOB" ;;
    esac
    echo "LATE_VAR=1"
    echo "15 4 * * * root /usr/bin/true unrelated job"
  } > "$W/cron"
}
export P5A_OUT="$W/out" P5A_CRON_FILE="$W/cron" P5A_LOCK="$W/lock" P5A_LOG="$W/backup.log" P5A_ENTRY="$W/entry.sh" \
       P5A_ENV_FILE="$W/env" P5A_LIVE="$W/live" P5A_SUDO="" P5A_NOW_HM=600 P5A_TZ_OFFSET=+0000 \
       PARKIO_REAL_USER_PASSWORD="SESSIONSECRET42"
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
absent() { if grep -qF -- "$2" "$W/$1"; then echo "FAIL $1: must not show: $2"; fail=1; else echo "ok   $1: does not show $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
yes_() { if eval "$2"; then echo "ok   $1"; else echo "FAIL $1"; fail=1; fi; }
runit() { bash "${2:-$TOOL}" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
fresh() { rm -f "$W/entry-env.txt" "$W/out/P5a-start.txt" "$W/fire"; echo "$1" > "$W/mode"; }

mkcron approved; fresh ok; runit s1.out; sed "s#$W#<W>#g" "$W/s1.out"; rc_is s1.out 0
for t in "P6 planned record present" "host clock UTC" "outside 03:00-04:30 UTC" "no backup or pg_dump process running" \
         "job: '30 3 * * *' as root, approved command; other jobs in the file: 1" \
         "cron variables applied to this run: SHELL PATH MAILTO PARKIO_TEST_VAR" \
         "PARKIO_BACKUP_FIRE_MARKER: not applied" "backup lock free" "backup exit=0" \
         "(log destination): COMPLETE present and names the stamp" "P5A STEP COMPLETE"; do check s1.out "$t"; done
absent s1.out "LATE_VAR"; absent s1.out "hello world"
yes_ "s1: start file written, mode 600" '[ -s "$W/out/P5a-start.txt" ] && [ "$(stat -c %a "$W/out/P5a-start.txt")" = 600 ]'
yes_ "s1: run record PASS with source and variable names" \
  'python3 -c "import glob,json,sys; r=json.load(open(sorted(glob.glob(sys.argv[1]+\"/P5a-run-*.json\"))[-1])); assert r[\"result\"]==\"PASS\" and r[\"complete\"] is True and r[\"stampSource\"]==\"log destination\" and r[\"cronVariables\"]==\"SHELL PATH MAILTO PARKIO_TEST_VAR\", r" "$W/out"'
for t in "PARKIO_TEST_VAR=hello world" "MAILTO=" "PATH=/usr/bin:/bin" "SHELL=/bin/sh" "LOGNAME=$(id -un)" "umask=0022" "cwd=$HOME"; do
  label="$(printf '%s' "$t" | sed "s#=$HOME\$#=<HOME>#; s#=$(id -un)\$#=<user>#")"
  yes_ "s1 entry environment has $label" 'grep -qx -- "$t" "$W/entry-env.txt"'; done
for t in LATE_VAR PARKIO_BACKUP_FIRE_MARKER SESSIONSECRET42 PARKIO_REAL_USER_PASSWORD; do
  yes_ "s1 entry environment lacks $t" '! grep -q -- "$t" "$W/entry-env.txt"'; done
extra="$(grep -v -E '^(umask|cwd)=' "$W/entry-env.txt" | cut -d= -f1 | grep -v -x -E 'PATH|HOME|LOGNAME|SHELL|MAILTO|PARKIO_TEST_VAR|PWD|SHLVL|_|OLDPWD' || true)"
yes_ "s1 entry environment has nothing else (${extra:-none})" '[ -z "$extra" ]'
yes_ "s1 fire marker not written" '[ ! -e "$W/fire" ]'

fresh ok; flock "$W/lock" sleep 4 & BG=$!; sleep 1
runit s2.out; wait "$BG" 2>/dev/null || true; BG=""
rc_is s2.out 1; check s2.out "the backup lock is held (another backup is running); nothing was started"
yes_ "s2: no start file, the entry never ran" '[ ! -e "$W/out/P5a-start.txt" ] && [ ! -e "$W/entry-env.txt" ]'

fresh ok; flock "$W/lock" sleep 4 & BG=$!; sleep 1
P5A_TEST_PRECHECK_LOCK="$W/prelock" runit s2b.out; wait "$BG" 2>/dev/null || true; BG=""
rc_is s2b.out 1; check s2b.out "backup exit=75"; check s2b.out "this run did not start"
yes_ "s2b: the entry never ran" '[ ! -e "$W/entry-env.txt" ]'

fresh failmask; runit s3.out; sed "s#$W#<W>#g" "$W/s3.out" | sed -n '/error lines/,$p'; rc_is s3.out 1
for t in "backup exit=1" "<url>" "<azure-host>" "AccountKey=<hidden>;AccountName=<hidden>" "offsite/<path>" \
         "--account-key <hidden> --sas-token <hidden> --container-name <hidden>" "<email>" "Bearer <hidden>" \
         "<long>" "P5A NOT COMPLETE - do not run P5b or any deployment step"; do check s3.out "$t"; done
for s in SECRETKEY123 TOK999 secretcontainer secretbucket RAWLINE SECRETPASS77 SESSIONSECRET42 SIGSECRET secretacct \
         KEYARG777 SASARG888 admin@example.org BEARER999 JSONPASS1 LONGBASE64KEYMATERIAL; do absent s3.out "$s"; done

fresh fail75; runit s3b.out; rc_is s3b.out 1; check s3b.out "backup exit=75"; check s3b.out "the backup failed (exit 75)"
absent s3b.out "did not start"
fresh failmany; runit s3c.out; rc_is s3c.out 1; check s3c.out "... (4 more) ..."; check s3c.out "ERROR: retry 6 failed"
check s3c.out "ERROR: retry 11 failed"; absent s3c.out "ERROR: retry 7 failed"
yes_ "s3c: 10 error lines plus the elision line" '[ "$(grep -c "^    " "$W/s3c.out")" = 11 ]'

fresh nocomplete; runit s4.out; rc_is s4.out 1; check s4.out "has no COMPLETE that names it"
fresh wrongcomplete; runit s4b.out; rc_is s4b.out 1; check s4b.out "has no COMPLETE that names it"
fresh okquiet; runit s4c.out; rc_is s4c.out 0; check s4c.out "(env file BACKUP_DIR): COMPLETE present"
fresh okrel; runit s4d.out; rc_is s4d.out 0; check s4d.out "(log destination): COMPLETE present"

fresh ok; P5A_NOW_HM=195 runit s5.out; rc_is s5.out 1; check s5.out "03:00-04:30 UTC is the nightly backup window"
absent s5.out "backup exit"
fresh ok; P5A_TZ_OFFSET=+0300 runit s5b.out; rc_is s5b.out 1; check s5b.out "the host clock is not UTC (offset +0300)"
fresh ok; bash -c 'exec -a run-production-backup.sh sleep 4' & BG=$!; sleep 1
runit s6.out; wait "$BG" 2>/dev/null || true; BG=""
rc_is s6.out 1; check s6.out "a backup or pg_dump process is already running"; absent s6.out "backup exit"
fresh ok; mv "$W/out/planned" "$W/out/planned.x"; runit s7.out; mv "$W/out/planned.x" "$W/out/planned"
rc_is s7.out 1; check s7.out "no P6 planned record"
fresh ok; mkdir -p "$W/run-production-backup-check"; cp "$TOOL" "$W/run-production-backup-check/p5a.sh"
runit s8.out "$W/run-production-backup-check/p5a.sh"; rc_is s8.out 0
check s8.out "no backup or pg_dump process running"; check s8.out "P5A STEP COMPLETE"
fresh ok; bash -c ': run-production-backup backup-hosted-beta pg_dump; bash "$1"; rc=$?; exit "$rc"' parent "$TOOL" > "$W/s8b.out" 2>&1 \
  && echo 0 > "$W/s8b.out.rc" || echo $? > "$W/s8b.out.rc"
rc_is s8b.out 0; check s8b.out "no backup or pg_dump process running"; check s8b.out "P5A STEP COMPLETE"

for v in "schedule:the cron schedule is '30 19 * * *', not the approved '30 3 * * *'" \
         "twojobs:the cron file has 2 job line(s) for the backup entry point" \
         "otherlog:the cron command differs from the approved form (lock path 1, entry 1, log path 0" \
         "malformed:line(s) that are neither comments, variables nor jobs" \
         "tz:the cron file sets a timezone variable" \
         "user:the cron job runs as 'civo', not root"; do
  name="${v%%:*}"; want="${v#*:}"; mkcron "$name"; fresh ok; runit "s10-$name.out"; rc_is "s10-$name.out" 1
  check "s10-$name.out" "$want"; absent "s10-$name.out" "start "
  yes_ "s10-$name: no start file, the entry never ran" '[ ! -e "$W/out/P5a-start.txt" ] && [ ! -e "$W/entry-env.txt" ]'
done
rm -f "$W/cron"; fresh ok; runit s10-missing.out; rc_is s10-missing.out 1; check s10-missing.out "cannot read the cron file"

# S11 mawk (Ubuntu's default awk): the cron program gives the same facts and environment as gawk for every cron variant, and
# the guard, the cron check and the elision work when awk is mawk.
if [ -x /usr/bin/mawk ] && [ -x /usr/bin/gawk ]; then
  prog="$(awk '/<<.AWK.$/ { f = 1; next } /^AWK$/ { f = 0 } f' "$TOOL")"
  for v in approved schedule twojobs otherlog malformed tz user; do
    mkcron "$v"
    for m in inspect env; do
      a="$(/usr/bin/mawk -v mode=$m -v lockp="$W/lock" -v entryp="$W/entry.sh" -v logp="$W/backup.log" "$prog" "$W/cron")"
      b="$(/usr/bin/gawk -v mode=$m -v lockp="$W/lock" -v entryp="$W/entry.sh" -v logp="$W/backup.log" "$prog" "$W/cron")"
      yes_ "s11 mawk = gawk for cron variant $v, mode $m" '[ -n "$a" ] && [ "$a" = "$b" ]'
    done
  done
  mkdir -p "$W/shim"; ln -sf /usr/bin/mawk "$W/shim/awk"; mkcron approved
  fresh ok; PATH="$W/shim:$PATH" runit s11-ok.out; rc_is s11-ok.out 0; check s11-ok.out "cron variables applied to this run: SHELL PATH MAILTO PARKIO_TEST_VAR"
  fresh failmany; PATH="$W/shim:$PATH" runit s11-many.out; rc_is s11-many.out 1; check s11-many.out "... (4 more) ..."
  fresh ok; bash -c 'exec -a run-production-backup.sh sleep 4' & BG=$!; sleep 1
  PATH="$W/shim:$PATH" runit s11-guard.out; wait "$BG" 2>/dev/null || true; BG=""
  rc_is s11-guard.out 1; check s11-guard.out "a backup or pg_dump process is already running (1 process(es))"
  fresh ok; PATH="$W/shim:$PATH" bash -c ': run-production-backup; bash "$1"; rc=$?; exit "$rc"' parent "$TOOL" > "$W/s11-parent.out" 2>&1 \
    && echo 0 > "$W/s11-parent.out.rc" || echo $? > "$W/s11-parent.out.rc"
  rc_is s11-parent.out 0
else
  echo "skip s11: mawk or gawk not installed here"
fi
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
