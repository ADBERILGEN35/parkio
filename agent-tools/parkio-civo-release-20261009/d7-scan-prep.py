#!/usr/bin/env python3
"""D7 scan preparation (owner request 2026-10-12: confirm the pinned Trivy tool and its databases before D7, or prepare them).

Run as civo on parkio-civo-prod, after T1 v4 is installed:
  python3 -I ~/parkio-release-20261009/tools/d7-scan-prep.py

Every scanner command here is T1's own: the installed T1 is checked against its pinned SHA-256 and loaded as a module, and
its trivy_base / trivy_scan / trivy_db_meta / db_current run exactly as D7 runs them.
A. T1 v4 installed; the release checkout clean at the PIN.
B. The scanner aquasec/trivy@sha256:a8ca2907... (Trivy 0.64.1): present locally, or pulled by that digest - the only pull this
   step makes; then `trivy --version` in a throwaway container.
C. The release's .trivyignore.yaml (read-only): SHA-256, entries and expiry dates.
D. The two databases, downloaded into T1's D7 scanner cache ~/parkio-release-20261009/trivy-cache (mode 700) with T1's
   command (--download-db-only, --download-java-db-only), and D7's freshness gates on them (vulnerability DB updated within
   48 h, Java DB within 7 days).
E. D7's abort path and scan path: the six rollback tags parkio-<service>:rollback-pre-22f96990 are present (D7 points
   latest back at them when a check fails); then one end-to-end scan with T1's trivy_scan of
   parkio-notification-service:rollback-pre-22f96990 - a host-built image named by a tag, as D7 scans the six - must write
   both reports as civo (docker socket, ignore file, JSON). Its policy result describes the notification-service image that
   runs now and that D7 replaces: shown, not a gate. (A registry-pulled image is not used: on a containerd image store its
   saved index can lack a blob the scanner then cannot read, 2026-10-12.)
F. Free space on / still >= 12 GiB (D7's pre-check).
Changes: at most the scanner pull, and the cache directory with the two databases. D7 reuses it (Trivy does not download a
database again within an hour, or before the database's next update) and removes it after its scans. No build, no tag; no
container of the project started or stopped; scanner containers are throwaway (--rm). T1's state and journal are not
touched. If this step pulled the scanner, D7 keeps it (D7 removes only an image it pulled itself); remove it after E with
`docker image rm aquasec/trivy@sha256:a8ca2907...`.
Exit 0: ready for D7. Exit 1: not ready (a gate failed). Report: ~/parkio-release-20261009/scanprep-<stamp>.txt and .json
(mode 600). Prints no secret.
"""
import hashlib
import importlib.util
import json
import os
import re
import sys

T1_PATH = os.environ.get("D7S_T1", os.path.join(os.path.expanduser("~"), "parkio-release-20261009", "tools",
                                                 "t1-release-step.py"))
T1_SHA = os.environ.get("D7S_T1_SHA256", "01ed008c8ef2268381fcb238025c589b3b0e085f4e8366862a459372c536236e")          # overrides: self-test only
BASELINE_SERVICE = "notification-service"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def ignore_entries(text):
    entries, current = [], None
    for line in text.splitlines():
        m = re.match(r"^\s*-\s*id:\s*(\S+)", line)
        if m:
            current = [m.group(1), "no expiry"]
            entries.append(current)
            continue
        m = re.match(r"^\s*expired_at:\s*(\S+)", line)
        if m and current:
            current[1] = m.group(1)
    return entries


def tree_bytes(path):
    total = 0
    for root, _, files in os.walk(path):
        for f in files:
            try:
                total += os.lstat(os.path.join(root, f)).st_size
            except OSError:
                pass
    return total


