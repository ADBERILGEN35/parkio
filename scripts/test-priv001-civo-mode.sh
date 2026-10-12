#!/usr/bin/env bash
# Regression gates for the PRIV-001A harness's civo-production mode.
# Deterministic. No network, no container, no production access: a fake `docker` stands in for
# `docker ps` (Compose labels), the gateway (`curl --config -` inside its container), the
# auth-service invite call (`sh -c` with headers on stdin) and the auth database (`sh -c` psql).
# Every fake records its argv; no password, token, invite, operator token or gateway secret may
# appear there, in any output, or in evidence.
#
# shellcheck shell=bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
pass=0
fail=0
ok() { echo "PASS: $*"; pass=$((pass + 1)); }
no() { echo "FAIL: $*" >&2; fail=$((fail + 1)); }
has() { if grep -qF -- "$2" "$1"; then ok "$(basename "$1"): $2"; else no "$(basename "$1"): missing '$2'"; fi; }
hasnt() { if grep -qF -- "$2" "$1"; then no "$(basename "$1"): unexpected '$2'"; else ok "$(basename "$1"): no '$2'"; fi; }

T="$(mktemp -d)"
trap 'rm -rf -- "$T"' EXIT
mkdir -p "$T/bin" "$T/ctr"
OPERATOR_TOKEN="op-$(openssl rand -hex 24)"
GATEWAY_SECRET="gw-$(openssl rand -hex 16)"

cat > "$T/fakeapi.py" <<'PY'
"""Shared state and behavior of the fake gateway, invite endpoint and auth database."""
import json, os, re, sys, uuid

STATE = os.environ["PRIV001_FAKE_STATE"]

def load():
    return json.load(open(STATE))

def save(s):
    json.dump(s, open(STATE, "w"))

def log_argv(tool, argv):
    with open(os.environ["PRIV001_FAKE_ARGV_LOG"], "a") as f:
        f.write(json.dumps([tool] + argv) + "\n")

def gateway(method, path, headers, body):
    s = load()
    bearer = ""
    for h in headers:
        if h.lower().startswith("authorization: bearer "):
            bearer = h.split(" ", 2)[2]
    data = json.loads(body) if body else {}
    if path == "/api/v1/auth/register" and method == "POST":
        inv = data.get("inviteToken")
        if inv not in s["invites"] or s["invites"][inv] != "issued":
            return 403, {"code": "REGISTRATION_INVITE_INVALID"}
        s["invites"][inv] = "consumed"
        uid = str(uuid.uuid4())
        s["users"][data["email"]] = {"id": uid, "status": "PENDING_VERIFICATION", "password": data["password"]}
        save(s)
        return 201, {"user": {"id": uid, "status": "PENDING_VERIFICATION"}}
    if path == "/api/v1/auth/login" and method == "POST":
        u = s["users"].get(data.get("email"))
        if not u or u["password"] != data.get("password") or u["status"] != "ACTIVE":
            return 401, {"code": "INVALID_CREDENTIALS"}
        u["access"] = "eyJhbGciOiJSUzI1NiJ9.fake" + uuid.uuid4().hex
        u["refresh"] = "rt-" + uuid.uuid4().hex
        save(s)
        return 200, {"accessToken": u["access"], "refreshToken": u["refresh"], "user": {"status": "ACTIVE"}}
    if path == "/api/v1/auth/me" and method == "GET":
        ok = any(u.get("access") == bearer and bearer for u in s["users"].values())
        return (200, {"ok": True}) if ok else (401, {"code": "UNAUTHORIZED"})
    if path == "/api/v1/account" and method == "DELETE":
        u = next((u for u in s["users"].values() if u.get("access") == bearer and bearer), None)
        if not u:
            return 401, {"code": "UNAUTHORIZED"}
        if not s["erasureEnabled"]:
            return 403, {"code": "ACCOUNT_ERASURE_DISABLED"}
        if u["password"] != data.get("password"):
            return 403, {"code": "INVALID_PASSWORD"}
        rid = str(uuid.uuid4())
        s["requests"].append({"id": rid, "authUserId": u["id"], "status": "IN_PROGRESS"})
        u["status"] = "ERASURE_IN_PROGRESS"
        save(s)
        return 202, {"erasureRequestId": rid, "status": "IN_PROGRESS"}
    return 404, {"code": "NOT_FOUND"}

