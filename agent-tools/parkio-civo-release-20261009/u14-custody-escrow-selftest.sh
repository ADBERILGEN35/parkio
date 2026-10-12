#!/usr/bin/env bash
# Local self-test of u14-custody-escrow.sh. The "live checkout" holds the bf9cad51 backup library and an env file whose
# BACKUP_ENCRYPT_PASSPHRASE is a synthetic value with spaces, quotes, '$' and a backslash; the caller's environment carries a
# wrong passphrase, so a pass shows that the escrow holds the cron job's value. Every prompt is answered through a real
# pseudo-terminal by a small expect-like driver, which also scans every process's command line for the secrets while the
# tool runs.
# K1 create: password twice, save, paste the saved copy, password from the card -> MATCH, exit 0, record PASS, the pasted blob
#    is the created one; the documented openssl command opens the saved copy to "parkio-escrow-v1\n<passphrase>" exactly;
# K2 create: entries differ, then too short, then accepted -> MATCH; K3 create: differ, short, the passphrase itself -> STOP;
# K4 create: a wrong card password, then the right one -> MATCH after 2 attempts; K5 create: a copy missing one line, three
#    times -> FAIL, exit 1, "not the escrow file printed above"; K6 verify with K1's saved copy -> CONFIRMED;
# K7 verify with an escrow of another passphrase -> exit 1; K8 a paste without the blob part is refused and asked again;
# K9 no passphrase in the env file, K10 a second backup job -> STOP before any prompt;
# K11 no secret in any output, record or process command line; the blob is not in the record; record 700/600;
# K12 the cron reader is byte-identical to P5b's; the source passes no secret on a command line.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u14-custody-escrow.sh"; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
W="$(mktemp -d)"; trap 'chmod -R u+rwX "$W" 2>/dev/null; rm -rf "${W:?}"' EXIT
PASS="synthetic pass \$HOME \"q\" \\x-$(openssl rand -hex 6)"
PW="correct horse battery staple $(openssl rand -hex 3)"
WRONG="not-the-key-$(openssl rand -hex 4)"
L="$W/live"
mkdir -p "$L/scripts/lib" "$L/docker" "$W/out" "$W/home"
git -C "$REPO" show bf9cad51:scripts/lib/backup-common.sh > "$L/scripts/lib/backup-common.sh"
envfile() {  # [omit the passphrase]
  { echo "BACKUP_DIR=$W/backups"; echo "BACKUP_OFFSITE_KIND=azure"
    [ -n "${1:-}" ] || printf "BACKUP_ENCRYPT_PASSPHRASE='%s'\n" "$PASS"
    echo "PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta"; } > "$L/docker/.env.azure-hosted-beta"
}
JOB="30 3 * * * root flock -n $W/backup.lock $W/run-production-backup.sh >> $W/backup.log 2>&1"
cronfile() {  # [extra line after the job]
  { echo "# parkio backup (test)"; echo "SHELL=/bin/bash"; echo "PATH=/usr/bin:/bin"; echo 'MAILTO=""'
    echo "$JOB"; [ -n "${1:-}" ] && printf '%s\n' "$1"; } > "$W/parkio-backup"
}
cat > "$W/drive.py" <<'PY'
import os, pty, re, select, sys, threading, time
tool, mode, out_path, scenario = sys.argv[1:5]
W, PASS, PW, WRONG = os.environ["T_W"], os.environ["T_PASS"], os.environ["T_PW"], os.environ["T_WRONG"]
secrets = [s.encode() for s in (PASS, PW, WRONG)]
hits, done = [], [False]
def scan():
    me = os.getpid()
    while not done[0]:
        for d in os.listdir("/proc"):
            if not d.isdigit() or int(d) == me:
                continue
            try:
                with open(f"/proc/{d}/cmdline", "rb") as f:
                    c = f.read()
            except OSError:
                continue
            if any(s in c for s in secrets):
                hits.append(d)
        time.sleep(0.02)
threading.Thread(target=scan, daemon=True).start()
pid, fd = pty.fork()
if pid == 0:
    os.execvp("bash", ["bash", tool, mode])
