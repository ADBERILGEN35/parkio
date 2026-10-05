#!/usr/bin/env python3
"""Tests for scripts/ci/select-rollback-acceptance-source.py (#289 review R2-F1), with a fake GitHub API.

- Unordered listing: the newest qualifying deploy run is chosen by created_at, whatever order the API
  returns.
- A newer run is never passed over silently: a newer run whose listing holds no unexpired manifest,
  or two, fails the selection, and so does one whose deploy job cannot be read.
- A newer build-only run becomes the build-only reference. An older run with a broken listing does
  not matter once a deploy run is chosen.
- No deploy run fails with the missing-precondition error.
- Listings (#289 review R3-F1): a stale status=success listing that misses the newest run still
  yields it, through the unfiltered listing; runs in both listings are one candidate; every page of
  both listings is read, and a listing that does not end is refused; unsuccessful runs and runs on
  other branches or events are no candidates; two listings that differ are no error.
- The outputs and the summary name the chosen run, its commit, its creation time and when the API
  was listed, and say that the listings can lag; there is no bare "latest".
"""
from __future__ import annotations

import datetime
import importlib.util
import io
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock
from urllib.parse import parse_qs, urlsplit

SCRIPT = Path(__file__).resolve().parent / "select-rollback-acceptance-source.py"
spec = importlib.util.spec_from_file_location("select_source", SCRIPT)
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)

NOW = datetime.datetime(2026, 10, 5, tzinfo=datetime.timezone.utc)


def run(run_id: int, created: str, sha_char: str, conclusion: str = "success", branch: str = "api",
        event: str = "workflow_dispatch") -> dict:
    return {"id": run_id, "created_at": created, "head_sha": sha_char * 40, "event": event,
            "head_branch": branch, "status": "completed", "conclusion": conclusion}


class FakeApi:
    """Answers the GitHub API paths the script calls. The two run listings are paginated; by default
    both hold `runs`, in the order given."""

    def __init__(self, runs, artifacts, deploy_jobs, filtered=None, unfiltered=None):
        self.runs, self.artifacts, self.deploy_jobs = runs, artifacts, deploy_jobs
        self.filtered = runs if filtered is None else filtered
        self.unfiltered = runs if unfiltered is None else unfiltered
        self.calls = []

    def __call__(self, path: str) -> dict:
        self.calls.append(path)
        if path.startswith("actions/workflows/"):
            query = parse_qs(urlsplit(path).query)
            assert query["event"] == ["workflow_dispatch"] and query["branch"] == ["api"], path
            listing = self.filtered if query.get("status") == ["success"] else self.unfiltered
            per_page, page = int(query["per_page"][0]), int(query["page"][0])
            return {"workflow_runs": listing[(page - 1) * per_page:page * per_page]}
        run_id = int(path.split("/")[2])
        if "/artifacts?" in path:
            return {"artifacts": self.artifacts.get(run_id, [])}
        if "/jobs?" in path:
            return {"jobs": [{"name": source.DEPLOY_JOB, "conclusion": c} for c in self.deploy_jobs.get(run_id, [])]
                    + [{"name": "Build images + secret-safe dry-run manifest", "conclusion": "success"}]}
        raise AssertionError(path)


def artifact(expires: str = "2026-12-08T15:40:36Z", expired: bool = False) -> dict:
    return {"expired": expired, "expires_at": expires}


NEW = run(34371794213, "2026-09-09T15:40:34Z", "e")
MID = run(34352374217, "2026-09-09T12:40:16Z", "6")
OLD = run(33734515937, "2026-09-03T08:39:15Z", "9")
FAILED = run(34483619439, "2026-09-10T13:35:45Z", "a", conclusion="failure")


def choose(api: FakeApi, runs=None):
    return source.select(api.runs if runs is None else runs,
                         lambda r, name: api(f"actions/runs/{r['id']}/artifacts?name={name}")["artifacts"],
                         lambda r: [j["conclusion"] for j in api(f"actions/runs/{r['id']}/jobs?per_page=100")["jobs"]
                                    if j["name"] == source.DEPLOY_JOB])