def invite(headers):
    s = load()
    hd = dict(h.split(": ", 1) for h in headers if ": " in h)
    if not s["inviteCreation"]:
        return 403, {"code": "FORBIDDEN", "message": "Registration invite creation is disabled."}
    if hd.get("X-Parkio-Registration-Invite-Operator-Token") != s["operatorToken"] or hd.get("X-Gateway-Auth") != s["gatewaySecret"]:
        return 403, {"code": "FORBIDDEN"}
    tok = uuid.uuid4().hex + "_-" + uuid.uuid4().hex[:8]
    s["invites"][tok] = "issued"
    save(s)
    return 201, {"token": tok, "expiresAt": "2026-10-20T00:00:00Z"}

def psql(sql):
    s = load()
    if sql.lstrip().startswith("BEGIN;") and "UPDATE auth_users" in sql:
        m = re.search(r"WHERE email = '([^']+)'", sql)
        u = s["users"].get(m.group(1)) if m else None
        if not u or u["status"] != "PENDING_VERIFICATION":
            return "BEGIN\nCOMMIT"
        u["status"] = "ACTIVE"
        save(s)
        return "BEGIN\n" + u["id"] + "\nUPDATE 1\nCOMMIT"
    m = re.search(r"auth_user_id = '([0-9a-f-]{36})'::uuid", sql)
    uid = m.group(1) if m else ""
    user = next((u for u in s["users"].values() if u["id"] == uid), None)
    reqs = [r for r in s["requests"] if r["authUserId"] == uid]
    req = reqs[-1] if reqs else None
    if req is not None:
        acks = {p: "SUCCESS" for p in s["participants"] if p not in s["down"]}
        if not s["down"]:
            req["status"] = "COMPLETE"
            user["status"] = "ERASED"
        save(s)
    else:
        acks = {}
    other = len([r for r in s["requests"] if r["authUserId"] != uid]) + s.get("otherRequests", 0)
    out = {"request_status": req["status"] if req else "ABSENT", "request_count": len(reqs),
           "auth_state": user["status"] if user else "ABSENT", "acks": acks,
           "other_requests_since_window_start": other if "requested_at >=" in sql else None}
    if "participant_acks" in sql:
        out = {"auth_state": out["auth_state"], "participant_acks": acks, "erasure_request_status": out["request_status"],
               "participant_ack_count": len(acks), "tombstone_count": 1 if req else 0}
    return json.dumps(out)
PY

cat > "$T/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, subprocess, sys
sys.path.insert(0, os.environ["PRIV001_FAKE_DIR"])
import fakeapi
a = sys.argv[1:]
fakeapi.log_argv("docker", a)
s = fakeapi.load()
if a[:1] == ["ps"]:
    svc = next(x[len("label=com.docker.compose.service="):] for x in a if x.startswith("label=com.docker.compose.service="))
    for name in s["containers"].get(svc, []):
        print(name)
    sys.exit(0)
