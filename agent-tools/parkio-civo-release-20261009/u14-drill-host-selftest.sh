#!/usr/bin/env bash
# Synthetic self-test of u14-drill-host.sh: every external command is a fake on PATH, except openssl, sha256sum and the
# shell tools. The escrow is made exactly as u14-custody-escrow.sh makes it, from a synthetic passphrase and password.
# No real backup, escrow, SAS or host is used. usage: bash u14-drill-host-selftest.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TOOL="$HERE/u14-drill-host.sh"
W="$(mktemp -d "${TMPDIR:-/tmp}/u14h-selftest.XXXXXX")"
trap 'rm -rf "$W"' EXIT
pass=0; fail=0
ok() { pass=$((pass + 1)); echo "ok   $*"; }
no() { fail=$((fail + 1)); echo "FAIL $*"; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; sed 's/^/     | /' "$W/$1" | tail -8; fi; }
hasnt() {  # the needle may be a (synthetic) secret: it is never printed
  if grep -qF -- "$2" "$W/$1"; then no "$1 holds a ${3:-secret} value"; else ok "$1 holds no ${3:-secret} value"; fi
}

mkdir -p "$W/bin" "$W/etc/apt/sources.list.d" "$W/dev/mapper" "$W/repo/scripts/lib" "$W/root"
export FAKE_PASSPHRASE="synthetic-backup-passphrase-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export FAKE_SAS="sv=2024-11-04&sr=c&sp=rl&se=2026-10-13T12:00:00Z&sip=10.0.0.4&spr=https&sig=SyntheticSig$(head -c 6 /dev/urandom | od -An -tx1 | tr -d ' \n')"
export FAKE_LOG="$W/fake.log" FAKE_STATE="$W/fake-state"
export FAKE_SUMS_CONTENT="0000000000000000000000000000000000000000000000000000000000000000  auth.sql.gz.enc"
ESCROW_PW="synthetic escrow password words five"
mkdir -p "$FAKE_STATE"

# --- fakes ------------------------------------------------------------------------------------------------------------
fake() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$W/bin/$1"; chmod +x "$W/bin/$1"; }
fake hostname 'echo "${FAKE_HOST:-parkio-rd-20261013}"'
fake apt-get 'echo "apt-get $*" >> "$FAKE_LOG"; [ -z "${FAKE_APT_FAIL:-}" ]'
fake dpkg-query 'printf "docker.io=29.1.3 cryptsetup=2:2.7.0 jq=1.7.1 azure-cli=2.91.0-1~noble python3=3.12.3 openssl=3.0.13 git=1:2.43.0 "'
fake curl 'case "$*" in
  *packages.microsoft.com/keys*) echo "FAKE-MICROSOFT-KEY" ;;
  *blob.core.windows.net*) [ "${FAKE_EGRESS:-closed}" = open ] ;;
  *) exit 7 ;;
esac'
fake gpg 'case "$1" in
  --dearmor) cat ;;
  --show-keys) echo "pub:-:4096:1:EB3E94ADBE1229CF:::::::scESC:"; echo "fpr:::::::::${FAKE_FPR:-BC528686B50D79E339D3721CEB3E94ADBE1229CF}:" ;;
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
  exec) [ -f "$FAKE_STATE/target" ] ;;
  rm) rm -f "$FAKE_STATE/target" ;;
esac'
fake git 'case "$*" in
  clone*) cp -r "$FAKE_REPO" "${@: -1}" ;;
  *rev-parse*) echo "${FAKE_HEAD:-f888a81b113c0c486ea0949067779443857e644d}" ;;
  *checkout*) true ;;
esac'

