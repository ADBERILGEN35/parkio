"""Offline contract test for the default-branch scheduled staging dispatcher."""

from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
DISPATCHER = ROOT / ".github/workflows/scheduled-restore-drills.yml"
RECEIVER = ROOT / ".github/workflows/staging-verification.yml"


def workflow(path):
    # BaseLoader keeps the YAML key `on` and scalar defaults literal.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


def gh_workflow_json_inputs(payload):
    """Match gh 2.92.0: `workflow run --json` values must be JSON strings.

    A JSON boolean fails locally with:
    could not parse provided JSON: json: cannot unmarshal bool into Go value of type string
    """
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise ValueError(f"could not parse provided JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise ValueError("could not parse provided JSON: expected a JSON object")
    for key, value in parsed.items():
        if not isinstance(value, str):
            kind = "bool" if isinstance(value, bool) else type(value).__name__
            raise ValueError(
                "could not parse provided JSON: json: cannot unmarshal "
                f"{kind} into Go value of type string ({key})"
            )
    return parsed


MOCK_GH = """#!/usr/bin/env python3
import json, os, subprocess, sys
from pathlib import Path
argv = sys.argv[1:]
# Only `workflow run --json` reads stdin, as gh does; other calls never block on it.
stdin = sys.stdin.read() if argv[:2] == ["workflow", "run"] and "--json" in argv else ""
if argv[:2] == ["workflow", "run"] and "--json" in argv:
    try:
        parsed = json.loads(stdin)
    except json.JSONDecodeError as exc:
        sys.stderr.write(f"could not parse provided JSON: {exc}\\n")
        sys.exit(1)
    if not isinstance(parsed, dict):
        sys.stderr.write("could not parse provided JSON: expected a JSON object\\n")
        sys.exit(1)
    for key, value in parsed.items():
        if not isinstance(value, str):
            kind = "bool" if isinstance(value, bool) else type(value).__name__
            sys.stderr.write(
                "could not parse provided JSON: json: cannot unmarshal "
                f"{kind} into Go value of type string\\n"
            )
            sys.exit(1)
with open(os.environ["GH_CAPTURE"], "a") as out:
    out.write(json.dumps({"argv": argv, "stdin": stdin}) + "\\n")
scenario = json.loads(os.environ.get("GH_SCENARIO") or "{}")
if argv[:2] == ["workflow", "run"]:
    counter = Path(os.environ["GH_CAPTURE"] + ".dispatches")
    number = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(number))
    if scenario.get("print_url", True):
        # gh 2.92 prints the created run's URL (scheduled run 37112016766 shows it).
        print(f"https://github.com/{os.environ['GH_REPO']}/actions/runs/{9000 + number}")
elif argv[:2] == ["run", "view"]:
    states = scenario.get("views", {}).get(argv[2], ["completed success"])
    polls = Path(os.environ["GH_CAPTURE"] + ".polls-" + argv[2])
    seen = int(polls.read_text()) if polls.exists() else 0
    polls.write_text(str(seen + 1))
    state = states[min(seen, len(states) - 1)]
    if state == "api-error":
        sys.stderr.write("HTTP 502: Bad Gateway\\n")
        sys.exit(1)
    print(state)
elif argv[:2] == ["run", "list"]:
    # Like gh: filter by the flags given, newest first, then --json fields and --jq.
    def flag(name):
        return argv[argv.index(name) + 1] if name in argv else None
    filters = {"--workflow": "workflow", "--branch": "branch", "--event": "event", "--user": "actor"}
    runs = [run for run in scenario.get("runs", [])
            if all(flag(option) is None or run[key] == flag(option) for option, key in filters.items())]
    runs.sort(key=lambda run: run["createdAt"], reverse=True)
    runs = runs[:int(flag("--limit") or 20)]
    listed = json.dumps([{field: run[field] for field in flag("--json").split(",")} for run in runs])
    if flag("--jq") is None:
        print(listed)
    else:
        # The workflow's own jq expression, evaluated by real jq (as gh does with --jq).
        result = subprocess.run(["jq", "-r", flag("--jq")], input=listed, capture_output=True, text=True)
        sys.stdout.write(result.stdout)
        sys.stderr.write(result.stderr)
        sys.exit(result.returncode)
"""

SLEEP_STUB = "#!/usr/bin/env bash\nexit 0\n"
REPO = "ADBERILGEN35/parkio"


class ScheduledStagingDispatchTest(unittest.TestCase):
    def setUp(self):
        self.dispatcher = workflow(DISPATCHER)
        self.receiver = workflow(RECEIVER)

    def fake_bin(self, temp):
        for name, body in (("gh", MOCK_GH), ("sleep", SLEEP_STUB)):
            path = temp / name
            path.write_text(body)
            path.chmod(0o755)

    def dispatch(self, *, event, cron="", only="", run_restore="", scenario=None, with_outputs=False):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            self.fake_bin(temp)
            capture = temp / "calls.jsonl"
            outputs = temp / "github_output"
            env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}",
                       GH_CAPTURE=str(capture), GH_REPO=REPO, GH_SCENARIO=json.dumps(scenario or {}),
                       GITHUB_OUTPUT=str(outputs), RUNNER_TEMP=str(temp),
                       EVENT=event, CRON=cron, ONLY=only, RUN_RESTORE=run_restore)
            script = self.dispatcher["jobs"]["dispatch"]["steps"][0]["run"]
            result = subprocess.run(["bash", "-e", "-c", script], env=env,
                                    capture_output=True, text=True, stdin=subprocess.DEVNULL)
            calls = [json.loads(line) for line in capture.read_text().splitlines()] if capture.exists() else []
            calls = [call for call in calls if call["argv"][:2] == ["workflow", "run"]] if not with_outputs else calls
            if with_outputs:
                return result, calls, (outputs.read_text() if outputs.exists() else "")
            return result, calls

    def watch(self, children, scenario=None, wait_seconds="600"):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            self.fake_bin(temp)
            capture = temp / "calls.jsonl"
            summary = temp / "summary.md"
            env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}",
                       GH_CAPTURE=str(capture), GH_REPO=REPO, GH_SCENARIO=json.dumps(scenario or {}),
                       GITHUB_STEP_SUMMARY=str(summary), CHILDREN=children,
                       WAIT_SECONDS=wait_seconds, POLL_SECONDS="0")
            script = self.dispatcher["jobs"]["watch"]["steps"][0]["run"]
            result = subprocess.run(["bash", "-e", "-c", script], env=env,
                                    capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)
            return result, (summary.read_text() if summary.exists() else "")

    def assert_staging(self, calls, expected):
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["argv"],
                         ["workflow", "run", "staging-verification.yml", "--ref", "api", "--json"])
        parsed = gh_workflow_json_inputs(calls[0]["stdin"])
        self.assertEqual(parsed, {"run_restore": expected})
        self.assertIsInstance(parsed["run_restore"], str)

    def test_scheduled_staging_forwards_true(self):
        result, calls = self.dispatch(event="schedule", cron="47 5 * * 3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_staging(calls, "true")

    def test_manual_true_and_false_and_default(self):
        for value, expected in (("true", "true"), ("false", "false"), ("", "false")):
            with self.subTest(value=value):
                result, calls = self.dispatch(event="workflow_dispatch",
                                              only="staging-verification.yml", run_restore=value)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_staging(calls, expected)

    def test_boolean_json_is_rejected_before_dispatch(self):
        with self.assertRaises(ValueError) as raised:
            gh_workflow_json_inputs('{"run_restore":true}\n')
        self.assertIn("cannot unmarshal bool into Go value of type string", str(raised.exception))
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            mock = temp / "gh"
            capture = temp / "calls.jsonl"
            mock.write_text(MOCK_GH)
            mock.chmod(0o755)
            result = subprocess.run(
                [sys.executable, str(mock), "workflow", "run",
                 "staging-verification.yml", "--ref", "api", "--json"],
                input='{"run_restore":true}\n',
                env=dict(os.environ, GH_CAPTURE=str(capture)),
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("cannot unmarshal bool into Go value of type string", result.stderr)
            self.assertFalse(capture.exists())

    def test_malformed_input_fails_closed(self):
        result, calls = self.dispatch(event="workflow_dispatch",
                                      only="staging-verification.yml", run_restore="TRUE")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])

    def test_other_target_and_guards_unchanged(self):
        result, calls = self.dispatch(event="schedule", cron="41 5 * * 1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [{"argv": ["workflow", "run", "runtime-validation.yml", "--ref", "api"], "stdin": ""}])
        self.assertEqual(self.dispatcher["permissions"], {"actions": "write", "contents": "read"})
        self.assertEqual(self.receiver["permissions"]["contents"], "read")
        self.assertEqual(self.receiver["on"]["workflow_dispatch"]["inputs"]["run_restore"],
                         {"description": "Run restore drill with evidence", "type": "boolean", "default": "false"})
        self.assertIn("inputs.run_restore", self.receiver["jobs"]["restore-evidence"]["if"])
        self.assertEqual(self.receiver["jobs"]["restore-evidence"]["env"]["PARKIO_ENVIRONMENT_TYPE"], "CI_EPHEMERAL")


    @staticmethod
    def children_output(outputs):
        """The `children` job output as (workflow, run id, url) rows."""
        lines = outputs.splitlines()
        start = lines.index("children<<DISPATCHED_RUNS_EOF")
        end = lines.index("DISPATCHED_RUNS_EOF", start)
        return [tuple(line.split("\t")) for line in lines[start + 1:end]]

    def test_each_dispatched_run_is_recorded_from_the_gh_output(self):
        result, calls, outputs = self.dispatch(event="schedule", cron="23 4 * * 1", with_outputs=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["argv"][:3] for call in calls],
                         [["workflow", "run", "backup-restore-drill.yml"],
                          ["workflow", "run", "restore-drill-01-procedure.yml"]])
        self.assertEqual(self.children_output(outputs), [
            ("backup-restore-drill.yml", "9001", f"https://github.com/{REPO}/actions/runs/9001"),
            ("restore-drill-01-procedure.yml", "9002", f"https://github.com/{REPO}/actions/runs/9002"),
        ])

    def test_manual_single_dispatch_is_recorded_too(self):
        result, _, outputs = self.dispatch(event="workflow_dispatch", only="staging-verification.yml",
                                           run_restore="true", with_outputs=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.children_output(outputs),
                         [("staging-verification.yml", "9001", f"https://github.com/{REPO}/actions/runs/9001")])

    @staticmethod
    def run_record(run_id, seconds_ago, workflow="backend-integration.yml", branch="api",
                   event="workflow_dispatch", actor="github-actions[bot]"):
        created = datetime.now(timezone.utc) - timedelta(seconds=seconds_ago)
        return {"databaseId": run_id, "createdAt": created.strftime("%Y-%m-%dT%H:%M:%SZ"),
                "workflow": workflow, "branch": branch, "event": event, "actor": actor}

    def test_without_a_url_only_the_bot_dispatched_api_run_is_taken(self):
        self.assertIsNotNone(shutil.which("jq"), "jq is required: the lookup's --jq expression is evaluated")
        runs = [
            # A person dispatched the same workflow just before: never attributed (#216 review).
            self.run_record(4001, 5, actor="octo-operator"),
            self.run_record(4242, 3),
            self.run_record(4002, 3600),  # an older bot run, before this dispatch
            self.run_record(4003, 2, workflow="security-ci.yml"),
            self.run_record(4004, 2, branch="master"),
            self.run_record(4005, 2, event="schedule"),
        ]
        result, calls, outputs = self.dispatch(event="schedule", cron="17 3 * * *", with_outputs=True,
                                               scenario={"print_url": False, "runs": runs})
        self.assertEqual(result.returncode, 0, result.stderr)
        lookup = [call["argv"] for call in calls if call["argv"][:2] == ["run", "list"]]
        self.assertEqual(len(lookup), 1)
        self.assertEqual(lookup[0][:14], ["run", "list", "--workflow", "backend-integration.yml",
                                          "--branch", "api", "--event", "workflow_dispatch",
                                          "--user", "github-actions[bot]", "--limit", "20",
                                          "--json", "databaseId,createdAt"])
        self.assertEqual(self.children_output(outputs),
                         [("backend-integration.yml", "4242", f"https://github.com/{REPO}/actions/runs/4242")])

    def test_a_person_dispatched_run_alone_is_not_attributed(self):
        self.assertIsNotNone(shutil.which("jq"), "jq is required: the lookup's --jq expression is evaluated")
        result, calls, outputs = self.dispatch(event="schedule", cron="17 3 * * *", with_outputs=True,
                                               scenario={"print_url": False,
                                                         "runs": [self.run_record(4001, 5, actor="octo-operator")]})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.children_output(outputs), [("backend-integration.yml", "unidentified", "")])

    def test_a_run_that_cannot_be_found_is_recorded_as_unidentified(self):
        self.assertIsNotNone(shutil.which("jq"), "jq is required: the lookup's --jq expression is evaluated")
        result, calls, outputs = self.dispatch(event="schedule", cron="17 3 * * *", with_outputs=True,
                                               scenario={"print_url": False, "runs": []})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len([call for call in calls if call["argv"][:2] == ["run", "list"]]), 6)
        self.assertEqual(self.children_output(outputs), [("backend-integration.yml", "unidentified", "")])
        watched, summary = self.watch("backend-integration.yml\tunidentified\t\n")
        self.assertNotEqual(watched.returncode, 0)
        self.assertIn("::error title=Dispatched run did not succeed::backend-integration.yml: unidentified",
                      watched.stdout)

    def test_a_failed_dispatch_stops_with_the_gh_error(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            failing = temp / "gh"
            failing.write_text("#!/usr/bin/env bash\necho 'HTTP 422: workflow not found' >&2\nexit 1\n")
            failing.chmod(0o755)
            env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}", GH_REPO=REPO,
                       GITHUB_OUTPUT=str(temp / "out"), RUNNER_TEMP=str(temp),
                       EVENT="schedule", CRON="17 3 * * *", ONLY="", RUN_RESTORE="")
            script = self.dispatcher["jobs"]["dispatch"]["steps"][0]["run"]
            result = subprocess.run(["bash", "-e", "-c", script], env=env, capture_output=True,
                                    text=True, stdin=subprocess.DEVNULL)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("HTTP 422: workflow not found", result.stderr)

    def test_watch_passes_only_when_every_run_succeeds(self):
        children = (f"security-ci.yml\t11\thttps://github.com/{REPO}/actions/runs/11\n"
                    f"supply-chain.yml\t12\thttps://github.com/{REPO}/actions/runs/12\n")
        result, summary = self.watch(children, {"views": {
            "11": ["queued null", "in_progress null", "api-error", "completed success"],
            "12": ["completed success"]}})
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"| security-ci.yml | https://github.com/{REPO}/actions/runs/11 | completed | success |", summary)
        self.assertIn(f"| supply-chain.yml | https://github.com/{REPO}/actions/runs/12 | completed | success |", summary)

    def test_watch_fails_on_any_run_that_does_not_succeed(self):
        for conclusion in ("failure", "cancelled", "timed_out", "skipped", "startup_failure", "action_required"):
            with self.subTest(conclusion=conclusion):
                children = (f"backend-integration.yml\t21\thttps://github.com/{REPO}/actions/runs/21\n"
                            f"security-ci.yml\t22\thttps://github.com/{REPO}/actions/runs/22\n")
                result, summary = self.watch(children, {"views": {"21": [f"completed {conclusion}"]}})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("::error title=Dispatched run did not succeed::backend-integration.yml: "
                              f"{conclusion} https://github.com/{REPO}/actions/runs/21", result.stdout)
                self.assertNotIn("::error title=Dispatched run did not succeed::security-ci.yml", result.stdout)
                self.assertIn(f"| backend-integration.yml | https://github.com/{REPO}/actions/runs/21 | completed | {conclusion} |",
                              summary)

    def test_watch_fails_when_a_run_is_still_open_at_the_deadline(self):
        children = f"staging-verification.yml\t31\thttps://github.com/{REPO}/actions/runs/31\n"
        result, summary = self.watch(children, {"views": {"31": ["in_progress null"]}}, wait_seconds="0")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("staging-verification.yml: still in_progress after 0s", result.stdout)
        self.assertIn("| staging-verification.yml |", summary)

    def test_watch_fails_without_recorded_runs(self):
        result, _ = self.watch("\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("::error title=No dispatched runs::", result.stdout)

    def test_watch_job_shape(self):
        dispatch = self.dispatcher["jobs"]["dispatch"]
        watch = self.dispatcher["jobs"]["watch"]
        self.assertEqual(dispatch["outputs"], {"children": "${{ steps.start.outputs.children }}"})
        self.assertEqual(dispatch["steps"][0]["id"], "start")
        self.assertEqual(watch["needs"], "dispatch")
        self.assertEqual(watch["permissions"], {"actions": "read"})
        self.assertNotIn("if", watch)
        self.assertNotIn("continue-on-error", watch)
        env = watch["steps"][0]["env"]
        self.assertEqual(env["CHILDREN"], "${{ needs.dispatch.outputs.children }}")
        # The step reports open runs before the job timeout would cancel it.
        self.assertGreater(int(watch["timeout-minutes"]) * 60, int(env["WAIT_SECONDS"]))
        self.assertGreaterEqual(int(env["WAIT_SECONDS"]), 90 * 60)


if __name__ == "__main__":
    unittest.main()
