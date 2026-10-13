#!/usr/bin/env bash
# U14 run B on the disposable drill VM (Asana 1219001907916752; DRILL-PLAN-B.md v3): bootstrap, pull, egress probe, the
# dated acceptance with the passphrase recovered from the escrow, evidence, wipe. It runs only on the VM that
# u14-drill-azure.sh `up` made (hostname parkio-rd-YYYYMMDD, Ubuntu 24.04), as root, one phase at a time:
#
#   bootstrap                       D2, outbound open, no backup byte on the host yet: packages (azure-cli pinned, the
#                                   Microsoft keyring must hold exactly the pinned key), the encrypted work volume /rd
#                                   (dm-crypt, random key handed to the kernel on stdin and kept nowhere else), Docker's
#                                   data root and image store on it, the two images by digest, the repository at the pinned
#                                   commit with every drill script checked against its SHA-256, the hostname in /etc/hosts.
#                                   After a partial failure: wipe, then bootstrap again.
#   pull --account A --container C [--stamp S] [--ledger-stamp L]
#                                   D3, after `egress storage` (refused while other outbound works). The read-only SAS
#                                   arrives on stdin, one line; it is never shown, written or put on a command line, and
#                                   reaches backup-offsite-pull.sh only in its environment (az's config and logs on /rd).
#                                   The data stamp's SHA256SUMS digest must equal run A's seal.
#   probe [--account A]             after `egress deny`: DNS, TCP and HTTPS out must all fail
#   run --cutoff STAMP --expected-blob-sha256 HEX [--stamp S] [--ledger-stamp L] [--no-tmux] [-- EXTRA DRILL ARGS]
#                                   D4, inside tmux (or screen): a dropped SSH session must not stop it. Refuses unless the
#                                   probe passes again now and the pins still hold. The cutoff is the newest pulled stamp
#                                   (the ledger stamp if one was pulled). HEX is blobSha256 of the C1 escrow.json
#                                   (DRILL-HOST-PREP.md). The escrow file is pasted and its password typed at a hidden prompt;
#                                   a failure there records no run, so run can be started again. The passphrase is recovered
#                                   in memory and exported only to backup-dated-acceptance.sh (outcomes 3-5 on the pulled
#                                   copy, PARKIO_DRILL_ID rd-YYYYMMDD-b), then dropped. PASS only when outcomes 3, 4 and 5
#                                   are all PASS; INCOMPLETE (NOT_RUN, BLOCKED) and FAIL exit 1.
#   reset --yes                     after an interrupted or failed run: the target and its network are removed, the run's
#                                   evidence and work are moved aside (kept as evidence), and run may be started again
#   evidence                        D5: SHA256SUMS over the secret-free evidence, packed (and copied to the sudo user's home
#                                   for scp from Cloud Shell)
#   wipe                            the target container and network removed; the volume unmounted and closed (its key is
#                                   gone); the backing file removed
#
# Nothing here reads production, changes a cloud resource or holds a credential beyond the SAS's own phase. The escrow file,
# the escrow password and the passphrase are never printed, logged, stored or put on a command line (bash builtins and
# openssl -pass env: only). Evidence (/root/rd-evidence) is secret-free; stamps and work files live only on /rd.
# Exit: 0 = the phase passed; 1 = it failed, was incomplete or refused; 2 = usage.
set -uo pipefail
PHASE="${1:-}"; [ "$#" -gt 0 ] && shift

