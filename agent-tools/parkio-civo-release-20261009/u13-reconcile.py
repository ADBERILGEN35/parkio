#!/usr/bin/env python3
"""U13 reconciliation v2 (Asana 1219002004548841): a dated, read-only comparison of the effective production Compose and every
container's image with the canonical api file set. Also prints the U12 cutoff values (Asana 1219001760069487).

Run as civo on parkio-civo-prod:
  python3 -I ~/parkio-release-20261009/tools/u13-reconcile.py

Read-only: docker ps / inspect / image inspect, `docker compose config` renders, `systemctl cat`, `git --no-optional-locks
status/diff --stat`. Nothing is created, started, stopped, pulled or tagged; no env or Compose file is changed; no file outside
the report directory is written. T1 v7 (checksum-verified) is loaded as a module for its renders: the production wrapper's
model of the release checkout, and its helpers.

v2 (owner review of the 20261012T153129Z run):
- A Compose project started by a systemd unit (the NR transport, parkio-nr-log-continuous.service) is rendered with that
  unit's own inputs: the env files and profiles of its ExecStart, read with `systemctl cat` (no root). Those env files are
  root-only, so that render is `sudo -n docker compose ... config --hash`, which prints service hashes only: no value enters
  this tool, is printed or stored, no error text from it is shown, and no JSON model is rendered from root-only inputs.
- The web binding that the wrapper deletes after a start is rebuilt from the container's own Config.Image (the repo@sha256
  reference the web guard bound, the model's pin) with pull_policy never, as the guard writes it. v1 rebuilt it from the
  daemon image id, a reconstruction error and not a configuration difference. pull_policy is outside the config hash.
- Self-check of each container (its config-hash label against today's render of its own recorded inputs):
    match          - equal;
    MISMATCH       - a proven difference: the container was created from another configuration than its recorded
                     inputs give today;
    unrenderable   - no render, so no conclusion (the reason is printed);
    inputs-missing - its recorded Compose files no longer exist;
    -              - not created by Compose.
- Differences from the canonical render name the keys (environment variables by name only); for configuration keys that
  carry no secrets (image, resources, restart, capabilities, security options, user, platform, read_only, tmpfs, ulimits,
  stop settings, init, privileged) both values are shown.
- G. Canonical services without a running container: the intended state from the canonical model (one-shot init that must
  complete, or long-running) against the actual container state.

Sections: A host and checkouts (changed paths by name only); B canonical render; C launchers and every container (image,
digest, revision, state, self-check, recorded inputs); D digest table (pinned / pinned-other / release-built / canonical-tag /
outside / unknown) with differences from the canonical render; E flags (true/false/empty/set); F U12 values; G intended states.
Report: ~/parkio-release-20261009/reconcile-<stamp>/report.{txt,json} (dir 700, files 600). Exit 0 when the inventory is
complete (canonical render readable, every Compose container's self-check concluded or its inputs gone, U12 values read);
1 otherwise. Mismatches and differences are findings, not failures.
"""
import hashlib
import importlib.util
import json
import os
import re
import shlex
import sys

T1_PATH = os.environ.get("U13R_T1", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "tools",
                                                  "t1-release-step.py"))
T1_SHA = os.environ.get("U13R_T1_SHA256", "2670b41e58e75b8edcfc57c6ad4b4972cc4f3c5e14a45b0b8c7132bf07efe0d4")  # T1 v7
PIN_OVERRIDE = os.environ.get("U13R_PIN", "")                    # self-test only
LAUNCHER_UNITS = ("parkio-nr-log-continuous.service",)
FLAG = re.compile(r"(_ENABLED$|_FLAG|^FEATURE_|_FEATURE_)")
SKIP_KEYS = {"environment"}
SHOW_VALUE_KEYS = {"image", "mem_limit", "memswap_limit", "mem_reservation", "cpus", "cpu_shares", "pids_limit", "read_only",
                   "restart", "cap_drop", "cap_add", "security_opt", "user", "platform", "stop_grace_period", "stop_signal",
                   "init", "privileged", "shm_size", "tmpfs", "ulimits"}
