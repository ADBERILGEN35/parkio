#!/usr/bin/env bash
# U14 G1 passphrase custody (Asana 1219001907916752): an off-host recovery copy ("escrow") of the backup encryption
# passphrase, made and checked without the passphrase ever being shown, typed, stored or sent. The operational passphrase is
# not changed.
#
# Run on parkio-civo-prod as civo, with sudo, in an interactive SSH terminal:
#   sudo bash /home/civo/parkio-release-20261009/tools/u14-custody-escrow.sh create   # make the escrow, then check the saved copy
#   sudo bash /home/civo/parkio-release-20261009/tools/u14-custody-escrow.sh verify   # check a saved copy again later
#
# The escrow has two parts. Both are needed to recover the passphrase; neither is enough alone.
#   - The escrow password: new, chosen by the owner, at least 20 characters (English letters, digits and spaces are safest,
#     because it is typed on other machines later). It is written by hand on two cards kept in two places, and typed only at
#     the hidden prompts of this tool or of a recovery.
#   - The escrow file: the backup passphrase encrypted with that password (openssl enc AES-256-CBC, PBKDF2-SHA256, 600000
#     iterations, random salt), printed as text between BEGIN and END lines. It is ciphertext. The owner keeps two copies off
#     this host, for example a file on the owner's computer and a copy on a USB stick or in the owner's own cloud drive.
# create:
#   0. Preconditions: root; the cron file has exactly one backup job, as root, on the approved schedule and command (the
#      P5a/P5b cron reader, byte-identical); the backup job's environment builds with the live library and env file, as the
#      nightly job builds it (the run-A helper's construction), and its BACKUP_ENCRYPT_PASSPHRASE is set; a terminal exists.
#   1. Hidden prompts ask for the new escrow password twice. It must have at least 20 characters and must not be the backup
#      passphrase itself.
#   2. Inside the backup job's environment, its passphrase is encrypted with that password. The result is decrypted again at
#      once and compared with the passphrase (round trip).
#   3. The escrow file is printed; the owner saves it twice, off this host.
#   4. Check of the saved copy: the owner pastes the escrow file from the saved copy and types the password from a card. The
#      decrypted text must equal the passphrase of the backup job's environment (MATCH).
# verify: step 4 alone, against the current passphrase (a later check; after a passphrase change it reports MISMATCH).
# Nothing is written on this host except a secret-free record under ~civo/parkio-release-20261009/u14/custody-<run>-<mode>/
# (dir 700, file 600): the time, the format, the SHA-256 of the escrow blob and the results. The passphrase, the escrow
# password and the blob are not stored, logged or put on a command line (bash builtins and openssl -pass env: only). The
# comparisons use SHA-256 digests held in memory and never shown.
# Exit 0 when custody is established (create) or confirmed (verify); 1 otherwise.
set -uo pipefail
MODE="${1:-}"
LIVE="${U14C_LIVE:-/opt/parkio}"
ENVF_DEFAULT="${U14C_ENV_FILE:-docker/.env.azure-hosted-beta}"   # the entry point's default, relative to the live checkout
OUT="${U14C_OUT:-/home/civo/parkio-release-20261009}"
OWNER="${U14C_OWNER:-civo}"
CRONF="${U14C_CRON_FILE:-/etc/cron.d/parkio-backup}"
LOCK="${U14C_LOCK:-/var/lock/parkio-backup.lock}"
ENTRY="${U14C_ENTRY:-/opt/parkio/scripts/run-production-backup.sh}"
LOG="${U14C_LOG:-/var/log/parkio-backup.log}"
ENV_HOME="${U14C_HOME:-/root}"        # cron's HOME and LOGNAME for a root job; only the self-test changes them
ENV_USER="${U14C_LOGNAME:-root}"
CRON_PATH="${U14C_CRON_PATH:-/usr/bin:/bin}"
TTY="${U14C_TTY:-/dev/tty}"
FORMAT="parkio-escrow-v1"
ITER=600000
MINLEN=20
APPROVED_SCHEDULE='30 3 * * *'
RUNSTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
stop() { echo "STOP: $*"; echo "U14 CUSTODY NOT RECORDED - nothing was changed"; exit 1; }

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