# --- pins (reviewed; a change needs a new reviewed commit) --------------------------------------------------------------
DATA_STAMP_DEFAULT="2026-10-12T11-18-41Z"
SEAL="cac808e9040078db848c8de3aead320ff323965b46756bf8bcfee817762a70a8"     # run A: SHA256SUMS digest of that stamp
REPO_URL="https://github.com/ADBERILGEN35/parkio.git"
REPO_COMMIT="f888a81b113c0c486ea0949067779443857e644d"                       # the release PIN run A used
SCRIPT_PINS="3014cc2a7820e0cb5f51ba2b59fbeb2eeb97ef777ebd627761c4a884a0b1b899  scripts/backup-dated-acceptance.sh
c20de2cf750cc636f13eaee18667cac26a261113c4f61a12f55873aa324422e8  scripts/backup-offsite-pull.sh
d8b6ef58df3f8aeaa09e5c8564406a0fa5f5730f9ea72c0493f64ce0679e4ca5  scripts/restore-drill-01.sh
776a70d6b78c00f7d1a398242e01b1c48d8b40c304503c16c75a0a592bbe871b  scripts/lib/backup-common.sh
c1b36d2be52e09277d85636170c87daad1114d66d8ae2d67ca44db333ae3756b  scripts/lib/erasure-tombstones.sh
db312feba7117d7f568d02c1a35bbf8134631d3c80a7eff8e7107218932c23a9  scripts/lib/restore-stamp-preflight.py
f946d8589b5a66a4fb55339ba2f815177d3684e41cee5979b901e8067e5e3f89  scripts/lib/restore-client-compat.py
f07c5711aa6c1958831b5e1d2eb0b4506719e3e57ec321207b13ed7da89300f2  scripts/lib/restore-drill-isolation-preflight.py
3008048b969b1f9ea921b0baa59687ebc4ca492f4a2cc40bf919a5b77450c8c2  scripts/lib/restore-dump-profile.py
e81a230804bd645107ebe69c24a17cbcc432d4171b149f43633a404951527256  scripts/lib/restore-erasure-ledger.py"
POSTGIS="postgis/postgis@sha256:44126d872ac91993766c341e369c539e8196614321765d36a6f1bab0419a5fa5"   # = the live parking DB
PSQL="postgres@sha256:21f6013073bc6b92830a2129570e2f5ec42a6c734b5a985a41e83aa58f54c3c1"             # postgres:16.10 client
AZ_CLI_VERSION="2.91.0-1~noble"
MS_KEY_FPR="BC528686B50D79E339D3721CEB3E94ADBE1229CF"                        # packages.microsoft.com signing key
ESCROW_FORMAT="parkio-escrow-v1"
ESCROW_ITER=600000
if [ "${U14H_SELFTEST:-}" = 1 ]; then            # the synthetic self-test only: its own pins and seal
  SCRIPT_PINS="${U14H_SCRIPT_PINS:-$SCRIPT_PINS}"; SEAL="${U14H_SEAL:-$SEAL}"; MS_KEY_FPR="${U14H_MS_KEY_FPR:-$MS_KEY_FPR}"
fi

# --- paths (the self-test redirects them; nothing else does) -----------------------------------------------------------
RD="${U14H_RD:-/rd}"                                     # mount point of the encrypted volume
IMG="${U14H_IMG:-/var/lib/parkio-rd/volume.img}"
IMG_SIZE="${U14H_IMG_SIZE:-20G}"
MAPPER="${U14H_MAPPER:-rd-crypt}"
EV="${U14H_EVIDENCE:-/root/rd-evidence}"
ST="${U14H_STATE:-/root/rd-state}"
ETC="${U14H_ETC:-/etc}"
TTY_IN="${U14H_TTY_IN:-/dev/tty}"
TTY_OUT="${U14H_TTY_OUT:-/dev/tty}"
DEVMAP="${U14H_DEVMAPPER:-/dev/mapper}"
OS_RELEASE="${U14H_OS_RELEASE:-/etc/os-release}"
NET=rd-net
TARGET=rd-postgres

say() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
stop() { say "STOP: $*"; say "U14 DRILL HOST ${PHASE:-?}: FAIL"; exit 1; }
usage() { sed -n '2,42p' "$0" >&2; exit 2; }
record() {  # record <phase> key=value...: a secret-free JSON record of the phase in the state and evidence directories
  local ph="$1"; shift
  python3 -I - "$ST/$ph.json" "$EV/$ph.json" "$ph" "$@" <<'PY'
import json, sys, time
st, ev, ph = sys.argv[1:4]
r = {"phase": ph, "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
for kv in sys.argv[4:]:
    k, _, v = kv.partition("=")
    r[k] = v
for p in (st, ev):
    with open(p, "w") as f:
        json.dump(r, f, indent=1, sort_keys=True)
PY
}
state() {  # state <phase> <key>: a value of a phase record, or nothing
  python3 -I -c 'import json,sys
try: print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))
except Exception: print("")' "$ST/$1.json" "$2"
}