IDENTITY_ENV = ("PARKIO_COLLECTOR_SOURCE_SHA", "PARKIO_RELEASE_ID")   # non-secret identity of launcher-started containers
VALUE_OPTS = {"--env-file", "-f", "--file", "--profile", "-p", "--project-name", "--project-directory", "--ansi",
              "--progress", "--parallel"}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def digest_of(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, default=str).encode()).hexdigest()


def env_map(svc):
    env = (svc or {}).get("environment") or {}
    if isinstance(env, list):
        env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
    return {k: digest_of(v) for k, v in env.items()}


def differences(a, b):
    """Names of the keys whose rendered values differ between two service dicts; environment variables by name only."""
    if a is None or b is None:
        return ["(service missing in one render)"]
    out = []
    for k in sorted(set(a) | set(b)):
        if k in SKIP_KEYS:
            continue
        if digest_of(a.get(k)) != digest_of(b.get(k)):
            out.append(k)
    ea, eb = env_map(a), env_map(b)
    env_diff = sorted(k for k in set(ea) | set(eb) if ea.get(k) != eb.get(k))
    if env_diff:
        out.append("environment[" + ",".join(env_diff) + "]")
    return out


def brief(value, limit=150):
    text = json.dumps(value, sort_keys=True, default=str) if value is not None else "absent"
    return text if len(text) <= limit else text[:limit] + "…"


def unit_lines(text):
    out, buf = [], ""
    for line in (text or "").splitlines():
        if line.endswith("\\"):
            buf += line[:-1] + " "
            continue
        out.append(buf + line)
        buf = ""
    if buf:
        out.append(buf)
    return out


def compose_launch(unit_text):
    """WorkingDirectory and the `docker compose` options of a unit's ExecStart (the last one that runs docker compose)."""
    wd, execs = "", []
    for line in unit_lines(unit_text):
        line = line.strip()
        if not line or line.startswith(("#", ";", "[")) or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if key == "WorkingDirectory":
            wd = value.lstrip("-")
        elif key == "ExecStart":
            execs = [] if not value else execs + [value]
    for cmd in reversed(execs):
        try:
            words = shlex.split(cmd)
        except ValueError:
            continue
        if len(words) < 2:
            continue
        words[0] = words[0].lstrip("-@+!:")
        if os.path.basename(words[0]) != "docker" or words[1] != "compose":
            continue
        opts = {"workingDir": wd, "envFiles": [], "files": [], "profiles": [], "project": "", "projectDirectory": ""}
        i = 2
        while i < len(words):
            word = words[i]
            name, eq, inline = word.partition("=")
            if name in VALUE_OPTS:
                value = inline if eq else (words[i + 1] if i + 1 < len(words) else "")
                i += 1 if eq else 2
                if name == "--env-file":
                    opts["envFiles"].append(value)
                elif name in ("-f", "--file"):
                    opts["files"].append(value)
                elif name == "--profile":
                    opts["profiles"].append(value)
                elif name in ("-p", "--project-name"):
                    opts["project"] = value
                elif name == "--project-directory":
                    opts["projectDirectory"] = value
            elif word.startswith("-"):
                i += 1
            else:
                break
        return opts
    return None


