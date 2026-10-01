"""Offline contract test for the default-branch scheduled staging dispatcher."""

import json
import os
from pathlib import Path
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
import json, os, sys
argv = sys.argv[1:]
stdin = sys.stdin.read()
if "--json" in argv:
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
"""


class ScheduledStagingDispatchTest(unittest.TestCase):
    def setUp(self):
        self.dispatcher = workflow(DISPATCHER)
        self.receiver = workflow(RECEIVER)

    def dispatch(self, *, event, cron="", only="", run_restore=""):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            mock = temp / "gh"
            capture = temp / "calls.jsonl"
            mock.write_text(MOCK_GH)
            mock.chmod(0o755)
            env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}",
                       GH_CAPTURE=str(capture), EVENT=event, CRON=cron,
                       ONLY=only, RUN_RESTORE=run_restore)
            script = self.dispatcher["jobs"]["dispatch"]["steps"][0]["run"]
            result = subprocess.run(["bash", "-e", "-c", script], env=env,
                                    capture_output=True, text=True)
            calls = [json.loads(line) for line in capture.read_text().splitlines()] if capture.exists() else []
            return result, calls

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


if __name__ == "__main__":
    unittest.main()