[ "${U14H_SELFTEST:-}" = 1 ] || [ "$(id -u)" = 0 ] || { echo "STOP: run as root (sudo)"; exit 1; }
case "$PHASE" in bootstrap|pull|probe|run|reset|evidence|wipe) ;; *) usage ;; esac
host="$(hostname)"
[[ "$host" =~ ^parkio-rd-[0-9]{8}$ ]] || stop "this host is '$host'; the drill host is parkio-rd-YYYYMMDD (u14-drill-azure.sh up)"
grep -qx 'VERSION_ID="24.04"' "$OS_RELEASE" 2>/dev/null || stop "the drill host runs Ubuntu 24.04"
umask 077
mkdir -p "$ST" "$EV"; chmod 700 "$ST" "$EV"

ACCOUNT=""; CONTAINER=""; STAMP="$DATA_STAMP_DEFAULT"; LEDGER=""; CUTOFF=""; BLOB_SHA=""; NO_TMUX=0; YES=0; EXTRA=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --account|--container|--stamp|--ledger-stamp|--cutoff|--expected-blob-sha256)
      [ "$#" -ge 2 ] || usage
      case "$1" in
        --account) ACCOUNT="$2" ;; --container) CONTAINER="$2" ;; --stamp) STAMP="$2" ;; --ledger-stamp) LEDGER="$2" ;;
        --cutoff) CUTOFF="$2" ;; --expected-blob-sha256) BLOB_SHA="$2" ;;
      esac
      shift 2 ;;
    --no-tmux) NO_TMUX=1; shift ;;
    --yes) YES=1; shift ;;
    --) shift; EXTRA=("$@"); break ;;
    *) usage ;;
  esac
done
STAMP_RE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}-[0-9]{2}Z$'
[[ "$STAMP" =~ $STAMP_RE ]] || stop "--stamp must look like 2026-10-12T11-18-41Z"
[ -z "$LEDGER" ] || [[ "$LEDGER" =~ $STAMP_RE ]] || stop "--ledger-stamp must look like 2026-10-12T11-18-41Z"
[ -z "$ACCOUNT" ] || [[ "$ACCOUNT" =~ ^[a-z0-9]{3,24}$ ]] || stop "--account is a storage account name"
[ -z "$CONTAINER" ] || [[ "$CONTAINER" =~ ^[a-z0-9-]{3,63}$ ]] || stop "--container is a blob container name"

volume_open() { [ -e "$DEVMAP/$MAPPER" ] && mountpoint -q "$RD"; }
pins_ok() { (cd "$RD/parkio" && printf '%s\n' "$SCRIPT_PINS" | sha256sum -c --quiet -) >/dev/null 2>&1; }
probe_out() {  # OPEN = what reaches out from here: dns tcp https-github https-storage
  local open=""
  if timeout 6 getent hosts github.com >/dev/null 2>&1; then open="$open dns"; fi
  if timeout 6 bash -c 'exec 3<>/dev/tcp/1.1.1.1/443' >/dev/null 2>&1; then open="$open tcp"; fi
  if timeout 8 curl -s -o /dev/null --max-time 6 https://github.com/ 2>/dev/null; then open="$open https-github"; fi
  if [ -n "$ACCOUNT" ] && timeout 8 curl -s -o /dev/null --max-time 6 "https://$ACCOUNT.blob.core.windows.net/" 2>/dev/null; then
    open="$open https-storage"
  fi
  OPEN="${open# }"
}
egress_denied() { probe_out; [ -z "$OPEN" ]; }
egress_storage_only() { probe_out; case " $OPEN " in *" tcp "*|*" https-github "*) return 1 ;; esac; return 0; }
in_multiplexer() {  # 0 when an ancestor process is tmux or screen
  local p="$$" c
  while [ -n "$p" ] && [ "$p" -gt 1 ] 2>/dev/null; do
    c="$(cat "/proc/$p/comm" 2>/dev/null)"
    case "$c" in tmux*|screen*|SCREEN*) return 0 ;; esac
    p="$(awk '/^PPid:/ {print $2}' "/proc/$p/status" 2>/dev/null)"
  done
  return 1
}

