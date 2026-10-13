#!/usr/bin/env python3
"""Self-test for web-bundle-compare.py on real candidate web images.

  python3 -I web-bundle-compare-selftest.py --a-tar A/image.tar.gz --b-tar B/image.tar.gz [--c-tar C/image.tar.gz] \
      --esbuild ESBUILD

A and B are two candidates built from the same source with the same key, and C is one built with another key. ESBUILD is
the pinned binary from fetch-esbuild-0.27.4.sh. The bundles are extracted with the layer reader of
candidate-web-check.py, which must sit beside this file.

Cases:
- Usage: a binary other than the pinned esbuild is refused.
- On copies of tree B:
  - a simulated key change (another synthetic key, the key-bearing chunks renamed, references and CACHE_VERSION
    updated) is IDENTICAL;
  - an unrelated JavaScript byte, an extra file, a binary byte or a missing file is reported;
  - a tree without a key is invalid input;
  - colliding normalized names are refused: two hashed chunks, or a file literally named like a normalized one,
    whichever of the two sorts first;
  - a key-bearing text file that is not JavaScript is compared byte for byte (a whitespace change is DIFFERENT).
- With C, on the real key-bearing chunk (assets/env-<H>.js):
  - the real pair is IDENTICAL through esbuild canonical equality;
  - the review probes are DIFFERENT: two import names swapped, and an exported name renamed;
  - a real non-key chunk replaced by its own esbuild-minified form is DIFFERENT, because only key-bearing chunks get the
    canonical comparison;
  - a string changed, a boolean flipped (DEV:!1 -> DEV:!0) or an unterminated string in the real chunk is DIFFERENT;
  - a behaviour-free change (DEV:!1 -> DEV:!2, both false) is IDENTICAL: the comparison is semantic, by design.
- Snippet pairs, appended to the real key-bearing chunks of A (first snippet) and of C (second):
  - an alpha-renamed pair is IDENTICAL;
  - each other pair changes one invariant and is DIFFERENT: merged locals, code appended on one side, an identifier
    turned into a literal, a free global renamed, a shorthand key, a class member, `in` vs `of`, a regular expression
    after `)`, a statement boundary (return + newline), a property name, swapped import names, an exported name,
    input esbuild rejects on one side, and two different inputs it rejects on both sides.
The key values in the artifacts are never printed, and the test checks the tool's output for them.
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
spec2 = importlib.util.spec_from_file_location("wbc", TOOL)
wbc = importlib.util.module_from_spec(spec2)
spec2.loader.exec_module(wbc)
results = []
KEY_RE = re.compile(r'VITE_MAPTILER_KEY\s*:\s*"([^"]*)"')
ESBUILD = None


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


def run(a, b, want_rc, want, label, secrets=(), esbuild=None):
    p = subprocess.run([sys.executable, "-I", TOOL, "--a-dir", a, "--b-dir", b, "--esbuild", esbuild or ESBUILD],
                       capture_output=True, text=True)
    out = p.stdout + p.stderr
    ok = p.returncode == want_rc and all(w in out for w in want) and not any(s and s in out for s in secrets)
    results.append(ok)
    print(("ok   " if ok else "FAIL ") + label + ("" if ok else f" (rc={p.returncode})\n{out}"))


def check(cond, label):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label)


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


def env_chunk(d):
    found = [os.path.join(d, "assets", f) for f in os.listdir(os.path.join(d, "assets")) if re.fullmatch(r"env-[A-Za-z0-9_-]{8}\.js", f)]
    assert len(found) == 1, "exactly one env chunk"
    return found[0]


def swap_import_names(t):
    """Swap the imported (external) names of the first two aliased specifiers of the first import list."""
    m = re.search(r"import\{([^}]*)\}from", t)
    specs = m.group(1).split(",")
    aliased = [i for i, s in enumerate(specs) if " as " in s]
    i, j = aliased[0], aliased[1]
    (xi, yi), (xj, yj) = specs[i].split(" as "), specs[j].split(" as ")
    assert xi != xj
    specs[i], specs[j] = f"{xj} as {yi}", f"{xi} as {yj}"
    return t[:m.start(1)] + ",".join(specs) + t[m.end(1):]


def rename_export(t):
    """Rename the exported (external) name of the first aliased specifier of the export list."""
    m = re.search(r"export\{([^}]*)\}", t)
    specs = m.group(1).split(",")
    i = next(i for i, s in enumerate(specs) if " as " in s)
    local, exported = specs[i].split(" as ")
    specs[i] = f"{local} as Qx9{exported}"
    return t[:m.start(1)] + ",".join(specs) + t[m.end(1):]


SNIPPETS = [
    # (case, snippet appended in tree A, snippet appended in tree C, expected rc)
    ("an alpha-renamed pair", "function zq1(a,b){return a*b+a}export{zq1 as zq1x};", "function zq1(b,a){return b*a+b}export{zq1 as zq1x};", 0),
    ("merged locals (not a bijection)", "function zq2(a,b){return a*b+a}export{zq2 as zq2x};", "function zq2(a,b){return a*a+a}export{zq2 as zq2x};", 1),
    ("code appended on one side (token count)", "", "console.log(1);", 1),
    ("an identifier turned into a literal (token kind)", "function zq3(a){return a}export{zq3 as zq3x};", "function zq3(a){return 1}export{zq3 as zq3x};", 1),
    ("a free global renamed", "function zq4(){return Qa}export{zq4 as zq4x};", "function zq4(){return Qb}export{zq4 as zq4x};", 1),
    ("a shorthand key", "function zq5(a){return{a}}export{zq5 as zq5x};", "function zq5(b){return{b}}export{zq5 as zq5x};", 1),
    ("a class member", "class Zq6{ab(){return 1}}export{Zq6 as zq6x};", "class Zq6{cd(){return 1}}export{Zq6 as zq6x};", 1),
    ("`in` versus `of`", "function zq7(o){for(const k in o)console.log(k)}export{zq7 as zq7x};",
     "function zq7(o){for(const k of o)console.log(k)}export{zq7 as zq7x};", 1),
    ("a regular expression after `)`", "function zq8(x){if(x)/ab/.test(x)}export{zq8 as zq8x};",
     "function zq8(x){if(x)/cd/.test(x)}export{zq8 as zq8x};", 1),
    ("a statement boundary (return + newline)", "function zq9(){return\n1}export{zq9 as zq9x};", "function zq9(){return 1}export{zq9 as zq9x};", 1),
    ("a property name", "function zr1(o){return o.ab}export{zr1 as zr1x};", "function zr1(o){return o.cd}export{zr1 as zr1x};", 1),
    ("swapped import names", 'import{a as x,b as y}from"./zr2.js";console.log(x,y);', 'import{b as x,a as y}from"./zr2.js";console.log(x,y);', 1),
    ("an exported name", "const zr3=1;export{zr3 as ab};", "const zr3=1;export{zr3 as cd};", 1),
    ("input esbuild rejects", "function zr4(){return 1}", "function zr4({return 1}", 1),
    ("two different inputs esbuild rejects (no match through failure)", "function zr5({return 1}", "function zr5({return 2}", 1),
]


def main():
    global ESBUILD
    ap = argparse.ArgumentParser()
    ap.add_argument("--a-tar", required=True)
    ap.add_argument("--b-tar", required=True)
    ap.add_argument("--c-tar")
    ap.add_argument("--esbuild", required=True)
    args = ap.parse_args()
    ESBUILD = os.path.abspath(args.esbuild)
    with tempfile.TemporaryDirectory() as tmp:
        a, b = os.path.join(tmp, "a"), os.path.join(tmp, "b")
        extract(args.a_tar, a)
        extract(args.b_tar, b)
        ka, kb = tree_key(a), tree_key(b)

        fake = os.path.join(tmp, "not-esbuild")
        shutil.copy(ESBUILD, fake)
        with open(fake, "ab") as fh:
            fh.write(b"\0")
        run(a, b, 2, ["not the pinned esbuild 0.27.4"], "a binary other than the pinned esbuild is refused (usage)", esbuild=fake)

        run(a, b, 0, ["IDENTICAL"], "the two real candidate bundles (same source, same key) are identical", (ka, kb))

        c = os.path.join(tmp, "c")
        shutil.copytree(b, c)
        old, renames = simulate_key_change(c, "SyntheticKeyZZZZ9999", "0123456789abcdef")
        check(bool(renames), f"the simulation renamed {len(renames)} key-bearing chunk(s)")
        run(a, c, 0, ["IDENTICAL"], "a key change (renamed chunks, references, CACHE_VERSION) normalizes to identical",
            (ka, "SyntheticKeyZZZZ9999"))

        def variant(name, change, base=c):
            d = os.path.join(tmp, name)
            shutil.copytree(base, d)
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
        run(a, variant("d4", lambda d: os.remove(os.path.join(d, "index.html"))), 1, ["DIFFERENT", "index.html"], "a missing file is reported")

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
            shutil.copy(os.path.join(d, "assets", js[0]), os.path.join(d, "assets", re.sub(r"-[A-Za-z0-9_-]{8}\.js$", "-Zz9Zz9Zz.js", js[0])))
        run(a, variant("d6", twin), 1, ["normalized asset names collide"], "colliding normalized asset names (two hashed chunks) are refused")

        def literal(d):
            js = sorted(n for n in os.listdir(os.path.join(d, "assets")) if re.search(r"-[A-Za-z0-9_-]{8}\.js$", n))
            shutil.copy(os.path.join(d, "assets", js[0]), os.path.join(d, "assets", re.sub(r"-[A-Za-z0-9_-]{8}\.js$", "-<H>.js", js[0])))
        run(a, variant("d7", literal), 1, ["normalized asset names collide"], "a file literally named like a normalized one is refused")

        def literal_after(d):
            src = sorted(n for n in os.listdir(os.path.join(d, "assets")) if n.endswith(".js"))[0]
            for name in ("zzcollide-00000000.js", "zzcollide-<H>.js"):      # '0' sorts before '<': the hashed twin comes first
                shutil.copy(os.path.join(d, "assets", src), os.path.join(d, "assets", name))
        run(a, variant("d8", literal_after), 1, ["normalized asset names collide"],
            "a literal normalized name that sorts after its hashed twin is refused (order-independent check)")

        # Canonical comparison is for JavaScript only: a key-bearing text file of another type is compared byte for byte.
        a9, c9 = os.path.join(tmp, "a9"), os.path.join(tmp, "c9")
        shutil.copytree(a, a9)
        shutil.copytree(c, c9)
        open(os.path.join(a9, "key-note.txt"), "w").write('"' + ka + '"')
        open(os.path.join(c9, "key-note.txt"), "w").write('"SyntheticKeyZZZZ9999" ')
        run(a9, c9, 1, ["DIFFERENT", "key-note.txt"], "a key-bearing non-JavaScript file differing only in whitespace is DIFFERENT", (ka,))

        if args.c_tar:
            n = os.path.join(tmp, "new")
            extract(args.c_tar, n)
            kn = tree_key(n)
            secrets = (ka, kn)
            run(a, n, 0, ["IDENTICAL", "key-bearing assets/env-<H>.js: equal after esbuild 0.27.4 minification of both"],
                "the real candidate with another key: IDENTICAL through esbuild canonical equality of the key-bearing chunk", secrets)

            def env_mutation(name, fn):
                d = os.path.join(tmp, name)
                shutil.copytree(n, d)
                env = env_chunk(d)
                t = open(env, encoding="utf-8").read()
                u = fn(t)
                assert u != t, name
                open(env, "w", encoding="utf-8").write(u)
                return d
            for name, fn, label in [
                ("r1", swap_import_names, "two imported names swapped (review probe)"),
                ("r2", rename_export, "the exported name renamed (review probe)"),
                ("r3", lambda t: t.replace('"streets-v2"', '"streets-v3"', 1), "a string changed"),
                ("r4", lambda t: t.replace("DEV:!1", "DEV:!0", 1), "a boolean flipped (DEV:!1 -> DEV:!0)"),
                ("r5", lambda t: t + "'unterminated", "an unterminated string (esbuild rejects it)"),
            ]:
                run(a, env_mutation(name, fn), 1, ["DIFFERENT", "env-<H>.js"], f"the real key-bearing chunk with {label} is DIFFERENT", secrets)

            # Canonical equality is semantic: a textual change that minification folds away is not a difference.
            run(a, env_mutation("r7", lambda t: t.replace("DEV:!1", "DEV:!2", 1)), 0, ["IDENTICAL"],
                "the real key-bearing chunk with a behaviour-free change (DEV:!1 -> DEV:!2, both false) is IDENTICAL", secrets)

            def canonical_non_key(d):
                keyed = {os.path.basename(p) for p in (env_chunk(d),)}
                for f in sorted(os.listdir(os.path.join(d, "assets"))):
                    full = os.path.join(d, "assets", f)
                    if f.endswith(".js") and f not in keyed and kn not in open(full, encoding="utf-8", errors="replace").read():
                        t = open(full, encoding="utf-8", errors="surrogateescape").read()
                        u = wbc.canonical(ESBUILD, t)
                        if u and u != t.encode("utf-8", "surrogateescape"):
                            open(full, "wb").write(u)
                            return f
                raise AssertionError("no non-key chunk changes under minification")
            d = os.path.join(tmp, "r6")
            shutil.copytree(n, d)
            victim = canonical_non_key(d)
            run(a, d, 1, ["DIFFERENT", re.sub(r"-[A-Za-z0-9_-]{8}\.js$", "-<H>.js", victim)],
                "a real non-key chunk replaced by its own esbuild-minified form is DIFFERENT (canonical comparison is for key-bearing chunks only)", secrets)

            for i, (label, sa, sc, want) in enumerate(SNIPPETS):
                da, dc = os.path.join(tmp, f"sa{i}"), os.path.join(tmp, f"sc{i}")
                shutil.copytree(a, da)
                shutil.copytree(n, dc)
                for root, snippet in ((da, sa), (dc, sc)):
                    with open(env_chunk(root), "a", encoding="utf-8") as fh:
                        fh.write("\n" + snippet)
                run(da, dc, want, ["IDENTICAL"] if want == 0 else ["DIFFERENT", "env-<H>.js"],
                    f"snippets: {label} -> {'IDENTICAL' if want == 0 else 'DIFFERENT'}", secrets)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
