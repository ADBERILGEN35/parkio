#!/usr/bin/env python3
"""D0.2.2 - read-only, value-free release scope for parkio-civo-prod (release 22f96990).

Run as civo on the host (Python 3.8+):
  python3 -I /tmp/release-scope.py --release /opt/parkio-release \\
      --env-file /opt/parkio/docker/.env.azure-hosted-beta

Lists, before any container is touched, which containers of the Compose project `parkio` each
deployment path recreates. It applies Compose's own rule: a container is recreated when the service's
config hash (`docker compose config --hash`) differs from its com.docker.compose.config-hash label, or
when the service's image now resolves to another local image than its com.docker.compose.image label.

1. Self-check per live file list: re-renders every distinct Compose file list the running containers
   record (working_dir, config_files and environment_file labels) and checks that this Compose
   reproduces their labels. A temporary web binding override that no longer exists is rebuilt from the
   web container's own image reference (the binding sets only image and pull_policy).
2. Release model: the production wrapper's model from the release checkout
   (docker/compose.production.files plus the Civo Alertmanager overlay), which D2-D7 use.
3. Per service: the explicit steps (D2-D6, --no-deps --force-recreate), the six host-built services
   (D7: build from the release checkout, then up --no-deps), and every other service whose running
   container differs from the release model (drift), with the names of the differing fields.
4. Whether scripts/deploy-hosted-beta.sh can run this release on this host.
5. Other Compose projects on the host. No step touches them.

Runs only docker ps/inspect/image inspect/version/info and docker compose version/config.
Writes only its report, and a rebuilt web binding when one is needed, into --out (outside both
checkouts). Never prints environment values, rendered configuration or hashes.
"""
import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

EXPLICIT = (
    ("D2", ("caddy", "prometheus", "alertmanager")),
    ("D3", ("gateway-service",)),
    ("D4", ("web",)),
    ("D5", ("auth-service",)),
    ("D6", ("media-service", "parking-service")),
)
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
# scripts/lib/deploy-common.sh PARKIO_PRODUCTION_HOSTNAMES at the release revision.
PRODUCTION_HOSTNAMES = ("api.parkio.dev", "app.parkio.dev", "media.parkio.dev")
KNOWN_PROFILES = ("hosted-beta", "azure-hosted-beta", "invite-production")
# scripts/newrelic_log_pilot: Compose project of the log pilot and the parkio services its source
# helper pins by container id (resolve_production_sources.sh at the release revision).
NR_PROJECT = "parkio-nr-log-continuous"
NR_SOURCE_SERVICES = ("gateway-service", "auth-service", "parking-service")
STATEFUL = {"kafka": "BROKER", "redis": "CACHE", "minio": "OBJECT STORE"}
UNIT_NS = {"ns": 1, "us": 1e3, "ms": 1e6, "s": 1e9, "m": 60e9, "h": 3600e9}

REPORT = []
STATE = {"out": None, "stamp": None}


def say(line=""):
    print(line)
    REPORT.append(line)


