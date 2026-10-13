#!/usr/bin/env python3
"""Mutation run for gis-rotate.py: each mutant disables or alters one guard; gis-rotate-selftest.py must fail
(killed). Each mutant runs in its own temporary copy of the tool and the self-test; nothing else is touched.
Mutants marked EQUIVALENT are defense-in-depth guards that no reachable fault exercises; they are listed, not run.

  python3 -I gis-rotate-mutants.py
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL, SELFTEST = "gis-rotate.py", "gis-rotate-selftest.py"

MUTANTS = [
    ('host-guard', 'check(f"host {HOST}", socket.gethostname() == HOST', 'check(f"host {HOST}", True'),
    ('dockerd-debug-off', 'rc == 0 and out.decode().strip() == "false", out.decode().strip())', 'True, out.decode().strip())'),
    ('release-at-r2', 'head == R2 and not status.strip()', 'not status.strip()'),
    ('backup-window', 'not (t.hour == 3 or (t.hour == 4 and t.minute < 30))', 'True'),
    ('env-secret-and-empty-accepted', 'len(cur) >= 32 and not ev.get(ACCEPTED))', 'len(cur) >= 32)'),
    ('model-carriers', 'carriers_model == sorted(CONSUMERS) and all(model_env(m, s).get(SECRET) == cur for s in CONSUMERS)', 'True'),
    ('live-carriers', 'live == want and len(want) == len(CONSUMERS) and not outside', 'True'),
    ('live-outside-env', ' and not outside, ", ".join(live))', ', ", ".join(live))'),
    ('pre-model-hash', 'health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc)\n', 'health(d) == "healthy"\n'),
    ('image-identity', '              and (d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image"), health(d)', '              , health(d)'),
    ('probe-discriminates', 'bool(port) and accepted(svc, port, cur) and refused(svc, port, wrong)', 'True'),
    ('pre-public', '    check("the public API answers 200 through the gateway", public_ok())\n    rec["baseline"]', '    check("the public API answers 200 through the gateway", True)\n    rec["baseline"]'),
    ('inbox-overlay-detected', '_OVERLAYS = ["-f", INBOX_OVERLAY] if any(', '_OVERLAYS = [] if any('),
    ('inbox-overlay-always', ' else []\n    return _OVERLAYS', ' else ["-f", INBOX_OVERLAY]\n    return _OVERLAYS'),
    ('pre-age', ' and age is not None and 0 <= age <= PRE_MAX_AGE', ''),
    ('env-unchanged-since-pre', 'hashlib.sha256(data).hexdigest() == pre.get("envSha256")', 'True'),
    ('containers-unchanged-since-pre', 'bool(pre) and baseline(inspect_all()) == pre.get("baseline")', 'bool(pre)'),
    ('preflight', 'ok &= check(f"{phase}: the release preflight passes", preflight_ok())', 'ok &= check(f"{phase}: the release preflight passes", True)'),
    ('hash-set', 'changed == sorted(CONSUMERS)', 'True'),
    ('restore-env-on-failure', '    if not ok:\n        write_atomically(ENV_FILE, data, st)\n', '    if not ok:\n'),
    ('recreate-new-healthy', 'rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy"\n                 and lab.get', 'rc == 0 and bool(d)\n                 and lab.get'),
    ('recreate-expected-env', 'all(env.get(k, "") == v for k, v in expect.items()))', 'True)'),
    ('recreate-probes', 'bool(port) and all(accepted(svc, port, v) for _, v in accept) and all(refused(svc, port, v) for _, v in refuse))', 'True)'),
    ('recreate-refuses-old', ' and all(refused(svc, port, v) for _, v in refuse))', ')'),
    ('recreate-others-unchanged', 'good &= check(f"{rec[\'phase\']} {svc}: every other container unchanged", not others', 'good &= check(f"{rec[\'phase\']} {svc}: every other container unchanged", True'),
    ('stop-on-recreate-failure-A', '        if not recreate(svc, base, moved, {SECRET: old, ACCEPTED: new}, [("old", old), ("new", new)], [], m, rec):\n            say("  STOP: run `rollback --yes`")\n            return finish("rotate", rec)\n', '        if not recreate(svc, base, moved, {SECRET: old, ACCEPTED: new}, [("old", old), ("new", new)], [], m, rec):\n            say("  STOP: run `rollback --yes`")\n'),
    ('phase-a-accepts-new', 'set_env(data, {ACCEPTED: new}, st, rec, "A")', 'set_env(data, {ACCEPTED: ""}, st, rec, "A")'),
    ('phase-b-keeps-old-accepted', '{SECRET: new, ACCEPTED: old}, st, rec, "B")', '{SECRET: new, ACCEPTED: ""}, st, rec, "B")'),
    ('b-public', 'if not check("B: the public API answers 200 through the gateway (it sends the new value)", public_ok()):', 'if not check("B: the public API answers 200 through the gateway (it sends the new value)", True):'),
    ('rollback-phase-guard', 'phase in ("A", "B", "C")', 'True'),
    ('rollback-backup-required', 'if not check("the rotation\'s backup exists and is readable", old_data is not None):', 'if False:'),
    ('rollback-env-restore', '        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))\n', ''),
    ('rollback-live-state', '\n            or any(env_of(parkio(s, ds) or {}).get(k, "") != v for k, v in want.items())]', ']'),
    ('rollback-end-state', 'bool(d) and health(d) == "healthy" and all(env_of(d).get(k, "") == v for k, v in want.items()))', 'True)'),
    ('rollback-accepts-original', 'bool(port) and accepted(svc, port, want[SECRET]))', 'True)'),
    ('rollback-marks-rolled-back', '        prog["phase"] = "rolled-back"\n', ''),
    ('redact-needs-passing-rotation', 'bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"\n                 and rot.get("stamp") == (prog or {}).get("stamp")', 'True'),
    ('redact-live-env-guard', 'if os.path.realpath(path) == os.path.realpath(ENV_FILE):', 'if False:'),
    ('redact-holds-new', 'if new.encode() in data:', 'if False:'),
    ('redact-missing-ok', 'return True, "missing; nothing to redact"', 'return False, "missing"'),
    ('redact-keeps-mtime', '        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))\n', ''),
    ('redact-scan', 'holds the old value", not left, ", ".join(left))', 'holds the old value", True)'),
    ('redact-backup-last', '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n        return finish("redact", rec)\n', '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n'),
    ('redact-already-complete', 'if old == MARKER:', 'if False:'),
    ('probe-header-on-stdin', 'run(["docker", "exec", "-i", gw, "curl", "-s", "-m", "10", "-H", "@-", "-w", "\\n%{http_code}", url],\n                     stdin=f"X-Gateway-Auth: {value}\\n".encode(), timeout=60)', 'run(["docker", "exec", "-i", gw, "curl", "-s", "-m", "10", "-H", f"X-Gateway-Auth: {value}", "-w", "\\n%{http_code}", url],\n                     stdin=b"", timeout=60)'),
    ('child-env-allowlist', 'env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}', 'env = dict(os.environ)'),
]
EQUIVALENT = [
    ('backup-identical', 'the backup is written from the bytes just read; a mismatch needs a failing disk'),
    ('only-keys-changed', 'with_values() changes only the listed lines; the check guards against a future edit of it'),
    ('final-no-old-holder', 'every C recreate already checks the expected values, so a holder of the old value fails earlier'),
    ('final-model-hash', "every recreate already checks the model's hash"),
    ('record-secret-guard', 'finish() refuses a record containing a value; no record path carries one'),
    ('scrub', 'say() scrubs known values; nothing the tool prints contains one'),
    ('placeholder-redraw', 'a random value matches a placeholder pattern about once in 25,000 draws'),
]


def run_one(mutant):
    name, old, new = mutant
    tmp = tempfile.mkdtemp(prefix="rrt-mut-")
    try:
        for f in (TOOL, SELFTEST):
            shutil.copy2(os.path.join(HERE, f), os.path.join(tmp, f))
        path = os.path.join(tmp, TOOL)
        text = open(path, encoding="utf-8").read()
        if text.count(old) != 1:
            return name, f"ANCHOR x{text.count(old)}"
        open(path, "w", encoding="utf-8").write(text.replace(old, new))
        p = subprocess.run([sys.executable, "-I", os.path.join(tmp, SELFTEST)], capture_output=True, text=True, timeout=900)
        fails = [l[5:] for l in p.stdout.splitlines() if l.startswith("FAIL ")]
        return name, ("killed: " + "; ".join(fails[:2])[:150]) if p.returncode != 0 else "SURVIVED"
    except subprocess.TimeoutExpired:
        return name, "killed: timeout"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(run_one, MUTANTS))
    for name, outcome in results:
        print(f"{name:34} {outcome}")
    for name, why in EQUIVALENT:
        print(f"{name:34} EQUIVALENT (not run): {why}")
    killed = sum(1 for _, o in results if o.startswith("killed"))
    print(f"=== {killed}/{len(MUTANTS)} killed, {len(MUTANTS) - killed} survived or broken; {len(EQUIVALENT)} equivalent listed ===")
    return 0 if killed == len(MUTANTS) else 1


if __name__ == "__main__":
    sys.exit(main())