case "$PHASE" in
bootstrap)
  [ -z "$(state bootstrap result)" ] || stop "bootstrap already ran ($(state bootstrap result)); wipe and start a new VM"
  say "D2 bootstrap on $host"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -q >/dev/null || stop "apt-get update failed"
  apt-get install -y -q --no-install-recommends cryptsetup jq docker.io git ca-certificates curl gnupg python3 openssl gzip \
    >/dev/null || stop "apt-get install failed"
  install -d -m 0755 "$ETC/apt/keyrings"
  curl -fsSL --max-time 60 https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor > "$ETC/apt/keyrings/microsoft.gpg.new" \
    || stop "could not fetch the Microsoft package key"
  # exactly one primary key, and it is the pinned one (an extra key in the file would also be trusted by apt)
  keys="$(gpg --show-keys --with-colons "$ETC/apt/keyrings/microsoft.gpg.new" 2>/dev/null \
    | awk -F: '$1 == "pub" {n++; want = 1; next} want && $1 == "fpr" {print $10; want = 0} END {print "count=" n + 0}')"
  if [ "$(printf '%s\n' "$keys" | tail -1)" != "count=1" ] || [ "$(printf '%s\n' "$keys" | head -1)" != "$MS_KEY_FPR" ]; then
    rm -f "$ETC/apt/keyrings/microsoft.gpg.new"; stop "the Microsoft keyring is not exactly the pinned key (got: $(printf '%s' "$keys" | tr '\n' ' '))"
  fi
  mv "$ETC/apt/keyrings/microsoft.gpg.new" "$ETC/apt/keyrings/microsoft.gpg"; chmod 644 "$ETC/apt/keyrings/microsoft.gpg"
  echo "deb [arch=amd64 signed-by=$ETC/apt/keyrings/microsoft.gpg] https://packages.microsoft.com/repos/azure-cli/ noble main" \
    > "$ETC/apt/sources.list.d/azure-cli.list"
  apt-get update -q >/dev/null && apt-get install -y -q "azure-cli=$AZ_CLI_VERSION" >/dev/null || stop "azure-cli $AZ_CLI_VERSION is not installable"
  versions="$(dpkg-query -W -f '${Package}=${Version} ' docker.io cryptsetup jq azure-cli python3 openssl git 2>/dev/null)"

  say "  encrypted work volume $RD ($IMG_SIZE, dm-crypt plain aes-xts-plain64, a random 512-bit key on stdin only)"
  volume_open && stop "$RD is already open (after a partial bootstrap: wipe, then bootstrap again)"
  bstop() { stop "$* (after a partial bootstrap: wipe, then bootstrap again)"; }
  mkdir -p "$(dirname "$IMG")"; fallocate -l "$IMG_SIZE" "$IMG" || bstop "fallocate failed"
  loop="$(losetup --find --show "$IMG")" || bstop "losetup failed"
  echo "$loop" > "$ST/loop"
  head -c 64 /dev/urandom | cryptsetup open --type plain --cipher aes-xts-plain64 --key-size 512 --key-file - \
    --keyfile-size 64 "$loop" "$MAPPER" || bstop "cryptsetup open failed"
  mkfs.ext4 -q -L rd "$DEVMAP/$MAPPER" || bstop "mkfs failed"
  mkdir -p "$RD"; mount "$DEVMAP/$MAPPER" "$RD" || bstop "mount failed"; chmod 700 "$RD"

  say "  Docker's data root and image store on $RD/docker"
  systemctl stop docker.socket docker >/dev/null 2>&1
  mkdir -p "$RD/docker" "$ETC/docker"
  # Without the containerd image store, images and layers live under the data root (Docker 29 defaults to containerd).
  printf '{"data-root": "%s/docker", "features": {"containerd-snapshotter": false}}\n' "$RD" > "$ETC/docker/daemon.json"
  systemctl start docker || bstop "docker did not start"
  [ "$(docker info --format '{{.DockerRootDir}}' 2>/dev/null)" = "$RD/docker" ] || bstop "Docker's data root is not $RD/docker"
  for img in "$POSTGIS" "$PSQL"; do
    docker pull -q "$img" >/dev/null || bstop "docker pull $img failed"
    docker image inspect -f '{{json .RepoDigests}}' "$img" | grep -qF "${img##*@}" || bstop "$img is not the pinned digest"
  done

  say "  repository at $REPO_COMMIT, drill scripts checked"
  git clone -q --no-checkout "$REPO_URL" "$RD/parkio" || bstop "git clone failed"
  git -C "$RD/parkio" -c advice.detachedHead=false checkout -q "$REPO_COMMIT" || bstop "checkout $REPO_COMMIT failed"
  [ "$(git -C "$RD/parkio" rev-parse HEAD)" = "$REPO_COMMIT" ] || bstop "HEAD is not $REPO_COMMIT"
  pins_ok || bstop "a drill script differs from its pin"

  grep -qE "[[:space:]]$host([[:space:]]|$)" "$ETC/hosts" || echo "127.0.1.1 $host" >> "$ETC/hosts"
  record bootstrap result=PASS host="$host" versions="$versions" volume="dm-crypt plain aes-xts-plain64 512, key not stored" \
    dockerRoot="$RD/docker" postgis="$POSTGIS" psql="$PSQL" repoCommit="$REPO_COMMIT" scriptsPinned=10
  say "U14 DRILL HOST bootstrap: PASS"
  ;;

