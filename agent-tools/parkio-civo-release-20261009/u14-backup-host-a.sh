#!/usr/bin/env bash
# U14 run A on the backup host (Asana 1219001907916752): the dated five-outcome acceptance of ONE backup stamp, with the
# reviewed scripts/backup-dated-acceptance.sh of the clean release checkout (PIN f888a81b), run in the nightly backup job's own
# environment. Read-only towards production: it reads the local stamp, lists the stamp's offsite objects, pulls its encrypted
# copy into a private temporary directory under /root, test-decrypts that copy streamed (nothing decrypted is written) and
# deletes the copy. No restore (outcome 5 belongs to a disposable drill host), no backup, no env, cron or container change.
#
# Run on parkio-civo-prod as civo, with sudo:
#   sudo bash /home/civo/parkio-release-20261009/tools/u14-backup-host-a.sh <stamp as YYYY-MM-DDTHH-MM-SSZ>
#
# 0. Preconditions: root; the release checkout /opt/parkio-release at the PIN and clean; its backup-dated-acceptance.sh has the
#    reviewed SHA-256; the cron file /etc/cron.d/parkio-backup has exactly one backup job, as root, on "30 3 * * *", with the
#    approved command (the P5a/P5b cron reader, byte-identical); the backup job's environment builds with the live library
#    and env file; the offsite client is on the cron PATH; the stamp is under the backup dir with COMPLETE and SHA256SUMS;
#    free space for the temporary copy (stamp size + 2 GiB under /root).
# 1. The acceptance, in the backup job's environment (cron defaults, the cron file's variables, cd /opt/parkio,
#    PARKIO_ENV_FILE default docker/.env.azure-hosted-beta, BACKUP_PRODUCTION_MODE default 1; the caller's environment never
#    reaches it): backup-dated-acceptance.sh --stamp-dir <stamp> --decrypt. It records:
#      1 complete          the reviewed stamp preflight (full scope) on the local stamp;
#      2 remote-presence   the offsite listing equals the seal (names and sizes only);
#      3 remote-integrity  a pulled copy verifies against the seal;
#      4 decrypt           with the HOST'S OPERATIONAL passphrase (BACKUP_ENCRYPT_PASSPHRASE of the backup job's
#                          environment). PASS proves the stamp opens with the key the backup used. It does NOT prove that an
#                          independently held recovery copy of that passphrase exists;
#      5 isolated-restore  NOT_RUN here by design (a disposable drill host).
# 2. Custody check (optional): a hidden prompt on the terminal asks for the passphrase from the independently held recovery
#    copy; Enter skips it. With a value, every encrypted artifact of the same verified copy is test-decrypted, streamed. PASS
#    proves that copy opens this stamp. The value is read with `read -s` from the terminal, kept only in this process's
#    environment for openssl (`-pass env:`), never on a command line, in a file or in the output, and unset at once.
# 3. Reuse: the record's SHA-256 of SHA256SUMS is compared with the P5b receipt of the same stamp, when one exists.
# 4. Evidence: ~civo/parkio-release-20261009/u14/<run stamp>-<stamp>/backup-host/ (owner civo, dir 700, files 600):
#    acceptance.json/.md, local-preflight.json, remote-listing.tsv (object names and sizes), custody-check.json, run.json.
#    The temporary directory with the pulled ciphertext and raw logs is deleted, also on interruption.
# Exit 0 when the record was written (FAIL / NOT_RUN outcomes are findings, shown in the table); 1 when it was not.
set -uo pipefail
STAMP="${1:-}"
RELEASE="${U14A_RELEASE:-/opt/parkio-release}"
PIN="${U14A_PIN:-f888a81b113c0c486ea0949067779443857e644d}"
SCRIPT_SHA="${U14A_SCRIPT_SHA:-3014cc2a7820e0cb5f51ba2b59fbeb2eeb97ef777ebd627761c4a884a0b1b899}"
LIVE="${U14A_LIVE:-/opt/parkio}"
ENVF_DEFAULT="${U14A_ENV_FILE:-docker/.env.azure-hosted-beta}"   # the entry point's default, relative to the live checkout
OUT="${U14A_OUT:-/home/civo/parkio-release-20261009}"
OWNER="${U14A_OWNER:-civo}"
TMPROOT="${U14A_TMPROOT:-/root}"
CRONF="${U14A_CRON_FILE:-/etc/cron.d/parkio-backup}"
LOCK="${U14A_LOCK:-/var/lock/parkio-backup.lock}"
ENTRY="${U14A_ENTRY:-/opt/parkio/scripts/run-production-backup.sh}"
LOG="${U14A_LOG:-/var/log/parkio-backup.log}"
ENV_HOME="${U14A_HOME:-/root}"        # cron's HOME and LOGNAME for a root job; only the self-test changes them
ENV_USER="${U14A_LOGNAME:-root}"
CRON_PATH="${U14A_CRON_PATH:-/usr/bin:/bin}"
MARGIN="${U14A_MARGIN:-2147483648}"
TTY="${U14A_TTY:-/dev/tty}"
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'
APPROVED_SCHEDULE='30 3 * * *'
RUNSTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
stop() { echo "STOP: $*"; echo "U14 A NOT RECORDED - nothing was pulled or changed"; exit 1; }

