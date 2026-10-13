#!/usr/bin/env python3
"""Self-test for web-bundle-compare.py on two real candidate web images built from the same source.

  python3 -I web-bundle-compare-selftest.py --a-tar A/image.tar.gz --b-tar B/image.tar.gz

The bundles are extracted with candidate-web-check.py's layer reader. Each case below changes a copy of tree B:
  - a simulated key change: another synthetic key, the key-bearing chunks renamed, references and CACHE_VERSION updated;
    the comparison must report IDENTICAL;
  - any other change must be reported: an unrelated JS byte, an extra file, a binary byte, a missing file;
  - a tree without a key is invalid input.
With --c-tar (a candidate built with another key), the real key-bearing chunk is checked too:
  - the real pair must be IDENTICAL through a consistent renaming of short locals;
  - each of these mutations of the real chunk must be DIFFERENT: an inconsistent rename, a long identifier, a string,
    a number, a keyword, an extra token, an unterminated string.
The key values in the artifacts are never printed; the test checks the tool's output for them.
"""
import argparse
import hashlib
import importlib.util
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "web-bundle-compare.py")
spec = importlib.util.spec_from_file_location("cwc", os.path.join(HERE, "candidate-web-check.py"))
cwc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cwc)
results = []
KEY_RE = re.compile(r'VITE_MAPTILER_KEY\s*:\s*"([^"]*)"')


def extract(tar, dest):
    files = cwc.final_files(tar, lambda p: p.startswith("usr/share/nginx/html/"))
    for p, b in files.items():
        rel = p[len("usr/share/nginx/html/"):]
        full = os.path.join(dest, rel)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "wb") as fh:
            fh.write(b)


def tree_key(root):
    for dirpath, _, names in os.walk(root):
        for n in names:
            if n.endswith(".js"):
                m = KEY_RE.search(open(os.path.join(dirpath, n), encoding="utf-8", errors="replace").read())
                if m:
                    return m.group(1)
    return None


def run(a, b, want_rc, want, label, secrets=()):
    p = subprocess.run([sys.executable, "-I", TOOL, "--a-dir", a, "--b-dir", b], capture_output=True, text=True)
    out = p.stdout + p.stderr
    ok = p.returncode == want_rc and all(w in out for w in want) and not any(s and s in out for s in secrets)
    results.append(ok)
    print(("ok   " if ok else "FAIL ") + label + ("" if ok else f" (rc={p.returncode})\n{out}"))


