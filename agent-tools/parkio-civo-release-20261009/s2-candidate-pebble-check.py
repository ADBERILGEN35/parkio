#!/usr/bin/env python3
"""S2 - pebble in the candidate images: final filesystem versus base layers, plus the candidate's own scan (read-only).

Coordinator workstation (local WSL), on downloaded "Candidate images" artifacts (candidate-image-<service>-<sha12>):
  python3 -I s2-candidate-pebble-check.py <artifact dir> [<artifact dir> ...]

Each artifact directory holds image.tar.gz (docker save | gzip), inspect.json, trivy.json, trivy-summary.json,
trivy-library.txt, trivy-critical.txt and SHA256SUMS, as .github/actions/candidate-image-build writes them. For each:
  - SHA256SUMS verifies;
  - the image's layers are read in order from the docker-save archive (nothing is loaded into Docker): every layer that
    adds usr/bin/pebble (size, SHA-256, Go version and module from the embedded build information; never executed) and
    every layer that deletes it (whiteout usr/bin/.wh.pebble, or an opaque usr/bin) is listed, and the final state is
    computed - this separates the final runtime filesystem from what the base layers still carry;
  - the candidate's own scan: trivy-summary.json (critical_gate and library_gate as Security CI applies them) and whether
    any scan target (trivy.json Results, trivy-library.txt) is usr/bin/pebble; the image history in trivy.json names the
    removal command and is not a finding.
It writes nothing except an optional JSON record (S2_OUT=<file>). Exit 0: every image's final filesystem has no
usr/bin/pebble and both gates passed; 1: otherwise or a check could not be completed.
"""
import gzip
import hashlib
import io
import json
import os
import re
import sys
import tarfile

PEBBLE = "usr/bin/pebble"
WHITEOUT = "usr/bin/.wh.pebble"
OPAQUE = "usr/bin/.wh..wh..opq"
GO_MAGIC = b"\xff Go buildinf:"
FIXED = {(1, 26): 9, (1, 27): 2}  # Trivy's fixed versions for CVE-2026-78667, CVE-2026-78669, CVE-2026-97031


def uvarint_string(data):
    n = shift = 0
    for idx, b in enumerate(data[:10]):
        n |= (b & 0x7F) << shift
        if b < 0x80:
            start = idx + 1
            if start + n > len(data):
                return None, b""
            return data[start:start + n], data[start + n:]
        shift += 7
    return None, b""


def go_build_info(binary):
    i = binary.find(GO_MAGIC)
    while i != -1:
        header = binary[i:i + 32]
        if len(header) == 32 and header[14] in (4, 8) and header[15] & 0x2:
            vers, rest = uvarint_string(binary[i + 32:])
            mod, _ = uvarint_string(rest)
            if vers and vers.startswith(b"go"):
                info = {"go": vers.decode("ascii", "replace"), "module": None, "moduleVersion": None}
                if mod and len(mod) >= 33 and mod[len(mod) - 17:len(mod) - 16] == b"\n":
                    for line in mod[16:len(mod) - 16].decode("utf-8", "replace").splitlines():
                        cols = line.split("\t")
                        if cols[0] == "mod" and len(cols) > 2:
                            info["module"], info["moduleVersion"] = cols[1], cols[2]
                return info
        i = binary.find(GO_MAGIC, i + 1)
    return None


def classify(go):
    m = re.match(r"go(\d+)\.(\d+)(?:\.(\d+))?", go or "")
    if not m:
        return "unknown Go version"
    key, patch = (int(m.group(1)), int(m.group(2))), int(m.group(3) or 0)
    if key in FIXED:
        return f"affected (< {key[0]}.{key[1]}.{FIXED[key]})" if patch < FIXED[key] else "fixed"
    return "affected (older than the fixed lines)" if key < min(FIXED) else "not affected (newer line)"


def norm(name):
    while name.startswith("./"):
        name = name[2:]
    return name.lstrip("/")


def open_layer(raw):
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    elif raw[:4] == b"\x28\xb5\x2f\xfd":
        raise ValueError("zstd-compressed layer (not supported by this check)")
    return tarfile.open(fileobj=io.BytesIO(raw), mode="r:")


def layer_events(archive):
    """[(index, blob, event, detail)] for usr/bin/pebble across the image's layers, and the final state."""
    with tarfile.open(archive, mode="r:gz") as outer:
        members = {norm(m.name): m for m in outer.getmembers()}
        manifest = json.load(outer.extractfile(members["manifest.json"]))
        if len(manifest) != 1:
            raise ValueError(f"archive holds {len(manifest)} images")
        layers = manifest[0]["Layers"]
        events, present, info = [], False, None
        for index, blob in enumerate(layers):
            raw = outer.extractfile(members[norm(blob)]).read()
            with open_layer(raw) as layer:
                for m in layer.getmembers():
                    name = norm(m.name)
                    if name == OPAQUE and present:
                        events.append((index, blob, "deleted (opaque usr/bin)", None))
                        present = False
                    elif name == WHITEOUT:
                        events.append((index, blob, "deleted (whiteout)", None))
                        present = False
                    elif name == PEBBLE and (m.isfile() or m.issym() or m.islnk()):
                        if m.isfile():
                            data = layer.extractfile(m).read()
                            build = go_build_info(data)
                            info = {"size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "goBuildInfo": build,
                                    "status": classify(build["go"]) if build else "no Go build information"}
                        else:
                            info = {"link": m.linkname}
                        events.append((index, blob, "added", info))
                        present = True
        return layers, events, present