buf, pos = b"", [0]
def pump(t=0.2):
    global buf
    r, _, _ = select.select([fd], [], [], t)
    if not r:
        return True
    try:
        data = os.read(fd, 65536)
    except OSError:
        return False
    if not data:
        return False
    buf += data
    return True
def expect(pat, t=90):
    end = time.time() + t
    while True:
        m = re.compile(pat.encode()).search(buf, pos[0])
        if m:
            pos[0] = m.end()
            return m
        if time.time() > end or not pump():
            raise RuntimeError(f"no {pat!r}")
def send(s):
    os.write(fd, s.encode())
    time.sleep(0.2)
def saved_block():
    m = re.search(rb"-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----.*?-----END PARKIO BACKUP PASSPHRASE ESCROW-----", buf, re.S)
    return m.group(0).decode().replace("\r", "")
def paste(text):
    for line in text.split("\n"):
        send(line + "\n")
def password(a, b=None):
    expect(r"Escrow password: "); send(a + "\n")
    expect(r"The same again: "); send((a if b is None else b) + "\n")
def create_save():
    expect(r"Press Enter when BOTH copies are saved: ")
    block = saved_block()
    open(f"{W}/saved.txt", "w").write(block + "\n")
    send("\n")
    return block
def check(block, p):
    expect(r"paste it here and press Enter|Try again\.|Paste the whole saved text again\.")
    paste(block)
    expect(r"Escrow password from your card \(not shown\): ")
    send(p + "\n")
try:
    if scenario == "k1":
        password(PW); block = create_save(); check(block, PW)
    elif scenario == "k2":
        password(PW, PW + "x"); password("too short"); password(PW); block = create_save(); check(block, PW)
    elif scenario == "k3":
        password(PW, PW + "x"); password("too short"); password(PASS)
    elif scenario == "k4":
        password(PW); block = create_save(); check(block, "wrong card password value"); check(block, PW)
    elif scenario == "k5":
        password(PW); block = create_save()
        lines = block.split("\n"); i = lines.index("blob:") + 1; broken = "\n".join(lines[:i] + lines[i + 1:])
        for _ in range(3):
            check(broken, PW)
    elif scenario == "k6":
        check(open(f"{W}/saved.txt").read().rstrip("\n"), PW)
    elif scenario == "k7":
        for _ in range(3):
            check(open(f"{W}/other.txt").read().rstrip("\n"), PW)
    elif scenario == "k8":
        expect(r"paste it here and press Enter")
        paste("-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----\nformat: x\n-----END PARKIO BACKUP PASSPHRASE ESCROW-----")
        expect(r"Paste the whole saved text again\.")
        paste(open(f"{W}/saved.txt").read().rstrip("\n"))
        expect(r"Escrow password from your card \(not shown\): "); send(PW + "\n")
finally:
    deadline = time.time() + 120
    while time.time() < deadline and pump(0.5):
        pass
    if time.time() >= deadline:
        os.kill(pid, 9)
    try:
        _, status = os.waitpid(pid, 0)
        code = os.waitstatus_to_exitcode(status)
    except ChildProcessError:
        code = -1
    done[0] = True
    open(out_path, "w").write(buf.decode(errors="replace").replace("\r", "") + f"\ndriver exit={code}\nargv hits={len(hits)}\n")
PY
export U14C_TEST=1 U14C_LIVE="$L" U14C_OUT="$W/out" U14C_OWNER="$(id -un)" U14C_CRON_FILE="$W/parkio-backup" \
  U14C_LOCK="$W/backup.lock" U14C_ENTRY="$W/run-production-backup.sh" U14C_LOG="$W/backup.log" U14C_HOME="$W/home" \
  U14C_LOGNAME="$(id -un)"
export T_W="$W" T_PASS="$PASS" T_PW="$PW" T_WRONG="$WRONG"
# the caller's environment carries another passphrase; only the cron job's environment may reach the escrow
export BACKUP_ENCRYPT_PASSPHRASE="$WRONG" PARKIO_ENV_FILE="$W/nowhere.env"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasE() { if grep -qE -- "$2" "$W/$1"; then ok "$1: /$2/"; else no "$1: /$2/"; fi; }
hasnt() { if grep -qF -- "$2" "$W/$1"; then no "$1: unexpected $2"; else ok "$1: no $2"; fi; }
drive() { python3 -I "$W/drive.py" "$TOOL" "$2" "$W/$1" "$3" >/dev/null 2>"$W/$1.driver.err" || no "$1: driver: $(tail -1 "$W/$1.driver.err")"; }
rec() { ls -1d "$W"/out/u14/custody-*-"$1" 2>/dev/null | tail -1; }
clean() {
  if grep -r -q -F -e "$PASS" -e "$PW" -e "$WRONG" "$W/$1" "$W/out" 2>/dev/null; then no "$1: a secret reached the output or a record"
  else ok "$1: no secret in the output or the records"; fi
  has "$1" "argv hits=0"
}
envfile; cronfile

