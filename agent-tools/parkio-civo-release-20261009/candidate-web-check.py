#!/usr/bin/env python3
"""MapTiler fix plan, step 2: check a candidate web image artifact before any publication.

  python3 -I candidate-web-check.py --artifact-dir DIR --expect-fp HEX12 [--no-probe] [--json OUT]

DIR holds a downloaded `candidate-image-web-<sha12>` artifact: image.tar.gz (docker save, gzip -1), inspect.json and
SHA256SUMS. Download it with: gh run download <run> -n candidate-image-web-<sha12> -D DIR

Checks, all read-only:
  1. SHA256SUMS verifies.
  2. From the image layers, in order and with whiteouts applied, the files under usr/share/nginx/html/assets/*.js
     bake exactly one VITE_MAPTILER_KEY value, of shape [A-Za-z0-9]{20}, whose SHA-256 fingerprint (first 12 hex) equals
     --expect-fp. The key value is never printed or written.
  3. Unless --no-probe: MapTiler answers 200 to style.json and tiles.json for Origin https://app.parkio.dev, and 403 with
     "Key usage restricted" for Origin https://example.com (the key is restricted, not open).
  4. Records the libexpat and pcre2 package versions from lib/apk/db/installed (for the deploy-time comparison).
Exit 0 = PASS, 1 = FAIL, 2 = usage.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
import re
import sys
import tarfile
import urllib.error
import urllib.request

ASSETS = re.compile(r"^usr/share/nginx/html/assets/[^/]+\.js$")
KEY_RE = re.compile(r'VITE_MAPTILER_KEY\s*:\s*"([^"]*)"')
APK_DB = "lib/apk/db/installed"


def fp(value):
    return hashlib.sha256(value.encode()).hexdigest()[:12]


def verify_sums(d):
    lines = [l for l in open(os.path.join(d, "SHA256SUMS"), encoding="utf-8").read().splitlines() if l.strip()]
    bad = []
    for line in lines:
        digest, name = line.split(None, 1)
        name = name.lstrip("*")
        h = hashlib.sha256()
        with open(os.path.join(d, name), "rb") as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest() != digest:
            bad.append(name)
    return len(lines), bad


def layer_files(image_tar):
    """Yield (layer index, TarFile) for each layer of a docker-save archive, in manifest order."""
    with tarfile.open(image_tar, "r:*") as outer:
        manifest = json.load(outer.extractfile("manifest.json"))
        if len(manifest) != 1:
            raise SystemExit(f"FAIL: the archive holds {len(manifest)} images, expected 1")
        for i, layer in enumerate(manifest[0]["Layers"]):
            raw = outer.extractfile(layer).read()
            if raw[:2] == b"\x1f\x8b":
                raw = gzip.decompress(raw)
            yield i, tarfile.open(fileobj=io.BytesIO(raw), mode="r:")


def final_files(image_tar, wanted):
    """{path: bytes} of the final filesystem for the paths `wanted(path)` accepts, whiteouts applied."""
    files = {}
    for _, layer in layer_files(image_tar):
        for m in layer.getmembers():
            name = m.name.lstrip("./")
            base = os.path.basename(name)
            parent = os.path.dirname(name)
            if base == ".wh..wh..opq":
                for p in [p for p in files if p.startswith(parent + "/")]:
                    del files[p]
                continue
            if base.startswith(".wh."):
                target = os.path.join(parent, base[4:])
                for p in [p for p in files if p == target or p.startswith(target + "/")]:
                    del files[p]
                continue
            if m.isfile() and wanted(name):
                files[name] = layer.extractfile(m).read()
    return files


def probe(key, origin):
    out = {}
    for name, path in (("style", "maps/streets-v2/style.json"), ("tilejson", "tiles/v3/tiles.json")):
        req = urllib.request.Request(f"https://api.maptiler.com/{path}?key={key}",
                                     headers={"Origin": origin, "Referer": origin + "/", "User-Agent": "parkio-candidate-web-check"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                r.read()
                out[name] = {"status": r.status}
        except urllib.error.HTTPError as e:
            out[name] = {"status": e.code, "body": e.read()[:60].decode("utf-8", "replace").replace(key, "<key>")}
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--artifact-dir", required=True)
    ap.add_argument("--expect-fp", required=True)
    ap.add_argument("--no-probe", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    if not re.fullmatch(r"[0-9a-f]{12}", a.expect_fp):
        print("usage: --expect-fp takes 12 lowercase hex", file=sys.stderr)
        return 2
    rec = {"tool": "candidate-web-check", "expectFp": a.expect_fp, "checks": []}

    def check(ok, what):
        rec["checks"].append({"pass": bool(ok), "check": what})
        print(("PASS " if ok else "FAIL ") + what)
        return ok

    n, bad = verify_sums(a.artifact_dir)
    check(n > 0 and not bad, f"SHA256SUMS verifies ({n} files{', mismatched ' + ','.join(bad) if bad else ''})")
    image = os.path.join(a.artifact_dir, "image.tar.gz")
    files = final_files(image, lambda p: bool(ASSETS.match(p)) or p == APK_DB)
    assets = {p: b for p, b in files.items() if ASSETS.match(p)}
    keys = set()
    for body in assets.values():
        keys.update(m.group(1) for m in KEY_RE.finditer(body.decode("utf-8", "replace")))
    fps = sorted(fp(k) for k in keys)
    rec["assets"] = len(assets)
    rec["bakedFingerprints"] = fps
    check(len(assets) > 0, f"bundle assets found in the final filesystem ({len(assets)} js files)")
    check(len(keys) == 1, f"exactly one baked VITE_MAPTILER_KEY value (found {len(keys)}: fp {fps})")
    key = next(iter(keys)) if len(keys) == 1 else None
    ok_key = bool(key) and check(re.fullmatch(r"[A-Za-z0-9]{20}", key) is not None, "the key has the MapTiler shape [A-Za-z0-9]{20}") \
        and check(fp(key) == a.expect_fp, f"the key fingerprint equals {a.expect_fp}")
    apk = files.get(APK_DB, b"").decode("utf-8", "replace")
    pkgs = dict(re.findall(r"(?m)^P:(\S+)\nV:(\S+)", apk))
    rec["apk"] = {p: pkgs.get(p) for p in ("libexpat", "pcre2", "nginx", "musl", "openssl", "libssl3", "libcrypto3")}
    print("apk versions:", json.dumps(rec["apk"], sort_keys=True))
    if not a.no_probe and ok_key:
        prod = probe(key, "https://app.parkio.dev")
        foreign = probe(key, "https://example.com")
        rec["probe"] = {"app.parkio.dev": prod, "example.com": foreign}
        check(all(v["status"] == 200 for v in prod.values()), f"MapTiler accepts the key for https://app.parkio.dev ({prod})")
        check(all(v["status"] == 403 and v.get("body", "").startswith("Key usage restricted") for v in foreign.values()),
              f"MapTiler restricts the key for a foreign origin ({foreign})")
    elif a.no_probe:
        rec["probe"] = "skipped"
    del key
    rec["verdict"] = "PASS" if all(c["pass"] for c in rec["checks"]) else "FAIL"
    print("VERDICT", rec["verdict"])
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(rec, fh, indent=1, sort_keys=True)
    return 0 if rec["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
