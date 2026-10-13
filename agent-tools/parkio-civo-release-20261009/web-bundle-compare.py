#!/usr/bin/env python3
"""MapTiler fix plan, step 8.5: prove that two web bundles differ only in the baked MapTiler key.

  python3 -I web-bundle-compare.py --a-dir OLD_HTML --b-dir NEW_HTML --esbuild ESBUILD [--json OUT]

OLD_HTML and NEW_HTML are copies of /usr/share/nginx/html from the two images, made with `docker create` + `docker cp`
(never started). ESBUILD is the esbuild 0.27.4 linux-x64 binary, the version the web build minifies with
(frontend/pnpm-lock.yaml at f888a81b: vite@6.4.3>esbuild 0.27.4). Its SHA-256 is pinned below and checked before
anything else; fetch-esbuild-0.27.4.sh downloads it from the npm registry and verifies the lockfile's integrity value.
Each tree must bake exactly one VITE_MAPTILER_KEY value. Before comparison, each tree is normalized with its own values:
  - every occurrence of the tree's key value -> <KEY>;
  - every content-hashed asset name `<name>-<8 chars>.<ext>` under assets/ -> `<name>-<H>.<ext>`, in file names and in
    every reference to it inside text files (exact names of that tree only);
  - sw.js `CACHE_VERSION = '<hex>'` -> `CACHE_VERSION = '<V>'` (derived from the bundle content, so it moves with the key).
Two files with the same normalized path would make the comparison ambiguous; that input is refused, and so is any
entry that is not a regular file or a directory (a symbolic link could point anywhere).
The normalized trees must be identical: the same multiset of (path, SHA-256 of content, file mode), with directories
and their modes included, with one exception, the key-bearing JavaScript chunks (the .js files that bake the key).
The build minifies with esbuild, which picks short local names by character frequency, so a different key string
renames locals inside the chunk that holds it. A key-bearing chunk with the same normalized path and mode in both trees
is therefore also accepted when the pinned esbuild, printing both normalized texts with the same options
(ESBUILD_ARGS), produces byte-identical output.
What those options do:
  - parse the module and print it again without whitespace;
  - drop comments, except legal ones;
  - ignore /*@__PURE__*/-style annotations;
  - rename bound local symbols, from esbuild's scope analysis.
There is deliberately no syntax minification. --minify would delete code the browser still runs, such as an
annotated pure call whose result is unused, and an unused import specifier, which the browser still links (review
round 4, D1). So no code is removed, and everything except local names stays as written: property names, object keys
(shorthand ones included), class members, import and export names, free (global) identifiers, strings, regular
expressions, templates and statement boundaries.
What compares equal:
  - local names;
  - whitespace, non-legal comments, a BOM and the //# devtools comments;
  - the parser's own literal printing (`!1` and `!2` both print as `false`);
  - the `name` and source text of local functions and classes, which the original minified build had already made
    arbitrary.
Input esbuild does not accept makes the result DIFFERENT. Every other difference fails and is listed by path; contents
are never printed. Exit 0 = identical, 1 = different or invalid input, 2 = usage (including a wrong esbuild).

Reference: the two candidate web images built from the same source and key on 2026-10-08 (run 37788374271) and
2026-10-12 (run 38263294393) are byte-identical (108 files), so the Vite build itself is deterministic.
"""
import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tempfile
from collections import Counter

KEY_RE = re.compile(r'VITE_MAPTILER_KEY\s*:\s*"([^"]*)"')
HASHED = re.compile(r"^(assets/(?:.*/)?)([^/]+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$")
CACHE_RE = re.compile(r"(CACHE_VERSION\s*=\s*)(['\"])[0-9A-Za-z_-]+\2")
TEXT_EXT = {".js", ".mjs", ".css", ".html", ".json", ".webmanifest", ".svg", ".txt", ".map", ".xml"}


