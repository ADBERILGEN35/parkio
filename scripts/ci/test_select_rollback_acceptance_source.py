#!/usr/bin/env python3
"""Tests for scripts/ci/select-rollback-acceptance-source.py (#289 review R2-F1), with a fake GitHub API.

- Unordered listing: the newest qualifying deploy run is chosen by created_at, whatever order the API
  returns.
- A newer run is never passed over silently: a newer run whose listing holds no unexpired manifest,
  or two, fails the selection, and so does one whose deploy job cannot be read.
- A newer build-only run becomes the build-only reference. An older run with a broken listing does
  not matter once a deploy run is chosen.
- No deploy run fails with the missing-precondition error.
- The outputs and the summary name the chosen run, its commit and its creation time; there is no bare
  "latest".
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

SCRIPT = Path(__file__).resolve().parent / "select-rollback-acceptance-source.py"
spec = importlib.util.spec_from_file_location("select_source", SCRIPT)
source = importlib.util.module_from_spec(spec)
spec.loader.exec_module(source)

NOW = datetime.datetime(2026, 10, 5, tzinfo=datetime.timezone.utc)


def run(run_id: int, created: str, sha_char: str) -> dict:
    return {"id": run_id, "created_at": created, "head_sha": sha_char * 40}


class FakeApi:
    """Answers the three GitHub API paths the script calls."""

    def __init__(self, runs, artifacts, deploy_jobs):
        self.runs, self.artifacts, self.deploy_jobs = runs, artifacts, deploy_jobs

    def __call__(self, path: str) -> dict:
        if path.startswith("actions/workflows/"):
            return {"workflow_runs": self.runs}
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


class Selection(unittest.TestCase):
    def choose(self, api: FakeApi):
        return source.select(api.runs,
                             lambda r, name: api(f"actions/runs/{r['id']}/artifacts?name={name}")["artifacts"],
                             lambda r: [j["conclusion"] for j in api(f"actions/runs/{r['id']}/jobs?per_page=100")["jobs"]
                                        if j["name"] == source.DEPLOY_JOB])

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


class Main(unittest.TestCase):
    def run_main(self, api: FakeApi):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {
                "GITHUB_API_URL": "https://api.invalid", "GITHUB_REPOSITORY": "o/r", "GITHUB_TOKEN": "t",
                "GITHUB_OUTPUT": f"{tmp}/out", "GITHUB_STEP_SUMMARY": f"{tmp}/summary"}):
            stdout = io.StringIO()
            with redirect_stdout(stdout):
                rc = source.main(get=api, now=NOW)
            out = Path(f"{tmp}/out").read_text() if Path(f"{tmp}/out").exists() else ""
            summary = Path(f"{tmp}/summary").read_text() if Path(f"{tmp}/summary").exists() else ""
            return rc, stdout.getvalue(), out, summary

    def test_outputs_and_summary_name_the_newest_qualifying_run(self):
        api = FakeApi([OLD, NEW], {r["id"]: [artifact()] for r in (OLD, NEW)}, {r["id"]: ["success"] for r in (OLD, NEW)})
        rc, stdout, out, summary = self.run_main(api)
        self.assertEqual(rc, 0)
        self.assertIn(f"run_id={NEW['id']}\n", out)
        self.assertIn(f"run_head_sha={NEW['head_sha']}\n", out)
        self.assertIn(f"run_created_at={NEW['created_at']}\n", out)
        self.assertIn(f"reference={NEW['id']}/invite-production-manifest-{NEW['head_sha']}\n", out)
        self.assertIn(f"Newest qualifying deploy run: `{NEW['id']}` (commit `{NEW['head_sha'][:12]}`, created "
                      f"{NEW['created_at']})", summary)
        self.assertNotIn("latest", (stdout + summary).lower())

    def test_a_refusal_is_an_error_line_and_exit_1(self):
        api = FakeApi([NEW, OLD], {NEW["id"]: [], OLD["id"]: [artifact()]}, {r["id"]: ["success"] for r in (OLD, NEW)})
        rc, stdout, out, _ = self.run_main(api)
        self.assertEqual(rc, 1)
        self.assertIn("::error::", stdout)
        self.assertEqual(out, "")


if __name__ == "__main__":
    unittest.main(verbosity=1)