def run_main(api: FakeApi):
    """(exit code, stdout, GITHUB_OUTPUT, GITHUB_STEP_SUMMARY) of main() against api."""
    with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {
            "GITHUB_API_URL": "https://api.invalid", "GITHUB_REPOSITORY": "o/r", "GITHUB_TOKEN": "t",
            "GITHUB_OUTPUT": f"{tmp}/out", "GITHUB_STEP_SUMMARY": f"{tmp}/summary"}):
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            rc = source.main(get=api, now=NOW)
        out = Path(f"{tmp}/out").read_text() if Path(f"{tmp}/out").exists() else ""
        summary = Path(f"{tmp}/summary").read_text() if Path(f"{tmp}/summary").exists() else ""
        return rc, stdout.getvalue(), out, summary


class Selection(unittest.TestCase):
    def choose(self, api: FakeApi):
        return choose(api)

    def test_an_unordered_listing_still_yields_the_newest_deploy_run(self):
        api = FakeApi([OLD, NEW, MID], {r["id"]: [artifact()] for r in (OLD, NEW, MID)},
                      {r["id"]: ["success"] for r in (OLD, NEW, MID)})
        self.assertEqual(self.choose(api)["deployed"][0]["id"], NEW["id"])

    def test_a_newer_run_without_exactly_one_manifest_fails_instead_of_falling_back(self):
        for listing in ([], [artifact(), artifact()], [artifact(expired=True)]):
            with self.subTest(listing=len(listing)):
                api = FakeApi([OLD, MID, NEW], {NEW["id"]: listing, MID["id"]: [artifact()], OLD["id"]: [artifact()]},
                              {r["id"]: ["success"] for r in (OLD, NEW, MID)})
                with self.assertRaisesRegex(source.Refused, rf"run {NEW['id']} .* not exactly one\. Refusing to pass over it"):
                    self.choose(api)

    def test_a_newer_run_whose_deploy_job_cannot_be_read_fails(self):
        api = FakeApi([MID, NEW], {NEW["id"]: [artifact()], MID["id"]: [artifact()]},
                      {NEW["id"]: [], MID["id"]: ["success"]})
        with self.assertRaisesRegex(source.Refused, rf"cannot tell whether run {NEW['id']} "):
            self.choose(api)

    def test_a_newer_build_only_run_is_the_build_only_reference(self):
        api = FakeApi([OLD, NEW, MID], {r["id"]: [artifact()] for r in (OLD, NEW, MID)},
                      {NEW["id"]: ["skipped"], MID["id"]: ["success"], OLD["id"]: ["success"]})
        chosen = self.choose(api)
        self.assertEqual(chosen["deployed"][0]["id"], MID["id"])
        self.assertEqual(chosen["build_only"][0]["id"], NEW["id"])

    def test_an_older_broken_listing_does_not_matter_once_a_deploy_run_is_chosen(self):
        api = FakeApi([OLD, NEW], {NEW["id"]: [artifact()], OLD["id"]: []}, {NEW["id"]: ["success"], OLD["id"]: ["skipped"]})
        chosen = self.choose(api)
        self.assertEqual(chosen["deployed"][0]["id"], NEW["id"])
        self.assertIsNone(chosen["build_only"])

    def test_no_deploy_run_is_the_missing_precondition(self):
        api = FakeApi([NEW], {NEW["id"]: [artifact()]}, {NEW["id"]: ["skipped"]})
        with self.assertRaisesRegex(source.Refused, "missing precondition"):
            self.choose(api)


