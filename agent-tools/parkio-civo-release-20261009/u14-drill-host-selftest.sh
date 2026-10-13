#!/usr/bin/env bash
# Self-test of u14-drill-host.sh. Host commands (apt, gpg, cryptsetup, mount, docker, git, az through the pull) are fakes on
# PATH. The run phase drives the REAL backup-dated-acceptance.sh and the REAL restore-drill-isolation-preflight.py from the
# release PIN f888a81b (read from this repository), on a synthetic encrypted stamp sealed like a real backup;
# restore-drill-01.sh is a stub that runs the real isolation preflight, as scripts/test-backup-dated-acceptance.sh does.
# The escrow is made exactly as u14-custody-escrow.sh makes it. No real backup, escrow, SAS or host is used.
# usage: bash u14-drill-host-selftest.sh   (from a checkout of the repository: it reads f888a81b)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOL="$HERE/u14-drill-host.sh"
PIN=f888a81b113c0c486ea0949067779443857e644d
W="$(mktemp -d "${TMPDIR:-/tmp}/u14h-selftest.XXXXXX")"
trap 'rm -rf "$W"' EXIT
pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   $*"; }
no() { fail=$((fail + 1)); echo "FAIL $*"; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; sed 's/^/     | /' "$W/$1" | tail -8; fi; }
hasnt() {  # the needle may be a (synthetic) secret: it is never printed
  if grep -qF -- "$2" "$W/$1"; then no "$1 holds a ${3:-secret} value"; else ok "$1 holds no ${3:-secret} value"; fi
}

git -C "$HERE" cat-file -e "$PIN^{commit}" 2>/dev/null || { echo "SELFTEST FAIL: needs the repository at $PIN"; exit 1; }
mkdir -p "$W/bin" "$W/etc/apt/sources.list.d" "$W/dev/mapper" "$W/repo/scripts/lib" "$W/root" "$W/state"
export FAKE_PASSPHRASE="synthetic-backup-passphrase-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export FAKE_SAS="sv=2024-11-04&sr=c&sp=rl&se=2026-10-13T12:00:00Z&sip=10.0.0.4&spr=https&sig=SyntheticSig$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export FAKE_LOG="$W/fake.log" FAKE_STATE="$W/state" FAKE_STAMPS="$W/stamps-src"
ESCROW_PW="synthetic escrow password words five"
DATA=2026-10-12T11-18-41Z; LEDGER=2026-10-14T03-30-00Z

# --- fakes ------------------------------------------------------------------------------------------------------------
fake() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$W/bin/$1"; chmod +x "$W/bin/$1"; }
fake hostname 'echo "${FAKE_HOST:-parkio-rd-20261013}"'
fake apt-get 'echo "apt-get $*" >> "$FAKE_LOG"'
fake dpkg-query 'printf "docker.io=29.1.3 cryptsetup=2:2.7.0 jq=1.7.1 azure-cli=2.91.0-1~noble python3=3.12.3 openssl=3.0.13 git=1:2.43.0 "'
fake curl 'case "$*" in
  *packages.microsoft.com/keys*) echo "FAKE-MICROSOFT-KEY" ;;
  *blob.core.windows.net*) [ "${FAKE_EGRESS:-closed}" = open ] || [ "${FAKE_EGRESS:-closed}" = storage ] ;;
  *github.com*) [ "${FAKE_EGRESS:-closed}" = open ] ;;
  *) exit 7 ;;
esac'
fake gpg 'case "$1" in
  --dearmor) cat ;;
  --show-keys) echo "pub:-:4096:1:EB3E94ADBE1229CF:::::::scESC:"; echo "fpr:::::::::${FAKE_FPR:-BC528686B50D79E339D3721CEB3E94ADBE1229CF}:"
    if [ -n "${FAKE_EXTRA_KEY:-}" ]; then echo "pub:-:4096:1:1111111111111111:::::::scESC:"; echo "fpr:::::::::$FAKE_EXTRA_KEY:"; fi ;;
