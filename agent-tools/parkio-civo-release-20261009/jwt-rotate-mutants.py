#!/usr/bin/env python3
"""Mutation run for jwt-rotate.py: each mutant disables or alters one guard; jwt-rotate-selftest.py must fail
(killed). Each mutant runs in its own temporary copy of the tool and the self-test; nothing else is touched.
Mutants marked EQUIVALENT are defense-in-depth guards that no reachable fault exercises; they are listed, not run.

  python3 -I jwt-rotate-mutants.py
"""
import concurrent.futures
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL, SELFTEST = "jwt-rotate.py", "jwt-rotate-selftest.py"

MUTANTS = [
    ("release-at-r2", 'head == R2 and not status.strip()', 'not status.strip()'),
    ("backup-window", 'not (t.hour == 3 or (t.hour == 4 and t.minute < 30))', 'True'),
    ("no-additional-keys-env", 'check("no additional (previous) public keys in the env file", not ev.get(ADD))',
     'check("no additional (previous) public keys in the env file", True)'),
    ("auth-runs-env-key", 'bool(auth) and aenv.get(PEM) == container_form(key) and aenv.get(KID) == kid and not aenv.get(ADD))', 'bool(auth))'),
    ("carriers", 'carriers == [name_of(auth)] if auth else False', 'True'),
    ("model-holders", 'holders == [SIGNER] and model_env(m, SIGNER).get(PEM) == container_form(key)', 'True'),
    ("pre-model-hash", 'health(d) == "healthy" and lab.get("com.docker.compose.config-hash") == hashes.get(svc)\n', 'health(d) == "healthy"\n'),
    ("image-identity", '                  and bool(ref) and image_id(ref) == d.get("Image"), health(d)', '                  , health(d)'),
    ("pre-jwks", 'keys is not None and n is not None and keys == [(kid, n)]', 'True'),
    ("pre-age", ' and age is not None and 0 <= age <= PRE_MAX_AGE', ''),
    ("env-unchanged-since-pre", 'hashlib.sha256(data).hexdigest() == pre.get("envSha256")', 'True'),
    ("containers-unchanged-since-pre", 'bool(pre) and baseline(inspect_all()) == pre.get("baseline")', 'bool(pre)'),
    ("preflight", 'ok &= check("the release preflight still passes", preflight_ok())', 'ok &= check("the release preflight still passes", True)'),
    ("hash-set", 'changed == [SIGNER]', 'True'),
    ("restore-env-on-B-failure", '        write_atomically(ENV_FILE, data, st)\n        rec["phase"] = "restored"', '        rec["phase"] = "restored"'),
    ("auth-new-healthy", 'good = check("auth-service: wrapper exit 0, a new healthy container", rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy",',
     'good = check("auth-service: wrapper exit 0, a new healthy container", rc == 0 and bool(d),'),
    ("auth-new-env", 'bool(d) and env_of(d).get(PEM) == container_form(new_pem) and env_of(d).get(KID) == new_kid and\n', 'bool(d) and\n'),
    ("auth-others", 'good &= check("auth-service: every other container unchanged", not others, ", ".join(others))',
     'good &= check("auth-service: every other container unchanged", True)'),
    ("jwks-after-auth", 'keys == [(new_kid, new_n)],', 'True,'),
    ("stop-after-C", '        say("  STOP: run `rollback --yes` if auth-service cannot serve the new key")\n        return finish("rotate", rec)\n',
     '        say("  STOP: run `rollback --yes` if auth-service cannot serve the new key")\n'),
    ("gateway-force-recreate", 'wrapper("up", "-d", "--no-build", "--no-deps", "--force-recreate", VERIFIER)', 'wrapper("up", "-d", "--no-build", "--no-deps", VERIFIER)'),
    ("gateway-new-healthy", 'rc == 0 and bool(d) and d.get("Id") != (before or {}).get("Id") and health(d) == "healthy" and\n                 lab.get',
     'rc == 0 and bool(d) and\n                 lab.get'),
    ("gateway-others", 'good &= check("gateway-service: every other container unchanged", not others, ", ".join(others))',
     'good &= check("gateway-service: every other container unchanged", True)'),
    ("stop-after-D", '        say("  STOP: see the procedure\'s recovery section")\n        return finish("rotate", rec)\n',
     '        say("  STOP: see the procedure\'s recovery section")\n'),
    ("old-key-refused", 'status == 401 and code == "INVALID_TOKEN"', 'True'),
    ("new-key-accepted", 'code in ACCEPTED_AFTER_SIGNATURE and status in (401, 503)', 'True'),
    ("probe-epoch", '"session_epoch": -1}', '"session_epoch": 0}'),
    ("probe-audience", '"aud": aud,', '"aud": iss,'),
    ("rollback-phase-guard", 'phase in ("C", "D", "E")', 'True'),
    ("rollback-env-restore", '    write_atomically(ENV_FILE, old_data, os.stat(ENV_FILE))\n', ''),
    ("rollback-recreate", '    for svc in prog.get("moved") or []:', '    for svc in []:'),
    ("rollback-gateway-force", '(["--force-recreate"] if svc == VERIFIER else [])', '[]'),
    ("redact-needs-passing-rotation", 'bool(rot) and rot.get("verdict") == "PASS" and (prog or {}).get("phase") == "done"\n                 and rot.get("stamp") == (prog or {}).get("stamp")', 'True'),
    ("redact-jwks-new-only", 'old != cur.get(PEM) and keys is not None and [k for k, _ in keys] == [cur.get(KID)]', 'old != cur.get(PEM)'),
    ("redact-live-env-guard", 'if os.path.realpath(path) == os.path.realpath(ENV_FILE):', 'if False:'),
    ("redact-regular-own-file", 'if not stat.S_ISREG(st.st_mode) or st.st_uid != os.getuid():', 'if False:'),
    ("redact-holds-current", 'if current.encode() in data:', 'if False:'),
    ("redact-missing-ok", 'return True, "missing; nothing to redact"', 'return False, "missing"'),
    ("redact-keeps-mtime", '        os.utime(tmp, ns=(st.st_atime_ns, st.st_mtime_ns))\n', ''),
    ("redact-scan", 'holds the old key in any form", not left, ", ".join(left))', 'holds the old key in any form", True)'),
    ("redact-backup-last", '        say("  the rotation\'s backup keeps the old key, so this can run again once the failures above are dealt with")\n        return finish("redact", rec)\n',
     '        say("  the rotation\'s backup keeps the old key, so this can run again once the failures above are dealt with")\n'),
    ("redact-already-complete", 'if old == MARKER:', 'if False:'),
    ("child-env-allowlist", 'env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}', 'env = dict(os.environ)'),
]
EQUIVALENT = [
    ("env-pkcs8-kid", "the env PEM's form is also proven by auth-runs-env-key and the JWKS modulus match"),
    ("new-key-check", "openssl genpkey always yields a 2048-bit PKCS#8 key; the check documents the expectation"),
    ("backup-identical", "the backup is written from the bytes just read; a mismatch needs a failing disk"),
    ("only-keys-changed", "with_values() changes only the two lines; the check guards against a future edit of it"),
    ("record-secret-guard", "finish() refuses a record containing key material; no record path carries any"),
    ("scrub", "say() scrubs known values; nothing the tool prints contains one"),
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