pull)
  [ "$(state bootstrap result)" = PASS ] || stop "bootstrap has not passed"
  volume_open || stop "$RD is not open"
  [ -n "$ACCOUNT" ] && [ -n "$CONTAINER" ] || stop "pull needs --account and --container"
  [ "$(state pull result)" != PASS ] || stop "pull already passed"
  egress_storage_only || stop "outbound is wider than the storage phase ($OPEN): run u14-drill-azure.sh egress storage first"
  IFS= read -r -t 120 SAS || stop "no SAS on stdin (one line, within 120 s)"
  case "$SAS" in *sig=*se=*|*se=*sig=*) ;; *) SAS=""; stop "stdin does not hold a SAS (sig= and se= are required)" ;; esac
  mkdir -p "$RD/stamps" "$RD/work" "$RD/azcfg"
  for s in "$STAMP" ${LEDGER:+"$LEDGER"}; do
    [ -e "$RD/stamps/$s" ] && { SAS=""; stop "$RD/stamps/$s exists already"; }
    say "D3 pull $s"
    # az keeps its configuration and command logs (which could quote the SAS on an error) on the encrypted volume
    AZURE_CONFIG_DIR="$RD/azcfg" AZURE_CORE_COLLECT_TELEMETRY=false \
      BACKUP_OFFSITE_KIND=azure BACKUP_AZURE_STORAGE_ACCOUNT="$ACCOUNT" BACKUP_AZURE_CONTAINER="$CONTAINER" \
      BACKUP_AZURE_SAS_TOKEN="$SAS" "$RD/parkio/scripts/backup-offsite-pull.sh" --stamp "$s" --dest "$RD/stamps/$s" \
      >> "$RD/work/pull.log" 2>&1 </dev/null || { SAS=""; stop "backup-offsite-pull.sh failed for $s (log on $RD/work)"; }
  done
  SAS=""; unset SAS
  digest="$(sha256sum "$RD/stamps/$STAMP/SHA256SUMS" 2>/dev/null | cut -d' ' -f1)"
  seal="mismatch"
  if [ "$STAMP" = "$DATA_STAMP_DEFAULT" ]; then [ "$digest" = "$SEAL" ] && seal="equal"; else seal="no-seal-for-this-stamp"; fi
  [ "$seal" = equal ] || stop "the pulled SHA256SUMS digest is not run A's seal ($seal)"
  bytes="$(du -sb "$RD/stamps" | cut -f1)"
  record pull result=PASS stamp="$STAMP" ledgerStamp="$LEDGER" sha256sumsDigest="$digest" seal="$seal" bytes="$bytes"
  say "U14 DRILL HOST pull: PASS (SHA256SUMS digest equals run A's seal)"
  ;;

