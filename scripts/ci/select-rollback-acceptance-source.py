#!/usr/bin/env python3
"""Choose the newest qualifying invite-production deploy run found in the API listings, for rollback
compatibility-guard acceptance.

A qualifying deploy run is a successful workflow_dispatch run of invite-production-deploy.yml on api
whose "Deploy invite-production" job succeeded, and which holds exactly one unexpired manifest
artifact, invite-production-manifest-<head sha>.

The candidates are the union of two complete, paginated listings of the workflow's dispatch runs on
api: one the API filters to status=success, and one without that filter, kept here when the run
concluded success. The API sometimes serves a stale page that misses the newest run (#289 review
R3-F1); the union makes that less likely, and both counts are logged. Two listings that differ are
not an error. The listings can still lag, so the chosen run is the newest qualifying run they
showed, not necessarily the most recent deploy. Candidates are deduplicated by id and walked by
created_at, newest first, whatever order the API returns them in (#289 review R2-F1).

Up to the chosen run, nothing is passed over silently. A newer run whose artifact listing is not
exactly one unexpired manifest, or whose deploy job cannot be read, fails the selection. A newer
build-only run (deploy job skipped) with one manifest becomes the build-only reference, which the
acceptance must see refused.

Reads GITHUB_API_URL, GITHUB_REPOSITORY and GITHUB_TOKEN. Writes these to GITHUB_OUTPUT:
reference, run_id, artifact, run_head_sha, run_created_at, listed_at, build_only_reference. It
also writes lines to GITHUB_STEP_SUMMARY. Exit 0 when a run was chosen; 1 otherwise, with an
::error:: line.
"""
from __future__ import annotations

import datetime
import json
import os
import sys
import urllib.request

WORKFLOW = "invite-production-deploy.yml"
DEPLOY_JOB = "Deploy invite-production"
KINDS = {"success": "deployed", "skipped": "build_only"}
DISPATCH_ON_API = "event=workflow_dispatch&branch=api"
PER_PAGE = 100
MAX_PAGES = 50


class Refused(Exception):
    """The selection fails; the message says why."""


def manifest_name(run: dict) -> str:
    return f"invite-production-manifest-{run['head_sha']}"


def describe(run: dict) -> str:
    return f"run {run['id']} ({run['head_sha'][:12]}, created {run['created_at']})"


def list_runs(get, query: str) -> list:
    """Every run of one listing, page by page, until a page is not full."""
    runs = []
    for page in range(1, MAX_PAGES + 1):
        batch = get(f"actions/workflows/{WORKFLOW}/runs?{query}&per_page={PER_PAGE}&page={page}")["workflow_runs"]
        runs.extend(batch)
        if len(batch) < PER_PAGE:
            return runs
    raise Refused(f"the run listing {query} still has runs after {MAX_PAGES} pages of {PER_PAGE}; "
                  "refusing to choose from an incomplete listing.")


def successful(run: dict) -> bool:
    return (run.get("event") == "workflow_dispatch" and run.get("head_branch") == "api"
            and run.get("status") == "completed" and run.get("conclusion") == "success")


def candidates(get) -> tuple:
    """(the successful dispatch runs on api in both listings, deduplicated by id, counts for the log)."""
    filtered = list_runs(get, f"{DISPATCH_ON_API}&status=success")
    unfiltered = list_runs(get, DISPATCH_ON_API)
    union = {}
    for run in filtered + unfiltered:
        if successful(run):
            union.setdefault(run["id"], run)
    counts = {"filtered": len(filtered), "unfiltered": len(unfiltered),
              "unfiltered_successful": sum(1 for run in unfiltered if successful(run)), "distinct": len(union)}
    return list(union.values()), counts


