#!/usr/bin/env python3
"""Static guards for the two deploy workflows (U13, #204 review D1, N3, N6).

1. Operator text never becomes shell source. A run script may not interpolate a free-text
   workflow_dispatch input, the dispatch ref, or pull-request text. The exceptions are values a
   job's own `if` pins to a trusted value: inputs.git_sha under `inputs.git_sha == github.sha`,
   and github.ref under `github.ref == 'refs/heads/api'`. Everything else goes through env.
2. A job on a self-hosted runner works with the real environment, so its artifact uploads name
   files, never a whole directory, and never the rendered Compose model. The hosted-beta live
   upload is exactly the manifest the rollback needs.

Each guard is also run against mutated copies of the parsed workflows, so a broken guard fails.
"""
from __future__ import annotations

import copy
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = {name: ROOT / ".github" / "workflows" / f"{name}.yml"
             for name in ("invite-production-deploy", "hosted-beta-deploy")}
EXPRESSION = re.compile(r"\$\{\{(.*?)\}\}", re.S)
INPUT = re.compile(r"\b(?:github\.event\.)?inputs\.([A-Za-z0-9_-]+)")
REF = re.compile(r"\bgithub\.ref(?:_name)?\b")
PR_TEXT = re.compile(r"\bgithub\.(?:head_ref|event\.pull_request\.|event\.head_commit\.)")
GIT_SHA_PINNED = "inputs.git_sha == github.sha"
REF_PINNED = "github.ref == 'refs/heads/api'"
HOSTED_BETA_LIVE_UPLOAD = ["deploy-artifacts/deploy-*.json"]


def load(path: Path) -> dict:
    # BaseLoader keeps GitHub's `on` key and every scalar as a string.
    return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def free_text_inputs(workflow: dict) -> set[str]:
    inputs = ((workflow.get("on") or {}).get("workflow_dispatch") or {}).get("inputs") or {}
    return {name for name, spec in inputs.items() if (spec or {}).get("type", "string") == "string"}


def interpolation_violations(name: str, workflow: dict) -> list[str]:
    free_text = free_text_inputs(workflow)
    problems = []
    for job_name, job in workflow["jobs"].items():
        gate = job.get("if", "")
        for step in job.get("steps", []):
            for expression in EXPRESSION.findall(step.get("run", "")):
                where = f"{name}: job {job_name}, step {step.get('name', '?')!r}: ${{{{{expression.strip()}}}}}"
                for input_name in INPUT.findall(expression):
                    if input_name not in free_text:
                        continue
                    if input_name == "git_sha" and GIT_SHA_PINNED in gate:
                        continue
                    problems.append(f"{where} interpolates free-text input {input_name}")
                if REF.search(expression) and REF_PINNED not in gate:
                    problems.append(f"{where} interpolates the dispatch ref")
                if PR_TEXT.search(expression):
                    problems.append(f"{where} interpolates pull-request text")
    return problems


def upload_paths(step: dict) -> list[str]:
    return [line.strip() for line in str((step.get("with") or {}).get("path", "")).splitlines() if line.strip()]


def upload_violations(name: str, workflow: dict) -> list[str]:
    problems = []
    for job_name, job in workflow["jobs"].items():
        runs_on = job.get("runs-on", "")
        if "self-hosted" not in (runs_on if isinstance(runs_on, list) else [runs_on]):
            continue
        for step in job.get("steps", []):
            if "actions/upload-artifact" not in step.get("uses", ""):
                continue
            for path in upload_paths(step):
                last = path.rsplit("/", 1)[-1]
                if path.endswith("/") or ("*" not in last and "." not in last):
                    problems.append(f"{name}: job {job_name} uploads the directory {path!r}")
                if "compose-config" in path or path.endswith(".yml") or path.endswith(".env"):
                    problems.append(f"{name}: job {job_name} uploads {path!r}")
    deploy = workflow["jobs"].get("deploy", {}) if name == "hosted-beta-deploy" else {}
    for step in deploy.get("steps", []):
        if "actions/upload-artifact" in step.get("uses", "") and upload_paths(step) != HOSTED_BETA_LIVE_UPLOAD:
            problems.append(f"{name}: the live upload is {upload_paths(step)}, not {HOSTED_BETA_LIVE_UPLOAD}")
    return problems


