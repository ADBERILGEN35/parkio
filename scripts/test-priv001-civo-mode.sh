#!/usr/bin/env bash
# Regression gates for the PRIV-001A harness's civo-production mode.
# Deterministic and offline except the last section: a fake `docker` stands in for `docker ps`
# (Compose labels), the gateway (`curl --config -` inside its container), the auth-service invite
# call (`sh -c` with headers on stdin) and the auth database (`sh -c` psql). Like the real CLI, the
# fake drains its caller's stdin when given `-i`. The fakes record their own argv; no password,
# token, invite, operator token or gateway secret may appear there, in any output, or in evidence.
# The last section runs the real SQL against a throwaway PostgreSQL 16 container with the
# auth-service migrations when a Docker daemon is available.
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
mkdir -p "$T/bin" "$T/hostbin"
OPERATOR_TOKEN="op-$(openssl rand -hex 24)"
GATEWAY_SECRET="gw-$(openssl rand -hex 16)"

cat > "$T/fakeapi.py" <<'PY'
"""Shared state and behavior of the fake gateway, invite endpoint and auth database."""
import base64, json, os, re, sys, time, uuid

STATE = os.environ["PRIV001_FAKE_STATE"]
WRITE = re.compile(r"\b(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER)\b")

def load():
    return json.load(open(STATE))

def save(s):
    json.dump(s, open(STATE, "w"))

def now():
    t = time.time()
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(t)) + ".%06dZ" % int((t % 1) * 1e6)

def log_argv(tool, argv):
    with open(os.environ["PRIV001_FAKE_ARGV_LOG"], "a") as f:
        f.write(json.dumps([tool] + argv) + "\n")

def secret(s, v):
    s.setdefault("issuedSecrets", []).append(v)
    return v

def jwt(sub):
    seg = lambda o: base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=")
    return seg({"alg": "RS256"}) + "." + seg({"sub": sub, "jti": uuid.uuid4().hex}) + "." + uuid.uuid4().hex

def policy_ok(p):
    return len(p) >= 12 and re.search("[a-z]", p) and re.search("[A-Z]", p) and re.search("[0-9]", p)

def gateway(method, path, headers, body):
    s = load()
    bearer = ""
    for h in headers:
        if h.lower().startswith("authorization: bearer "):
            bearer = h.split(" ", 2)[2]
    data = json.loads(body) if body else {}
    if path == "/api/v1/auth/registration-mode" and method == "GET":
        return (200, {"mode": s["mode"]}) if s["mode"] else (503, {"code": "UNAVAILABLE"})
    if path == "/api/v1/auth/register" and method == "POST":
        if s["mode"] == "CLOSED":
            return 403, {"code": "REGISTRATION_CLOSED"}
        if not policy_ok(data.get("password", "")):
            return 400, {"code": "WEAK_PASSWORD"}
        if s.get("registerFails"):
            return 503, {"code": "UNAVAILABLE"}
        if s["mode"] == "INVITE":
            inv = s["invites"].get(data.get("inviteToken") or "")
            if not inv or inv["state"] != "issued":
                return 403, {"code": "REGISTRATION_INVITE_INVALID"}
            inv["state"] = "consumed"
        uid = str(uuid.uuid4())
        s["users"][data["email"]] = {"id": uid, "status": "PENDING_VERIFICATION", "password": secret(s, data["password"])}
        save(s)
        return 201, {"user": {"id": uid, "status": "PENDING_VERIFICATION"}}
    if path == "/api/v1/auth/login" and method == "POST":
        if s.get("loginFails"):
            return 503, {"code": "UNAVAILABLE"}
        u = s["users"].get(data.get("email"))
        if not u or u["password"] != data.get("password") or u["status"] != "ACTIVE":
            return 401, {"code": "INVALID_CREDENTIALS"}
        u["access"] = secret(s, jwt(s.get("loginSubOverride") or u["id"]))
        u["refresh"] = secret(s, "rt-" + uuid.uuid4().hex)
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
        s["requests"].append({"id": rid, "authUserId": u["id"], "status": "IN_PROGRESS", "acks": {}})
        u["status"] = "ERASURE_IN_PROGRESS"
        s["deletes"] = s.get("deletes", 0) + 1
        save(s)
        return 200, {"erasureRequestId": rid, "status": "IN_PROGRESS"}
    return 404, {"code": "NOT_FOUND"}

def invite(headers):
    s = load()
    hd = dict(h.split(": ", 1) for h in headers if ": " in h)
    if not s["inviteCreation"]:
        return 403, {"code": "FORBIDDEN", "message": "Registration invite creation is disabled."}
    if hd.get("X-Parkio-Registration-Invite-Operator-Token") != s["operatorToken"] or hd.get("X-Gateway-Auth") != s["gatewaySecret"]:
        return 403, {"code": "FORBIDDEN"}
    tok = secret(s, uuid.uuid4().hex + "_-" + uuid.uuid4().hex[:8])
    s["invites"][tok] = {"state": "issued", "createdAt": now(), "createdBy": "priv001a"}
    save(s)
    return 201, {"token": tok, "expiresAt": "2026-10-20T00:00:00Z"}

