#!/usr/bin/env python3
"""Mutation run for redis-rotate.py: each mutant disables or alters one guard; redis-rotate-selftest.py must fail
(killed). Each mutant runs in its own temporary copy of the tool and the self-test; nothing else is touched.
Mutants marked EQUIVALENT are defense-in-depth guards that no reachable fault exercises; they are listed, not run.

  python3 -I redis-rotate-mutants.py
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL, SELFTEST = "redis-rotate.py", "redis-rotate-selftest.py"

MUTANTS = [
    ("backup-window", 'not (t.hour == 3 or (t.hour == 4 and t.minute < 30))', 'True'),
    ("env-matches-redis", 'check("the env file\'s value is the one redis runs with", bool(red) and env_of(red).get(KEY) == value)',
     'check("the env file\'s value is the one redis runs with", True)'),
    ("model-value-outside-env", 'bool(value) and value not in json.dumps(outside)', 'True'),
    ("model-fix-command", '"/tmp/redis-auth.conf" in json.dumps(rs.get("command"))', 'True'),
    ("model-consumers", 'mc == want, ', 'True, '),
    ('image-identity', '(d.get("Config") or {}).get("Image") == ref and image_id(ref) == d.get("Image")', 'True'),
    ('build-only-implicit-ref', 'or f"{PROJECT}-{svc}"', 'or ""'),
    ('container-runs-model-ref', '(d.get("Config") or {}).get("Image") == ref and image_id', 'image_id'),
    ('dockerd-debug-off', 'rc == 0 and out.decode().strip() == "false"', 'True'),
    ('pre-aggregate-health', 'the baseline for the post-check", code == "200"', 'the baseline for the post-check", True'),
    ('post-aggregate-health', 'which includes Redis; review T7)", code == "200"', 'which includes Redis; review T7)", True'),
    ('carriers-substring', 'if value in v)     # embedded too', 'if v == value)     # embedded too'),
    ('redis-accepts-new-before-consumer', '(a restart would have dropped it; review T5)", ping(rname, new))', '(a restart would have dropped it; review T5)", True)'),
    ('probe-write-stops', '    if not check("probe key written with the new value (redis still accepts it)", rc == 0 and "OK" in out):\n        say("  STOP before redis was recreated: the old value still works. Run `rollback --yes`.")\n        return finish("rotate", rec)\n', '    check("probe key written with the new value (redis still accepts it)", rc == 0 and "OK" in out)\n'),
    ('progress-before-acl', '    rec["phase"] = "A"\n    save_progress(rec)                       # before the ACL command', '    rec["phase"] = "A"                       # before the ACL command'),
    ('rollback-live-state', '\n            or env_of(parkio(s, ds) or {}).get(CONSUMER_KEY) != old]', ']'),
    ('rollback-backup-required', 'if not check("the rotation\'s backup exists and is readable", old_data is not None):', 'if False:'),
    ('rollback-end-state-consumers', 'bool(d) and env_of(d).get(CONSUMER_KEY) == old and health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc))', 'True)'),
    ('rollback-end-state-redis-refuses', 'check("redis refuses the rotation\'s value", not ping(rname, current))', 'check("redis refuses the rotation\'s value", True)'),
    ('rollback-marks-rolled-back', '        prog["phase"] = "rolled-back"\n', ''),
    ('redact-older-attempts', 'for path in REDACT_FILES + attempts:', 'for path in REDACT_FILES:'),
    ('redact-oserror-recorded', '        fd, tmp = tempfile.mkstemp(prefix=".rrt-redact-", dir=os.path.dirname(path))\n    except OSError as e:', '        fd, tmp = tempfile.mkstemp(prefix=".rrt-redact-", dir=os.path.dirname(path))\n    except ZeroDivisionError as e:'),
    ("live-carriers", 'live == want_live and len(want_live) == len(CONSUMERS) + 1', 'True'),
    ("consumer-at-model-hash", 'bool(d) and health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc),',
     'bool(d) and health(d) == "healthy",'),
    ("redis-accepts-current", 'check("redis accepts the current value", bool(red) and ping(red.get("Name", "").lstrip("/"), value or ""))',
     'check("redis accepts the current value", True)'),
    ("pre-age", ' and age is not None and 0 <= age <= PRE_MAX_AGE', ''),
    ("env-unchanged-since-pre", 'hashlib.sha256(data).hexdigest() == pre.get("envSha256")', 'True'),
    ("containers-unchanged-since-pre", 'bool(pre) and baseline(ds) == pre.get("baseline")', 'bool(pre)'),
    ("acl-setuser-ok", 'check("ACL SETUSER default >new: OK", rc == 0 and "OK" in out)', 'check("ACL SETUSER default >new: OK", True)'),
    ("preflight", 'ok &= check("the release preflight still passes", preflight_ok())', 'ok &= check("the release preflight still passes", True)'),
    ("hash-set", 'changed == sorted(CONSUMERS + (REDIS,))', 'True'),
    ("restore-env-on-B-failure", '    if not ok:\n        write_atomically(data, st)\n', '    if not ok:\n'),
    ("consumer-new-healthy", 'good = check(f"{svc}: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",',
     'good = check(f"{svc}: wrapper exit 0, a new healthy container", rc == 0 and bool(d),'),
    ("consumer-new-value", 'bool(d) and env_of(d).get(CONSUMER_KEY) == new and lab.get(', 'bool(d) and lab.get('),
    ("consumer-others-unchanged", 'good &= check(f"{svc}: every other container unchanged", not others, ", ".join(others))',
     'good &= check(f"{svc}: every other container unchanged", True)'),
    ('stop-before-redis', '        if not good:\n            say("  STOP before redis was recreated: the old value still works. Run `rollback --yes`.")\n            return finish("rotate", rec)\n', '        if not good:\n            say("  STOP before redis was recreated: the old value still works. Run `rollback --yes`.")\n'),
    ("consumer-order", 'CONSUMERS = ("gamification-service", "parking-service", "auth-service", "gateway-service")',
     'CONSUMERS = ("gateway-service", "auth-service", "parking-service", "gamification-service")'),
    ("redis-new-healthy", 'check("redis: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",',
     'check("redis: wrapper exit 0, a new healthy container", rc == 0 and bool(d),'),
    ("redis-model-hash", 'lab.get("com.docker.compose.config-hash") == (model_hashes() or {}).get(REDIS) and\n', 'True and\n'),
    ("redis-same-image", ' and\n          d.get("Image") == (before or {}).get("Image"))', ')'),
    ("redis-new-works", 'check("redis: the new value works", ping(rname, new))', 'check("redis: the new value works", True)'),
    ("redis-old-refused", 'check("redis: the old value is refused", not ping(rname, old))', 'check("redis: the old value is refused", True)'),
    ("redis-probe-key", 'check("redis: the probe key survived (AOF)", marker in out)', 'check("redis: the probe key survived (AOF)", True)'),
    ("redis-inspect-leak", 'carriers(new, [d]) == {rname: [KEY]} and not carriers(old, [d])', 'True'),
    ("redis-argv-leak", 'rc == 0 and new.encode() not in top and old.encode() not in top', 'True'),
    ("consumer-auth-errors", ' and not AUTH_ERRORS.search(logs + lerr)', ''),
    ("final-others-unchanged", 'check("every other container unchanged", not others, ", ".join(others))', 'check("every other container unchanged", True)'),
    ("rollback-phase-guard", 'phase in ("A", "B", "C")', 'True'),
    ('rollback-env-restore', '        write_atomically(old_data, os.stat(ENV_FILE))\n', ''),
    ('rollback-consumers', '    for svc in todo:\n', '    for svc in []:\n'),
    ("rollback-resetpass", 'f"ACL SETUSER default resetpass >{old}\\n"', 'f"ACL SETUSER default >{old}\\n"'),
    ("child-env-allowlist", 'env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}', 'env = dict(os.environ)'),
    ("auth-as-argument", 'run(["docker", "exec", "-i", "-e", "REDISCLI_AUTH", name, "redis-cli"], extra_env=',
     'run(["docker", "exec", "-i", "-e", "REDISCLI_AUTH", name, "redis-cli", "-a", password], extra_env='),
    ("checkout-at-r1", 'head == R1 and clean', 'clean'),
    ("checkout-branch-tip", 'rc == 0 and tip.strip() == r2', 'rc == 0'),
    ("r2-parent", 'parents.split()[1:] != [R1]', 'False'),
    ("r2-only-compose", 'changed != [COMPOSE_FILE]', 'False'),
    ("r2-fix-blob", 'blob.strip() != FIX_COMPOSE_BLOB', 'False'),
    ("redact-needs-passing-rotation", 'bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done" and\n                 rot.get("stamp") == (prog or {}).get("stamp")', 'True'),
    ("redact-old-invalid", 'bool(old) and bool(new) and old != new and bool(red) and not ping(rname, old) and ping(rname, new)', 'True'),
    ("redact-live-env-guard", 'if os.path.realpath(path) == os.path.realpath(ENV_FILE):', 'if False:'),
    ("redact-regular-own-file", 'if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():', 'if False:'),
    ("redact-holds-new", 'if new.encode() in data:', 'if False:'),
    ("redact-missing-ok", 'return True, "missing; nothing to redact"', 'return False, "missing"'),
    ("redact-keeps-mode", '            os.fchmod(fh.fileno(), st.st_mode & 0o7777)\n            os.fchown(fh.fileno(), st.st_uid, st.st_gid)\n        os.utime(tmp',
     '            os.fchown(fh.fileno(), st.st_uid, st.st_gid)\n        os.utime(tmp'),
    ("redact-keeps-mtime", '        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))\n', ''),
    ("redact-scan", 'holds the old value", not left, ", ".join(left))', 'holds the old value", True)'),
    ("redact-backup-last", '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n        return finish("redact", rec)\n',
     '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n'),
    ("redact-already-complete", 'if old == MARKER:', 'if False:'),
]
EQUIVALENT = [
    ("rollback-moving", "the live-state rule recreates every consumer holding the new value, which covers the moving one"),
    ("placeholder-redraw", "a random value matches a placeholder pattern about once in 25,000 draws; the redraw cannot be observed"),
    ("new-value-alphabet", "secrets.token_urlsafe(32) always matches [A-Za-z0-9_-]{43}; the check documents the #344 R1 condition"),
    ("record-secret-guard", "finish() refuses a record containing a value; no record path carries one"),
    ("scrub", "say() scrubs known values; nothing the tool prints contains one"),
    ("backup-identical", "the backup is written from the bytes just read; a mismatch needs a failing disk"),
    ("only-key-changed", "with_value() changes only the key's line; the check guards against a future edit of it"),
    ("rotate-model-new-value", "pre already refuses a model that interpolates the value outside `environment`"),
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