if a[:2] == ["exec", "-i"]:
    ctr, rest = a[2], a[3:]
    if rest[:2] == ["curl", "--config"]:
        conf = sys.stdin.read()
        def unq(v):
            v = v.strip()
            assert v.startswith('"') and v.endswith('"'), v
            v = v[1:-1]
            out, i = "", 0
            while i < len(v):
                if v[i] == "\\" and i + 1 < len(v):
                    out += {"n": "\n", "t": "\t"}.get(v[i + 1], v[i + 1]); i += 2
                else:
                    out += v[i]; i += 1
            return out
        url = method = body = None
        headers = []
        for line in conf.splitlines():
            k, _, v = line.partition(" = ")
            if k == "url": url = unq(v)
            elif k == "request": method = unq(v)
            elif k == "header": headers.append(unq(v))
            elif k == "data-binary": body = unq(v)
        assert ctr in s["containers"]["gateway-service"], ctr
        assert url.startswith("http://localhost:8080/"), url
        code, payload = fakeapi.gateway(method, url[len("http://localhost:8080"):], headers, body)
        sys.stdout.write(json.dumps(payload) + "\n" + str(code))
        sys.exit(0)
    if rest[:2] == ["sh", "-c"]:
        env = {"PATH": os.environ["PRIV001_FAKE_BIN"] + ":/usr/bin:/bin", "PRIV001_FAKE_DIR": os.environ["PRIV001_FAKE_DIR"],
               "PRIV001_FAKE_STATE": os.environ["PRIV001_FAKE_STATE"], "PRIV001_FAKE_ARGV_LOG": os.environ["PRIV001_FAKE_ARGV_LOG"]}
        env.update(s["env"].get(ctr, {}))
        p = subprocess.run(["sh", "-c", rest[2], *rest[3:]], env=env, stdin=sys.stdin)
        sys.exit(p.returncode)
sys.stderr.write(f"fake docker: unhandled {a}\n")
sys.exit(2)
PY

cat > "$T/bin/curl" <<'PY'
#!/usr/bin/env python3
import json, os, sys
sys.path.insert(0, os.environ["PRIV001_FAKE_DIR"])
import fakeapi
a = sys.argv[1:]
fakeapi.log_argv("curl", a)
headers, i = [], 0
while i < len(a):
    if a[i] == "-H" and a[i + 1] == "@-":
        headers += [l for l in sys.stdin.read().splitlines() if l]
        i += 2
    elif a[i] == "-H":
        headers.append(a[i + 1]); i += 2
    else:
        i += 1
assert a[-1] == "http://localhost:8081/internal/auth/registration-invites", a[-1]
code, payload = fakeapi.invite(headers)
sys.stdout.write(json.dumps(payload) + "\n" + str(code))
PY

cat > "$T/bin/psql" <<'PY'
#!/usr/bin/env python3
import os, sys
sys.path.insert(0, os.environ["PRIV001_FAKE_DIR"])
import fakeapi
a = sys.argv[1:]
fakeapi.log_argv("psql", a)
assert a[:5] == ["--set=ON_ERROR_STOP=1", "-U", "parkio_auth_civo", "-d", "parkio_auth_civo"], a[:5]
sql = a[a.index("-c") + 1]
print(fakeapi.psql(sql))
PY
chmod +x "$T/bin/docker" "$T/bin/curl" "$T/bin/psql"

state() {  # gateways invite_creation erasure_enabled down(comma) other_requests
  python3 -I - "$T/state.json" "$OPERATOR_TOKEN" "$GATEWAY_SECRET" "$@" <<'PY'
import json, sys
path, op, gw, gateways, creation, erasure, down, other = sys.argv[1:9]
prev = {}
try:
    prev = json.load(open(path))
except Exception:
    pass
s = {"containers": {"gateway-service": [f"parkio-gateway-service-{i}" for i in range(1, int(gateways) + 1)],
                    "auth-service": ["parkio-auth-service-1"], "postgres-auth": ["parkio-postgres-auth"]},
     "env": {"parkio-auth-service-1": {"PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN": op if creation != "notoken" else "",
                                       "PARKIO_GATEWAY_INTERNAL_SECRET": gw},
             "parkio-postgres-auth": {"POSTGRES_USER": "parkio_auth_civo", "POSTGRES_DB": "parkio_auth_civo"}},
     "operatorToken": op, "gatewaySecret": gw, "inviteCreation": creation == "on",
     "erasureEnabled": erasure == "on", "down": [d for d in down.split(",") if d],
     "participants": ["user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation"],
     "otherRequests": int(other), "invites": prev.get("invites", {}), "users": prev.get("users", {}),
     "requests": prev.get("requests", [])}
json.dump(s, open(path, "w"))
PY
}
export PRIV001_FAKE_DIR="$T" PRIV001_FAKE_STATE="$T/state.json" PRIV001_FAKE_ARGV_LOG="$T/argv.log" PRIV001_FAKE_BIN="$T/bin"
CIVO_HOST="$(hostname)"
export PATH="$T/bin:$PATH" PARKIO_PRIV001_CIVO_HOST="$CIVO_HOST" TMPDIR="$T"
unset PARKIO_DEPLOYMENT_PROFILE
: > "$T/argv.log"
CREATE=scripts/acceptance/create-priv001-synthetic-principal.sh
ERASE=scripts/acceptance/erase-priv001-synthetic-principal.sh
INSPECT=scripts/acceptance/inspect-priv001-synthetic-residue.sh
run() { local out="$1"; shift; if "$@" >"$T/$out" 2>&1; then echo "exit=0" >> "$T/$out"; else echo "exit=$?" >> "$T/$out"; fi; }