def psql(sql):
    if "default_transaction_read_only=on" in os.environ.get("PGOPTIONS", "") and WRITE.search(sql):
        sys.stderr.write("ERROR:  cannot execute UPDATE in a read-only transaction\n")
        sys.exit(1)
    s = load()
    if sql.lstrip().startswith("BEGIN;") and "UPDATE auth_users" in sql:
        m = re.search(r"WHERE email = '([^']+)'", sql)
        u = s["users"].get(m.group(1)) if m else None
        if not u or u["status"] != "PENDING_VERIFICATION" or s.get("verifyFails"):
            return "BEGIN\nCOMMIT"
        u["status"] = "ACTIVE"
        save(s)
        return "BEGIN\n" + u["id"] + "\nUPDATE 1\nCOMMIT"
    if "to_char(now()" in sql:
        return now()
    if "FROM registration_invites" in sql:
        since = re.search(r"created_at >= '([^']+)'::timestamptz", sql).group(1)
        rows = [i for i in s["invites"].values() if i["createdBy"] == "priv001a" and i["createdAt"] >= since]
        return f"{len(rows)}|{sum(1 for i in rows if i['state'] == 'issued')}"
    if sql.lstrip().startswith("SELECT id, email, status FROM auth_users WHERE"):
        m_id = re.search(r"id = '([0-9a-f-]{36})'::uuid", sql)
        m_em = re.search(r"email = '([^']+)'", sql)
        for email, u in s["users"].items():
            current = u.get("tombstone") or email
            if (m_id and u["id"] == m_id.group(1)) or (m_em and current == m_em.group(1)):
                return f"{u['id']}|{current}|{u['status']}"
        return ""
    m = re.search(r"auth_user_id = '([0-9a-f-]{36})'::uuid", sql)
    uid = m.group(1) if m else ""
    user = next((u for u in s["users"].values() if u["id"] == uid), None)
    reqs = [r for r in s["requests"] if r["authUserId"] == uid]
    req = reqs[-1] if reqs else None
    acks = {}
    if req is not None:
        if req["status"] == "IN_PROGRESS":
            req["acks"] = {p: "SUCCESS" for p in s["participants"] if p not in s["down"] and p not in s["failed"]}
            req["acks"].update({p: "FAILED" for p in s["failed"]})
            if s["failed"]:
                req["status"] = "FAILED_RETRYING"
            elif not s["down"]:
                req["status"] = "COMPLETE"
                user["status"] = "ERASED"
                user["tombstone"] = f"erased-{uid}@invalid.localhost"
            save(s)
        acks = req["acks"]
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
    if s.get("psFails"):
        sys.exit(1)
    def label(key):
        vals = [x[len("label=" + key + "="):] for x in a if x.startswith("label=" + key + "=")]
        assert len(vals) == 1, (key, a)
        return vals[0]
    if label("com.docker.compose.project") == s["project"]:
        for name in s["containers"].get(label("com.docker.compose.service"), []):
            print(name)
    sys.exit(0)
if a[:1] == ["exec"]:
    i, interactive, extra = 1, False, {}
    while a[i].startswith("-"):
        if a[i] == "-i":
            interactive, i = True, i + 1
        elif a[i] == "-e":
            k, _, v = a[i + 1].partition("=")
            extra[k] = v
            i += 2
        else:
            sys.stderr.write(f"fake docker: unhandled exec option {a[i]}\n")
            sys.exit(2)
    ctr, rest = a[i], a[i + 1:]
    # Like the real CLI: with -i the caller's stdin is attached and drained; without -i it is not touched.
    data = sys.stdin.buffer.read() if interactive else b""
    if rest[:2] == ["curl", "--config"]:
        assert interactive, "curl --config - needs -i"
        def unq(v):
            v = v.strip()
            assert v.startswith('"') and v.endswith('"'), v
            v = v[1:-1]
            out, j = "", 0
            while j < len(v):
                if v[j] == "\\" and j + 1 < len(v):
                    out += {"n": "\n", "t": "\t"}.get(v[j + 1], v[j + 1]); j += 2
                else:
                    out += v[j]; j += 1
            return out
        url = method = body = None
        headers = []
        for line in data.decode().splitlines():
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
        env.update(extra)
        p = subprocess.run(["sh", "-c", rest[2], *rest[3:]], env=env, input=data)
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

cat > "$T/hostbin/hostname" <<'SH'
#!/bin/sh
if [ "${1-}" = "-s" ]; then echo parkio-civo-prod; else echo parkio-civo-prod.example.internal; fi
SH
chmod +x "$T/bin/docker" "$T/bin/curl" "$T/bin/psql" "$T/hostbin/hostname"

