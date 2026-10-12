#!/usr/bin/env python3
"""U13 reconciliation (Asana 1219002004548841): a dated, read-only comparison of the effective production Compose and every
running container's image with the canonical api file set. Also prints the U12 cutoff values (Asana 1219001760069487).

Run as civo on parkio-civo-prod:
  python3 -I ~/parkio-release-20261009/tools/u13-reconcile.py

Read-only: docker ps / inspect / image inspect, `docker compose config` renders, `git --no-optional-locks status/diff --stat`.
Nothing is created, started, stopped, pulled or tagged, and no file outside the report directory is written. T1 v7
(checksum-verified) is loaded as a module for its renders: the production wrapper's model of the release checkout, and each
container's own recorded Compose inputs (working directory, files, env file) - the reproduction D0.2.2 used.
A. Host: name, date, Docker version; the release checkout (HEAD = PIN, clean); the live checkout /opt/parkio (HEAD, changed
   paths by name and git diff --stat only - no content).
B. Canonical render: the release checkout at the PIN through the production wrapper (docker/compose.production.files and the
   Civo overlay; the waitlist overlay for gateway-service): each service's image reference, build or not, config hash.
C. Every container on the host: name, Compose project and service, image reference, image id, repo digests, OCI revision,
   state/health, its recorded Compose inputs. Each group of recorded inputs is rendered once (read-only); the container's
   config-hash label must equal that render (D0.2.2's self-check).
D. Digest table: each container mapped to exactly one of
     pinned          - the canonical render pins the service by digest and the container runs that image;
     pinned-other    - the canonical render pins it by digest but the container runs a different image;
     release-built   - built on the host from the canonical file set (revision label shown; no registry digest);
     canonical-tag   - the canonical render names a tag, not a digest (unpinned);
     outside         - not part of the api file set (another project, or a service the canonical render lacks): its own
                       reference, pinned by digest or not;
     unknown         - no Compose labels.
   With: config hash equal to the canonical render or not, and the differing keys by name (environment variables by name;
   no value is printed).
E. Effective flags from the env file: keys that look like flags (…_ENABLED, …_FLAG…, FEATURE_…), each as
   true / false / empty / set. No other value.
F. U12 (criterion 5 evidence): the D7 record's end offsets of parkio.dlt.user and parkio.gamification.score before D7 and
   at T_g, per partition.
Report: ~/parkio-release-20261009/reconcile-<stamp>/report.{txt,json} (dir 700, files 600). Exit 0 when the inventory is
complete (every container classified, every render readable); 1 otherwise. Differences are findings, not failures.
"""
import hashlib
import importlib.util
import json
import os
import re
import sys

T1_PATH = os.environ.get("U13R_T1", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "tools",
                                                  "t1-release-step.py"))
T1_SHA = os.environ.get("U13R_T1_SHA256", "2670b41e58e75b8edcfc57c6ad4b4972cc4f3c5e14a45b0b8c7132bf07efe0d4")  # T1 v7
PIN_OVERRIDE = os.environ.get("U13R_PIN", "")                    # self-test only
FLAG = re.compile(r"(_ENABLED$|_FLAG|^FEATURE_|_FEATURE_)")
SKIP_KEYS = {"environment"}


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


