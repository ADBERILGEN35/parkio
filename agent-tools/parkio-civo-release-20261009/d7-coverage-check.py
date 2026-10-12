#!/usr/bin/env python3
"""D7 image coverage check (owner request 2026-10-12): what "targets Java (0)" in D7's scan lines means, and proof from
saved reports that the packaged Java dependencies of the six final images were scanned. Changes nothing in the deployment.

Run as civo on parkio-civo-prod:
  python3 -I ~/parkio-release-20261009/tools/d7-coverage-check.py

T1 v4 (checksum-verified) is loaded as a module, so every scanner command is T1's own: the pinned Trivy with civo's uid and
the release's .trivyignore.yaml.
A. Identity: T1's state names the D7 run; its record (steps/D7-<stamp>.json) is PASS with the six built image ids. Each
   release tag parkio/<service>:sha-f888a81b still names that image, and each service's running container runs it.
B. Databases: downloaded into a fresh cache ~/parkio-release-20261009/trivy-cache-coverage (mode 700) with T1's command;
   D7's freshness gates.
C. For each of the six, by its release tag:
   1. T1's own D7 gate again (trivy_scan): CRITICAL fixed, and HIGH/CRITICAL fixed in libraries.
   2. A full inventory of the same image: --list-all-pkgs, every severity, fixed and unfixed, --show-suppressed.
   3. Gates on the inventory:
      - a Java target with at least 50 packages read from the JAR files inside the application jar (app.jar/BOOT-INF/lib);
      - spring-core and spring-boot among them;
      - the OS packages scanned;
      - no usr/bin/pebble target;
      - the policy recomputed from the inventory gives the same pass as T1's gate.
      Shown, not gated: key dependency versions (jackson-databind, kafka-clients, lz4-java, ...), every vulnerability by
      class, severity and status, and the findings the release's ignore file suppresses, by ID.
D. What "targets Java (0)" means: T1's library scan lists each scanned target with the number of findings that fail the
   policy. "Java (0)" is the target holding every package read from JAR files, with 0 failing findings. The inventory gives
   how many packages that target holds. An exit code of 0 alone would not prove this: an image without JAR files also
   passes the gates (the self-test shows it).
Writes ~/parkio-release-20261009/coverage-<stamp>/ (dir 700, files 600):
- <service>.json: the inventory, reduced to targets, packages, vulnerabilities and suppressed findings; no image config;
- summary.json and summary.txt.
The cache is removed at the end. No build, no tag, no container started or stopped; scanner containers are throwaway.
Exit 0 when every gate passes; 1 otherwise.
"""
import collections
import hashlib
import importlib.util
import json
import os
import sys

T1_PATH = os.environ.get("D7C_T1", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "tools",
                                                 "t1-release-step.py"))
T1_SHA = os.environ.get("D7C_T1_SHA256", "01ed008c8ef2268381fcb238025c589b3b0e085f4e8366862a459372c536236e")  # T1 v4
SERVICES = os.environ.get("D7C_SERVICES", "").split()           # self-test only: a subset of the six
REF_TEMPLATE = os.environ.get("D7C_REF", "")                     # self-test only, e.g. "local/{svc}:test"
CACHE = os.environ.get("D7C_CACHE", "")                          # self-test only: a shared cache ...
KEEP_CACHE = os.environ.get("D7C_KEEP_CACHE") == "1"             # ... kept between runs
MIN_JAVA = 50
KEY = ["org.springframework:spring-core", "org.springframework.boot:spring-boot",
       "com.fasterxml.jackson.core:jackson-databind", "org.apache.kafka:kafka-clients", "at.yawk.lz4:lz4-java",
       "org.postgresql:postgresql", "org.flywaydb:flyway-core", "org.apache.tomcat.embed:tomcat-embed-core",
       "io.netty:netty-codec-http"]


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def fixed(v):
    return v.get("Status") == "fixed"


def analyse(rep):
    results = rep.get("Results") or []
    os_t = [t for t in results if t.get("Class") == "os-pkgs"]
    jar_t = [t for t in results if t.get("Type") == "jar"]
    jar_pkgs = [p for t in jar_t for p in t.get("Packages") or []]
    nested = [p for p in jar_pkgs if "/BOOT-INF/lib/" in (p.get("FilePath") or "")]
    versions = {}
    for p in jar_pkgs:
        versions.setdefault(p.get("Name"), set()).add(p.get("Version"))
    counts = collections.OrderedDict()
    for t in results:
        c = collections.Counter(f"{v.get('Severity')}/{v.get('Status')}" for v in t.get("Vulnerabilities") or [])
        counts[t.get("Target")] = dict(sorted(c.items()))
    suppressed = sorted({(m.get("Finding") or {}).get("VulnerabilityID") or "?"
                         for t in results for m in t.get("ExperimentalModifiedFindings") or []})
    crit = [v for t in results for v in t.get("Vulnerabilities") or [] if v.get("Severity") == "CRITICAL" and fixed(v)]
    lib = [v for t in results if t.get("Class") == "lang-pkgs" for v in t.get("Vulnerabilities") or []
           if v.get("Severity") in ("HIGH", "CRITICAL") and fixed(v)]
    return {
        "targets": [f"{t.get('Target')} [{t.get('Class')}/{t.get('Type')}]" for t in results],
        "osPackages": sum(len(t.get("Packages") or []) for t in os_t),
        "osTarget": os_t[0].get("Target") if os_t else None,
        "javaPackages": len(jar_pkgs),
        "javaFromBootInfLib": len(nested),
        "javaJarFiles": len({p.get("FilePath") for p in jar_pkgs}),
        "pebbleTarget": any((t.get("Target") or "").endswith("usr/bin/pebble") for t in results),
        "keyVersions": {k: sorted(versions[k]) for k in KEY if k in versions},
        "vulnerabilities": counts,
        "suppressed": suppressed,
        "policyCriticalFixed": sorted({f"{v.get('VulnerabilityID')} {v.get('PkgName')}" for v in crit}),
        "policyLibraryFixed": sorted({f"{v.get('VulnerabilityID')} {v.get('PkgName')}" for v in lib}),
    }


