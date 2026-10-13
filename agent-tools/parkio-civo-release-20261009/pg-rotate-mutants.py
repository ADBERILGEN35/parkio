#!/usr/bin/env python3
"""Mutation run for pg-rotate.py: each mutant disables or alters one guard; pg-rotate-selftest.py must fail
(killed). Each mutant runs in its own temporary copy of the tool and the self-test; nothing else is touched.
Mutants marked EQUIVALENT are defense-in-depth guards that no reachable fault exercises; they are listed, not run.
A mutant counts as killed only when two runs of the self-test fail with at least one failing case in common; a first
failure that does not repeat is reported FLAKY and not counted (load or clock steps on a shared machine). A mutant
that does not compile is reported BROKEN and not counted: a syntax error would fail every case.

  python3 -I pg-rotate-mutants.py
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL, SELFTEST = "pg-rotate.py", "pg-rotate-selftest.py"

MUTANTS = [
    ('host-guard', 'check(f"host {HOST}", socket.gethostname() == HOST', 'check(f"host {HOST}", True'),
    ('dockerd-debug-off', 'rc == 0 and out.decode().strip() == "false", out.decode().strip())', 'True, out.decode().strip())'),
    ('release-at-r2', 'head == R2 and not st.strip()', 'not st.strip()'),
    ('backup-window', 'not (t.hour == 3 or (t.hour == 4 and t.minute < 30))', 'True'),
    ('value-length', 'check(f"the env file\'s {key} is at least 32 characters", len(cur) >= 32)', 'check(f"the env file\'s {key} is at least 32 characters", True)'),
    ('model-carriers', 'in_model == sorted((pg_svc, app_svc)), ", ".join(in_model))', 'True, ", ".join(in_model))'),
    ('live-carriers', 'len(want) == 2 and live == want and not outside', 'True'),
    ('live-outside-env', ' and not outside, ", ".join(live))', ', ", ".join(live))'),
    ('pre-pg-value', 'env_of(pg).get(PG_KEY) == cur and ', ''),
    ('log-statement', 'bool(user) and setting(pg, "log_statement") == "none")', 'True)'),
    ('pre-app-hash', 'label(app, "com.docker.compose.config-hash") == hashes.get(app_svc) and ', ''),
    ('pre-app-image', ' and image_ok(m, app, app_svc)\n', '\n'),
    ('pre-app-health', 'check(f"{app_svc}: its aggregate health answers 200", bool(app) and app_health(app, port) == "200")', 'check(f"{app_svc}: its aggregate health answers 200", True)'),
    ('pre-sessions', 'bool(app) and bool(user) and (fresh_sessions(pg, app) or 0) > 0)', 'True)'),
    ('login-discriminates', 'login(DB, pg, cur) is True and login(DB, pg, wrong) is False', 'login(DB, pg, cur) is True'),
    ('pre-backup-loader', 'value == cur and mode == "docker", f"mode {mode}")', 'True, f"mode {mode}")'),
    ('pre-public', '    check("the public API answers 200 through the gateway", public_ok())\n    rec["baseline"]', '    check("the public API answers 200 through the gateway", True)\n    rec["baseline"]'),
    ('inbox-overlay-detected', '_OVERLAYS = ["-f", INBOX_OVERLAY] if any(', '_OVERLAYS = [] if any('),
    ('inbox-overlay-always', ' else []\n    return _OVERLAYS', ' else ["-f", INBOX_OVERLAY]\n    return _OVERLAYS'),
    ('pre-for-this-db', 'paths = records("pre", DB)', 'paths = records("pre")'),
    ('pre-age', ' and age is not None and -60 <= age <= PRE_MAX_AGE', ''),
    ('env-unchanged-since-pre', 'hashlib.sha256(data).hexdigest() == pre.get("envSha256")', 'True'),
    ('containers-unchanged-since-pre', 'bool(pre) and baseline(inspect_all()) == pre.get("baseline")', 'bool(pre)'),
    ('alter-role', 'bool(pg) and set_role_password(pg, new))', 'True)'),
    ('step1-login', 'login(DB, pg, new) is True and login(DB, pg, old) is False)', 'True)'),
    ('stop-after-role', '        say(f"  STOP: run `rollback --yes --db {DB}`")\n        return finish("rotate", rec)\n\n    say(f"2.', '        say(f"  STOP: run `rollback --yes --db {DB}`")\n\n    say(f"2.'),
    ('preflight', 'good &= check("the release preflight passes", preflight_ok())', 'good &= check("the release preflight passes", True)'),
    ('hash-set', 'changed == sorted((pg_svc, app_svc)), ", ".join(changed))', 'True, ", ".join(changed))'),
    ('stop-after-env', '        say(f"  STOP: run `rollback --yes --db {DB}` (it sets the role back and restores the env file)")\n        return finish("rotate", rec)\n', '        say(f"  STOP: run `rollback --yes --db {DB}` (it sets the role back and restores the env file)")\n'),
    ('app-new-healthy', 'rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy"\n                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(app_svc)', 'True'),
    ('app-new-value', 'good &= check(f"{app_svc}: runs the new value", env_of(d).get(APP_KEY) == new)', 'good &= check(f"{app_svc}: runs the new value", True)'),
    ('app-health', 'good &= check(f"{app_svc}: its aggregate health answers 200", bool(d) and app_health(d, port) == "200")', 'good &= check(f"{app_svc}: its aggregate health answers 200", True)'),
    ('app-fresh-sessions', '                  bool(n), f"{n} session(s)")\n    others', '                  True, f"{n} session(s)")\n    others'),
    ('sessions-since-start', ' and backend_start >= \'{since}\'::timestamptz;\\n")', ';\\n")'),
    ('others-unchanged', 'good &= check(f"every other container unchanged ({pg_svc} included)", not others', 'good &= check(f"every other container unchanged ({pg_svc} included)", True'),
    ('rotate-backup-loader', 'value == new and mode == "docker", f"mode {mode}")', 'True, f"mode {mode}")'),
    ('end-public', '    check("the public API answers 200 through the gateway", public_ok())\n    rec["postgresContainer"]', '    check("the public API answers 200 through the gateway", True)\n    rec["postgresContainer"]'),
    ('rollback-phase-guard', 'phase in ("role", "env", "app")', 'True'),
    ('rollback-db-records', 'paths = records("rotate-progress", DB)', 'paths = records("rotate-progress")'),
    ('rollback-backup-required', 'if not check("the run\'s backup exists and is readable", old_data is not None):', 'if False:'),
    ('rollback-only-key', 'if not check(f"the backup differs from the env file only in the {key} line", only_key_changed(old_data, data, key)):', 'if False:'),
    ('rollback-role-back', 'if login(DB, pg, old) is not True:', 'if False:'),
    ('rollback-env-restore', '        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))\n    check("the env file is byte-identical to the run\'s backup"', '        pass\n    check("the env file is byte-identical to the run\'s backup"'),
    ('rollback-app-recreate', 'if not app or env_of(app).get(APP_KEY) != old or health(app) != "healthy":', 'if False:'),
    ('rollback-unhealthy-app', ' or health(app) != "healthy":\n        rc, _, _ = wrapper', ':\n        rc, _, _ = wrapper'),
    ('rollback-end-app', 'bool(app) and env_of(app).get(APP_KEY) == old and health(app) == "healthy" and app_health(app, port) == "200")', 'True)'),
    ('rollback-login-original', 'login(DB, pg, old) is True and (not new or login(DB, pg, new) is False))', 'True)'),
    ('rollback-sessions', '    check(f"{app_svc}: fresh sessions of the role from its address, started after it", bool(n), f"{n} session(s)")', '    check(f"{app_svc}: fresh sessions of the role from its address, started after it", True, f"{n} session(s)")'),
    ('rollback-backup-loader', 'check("the nightly backup\'s env loader yields the original value", value == old)', 'check("the nightly backup\'s env loader yields the original value", True)'),
    ('rollback-public', '    check("the public API answers 200 through the gateway", public_ok())\n    if all(c["pass"] for c in CHECKS):\n        prog["phase"] = "rolled-back"', '    check("the public API answers 200 through the gateway", True)\n    if all(c["pass"] for c in CHECKS):\n        prog["phase"] = "rolled-back"'),
    ('rollback-marks-rolled-back', '        prog["phase"] = "rolled-back"\n', '        pass\n'),
    ('redact-all-rotated', 'done and len(olds) == len(DBS))', 'True)'),
    ('redact-already-complete', 'if all(o == MARKER for o in olds.values()):', 'if False:'),
    ('redact-old-refused', 'len(olds[db]) >= 32 and olds[db] != news[db] and login(db, pg, olds[db]) is False and login(db, pg, news[db]) is True)', 'True)'),
    ('redact-live-env-guard', 'if os.path.realpath(path) == os.path.realpath(ENV_FILE):', 'if False:'),
    ('redact-missing-ok', 'return True, "missing; nothing to redact"', 'return False, "missing"'),
    ('redact-keeps-mtime', '        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))\n', '        pass\n'),
    ('redact-scan', 'holds an old value", not left, ", ".join(left))', 'holds an old value", True)'),
    ('redact-backups-last', '        say("  the runs\' backups keep the old values, so this can run again once the failures above are dealt with")\n        return finish("redact", rec)\n', '        say("  the runs\' backups keep the old values, so this can run again once the failures above are dealt with")\n'),
    ('login-refused-pattern', '    return False if REFUSED.search(out + err) else None\n', '    return None\n'),
    ('login-password-on-stdin', 'stdin=(password + "\\n").encode(), timeout=120)', 'stdin=b"", timeout=120)'),
    ('scram-stored-key', 'stored_key = hashlib.sha256(client_key).digest()', 'stored_key = client_key'),
    ('child-env-allowlist', 'env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}', 'env = dict(os.environ)'),
]
EQUIVALENT = [
    ('backup-identical', 'the backup is written from the bytes just read; a mismatch needs a failing disk'),
    ('only-key-changed', "with_value() changes only the key's line; the check guards against a future edit of it"),
    ('pre-app-value', "a service at the model's hash runs the env file's value, and the carriers check requires it to carry the value"),
    ('old-still-refused', 'nothing changes the role between step 1, which proved the old password refused, and step 3'),
    ('stop-after-app', 'only the public check and the record follow, and they change nothing'),
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
        try:
            compile(text.replace(old, new), path, "exec")
        except SyntaxError as e:
            return name, f"BROKEN (syntax, not counted): line {e.lineno}"
        open(path, "w", encoding="utf-8").write(text.replace(old, new))
        runs = []
        for _ in range(2):
            p = subprocess.run([sys.executable, "-I", os.path.join(tmp, SELFTEST)], capture_output=True, text=True, timeout=900)
            runs.append((p.returncode, [l[5:] for l in p.stdout.splitlines() if l.startswith("FAIL ")]))
            if p.returncode == 0:
                break
        if runs[0][0] == 0:
            return name, "SURVIVED"
        common = [f for f in runs[0][1] if len(runs) == 2 and runs[1][0] != 0 and f in runs[1][1]]
        if not common:
            return name, "FLAKY (not counted): first run " + "; ".join(runs[0][1][:2])[:120] + " | second run " + ("passed" if runs[-1][0] == 0 else "; ".join(runs[-1][1][:2])[:120])
        return name, "killed (twice): " + "; ".join(common[:2])[:150]
    except subprocess.TimeoutExpired:
        return name, "TIMEOUT (not counted)"
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
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