esac'
fake fallocate 'touch "${@: -1}"'
fake losetup 'case "$1" in --find) echo /dev/loop9 ;; -d) echo "losetup -d $2" >> "$FAKE_LOG" ;; esac'
fake cryptsetup 'case "$1" in
  open) n="$(wc -c | tr -d " ")"; echo "cryptsetup open keylen=$n args=$*" >> "$FAKE_LOG"; touch "$U14H_DEVMAPPER/${@: -1}" ;;
  close) rm -f "$U14H_DEVMAPPER/$2"; echo "cryptsetup close $2" >> "$FAKE_LOG" ;;
esac'
fake mkfs.ext4 'true'
fake mount 'echo "${@: -1}" > "$FAKE_STATE/mounted"'
fake umount 'rm -f "$FAKE_STATE/mounted"'
fake mountpoint '[ -f "$FAKE_STATE/mounted" ] && [ "$(cat "$FAKE_STATE/mounted")" = "${@: -1}" ]'
fake systemctl 'echo "systemctl $*" >> "$FAKE_LOG"'
fake getent '[ "${FAKE_EGRESS:-closed}" = open ]'
fake timeout 'case "$*" in
  */dev/tcp/*) [ "${FAKE_EGRESS:-closed}" = open ] ;;
  *) shift; exec "$@" ;;
esac'
fake docker 'for a in "$@"; do [ "$a" = "$FAKE_PASSPHRASE" ] && { echo "PASSPHRASE ON DOCKER ARGV" >> "$FAKE_LOG"; exit 9; }; done
case "$1" in
  info) python3 -I -c "import json,sys; print(json.load(open(sys.argv[1]))[\"data-root\"])" "$U14H_ETC/docker/daemon.json" ;;
  pull) echo "docker pull $3" >> "$FAKE_LOG" ;;
  image) echo "[\"${@: -1}\"]" ;;
  network) case "$2" in inspect) [ -f "$FAKE_STATE/net" ] ;; create) touch "$FAKE_STATE/net" ;; rm) rm -f "$FAKE_STATE/net" ;; esac ;;
  run) echo "docker run $*" >> "$FAKE_LOG"; touch "$FAKE_STATE/target" ;;
  container) [ -f "$FAKE_STATE/target" ] ;;
  exec) [ -f "$FAKE_STATE/target" ] ;;
  rm) rm -f "$FAKE_STATE/target" ;;
esac'
fake git 'case "$*" in
  clone*) cp -r "$FAKE_REPO" "${@: -1}" ;;
  *rev-parse*) echo "f888a81b113c0c486ea0949067779443857e644d" ;;
  *checkout*) true ;;
esac'
cp "$(command -v bash)" "$W/bin/tmux"          # a process named tmux: the drill's multiplexer check walks the ancestors

# --- the tree at the release PIN: real acceptance, preflights and libraries; a fake pull; a stub restore drill ----------
export FAKE_REPO="$W/repo"
for f in scripts/backup-dated-acceptance.sh scripts/lib/backup-common.sh scripts/lib/erasure-tombstones.sh \
         scripts/lib/restore-stamp-preflight.py scripts/lib/restore-client-compat.py scripts/lib/restore-drill-isolation-preflight.py \
         scripts/lib/restore-dump-profile.py scripts/lib/restore-erasure-ledger.py; do
  git -C "$HERE" show "$PIN:$f" > "$W/repo/$f" || { echo "SELFTEST FAIL: cannot read $f at $PIN"; exit 1; }
done
cat > "$W/repo/scripts/backup-offsite-pull.sh" <<'SH'
#!/usr/bin/env bash
for a in "$@"; do [ "$a" = "$FAKE_SAS" ] && { echo "SAS ON ARGV"; exit 9; }; done
[ "${BACKUP_AZURE_SAS_TOKEN:-}" = "$FAKE_SAS" ] || { echo "no SAS in the environment"; exit 8; }
case "${AZURE_CONFIG_DIR:-}" in */rd/azcfg) ;; *) echo "az config is not on the volume"; exit 8 ;; esac
while [ "$#" -gt 0 ]; do case "$1" in --stamp) s="$2"; shift 2 ;; --dest) d="$2"; shift 2 ;; *) shift ;; esac; done
cp -r "$FAKE_STAMPS/$s" "$d"
if [ -n "${FAKE_SUMS_TAMPER:-}" ]; then echo "tamper" >> "$d/SHA256SUMS"; fi
echo "pull env ok $s" >> "$FAKE_LOG"
SH
cat > "$W/repo/scripts/restore-drill-01.sh" <<'SH'
#!/usr/bin/env bash
# stub, as in scripts/test-backup-dated-acceptance.sh: the REAL isolation preflight, then a chosen verdict
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EVIDENCE="" ENV_FILE=""
while [ "$#" -gt 0 ]; do case "$1" in --evidence) EVIDENCE="$2"; shift 2 ;; --env-file) ENV_FILE="$2"; shift 2 ;; *) shift ;; esac; done
mkdir -p "$EVIDENCE"
export PARKIO_RESTORE_REQUIRE_ERASURE_LEDGER=1
echo rd-postgres > "$EVIDENCE/containers.txt"
iso=(--containers-file "$EVIDENCE/containers.txt" --hostname parkio-rd-20261013)
[ -n "$ENV_FILE" ] && iso+=(--env-file "$ENV_FILE")
if ! python3 "$ROOT/scripts/lib/restore-drill-isolation-preflight.py" "${iso[@]}" > "$EVIDENCE/isolation.json"; then
  printf '{"tool":"restore-drill-01","verdict":"FAIL","reason":"isolation preflight"}\n' > "$EVIDENCE/summary.json"; exit 1