echo "=== civo-production opt-in guards ==="
state 1 on off "" 0
PARKIO_PRIV001_CIVO_HOST=not-this-host run g1.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --dry-run-guards
has "$T/g1.out" "runs only on not-this-host"; has "$T/g1.out" "exit=2"
run g2.out bash "$CREATE" --environment civo-production --dry-run-guards
has "$T/g2.out" "requires --confirm-synthetic-only"; has "$T/g2.out" "exit=2"
run g3.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --gateway-url http://127.0.0.1:8080 --dry-run-guards
has "$T/g3.out" "--gateway-url does not apply to civo-production"
run g4.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --dry-run-guards
has "$T/g4.out" "PASS: dry-run guards"; has "$T/g4.out" "environment=civo-production"
has "$T/g4.out" "registration=one-time-operator-invite"; has "$T/g4.out" "verification=allowlisted-auth-sql-docker-exec"
[ ! -s "$T/argv.log" ] && ok "guards and dry run touch no container" || no "guards called docker: $(cat "$T/argv.log")"
PARKIO_DEPLOYMENT_PROFILE=invite-production run g5.out bash "$CREATE" --environment invite-production --confirm-synthetic-only --dry-run-guards
has "$T/g5.out" "verification=allowlisted-managed-auth-sql"; hasnt "$T/g5.out" "civo-production"
run g6.out bash "$CREATE" --environment staging --confirm-synthetic-only --dry-run-guards
has "$T/g6.out" "requires --environment invite-production"

echo "=== civo-production create ==="
CREDS="$T/creds"; EVID="$T/create-evidence.json"
run c1.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --credentials-file "$CREDS" --evidence-file "$EVID"
cat "$T/c1.out"
has "$T/c1.out" "PRIV-001A: invite=PASS"; has "$T/c1.out" "PRIV-001A: verify=PASS"; has "$T/c1.out" "PRIV-001A: login=PASS status=ACTIVE"
has "$T/c1.out" "PASS: PRIV-001A synthetic principal created"; has "$T/c1.out" "auth_me=SEEDED"; has "$T/c1.out" "exit=0"
[ "$(stat -c %a "$CREDS")" = 600 ] && ok "credentials file 600" || no "credentials mode $(stat -c %a "$CREDS")"
python3 -I - "$EVID" <<'PY' && ok "create evidence names civo-production and the invite route" || no "create evidence"
import json, sys
e = json.load(open(sys.argv[1]))
assert e["environment"] == "civo-production" and "one-time operator invite" in e["registration"]
assert e["verification"] == "allowlisted-auth-sql-docker-exec" and e["secretsInEvidence"] is False
PY
python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); assert list(s["invites"].values())==["consumed"]' "$T/state.json" \
  && ok "exactly one invite issued and consumed by the registration" || no "invite accounting"
