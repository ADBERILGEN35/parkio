#!/usr/bin/env bash
# Local self-test of u05-priv001a-window.py together with the real u05-window-snapshot.py, in a fake world.
# The fake world holds the containers, the auth database rows and the env file; a fake production wrapper renders the
# erasure flag through the shared app anchor (every app service's rendered config changes while it is on), recreates
# auth-service with `up -d --no-deps` and stops/starts analytics-service; fake docker, systemd-run and systemctl record
# every call; fake harness scripts enforce what the real services enforce (invite route only with mode=invite and invite
# creation on, DELETE only with the flag on, IN_PROGRESS only while analytics is stopped).
# R1 happy path (W0 mode closed, token unset): PASS; guards armed before the change and disarmed; analytics stopped and
#    started (never `up`), within the limit; only auth-service recreated; env restored byte-identically; cleanup proof;
#    compare PASS; backup and credentials removed; the generated token in no log, record, state or report;
# R2 mode open, R3 a duplicate window key -> preflight FAIL, nothing changed;
# R4 creating the principal fails -> restored at once (env byte-identical, auth recreated, analytics never stopped), FAIL;
# R5 an unrelated concurrent env edit during the window -> only the window keys reverted, the other edit kept;
# R6 a window key changed by someone else -> CONFLICT: the file is left as it is, auth-service not recreated;
# R7 the request completes early (no IN_PROGRESS) -> FAIL, analytics started, env restored;
# R8 the runner dies after stopping analytics -> `restore --analytics-only` (the analytics guard) starts it, nothing else;
#    then `restore --guard` (the window guard) kills the runner unit and restores everything;
# R9 an operator token already set -> it is not touched; mode already invite -> not touched.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u05-priv001a-window.py"; SNAP="$HERE/u05-window-snapshot.py"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/harness/scripts/acceptance" "$W/harness/scripts/lib" "$W/release/scripts"

cat > "$W/fakeworld.py" <<'PY'
import hashlib, json, os, re, sys, time, uuid
STATE = os.environ["FW_STATE"]
def load(): return json.load(open(STATE))
def save(s): json.dump(s, open(STATE, "w"))
def log(s, what): s.setdefault("calls", []).append(what)
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
def init(mode, token_set, erasure="false", extra=""):
    lines = [f"PARKIO_ACCOUNT_ERASURE_ENABLED={erasure}", f"PARKIO_REGISTRATION_MODE={mode}",
             "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=false", "POSTGRES_AUTH_PASSWORD=db-secret-xyz"]
    if token_set: lines.append("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=preset-operator-token-" + "x" * 30)
    if extra: lines.append(extra)
    open(os.environ["FW_ENV"], "w").write("\n".join(lines) + "\n")
    os.chmod(os.environ["FW_ENV"], 0o600)
    vals = env_file_values()
    cs = {}
    for svc in APP + ["postgres-auth", "kafka", "minio", "nr-collector"]:
        name = f"parkio-{svc}-1" if svc in APP else f"parkio-{svc}"
        env = rendered_env(svc, vals)
        img = "sha256:img-" + svc
        cs[name] = {"Id": uuid.uuid4().hex * 2, "Image": img, "service": svc, "project": "parkio" if svc != "nr-collector" else "nr",
                    "status": "running", "startedAt": "2026-10-12T12:49:14Z", "restartCount": 0, "health": "healthy",
                    "env": env, "hash": chash(svc, img, env)}
    save({"containers": cs, "db": {"users": [], "invites": [], "requests": []}, "calls": []})
PY

cat > "$W/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, re, sys
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
a = sys.argv[1:]; s = fw.load()
if a[:2] == ["ps", "-a"]:
    print("\n".join(c["Id"][:12] for c in s["containers"].values())); sys.exit(0)