probe)
  if egress_denied; then
    record probe result=PASS checks="dns tcp https-github${ACCOUNT:+ https-storage}"
    say "U14 DRILL HOST probe: PASS (no outbound)"
  else
    record probe result=FAIL open="$OPEN"
    stop "outbound still works: $OPEN (run u14-drill-azure.sh egress deny first)"
  fi
  ;;

run)
  [ "$(state pull result)" = PASS ] || stop "pull has not passed"
  [ "$(state pull stamp)" = "$STAMP" ] || stop "the pulled stamp is $(state pull stamp), not $STAMP"
  [ "$(state pull ledgerStamp)" = "$LEDGER" ] || stop "the pulled ledger stamp is '$(state pull ledgerStamp)', not '$LEDGER'"
  [ "$(state run result)" = "" ] || stop "run already ran ($(state run result)); reset --yes first if a new run is intended"
  volume_open || stop "$RD is not open"
  [[ "$CUTOFF" =~ $STAMP_RE ]] || stop "--cutoff must be a stamp like 2026-10-14T03-30-00Z"
  [ "$CUTOFF" = "${LEDGER:-$STAMP}" ] || stop "--cutoff must be the newest pulled stamp (${LEDGER:-$STAMP})"
  [[ "$BLOB_SHA" =~ ^[0-9a-f]{64}$ ]] || stop "run needs --expected-blob-sha256: blobSha256 of the C1 escrow.json (DRILL-HOST-PREP.md)"
  { [ -e "$EV/B" ] || [ -e "$RD/work/B" ]; } && stop "a previous run left $EV/B or $RD/work/B: reset --yes first"
  [ "$NO_TMUX" = 1 ] || in_multiplexer || stop "start run inside tmux (or screen), so a dropped SSH session cannot stop it"
  egress_denied || stop "outbound works again ($OPEN); nothing is decrypted"
  pins_ok || stop "a drill script differs from its pin since the bootstrap"
  DRILL_ID="rd-$(date -u +%Y%m%d)-b"
  { exec 3<"$TTY_IN" 4>"$TTY_OUT"; } 2>/dev/null || stop "no terminal: run it in an interactive SSH session (ssh -t, then tmux)"
  t() { printf '%s\n' "$*" >&4; }

  # The escrow: up to 3 pastes and 3 typed passwords. Empty lines (a paste's trailing newline) are not passwords. A failure
  # here records no run: nothing was decrypted, and run can be started again.
  pp=""; pb=""; got_blob=""; pastes=0; pwtries=0; esc=$'\e'
  t ""; t "  The escrow: paste the saved escrow file (from the BEGIN line to the END line), then press Enter."
  while [ -z "$pp" ] && [ "$pastes" -lt 3 ] && [ "$pwtries" -lt 3 ]; do
    if [ -z "$pb" ]; then
      pastes=$((pastes + 1)); inblob=0; ended=0; n=0
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
        pb=""; t "  That paste had no 'blob:' part before the END line. Paste the whole file again."; continue
      fi
      got_blob="$(printf '%s' "$pb" | sha256sum | cut -d' ' -f1)"
      if [ "$got_blob" != "$BLOB_SHA" ]; then
        pb=""; t "  This is not the escrow escrow.json records (or --expected-blob-sha256 is mistyped). Paste the saved copy again."
        continue
      fi
    fi
    pw=""; blanks=0
    while [ -z "$pw" ] && [ "$blanks" -lt 5 ]; do
      printf '  Escrow password from your card (not shown): ' >&4
      IFS= read -r -s -t 900 pw <&3 || break
      printf '\n' >&4
      [ -z "$pw" ] && blanks=$((blanks + 1))
    done
    [ -n "$pw" ] || break
    pwtries=$((pwtries + 1))
    text="$(printf '%s\n' "$pb" | fold -w 64 | U14H_PW="$pw" openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 \
      -iter "$ESCROW_ITER" -a -pass env:U14H_PW 2>/dev/null)"
    pw=""
    if [ "${text%%$'\n'*}" = "$ESCROW_FORMAT" ] && [ "${text#*$'\n'}" != "$text" ] && [ -n "${text#*$'\n'}" ]; then
      pp="${text#*$'\n'}"
    else
      t "  The escrow did not open: a wrong password, or a damaged copy. Try again."
    fi
    text=""
  done
  pb=""
  exec 3<&- 4>&-
  if [ -z "$pp" ]; then
    python3 -I - "$ST/escrow-attempts.log" "$pastes" "$pwtries" "${got_blob:-none}" "$BLOB_SHA" <<'PY'