fi
case "${PARKIO_DRILL_FAKE_RC:-0}" in
  0) printf '{"verdict":"PASS","reason":""}\n' > "$EVIDENCE/summary.json" ;;
  3) printf '{"verdict":"BLOCKED","reason":"erasure set incomplete"}\n' > "$EVIDENCE/summary.json" ;;
  *) printf '{"verdict":"FAIL","reason":"row-count parity auth"}\n' > "$EVIDENCE/summary.json" ;;
esac
exit "${PARKIO_DRILL_FAKE_RC:-0}"
SH
chmod +x "$W/repo/scripts/"*.sh
U14H_SCRIPT_PINS="$(cd "$W/repo" && sha256sum scripts/backup-dated-acceptance.sh scripts/backup-offsite-pull.sh scripts/restore-drill-01.sh \
  scripts/lib/backup-common.sh scripts/lib/erasure-tombstones.sh scripts/lib/restore-stamp-preflight.py scripts/lib/restore-client-compat.py \
  scripts/lib/restore-drill-isolation-preflight.py scripts/lib/restore-dump-profile.py scripts/lib/restore-erasure-ledger.py)"
export U14H_SCRIPT_PINS

# --- synthetic stamps, encrypted and sealed as the backup writes them -------------------------------------------------
DBS="auth gateway user parking media gamification notification moderation analytics ai-validation"
mkstamp() {
  local d="$1" db src; mkdir -p "$d"
  for db in $DBS; do
    printf -- '-- synthetic %s dump\nSELECT 1;\n' "$db" | gzip -c \
      | BACKUP_ENCRYPT_PASSPHRASE="$FAKE_PASSPHRASE" openssl enc -aes-256-cbc -pbkdf2 -salt -pass env:BACKUP_ENCRYPT_PASSPHRASE > "$d/$db.sql.gz.enc"
  done
  src="$(mktemp -d "$W/minio.XXXXXX")"; mkdir -p "$src/minio/b"; echo obj > "$src/minio/b/o"
  tar -C "$src" -czf - minio | BACKUP_ENCRYPT_PASSPHRASE="$FAKE_PASSPHRASE" openssl enc -aes-256-cbc -pbkdf2 -salt \
    -pass env:BACKUP_ENCRYPT_PASSPHRASE > "$d/minio.tar.gz.enc"
  printf '[]\n' > "$d/erasure-tombstones.json"
  python3 -I - "$d" "$DBS" <<'PY'
import json, sys
d, dbs = sys.argv[1], sys.argv[2].split()
json.dump({"schemaVersion": 3, "timestamp": d.rsplit("/", 1)[-1], "databases": dbs, "databasesFailed": 0,
           "minioOk": 1, "minio": {"objectCount": 1}, "encryption": {"enabled": True}, "offsite": {"uploaded": True}},
          open(f"{d}/backup-manifest.json", "w"))
PY
  (cd "$d" && find . -type f ! -name SHA256SUMS ! -name COMPLETE | LC_ALL=C sort | xargs sha256sum > SHA256SUMS)
  printf 'stamp=%s\nsha256sums=%s\n' "$(basename "$d")" "$(sha256sum "$d/SHA256SUMS" | cut -d' ' -f1)" > "$d/COMPLETE"
}
mkstamp "$FAKE_STAMPS/$DATA"; mkstamp "$FAKE_STAMPS/$LEDGER"
export U14H_SEAL="$(sha256sum "$FAKE_STAMPS/$DATA/SHA256SUMS" | cut -d' ' -f1)"