if a[:1] == ["inspect"]:
    out = []
    for name, c in s["containers"].items():
        out.append({"Name": "/" + name, "Id": c["Id"], "Image": c["Image"], "RestartCount": c["restartCount"],
                    "Config": {"Labels": {"com.docker.compose.project": c["project"], "com.docker.compose.service": c["service"],
                                          "com.docker.compose.config-hash": c["hash"]},
                               "Env": [f"{k}={v}" for k, v in c["env"].items()]},
                    "State": {"Status": c["status"], "StartedAt": c["startedAt"], "Health": {"Status": c["health"]}}})
    print(json.dumps(out)); sys.exit(0)
if a[:2] == ["exec", "-i"] and "psql" in a:
    sql = a[-1]; db = s["db"]
    def users(): return db["users"]
    if sql == "select count(*) from erasure_requests": print(len(db["requests"]))
    elif "from auth_users where email like 'priv001a-%" in sql: print(sum(1 for u in users() if u["email"].startswith("priv001a-")))
    elif "(email like 'erased-%@invalid.localhost')::text" in sql:
        uid = re.search(r"id = '([0-9a-f-]+)'", sql).group(1)
        print(next(("true" if u["email"].startswith("erased-") else "false") for u in users() if u["id"] == uid))
    elif sql.startswith("select status from auth_users"):
        uid = re.search(r"id = '([0-9a-f-]+)'", sql).group(1)
        print(next(u["status"] for u in users() if u["id"] == uid))
    elif "from registration_invites" in sql:
        inv = [i for i in db["invites"] if i["created_by"] == "priv001a"]
        print(sum(1 for i in inv if not i["consumed"]) if "consumed_at is null" in sql else len(inv))
    elif "from erasure_requests where auth_user_id <>" in sql:
        uid = re.search(r"auth_user_id <> '([0-9a-f-]+)'", sql).group(1)
        print(sum(1 for r in db["requests"] if r["uid"] != uid))
    else: sys.stderr.write("fake docker: unknown sql " + sql); sys.exit(3)
    sys.exit(0)
sys.stderr.write(f"fake docker: unhandled {a}\n"); sys.exit(2)
PY

cat > "$W/release/scripts/parkio-prod-compose.sh" <<'SH'
#!/usr/bin/env bash
exec python3 -I "$FW_DIR/compose.py" "$@"
SH
cat > "$W/compose.py" <<'PY'
import os, sys, time, uuid
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
a = sys.argv[1:]; s = fw.load(); fw.log(s, " ".join(a))
vals = fw.env_file_values()
def find(svc): return next(n for n, c in s["containers"].items() if c["service"] == svc and c["project"] == "parkio")
if a[:3] == ["config", "--hash", "*"]:
    for c in s["containers"].values():
        if c["project"] == "parkio":
            print(c["service"], fw.chash(c["service"], c["Image"], fw.rendered_env(c["service"], vals)))
elif a == ["up", "-d", "--no-deps", "auth-service"]:
    n = find("auth-service"); c = s["containers"][n]
    c["env"] = fw.rendered_env("auth-service", vals); c["hash"] = fw.chash("auth-service", c["Image"], c["env"])
    c["Id"] = os.urandom(32).hex(); c["startedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S.%fZ", time.gmtime())
elif a == ["stop", "analytics-service"]:
    s["containers"][find("analytics-service")]["status"] = "exited"
elif a == ["start", "analytics-service"]:
    c = s["containers"][find("analytics-service")]; c["status"] = "running"
    c["startedAt"] = time.strftime("%Y-%m-%dT%H:%M:%S.%fZ", time.gmtime())
else:
    fw.save(s); sys.stderr.write(f"fake compose: unhandled {a}\n"); sys.exit(2)
fw.save(s)
PY

cat > "$W/bin/systemd-run" <<'PY'
#!/usr/bin/env python3
import os, sys
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
s = fw.load(); fw.log(s, "systemd-run " + " ".join(sys.argv[1:])); fw.save(s)
PY
cat > "$W/bin/systemctl" <<'PY'
#!/usr/bin/env python3
import os, sys
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
s = fw.load(); fw.log(s, "systemctl " + " ".join(sys.argv[1:])); fw.save(s)
PY

