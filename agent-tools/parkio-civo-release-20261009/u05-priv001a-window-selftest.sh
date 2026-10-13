#!/usr/bin/env bash
# Local self-test of u05-priv001a-window.py (v2) together with the real u05-window-snapshot.py, in a fake world.
# The fake world holds the containers (State times included), the auth database rows, the consumer lag, the systemd
# timers and the env file. A fake production wrapper renders the erasure flag through the shared app anchor (every app
# service's rendered config changes while it is on), recreates auth-service with `up -d --no-deps --no-build` and
# stops/starts analytics-service. Fake docker, systemd-run and systemctl record every call; `systemctl kill` kills the
# runner and its harness children. Fake harness scripts behave like PR #336's: credentials before the register call,
# the invite route only with mode=invite and invite creation on, DELETE only with the flag on, a PENDING account verified
# before --delete, IN_PROGRESS only while analytics is stopped, exit 4 when no account exists.
# Scenarios R1-R33 (the U04 hold R26-R29, U03 R30-R33).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u05-priv001a-window.py"; SNAP="$HERE/u05-window-snapshot.py"
W="$(mktemp -d)"; trap 'kill $(jobs -p) 2>/dev/null; rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/harness/scripts/acceptance" "$W/harness/scripts/lib" "$W/release/scripts"

cat > "$W/fakeworld.py" <<'PY'
import calendar, hashlib, json, os, re, sys, time, uuid
STATE = os.environ["FW_STATE"]
def load():
    for _ in range(50):
        try:
            return json.load(open(STATE))
        except ValueError:
            time.sleep(0.05)
    return json.load(open(STATE))
def save(s):
    tmp = STATE + f".{os.getpid()}"
    json.dump(s, open(tmp, "w")); os.replace(tmp, STATE)
def log(s, what): s.setdefault("calls", []).append(what)
def ts(t=None):
    t = time.time() if t is None else t
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(t)) + ".%09dZ" % int((t % 1) * 1e9)
def epoch(iso):
    return calendar.timegm(time.strptime(iso[:19], "%Y-%m-%dT%H:%M:%S"))
APP = ["auth-service", "user-service", "parking-service", "media-service", "moderation-service", "gamification-service",
       "notification-service", "analytics-service", "ai-validation-service", "gateway-service"]
def env_file_values():
    vals = {}
    for line in open(os.environ["FW_ENV"], encoding="utf-8").read().splitlines():
        m = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$", line)
        if m: vals[m.group(1)] = m.group(2).strip()
    return vals
def rendered_env(service, vals):
    env = {"PARKIO_ACCOUNT_ERASURE_ENABLED": vals.get("PARKIO_ACCOUNT_ERASURE_ENABLED") or "false"} if service in APP else {}
    if service == "auth-service":
        env.update({"PARKIO_REGISTRATION_MODE": vals.get("PARKIO_REGISTRATION_MODE") or "closed",
                    "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED": vals.get("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED") or "false",
                    "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN": vals.get("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN", "")})
    return env
def chash(service, image, env):
    return hashlib.sha256(json.dumps([service, image, sorted(env.items())]).encode()).hexdigest()
def init(mode, token_set, extra="", knobs=None):
    lines = ["PARKIO_ACCOUNT_ERASURE_ENABLED=false", f"PARKIO_REGISTRATION_MODE={mode}",
             "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=false", "POSTGRES_AUTH_PASSWORD=db-secret-xyz"]
    if token_set: lines.append("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=preset-operator-token-" + "x" * 30)
    if extra: lines.append(extra)
    open(os.environ["FW_ENV"], "w").write("\n".join(lines) + "\n")
    os.chmod(os.environ["FW_ENV"], 0o640)
    vals = env_file_values()
    cs = {}
    for svc in APP + ["postgres-auth", "kafka", "minio", "nr-collector"]:
        name = f"parkio-{svc}-1" if svc in APP else f"parkio-{svc}"
        env = rendered_env(svc, vals)
        img = "sha256:img-" + svc
        cs[name] = {"Id": uuid.uuid4().hex * 2, "Image": img, "service": svc, "project": "parkio" if svc != "nr-collector" else "nr",
                    "status": "running", "startedAt": "2026-10-12T12:49:14.123456789Z", "finishedAt": "0001-01-01T00:00:00Z",
                    "restartCount": 0, "health": "healthy", "env": env, "hash": chash(svc, img, env)}
    save({"containers": cs, "db": {"users": [], "invites": [], "requests": [{"uid": "00000000-0000-4000-8000-000000000001",
          "status": "COMPLETE", "at": time.time() - 86400, "acks": 8}]},
          "calls": [], "timers": [], "lag": 0, "knobs": knobs or {}})
PY

cat > "$W/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, re, sys, time
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
a = sys.argv[1:]; s = fw.load(); k = s.get("knobs", {})
if a[:2] == ["ps", "-a"]:
    print("\n".join(c["Id"][:12] for c in s["containers"].values())); sys.exit(0)