PASSWORD="$(sed -n 's/^password=//p' "$CREDS")"; ACCESS="$(sed -n 's/^access_token=//p' "$CREDS")"
REFRESH="$(sed -n 's/^refresh_token=//p' "$CREDS")"
INVITE="$(python3 -I -c 'import json,sys; print(list(json.load(open(sys.argv[1]))["invites"])[0])' "$T/state.json")"
secrets_clean() {  # label files...
  local label="$1"; shift
  local leak=""
  for v in "$PASSWORD" "$ACCESS" "$REFRESH" "$INVITE" "$OPERATOR_TOKEN" "$GATEWAY_SECRET"; do
    [ -n "$v" ] || continue
    if grep -qF -- "$v" "$@" 2>/dev/null; then leak="yes"; fi
  done
  if [ -z "$leak" ]; then ok "$label: no password, token, invite, operator token or gateway secret"; else no "$label: a secret leaked"; fi
}
secrets_clean "argv of every docker/curl/psql call" "$T/argv.log"
secrets_clean "create output and evidence" "$T/c1.out" "$EVID"
grep -q '"curl", "--config", "-"' "$T/argv.log" && ok "gateway requests go as curl config on stdin" || no "no curl --config - call"
grep -q '"-H", "@-"' "$T/argv.log" && ok "invite headers go on stdin" || no "no -H @- call"

echo "=== civo-production create failures ==="
state 1 off off "" 0
run c2.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/creds2"
has "$T/c2.out" "invite creation failed http=403"; has "$T/c2.out" "exit=1"
state 1 notoken off "" 0
run c3.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/creds3"
has "$T/c3.out" "has no PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"; has "$T/c3.out" "exit=1"
state 2 on off "" 0
run c4.out bash "$CREATE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/creds4"
has "$T/c4.out" "expected exactly one running gateway-service container, found 2"

echo "=== civo-production erase ==="
state 1 on off analytics 0
run e1.out bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$CREDS" --delete --expect IN_PROGRESS
has "$T/e1.out" "DELETE failed http=403"; has "$T/e1.out" "exit=1"
state 1 on on analytics 1
WSTART="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run e2.out bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$CREDS" --delete \
  --expect IN_PROGRESS --hold-seconds 2 --poll-seconds 1 --window-start "$WSTART" --evidence-file "$T/erase-hold.json"
cat "$T/e2.out"
has "$T/e2.out" "delete=ACCEPTED http=202 status=IN_PROGRESS"; has "$T/e2.out" "success=7/8 missing=analytics"
has "$T/e2.out" "other_requests_since_window_start=1"; has "$T/e2.out" "expect=IN_PROGRESS verdict=PASS"; has "$T/e2.out" "exit=0"
state 1 on on "" 0
run e3.out bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$CREDS" \
  --expect COMPLETE --timeout-seconds 3 --poll-seconds 1 --evidence-file "$T/erase-complete.json"
has "$T/e3.out" "request=COMPLETE auth=ERASED success=8/8"; has "$T/e3.out" "expect=COMPLETE verdict=PASS"
python3 -I - "$T/erase-hold.json" "$T/erase-complete.json" <<'PY' && ok "erase evidence: hold PASS with analytics missing, then COMPLETE 8/8" || no "erase evidence"
import json, sys
h, c = (json.load(open(p)) for p in sys.argv[1:3])
assert h["verdict"] == "PASS" and h["delete"]["sent"] and h["observations"][-1]["missing"] == ["analytics"]
assert c["verdict"] == "PASS" and not c["delete"]["sent"] and c["observations"][-1]["successCount"] == 8
PY
run e4.out bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$CREDS" \
  --expect IN_PROGRESS --hold-seconds 2 --poll-seconds 1
has "$T/e4.out" "expect=IN_PROGRESS verdict=FAIL"; has "$T/e4.out" "exit=1"
run e5.out bash "$ERASE" --environment invite-production --confirm-synthetic-only --credentials-file "$CREDS"
has "$T/e5.out" "supports --environment civo-production only"
sed 's/^email=.*/email=someone@gmail.com/' "$CREDS" > "$T/creds-real"; chmod 600 "$T/creds-real"
run e6.out bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/creds-real"
has "$T/e6.out" "not an allowed PRIV-001A synthetic principal"
secrets_clean "argv after erase" "$T/argv.log"
secrets_clean "erase output and evidence" "$T/e2.out" "$T/e3.out" "$T/erase-hold.json" "$T/erase-complete.json"