drive k1.out create k1; sed -e "s#$W#<W>#g" -e "s#$(hostname)#<host>#g" "$W/k1.out"
has k1.out "driver exit=0"; has k1.out "U14 CUSTODY ESTABLISHED"; has k1.out "round trip PASS"
hasE k1.out "saved copy: MATCH after 1 attempt\(s\); pasted blob sha256 [0-9a-f]{64}; it is the blob created now: yes"
has k1.out "BACKUP_ENCRYPT_PASSPHRASE of the backup job's environment: set (value not shown, not changed)"
E="$(rec create)"
[ -n "$E" ] && [ "$(stat -c %a "$E")" = 700 ] && [ "$(stat -c %a "$E/escrow.json")" = 600 ] && ok "k1: record dir 700, file 600" || no "k1 record modes"
python3 -I - "$E/escrow.json" <<'PY' && ok "k1: escrow.json fields" || no "k1 escrow.json"
import json, re, sys
r = json.load(open(sys.argv[1]))
assert r["result"] == "PASS" and r["mode"] == "create" and r["roundTrip"] == "PASS" and r["savedCopy"] == "MATCH"
assert r["savedIsCreated"] is True and r["iterations"] == 600000 and r["format"] == "parkio-escrow-v1"
assert re.fullmatch(r"[0-9a-f]{64}", r["blobSha256"]) and r["blobSha256"] == r["savedBlobSha256"] and r["attempts"] == 1
PY
B64="$(sed -n '/^blob:$/,/^-----END/p' "$W/saved.txt" | sed '1d;$d' | tr -d '\n')"
[ -n "$B64" ] && ! grep -r -q -F -- "${B64:0:40}" "$W/out" && ok "k1: the blob is not in the record" || no "k1: blob in the record"
[ "$(printf '%s' "$B64" | sha256sum | cut -d' ' -f1)" = "$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["blobSha256"])' "$E/escrow.json")" ] \
  && ok "k1: the recorded blob sha256 is the saved blob's" || no "k1 blob sha"
# the documented recovery: the cipher line of the saved copy, -d, the password from the card
sed -n '/^blob:$/,/^-----END/p' "$W/saved.txt" | sed '1d;$d' \
  | ESCROW_PASSWORD="$PW" openssl enc -d -aes-256-cbc -pbkdf2 -md sha256 -iter 600000 -a -pass env:ESCROW_PASSWORD > "$W/opened.bin" 2>/dev/null
python3 -I - "$W/opened.bin" <<'PY' && ok "k1: openssl opens the saved copy to 'parkio-escrow-v1\\n' + the cron job's passphrase, byte for byte" || no "k1 independent decrypt"
import os, sys
assert open(sys.argv[1], "rb").read() == b"parkio-escrow-v1\n" + os.environ["T_PASS"].encode()
PY
rm -f "$W/opened.bin"; clean k1.out