state() {  # gateways invite_creation erasure_enabled down(comma) other_requests [mode] [failed(comma)]
  python3 -I - "$T/state.json" "$OPERATOR_TOKEN" "$GATEWAY_SECRET" "$@" <<'PY'
import json, sys
path, op, gw, gateways, creation, erasure, down, other = sys.argv[1:9]
mode = sys.argv[9] if len(sys.argv) > 9 else "INVITE"
failed = sys.argv[10] if len(sys.argv) > 10 else ""
prev = {}
try:
    prev = json.load(open(path))
except Exception:
    pass
s = {"project": "parkio",
     "containers": {"gateway-service": [f"parkio-gateway-service-{i}" for i in range(1, int(gateways) + 1)],
                    "auth-service": ["parkio-auth-service-1"], "postgres-auth": ["parkio-postgres-auth"]},
     "env": {"parkio-auth-service-1": {"PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN": op if creation != "notoken" else "",
                                       "PARKIO_GATEWAY_INTERNAL_SECRET": gw},
             "parkio-postgres-auth": {"POSTGRES_USER": "parkio_auth_civo", "POSTGRES_DB": "parkio_auth_civo"}},
     "operatorToken": op, "gatewaySecret": gw, "inviteCreation": creation == "on",
     "erasureEnabled": erasure == "on", "down": [d for d in down.split(",") if d],
     "failed": [d for d in failed.split(",") if d], "mode": mode,
     "participants": ["user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation"],
     "otherRequests": int(other)}
for k in ("invites", "users", "requests", "issuedSecrets", "deletes"):
    if k in prev:
        s[k] = prev[k]
s.setdefault("invites", {}); s.setdefault("users", {}); s.setdefault("requests", [])
s.setdefault("issuedSecrets", [op, gw]); s.setdefault("deletes", 0)
json.dump(s, open(path, "w"))
PY
}
knob() {  # knob <key> <JSON value>
  python3 -I - "$T/state.json" "$1" "$2" <<'PY'
import json, sys
p, k, v = sys.argv[1:4]
s = json.load(open(p))
s[k] = json.loads(v)
json.dump(s, open(p, "w"))
PY
}
count() {  # count invites|deletes
  python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); v=s[sys.argv[2]]; print(len(v) if isinstance(v, dict) else v)' "$T/state.json" "$1"
}
secrets_clean() {  # label files...: no issued password, token, invite, operator token or gateway secret in them
  local label="$1"; shift
  if python3 -I - "$T/state.json" "$@" <<'PY'
import json, sys
vals = [v for v in json.load(open(sys.argv[1])).get("issuedSecrets", []) if v]
for f in sys.argv[2:]:
    try:
        text = open(f, encoding="utf-8", errors="replace").read()
    except FileNotFoundError:
        continue
    if any(v in text for v in vals):
        sys.exit(1)
PY
  then ok "$label: no password, token, invite, operator token or gateway secret"; else no "$label: a secret leaked"; fi
}
export PRIV001_FAKE_DIR="$T" PRIV001_FAKE_STATE="$T/state.json" PRIV001_FAKE_ARGV_LOG="$T/argv.log" PRIV001_FAKE_BIN="$T/bin"
CIVO_HOST="$(hostname)"
export PATH="$T/bin:$PATH" PARKIO_PRIV001_CIVO_HOST="$CIVO_HOST" TMPDIR="$T"
unset PARKIO_DEPLOYMENT_PROFILE PARKIO_ACCOUNT_ERASURE_PARTICIPANTS
: > "$T/argv.log"
CREATE=scripts/acceptance/create-priv001-synthetic-principal.sh
ERASE=scripts/acceptance/erase-priv001-synthetic-principal.sh
INSPECT=scripts/acceptance/inspect-priv001-synthetic-residue.sh
# stdin is /dev/null: a harness call that reads stdin cannot hang the test; the stdin-fed caller section checks that.
run() { local out="$1"; shift; if "$@" </dev/null >"$T/$out" 2>&1; then echo "exit=0" >> "$T/$out"; else echo "exit=$?" >> "$T/$out"; fi; }
OPTS=(--environment civo-production --confirm-synthetic-only)

echo "=== civo-production opt-in guards ==="
state 1 on off "" 0
PARKIO_PRIV001_CIVO_HOST=not-this-host run g1.out bash "$CREATE" "${OPTS[@]}" --dry-run-guards
has "$T/g1.out" "runs only on not-this-host"; has "$T/g1.out" "exit=2"; has "$T/g1.out" "is a test override"
run g2.out bash "$CREATE" --environment civo-production --dry-run-guards
has "$T/g2.out" "requires --confirm-synthetic-only"; has "$T/g2.out" "exit=2"
run g3.out bash "$CREATE" "${OPTS[@]}" --gateway-url http://127.0.0.1:8080 --dry-run-guards
has "$T/g3.out" "--gateway-url does not apply to civo-production"; has "$T/g3.out" "exit=2"
run g4.out bash "$CREATE" "${OPTS[@]}" --dry-run-guards
has "$T/g4.out" "PASS: dry-run guards"; has "$T/g4.out" "environment=civo-production"
has "$T/g4.out" "registration=one-time-operator-invite"; has "$T/g4.out" "verification=allowlisted-auth-sql-docker-exec"
[ ! -s "$T/argv.log" ] && ok "guards and dry run touch no container" || no "guards called docker: $(cat "$T/argv.log")"
PARKIO_DEPLOYMENT_PROFILE=invite-production run g5.out bash "$CREATE" --environment invite-production --confirm-synthetic-only --dry-run-guards
has "$T/g5.out" "verification=allowlisted-managed-auth-sql"; hasnt "$T/g5.out" "civo-production"
run g6.out bash "$CREATE" --environment staging --confirm-synthetic-only --dry-run-guards
has "$T/g6.out" "requires --environment invite-production or civo-production"
run g7.out env -u PARKIO_PRIV001_CIVO_HOST PATH="$T/hostbin:$PATH" bash "$CREATE" "${OPTS[@]}" --dry-run-guards
has "$T/g7.out" "PASS: dry-run guards"; hasnt "$T/g7.out" "test override"

