#!/usr/bin/env bash
# Tests for resolve-rollback-manifest-run.sh (against a local fake of the GitHub API) and for
# verify-rollback-manifest.sh (against synthetic manifests). No network and no Docker.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RESOLVE="$ROOT/scripts/ci/resolve-rollback-manifest-run.sh"
VERIFY="$ROOT/scripts/ci/verify-rollback-manifest.sh"
WORK="$(mktemp -d)"
SERVER_PID=""
pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }
cleanup() {
  if [ -n "$SERVER_PID" ]; then kill "$SERVER_PID" 2>/dev/null || true; fi
  rm -rf -- "${WORK:?}"
}
trap cleanup EXIT

SHA=0123456789abcdef0123456789abcdef01234567
ART=invite-production-manifest-$SHA

# Fake API: GET /repos/o/r/actions/runs/<id>, .../<id>/artifacts?name=<n> and .../<id>/jobs.
cat > "$WORK/server.py" <<'PY'
import json, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

SHA = "0123456789abcdef0123456789abcdef01234567"
ART = "invite-production-manifest-" + SHA
GOOD = {"id": 0, "repository": {"full_name": "o/r"}, "path": ".github/workflows/invite-production-deploy.yml",
        "head_branch": "api", "event": "workflow_dispatch", "status": "completed", "conclusion": "success",
        "head_sha": SHA}
RUNS = {
    101: {},
    102: {"path": ".github/workflows/runtime-validation.yml"},
    103: {"head_branch": "feature"},
    104: {"event": "pull_request"},
    105: {"conclusion": "failure"},
    106: {"repository": {"full_name": "fork/r"}},
    107: {}, 108: {}, 109: {}, 110: {}, 111: {}, 112: {}, 113: {},
}
def artifacts(run_id, name):
    if run_id == 107:
        items = [{"id": 7, "name": ART, "expired": True}]
    elif run_id == 108:
        items = [{"id": 8, "name": ART, "expired": False}, {"id": 9, "name": ART, "expired": False}]
    elif run_id == 113:
        items = [{"id": "13;x", "name": ART, "expired": False}]
    else:
        items = [{"id": 1, "name": ART, "expired": False}]
    return {"artifacts": [a for a in items if a["name"] == name]}
BUILD = {"name": "Build images + secret-safe dry-run manifest", "conclusion": "success"}
ROLLBACK = {"name": "Rollback invite-production", "conclusion": "skipped"}
def deploy(conclusion):
    return {"name": "Deploy invite-production", "conclusion": conclusion}
def jobs(run_id):
    deploys = {109: [deploy("skipped")], 110: [deploy("failure")], 111: [],
               112: [deploy("success"), deploy("success")]}.get(run_id, [deploy("success")])
    return {"jobs": [BUILD, *deploys, ROLLBACK]}

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        parts = url.path.strip("/").split("/")
        if self.headers.get("Authorization") != "Bearer test-token":
            return self.reply(401, {"message": "Bad credentials"})
        if parts[:5] == ["repos", "o", "r", "actions", "runs"] and len(parts) in (6, 7):
            run_id = int(parts[5]) if parts[5].isdigit() else -1
            if run_id not in RUNS:
                return self.reply(404, {"message": "Not Found"})
            if len(parts) == 6:
                return self.reply(200, {**GOOD, "id": run_id, **RUNS[run_id]})
            if parts[6] == "artifacts":
                return self.reply(200, artifacts(run_id, parse_qs(url.query).get("name", [""])[0]))
            if parts[6] == "jobs":
                return self.reply(200, jobs(run_id))
        self.reply(404, {"message": "Not Found"})
    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self, *args):
        pass

server = HTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
PY
python3 "$WORK/server.py" > "$WORK/port" &
SERVER_PID=$!
for _ in $(seq 1 50); do [ -s "$WORK/port" ] && break; sleep 0.1; done
GITHUB_API_URL="http://127.0.0.1:$(cat "$WORK/port")"
export GITHUB_API_URL
export GITHUB_TOKEN=test-token

resolve() { # run id, artifact; sets out and rc
  if out="$(GITHUB_OUTPUT="$WORK/output" "$RESOLVE" --repo o/r --workflow invite-production-deploy.yml \
    --branch api --deploy-job "Deploy invite-production" --run-id "$1" --artifact "$2" 2>&1)"; then rc=0; else rc=$?; fi
}

