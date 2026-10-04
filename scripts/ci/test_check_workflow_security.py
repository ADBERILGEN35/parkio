#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_workflow_security.py (CL-F40)."""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_workflow_security as check  # noqa: E402

SHA = "0123456789abcdef0123456789abcdef01234567"
DIGEST = "sha256:" + "ab" * 32


def workflow(*steps, job_uses=None):
    job = {"runs-on": "ubuntu-latest", "steps": list(steps)}
    if job_uses:
        job = {"uses": job_uses}
    return {"jobs": {"build": job}}


class ActionPins(unittest.TestCase):
    def test_third_party_actions_need_a_full_commit_sha(self):
        self.assertEqual(check.check_workflow("w.yml", workflow({"uses": f"pnpm/action-setup@{SHA}"})), [])
        self.assertEqual(check.check_workflow("w.yml", workflow({"uses": f"gradle/actions/wrapper-validation@{SHA}"})), [])
        for ref in ("pnpm/action-setup@v4", f"pnpm/action-setup@{SHA[:12]}", "pnpm/action-setup@main"):
            problems = check.check_workflow("w.yml", workflow({"uses": ref}))
            self.assertEqual(len(problems), 1, ref)
            self.assertIn("not pinned to a full commit SHA", problems[0])

    def test_docker_actions_need_a_digest(self):
        self.assertEqual(check.check_workflow("w.yml", workflow({"uses": f"docker://zricethezav/gitleaks@{DIGEST}"})), [])
        problems = check.check_workflow("w.yml", workflow({"uses": "docker://zricethezav/gitleaks:v8.28.0"}))
        self.assertIn("not pinned to an image digest", problems[0])

    def test_github_owned_and_local_actions_are_exempt(self):
        steps = [{"uses": "actions/checkout@v4"}, {"uses": "github/codeql-action/init@v4"}, {"uses": "./.github/actions/x"}]
        self.assertEqual(check.check_workflow("w.yml", workflow(*steps)), [])

    def test_a_reusable_workflow_from_another_repository_needs_a_pin(self):
        problems = check.check_workflow("w.yml", workflow(job_uses="octo/shared/.github/workflows/build.yml@v1"))
        self.assertEqual(len(problems), 1)
        self.assertEqual(check.check_workflow("w.yml", workflow(job_uses="./.github/workflows/build.yml")), [])


class ScriptInjection(unittest.TestCase):
    def test_inputs_and_event_fields_in_a_script_are_refused(self):
        for expression in ("inputs.git_sha", "github.event.inputs.mode", "github.event.pull_request.title",
                           "github.head_ref", "github.event.issue.body"):
            problems = check.check_workflow("w.yml", workflow({"run": f"echo ${{{{ {expression} }}}}"}))
            self.assertEqual(len(problems), 1, expression)
            self.assertIn("pass it through env:", problems[0])

    def test_a_step_shell_is_checked_too(self):
        problems = check.check_workflow("w.yml", workflow({"run": "true", "shell": "bash -c ${{ inputs.x }}"}))
        self.assertEqual(len(problems), 1)

    def test_values_the_workflow_controls_and_env_use_are_allowed(self):
        step = {"env": {"MODE": "${{ inputs.mode }}"},
                "run": 'echo "$MODE" ${{ github.sha }} ${{ github.event_name }} ${{ runner.temp }}'}
        self.assertEqual(check.check_workflow("w.yml", workflow(step)), [])

    def test_a_multiline_expression_is_still_seen(self):
        problems = check.check_workflow("w.yml", workflow({"run": "echo ${{\n  inputs.x\n}}"}))
        self.assertEqual(len(problems), 1)


class Repository(unittest.TestCase):
    def test_every_repository_workflow_passes(self):
        self.assertEqual(check.main([]), 0)


if __name__ == "__main__":
    unittest.main()