echo "=== civo-production create ==="
CREDS="$T/creds"; EVID="$T/create-evidence.json"
run c1.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$CREDS" --evidence-file "$EVID"
cat "$T/c1.out"
has "$T/c1.out" "PRIV-001A: registration mode INVITE; containers resolved"
has "$T/c1.out" "PRIV-001A: invite=PASS"; has "$T/c1.out" "PRIV-001A: verify=PASS"; has "$T/c1.out" "PRIV-001A: login=PASS status=ACTIVE"
has "$T/c1.out" "PRIV-001A: invite accounting PASS (created 1, unconsumed 0)"
has "$T/c1.out" "PASS: PRIV-001A synthetic principal created"; has "$T/c1.out" "auth_me=SEEDED"; has "$T/c1.out" "exit=0"
[ "$(stat -c %a "$CREDS")" = 600 ] && ok "credentials file 600" || no "credentials mode $(stat -c %a "$CREDS")"
python3 -I - "$EVID" <<'PY' && ok "create evidence: civo-production, invite route, mode INVITE, invite accounting 1/0" || no "create evidence"
import json, sys
e = json.load(open(sys.argv[1]))
assert e["environment"] == "civo-production" and "one-time operator invite" in e["registration"]
assert e["verification"] == "allowlisted-auth-sql-docker-exec" and e["secretsInEvidence"] is False
assert e["registrationMode"] == "INVITE" and e["inviteAccounting"]["created"] == 1 and e["inviteAccounting"]["unconsumed"] == 0
PY
python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); assert [i["state"] for i in s["invites"].values()]==["consumed"]' "$T/state.json" \
  && ok "exactly one invite issued and consumed by the registration" || no "invite accounting"
secrets_clean "argv of every docker/curl/psql call" "$T/argv.log"
secrets_clean "create output and evidence" "$T/c1.out" "$EVID"
grep -q '"curl", "--config", "-"' "$T/argv.log" && ok "gateway requests go as curl config on stdin" || no "no curl --config - call"
grep -q '"-H", "@-"' "$T/argv.log" && ok "invite headers go on stdin" || no "no -H @- call"

echo "=== civo-production create refusals: none may leave an invite ==="
n0="$(count invites)"
state 1 off off "" 0
run c2.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds2"
has "$T/c2.out" "invite creation failed http=403"; has "$T/c2.out" "exit=1"
state 1 notoken off "" 0
run c3.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds3"
has "$T/c3.out" "has no PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"; has "$T/c3.out" "exit=1"
state 2 on off "" 0
run c4.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds4"
has "$T/c4.out" "expected exactly one running gateway-service container, found 2"; has "$T/c4.out" "exit=2"
state 1 on off "" 0 CLOSED
run c5.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds5"
has "$T/c5.out" "registration mode is CLOSED"; has "$T/c5.out" "exit=1"
state 1 on off "" 0 OPEN
run c6.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds6"
has "$T/c6.out" "registration mode is OPEN"; has "$T/c6.out" "exit=1"
state 1 on off "" 0 ""
run c7.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds7"
has "$T/c7.out" "cannot read the registration mode"; has "$T/c7.out" "exit=1"
state 1 on off "" 0; knob project '"another-project"'
run c8.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds8"
has "$T/c8.out" "found 0"; has "$T/c8.out" "exit=2"
state 1 on off "" 0; knob psFails true
run c9.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds9"
has "$T/c9.out" "docker ps failed"; has "$T/c9.out" "exit=2"
[ "$(count invites)" = "$n0" ] && ok "no refusal issued an invite" || no "a refusal left an invite ($(count invites) vs ${n0})"

echo "=== civo-production erase ==="
state 1 on off analytics 0
run e1.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --delete --expect IN_PROGRESS
has "$T/e1.out" "DELETE failed http=403"; has "$T/e1.out" "ACCOUNT_ERASURE_DISABLED"; has "$T/e1.out" "exit=1"
state 1 on on analytics 1
python3 -I - "$T/state.json" <<'PY'
import json, sys
s = json.load(open(sys.argv[1]))
for u in s["users"].values():
    u["access"] = ""
json.dump(s, open(sys.argv[1], "w"))
PY
WSTART="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run e2.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --delete \
  --expect IN_PROGRESS --hold-seconds 2 --poll-seconds 1 --window-start "$WSTART" --evidence-file "$T/erase-hold.json"