echo "=== civo-production inspect ==="
UID_="$(sed -n 's/^auth_user_id=//p' "$CREDS")"
run i1.out bash "$INSPECT" --environment civo-production --confirm-synthetic-only --auth-user-id "$UID_"
has "$T/i1.out" "participant_acks"; has "$T/i1.out" "erasure_request_status"; has "$T/i1.out" "exit=0"
secrets_clean "inspect output" "$T/i1.out"

echo "=== civo-production SQL against a real PostgreSQL 16 (docker exec, the real auth migrations) ==="
REAL_PATH="${PATH#"$T/bin:"}"
if PATH="$REAL_PATH" docker info >/dev/null 2>&1; then
  PG="priv001-civo-it-$(openssl rand -hex 3)"
  trap 'PATH="$REAL_PATH" docker rm -f "$PG" >/dev/null 2>&1 || true; rm -rf -- "$T"' EXIT
  if PATH="$REAL_PATH" docker run -d --name "$PG" --label parkio.task=priv001-civo-it \
      --label com.docker.compose.project=priv001-civo-it --label com.docker.compose.service=postgres-auth \
      -e POSTGRES_USER=parkio_auth -e POSTGRES_DB=parkio_auth -e POSTGRES_PASSWORD=it-only postgres:16-alpine >/dev/null 2>&1; then
    for _ in $(seq 1 60); do PATH="$REAL_PATH" docker exec "$PG" pg_isready -q -U parkio_auth -d parkio_auth 2>/dev/null && break; sleep 1; done
    sleep 2
    pgq() { PATH="$REAL_PATH" docker exec -i "$PG" psql -q -At -v ON_ERROR_STOP=1 -U parkio_auth -d parkio_auth "$@"; }
    mig_ok=1
    for f in $(ls services/auth-service/src/main/resources/db/migration/V*.sql | sort -V); do
      pgq < "$f" >/dev/null 2>&1 || { mig_ok=0; echo "migration failed: $f" >&2; break; }
    done
    [ "$mig_ok" = 1 ] && ok "real PG: the auth-service migrations apply" || no "real PG: auth migrations"
    IT_EMAIL="priv001a-$(date -u +%Y%m%d%H%M%S)it01@priv001a.parkio.invalid"
    IT_UID="$(python3 -I -c 'import uuid; print(uuid.uuid4())')"
    pgq -c "insert into auth_users (id, email, password_hash, status) values ('$IT_UID', '$IT_EMAIL', 'x', 'PENDING_VERIFICATION')" >/dev/null
    it_env=(env PATH="$REAL_PATH" PARKIO_PRIV001_COMPOSE_PROJECT=priv001-civo-it)
    VERIFIED="$("${it_env[@]}" PARKIO_PRIV001_ENVIRONMENT=civo-production PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes \
      bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_mark_verified "$0" 900' "$IT_EMAIL" 2>"$T/it-verify.err")" || true
    [ "$VERIFIED" = "$IT_UID" ] && ok "real PG: the allowlisted verification through docker exec returns exactly that row" \
      || no "real PG verification returned '$VERIFIED' ($(head -2 "$T/it-verify.err" | tr '\n' ' '))"
    [ "$(pgq -c "select status||'/'||email_verified from auth_users where id = '$IT_UID'")" = "ACTIVE/true" ] \
      && ok "real PG: the row is ACTIVE and verified" || no "real PG: row state"
    if "${it_env[@]}" PARKIO_PRIV001_ENVIRONMENT=civo-production PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes \
        bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_mark_verified "$0" 900' "$IT_EMAIL" >/dev/null 2>&1; then
      no "real PG: a second verification must find no row"; else ok "real PG: a second verification finds no row (fail-closed)"; fi
    REQ="$(python3 -I -c 'import uuid; print(uuid.uuid4())')"
    pgq -c "update auth_users set status = 'ERASURE_IN_PROGRESS' where id = '$IT_UID';
            insert into erasure_requests (id, auth_user_id, status, requested_at) values ('$REQ', '$IT_UID', 'IN_PROGRESS', now());
            insert into erasure_service_acks (erasure_request_id, service_name, status, acked_at)
              select '$REQ', s, 'SUCCESS', now() from unnest(array['user','parking','media','moderation','gamification','notification','ai-validation']) s" >/dev/null
    printf 'acceptance_id=PRIV001A-IT\nauth_user_id=%s\nemail=%s\n' "$IT_UID" "$IT_EMAIL" > "$T/it-creds"; chmod 600 "$T/it-creds"
    run it1.out "${it_env[@]}" bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/it-creds" \
      --expect IN_PROGRESS --hold-seconds 1 --poll-seconds 1 --window-start 2026-01-01T00:00:00Z
    has "$T/it1.out" "request=IN_PROGRESS auth=ERASURE_IN_PROGRESS success=7/8 missing=analytics other_requests_since_window_start=0"
    has "$T/it1.out" "expect=IN_PROGRESS verdict=PASS"
    pgq -c "insert into erasure_service_acks (erasure_request_id, service_name, status, acked_at) values ('$REQ', 'analytics', 'SUCCESS', now());
            update erasure_requests set status = 'COMPLETE', completed_at = now() where id = '$REQ';
            update auth_users set status = 'ERASED' where id = '$IT_UID'" >/dev/null
    run it2.out "${it_env[@]}" bash "$ERASE" --environment civo-production --confirm-synthetic-only --credentials-file "$T/it-creds" \
      --expect COMPLETE --timeout-seconds 2 --poll-seconds 1
    has "$T/it2.out" "request=COMPLETE auth=ERASED success=8/8"; has "$T/it2.out" "expect=COMPLETE verdict=PASS"
    run it3.out "${it_env[@]}" bash "$INSPECT" --environment civo-production --confirm-synthetic-only --auth-user-id "$IT_UID"
    has "$T/it3.out" '"erasure_request_status" : "COMPLETE"'; has "$T/it3.out" '"analytics" : "SUCCESS"'; has "$T/it3.out" "exit=0"
  else
    no "real PG: postgres:16-alpine did not start"
  fi
