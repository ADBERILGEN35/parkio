#!/usr/bin/env python3
"""Emit a minimal SLSA provenance v1 predicate for a published candidate image.

Truthful fields only: source commit, builder id (this workflow), subject digest.
Does not claim an independently audited SLSA build level.
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--source-sha", required=True)
    p.add_argument("--digest", required=True, help="sha256:...")
    p.add_argument("--image-name", required=True, help="registry/repo/service")
    p.add_argument("--run-id", required=True)
    p.add_argument("--workflow-ref", required=True, help="github.workflow_ref")
    p.add_argument("--out", required=True)
    args = p.parse_args()
    digest = args.digest if args.digest.startswith("sha256:") else f"sha256:{args.digest}"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    predicate = {
        "buildDefinition": {
            "buildType": "https://github.com/ADBERILGEN35/parkio/candidate-publish@v1",
            "externalParameters": {
                "source": f"git+https://github.com/ADBERILGEN35/parkio@{args.source_sha}",
                "workflow": args.workflow_ref,
                "candidatePublishRunId": args.run_id,
            },
            "resolvedDependencies": [
                {
                    "uri": f"git+https://github.com/ADBERILGEN35/parkio@{args.source_sha}",
                    "digest": {"gitCommit": args.source_sha},
                }
            ],
        },
        "runDetails": {
            "builder": {
                "id": f"https://github.com/{args.workflow_ref}",
            },
            "metadata": {
                "invocationId": args.run_id,
                "startedOn": now,
                "finishedOn": now,
            },
        },
    }
    # cosign attest --type slsaprovenance1 expects the predicate object
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(predicate, fh, indent=2)
        fh.write("\n")
    print(f"wrote predicate for {args.image_name}@{digest}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