class Listings(unittest.TestCase):
    def test_a_stale_filtered_listing_that_misses_the_newest_run_still_yields_it(self):
        # Job 111857050831: the status=success listing lacked 34371794213, and 34352374217 was chosen.
        api = FakeApi([NEW, MID, OLD], {r["id"]: [artifact()] for r in (OLD, NEW, MID)},
                      {r["id"]: ["success"] for r in (OLD, NEW, MID)},
                      filtered=[MID, OLD], unfiltered=[FAILED, NEW, MID, OLD])
        rc, stdout, out, _ = run_main(api)
        self.assertEqual(rc, 0)
        self.assertIn(f"run_id={NEW['id']}\n", out)
        self.assertIn("2 runs with status=success; 4 runs without that filter, 3 of them successful; "
                      "3 distinct successful runs.", stdout)

    def test_runs_in_both_listings_are_one_candidate(self):
        api = FakeApi([NEW, MID, OLD], {r["id"]: [artifact()] for r in (OLD, NEW, MID)},
                      {r["id"]: ["success"] for r in (OLD, NEW, MID)})
        runs, counts = source.candidates(api)
        self.assertEqual(sorted(r["id"] for r in runs), sorted(r["id"] for r in (NEW, MID, OLD)))
        self.assertEqual(counts, {"filtered": 3, "unfiltered": 3, "unfiltered_successful": 3, "distinct": 3})

    def test_every_page_of_both_listings_is_read(self):
        older = [run(33000000000 + i, f"2026-08-0{i}T00:00:00Z", str(i)) for i in range(1, 5)]
        listing = older + [NEW]  # the newest run comes last, on the third page
        api = FakeApi(listing, {r["id"]: [artifact()] for r in listing}, {r["id"]: ["success"] for r in listing})
        with mock.patch.object(source, "PER_PAGE", 2):
            runs, counts = source.candidates(api)
            chosen = choose(api, runs)
        self.assertEqual(chosen["deployed"][0]["id"], NEW["id"])
        self.assertEqual(counts["distinct"], 5)
        pages = sorted((("status=success" in c), parse_qs(urlsplit(c).query)["page"][0])
                       for c in api.calls if c.startswith("actions/workflows/"))
        self.assertEqual(pages, [(False, "1"), (False, "2"), (False, "3"), (True, "1"), (True, "2"), (True, "3")])

    def test_a_listing_that_does_not_end_is_refused(self):
        listing = [run(33000000000 + i, f"2026-08-0{i}T00:00:00Z", str(i)) for i in range(1, 6)]
        api = FakeApi(listing, {}, {})
        with mock.patch.object(source, "PER_PAGE", 1), mock.patch.object(source, "MAX_PAGES", 3):
            with self.assertRaisesRegex(source.Refused, "still has runs after 3 pages of 1"):
                source.candidates(api)

    def test_unsuccessful_runs_and_other_branches_or_events_are_no_candidates(self):
        other_branch = run(34500000000, "2026-09-11T00:00:00Z", "b", branch="feature")
        other_event = run(34500000001, "2026-09-11T01:00:00Z", "c", event="push")
        api = FakeApi([NEW, OLD], {r["id"]: [artifact()] for r in (NEW, OLD)}, {r["id"]: ["success"] for r in (NEW, OLD)},
                      unfiltered=[other_event, other_branch, FAILED, NEW, OLD])
        runs, counts = source.candidates(api)
        self.assertEqual(sorted(r["id"] for r in runs), sorted([NEW["id"], OLD["id"]]))
        self.assertEqual(counts["unfiltered_successful"], 2)


class Main(unittest.TestCase):

    def test_outputs_and_summary_name_the_newest_qualifying_run(self):
        api = FakeApi([OLD, NEW], {r["id"]: [artifact()] for r in (OLD, NEW)}, {r["id"]: ["success"] for r in (OLD, NEW)})
        rc, stdout, out, summary = run_main(api)
        self.assertEqual(rc, 0)
        self.assertIn(f"run_id={NEW['id']}\n", out)
        self.assertIn(f"run_head_sha={NEW['head_sha']}\n", out)
        self.assertIn(f"run_created_at={NEW['created_at']}\n", out)
        self.assertIn("listed_at=2026-10-05T00:00:00Z\n", out)
        self.assertIn(f"reference={NEW['id']}/invite-production-manifest-{NEW['head_sha']}\n", out)
        self.assertIn(f"Newest qualifying deploy run found in the API listings at 2026-10-05T00:00:00Z: `{NEW['id']}` "
                      f"(commit `{NEW['head_sha'][:12]}`, created {NEW['created_at']})", summary)
        self.assertIn("The API listings can lag, so this is not guaranteed to be the most recent deploy.", summary)
        self.assertNotIn("latest", (stdout + summary).lower())

    def test_a_refusal_is_an_error_line_and_exit_1(self):
        api = FakeApi([NEW, OLD], {NEW["id"]: [], OLD["id"]: [artifact()]}, {r["id"]: ["success"] for r in (OLD, NEW)})
        rc, stdout, out, _ = run_main(api)
        self.assertEqual(rc, 1)
        self.assertIn("::error::", stdout)
        self.assertEqual(out, "")


if __name__ == "__main__":
    unittest.main(verbosity=1)
