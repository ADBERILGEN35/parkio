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
The normalized trees must be identical: the same multiset of (path, SHA-256 of content). There is one exception, the
files that bake the key (the key-bearing chunks). esbuild picks its short local names by character frequency, so a
different key string renames locals inside the chunk that holds it. A pair of key-bearing files with the same
normalized path is therefore also accepted when their JavaScript tokens are identical apart from a consistent,
bijective renaming of short identifiers. That means:
  - the same token kinds in the same order;
  - identical strings, template text, numbers, punctuation and regular expressions;
  - identical keywords and every identifier of 3 or more characters;
  - identifiers of 1-2 characters may differ, but only through one consistent bijection.
A lexing doubt makes the result DIFFERENT, never IDENTICAL. Any other difference fails and is listed by path (contents
are never printed). Exit 0 = identical, 1 = different or invalid input, 2 = usage.

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
RESERVED = {"if", "in", "do", "of", "as", "let", "var", "new", "for", "try", "get", "set", "this", "null", "true", "void",
            "else", "case", "with", "enum", "false", "break", "catch", "class", "const", "super", "throw", "while", "yield",
            "await", "async", "delete", "export", "import", "return", "static", "switch", "typeof", "default", "extends",
            "finally", "package", "private", "continue", "debugger", "function", "arguments", "interface", "protected",
            "implements", "instanceof", "undefined"}
IDENT = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
NUMBER = re.compile(r"(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|(?:\d[\d_]*\.?[\d_]*|\.\d[\d_]*)(?:[eE][+-]?\d+)?)n?")
PUNCT = re.compile(r">>>=|\.\.\.|===|!==|\*\*=|<<=|>>=|>>>|&&=|\|\|=|\?\?=|=>|==|!=|<=|>=|&&|\|\||\?\?|\?\.|\+\+|--|\+=|-=|\*=|/=|%=|&=|\|=|\^=|\*\*|<<|>>|[{}()\[\];,<>+\-*/%&|^!~?:=.@#]")


class LexDoubt(Exception):
    pass


def js_tokens(text):
    """(kind, value) tokens of minified JavaScript. Strings and regular expressions stay whole; template literals are
    split into their text parts and the tokens of their expressions. Raises LexDoubt when the input does not lex."""
    out, i, n, stack = [], 0, len(text), []      # stack: brace depth of each open template expression

    def regex_allowed():
        if not out:
            return True
        kind, val = out[-1]
        if kind in ("num", "str", "regex", "tpl"):
            return False
        if kind == "id":
            return val in RESERVED and val not in ("this", "super", "null", "true", "false", "undefined")
        return val not in (")", "]", "}")

    while i < n:
        c = text[i]
        if c in " \t\r\n":
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            if j < 0:
                raise LexDoubt("unterminated comment")
            out.append(("comment", text[i:j + 2]))
            i = j + 2
            continue
        if c in "'\"":
            j = i + 1
            while j < n and text[j] != c:
                j += 2 if text[j] == "\\" else 1
            if j >= n:
                raise LexDoubt("unterminated string")
            out.append(("str", text[i:j + 1]))
            i = j + 1
            continue
        if c == "`" or (c == "}" and stack and stack[-1] == 0):
            if c == "}":
                stack.pop()
            j, part = i + 1, []
            while j < n:
                if text[j] == "\\":
                    part.append(text[j:j + 2])
                    j += 2
                    continue
                if text[j] == "`":
                    out.append(("tpl", "".join(part) + "`"))
                    j += 1
                    break
                if text.startswith("${", j):
                    out.append(("tpl", "".join(part) + "${"))
                    stack.append(0)
                    j += 2
                    break
                part.append(text[j])
                j += 1
            else:
                raise LexDoubt("unterminated template")
            i = j
            continue
        if c == "{" and stack:
            stack[-1] += 1
        if c == "}" and stack:
            stack[-1] -= 1
        if c == "/" and regex_allowed():
            j, in_class = i + 1, False
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "[":
                    in_class = True
                elif text[j] == "]":
                    in_class = False
                elif text[j] == "/" and not in_class:
                    break
                elif text[j] == "\n":
                    raise LexDoubt("unterminated regular expression")
                j += 1
            else:
                raise LexDoubt("unterminated regular expression")
            m = IDENT.match(text, j + 1)
            end = m.end() if m else j + 1
            out.append(("regex", text[i:end]))
            i = end
            continue
        m = IDENT.match(text, i)
        if m:
            out.append(("id", m.group(0)))
            i = m.end()
            continue
        m = NUMBER.match(text, i)
        if m and m.group(0):
            out.append(("num", m.group(0)))
            i = m.end()
            continue
        m = PUNCT.match(text, i)
        if m:
            out.append(("p", m.group(0)))
            i = m.end()
            continue
        raise LexDoubt(f"unexpected character at {i}")
    if stack:
        raise LexDoubt("unterminated template expression")
    return out