def save():
    if not STATE["out"]:
        return
    path = os.path.join(STATE["out"], f"D022-release-scope-{STATE['stamp']}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    save()
    sys.exit(1)


def run(cmd, env=None):
    p = subprocess.run(cmd, capture_output=True, text=True, env=env)
    return p.returncode, p.stdout, p.stderr


def unset_names(err):
    return sorted(set(re.findall(r'The "([A-Za-z_][A-Za-z0-9_]*)" variable is not set', err)))


def compose(files, env_file, project_dir, *args):
    cmd = ["docker", "compose", "--env-file", env_file]
    if project_dir:
        cmd += ["--project-directory", project_dir]
    for f in files:
        cmd += ["-f", f]
    return run(cmd + list(args))


def render(files, env_file, project_dir=None):
    """Model (memory only) and per-service config hashes. stderr is never echoed: it may quote values."""
    rc, out, err = compose(files, env_file, project_dir, "config", "--format", "json")
    if rc != 0:
        return None, None, f"compose config failed (exit {rc}); unset variables {unset_names(err)}; stderr withheld"
    model = json.loads(out)
    services = sorted((model.get("services") or {}).keys())
    rc, out, err = compose(files, env_file, project_dir, "config", "--hash", ",".join(services))
    if rc != 0:
        return None, None, f"compose config --hash failed (exit {rc}); stderr withheld"
    hashes = {}
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            hashes[parts[0]] = parts[1]
    if set(hashes) != set(services):
        return None, None, "compose config --hash did not return one hash per service"
    return model, hashes, None


def kind(svc):
    if svc.startswith("postgres-"):
        return "DATABASE"
    return STATEFUL.get(svc, "")


def dur_ns(value):
    if value is None:
        return 0
    if isinstance(value, (int, float)):
        return int(value)
    return int(sum(float(n) * UNIT_NS[u] for n, u in re.findall(r"(\d+(?:\.\d+)?)(ns|us|ms|s|m|h)", str(value))))


def env_dict(items):
    out = {}
    for item in items or []:
        k, _, v = item.partition("=")
        out[k] = v
    return out


def caps(values):
    out = set()
    for v in values or []:
        v = (v or "").upper()
        out.add(v[4:] if v.startswith("CAP_") else v)
    return sorted(out)


def port_key(host_ip, published, target, proto):
    hip = host_ip or "*"
    if hip in ("0.0.0.0", "::"):
        hip = "*"
    return f"{hip}:{published or ''}->{target}/{proto or 'tcp'}"


def to_int(value):
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return None


def host_of(value):
    text = str(value or "").strip().lower()
    text = re.sub(r"^[a-z][a-z0-9+.-]*://", "", text)
    text = re.split(r"[/?#]", text, 1)[0]
    text = text.rsplit("@", 1)[-1]
    text = re.sub(r":\d*$", "", text)
    return text.rstrip(".")


class Images:
    def __init__(self):
        self.cache = {}

    def inspect(self, ref):
        if ref not in self.cache:
            rc, out, _ = run(["docker", "image", "inspect", ref])
            self.cache[ref] = json.loads(out)[0] if rc == 0 and out.strip().startswith("[") else None
        return self.cache[ref]

    def ids(self, ref, platform):
        """Every id Compose may have recorded for ref: the plain id (classic store) and the
        platform-specific id (containerd store, where `docker image inspect` returns the index digest)."""
        out = set()
        base = self.inspect(ref)
        if base and base.get("Id"):
            out.add(base["Id"])
        rc, o, _ = run(["docker", "image", "inspect", "--platform", platform, "--format", "{{.Id}}", ref])
        if rc == 0 and o.strip().startswith("sha256:"):
            out.add(o.strip())
        return out


def field_diff(c, img, s, model):
    """Names of the differences between running container c (docker inspect) and model service s."""
    cfg, hc = c.get("Config") or {}, c.get("HostConfig") or {}
    icfg = (img or {}).get("Config") or {}
    notes = []
    ref = s.get("image")
    if ref and ref != cfg.get("Image"):
        notes.append(f"image {str(cfg.get('Image')).split('/')[-1][:44]} -> {ref.split('/')[-1][:44]}")
    live_env, img_env = env_dict(cfg.get("Env")), env_dict(icfg.get("Env"))
    model_env = {k: str(v) for k, v in (s.get("environment") or {}).items() if v is not None}
    added = sorted(k for k in model_env if k not in live_env)
    changed = sorted(k for k in model_env if k in live_env and live_env[k] != model_env[k])
    removed = sorted(k for k, v in live_env.items() if k not in model_env and img_env.get(k) != v)
    if added:
        notes.append(f"env keys added {added}")
    if removed:
        notes.append(f"env keys removed {removed}")
    if changed:
        notes.append(f"env values changed {changed}")
    mounts = c.get("Mounts") or []
    live_binds = {m["Destination"]: (m.get("Source"), bool(m.get("RW", True))) for m in mounts if m.get("Type") == "bind"}
    model_binds = {v["target"]: (v.get("source"), not v.get("read_only", False))
                   for v in s.get("volumes") or [] if isinstance(v, dict) and v.get("type") == "bind"}
    moved = []
    for tgt in sorted(set(live_binds) | set(model_binds)):
        a, b = live_binds.get(tgt), model_binds.get(tgt)
        if a is None:
            notes.append(f"bind added {tgt}")
        elif b is None:
            notes.append(f"bind removed {tgt}")
        elif a[0] != b[0]:
            moved.append(tgt)
        elif a[1] != b[1]:
            notes.append(f"bind {tgt}: {'rw' if a[1] else 'ro'} -> {'rw' if b[1] else 'ro'}")
    if moved:
        notes.append(f"bind sources moved to the release checkout: {len(moved)} ({', '.join(moved[:3])}{', ...' if len(moved) > 3 else ''})")
    top_vols = model.get("volumes") or {}
    live_vols = {m["Destination"]: m.get("Name") for m in mounts if m.get("Type") == "volume"}
    model_vols = {v["target"]: (top_vols.get(v["source"]) or {}).get("name", v["source"])
                  for v in s.get("volumes") or [] if isinstance(v, dict) and v.get("type") == "volume" and v.get("source")}
    for tgt in sorted(model_vols):
        if tgt not in live_vols:
            notes.append(f"named volume added {tgt}")
        elif live_vols[tgt] != model_vols[tgt]:
            notes.append(f"NAMED VOLUME CHANGED {tgt}: {live_vols[tgt]} -> {model_vols[tgt]}")
    anon = {v["target"] for v in s.get("volumes") or [] if isinstance(v, dict) and v.get("type") == "volume" and not v.get("source")}
    for tgt in sorted(set(live_vols) - set(model_vols) - anon):
        if len(str(live_vols[tgt])) != 64:
            notes.append(f"named volume removed {tgt}")
    live_tmpfs = dict(hc.get("Tmpfs") or {})
    for m in mounts:
        if m.get("Type") == "tmpfs":
            live_tmpfs.setdefault(m["Destination"], None)
    model_tmpfs = {}
    for t in s.get("tmpfs") or []:
        path, _, opts = str(t).partition(":")
        model_tmpfs[path] = opts
    for v in s.get("volumes") or []:
        if isinstance(v, dict) and v.get("type") == "tmpfs":
            model_tmpfs.setdefault(v["target"], None)
    if set(live_tmpfs) != set(model_tmpfs):
        notes.append(f"tmpfs {sorted(live_tmpfs)} -> {sorted(model_tmpfs)}")
    else:
        opts = sorted(p for p in model_tmpfs if model_tmpfs[p] is not None and live_tmpfs.get(p) != model_tmpfs[p])
        if opts:
            notes.append(f"tmpfs options {opts}")
    pairs = (
        ("read_only", bool(hc.get("ReadonlyRootfs")), bool(s.get("read_only"))),
        ("pid", hc.get("PidMode") or "", s.get("pid") or ""),
        ("init", bool(hc.get("Init")), bool(s.get("init"))),
        ("privileged", bool(hc.get("Privileged")), bool(s.get("privileged"))),
        ("restart", (hc.get("RestartPolicy") or {}).get("Name") or "no", s.get("restart") or "no"),
        ("cap_drop", caps(hc.get("CapDrop")), caps(s.get("cap_drop"))),
        ("cap_add", caps(hc.get("CapAdd")), caps(s.get("cap_add"))),
        ("security_opt", sorted(hc.get("SecurityOpt") or []), sorted(s.get("security_opt") or [])),
        ("mem_limit", hc.get("Memory") or 0, to_int(s.get("mem_limit")) or 0),
        ("cpus", (hc.get("NanoCpus") or 0) / 1e9, float(s.get("cpus") or 0)),
        ("pids_limit", hc.get("PidsLimit") or 0, to_int(s.get("pids_limit")) or 0),
    )
    for name, a, b in pairs:
        if a != b:
            notes.append(f"{name}: {a} -> {b}")
    if s.get("stop_grace_period") and cfg.get("StopTimeout") != dur_ns(s["stop_grace_period"]) // 10**9:
        notes.append(f"stop_grace_period: {cfg.get('StopTimeout')}s -> {s.get('stop_grace_period')}")
    if s.get("shm_size") is not None and to_int(s.get("shm_size")) != hc.get("ShmSize"):
        notes.append(f"shm_size: {hc.get('ShmSize')} -> {s.get('shm_size')}")
    live_user, model_user = cfg.get("User") or "", s.get("user") or ""
    if model_user != live_user and not (model_user == "" and live_user == (icfg.get("User") or "")):
        notes.append(f"user: {live_user or '(image default)'} -> {model_user or '(image default)'}")
    live_ports = set()
    for k, binds in (hc.get("PortBindings") or {}).items():
        target, _, proto = k.partition("/")
        for b in binds or []:
            live_ports.add(port_key(b.get("HostIp"), b.get("HostPort"), target, proto))
    model_ports = {port_key(p.get("host_ip"), p.get("published"), p.get("target"), p.get("protocol"))
                   for p in s.get("ports") or [] if isinstance(p, dict)}
    if live_ports != model_ports:
        notes.append(f"ports {sorted(live_ports)} -> {sorted(model_ports)}")
    top_nets = model.get("networks") or {}
    model_nets = sorted((top_nets.get(n) or {}).get("name", n) for n in (s.get("networks") or {}))
    live_nets = sorted(((c.get("NetworkSettings") or {}).get("Networks") or {}).keys())
    if s.get("network_mode"):
        if hc.get("NetworkMode") != s.get("network_mode"):
            notes.append(f"network_mode {hc.get('NetworkMode')} -> {s.get('network_mode')}")
    elif live_nets != model_nets:
        notes.append(f"networks {live_nets} -> {model_nets}")
    for key, live_key in (("command", "Cmd"), ("entrypoint", "Entrypoint")):
        want, have = s.get(key), cfg.get(live_key)
        if want is None:
            if img and have != icfg.get(live_key):
                notes.append(f"{key}: custom -> image default")
        elif isinstance(want, list) and have != want:
            notes.append(f"{key} changed")
    mh, lh = s.get("healthcheck"), cfg.get("Healthcheck")
    if mh is None:
        if lh and lh != icfg.get("Healthcheck"):
            notes.append("healthcheck removed")
    elif mh.get("disable"):
        if not lh or lh.get("Test") != ["NONE"]:
            notes.append("healthcheck disabled")
    else:
        lh = lh or {}
        if mh.get("test") != lh.get("Test"):
            notes.append("healthcheck test changed")
        for mk, lk in (("interval", "Interval"), ("timeout", "Timeout"), ("start_period", "StartPeriod"),
                       ("start_interval", "StartInterval")):
            if mh.get(mk) is not None and dur_ns(mh.get(mk)) != (lh.get(lk) or 0):
                notes.append(f"healthcheck {mk} changed")
        if mh.get("retries") is not None and mh.get("retries") != lh.get("Retries"):
            notes.append("healthcheck retries changed")
    ml, ll = s.get("logging") or {}, hc.get("LogConfig") or {}
    if ml:
        if (ml.get("driver") or "json-file") != ll.get("Type"):
            notes.append(f"logging driver {ll.get('Type')} -> {ml.get('driver')}")
        mo, lo = ml.get("options") or {}, ll.get("Config") or {}
        if mo != lo:
            notes.append("logging options changed")
    img_labels = icfg.get("Labels") or {}
    live_labels = {k: v for k, v in (cfg.get("Labels") or {}).items()
                   if not k.startswith(("com.docker.compose.", "desktop.docker.io/")) and img_labels.get(k) != v}
    model_labels = {k: str(v) for k, v in (s.get("labels") or {}).items()}
    if live_labels != model_labels:
        notes.append("labels changed")
    return notes


def label(c, key):
    return ((c.get("Config") or {}).get("Labels") or {}).get(key, "")


def env_key(path, key):
    """Value of key in an env file, read the way deploy-common.sh parkio_env_value reads it. Only ever
    compared with known profile names; never printed."""
    value = None
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith(key + "="):
                value = line.rstrip("\n").split("=", 1)[1]
    if value is not None and len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", required=True)
    ap.add_argument("--env-file", required=True)
    ap.add_argument("--project", default="parkio")
    ap.add_argument("--out", default=os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
    a = ap.parse_args()
    STATE["stamp"] = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    os.makedirs(a.out, mode=0o700, exist_ok=True)
    STATE["out"] = a.out

    list_file = os.path.join(a.release, "docker", "compose.production.files")
    for path, what in ((a.env_file, "env file"), (list_file, "production file list")):
        if not os.path.isfile(path):
            stop(f"missing {what} {path}")
    canonical = [ln.strip() for ln in open(list_file, encoding="utf-8") if ln.strip() and not ln.strip().startswith("#")]
    civo_files = [os.path.join(a.release, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        civo_files.append(os.path.join(a.release, CIVO_OVERLAY))
    for f in civo_files:
        if not os.path.isfile(f):
            stop(f"missing compose file {f}")
    rc, cver, _ = run(["docker", "compose", "version", "--short"])
    rc, native, _ = run(["docker", "version", "--format", "{{.Server.Os}}/{{.Server.Arch}}"])
    native = native.strip() or "linux/amd64"
    say(f"D0.2.2 release scope | stamp {STATE['stamp']} | compose {cver.strip()} | daemon {native}")
    say(f"release checkout {a.release} | project {a.project} | report and evidence in {a.out}")

    model, hashes, err = render(civo_files, a.env_file)
    if err:
        stop(f"release model (production wrapper): {err}")
    if model.get("name") != a.project:
        stop(f"release model project name is not {a.project}")
    services = model.get("services") or {}
    built = sorted(n for n, s in services.items() if s.get("build") and "@sha256:" not in (s.get("image") or ""))
    pinned = sorted(n for n, s in services.items() if "@sha256:" in (s.get("image") or "") and s.get("build"))
    say(f"release model: {len(services)} active services | digest-pinned app services {pinned} | built on the host {built}")

    rc, out, _ = run(["docker", "ps", "-aq", "--filter", f"label=com.docker.compose.project={a.project}"])
    ids = out.split()
    if not ids:
        stop(f"no container carries label com.docker.compose.project={a.project}")
    rc, out, _ = run(["docker", "inspect", *ids])
    live = {}
    for c in json.loads(out):
        svc = label(c, "com.docker.compose.service")
        if svc:
            live.setdefault(svc, []).append(c)
    images = Images()

    # 1. Live file lists and self-check
    say("")
    say("1. Live Compose file lists recorded on the running containers (self-check: this Compose reproduces the labels)")
    groups, group_of = [], {}
    for svc in sorted(live):
        for c in live[svc]:
            key = (label(c, "com.docker.compose.project.working_dir"), label(c, "com.docker.compose.project.config_files"),
                   label(c, "com.docker.compose.project.environment_file"))
            if key not in [g["key"] for g in groups]:
                groups.append({"key": key, "members": []})
            g = [g for g in groups if g["key"] == key][0]
            g["members"].append((svc, c))
            group_of[c["Id"]] = groups.index(g) + 1
    canon_base = [os.path.basename(f) for f in canonical]
    reproduced_total, total = 0, 0
    for idx, g in enumerate(groups, 1):
        wd, cfgs, envf = g["key"]
        files = [f for f in cfgs.split(",") if f]
        names = [os.path.basename(f) for f in files]
        missing = [f for f in files if not os.path.isfile(f)]
        rebuilt = None
        member_names = sorted({s for s, _ in g["members"]})
        if len(missing) == 1 and os.path.basename(missing[0]) == "web-binding.yml" and member_names == ["web"]:
            web_image = (g["members"][0][1].get("Config") or {}).get("Image")
            rebuilt = os.path.join(a.out, f"D022-web-binding-rebuilt-L{idx}.yml")
            fd = os.open(rebuilt, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                json.dump({"services": {"web": {"image": web_image, "pull_policy": "never"}}}, fh)
            files = [rebuilt if f == missing[0] else f for f in files]
            missing = []
        env_used = envf if envf and "," not in envf and os.path.isfile(envf) else a.env_file
        say(f"L{idx}: {len(g['members'])} container(s) {member_names}")
        say(f"    project dir {wd or '(none)'} | env file {'as recorded' if env_used == envf else 'given (--env-file)'}")
        say(f"    files {names}")
        extra = [n for n in names if n not in canon_base and n != os.path.basename(CIVO_OVERLAY)]
        lacking = [n for n in canon_base if n not in names]
        say(f"    vs the release production list: lacks {lacking or 'nothing'}; adds {extra or 'nothing'}"
            f"{'; Civo overlay present' if os.path.basename(CIVO_OVERLAY) in names else '; no Civo overlay'}"
            f"{'; temporary web binding rebuilt from the container image' if rebuilt else ''}")
        total += len(g["members"])
        if missing:
            say(f"    self-check: NOT POSSIBLE, recorded file(s) missing: {[os.path.basename(m) for m in missing]}")
            continue
        gmodel, ghash, err = render(files, env_used, wd or None)
        if err:
            say(f"    self-check: NOT POSSIBLE, {err}")
            continue
        ok, bad = 0, []
        for svc, c in g["members"]:
            if ghash.get(svc) and ghash[svc] == label(c, "com.docker.compose.config-hash"):
                ok += 1
            else:
                s = (gmodel.get("services") or {}).get(svc)
                why = field_diff(c, images.inspect(c.get("Image")), s, gmodel) if s else ["service absent from its recorded model"]
                bad.append(f"{svc} ({'; '.join(why) or 'outside the compared fields'})")
        reproduced_total += ok
        say(f"    self-check: {ok}/{len(g['members'])} labels reproduced" + (f"; not reproduced: {bad}" if bad else ""))
    say(f"self-check total: {reproduced_total}/{total} running containers reproduce their config-hash label")

    # 2./3. Per-service scope against the release model
    explicit = {}
    for step, names in EXPLICIT:
        for n in names:
            explicit[n] = step
    say("")
    say("2. Per service against the release model (L<n> = live file list above)")
    rows = {"explicit": [], "six": [], "drift": [], "same": [], "absent": [], "outside": []}
    for svc in sorted(set(live) | set(services)):
        cs = live.get(svc, [])
        c = cs[0] if cs else None
        k = kind(svc)
        tag = f" [{k}]" if k else ""
        where = f" L{group_of[c['Id']]}" if c else ""
        multi = f" ({len(cs)} containers)" if len(cs) > 1 else ""
        s = services.get(svc)
        if svc in explicit and s is None:
            stop(f"{svc} is named in {explicit[svc]} but is not in the release model")
        reasons = []
        same = False
        if c and s is not None:
            same = hashes.get(svc) == label(c, "com.docker.compose.config-hash")
            if not same:
                reasons = field_diff(c, images.inspect(c.get("Image")), s, model) or ["outside the compared fields (config hash differs)"]
            if s.get("image") and svc not in built:
                cands = images.ids(s["image"], s.get("platform") or native)
                current = images.inspect(s["image"])
                rec = label(c, "com.docker.compose.image")
                if not cands:
                    reasons.append("image not present locally (would be pulled)")
                elif not rec:
                    reasons.append("container has no com.docker.compose.image label")
                elif (rec not in cands and not (current and current.get("Id") == c.get("Image"))
                      and s["image"] == (c.get("Config") or {}).get("Image")):
                    reasons.append("same image reference now resolves to another local image")
                if reasons:
                    same = False
        detail = "; ".join(reasons)
        if svc in explicit:
            what = "recreated (--no-deps --force-recreate)" if c else "CREATED (no container today)"
            rest = f" -- changes: {detail}" if detail else (" -- config otherwise unchanged" if c else "")
            rows["explicit"].append((svc, f"{explicit[svc]}  {svc}{tag}{where}{multi}: {what}{rest}"))
        elif svc in built:
            what = "recreated" if c else "CREATED (no container today)"
            rows["six"].append((svc, f"D7  {svc}{tag}{where}{multi}: {what} with the image built from the release checkout"
                                     + (f" -- changes: {detail}" if detail else "")))
        elif c and s is not None and not same:
            rows["drift"].append((svc, f"--  {svc}{tag}{where}{multi}: DRIFT, kept as is by D2-D7 -- {detail}"))
        elif c and s is not None:
            rows["same"].append((svc, f"--  {svc}{tag}{where}: unchanged"))
        elif s is not None:
            rows["absent"].append((svc, f"--  {svc}{tag}: in the release model, no container today; no step creates it"))
        else:
            state = (c.get("State") or {}).get("Status")
            rows["outside"].append((svc, f"--  {svc}{tag}{where}: not in the release model ({state}); no step touches it"))
    for key in ("explicit", "six", "drift", "same", "absent", "outside"):
        for _, line in rows[key]:
            say(line)
    drift_names = [n for n, _ in rows["drift"]]
    drift_db = [n for n in drift_names if kind(n)]

    # 4. Deploy wrapper feasibility on this host
    say("")
    say("4. scripts/deploy-hosted-beta.sh on this host")
    shell_profile = os.environ.get("PARKIO_DEPLOYMENT_PROFILE")
    file_profile = env_key(a.env_file, "PARKIO_DEPLOYMENT_PROFILE")

    def name(v):
        if v is None:
            return "unset"
        return v if v in KNOWN_PROFILES else "(unrecognised value)"

    selected = shell_profile or file_profile or "hosted-beta"
    say(f"profile: shell {name(shell_profile)} | env file {name(file_profile)} | selected {name(selected)}")
    caddy_env = (services.get("caddy") or {}).get("environment") or {}
    found = [k for k in ("PARKIO_DOMAIN", "PARKIO_WEB_DOMAIN", "PARKIO_MEDIA_DOMAIN") if host_of(caddy_env.get(k)) in PRODUCTION_HOSTNAMES]
    csp = str(((services.get("web") or {}).get("environment") or {}).get("PARKIO_WEB_CSP_CONNECT_SRC") or "")
    if any("://" in u and host_of(u) in PRODUCTION_HOSTNAMES for u in csp.split()):
        found.append("web PARKIO_WEB_CSP_CONNECT_SRC")
    say(f"production hostnames in the model: {found or 'none'}")
    if selected == "hosted-beta" and found:
        say("hosted-beta profile: REFUSES this env by design (owner decision 2026-10-05 item 4; no override)")
    say("azure-hosted-beta profile: deprecated (B7); it cannot build digest-pinned services "
        f"({', '.join(pinned) or 'none'}): a build-and-deploy stops at the first one")
    say("=> D7 runs through the production wrapper scripts/parkio-prod-compose.sh: build the six, then up --no-deps;"
        " the deploy wrapper's manifest, deployed record and built-in smoke do not run there")

    # 5. Other Compose projects
    say("")
    say("5. Other Compose projects on this host (no step names them; project-scoped commands never touch them)")
    rc, out, _ = run(["docker", "ps", "-a", "--format", '{{.Label "com.docker.compose.project"}}|{{.Names}}'])
    counts = {}
    for line in out.splitlines():
        proj, _, nm = line.partition("|")
        if proj and proj != a.project:
            counts[proj] = counts.get(proj, 0) + 1
        elif not proj and nm.startswith("parkio"):
            counts["(no compose label)"] = counts.get("(no compose label)", 0) + 1
    for proj in sorted(counts):
        say(f"project {proj}: {counts[proj]} container(s), untouched")
    if not counts:
        say("none")
    if NR_PROJECT in counts:
        hit = [s for s in NR_SOURCE_SERVICES if s in explicit]
        say(f"note: the {NR_PROJECT} source helper pins {list(NR_SOURCE_SERVICES)} of project {a.project} by container id"
            f" (repository version at the release revision); {hit} are recreated in D3-D6, so their ids change")

    # Summary
    say("")
    say("SUMMARY")
    exp_names = [n for n, _ in rows["explicit"]]
    six_names = [n for n, _ in rows["six"]]
    say(f"D2-D6 recreate {len(exp_names)}: {exp_names}")
    say(f"D7 recreates {len(six_names)}: {six_names}")
    say(f"databases recreated by D2-D7: {[n for n in exp_names + six_names if kind(n) == 'DATABASE'] or 'none'}")
    say(f"drift kept by D2-D7 ({len(drift_names)}): {drift_names}")
    say(f"stateful services in that drift: {drift_db or 'none'}")
    say(f"a whole-project up would also recreate those {len(drift_names)} and create {len(rows['absent'])} service(s)")
    ne = "node-exporter" in exp_names + six_names
    say(f"node-exporter recreated by D2-D7: {'yes' if ne else 'no'} -> textfile correction "
        f"{'REQUIRED before D2' if ne else 'not required for D2-D7 (required before any step that recreates node-exporter)'}")
    say("D0.2.2 COMPLETE (read-only)")
    save()


if __name__ == "__main__":
    main()