# --- the synthetic escrow, made as u14-custody-escrow.sh makes it -----------------------------------------------------
mkescrow() {  # passphrase password -> escrow file text; blob sha in $W/blobsha
  local blob
  blob="$(printf '%s\n%s' parkio-escrow-v1 "$1" | U14C_PW="$2" openssl enc -e -aes-256-cbc -pbkdf2 -md sha256 -iter 600000 \
    -salt -a -A -pass env:U14C_PW 2>/dev/null | tr -d ' \r\n\t')"
  printf '%s' "$blob" | sha256sum | cut -d' ' -f1 > "$W/blobsha"
  echo "-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----"
  echo "format: parkio-escrow-v1 (line 1 of the decrypted text is the format; line 2 is the passphrase)"
  echo "blob:"
  printf '%s\n' "$blob" | fold -w 64
  echo "-----END PARKIO BACKUP PASSPHRASE ESCROW-----"
}
mkescrow "$FAKE_PASSPHRASE" "$ESCROW_PW" > "$W/escrow.txt"; BLOBSHA="$(cat "$W/blobsha")"
mkescrow "another-passphrase" "$ESCROW_PW" > "$W/other-escrow.txt"

printf 'NAME="Ubuntu"\nVERSION_ID="24.04"\n' > "$W/os-release"
export PATH="$W/bin:$PATH" U14H_SELFTEST=1 U14H_RD="$W/rd" U14H_IMG="$W/volume.img" U14H_EVIDENCE="$W/root/rd-evidence" \
  U14H_STATE="$W/root/rd-state" U14H_ETC="$W/etc" U14H_DEVMAPPER="$W/dev/mapper" U14H_OS_RELEASE="$W/os-release" \
  U14H_TTY_OUT="$W/tty.out"
: > "$W/etc/hosts"
T() { local out="$1"; shift; /usr/bin/timeout 600 bash "$TOOL" "$@" > "$W/$out" 2>&1; echo "exit=$?" >> "$W/$out"; }
# bash execs a lone -c command in place; the trailing `exit` keeps the process named tmux alive as the tool's parent
TX() { local out="$1"; shift; /usr/bin/timeout 600 "$W/bin/tmux" -c 'bash "$0" "$@"; rc=$?; exit $rc' "$TOOL" "$@" > "$W/$out" 2>&1; echo "exit=$?" >> "$W/$out"; }
under_tmux() { local p=$$ c; while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do c="$(cat "/proc/$p/comm" 2>/dev/null)"
  case "$c" in tmux*|screen*|SCREEN*) return 0 ;; esac; p="$(awk '/^PPid:/ {print $2}' "/proc/$p/status" 2>/dev/null)"; done; return 1; }
