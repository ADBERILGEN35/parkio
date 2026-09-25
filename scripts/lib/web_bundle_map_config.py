#!/usr/bin/env python3
"""Classify the MapTiler configuration baked into an extracted web bundle.

Input is the web image's static root (``/usr/share/nginx/html`` copied out of
the exact image the deploy will run). Vite inlines ``import.meta.env`` into the
JS as an object literal; this reads every such literal plus the raw JS text,
without executing anything, and prints one JSON verdict on stdout.

Key values are NEVER printed. The verdict carries only statuses, counts and a
12-hex SHA-256 fingerprint of the baked key so evidence can be tied to an
image without disclosing the key.

Exit codes: 0 = accepted, 1 = rejected (synthetic, missing evidence or
ambiguous), 2 = usage.

What an accepted verdict proves: the bundle bakes exactly one non-empty
VITE_MAPTILER_KEY that is not a known repository synthetic/placeholder value.
It does NOT prove the key is valid or authorized at the provider.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sys

# The class the audit (F-05) and PR #91 incident name: CI image acceptance bakes
# this value (optionally suffixed, e.g. per-run) into an otherwise release-shaped
# bundle.
SYNTHETIC_CLASS_PREFIX = "ci-web-build-security-synthetic"

# Exact placeholder values the repository itself uses for web builds in CI,
# dry-runs and fixtures. None of them is ever a production key.
KNOWN_PLACEHOLDER_VALUES = frozenset(
    {
        "synthetic-non-production-value",
        "SECRET_SENTINEL_MAPTILER_PUBLIC_KEY",
        "ci-public-map-key",
        "fixture-public-map-key",
        "fixture-public-map-key-never-use-in-production",
    }
)

ENV_MEMBER = re.compile(r"""["']?\bVITE_APP_ENV["']?\s*:""")
PAIR = re.compile(r"""["']?(VITE_[A-Z0-9_]+)["']?\s*:\s*"((?:[^"\\]|\\.)*)\"""")
MAX_JS_BYTES = 64 * 1024 * 1024


def is_synthetic(value: str) -> bool:
    v = value.strip()
    if v in KNOWN_PLACEHOLDER_VALUES:
        return True
    return v == SYNTHETIC_CLASS_PREFIX or v.startswith(SYNTHETIC_CLASS_PREFIX + "-") \
        or v.startswith(SYNTHETIC_CLASS_PREFIX + "_") or v.startswith(SYNTHETIC_CLASS_PREFIX + ".")


def env_literals(source: str):
    """Yield dicts for every inlined import.meta.env object literal (mirrors
    frontend/apps/web/scripts/verify-bundle-env.mjs extractInjectedEnv)."""
    for member in ENV_MEMBER.finditer(source):
        start = source.rfind("{", 0, member.start())
        if start == -1:
            continue
        depth = 0
        end = -1
        in_string = False
        quote = ""
        i = start
        while i < len(source):
            ch = source[i]
            if in_string:
                if ch == "\\":
                    i += 1
                elif ch == quote:
                    in_string = False
            elif ch in ("'", '"'):
                in_string = True
                quote = ch
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
            i += 1
        if end == -1:
            continue
        pairs = {k: v for k, v in PAIR.findall(source[start:end + 1])}
        if pairs:
            yield pairs


def js_files(root: str):
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if name.endswith((".js", ".mjs")):
                yield os.path.join(dirpath, name)


def classify(root: str) -> dict:
    verdict = {
        "jsFiles": 0,
        "envObjects": 0,
        "mapKeyStatus": "MISSING",
        "mapKeyFingerprint": None,
        "appEnv": None,
        "syntheticClassInRawJs": False,
        "knownPlaceholderInRawJs": False,
        "accepted": False,
        "reason": "",
    }
    if not os.path.isdir(root):
        verdict["reason"] = "bundle root is missing or unreadable"
        return verdict

    keys = set()
    app_envs = set()
    placeholder_needles = [SYNTHETIC_CLASS_PREFIX] + sorted(KNOWN_PLACEHOLDER_VALUES)
    for path in js_files(root):
        try:
            if os.path.getsize(path) > MAX_JS_BYTES:
                verdict["reason"] = "bundle JS file exceeds the size bound"
                return verdict
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                source = fh.read()
        except OSError:
            verdict["reason"] = "bundle JS file is unreadable"
            return verdict
        verdict["jsFiles"] += 1
        if SYNTHETIC_CLASS_PREFIX in source:
            verdict["syntheticClassInRawJs"] = True
        for needle in placeholder_needles[1:]:
            # Quoted, so a placeholder that merely prefixes another token is not hit.
            if f'"{needle}"' in source or f"'{needle}'" in source:
                verdict["knownPlaceholderInRawJs"] = True
        for env in env_literals(source):
            verdict["envObjects"] += 1
            if "VITE_APP_ENV" in env:
                app_envs.add(env["VITE_APP_ENV"])
            if "VITE_MAPTILER_KEY" in env:
                keys.add(env["VITE_MAPTILER_KEY"])

    verdict["appEnv"] = sorted(app_envs)[0] if len(app_envs) == 1 else (sorted(app_envs) or None)

    if verdict["jsFiles"] == 0:
        verdict["reason"] = "no JS files in bundle (not a web runtime image?)"
        return verdict
    if verdict["envObjects"] == 0:
        verdict["reason"] = "no inlined import.meta.env object found; cannot establish baked map config"
        return verdict
    if len(keys) > 1:
        verdict["mapKeyStatus"] = "CONFLICTING"
        verdict["reason"] = "bundle bakes more than one distinct VITE_MAPTILER_KEY"
        if any(is_synthetic(k) for k in keys):
            verdict["mapKeyStatus"] = "SYNTHETIC"
        return verdict
    if not keys:
        verdict["reason"] = "VITE_MAPTILER_KEY is missing from the inlined env"
        return verdict
    key = next(iter(keys))
    if key.strip() == "":
        verdict["mapKeyStatus"] = "EMPTY"
        verdict["reason"] = "VITE_MAPTILER_KEY is empty in the bundle"
        return verdict
    verdict["mapKeyFingerprint"] = hashlib.sha256(key.encode("utf-8")).hexdigest()[:12]
    if is_synthetic(key):
        verdict["mapKeyStatus"] = "SYNTHETIC"
        verdict["reason"] = "bundle bakes a known synthetic/placeholder MapTiler key"
        return verdict
    if verdict["syntheticClassInRawJs"] or verdict["knownPlaceholderInRawJs"]:
        verdict["mapKeyStatus"] = "PRESENT"
        verdict["reason"] = "bundle JS contains a known synthetic/placeholder map value outside the env object"
        return verdict
    verdict["mapKeyStatus"] = "PRESENT"
    verdict["accepted"] = True
    verdict["reason"] = "one non-synthetic VITE_MAPTILER_KEY baked"
    return verdict


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] in ("-h", "--help"):
        print("usage: web_bundle_map_config.py <extracted-web-root>", file=sys.stderr)
        return 2
    verdict = classify(argv[1])
    print(json.dumps(verdict, sort_keys=True))
    return 0 if verdict["accepted"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