def recorded_inputs(t1, c, outdir, launchers):
    """The Compose inputs recorded on a container: working directory, files and env files (labels), plus the profiles of
    the unit that launches its project. A temporary web binding that no longer exists is rebuilt into this report's
    directory from the container's Config.Image."""
    lab = c["labels"]
    project = lab.get("com.docker.compose.project") or ""
    files = [f for f in (lab.get("com.docker.compose.project.config_files") or "").split(",") if f]
    envs = [e for e in (lab.get("com.docker.compose.project.environment_file") or "").split(",") if e]
    launcher = launchers.get(project)
    if not envs:
        envs = list(launcher["envFiles"]) if launcher else ([t1.ENV_FILE] if project == t1.PROJECT else [])
    inputs = {"project": project, "workingDir": lab.get("com.docker.compose.project.working_dir") or "", "files": files,
              "envFiles": envs, "profiles": list(launcher["profiles"]) if launcher else [],
              "launcher": launcher["unit"] if launcher else None}
    missing = [f for f in files if not os.path.isfile(f)]
    if (lab.get("com.docker.compose.service") == "web" and len(missing) == 1
            and os.path.basename(missing[0]) == "web-binding.yml" and c.get("configImage")):
        rebuilt = os.path.join(outdir, "web-binding-rebuilt.yml")
        t1.private_write(rebuilt, json.dumps({"services": {"web": {"image": c["configImage"], "pull_policy": "never"}}}) + "\n")
        inputs["files"] = [rebuilt if f == missing[0] else f for f in files]
        inputs["rebuiltWebBinding"] = c["configImage"]
    elif missing:
        inputs["missing"] = [os.path.basename(f) for f in missing]
    return inputs


def compose_cmd(inputs, args):
    cmd = ["docker", "compose"]
    for e in inputs["envFiles"]:
        cmd += ["--env-file", e]
    if inputs.get("workingDir"):
        cmd += ["--project-directory", inputs["workingDir"]]
    if inputs.get("project"):
        cmd += ["-p", inputs["project"]]
    for f in inputs["files"]:
        cmd += ["-f", f]
    for p in inputs.get("profiles") or []:
        cmd += ["--profile", p]
    return cmd + list(args)


def needs_root(inputs):
    return any(not os.access(e, os.R_OK) for e in inputs["envFiles"])


def parse_hashes(out):
    return dict(ln.split()[:2] for ln in (out or "").splitlines() if len(ln.split()) == 2)


class Group:
    """One set of recorded inputs, rendered once: a JSON model (never from root-only inputs) and config hashes."""

    def __init__(self, t1, inputs):
        self.t1, self.inputs, self.model, self.hashes, self.reason = t1, inputs, None, {}, None
        self.root = needs_root(inputs) if not inputs.get("missing") else False
        self.sudo_ok = None
        if inputs.get("missing"):
            return
        if self.root:
            rc, _, _ = t1.run(["sudo", "-n", "true"])
            self.sudo_ok = rc == 0
            if not self.sudo_ok:
                self.reason = "the launcher's env files are root-only and `sudo -n` was refused"
                return
        else:
            rc, out, err = t1.run(compose_cmd(inputs, ["config", "--format", "json"]), timeout=180)
            try:
                self.model = json.loads(out) if rc == 0 else None
            except ValueError:
                self.model = None
            if self.model is None:
                self.reason = t1.first_error(err) or f"render failed (exit {rc})"
        rc, out, err = self.run(["config", "--hash", "*"])
        self.hashes = parse_hashes(out) if rc == 0 else {}

    def run(self, args):
        prefix = ["sudo", "-n"] if self.root else []
        return self.t1.run(prefix + compose_cmd(self.inputs, args), timeout=180)

    def hash_of(self, service):
        """(hash, reason): the config hash today's render of these inputs gives the service."""
        if self.inputs.get("missing"):
            return None, "recorded files no longer exist"
        if self.root and not self.sudo_ok:
            return None, self.reason
        if service not in self.hashes:                     # Compose without --hash '*', or a service it did not print
            rc, out, err = self.run(["config", "--hash", service])
            h = parse_hashes(out).get(service) if rc == 0 else None
            if h:
                self.hashes[service] = h
            else:
                why = (f"render failed (exit {rc}); output withheld (root-only inputs)" if self.root
                       else (self.reason or self.t1.first_error(err) or f"render failed (exit {rc})"))
                return None, why
        return self.hashes[service], None


def intended(name, svc, canon):
    """(kind, text) of a canonical service's intended operating state. One-shot only when another service waits for it
    with service_completed_successfully; otherwise long-running."""
    restart = svc.get("restart") or "unset"
    waiters = sorted(s for s, d in canon.items() if isinstance(d.get("depends_on"), dict)
                     and ((d["depends_on"].get(name) or {}).get("condition") == "service_completed_successfully"))
    if waiters:
        return "one-shot", (f"one-shot init (restart: {restart}; {', '.join(waiters)} wait(s) for "
                            "service_completed_successfully): expected to have run and exited 0")
    return "long-running", f"long-running (restart: {restart}): expected running"