cat "$T/e2.out"
has "$T/e2.out" "delete=ACCEPTED http=200 status=IN_PROGRESS"; has "$T/e2.out" "success=7/8 missing=analytics"
has "$T/e2.out" "other_requests_since_window_start=1"; has "$T/e2.out" "expect=IN_PROGRESS status=IN_PROGRESS verdict=PASS"
has "$T/e2.out" "exit=0"
state 1 on on "" 0
run e3.out env PARKIO_ACCOUNT_ERASURE_PARTICIPANTS=user bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" \
  --expect COMPLETE --timeout-seconds 3 --poll-seconds 1 --evidence-file "$T/erase-complete.json"
has "$T/e3.out" "PARKIO_ACCOUNT_ERASURE_PARTICIPANTS in this shell is ignored"
has "$T/e3.out" "request=COMPLETE auth=ERASED success=8/8"; has "$T/e3.out" "expect=COMPLETE status=COMPLETE verdict=PASS"
python3 -I - "$T/erase-hold.json" "$T/erase-complete.json" <<'PY' && ok "erase evidence: hold PASS 7/8 with a bound fresh token, then COMPLETE 8/8" || no "erase evidence"
import json, sys
h, c = (json.load(open(p)) for p in sys.argv[1:3])
assert h["verdict"] == "PASS" and h["delete"]["sent"] and h["delete"]["httpStatus"] == "200"
assert h["finalRequestStatus"] == "IN_PROGRESS" and h["observations"][-1]["missing"] == ["analytics"]
assert h["binding"]["tokenSubjectMatches"] is True and len(h["expectedParticipants"]) == 8
assert c["verdict"] == "PASS" and not c["delete"]["sent"] and c["observations"][-1]["successCount"] == 8
assert c["binding"]["tokenSubjectMatches"] is None and c["failReason"] is None
PY
run e4.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --expect IN_PROGRESS --hold-seconds 2 --poll-seconds 1
has "$T/e4.out" "the request reached COMPLETE during the hold"; has "$T/e4.out" "expect=IN_PROGRESS status=COMPLETE verdict=FAIL"
has "$T/e4.out" "exit=1"
run e5.out bash "$ERASE" --environment invite-production --confirm-synthetic-only --credentials-file "$CREDS"
has "$T/e5.out" "supports --environment civo-production only"
sed 's/^email=.*/email=someone@gmail.com/' "$CREDS" > "$T/creds-real"; chmod 600 "$T/creds-real"
run e6.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-real"
has "$T/e6.out" "not an allowed PRIV-001A synthetic principal"
run e7.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --window-start 2026-10-13
has "$T/e7.out" "--window-start must be UTC"; has "$T/e7.out" "exit=2"
run e8.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --hold-seconds 0
has "$T/e8.out" "must be integers in [1, 3600]"; has "$T/e8.out" "exit=2"
run e9.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$CREDS" --expect DONE
has "$T/e9.out" "--expect must be COMPLETE or IN_PROGRESS"; has "$T/e9.out" "exit=2"

echo "=== erase binding: the DELETE reaches only the file's synthetic principal ==="
state 1 on on "" 0
run c10.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds-p2"
has "$T/c10.out" "exit=0"
OTHER_ID="$(python3 -I - "$T/state.json" <<'PY'
import json, sys, uuid
s = json.load(open(sys.argv[1]))
uid = str(uuid.uuid4())
s["users"]["someone@example.com"] = {"id": uid, "status": "ACTIVE", "password": "Unrelated-Passw0rd"}
json.dump(s, open(sys.argv[1], "w"))
print(uid)
PY
)"
sed "s/^auth_user_id=.*/auth_user_id=${OTHER_ID}/" "$T/creds-p2" > "$T/creds-swapped"; chmod 600 "$T/creds-swapped"
d0="$(count deletes)"
run b1.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-swapped" --delete --expect IN_PROGRESS --hold-seconds 1 --poll-seconds 1
has "$T/b1.out" "does not hold the credentials file's synthetic email; refusing"; has "$T/b1.out" "exit=1"
knob loginSubOverride "\"${OTHER_ID}\""
run b2.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-p2" --delete --expect IN_PROGRESS --hold-seconds 1 --poll-seconds 1
has "$T/b2.out" "the fresh access token's subject is not"; has "$T/b2.out" "exit=1"
knob loginSubOverride '""'
[ "$(count deletes)" = "$d0" ] && ok "no DELETE was sent for a mismatched binding" || no "a DELETE was sent despite the binding"

echo "=== a FAILED ACK (FAILED_RETRYING) never passes as IN_PROGRESS ==="
state 1 on on "" 0 INVITE analytics
run f1.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-p2" --delete --expect IN_PROGRESS --hold-seconds 2 --poll-seconds 1
has "$T/f1.out" "delete=ACCEPTED http=200"; has "$T/f1.out" "the request is FAILED_RETRYING (a participant sent a FAILED ACK)"
has "$T/f1.out" "expect=IN_PROGRESS status=FAILED_RETRYING verdict=FAIL"; has "$T/f1.out" "exit=1"
run f2.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-p2" --delete
has "$T/f2.out" "--delete needs account"; has "$T/f2.out" "status=ERASURE_IN_PROGRESS"; has "$T/f2.out" "exit=1"

