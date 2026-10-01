"""Offline contract test for the default-branch scheduled staging dispatcher."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
DISPATCHER = ROOT / ".github/workflows/scheduled-restore-drills.yml"
RECEIVER = ROOT / ".github/workflows/staging-verification.yml"


def workflow(path):
    # BaseLoader keeps the YAML key `on` and scalar defaults literal.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


class ScheduledStagingDispatchTest(unittest.TestCase):
    def setUp(self):
        self.dispatcher = workflow(DISPATCHER)
        self.receiver = workflow(RECEIVER)

    def dispatch(self, *, event, cron="", only="", run_restore=""):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            mock = temp / "gh"
            capture = temp / "calls.jsonl"
            mock.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "with open(os.environ['GH_CAPTURE'], 'a') as out:\n"
                "    out.write(json.dumps({'argv': sys.argv[1:], "
                "'stdin': sys.stdin.read()}) + '\\n')\n"
            )
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
        self.assertEqual(json.loads(calls[0]["stdin"]), {"run_restore": expected})
        self.assertIs(type(json.loads(calls[0]["stdin"])["run_restore"]), bool)

    def test_scheduled_staging_forwards_true(self):
        result, calls = self.dispatch(event="schedule", cron="47 5 * * 3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_staging(calls, True)

    def test_manual_true_and_false_and_default(self):
        for value, expected in (("true", True), ("false", False), ("", False)):
            with self.subTest(value=value):
                result, calls = self.dispatch(event="workflow_dispatch",
                                              only="staging-verification.yml", run_restore=value)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_staging(calls, expected)

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
