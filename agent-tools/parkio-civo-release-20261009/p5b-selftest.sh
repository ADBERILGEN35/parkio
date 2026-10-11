#!/usr/bin/env bash
# Local self-test of p5b-backup-verify.sh with the live library as of bf9cad51 (scripts/lib/backup-common.sh), an s3-kind
# offsite served by a fake `mc` from a local "remote" directory, a stamp written with the library's own integrity function and
# textfile metrics. S1 identical copy -> VERIFIED; S2 a tampered remote file -> NOT VERIFIED; S3 a stamp older than the start
# -> NOT VERIFIED; S4 a plaintext dump -> NOT VERIFIED; S5 offsite_last_success 0 -> NOT VERIFIED; S6 no remote copy (pull
# fails) -> NOT VERIFIED, temporary copy deleted. Every run: no secret, bucket or pull output in the report; receipt mode 600.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p5b-backup-verify.sh"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; trap 'chmod -R u+rwX "$W" 2>/dev/null; rm -rf "${W:?}"' EXIT
L="$W/live"; mkdir -p "$L/scripts/lib" "$L/docker" "$W/backups" "$W/textfile" "$W/remote" "$W/out" "$W/tmproot" "$W/bin"
git -C "$REPO" show bf9cad51:scripts/lib/backup-common.sh > "$L/scripts/lib/backup-common.sh"
cat > "$L/docker/.env.azure-hosted-beta" <<EOF
BACKUP_DIR=$W/backups
BACKUP_OFFSITE_KIND=s3
BACKUP_MC_DEST=offsite/secretbucket
BACKUP_ENCRYPT_PASSPHRASE=SECRETVALUE123
PARKIO_PROMETHEUS_TEXTFILE_DIR=$W/textfile
EOF
cat > "$W/bin/mc" <<EOF
#!/usr/bin/env bash
# fake mc: "mirror [--overwrite] offsite/secretbucket/<stamp> <dir>/" copies from $W/remote/<stamp>
echo "mc: mirroring from offsite/secretbucket (fake)"
[ "\$1" = mirror ] || exit 2; shift; [ "\$1" = --overwrite ] && shift
src="$W/remote/\${1#offsite/secretbucket/}"; [ -d "\$src" ] || { echo "mc: no such object \$1" >&2; exit 1; }
mkdir -p "\$2"; cp -a "\$src/." "\$2"
EOF
chmod +x "$W/bin/mc"; export PATH="$W/bin:$PATH"
mkstamp() {  # name [plaintext]
  local d="$W/backups/$1"; rm -rf "${d:?}"; mkdir -p "$d"
  head -c 2048 /dev/urandom > "$d/parkio_auth.sql.gz.enc"; head -c 1024 /dev/urandom > "$d/minio.tar.gz.enc"
  [ -n "${2:-}" ] && echo "plain" | gzip > "$d/parkio_user.sql.gz"
  (cd "$L" && . scripts/lib/backup-common.sh && parkio_backup_write_stamp_integrity "$d" "$1")
  rm -rf "${W:?}/remote/$1"; cp -a "$d" "$W/remote/$1"
}
metrics() {  # offsite_ok timestamp
  printf 'parkio_backup_last_success{scope="azure-hosted-beta"} 1\nparkio_backup_last_timestamp_seconds{scope="azure-hosted-beta"} %s\nparkio_backup_databases_failed{scope="azure-hosted-beta"} 0\nparkio_backup_minio_objects{scope="azure-hosted-beta",bucket="parkio-media"} 3\nparkio_backup_offsite_last_success{scope="azure-hosted-beta"} %s\nparkio_backup_encryption_enabled{scope="azure-hosted-beta"} 1\nparkio_backup_last_bytes{scope="azure-hosted-beta"} 3072\nparkio_backup_production_mode{scope="azure-hosted-beta"} 1\n' "$2" "$1" > "$W/textfile/parkio_backup.prom"
}
export P5B_TEST=1 P5B_LIVE="$L" P5B_OUT="$W/out" P5B_OWNER="$(id -un)" P5B_TMPROOT="$W/tmproot"
fail=0
check() { if grep -qF -- "$2" "$W/$1"; then echo "ok   $1: $2"; else echo "FAIL $1: $2"; fail=1; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && echo "ok   $1 exit $2" || { echo "FAIL $1 exit $(cat "$W/$1.rc"), want $2"; fail=1; }; }
runit() { bash "$TOOL" "$2" > "$W/$1" 2>&1 && echo 0 > "$W/$1.rc" || echo $? > "$W/$1.rc"; }
clean() { if grep -qE 'SECRETVALUE123|secretbucket|mirroring' "$W/$1"; then echo "FAIL $1: secret, bucket or pull output in the report"; fail=1; else echo "ok   $1: no secret, bucket or pull output"; fi
          [ -z "$(ls -A "$W/tmproot")" ] && echo "ok   $1: temporary copy deleted" || { echo "FAIL $1: temporary copy left"; fail=1; }; }