echo "=== a create that stops after registration still leaves an erasable principal ==="
state 1 on on "" 0; knob registerFails true
run o1.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds-o1"
has "$T/o1.out" "register failed http=503"; has "$T/o1.out" "exit=1"
grep -q '^password=' "$T/creds-o1" && ! grep -q '^auth_user_id=' "$T/creds-o1" \
  && ok "the credentials file holds the password before the register call" || no "credentials before register"
state 1 on on "" 0
run o1e.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-o1" --delete --expect COMPLETE --timeout-seconds 3 --poll-seconds 1
has "$T/o1e.out" "no auth account holds the credentials file's email (nothing to erase)"; has "$T/o1e.out" "exit=4"
state 1 on on "" 0; knob verifyFails true
run o2.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds-o2"
has "$T/o2.out" "verification did not return exactly one auth user id"; has "$T/o2.out" "exit=1"
grep -q '^auth_user_id=' "$T/creds-o2" && ok "the credentials file records auth_user_id right after registration" || no "auth_user_id after register"
state 1 on on "" 0
run o2e.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-o2" --delete --expect COMPLETE --timeout-seconds 3 --poll-seconds 1
has "$T/o2e.out" "the account was PENDING_VERIFICATION; applied the allowlisted verification first"
has "$T/o2e.out" "expect=COMPLETE status=COMPLETE verdict=PASS"; has "$T/o2e.out" "exit=0"
state 1 on on "" 0; knob loginFails true
run o3.out bash "$CREATE" "${OPTS[@]}" --credentials-file "$T/creds-o3"
has "$T/o3.out" "login failed http=503"; has "$T/o3.out" "exit=1"
state 1 on on "" 0
sed '/^auth_user_id=/d' "$T/creds-o3" > "$T/creds-o3-noid"; chmod 600 "$T/creds-o3-noid"
run o3e.out bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/creds-o3-noid" --delete --expect COMPLETE --timeout-seconds 3 --poll-seconds 1
has "$T/o3e.out" "found by the allowlisted email (the file had none)"
has "$T/o3e.out" "expect=COMPLETE status=COMPLETE verdict=PASS"; has "$T/o3e.out" "exit=0"

echo "=== civo-production inspect ==="
state 1 on on "" 0
UID_="$(sed -n 's/^auth_user_id=//p' "$CREDS")"
run i1.out bash "$INSPECT" "${OPTS[@]}" --auth-user-id "$UID_"
has "$T/i1.out" "participant_acks"; has "$T/i1.out" "erasure_request_status"; has "$T/i1.out" "exit=0"

echo "=== a caller that feeds its commands on stdin keeps them (no docker exec -i drains it) ==="
cat > "$T/caller.sh" <<EOF
bash $CREATE --environment civo-production --confirm-synthetic-only --credentials-file $T/creds-stdin >/dev/null 2>&1; echo "AFTER-CREATE rc=\$?"
bash $INSPECT --environment civo-production --confirm-synthetic-only --auth-user-id $UID_ >/dev/null 2>&1; echo "AFTER-INSPECT rc=\$?"
bash $ERASE --environment civo-production --confirm-synthetic-only --credentials-file $CREDS --expect COMPLETE --timeout-seconds 2 --poll-seconds 1 >/dev/null 2>&1; echo "AFTER-ERASE rc=\$?"
EOF
bash -s < "$T/caller.sh" > "$T/stdin.out" 2>&1 || true
has "$T/stdin.out" "AFTER-CREATE rc=0"; has "$T/stdin.out" "AFTER-INSPECT rc=0"; has "$T/stdin.out" "AFTER-ERASE rc=0"
printf 'docker exec -i parkio-auth-service-1 sh -c true\necho AFTER-DRAIN\n' > "$T/drain.sh"
PRIV001_FAKE_ARGV_LOG="$T/argv-control.log" bash -s < "$T/drain.sh" > "$T/drain.out" 2>&1 || true
if grep -qF AFTER-DRAIN "$T/drain.out"; then no "control: the fake docker did not drain stdin on -i"
else ok "control: the fake docker drains a stdin-fed caller on -i, as the real CLI does"; fi

echo "=== argv: secrets, stdin and read-only sessions ==="
secrets_clean "argv of every docker/curl/psql call" "$T/argv.log"
secrets_clean "erase and inspect output and evidence" "$T"/e*.out "$T"/b*.out "$T"/f*.out "$T"/o*.out "$T/i1.out" "$T/erase-hold.json" "$T/erase-complete.json"
if python3 -I - "$T/argv.log" > "$T/argv-check.out" <<'PY'
import json, sys
bad, ro, rw = [], 0, 0
for line in open(sys.argv[1]):
    j = json.loads(line)
    if j[0] != "docker" or j[1:2] != ["exec"]:
        continue
    a, i, opts = j[1:], 1, []
    while a[i].startswith("-"):
        if a[i] == "-e":
            opts += a[i:i + 2]; i += 2
        else:
            opts.append(a[i]); i += 1
    rest = a[i + 1:]
    if rest[:2] == ["curl", "--config"]:
        if "-i" not in opts:
            bad.append("curl --config - without -i")
        continue
    if "-i" in opts:
        bad.append("docker exec -i on " + " ".join(rest[:2]))
    if rest[:2] == ["sh", "-c"] and "exec psql" in rest[2]:
        sql = rest[rest.index("-c", 3) + 1].lstrip()
        readonly = "PGOPTIONS=-c default_transaction_read_only=on" in opts
        if sql.startswith("SELECT"):
            ro += 1
            if not readonly:
                bad.append("SELECT outside a read-only session: " + sql[:50])
        elif readonly:
            bad.append("mutation in a read-only session: " + sql[:50])
        else:
            rw += 1