def simulate_key_change(root, new_key, new_cache):
    old_key = tree_key(root)
    holders = []
    for dirpath, _, names in os.walk(root):
        for n in names:
            full = os.path.join(dirpath, n)
            if n.endswith((".js", ".html", ".css", ".json", ".webmanifest")):
                t = open(full, encoding="utf-8", errors="surrogateescape").read()
                if old_key in t:
                    open(full, "w", encoding="utf-8", errors="surrogateescape").write(t.replace(old_key, new_key))
                    if re.search(r"-[A-Za-z0-9_-]{8}\.js$", n):
                        holders.append(full)
    renames = {}
    for full in holders:
        n = os.path.basename(full)
        base = re.sub(r"-[A-Za-z0-9_-]{8}\.js$", "", n)
        new = f"{base}-{hashlib.sha256(n.encode()).hexdigest()[:8]}.js"
        os.rename(full, os.path.join(os.path.dirname(full), new))
        renames[n] = new
    for dirpath, _, names in os.walk(root):
        for n in names:
            full = os.path.join(dirpath, n)
            if n.endswith((".js", ".html", ".css", ".json", ".webmanifest")):
                t = open(full, encoding="utf-8", errors="surrogateescape").read()
                u = t
                for o, nn in renames.items():
                    u = u.replace(o, nn)
                if n == "sw.js":
                    u = re.sub(r"(CACHE_VERSION\s*=\s*')[0-9a-f]+'", lambda m: m.group(1) + new_cache + "'", u)
                if u != t:
                    open(full, "w", encoding="utf-8", errors="surrogateescape").write(u)
    return old_key, renames


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a-tar", required=True)
    ap.add_argument("--b-tar", required=True)
    ap.add_argument("--c-tar")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        a, b = os.path.join(tmp, "a"), os.path.join(tmp, "b")
        extract(args.a_tar, a)
        extract(args.b_tar, b)
        ka, kb = tree_key(a), tree_key(b)
        run(a, b, 0, ["IDENTICAL"], "the two real candidate bundles (same source, same key) are identical", (ka, kb))

        c = os.path.join(tmp, "c")
        shutil.copytree(b, c)
        old, renames = simulate_key_change(c, "SyntheticKeyZZZZ9999", "0123456789abcdef")
        results.append(bool(renames))
        print(("ok   " if renames else "FAIL ") + f"the simulation renamed {len(renames)} key-bearing chunk(s)")
        run(a, c, 0, ["IDENTICAL"], "a key change (renamed chunks, references, CACHE_VERSION) normalizes to identical",
            (ka, "SyntheticKeyZZZZ9999"))

        def variant(name, change):
            d = os.path.join(tmp, name)
            shutil.copytree(c, d)
            change(d)
            return d

        def js_byte(d):
            js = sorted(n for n in os.listdir(os.path.join(d, "assets")) if n.endswith(".js"))
            victim = next(n for n in js if not any(n == r for r in renames.values()))
            with open(os.path.join(d, "assets", victim), "ab") as fh:
                fh.write(b"\n/*x*/")
        run(a, variant("d1", js_byte), 1, ["DIFFERENT"], "an unrelated JS change is reported")

        run(a, variant("d2", lambda d: open(os.path.join(d, "extra.txt"), "w").write("x")), 1, ["DIFFERENT", "extra.txt"],
            "an extra file is reported")

        def bin_byte(d):
            for dirpath, _, names in os.walk(d):
                for n in names:
                    if n.endswith((".png", ".woff2", ".ico", ".webp")):
                        with open(os.path.join(dirpath, n), "ab") as fh:
                            fh.write(b"\0")
                        return
        run(a, variant("d3", bin_byte), 1, ["DIFFERENT"], "a binary change is reported")

        def drop(d):
            os.remove(os.path.join(d, "index.html"))
        run(a, variant("d4", drop), 1, ["DIFFERENT", "index.html"], "a missing file is reported")

        def unkey(d):
            for dirpath, _, names in os.walk(d):
                for n in names:
                    full = os.path.join(dirpath, n)
                    if n.endswith(".js"):
                        t = open(full, encoding="utf-8", errors="surrogateescape").read()
                        if "VITE_MAPTILER_KEY" in t:
                            open(full, "w", encoding="utf-8", errors="surrogateescape").write(t.replace("VITE_MAPTILER_KEY", "VITE_MAPTILER_XXX"))
        run(a, variant("d5", unkey), 1, ["invalid input"], "a tree without a baked key is invalid input")

        def twin(d):
            js = sorted(n for n in os.listdir(os.path.join(d, "assets")) if re.search(r"-[A-Za-z0-9_-]{8}\.js$", n))
            src = js[0]
            shutil.copy(os.path.join(d, "assets", src), os.path.join(d, "assets", re.sub(r"-[A-Za-z0-9_-]{8}\.js$", "-Zz9Zz9Zz.js", src)))
        run(a, variant("d6", twin), 1, ["normalized asset names collide"], "colliding normalized asset names are refused")

        if args.c_tar:
            n = os.path.join(tmp, "new")
            extract(args.c_tar, n)
            kn = tree_key(n)
            run(a, n, 0, ["IDENTICAL", "equal apart from a consistent renaming of"],
                "the real candidate with another key: IDENTICAL through a consistent renaming in the key-bearing chunk", (ka, kn))

            def env_mutation(name, fn):
                d = os.path.join(tmp, name)
                shutil.copytree(n, d)
                env = [os.path.join(d, "assets", f) for f in os.listdir(os.path.join(d, "assets")) if f.startswith("env-")][0]
                t = open(env, encoding="utf-8").read()
                u = fn(t)
                assert u != t, name
                open(env, "w", encoding="utf-8").write(u)
                return d

            def inconsistent(t):
                ids = re.findall(r"(?<![A-Za-z0-9_$.])([A-Za-z_$])(?![A-Za-z0-9_$])", t)
                short = next(x for x in ids if ids.count(x) >= 2 and x not in "e")
                i = t.index(short, t.index(short) + 1)
                while not (not re.match(r"[A-Za-z0-9_$.]", t[i - 1]) and not re.match(r"[A-Za-z0-9_$]", t[i + 1])):
                    i = t.index(short, i + 1)
                other = next(c for c in "QZqz" if not re.search(r"(?<![A-Za-z0-9_$])" + c + r"(?![A-Za-z0-9_$])", t))
                return t[:i] + other + t[i + 1:]
            cases = [
                ("m1", inconsistent, "one occurrence of a short local renamed (inconsistent)"),
                ("m2", lambda t: t.replace(".optional()", ".nullable()", 1), "a long identifier changed"),
                ("m3", lambda t: t.replace('"streets-v2"', '"streets-v3"', 1), "a string changed"),
                ("m4", lambda t: re.sub(r"(?<![A-Za-z0-9_$.])(\d+)(?![\d.])", lambda m: str(int(m.group(1)) + 1), t, count=1), "a number changed"),
                ("m5", lambda t: t.replace("throw new Error", "throw Error", 1), "a token removed (new)"),
                ("m6", lambda t: t.replace("===void 0?", "===void 1?", 1), "a literal in a comparison changed"),
                ("m7", lambda t: t.replace("return", "return 0,", 1) if "return" in t else t + ";0", "an extra token"),
                ("m8", lambda t: t + "'unterminated", "an unterminated string (lexing doubt)"),
            ]
            for name, fn, label in cases:
                run(a, env_mutation(name, fn), 1, ["DIFFERENT"], f"the real key-bearing chunk with {label} is DIFFERENT", (ka, kn))
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