START="$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ)"; NOWS="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
mkstamp 2026-01-01T03-30-00Z; mkstamp "$NOWS"; metrics 1 "$(date -u +%s)"
runit s1.out "$START"; sed "s#$W#<W>#g" "$W/s1.out"; rc_is s1.out 0
check s1.out "offsite kind: s3; pull function present"
check s1.out "PASS newest stamp $NOWS is not older than the P5a start"
check s1.out "PASS COMPLETE names the stamp and the SHA-256 of SHA256SUMS"
check s1.out "PASS local sha256sum -c --strict"
check s1.out "PASS SHA256SUMS lists every file except itself and COMPLETE (2 of 4)"
check s1.out "PASS no plaintext dump"
check s1.out "PASS last_timestamp_seconds after the P5a start"
check s1.out "PASS offsite SHA256SUMS byte-identical"
check s1.out "PASS offsite COMPLETE byte-identical"
check s1.out "PASS offsite sha256sum -c --strict"
check s1.out "PASS offsite holds the same 4 file names"
check s1.out "offsite copy identical: true"
check s1.out "P5B VERIFIED"; clean s1.out
[ "$(stat -c %a "$W/out/backup/P5b-receipt-$NOWS.json")" = 600 ] && echo "ok   receipt mode 600" || { echo "FAIL receipt mode"; fail=1; }
python3 -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"] == "PASS" and r["offsiteIdentical"] is True and r["files"] == 4, r; s=json.dumps(r); assert "secretbucket" not in s and "SECRET" not in s' "$W/out/backup/P5b-receipt-$NOWS.json" && echo "ok   receipt content" || { echo "FAIL receipt"; fail=1; }
head -c 10 /dev/urandom >> "$W/remote/$NOWS/minio.tar.gz.enc"
runit s2.out "$START"; rc_is s2.out 1; check s2.out "FAIL offsite pull failed (exit"; check s2.out "PASS offsite SHA256SUMS byte-identical"
check s2.out "FAIL offsite checksum verification failed"; check s2.out "offsite copy identical: false"; check s2.out "P5B NOT VERIFIED"; clean s2.out
mkstamp "$NOWS"
runit s3.out "$(date -u -d '+5 minutes' +%Y-%m-%dT%H:%M:%SZ)"; rc_is s3.out 1; check s3.out "is older than the P5a start (P5a made no new stamp)"
mkstamp "$NOWS" plain
runit s4.out "$START"; rc_is s4.out 1; check s4.out "FAIL plaintext dump present (1 file(s); library check fail)"
mkstamp "$NOWS"; metrics 0 "$(date -u +%s)"
runit s5.out "$START"; rc_is s5.out 1; check s5.out "FAIL offsite_last_success = 0 (want 1)"
metrics 1 "$(date -u +%s)"; rm -rf "${W:?}/remote/$NOWS"
runit s6.out "$START"; rc_is s6.out 1; check s6.out "FAIL offsite pull failed (exit"; check s6.out "its output is not shown"; clean s6.out
runit s7.out "not-a-time"; rc_is s7.out 1; check s7.out "STOP: usage: p5b-backup-verify.sh"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
