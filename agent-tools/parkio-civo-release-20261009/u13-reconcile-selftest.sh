#!/usr/bin/env bash
# Self-test of u13-reconcile.py v2 against real Docker. A small "release checkout" (git repo, production wrapper, canonical file
# set) and a dirty "live checkout" copy run busybox containers of every class, plus:
#   - a launcher project like the NR transport: started by a (fake) systemd unit with two env files, one of them "root-only"
#     (mode 000), and a profile; `systemctl cat` and `sudo -n` are fakes on PATH (sudo grants or refuses);
#   - web created with a temporary binding file (image = the model's repo@sha256 pin, pull_policy never) that is then deleted;
#   - a container whose recorded file changed after creation (proven MISMATCH);
#   - a one-shot init that a service waits for (service_completed_successfully) and a long-running service never started.
# Checks: exit 0 and every class; self-check match / MISMATCH / unrenderable distinguished; the launcher render reproduces the
# label hash with the unit's profile and env files; the web binding rebuilt from Config.Image matches, and equals the canonical
# render; value differences shown for safe keys only; no secret value in the output or the report; G verdicts; flags; U12
# values; report dir 700, files 600; nothing written outside the report directory; wrong T1 checksum -> STOP; D7 record
# unreadable -> state.json u12; sudo refused -> the launcher container is unrenderable and the run is INCOMPLETE (exit 1).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u13-reconcile.py"; T1="$HERE/t1-release-step.py"
P="u13t$$"; W="$(mktemp -d)"
cleanup() {
  chmod 600 "$W/nr-root.env" 2>/dev/null
  for proj in "$P" "$P-other" "$P-nr"; do
    ids="$(docker ps -aq --filter "label=com.docker.compose.project=$proj")"
    [ -n "$ids" ] && docker rm -f $ids >/dev/null 2>&1
    nets="$(docker network ls -q --filter "label=com.docker.compose.project=$proj")"
    [ -n "$nets" ] && docker network rm $nets >/dev/null 2>&1
  done
  docker rm -f "$P-plain" >/dev/null 2>&1 || true
  docker image rm "$P-svc-built" "$P-svc-built:latest" "$P-other-image:v1" >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap cleanup EXIT
DIG="$(docker image inspect busybox:latest --format '{{index .RepoDigests 0}}' 2>/dev/null)"
[ -n "$DIG" ] || { echo "SELFTEST SETUP: busybox:latest has no repo digest"; exit 2; }
SECRET="secret-$(openssl rand -hex 6)"; SECRET2="nrkey-$(openssl rand -hex 6)"
mkdir -p "$W/rel/docker" "$W/rel/scripts" "$W/rel/build" "$W/out/steps" "$W/other/docker" "$W/nr/docker" "$W/bin" "$W/bind"
printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=fakepin\n' > "$W/rel/build/Dockerfile"
echo "docker/docker-compose.yml" > "$W/rel/docker/compose.production.files"
cat > "$W/rel/docker/docker-compose.yml" <<EOF
services:
  svc-init:
    image: $DIG
    command: ["true"]
    restart: "no"
  svc-pinned:
    image: $DIG
    command: ["sleep", "3600"]
    depends_on:
      svc-init:
        condition: service_completed_successfully
  svc-other:
    image: $DIG
    command: ["sleep", "3600"]
  svc-built:
    build: ../build
    command: ["sleep", "3600"]
  svc-tag:
    image: busybox:latest
    command: ["sleep", "3600"]
    environment:
      PLAIN: "1"
  svc-drift:
    image: busybox:latest
    command: ["sleep", "3600"]
    environment:
      DRIFT: "1"
  svc-drift2:
    image: busybox:latest
    command: ["sleep", "3600"]
    environment:
      DRIFT2: "1"
  svc-absent:
    image: busybox:latest
    command: ["sleep", "3600"]
    restart: unless-stopped
  web:
    image: $DIG
    command: ["sleep", "3600"]
EOF
cat > "$W/rel/scripts/parkio-prod-compose.sh" <<EOF
#!/usr/bin/env bash
cd "\$(dirname "\$0")/.." && exec docker compose -p $P --env-file "\$PARKIO_ENV_FILE" -f docker/docker-compose.yml "\$@"
EOF
chmod +x "$W/rel/scripts/parkio-prod-compose.sh"
git -C "$W/rel" init -q && git -C "$W/rel" add -A && git -C "$W/rel" -c user.name=t -c user.email=t@t commit -q -m pin
PIN="$(git -C "$W/rel" rev-parse HEAD)"
cp -r "$W/rel" "$W/live"
# the dirty live checkout: svc-tag gets a secret-valued variable and a memory limit; svc-other another image; svc-extra exists
# only here
python3 -I - "$W/live/docker/docker-compose.yml" "$SECRET" <<'PY'
import sys
p, secret = sys.argv[1], sys.argv[2]
s = open(p).read()
s = s.replace('      PLAIN: "1"\n', f'      PLAIN: "1"\n      EXTRA_TOKEN: "{secret}"\n    mem_limit: 32m\n')
s = s.replace('      DRIFT2: "1"\n', '      DRIFT2: "1"\n    mem_limit: 48m\n')
s += '  svc-extra:\n    image: busybox:latest\n    command: ["sleep", "3600"]\n'
open(p, "w").write(s)
PY
docker build -q -t "$P-other-image:v1" - >/dev/null <<'EOF'
FROM busybox:latest
LABEL u13t=other
EOF
sed -i "/^  svc-other:/,/^  svc-built:/ s#image: .*#image: $P-other-image:v1#" "$W/live/docker/docker-compose.yml"
printf 'PARKIO_DOMAIN=example\nFOO_ENABLED=true\nBAR_FLAG=false\nFEATURE_X=\nSERVICE_API_TOKEN=%s\n' "$SECRET" > "$W/env"; chmod 600 "$W/env"
(cd "$W/rel" && PARKIO_ENV_FILE="$W/env" ./scripts/parkio-prod-compose.sh up -d --build svc-pinned svc-built >/dev/null 2>&1)
printf '{"services": {"web": {"image": "%s", "pull_policy": "never"}}}\n' "$DIG" > "$W/bind/web-binding.yml"
(cd "$W/rel" && docker compose -p "$P" --env-file "$W/env" -f docker/docker-compose.yml -f "$W/bind/web-binding.yml" up -d web >/dev/null 2>&1)
rm -rf "$W/bind"
(cd "$W/live" && docker compose -p "$P" --env-file "$W/env" -f docker/docker-compose.yml up -d svc-tag svc-other svc-extra svc-drift svc-drift2 >/dev/null 2>&1)
sed -i -e 's/      DRIFT: "1"/      DRIFT: "2"/' -e 's/      DRIFT2: "1"/      DRIFT2: "2"/' "$W/live/docker/docker-compose.yml"   # changed after creation
cat > "$W/other/docker/docker-compose.yml" <<EOF
services:
  helper:
    image: busybox:latest
    command: ["sleep", "3600"]
EOF
(cd "$W/other" && docker compose -p "$P-other" -f docker/docker-compose.yml up -d >/dev/null 2>&1)
# the launcher project (NR-like)
cat > "$W/nr/docker/a.yml" <<'EOF'
services:
  nr-svc:
    image: busybox:latest
    profiles: ["nrp"]
    command: ["sleep", "3600"]
    environment:
      PARKIO_COLLECTOR_SOURCE_SHA: ${PARKIO_COLLECTOR_SOURCE_SHA:?set the reviewed collector source SHA}
      NR_KEY: ${NR_KEY:?supply a key out of band}
EOF
printf 'services:\n  nr-svc:\n    environment:\n      NR_EXTRA: ${NR_EXTRA:-x}\n' > "$W/nr/docker/b.yml"
printf 'NR_KEY=%s\n' "$SECRET2" > "$W/nr-root.env"; printf 'PARKIO_COLLECTOR_SOURCE_SHA=fakesha123\n' > "$W/nr-runtime.env"
(cd "$W/nr/docker" && docker compose --env-file "$W/nr-root.env" --env-file "$W/nr-runtime.env" -p "$P-nr" -f a.yml -f b.yml \
  --profile nrp up -d nr-svc >/dev/null 2>&1)
chmod 000 "$W/nr-root.env"
cat > "$W/bin/systemctl" <<EOF
#!/usr/bin/env bash
[ "\$1" = cat ] || exit 1
for a in "\$@"; do unit="\$a"; done
[ "\$unit" = parkio-nr-log-continuous.service ] || { echo "No files found for \$unit." >&2; exit 1; }
cat <<'UNIT'
# /etc/systemd/system/parkio-nr-log-continuous.service
[Service]
Type=oneshot
WorkingDirectory=$W/nr/docker
EnvironmentFile=$W/nr-runtime.env
EnvironmentFile=$W/nr-root.env
ExecStartPre=/usr/bin/env FOO=1 /bin/true
ExecStart=/usr/bin/docker compose --env-file $W/nr-root.env --env-file $W/nr-runtime.env -p $P-nr -f a.yml -f b.yml \\
  --profile nrp up -d --wait nr-svc
UNIT
EOF
cat > "$W/bin/sudo" <<EOF
#!/usr/bin/env bash
[ "\${1:-}" = "-n" ] || { echo "fake sudo: -n expected" >&2; exit 2; }
shift
if [ "\$(cat "$W/sudo-mode")" = refuse ]; then echo "sudo: a password is required" >&2; exit 1; fi
chmod 600 "$W/nr-root.env"; "\$@"; rc=\$?; chmod 000 "$W/nr-root.env"; exit \$rc
EOF
chmod +x "$W/bin/systemctl" "$W/bin/sudo"; echo ok > "$W/sudo-mode"
docker run -d --name "$P-plain" busybox:latest sleep 3600 >/dev/null
echo '{"status": "complete", "steps": {"D7": {"status": "ok", "stamp": "20261012T123453Z"}}, "u12": {"dltUserEndBefore": {"0": 5}}}' > "$W/out/state.json"
echo '{"result": "PASS", "u12": {"dltUserEndBefore": {"0": 7, "1": 0, "2": 3}, "scoreEndBefore": {"0": 10}, "Tg": "2026-10-12T12:49:14.1Z", "scoreEndAtTg": {"0": 12}}}' > "$W/out/steps/D7-20261012T123453Z.json"
export U13R_T1="$T1" U13R_T1_SHA256="$(sha256sum "$T1" | cut -d' ' -f1)" U13R_PIN="$PIN" T1_RELEASE="$W/rel" T1_LIVE="$W/live" \
  T1_ENV_FILE="$W/env" T1_OUT="$W/out" T1_PROJECT="$P" PATH="$W/bin:$PATH"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasE() { if grep -qE -- "$2" "$W/$1"; then ok "$1: /$2/"; else no "$1: /$2/"; fi; }
before="$(cd "$W/out" && find . -type f | sort)"
python3 -I "$TOOL" > "$W/r1.out" 2>&1; rc=$?
sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" "$W/r1.out" | grep -v -E 'navlo|pr86|/mnt/c/Users|/Desktop/' | cut -c1-220
[ "$rc" = 0 ] && ok "r1 exit 0" || no "r1 exit $rc"
grep -q "HEAD ${PIN:0:12} (= PIN), clean" "$W/r1.out" && ok "r1: release checkout = PIN, clean" || no "r1 release head"
hasE r1.out "live checkout .*1 changed path\(s\)"
for c in "svc-pinned-1 .*pinned " "svc-other-1 .*pinned-other " "svc-built-1 .*release-built " "svc-tag-1 .*canonical-tag " \
         "helper-1 .*outside " "svc-extra-1 .*outside " "nr-svc-1 .*outside " "$P-plain .*unknown "; do
  hasE r1.out "$c"
done
hasE r1.out "svc-pinned-1 .*self-check match canonical ="
hasE r1.out "svc-tag-1 .*self-check match canonical ≠"
hasE r1.out "svc-drift-1 .*self-check MISMATCH"
hasE r1.out "web-1 .*self-check match canonical ="
has r1.out "web binding rebuilt from Config.Image $DIG"
hasE r1.out "nr-svc-1 .*self-check match"
has r1.out "launcher parkio-nr-log-continuous.service | root-only env files: hash-only render via sudo -n"
has r1.out "profiles nrp"
has r1.out "identity: PARKIO_COLLECTOR_SOURCE_SHA=fakesha123"
hasE r1.out "parkio-nr-log-continuous.service: project <?$P-nr"
has r1.out "nr-root.env (root-only)"
has r1.out "differs from canonical in: mem_limit, environment[EXTRA_TOKEN]"
has r1.out 'mem_limit: live "33554432" | canonical absent'
hasE r1.out "svc-drift-1 .*self-check MISMATCH canonical ="
hasE r1.out "svc-drift2-1 .*self-check MISMATCH canonical ≠"
has r1.out "differs from canonical in (approximate: self-check MISMATCH): mem_limit, environment[DRIFT2]"
has r1.out "rev fakepin"
has r1.out "svc-init: intended one-shot init (restart: no; svc-pinned wait(s) for service_completed_successfully)"
hasE r1.out "actual exited \(exit 0\) at .* -> as intended"
has r1.out "svc-absent: intended long-running (restart: unless-stopped): expected running"
has r1.out "actual absent (no container) -> differs from its intended state - owner decision"
hasE r1.out "self-checks: .*MISMATCH 2"
has r1.out "FOO_ENABLED: true"; has r1.out "BAR_FLAG: false"; has r1.out "FEATURE_X: empty"
grep -q "SERVICE_API_TOKEN" "$W/r1.out" && no "r1 printed a non-flag key" || ok "r1: non-flag keys are not listed"
has r1.out 'dltUserEndBefore: {"0": 7, "1": 0, "2": 3}'; has r1.out 'Tg: "2026-10-12T12:49:14.1Z"'
has r1.out "source: steps/D7-20261012T123453Z.json"
has r1.out "U13 RECONCILIATION COMPLETE"
D="$(ls -1d "$W"/out/reconcile-* | tail -1)"
for s in "$SECRET" "$SECRET2"; do
  grep -r -q -F -- "$s" "$W/r1.out" "$D" && no "r1: a secret value reached the output or the report" || ok "r1: secret ${s%%-*} not in the output or the report"
done
[ "$(stat -c %a "$D")" = 700 ] && [ "$(stat -c %a "$D"/* | sort -u | tr '\n' ' ')" = "600 " ] && ok "r1: report dir 700, files 600" || no "r1 modes"
after="$(cd "$W/out" && find . -type f -not -path './reconcile-*' | sort)"
[ "$before" = "$after" ] && ok "r1: nothing written outside the report directory" || no "r1 wrote outside: $(diff <(echo "$before") <(echo "$after") | tail -3)"
[ "$(stat -c %a "$W/nr-root.env")" = 0 ] && ok "r1: the root-only env file was left as it was" || no "r1 env file mode $(stat -c %a "$W/nr-root.env")"
python3 -I - "$D/report.json" <<'PY' && ok "r1: report.json classes, self-checks and G recorded" || no "r1 json"
import json, sys
r = json.load(open(sys.argv[1]))
c = {x["service"] or x["name"]: x for x in r["containers"]}
assert c["svc-pinned"]["class"] == "pinned" and c["svc-other"]["class"] == "pinned-other" and c["svc-built"]["class"] == "release-built"
assert c["svc-drift"]["selfCheck"] == "MISMATCH" and c["svc-drift"]["equalsCanonical"] is True
assert c["svc-drift2"]["selfCheck"] == "MISMATCH" and c["svc-drift2"]["equalsCanonical"] is False
assert c["nr-svc"]["selfCheck"] == "match" and c["web"]["selfCheck"] == "match"
assert c["nr-svc"]["inputs"]["rootOnly"] and c["nr-svc"]["inputs"]["profiles"] == ["nrp"]
assert r["notRunning"]["svc-init"]["verdict"] == "as intended" and r["notRunning"]["svc-absent"]["verdict"].startswith("differs")
assert r["problems"] == [] and r["u12"]["dltUserEndBefore"] == {"0": 7, "1": 0, "2": 3}, r["problems"]
PY
U13R_T1_SHA256="$(printf '0%.0s' $(seq 64))" python3 -I "$TOOL" > "$W/r2.out" 2>&1; rc=$?
[ "$rc" = 1 ] && grep -q "STOP: the installed T1" "$W/r2.out" && ok "r2: wrong T1 checksum -> STOP" || no "r2 exit $rc"
mv "$W/out/steps/D7-20261012T123453Z.json" "$W/d7.bak"; sleep 1
python3 -I "$TOOL" > "$W/r3.out" 2>&1; rc=$?
[ "$rc" = 0 ] && ok "r3 exit 0 (D7 record unreadable, state.json u12 used)" || no "r3 exit $rc"
has r3.out "source: state.json u12"; has r3.out 'dltUserEndBefore: {"0": 5}'
mv "$W/d7.bak" "$W/out/steps/D7-20261012T123453Z.json"
echo refuse > "$W/sudo-mode"; sleep 1
python3 -I "$TOOL" > "$W/r4.out" 2>&1; rc=$?
[ "$rc" = 1 ] && ok "r4 exit 1 (sudo refused)" || no "r4 exit $rc"
hasE r4.out "nr-svc-1 .*self-check unrenderable"
has r4.out "self-check unrenderable: the launcher's env files are root-only and \`sudo -n\` was refused"
has r4.out "U13 RECONCILIATION INCOMPLETE"
grep -q -F -- "$SECRET2" "$W/r4.out" && no "r4 printed the root-only secret" || ok "r4: the root-only secret is not printed"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