cat > "$W/harness.py" <<'PY'
import json, os, re, sys, time, uuid
sys.path.insert(0, os.environ["FW_DIR"]); import fakeworld as fw
kind, a = sys.argv[1], sys.argv[2:]
s = fw.load(); sc = os.environ.get("FW_SCENARIO", "")
def opt(n): return a[a.index(n) + 1] if n in a else None
auth = next(c for c in s["containers"].values() if c["service"] == "auth-service")
analytics = next(c for c in s["containers"].values() if c["service"] == "analytics-service")
if kind == "create":
    if sc == "create_fail": print("ERROR: register failed http=500"); sys.exit(1)
    e = auth["env"]
    if e.get("PARKIO_REGISTRATION_MODE") != "invite" or e.get("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED") != "true" or not e.get("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"):
        print("ERROR: invite creation failed http=403"); sys.exit(1)
    uid = str(uuid.uuid4()); email = "priv001a-20261013090000abcdef12@priv001a.parkio.invalid"
    s["db"]["users"].append({"id": uid, "email": email, "status": "ACTIVE"})
    s["db"]["invites"].append({"created_by": "priv001a", "consumed": True})
    fw.save(s)
    open(opt("--credentials-file"), "w").write(f"auth_user_id={uid}\nemail={email}\npassword=pw-synthetic-123456\naccess_token=eyJfake.tok\n")
    if sc == "concurrent_edit": open(os.environ["FW_ENV"], "a").write("OTHER_CONCURRENT=1\n")
    if sc == "conflict_edit":
        t = open(os.environ["FW_ENV"]).read().replace("PARKIO_ACCOUNT_ERASURE_ENABLED=true", "PARKIO_ACCOUNT_ERASURE_ENABLED=maybe")
        open(os.environ["FW_ENV"], "w").write(t)
    print("PRIV-001A: invite=PASS\nPRIV-001A: login=PASS status=ACTIVE\nPASS: PRIV-001A synthetic principal created")
    print(f"auth_user_id={uid}")
    if opt("--evidence-file"): open(opt("--evidence-file"), "w").write("{}")
    sys.exit(0)
if kind == "erase":
    creds = dict(l.split("=", 1) for l in open(opt("--credentials-file")).read().splitlines() if "=" in l)
    u = next(x for x in s["db"]["users"] if x["id"] == creds["auth_user_id"])
    if "--delete" in a:
        if auth["env"].get("PARKIO_ACCOUNT_ERASURE_ENABLED") != "true": print("ERROR: DELETE failed http=403"); sys.exit(1)
        s["db"]["requests"].append({"uid": u["id"], "status": "IN_PROGRESS"}); u["status"] = "ERASURE_IN_PROGRESS"; fw.save(s)
        print("PRIV-001A: delete=ACCEPTED http=202 status=IN_PROGRESS")
    req = next((r for r in s["db"]["requests"] if r["uid"] == u["id"]), None)
    if opt("--expect") == "IN_PROGRESS":
        ok = analytics["status"] == "exited" and sc != "complete_early" and req is not None
        if ok: time.sleep(int(opt("--hold-seconds") or 1))
        print("  request=IN_PROGRESS auth=ERASURE_IN_PROGRESS success=7/8 missing=analytics" if ok else "  request=COMPLETE")
        print(f"PRIV-001A erasure observation: expect=IN_PROGRESS verdict={'PASS' if ok else 'FAIL'}")
        if opt("--evidence-file"): open(opt("--evidence-file"), "w").write("{}")
        sys.exit(0 if ok else 1)
    ok = analytics["status"] == "running" and req is not None
    if ok:
        req["status"] = "COMPLETE"; u["status"] = "ERASED"; u["email"] = f"erased-{uuid.uuid4()}@invalid.localhost"; fw.save(s)
    print("  request=COMPLETE auth=ERASED success=8/8" if ok else "  request=IN_PROGRESS")
    print(f"PRIV-001A erasure observation: expect=COMPLETE verdict={'PASS' if ok else 'FAIL'}")
    if opt("--evidence-file"): open(opt("--evidence-file"), "w").write("{}")
    sys.exit(0 if ok else 1)