if a[:1] == ["inspect"]:
    out = []
    for name, c in s["containers"].items():
        out.append({"Name": "/" + name, "Id": c["Id"], "Image": c["Image"], "RestartCount": c["restartCount"],
                    "Config": {"Labels": {"com.docker.compose.project": c["project"], "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]},
                               "Env": [f"{k}={v}" for k, v in c["env"].items()]},
                    "State": {"Status": c["status"], "StartedAt": c["startedAt"], "FinishedAt": c["finishedAt"],
                              "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
if a[:1] == ["exec"] and "kafka-consumer-groups" in a:
    fw.log(s, "lag " + a[-1]); fw.save(s)
    if k.get("lagUnreadable"): sys.exit(1)
    an = next(c for c in s["containers"].values() if c["service"] == "analytics-service")
    restarted = an["startedAt"] != "2026-10-12T12:49:14.123456789Z"
    lag = 0 if an["status"] == "running" and not (k.get("lagStuckAfterRestart") and restarted) else 42
    print(f"\nGROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID")
    print(f"{a[-1]} parkio.some.topic 0 100 {100 + lag} {lag} consumer-1 /10.0.0.9 analytics")
    sys.exit(0)
if a[:1] == ["exec"] and "psql" in a:
    sql = a[-1]; db = s["db"]; users = db["users"]
    if sql.startswith("with t as (select u.id from auth_users u where u.id = "):
        uid = re.search(r"u.id = '([0-9a-f-]+)'::uuid", sql).group(1); em = re.search(r"u.email = '([^']+)'", sql).group(1)
        t = fw.epoch(re.search(r"created_at >= '([^']+)'::timestamptz", sql).group(1))
        u = next((x for x in users if x["id"] == uid and x["email"] == em and x["status"] == "ACTIVE" and x["at"] >= t
                  and set(x.get("roles", ["USER"])) == {"USER"} and not any(r["uid"] == uid for r in db["requests"])), None)
        if u: u["roles"] = ["ADMIN", "USER"]; u["epoch"] = u.get("epoch", 0) + 1; fw.log(s, "elevate " + uid); fw.save(s); print(f"{uid}|{u['epoch']}")
        sys.exit(0)
    if sql.startswith("with del as (delete from auth_user_roles ur using roles r, auth_users u where "):
        uid = re.search(r"ur.user_id = '([0-9a-f-]+)'::uuid", sql).group(1)
        u = next((x for x in users if x["id"] == uid and "ADMIN" in x.get("roles", [])
                  and (x["email"].startswith("priv001a-") or x.get("tomb"))), None)
        if u: u["roles"] = ["USER"]; u["epoch"] = u.get("epoch", 0) + 1; fw.log(s, "demote " + uid); fw.save(s); print(f"{uid}|{u['epoch']}")
        sys.exit(0)
    if "PGOPTIONS=-c default_transaction_read_only=on" not in a:
        sys.stderr.write("fake docker: psql without a read-only session\n"); sys.exit(5)
    def since(op):
        m = re.search(r"created_at " + op + r" '([^']+)'::timestamptz", sql) or re.search(r"requested_at >= '([^']+)'::timestamptz", sql)
        return fw.epoch(m.group(1))
    def row(u): return f"{u['id']}|{u['status']}|{u.get('tomb') or u['email']}"
    if sql == "select count(*) from erasure_requests":
        print(len(db["requests"]))
    elif sql.startswith("select r.name from auth_user_roles ur join roles r on r.id = ur.role_id where ur.user_id = "):
        uid = re.search(r"ur.user_id = '([0-9a-f-]+)'", sql).group(1)
        print("\n".join(next((x.get("roles", ["USER"]) for x in users if x["id"] == uid), [])))
    elif sql.startswith("select extract(epoch from occurred_at), actor_roles from admin_audit_events"):
        uid = re.search(r"target_resource_id = '([0-9a-f-]+)'", sql).group(1)
        act = re.search(r"action_type = '([A-Z_]+)'", sql).group(1)
        since = float(re.search(r"to_timestamp\(([0-9.]+)\)", sql).group(1))
        print("\n".join(f"{a['at']}|SUPER_ADMIN" for a in db.get("audit", [])
                         if a["uid"] == uid and a["action"] == act and a["at"] >= since))
    elif sql.startswith("select session_epoch from auth_users where id = "):
        uid = re.search(r"id = '([0-9a-f-]+)'", sql).group(1)
        print(next((x.get("epoch", 0) for x in users if x["id"] == uid), ""))
    elif sql.startswith("select id, status, email from auth_users where id ="):
        uid = re.search(r"id = '([0-9a-f-]+)'", sql).group(1)
        print("\n".join(row(u) for u in users if u["id"] == uid))
    elif sql.startswith("select id, status, email from auth_users where email ="):
        em = re.search(r"email = '([^']+)'", sql).group(1)
        print("\n".join(row(u) for u in users if (u.get("tomb") or u["email"]) == em))
    elif sql.startswith("select r.status, (select count(*) from erasure_service_acks"):
        uid = re.search(r"auth_user_id = '([0-9a-f-]+)'", sql).group(1)
        rq = [r for r in db["requests"] if r["uid"] == uid]
        if rq: print(f"{rq[-1]['status']}|{rq[-1]['acks']}")
    elif sql.startswith("select id, status from auth_users where email like 'priv001a-%"):
        t = since(">=")
        print("\n".join(f"{u['id']}|{u['status']}" for u in users if not u.get("tomb") and u["at"] >= t))
    elif sql.startswith("select count(*) from auth_users where email like 'priv001a-%"):
        t = since("<")
        print(sum(1 for u in users if not u.get("tomb") and u["at"] < t))
    elif "from registration_invites" in sql:
        t = since(">=")
        inv = [i for i in db["invites"] if i["at"] >= t]
        print(f"{len(inv)}|{sum(1 for i in inv if not i['consumed'])}")
    elif sql.startswith("select count(*) from erasure_requests where"):
        t = since(">=")
        ours = re.findall(r"'([0-9a-f-]{36})'::uuid", sql.split("requested_at")[0])
        print(sum(1 for r in db["requests"] if r["at"] >= t and r["uid"] not in ours))
    else:
        sys.stderr.write("fake docker: unknown sql " + sql); sys.exit(3)
    sys.exit(0)
sys.stderr.write(f"fake docker: unhandled {a}\n"); sys.exit(2)
PY

cat > "$W/release/scripts/parkio-prod-compose.sh" <<'SH'
#!/usr/bin/env bash
exec python3 -I "$FW_DIR/compose.py" "$@"
SH
cat > "$W/compose.py" <<'PY'
import os, sys, time
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
a = sys.argv[1:]; s = fw.load(); k = s.get("knobs", {}); fw.log(s, " ".join(a)); fw.save(s)
vals = fw.env_file_values()
def find(svc): return next(n for n, c in s["containers"].items() if c["service"] == svc and c["project"] == "parkio")
if a[:3] == ["config", "--hash", "*"]:
    for c in s["containers"].values():
        if c["project"] == "parkio":
            h = fw.chash(c["service"], c["Image"], fw.rendered_env(c["service"], vals))
            if k.get("authDriftAtW0") and c["service"] == "auth-service": h = "drifted"
            print(c["service"], h)
    sys.exit(0)
if a == ["up", "-d", "--no-deps", "--no-build", "auth-service"]:
    if k.get("slowUp"): time.sleep(k["slowUp"])
    s = fw.load(); n = find("auth-service"); c = s["containers"][n]
    c["env"] = fw.rendered_env("auth-service", vals); c["hash"] = fw.chash("auth-service", c["Image"], c["env"])
    c["Id"] = os.urandom(32).hex(); c["startedAt"] = fw.ts()
elif a == ["stop", "analytics-service"]:
    c = s["containers"][find("analytics-service")]; c["status"] = "exited"; c["health"] = None; c["finishedAt"] = fw.ts()
elif a == ["start", "analytics-service"]:
    if k.get("startFails"): sys.stderr.write("fake compose: start failed\n"); sys.exit(1)
    c = s["containers"][find("analytics-service")]; c["status"] = "running"; c["health"] = "healthy"; c["startedAt"] = fw.ts()
else:
    sys.stderr.write(f"fake compose: unhandled {a}\n"); sys.exit(2)
s2 = fw.load(); s2["containers"] = s["containers"]; fw.save(s2)
PY

cat > "$W/bin/systemd-run" <<'PY'
#!/usr/bin/env python3
import os, sys
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
s = fw.load(); a = sys.argv[1:]; fw.log(s, "systemd-run " + " ".join(a))
if any(x.startswith("--on-active=") for x in a):
    s["timers"].append(a[a.index("--unit") + 1] + ".timer")
fw.save(s)
PY
cat > "$W/bin/systemctl" <<'PY'
#!/usr/bin/env python3
import glob, os, signal, sys
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
s = fw.load(); a = sys.argv[1:]; k = s.get("knobs", {})
def runner_alive(unit):
    run = unit[len("u05-priv001a-window-"):-len(".service")]
    try:
        pid = int(open(os.path.join(os.environ["U05R_PRIVATE"], run, "runner.pid")).read())
        os.kill(pid, 0)
        return pid if open(f"/proc/{pid}/stat").read().split()[2] != "Z" else None
    except (OSError, ValueError):
        return None
if a[:1] == ["is-active"]:
    u = a[1]
    act = (u in s["timers"]) if u.endswith(".timer") else bool(runner_alive(u)) if u.startswith("u05-priv001a-window-") else False
    print("active" if act else "inactive"); sys.exit(0 if act else 3)
fw.log(s, "systemctl " + " ".join(a))
if a[:1] == ["stop"] and a[1].endswith(".timer"):
    if not k.get("disarmFails") and a[1] in s["timers"]: s["timers"].remove(a[1])
elif a[:2] == ["kill", "--signal=SIGKILL"]:
    pid = runner_alive(a[2])
    if pid:
        os.kill(pid, signal.SIGKILL)
        for f in glob.glob(os.path.join(os.environ["FW_DIR"], "harness-pids", "*")):
            try: os.killpg(int(os.path.basename(f)), signal.SIGKILL)
            except OSError: pass
elif a[:1] == ["list-units"]:
    live = list(s["timers"])
    for f in glob.glob(os.path.join(os.environ["U05R_PRIVATE"], "*", "runner.pid")):
        u = f"u05-priv001a-window-{os.path.basename(os.path.dirname(f))}.service"
        if runner_alive(u): live.append(u)
    print("\n".join(f"{u} loaded active running x" for u in live))
fw.save(s)
PY

cat > "$W/harness.py" <<'PY'
import json, os, re, signal, sys, time, uuid
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
kind, a = sys.argv[1], sys.argv[2:]
os.makedirs(os.path.join(os.environ["FW_DIR"], "harness-pids"), exist_ok=True)
pidf = os.path.join(os.environ["FW_DIR"], "harness-pids", str(os.getpgrp())); open(pidf, "w").close()
s = fw.load(); k = s.get("knobs", {}); sc = os.environ.get("FW_SCENARIO", "")
def opt(n): return a[a.index(n) + 1] if n in a else None
def svc(name): return next(c for c in s["containers"].values() if c["service"] == name)
def done(rc):
    try: os.remove(pidf)
    except OSError: pass
    sys.exit(rc)
if kind == "create":
    if "--dry-run-guards" in a:
        if k.get("hostRefused"): print("ERROR: --environment civo-production runs only on parkio-civo-prod (this host: x)."); done(2)
        print("PASS: dry-run guards"); done(0)
    e = svc("auth-service")["env"]
    if e.get("PARKIO_REGISTRATION_MODE") != "invite":
        print("ERROR: registration mode is CLOSED; the window must set PARKIO_REGISTRATION_MODE=invite first (no invite was created)."); done(1)
    if e.get("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED") != "true" or not e.get("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"):
        print("ERROR: invite creation failed http=403"); done(1)
    td = os.path.join(os.environ["TMPDIR"], "priv001a-civo.fake"); os.makedirs(td, exist_ok=True)
    open(os.path.join(td, "login.json"), "w").write('{"accessToken": "eyJfake-tmp-token"}')
    creds = opt("--credentials-file"); email = f"priv001a-20261013{uuid.uuid4().hex[:16]}@priv001a.parkio.invalid"
    inv = {"at": time.time(), "consumed": False}; s["db"]["invites"].append(inv); fw.save(s)
    print("PRIV-001A: invite=PASS")
    open(creds, "w").write(f"acceptance_id=PRIV001A-T\nemail={email}\npassword=Pw-synthetic-123456\n")
    if sc == "register_fail": print("ERROR: register failed http=503"); done(1)
    uid = str(uuid.uuid4()); inv["consumed"] = True
    s["db"]["users"].append({"id": uid, "email": email, "status": "PENDING_VERIFICATION", "at": time.time()}); fw.save(s)
    open(creds, "w").write(f"acceptance_id=PRIV001A-T\nauth_user_id={uid}\nemail={email}\npassword=Pw-synthetic-123456\n")
    print(f"PRIV-001A: register=PASS user_id={uid} status=PENDING_VERIFICATION")
    if sc == "create_hang": time.sleep(60)
    if sc == "verify_fail": print("ERROR: verification did not return exactly one auth user id."); done(1)
    next(u for u in s["db"]["users"] if u["id"] == uid)["status"] = "ACTIVE"; fw.save(s)
    print("PRIV-001A: verify=PASS")
    if sc == "login_fail": print("ERROR: login failed http=503"); done(1)
    open(creds, "a").write("access_token=eyJfake.tok\nrefresh_token=rt-fake\n")
    print("PRIV-001A: login=PASS status=ACTIVE")
    if sc == "concurrent_edit": open(os.environ["FW_ENV"], "a").write("OTHER_CONCURRENT=1\n")
    if sc == "conflict_edit":
        t = open(os.environ["FW_ENV"]).read().replace("PARKIO_ACCOUNT_ERASURE_ENABLED=true", "PARKIO_ACCOUNT_ERASURE_ENABLED=maybe")
        open(os.environ["FW_ENV"], "w").write(t)
    if "--skip-seed" not in a or k.get("u03SeedAlways"):   # the real harness calls GET /auth/me: the gateway caches the epoch
        s = fw.load(); s.setdefault("gwcache", {})[uid] = [0, time.time() + k.get("u03TTL", 2)]; fw.save(s)
    print("PRIV-001A: invite accounting PASS (created 1, unconsumed 0)\nPASS: PRIV-001A synthetic principal created")
    print(f"acceptance_id=PRIV001A-T\nauth_user_id={uid}")
    if opt("--evidence-file"): open(opt("--evidence-file"), "w").write("{}")
    done(0)
if kind == "lib-invite":
    e = svc("auth-service")["env"]
    if e.get("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED") != "true" or not e.get("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"):
        open(a[0], "w").write('{"code":"FORBIDDEN"}'); print("403"); done(0)
    tok = uuid.uuid4().hex + "-" + uuid.uuid4().hex[:10]
    s["db"]["invites"].append({"at": time.time(), "consumed": False, "token": tok}); fw.save(s)
    open(a[0], "w").write(json.dumps({"token": tok, "expiresAt": "2026-10-20T00:00:00Z"})); print("201"); done(0)
if kind == "lib-http":
    import base64
    method, path, out = a[0], a[1], a[2]
    body, bearer = os.environ.get("FW_BODY", ""), os.environ.get("FW_BEARER", "")
    users = s["db"]["users"]
    def b64(o): return base64.urlsafe_b64encode(json.dumps(o).encode()).decode().rstrip("=")
    def jwt(u): return (b64({"alg": "HS256"}) + "." + b64({"sub": u["id"], "roles": sorted(u.get("roles", ["USER"])),
                        "session_epoch": u.get("epoch", 0)}) + ".sig" + u["id"][:8])
    def reply(code, text=""):
        open(out, "w").write(text); print(code); done(0)
    if method == "POST" and path == "/api/v1/auth/login":
        b = json.loads(body)
        u = next((x for x in users if x["email"] == b.get("email") and x["status"] == "ACTIVE" and not x.get("tomb")), None)
        if u is None: reply("401", '{"code":"INVALID_CREDENTIALS"}')
        if "ADMIN" in u.get("roles", []) and k.get("u03LoginFailAfterElevate"): reply("503", '{"code":"UNAVAILABLE"}')
        if "ADMIN" in u.get("roles", []) and k.get("u03HangAfterElevate"): time.sleep(600)
        reply("200", json.dumps({"accessToken": jwt(u), "refreshToken": "rt-fake", "user": {"id": u["id"], "status": "ACTIVE"}}))
    parts = bearer.split(".")
    if not bearer: reply("401", '{"code":"UNAUTHORIZED"}')
    try:
        claims = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    except Exception:
        reply("401", '{"code":"INVALID_TOKEN"}')
    u = next((x for x in users if x["id"] == claims.get("sub")), None)
    if u is None or len(parts) != 3 or parts[2] != "sig" + u["id"][:8]: reply("401", '{"code":"INVALID_TOKEN"}')
    cache = s.setdefault("gwcache", {})
    ent = cache.get(u["id"])
    if not ent or time.time() >= ent[1]:
        ent = [u.get("epoch", 0), time.time() + k.get("u03TTL", 2)]; cache[u["id"]] = ent; fw.save(s)
    if not k.get("u03NoRevoke") and claims.get("session_epoch") != ent[0]: reply("401", '{"code":"TOKEN_REVOKED"}')
    if (path.startswith("/api/v1/admin/") or path.startswith("/api/v1/waitlist/export")) and "ADMIN" not in claims.get("roles", []):
        reply("403", '{"code":"FORBIDDEN"}')
    if path == "/api/v1/auth/me": reply("200", json.dumps({"id": u["id"]}))
    if path.startswith("/api/v1/admin/users?userId="):
        uid = path.split("=", 1)[1]
        reply("200", json.dumps({"content": [{"id": x["id"]} for x in users if x["id"] == uid]}))
    if path.startswith("/api/v1/waitlist/export"): reply("406", '{"code":"NOT_ACCEPTABLE"}')
    if method == "POST" and re.match(r"^/api/v1/admin/users/[0-9a-f-]{36}/suspend$", path): reply("404", '{"code":"USER_NOT_FOUND"}')
    reply("404", '{"code":"NOT_MOCKED"}')
if kind == "lib-verify":
    open(os.path.join(os.environ["FW_DIR"], "verify-max-age.log"), "a").write(f"{a[1] if len(a) > 1 else ''}\n")
    u = next((x for x in s["db"]["users"] if x["email"] == a[0] and x["status"] == "PENDING_VERIFICATION"), None)
    if u is None: print("ERROR: verification did not return exactly one auth user id."); done(1)
    u["status"] = "ACTIVE"; fw.save(s); print(u["id"]); done(0)
if kind == "erase":
    cpath = opt("--credentials-file")
    creds = dict(l.split("=", 1) for l in open(cpath).read().splitlines() if "=" in l)
    u = next((x for x in s["db"]["users"] if x["id"] == creds.get("auth_user_id") or
              (not creds.get("auth_user_id") and x["email"] == creds["email"] and not x.get("tomb"))), None)
    if u is None: print("PRIV-001A: no auth account holds the credentials file's email (nothing to erase)."); done(4)
    if not creds.get("auth_user_id"): open(cpath, "a").write(f"auth_user_id={u['id']}\n")
    if "--delete" in a:
        if u["status"] == "PENDING_VERIFICATION":
            u["status"] = "ACTIVE"; print("PRIV-001A: the account was PENDING_VERIFICATION; applied the allowlisted verification first")
        if u["status"] != "ACTIVE": print(f"ERROR: --delete needs account {u['id']} ACTIVE"); done(1)
        if svc("auth-service")["env"].get("PARKIO_ACCOUNT_ERASURE_ENABLED") != "true": print("ERROR: DELETE failed http=403"); done(1)
        s["db"]["requests"].append({"uid": u["id"], "status": "IN_PROGRESS", "at": time.time(), "acks": 7}); u["status"] = "ERASURE_IN_PROGRESS"
        fw.save(s); print("PRIV-001A: delete=ACCEPTED http=200 status=IN_PROGRESS")
        if sc == "die_in_w3" and opt("--expect") == "IN_PROGRESS":
            pid = int(open(os.path.join(os.environ["U05R_PRIVATE"], os.environ["FW_RUN"], "runner.pid")).read())
            os.kill(pid, signal.SIGKILL); done(1)
    req = next((r for r in reversed(s["db"]["requests"]) if r["uid"] == u["id"]), None)
    ev = opt("--evidence-file")
    if opt("--expect") == "IN_PROGRESS":
        if svc("analytics-service")["status"] == "exited": time.sleep(int(opt("--hold-seconds") or 1))
        s = fw.load(); an = svc("analytics-service")
        ok = an["status"] == "exited" and sc != "complete_early" and req is not None
        obs = {"request_status": "IN_PROGRESS", "successCount": 7, "missing": ["analytics"], "auth_state": "ERASURE_IN_PROGRESS"}
        if sc == "wrong_shape": obs.update(successCount=0, missing=["user", "parking", "media", "moderation", "gamification", "notification", "analytics", "ai-validation"])
        if not ok:
            obs = {"request_status": "COMPLETE", "successCount": 8, "missing": [], "auth_state": "ERASED"}
        print(f"  request={obs['request_status']} success={obs['successCount']}/8")
        print(f"PRIV-001A erasure observation: expect=IN_PROGRESS status={obs['request_status']} verdict={'PASS' if ok else 'FAIL'}")
        if ev: json.dump({"verdict": "PASS" if ok else "FAIL", "observations": [obs]}, open(ev, "w"))
        done(0 if ok else 1)
    s = fw.load(); req = next((r for r in reversed(s["db"]["requests"]) if r["uid"] == u["id"]), None)
    ok = svc("analytics-service")["status"] == "running" and req is not None
    if ok:
        req["status"] = "COMPLETE"; req["acks"] = 8
        uu = next(x for x in s["db"]["users"] if x["id"] == u["id"]); uu["status"] = "ERASED"; uu["tomb"] = f"erased-{u['id']}@invalid.localhost"
        fw.save(s)
    obs = {"request_status": "COMPLETE" if ok else "IN_PROGRESS", "successCount": 8 if ok else 7,
           "missing": [] if ok else ["analytics"], "auth_state": "ERASED" if ok else "ERASURE_IN_PROGRESS"}
    print(f"  request={obs['request_status']} auth={obs['auth_state']} success={obs['successCount']}/8")
    print(f"PRIV-001A erasure observation: expect=COMPLETE status={obs['request_status']} verdict={'PASS' if ok else 'FAIL'}")
    if ev: json.dump({"verdict": "PASS" if ok else "FAIL", "observations": [obs]}, open(ev, "w"))
    done(0 if ok else 1)
if kind == "inspect":
    uid = opt("--auth-user-id"); u = next(x for x in s["db"]["users"] if x["id"] == uid)
    req = next((r for r in reversed(s["db"]["requests"]) if r["uid"] == uid), None)
    print("PRIV-001A residue inspection (read-only)"); print(f"auth_user_id={uid}")
    print(json.dumps({"auth_state": u["status"], "erasure_request_status": req["status"] if req else "ABSENT",
                      "participant_ack_count": req["acks"] if req else 0, "tombstone_count": 1 if u.get("tomb") else 0,
                      "hard_delete_residue_count": 0, "forbidden_user_id_hits": 0}, separators=(", ", " : ")))
    done(0)
PY
for k in create erase inspect; do
  f="$W/harness/scripts/acceptance/$k-priv001-synthetic-principal.sh"; [ "$k" = inspect ] && f="$W/harness/scripts/acceptance/inspect-priv001-synthetic-residue.sh"
  printf '#!/usr/bin/env bash\nexec python3 -I "$FW_DIR/harness.py" %s "$@"\n' "$k" > "$f"
done
cat > "$W/harness/scripts/lib/priv001-synthetic.sh" <<'SH'
# fake lib: the two functions the U04 subcommands call
parkio_priv001_civo_create_invite() { python3 -I "$FW_DIR/harness.py" lib-invite "$1"; }
parkio_priv001_mark_verified() { python3 -I "$FW_DIR/harness.py" lib-verify "$1" "$2"; }
parkio_priv001_civo_http() { FW_BODY="$3" FW_BEARER="$4" python3 -I "$FW_DIR/harness.py" lib-http "$1" "$2" "$5"; }
SH
echo "# fake dark url" > "$W/harness/scripts/lib/dark-gateway-url.sh"
chmod +x "$W/bin/"* "$W/release/scripts/parkio-prod-compose.sh"
git -C "$W/release" init -q && git -C "$W/release" add -A && git -C "$W/release" -c user.name=t -c user.email=t@t commit -q -m pin
python3 -I - "$W/harness" "$W/pins.json" <<'PY'
import hashlib, json, os, sys
h, out = sys.argv[1:3]
files = ["scripts/acceptance/create-priv001-synthetic-principal.sh", "scripts/acceptance/erase-priv001-synthetic-principal.sh",
         "scripts/acceptance/inspect-priv001-synthetic-residue.sh", "scripts/lib/priv001-synthetic.sh", "scripts/lib/dark-gateway-url.sh"]
json.dump({"harness": {f: hashlib.sha256(open(os.path.join(h, f), "rb").read()).hexdigest() for f in files}}, open(out, "w"))
PY
cp "$TOOL" "$W/tools-u05-priv001a-window.py"; cp "$SNAP" "$W/u05-window-snapshot.py"; TOOLC="$W/tools-u05-priv001a-window.py"
export FW_DIR="$W" FW_STATE="$W/world.json" FW_ENV="$W/env"
export U05R_TEST=1 U05W_TEST=1 U05R_RELEASE="$W/release" U05R_PIN="$(git -C "$W/release" rev-parse HEAD)" U05R_ENV_FILE="$W/env" \
  U05R_PRIVATE="$W/private" U05R_EVIDENCE="$W/evidence" U05R_DOCKER="$W/bin/docker" U05R_SYSTEMD_RUN="$W/bin/systemd-run" \
  U05R_SYSTEMCTL="$W/bin/systemctl" U05R_HOLD=1 U05R_COMPLETE_TIMEOUT=5 U05R_HEALTH_TIMEOUT=5 U05R_POLL=0 U05R_LAG_WAIT=2 \
  U05R_HARNESS_POLL=1 U05R_PINS="$W/pins.json" U05W_OWNER="$(id -un)" U05R_OWNER="$(id -un)"
mkdir -p "$W/private" "$W/evidence"
pass=0; fail=0
ok() { echo "ok   $*"; pass=$((pass + 1)); }
no() { echo "FAIL $*"; fail=$((fail + 1)); }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasnt() { if grep -qF -- "$2" "$W/$1"; then no "$1: unexpected $2"; else ok "$1: no $2"; fi; }
calls() { python3 -I -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["calls"]))' "$W/world.json"; }
envsha() { sha256sum "$W/env" | cut -d' ' -f1; }
fresh() {  # mode token_set [extra-line] [knobs-json]
  rm -rf "$W/private"/* "$W/evidence"/* "$W/harness-pids"
  python3 -I -c "import json,sys; sys.path.insert(0, '$W'); import fakeworld as fw; fw.init('$1', $2, extra='${3:-}', knobs=json.loads('''${4:-{\}}'''))"
  SHA0="$(envsha)"
}
start() {  # out [args...]
  local out="$1"; shift
  python3 -I "$TOOLC" start --harness "$W/harness" "$@" > "$W/$out.start" 2>&1
  RUN="$(ls "$W/private" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | tail -1)"
}
window() {  # out scenario [start args...] (default: --fault analytics)
  local out="$1" sc="$2"; shift 2
  if [ "$#" -eq 0 ]; then set -- --fault analytics; fi
  start "$out" "$@"; sleep 1.1
  FW_SCENARIO="$sc" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/$out" 2>&1; echo "exit=$?" >> "$W/$out"
}
report() { python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); exec(sys.argv[2])' "$W/evidence/$RUN/window-report.json" "$1"; }
wait_for() {  # file pattern seconds
  local i; for i in $(seq 1 $(( $3 * 10 ))); do grep -qF -- "$2" "$1" 2>/dev/null && return 0; sleep 0.1; done; return 1
}
wait_call() {  # pattern seconds
  local i; for i in $(seq 1 $(( $2 * 10 ))); do calls | grep -qF -- "$1" && return 0; sleep 0.1; done; return 1
}

echo "=== R1 happy path (fault analytics; mode closed; token unset) ==="
fresh closed False
window r1.out ""
sed -e "s#$W#<W>#g" "$W/r1.out" | head -80
has r1.out "U05 PRIV-001A WINDOW PASS"; has r1.out "exit=0"
has r1.out "env keys changed: PARKIO_ACCOUNT_ERASURE_ENABLED, PARKIO_REGISTRATION_INVITE_CREATION_ENABLED, PARKIO_REGISTRATION_MODE, PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
has r1.out "<generated, 48 chars, never shown>"; has r1.out "restore env file: ok - byte-identical"; has r1.out "U05 WINDOW COMPARE PASS"
has r1.out "restore guards: ok - both guard timers inactive"; has r1.out "(limit 600 s; Docker State times)"
[ "$(envsha)" = "$SHA0" ] && ok "r1: env file byte-identical to W0" || no "r1 env sha"
[ "$(stat -c %a "$W/env")" = 640 ] && ok "r1: env file mode unchanged (640)" || no "r1 env mode"
calls > "$W/r1.calls"
python3 -I - "$W/r1.calls" "$RUN" <<'PY' && ok "r1: guard before each change; KillMode=mixed; only auth recreated (--no-build); analytics stopped/started, never up; timers disarmed" || no "r1 call order"
import sys
c = open(sys.argv[1]).read().splitlines(); run = sys.argv[2]
unit = next(l for l in c if l.startswith(f"systemd-run --unit u05-priv001a-window-{run} "))
assert "--property=KillMode=mixed" in unit and "--property=TimeoutStopSec=1200" in unit and "/window.py run --run" in unit
iw = next(i for i, l in enumerate(c) if l.startswith(f"systemd-run --unit u05-priv001a-guard-window-{run} "))
assert "--on-active=2520" in c[iw] and "StandardOutput=append:" in c[iw] and f"/private/{run}/window.py restore --guard" in c[iw]
up = [i for i, l in enumerate(c) if l == "up -d --no-deps --no-build auth-service"]
ia = next(i for i, l in enumerate(c) if l.startswith(f"systemd-run --unit u05-priv001a-guard-analytics-{run} "))
assert "--on-active=510" in c[ia]
istop, istart = c.index("stop analytics-service"), c.index("start analytics-service")
assert iw < up[0] and len(up) == 2 and ia < istop < istart
assert not any(l.startswith("up") and ("analytics" in l or "auth-service" not in l) for l in c)
assert f"systemctl stop u05-priv001a-guard-analytics-{run}.timer" in c and f"systemctl stop u05-priv001a-guard-window-{run}.timer" in c
PY
[ ! -e "$W/private/$RUN/env.backup" ] && [ ! -e "$W/private/$RUN/creds" ] && [ ! -e "$W/private/$RUN/tmp" ] \
  && ok "r1: env backup, credentials and harness temp files shredded after the verified restore" || no "r1 secrets left"
report 'a = r["accounting"]; p = a["principal"]
assert r["verdict"] == "PASS" and r["compare"] == "PASS" and r["envRestore"] == "byte-identical" and r["deleteSent"] is True
assert a["syntheticAccountsSinceW1"] == [] and a["invitesSinceW1"] == {"created": 1, "unconsumed": 0}
assert p["status"] == "ERASED" and p["emailErased"] and p["request"] == "COMPLETE" and p["successAcks"] == 8
assert 0 <= r["analyticsStopSeconds"] <= 600 and r["lagW0"] == 0 and r["lagW6"] == 0 and r["notes"] == []
assert r["inspect"]["tombstone_count"] == 1 and all(s["ok"] for s in r["restoreSteps"])' \
  && ok "r1: report: accounting clean, 1 invite consumed, principal ERASED 8/8, stop measured, lag back to 0, inspect" || no "r1 report"
if grep -r -q -E 'PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=[A-Za-z0-9_-]{40,}' "$W/r1.out" "$W/evidence" "$W/private" 2>/dev/null; then
  no "r1: a generated token reached a log, record or state"; else ok "r1: no generated token in any log, record or state"; fi
grep -r -q -e 'db-secret-xyz' -e 'Pw-synthetic' -e 'eyJfake' "$W/r1.out" "$W/evidence" && no "r1: a secret leaked" || ok "r1: no env secret, password or token in output or evidence"
"$W/bin/docker" exec parkio-postgres-auth sh -c 'exec psql' psql "select count(*) from erasure_requests" > /dev/null 2>&1; rc=$?
[ "$rc" = 5 ] && ok "r1: the fake refuses a psql outside a read-only session, so every runner query above ran read-only" || no "r1 read-only control rc=$rc"

echo "=== R2/R3 preflight refusals change nothing ==="
fresh open False
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r2.out" 2>&1; echo "exit=$?" >> "$W/r2.out"
has r2.out "PARKIO_REGISTRATION_MODE is open"; has r2.out "PREFLIGHT FAIL"
python3 -I "$TOOLC" start --harness "$W/harness" > "$W/r2b.out" 2>&1; has r2b.out "NOT STARTED"
[ "$(envsha)" = "$SHA0" ] && [ -z "$(calls | grep -v -E '^(systemctl list-units|config --hash|lag )')" ] \
  && ok "r2: nothing changed; only read-only listings ran" || no "r2 changed something"
[ -z "$(ls "$W/private" | grep -E '^[0-9]{8}T')" ] && ok "r2: no run directory left" || no "r2 run dir left"
fresh closed False "PARKIO_ACCOUNT_ERASURE_ENABLED=false"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r3.out" 2>&1; has r3.out "more than once"; has r3.out "PREFLIGHT FAIL"

echo "=== R4 register fails: the invite is an ORPHAN, nothing else is left ==="
fresh closed False
window r4.out register_fail
has r4.out "STOP: creating the synthetic principal failed"; has r4.out "restore env file: ok - byte-identical"
has r4.out "restore synthetic principal: ok - no synthetic principal exists"
has r4.out "ORPHAN: 1 priv001a invite(s) created since W1 are unconsumed"; has r4.out "U05 PRIV-001A WINDOW FAIL"
[ "$(envsha)" = "$SHA0" ] && ok "r4: env byte-identical" || no "r4 env"
calls | grep -q 'stop analytics-service' && no "r4: analytics was stopped" || ok "r4: analytics never stopped"

echo "=== R5/R6/R7 a create that stops after registration: the principal is erased in the restore ==="
for sc in verify_fail login_fail create_hang; do
  fresh closed False
  if [ "$sc" = create_hang ]; then export U05R_HARNESS_TIMEOUT_CAP=4; fi
  window "r5-$sc.out" "$sc"
  unset U05R_HARNESS_TIMEOUT_CAP
  has "r5-$sc.out" "STOP: creating the synthetic principal failed"
  has "r5-$sc.out" "restore synthetic principal: ok - principal"; has "r5-$sc.out" "erased canonically (COMPLETE)"
  report 'a = r["accounting"]; assert a["syntheticAccountsSinceW1"] == [] and a["principal"]["status"] == "ERASED"
assert a["invitesSinceW1"]["unconsumed"] == 0 and r["verdict"] == "FAIL" and not any(n.startswith(("ORPHAN", "PENDING")) for n in r["notes"])' \
    && ok "r5-$sc: no orphan left; verdict FAIL (the window did not complete)" || no "r5-$sc accounting"
  [ "$(envsha)" = "$SHA0" ] && ok "r5-$sc: env byte-identical" || no "r5-$sc env"
done
has r5-create_hang.out "timed out after 4 s"

echo "=== R8/R9 any other change to the env file stops the automatic restore (owner: abort, never overwrite) ==="
for sc in concurrent_edit conflict_edit; do
  fresh closed False
  window "r8-$sc.out" "$sc"
  has "r8-$sc.out" "restore env file: FAILED - CONFLICT: the env file was changed by someone else during the window; nothing was written"
  has "r8-$sc.out" "restore auth-service: FAILED - skipped: the env file is not restored"
  has "r8-$sc.out" "W6 comparison skipped: the env file or auth-service is not restored (RECOVERY.md)"
  has "r8-$sc.out" "U05 PRIV-001A WINDOW FAIL"
  [ "$(calls | grep -c '^up -d --no-deps --no-build auth-service$')" = 1 ] && ok "r8-$sc: auth-service not recreated by the restore" || no "r8-$sc auth"
  [ -e "$W/private/$RUN/env.backup" ] && ok "r8-$sc: the W1 backup is kept for RECOVERY.md" || no "r8-$sc backup removed"
  if [ "$sc" = concurrent_edit ]; then
    grep -q '^OTHER_CONCURRENT=1$' "$W/env" && ok "r8-$sc: the other edit is kept (nothing was written)" || no "r8-$sc other edit lost"
  else
    grep -q '^PARKIO_ACCOUNT_ERASURE_ENABLED=maybe$' "$W/env" && ok "r8-$sc: the file is left as it is" || no "r8-$sc file changed"
  fi
done
python3 -I - "$W/private" <<'PY' && ok "r8: the conflict report names the window keys still set, without values" || no "r8 conflict report"
import glob, json, sys
st = json.load(open(sorted(glob.glob(sys.argv[1] + "/*/state.json"))[-1]))
c = st["envConflict"]; assert "PARKIO_REGISTRATION_MODE" in c["windowKeysStillSet"] and "PARKIO_ACCOUNT_ERASURE_ENABLED" not in c["windowKeysStillSet"]
PY

echo "=== R10 the request completes early: criterion 3 fails, everything restored ==="
fresh closed False
window r10.out complete_early
has r10.out "criterion 3 (IN_PROGRESS while analytics is down) did not hold"; has r10.out "restore analytics-service: ok - started"
[ "$(envsha)" = "$SHA0" ] && ok "r10: env byte-identical" || no "r10 env"
window r10b.out wrong_shape
has r10b.out "criterion 3: expected 7/8 SUCCESS with only analytics missing, got 0/8"

echo "=== R11 the runner dies in W3: the analytics guard, then the window guard ==="
fresh closed False
window r11.out die_in_w3
python3 -I "$W/private/$RUN/window.py" restore --analytics-only --run "$RUN" > "$W/r11a.out" 2>&1; echo "exit=$?" >> "$W/r11a.out"
has r11a.out "analytics guard: analytics-service started by its guard"; has r11a.out "exit=0"
[ "$(envsha)" != "$SHA0" ] && ok "r11: the analytics guard touched nothing else (env still in its window state)" || no "r11a env"
cp "$W/private/$RUN/state.json" "$W/r11-state-before"
python3 -I "$W/private/$RUN/window.py" restore --guard --run "$RUN" > "$W/r11b.out" 2>&1; echo "exit=$?" >> "$W/r11b.out"
has r11b.out "restore synthetic principal: ok - erasure already requested (IN_PROGRESS, 7/8 SUCCESS)"
has r11b.out "restore env file: ok - byte-identical"; has r11b.out "restore erasure completion: ok - request COMPLETE, 8/8 SUCCESS"
has r11b.out "U05 WINDOW COMPARE PASS"; has r11b.out "U05 PRIV-001A RESTORE COMPLETE"
[ "$(envsha)" = "$SHA0" ] && ok "r11: env byte-identical after the window guard" || no "r11 env"

echo "=== R12 analytics-service cannot be started: the env and auth-service are still restored ==="
fresh closed False '' '{"startFails": true}'
window r12.out ""
has r12.out "STOP: start analytics-service failed (exit 1)"; has r12.out "restore analytics-service: FAILED - start analytics-service failed"
has r12.out "restore env file: ok - byte-identical"; has r12.out "restore auth-service: ok - recreated with the W0 settings"
has r12.out "restore guards: ok - both guard timers inactive"; has r12.out "PENDING: the erasure of"; has r12.out "U05 PRIV-001A WINDOW FAIL"
[ "$(envsha)" = "$SHA0" ] && ok "r12: env byte-identical despite the analytics failure" || no "r12 env"

echo "=== R13 SIGTERM during W3: the runner restores ==="
fresh closed False
export U05R_HOLD=8
start r13.out --fault analytics; sleep 1.1
(FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r13.out" 2>&1; echo "exit=$?" >> "$W/r13.out") &
wait_call "stop analytics-service" 30 && sleep 1 && kill -TERM "$(cat "$W/private/$RUN/runner.pid")"
wait_for "$W/r13.out" "exit=" 60
unset U05R_HOLD; export U05R_HOLD=1
has r13.out "STOP: SIGTERM received"; has r13.out "restore analytics-service: ok - started"; has r13.out "restore env file: ok - byte-identical"
has r13.out "U05 PRIV-001A WINDOW FAIL"
[ "$(envsha)" = "$SHA0" ] && ok "r13: env byte-identical" || no "r13 env"

echo "=== R14 SIGTERM during the restore is ignored ==="
fresh closed False '' '{"slowUp": 3}'
start r14.out --fault analytics; sleep 1.1
(FW_SCENARIO="register_fail" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r14.out" 2>&1; echo "exit=$?" >> "$W/r14.out") &
wait_for "$W/r14.out" "restore (the runner holds the run lock)" 60 && sleep 0.5 && kill -TERM "$(cat "$W/private/$RUN/runner.pid")"
wait_for "$W/r14.out" "exit=" 90
has r14.out "restore auth-service: ok - recreated with the W0 settings"; has r14.out "U05 PRIV-001A WINDOW FAIL"
[ "$(envsha)" = "$SHA0" ] && ok "r14: the restore finished despite a SIGTERM" || no "r14 env"

echo "=== R15 a manual restore while the runner is alive ==="
fresh closed False
export U05R_HOLD=20
start r15.out --fault analytics; sleep 1.1
(FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r15.out" 2>&1; echo "exit=$?" >> "$W/r15.out") &
wait_call "stop analytics-service" 30
python3 -I "$W/private/$RUN/window.py" restore --run "$RUN" > "$W/r15a.out" 2>&1; echo "exit=$?" >> "$W/r15a.out"
has r15a.out "STOP: the window runner is still active and restores by itself"; has r15a.out "exit=1"
python3 -I "$W/private/$RUN/window.py" restore --stop-window --run "$RUN" > "$W/r15b.out" 2>&1; echo "exit=$?" >> "$W/r15b.out"
unset U05R_HOLD; export U05R_HOLD=1
has r15b.out "restore: the window runner was stopped"; has r15b.out "restore env file: ok - byte-identical"; has r15b.out "U05 PRIV-001A RESTORE COMPLETE"
calls | grep -q "systemctl kill --signal=SIGKILL u05-priv001a-window-$RUN.service" && ok "r15: --stop-window killed the runner unit first" || no "r15 kill"
[ "$(envsha)" = "$SHA0" ] && ok "r15: env byte-identical" || no "r15 env"

echo "=== R16 the window guard fires while the runner restores: it waits, then checks ==="
fresh closed False '' '{"slowUp": 4}'
export U05R_GUARD_LOCK_WAIT=30
start r16.out --fault analytics; sleep 1.1
(FW_SCENARIO="register_fail" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r16.out" 2>&1; echo "exit=$?" >> "$W/r16.out") &
wait_for "$W/r16.out" "restore (the runner holds the run lock)" 60
python3 -I "$W/private/$RUN/window.py" restore --guard --run "$RUN" > "$W/r16g.out" 2>&1; echo "exit=$?" >> "$W/r16g.out"
wait_for "$W/r16.out" "exit=" 60
unset U05R_GUARD_LOCK_WAIT
has r16.out "U05 PRIV-001A WINDOW FAIL"; has r16g.out "the window already finished at"; has r16g.out "U05 PRIV-001A RESTORE CHECK COMPLETE"
calls | grep -q "systemctl kill" && no "r16: the guard killed the runner during its restore" || ok "r16: the guard waited for the runner's restore (no kill)"
report 'assert r["verdict"] == "FAIL" and r["failure"] == "creating the synthetic principal failed"' \
  && ok "r16: the runner's own report and verdict are kept" || no "r16 report overwritten"
ls "$W/evidence/$RUN" | grep -q '^restore-check-' && ok "r16: the guard's check is a separate record" || no "r16 no check record"

echo "=== R17 the deadline stops the window before the restore budget runs out ==="
fresh closed False
export U05R_RESTORE_BY=330
window r17.out ""
unset U05R_RESTORE_BY
has r17.out "STOP: deadline: the failure case and the recovery needs about 601 s"; has r17.out "restore env file: ok - byte-identical"
calls | grep -q 'stop analytics-service' && no "r17: analytics stopped after the deadline" || ok "r17: analytics never stopped"

echo "=== R18 integrity ==="
fresh closed False
cp "$W/harness/scripts/lib/priv001-synthetic.sh" "$W/lib.bak"; echo "# tampered" >> "$W/harness/scripts/lib/priv001-synthetic.sh"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r18.out" 2>&1
has r18.out "integrity: scripts/lib/priv001-synthetic.sh do not match the pinned SHA-256"; has r18.out "PREFLIGHT FAIL"
cp "$W/lib.bak" "$W/harness/scripts/lib/priv001-synthetic.sh"
U05R_PINS= python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r18b.out" 2>&1
has r18b.out "integrity: scripts/acceptance/create-priv001-synthetic-principal.sh, scripts/acceptance/erase-priv001-synthetic-principal.sh"
has r18b.out "PREFLIGHT FAIL"
M=9ffdd377c188ebd28babd54089b5049d71610435
if git -C "$HERE" cat-file -e "$M^{commit}" 2>/dev/null; then
  python3 -I - "$TOOL" "$HERE" "$M" <<'PYX' && ok "r18: the embedded HARNESS_PINS equal the harness files at the #338 merge" || no "r18 pins differ from the merge"
import hashlib, re, subprocess, sys
src, here, m = sys.argv[1:4]
pins = dict(re.findall(r'"(scripts/[^"]+)":\s*"([0-9a-f]{64})"', open(src).read()))
assert len(pins) == 5
for path, pin in pins.items():
    blob = subprocess.run(["git", "-C", here, "show", f"{m}:{path}"], capture_output=True, check=True).stdout
    assert hashlib.sha256(blob).hexdigest() == pin, path
PYX
else echo "SKIP r18: the #338 merge commit is not in this clone"; fi
python3 -I - "$TOOL" "$SNAP" <<'PY' && ok "r18: the runner's embedded SNAPSHOT_PIN equals the committed snapshot tool" || no "r18 snapshot pin stale"
import hashlib, re, sys
src = open(sys.argv[1]).read()
pin = re.search(r'^SNAPSHOT_PIN = "([0-9a-f]{64})"', src, re.M).group(1)
assert pin == hashlib.sha256(open(sys.argv[2], "rb").read()).hexdigest()
PY

echo "=== R19 contract: the runner parses the real harness formats (PR #336) ==="
python3 -I - "$TOOL" <<'PY' && ok "r19: register/auth_user_id/delete lines, erase evidence and inspect JSON formats" || no "r19 contract"
import json, re, sys
src = open(sys.argv[1]).read()
uid = "0b6f3c1e-2d4a-4b8c-9e1f-123456789abc"
create_out = (f'PRIV-001A: registration mode INVITE; containers resolved\nPRIV-001A: invite=PASS\n'
              f'PRIV-001A: register=PASS user_id={uid} status=PENDING_VERIFICATION\nPRIV-001A: verify=PASS\n'
              f'PASS: PRIV-001A synthetic principal created\nacceptance_id=PRIV001A-X\nauth_user_id={uid}\n')
assert re.search(r"register=PASS user_id=([0-9a-f-]{36})", create_out).group(1) == uid
assert re.search(r"^auth_user_id=([0-9a-f-]{36})$", create_out, re.M).group(1) == uid
assert "delete=ACCEPTED" in "PRIV-001A: delete=ACCEPTED http=200 status=IN_PROGRESS"
ev = {"observations": [{"acks": {}, "at": "x", "auth_state": "ERASURE_IN_PROGRESS", "missing": ["analytics"],
                        "other_requests_since_window_start": 0, "participants": 8, "request_count": 1,
                        "request_status": "IN_PROGRESS", "successCount": 7}]}
last = ev["observations"][-1]
assert last["successCount"] == 7 and last["missing"] == ["analytics"] and last["request_status"] == "IN_PROGRESS"
ins = 'PRIV-001A residue inspection (read-only)\nauth_user_id=x\n{"auth_state" : "ERASED", "participant_ack_count" : 8, "tombstone_count" : 1}'
assert json.loads(re.search(r"\{.*\}", ins, re.S).group(0))["tombstone_count"] == 1
for needle in ('register=PASS user_id=([0-9a-f-]{36})', '"successCount"', '"missing"', '"request_status"', '"tombstone_count"'):
    assert needle in src, needle
PY

echo "=== R20 --fault none: criterion 2 only, analytics-service never touched ==="
fresh closed False
window r20.out "" --fault none
has r20.out "W3 DELETE with every participant running"; has r20.out "U05 WINDOW COMPARE PASS"; has r20.out "U05 PRIV-001A WINDOW PASS"
calls | grep -q 'analytics-service' && no "r20: analytics-service was stopped or started" || ok "r20: analytics-service never stopped or started"
calls | grep 'guard-analytics' | grep -q '^systemd-run' && no "r20 analytics guard armed" || ok "r20: no analytics guard armed"

echo "=== R20b without --fault the default is none: analytics-service is never stopped ==="
fresh closed False
start r20b.out; sleep 1.1
FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r20b.out" 2>&1; echo "exit=$?" >> "$W/r20b.out"
has r20b.out.start "(scenario: u05, fault: none)"; has r20b.out "U05 PRIV-001A WINDOW PASS"
calls | grep -q 'stop analytics-service' && no "r20b: analytics stopped by default" || ok "r20b: the default fault is none"

echo "=== R21 preflight: drift, leftovers, lag, host, units ==="
fresh closed False '' '{"authDriftAtW0": true}'
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r21a.out" 2>&1; has r21a.out "auth-service already differ from the rendered config"
fresh closed False
touch "$W/.env.u05-12345"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r21b.out" 2>&1; has r21b.out "an interrupted write left 1 temporary env file(s)"
rm -f "$W/.env.u05-12345"
fresh closed False '' '{"lagUnreadable": true}'
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r21c.out" 2>&1; has r21c.out "cannot read the consumer lag of parkio.analytics"
fresh closed False '' '{"hostRefused": true}'
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r21d.out" 2>&1; has r21d.out "the harness's own guards refuse this host"

echo "=== R36 release checkout: the PIN, or the PIN plus only the web pin file (MapTiler fix R1) ==="
G=(git -C "$W/release" -c user.name=t -c user.email=t@t)
fresh closed False
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36p.out" 2>&1
has r36p.out "release checkout: at the PIN"; has r36p.out "PREFLIGHT PASS"
mkdir -p "$W/release/docker"
printf 'services:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:%064d\n' 1 > "$W/release/docker/docker-compose.web-release-pin.yml"
"${G[@]}" add -A && "${G[@]}" commit -q -m "web pin only"
fresh closed False
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36a.out" 2>&1
has r36a.out "plus only docker/docker-compose.web-release-pin.yml"; has r36a.out "PREFLIGHT PASS"
echo "dirty" > "$W/release/untracked.txt"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36d.out" 2>&1
has r36d.out "the release checkout is not clean at the PIN"; has r36d.out "PREFLIGHT FAIL"
rm -f "$W/release/untracked.txt"
echo "x" > "$W/release/docker/docker-compose.app-release-pins.yml"
"${G[@]}" add -A && "${G[@]}" commit -q -m "web pin and app pins"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36b.out" 2>&1
has r36b.out "only the PIN, or a descendant of it that changes nothing but docker/docker-compose.web-release-pin.yml"
has r36b.out "PREFLIGHT FAIL"
"${G[@]}" checkout -q --detach "$U05R_PIN"
echo "x" > "$W/release/docker-app-only.yml"; "${G[@]}" add -A && "${G[@]}" commit -q -m "another file only"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36c.out" 2>&1
has r36c.out "is accepted"; has r36c.out "PREFLIGHT FAIL"
"${G[@]}" checkout -q --detach "$U05R_PIN"
tree=$(git -C "$W/release" rev-parse "$U05R_PIN^{tree}")
orphan=$(printf 'orphan\n' | "${G[@]}" commit-tree "$tree")
"${G[@]}" checkout -q --detach "$orphan"
mkdir -p "$W/release/docker"
printf 'services:\n  web:\n    image: ghcr.io/adberilgen35/parkio/web@sha256:%064d\n' 2 > "$W/release/docker/docker-compose.web-release-pin.yml"
"${G[@]}" add -A && "${G[@]}" commit -q -m "web pin on a non-descendant"
python3 -I "$TOOLC" preflight --harness "$W/harness" > "$W/r36e.out" 2>&1
has r36e.out "is accepted"; has r36e.out "PREFLIGHT FAIL"
"${G[@]}" checkout -q --detach "$U05R_PIN"
[ -z "$(git -C "$W/release" status --porcelain)" ] && [ "$(git -C "$W/release" rev-parse HEAD)" = "$U05R_PIN" ] \
  && ok "r36: the test checkout is back at the PIN, clean" || no "r36: the test checkout was not restored"

echo "=== R22 analytics lag that does not drain after the restart is a FAIL ==="
fresh closed False '' '{"lagStuckAfterRestart": true}'
window r22.out ""
has r22.out "analytics consumer lag 84 did not return to the W0 level 0"; has r22.out "U05 PRIV-001A WINDOW FAIL"

echo "=== R23 a guard timer that will not stop is a FAIL ==="
fresh closed False '' '{"disarmFails": true}'
window r23.out ""
has r23.out "is still active after the stop"; has r23.out "U05 PRIV-001A WINDOW FAIL"

echo "=== R24 a preset token and mode invite are not touched ==="
fresh invite True
window r24.out ""
has r24.out "env keys changed: PARKIO_ACCOUNT_ERASURE_ENABLED, PARKIO_REGISTRATION_INVITE_CREATION_ENABLED ("
grep -E 'env keys changed:' "$W/r24.out" | grep -q 'PARKIO_REGISTRATION_MODE' && no "r24: the mode was changed" || ok "r24: the mode (already invite) was not changed"
has r24.out "U05 PRIV-001A WINDOW PASS"
[ "$(envsha)" = "$SHA0" ] && ok "r24: env byte-identical; the preset token untouched" || no "r24 env"

echo "=== R24b an empty token line is replaced and restored; its value never leaves the env file ==="
fresh closed False "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN="
window r24b.out "" --fault none
has r24b.out "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=<generated, 48 chars, never shown>"; has r24b.out "U05 PRIV-001A WINDOW PASS"
[ "$(envsha)" = "$SHA0" ] && ok "r24b: env byte-identical (the empty token line is back)" || no "r24b env"
python3 -I - "$W/private/$RUN/state.json" <<'PYX' && ok "r24b: the state keeps the empty original token line, never the generated value" || no "r24b state"
import json, sys
st = json.load(open(sys.argv[1]))
p = next(x for x in st["patches"] if x["key"] == "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN")
assert p["original"] == "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=\n" and not p["appended"]
PYX
if grep -r -q -E 'PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=[A-Za-z0-9_-]{40,}' "$W/r24b.out" "$W/evidence" "$W/private" 2>/dev/null; then
  no "r24b: the generated token leaked"; else ok "r24b: the generated token is in no log, record or state"; fi

echo "=== R25 status shows the units and no secret ==="
python3 -I "$TOOLC" status --run "$RUN" > "$W/r25.out" 2>&1
has r25.out '"verdict": "PASS"'; has r25.out '"units"'
grep -q -e 'preset-operator-token' -e 'Pw-synthetic' "$W/r25.out" && no "r25 secret in status" || ok "r25: no secret in status"

echo "=== R26 U04: hold, two browser registrations by invite, verification, stored credentials, cleanup ==="
fresh closed False
browser_register() {  # token email: what the web app's register call does in the fake world
  python3 -I - "$W/world.json" "$1" "$2" <<'PYX'
import json, sys, time, uuid
p, tok, email = sys.argv[1:4]
s = json.load(open(p))
inv = next(i for i in s["db"]["invites"] if i.get("token") == tok and not i["consumed"])
inv["consumed"] = True
s["db"]["users"].append({"id": str(uuid.uuid4()), "email": email, "status": "PENDING_VERIFICATION", "at": time.time()})
json.dump(s, open(p, "w"))
PYX
}
A1=priv001a-20261013u04a1abcdef@priv001a.parkio.invalid; A2=priv001a-20261013u04a2abcdef@priv001a.parkio.invalid
start r26.out --scenario u04; sleep 1.1
(FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r26.out" 2>&1; echo "exit=$?" >> "$W/r26.out") &
wait_for "$W/r26.out" "W2 U04 hold until" 60
WIN="$W/private/$RUN/window.py"
T1="$(python3 -I "$WIN" u04-invite --run "$RUN")"; T2="$(python3 -I "$WIN" u04-invite --run "$RUN")"
[[ "$T1" =~ ^[A-Za-z0-9_-]{16,256}$ && "$T2" =~ ^[A-Za-z0-9_-]{16,256}$ && "$T1" != "$T2" ]] && ok "r26: two one-time invite tokens printed" || no "r26 tokens"
A3=priv001a-20261013u04a3abcdef@priv001a.parkio.invalid
printf 'email=%s\npassword=Pw-a1-synthetic-1234\n' "$A1" | python3 -I "$WIN" u04-creds --run "$RUN" --name A1 > "$W/r26c.out" 2>&1
printf 'email=%s\npassword=Pw-a2-synthetic-1234\n' "$A2" | python3 -I "$WIN" u04-creds --run "$RUN" --name A2 >> "$W/r26c.out" 2>&1
printf 'email=%s\npassword=Pw-a3-synthetic-1234\n' "$A3" | python3 -I "$WIN" u04-creds --run "$RUN" --name A3 >> "$W/r26c.out" 2>&1
has r26c.out "stored creds-A1"; has r26c.out "stored creds-A2"; has r26c.out "stored creds-A3"
printf 'email=%s\npassword=Pw-other-synthetic-12\n' "$A2" | python3 -I "$WIN" u04-creds --run "$RUN" --name A1 > "$W/r26e.out" 2>&1
has r26e.out "STOP: creds-A1 already exists; use another name"
printf 'email=%s\npassword=Pw-other-synthetic-12\n' "$A1" | python3 -I "$WIN" u04-creds --run "$RUN" --name A9 > "$W/r26f.out" 2>&1
has r26f.out "STOP: creds-A1 already holds this email"
grep -q '^password=Pw-a1-synthetic-1234$' "$W/private/$RUN/u04/creds-A1" && ok "r26: a refused overwrite leaves creds-A1 intact" || no "r26 creds-A1 changed"
browser_register "$T1" "$A1"; browser_register "$T2" "$A2"
python3 -I "$WIN" u04-verify --run "$RUN" --email "$A2" > "$W/r26v.out" 2>&1; has r26v.out "verified "
ma="$(tail -1 "$W/verify-max-age.log")"
[[ "$ma" =~ ^[0-9]+$ ]] && [ "$ma" -ge 60 ] && [ "$ma" -le 300 ] && ok "r26: verification bound to the window (max age ${ma} s)" || no "r26 max age '${ma}'"
python3 -I "$WIN" u04-verify --run "$RUN" --email someone@gmail.com > "$W/r26w.out" 2>&1; has r26w.out "STOP: not a synthetic PRIV-001A email"
printf 'email=someone@gmail.com\npassword=Pw-real-person-123456\n' | python3 -I "$WIN" u04-creds --run "$RUN" --name X > "$W/r26x.out" 2>&1
has r26x.out "STOP: credentials need a synthetic PRIV-001A email"
python3 -I "$WIN" u04-done --run "$RUN" > "$W/r26d.out" 2>&1; has r26d.out "U04 hold ends"
wait_for "$W/r26.out" "exit=" 90
has r26.out "A1: "; has r26.out "erased canonically (COMPLETE)"; has r26.out "W5 U04 accounting (read-only)"
has r26.out "A3: no account (never registered, or already erased)"
has r26.out "U05 WINDOW COMPARE PASS"; has r26.out "U05 PRIV-001A WINDOW PASS"; has r26.out "exit=0"
report 'a = r["accounting"]; u = a["u04Accounts"]
assert r["scenario"] == "u04" and r["verdict"] == "PASS" and a["invitesSinceW1"] == {"created": 2, "unconsumed": 0}
assert [x["name"] for x in u] == ["A1", "A2", "A3"] and "id" not in u[2]
assert all(x["status"] == "ERASED" and x["emailErased"] and x["request"] == "COMPLETE" for x in u[:2])
assert a["syntheticAccountsSinceW1"] == [] and a["otherErasureRequestsSinceW1"] == 0' \
  && ok "r26: report: 2 invites consumed, A1 (registered, never verified) and A2 erased, nothing left" || no "r26 report"
[ "$(envsha)" = "$SHA0" ] && ok "r26: env byte-identical" || no "r26 env"
[ -z "$(ls "$W/private/$RUN/u04" | grep '^creds-')" ] && ok "r26: the U04 credentials files are shredded after the verified restore" || no "r26 creds left"
calls | grep -q 'analytics-service' && no "r26: analytics touched" || ok "r26: analytics-service never stopped or started"
if grep -r -q -F -e "$T1" -e "$T2" -e "Pw-a1-synthetic" "$W/r26.out" "$W/evidence" "$W/private" 2>/dev/null; then
  no "r26: an invite token or password reached a log, record or state"; else ok "r26: no invite token or password in any log, record or state"; fi

echo "=== R27 U04: the hold ends without u04-done; an unused invite is an ORPHAN ==="
fresh closed False
export U05R_U04_HOLD=4
start r27.out --scenario u04; sleep 1.1
(FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r27.out" 2>&1; echo "exit=$?" >> "$W/r27.out") &
wait_for "$W/r27.out" "W2 U04 hold until" 60
python3 -I "$W/private/$RUN/window.py" u04-invite --run "$RUN" > /dev/null 2>&1
wait_for "$W/r27.out" "exit=" 90
unset U05R_U04_HOLD
has r27.out "STOP: the U04 hold reached its end without u04-done"; has r27.out "restore U04 accounts: ok - no U04 credentials stored"
has r27.out "ORPHAN: 1 priv001a invite(s) created since W1 are unconsumed"; has r27.out "U05 PRIV-001A WINDOW FAIL"
python3 -I -c 'import json,sys; assert json.load(open(sys.argv[1]))["phase"] != "U04-HOLD"' "$W/private/$RUN/state.json" \
  && ok "r27: the hold is closed (no operator command is accepted while the window restores)" || no "r27 phase still U04-HOLD"
[ "$(envsha)" = "$SHA0" ] && ok "r27: env byte-identical" || no "r27 env"

echo "=== R29 U04: no time left to erase: the account is reported, the restore fails closed, credentials kept ==="
fresh closed False
export U05R_U04_ERASE_MIN=5000
start r29.out --scenario u04; sleep 1.1
(FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/r29.out" 2>&1; echo "exit=$?" >> "$W/r29.out") &
wait_for "$W/r29.out" "W2 U04 hold until" 60
WIN="$W/private/$RUN/window.py"
T1="$(python3 -I "$WIN" u04-invite --run "$RUN")"
printf 'email=%s\npassword=Pw-a1-synthetic-1234\n' "$A1" | python3 -I "$WIN" u04-creds --run "$RUN" --name A1 > /dev/null 2>&1
browser_register "$T1" "$A1"
python3 -I "$WIN" u04-done --run "$RUN" > /dev/null 2>&1
wait_for "$W/r29.out" "exit=" 90
unset U05R_U04_ERASE_MIN
has r29.out "NOT ERASED: "; has r29.out "restore U04 accounts: FAILED"; has r29.out "U05 PRIV-001A WINDOW FAIL"
[ -f "$W/private/$RUN/u04/creds-A1" ] && ok "r29: the unerased account's credentials are kept" || no "r29 creds gone"
[ "$(envsha)" = "$SHA0" ] && ok "r29: env byte-identical" || no "r29 env"
calls | grep -q 'analytics-service' && no "r29: analytics touched" || ok "r29: analytics-service never stopped or started"

u03_window() {  # tag knobs: a U04 window held open for U03
  fresh closed False "" "$2"
  start "$1.out" --scenario u04; sleep 1.1
  (FW_SCENARIO="" FW_RUN="$RUN" python3 -I "$W/private/$RUN/window.py" run --run "$RUN" > "$W/$1.out" 2>&1; echo "exit=$?" >> "$W/$1.out") &
  wait_for "$W/$1.out" "W2 U04 hold until" 60
  WIN="$W/private/$RUN/window.py"
}
u03_end() {  # tag
  python3 -I "$WIN" u04-done --run "$RUN" > /dev/null 2>&1
  wait_for "$W/$1.out" "exit=" 120
}
export U05R_U03_TTL=2 U05R_U03_SLACK=4 U05R_U03_POLL=1

echo "=== R30 U03: harness accounts, elevation, controls, demotion, refusal within the TTL, cleanup ==="
u03_window r30 '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" > "$W/r30n.out" 2>&1; has r30n.out "STOP: choose --owner-steps"
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r30u.out" 2>&1; echo "u03 exit=$?" >> "$W/r30u.out"
has r30u.out "U03 ACCEPTANCE PASS"; has r30u.out "u03 exit=0"; has r30u.out "PASS control: admin read 200, only X"
has r30u.out "PASS control: export authorized"; has r30u.out "PASS control: admin mutation authorized (404, no such user)"
has r30u.out "PASS old admin token refused on read within the TTL"; has r30u.out "PASS old admin token refused on export within the TTL"
has r30u.out "PASS old admin token refused on mutation within the TTL"; has r30u.out "PASS X's new token carries USER only"
has r30u.out "PASS unrelated user Y unaffected"; has r30u.out "PASS Y's session epoch unchanged"
has r30u.out "PASS missing JWT refused"; has r30u.out "PASS malformed JWT refused"; has r30u.out "PASS re-signed (invalid signature) JWT refused"
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r30v.out" 2>&1; has r30v.out "STOP: U03 already ran in this window"
u03_end r30
has r30.out "U03X: "; has r30.out "U03Y: "; has r30.out "U05 PRIV-001A WINDOW PASS"
report 'a = r["accounting"]; u = {x["name"]: x for x in a["u04Accounts"]}
assert u["U03X"]["status"] == "ERASED" and u["U03Y"]["status"] == "ERASED" and [e["roles"] for e in a["u03Elevated"]] == [["USER"]]
assert a["invitesSinceW1"] == {"created": 2, "unconsumed": 0} and a["syntheticAccountsSinceW1"] == []' \
  && ok "r30: report: X and Y erased, X holds no ADMIN, 2 invites consumed" || no "r30 report"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["verdict"] == "PASS" and set(r["rejectedAfterSeconds"]) == {"read", "export", "mutation"}' \
  "$W/evidence/$RUN/u03/u03-results.json" && ok "r30: u03-results.json PASS with the three refusal times" || no "r30 results"
if grep -r -q -E 'eyJ|Pw-synthetic|accessToken|rt-fake' "$W/r30u.out" "$W/evidence/$RUN" "$W/private/$RUN/u04/u03.log" 2>/dev/null; then
  no "r30: a token or password reached the U03 output, log or records"; else ok "r30: no token or password in the U03 output, log or records"; fi
[ "$(envsha)" = "$SHA0" ] && ok "r30: env byte-identical" || no "r30 env"

echo "=== R31 U03: the old token is never refused: FAIL; X still loses ADMIN; the window cleans up ==="
u03_window r31 '{"u03TTL": 2, "u03NoRevoke": true}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r31u.out" 2>&1; echo "u03 exit=$?" >> "$W/r31u.out"
has r31u.out "FAIL old admin token refused on read within the TTL"; has r31u.out "U03 ACCEPTANCE FAIL"; has r31u.out "u03 exit=1"
has r31u.out "PASS X holds only USER in the database"
u03_end r31
has r31.out "U05 PRIV-001A WINDOW PASS"

echo "=== R32 U03: a failure after the elevation: X's ADMIN is removed before the command ends ==="
u03_window r32 '{"u03LoginFailAfterElevate": true}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r32u.out" 2>&1; echo "u03 exit=$?" >> "$W/r32u.out"
has r32u.out "STOP: login failed (http 503)"; has r32u.out "U03 cleanup: X's ADMIN removed (1 row)"; has r32u.out "U03 ACCEPTANCE FAIL"
u03_end r32
has r32.out "U05 PRIV-001A WINDOW PASS"
report '[e["roles"] for e in r["accounting"]["u03Elevated"]] == [["USER"]] or sys.exit(1)' && ok "r32: X holds no ADMIN" || no "r32 roles"

echo "=== R33 U03: the command is killed while X holds ADMIN: the window removes it before the erasure ==="
u03_window r33 '{"u03HangAfterElevate": true}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r33u.out" 2>&1 & UP=$!
wait_for "$W/r33u.out" "PASS X holds ADMIN and USER in the database" 60
sleep 1; kill -KILL "$UP" 2>/dev/null; wait "$UP" 2>/dev/null; pkill -KILL -f "$W/harness.py lib-http" 2>/dev/null
u03_end r33
has r33.out "ADMIN removed before the erasure (1 row)"; has r33.out "U05 PRIV-001A WINDOW PASS"
report '[e["roles"] for e in r["accounting"]["u03Elevated"]] == [["USER"]] or sys.exit(1)' && ok "r33: X holds no ADMIN after the window" || no "r33 roles"
echo "=== R30b U03: a seed call cached X's epoch before the grant: the controls wait it out, then pass ==="
u03_window r30b '{"u03TTL": 3, "u03SeedAlways": true}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --sql-elevation > "$W/r30bu.out" 2>&1; echo "u03 exit=$?" >> "$W/r30bu.out"
has r30bu.out "U03 ACCEPTANCE PASS"; has r30bu.out "PASS control: admin read 200, only X"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["acceptedAfterTries"]["X admin read"] > 1' \
  "$W/evidence/$RUN/u03/u03-results.json" && ok "r30b: X's admin token was accepted only after the cached epoch expired" || no "r30b tries"
u03_end r30b

echo "=== R34 U03 owner mode: the owner grants and revokes in the console ==="
u03_window r34 '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --owner-steps > "$W/r34u.out" 2>&1 & UP=$!
owner() {  # grant|revoke <id>: the owner's console through auth-service (role row, epoch bump); survives lost updates
  python3 -I - "$W/world.json" "$1" "$2" <<'PYO'
import json, os, sys, time
p, act, uid = sys.argv[1:4]
want = {"grant": ["ADMIN", "USER"], "revoke": ["USER"], "grant-moderator": ["MODERATOR", "USER"],
        "sql-revoke": ["USER"]}[act]
audit = {"grant": "ADMIN_USER_ROLE_GRANTED", "revoke": "ADMIN_USER_ROLE_REVOKED"}.get(act)
for _ in range(50):
    s = json.load(open(p))
    u = next(x for x in s["db"]["users"] if x["id"] == uid)
    if sorted(u.get("roles", ["USER"])) == want:
        time.sleep(0.3)
        s = json.load(open(p))
        if sorted(next(x for x in s["db"]["users"] if x["id"] == uid).get("roles", ["USER"])) == want:
            break
        continue
    u["roles"] = want; u["epoch"] = u.get("epoch", 0) + 1
    if audit:
        s["db"].setdefault("audit", []).append({"uid": uid, "action": audit, "at": time.time()})
    tmp = p + ".owner"; json.dump(s, open(tmp, "w")); os.replace(tmp, p)
PYO
}
wait_for "$W/r34u.out" "OWNER STEP 1" 60
XID="$(grep -o 'open the user [0-9a-f-]\{36\}' "$W/r34u.out" | head -1 | awk '{print $4}')"; owner grant "$XID"
wait_for "$W/r34u.out" "OWNER STEP 2" 60; sleep 1.5; owner revoke "$XID"
wait "$UP"; echo "u03 exit=$?" >> "$W/r34u.out"
has r34u.out "the grant is seen, with its console audit row"; has r34u.out "U03 ACCEPTANCE PASS"; has r34u.out "u03 exit=0"
has r34u.out "PASS the revoke came through the console (audit row, SUCCESS)"; has r34u.out "PASS the cache was warm"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); assert r["mode"] == "owner" and set(r["rejectedAfterSeconds"]) == {"read", "export", "mutation"}' \
  "$W/evidence/$RUN/u03/u03-results.json" && ok "r34: owner-mode record with the three refusal times" || no "r34 record"
calls | grep -q "demote" && no "r34: the runner itself demoted" || ok "r34: no runner role statement in owner mode"
u03_end r34
has r34.out "U05 PRIV-001A WINDOW PASS"

echo "=== R34b U03 owner mode: the revoke comes long after the TTL; the warm cache keeps the measurement honest ==="
u03_window r34b '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --owner-steps > "$W/r34bu.out" 2>&1 & UP=$!
wait_for "$W/r34bu.out" "OWNER STEP 1" 60
XID="$(grep -o 'open the user [0-9a-f-]\{36\}' "$W/r34bu.out" | head -1 | awk '{print $4}')"; owner grant "$XID"
wait_for "$W/r34bu.out" "OWNER STEP 2" 60; sleep 7; owner revoke "$XID"
wait "$UP"; echo "u03 exit=$?" >> "$W/r34bu.out"
has r34bu.out "U03 ACCEPTANCE PASS"; has r34bu.out "PASS the cache was warm"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); t=r["t0"]; assert t["lastAcceptedBeforeT0Seconds"] is not None and t["lastAcceptedBeforeT0Seconds"] <= 8' \
  "$W/evidence/$RUN/u03/u03-results.json" && ok "r34b: the old token was accepted a few seconds before the late revoke" || no "r34b warm"
u03_end r34b

echo "=== R34c U03 owner mode: ADMIN disappears without a console audit row ==="
u03_window r34c '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --owner-steps > "$W/r34cu.out" 2>&1 & UP=$!
wait_for "$W/r34cu.out" "OWNER STEP 1" 60
XID="$(grep -o 'open the user [0-9a-f-]\{36\}' "$W/r34cu.out" | head -1 | awk '{print $4}')"; owner grant "$XID"
wait_for "$W/r34cu.out" "OWNER STEP 2" 60; sleep 1.5; owner sql-revoke "$XID"
wait "$UP"; echo "u03 exit=$?" >> "$W/r34cu.out"
has r34cu.out "STOP: X lost ADMIN without a console audit row"; has r34cu.out "U03 ACCEPTANCE FAIL"
u03_end r34c

echo "=== R34d U03 owner mode: a wrong role is granted: stop at once, and the window does not erase X ==="
u03_window r34d '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --owner-steps > "$W/r34du.out" 2>&1 & UP=$!
wait_for "$W/r34du.out" "OWNER STEP 1" 60
XID="$(grep -o 'open the user [0-9a-f-]\{36\}' "$W/r34du.out" | head -1 | awk '{print $4}')"; owner grant-moderator "$XID"
wait "$UP"; echo "u03 exit=$?" >> "$W/r34du.out"
has r34du.out "only ADMIN may be granted"; has r34du.out "U03 ACCEPTANCE FAIL"
u03_end r34d
has r34d.out "holds MODERATOR; not erased (the owner removes the role first)"; has r34d.out "ELEVATED: the synthetic account"
has r34d.out "U05 PRIV-001A WINDOW FAIL"

echo "=== R35 U03 owner mode: the grant never comes ==="
export U05R_U03_OWNER_WAIT=3
u03_window r35 '{"u03TTL": 2}'
python3 -I "$WIN" u03-acceptance --run "$RUN" --owner-steps > "$W/r35u.out" 2>&1; echo "u03 exit=$?" >> "$W/r35u.out"
unset U05R_U03_OWNER_WAIT
has r35u.out "STOP: the owner's ADMIN grant was not seen"; has r35u.out "U03 ACCEPTANCE FAIL"
u03_end r35
has r35.out "U05 PRIV-001A WINDOW PASS"
unset U05R_U03_TTL U05R_U03_SLACK U05R_U03_POLL

echo "=== R28 U04 refusals ==="
python3 -I "$W/private/$RUN/window.py" u04-invite --run "$RUN" > "$W/r28a.out" 2>&1; has r28a.out "STOP: no U04 hold is open for this run"
python3 -I "$TOOLC" start --harness "$W/harness" --scenario u04 --fault analytics > "$W/r28b.out" 2>&1
has r28b.out "NOT STARTED: --scenario must be u05 or u04, and u04 runs only with --fault none"

# A broken heredoc or an early exit would skip checks silently: require the full count.
[ "$pass" -ge 160 ] || no "only ${pass} checks ran (expected at least 160): the self-test did not run to the end"
echo "=== summary: ${pass} ok, ${fail} FAIL ==="
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$((fail > 0))"