def renamed_equivalent(a, b):
    """(ok, renamed): True when the two texts tokenize identically apart from a consistent, bijective renaming of
    identifiers of 1-2 characters that are not reserved words."""
    try:
        ta, tb = js_tokens(a), js_tokens(b)
    except LexDoubt:
        return False, 0
    if len(ta) != len(tb):
        return False, 0
    ab, ba = {}, {}
    for (ka, va), (kb, vb) in zip(ta, tb):
        if ka != kb:
            return False, 0
        if ka != "id":
            if va != vb:
                return False, 0
            continue
        if va in RESERVED or vb in RESERVED or len(va) > 2 or len(vb) > 2:
            if va != vb:
                return False, 0
            continue
        if ab.setdefault(va, vb) != vb or ba.setdefault(vb, va) != va:
            return False, 0
    return True, sum(1 for k, v in ab.items() if k != v)



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
    out, keyed = [], {}
    for p, b in files.items():
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
                keyed[np] = t
            nb = t.encode("utf-8", "surrogateescape")
        else:
            nb = b
        out.append((np, hashlib.sha256(nb).hexdigest()))
    info = {"files": len(files), "hashedAssets": len(renames), "keyBearing": sorted(keyed),
            "keyFingerprint": hashlib.sha256(key.encode()).hexdigest()[:12]}
    return Counter(out), info, keyed


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
        na, ia, ka = normalize(fa)
        nb, ib, kb = normalize(fb)
    except ValueError as e:
        print(f"FAIL invalid input: {e}")
        rec["verdict"] = "FAIL"; rec["error"] = str(e)
        if a.json:
            json.dump(rec, open(a.json, "w"), indent=1)
        return 1
    raw_changed = sorted(p for p in set(fa) & set(fb) if fa[p] != fb[p])
    only_a = sorted({p for p, _ in (na - nb).elements()})
    only_b = sorted({p for p, _ in (nb - na).elements()})
    # Key-bearing files that differ only by a consistent renaming of short locals (esbuild's frequency-based names).
    renamed = {}
    for p in sorted(set(only_a) & set(only_b)):
        once = sum(1 for q, _ in na.elements() if q == p) == 1 and sum(1 for q, _ in nb.elements() if q == p) == 1
        if p in ka and p in kb and once:
            same, count = renamed_equivalent(ka[p], kb[p])
            if same:
                renamed[p] = count
    only_a = [p for p in only_a if p not in renamed]
    only_b = [p for p in only_b if p not in renamed]
    rec["renamedEquivalent"] = renamed
    rec.update(a=ia, b=ib, rawDifferingPaths=len(raw_changed) + len(set(fa) ^ set(fb)),
               normalizedOnlyInA=only_a, normalizedOnlyInB=only_b)
    print(f"a: {ia['files']} files, {ia['hashedAssets']} hashed assets, key fp {ia['keyFingerprint']}")
    print(f"b: {ib['files']} files, {ib['hashedAssets']} hashed assets, key fp {ib['keyFingerprint']}")
    print(f"raw differences (names or bytes): {rec['rawDifferingPaths']}")
    for p, count in renamed.items():
        print(f"key-bearing {p}: equal apart from a consistent renaming of {count} short local name(s)")
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