def select(runs: list, artifacts_of, deploy_conclusions_of) -> dict:
    """{"deployed": (run, artifact), "build_only": (run, artifact) or None} for the newest qualifying run.

    artifacts_of(run, name) lists the run's artifacts with that name. deploy_conclusions_of(run) lists
    the conclusions of its deploy jobs.
    """
    deployed = build_only = None
    for run in sorted(runs, key=lambda r: r["created_at"], reverse=True):
        name = manifest_name(run)
        unexpired = [a for a in artifacts_of(run, name) if not a.get("expired")]
        conclusions = deploy_conclusions_of(run)
        kind = KINDS.get(conclusions[0]) if len(conclusions) == 1 else None
        if deployed is None:
            # Newer than any choice so far: it qualifies, or the selection fails.
            if kind is None:
                raise Refused(f"cannot tell whether {describe(run)} deployed: its {DEPLOY_JOB!r} job "
                              f"conclusions are {conclusions}. Refusing to pass over it to an older release.")
            if len(unexpired) != 1:
                raise Refused(f"{describe(run)} is newer than any qualifying deploy run, but holds "
                              f"{len(unexpired)} unexpired {name} artifacts, not exactly one. Refusing to "
                              f"pass over it to an older release.")
            if kind == "deployed":
                deployed = (run, unexpired[0])
            elif build_only is None:
                build_only = (run, unexpired[0])
        elif build_only is None and kind == "build_only" and len(unexpired) == 1:
            build_only = (run, unexpired[0])
        if deployed is not None and build_only is not None:
            break
    if deployed is None:
        raise Refused("No successful invite-production deploy run on api holds an unexpired manifest. "
                      "Artifacts are kept 90 days, the maximum for a public repository, so this check needs "
                      "an api deploy from that window, as a rollback does. That is a missing precondition, "
                      "not a code defect: a new api deploy restores it. The unit tests still cover the "
                      "resolver and the verifier.")
    return {"deployed": deployed, "build_only": build_only}


def main(get=None, now=None) -> int:
    api, repo = os.environ["GITHUB_API_URL"], os.environ["GITHUB_REPOSITORY"]

    def http_get(path):
        req = urllib.request.Request(f"{api}/repos/{repo}/{path}", headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)

    get = get or http_get
    now = now or datetime.datetime.now(datetime.timezone.utc)
    listed_at = now.strftime("%Y-%m-%dT%H:%M:%SZ")
    try:
        runs, counts = candidates(get)
        listings = (f"API listings at {listed_at}: {counts['filtered']} runs with status=success; "
                    f"{counts['unfiltered']} runs without that filter, {counts['unfiltered_successful']} of them "
                    f"successful; {counts['distinct']} distinct successful runs.")
        print(listings)
        chosen = select(
            runs,
            lambda run, name: get(f"actions/runs/{run['id']}/artifacts?name={name}")["artifacts"],
            lambda run: [j["conclusion"] for j in get(f"actions/runs/{run['id']}/jobs?per_page=100")["jobs"]
                         if j["name"] == DEPLOY_JOB])
    except Refused as refused:
        print(f"::error::{refused}")
        return 1
    run, artifact = chosen["deployed"]
    reference = f"{run['id']}/{manifest_name(run)}"
    build_only = ""
    if chosen["build_only"] is not None:
        build_only = f"{chosen['build_only'][0]['id']}/{manifest_name(chosen['build_only'][0])}"
        print(f"build-only run: {describe(chosen['build_only'][0])}")
    print(f"newest qualifying deploy run found in the API listings at {listed_at}: {describe(run)}, "
          f"artifact {manifest_name(run)}")
    expires_at = artifact["expires_at"]
    expires = datetime.datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
    days_left = (expires - now).days
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write(f"Newest qualifying deploy run found in the API listings at {listed_at}: `{run['id']}` (commit "
                      f"`{run['head_sha'][:12]}`, created {run['created_at']}), manifest `{reference}`, expires "
                      f"{expires_at} ({days_left} days). The API listings can lag, so this is not guaranteed to be "
                      f"the most recent deploy. {listings}\n")
    if days_left < 14:
        print(f"::warning::The source deploy manifest {reference} expires on {expires_at}. After that, this "
              "check and a rollback to that deploy fail closed until a new api deploy.")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"reference={reference}\nrun_id={run['id']}\nartifact={manifest_name(run)}\n"
                  f"run_head_sha={run['head_sha']}\nrun_created_at={run['created_at']}\nlisted_at={listed_at}\n"
                  f"build_only_reference={build_only}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