# The environment helper: builds the backup job's environment as the run-A helper does (cron defaults, the cron file's
# variables, cd to the live checkout, PARKIO_ENV_FILE and BACKUP_PRODUCTION_MODE defaults, the live library's
# parkio_backup_load_env), then runs one action in it. The passphrase exists only in this process. Its stdout carries
# key=value results without secret values; prompts and the escrow file go to the terminal only.
read -r -d '' CRONENV <<'RUN'
set -uo pipefail
cronf=$1; prog=$2; lockp=$3; entryp=$4; logp=$5; live=$6; envdef=$7; ln=$8; action=$9; shift 9
assign="$(awk -v mode=env -v lockp="$lockp" -v entryp="$entryp" -v logp="$logp" "$prog" "$cronf")" \
  || { echo "U14C-ENV: cannot read the cron file" >&2; exit 70; }
while IFS= read -r kv; do
  [ -n "$kv" ] || continue
  case "${kv%%=*}" in LOGNAME|PARKIO_BACKUP_FIRE_MARKER) continue ;; esac
  export "$kv" || { echo "U14C-ENV: cannot apply a cron variable" >&2; exit 70; }
done <<< "$assign"
export LOGNAME="$ln"
umask 077
cd "$live" || { echo "U14C-ENV: cannot enter the live checkout" >&2; exit 71; }
export PARKIO_ENV_FILE="${PARKIO_ENV_FILE:-$envdef}"
export BACKUP_PRODUCTION_MODE="${BACKUP_PRODUCTION_MODE:-1}"
[ -f "$PARKIO_ENV_FILE" ] || { echo "U14C-ENV: the env file is missing" >&2; exit 73; }
# shellcheck disable=SC1091
. scripts/lib/backup-common.sh || { echo "U14C-ENV: cannot load the backup library" >&2; exit 72; }
parkio_backup_load_env "$PARKIO_ENV_FILE" 2>/dev/null
if [ "$action" = facts ]; then
  if [ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ]; then echo "encryption=set"; else echo "encryption=unset"; fi
  for t in openssl sha256sum fold tr cut hostname; do command -v "$t" >/dev/null 2>&1 || echo "missing_tool=$t"; done
  exit 0
fi
case "$action" in create|verify) ;; *) exit 64 ;; esac
fmt=$1; iter=$2; minlen=$3; tty=$4
[ -n "${BACKUP_ENCRYPT_PASSPHRASE:-}" ] || { echo "result=no-passphrase"; exit 0; }
{ exec 3<"$tty" 4>"$tty"; } 2>/dev/null || { echo "result=no-terminal"; exit 0; }
say() { printf '%s\n' "$*" >&4; }
want="$(printf '%s\n%s' "$fmt" "$BACKUP_ENCRYPT_PASSPHRASE" | sha256sum | cut -d' ' -f1)"
opened() {  # base64 (64-character lines) on stdin, password in U14C_PW -> SHA-256 of the decrypted text
  openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 -iter "$iter" -a -pass env:U14C_PW 2>/dev/null | sha256sum | cut -d' ' -f1
}
bsha=""
if [ "$action" = create ]; then
  say ""
  say "  Step 1 of 3: the escrow password."
  say "  Type a NEW password of at least $minlen characters that you have already written by hand on two cards, for example"
  say "  five unrelated English words. It is not shown while you type."
  ok=0; pw=""; pw2=""
  for try in 1 2 3; do
    printf '  Escrow password: ' >&4; IFS= read -r -s pw <&3 || break; printf '\n' >&4
    printf '  The same again: ' >&4; IFS= read -r -s pw2 <&3 || break; printf '\n' >&4
    if [ "$pw" != "$pw2" ]; then say "  The two entries differ; try again."
    elif [ "${#pw}" -lt "$minlen" ]; then say "  It has fewer than $minlen characters; try again."
    elif [ "$pw" = "$BACKUP_ENCRYPT_PASSPHRASE" ]; then say "  That is the backup passphrase itself; choose a new password and try again."
    else ok=1; break; fi
  done
  pw2=""
  if [ "$ok" != 1 ]; then pw=""; echo "result=no-password"; exit 0; fi
  blob="$(printf '%s\n%s' "$fmt" "$BACKUP_ENCRYPT_PASSPHRASE" \
    | U14C_PW="$pw" openssl enc -e -aes-256-cbc -pbkdf2 -md sha256 -iter "$iter" -salt -a -A -pass env:U14C_PW 2>/dev/null \
    | tr -d ' \r\n\t')"
  [ -n "$blob" ] || { pw=""; echo "result=encrypt-failed"; exit 0; }
  got="$(printf '%s\n' "$blob" | fold -w 64 | U14C_PW="$pw" opened)"
  pw=""
  [ "$got" = "$want" ] || { echo "result=roundtrip-failed"; exit 0; }
  echo "roundtrip=PASS"
  bsha="$(printf '%s' "$blob" | sha256sum | cut -d' ' -f1)"
  created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "blob_sha256=$bsha"; echo "created=$created"
  say ""
  say "  Step 2 of 3: save the escrow file. Copy ALL lines from the BEGIN line to the END line into a new text file on your"
  say "  computer (for example parkio-backup-escrow-${created%%T*}.txt), and keep a second copy in another place you control"
  say "  (a USB stick, or your own cloud drive or e-mail). It is encrypted; without the password on your cards it is useless."
  say ""
  say "-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----"
  say "format: $fmt (line 1 of the decrypted text is the format; line 2 is the passphrase)"
  say "protects: BACKUP_ENCRYPT_PASSPHRASE of the nightly backup on $(hostname)"
  say "created: $created"
  say "cipher: openssl enc -aes-256-cbc -pbkdf2 -md sha256 -iter $iter -a"
  say "blob-sha256: $bsha"
  say "blob:"
  printf '%s\n' "$blob" | fold -w 64 >&4
  say "-----END PARKIO BACKUP PASSPHRASE ESCROW-----"
  say ""
  printf '  Press Enter when BOTH copies are saved: ' >&4; IFS= read -r _ <&3
  say ""
  say "  Step 3 of 3: check the SAVED copy (not the text above)."
