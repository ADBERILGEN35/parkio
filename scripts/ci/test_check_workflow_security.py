#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_workflow_security.py (CL-F40)."""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def test_context_names_match_in_any_case_as_github_does(self):
        for expression in ("Inputs.x", "INPUTS.x", "github.EVENT.issue.title", "GITHUB.HEAD_REF"):
            problems = check.check_workflow("w.yml", workflow({"run": f"echo ${{{{ {expression} }}}}"}))
            self.assertEqual(len(problems), 1, expression)

    def test_index_syntax_and_whole_objects_are_refused(self):
        for expression in ("inputs['x']", 'inputs["x"]', "github['head_ref']", "github.event['pull_request'].title",
                           "toJSON(github.event)", "toJSON(inputs)", "github [ 'event' ].issue.body",
                           "toJSON(github)", "fromJSON(toJSON(GitHub)).event.issue.title"):
            problems = check.check_workflow("w.yml", workflow({"run": f"echo ${{{{ {expression} }}}}"}))
            self.assertEqual(len(problems), 1, expression)

    def test_similar_names_that_are_not_inputs_or_event_fields_pass(self):
        for expression in ("github.event_name", "github['event_name']", "github.event_path",
                           "steps.inputs_check.outputs.ok", "needs.version.outputs.version", "github.base_ref",
                           "steps.inputs.outputs.ok", "needs.github.outputs.sha", "matrix.inputs"):
            self.assertEqual(check.check_workflow("w.yml", workflow({"run": f"echo ${{{{ {expression} }}}}"})), [],
                             expression)

    def test_default_shells_are_checked_at_workflow_and_job_level(self):
        flow = workflow({"run": "true"})
        flow["defaults"] = {"run": {"shell": "bash -c ${{ inputs.x }}"}}
        flow["jobs"]["build"]["defaults"] = {"run": {"shell": "bash -c ${{ github.head_ref }}"}}
        problems = check.check_workflow("w.yml", flow)
        self.assertEqual(len(problems), 2)
        self.assertTrue(problems[0].startswith("w.yml: defaults.run.shell:"))
        self.assertTrue(problems[1].startswith("w.yml: build: defaults.run.shell:"))

    def test_the_github_script_input_is_a_script(self):
        script_step = {"uses": "actions/github-script@v7", "with": {"script": "core.info('${{ inputs.x }}')"}}
        self.assertEqual(len(check.check_workflow("w.yml", workflow(script_step))), 1)
        data_step = {"uses": f"some/action@{SHA}", "with": {"script": "${{ inputs.x }}"}}
        self.assertEqual(check.check_workflow("w.yml", workflow(data_step)), [])


class Repository(unittest.TestCase):
    def test_every_repository_workflow_passes(self):
        self.assertEqual(check.main([]), 0)

    def test_yaml_extension_workflows_are_scanned_too(self):
        with tempfile.TemporaryDirectory() as root:
            workflows = Path(root, ".github", "workflows")
            workflows.mkdir(parents=True)
            workflows.joinpath("ok.yml").write_text("jobs: {}\n", encoding="utf-8")
            workflows.joinpath("late.yaml").write_text(
                "jobs:\n  build:\n    steps:\n      - uses: pnpm/action-setup@v4\n", encoding="utf-8")
            output = io.StringIO()
            with mock.patch.object(check, "ROOT", Path(root)), contextlib.redirect_stdout(output):
                self.assertEqual(check.main([]), 1)
            self.assertIn("late.yaml: build: step 1", output.getvalue())


if __name__ == "__main__":
    unittest.main()