echo "=== resolve-rollback-manifest-run ==="
: > "$WORK/output"
resolve 101 "$ART"
[ "$rc" = 0 ] && grep -qx "head_sha=$SHA" <<<"$out" && grep -qx "artifact_id=1" "$WORK/output" \
  && ok "a successful dispatch run of the workflow with the artifact resolves" || bad "positive: rc=$rc $out"
for case in "102:is a run of '.github/workflows/runtime-validation.yml'" "103:ran on branch 'feature'" \
  "104:was started by 'pull_request'" "105:ended completed/failure" "106:belongs to 'fork/r'" \
  "107:holds 0 unexpired artifact" "108:holds 2 unexpired artifact" "999:is not readable" \
  "109:'Deploy invite-production' job is skipped" "110:'Deploy invite-production' job is failure" \
  "111:'Deploy invite-production' job is absent" "112:'Deploy invite-production' job is success, success" \
  "113:the artifact has no valid id"; do
  run="${case%%:*}"; reason="${case#*:}"
  resolve "$run" "$ART"
  [ "$rc" = 3 ] && grep -qF "$reason" <<<"$out" && ok "run $run is refused: $reason" || bad "run $run: rc=$rc $out"
done
resolve 101 invite-production-manifest-other
[ "$rc" = 3 ] && grep -qF "holds 0 unexpired artifact" <<<"$out" && ok "a run without that artifact is refused" || bad "other artifact: rc=$rc $out"
resolve "" "$ART"
[ "$rc" = 3 ] && grep -qF "is not a run number" <<<"$out" && ok "a missing run id is refused" || bad "empty id: rc=$rc $out"
resolve "101;id" "$ART"
[ "$rc" = 3 ] && ok "a run id that is not a number is refused" || bad "bad id: rc=$rc $out"
resolve 101 "../x"
[ "$rc" = 3 ] && ok "an artifact name with path characters is refused" || bad "bad artifact: rc=$rc $out"
if out="$("$RESOLVE" --repo o/r --workflow invite-production-deploy.yml --branch api \
  --run-id 101 --artifact "$ART" 2>&1)"; then rc=0; else rc=$?; fi
[ "$rc" = 2 ] && grep -qF -- "--deploy-job is required" <<<"$out" && ok "the deploy job name is required" || bad "no deploy job: rc=$rc $out"
reference() { # reference; sets out and rc
  if out="$("$RESOLVE" --repo o/r --workflow invite-production-deploy.yml --branch api \
    --deploy-job "Deploy invite-production" --reference "$1" 2>&1)"; then rc=0; else rc=$?; fi
}
reference "101/$ART"
[ "$rc" = 0 ] && grep -qx "run_id=101" <<<"$out" && grep -qx "artifact=$ART" <<<"$out" && grep -qx "manifest_sha256=" <<<"$out" \
  && ok "a RUN_ID/ARTIFACT reference resolves to the run and the artifact, without a pin" || bad "reference: rc=$rc $out"
PIN="$(printf 'ab%.0s' $(seq 1 32))"
reference "101/$ART@$PIN"
[ "$rc" = 0 ] && grep -qx "artifact=$ART" <<<"$out" && grep -qx "manifest_sha256=$PIN" <<<"$out" \
  && ok "a RUN_ID/ARTIFACT@SHA256 reference passes the pin on" || bad "pinned reference: rc=$rc $out"
for pin in "ABAB${PIN:4}" "${PIN:2}" "${PIN}00" "not-a-hash" ""; do
  reference "101/$ART@$pin"
  [ "$rc" = 3 ] && grep -qF "is not 64 lowercase hex" <<<"$out" && ok "a malformed pin '${pin:0:12}' is refused" || bad "pin '$pin': rc=$rc $out"
done
reference "$ART"
[ "$rc" = 3 ] && grep -qF "is not RUN_ID/ARTIFACT" <<<"$out" && ok "an artifact name without the run id is refused" || bad "no run id: rc=$rc $out"
reference "x101/$ART"
[ "$rc" = 3 ] && grep -qF "is not a run number" <<<"$out" && ok "a reference whose run part is not a number is refused" || bad "bad run part: rc=$rc $out"
if out="$(GITHUB_TOKEN=wrong "$RESOLVE" --repo o/r --workflow invite-production-deploy.yml --branch api \
  --deploy-job "Deploy invite-production" --run-id 101 --artifact "$ART" 2>&1)"; then rc=0; else rc=$?; fi