fi
say "  Open the saved file, copy everything from the BEGIN line to the END line, paste it here and press Enter."
res=MISMATCH; psha=""; try=0; esc=$'\e'
while [ "$try" -lt 3 ]; do
  try=$((try + 1)); pb=""; inblob=0; ended=0; n=0
  while [ "$n" -lt 60 ] && IFS= read -r -t 900 line <&3; do
    n=$((n + 1)); line="${line//${esc}\[200~/}"; line="${line//${esc}\[201~/}"; line="${line//[$' \t\r']/}"
    case "$line" in
      -----END*) ended=1; break ;;
      blob:) inblob=1 ;;
      blob:*) inblob=1; pb="${pb}${line#blob:}" ;;
      *) [ "$inblob" = 1 ] && pb="${pb}${line}" ;;
    esac
  done
  if [ "$ended" != 1 ] || [ -z "$pb" ]; then
    say "  That paste had no 'blob:' part before the END line (or no END line). Paste the whole saved text again."
    continue
  fi
  printf '  Escrow password from your card (not shown): ' >&4; IFS= read -r -s pw <&3 || break; printf '\n' >&4
  psha="$(printf '%s' "$pb" | sha256sum | cut -d' ' -f1)"
  got="$(printf '%s\n' "$pb" | fold -w 64 | U14C_PW="$pw" opened)"
  pw=""
  if [ "$got" = "$want" ]; then res=MATCH; break; fi
  if [ -n "$bsha" ] && [ "$psha" != "$bsha" ]; then
    say "  No match: the pasted text is not the escrow file printed above (an incomplete copy?). Try again."
  else
    say "  No match: a wrong password, a damaged copy, or an escrow of another passphrase. Try again."
  fi
done
pb=""; pw=""
echo "saved_copy=$res"; echo "saved_blob_sha256=$psha"; echo "attempts=$try"
if [ -n "$bsha" ]; then
  if [ "$psha" = "$bsha" ]; then echo "saved_is_created=yes"; else echo "saved_is_created=no"; fi
fi
echo "result=done"
RUN
cronenv() {
  env -i SHELL=/bin/sh PATH="$CRON_PATH" HOME="$ENV_HOME" LOGNAME="$ENV_USER" \
    /bin/bash -c "$CRONENV" u14c-cronenv "$CRONF" "$CRON_AWK" "$LOCK" "$ENTRY" "$LOG" "$LIVE" "$ENVF_DEFAULT" "$ENV_USER" "$@"
}

case "$MODE" in create|verify) ;; *) echo "usage: u14-custody-escrow.sh create|verify"; exit 1 ;; esac
echo "U14 passphrase custody ($MODE) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | the passphrase is never shown, typed or stored"
[ "$(id -u)" = 0 ] || [ -n "${U14C_TEST:-}" ] || stop "run as root (sudo bash ...)"