# The cron file reader, byte-identical to P5a's and P5b's: mode=inspect prints facts and variable names only; mode=env prints
# NAME=VALUE lines for the variables that precede the first backup job (used only inside the environment helper, never shown).
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

# The environment helper: builds the backup job's environment as P5b does, then either runs the reviewed acceptance script
# of the release checkout in it (accept; that script loads its own library and the env file) or prints facts with the live
# library and env file loaded as the nightly job loads them (facts: key=value lines, no secret values).
read -r -d '' CRONENV <<'RUN'
set -uo pipefail
cronf=$1; prog=$2; lockp=$3; entryp=$4; logp=$5; live=$6; envdef=$7; ln=$8; release=$9; action=${10}; shift 10
assign="$(awk -v mode=env -v lockp="$lockp" -v entryp="$entryp" -v logp="$logp" "$prog" "$cronf")" \
  || { echo "U14A-ENV: cannot read the cron file" >&2; exit 70; }
while IFS= read -r kv; do
  [ -n "$kv" ] || continue
  case "${kv%%=*}" in LOGNAME|PARKIO_BACKUP_FIRE_MARKER) continue ;; esac
  export "$kv" || { echo "U14A-ENV: cannot apply a cron variable" >&2; exit 70; }
done <<< "$assign"
export LOGNAME="$ln"
umask 077
cd "$live" || { echo "U14A-ENV: cannot enter the live checkout" >&2; exit 71; }
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-$envdef}"
export BACKUP_PRODUCTION_MODE="${BACKUP_PRODUCTION_MODE:-1}"
[ -f "$PARKIO_ENV_FILE" ] || { echo "U14A-ENV: the env file is missing" >&2; exit 73; }
if [ "$action" = accept ]; then
  exec bash "$release/scripts/backup-dated-acceptance.sh" --env-file "$PARKIO_ENV_FILE" "$@"