else
  echo "SKIP: no Docker daemon for the real PostgreSQL section"
fi

echo "=== static: the civo code path puts no secret on a command line ==="
civo_src="$(sed -n '/^# --- Civo production mode/,/^# parkio_priv001_auth_psql/p' scripts/lib/priv001-synthetic.sh)"
if printf '%s\n' "$civo_src" | grep -E -- '--data-binary "\$|-H "Authorization|PGPASSWORD' >/dev/null; then
  no "the civo section passes a body, bearer or password in argv"
else ok "civo section: bodies and secret headers only via stdin"; fi
if grep -E -- '--data-binary "\$|-H "Authorization|PGPASSWORD' "$ERASE" >/dev/null; then no "erase script argv secret"; else ok "erase script: no argv secret"; fi
if grep -RniE 'PARKIO_EMAIL_VERIFICATION_BYPASS|email.verification.bypass|DISABLE_EMAIL_VERIFICATION' "$ERASE" scripts/lib/priv001-synthetic.sh; then
  no "global verification bypass marker"; else ok "no global verification bypass"; fi
if grep -En '^\s*(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER)\b' "$ERASE" | grep -vE '^[0-9]+:#'; then
  no "erase script contains mutating SQL"; else ok "erase script SQL is read-only"; fi

echo "=== summary pass=${pass} fail=${fail} ==="
[ "$fail" -eq 0 ] || exit 1
echo "PRIV-001A civo-production mode contract: PASS"