def check(directory):
    name = os.path.basename(os.path.normpath(directory))
    result = {"artifact": name, "ok": False}
    sums = os.path.join(directory, "SHA256SUMS")
    bad = []
    for line in open(sums, encoding="utf-8").read().splitlines():
        digest, fname = line.split(None, 1)
        fname = fname.lstrip("*")
        with open(os.path.join(directory, fname), "rb") as fh:
            if hashlib.sha256(fh.read()).hexdigest() != digest:
                bad.append(fname)
    result["sha256sums"] = "OK" if not bad else f"MISMATCH {', '.join(bad)}"
    inspect = json.load(open(os.path.join(directory, "inspect.json"), encoding="utf-8"))
    inspect = inspect[0] if isinstance(inspect, list) else inspect
    labels = (inspect.get("Config") or {}).get("Labels") or {}
    result.update(imageId=inspect.get("Id"), revision=labels.get("org.opencontainers.image.revision"),
                  title=labels.get("org.opencontainers.image.title"))
    layers, events, present = layer_events(os.path.join(directory, "image.tar.gz"))
    result["layers"] = len(layers)
    result["pebbleEvents"] = [{"layer": i, "blob": b.rsplit("/", 1)[-1][:19], "event": e, "detail": d} for i, b, e, d in events]
    result["finalPebble"] = "present" if present else "absent"
    summary = json.load(open(os.path.join(directory, "trivy-summary.json"), encoding="utf-8"))
    result["scan"] = {k: summary.get(k) for k in ("critical_gate", "library_gate", "gate", "fixed_critical", "fixed_high",
                                                  "scanner") if k in summary}
    report = json.load(open(os.path.join(directory, "trivy.json"), encoding="utf-8"))
    targets = [str(t.get("Target", "")) for t in report.get("Results") or []]
    result["scanTargets"] = targets
    library_text = open(os.path.join(directory, "trivy-library.txt"), encoding="utf-8").read()
    result["pebbleInScanReports"] = any(PEBBLE in t for t in targets) or (PEBBLE in library_text)
    passed = (result["sha256sums"] == "OK" and not present and not result["pebbleInScanReports"]
              and summary.get("critical_gate") == "pass" and summary.get("library_gate") == "pass")
    result["ok"] = passed
    return result


def main():
    if len(sys.argv) < 2:
        print("usage: python3 -I s2-candidate-pebble-check.py <artifact dir> [...]")
        sys.exit(2)
    results, failed = [], 0
    for directory in sys.argv[1:]:
        try:
            r = check(directory)
        except (OSError, ValueError, KeyError, tarfile.TarError, json.JSONDecodeError) as exc:
            r = {"artifact": os.path.basename(os.path.normpath(directory)), "ok": False, "error": str(exc)[:200]}
        results.append(r)
        print(f"== {r['artifact']}")
        if "error" in r:
            print(f"  ERROR {r['error']}")
            failed += 1
            continue
        print(f"  SHA256SUMS {r['sha256sums']}; image {str(r['imageId'])[7:19]}; revision {str(r['revision'])[:12]}; {r['layers']} layers")
        for e in r["pebbleEvents"]:
            d = e["detail"] or {}
            if e["event"] == "added" and "sha256" in d:
                b = d.get("goBuildInfo") or {}
                extra = (f"{d['size']} bytes, sha256 {d['sha256'][:12]}, {b.get('go', '?')}, "
                         f"{b.get('module') or '?'} {b.get('moduleVersion') or ''} -> {d['status']}")
            else:
                extra = json.dumps(d) if d else ""
            print(f"  layer {e['layer']:>2} ({e['blob']}): usr/bin/pebble {e['event']}{' - ' + extra if extra else ''}")
        print(f"  final runtime filesystem: usr/bin/pebble {r['finalPebble'].upper()}")
        print(f"  candidate scan: critical gate {r['scan'].get('critical_gate')}, library gate {r['scan'].get('library_gate')} "
              f"(fixed CRITICAL {r['scan'].get('fixed_critical')}, HIGH {r['scan'].get('fixed_high')}); targets "
              f"{', '.join(r['scanTargets']) or 'none'}; usr/bin/pebble as a scan target: "
              f"{'yes' if r['pebbleInScanReports'] else 'no'}")
        print(f"  {'PASS' if r['ok'] else 'FAIL'}")
        failed += 0 if r["ok"] else 1
    if os.environ.get("S2_OUT"):
        with open(os.environ["S2_OUT"], "w", encoding="utf-8") as fh:
            json.dump(results, fh, indent=2, sort_keys=True)
    print(f"S2 {'PASS' if not failed else 'FAIL'}: {len(results) - failed}/{len(results)} images")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