def recorded_inputs(t1, c, svc, outdir):
    """T1's recorded_inputs, except that a missing temporary web binding is rebuilt into this report's directory, not into
    T1's records (read-only towards everything else)."""
    lab = c.get("labels") or {}
    files = [f for f in (lab.get("com.docker.compose.project.config_files") or "").split(",") if f]
    env = lab.get("com.docker.compose.project.environment_file") or ""
    inputs = {"workingDir": lab.get("com.docker.compose.project.working_dir") or "", "files": files,
              "envFile": env if env and "," not in env and os.path.isfile(env) else t1.ENV_FILE}
    missing = [f for f in files if not os.path.isfile(f)]
    if svc == "web" and len(missing) == 1 and os.path.basename(missing[0]) == "web-binding.yml":
        rebuilt = os.path.join(outdir, "web-binding-rebuilt.yml")
        t1.private_write(rebuilt, json.dumps({"services": {"web": {"image": c["image"], "pull_policy": "never"}}}) + "\n")
        inputs["files"] = [rebuilt if f == missing[0] else f for f in files]
        inputs["rebuiltWebBinding"] = True
    elif missing:
        inputs["missing"] = [os.path.basename(f) for f in missing]
    return inputs


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
    rep = {"tool": "u13-reconcile", "pin": pin, "stamp": t1.STAMP, "t1Sha256": T1_SHA}
    outdir = os.path.join(t1.OUT, f"reconcile-{t1.STAMP}")
    os.makedirs(outdir, mode=0o700, exist_ok=True)

    say(f"U13 reconciliation | stamp {t1.STAMP} | read-only | canonical = api file set at {pin[:12]}")
    say("")
    say("A. Host")
    rc, host, _ = t1.run(["hostname"])
    rc, dver, _ = t1.run(["docker", "version", "--format", "{{.Server.Version}}"])
    rc, rel_head = t1.git(t1.REL, "rev-parse", "HEAD")
    rc, rel_dirty = t1.git(t1.REL, "status", "--porcelain")
    rc, live_head = t1.git(t1.LIVE, "rev-parse", "HEAD")
    rc, live_status, _ = t1.run(["git", "--no-optional-locks", "-C", t1.LIVE, "status", "--porcelain"])
    rc, live_stat = t1.git(t1.LIVE, "diff", "--stat", "HEAD")
    changed = [ln[3:] for ln in live_status.splitlines() if len(ln) > 3]
    rep["host"] = {"name": host.strip(), "date": t1.now(), "docker": dver.strip(), "releaseHead": rel_head,
                   "releaseClean": not rel_dirty, "liveHead": live_head, "liveChangedPaths": changed,
                   "liveDiffStat": (live_stat.splitlines() or [""])[-1]}
    say(f"  {host.strip()} | {t1.now()} | Docker {dver.strip()}")
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
    rep["canonical"] = {s: {"image": csvc[s].get("image"), "build": bool(csvc[s].get("build")), "hash": chash.get(s)}
                        for s in csvc}

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
        labels = (data.get("Config") or {}).get("Labels") or {}
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
               "ref": (data.get("Config") or {}).get("Image"), "imageId": image_id,
               "repoDigests": idata.get("RepoDigests") or [], "revision": ilabels.get("org.opencontainers.image.revision"),
               "state": state.get("Status"), "health": (state.get("Health") or {}).get("Status"),
               "labelHash": labels.get("com.docker.compose.config-hash")}
        if row["project"] and row["service"]:
            c = {"labels": labels, "image": image_id}
            inputs = recorded_inputs(t1, c, row["service"], outdir)
            key = json.dumps([inputs.get("workingDir"), inputs.get("files"), inputs.get("envFile")])
            if key not in groups and inputs.get("missing"):
                groups[key] = {"inputs": inputs, "model": None, "hashes": {}, "error": None}
            if key not in groups:
                rc1, out1, err1 = t1.raw_compose(inputs, ["config", "--format", "json"], timeout=180)
                rc2, out2, err2 = t1.raw_compose(inputs, ["config", "--hash", "*"], timeout=180)
                try:
                    model = json.loads(out1) if rc1 == 0 else None
                except ValueError:
                    model = None
                hashes = dict(ln.split()[:2] for ln in out2.splitlines() if len(ln.split()) == 2) if rc2 == 0 else {}
                groups[key] = {"inputs": inputs, "model": model, "hashes": hashes,
                               "error": None if model is not None else t1.first_error(err1 or err2)}
            if inputs.get("missing"):
                groups[key]["error"] = None          # recorded files gone: no re-render possible (a finding, not a failure)
            g = groups[key]
            row["inputs"] = {"workingDir": inputs.get("workingDir"), "files": [os.path.basename(f) for f in inputs.get("files") or []],
                             "envFile": os.path.basename(inputs.get("envFile") or ""), "missing": inputs.get("missing")}
            row["recordedHash"] = g["hashes"].get(row["service"])
            row["selfCheck"] = (None if inputs.get("missing")
                                else row["recordedHash"] == row["labelHash"] and row["labelHash"] is not None)
            live_svc = ((g["model"] or {}).get("services") or {}).get(row["service"])
            if g["error"]:
                problems.append(f"render of {row['name']}'s recorded inputs failed: {g['error']}")
        else:
            live_svc = None
            row["selfCheck"] = None
        svc = row["service"]
        canon_svc = csvc.get(svc) if row["project"] == t1.PROJECT else None
        cref = (canon_svc or {}).get("image") or ""
        if not row["project"]:
            cls = "unknown"
        elif canon_svc is None:
            cls = "outside"
        elif "@sha256:" in cref:
            cls = "pinned" if t1.image_id(cref) == image_id else "pinned-other"
        elif canon_svc.get("build"):
            cls = "release-built"
        else:
            cls = "canonical-tag"
        row["class"] = cls
        row["equalsCanonical"] = (row["labelHash"] == chash.get(svc)) if canon_svc is not None else None
        row["differences"] = differences(live_svc, canon_svc) if canon_svc is not None and not row["equalsCanonical"] else []
        if cls == "outside":
            row["ownReference"] = "digest" if "@sha256:" in (row["ref"] or "") else "tag"
        rows.append(row)
    rows.sort(key=lambda r: (r["project"] or "~", r["service"] or r["name"]))
    for r in rows:
        dig = next((d.split("@")[1][7:19] for d in r["repoDigests"]), "-")
        sc = "ok" if r["selfCheck"] else ("NO" if r["selfCheck"] is False else
                                            ("inputs-missing" if (r.get("inputs") or {}).get("missing") else "-"))
        say(f"  {r['name'][:40]:40} {r['class']:13} {(r['state'] or '-')}/{r['health'] or '-'} img {t1.short(r['imageId'])} "
            f"digest {dig} rev {(r['revision'] or '-')[:12]} self-check {sc} "
            f"canonical {'=' if r['equalsCanonical'] else ('-' if r['equalsCanonical'] is None else '≠')}")
        say(f"      ref {(r['ref'] or '-')[:120]}")
        if r["differences"]:
            say(f"      differs from canonical in: {', '.join(r['differences'])[:600]}")
        if r.get("inputs"):
            say(f"      recorded inputs: {r['inputs']['workingDir']} | {', '.join(r['inputs']['files'])} | env {r['inputs']['envFile']}"
                + (f" | missing {r['inputs']['missing']}" if r['inputs'].get('missing') else ""))
    rep["containers"] = rows
    running = {r["service"] for r in rows if r["project"] == t1.PROJECT and r["state"] == "running"}
    rep["canonicalNotRunning"] = sorted(s for s in csvc if s not in running)
    say(f"  canonical services without a running container: {', '.join(rep['canonicalNotRunning']) or 'none'}")
    counts = {}
    for r in rows:
        counts[r["class"]] = counts.get(r["class"], 0) + 1
    rep["counts"] = counts
    say(f"  classes: {', '.join(f'{k} {v}' for k, v in sorted(counts.items()))}")
    if any(r["selfCheck"] is False for r in rows):
        say("  note: a container whose config-hash label differs from the render of its own recorded inputs was created from "
            "a different model than its recorded files give today (see self-check NO)")

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
    try:
        rec = json.load(open(os.path.join(t1.OUT, "steps", f"D7-{d7.get('stamp')}.json"), encoding="utf-8"))
        u12 = rec.get("u12") or {}
    except (OSError, ValueError):
        u12 = {}
    for k in ("dltUserEndBefore", "scoreEndBefore", "Tg", "scoreEndAtTg"):
        say(f"  {k}: {json.dumps(u12.get(k), sort_keys=True)}")
    rep["u12"] = {k: u12.get(k) for k in ("dltUserEndBefore", "scoreEndBefore", "Tg", "scoreEndAtTg")}
    if not u12:
        problems.append("D7 record u12 values not readable")

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
