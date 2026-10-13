#!/usr/bin/env python3
"""Mutation run for minio-rotate.py: each mutant disables or alters one guard; minio-rotate-selftest.py must fail
(killed). Each mutant runs in its own temporary copy of the tool and the self-test; nothing else is touched.
Mutants marked EQUIVALENT are defense-in-depth guards that no reachable fault exercises; they are listed, not run.
A mutant counts as killed only when two runs of the self-test fail with at least one failing case in common; a first
failure that does not repeat is reported FLAKY and not counted (load or clock steps on a shared machine). A mutant
that does not compile is reported BROKEN and not counted: a syntax error would fail every case.

  python3 -I minio-rotate-mutants.py
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL, SELFTEST = "minio-rotate.py", "minio-rotate-selftest.py"

MUTANTS = [
    ('host-guard', 'check(f"host {HOST}", socket.gethostname() == HOST', 'check(f"host {HOST}", True'),
    ('dockerd-debug-off', 'rc == 0 and out.decode().strip() == "false", out.decode().strip())', 'True, out.decode().strip())'),
    ('release-at-r2', 'head == R2 and not st.strip()', 'not st.strip()'),
    ('backup-window', 'not (t.hour == 3 or (t.hour == 4 and t.minute < 30))', 'True'),
    ('password-length', 'check("the env file\'s password is at least 32 characters", len(cur) >= 32)', 'check("the env file\'s password is at least 32 characters", True)'),
    ('model-carriers', 'in_model == sorted(CARRIERS), ", ".join(in_model))', 'True, ", ".join(in_model))'),
    ('live-carriers', 'live == want and len(want) == len(CARRIERS) and not outside', 'True'),
    ('live-outside-env', ' and not outside, ", ".join(live))', ', ", ".join(live))'),
    ('pre-minio-volume', '\n          and data_volume(mi) == [DATA_VOLUME], f"{health(mi)}, {data_volume(mi)}")', ', f"{health(mi)}, {data_volume(mi)}")'),
    ('override-reviewed', 'hashlib.sha256(open(OVERRIDE, "rb").read()).hexdigest() == OVERRIDE_SHA256', 'True'),
    ('pre-minio-drift', 'label(mi, "com.docker.compose.config-hash") == em.get(MINIO))', 'True)'),
    ('pre-setup-image', 'check("minio-setup runs the model\'s image", image_ok(m, se, SETUP)', 'check("minio-setup runs the model\'s image", True'),
    ('pre-media-hash', 'bool(me) and health(me) == "healthy" and label(me, "com.docker.compose.config-hash") == hashes.get(MEDIA) and image_ok(m, me, MEDIA)', 'bool(me) and health(me) == "healthy" and image_ok(m, me, MEDIA)'),
    ('pre-media-image', ' and image_ok(m, me, MEDIA)\n', '\n'),
    ('pre-media-pair', '\n          and env_of(me).get(MEDIA_KEY) == cur and env_of(me).get(MEDIA_USER_KEY) == user, health(me) or "?")', ', health(me) or "?")'),
    ('pre-media-health', 'check("media-service\'s aggregate health answers 200", bool(me) and media_health(me) == "200")', 'check("media-service\'s aggregate health answers 200", True)'),
    ('login-discriminates', 'login(user, cur) is True and login(user, wrong) is False', 'login(user, cur) is True'),
    ('pre-backup-loader', 'check("the nightly backup\'s env loader yields minio\'s user and password", bu == user and bp == cur)', 'check("the nightly backup\'s env loader yields minio\'s user and password", True)'),
    ('pre-objects-listed', 'check("the media bucket\'s objects are listed", listing is not None', 'check("the media bucket\'s objects are listed", True'),
    ('pre-public', '    check("the public API answers 200 through the gateway", public_ok())\n    rec["baseline"]', '    check("the public API answers 200 through the gateway", True)\n    rec["baseline"]'),
    ('inbox-overlay-detected', '_OVERLAYS = ["-f", INBOX_OVERLAY] if any(', '_OVERLAYS = [] if any('),
    ('inbox-overlay-always', ' else []\n    return _OVERLAYS', ' else ["-f", INBOX_OVERLAY]\n    return _OVERLAYS'),
    ('pre-age', ' and age is not None and -60 <= age <= PRE_MAX_AGE', ''),
    ('env-unchanged-since-pre', 'hashlib.sha256(data).hexdigest() == pre.get("envSha256")', 'True'),
    ('containers-unchanged-since-pre', 'bool(pre) and baseline(inspect_all()) == pre.get("baseline")', 'bool(pre)'),
    ('objects-before-cutover', 'check("the media bucket\'s objects are listed before the cutover", before_list is not None', 'check("the media bucket\'s objects are listed before the cutover", True'),
    ('preflight', 'ok &= check("the release preflight passes", preflight_ok())', 'ok &= check("the release preflight passes", True)'),
    ('hash-set', 'changed == sorted(CARRIERS), ", ".join(changed))', 'True, ", ".join(changed))'),
    ('restore-env-on-failure', '    if not ok:\n        write_atomically(ENV_FILE, data, st)\n', '    if not ok:\n'),
    ('minio-new-healthy', 'd.get("Id") != (mi or {}).get("Id") and health(d) == "healthy"\n                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(MINIO)', 'True'),
    ('minio-same-image-volume', '\n                 and d.get("Image") == (mi or {}).get("Image") and data_volume(d) == [DATA_VOLUME], f"exit {rc}', ', f"exit {rc}'),
    ('minio-hardening-value', 'hardened(d, MINIO) and env_of(d).get(KEY) == new)', 'True)'),
    ('minio-old-refused', 'login(user, new) is True and login(user, old) is False)', 'login(user, new) is True)'),
    ('minio-objects-intact', 'after_list is not None and not missing and after_content == before_content and all(before_content.values())', 'True'),
    ('minio-content-samples', ' and after_content == before_content and all(before_content.values())', ''),
    ('minio-others-unchanged', 'good &= check("minio: every other container unchanged", not others', 'good &= check("minio: every other container unchanged", True'),
    ('stop-after-minio', '        say("  STOP: run `rollback --yes` (add --unhardened when minio does not come back healthy under the hardening)")\n        return finish("rotate", rec)\n', '        say("  STOP: run `rollback --yes` (add --unhardened when minio does not come back healthy under the hardening)")\n'),
    ('media-new-healthy', 'rc == 0 and bool(d) and d.get("Id") != (me or {}).get("Id") and health(d) == "healthy"\n                 and label(d, "com.docker.compose.config-hash") == (model_hashes() or {}).get(MEDIA)', 'True'),
    ('media-new-value', 'e.get(MEDIA_KEY) == new and e.get(MEDIA_USER_KEY) == user)', 'True)'),
    ('media-health', 'good &= check("media-service: its aggregate health answers 200", bool(d) and media_health(d) == "200")', 'good &= check("media-service: its aggregate health answers 200", True)'),
    ('media-auth-errors', 'bool(d) and auth_errors_since_start(d) == 0)', 'True)'),
    ('media-others-unchanged', 'good &= check("media-service: every other container unchanged", not others', 'good &= check("media-service: every other container unchanged", True'),
    ('stop-after-media', '        say("  STOP: run `rollback --yes`")\n        return finish("rotate", rec)\n\n    say("4.', '        say("  STOP: run `rollback --yes`")\n\n    say("4.'),
    ('setup-created-hardened-new', 'rc == 0 and bool(d) and d.get("Id") != (se or {}).get("Id") and status(d) == "created" and hardened(d, SETUP)', 'True'),
    ('setup-new-value', ' and env_of(d).get(KEY) == new,\n                 f"exit {rc}, {status(d) or \'?\'}")', ',\n                 f"exit {rc}, {status(d) or \'?\'}")'),
    ('rotate-backup-loader', 'bu == user and bp == new and login(bu, bp) is True)', 'True)'),
    ('end-public', '    check("the public API answers 200 through the gateway", public_ok())\n    ds = inspect_all()', '    check("the public API answers 200 through the gateway", True)\n    ds = inspect_all()'),
    ('rollback-phase-guard', 'phase in ("env", "minio", "media", "setup")', 'True'),
    ('rollback-override-reviewed', 'if unhardened and not check("the emergency override is the reviewed file", override_ok()):', 'if False:'),
    ('rollback-backup-required', 'if not check("the rotation\'s backup exists and is readable", old_data is not None):', 'if False:'),
    ('rollback-env-restore', '        write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))\n    check("the env file is byte-identical', '        pass\n    check("the env file is byte-identical'),
    ('rollback-minio-live', 'if not mi or env_of(mi).get(KEY) != old or health(mi) != "healthy" or unhardened:', 'if unhardened:'),
    ('rollback-unhardened-override', 'extra_files=(OVERRIDE,) if unhardened else ())', 'extra_files=())'),
    ('rollback-media-live', 'if not me or env_of(me).get(MEDIA_KEY) != old:', 'if False:'),
    ('rollback-setup-live', 'if not se or env_of(se).get(KEY) != old:', 'if False:'),
    ('rollback-end-carriers', 'bool(mi and se and me) and env_of(mi).get(KEY) == old and env_of(se).get(KEY) == old\n          and env_of(me).get(MEDIA_KEY) == old)', 'True)'),
    ('rollback-end-health', 'health(mi) == "healthy" and health(me) == "healthy" and media_health(me) == "200")', 'True)'),
    ('rollback-login-original', 'login(user, old) is True and (not new or login(user, new) is False))', 'True)'),
    ('rollback-objects', 'listing is not None and len(listing) >= (pre.get("objects") or 0),', 'True,'),
    ('rollback-backup-loader', 'check("the nightly backup\'s env loader yields the original password", bu == user and bp == old)', 'check("the nightly backup\'s env loader yields the original password", True)'),
    ('rollback-public', '    check("the public API answers 200 through the gateway", public_ok())\n    if all(c["pass"] for c in CHECKS):\n        prog["phase"] = "rolled-back"', '    check("the public API answers 200 through the gateway", True)\n    if all(c["pass"] for c in CHECKS):\n        prog["phase"] = "rolled-back"'),
    ('rollback-marks-rolled-back', '        prog["phase"] = "rolled-back"\n', '        pass\n'),
    ('redact-needs-passing-rotation', 'bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"\n                 and rot.get("stamp") == (prog or {}).get("stamp")', 'True'),
    ('redact-old-refused', 'len(old) >= 32 and old != new and login(user, old) is False and login(user, new) is True)', 'len(old) >= 32 and old != new)'),
    ('redact-live-env-guard', 'if os.path.realpath(path) == os.path.realpath(ENV_FILE):', 'if False:'),
    ('redact-holds-new', 'if new.encode() in data:', 'if False:'),
    ('redact-missing-ok', 'return True, "missing; nothing to redact"', 'return False, "missing"'),
    ('redact-keeps-mtime', '        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))\n', '        pass\n'),
    ('redact-scan', 'holds the old value", not left, ", ".join(left))', 'holds the old value", True)'),
    ('redact-backup-last', '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n        return finish("redact", rec)\n', '        say("  the rotation\'s backup keeps the old value, so this can run again once the failures above are dealt with")\n'),
    ('redact-already-complete', 'if old == MARKER:', 'if False:'),
    ('login-refused-pattern', '        if REFUSED.search(out):\n            return False\n', '        pass\n'),
    ('login-settle', '        if time.time() >= deadline:\n            return None\n', '        return None\n'),
    ('mc-credentials-on-stdin', 'stdin=f"{user}\\n{password}\\n".encode(), timeout=timeout)', 'stdin=b"", timeout=timeout)'),
    ('child-env-allowlist', 'env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}', 'env = dict(os.environ)'),
]
EQUIVALENT = [
    ('backup-identical', 'the backup is written from the bytes just read; a mismatch needs a failing disk'),
    ('only-key-changed', 'with_value() changes only the password line; the check guards against a future edit of it'),
    ('media-own-credentials', "media-service's pair is checked equal to minio's user and the new password, which the minio step proved logs in"),
    ('stop-after-setup', 'only the end checks follow, and they change nothing'),
    ('end-no-old-holder', "every carrier's recreate checks its value, and pre limits the carriers to the three"),
    ('end-model-hash', "every recreate checks the model's hash"),
    ('record-secret-guard', 'finish() refuses a record containing a value; no record path carries one'),
    ('scrub', 'say() scrubs known values; nothing the tool prints contains one'),
    ('placeholder-redraw', 'a random value matches a placeholder pattern about once in 25,000 draws'),
]


def run_one(mutant):
    name, old, new = mutant
    tmp = tempfile.mkdtemp(prefix="rrt-mut-")
    try:
        for f in (TOOL, SELFTEST, "minio-emergency-override.yml"):
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
