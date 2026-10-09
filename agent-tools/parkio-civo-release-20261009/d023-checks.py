#!/usr/bin/env python3
"""D0.2.3 - read-only, value-free pre-deployment checks for the scoped release on parkio-civo-prod.

Run as civo on the host (Python 3.8+):
  python3 -I /tmp/d023-checks.py

Release 22f96990 in /opt/parkio-release; live checkout /opt/parkio (dirty, untouched); project parkio.
Scope: the 14 services of the scoped plan. It changes nothing. It runs docker ps/inspect/image
inspect, docker compose config (render only), read-only SELECTs on flyway_schema_history through
docker exec, git status/rev-parse without optional locks, stat, getent and systemctl is-active/show.
It writes its report into ~/parkio-release-20261009 only. It prints no environment value, file content,
rendered configuration or secret; files are identified by their git blob ids.

A. Configuration files bound by the recreated caddy, prometheus and alertmanager: live vs release.
B. Waitlist ops inbox (gateway): live state, prerequisites, and the release model with the overlay.
C. Flyway: per database, the versions applied today vs the scripts the release carries.
D. Images: the six host-built services (tag collisions, rollback tags) and the five pins (release and
   rollback digests present locally).
E. Deploy records: current.json in the live checkout and the per-host deployed-manifest.json files.
F. New Relic log pilot (project parkio-nr-log-continuous): unit states and source status, read-only.
G. Rollback reproducibility: which recorded inputs recreate each of the 14 containers exactly.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
LIVE = os.environ.get("D023_LIVE", "/opt/parkio")
REL = os.environ.get("D023_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("D023_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("D023_PROJECT", "parkio")
OUT = os.environ.get("D023_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
PG_PREFIX = os.environ.get("D023_PG_PREFIX", "parkio-postgres-")
EXPLICIT = ["caddy", "prometheus", "alertmanager", "gateway-service", "web", "auth-service",
            "media-service", "parking-service"]
SIX = ["user-service", "gamification-service", "notification-service", "moderation-service",
       "ai-validation-service", "analytics-service"]
CONFIG_SERVICES = ["caddy", "prometheus", "alertmanager"]
# Flyway scripts at the release revision: V1..Vn, contiguous, per service.
RELEASE_MIGRATIONS = {"gateway": 6, "auth": 27, "media": 18, "parking": 42, "user": 20, "gamification": 15,
                      "notification": 15, "moderation": 14, "ai-validation": 11, "analytics": 11}
# Previous live pins, from the Rollback comments of the release's pin files.
ROLLBACK_PINS = {
    "gateway-service": "ghcr.io/adberilgen35/parkio/gateway-service@sha256:866a7fe09ccc5fd2148db893fc8abb013a2c2e8ac9567f85a4eb6c7c11f9bb48",
    "media-service": "ghcr.io/adberilgen35/parkio/media-service@sha256:62f49d04087fb7aa475db0a42c7f8d1e7d1511f84e6b496a58c5beda58f864ec",
    "parking-service": "ghcr.io/adberilgen35/parkio/parking-service@sha256:85da26542fe6729b72183a3593df42f4f51482ffeb7a0e930a48b64e3b027fb2",
    "auth-service": "ghcr.io/adberilgen35/parkio/auth-service@sha256:a4410a45634b73246e13c3054dc3b1d0c8069baa7c0738258ecf9774ca72514e",
    "web": "ghcr.io/adberilgen35/parkio/web@sha256:aacf9dc9ab8ef412dee01429da2b2bbc33099c4560a7904f181f9fe6db381f6e",
}
WAITLIST_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
WAITLIST_DIR = "/var/lib/parkio/waitlist-ops-inbox"
WAITLIST_GROUP = "parkio-waitlist-inbox"
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
NR_UNITS = ["parkio-nr-log-source.service", "parkio-nr-log-continuous.service",
            "parkio-nr-log-continuous-guard.timer", "parkio-nr-log-continuous-guard.service"]
NR_STATE = "/var/lib/parkio-nr-log-continuous/source/state"
NR_SERVICES = ["gateway-service", "auth-service", "parking-service"]
KNOWN_PROFILES = ("hosted-beta", "azure-hosted-beta", "invite-production")

REPORT = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def say(line=""):
    print(line)
    REPORT.append(line)


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"D023-checks-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    save()
    sys.exit(1)


def run(cmd, env=None, cwd=None):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=cwd)
    except FileNotFoundError:
        return 127, "", ""
    return p.returncode, p.stdout, p.stderr


def unset_names(err):
    return sorted(set(re.findall(r'The "([A-Za-z_][A-Za-z0-9_]*)" variable is not set', err)))


def required_missing(err):
    return sorted(set(re.findall(r"required variable ([A-Za-z_][A-Za-z0-9_]*)", err)))


def blob(path):
    if not path or not os.path.exists(path):
        return "missing"
    if os.path.isdir(path):
        return "directory"
    with open(path, "rb") as fh:
        data = fh.read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def short(value, n=12):
    return value[:n] if value and re.match(r"^[0-9a-f]{12,}$", value) else value


def env_lines(key):
    count = 0
    value = None
    with open(ENV_FILE, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith(key + "="):
                count += 1
                value = line.rstrip("\n").split("=", 1)[1].strip().strip("'\"")
    return count, value


def label(c, key):
    return ((c.get("Config") or {}).get("Labels") or {}).get(key, "")


def compose(files, *args, env_file=ENV_FILE, project_dir=None, extra_env=None):
    cmd = ["docker", "compose", "--env-file", env_file]
    if project_dir:
        cmd += ["--project-directory", project_dir]
    for f in files:
        cmd += ["-f", f]
    env = dict(os.environ)
    env.update(extra_env or {})
    return run(cmd + list(args), env=env)


def render(files, **kw):
    rc, out, err = compose(files, "config", "--format", "json", **kw)
    if rc != 0:
        return None, f"render failed (exit {rc}); unset {unset_names(err)}; required {required_missing(err)}"
    return json.loads(out), None


def hashes(files, services, **kw):
    rc, out, err = compose(files, "config", "--hash", ",".join(services), **kw)
    if rc != 0:
        return None, f"config --hash failed (exit {rc}); unset {unset_names(err)}; required {required_missing(err)}"
    result = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            result[parts[0]] = parts[1]
    return result, None


def image_info(ref):
    rc, out, _ = run(["docker", "image", "inspect", ref])
    if rc != 0 or not out.strip().startswith("["):
        return None
    return json.loads(out)[0]


def git(*args, repo=LIVE):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out.strip()


def file_state(path):
    """unmodified / MODIFIED / untracked / outside, for a file of the live checkout."""
    if not path or not path.startswith(LIVE + "/"):
        return "outside the live checkout", "-"
    rel = path[len(LIVE) + 1:]
    rc, out = git("status", "--porcelain", "--", rel)
    state = "unmodified" if rc == 0 and not out else ("untracked" if out.startswith("??") else "MODIFIED")
    rc, head = git("rev-parse", f"HEAD:{rel}")
    return state, (head if rc == 0 else "-")


def ranges(versions):
    """['4', '5', '6', '9'] -> 'V4-V6, V9' (integer versions; others listed as they are)."""
    ints = sorted(int(v) for v in versions if v.isdigit())
    other = sorted(v for v in versions if not v.isdigit())
    parts, start, prev = [], None, None
    for v in ints:
        if start is None:
            start = prev = v
        elif v == prev + 1:
            prev = v
        else:
            parts.append(f"V{start}" if start == prev else f"V{start}-V{prev}")
            start = prev = v
    if start is not None:
        parts.append(f"V{start}" if start == prev else f"V{start}-V{prev}")
    return ", ".join(parts + ["V" + v for v in other]) or "none"


def model_binds(service):
    return {v["target"]: v.get("source") for v in service.get("volumes") or []
            if isinstance(v, dict) and v.get("type") == "bind"}


def live_binds(c):
    return {m["Destination"]: m.get("Source") for m in c.get("Mounts") or [] if m.get("Type") == "bind"}


def env_names(c):
    return {e.split("=", 1)[0] for e in (c.get("Config") or {}).get("Env") or []}


def main():
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if not os.path.isfile(ENV_FILE):
        stop("missing env file")
    rc, head = git("rev-parse", "HEAD", repo=REL)
    if head != PIN and not os.environ.get("D023_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    say(f"D0.2.3 checks | stamp {STAMP} | release {REL} @ {head[:12]} | live {LIVE} | project {PROJECT}")
    canonical = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    civo = [os.path.join(REL, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        civo.append(os.path.join(REL, CIVO_OVERLAY))
    model, err = render(civo)
    if err:
        stop(f"release model: {err}")
    services = model.get("services") or {}
    rc, out, _ = run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={PROJECT}"])
    if not out.split():
        stop(f"no container of project {PROJECT}")
    rc, out, _ = run(["docker", "inspect"] + out.split())
    live = {}
    for c in json.loads(out):
        live.setdefault(label(c, "com.docker.compose.service"), c)

    # A
    say("")
    say("A. Configuration files bound by caddy, prometheus and alertmanager (git blob ids; live state vs live HEAD)")
    differing = 0
    for svc in CONFIG_SERVICES:
        c, s = live.get(svc), services.get(svc)
        if s is None:
            say(f"  {svc}: not in the release model")
            continue
        lb, mb = (live_binds(c) if c else {}), model_binds(s)
        for tgt in sorted(set(lb) | set(mb)):
            lsrc, msrc = lb.get(tgt), mb.get(tgt)
            lblob, mblob = blob(lsrc), blob(msrc)
            state, headblob = file_state(lsrc)
            rel = msrc[len(REL) + 1:] if msrc and msrc.startswith(REL + "/") else (msrc or "-")
            same = lblob == mblob
            differing += 0 if same else 1
            say(f"  {svc} {rel}: live {short(lblob)} ({state}; HEAD {short(headblob)}) | release {short(mblob)}"
                f" | {'same' if same else 'DIFFERENT'}")
    say(f"  files that differ: {differing}")

    # B
    say("")
    say("B. Waitlist ops inbox (gateway-service)")
    gw = live.get("gateway-service")
    if gw:
        names = sorted(n for n in env_names(gw) if "WAITLIST" in n)
        say(f"  live gateway env keys with WAITLIST: {names}")
        wb = {t: s for t, s in live_binds(gw).items() if "waitlist" in t}
        say(f"  live gateway waitlist binds: {sorted(wb.items()) or 'none'}")
        groups = [str(g) for g in (gw.get("HostConfig") or {}).get("GroupAdd") or []]
        say(f"  live gateway group_add: {groups or 'none'}")
        say(f"  live gateway compose files: {[os.path.basename(f) for f in label(gw, 'com.docker.compose.project.config_files').split(',') if f]}")
    count, gid_value = env_lines("PARKIO_WAITLIST_OPS_INBOX_GID")
    enabled_count, _ = env_lines("PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED")
    rc, getent, _ = run(["getent", "group", WAITLIST_GROUP])
    host_gid = getent.split(":")[2] if rc == 0 and getent.count(":") >= 2 else None
    say(f"  env file: PARKIO_WAITLIST_OPS_INBOX_GID lines {count}; PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED lines {enabled_count}")
    say(f"  host group {WAITLIST_GROUP}: {'gid ' + host_gid if host_gid else 'absent'}"
        f"; env GID equals it: {'yes' if host_gid and gid_value == host_gid else 'no'}"
        f"; live group_add equals it: {'yes' if gw and host_gid and host_gid in [str(g) for g in (gw.get('HostConfig') or {}).get('GroupAdd') or []] else 'no'}")
    if os.path.isdir(WAITLIST_DIR):
        rc, st, _ = run(["stat", "-c", "%U:%G %a", WAITLIST_DIR])
        say(f"  host dir {WAITLIST_DIR}: {st.strip()}")
    else:
        say(f"  host dir {WAITLIST_DIR}: absent")
    lo, ro = os.path.join(LIVE, WAITLIST_OVERLAY), os.path.join(REL, WAITLIST_OVERLAY)
    state, headblob = file_state(lo)
    say(f"  overlay: live {short(blob(lo))} ({state}) | release {short(blob(ro))} | {'same' if blob(lo) == blob(ro) else 'DIFFERENT'}")
    rc, out, _ = run(["systemctl", "is-active", "parkio-slack-biz-worker.service"])
    say(f"  relay unit parkio-slack-biz-worker.service: {out.strip() or 'unknown'}")
    if gw:
        wmodel, err = render(civo + [ro])
        if err:
            say(f"  release model + overlay: {err}")
        else:
            s = (wmodel.get("services") or {}).get("gateway-service") or {}
            menv = set((s.get("environment") or {}).keys())
            lenv = env_names(gw)
            img_env = {}
            for item in ((image_info(gw.get("Image")) or {}).get("Config") or {}).get("Env") or []:
                k, _, v = item.partition("=")
                img_env[k] = v
            live_env = {}
            for item in (gw.get("Config") or {}).get("Env") or []:
                k, _, v = item.partition("=")
                live_env[k] = v
            added = sorted(n for n in menv - lenv)
            removed = sorted(n for n in lenv - menv if img_env.get(n) != live_env.get(n))
            mgroups = [str(g) for g in s.get("group_add") or []]
            say(f"  release model + overlay vs live gateway: env keys added {added}; env keys only live {removed}")
            say(f"    waitlist bind kept: {'yes' if WAITLIST_DIR in model_binds(s) else 'NO'}; group_add equal: "
                f"{'yes' if sorted(mgroups) == sorted(str(g) for g in (gw.get('HostConfig') or {}).get('GroupAdd') or []) else 'NO'}")
            plain = services.get("gateway-service") or {}
            say(f"    without the overlay: waitlist bind {'kept' if WAITLIST_DIR in model_binds(plain) else 'REMOVED'}; "
                f"export-dir key {'kept' if 'PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR' in (plain.get('environment') or {}) else 'REMOVED'}; "
                f"group_add {'kept' if plain.get('group_add') else 'REMOVED'}")

    # C
    say("")
    say("C. Flyway (read-only SELECT on flyway_schema_history): applied today vs release scripts V1..Vn")
    to_apply = {}
    q1 = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "select version from flyway_schema_history where success and version is not null order by installed_rank"'
    q2 = 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "select count(*) from flyway_schema_history where not success"'
    for db, n in RELEASE_MIGRATIONS.items():
        cname = PG_PREFIX + db
        rc, out, _ = run(["docker", "exec", cname, "sh", "-c", q1])
        if rc != 0:
            say(f"  {db}: query failed (exit {rc}) on {cname}")
            continue
        applied = {v.strip() for v in out.split() if v.strip()}
        rc2, failed, _ = run(["docker", "exec", cname, "sh", "-c", q2])
        release = {str(i) for i in range(1, n + 1)}
        new = sorted(release - applied, key=lambda v: [int(x) for x in v.split(".")])
        ahead = sorted(applied - release)
        to_apply[db] = new
        top = max(applied, key=lambda v: [int(x) for x in v.split(".")]) if applied else "none"
        say(f"  {db}: applied {len(applied)} (max V{top}); release V1..V{n}; to apply {ranges(new)}"
            f"; applied but absent from the release {ahead or 'none'}; failed rows {failed.strip() if rc2 == 0 else '?'}")

    # D
    say("")
    say("D. Images")
    pretag = []
    for svc in SIX:
        c, s = live.get(svc), services.get(svc) or {}
        if not c:
            say(f"  {svc}: no container")
            continue
        running_id, running_ref = c.get("Image"), (c.get("Config") or {}).get("Image")
        info = image_info(running_id) or {}
        tags = info.get("RepoTags") or []
        name = s.get("image") or f"{PROJECT}-{svc}"
        name_info = image_info(name)
        collides = bool(name_info) and name_info.get("Id") == running_id
        if collides:
            pretag.append(svc)
        revision = ((info.get("Config") or {}).get("Labels") or {}).get("org.opencontainers.image.revision") or "none"
        say(f"  {svc}: running ref {running_ref} (OCI revision {revision[:12]}); tags on the running image {tags}; release builds {name}"
            f"{' (NOW the running image: the build moves this tag, pre-tag needed)' if collides else ''}")
    for svc in ("gateway-service", "media-service", "parking-service", "auth-service", "web"):
        c, s = live.get(svc), services.get(svc) or {}
        rel_ref, rb_ref = s.get("image"), ROLLBACK_PINS[svc]
        rel_info, rb_info = image_info(rel_ref) if rel_ref else None, image_info(rb_ref)
        running_id = c.get("Image") if c else None
        say(f"  {svc}: release digest present locally {'yes' if rel_info else 'no (pulled in its step)'}; rollback digest present"
            f" {'yes' if rb_info else 'NO'}; running image is the rollback digest {'yes' if rb_info and rb_info.get('Id') == running_id else 'no'}")

    # E
    say("")
    say("E. Deploy records")
    count, profile = env_lines("PARKIO_DEPLOYMENT_PROFILE")
    say(f"  env file PARKIO_DEPLOYMENT_PROFILE: {profile if profile in KNOWN_PROFILES else ('absent' if not count else '(unrecognised value)')}")
    for path in (os.path.join(LIVE, "deploy-artifacts", "current.json"), "/var/lib/parkio/hosted-beta/deployed-manifest.json",
                 "/var/lib/parkio/azure-hosted-beta/deployed-manifest.json"):
        if not os.path.exists(path):
            say(f"  {path}: absent")
            continue
        try:
            m = json.load(open(path, encoding="utf-8"))
        except (OSError, ValueError):
            say(f"  {path}: present, unreadable as JSON or not readable by {os.environ.get('USER', 'this user')}")
            continue
        files = m.get("composeFiles")
        if isinstance(files, list):
            files = [os.path.basename(str(f)) for f in files]
        elif isinstance(files, str):
            files = [os.path.basename(f) for f in files.split() if f != "-f"]
        prof = m.get("deploymentProfile")
        say(f"  {path}: action {m.get('action')}; gitSha {str(m.get('gitSha'))[:12]}; profile "
            f"{prof if prof in KNOWN_PROFILES else ('(other)' if prof else 'absent')}; buildTime {m.get('buildTime')}"
            f"; migrationVersions {'present' if m.get('migrationVersions') else 'absent'}"
            f"; pinnedImages {'present' if m.get('pinnedImages') else 'absent'}; composeFiles {files or 'absent'}")
    for d in ("/var/lib/parkio/hosted-beta", "/var/lib/parkio/azure-hosted-beta"):
        say(f"  {d}: {'writable by this user' if os.path.isdir(d) and os.access(d, os.W_OK) else ('exists, not writable' if os.path.isdir(d) else 'absent')}")

    # F
    say("")
    say("F. New Relic log pilot (read-only)")
    for unit in NR_UNITS:
        rc, out, _ = run(["systemctl", "is-active", unit])
        say(f"  {unit}: {out.strip() or 'unknown'}")
    rc, out, _ = run(["systemctl", "show", "parkio-nr-log-continuous-guard.timer", "-p", "LastTriggerUSec", "-p", "NextElapseUSecRealtime"])
    say(f"  guard timer: {' '.join(out.split()) or 'unknown'}")
    rc, out, _ = run(["systemctl", "show", "parkio-nr-log-continuous-guard.service", "-p", "Result", "-p", "ExecMainStatus"])
    say(f"  guard last run: {' '.join(out.split()) or 'unknown'}")
    for svc in NR_SERVICES:
        path = os.path.join(NR_STATE, f"{svc}.status.json")
        if not os.access(path, os.R_OK):
            say(f"  helper status {svc}: not readable by this user (root-owned)")
            continue
        try:
            st = json.load(open(path, encoding="utf-8"))
            cur = (live.get(svc) or {}).get("Id")
            say(f"  helper status {svc}: {st.get('status')}; attached to the running container: {'yes' if st.get('container_id') == cur else 'no'}")
        except (OSError, ValueError):
            say(f"  helper status {svc}: unreadable")
    rc, out, _ = run(["docker", "ps", "-a", "--filter", "label=com.docker.compose.project=parkio-nr-log-continuous",
                      "--format", '{{.Label "com.docker.compose.service"}} {{.State}}'])
    say(f"  project parkio-nr-log-continuous containers: {out.split(chr(10)) if out.strip() else 'none'}")

    # G
    say("")
    say("G. Rollback reproducibility (config hash of the previous container from recorded inputs)")
    wrapper = os.path.join(LIVE, "scripts", "parkio-prod-compose.sh")
    wenv = dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)
    scope = EXPLICIT + SIX
    have = [s for s in scope if s in live]
    rc, out, err = run([wrapper, "config", "--hash", ",".join(have)], env=wenv, cwd=LIVE)
    old = {}
    if rc == 0:
        for line in out.splitlines():
            parts = line.split()
            if len(parts) == 2:
                old[parts[0]] = parts[1]
    else:
        say(f"  live checkout wrapper: config --hash failed (exit {rc}); unset {unset_names(err)}")
    rc, out, err = run([wrapper, "-f", WAITLIST_OVERLAY, "config", "--hash", "gateway-service"], env=wenv, cwd=LIVE)
    old_gw = out.split()[1] if rc == 0 and len(out.split()) == 2 else None
    for svc in scope:
        c = live.get(svc)
        if not c:
            say(f"  {svc}: no container")
            continue
        want = label(c, "com.docker.compose.config-hash")
        files = [f for f in label(c, "com.docker.compose.project.config_files").split(",") if f]
        names = [os.path.basename(f) for f in files]
        via = []
        if old.get(svc) == want:
            via.append("live wrapper")
        if svc == "gateway-service" and old_gw == want:
            via.append("live wrapper + waitlist overlay")
        missing = [f for f in files if not os.path.isfile(f)]
        extra = {}
        if missing and len(missing) == 1 and os.path.basename(missing[0]) == "web-binding.yml":
            os.makedirs(OUT, mode=0o700, exist_ok=True)
            rebuilt = os.path.join(OUT, f"D023-web-binding-rebuilt-{STAMP}.yml")
            fd = os.open(rebuilt, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump({"services": {"web": {"image": (c.get("Config") or {}).get("Image"), "pull_policy": "never"}}}, fh)
            files = [rebuilt if f == missing[0] else f for f in files]
            missing = []
        ref = (c.get("Config") or {}).get("Image") or ""
        m = re.match(r"^parkio/[a-z-]+:(sha-[0-9a-f]+)$", ref)
        if m and "docker-compose.images.yml" in names:
            extra["PARKIO_IMAGE_TAG"] = m.group(1)
        if not missing:
            envf = label(c, "com.docker.compose.project.environment_file")
            envf = envf if envf and "," not in envf and os.path.isfile(envf) else ENV_FILE
            h, err = hashes(files, [svc], env_file=envf, project_dir=label(c, "com.docker.compose.project.working_dir") or None,
                            extra_env=extra)
            if h and h.get(svc) == want:
                via.append("recorded file list" + (" (+ rebuilt web binding)" if any(f.startswith(OUT) for f in files) else "")
                           + (f" (+ PARKIO_IMAGE_TAG from its image tag)" if extra else ""))
        say(f"  {svc}: {'reproduced by ' + ', '.join(via) if via else 'NOT reproduced'} | recorded files {names}")

    # Summary
    say("")
    say("SUMMARY")
    say(f"config files differing (A): {differing}")
    say(f"databases with migrations to apply (C): {'; '.join(k + ' ' + ranges(v) for k, v in to_apply.items() if v) or 'none'}")
    say(f"host-built images needing a rollback tag before D7 (D): {pretag or 'none'}")
    say("D0.2.3 COMPLETE (read-only)")
    save()


if __name__ == "__main__":
    main()
