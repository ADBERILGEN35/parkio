#!/usr/bin/env python3
"""MapTiler fix plan, step 8.5: prove that two web bundles differ only in the baked MapTiler key.

  python3 -I web-bundle-compare.py --a-dir OLD_HTML --b-dir NEW_HTML [--json OUT]

OLD_HTML and NEW_HTML are copies of /usr/share/nginx/html from the two images, made with `docker create` + `docker cp`
(never started). Each tree must bake exactly one VITE_MAPTILER_KEY value. Before comparison, each tree is normalized
with its own values:
  - every occurrence of the tree's key value -> <KEY>;
  - every content-hashed asset name `<name>-<8 chars>.<ext>` under assets/ -> `<name>-<H>.<ext>`, in file names and in
    every reference to it inside text files (exact names of that tree only);
  - sw.js `CACHE_VERSION = '<hex>'` -> `CACHE_VERSION = '<V>'` (derived from the bundle content, so it moves with the key).
Two hashed assets with the same normalized name would make references ambiguous; that input is refused.
The normalized trees must be identical: the same multiset of (path, SHA-256 of content). Any other difference fails
and is listed by path (contents are never printed). Exit 0 = identical, 1 = different or invalid input, 2 = usage.

Reference: the two candidate web images built from the same source and key on 2026-10-08 (run 37788374271) and
2026-10-12 (run 38263294393) are byte-identical (108 files), so the Vite build itself is deterministic.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from collections import Counter

KEY_RE = re.compile(r'VITE_MAPTILER_KEY\s*:\s*"([^"]*)"')
HASHED = re.compile(r"^(assets/(?:.*/)?)([^/]+)-([A-Za-z0-9_-]{8})\.([A-Za-z0-9]+)$")
CACHE_RE = re.compile(r"(CACHE_VERSION\s*=\s*)(['\"])[0-9A-Za-z_-]+\2")
TEXT_EXT = {".js", ".mjs", ".css", ".html", ".json", ".webmanifest", ".svg", ".txt", ".map", ".xml"}


def read_tree(root):
    files = {}
    for dirpath, _, names in os.walk(root):
        for n in names:
            full = os.path.join(dirpath, n)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as fh:
                files[rel] = fh.read()
    return files


def normalize(files):
    """Returns (normalized {path: sha256}, info). Raises ValueError on invalid input."""
    keys = set()
    for p, b in files.items():
        if os.path.splitext(p)[1] in TEXT_EXT:
            keys.update(m.group(1) for m in KEY_RE.finditer(b.decode("utf-8", "replace")))
    if len(keys) != 1:
        raise ValueError(f"expected exactly one baked VITE_MAPTILER_KEY value, found {len(keys)}")
    key = next(iter(keys))
    if not re.fullmatch(r"[A-Za-z0-9]{20}", key):
        raise ValueError("the baked key does not have the MapTiler shape")
    renames, seen = {}, {}
    for p in files:
        m = HASHED.match(p)
        if m:
            renames[m.group(2) + "-" + m.group(3) + "." + m.group(4)] = m.group(2) + "-<H>." + m.group(4)
            norm = m.group(1) + m.group(2) + "-<H>." + m.group(4)
            if norm in seen:
                # two chunks with one base name would make references ambiguous after normalization
                raise ValueError(f"normalized asset names collide: {seen[norm]} and {p}")
            seen[norm] = p
    # longest names first, so a name that contains another is replaced whole
    ordered = sorted(renames, key=len, reverse=True)
    pattern = re.compile("|".join(re.escape(n) for n in ordered)) if ordered else None
    out = []
    for p, b in files.items():
        m = HASHED.match(p)
        np = (m.group(1) + m.group(2) + "-<H>." + m.group(4)) if m else p
        if os.path.splitext(p)[1] in TEXT_EXT:
            t = b.decode("utf-8", "surrogateescape").replace(key, "<KEY>")
            if pattern:
                t = pattern.sub(lambda x: renames[x.group(0)], t)
            if p == "sw.js":
                t = CACHE_RE.sub(lambda x: x.group(1) + x.group(2) + "<V>" + x.group(2), t)
            nb = t.encode("utf-8", "surrogateescape")
        else:
            nb = b
        out.append((np, hashlib.sha256(nb).hexdigest()))
    info = {"files": len(files), "hashedAssets": len(renames), "keyFingerprint": hashlib.sha256(key.encode()).hexdigest()[:12]}
    return Counter(out), info


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--a-dir", required=True)
    ap.add_argument("--b-dir", required=True)
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    for d in (a.a_dir, a.b_dir):
        if not os.path.isdir(d):
            print(f"usage: not a directory: {d}", file=sys.stderr)
            return 2
    fa, fb = read_tree(a.a_dir), read_tree(a.b_dir)
    rec = {"tool": "web-bundle-compare"}
    try:
        na, ia = normalize(fa)
        nb, ib = normalize(fb)
    except ValueError as e:
        print(f"FAIL invalid input: {e}")
        rec["verdict"] = "FAIL"; rec["error"] = str(e)
        if a.json:
            json.dump(rec, open(a.json, "w"), indent=1)
        return 1
    raw_changed = sorted(p for p in set(fa) & set(fb) if fa[p] != fb[p])
    only_a = sorted({p for p, _ in (na - nb).elements()})
    only_b = sorted({p for p, _ in (nb - na).elements()})
    rec.update(a=ia, b=ib, rawDifferingPaths=len(raw_changed) + len(set(fa) ^ set(fb)),
               normalizedOnlyInA=only_a, normalizedOnlyInB=only_b)
    print(f"a: {ia['files']} files, {ia['hashedAssets']} hashed assets, key fp {ia['keyFingerprint']}")
    print(f"b: {ib['files']} files, {ib['hashedAssets']} hashed assets, key fp {ib['keyFingerprint']}")
    print(f"raw differences (names or bytes): {rec['rawDifferingPaths']}")
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
