#!/usr/bin/env bash
# Local self-test of p5a-backup-run.sh without sudo (P5A_SUDO=""), with a fake entry point, a private lock, log, env file and
# backup dir. S1 success -> COMPLETE, start file and record written; S2 the lock held by another holder -> exit-75 path, the
# entry never runs; S3 the entry fails -> at most 10 masked, shortened error lines, no secret, no raw log line; S4 a stamp
# without COMPLETE -> STOP; S5 03:15 UTC -> STOP before anything runs; S6 a process named like the backup is running -> STOP;
# S7 no P6 record -> STOP; S8 the tool runs from a path containing "run-production-backup" and its guard does not match
# itself; S9 the entry gets a cron-like environment only (a session variable does not reach it).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p5a-backup-run.sh"
W="$(mktemp -d)"; BG=""
cleanup() { [ -n "$BG" ] && kill "$BG" 2>/dev/null || true; rm -rf "${W:?}"; }
trap cleanup EXIT
mkdir -p "$W/out/planned" "$W/backups" "$W/live"
echo '{}' > "$W/out/planned/planned-20261011T000000Z.json"
printf 'BACKUP_DIR=%s\nBACKUP_ENCRYPT_PASSPHRASE=SECRETPASS77\n' "$W/backups" > "$W/env"
: > "$W/backup.log"
cat > "$W/entry.sh" <<EOF
#!/bin/sh
mode=\$(cat "$W/mode")
st=\$(date -u +%Y-%m-%dT%H-%M-%SZ)
echo "Backup starting \$st"
env | sort > "$W/entry-env.txt"
case "\$mode" in
  ok) mkdir -p "$W/backups/\$st"; echo "stamp=\$st" > "$W/backups/\$st/COMPLETE"; echo "Backup complete"; exit 0 ;;
  nocomplete) mkdir -p "$W/backups/\$st"; exit 0 ;;
  fail) echo "dumping parkio_auth RAWLINE that must not be printed"
        echo "ERROR: upload to azure://secretcontainer/\$st failed AccountKey=SECRETKEY123"
        echo "ERROR: offsite/secretbucket/\$st refused token=TOK999"
        for i in 1 2 3 4 5 6 7 8 9 10 11 12; do echo "ERROR: retry \$i failed"; done
        exit 1 ;;
esac
EOF
chmod +x "$W/entry.sh"
export P5A_OUT="$W/out" P5A_LOCK="$W/lock" P5A_LOG="$W/backup.log" P5A_ENTRY="$W/entry.sh" P5A_ENV_FILE="$W/env" \
       P5A_LIVE="$W/live" P5A_SUDO="" P5A_NOW_HM=600 PARKIO_REAL_USER_PASSWORD="SESSIONSECRET42"
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
absent() { if grep -qF -- "$2" "$W/$1"; then echo "FAIL $1: must not show: $2"; fail=1; else echo "ok   $1: does not show $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { bash "${3:-$TOOL}" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
echo ok > "$W/mode"; runit s1.out; sed "s#$W#<W>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "P6 planned record present"; check s1.out "outside 03:00-04:30 UTC"; check s1.out "no backup or pg_dump process running"
check s1.out "backup exit=0"; check s1.out "COMPLETE present"; check s1.out "P5A STEP COMPLETE"
[ -s "$W/out/P5a-start.txt" ] && [ "$(stat -c %a "$W/out/P5a-start.txt")" = 600 ] && echo "ok   start file written, mode 600" || { echo "FAIL start file"; fail=1; }
python3 -c 'import glob,json,sys; r=json.load(open(sorted(glob.glob(sys.argv[1]+"/P5a-run-*.json"))[-1])); assert r["result"]=="PASS" and r["complete"] is True and r["exit"]==0, r' "$W/out" \
  && echo "ok   run record PASS" || { echo "FAIL run record"; fail=1; }
if grep -q 'SESSIONSECRET42' "$W/entry-env.txt"; then echo "FAIL s9 a session variable reached the entry point"; fail=1; else echo "ok   s9: a session variable does not reach the entry point"; fi
[ "$(cut -d= -f1 "$W/entry-env.txt" | grep -v -E '^(PATH|HOME|LOGNAME|SHELL|PWD|SHLVL|_|OLDPWD)$' | wc -l)" = 0 ] && echo "ok   s9: cron-like environment (PATH, HOME, LOGNAME, SHELL only)" || { echo "FAIL s9 extra env: $(cut -d= -f1 "$W/entry-env.txt" | tr '\n' ' ')"; fail=1; }
flock "$W/lock" sleep 4 & BG=$!; sleep 1   # another holder of the backup lock for 4 s
rm -f "$W/entry-env.txt"; runit s2.out; wait "$BG" 2>/dev/null || true; BG=""
rc_is s2.out 1; check s2.out "backup exit=75"; check s2.out "the backup lock is held"
[ ! -e "$W/entry-env.txt" ] && echo "ok   s2: the entry point never ran" || { echo "FAIL s2 entry ran"; fail=1; }
echo fail > "$W/mode"; runit s3.out; sed "s#$W#<W>#g" "$W/s3.out" | tail -14; rc_is s3.out 1
check s3.out "backup exit=1"; check s3.out "<url>"; check s3.out "offsite/<path>"; check s3.out "AccountKey=<hidden>"
for s in SECRETKEY123 TOK999 secretcontainer secretbucket RAWLINE SECRETPASS77 SESSIONSECRET42; do absent s3.out "$s"; done
[ "$(grep -c '^    ' "$W/s3.out")" -le 11 ] && echo "ok   s3: at most 10 error lines plus the elision line" || { echo "FAIL s3 too many lines"; fail=1; }
check s3.out "... (4 more) ..." 
check s3.out "P5A NOT COMPLETE - do not run P5b or any deployment step"
echo nocomplete > "$W/mode"; runit s4.out; rc_is s4.out 1; check s4.out "has no COMPLETE"
echo ok > "$W/mode"; P5A_NOW_HM=195 runit s5.out; rc_is s5.out 1; check s5.out "03:00-04:30 UTC is the nightly backup window"
absent s5.out "backup exit"
bash -c 'exec -a run-production-backup.sh sleep 4' & BG=$!; sleep 1   # a process that looks like the nightly backup
runit s6.out; wait "$BG" 2>/dev/null || true; BG=""
rc_is s6.out 1; check s6.out "a backup or pg_dump process is already running"; absent s6.out "backup exit"
mv "$W/out/planned" "$W/out/planned.x"; runit s7.out; mv "$W/out/planned.x" "$W/out/planned"
rc_is s7.out 1; check s7.out "no P6 planned record"
mkdir -p "$W/run-production-backup-check"; cp "$TOOL" "$W/run-production-backup-check/p5a.sh"
runit s8.out "" "$W/run-production-backup-check/p5a.sh"; rc_is s8.out 0
check s8.out "no backup or pg_dump process running"; check s8.out "P5A STEP COMPLETE"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
