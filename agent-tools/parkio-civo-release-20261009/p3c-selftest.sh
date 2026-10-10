#!/usr/bin/env bash
# Local self-test of p3c-expo-push-env.py (P3c). The env file is built from the release's
# docker/.env.azure-hosted-beta.example with synthetic secrets, provider noop, an empty Expo token, no
# PARKIO_PUSH_DELIVERY_ENABLED line and no final newline, so the release's REAL preflight-hosted-beta.sh fails exactly
# on PARKIO_EXPO_ACCESS_TOKEN and PARKIO_PUSH_DELIVERY_PROVIDER, like the host. The live checkout is a dirty git
# repository that ignores .env.*; the release checkout holds the real preflight and a small Compose model wired like
# docker-compose.apps.yml; a running notification-service container must stay untouched. The tool runs under a
# pseudo-terminal and answers its hidden prompts like a person pasting a token.
# Scenarios: S0 no terminal, S1 entries differ, S2 entry equals another secret, S3 placeholder, S4 unknown argument
# -> all stop with the file unchanged and no backup; S5 normal run -> PASS; S6 re-run -> stops (token set);
# S7 --replace-token -> PASS with a second backup. Pass = expected lines and files, no token or other secret in any
# transcript or report, live git status and the container unchanged.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/p3c-expo-push-env.py"
PIN=22f9699038cdae15ffff5dd7433c4a64aed0a951; REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
P=p3cprobe; NAME=p3cprobe-notification; W="$(mktemp -d)"
ids="$(docker ps -aq --filter "name=^$NAME$" 2>/dev/null | grep -E '^[0-9a-f]{12,64}$' || true)"
[ -z "$ids" ] || { echo "container $NAME exists"; rm -rf "$W"; exit 2; }
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$W"; }
trap cleanup EXIT
L="$W/live"; R="$W/release"; mkdir -p "$L/docker" "$R/docker" "$R/scripts"
ENV="$L/docker/.env.azure-hosted-beta"
git -C "$REPO" show "$PIN:docker/.env.azure-hosted-beta.example" > "$W/example.env"
python3 - "$W/example.env" "$ENV" <<'PY'
import base64, os, re, secrets, sys
pat = re.compile(r'CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000', re.I)
out = []
for line in open(sys.argv[1], encoding="utf-8").read().splitlines():
    m = re.match(r'^([A-Z0-9_]+)=(.*)$', line)
    key = m.group(1) if m else None
    if key == "PARKIO_PUSH_DELIVERY_PROVIDER":
        line = "PARKIO_PUSH_DELIVERY_PROVIDER=noop"
    elif key == "PARKIO_EXPO_ACCESS_TOKEN":
        line = "PARKIO_EXPO_ACCESS_TOKEN="
    elif key == "KAFKA_CLUSTER_ID":
        line = "KAFKA_CLUSTER_ID=" + base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")
    elif key == "PARKIO_JWT_PRIVATE_KEY_PEM":
        body = base64.b64encode(secrets.token_bytes(48)).decode()
        line = 'PARKIO_JWT_PRIVATE_KEY_PEM="-----BEGIN PRIVATE KEY-----\\n' + body + '\\n-----END PRIVATE KEY-----"'
    elif key == "PARKIO_ACME_EMAIL":
        line = "PARKIO_ACME_EMAIL=ops@selftest.parkio.invalid"
    elif m and pat.search(m.group(2)):
        line = f"{key}={'re_' if key == 'PARKIO_RESEND_API_KEY' else ''}Synth{secrets.token_hex(24)}"
    out.append(line)
fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as fh:
    fh.write("\n".join(out))          # no final newline on purpose
