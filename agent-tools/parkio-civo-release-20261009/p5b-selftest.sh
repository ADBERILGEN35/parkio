#!/usr/bin/env bash
# Local self-test of p5b-backup-verify.sh (v2) with the live library as of bf9cad51 (scripts/lib/backup-common.sh), an s3-kind
# offsite served by a fake `mc` from a local "remote" directory, stamps written with the library's own integrity function,
# textfile metrics, a P5a record and a cron file. The fake `mc` is reachable only through the PATH line of the test cron file,
# and the caller's environment carries a wrong offsite kind and destination, so a pass shows that the library ran in the cron
# job's environment and not in the caller's.
# S1 identical copy -> VERIFIED with a receipt; S1m the same with awk = mawk everywhere (the Ubuntu default); S2 a tampered
# remote file -> NOT VERIFIED; S3 another stamp than the P5a record names -> STOP; S4 a plaintext dump -> NOT VERIFIED;
# S5 offsite_last_success 0 -> NOT VERIFIED; S6 no remote copy -> NOT VERIFIED with a category only; S7 a bad argument -> STOP;
# S8 the cron variables changed after P5a -> STOP; S9 a second backup job -> STOP; S10 a failed P5a record -> STOP; S11 an *.enc
# file without the OpenSSL header -> NOT VERIFIED; S12 not enough free space -> NOT VERIFIED, nothing pulled; S13 the cron file
# reader is byte-identical to P5a's; S14 a P5a start after the stamp -> STOP. Every run: no secret, bucket or pull output in
# the report; the temporary copy is deleted.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p5b-backup-verify.sh"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; trap 'chmod -R u+rwX "$W" 2>/dev/null; rm -rf "${W:?}"' EXIT
L="$W/live"
mkdir -p "$L/scripts/lib" "$L/docker" "$W/backups" "$W/textfile" "$W/remote" "$W/out" "$W/tmproot" "$W/cronbin" "$W/mawkbin" "$W/home"
git -C "$REPO" show bf9cad51:scripts/lib/backup-common.sh > "$L/scripts/lib/backup-common.sh"
cat > "$L/docker/.env.azure-hosted-beta" <<EOF
BACKUP_DIR=$W/backups
BACKUP_OFFSITE_KIND=s3
BACKUP_MC_DEST=offsite/secretbucket
BACKUP_ENCRYPT_PASSPHRASE=SECRETVALUE123
PARKIO_PROMETHEUS_TEXTFILE_DIR=$W/textfile
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
EOF
cat > "$W/cronbin/mc" <<EOF
#!/usr/bin/env bash
# fake mc: "mirror [--overwrite] offsite/secretbucket/<stamp> <dir>/" copies from $W/remote/<stamp>
echo "mc: mirroring from offsite/secretbucket (fake)"
[ "\$1" = mirror ] || exit 2; shift; [ "\$1" = --overwrite ] && shift
case "\$1" in offsite/secretbucket/*) ;; *) echo "mc: no such object \$1" >&2; exit 1 ;; esac
src="$W/remote/\${1#offsite/secretbucket/}"; [ -d "\$src" ] || { echo "mc: no such object \$1" >&2; exit 1; }
mkdir -p "\$2"; cp -a "\$src/." "\$2"
EOF
printf '#!/bin/sh\nexec mawk "$@"\n' > "$W/mawkbin/awk"
chmod +x "$W/cronbin/mc" "$W/mawkbin/awk"
JOB="30 3 * * * root flock -n $W/backup.lock $W/run-production-backup.sh >> $W/backup.log 2>&1"
cronfile() {  # [extra lines before the job]
  { echo "# parkio backup (test)"; echo "SHELL=/bin/bash"; echo "PATH=$W/cronbin:/usr/bin:/bin"; echo 'MAILTO=""'
    [ -n "${1:-}" ] && printf '%s\n' "$1"; echo "$JOB"; } > "$W/parkio-backup"
}
mkstamp() {  # name [plain|noheader]
  local d="$W/backups/$1"; rm -rf "${d:?}"; mkdir -p "$d"
  { printf 'Salted__'; head -c 2040 /dev/urandom; } > "$d/parkio_auth.sql.gz.enc"
  if [ "${2:-}" = noheader ]; then head -c 1024 /dev/urandom > "$d/minio.tar.gz.enc"
  else { printf 'Salted__'; head -c 1016 /dev/urandom; } > "$d/minio.tar.gz.enc"; fi
  [ "${2:-}" = plain ] && echo "plain" | gzip > "$d/parkio_user.sql.gz"
  (cd "$L" && . scripts/lib/backup-common.sh && parkio_backup_write_stamp_integrity "$d" "$1")
  rm -rf "${W:?}/remote/$1"; cp -a "$d" "$W/remote/$1"
}
metrics() {  # offsite_ok timestamp
  printf 'parkio_backup_last_success{scope="azure-hosted-beta"} 1\nparkio_backup_last_timestamp_seconds{scope="azure-hosted-beta"} %s\nparkio_backup_databases_failed{scope="azure-hosted-beta"} 0\nparkio_backup_minio_objects{scope="azure-hosted-beta",bucket="parkio-media"} 3\nparkio_backup_offsite_last_success{scope="azure-hosted-beta"} %s\nparkio_backup_encryption_enabled{scope="azure-hosted-beta"} 1\nparkio_backup_last_bytes{scope="azure-hosted-beta"} 3072\nparkio_backup_production_mode{scope="azure-hosted-beta"} 1\n' "$2" "$1" > "$W/textfile/parkio_backup.prom"
}
p5arecord() {  # start stamp result
  echo "$1" > "$W/out/P5a-start.txt"
  printf '{"tool": "p5a-backup-run", "start": "%s", "end": "%s", "exit": %s, "durationSeconds": 28, "stamp": "%s", "complete": %s, "stampSource": "log destination", "cronVariables": "SHELL PATH MAILTO", "result": "%s"}\n' \
    "$1" "$1" "$([ "$3" = PASS ] && echo 0 || echo 1)" "$2" "$([ "$3" = PASS ] && echo true || echo false)" "$3" > "$W/out/P5a-run-${1//:/-}.json"
}
export P5B_TEST=1 P5B_LIVE="$L" P5B_OUT="$W/out" P5B_OWNER="$(id -un)" P5B_TMPROOT="$W/tmproot" P5B_CRON_FILE="$W/parkio-backup" \
  P5B_LOCK="$W/backup.lock" P5B_ENTRY="$W/run-production-backup.sh" P5B_LOG="$W/backup.log" P5B_HOME="$W/home" \
  P5B_LOGNAME="$(id -un)"
# The caller's environment must not reach the library: a wrong kind and destination here would make every pull fail.
export BACKUP_OFFSITE_KIND=none BACKUP_MC_DEST=offsite/wrongbucket
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { local out="$1"; shift; bash "$TOOL" "$@" > "$W/$out" 2>&1 && echo 0 > "$W/$out.rc" || echo $? > "$W/$out.rc"; }
clean() { if grep -qE 'SECRETVALUE123|secretbucket|wrongbucket|mirroring' "$W/$1"; then echo "FAIL $1: secret, bucket or pull output in the report"; fail=1; else echo "ok   $1: no secret, bucket or pull output"; fi
          [ -z "$(ls -A "$W/tmproot")" ] && echo "ok   $1: temporary copy deleted" || { echo "FAIL $1: temporary copy left"; fail=1; }; }
START="$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)"; NOWS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"; OLD=2026-01-01T03-30-00Z
cronfile; mkstamp "$OLD"; mkstamp "$NOWS"; metrics 1 "$(date -u +%s)"; p5arecord "$START" "$NOWS" PASS

runit s1.out "$NOWS"; sed -e "s#$W#<W>#g" -e "s#$(id -un)#<user>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "P5a run $START: result PASS, exit 0, COMPLETE present, stamp $NOWS"
check s1.out "job: '30 3 * * *' as root, approved command"
check s1.out "cron variables applied: SHELL PATH MAILTO (the same as in P5a)"
check s1.out "deployment profile azure-hosted-beta; offsite kind s3; encryption passphrase set"
check s1.out "PASS COMPLETE names the stamp and the SHA-256 of SHA256SUMS"
check s1.out "PASS local sha256sum -c --strict"
check s1.out "PASS SHA256SUMS lists every file except itself and COMPLETE (2 of 4)"
check s1.out "PASS 2 encrypted files (*.enc), each with the OpenSSL salted header"
check s1.out "PASS no plaintext dump"
check s1.out "newer local stamps: 0"
check s1.out "PASS the env file's deployment profile is azure-hosted-beta"
check s1.out "PASS last_timestamp_seconds not before the P5a start"
check s1.out "PASS pulled with the library's parkio_backup_offsite_pull; its own checksum verification passed"
check s1.out "PASS offsite SHA256SUMS byte-identical"
check s1.out "PASS offsite COMPLETE byte-identical"
check s1.out "PASS offsite sha256sum -c --strict"
check s1.out "PASS offsite holds the same 4 file names"
check s1.out "PASS offsite copy encrypted: 2 *.enc files, each with the OpenSSL salted header"
check s1.out "PASS no plaintext dump offsite"
check s1.out "PASS temporary copy deleted"
check s1.out "offsite copy identical: true"
check s1.out "D2a window: start before"
check s1.out "P5B VERIFIED"; clean s1.out
R1="$W/out/backup/P5b-receipt-$NOWS.json"
[ "$(stat -c %a "$R1")" = 600 ] && echo "ok   receipt mode 600" || { echo "FAIL receipt mode"; fail=1; }
python3 -I -c '
import json, sys
r = json.load(open(sys.argv[1]))
assert r["result"] == "PASS" and r["stamp"] == sys.argv[2] and r["offsiteIdentical"] is True and r["files"] == 4, r
assert r["encryptedFiles"] == 2 and r["version"] == 2 and r["cronVariables"] == "SHELL PATH MAILTO" and r["failedChecks"] == 0, r
s = json.dumps(r)
assert "secretbucket" not in s and "SECRET" not in s and "wrongbucket" not in s
' "$R1" "$NOWS" && echo "ok   receipt content" || { echo "FAIL receipt"; fail=1; }

PATH="$W/mawkbin:$PATH" P5B_CRON_PATH="$W/mawkbin:/usr/bin:/bin" runit s1m.out "$NOWS"; rc_is s1m.out 0
check s1m.out "cron variables applied: SHELL PATH MAILTO (the same as in P5a)"
check s1m.out "offsite copy identical: true"; check s1m.out "P5B VERIFIED"; clean s1m.out

head -c 10 /dev/urandom >> "$W/remote/$NOWS/minio.tar.gz.enc"
runit s2.out "$NOWS"; rc_is s2.out 1
check s2.out "FAIL offsite pull failed (exit"; check s2.out "the pulled copy failed the library's checksum verification"
check s2.out "PASS offsite SHA256SUMS byte-identical"; check s2.out "FAIL offsite checksum verification failed"
check s2.out "offsite copy identical: false"; check s2.out "P5B NOT VERIFIED"; clean s2.out
mkstamp "$NOWS"

runit s3.out "$OLD"; rc_is s3.out 1; check s3.out "STOP: the last P5a run made stamp $NOWS, not $OLD"

mkstamp "$NOWS" plain
runit s4.out "$NOWS"; rc_is s4.out 1
check s4.out "FAIL plaintext dump present (1 file(s) or minio/ tree; library check fail)"
check s4.out "FAIL plaintext dump offsite (1 file(s) or minio/ tree; library check fail)"; clean s4.out
mkstamp "$NOWS"; metrics 0 "$(date -u +%s)"
runit s5.out "$NOWS"; rc_is s5.out 1; check s5.out "FAIL offsite_last_success = 0 (want 1)"; check s5.out "P5B NOT VERIFIED"
metrics 1 "$(date -u +%s)"

mv "$W/remote/$NOWS" "$W/remote-away"
runit s6.out "$NOWS"; rc_is s6.out 1
check s6.out "FAIL offsite pull failed (exit"; check s6.out "the stamp, bucket or container was not found offsite"
check s6.out "the pull's output is not shown"; clean s6.out
mv "$W/remote-away" "$W/remote/$NOWS"

runit s7.out "not-a-stamp"; rc_is s7.out 1; check s7.out "STOP: usage: p5b-backup-verify.sh"

cronfile "BACKUP_EXTRA=1"
runit s8.out "$NOWS"; rc_is s8.out 1; check s8.out "STOP: the cron variables (SHELL PATH MAILTO BACKUP_EXTRA) differ from those P5a applied (SHELL PATH MAILTO)"
cronfile "15 4 * * * root /opt/parkio/scripts/backup-hosted-beta.sh"
runit s9.out "$NOWS"; rc_is s9.out 1; check s9.out "STOP: the cron file has 2 job line(s) for the backup entry point (want exactly 1)"
cronfile

p5arecord "$START" "$NOWS" FAIL
runit s10.out "$NOWS"; rc_is s10.out 1; check s10.out "STOP: the P5a record does not report a complete backup (result FAIL, exit 1, COMPLETE false)"
p5arecord "$START" "$NOWS" PASS

mkstamp "$NOWS" noheader
runit s11.out "$NOWS"; rc_is s11.out 1
check s11.out "FAIL encrypted files: 2, with the OpenSSL salted header: 1"
check s11.out "FAIL offsite encrypted files: 2 (local 2), with the OpenSSL salted header: 1"; clean s11.out
mkstamp "$NOWS"

P5B_MARGIN=999999999999999999 runit s12.out "$NOWS"; rc_is s12.out 1
check s12.out "FAIL not enough free space under"; check s12.out "not pulled"; check s12.out "P5B NOT VERIFIED"; clean s12.out

awkprog() { sed -n "/^read -r -d '' CRON_AWK <<'AWK'\$/,/^AWK\$/p" "$1"; }
if [ -n "$(awkprog "$TOOL")" ] && [ "$(awkprog "$TOOL" | sha256sum)" = "$(awkprog "$HERE/p5a-backup-run.sh" | sha256sum)" ]; then
  echo "ok   s13: the cron file reader is byte-identical to P5a's"
else
  echo "FAIL s13: the cron file reader differs from P5a's"; fail=1
fi

p5arecord "$(date -u -d '+5 minutes' +%Y-%m-%dT%H:%M:%SZ)" "$NOWS" PASS
runit s14.out "$NOWS"; rc_is s14.out 1; check s14.out "is older than the P5a start"

[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