def step_named(workflow: dict, job: str, step_name: str) -> dict:
    return next(step for step in workflow["jobs"][job]["steps"] if step.get("name") == step_name)


class DeployWorkflowGuardsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflows = {name: load(path) for name, path in WORKFLOWS.items()}

    def test_operator_text_never_becomes_shell_source(self) -> None:
        for name, workflow in self.workflows.items():
            self.assertEqual(interpolation_violations(name, workflow), [])

    def test_self_hosted_uploads_name_files_and_the_live_upload_is_the_manifest(self) -> None:
        for name, workflow in self.workflows.items():
            self.assertEqual(upload_violations(name, workflow), [])

    def test_the_free_text_inputs_are_the_ones_the_guard_protects(self) -> None:
        self.assertIn("git_sha", free_text_inputs(self.workflows["invite-production-deploy"]))
        self.assertIn("manifest_artifact", free_text_inputs(self.workflows["invite-production-deploy"]))
        self.assertIn("manifest_artifact", free_text_inputs(self.workflows["hosted-beta-deploy"]))

    def test_interpolation_guard_catches_mutations(self) -> None:
        cases = [
            # D1: the build job is not gated, so its git_sha must stay in env.
            ("invite-production-deploy", "build-images", "Verify manual build pin",
             "./scripts/verify-invite-production-ref.sh --expected-sha '${{ inputs.git_sha }}'"),
            ("invite-production-deploy", "build-images", "Verify manual build pin",
             "echo '${{ github.ref }}'"),
            # N3: the rollback job pins git_sha, but not the manifest reference.
            ("invite-production-deploy", "rollback", "Verify trusted ref and clean checkout",
             "test -n '${{ inputs.manifest_artifact }}'"),
            ("hosted-beta-deploy", "rollback", "Rollback", "echo '${{ github.event.inputs.manifest_artifact }}'"),
            ("invite-production-deploy", "build-images", "Verify manual build pin", "echo '${{ github.head_ref }}'"),
        ]
        for name, job, step_name, script in cases:
            with self.subTest(job=job, script=script):
                workflow = copy.deepcopy(self.workflows[name])
                step_named(workflow, job, step_name)["run"] = script
                self.assertNotEqual(interpolation_violations(name, workflow), [])

    def test_pinned_values_stay_allowed(self) -> None:
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        step_named(workflow, "deploy", "Deploy exact commit with quarantined log")["run"] = (
            "./scripts/deploy-invite-production.sh --expected-sha '${{ inputs.git_sha }}' --ref '${{ github.ref }}'")
        self.assertEqual(interpolation_violations("invite-production-deploy", workflow), [])
        # Without the job's pins the same step is two violations (the job's other steps add more).
        workflow["jobs"]["deploy"]["if"] = "${{ github.event_name == 'workflow_dispatch' }}"
        step = "step 'Deploy exact commit with quarantined log'"
        self.assertEqual(len([v for v in interpolation_violations("invite-production-deploy", workflow) if step in v]), 2)

    def test_upload_guard_catches_mutations(self) -> None:
        for path in ("deploy-artifacts/", "deploy-artifacts", "deploy-artifacts/compose-config.rendered.yml",
                     "deploy-artifacts/deploy-*.json\ndeploy-artifacts/compose-config.rendered.yml"):
            with self.subTest(path=path):
                workflow = copy.deepcopy(self.workflows["hosted-beta-deploy"])
                step_named(workflow, "deploy", "Upload post-deploy manifest")["with"]["path"] = path
                self.assertNotEqual(upload_violations("hosted-beta-deploy", workflow), [])


if __name__ == "__main__":
    unittest.main()