def reduced(rep):
    meta = rep.get("Metadata") or {}
    out = {"ArtifactName": rep.get("ArtifactName"), "CreatedAt": rep.get("CreatedAt"),
           "Metadata": {k: meta.get(k) for k in ("ImageID", "RepoTags", "RepoDigests", "OS", "DiffIDs")}, "Results": []}
    for t in rep.get("Results") or []:
        out["Results"].append({
            "Target": t.get("Target"), "Class": t.get("Class"), "Type": t.get("Type"),
            "Packages": [{k: p.get(k) for k in ("Name", "Version", "FilePath")} for p in t.get("Packages") or []],
            "Vulnerabilities": [{k: v.get(k) for k in ("VulnerabilityID", "PkgName", "InstalledVersion", "FixedVersion",
                                                       "Status", "Severity", "PkgPath")}
                                for v in t.get("Vulnerabilities") or []],
            "Suppressed": [{"VulnerabilityID": (m.get("Finding") or {}).get("VulnerabilityID"), "Status": m.get("Status"),
                            "Source": m.get("Source"), "Statement": (m.get("Statement") or "")[:300]}
                           for m in t.get("ExperimentalModifiedFindings") or []]})
    return out


def main():
    try:
        actual = sha256_file(T1_PATH)
    except OSError:
        actual = "missing"
    if actual != T1_SHA:
        print(f"STOP: the installed T1 ({T1_PATH}) is not T1 v4 {T1_SHA[:12]} (found {actual[:12]}); nothing was done")
        sys.exit(1)
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("t1", T1_PATH)
    t1 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(t1)
    say, gate = t1.say, t1.gate
    services = SERVICES or list(t1.BUILT)
    outdir = os.path.join(t1.OUT, f"coverage-{t1.STAMP}")
    summary = {"tool": "d7-coverage-check", "pin": t1.PIN, "t1Sha256": T1_SHA, "stamp": t1.STAMP, "services": {}}
    say(f"D7 image coverage check | stamp {t1.STAMP} | T1 v4 {T1_SHA[:12]} | read-only for the deployment")

    say("")
    say("A. Identity: the D7 record, the release tags and the running containers")
    state = t1.load_state()
    d7 = (state.get("steps") or {}).get("D7") or {}
    rec = {}
    try:
        rec = json.load(open(os.path.join(t1.OUT, "steps", f"D7-{d7.get('stamp')}.json"), encoding="utf-8"))
    except (OSError, ValueError):
        pass
    built = rec.get("builtImages") or {}
    gate("T1 state D7 ok; its record PASS with the built image ids", d7.get("status") == "ok" and rec.get("result") == "PASS"
         and all(built.get(s) for s in services), f"D7 {d7.get('status', '-')} stamp {d7.get('stamp', '-')}, record "
         f"{rec.get('result', 'missing')}, {len(built)} built image ids")
    refs = {}
    for svc in services:
        ref = REF_TEMPLATE.format(svc=svc) if REF_TEMPLATE else f"parkio/{svc}:sha-{t1.PIN[:8]}"
        refs[svc] = ref
        iid = t1.image_id(ref)
        c = t1.container(svc) or {}
        gate(f"{svc}: {ref} = the image D7 built = the running container's image",
             bool(iid) and iid == built.get(svc) and c.get("image") == iid and c.get("status") == "running",
             f"tag {t1.short(iid)}, built {t1.short(built.get(svc))}, running {t1.short(c.get('image'))} {c.get('status', '-')}")
    if any(not ok for _, ok in t1.GATES):
        return done(t1, summary, outdir, None, 1)

    say("")
    cache = CACHE or os.path.join(t1.OUT, "trivy-cache-coverage")
    say(f"B. Databases in a fresh scanner cache {cache} (T1's command)")
    os.makedirs(cache, mode=0o700, exist_ok=True)
    os.chmod(cache, 0o700)
    for flag, name in (("--download-db-only", "vulnerability DB"), ("--download-java-db-only", "Java DB")):
        rc, _, err = t1.run(t1.trivy_base(cache) + [flag], timeout=1800)
        gate(f"{name} downloaded", rc == 0, f"exit {rc}" + (f"; {t1.first_error(err)}" if rc else ""))
    meta = t1.trivy_db_meta(cache)
    summary["trivyDb"] = meta
    for name, ok, detail in t1.db_current(meta):
        gate(f"D7's freshness gate: {name}", ok, detail)
    if any(not ok for _, ok in t1.GATES):
        return done(t1, summary, outdir, cache, 1)

    os.makedirs(outdir, mode=0o700, exist_ok=True)
    for svc in services:
        ref = refs[svc]
        say("")
        say(f"C. {svc} ({ref})")
        crit, lib, targets, note = t1.trivy_scan(ref, cache)
        gate(f"{svc}: T1's D7 gate again (CRITICAL fixed; HIGH/CRITICAL fixed libraries)", crit and lib,
             f"targets {', '.join(targets) or '-'}" + (f"; {note}" if note else ""))
        rc, rep, err = t1.trivy_json(["--list-all-pkgs", "--show-suppressed", "--format", "json", ref], cache)
        if not gate(f"{svc}: full inventory report written", rep is not None,
                    f"exit {rc}" + ("" if rep is not None else f"; {t1.first_error(err)}")):
            continue
        a = analyse(rep)
        t1.private_write(os.path.join(outdir, f"{svc}.json"), json.dumps(reduced(rep), indent=1, sort_keys=True) + "\n")
        summary["services"][svc] = dict(a, ref=ref, imageId=t1.image_id(ref),        # docker's id, as T1 records it
                                        trivyImageId=(rep.get("Metadata") or {}).get("ImageID"), gateCritical=crit,
                                        gateLibrary=lib, gateTargets=targets)
        say(f"  targets: {'; '.join(a['targets'])}")
        gate(f"{svc}: Java target scanned: {a['javaPackages']} packages, {a['javaFromBootInfLib']} of them from "
             f"{a['javaJarFiles']} JAR files inside app.jar/BOOT-INF/lib", a["javaFromBootInfLib"] >= MIN_JAVA,
             f"at least {MIN_JAVA} required")
        gate(f"{svc}: spring-core and spring-boot among them", all(k in a["keyVersions"] for k in KEY[:2]),
             ", ".join(f"{k.split(':')[1]} {'/'.join(v)}" for k, v in a["keyVersions"].items()))
        gate(f"{svc}: OS packages scanned ({a['osTarget'] or 'no OS target'})", a["osPackages"] > 0,
             f"{a['osPackages']} packages")
        gate(f"{svc}: no usr/bin/pebble target", not a["pebbleTarget"])
        same = (not a["policyCriticalFixed"] and not a["policyLibraryFixed"]) == (crit and lib)
        gate(f"{svc}: the policy recomputed from the inventory gives T1's result", same and crit and lib,
             f"critical fixed {len(a['policyCriticalFixed'])}, library HIGH/CRITICAL fixed {len(a['policyLibraryFixed'])}"
             + (f": {', '.join((a['policyCriticalFixed'] + a['policyLibraryFixed'])[:8])}" if not same or not (crit and lib)
                else ""))
        for target, c in a["vulnerabilities"].items():
            say(f"  vulnerabilities in {target}: {', '.join(f'{k} {n}' for k, n in c.items()) or 'none'}")
        say(f"  suppressed by the release's ignore file: {', '.join(a['suppressed']) or 'none'}")

    say("")
    say("D. Meaning of \"targets Java (0)\" in D7's scan lines")
    say("  T1's library scan lists every scanned target with the number of findings that fail the policy. \"Java\" is the")
    say("  target that holds every package read from JAR files. (0) means none of them fails the policy (HIGH/CRITICAL")
    say("  with a fixed version, after the ignore file). It does not mean that no Java packages were found: their number")
    say("  per image is in the C lines above and in the saved inventories.")
    return done(t1, summary, outdir, cache, 1 if any(not ok for _, ok in t1.GATES) else 0)


def done(t1, summary, outdir, cache, code):
    if cache and not KEEP_CACHE:
        t1.run(["rm", "-rf", cache])
        t1.say("  scanner cache removed")
    failed = [n for n, ok in t1.GATES if not ok]
    t1.say("")
    t1.say(f"gates passed {len(t1.GATES) - len(failed)}/{len(t1.GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    t1.say("D7 COVERAGE CHECK PASS - the packaged Java dependencies and OS packages of the six images were scanned and pass"
           if code == 0 else "D7 COVERAGE CHECK NOT COMPLETE - do not run E; send this output")
    summary.update(gates={n: ok for n, ok in t1.GATES}, result="PASS" if code == 0 else "FAIL")
    os.makedirs(outdir, mode=0o700, exist_ok=True)
    t1.private_write(os.path.join(outdir, "summary.json"), json.dumps(summary, indent=2, sort_keys=True) + "\n")
    t1.private_write(os.path.join(outdir, "summary.txt"), "\n".join(t1.REPORT) + "\n")
    print(f"saved: {outdir}/")
    sys.exit(code)


if __name__ == "__main__":
    main()