def main():
    try:
        actual = sha256_file(T1_PATH)
    except OSError:
        actual = "missing"
    if actual != T1_SHA:
        print(f"STOP: the installed T1 ({T1_PATH}) is not T1 v7 {T1_SHA[:12]} (found {actual[:12]}); nothing was done")
        sys.exit(1)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("t1", T1_PATH)
    t1 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(t1)
    say = t1.say
    problems = []
    pin = PIN_OVERRIDE or t1.PIN
    rep = {"tool": "u13-reconcile", "version": 2, "pin": pin, "stamp": t1.STAMP, "t1Sha256": T1_SHA}
    outdir = os.path.join(t1.OUT, f"reconcile-{t1.STAMP}")
    os.makedirs(outdir, mode=0o700, exist_ok=True)

    say(f"U13 reconciliation v2 | stamp {t1.STAMP} | read-only | canonical = api file set at {pin[:12]}")
    say("")
    say("A. Host")
    rc, host, _ = t1.run(["hostname"])
    rc, dver, _ = t1.run(["docker", "version", "--format", "{{.Server.Version}}"])
    rc, cver, _ = t1.run(["docker", "compose", "version", "--short"])
    rc, rel_head = t1.git(t1.REL, "rev-parse", "HEAD")
    rc, rel_dirty = t1.git(t1.REL, "status", "--porcelain")
    rc, live_head = t1.git(t1.LIVE, "rev-parse", "HEAD")
    rc, live_status, _ = t1.run(["git", "--no-optional-locks", "-C", t1.LIVE, "status", "--porcelain"])
    rc, live_stat = t1.git(t1.LIVE, "diff", "--stat", "HEAD")
    changed = [ln[3:] for ln in live_status.splitlines() if len(ln) > 3]
    rep["host"] = {"name": host.strip(), "date": t1.now(), "docker": dver.strip(), "compose": cver.strip(),
                   "releaseHead": rel_head, "releaseClean": not rel_dirty, "liveHead": live_head, "liveChangedPaths": changed,
                   "liveDiffStat": (live_stat.splitlines() or [""])[-1]}
    say(f"  {host.strip()} | {t1.now()} | Docker {dver.strip()} | Compose {cver.strip()}")
    say(f"  release checkout {t1.REL}: HEAD {rel_head[:12]} ({'= PIN' if rel_head == pin else 'NOT the PIN'}), "
        f"{'clean' if not rel_dirty else str(len(rel_dirty.splitlines())) + ' change(s)'}")
    say(f"  live checkout {t1.LIVE}: HEAD {live_head[:12]}, {len(changed)} changed path(s); {rep['host']['liveDiffStat']}")
    for p in changed:
        say(f"    changed: {p}")
    if rel_head != pin or rel_dirty:
        problems.append("release checkout not clean at the PIN")

    say("")
    say("B. Canonical render (production wrapper, release checkout)")
    canon = t1.model() or {}
    canon_gw = t1.model(extra=t1.EXTRA_FILES.get("gateway-service", [])) or {}
    csvc = dict((canon.get("services") or {}))
    if "gateway-service" in (canon_gw.get("services") or {}):
        csvc["gateway-service"] = canon_gw["services"]["gateway-service"]
    if not csvc:
        problems.append("canonical render failed")
    chash = {}
    for svc in sorted(csvc):
        h = t1.model_hash(svc)
        chash[svc] = h
        img = csvc[svc].get("image") or "-"
        say(f"  {svc}: {img[:110]}{' (build)' if csvc[svc].get('build') else ''} hash {(h or '-')[:12]}")
    rep["canonical"] = {s: {"image": csvc[s].get("image"), "build": bool(csvc[s].get("build")), "hash": chash.get(s),
                            "restart": csvc[s].get("restart")} for s in csvc}

    say("")
    say("C0. Compose projects started by systemd units (systemctl cat, no root)")
    launchers = {}
    for unit in LAUNCHER_UNITS:
        rc, text, _ = t1.run(["systemctl", "cat", "--no-pager", unit])
        opts = compose_launch(text) if rc == 0 else None
        if not opts or not opts["project"]:
            say(f"  {unit}: {'not installed' if rc != 0 else 'no docker compose ExecStart'}")
            continue
        opts["unit"] = unit
        launchers[opts["project"]] = opts
        envs = ", ".join(f"{e} ({'readable' if os.access(e, os.R_OK) else 'root-only'})" for e in opts["envFiles"])
        say(f"  {unit}: project {opts['project']}, working dir {opts['workingDir']} -> {os.path.realpath(opts['workingDir'])}")
        say(f"      profiles {', '.join(opts['profiles']) or '-'} | files {', '.join(opts['files'])} | env files {envs or '-'}")
    rep["launchers"] = launchers

    say("")
    say("C/D. Containers and digest table")
    rc, ids, _ = t1.run(["docker", "ps", "-a", "-q", "--no-trunc"])
    groups, rows = {}, []
    for cid in ids.split():
        rc, info, _ = t1.run(["docker", "inspect", cid])
        try:
            data = json.loads(info)[0]
        except (ValueError, IndexError):
            problems.append(f"inspect failed for {cid[:12]}")
            continue
        config = data.get("Config") or {}
        labels = config.get("Labels") or {}
        image_id = data.get("Image") or ""
        rc, iinfo, _ = t1.run(["docker", "image", "inspect", image_id])
        try:
            idata = json.loads(iinfo)[0]
        except (ValueError, IndexError):
            idata = {}
        ilabels = (idata.get("Config") or {}).get("Labels") or {}
        state = data.get("State") or {}
        row = {"name": (data.get("Name") or "").lstrip("/"), "id": data.get("Id", "")[:12],
               "project": labels.get("com.docker.compose.project"), "service": labels.get("com.docker.compose.service"),
               "ref": config.get("Image"), "imageId": image_id,
               "repoDigests": idata.get("RepoDigests") or [], "revision": ilabels.get("org.opencontainers.image.revision"),
               "state": state.get("Status"), "exitCode": state.get("ExitCode"), "finishedAt": state.get("FinishedAt"),
               "health": (state.get("Health") or {}).get("Status"), "labelHash": labels.get("com.docker.compose.config-hash")}
        live_svc = None
        if row["project"] and row["service"]:
            inputs = recorded_inputs(t1, {"labels": labels, "configImage": config.get("Image")}, outdir, launchers)
            key = json.dumps([inputs.get("workingDir"), inputs.get("files"), inputs.get("envFiles"), inputs.get("project"),
                              inputs.get("profiles")])
            if key not in groups:
                groups[key] = Group(t1, inputs)
            g = groups[key]
            row["inputs"] = {"workingDir": inputs.get("workingDir"), "files": [os.path.basename(f) for f in inputs["files"]],
                             "envFiles": [os.path.basename(e) for e in inputs["envFiles"]], "profiles": inputs["profiles"],
                             "launcher": inputs["launcher"], "rootOnly": g.root, "missing": inputs.get("missing"),
                             "rebuiltWebBinding": inputs.get("rebuiltWebBinding")}
            if inputs.get("missing"):
                row["selfCheck"], row["selfCheckReason"] = "inputs-missing", "recorded files no longer exist"
            else:
                h, why = g.hash_of(row["service"])
                row["recordedHash"] = h
                if h is None:
                    row["selfCheck"], row["selfCheckReason"] = "unrenderable", why
                else:
                    row["selfCheck"] = "match" if h == row["labelHash"] else "MISMATCH"
            live_svc = ((g.model or {}).get("services") or {}).get(row["service"])
            if inputs["launcher"]:
                envd = dict(e.split("=", 1) for e in (config.get("Env") or []) if "=" in e)
                row["identity"] = {k: envd.get(k) for k in IDENTITY_ENV if k in envd}
        else:
            row["selfCheck"] = "-"
        svc = row["service"]
        canon_svc = csvc.get(svc) if row["project"] == t1.PROJECT else None
        cref = (canon_svc or {}).get("image") or ""
        if not row["project"]:
            cls = "unknown"
        elif canon_svc is None:
            cls = "outside"
        elif "@sha256:" in cref:
            pin_digest = cref.split("@", 1)[1]
            same = (t1.image_id(cref) == image_id or image_id == pin_digest
                    or any(d.endswith("@" + pin_digest) for d in row["repoDigests"]))
            cls = "pinned" if same else "pinned-other"
        elif canon_svc.get("build"):
            cls = "release-built"
        else:
            cls = "canonical-tag"
        row["class"] = cls
        row["equalsCanonical"] = (row["labelHash"] == chash.get(svc)) if canon_svc is not None else None
        row["differences"], row["valueDifferences"] = [], {}
        if canon_svc is not None and not row["equalsCanonical"]:
            if live_svc is None:
                row["differences"] = ["(not compared: today's render of the recorded inputs is unavailable)"]
            else:
                row["differences"] = differences(live_svc, canon_svc)
                row["valueDifferences"] = {k: {"live": live_svc.get(k), "canonical": canon_svc.get(k)}
                                           for k in row["differences"] if k in SHOW_VALUE_KEYS}
        if cls == "outside":
            row["ownReference"] = "digest" if "@sha256:" in (row["ref"] or "") else "tag"
            row["outsideReason"] = ("another Compose project" if row["project"] != t1.PROJECT
                                    else "a parkio service the canonical render lacks")
        rows.append(row)
    rows.sort(key=lambda r: (r["project"] or "~", r["service"] or r["name"]))
    for r in rows:
        dig = next((d.split("@")[1][7:19] for d in r["repoDigests"]), "-")
        state = f"{r['state'] or '-'}" + (f"({r['exitCode']})" if r["state"] == "exited" else "") + f"/{r['health'] or '-'}"
        say(f"  {r['name'][:40]:40} {r['class']:13} {state} img {t1.short(r['imageId'])} digest {dig} "
            f"rev {(r['revision'] or '-')[:12]} self-check {r['selfCheck']} "
            f"canonical {'=' if r['equalsCanonical'] else ('-' if r['equalsCanonical'] is None else '≠')}")
        say(f"      ref {(r['ref'] or '-')[:120]}"
            + (f" | {r['outsideReason']}, own reference by {r['ownReference']}" if r.get("outsideReason") else ""))
        if r.get("selfCheckReason") and r["selfCheck"] == "unrenderable":
            say(f"      self-check unrenderable: {r['selfCheckReason']}")
        if r["differences"]:
            note = " (approximate: self-check MISMATCH)" if r["selfCheck"] == "MISMATCH" else ""
            say(f"      differs from canonical in{note}: {', '.join(r['differences'])[:600]}")
            for k, v in sorted(r["valueDifferences"].items()):
                say(f"        {k}: live {brief(v['live'])} | canonical {brief(v['canonical'])}")
        i = r.get("inputs")
        if i:
            say(f"      recorded inputs: {i['workingDir']} | {', '.join(i['files'])} | env {', '.join(i['envFiles']) or '-'}"
                + (f" | profiles {', '.join(i['profiles'])}" if i["profiles"] else "")
                + (f" | launcher {i['launcher']}" if i["launcher"] else "")
                + (" | root-only env files: hash-only render via sudo -n" if i["rootOnly"] else "")
                + (f" | missing {i['missing']}" if i.get("missing") else "")
                + (f" | web binding rebuilt from Config.Image {i['rebuiltWebBinding']}" if i.get("rebuiltWebBinding") else ""))
        if r.get("identity"):
            say(f"      identity: {', '.join(f'{k}={v}' for k, v in sorted(r['identity'].items()))}")
    rep["containers"] = rows
    running = {r["service"] for r in rows if r["project"] == t1.PROJECT and r["state"] == "running"}
    counts, checks = {}, {}
    for r in rows:
        counts[r["class"]] = counts.get(r["class"], 0) + 1
        checks[r["selfCheck"]] = checks.get(r["selfCheck"], 0) + 1
    rep["counts"], rep["selfChecks"] = counts, checks
    say(f"  classes: {', '.join(f'{k} {v}' for k, v in sorted(counts.items()))}")
    say(f"  self-checks: {', '.join(f'{k} {v}' for k, v in sorted(checks.items()))}")
    unrenderable = [r["name"] for r in rows if r["selfCheck"] == "unrenderable"]
    if unrenderable:
        problems.append(f"self-check unrenderable for {', '.join(unrenderable)}")

    say("")
    say("E. Effective flags (env file; true / false / empty / set)")
    flags = {}
    try:
        with open(t1.ENV_FILE, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line.rstrip("\n"))
                if m and FLAG.search(m.group(1)):
                    v = m.group(2).strip().strip("'\"")
                    flags[m.group(1)] = (v.lower() if v.lower() in ("true", "false") else ("empty" if v == "" else "set"))
    except OSError:
        problems.append("env file not readable")
    for k in sorted(flags):
        say(f"  {k}: {flags[k]}")
    rep["flags"] = flags

    say("")
    say("F. U12 cutoff values from the D7 record")
    state = t1.load_state()
    d7 = (state.get("steps") or {}).get("D7") or {}
    source = f"steps/D7-{d7.get('stamp')}.json"
    try:
        rec = json.load(open(os.path.join(t1.OUT, "steps", f"D7-{d7.get('stamp')}.json"), encoding="utf-8"))
        u12 = rec.get("u12") or {}
    except (OSError, ValueError):
        u12 = {}
    if not u12:
        u12, source = state.get("u12") or {}, "state.json u12 (T1 copies the D7 record's values there)"
    say(f"  source: {source}")
    for k in ("dltUserEndBefore", "scoreEndBefore", "Tg", "scoreEndAtTg"):
        say(f"  {k}: {json.dumps(u12.get(k), sort_keys=True)}")
    rep["u12"] = {k: u12.get(k) for k in ("dltUserEndBefore", "scoreEndBefore", "Tg", "scoreEndAtTg")}
    if not u12:
        problems.append("D7 record u12 values not readable")

    say("")
    say("G. Canonical services without a running container: intended against actual")
    rep["notRunning"] = {}
    for name in sorted(s for s in csvc if s not in running):
        kind, text = intended(name, csvc[name], csvc)
        mine = [r for r in rows if r["project"] == t1.PROJECT and r["service"] == name]
        if not mine:
            actual_state = "absent (no container)"
        else:
            actual_state = "; ".join(f"{r['state']}" + (f" (exit {r['exitCode']}) at {(r['finishedAt'] or '-')[:19]}Z"
                                                        if r["state"] == "exited" else "") for r in mine)
        as_intended = kind == "one-shot" and bool(mine) and all(r["state"] == "exited" and r["exitCode"] == 0 for r in mine)
        verdict = "as intended" if as_intended else "differs from its intended state - owner decision"
        rep["notRunning"][name] = {"intended": text, "actual": actual_state, "verdict": verdict}
        say(f"  {name}: intended {text}")
        say(f"      actual {actual_state} -> {verdict}")

    say("")
    rep["problems"] = problems
    say(f"inventory: {len(rows)} containers, {len(groups)} recorded-input groups, {len(csvc)} canonical services; "
        f"problems: {len(problems)}" + (f" ({'; '.join(problems)[:300]})" if problems else ""))
    say("U13 RECONCILIATION COMPLETE" if not problems else "U13 RECONCILIATION INCOMPLETE - send this output")
    t1.private_write(os.path.join(outdir, "report.txt"), "\n".join(t1.REPORT) + "\n")
    t1.private_write(os.path.join(outdir, "report.json"), json.dumps(rep, indent=1, sort_keys=True, default=str) + "\n")
    print(f"saved: {outdir}/")
    sys.exit(0 if not problems else 1)


if __name__ == "__main__":
    main()
