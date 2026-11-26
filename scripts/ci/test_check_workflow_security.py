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

import yaml

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

    def test_object_filters_on_github_are_refused(self):
        # github.* yields every property of github, event and head_ref included (#238 review R4).
        for expression in ("toJSON(github.*)", "join(github.*, ',')", "github.*.pull_request",
                           "toJSON(GitHub . *.issue)"):
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


class GradleNeedsSetupJava(unittest.TestCase):
    SETUP_JAVA = {"uses": "actions/setup-java@v4", "with": {"distribution": "temurin", "java-version": "21"}}

    def test_a_job_running_gradlew_needs_setup_java_first(self):
        # Without it Gradle runs on the runner's default JDK; ubuntu-26.04 ships 25, which Gradle 8.11.1
        # cannot run on (#486).
        problems = check.check_workflow("w.yml", workflow({"uses": "actions/checkout@v4"}, {"run": "./gradlew build"}))
        self.assertEqual(len(problems), 1)
        self.assertIn("runs gradlew without an earlier actions/setup-java step", problems[0])
        self.assertEqual(check.check_workflow("w.yml", workflow(self.SETUP_JAVA, {"run": "./gradlew build"})), [])

    def test_setup_java_after_the_gradle_step_is_too_late(self):
        problems = check.check_workflow("w.yml", workflow({"run": "./gradlew build"}, self.SETUP_JAVA))
        self.assertEqual(len(problems), 1)

    def test_every_form_of_running_the_wrapper_counts(self):
        for script in ("./gradlew build", "gradlew.bat build", "chmod +x gradlew && ./gradlew build",
                       "set -e\n  ./gradlew :a:test \\\n    --tests X", "(cd x; ../gradlew test)"):
            self.assertEqual(len(check.check_workflow("w.yml", workflow({"run": script}))), 1, script)

    def test_making_the_wrapper_executable_is_not_running_it(self):
        for script in ("chmod +x gradlew", "chmod +x ./gradlew scripts/*.sh", "# ./gradlew build runs in a script"):
            self.assertEqual(check.check_workflow("w.yml", workflow({"run": script})), [], script)


class GradleCacheOnPullRequests(unittest.TestCase):
    # setup-java and actions/cache save in their post step whenever their exact key had no entry, pull
    # requests included; such an entry serves only that pull request (#653).
    SETUP_JAVA_CACHE = {"uses": "actions/setup-java@v4",
                        "with": {"distribution": "temurin", "java-version": "21", "cache": "gradle"}}
    GRADLE_PATHS = "~/.gradle/caches/*\n!~/.gradle/caches/build-cache-1\n~/.gradle/wrapper\n"

    def flow(self, *steps, on=("pull_request",), job_if=None):
        flow = workflow(*steps)
        flow[True] = list(on)  # as PyYAML reads the key `on`
        if job_if is not None:
            flow["jobs"]["build"]["if"] = job_if
        return flow

    def cache(self, action, path=None, **step):
        return {"uses": f"actions/{action}@v4", "with": {"path": path or self.GRADLE_PATHS, "key": "gradle-k"}, **step}

    def saving_steps(self):
        return self.SETUP_JAVA_CACHE, self.cache("cache"), self.cache("cache/save")

    def test_a_step_that_saves_a_gradle_cache_on_pull_requests_is_refused(self):
        for step in self.saving_steps():
            problems = check.check_workflow("w.yml", self.flow(step))
            self.assertEqual(len(problems), 1, step)
            self.assertTrue(problems[0].startswith(f"w.yml: build: step 1 ({step['uses']}): "), problems[0])
            self.assertIn("on a pull request; restore it with actions/cache/restore", problems[0])
            self.assertIn("github.event_name != 'pull_request'", problems[0])

    def test_a_step_or_job_whose_if_excludes_pull_requests_may_save(self):
        for condition in ("github.event_name != 'pull_request' && github.ref == 'refs/heads/api'",
                          "${{ github.ref == 'refs/heads/api' && github.event_name != 'pull_request' }}",
                          "(GitHub.Event_Name!='PULL_REQUEST') && steps.deps.outputs.cache-hit != 'true'",
                          "always() && 'pull_request' != github.event_name"):
            for step in self.saving_steps():
                self.assertEqual(check.check_workflow("w.yml", self.flow({**step, "if": condition})), [], condition)
                self.assertEqual(check.check_workflow("w.yml", self.flow(step, job_if=condition)), [], condition)

    def test_a_condition_that_lets_a_pull_request_through_is_no_guard(self):
        for condition in ("github.event_name != 'pull_request' || github.ref == 'refs/heads/api'",
                          "(github.event_name != 'pull_request' || always()) && success()",
                          "!(github.event_name != 'pull_request')",
                          "github.event_name == 'pull_request'",
                          "github.ref_name != 'pull_request'",
                          "steps.deps.outputs.cache-hit != 'true'",
                          "contains('github.event_name != ''pull_request''', 'x') && true"):
            for step in self.saving_steps():
                problems = check.check_workflow("w.yml", self.flow({**step, "if": condition}))
                self.assertEqual(len(problems), 1, condition)

    def test_restores_and_caches_of_other_files_pass(self):
        steps = (self.cache("cache/restore"),
                 self.cache("cache", path="${{ env.TRIVY_CACHE_DIR }}/db"),
                 self.cache("cache/save", path="!~/.gradle/caches/build-cache-1\nbuild/reports"),
                 {"uses": "actions/setup-node@v4", "with": {"node-version": "22", "cache": "pnpm"}},
                 {"uses": "actions/setup-java@v4", "with": {"distribution": "temurin", "java-version": "21"}})
        self.assertEqual(check.check_workflow("w.yml", self.flow(*steps)), [])

    def test_only_a_workflow_that_a_pull_request_triggers_is_held_to_it(self):
        for on in (["push", "workflow_dispatch"], ["schedule"]):
            self.assertEqual(check.check_workflow("w.yml", self.flow(*self.saving_steps(), on=on)), [], on)
        for trigger in ("pull_request", ["push", "pull_request"], {"pull_request": {"paths": ["x"]}}):
            flow = workflow(self.SETUP_JAVA_CACHE)
            flow[True] = trigger
            self.assertEqual(len(check.check_workflow("w.yml", flow)), 1, trigger)

    def test_the_on_key_is_read_as_pyyaml_loads_a_workflow(self):
        text = ("on:\n  pull_request:\n    paths: [x]\n  push:\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
                "    steps:\n      - uses: actions/setup-java@v4\n        with:\n          cache: gradle\n")
        self.assertEqual(len(check.check_workflow("w.yml", yaml.safe_load(text))), 1)
        jobs = yaml.safe_load(text[text.index("jobs"):])
        self.assertEqual(len(check.check_workflow("w.yml", {"on": "pull_request", **jobs})), 1)


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