import sys, time
p, pastes, pwtries, got, want = sys.argv[1:6]
open(p, "a").write(f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} pastes={pastes} passwords={pwtries} "
                   f"blobMatches={'yes' if got == want else 'no'}\n")
PY
    stop "the escrow did not open (no run is recorded; start run again)"
  fi
  say "D4 the escrow opened (blob SHA-256 equals escrow.json's; pastes $pastes, passwords $pwtries); the passphrase is held in memory only"

  printf '%s\n' PARKIO_EMAIL_PROVIDER=logging PARKIO_WAITLIST_EMAIL_PROVIDER=logging PARKIO_PUSH_DELIVERY_PROVIDER=noop \
    > "$RD/drill.env"
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create --internal "$NET" >/dev/null || { pp=""; stop "network $NET"; }
  docker run -d --name "$TARGET" --network "$NET" --memory 1500m -e POSTGRES_USER=rd_admin \
    -e POSTGRES_PASSWORD="dummy-$(openssl rand -hex 12)" -e POSTGRES_DB=rd_admin "$POSTGIS" >/dev/null || { pp=""; stop "target container"; }
  for _ in $(seq 1 60); do docker exec "$TARGET" pg_isready -q -U rd_admin -d rd_admin 2>/dev/null && break; sleep 2; done
  docker exec "$TARGET" pg_isready -q -U rd_admin -d rd_admin 2>/dev/null || { pp=""; stop "the target did not become ready"; }

  say "D4 backup-dated-acceptance.sh --pulled (outcomes 3-5), drill $DRILL_ID, target $TARGET on --internal $NET"
  mkdir -p "$RD/work"                         # the acceptance itself creates $EV/B and $RD/work/B (it refuses existing ones)
  ( export BACKUP_ENCRYPT_PASSPHRASE="$pp" PARKIO_DRILL_ID="$DRILL_ID" PARKIO_RESTORE_PSQL_IMAGE="$PSQL" \
      PARKIO_RESTORE_PSQL_NETWORK="$NET" PARKIO_RESTORE_PSQL_HOST="$TARGET"
    exec "$RD/parkio/scripts/backup-dated-acceptance.sh" --pulled "$RD/stamps/$STAMP" --expected-sha256sums "$SEAL" \
      --evidence "$EV/B" --work "$RD/work/B" --decrypt --isolated-restore --drill-env-file "$RD/drill.env" \
      -- --recovery-cutoff "$CUTOFF" --container "$TARGET" --probe-default-egress \
      ${LEDGER:+--ledger-stamp "$RD/stamps/$LEDGER"} "${EXTRA[@]}" ) </dev/null
  rc=$?
  pp=""; unset pp
  read -r r3 r4 r5 outcomes < <(python3 -I -c 'import json,sys
try:
    a = json.load(open(sys.argv[1])); o = {x["id"]: x["result"] for x in a["outcomes"]}
    print(o.get("remote-integrity", "?"), o.get("decrypt", "?"), o.get("isolated-restore", "?"),
          " ".join(f"{k}={v}" for k, v in o.items()) + " verdict=" + str(a.get("verdict")))
except Exception:
    print("? ? ? unreadable")' "$EV/B/acceptance.json")
  if [ "$r3" = PASS ] && [ "$r4" = PASS ] && [ "$r5" = PASS ]; then result=PASS
  elif [ "$r3" = FAIL ] || [ "$r4" = FAIL ] || [ "$r5" = FAIL ] || { [ "$rc" != 0 ] && [ "$rc" != 4 ]; }; then result=FAIL
  else result=INCOMPLETE; fi
  record run result="$result" exit="$rc" outcomes="$outcomes" drillId="$DRILL_ID" blobSha256="$got_blob" pastes="$pastes" \
    passwords="$pwtries" cutoff="$CUTOFF" target="$TARGET" network="$NET"
  say "acceptance exit $rc: $outcomes"
  case "$result" in
    PASS) say "U14 DRILL HOST run: PASS (outcomes 3, 4 and 5 PASS); combine with run A for the verdict" ;;
    INCOMPLETE) stop "INCOMPLETE: outcomes 3-5 are $r3/$r4/$r5 (NOT_RUN or BLOCKED is not a PASS); see $EV/B" ;;
    *) stop "the acceptance reported a FAIL or could not run (exit $rc; $r3/$r4/$r5)" ;;
  esac
  ;;

