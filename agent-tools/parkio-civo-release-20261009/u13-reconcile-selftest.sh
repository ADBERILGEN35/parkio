#!/usr/bin/env bash
# Self-test of u13-reconcile.py against real Docker. A small "release checkout" (git repo, production wrapper, canonical file
# set) and a dirty "live checkout" copy run busybox containers of every class:
#   pinned (canonical digest = running image), pinned-other (canonical digest, running another image from the live file),
#   release-built (build: in the canonical file set), canonical-tag (tag, not digest), outside (another Compose project),
#   unknown (plain docker run, no Compose labels).
# Checks: exit 0 and every class reported; self-check ok; the live-created service's differing environment variable is named
# but its value never printed; flags as true/false/empty with a secret-looking key absent; U12 values printed; report dir 700,
# files 600; nothing written outside the report directory; wrong T1 checksum -> STOP.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u13-reconcile.py"; T1="$HERE/t1-release-step.py"
P="u13t$$"; W="$(mktemp -d)"
cleanup() {
  for d in rel live other; do (cd "$W/$d" 2>/dev/null && docker compose -p "$P" -f docker/docker-compose.yml down -t 1 >/dev/null 2>&1); done
  (cd "$W/other" 2>/dev/null && docker compose -p "$P-other" -f docker/docker-compose.yml down -t 1 >/dev/null 2>&1)
  docker rm -f "$P-plain" >/dev/null 2>&1 || true
  docker image rm "$P-svc-built" "$P-svc-built:latest" "$P-other-image:v1" >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap cleanup EXIT
DIG="$(docker image inspect busybox:latest --format '{{index .RepoDigests 0}}' 2>/dev/null)"
[ -n "$DIG" ] || { echo "SELFTEST SETUP: busybox:latest has no repo digest"; exit 2; }
SECRET="secret-$(openssl rand -hex 6)"
mkdir -p "$W/rel/docker" "$W/rel/scripts" "$W/rel/build" "$W/out/steps" "$W/other/docker"
printf 'FROM busybox:latest\nLABEL org.opencontainers.image.revision=fakepin\n' > "$W/rel/build/Dockerfile"
echo "docker/docker-compose.yml" > "$W/rel/docker/compose.production.files"
cat > "$W/rel/docker/docker-compose.yml" <<EOF
services:
  svc-pinned:
    image: $DIG
    command: ["sleep", "3600"]
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
  svc-absent:
    image: busybox:latest
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
# the dirty live checkout: svc-tag gets an extra secret-valued variable; svc-other runs a different image (busybox:musl-like tag)
python3 -I - "$W/live/docker/docker-compose.yml" "$SECRET" <<'PY'
import sys
p, secret = sys.argv[1], sys.argv[2]
s = open(p).read()
s = s.replace('      PLAIN: "1"\n', f'      PLAIN: "1"\n      EXTRA_TOKEN: "{secret}"\n')
open(p, "w").write(s)
PY
docker build -q -t "$P-other-image:v1" - >/dev/null <<'EOF'
FROM busybox:latest
LABEL u13t=other
EOF
sed -i "/^  svc-other:/,/^  svc-built:/ s#image: .*#image: $P-other-image:v1#" "$W/live/docker/docker-compose.yml"
printf 'PARKIO_DOMAIN=example\nFOO_ENABLED=true\nBAR_FLAG=false\nFEATURE_X=\nSERVICE_API_TOKEN=%s\n' "$SECRET" > "$W/env"; chmod 600 "$W/env"
(cd "$W/rel" && PARKIO_ENV_FILE="$W/env" ./scripts/parkio-prod-compose.sh up -d --build svc-pinned svc-built >/dev/null 2>&1)
(cd "$W/live" && docker compose -p "$P" --env-file "$W/env" -f docker/docker-compose.yml up -d svc-tag svc-other >/dev/null 2>&1)
cat > "$W/other/docker/docker-compose.yml" <<EOF
services:
  helper:
    image: busybox:latest
    command: ["sleep", "3600"]
EOF
(cd "$W/other" && docker compose -p "$P-other" -f docker/docker-compose.yml up -d >/dev/null 2>&1)
docker run -d --name "$P-plain" busybox:latest sleep 3600 >/dev/null
echo '{"status": "complete", "steps": {"D7": {"status": "ok", "stamp": "20261012T123453Z"}}}' > "$W/out/state.json"
echo '{"result": "PASS", "u12": {"dltUserEndBefore": {"0": 7, "1": 0, "2": 3}, "scoreEndBefore": {"0": 10}, "Tg": "2026-10-12T12:49:14.1Z", "scoreEndAtTg": {"0": 12}}}' > "$W/out/steps/D7-20261012T123453Z.json"
export U13R_T1="$T1" U13R_T1_SHA256="$(sha256sum "$T1" | cut -d' ' -f1)" U13R_PIN="$PIN" T1_RELEASE="$W/rel" T1_LIVE="$W/live" \
  T1_ENV_FILE="$W/env" T1_OUT="$W/out" T1_PROJECT="$P"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
before="$(cd "$W/out" && find . -type f | sort)"
python3 -I "$TOOL" > "$W/r1.out" 2>&1; rc=$?
sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" "$W/r1.out" | cut -c1-200
[ "$rc" = 0 ] && ok "r1 exit 0" || no "r1 exit $rc"
grep -q "HEAD ${PIN:0:12} (= PIN), clean" "$W/r1.out" && ok "r1: release checkout = PIN, clean" || no "r1 release head"
grep -q "live checkout .*1 changed path(s)" "$W/r1.out" && ok "r1: the dirty live checkout reported (1 changed path)" || no "r1 live changes"
for c in "svc-pinned-1 .*pinned " "svc-other-1 .*pinned-other " "svc-built-1 .*release-built " "svc-tag-1 .*canonical-tag " "helper-1 .*outside " "$P-plain .*unknown "; do
  grep -E -q -- "$c" "$W/r1.out" && ok "r1: class $c" || no "r1: class $c"
done
grep -E -q "svc-pinned-1 .*self-check ok canonical =" "$W/r1.out" && ok "r1: canonical-created service self-check ok and equal to canonical" || no "r1 pinned self-check"
grep -E -q "svc-tag-1 .*self-check ok canonical ≠" "$W/r1.out" && ok "r1: live-created service self-check ok, differs from canonical" || no "r1 tag self-check"
has r1.out "differs from canonical in: environment[EXTRA_TOKEN]"
has r1.out "rev fakepin"
has r1.out "canonical services without a running container: svc-absent"
has r1.out "FOO_ENABLED: true"; has r1.out "BAR_FLAG: false"; has r1.out "FEATURE_X: empty"
grep -q "SERVICE_API_TOKEN" "$W/r1.out" && no "r1 printed a non-flag key" || ok "r1: non-flag keys are not listed"
has r1.out 'dltUserEndBefore: {"0": 7, "1": 0, "2": 3}'; has r1.out 'Tg: "2026-10-12T12:49:14.1Z"'
has r1.out "U13 RECONCILIATION COMPLETE"
D="$(ls -1d "$W"/out/reconcile-* | tail -1)"
grep -r -q -F -- "$SECRET" "$W/r1.out" "$D" && no "r1: a secret value reached the output or the report" || ok "r1: no secret value in the output or the report"
[ "$(stat -c %a "$D")" = 700 ] && [ "$(stat -c %a "$D"/* | sort -u | tr '\n' ' ')" = "600 " ] && ok "r1: report dir 700, files 600" || no "r1 modes"
after="$(cd "$W/out" && find . -type f -not -path './reconcile-*' | sort)"
[ "$before" = "$after" ] && ok "r1: nothing written outside the report directory" || no "r1 wrote outside: $(diff <(echo "$before") <(echo "$after") | tail -3)"
python3 -I - "$D/report.json" <<'PY' && ok "r1: report.json classes and inputs recorded" || no "r1 json"
import json, sys
r = json.load(open(sys.argv[1]))
cls = {c["service"] or c["name"]: c["class"] for c in r["containers"]}
assert cls.get("svc-pinned") == "pinned" and cls.get("svc-other") == "pinned-other" and cls.get("svc-built") == "release-built", cls
assert cls.get("svc-tag") == "canonical-tag" and cls.get("helper") == "outside", cls
assert r["problems"] == [] and r["u12"]["dltUserEndBefore"] == {"0": 7, "1": 0, "2": 3}, r["problems"]
PY
U13R_T1_SHA256="$(printf '0%.0s' $(seq 64))" python3 -I "$TOOL" > "$W/r2.out" 2>&1; rc=$?
[ "$rc" = 1 ] && grep -q "STOP: the installed T1" "$W/r2.out" && ok "r2: wrong T1 checksum -> STOP" || no "r2 exit $rc"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