if kind == "inspect":
    print('{"auth_state" : "ERASED", "participant_acks" : {"analytics" : "SUCCESS"}}'); sys.exit(0)
PY
for k in create erase inspect; do
  f="$W/harness/scripts/acceptance/$k-priv001-synthetic-principal.sh"; [ "$k" = inspect ] && f="$W/harness/scripts/acceptance/inspect-priv001-synthetic-residue.sh"
  printf '#!/usr/bin/env bash\nexec python3 -I "$FW_DIR/harness.py" %s "$@"\n' "$k" > "$f"
done
: > "$W/harness/scripts/lib/priv001-synthetic.sh"; : > "$W/harness/scripts/lib/dark-gateway-url.sh"
chmod +x "$W/bin/"* "$W/release/scripts/parkio-prod-compose.sh"
git -C "$W/release" init -q && git -C "$W/release" add -A && git -C "$W/release" -c user.name=t -c user.email=t@t commit -q -m pin
export FW_DIR="$W" FW_STATE="$W/world.json" FW_ENV="$W/env"
export U05R_TEST=1 U05W_TEST=1 U05R_RELEASE="$W/release" U05R_PIN="$(git -C "$W/release" rev-parse HEAD)" U05R_ENV_FILE="$W/env" \
  U05R_PRIVATE="$W/private" U05R_EVIDENCE="$W/evidence" U05R_SNAPSHOT="$SNAP" U05R_DOCKER="$W/bin/docker" \
  U05R_SYSTEMD_RUN="$W/bin/systemd-run" U05R_SYSTEMCTL="$W/bin/systemctl" U05R_HOLD=1 U05R_COMPLETE_TIMEOUT=5 \
  U05R_HEALTH_TIMEOUT=5 U05R_POLL=0 U05W_OWNER="$(id -un)" U05R_OWNER="$(id -un)"
