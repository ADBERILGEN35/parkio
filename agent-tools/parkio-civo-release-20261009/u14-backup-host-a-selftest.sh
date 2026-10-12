#!/usr/bin/env bash
# Local self-test of u14-backup-host-a.sh. A "release checkout" (git repo) holds the reviewed scripts of the PIN
# (backup-dated-acceptance.sh, backup-offsite-pull.sh, lib/backup-common.sh, lib/restore-stamp-preflight.py,
# lib/restore-drill-isolation-preflight.py); the "live checkout" holds the bf9cad51 library and an env file whose offsite is an
# s3 bucket served from a local directory by a fake mc (ls --json, mirror) that only the test cron file's PATH reaches. The
# stamp is a synthetic sealed stamp encrypted with a synthetic passphrase (the dated-acceptance test's own construction). The
# caller's environment carries a wrong offsite destination and passphrase, so a pass shows that the acceptance ran in the
# cron job's environment. The custody prompt is answered through a real pseudo-terminal (script).
# C1 the recovery copy opens the stamp -> outcomes 1-4 PASS, 5 NOT_RUN, custody PASS, P5b receipt matches, evidence 700/600,
#    temporary copy deleted, no secret in output or evidence; C2 Enter -> custody NOT_RUN; C3 a wrong recovery passphrase ->
#    custody FAIL while outcome 4 (operational) PASSES; C4 a stamp without erasure-tombstones.json (the bf9cad51 writer) ->
#    outcome 1 FAIL naming the ledger, 2-4 PASS; C5 a tampered offsite object -> outcome 3 FAIL, 4 NOT_RUN, custody NOT_RUN;
#    C6 a dirty release checkout, C7 another acceptance script, C8 an absent stamp (lists the stamps), C9 a second backup job
#    -> STOP with nothing pulled; C10 the cron reader is byte-identical to P5b's.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u14-backup-host-a.sh"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
PIN_SRC="${U14T_PIN_SRC:-f888a81b113c0c486ea0949067779443857e644d}"
JQ="${U14T_JQ:-$(command -v jq)}"
[ -n "$JQ" ] || { echo "SELFTEST SETUP: jq is required (set U14T_JQ)"; exit 2; }
W="$(mktemp -d)"; trap 'chmod -R u+rwX "$W" 2>/dev/null; rm -rf "${W:?}"' EXIT
PASS="u14a-synthetic-$(openssl rand -hex 6)"; WRONG="not-the-key-$(openssl rand -hex 4)"
STAMP="2026-10-12T11-18-41Z"
DATABASES="auth gateway user parking media gamification notification moderation analytics ai-validation"
R="$W/release"; L="$W/live"
mkdir -p "$R/scripts/lib" "$L/scripts/lib" "$L/docker" "$W/backups" "$W/bucket" "$W/out/backup" "$W/tmproot" "$W/cronbin" "$W/home"
for f in scripts/backup-dated-acceptance.sh scripts/backup-offsite-pull.sh scripts/lib/backup-common.sh \
         scripts/lib/restore-stamp-preflight.py scripts/lib/restore-drill-isolation-preflight.py; do
  git -C "$REPO" show "$PIN_SRC:$f" > "$R/$f"
done
chmod +x "$R/scripts/"*.sh
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -q -m pin
RPIN="$(git -C "$R" rev-parse HEAD)"
git -C "$REPO" show bf9cad51:scripts/lib/backup-common.sh > "$L/scripts/lib/backup-common.sh"
cat > "$L/docker/.env.azure-hosted-beta" <<EOF
BACKUP_DIR=$W/backups
BACKUP_OFFSITE_KIND=s3
BACKUP_MC_DEST=$W/bucket
BACKUP_ENCRYPT_PASSPHRASE=$PASS
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta
EOF
cp "$JQ" "$W/cronbin/jq"
cat > "$W/cronbin/mc" <<'PY'
#!/usr/bin/env python3
import json, os, shutil, sys
args = sys.argv[1:]
if args[:1] == ["ls"]:
    prefix = args[-1].rstrip("/")
    if not os.path.isdir(prefix):
        print(json.dumps({"status": "error", "error": {"message": "Object does not exist."}}))
        sys.exit(1)
    for base, _, files in os.walk(prefix):
        for name in sorted(files):
            path = os.path.join(base, name)
            print(json.dumps({"status": "success", "type": "file", "size": os.path.getsize(path),
                              "key": os.path.relpath(path, prefix)}))
elif args[:1] == ["mirror"]:
    src, dst = args[-2], args[-1]
    if not os.path.isdir(src):
        sys.stderr.write("mc: source does not exist\n")
        sys.exit(1)
    shutil.copytree(src, dst, dirs_exist_ok=True)
else:
    sys.stderr.write(f"unexpected mc call: {args}\n")
    sys.exit(2)
