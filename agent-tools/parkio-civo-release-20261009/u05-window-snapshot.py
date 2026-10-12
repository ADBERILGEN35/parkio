#!/usr/bin/env python3
"""U05 PRIV-001A live window: read-only snapshots and the unchanged-container proof (Asana 1219100261876615).

Run on parkio-civo-prod as civo, with sudo. It never changes anything:
  sudo python3 -I u05-window-snapshot.py snapshot W0      before the window (also the preflight)
  sudo python3 -I u05-window-snapshot.py snapshot W6      after the restore
  sudo python3 -I u05-window-snapshot.py compare          W0 against W6 (the latest of each), the rules below

snapshot records, for EVERY container on the host (docker ps -a + docker inspect):
- name, container id, image id, the com.docker.compose.{project,service,config-hash} labels;
- State.Status, State.StartedAt, RestartCount, health.
It also records:
- the production env file's SHA-256 and the four window keys. PARKIO_ACCOUNT_ERASURE_ENABLED,
  PARKIO_REGISTRATION_MODE and PARKIO_REGISTRATION_INVITE_CREATION_ENABLED are recorded by value; for
  PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN only "set" or "unset". A value is never shown or stored.
- the rendered config hash of every service (the production wrapper's `config --hash '*'`, read-only)
  against each running container's config-hash label.

compare (exit 0 PASS, 1 FAIL), project "parkio":
- every container except auth-service and analytics-service: id, image, config hash, StartedAt, RestartCount and
  status identical;
- auth-service: image and config hash equal to W0 (it is recreated twice, so its id and StartedAt may differ);
- analytics-service: id, image, config hash and RestartCount identical (it is stopped and started, so StartedAt may
  differ);
- the same container names; the same ids except auth-service's;
- W6: the env file's SHA-256 equals W0's, the window keys equal W0's, and every rendered hash equals the running one.
Records: ~civo/parkio-release-20261009/u05-window/<W0|W6>-<stamp>.json (dir 700, file 600).
"""
import hashlib, json, os, re, subprocess, sys, time

OUT = os.environ.get("U05W_OUT", "/home/civo/parkio-release-20261009/u05-window")
OWNER = os.environ.get("U05W_OWNER", "civo")
DOCKER = os.environ.get("U05W_DOCKER", "docker")
RELEASE = os.environ.get("U05W_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("U05W_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("U05W_PROJECT", "parkio")
TEST = bool(os.environ.get("U05W_TEST"))
KEYS_BY_VALUE = ("PARKIO_ACCOUNT_ERASURE_ENABLED", "PARKIO_REGISTRATION_MODE", "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED")
TOKEN_KEY = "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN"
ALLOWED = {"auth-service": ("image", "configHash", "status", "health"),
           "analytics-service": ("id", "image", "configHash", "restartCount", "status")}
STRICT = ("id", "image", "configHash", "startedAt", "restartCount", "status")


def stop(msg):
    print(f"STOP: {msg}")
    sys.exit(1)


def run(args, cwd=None, env=None):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=180, cwd=cwd, env=env)
        return p.returncode, p.stdout, p.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        return 127, "", str(e)


def containers():
    rc, out, err = run([DOCKER, "ps", "-a", "--format", "{{.ID}}"])
    if rc != 0:
        stop("docker ps failed")
    ids = [x for x in out.split() if x]
    if not ids:
        return []
    rc, out, err = run([DOCKER, "inspect", *ids])
    if rc != 0:
        stop("docker inspect failed")
    rows = []
    for c in json.loads(out):
        lab = (c.get("Config") or {}).get("Labels") or {}
        st = c.get("State") or {}
        rows.append({"name": c.get("Name", "").lstrip("/"), "id": c.get("Id"), "image": c.get("Image"),
                     "project": lab.get("com.docker.compose.project"), "service": lab.get("com.docker.compose.service"),
                     "configHash": lab.get("com.docker.compose.config-hash"), "status": st.get("Status"),
                     "startedAt": st.get("StartedAt"), "restartCount": c.get("RestartCount"),
                     "health": (st.get("Health") or {}).get("Status")})
    return sorted(rows, key=lambda r: r["name"])


def env_facts():
    try:
        data = open(ENV_FILE, "rb").read()
    except OSError:
        stop(f"cannot read the env file {ENV_FILE}")
    values = {}
    for line in data.decode("utf-8", "replace").splitlines():
        m = re.match(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$", line)
        if m and (m.group(1) in KEYS_BY_VALUE or m.group(1) == TOKEN_KEY):
            v = m.group(2).strip()
            if len(v) >= 2 and v[0] == v[-1] and v[0] in "'\"":
                v = v[1:-1]
            values[m.group(1)] = v
    keys = {k: values.get(k, "(absent)") for k in KEYS_BY_VALUE}
    keys[TOKEN_KEY] = "set" if values.get(TOKEN_KEY) else "unset"
    return {"sha256": hashlib.sha256(data).hexdigest(), "keys": keys}


def rendered():
    env = dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)
    rc, out, err = run(["./scripts/parkio-prod-compose.sh", "config", "--hash", "*"], cwd=RELEASE, env=env)
    if rc != 0:
        return None
    hashes = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            hashes[parts[0]] = parts[1]
    return hashes