# --- the fake repository at the pinned commit -------------------------------------------------------------------------
export FAKE_REPO="$W/repo"
cat > "$W/repo/scripts/backup-offsite-pull.sh" <<'SH'
#!/usr/bin/env bash
for a in "$@"; do [ "$a" = "$FAKE_SAS" ] && { echo "SAS ON ARGV"; exit 9; }; done
[ "${BACKUP_AZURE_SAS_TOKEN:-}" = "$FAKE_SAS" ] || { echo "no SAS in the environment"; exit 8; }
[ "${BACKUP_OFFSITE_KIND:-}" = azure ] || exit 8
while [ "$#" -gt 0 ]; do case "$1" in --stamp) s="$2"; shift 2 ;; --dest) d="$2"; shift 2 ;; *) shift ;; esac; done
mkdir -p "$d"; printf '%s\n' "${FAKE_SUMS_OVERRIDE:-$FAKE_SUMS_CONTENT}" > "$d/SHA256SUMS"; echo "pulled $s"
echo "pull env ok $s" >> "$FAKE_LOG"
SH
cat > "$W/repo/scripts/backup-dated-acceptance.sh" <<'SH'
#!/usr/bin/env bash
for a in "$@"; do [ "$a" = "$FAKE_PASSPHRASE" ] && { echo "PASSPHRASE ON ARGV"; exit 9; }; done
if [ "${BACKUP_ENCRYPT_PASSPHRASE:-}" = "$FAKE_PASSPHRASE" ]; then echo "acceptance passphrase=match" >> "$FAKE_LOG"
else echo "acceptance passphrase=mismatch" >> "$FAKE_LOG"; fi
echo "acceptance args: $*" >> "$FAKE_LOG"
echo "acceptance env: psql=${PARKIO_RESTORE_PSQL_IMAGE:-} net=${PARKIO_RESTORE_PSQL_NETWORK:-} host=${PARKIO_RESTORE_PSQL_HOST:-}" >> "$FAKE_LOG"
while [ "$#" -gt 0 ]; do case "$1" in --evidence) ev="$2"; shift 2 ;; --) break ;; *) shift ;; esac; done
mkdir -p "$ev"
printf '{"outcomes": [{"id": "complete", "result": "NOT_RUN"}, {"id": "remote-presence", "result": "NOT_RUN"}, {"id": "remote-integrity", "result": "PASS"}, {"id": "decrypt", "result": "PASS"}, {"id": "isolated-restore", "result": "PASS"}], "verdict": "INCOMPLETE"}\n' > "$ev/acceptance.json"
exit "${FAKE_ACC_RC:-4}"
SH
for f in restore-drill-01.sh lib/backup-common.sh lib/erasure-tombstones.sh lib/restore-stamp-preflight.py \
         lib/restore-client-compat.py lib/restore-drill-isolation-preflight.py lib/restore-dump-profile.py lib/restore-erasure-ledger.py; do
  echo "# fake $f" > "$W/repo/scripts/$f"
done
chmod +x "$W/repo/scripts/"*.sh
U14H_SCRIPT_PINS="$(cd "$W/repo" && sha256sum scripts/backup-dated-acceptance.sh scripts/backup-offsite-pull.sh scripts/restore-drill-01.sh \
  scripts/lib/backup-common.sh scripts/lib/erasure-tombstones.sh scripts/lib/restore-stamp-preflight.py scripts/lib/restore-client-compat.py \
  scripts/lib/restore-drill-isolation-preflight.py scripts/lib/restore-dump-profile.py scripts/lib/restore-erasure-ledger.py)"
export U14H_SCRIPT_PINS
export U14H_SEAL="$(printf '%s\n' "$FAKE_SUMS_CONTENT" | sha256sum | cut -d' ' -f1)"