PY
chmod +x "$W/cronbin/mc" "$W/cronbin/jq"
JOB="30 3 * * * root flock -n $W/backup.lock $W/run-production-backup.sh >> $W/backup.log 2>&1"
cronfile() {  # [extra line after the job]
  { echo "# parkio backup (test)"; echo "SHELL=/bin/bash"; echo "PATH=$W/cronbin:/usr/bin:/bin"; echo 'MAILTO=""'
    echo "$JOB"; [ -n "${1:-}" ] && printf '%s\n' "$1"; } > "$W/parkio-backup"
}
make_stamp() {  # dir [noledger]
  local dir="$1" db src
  rm -rf "${dir:?}"; mkdir -p "$dir"
  for db in $DATABASES; do
    printf -- '-- synthetic %s dump\nSELECT 1;\n' "$db" | gzip -c \
      | BACKUP_ENCRYPT_PASSPHRASE="$PASS" openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_ENCRYPT_PASSPHRASE > "$dir/$db.sql.gz.enc"
  done
  src="$(mktemp -d "$W/minio.XXXXXX")"; mkdir -p "$src/minio/parkio-media"; printf 'synthetic-object\n' > "$src/minio/parkio-media/object.bin"
  tar -C "$src" -czf - minio | BACKUP_ENCRYPT_PASSPHRASE="$PASS" openssl enc -aes-256-cbc -pbkdf2 -salt \
    -pass env:BACKUP_ENCRYPT_PASSPHRASE > "$dir/minio.tar.gz.enc"
  rm -rf "$src"
  [ "${2:-}" = noledger ] || printf '[]\n' > "$dir/erasure-tombstones.json"
  python3 -I - "$dir" "$DATABASES" <<'PY'
import json, sys
dest, databases = sys.argv[1], sys.argv[2].split()
json.dump({"schemaVersion": 3, "action": "backup", "timestamp": dest.rsplit("/", 1)[-1],
           "databases": databases, "databasesOk": len(databases), "databasesFailed": 0, "minioOk": 1,
           "minio": {"objectCount": 1, "artifact": "minio.tar.gz.enc"},
           "encryption": {"enabled": True, "algorithm": "aes-256-cbc-pbkdf2"},
           "offsite": {"kind": "s3", "uploaded": False}, "checksums": {"sha256sums": "SHA256SUMS"}},
          open(f"{dest}/backup-manifest.json", "w"))
PY
  (cd "$R" && . scripts/lib/backup-common.sh && parkio_backup_write_stamp_integrity "$dir" "$(basename "$dir")")
  rm -rf "${W:?}/bucket/$(basename "$dir")"; cp -a "$dir" "$W/bucket/$(basename "$dir")"
}
receipt() {
  python3 -I - "$W/out/backup/P5b-receipt-$STAMP.json" "$(sha256sum "$W/backups/$STAMP/SHA256SUMS" | cut -d' ' -f1)" <<'PY'
import json, sys
json.dump({"tool": "p5b-backup-verify", "result": "PASS", "verifiedAt": "2026-10-12T11:25:00Z", "offsiteIdentical": True,
           "sha256sumsSha256": sys.argv[2]}, open(sys.argv[1], "w"))
PY
}
export U14A_TEST=1 U14A_RELEASE="$R" U14A_PIN="$RPIN" U14A_SCRIPT_SHA="$(sha256sum "$R/scripts/backup-dated-acceptance.sh" | cut -d' ' -f1)" \
  U14A_LIVE="$L" U14A_OUT="$W/out" U14A_OWNER="$(id -un)" U14A_TMPROOT="$W/tmproot" U14A_CRON_FILE="$W/parkio-backup" \
  U14A_LOCK="$W/backup.lock" U14A_ENTRY="$W/run-production-backup.sh" U14A_LOG="$W/backup.log" U14A_HOME="$W/home" \
  U14A_LOGNAME="$(id -un)" U14A_MARGIN=0
