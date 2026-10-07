#!/usr/bin/env python3
"""Assemble the candidate manifest (candidate-images.yml, job manifest).

Reads the per-service artifacts (inspect.json, trivy-summary.json, SHA256SUMS) and the acceptance
results, and writes candidate-manifest.json plus a Markdown summary. The manifest is what the owner
reviews before approving publication: for every image the source SHA, the image id (which is also
the config digest an OCI registry will report), labels, platform, size, the Trivy counts, and the
acceptance outcomes. Nothing here publishes anything.

usage: candidate-manifest.py --source-sha SHA --artifacts DIR --out DIR [--acceptance-required]
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-sha", required=True)
    ap.add_argument("--artifacts", required=True, help="directory holding one sub-directory per downloaded artifact")
    ap.add_argument("--out", required=True)
    ap.add_argument("--acceptance-required", action="store_true")
    ap.add_argument("--expect-services", default="", help="comma-separated services that must have an identity record")
    args = ap.parse_args()
    root = Path(args.artifacts)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    images = []
    for d in sorted(p for p in root.iterdir() if p.is_dir() and p.name.startswith("candidate-image-")):
        inspect = load(d / "inspect.json")
        if not inspect:
            continue
        image = inspect[0] if isinstance(inspect, list) else inspect
        labels = (image.get("Config") or {}).get("Labels") or {}
        trivy = load(d / "trivy-summary.json") or {}
        sums = (d / "SHA256SUMS").read_text(encoding="utf-8").splitlines() if (d / "SHA256SUMS").exists() else []
        tar_sum = next((line.split()[0] for line in sums if line.endswith("image.tar.gz")), None)
        base = d.name[len("candidate-image-"):]
        if base.endswith("-meta"):
            base = base[: -len("-meta")]
        images.append({
            "service": base.rsplit("-", 1)[0],
            "artifact": d.name,
            "tag": (image.get("RepoTags") or [None])[0],
            "image_id": image.get("Id"),
            "config_digest_note": "an OCI registry reports the image id as the config digest on push",
            "platform": f"{image.get('Os')}/{image.get('Architecture')}",
            "size": image.get("Size"),
            "created": image.get("Created"),
            "revision_label": labels.get("org.opencontainers.image.revision"),
            "version_label": labels.get("org.opencontainers.image.version"),
            "rootfs_layers": len(((image.get("RootFS") or {}).get("Layers") or [])),
            "image_tar_gz_sha256": tar_sum,
            "trivy": trivy,
            "revision_matches_source": labels.get("org.opencontainers.image.revision") == args.source_sha,
        })

    acceptance = {}
    for name in ("acceptance-stack", "acceptance-auth-gateway", "acceptance-web"):
        d = root / f"candidate-{name}"
        result = load(d / "result.json") if d.exists() else None
        acceptance[name] = result if result is not None else {"status": "absent"}

    problems = []
    expected = [s for s in (args.expect_services or "").split(",") if s.strip()]
    found = {image["service"] for image in images}
    for service in expected:
        if service not in found:
            problems.append(f"{service}: no identity record (build missing or failed)")
    if not images:
        problems.append("no candidate image records at all")
    for image in images:
        if not image["trivy"]:
            problems.append(f"{image['service']}: no Trivy summary")
        if not image["revision_matches_source"]:
            problems.append(f"{image['service']}: revision label {image['revision_label']} != {args.source_sha}")
        if image["platform"] != "linux/amd64":
            problems.append(f"{image['service']}: platform {image['platform']}")
        if image["trivy"] and image["trivy"].get("gate") != "pass":
            problems.append(f"{image['service']}: Trivy gate {image['trivy'].get('gate')}")
    if args.acceptance_required:
        for name, result in acceptance.items():
            if result.get("status") != "pass":
                problems.append(f"{name}: {result.get('status')}")

    manifest = {
        "schema": "parkio.candidate-images/v1",
        "source_sha": args.source_sha,
        "run": {
            "id": os.environ.get("GITHUB_RUN_ID"),
            "attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
            "url": f"{os.environ.get('GITHUB_SERVER_URL', '')}/{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}",
        },
        "published": False,
        "publication": "separate approval; candidate-publish.yml verifies ids against this manifest before pushing",
        "images": images,
        "acceptance": acceptance,
        "problems": problems,
        "verdict": "accepted-candidate" if not problems else "not-accepted",
    }
    (out / "candidate-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    lines = [f"# Candidate images for `{args.source_sha}`", "", f"Verdict: **{manifest['verdict']}**", "",
             "| Service | Image id | Platform | Revision label ok | Trivy fixed (gate) | tar.gz sha256 |", "|---|---|---|---|---|---|"]
    for image in images:
        trivy = image["trivy"]
        lines.append(f"| {image['service']} | `{image['image_id']}` | {image['platform']} | {image['revision_matches_source']} | "
                     f"C {trivy.get('fixed_critical', '?')} / H {trivy.get('fixed_high', '?')} ({trivy.get('gate', '?')}) | `{image['image_tar_gz_sha256']}` |")
    lines += ["", "## Acceptance", ""]
    for name, result in acceptance.items():
        lines.append(f"- {name}: {result.get('status')}" + (f" — {result.get('detail')}" if result.get("detail") else ""))
    if problems:
        lines += ["", "## Problems", ""] + [f"- {p}" for p in problems]
    lines += ["", "Nothing is published by this run. Publication is a separate, owner-approved dispatch of candidate-publish.yml."]
    (out / "SUMMARY.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0 if not problems else 1


if __name__ == "__main__":
    raise SystemExit(main())
