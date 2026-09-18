#!/usr/bin/env python3
"""Parsed workflow mutation tests; never execute confirmed real delivery."""
import copy
import os
from pathlib import Path
import subprocess
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/alerting-operator-acceptance.yml"
TOKEN = "ALERTING-REAL-SLACK-ACCEPTANCE"
CONDITION = "${{ github.event_name == 'workflow_dispatch' && inputs.confirm_real_slack_delivery == '" + TOKEN + "' }}"


def load(path):
    # BaseLoader preserves YAML 1.2/GitHub's 'on' key, unlike YAML 1.1 booleans.
    return yaml.load(path.read_text(), Loader=yaml.BaseLoader)


def validate(workflow):
    assert set(workflow["on"]) == {"workflow_dispatch"}, "real delivery must be manual only"
    confirmation = workflow["on"]["workflow_dispatch"]["inputs"]["confirm_real_slack_delivery"]
    assert confirmation == {"description": "Explicit real FIRING + RESOLVED delivery: " + TOKEN,
                            "type": "string", "required": "true", "default": ""}
    assert set(workflow["jobs"]) == {"authorize", "slack-operator"}
    assert "secrets." not in str(workflow.get("env", {}))
    authorization = workflow["jobs"]["authorize"]
    assert "secrets." not in str(authorization)
    assert authorization["steps"][-1]["run"] == "bash scripts/lib/assert-real-slack-acceptance.sh"
    assert authorization["steps"][-1]["env"] == {
        "PARKIO_CONFIRM_REAL_SLACK_DELIVERY": "${{ inputs.confirm_real_slack_delivery }}"}
    assert "continue-on-error" not in authorization
    job = workflow["jobs"]["slack-operator"]
    assert job["needs"] == "authorize"
    assert job["if"] == CONDITION
    assert job["env"]["PARKIO_CONFIRM_REAL_SLACK_DELIVERY"] == "${{ inputs.confirm_real_slack_delivery }}"
    assert job["env"]["PARKIO_ALERT_SLACK_WEBHOOK_URL"] == "${{ secrets.PARKIO_ALERT_SLACK_WEBHOOK_URL }}"
    delivery = [step for step in job["steps"] if "./scripts/alerting-operator-acceptance.sh" in step.get("run", "")]
    assert len(delivery) == 1 and delivery[0]["if"] == CONDITION


class WorkflowSafetyTest(unittest.TestCase):
    def test_real_workflow_contract(self):
        validate(load(WORKFLOW))

    def test_automatic_triggers_rejected(self):
        for event in ("push", "pull_request", "pull_request_target", "schedule", "workflow_call", "workflow_run"):
            workflow = load(WORKFLOW)
            workflow["on"][event] = {}
            with self.subTest(event=event), self.assertRaises(AssertionError):
                validate(workflow)

    def test_permissive_default_or_missing_guard_rejected(self):
        baseline = load(WORKFLOW)
        mutations = []
        w = copy.deepcopy(baseline)
        w["on"]["workflow_dispatch"]["inputs"]["confirm_real_slack_delivery"]["default"] = TOKEN
        mutations.append(w)
        for condition in ("true", "${{ always() }}", "${{ github.event_name == 'workflow_dispatch' }}"):
            w = copy.deepcopy(baseline)
            w["jobs"]["slack-operator"]["if"] = condition
            mutations.append(w)
        w = copy.deepcopy(baseline)
        w["jobs"]["slack-operator"]["steps"][-1]["if"] = "true"
        mutations.append(w)
        w = copy.deepcopy(baseline)
        w["jobs"]["authorize"]["env"] = {"SECRET": "${{ secrets.PARKIO_ALERT_SLACK_WEBHOOK_URL }}"}
        mutations.append(w)
        for w in mutations:
            with self.assertRaises(AssertionError):
                validate(w)

    def test_dispatch_matrix_guard_only_never_runs_delivery(self):
        for event in ("", "push", "pull_request", "schedule", "workflow_dispatch"):
            for confirmation in ("", "wrong", TOKEN):
                env = {**os.environ, "GITHUB_EVENT_NAME": event,
                       "PARKIO_CONFIRM_REAL_SLACK_DELIVERY": confirmation}
                result = subprocess.run(["bash", "scripts/lib/assert-real-slack-acceptance.sh"],
                                        cwd=ROOT, env=env, capture_output=True)
                self.assertEqual(result.returncode, 0 if event == "workflow_dispatch" and confirmation == TOKEN else 2)

    def test_unauthorized_script_exits_before_secret_or_resource_access(self):
        text = (ROOT / "scripts/alerting-operator-acceptance.sh").read_text()
        self.assertLess(text.index('bash "${ROOT}/scripts/lib/assert-real-slack-acceptance.sh"'),
                        text.index('COMPOSE=('))
        for event, confirmation in (("push", TOKEN), ("workflow_dispatch", ""), ("workflow_dispatch", "wrong")):
            env = {**os.environ, "GITHUB_EVENT_NAME": event, "PARKIO_CONFIRM_REAL_SLACK_DELIVERY": confirmation}
            env.pop("PARKIO_ALERT_SLACK_WEBHOOK_URL", None)
            result = subprocess.run(["bash", "scripts/alerting-operator-acceptance.sh"], cwd=ROOT,
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 2)
            self.assertIn("requires manual dispatch", result.stderr)
            self.assertNotIn("SECRET NOT AVAILABLE", result.stderr)

    def test_normal_ci_keeps_non_delivery_coverage_without_real_secret(self):
        workflow = load(ROOT / ".github/workflows/observability-validation.yml")
        self.assertIn("push", workflow["on"])
        self.assertIn("pull_request", workflow["on"])
        self.assertNotIn("secrets.", str(workflow))
        steps = str(workflow["jobs"]["validate"]["steps"])
        for script in ("test_alerting_workflow_safety.py", "observability-validate.sh", "./scripts/alerting-acceptance.sh"):
            self.assertIn(script, steps)
        self.assertNotIn("./scripts/alerting-operator-acceptance.sh", steps)
        source = (ROOT / "scripts/alerting-acceptance.sh").read_text()
        self.assertIn('export PARKIO_ALERT_WEBHOOK_URL="http://alerting-webhook:8080/webhook"', source)
        self.assertLess(source.index("unset PARKIO_ALERT_SLACK_WEBHOOK_URL"), source.index('"${COMPOSE[@]}" up'))
        # All workflow references to the real secret or executable stay in the guarded workflow.
        for path in (ROOT / ".github/workflows").glob("*.yml"):
            if path == WORKFLOW:
                continue
            text = str(load(path))
            self.assertNotIn("secrets.PARKIO_ALERT_SLACK_WEBHOOK_URL", text, str(path))
            self.assertNotIn("./scripts/alerting-operator-acceptance.sh", text, str(path))


if __name__ == "__main__":
    unittest.main()
