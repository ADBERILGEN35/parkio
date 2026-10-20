#!/usr/bin/env python3
"""Emit a minimal SLSA provenance v1 predicate for a published candidate image.

Field meanings (do not conflate):
  source-sha              — git commit of the published candidate revision
  candidate-images-run-id — GitHub Actions run id of Candidate images (build/accept)
  publish-run-id          — GitHub Actions run id of this Candidate publish invocation
  workflow-ref            — github.workflow_ref of the publish workflow identity
  digest                  — OCI content digest of the published image (binding target)

Does not claim an independently audited SLSA build level. Does not invent build
timestamps (omits startedOn/finishedOn when unknown).
"""
from __future__ import annotations

import argparse
import json
import sys


def build_predicate(
    *,
    source_sha: str,
    digest: str,
    image_name: str,
    candidate_images_run_id: str,
    publish_run_id: str,
    workflow_ref: str,
) -> dict:
    digest = digest if digest.startswith("sha256:") else f"sha256:{digest}"
    return {
        "buildDefinition": {
            "buildType": "https://github.com/ADBERILGEN35/parkio/candidate-publish@v1",
            "externalParameters": {
                "source": f"git+https://github.com/ADBERILGEN35/parkio@{source_sha}",
                "publishWorkflow": workflow_ref,
                "candidateImagesRunId": str(candidate_images_run_id),
                "candidatePublishRunId": str(publish_run_id),
                "imageName": image_name,
                "publishedDigest": digest,
            },
            "resolvedDependencies": [
                {
                    "uri": f"git+https://github.com/ADBERILGEN35/parkio@{source_sha}",
                    "digest": {"gitCommit": source_sha},
                }
            ],
        },
        "runDetails": {
            "builder": {
                # Builder identity is the publish workflow that performed attest.
                "id": f"https://github.com/{workflow_ref}",
            },
            "metadata": {
                # Invocation of the publish operation (not the earlier Candidate images run).
                "invocationId": str(publish_run_id),
            },
        },
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--source-sha", required=True)
    p.add_argument("--digest", required=True, help="sha256:...")
    p.add_argument("--image-name", required=True, help="registry/repo/service")
    p.add_argument(
        "--candidate-images-run-id",
        required=True,
        help="Candidate images workflow run id that produced the artifacts",
    )
    p.add_argument(
        "--publish-run-id",
        required=True,
        help="This Candidate publish workflow run id (GITHUB_RUN_ID)",
    )
    p.add_argument("--workflow-ref", required=True, help="github.workflow_ref of publish")
    p.add_argument("--out", required=True)
    # Back-compat rejected: old --run-id alone is ambiguous.
    args = p.parse_args()
    digest = args.digest if args.digest.startswith("sha256:") else f"sha256:{args.digest}"
    predicate = build_predicate(
        source_sha=args.source_sha,
        digest=digest,
        image_name=args.image_name,
        candidate_images_run_id=args.candidate_images_run_id,
        publish_run_id=args.publish_run_id,
        workflow_ref=args.workflow_ref,
    )
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(predicate, fh, indent=2)
        fh.write("\n")
    print(f"wrote predicate for {args.image_name}@{digest}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