# the caller's environment points elsewhere; only the cron job's environment may reach the acceptance
export BACKUP_MC_DEST="$W/nowhere" BACKUP_ENCRYPT_PASSPHRASE="$WRONG" BACKUP_OFFSITE_KIND=s3 PARKIO_ENV_FILE="$W/nowhere.env"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasE() { if grep -qE -- "$2" "$W/$1"; then ok "$1: /$2/"; else no "$1: /$2/"; fi; }
runit() {  # name answer [stamp]; the answer goes to the hidden prompt through a pseudo-terminal
  local name="$1" answer="$2" st="${3:-$STAMP}"
  { sleep 1; printf '%s\n' "$answer"; sleep 4; } | script -qfec "bash '$TOOL' '$st'; echo \"wrapper exit=\$?\"" /dev/null \
    | tr -d '\r' > "$W/$name" 2>&1
}
clean() {  # name: no secret in the output or in any evidence file
  if grep -r -q -F -e "$PASS" -e "$WRONG" "$W/$1" "$W/out/u14" 2>/dev/null; then no "$1: a passphrase reached the output or the evidence"
  else ok "$1: no passphrase in the output or the evidence"; fi
}
evd() { ls -1d "$W"/out/u14/*-"$STAMP"/backup-host 2>/dev/null | tail -1; }
cronfile; make_stamp "$W/backups/$STAMP"; receipt

runit c1.out "$PASS"; sed -e "s#$W#<W>#g" "$W/c1.out"
has c1.out "wrapper exit=0"; has c1.out "U14 A RECORDED"
hasE c1.out "complete +PASS"; hasE c1.out "remote-presence +PASS"; hasE c1.out "remote-integrity +PASS"; hasE c1.out "decrypt +PASS"
hasE c1.out "isolated-restore +NOT_RUN"
has c1.out "custody check: PASS - the recovery copy opens all 11 encrypted artifacts"
has c1.out "matches the P5b receipt (PASS, verified 2026-10-12T11:25:00Z"
has c1.out "temporary copy and raw logs deleted: yes"
has c1.out "outcome 4 uses the host's operational passphrase"
E="$(evd)"
[ -n "$E" ] && [ "$(stat -c %a "$E")" = 700 ] && [ "$(stat -c %a "$E"/* | sort -u | tr '\n' ' ')" = "600 " ] && ok "c1: evidence dir 700, files 600" || no "c1 evidence modes"
[ "$(ls "$E" | tr '\n' ' ')" = "acceptance.json acceptance.md custody-check.json local-preflight.json remote-listing.tsv run.json " ] \
  && ok "c1: the six evidence files" || no "c1 evidence files: $(ls "$E" | tr '\n' ' ')"
[ "$(find "$W/tmproot" -mindepth 1 | wc -l)" = 0 ] && ok "c1: nothing left under the temporary root" || no "c1 tmproot not empty"
python3 -I -c 'import json,sys; c=json.load(open(sys.argv[1])); r=json.load(open(sys.argv[2])); assert c["result"]=="PASS" and r["acceptanceExit"]==4 and "operational" in r["outcome4Passphrase"]' \
  "$E/custody-check.json" "$E/run.json" && ok "c1: custody-check.json and run.json" || no "c1 json"
clean c1.out
rm -rf "$W/out/u14"; sleep 1
runit c2.out ""; has c2.out "custody check: NOT_RUN - skipped by the operator"; hasE c2.out "decrypt +PASS"; has c2.out "U14 A RECORDED"; clean c2.out
rm -rf "$W/out/u14"; sleep 1
runit c3.out "$WRONG"; has c3.out "custody check: FAIL - 11 of 11 encrypted artifacts do not open with the recovery copy"
hasE c3.out "decrypt +PASS"; has c3.out "U14 A RECORDED"; clean c3.out
rm -rf "$W/out/u14"; sleep 1
make_stamp "$W/backups/$STAMP" noledger; receipt
runit c4.out ""; hasE c4.out "complete +FAIL .*erasure-ledger"; hasE c4.out "remote-presence +PASS"; hasE c4.out "remote-integrity +PASS"
hasE c4.out "decrypt +PASS"; has c4.out "U14 A RECORDED"; clean c4.out
rm -rf "$W/out/u14"; sleep 1
make_stamp "$W/backups/$STAMP"; receipt
python3 -I - "$W/bucket/$STAMP/auth.sql.gz.enc" <<'PY'
import sys
p = sys.argv[1]; d = bytearray(open(p, "rb").read()); d[-1] ^= 1; open(p, "wb").write(bytes(d))
PY
runit c5.out "$PASS"; hasE c5.out "remote-integrity +FAIL"; hasE c5.out "decrypt +NOT_RUN"
has c5.out "custody check: NOT_RUN - no verified copy (remote-integrity FAIL)"; has c5.out "U14 A RECORDED"; clean c5.out
rm -rf "$W/out/u14"; sleep 1
make_stamp "$W/backups/$STAMP"; receipt
echo "# local edit" >> "$R/scripts/lib/backup-common.sh"
runit c6.out ""; has c6.out "STOP: the release checkout"; has c6.out "local change(s)"; has c6.out "wrapper exit=1"
git -C "$R" checkout -q -- scripts/lib/backup-common.sh
U14A_SCRIPT_SHA="$(printf '0%.0s' $(seq 64))" runit c7.out ""; has c7.out "is not the reviewed"; has c7.out "wrapper exit=1"
runit c8.out "" "2026-10-01T03-30-00Z"; has c8.out "    $STAMP"; has c8.out "STOP: stamp 2026-10-01T03-30-00Z has no COMPLETE"; has c8.out "wrapper exit=1"
cronfile "45 4 * * * root $W/run-production-backup.sh"
runit c9.out ""; has c9.out "job line(s) for the backup entry point (want exactly 1)"; has c9.out "wrapper exit=1"
cronfile
[ -z "$(ls -A "$W/out/u14" 2>/dev/null)" ] && [ "$(find "$W/tmproot" -mindepth 1 | wc -l)" = 0 ] && ok "c6-c9: nothing pulled, no evidence" || no "c6-c9 left files"
a="$(sed -n "/^read -r -d '' CRON_AWK <<'AWK'$/,/^AWK$/p" "$TOOL")"; b="$(sed -n "/^read -r -d '' CRON_AWK <<'AWK'$/,/^AWK$/p" "$HERE/p5b-backup-verify.sh")"
[ -n "$a" ] && [ "$a" = "$b" ] && ok "c10: the cron reader is byte-identical to P5b's" || no "c10 cron reader differs"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