PY
grep -q '^PARKIO_PUSH_DELIVERY_ENABLED=' "$ENV" && { echo "fixture already has ENABLED"; exit 2; } || true
printf '.env\n.env.*\n' > "$L/.gitignore"; echo tracked > "$L/README"
git -C "$L" init -q && git -C "$L" add -A && git -C "$L" -c user.name=t -c user.email=t@t commit -qm live
echo dirty >> "$L/README"; echo new > "$L/untracked.txt"
git -C "$REPO" show "$PIN:scripts/preflight-hosted-beta.sh" > "$R/scripts/preflight-hosted-beta.sh"
printf 'docker/docker-compose.yml\ndocker/docker-compose.azure-hosted-beta.yml\n' > "$R/docker/compose.production.files"
cat > "$R/docker/docker-compose.yml" <<'Y'
name: p3cprobe
services:
  notification-service:
    image: busybox:latest
    environment:
      PARKIO_PUSH_DELIVERY_ENABLED: ${PARKIO_PUSH_DELIVERY_ENABLED:-true}
      PARKIO_PUSH_DELIVERY_PROVIDER: ${PARKIO_PUSH_DELIVERY_PROVIDER:-noop}
      PARKIO_EXPO_ACCESS_TOKEN: ${PARKIO_EXPO_ACCESS_TOKEN:-}
  gateway-service:
    image: busybox:latest
    environment: {SECRET: "${PARKIO_GATEWAY_INTERNAL_SECRET}", HMAC: "${PARKIO_LOGIN_THROTTLE_HMAC_KEY}"}
  user-service: {image: "busybox:latest"}
Y
printf 'services: {}\n' > "$R/docker/docker-compose.azure-hosted-beta.yml"
printf 'services: {}\n' > "$R/docker/docker-compose.civo-alertmanager.yml"
git -C "$R" init -q && git -C "$R" add -A && git -C "$R" -c user.name=t -c user.email=t@t commit -qm release
docker run -d --name "$NAME" --label "com.docker.compose.project=$P" --label "com.docker.compose.service=notification-service" \
  --network none busybox:latest sleep 3600 >/dev/null
cat > "$W/drive.py" <<'PY'
import os, pty, select, sys, time
out_path, answers, cmd = sys.argv[1], [a for a in sys.argv[2].split("|") if a], sys.argv[4:]
pid, fd = pty.fork()
if pid == 0:
    os.execvp(cmd[0], cmd)
buf, sent, deadline = b"", 0, time.time() + 600
while time.time() < deadline:
    r, _, _ = select.select([fd], [], [], 1.0)
    if fd not in r:
        continue
    try:
        chunk = os.read(fd, 4096)
    except OSError:
        break
    if not chunk:
        break
    buf += chunk
    while sent < len(answers) and buf.count(b"(hidden): ") > sent:
        time.sleep(0.3)
        os.write(fd, answers[sent].encode() + b"\n")
        sent += 1