fi
# shellcheck disable=SC1091
. scripts/lib/backup-common.sh || { echo "U14A-ENV: cannot load the backup library" >&2; exit 72; }
parkio_backup_load_env "$PARKIO_ENV_FILE" 2>/dev/null
case "$action" in
  facts)
    bdir="${BACKUP_DIR:-./backups}"; case "$bdir" in /*) ;; *) bdir="$live/${bdir#./}" ;; esac
    echo "backup_dir=$bdir"
    echo "profile=${PARKIO_DEPLOYMENT_PROFILE:-hosted-beta}"
    echo "offsite_kind=$(parkio_backup_offsite_kind)"
    if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then echo "encryption=set"; else echo "encryption=unset"; fi
    if command -v az >/dev/null 2>&1; then echo "az=found"; else echo "az=missing"; fi
    if command -v mc >/dev/null 2>&1 || [ -n "${BACKUP_MC_URL:-}" ]; then echo "mc=found"; else echo "mc=missing"; fi
    for t in jq python3 openssl gzip tar sha256sum; do command -v "$t" >/dev/null 2>&1 || echo "missing_tool=$t"; done
    ;;
  *) exit 64 ;;
esac
RUN
cronenv() {
  env -i SHELL=/bin/sh PATH="$CRON_PATH" HOME="$ENV_HOME" LOGNAME="$ENV_USER" \
    /bin/bash -c "$CRONENV" u14a-cronenv "$CRONF" "$CRON_AWK" "$LOCK" "$ENTRY" "$LOG" "$LIVE" "$ENVF_DEFAULT" "$ENV_USER" \
    "$RELEASE" "$@"
}
RUN_DIR=""
cleanup() {
  if [ -n "$RUN_DIR" ] && [ -d "$RUN_DIR" ]; then rm -rf "${RUN_DIR:?}"; fi
  unset U14A_CUSTODY
}
trap cleanup EXIT
trap 'cleanup; echo "STOP: interrupted - the temporary copy was deleted"; exit 130' INT TERM

echo "U14 run A (backup host) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | stamp ${STAMP:-?} | read-only, no restore"
[ "$(id -u)" = 0 ] || [ -n "${U14A_TEST:-}" ] || stop "run as root (sudo bash ...)"
printf '%s\n' "$STAMP" | grep -q -E "$STAMP_RE" || stop "usage: u14-backup-host-a.sh <stamp as YYYY-MM-DDTHH-MM-SSZ>"

echo "== 0. preconditions"
head="$(git -c safe.directory="$RELEASE" --no-optional-locks -C "$RELEASE" rev-parse HEAD 2>/dev/null)"
dirty="$(git -c safe.directory="$RELEASE" --no-optional-locks -C "$RELEASE" status --porcelain 2>/dev/null | wc -l)"
[ "$head" = "$PIN" ] || stop "the release checkout $RELEASE is at ${head:-?}, not the PIN $PIN"
[ "$dirty" -eq 0 ] || stop "the release checkout $RELEASE has $dirty local change(s)"
have_sha="$(sha256sum "$RELEASE/scripts/backup-dated-acceptance.sh" 2>/dev/null | cut -d' ' -f1)"
[ "$have_sha" = "$SCRIPT_SHA" ] || stop "backup-dated-acceptance.sh sha256 ${have_sha:-missing} is not the reviewed $SCRIPT_SHA"
echo "  release checkout $RELEASE at the PIN ${PIN:0:12}, clean; backup-dated-acceptance.sh sha256 ${SCRIPT_SHA:0:16}"
facts="$(awk -v mode=inspect -v lockp="$LOCK" -v entryp="$ENTRY" -v logp="$LOG" "$CRON_AWK" "$CRONF" 2>/dev/null)" \
  || stop "cannot read the cron file $CRONF"
fact() { printf '%s\n' "$facts" | sed -n "s/^$1 //p" | head -1; }
[ "$(fact jobs)" = 1 ] || stop "the cron file has $(fact jobs) job line(s) for the backup entry point (want exactly 1)"
[ "$(fact unknown)" = 0 ] || stop "the cron file has $(fact unknown) line(s) that are neither comments, variables nor jobs"
[ "$(fact schedule)" = "$APPROVED_SCHEDULE" ] || stop "the cron schedule is '$(fact schedule)', not the approved '$APPROVED_SCHEDULE'"
[ "$(fact user)" = root ] || stop "the cron job runs as '$(fact user)', not root"
[ "$(fact command)" = approved ] || stop "the cron command differs from the approved form"
vars="$(printf '%s\n' "$facts" | sed -n 's/^var //p' | grep -v -x -E 'LOGNAME|PARKIO_BACKUP_FIRE_MARKER' | tr '\n' ' ' | sed 's/ $//')"
envfacts="$(cronenv facts 2>/dev/null)"; erc=$?
[ "$erc" -eq 0 ] || stop "cannot build the backup job's environment (exit $erc: 70 cron file, 71 checkout, 72 library, 73 env file)"
get() { printf '%s\n' "$envfacts" | sed -n "s/^$1=//p" | head -1; }
BDIR="$(get backup_dir)"; KIND="$(get offsite_kind)"
missing_tools="$(printf '%s\n' "$envfacts" | sed -n 's/^missing_tool=//p' | tr '\n' ' ')"
[ -z "$missing_tools" ] || stop "missing on the cron PATH: $missing_tools"
case "$KIND" in
  azure) [ "$(get az)" = found ] || stop "offsite kind azure, but az is not on the cron job's PATH" ;;
  s3) [ "$(get mc)" = found ] || stop "offsite kind s3, but mc is not on the cron job's PATH and BACKUP_MC_URL is unset" ;;
  *) stop "no offsite configured (kind $KIND)" ;;
esac
echo "  backup job: '$(fact schedule)' as root, approved command; cron variables applied: ${vars:-none}"
echo "  deployment profile $(get profile); offsite kind $KIND; operational passphrase $(get encryption) (value not shown)"
DEST="$BDIR/$STAMP"
if [ ! -f "$DEST/COMPLETE" ] || [ ! -f "$DEST/SHA256SUMS" ]; then
  echo "  COMPLETE stamps under the backup dir (names only):"
  find "$BDIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort | while read -r s; do
    [ -f "$BDIR/$s/COMPLETE" ] && echo "    $s"
  done
  stop "stamp $STAMP has no COMPLETE and SHA256SUMS under the backup dir"
fi
bytes="$(du -sb "$DEST" | cut -f1)"
avail="$(df -P -B1 "$TMPROOT" 2>/dev/null | awk 'NR == 2 { print $4 }')"
[ -n "$avail" ] && [ "$avail" -ge $((bytes + MARGIN)) ] \
  || stop "not enough free space under $TMPROOT for the temporary copy (need $((bytes + MARGIN)) bytes, have ${avail:-?})"
newer="$(find "$BDIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | grep -E "$STAMP_RE" | LC_ALL=C sort | awk -v s="$STAMP" '$0 > s' | wc -l)"
echo "  stamp $STAMP: $bytes bytes; newer local stamps: $newer; free under $TMPROOT $((avail / 1048576)) MiB"

echo ""
echo "== 1. backup-dated-acceptance.sh --stamp-dir <stamp> --decrypt (the backup job's environment)"
umask 077
RUN_DIR="$(mktemp -d "$TMPROOT/parkio-u14a.XXXXXX")" || stop "mktemp failed"
chmod 700 "$RUN_DIR"
cronenv accept --stamp-dir "$DEST" --evidence "$RUN_DIR/evidence" --work "$RUN_DIR/work" --decrypt \
  > "$RUN_DIR/accept.log" 2>&1
arc=$?
REPORT="$RUN_DIR/evidence/acceptance.json"
[ -s "$REPORT" ] || { echo "  the acceptance wrote no record (exit $arc); its log stays private"; stop "the acceptance did not complete"; }
python3 -I - "$REPORT" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
print(f"  stamp {r.get('stamp')} | localSha256sums {r.get('localSha256sums')} | offsite {r.get('offsiteKind') or '-'} | verdict {r.get('verdict')}")
for o in r.get("outcomes", []):
    print(f"  {o['id']:17} {o['result']:8} {o.get('detail', '')[:220]}")
PY
echo "  (acceptance exit $arc: 4 = no FAIL, but outcome 5 is NOT_RUN here by design; 1 = at least one FAIL)"
echo "  outcome 4 uses the host's operational passphrase: it proves the stamp opens with the key the backup used,"
echo "  not that an independently held recovery copy of that passphrase exists (see the custody check)."

echo ""
echo "== 2. custody check (independently held recovery copy of the passphrase; optional)"
custody=NOT_RUN; custody_detail="skipped by the operator"
integrity="$(python3 -I -c 'import json,sys; print({o["id"]: o["result"] for o in json.load(open(sys.argv[1]))["outcomes"]}.get("remote-integrity", ""))' "$REPORT" 2>/dev/null)"
COPY="$RUN_DIR/work/pulled"
if [ "$integrity" != PASS ] || [ ! -d "$COPY" ]; then
  custody_detail="no verified copy (remote-integrity $integrity)"
elif ! { : < "$TTY"; } 2>/dev/null; then
  custody_detail="no terminal for the hidden prompt"
else
  printf '  Type the passphrase from the independently held recovery copy, or press Enter to skip.\n  It is not echoed, not stored and not printed.\n  Recovery passphrase: ' > "$TTY"
  IFS= read -r -s U14A_CUSTODY < "$TTY" || U14A_CUSTODY=""
  printf '\n' > "$TTY"
  if [ -n "$U14A_CUSTODY" ]; then
    export U14A_CUSTODY
    opened=0; failed=0
    shopt -s nullglob
    for artifact in "$COPY"/*.sql.gz.enc; do
      if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:U14A_CUSTODY -in "$artifact" 2>/dev/null | gzip -t 2>/dev/null; then
        opened=$((opened + 1)); else failed=$((failed + 1)); fi
    done
    shopt -u nullglob
    if [ -f "$COPY/minio.tar.gz.enc" ]; then
      if openssl enc -d -aes-256-cbc -pbkdf2 -pass env:U14A_CUSTODY -in "$COPY/minio.tar.gz.enc" 2>/dev/null | tar -tzf - >/dev/null 2>&1; then
        opened=$((opened + 1)); else failed=$((failed + 1)); fi
    fi
    unset U14A_CUSTODY
    if [ "$failed" -eq 0 ] && [ "$opened" -gt 0 ]; then
      custody=PASS; custody_detail="the recovery copy opens all $opened encrypted artifacts of this stamp (streamed, nothing written)"
    else
      custody=FAIL; custody_detail="$failed of $((opened + failed)) encrypted artifacts do not open with the recovery copy"
    fi
  fi
fi
unset U14A_CUSTODY
echo "  custody check: $custody - $custody_detail"

echo ""
echo "== 3. reuse of the release's P5b evidence"
local_sums="$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("localSha256sums") or "")' "$REPORT" 2>/dev/null)"
P5B="$OUT/backup/P5b-receipt-$STAMP.json"
if [ -r "$P5B" ]; then
  reuse="$(python3 -I - "$P5B" "$local_sums" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
same = r.get("sha256sumsSha256") == sys.argv[2]
print(("matches" if same else "DIFFERS from") + f" the P5b receipt ({r.get('result')}, verified {r.get('verifiedAt')}, offsite identical {r.get('offsiteIdentical')})")
PY
)"
else
  reuse="no P5b receipt for this stamp"
fi
echo "  SHA-256 of SHA256SUMS $local_sums: $reuse"

echo ""
EVD="$OUT/u14/$RUNSTAMP-$STAMP/backup-host"
mkdir -p "$EVD" || stop "cannot create $EVD"
for f in acceptance.json acceptance.md local-preflight.json remote-listing.tsv; do
  [ -f "$RUN_DIR/evidence/$f" ] && cp "$RUN_DIR/evidence/$f" "$EVD/$f"
done
python3 -I - "$EVD/custody-check.json" "$custody" "$custody_detail" <<'PY'
import json, sys
json.dump({"check": "custody", "result": sys.argv[2], "detail": sys.argv[3],
           "meaning": "PASS: the independently held recovery copy of the passphrase opens every encrypted artifact of this stamp"},
          open(sys.argv[1], "w"), indent=2)
PY
python3 -I - "$EVD/run.json" "$RUNSTAMP" "$STAMP" "$PIN" "$SCRIPT_SHA" "$KIND" "$vars" "$arc" "$reuse" <<'PY'
import json, sys
a = sys.argv[2:]
json.dump({"tool": "u14-backup-host-a", "runStamp": a[0], "stamp": a[1], "releasePin": a[2], "acceptanceScriptSha256": a[3],
           "offsiteKind": a[4], "cronVariables": a[5], "acceptanceExit": int(a[6]), "p5bReuse": a[7],
           "environment": "backup cron job (cron defaults, cron file variables, entry point defaults, env file)",
           "outcome4Passphrase": "host operational passphrase (BACKUP_ENCRYPT_PASSPHRASE of the backup job's environment)"},
          open(sys.argv[1], "w"), indent=2)
PY
chown -R "$OWNER:$OWNER" "$OUT/u14" 2>/dev/null
chmod 700 "$OUT/u14" "$OUT/u14/$RUNSTAMP-$STAMP" "$EVD"; chmod 600 "$EVD"/*
cleanup
RUN_DIR=""
left="$(find "$TMPROOT" -maxdepth 1 -name 'parkio-u14a.*' 2>/dev/null | wc -l)"
echo "evidence: $EVD/ ($(ls "$EVD" | tr '\n' ' '))"
echo "acceptance.json sha256 $(sha256sum "$EVD/acceptance.json" | cut -d' ' -f1)"
echo "temporary copy and raw logs deleted: $([ "$left" -eq 0 ] && echo yes || echo "NO ($left left)")"
echo "U14 A RECORDED"
exit 0