def save(label, rec):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f"{label}-{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}.json")
    with open(path, "w") as f:
        json.dump(rec, f, indent=2, sort_keys=True)
    if not TEST:
        subprocess.run(["chown", "-R", f"{OWNER}:{OWNER}", OUT], check=False)
    os.chmod(OUT, 0o700)
    os.chmod(path, 0o600)
    return path


def latest(label):
    try:
        files = sorted(f for f in os.listdir(OUT) if re.match(rf"^{label}-\d{{8}}T\d{{6}}Z\.json$", f))
    except OSError:
        files = []
    if not files:
        stop(f"no {label} snapshot under {OUT}")
    return os.path.join(OUT, files[-1])


def cmd_snapshot(label):
    if label not in ("W0", "W6"):
        stop("snapshot label must be W0 or W6")
    rows = containers()
    env = env_facts()
    hashes = rendered()
    drift = None
    if hashes is not None:
        drift = sorted(r["service"] for r in rows if r["project"] == PROJECT and r["service"] in hashes
                       and r["status"] == "running" and r["configHash"] != hashes[r["service"]])
    rec = {"tool": "u05-window-snapshot", "label": label, "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "containers": rows, "envFile": {"path": ENV_FILE, **env}, "rendered": hashes, "renderDrift": drift}
    path = save(label, rec)
    print(f"U05 window snapshot {label} | {rec['at']} | read-only")
    print(f"  containers: {len(rows)} ({sum(1 for r in rows if r['status'] == 'running')} running)")
    print(f"  env file sha256 {env['sha256'][:16]}…; " + ", ".join(f"{k}={v}" for k, v in env["keys"].items()))
    if hashes is None:
        print("  rendered config: UNAVAILABLE (the production wrapper's config --hash failed)")
    else:
        print(f"  rendered config: {len(hashes)} services; running containers that differ from it: {drift or 'none'}")
    print(f"record: {path} sha256 {hashlib.sha256(open(path, 'rb').read()).hexdigest()}")
    return 0


def cmd_compare():
    a, b = (json.load(open(latest(x))) for x in ("W0", "W6"))
    problems = []
    w0 = {r["name"]: r for r in a["containers"]}
    w6 = {r["name"]: r for r in b["containers"]}
    if set(w0) != set(w6):
        problems.append(f"container set changed: added {sorted(set(w6) - set(w0))}, removed {sorted(set(w0) - set(w6))}")
    for name in sorted(set(w0) & set(w6)):
        x, y = w0[name], w6[name]
        svc = x["service"] if x["project"] == PROJECT else None
        keys = ALLOWED.get(svc, STRICT)
        diff = [k for k in keys if x.get(k) != y.get(k)]
        if diff:
            problems.append(f"{name}: " + ", ".join(f"{k} {str(x.get(k))[:19]} -> {str(y.get(k))[:19]}" for k in diff))
    if a["envFile"]["sha256"] != b["envFile"]["sha256"]:
        problems.append("the env file's SHA-256 differs from W0")
    if a["envFile"]["keys"] != b["envFile"]["keys"]:
        problems.append(f"window keys differ from W0: {b['envFile']['keys']}")
    if b.get("renderDrift"):
        problems.append(f"running config differs from the rendered config: {b['renderDrift']}")
    if b.get("rendered") is None:
        problems.append("W6 has no rendered config to compare")
    auth = [n for n in w6 if w6[n]["project"] == PROJECT and w6[n]["service"] == "auth-service"]
    if auth and w6[auth[0]].get("health") not in ("healthy", None):
        problems.append(f"auth-service health {w6[auth[0]].get('health')}")
    print(f"U05 window compare | W0 {a['at']} -> W6 {b['at']} | {len(w0)} containers")
    for p in problems:
        print(f"  DIFF {p}")
    same = len([n for n in set(w0) & set(w6) if w0[n]["service"] not in ALLOWED or w0[n]["project"] != PROJECT])
    if problems:
        print("U05 WINDOW COMPARE FAIL")
        return 1
    print(f"  {same} other containers identical (id, image, config hash, StartedAt, RestartCount, status); "
          "auth-service same image and config hash; analytics-service same id, image and config hash")
    print("  env file byte-identical to W0; window keys equal W0; every running config equals the rendered config")
    print("U05 WINDOW COMPARE PASS")
    return 0


def main():
    if not TEST and os.geteuid() != 0:
        stop("run as root (sudo python3 -I ...)")
    args = sys.argv[1:]
    if args[:1] == ["snapshot"] and len(args) == 2:
        return cmd_snapshot(args[1])
    if args == ["compare"]:
        return cmd_compare()
    print("usage: u05-window-snapshot.py snapshot W0|W6 | compare")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:   # no traceback: it could quote a value
        stop(f"unexpected {type(e).__name__}")