# --- the synthetic escrow, made as u14-custody-escrow.sh makes it -----------------------------------------------------
mkescrow() {  # passphrase password -> escrow file text on stdout, blob sha in $W/blobsha
  local blob
  blob="$(printf '%s\n%s' parkio-escrow-v1 "$1" | U14C_PW="$2" openssl enc -e -aes-256-cbc -pbkdf2 -md sha256 -iter 600000 \
    -salt -a -A -pass env:U14C_PW 2>/dev/null | tr -d ' \r\n\t')"
  printf '%s' "$blob" | sha256sum | cut -d' ' -f1 > "$W/blobsha"
  echo "-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----"
  echo "format: parkio-escrow-v1 (line 1 of the decrypted text is the format; line 2 is the passphrase)"
  echo "blob-sha256: $(cat "$W/blobsha")"
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
T() { local out="$1"; shift; bash "$TOOL" "$@" > "$W/$out" 2>&1; echo "exit=$?" >> "$W/$out"; }

echo "=== H1 host and OS guards ==="
FAKE_HOST=parkio-civo-prod T h1a.out bootstrap; has h1a.out "the drill host is parkio-rd-YYYYMMDD"; has h1a.out "exit=1"
printf 'VERSION_ID="22.04"\n' > "$W/os-2204"; U14H_OS_RELEASE="$W/os-2204" T h1b.out bootstrap; has h1b.out "the drill host runs Ubuntu 24.04"
T h1c.out nonsense; has h1c.out "exit=2"

echo "=== H2 bootstrap refusals ==="
FAKE_FPR=0000000000000000000000000000000000000000 T h2a.out bootstrap
has h2a.out "the Microsoft key's fingerprint is not the pinned one"; [ ! -e "$W/etc/apt/keyrings/microsoft.gpg" ] && ok "h2a: no key installed" || no "h2a key"
rm -rf "$W/root/rd-state"/* "$W/rd" "$W/volume.img" "$FAKE_STATE"/* "$W/dev/mapper"/*
cp "$W/repo/scripts/restore-drill-01.sh" "$W/rd01.bak"; echo "# tampered" >> "$W/repo/scripts/restore-drill-01.sh"
T h2b.out bootstrap; has h2b.out "a drill script differs from its pin"
cp "$W/rd01.bak" "$W/repo/scripts/restore-drill-01.sh"
rm -rf "$W/root/rd-state"/* "$W/rd" "$W/volume.img" "$FAKE_STATE"/* "$W/dev/mapper"/*

echo "=== H3 bootstrap ==="
T h3.out bootstrap
has h3.out "U14 DRILL HOST bootstrap: PASS"; has h3.out "exit=0"
grep -q "cryptsetup open keylen=64" "$FAKE_LOG" && ok "h3: a 64-byte key reached cryptsetup on stdin" || no "h3 key"
grep -q "keylen=64 args=open --type plain --cipher aes-xts-plain64 --key-size 512 --key-file -" "$FAKE_LOG" && ok "h3: plain dm-crypt, key from stdin" || no "h3 dm-crypt args"
grep -qF '"data-root": "'"$W/rd"'/docker"' "$W/etc/docker/daemon.json" && ok "h3: Docker data root on the volume" || no "h3 data root"
grep -qF "docker pull postgis/postgis@sha256:44126d87" "$FAKE_LOG" && grep -qF "docker pull postgres@sha256:21f60130" "$FAKE_LOG" && ok "h3: both images by digest" || no "h3 images"
grep -qx "127.0.1.1 parkio-rd-20261013" "$W/etc/hosts" && ok "h3: hostname in /etc/hosts" || no "h3 hosts"
grep -qF "azure-cli=2.91.0-1~noble" "$FAKE_LOG" && ok "h3: azure-cli pinned" || no "h3 az"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"]=="PASS" and r["repoCommit"].startswith("f888a81b") and "key not stored" in r["volume"]' "$W/root/rd-evidence/bootstrap.json" && ok "h3: bootstrap record" || no "h3 record"
T h3b.out bootstrap; has h3b.out "bootstrap already ran"

echo "=== H4 pull ==="
T h4a.out pull --account parkiobackups --container backups < /dev/null; has h4a.out "no SAS on stdin"
echo "not-a-sas" | T h4b.out pull --account parkiobackups --container backups; has h4b.out "stdin does not hold a SAS"
printf '%s\n' "$FAKE_SAS" | FAKE_SUMS_OVERRIDE="1111  other" T h4c.out pull --account parkiobackups --container backups
has h4c.out "is not run A's seal"; rm -rf "$W/rd/stamps"
printf '%s\n' "$FAKE_SAS" | T h4.out pull --account parkiobackups --container backups --ledger-stamp 2026-10-14T03-30-00Z
has h4.out "U14 DRILL HOST pull: PASS"; grep -q "pull env ok 2026-10-14T03-30-00Z" "$FAKE_LOG" && ok "h4: ledger stamp pulled too" || no "h4 ledger"
hasnt h4.out "$FAKE_SAS" SAS; hasnt fake.log "$FAKE_SAS" SAS
if grep -rqF "$FAKE_SAS" "$W/root" "$W/rd/work" 2>/dev/null; then no "h4: the SAS reached a file"; else ok "h4: the SAS is in no file"; fi

echo "=== H5 probe ==="
FAKE_EGRESS=open T h5a.out probe --account parkiobackups; has h5a.out "outbound still works: dns tcp https-storage"
T h5.out probe --account parkiobackups; has h5.out "U14 DRILL HOST probe: PASS"

echo "=== H6 run refusals ==="
FAKE_EGRESS=open U14H_TTY_IN="$W/escrow.txt" T h6a.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-14T03-30-00Z
has h6a.out "outbound works again"; grep -q "acceptance" "$FAKE_LOG" && no "h6a: the acceptance ran" || ok "h6a: nothing decrypted, no acceptance"
U14H_TTY_IN="$W/escrow.txt" T h6b.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA"
has h6b.out "the pulled ledger stamp is"
cat "$W/other-escrow.txt" "$W/other-escrow.txt" "$W/other-escrow.txt" > "$W/tty-other"
U14H_TTY_IN="$W/tty-other" T h6c.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-14T03-30-00Z
has h6c.out "the escrow did not open"; has tty.out "This is not the escrow recorded in escrow.json"
grep -q "acceptance" "$FAKE_LOG" && no "h6c: the acceptance ran" || ok "h6c: a foreign escrow never reaches the acceptance"
rm -f "$W/root/rd-state/run.json"

echo "=== H7 run: a wrong password, then the right one ==="
{ cat "$W/escrow.txt"; echo "a wrong escrow password here"; cat "$W/escrow.txt"; echo "$ESCROW_PW"; } > "$W/tty-run"
: > "$W/tty.out"
U14H_TTY_IN="$W/tty-run" T h7.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-14T03-30-00Z
has h7.out "the escrow opened (blob SHA-256 equals escrow.json's; attempts 2)"; has tty.out "The escrow did not open"
has h7.out "acceptance exit 4"; has h7.out "remote-integrity=PASS decrypt=PASS isolated-restore=PASS"; has h7.out "U14 DRILL HOST run: done"
grep -q "acceptance passphrase=match" "$FAKE_LOG" && ok "h7: the acceptance got the escrowed passphrase, in its environment" || no "h7 passphrase"
grep -qF -- "--pulled $W/rd/stamps/2026-10-12T11-18-41Z --expected-sha256sums $U14H_SEAL" "$FAKE_LOG" && ok "h7: the pulled copy and the seal" || no "h7 args"
grep -qF -- "--recovery-cutoff 2026-10-14T03-30-00Z --container rd-postgres --probe-default-egress --ledger-stamp $W/rd/stamps/2026-10-14T03-30-00Z" "$FAKE_LOG" \
  && ok "h7: the drill arguments" || no "h7 drill args"
grep -qF "acceptance env: psql=postgres@sha256:21f60130" "$FAKE_LOG" && grep -qF "net=rd-net host=rd-postgres" "$FAKE_LOG" && ok "h7: the identified restore client" || no "h7 env"
grep -qF -- "--network rd-net --memory 1500m" "$FAKE_LOG" && ok "h7: the target on the internal network" || no "h7 target"
for secret in "$FAKE_PASSPHRASE" "$ESCROW_PW" "$(sed -n '/^blob:/{n;p;}' "$W/escrow.txt")"; do
  if grep -rqF -- "$secret" "$W/h7.out" "$W/tty.out" "$W/root" "$FAKE_LOG" 2>/dev/null; then no "h7: a secret reached an output, log or record"; else ok "h7: secret ${#secret}-char value in no output, log or record"; fi
done
U14H_TTY_IN="$W/tty-run" T h7b.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-14T03-30-00Z
has h7b.out "run already ran"

echo "=== H8 a failing acceptance is reported as a failure ==="
rm -f "$W/root/rd-state/run.json"
FAKE_ACC_RC=1 U14H_TTY_IN="$W/tty-run" T h8.out run --cutoff 2026-10-14T03-30-00Z --expected-blob-sha256 "$BLOBSHA" --ledger-stamp 2026-10-14T03-30-00Z
has h8.out "the acceptance reported a FAIL or could not run (exit 1)"; has h8.out "exit=1"

echo "=== H9 evidence ==="
T h9.out evidence; has h9.out "U14 DRILL HOST evidence:"; [ -s "$W/root/rd-evidence/SHA256SUMS" ] && ok "h9: SHA256SUMS" || no "h9 sums"
echo "x?sig=AbCdEf123" > "$W/root/rd-evidence/leak.txt"; T h9b.out evidence; has h9b.out "the evidence holds escrow or SAS text"
rm -f "$W/root/rd-evidence/leak.txt"

echo "=== H10 wipe ==="
T h10.out wipe; has h10.out "U14 DRILL HOST wipe: PASS"
[ ! -e "$W/volume.img" ] && [ ! -e "$W/dev/mapper/rd-crypt" ] && ok "h10: volume closed and removed" || no "h10 volume"
grep -q "cryptsetup close rd-crypt" "$FAKE_LOG" && ok "h10: dm-crypt closed" || no "h10 close"

[ "$pass" -ge 50 ] || no "only ${pass} checks ran (expected at least 50)"
echo "=== summary: ${pass} ok, ${fail} FAIL ==="
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || { echo "SELFTEST FAIL"; exit 1; }