print(f"read-only SELECT sessions={ro} verification mutations={rw}")
if bad or not ro or not rw:
    print("\n".join(bad))
    sys.exit(1)
PY
then ok "only the curl config pipe uses -i; every SELECT ran read-only, the verification did not ($(cat "$T/argv-check.out"))"
else no "argv check: $(cat "$T/argv-check.out")"; fi

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
    it_env=(env PATH="$REAL_PATH" PARKIO_PRIV001_COMPOSE_PROJECT=priv001-civo-it PARKIO_PRIV001_ENVIRONMENT=civo-production)
    IT_EMAIL="priv001a-$(date -u +%Y%m%d%H%M%S)it01@priv001a.parkio.invalid"
    IT_UID="$(python3 -I -c 'import uuid; print(uuid.uuid4())')"
    pgq -c "insert into auth_users (id, email, password_hash, status) values ('$IT_UID', '$IT_EMAIL', 'x', 'PENDING_VERIFICATION')" >/dev/null
    VERIFIED="$("${it_env[@]}" PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes \
      bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_mark_verified "$0" 900' "$IT_EMAIL" 2>"$T/it-verify.err")" || true
    [ "$VERIFIED" = "$IT_UID" ] && ok "real PG: the allowlisted verification through docker exec returns exactly that row" \
      || no "real PG verification returned '$VERIFIED' ($(head -2 "$T/it-verify.err" | tr '\n' ' '))"
    [ "$(pgq -c "select status||'/'||email_verified from auth_users where id = '$IT_UID'")" = "ACTIVE/true" ] \
      && ok "real PG: the row is ACTIVE and verified" || no "real PG: row state"
    if "${it_env[@]}" PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY=yes \
        bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_mark_verified "$0" 900' "$IT_EMAIL" >/dev/null 2>&1; then
      no "real PG: a second verification must find no row"; else ok "real PG: a second verification finds no row (fail-closed)"; fi
    if "${it_env[@]}" PARKIO_PRIV001_PSQL_READONLY=1 \
        bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_auth_psql -c "UPDATE auth_users SET status = status WHERE false"' \
        > "$T/it-ro.out" 2>&1; then
      no "real PG: a read-only session accepted an UPDATE"
    else has "$T/it-ro.out" "read-only transaction"; fi
    IT_T0="$("${it_env[@]}" bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_civo_db_now')"
    [[ "$IT_T0" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{6}Z$ ]] \
      && ok "real PG: the database clock reads as UTC with microseconds" || no "real PG: database clock '${IT_T0}'"
    pgq -c "insert into registration_invites (id, token_hash, expires_at, consumed_at, created_at, created_by) values
              (gen_random_uuid(), 'it-h1', now() + interval '7 days', now(), now(), 'priv001a'),
              (gen_random_uuid(), 'it-h2', now() + interval '7 days', null, now() - interval '1 day', 'priv001a'),
              (gen_random_uuid(), 'it-h3', now() + interval '7 days', null, now(), 'someone-else')" >/dev/null
    acc="$("${it_env[@]}" bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_civo_invite_accounting "$0"' "$IT_T0")"
    [ "$acc" = "1|0" ] && ok "real PG: invite accounting counts only priv001a invites since the start (1|0)" || no "real PG: accounting '${acc}'"
    pgq -c "insert into registration_invites (id, token_hash, expires_at, created_at, created_by)
              values (gen_random_uuid(), 'it-h4', now() + interval '7 days', now(), 'priv001a')" >/dev/null
    acc="$("${it_env[@]}" bash -c 'source scripts/lib/priv001-synthetic.sh; parkio_priv001_civo_invite_accounting "$0"' "$IT_T0")"
    [ "$acc" = "2|1" ] && ok "real PG: an unconsumed invite shows in the accounting (2|1)" || no "real PG: accounting '${acc}'"
    REQ="$(python3 -I -c 'import uuid; print(uuid.uuid4())')"
    pgq -c "update auth_users set status = 'ERASURE_IN_PROGRESS' where id = '$IT_UID';
            insert into erasure_requests (id, auth_user_id, status, requested_at) values ('$REQ', '$IT_UID', 'IN_PROGRESS', now());
            insert into erasure_service_acks (erasure_request_id, service_name, status, acked_at)
              select '$REQ', s, 'SUCCESS', now() from unnest(array['user','parking','media','moderation','gamification','notification','ai-validation']) s" >/dev/null
    printf 'acceptance_id=PRIV001A-IT\nauth_user_id=%s\nemail=%s\n' "$IT_UID" "$IT_EMAIL" > "$T/it-creds"; chmod 600 "$T/it-creds"
    run it1.out "${it_env[@]}" bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/it-creds" \
      --expect IN_PROGRESS --hold-seconds 1 --poll-seconds 1 --window-start 2026-01-01T00:00:00Z
    has "$T/it1.out" "request=IN_PROGRESS auth=ERASURE_IN_PROGRESS success=7/8 missing=analytics other_requests_since_window_start=0"
    has "$T/it1.out" "expect=IN_PROGRESS status=IN_PROGRESS verdict=PASS"
    pgq -c "insert into erasure_service_acks (erasure_request_id, service_name, status, acked_at) values ('$REQ', 'analytics', 'FAILED', now());
            update erasure_requests set status = 'FAILED_RETRYING', last_error_code = 'PARTICIPANT_FAILED' where id = '$REQ'" >/dev/null
    run it1f.out "${it_env[@]}" bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/it-creds" \
      --expect IN_PROGRESS --hold-seconds 1 --poll-seconds 1
    has "$T/it1f.out" "expect=IN_PROGRESS status=FAILED_RETRYING verdict=FAIL"; has "$T/it1f.out" "exit=1"
    pgq -c "delete from erasure_service_acks where erasure_request_id = '$REQ' and service_name = 'analytics';
            insert into erasure_service_acks (erasure_request_id, service_name, status, acked_at) values ('$REQ', 'analytics', 'SUCCESS', now());
            update erasure_requests set status = 'COMPLETE', last_error_code = null, completed_at = now() where id = '$REQ';
            update auth_users set status = 'ERASED', email = 'erased-' || id || '@invalid.localhost' where id = '$IT_UID'" >/dev/null
    run it2.out "${it_env[@]}" bash "$ERASE" "${OPTS[@]}" --credentials-file "$T/it-creds" \
      --expect COMPLETE --timeout-seconds 2 --poll-seconds 1
    has "$T/it2.out" "request=COMPLETE auth=ERASED success=8/8"; has "$T/it2.out" "expect=COMPLETE status=COMPLETE verdict=PASS"
    run it3.out "${it_env[@]}" bash "$INSPECT" "${OPTS[@]}" --auth-user-id "$IT_UID"
    has "$T/it3.out" '"erasure_request_status" : "COMPLETE"'; has "$T/it3.out" '"analytics" : "SUCCESS"'; has "$T/it3.out" "exit=0"
  else
    no "real PG: postgres:16-alpine did not start"
  fi