[ "$rc" = 3 ] && ok "a token the API rejects fails closed" || bad "bad token: rc=$rc $out"

echo "=== verify-rollback-manifest ==="
manifest() { # dir, python dict updates
  mkdir -p "$1"
  python3 - "$1" "$2" <<'PY'
import json, sys
d, updates = sys.argv[1], sys.argv[2]
sha = "0123456789abcdef0123456789abcdef01234567"
tag = "sha-" + sha
m = {"schemaVersion": 1, "action": "deploy", "gitSha": sha, "imageTag": tag,
     "deploymentProfile": "invite-production",
     "images": {s: f"parkio/{s}:{tag}" for s in ("gateway-service", "auth-service")}}
m.update(json.loads(updates))
json.dump(m, open(f"{d}/deploy-{sha[:12]}-20261003T000000Z.json", "w"))
PY
}
verify() { # dir, extra args...; sets out and rc
  local dir="$1"; shift
  if out="$("$VERIFY" --dir "$dir" --profile invite-production --git-sha "$SHA" "$@" 2>&1)"; then rc=0; else rc=$?; fi
}
manifest "$WORK/good" '{}'
good_sha="$(sha256sum "$WORK"/good/deploy-*.json | cut -d' ' -f1)"
verify "$WORK/good" --sha256 "$good_sha"
[ "$rc" = 0 ] && grep -qx "sha256=$good_sha" <<<"$out" && ok "a matching manifest passes, with its SHA-256" || bad "good: rc=$rc $out"
verify "$WORK/good" --sha256 "$(printf '%064d' 0)"
[ "$rc" = 3 ] && grep -qF "not the expected" <<<"$out" && ok "a SHA-256 mismatch is refused" || bad "sha mismatch: rc=$rc $out"
for case in 'schema:{"schemaVersion": 2}:schemaVersion is 2' \
  'profile:{"deploymentProfile": "hosted-beta"}:deploymentProfile is '"'"'hosted-beta'"'" \
  'gitsha:{"gitSha": "ffffffffffffffffffffffffffffffffffffffff"}:not the deploy run' \
  'tag:{"imageTag": "sha-x y"}:is missing or not a tag' \
  'images:{"images": {}}:images is missing or empty' \
  'mixed:{"images": {"gateway-service": "parkio/gateway-service:latest"}}:images not tagged'; do
  name="${case%%:*}"; rest="${case#*:}"; reason="${rest##*:}"; updates="${rest%:*}"
  manifest "$WORK/$name" "$updates"
  verify "$WORK/$name"
  [ "$rc" = 3 ] && grep -qF "$reason" <<<"$out" && ok "a manifest with a bad $name is refused" || bad "$name: rc=$rc $out"
done
if out="$("$VERIFY" --dir "$WORK/profile" --git-sha "$SHA" 2>&1)"; then rc=0; else rc=$?; fi
[ "$rc" = 0 ] && ok "without --profile the profile is left to the rollback script" || bad "no profile: rc=$rc $out"
manifest "$WORK/two" '{}'
cp -- "$WORK"/two/deploy-*.json "$WORK/two/deploy-second.json"
verify "$WORK/two"
[ "$rc" = 3 ] && grep -qF "found 2" <<<"$out" && ok "two manifests are refused" || bad "two: rc=$rc $out"
mkdir -p "$WORK/none"
verify "$WORK/none"
[ "$rc" = 3 ] && grep -qF "found 0" <<<"$out" && ok "no manifest is refused" || bad "none: rc=$rc $out"
mkdir -p "$WORK/broken" && printf '{"schemaVersion": 1,' > "$WORK/broken/deploy-x.json"
verify "$WORK/broken"
[ "$rc" = 3 ] && grep -qF "not valid JSON" <<<"$out" && ok "invalid JSON is refused" || bad "broken: rc=$rc $out"

echo
echo "rollback-manifest-retrieval: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