RUNARGS=(--cutoff "$LEDGER" --expected-blob-sha256 "$BLOBSHA" --ledger-stamp "$LEDGER")
resetvol() { rm -rf "$W/root/rd-state"/* "$W/root/rd-evidence"/* "$W/rd" "$W/volume.img" "$FAKE_STATE"/* "$W/dev/mapper"/*; }

echo "=== H1 host, OS and usage guards ==="
FAKE_HOST=parkio-civo-prod T h1a.out bootstrap; has h1a.out "the drill host is parkio-rd-YYYYMMDD"; has h1a.out "exit=1"
printf 'VERSION_ID="22.04"\n' > "$W/os-2204"; U14H_OS_RELEASE="$W/os-2204" T h1b.out bootstrap; has h1b.out "the drill host runs Ubuntu 24.04"
T h1c.out nonsense; has h1c.out "exit=2"
/usr/bin/timeout 20 bash "$TOOL" run --cutoff > "$W/h1d.out" 2>&1; echo "exit=$?" >> "$W/h1d.out"   # the real timeout
has h1d.out "exit=2"                                    # a flag without its value is usage (it used to loop forever)

echo "=== H2 bootstrap refusals ==="
FAKE_FPR=0000000000000000000000000000000000000000 T h2a.out bootstrap
has h2a.out "the Microsoft keyring is not exactly the pinned key"; [ ! -e "$W/etc/apt/keyrings/microsoft.gpg" ] && ok "h2a: no key installed" || no "h2a key"
FAKE_EXTRA_KEY=2222222222222222222222222222222222222222 T h2b.out bootstrap
has h2b.out "the Microsoft keyring is not exactly the pinned key"; has h2b.out "count=2"
resetvol
cp "$W/repo/scripts/restore-drill-01.sh" "$W/rd01.bak"; echo "# tampered" >> "$W/repo/scripts/restore-drill-01.sh"
T h2c.out bootstrap; has h2c.out "a drill script differs from its pin"; has h2c.out "wipe, then bootstrap again"
cp "$W/rd01.bak" "$W/repo/scripts/restore-drill-01.sh"
T h2d.out wipe; resetvol

echo "=== H3 bootstrap ==="
T h3.out bootstrap
has h3.out "U14 DRILL HOST bootstrap: PASS"; has h3.out "exit=0"
grep -q "cryptsetup open keylen=64 args=open --type plain --cipher aes-xts-plain64 --key-size 512 --key-file -" "$FAKE_LOG" \
  && ok "h3: a 64-byte key reached plain dm-crypt on stdin" || no "h3 dm-crypt"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d["data-root"].endswith("/rd/docker") and d["features"]["containerd-snapshotter"] is False' \
  "$W/etc/docker/daemon.json" && ok "h3: Docker's data root and image store on the volume" || no "h3 daemon.json"
grep -qF "docker pull postgis/postgis@sha256:44126d87" "$FAKE_LOG" && grep -qF "docker pull postgres@sha256:21f60130" "$FAKE_LOG" && ok "h3: both images by digest" || no "h3 images"
grep -qx "127.0.1.1 parkio-rd-20261013" "$W/etc/hosts" && ok "h3: hostname in /etc/hosts" || no "h3 hosts"
grep -qF "azure-cli=2.91.0-1~noble" "$FAKE_LOG" && ok "h3: azure-cli pinned" || no "h3 az"
T h3b.out bootstrap; has h3b.out "bootstrap already ran"

echo "=== H4 pull ==="
FAKE_EGRESS=open T h4a.out pull --account parkiobackups --container backups < /dev/null
has h4a.out "outbound is wider than the storage phase"
FAKE_EGRESS=storage T h4b.out pull --account parkiobackups --container backups < /dev/null; has h4b.out "no SAS on stdin"
echo "not-a-sas" | FAKE_EGRESS=storage T h4c.out pull --account parkiobackups --container backups; has h4c.out "stdin does not hold a SAS"
printf '%s\n' "$FAKE_SAS" | FAKE_EGRESS=storage FAKE_SUMS_TAMPER=1 T h4d.out pull --account parkiobackups --container backups
has h4d.out "is not run A's seal"; rm -rf "$W/rd/stamps"
printf '%s\n' "$FAKE_SAS" | FAKE_EGRESS=storage T h4.out pull --account parkiobackups --container backups --ledger-stamp "$LEDGER"
has h4.out "U14 DRILL HOST pull: PASS"; grep -q "pull env ok $LEDGER" "$FAKE_LOG" && ok "h4: SAS env-only, az config on the volume, ledger stamp pulled" || no "h4 pull env"
hasnt h4.out "$FAKE_SAS" SAS; hasnt fake.log "$FAKE_SAS" SAS
if grep -rqF "$FAKE_SAS" "$W/root" 2>/dev/null; then no "h4: the SAS reached the state or evidence"; else ok "h4: the SAS is in no state or evidence file"; fi

echo "=== H5 probe ==="
FAKE_EGRESS=open T h5a.out probe --account parkiobackups; has h5a.out "outbound still works: dns tcp https-github https-storage"
T h5.out probe --account parkiobackups; has h5.out "U14 DRILL HOST probe: PASS"

echo "=== H6 run refusals (nothing decrypted) ==="
U14H_TTY_IN="$W/escrow.txt" TX h6a.out run --cutoff "$DATA" --expected-blob-sha256 "$BLOBSHA" --ledger-stamp "$LEDGER"
has h6a.out "--cutoff must be the newest pulled stamp ($LEDGER)"
if under_tmux; then ok "h6b: skipped (the self-test itself runs inside tmux, so every run has a tmux ancestor)"
else U14H_TTY_IN="$W/escrow.txt" T h6b.out run "${RUNARGS[@]}"; has h6b.out "start run inside tmux (or screen)"; fi
U14H_TTY_IN="$W/escrow.txt" TX h6e.out run "${RUNARGS[@]}" -- --max-age-hours 72; has h6e.out "--max-age-hours is refused"
U14H_TTY_IN="$W/escrow.txt" TX h6f.out run --cutoff "$LEDGER" --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-01T00-00-00Z
has h6f.out "--ledger-stamp must be newer than the data stamp"
FAKE_EGRESS=open U14H_TTY_IN="$W/escrow.txt" TX h6c.out run "${RUNARGS[@]}"; has h6c.out "outbound works again"
cat "$W/other-escrow.txt" "$W/other-escrow.txt" "$W/other-escrow.txt" > "$W/tty-other"; : > "$W/tty.out"
U14H_TTY_IN="$W/tty-other" TX h6d.out run "${RUNARGS[@]}"
has h6d.out "the escrow did not open (no run is recorded; start run again)"; has tty.out "or --expected-blob-sha256 is mistyped"
[ ! -e "$W/root/rd-state/run.json" ] && ok "h6d: a failed escrow records no run (run can start again)" || no "h6d run.json"
grep -q "pastes=3 passwords=0 blobMatches=no" "$W/root/rd-state/escrow-attempts.log" && ok "h6d: the attempt is logged without secrets" || no "h6d log"
cmp -s "$W/root/rd-state/escrow-attempts.log" "$W/root/rd-evidence/escrow-attempts.log" && ok "h6d: the attempt log is in the evidence too" || no "h6d evidence log"
[ ! -e "$W/root/rd-evidence/B" ] && ok "h6: no acceptance ran" || no "h6 acceptance ran"

echo "=== H7 run: blank line and a wrong password, then the right one; the real acceptance; outcomes 3-5 PASS ==="
{ cat "$W/escrow.txt"; echo ""; echo "a wrong escrow password here"; echo "$ESCROW_PW"; } > "$W/tty-run"; : > "$W/tty.out"
U14H_TTY_IN="$W/tty-run" TX h7.out run "${RUNARGS[@]}"
has h7.out "the escrow opened (blob SHA-256 equals escrow.json's; pastes 1, passwords 2)"
has h7.out "remote-integrity=PASS"; has h7.out "decrypt=PASS"; has h7.out "isolated-restore=PASS"
has h7.out "U14 DRILL HOST run: PASS (outcomes 3, 4 and 5 PASS)"; has h7.out "exit=0"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); c={x[0]:x[1] for x in r["checks"]} if isinstance(r.get("checks"),list) and r["checks"] and isinstance(r["checks"][0],list) else None
s=json.dumps(r); assert "drill-id" in s and "PARKIO_DRILL_ID set" in s' "$W/root/rd-evidence/B/isolated-restore/isolation.json" \
  && ok "h7: the isolation preflight saw PARKIO_DRILL_ID" || no "h7 drill id"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"] == "PASS" and r["drillId"].startswith("rd-")' \
  "$W/root/rd-state/run.json" && ok "h7: run record PASS with its drill id" || no "h7 record"
for secret in "$FAKE_PASSPHRASE" "$ESCROW_PW" "$(sed -n '/^blob:/{n;p;}' "$W/escrow.txt")"; do
  if grep -rqF -- "$secret" "$W/h7.out" "$W/tty.out" "$W/root" "$FAKE_LOG" 2>/dev/null; then no "h7: a secret reached an output, log or record"
  else ok "h7: a ${#secret}-char secret is in no output, log or record"; fi
done
U14H_TTY_IN="$W/tty-run" TX h7b.out run "${RUNARGS[@]}"; has h7b.out "run already ran (PASS); reset --yes first"

echo "=== H8 reset, then BLOCKED (INCOMPLETE) and FAIL are not a PASS ==="
T h8a.out reset; has h8a.out "pass --yes"
T h8b.out reset --yes; has h8b.out "U14 DRILL HOST reset: PASS (previous run PASS"
ls -d "$W/root/rd-evidence"/B-reset-* >/dev/null 2>&1 && ok "h8: the previous evidence is kept aside" || no "h8 kept"
PARKIO_DRILL_FAKE_RC=3 U14H_TTY_IN="$W/tty-run" TX h8c.out run "${RUNARGS[@]}"
has h8c.out "isolated-restore=BLOCKED"; has h8c.out "INCOMPLETE: outcomes 3-5 are PASS/PASS/BLOCKED"; has h8c.out "exit=1"
T h8d.out reset --yes
PARKIO_DRILL_FAKE_RC=1 U14H_TTY_IN="$W/tty-run" TX h8e.out run "${RUNARGS[@]}"
has h8e.out "isolated-restore=FAIL"; has h8e.out "the acceptance reported a FAIL"; has h8e.out "exit=1"
python3 -I -c 'import json,sys; assert json.load(open(sys.argv[1]))["result"] == "FAIL"' "$W/root/rd-state/run.json" \
  && ok "h8: run record FAIL" || no "h8 record"
T h8f.out reset --yes
mkdir -p "$W/root/rd-evidence/B"; : > "$W/tty.out"
U14H_TTY_IN="$W/tty-run" TX h8g.out run "${RUNARGS[@]}"; has h8g.out "a previous run left"
[ ! -s "$W/tty.out" ] && ok "h8g: refused before the escrow prompt" || no "h8g prompt shown"
rmdir "$W/root/rd-evidence/B"; touch "$FAKE_STATE/target"
U14H_TTY_IN="$W/tty-run" TX h8h.out run "${RUNARGS[@]}"; has h8h.out "a previous target rd-postgres exists"
rm -f "$FAKE_STATE/target"
{ cat "$W/other-escrow.txt" "$W/other-escrow.txt" "$W/escrow.txt"; echo "a wrong escrow password here"; echo "$ESCROW_PW"; } > "$W/tty-third"
U14H_TTY_IN="$W/tty-third" TX h8i.out run "${RUNARGS[@]}"
has h8i.out "the escrow opened (blob SHA-256 equals escrow.json's; pastes 3, passwords 2)"; has h8i.out "U14 DRILL HOST run: PASS"

echo "=== H9 evidence ==="
T h9.out evidence; has h9.out "U14 DRILL HOST evidence:"; [ -s "$W/root/rd-evidence/SHA256SUMS" ] && ok "h9: SHA256SUMS" || no "h9 sums"
echo "x?sig=AbCdEf123" > "$W/root/rd-evidence/leak.txt"; T h9b.out evidence; has h9b.out "the evidence holds escrow or SAS text"
rm -f "$W/root/rd-evidence/leak.txt"

echo "=== H10 wipe ==="
T h10.out wipe; has h10.out "U14 DRILL HOST wipe: PASS"
[ ! -e "$W/volume.img" ] && [ ! -e "$W/dev/mapper/rd-crypt" ] && ok "h10: volume closed and removed" || no "h10 volume"

[ "$pass" -ge 60 ] || no "only ${pass} checks ran (expected at least 60)"
echo "=== summary: ${pass} ok, ${fail} FAIL ==="
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || { echo "SELFTEST FAIL"; exit 1; }