else
  echo "SKIP: no Docker daemon for the real PostgreSQL section"
fi

echo "=== the generated password always meets auth-service's PasswordPolicy ==="
# shellcheck disable=SC2016
if bash -c 'source scripts/lib/priv001-synthetic.sh
  for ((n = 0; n < 500; n++)); do
    p="$(parkio_priv001_generate_password | tr -d "\n")"
    { [ "${#p}" -ge 12 ] && [[ "$p" =~ [a-z] ]] && [[ "$p" =~ [A-Z] ]] && [[ "$p" =~ [0-9] ]]; } || exit 1
  done'; then ok "500 generated passwords have a lowercase letter, an uppercase letter, a digit and 12+ characters"
else no "a generated password fails the auth password policy"; fi

echo "=== static: the civo code path puts no secret on a command line ==="
civo_src="$(sed -n '/^# --- Civo production mode/,/^# parkio_priv001_auth_psql/p' scripts/lib/priv001-synthetic.sh)"
if printf '%s\n' "$civo_src" | grep -E -- '--data-binary "\$|-H "Authorization|PGPASSWORD' >/dev/null; then
  no "the civo section passes a body, bearer or password in argv"
else ok "civo section: bodies and secret headers only via stdin"; fi
if [ "$(printf '%s\n' "$civo_src" | grep -c 'docker exec -i')" = 1 ] \
    && printf '%s\n' "$civo_src" | grep 'docker exec -i' | grep -qF 'curl --config -'; then
  ok "civo section: docker exec -i only on the curl config pipe"
else no "civo section: another docker exec -i"; fi
if grep -E -- '--data-binary "\$|-H "Authorization|PGPASSWORD' "$ERASE" >/dev/null; then no "erase script argv secret"; else ok "erase script: no argv secret"; fi
if grep -RniE 'PARKIO_EMAIL_VERIFICATION_BYPASS|email.verification.bypass|DISABLE_EMAIL_VERIFICATION' "$ERASE" scripts/lib/priv001-synthetic.sh; then
  no "global verification bypass marker"; else ok "no global verification bypass"; fi
if grep -En '^\s*(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER)\b' "$ERASE" | grep -vE '^[0-9]+:#'; then
  no "erase script contains mutating SQL"; else ok "erase script SQL is read-only"; fi

echo "=== summary pass=${pass} fail=${fail} ==="
[ "$fail" -eq 0 ] || exit 1
echo "PRIV-001A civo-production mode contract: PASS"