reset)
  [ "$YES" = 1 ] || stop "reset removes the target and moves the run's evidence and work aside, for a new run: pass --yes"
  if pgrep -f 'backup-dated-acceptance\.sh|restore-drill-01\.sh' >/dev/null 2>&1; then stop "an acceptance is still running"; fi
  prev="$(state run result)"; when="$(date -u +%Y%m%dT%H%M%SZ)"
  docker rm -f "$TARGET" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
  [ -e "$EV/B" ] && mv "$EV/B" "$EV/B-reset-$when"
  [ -e "$RD/work/B" ] && mv "$RD/work/B" "$RD/work/B-reset-$when"
  [ -e "$ST/run.json" ] && mv "$ST/run.json" "$ST/run-reset-$when.json"
  [ -e "$EV/run.json" ] && mv "$EV/run.json" "$EV/run-reset-$when.json"
  rm -f "$RD/drill.env"
  record reset result=PASS previousRun="${prev:-none}" movedAside="B-reset-$when"
  say "U14 DRILL HOST reset: PASS (previous run ${prev:-none}; its evidence kept as B-reset-$when); run may start again"
  ;;

evidence)
  [ -d "$EV" ] || stop "no evidence directory"
  if grep -rlE -e '-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW' -e '(^|[?&])sig=[A-Za-z0-9%]+' "$EV" >/dev/null 2>&1; then
    stop "the evidence holds escrow or SAS text; nothing is packed"
  fi
  (cd "$EV" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS)
  tar -C "$(dirname "$EV")" -czf "$EV.tgz" "$(basename "$EV")" || stop "tar failed"
  sum="$(sha256sum "$EV.tgz" | cut -d' ' -f1)"
  if [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ] && [ -d "/home/$SUDO_USER" ]; then
    install -m 0644 "$EV.tgz" "/home/$SUDO_USER/rd-evidence.tgz" && say "  copied for scp: /home/$SUDO_USER/rd-evidence.tgz"
  fi
  say "U14 DRILL HOST evidence: $EV.tgz sha256 $sum ($(wc -l < "$EV/SHA256SUMS") files)"
  ;;

wipe)
  docker rm -f "$TARGET" >/dev/null 2>&1; docker network rm "$NET" >/dev/null 2>&1
  systemctl stop docker.socket docker >/dev/null 2>&1
  if mountpoint -q "$RD"; then umount "$RD" || stop "umount $RD failed"; fi
  if [ -e "$DEVMAP/$MAPPER" ]; then cryptsetup close "$MAPPER" || stop "cryptsetup close failed"; fi
  [ -s "$ST/loop" ] && losetup -d "$(cat "$ST/loop")" 2>/dev/null
  rm -f "$IMG" "$ST/loop"
  volume_open && stop "the volume is still open"
  record wipe result=PASS volume="closed; key gone; backing file removed"
  say "U14 DRILL HOST wipe: PASS (the VM is deleted next: u14-drill-azure.sh down)"
  ;;
esac
exit 0