ESBUILD_VERSION = "0.27.4"
# package/bin/esbuild of @esbuild/linux-x64@0.27.4 (npm tarball sha512 integrity
# S5qOXrKV8BQEzJPVxAwnryi2+Iq5pB40gTEIT69BQONqR7JH1EPIcQ/Uiv9mCnn05jff9umq/5nqzxlqTOg9NA==, frontend/pnpm-lock.yaml at f888a81b)
ESBUILD_SHA256 = "5186a0791db7cff10adf1fef03dc4790db502eaaff86117664679412fedeccae"
# No --minify: it implies --minify-syntax, which deletes code (review round 4, D1). See the docstring.
ESBUILD_ARGS = ("--minify-whitespace", "--minify-identifiers", "--ignore-annotations", "--format=esm", "--loader=js",
                "--platform=browser", "--target=esnext", "--charset=utf8", "--legal-comments=inline", "--log-level=error")


def esbuild_problem(path):
    """None when `path` is the pinned esbuild binary, else the reason it is refused."""
    try:
        with open(path, "rb") as fh:
            digest = hashlib.sha256(fh.read()).hexdigest()
    except OSError:
        return "is not readable"
    if digest != ESBUILD_SHA256:
        return f"has SHA-256 {digest[:12]}, not the pinned esbuild {ESBUILD_VERSION} linux-x64 ({ESBUILD_SHA256[:12]})"
    if not os.access(path, os.X_OK):
        return "is not executable"
    return None


def canonical(esbuild, text):
    """esbuild's canonical print of an ES module text (ESBUILD_ARGS), or None when esbuild does not accept it. Runs in
    an empty directory with an empty environment, reading stdin only; `esbuild` must be an absolute path."""
    with tempfile.TemporaryDirectory() as cwd:
        try:
            p = subprocess.run([esbuild] + list(ESBUILD_ARGS), input=text.encode("utf-8", "surrogateescape"),
                               capture_output=True, cwd=cwd, env={}, timeout=120)
        except (OSError, subprocess.TimeoutExpired):
            return None
    return p.stdout if p.returncode == 0 and p.stdout else None


def read_tree(root):
    """({relative path: (bytes, mode)} of the regular files, {relative path: mode} of the directories). Raises
    ValueError for any other entry, a symbolic link included: links are neither followed nor compared."""
    files, dirs = {}, {}
    for dirpath, dirnames, names in os.walk(root):
        for n in sorted(dirnames) + sorted(names):
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            st = os.lstat(full)
            if stat.S_ISDIR(st.st_mode):
                dirs[rel] = st.st_mode & 0o7777
            elif stat.S_ISREG(st.st_mode):
                with open(full, "rb") as fh:
                    files[rel] = (fh.read(), st.st_mode & 0o7777)
            else:
                raise ValueError(f"not a regular file or directory: {rel}")
    return files, dirs