_, status = os.waitpid(pid, 0)
open(out_path, "wb").write(buf)
sys.exit(os.waitstatus_to_exitcode(status))
PY
export P3C_LIVE="$L" P3C_RELEASE="$R" P3C_ENV_FILE="$ENV" P3C_PROJECT="$P" P3C_OUT="$W/out" P3C_PIN_ANY=1
drive() {  # name answers [extra args]
  local name="$1" answers="$2"; shift 2
  python3 "$W/drive.py" "$W/$name.out" "$answers" -- python3 -I "$TOOL" "$@" && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"
  tr -d '\r' < "$W/$name.out" > "$W/$name.txt"
}
fail=0
ok() { echo "ok   $1"; }
bad() { echo "FAIL $1"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1.txt"; then ok "$1: $2"; else bad "$1: $2"; fi; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || bad "$1 exit $(cat "$W/$1.rc"), want $2"; }
pf() { { (cd "$R" && sh scripts/preflight-hosted-beta.sh --env-file "$ENV" --deployment-profile azure-hosted-beta \
         --skip-compose 2>&1) || true; } | grep -E '^ +(FAIL|WARN) |=== PREFLIGHT' | sed -E 's/^ +(FAIL|WARN) ([^: ]+).*/\1 \2/; s/ — / - /'; }
pf > "$W/pf-before.txt"
grep -qx 'FAIL PARKIO_EXPO_ACCESS_TOKEN' "$W/pf-before.txt" && grep -qx 'FAIL PARKIO_PUSH_DELIVERY_PROVIDER' "$W/pf-before.txt" \
  && [ "$(grep -c '^FAIL ' "$W/pf-before.txt")" = 2 ] && ok "real preflight before: exactly the two host FAILs" \
  || { bad "real preflight before"; cat "$W/pf-before.txt"; }
cp -p "$ENV" "$W/env-original"; git -C "$L" status --porcelain > "$W/live0"
CID0="$(docker inspect --format '{{.Id}} {{.State.StartedAt}}' "$NAME")"
HMAC="$(sed -n 's/^PARKIO_LOGIN_THROTTLE_HMAC_KEY=//p' "$ENV")"
T1=ExpoSelftestTokenA1b2C3d4E5f6G7h8I9j0kL
T2=ExpoSelftestTokenZ9y8X7w6V5u4T3s2R1q0pO
unchanged() { cmp -s "$ENV" "$W/env-original" && [ -z "$(ls -A "$L/docker" | grep '\.bak-' || true)" ] \
              && ok "$1: env file unchanged, no backup" || bad "$1: env file changed or backup left"; }
# S0 no terminal
python3 -I "$TOOL" < /dev/null > "$W/s0.txt" 2>&1 && echo 0 > "$W/s0.rc" || echo $? > "$W/s0.rc"
rc_is s0 1; check s0 "STOP: standard input is not a terminal"; unchanged s0
# S1 entries differ
drive s1 "$T1|${T1}x"; rc_is s1 1; check s1 "STOP: the two entries differ"; unchanged s1
# S2 entry equals another secret
drive s2 "$HMAC|$HMAC"; rc_is s2 1; check s2 "STOP: the entry equals the value of PARKIO_LOGIN_THROTTLE_HMAC_KEY"; unchanged s2
# S3 placeholder-like entry
drive s3 "REPLACE_ME_expo_token_value_12345|REPLACE_ME_expo_token_value_12345"; rc_is s3 1
check s3 "STOP: the entry looks like a placeholder"; unchanged s3
# S4 a token typed as an argument is refused and not echoed
drive s4 "" "$T1"; rc_is s4 1; check s4 "1 unknown argument(s), not shown"; unchanged s4
# S5 normal run
drive s5 "$T1|$T1"; rc_is s5 0
sed "s#$W#<W>#g" "$W/s5.txt"
check s5 "PARKIO_PUSH_DELIVERY_PROVIDER: noop"
check s5 "PARKIO_PUSH_DELIVERY_ENABLED: absent"
check s5 "PARKIO_EXPO_ACCESS_TOKEN: empty"
check s5 "entries match; shape accepted"
check s5 "identical content, mode 600, same owner/group"
check s5 "rewritten in place: PARKIO_PUSH_DELIVERY_PROVIDER, PARKIO_EXPO_ACCESS_TOKEN; appended: PARKIO_PUSH_DELIVERY_ENABLED"
check s5 "PARKIO_PUSH_DELIVERY_PROVIDER: expo; PARKIO_PUSH_DELIVERY_ENABLED: true; PARKIO_EXPO_ACCESS_TOKEN: set, equals the entered value"
check s5 "PASS env file holds exactly the intended change"
check s5 "PASS live checkout git status unchanged: 2 changes"
check s5 "PASS preflight (azure-hosted-beta, env validation): exit 0; === PREFLIGHT: PASS"
check s5 "WARN PARKIO_AI_VISION_PROVIDER"
check s5 "PASS release model gives notification-service the intended settings: PARKIO_PUSH_DELIVERY_PROVIDER expo; PARKIO_PUSH_DELIVERY_ENABLED true; PARKIO_EXPO_ACCESS_TOKEN equals the entered value"
check s5 "PASS only notification-service's release config changes: changed: notification-service"
check s5 "PASS running notification-service untouched: same container, same start time"
check s5 "P3c COMPLETE"
python3 - "$W/env-original" "$ENV" "$T1" <<'PY' && ok "env: three keys set, every other line byte-identical, one appended block" || bad "env content"
import sys
old = open(sys.argv[1], "rb").read().splitlines(keepends=True)
new = open(sys.argv[2], "rb").read().splitlines(keepends=True)
keys = (b"PARKIO_PUSH_DELIVERY_PROVIDER=", b"PARKIO_PUSH_DELIVERY_ENABLED=", b"PARKIO_EXPO_ACCESS_TOKEN=")
for i, line in enumerate(old):
    if line.startswith(keys):
        continue
    want = line if i < len(old) - 1 else line + b"\n"
    assert new[i] == want, i
assert new[len(old)] == b"# Expo push delivery (owner decision 2026-10-10, P3c)\n"
assert new[len(old) + 1] == b"PARKIO_PUSH_DELIVERY_ENABLED=true\n" and len(new) == len(old) + 2
vals = {l.split(b"=", 1)[0]: l.split(b"=", 1)[1].rstrip(b"\n") for l in new if b"=" in l and not l.startswith(b"#")}
assert vals[b"PARKIO_PUSH_DELIVERY_PROVIDER"] == b"expo" and vals[b"PARKIO_EXPO_ACCESS_TOKEN"] == sys.argv[3].encode()
PY
BAK1="$(ls -A "$L/docker" | grep '\.bak-' | head -1)"
cmp -s "$L/docker/$BAK1" "$W/env-original" && [ "$(stat -c %a "$L/docker/$BAK1")" = 600 ] && ok "backup equals the original, mode 600" || bad "backup"
[ "$(stat -c %a "$ENV")" = 600 ] && ok "env file mode 600" || bad "env file mode"
git -C "$L" status --porcelain | cmp -s - "$W/live0" && ok "live checkout git status unchanged" || bad "live git status changed"
[ "$(docker inspect --format '{{.Id}} {{.State.StartedAt}}' "$NAME")" = "$CID0" ] && ok "container untouched" || bad "container changed"
pf > "$W/pf-after.txt"; grep -q '=== PREFLIGHT: PASS' "$W/pf-after.txt" && ok "real preflight after: PASS" || bad "real preflight after"
# S6 re-run without --replace-token
drive s6 "$T2|$T2"; rc_is s6 1; check s6 "STOP: PARKIO_EXPO_ACCESS_TOKEN is already set"
[ "$(ls -A "$L/docker" | grep -c '\.bak-')" = 1 ] && ok "s6: no new backup" || bad "s6: backup count"
# S7 rotation
sleep 1
drive s7 "$T2|$T2" --replace-token; rc_is s7 0; check s7 "PARKIO_EXPO_ACCESS_TOKEN: set"; check s7 "P3c COMPLETE"
check s7 "rewritten in place: PARKIO_PUSH_DELIVERY_PROVIDER, PARKIO_PUSH_DELIVERY_ENABLED, PARKIO_EXPO_ACCESS_TOKEN; appended: none"
grep -qx "PARKIO_EXPO_ACCESS_TOKEN=$T2" "$ENV" && ok "s7: token rotated" || bad "s7: token not rotated"
[ "$(ls -A "$L/docker" | grep -c '\.bak-')" = 2 ] && ok "s7: second backup" || bad "s7: backup count"
# secrets never shown
for f in "$W"/s*.txt "$W"/s*.out "$W"/out/*; do
  for secret in "$T1" "$T2" "$HMAC" Synth; do
    if grep -qF -- "$secret" "$f"; then bad "$(basename "$f") shows a secret"; fi
  done
done
ok "no token or other secret in any transcript or report (checked 4 values)"
modes="$(stat -c %a "$W"/out/* | sort -u | tr '\n' ' ')"; [ "$modes" = "600 " ] && ok "reports mode 600" || bad "report modes $modes"
[ -z "$(git -C "$R" status --porcelain)" ] && ok "release checkout clean" || bad "release checkout changed"
[ -z "$(ls -A "$L/docker" | grep '^\.p3c-' || true)" ] && ok "no temporary file left" || bad "temporary file left"
echo "python $(python3 -c 'import sys; print(sys.version.split()[0])'), compose $(docker compose version --short), docker $(docker version --format '{{.Server.Version}}')"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
