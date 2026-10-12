#!/usr/bin/env bash
# Local self-test of u05-window-snapshot.py with a fake docker (ps -a, inspect) and a fake production wrapper
# (config --hash '*'). S1 only auth-service (recreated: new id, same image and hash) and analytics-service (new StartedAt)
# differ -> PASS; S2 another container restarted -> FAIL naming it; S3 the env file differs -> FAIL; S4 a window key not
# restored -> FAIL; S5 a running config differs from the rendered one -> FAIL; S6 auth-service on another image -> FAIL;
# S7 a container added -> FAIL; S8 the operator token's value is never printed or stored; records 700/600.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u05-window-snapshot.py"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/release/scripts" "$W/out"
TOKEN="op-token-$(openssl rand -hex 20)"
cat > "$W/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, sys
s = json.load(open(os.environ["FAKE_STATE"]))
a = sys.argv[1:]
if a[:2] == ["ps", "-a"]:
    print("\n".join(c["Id"][:12] for c in s["containers"])); sys.exit(0)
if a[:1] == ["inspect"]:
    print(json.dumps(s["containers"])); sys.exit(0)
sys.exit(2)
PY
cat > "$W/release/scripts/parkio-prod-compose.sh" <<'SH'
#!/usr/bin/env bash
[ "$1 $2 $3" = "config --hash *" ] || exit 2
python3 -I -c 'import json,os; s=json.load(open(os.environ["FAKE_STATE"])); [print(k, v) for k, v in s["rendered"].items()]'
SH
chmod +x "$W/bin/docker" "$W/release/scripts/parkio-prod-compose.sh"
state() {  # mutation
  python3 -I - "$W/state.json" "$1" <<'PY'
import json, sys
path, mut = sys.argv[1:3]
def c(name, svc, cid, img="sha256:img-" + "0"*8, started="2026-10-12T12:00:00Z", h="hash-"):
    return {"Name": "/" + name, "Id": cid * 8, "Image": img, "RestartCount": 0,
            "Config": {"Labels": {"com.docker.compose.project": "parkio", "com.docker.compose.service": svc,
                                  "com.docker.compose.config-hash": h + svc}},
            "State": {"Status": "running", "StartedAt": started, "Health": {"Status": "healthy"}}}
cs = [c("parkio-auth-service-1", "auth-service", "a1"), c("parkio-analytics-service-1", "analytics-service", "b2"),
      c("parkio-gateway-service-1", "gateway-service", "c3"), c("parkio-minio", "minio", "d4"),
      c("parkio-postgres-auth", "postgres-auth", "e5")]
rendered = {x["Config"]["Labels"]["com.docker.compose.service"]: x["Config"]["Labels"]["com.docker.compose.config-hash"] for x in cs}
if mut in ("ok", "restart", "envdiff", "keydiff", "drift", "authimage", "added"):
    cs[0]["Id"] = "f6" * 8; cs[0]["State"]["StartedAt"] = "2026-10-13T09:40:00Z"
    cs[1]["State"]["StartedAt"] = "2026-10-13T09:20:00Z"
if mut == "restart": cs[3]["State"]["StartedAt"] = "2026-10-13T09:10:00Z"
if mut == "drift": rendered["gateway-service"] = "hash-other"
if mut == "authimage": cs[0]["Image"] = "sha256:img-other"
if mut == "added": cs.append(c("parkio-extra", "extra", "g7"))
json.dump({"containers": cs, "rendered": rendered}, open(path, "w"))
PY
}
envfile() {  # erasure
  printf 'PARKIO_ACCOUNT_ERASURE_ENABLED=%s\nPARKIO_REGISTRATION_MODE=closed\nPARKIO_REGISTRATION_INVITE_CREATION_ENABLED=false\nPARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=%s\nOTHER=1\n' "$1" "$TOKEN" > "$W/env"
}
export U05W_TEST=1 U05W_DOCKER="$W/bin/docker" U05W_OUT="$W/out" U05W_RELEASE="$W/release" U05W_ENV_FILE="$W/env" FAKE_STATE="$W/state.json"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
run() { local n="$1"; shift; python3 -I "$TOOL" "$@" > "$W/$n" 2>&1; echo "exit=$?" >> "$W/$n"; }
w0() { rm -f "$W"/out/*.json; state base; envfile false; run w0.out snapshot W0; sleep 1; }

w0; cat "$W/w0.out" | sed -e "s#$W#<W>#g"
has w0.out "containers: 5 (5 running)"; has w0.out "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=set"
has w0.out "rendered config: 5 services; running containers that differ from it: none"
state ok; envfile false; run w6.out snapshot W6; run s1.out compare; sed -e "s#$W#<W>#g" "$W/s1.out"
has s1.out "3 other containers identical"; has s1.out "U05 WINDOW COMPARE PASS"; has s1.out "exit=0"
[ "$(stat -c %a "$W/out")" = 700 ] && [ "$(stat -c %a "$(ls "$W"/out/*.json | head -1)")" = 600 ] && ok "records 700/600" || no "record modes"
if grep -r -q -F -- "$TOKEN" "$W/out" "$W"/*.out; then no "S8: the token value leaked"; else ok "S8: the token value is in no output or record"; fi
w0; state restart; envfile false; run w6b.out snapshot W6; run s2.out compare
has s2.out "DIFF parkio-minio: startedAt 2026-10-12T12:00:00 -> 2026-10-13T09:10:00"; has s2.out "U05 WINDOW COMPARE FAIL"; has s2.out "exit=1"
w0; state ok; envfile false; echo "# edited" >> "$W/env"; run w6c.out snapshot W6; run s3.out compare
has s3.out "DIFF the env file's SHA-256 differs from W0"
w0; state keydiff; envfile true; run w6d.out snapshot W6; run s4.out compare
has s4.out "window keys differ from W0"; has s4.out "U05 WINDOW COMPARE FAIL"
w0; state drift; envfile false; run w6e.out snapshot W6; run s5.out compare
has s5.out "running config differs from the rendered config: ['gateway-service']"
w0; state authimage; envfile false; run w6f.out snapshot W6; run s6.out compare
has s6.out "DIFF parkio-auth-service-1: image"
w0; state added; envfile false; run w6g.out snapshot W6; run s7.out compare
has s7.out "container set changed: added ['parkio-extra']"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