mkdir -p "$W/private" "$W/evidence"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
hasnt() { if grep -qF -- "$2" "$W/$1"; then no "$1: unexpected $2"; else ok "$1: no $2"; fi; }
calls() { python3 -I -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["calls"]))' "$W/world.json"; }
fresh() { rm -rf "$W/private"/* "$W/evidence"/*; python3 -I -c "import sys; sys.path.insert(0, '$W'); import fakeworld as fw; fw.init('$1', $2, extra='${3:-}')"; }
window() {  # out scenario
  FW_SCENARIO="${2:-}" python3 -I "$TOOL" start --harness "$W/harness" > "$W/$1.start" 2>&1
  RUN="$(ls "$W/private" | grep -E '^[0-9]{8}T[0-9]{6}Z$' | tail -1)"
  FW_SCENARIO="${2:-}" python3 -I "$TOOL" run --run "$RUN" > "$W/$1" 2>&1; echo "exit=$?" >> "$W/$1"
}
token_of() { sed -n 's/^PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=//p' "$W/env" | tail -1; }

# R1
fresh closed False; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
window r1.out ""
sed -e "s#$W#<W>#g" "$W/r1.out" | head -60
has r1.out "U05 PRIV-001A WINDOW PASS"; has r1.out "exit=0"
has r1.out "env keys changed: PARKIO_ACCOUNT_ERASURE_ENABLED, PARKIO_REGISTRATION_INVITE_CREATION_ENABLED, PARKIO_REGISTRATION_MODE, PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
has r1.out "<generated, 48 chars, never shown>"; has r1.out "env file restore: byte-identical"; has r1.out "U05 WINDOW COMPARE PASS"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && ok "r1: env file byte-identical to W0" || no "r1 env sha"
calls > "$W/r1.calls"
python3 -I - "$W/r1.calls" <<'PY' && ok "r1: guards before the change, only auth recreated, analytics stopped and started (never up), guards disarmed" || no "r1 call order"
import sys
c = [l for l in open(sys.argv[1]).read().splitlines()]
i_wguard = next(i for i, l in enumerate(c) if "u05-priv001a-guard-window" in l and l.startswith("systemd-run"))
i_up = [i for i, l in enumerate(c) if l == "up -d --no-deps auth-service"]
i_aguard = next(i for i, l in enumerate(c) if "u05-priv001a-guard-analytics" in l and l.startswith("systemd-run"))
i_stop = c.index("stop analytics-service"); i_start = c.index("start analytics-service")
assert i_wguard < i_up[0] and len(i_up) == 2 and i_aguard < i_stop < i_start
assert not any(l.startswith("up") and "analytics" in l for l in c)
assert not any(l.startswith("up") and "auth-service" not in l for l in c)
assert any(l == "systemctl stop u05-priv001a-guard-analytics.timer" for l in c)
assert any(l == "systemctl stop u05-priv001a-guard-window.timer" for l in c)
PY
RUN="$(ls "$W/private" | tail -1)"
[ ! -e "$W/private/$RUN/env.backup" ] && [ ! -e "$W/private/$RUN/creds" ] && ok "r1: env backup and credentials removed after the verified restore" || no "r1 secrets left"
python3 -I - "$W/evidence/$RUN/window-report.json" <<'PY' && ok "r1: report (cleanup proof, analytics stop within the limit)" || no "r1 report"
import json, sys
r = json.load(open(sys.argv[1]))
p = r["cleanupProof"]
assert r["verdict"] == "PASS" and r["compare"] == "PASS" and r["envRestore"] == "byte-identical" and r["deleteSent"] is True
assert p["syntheticEmailsLeft"] == 0 and p["principalState"] == "ERASED" and p["invitesCreatedInWindow"] == 1 and p["invitesUnconsumed"] == 0
assert 0 <= r["analyticsStoppedSeconds"] <= 600
PY
if grep -r -q -E 'PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN=[A-Za-z0-9_-]{40,}' "$W/r1.out" "$W/evidence" "$W/private" 2>/dev/null; then
  no "r1: a generated token reached a log, record or state"; else ok "r1: no generated token in any log, record or state"; fi
grep -q 'db-secret-xyz' "$W/r1.out" "$W/evidence" -r && no "r1: another env secret leaked" || ok "r1: no other env secret in output or evidence"

# R2, R3
fresh open False; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
python3 -I "$TOOL" preflight --harness "$W/harness" > "$W/r2.out" 2>&1; echo "exit=$?" >> "$W/r2.out"
has r2.out "PARKIO_REGISTRATION_MODE is open"; has r2.out "PREFLIGHT FAIL"
python3 -I "$TOOL" start --harness "$W/harness" > "$W/r2b.out" 2>&1; has r2b.out "NOT STARTED"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && [ -z "$(calls | grep -v '^systemctl list-units')" ] && ok "r2: nothing changed; only the read-only unit listing ran" || no "r2 changed something"
fresh closed False "PARKIO_ACCOUNT_ERASURE_ENABLED=false"
python3 -I "$TOOL" preflight --harness "$W/harness" > "$W/r3.out" 2>&1; has r3.out "more than once"; has r3.out "PREFLIGHT FAIL"

# R4
fresh closed False; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
window r4.out create_fail
has r4.out "STOP: Stop: creating the synthetic principal failed"; has r4.out "env file restore: byte-identical"
has r4.out "U05 PRIV-001A WINDOW FAIL"; has r4.out "exit=1"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && ok "r4: env byte-identical" || no "r4 env"
calls | grep -q 'stop analytics-service' && no "r4: analytics was stopped" || ok "r4: analytics never stopped"
[ "$(calls | grep -c '^up -d --no-deps auth-service$')" = 2 ] && ok "r4: auth-service recreated back" || no "r4 auth recreate count"

# R5
fresh closed False
window r5.out concurrent_edit
has r5.out "env file restore: targeted"
grep -q '^OTHER_CONCURRENT=1$' "$W/env" && ok "r5: the concurrent edit kept" || no "r5 concurrent edit lost"
python3 -I - "$W/env" <<'PY' && ok "r5: the window keys reverted to their W0 values" || no "r5 keys"
import sys
t = open(sys.argv[1]).read()
assert "PARKIO_ACCOUNT_ERASURE_ENABLED=false" in t and "PARKIO_REGISTRATION_MODE=closed" in t
assert "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED=false" in t and "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN" not in t
PY
has r5.out "DIFF the env file's SHA-256 differs from W0"

# R6
fresh closed False
window r6.out conflict_edit
has r6.out "CONFLICT: a window key was changed by someone else (PARKIO_ACCOUNT_ERASURE_ENABLED"
grep -q '^PARKIO_ACCOUNT_ERASURE_ENABLED=maybe$' "$W/env" && ok "r6: the file left as it is" || no "r6 file changed"
[ "$(calls | grep -c '^up -d --no-deps auth-service$')" = 1 ] && ok "r6: auth-service not recreated by the restore" || no "r6 auth recreated"
has r6.out "exit=1"

# R7
fresh closed False; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
window r7.out complete_early
has r7.out "criterion 3 (IN_PROGRESS while analytics is down) did not hold"; has r7.out "analytics-service started"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && ok "r7: env byte-identical" || no "r7 env"

# R8: the runner dies after stopping analytics
fresh closed False; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
python3 -I "$TOOL" start --harness "$W/harness" > /dev/null 2>&1
RUN="$(ls "$W/private" | tail -1)"
python3 -I - "$TOOL" "$RUN" > /dev/null 2>&1 <<'PY'
import importlib.util, os, sys, time
spec = importlib.util.spec_from_file_location("w", sys.argv[1]); w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)
st = w.load_state(sys.argv[2])
st["harness"] = st["harness"]; st["w1"] = w.now(); st["w0Values"] = {w.KEY_ERASURE: "false", w.KEY_MODE: "closed", w.KEY_CREATION: "false"}
st["w0TokenSet"] = False; w.save_state(st)
w.snapshot("W0", st)
w.apply_changes(st); w.recreate_auth({w.KEY_ERASURE: "true"}); st["phase"] = "W3"; st["analyticsStoppedAt"] = time.time(); w.save_state(st)
w.analytics("stop")
PY
python3 -I "$TOOL" restore --analytics-only --run "$RUN" > "$W/r8a.out" 2>&1; echo "exit=$?" >> "$W/r8a.out"
has r8a.out "analytics-service started"; has r8a.out "exit=0"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" != "$SHA0" ] && ok "r8: the analytics guard touched nothing else" || no "r8a changed env"
python3 -I "$TOOL" restore --guard --run "$RUN" > "$W/r8b.out" 2>&1; echo "exit=$?" >> "$W/r8b.out"
has r8b.out "guard: the window runner was stopped"; has r8b.out "env file restore: byte-identical"; has r8b.out "U05 PRIV-001A RESTORE PASS"
calls | grep -q "systemctl kill --signal=SIGKILL u05-priv001a-window-$RUN.service" && ok "r8: the guard killed the runner unit first" || no "r8 kill"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && ok "r8: env byte-identical after the guard" || no "r8 env"

# R9
fresh invite True; SHA0="$(sha256sum "$W/env" | cut -d' ' -f1)"
window r9.out ""
has r9.out "env keys changed: PARKIO_ACCOUNT_ERASURE_ENABLED, PARKIO_REGISTRATION_INVITE_CREATION_ENABLED ("
grep -E 'env keys changed:' "$W/r9.out" | grep -q 'PARKIO_REGISTRATION_MODE' && no "r9: the mode was changed" || ok "r9: the mode (already invite) was not changed"
has r9.out "U05 PRIV-001A WINDOW PASS"
[ "$(sha256sum "$W/env" | cut -d' ' -f1)" = "$SHA0" ] && ok "r9: env byte-identical; the preset token untouched" || no "r9 env"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