def normalize(tree):
    """(Counter of normalized (path, sha256, mode) entries, info, {normalized path: text} of the key-bearing files).
    Raises ValueError on invalid input."""
    files, dirs = tree
    keys = set()
    for p, (b, _) in files.items():
        if os.path.splitext(p)[1] in TEXT_EXT:
            keys.update(m.group(1) for m in KEY_RE.finditer(b.decode("utf-8", "replace")))
    if len(keys) != 1:
        raise ValueError(f"expected exactly one baked VITE_MAPTILER_KEY value, found {len(keys)}")
    key = next(iter(keys))
    if not re.fullmatch(r"[A-Za-z0-9]{20}", key):
        raise ValueError("the baked key does not have the MapTiler shape")
    renames, seen = {}, {}
    for p in sorted(files):
        m = HASHED.match(p)
        norm = (m.group(1) + m.group(2) + "-<H>." + m.group(4)) if m else p
        if m:
            renames[m.group(2) + "-" + m.group(3) + "." + m.group(4)] = m.group(2) + "-<H>." + m.group(4)
        if norm in seen:
            # two files with one normalized path (two chunks with one base name, or a file literally named like a
            # normalized one) would make references and the comparison ambiguous
            raise ValueError(f"normalized asset names collide: {seen[norm]} and {p}")
        seen[norm] = p
    # longest names first, so a name that contains another is replaced whole
    ordered = sorted(renames, key=len, reverse=True)
    pattern = re.compile("|".join(re.escape(n) for n in ordered)) if ordered else None
    out, keyed = [("dir:" + d, "", mode) for d, mode in dirs.items()], {}
    for p, (b, mode) in files.items():
        m = HASHED.match(p)
        np = (m.group(1) + m.group(2) + "-<H>." + m.group(4)) if m else p
        if os.path.splitext(p)[1] in TEXT_EXT:
            raw = b.decode("utf-8", "surrogateescape")
            bears_key = key in raw
            t = raw.replace(key, "<KEY>")
            if pattern:
                t = pattern.sub(lambda x: renames[x.group(0)], t)
            if p == "sw.js":
                t = CACHE_RE.sub(lambda x: x.group(1) + x.group(2) + "<V>" + x.group(2), t)
            if bears_key:
                keyed[np] = (t, mode)
            nb = t.encode("utf-8", "surrogateescape")
        else:
            nb = b
        out.append((np, hashlib.sha256(nb).hexdigest(), mode))
    info = {"files": len(files), "hashedAssets": len(renames), "keyBearing": sorted(keyed),
            "keyFingerprint": hashlib.sha256(key.encode()).hexdigest()[:12]}
    return Counter(out), info, keyed


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--a-dir", required=True)
    ap.add_argument("--b-dir", required=True)
    ap.add_argument("--esbuild", required=True)
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    for d in (a.a_dir, a.b_dir):
        if not os.path.isdir(d):
            print(f"usage: not a directory: {d}", file=sys.stderr)
            return 2
    a.esbuild = os.path.abspath(a.esbuild)      # the file checked is the file run (review round 4, D2)
    problem = esbuild_problem(a.esbuild)
    if problem:
        print(f"usage: --esbuild {problem}", file=sys.stderr)
        return 2
    rec = {"tool": "web-bundle-compare"}
    try:
        ta, tb = read_tree(a.a_dir), read_tree(a.b_dir)
        fa, fb = ta[0], tb[0]
        na, ia, ka = normalize(ta)
        nb, ib, kb = normalize(tb)
    except ValueError as e:
        print(f"FAIL invalid input: {e}")
        rec["verdict"] = "FAIL"; rec["error"] = str(e)
        if a.json:
            json.dump(rec, open(a.json, "w"), indent=1)
        return 1
    raw_changed = sorted(p for p in set(fa) & set(fb) if fa[p] != fb[p])
    only_a = sorted({e[0] for e in (na - nb).elements()})
    only_b = sorted({e[0] for e in (nb - na).elements()})
    # Key-bearing chunks whose esbuild-minified forms are byte-identical (esbuild's frequency-based local names).
    # Normalized paths are unique within a tree (collisions are refused above).
    renamed = {}
    for p in sorted(set(only_a) & set(only_b)):
        if p in ka and p in kb and p.endswith((".js", ".mjs")) and ka[p][1] == kb[p][1]:
            ca, cb = canonical(a.esbuild, ka[p][0]), canonical(a.esbuild, kb[p][0])
            if ca is not None and ca == cb:
                renamed[p] = hashlib.sha256(ca).hexdigest()[:12]
    only_a = [p for p in only_a if p not in renamed]
    only_b = [p for p in only_b if p not in renamed]
    rec["canonicalEqual"] = renamed
    rec["esbuild"] = {"version": ESBUILD_VERSION, "sha256": ESBUILD_SHA256, "args": list(ESBUILD_ARGS)}
    rec.update(a=ia, b=ib, rawDifferingPaths=len(raw_changed) + len(set(fa) ^ set(fb)),
               normalizedOnlyInA=only_a, normalizedOnlyInB=only_b)
    print(f"a: {ia['files']} files, {ia['hashedAssets']} hashed assets, key fp {ia['keyFingerprint']}")
    print(f"b: {ib['files']} files, {ib['hashedAssets']} hashed assets, key fp {ib['keyFingerprint']}")
    print(f"raw differences (names or bytes): {rec['rawDifferingPaths']}")
    for p, digest in renamed.items():
        print(f"key-bearing {p}: equal after esbuild {ESBUILD_VERSION} canonical printing of both (canonical sha256 {digest})")
    if not only_a and not only_b:
        rec["verdict"] = "IDENTICAL"
        print("IDENTICAL after normalizing the key, the hashed asset names and sw.js CACHE_VERSION")
        rc = 0
    else:
        rec["verdict"] = "DIFFERENT"
        print("DIFFERENT after normalization:")
        for p in only_a:
            print(f"  only in a (or content differs): {p}")
        for p in only_b:
            print(f"  only in b (or content differs): {p}")
        rc = 1
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(rec, fh, indent=1, sort_keys=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())