def main():
    if "__" in T1_SHA:
        print("STOP: this copy has an unfilled T1 checksum")
        sys.exit(1)
    try:
        actual = sha256_file(T1_PATH)
    except OSError:
        actual = "missing"
    if actual != T1_SHA:
        print(f"STOP: the installed T1 ({T1_PATH}) is not T1 v4 {T1_SHA[:12]} (found {actual[:12]}); nothing was changed")
        sys.exit(1)
    spec = importlib.util.spec_from_file_location("t1", T1_PATH)
    t1 = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(t1)
    say, gate = t1.say, t1.gate
    record = {"tool": "d7-scan-prep", "pin": t1.PIN, "t1Sha256": T1_SHA, "scanner": t1.TRIVY}

    say(f"D7 scan preparation | stamp {t1.STAMP} | release {t1.REL} @ {t1.PIN[:12]} | T1 v4 {T1_SHA[:12]}")
    say("")
    say("A. T1 and the release checkout")
    rc, head = t1.git(t1.REL, "rev-parse", "HEAD")
    rc2, porcelain = t1.git(t1.REL, "status", "--porcelain")
    if not gate("release checkout clean at the PIN", head == t1.PIN and rc2 == 0 and not porcelain,
                f"HEAD {head[:12]}, {len(porcelain.splitlines())} change(s)"):
        return done(t1, record, 1)

    say("")
    say(f"B. Scanner {t1.TRIVY.split('@')[0]} (pinned digest {t1.TRIVY.split('@')[1][7:19]})")
    present = t1.image_id(t1.TRIVY)
    record["scannerPulledByPrep"] = False
    if present:
        gate("scanner present locally by its pinned digest", True, f"image {t1.short(present)}")
    else:
        say(f"  Run: docker pull {t1.TRIVY}")
        rc, _, err = t1.run(["docker", "pull", t1.TRIVY], timeout=1800)
        present = t1.image_id(t1.TRIVY)
        record["scannerPulledByPrep"] = rc == 0 and bool(present)
        if not gate("scanner pulled by its pinned digest", rc == 0 and bool(present),
                    f"exit {rc}" + (f"; {t1.first_error(err)}" if rc else f"; image {t1.short(present)}")):
            return done(t1, record, 1)
    record["scannerImage"] = present
    rc, out, err = t1.run(["docker", "run", "--rm", "--network", "none", t1.TRIVY, "--version"], timeout=300)
    version = next((ln.strip() for ln in out.splitlines() if ln.startswith("Version:")), "")
    record["scannerVersion"] = version
    gate("scanner runs (trivy --version, throwaway container, no network)", rc == 0 and bool(version),
         version or f"exit {rc}; {t1.first_error(err)}")

    say("")
    say("C. The release's .trivyignore.yaml (read-only)")
    ign = os.path.join(t1.REL, ".trivyignore.yaml")
    try:
        text = open(ign, encoding="utf-8").read()
    except OSError:
        text = ""
    entries = ignore_entries(text)
    record["ignoreFile"] = {"sha256": sha256_file(ign) if text else None, "entries": entries}
    gate("release ignore file present", bool(text), f"sha256 {record['ignoreFile']['sha256'][:16]}" if text else "missing")
    for cve, until in entries:
        say(f"  suppressed {cve}: expired_at {until} (Trivy applies it only before that date)")

    say("")
    cache = os.path.join(t1.OUT, "trivy-cache")
    say(f"D. Databases in D7's scanner cache {cache} (T1's command)")
    os.makedirs(cache, mode=0o700, exist_ok=True)
    os.chmod(cache, 0o700)
    for flag, name in (("--download-db-only", "vulnerability DB"), ("--download-java-db-only", "Java DB")):
        say(f"  Run: T1 trivy_base + {flag}")
        rc, _, err = t1.run(t1.trivy_base(cache) + [flag], timeout=1800)
        gate(f"{name} downloaded as civo with T1's scanner command", rc == 0,
             f"exit {rc}" + (f"; {t1.first_error(err)}" if rc else ""))
    meta = t1.trivy_db_meta(cache)
    record["trivyDb"] = meta
    for name, ok, detail in t1.db_current(meta):
        gate(f"D7's freshness gate: {name}", ok, detail)
    record["cacheBytes"] = tree_bytes(cache)
    say(f"  cache {record['cacheBytes'] / 1024 ** 2:.0f} MiB, mode {oct(os.stat(cache).st_mode & 0o777)[2:]}")
    if any(not ok for _, ok in t1.GATES):
        return done(t1, record, 1)

    say("")
    say("E. D7's abort path (rollback tags) and scan path (a host-built image by tag)")
    rollback = {svc: t1.image_id(f"{t1.PROJECT}-{svc}:{t1.ROLLBACK_TAG}") for svc in t1.BUILT}
    record["rollbackTags"] = rollback
    missing = [svc for svc, iid in rollback.items() if not iid]
    if not gate(f"the six rollback tags {t1.PROJECT}-<service>:{t1.ROLLBACK_TAG} present", not missing,
                ", ".join(f"{svc} {t1.short(iid)}" for svc, iid in rollback.items() if iid) + (
                    f"; missing: {', '.join(missing)}" if missing else "")):
        return done(t1, record, 1)
    ref = f"{t1.PROJECT}-{BASELINE_SERVICE}:{t1.ROLLBACK_TAG}"
    say(f"  Run: T1 trivy_scan {ref}")
    crit, lib, targets, note = t1.trivy_scan(ref, cache)
    ran = "wrote no report" not in note
    gate("both scans wrote a report as civo (docker socket, ignore file, JSON)", ran,
         f"targets {', '.join(targets) or '-'}" if ran else note)
    record["baseline"] = {"ref": ref, "critical": crit, "library": lib, "targets": targets, "note": note}
    if ran:
        say(f"  the {BASELINE_SERVICE} image running now (D7 replaces it), with today's databases: critical "
            f"{'pass' if crit else 'FAIL'}, library {'pass' if lib else 'FAIL'}" + (f"; {note}" if note else ""))

    say("")
    say("F. Free space")
    n = t1.free_bytes("/")
    gate("free space on / >= 12 GiB after the downloads (D7's pre-check)", n is not None and n >= t1.MIN_FREE,
         f"{(n or 0) / 1024 ** 3:.1f} GiB")
    if record["scannerPulledByPrep"]:
        say(f"  note: this step pulled the scanner; D7 keeps it. After E: docker image rm {t1.TRIVY}")
    return done(t1, record, 1 if any(not ok for _, ok in t1.GATES) else 0)


def done(t1, record, code):
    failed = [n for n, ok in t1.GATES if not ok]
    t1.say("")
    t1.say(f"gates passed {len(t1.GATES) - len(failed)}/{len(t1.GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    t1.say("D7 SCAN PREP COMPLETE - scanner, current databases and D7's scan and abort paths ready" if code == 0
           else "D7 SCAN PREP NOT COMPLETE - do not start D7; send this output")
    record.update(stamp=t1.STAMP, gates={n: ok for n, ok in t1.GATES}, exit=code)
    base = os.path.join(t1.OUT, f"scanprep-{t1.STAMP}")
    t1.private_write(base + ".txt", "\n".join(t1.REPORT) + "\n")
    t1.private_write(base + ".json", json.dumps(record, indent=2, sort_keys=True) + "\n")
    print(f"saved: {base}.txt / .json")
    sys.exit(code)


if __name__ == "__main__":
    main()