drive k2.out create k2
has k2.out "The two entries differ; try again."; has k2.out "It has fewer than 20 characters; try again."
has k2.out "U14 CUSTODY ESTABLISHED"; has k2.out "driver exit=0"; clean k2.out
n="$(ls -1d "$W"/out/u14/custody-*-create | wc -l)"
drive k3.out create k3
has k3.out "That is the backup passphrase itself; choose a new password and try again."
has k3.out "STOP: no escrow password was accepted (three tries)"; has k3.out "driver exit=1"; hasnt k3.out "BEGIN PARKIO"
[ "$(ls -1d "$W"/out/u14/custody-*-create | wc -l)" = "$n" ] && ok "k3: no record" || no "k3 wrote a record"; clean k3.out
sleep 1; drive k4.out create k4
has k4.out "No match: a wrong password, a damaged copy, or an escrow of another passphrase. Try again."
has k4.out "saved copy: MATCH after 2 attempt(s)"; has k4.out "U14 CUSTODY ESTABLISHED"; clean k4.out
sleep 1; drive k5.out create k5
has k5.out "No match: the pasted text is not the escrow file printed above (an incomplete copy?). Try again."
has k5.out "saved copy: MISMATCH after 3 attempt(s)"; has k5.out "it is the blob created now: no"
has k5.out "U14 CUSTODY NOT ESTABLISHED"; has k5.out "driver exit=1"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"]=="FAIL" and r["savedCopy"]=="MISMATCH"' "$(rec create)/escrow.json" \
  && ok "k5: record result FAIL" || no "k5 record"; clean k5.out
# K1's saved copy is still the one saved by the owner (K2-K5 overwrote saved.txt; restore K1's from its output)
sed -n '/^-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----$/,/^-----END PARKIO BACKUP PASSPHRASE ESCROW-----$/{p;/^-----END/q}' "$W/k1.out" > "$W/saved.txt"
drive k6.out verify k6
has k6.out "U14 CUSTODY CONFIRMED"; has k6.out "saved copy: MATCH after 1 attempt(s)"; hasnt k6.out "it is the blob created now"
has k6.out "driver exit=0"; clean k6.out
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["result"]=="PASS" and r["mode"]=="verify" and "blobSha256" not in r' "$(rec verify)/escrow.json" \
  && ok "k6: verify record" || no "k6 record"
{ echo "-----BEGIN PARKIO BACKUP PASSPHRASE ESCROW-----"; echo "format: parkio-escrow-v1"; echo "blob:"
  printf 'parkio-escrow-v1\nan-older-passphrase' | ESCROW_PASSWORD="$PW" openssl enc -e -aes-256-cbc -pbkdf2 -md sha256 -iter 600000 -salt -a -pass env:ESCROW_PASSWORD
  echo "-----END PARKIO BACKUP PASSPHRASE ESCROW-----"; } > "$W/other.txt"
sleep 1; drive k7.out verify k7
has k7.out "saved copy: MISMATCH after 3 attempt(s)"; has k7.out "U14 CUSTODY NOT ESTABLISHED"; has k7.out "driver exit=1"; clean k7.out
sleep 1; drive k8.out verify k8
has k8.out "That paste had no 'blob:' part before the END line (or no END line). Paste the whole saved text again."
has k8.out "saved copy: MATCH after 2 attempt(s)"; has k8.out "U14 CUSTODY CONFIRMED"; clean k8.out
n="$(find "$W/out" -name escrow.json | wc -l)"
envfile omit; drive k9.out create none
has k9.out "STOP: the backup job's environment has no BACKUP_ENCRYPT_PASSPHRASE"; has k9.out "driver exit=1"; hasnt k9.out "Escrow password"
envfile; cronfile "45 4 * * * root $W/run-production-backup.sh"; drive k10.out create none
has k10.out "job line(s) for the backup entry point (want exactly 1)"; has k10.out "driver exit=1"; hasnt k10.out "Escrow password"
cronfile
[ "$(find "$W/out" -name escrow.json | wc -l)" = "$n" ] && ok "k9-k10: no record" || no "k9-k10 wrote a record"
a="$(sed -n "/^read -r -d '' CRON_AWK <<'AWK'$/,/^AWK$/p" "$TOOL")"; b="$(sed -n "/^read -r -d '' CRON_AWK <<'AWK'$/,/^AWK$/p" "$HERE/p5b-backup-verify.sh")"
[ -n "$a" ] && [ "$a" = "$b" ] && ok "k12: the cron reader is byte-identical to P5b's" || no "k12 cron reader differs"
if grep -n -E -- '-pass (pass|file|fd|stdin)|-k |-kfile|set -x|echo "\$\{?BACKUP_ENCRYPT_PASSPHRASE|printf .*%s.*\$\{?pw\}?"? *>&4' "$TOOL" >/dev/null; then
  no "k12: a secret may reach a command line or the terminal"; else ok "k12: openssl reads every secret from -pass env: only"; fi
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
