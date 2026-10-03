#!/usr/bin/env python3
"""Static guards for the two deploy workflows (U13, #204 review D1, N3, N6; #206 review G1-G3).

1. Expressions never inject text into a script. Inside a `run:` script or an actions/github-script
   `script:`, an expression may only be one of a fixed allowlist of values the workflow itself
   controls (SAFE). Two operator values are also allowed where the job's own `if` pins them:
   inputs.git_sha under `inputs.git_sha == github.sha`, and github.ref under
   `github.ref == 'refs/heads/api'`. A pin only counts as a top-level term of a plain `&&`
   conjunction, with no `||` and no `!` negation anywhere in the condition. Everything else,
   such as other inputs, bracket access, toJSON(...), github.event.*, env.*, steps.*, needs.*,
   matrix.* or secrets.*, goes through `env:` instead.
2. A job that may run on a self-hosted runner works with the real environment, so its artifact
   uploads name files, not a directory or a bare `*`/`**`, and never YAML, .env or the rendered
   Compose model. The hosted-beta live upload is exactly the manifest the rollback needs. A
   runs-on mapping (runner group or labels) counts as self-hosted.

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
SAFE = {"github.sha", "github.run_id", "github.run_attempt", "github.run_number", "github.workspace",
        "github.repository", "runner.temp"}
GIT_SHA_PIN = "inputs.git_sha == github.sha"
REF_PIN = "github.ref == 'refs/heads/api'"
PINNED = {"inputs.git_sha": GIT_SHA_PIN, "github.ref": REF_PIN}
NEGATION = re.compile(r"!(?!=)")
GITHUB_HOSTED = ("ubuntu-", "windows-", "macos-")
HOSTED_BETA_LIVE_UPLOAD = ["deploy-artifacts/deploy-*.json"]


def load(path: Path) -> dict:
    # BaseLoader keeps GitHub's `on` key and every scalar as a string.
    return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def pinned(condition: str, pin: str) -> bool:
    """True only when the condition is a plain && conjunction with the pin as one of its terms."""
    body = condition.strip()
    match = re.fullmatch(r"\$\{\{(.*)\}\}", body, re.S)
    if match:
        body = match.group(1)
    if "||" in body or NEGATION.search(body):
        return False
    return pin in {term.strip() for term in body.split("&&")}


def scripts(step: dict) -> list[str]:
    texts = [step.get("run", "")]
    if "actions/github-script" in step.get("uses", ""):
        texts.append((step.get("with") or {}).get("script", ""))
    return texts


def interpolation_violations(name: str, workflow: dict) -> list[str]:
    problems = []
    for job_name, job in workflow["jobs"].items():
        condition = job.get("if", "")
        for step in job.get("steps", []):
            for text in scripts(step):
                for expression in EXPRESSION.findall(text):
                    value = expression.strip()
                    if value in SAFE or (value in PINNED and pinned(condition, PINNED[value])):
                        continue
                    problems.append(f"{name}: job {job_name}, step {step.get('name', '?')!r}: "
                                    f"${{{{ {value} }}}} is not allowed in a script")
    return problems


def self_hosted(runs_on) -> bool:
    if isinstance(runs_on, dict):
        return True
    labels = runs_on if isinstance(runs_on, list) else [runs_on]
    return any(not str(label).startswith(GITHUB_HOSTED) for label in labels)


def upload_paths(step: dict) -> list[str]:
    return [line.strip() for line in str((step.get("with") or {}).get("path", "")).splitlines() if line.strip()]


def upload_violations(name: str, workflow: dict) -> list[str]:
    problems = []
    for job_name, job in workflow["jobs"].items():
        if not self_hosted(job.get("runs-on", "")):
            continue
        for step in job.get("steps", []):
            if "actions/upload-artifact" not in step.get("uses", ""):
                continue
            for path in upload_paths(step):
                last = path.rstrip("/").rsplit("/", 1)[-1]
                if path.endswith("/") or last in ("*", "**") or ("*" not in last and "." not in last):
                    problems.append(f"{name}: job {job_name} uploads the directory {path!r}")
                if "compose-config" in path or path.endswith((".yml", ".yaml", ".env")):
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

    def test_scripts_only_interpolate_allowlisted_values(self) -> None:
        for name, workflow in self.workflows.items():
            self.assertEqual(interpolation_violations(name, workflow), [])

    def test_self_hosted_uploads_name_files_and_the_live_upload_is_the_manifest(self) -> None:
        for name, workflow in self.workflows.items():
            self.assertEqual(upload_violations(name, workflow), [])

    def test_pins_must_be_plain_conjuncts(self) -> None:
        self.assertTrue(pinned("${{ github.event_name == 'workflow_dispatch' && inputs.git_sha == github.sha }}", GIT_SHA_PIN))
        self.assertTrue(pinned("${{ inputs.action != 'build' && inputs.git_sha == github.sha }}", GIT_SHA_PIN))
        for condition in ("${{ !(inputs.git_sha == github.sha) }}",
                          "${{ inputs.git_sha == github.sha || true }}",
                          "${{ !cancelled() && inputs.git_sha == github.sha }}",
                          "${{ inputs.git_sha != github.sha }}",
                          "${{ (inputs.git_sha == github.sha) }}",
                          "${{ github.event_name == 'workflow_dispatch' }}"):
            with self.subTest(condition=condition):
                self.assertFalse(pinned(condition, GIT_SHA_PIN))

    def test_interpolation_guard_catches_mutations(self) -> None:
        cases = [
            # D1: the build job is not gated, so its git_sha and ref stay in env.
            ("invite-production-deploy", "build-images", "Verify manual build pin",
             "./scripts/verify-invite-production-ref.sh --expected-sha '${{ inputs.git_sha }}'"),
            ("invite-production-deploy", "build-images", "Verify manual build pin", "echo '${{ github.ref }}'"),
            # N3: the rollback job pins git_sha, but not the manifest reference.
            ("invite-production-deploy", "rollback", "Verify trusted ref and clean checkout",
             "test -n '${{ inputs.manifest_artifact }}'"),
            ("hosted-beta-deploy", "rollback", "Rollback", "echo '${{ github.event.inputs.manifest_artifact }}'"),
            ("invite-production-deploy", "build-images", "Verify manual build pin", "echo '${{ github.head_ref }}'"),
            # G2: other ways to reach operator or derived text.
            ("invite-production-deploy", "rollback", "Rollback exact environment with quarantined log",
             "echo '${{ inputs['manifest_artifact'] }}'"),
            ("invite-production-deploy", "rollback", "Rollback exact environment with quarantined log",
             "echo '${{ toJSON(inputs) }}'"),
            ("invite-production-deploy", "rollback", "Rollback exact environment with quarantined log",
             "echo '${{ github.event.ref }}'"),
            ("invite-production-deploy", "rollback", "Rollback exact environment with quarantined log",
             "echo '${{ env.PARKIO_MANIFEST_REFERENCE }}'"),
            ("invite-production-deploy", "rollback", "Rollback exact environment with quarantined log",
             "echo '${{ steps.manifest_run.outputs.artifact }}'"),
            ("invite-production-deploy", "deploy", "Deploy exact commit with quarantined log",
             "echo '${{ needs.build-images.outputs.manifest_artifact }}'"),
            ("invite-production-deploy", "deploy", "Deploy exact commit with quarantined log",
             "echo '${{ secrets.GITHUB_TOKEN }}'"),
        ]
        for name, job, step_name, script in cases:
            with self.subTest(job=job, script=script):
                workflow = copy.deepcopy(self.workflows[name])
                step_named(workflow, job, step_name)["run"] = script
                self.assertNotEqual(interpolation_violations(name, workflow), [])

    def test_github_script_blocks_are_checked(self) -> None:
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        workflow["jobs"]["rollback"]["steps"].append({
            "name": "Annotate", "uses": "actions/github-script@v7",
            "with": {"script": "core.info('${{ inputs.manifest_artifact }}')"}})
        self.assertNotEqual(interpolation_violations("invite-production-deploy", workflow), [])

    def test_pinned_values_stay_allowed_only_under_a_strict_pin(self) -> None:
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        step = "step 'Deploy exact commit with quarantined log'"
        step_named(workflow, "deploy", "Deploy exact commit with quarantined log")["run"] = (
            "./scripts/deploy-invite-production.sh --expected-sha '${{ inputs.git_sha }}' --ref '${{ github.ref }}'")
        self.assertEqual(interpolation_violations("invite-production-deploy", workflow), [])
        # G1: the pins' text is still present, but the condition no longer enforces them.
        gate = workflow["jobs"]["deploy"]["if"]
        for weakened in (gate.replace("}}", "|| true }}"), "${{ !(" + gate.strip()[3:-2].strip() + ") }}",
                         "${{ github.event_name == 'workflow_dispatch' }}"):
            with self.subTest(condition=weakened):
                workflow["jobs"]["deploy"]["if"] = weakened
                self.assertEqual(len([v for v in interpolation_violations("invite-production-deploy", workflow)
                                      if step in v]), 2)

    def test_upload_guard_catches_mutations(self) -> None:
        for path in ("deploy-artifacts/", "deploy-artifacts", "deploy-artifacts/*", "deploy-artifacts/**",
                     "deploy-artifacts/compose-config.rendered.yml", "deploy-artifacts/compose.yaml",
                     "docker/.env", "deploy-artifacts/deploy-*.json\ndeploy-artifacts/compose-config.rendered.yml"):
            with self.subTest(path=path):
                workflow = copy.deepcopy(self.workflows["hosted-beta-deploy"])
                step_named(workflow, "deploy", "Upload post-deploy manifest")["with"]["path"] = path
                self.assertNotEqual(upload_violations("hosted-beta-deploy", workflow), [])

    def test_runner_groups_and_unknown_labels_count_as_self_hosted(self) -> None:
        self.assertTrue(self_hosted({"group": "production", "labels": ["linux"]}))
        self.assertTrue(self_hosted(["self-hosted", "parkio-beta"]))
        self.assertTrue(self_hosted("${{ matrix.runner }}"))
        self.assertFalse(self_hosted("ubuntu-latest"))
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        workflow["jobs"]["build-images"]["runs-on"] = {"group": "production", "labels": ["linux"]}
        step_named(workflow, "build-images", "Upload secret-free manifest evidence")["with"]["path"] = "deploy-artifacts/"
        self.assertNotEqual(upload_violations("invite-production-deploy", workflow), [])


if __name__ == "__main__":
    unittest.main()