echo "== 0. preconditions"
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
missing_tools="$(printf '%s\n' "$envfacts" | sed -n 's/^missing_tool=//p' | tr '\n' ' ')"
[ -z "$missing_tools" ] || stop "missing on the cron PATH: $missing_tools"
[ "$(printf '%s\n' "$envfacts" | sed -n 's/^encryption=//p')" = set ] \
  || stop "the backup job's environment has no BACKUP_ENCRYPT_PASSPHRASE"
{ : < "$TTY"; } 2>/dev/null || stop "no terminal for the prompts (run it in an interactive SSH session)"
echo "  backup job: '$(fact schedule)' as root, approved command; cron variables applied: ${vars:-none}"
echo "  BACKUP_ENCRYPT_PASSPHRASE of the backup job's environment: set (value not shown, not changed)"

echo ""
echo "== 1. escrow ($MODE)"
res="$(cronenv "$MODE" "$FORMAT" "$ITER" "$MINLEN" "$TTY")"; rc=$?
get() { printf '%s\n' "$res" | sed -n "s/^$1=//p" | head -1; }
result="$(get result)"
case "$result" in
  done) ;;
  no-passphrase) stop "the backup job's environment has no BACKUP_ENCRYPT_PASSPHRASE" ;;
  no-terminal) stop "no terminal for the prompts" ;;
  no-password) stop "no escrow password was accepted (three tries)" ;;
  encrypt-failed) stop "openssl could not encrypt" ;;
  roundtrip-failed) stop "the escrow did not decrypt back to the passphrase (round trip); nothing to save" ;;
  *) stop "the escrow step ended without a result (exit $rc)" ;;
esac
saved="$(get saved_copy)"; bsha="$(get blob_sha256)"; psha="$(get saved_blob_sha256)"; same="$(get saved_is_created)"
if [ "$MODE" = create ]; then
  echo "  escrow created $(get created); round trip $(get roundtrip); blob sha256 $bsha"
fi
echo "  saved copy: $saved after $(get attempts) attempt(s); pasted blob sha256 ${psha:-none}${same:+; it is the blob created now: $same}"
verdict=FAIL
[ "$saved" = MATCH ] && verdict=PASS

EVD="$OUT/u14/custody-$RUNSTAMP-$MODE"
mkdir -p "$EVD" || stop "cannot create $EVD"
python3 -I - "$EVD/escrow.json" "$MODE" "$RUNSTAMP" "$(hostname)" "$FORMAT" "$ITER" "$(get created)" "$bsha" \
  "$(get roundtrip)" "$saved" "$psha" "$same" "$(get attempts)" "$verdict" "$vars" <<'PY'
import json, sys
a = sys.argv[2:]
rec = {"tool": "u14-custody-escrow", "mode": a[0], "runStamp": a[1], "host": a[2], "format": a[3],
       "cipher": "openssl enc aes-256-cbc", "kdf": "pbkdf2-sha256", "iterations": int(a[4]),
       "source": "BACKUP_ENCRYPT_PASSPHRASE of the nightly backup job's environment (cron file variables, live checkout, "
                 "env file loaded by the live backup library); not changed",
       "cronVariables": a[13], "savedCopy": a[8], "savedBlobSha256": a[9] or None, "attempts": int(a[11] or 0),
       "result": a[12],
       "meaning": "PASS: a copy of the escrow file saved off this host, opened with the escrow password typed from the owner's "
                  "card, yields exactly the passphrase the nightly backup uses",
       "notStored": "the passphrase, the escrow password and the escrow blob are not stored on this host"}
if a[0] == "create":
    rec.update({"created": a[5], "blobSha256": a[6], "roundTrip": a[7], "savedIsCreated": a[10] == "yes"})
json.dump(rec, open(sys.argv[1], "w"), indent=2)
PY
chown -R "$OWNER:$OWNER" "$OUT/u14" 2>/dev/null
chmod 700 "$OUT/u14" "$EVD"; chmod 600 "$EVD/escrow.json"
echo "evidence: $EVD/escrow.json sha256 $(sha256sum "$EVD/escrow.json" | cut -d' ' -f1)"
if [ "$verdict" = PASS ]; then
  if [ "$MODE" = create ]; then echo "U14 CUSTODY ESTABLISHED - the saved escrow copy opens to the backup passphrase"
  else echo "U14 CUSTODY CONFIRMED - the saved escrow copy opens to the backup passphrase"; fi
  exit 0
fi
echo "U14 CUSTODY NOT ESTABLISHED - the saved copy did not open to the backup passphrase (see above)"
exit 1
