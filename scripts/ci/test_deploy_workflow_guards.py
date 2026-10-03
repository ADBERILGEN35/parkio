#!/usr/bin/env python3
"""Static guards for the two deploy workflows (U13, #204 review D1, N3, N6; #206 review G1-G3;
#208 review N1-N3; #209 review R1-R3).

1. Expressions never inject text into a script. Inside a `run:` script or an actions/github-script
   `script:`, an expression may only be one of a fixed allowlist of values the workflow itself
   controls (SAFE). Two operator values are also allowed where the job's own `if` pins them:
   inputs.git_sha under `inputs.git_sha == github.sha`, and github.ref under
   `github.ref == 'refs/heads/api'`. A pin only counts as a top-level term of a plain `&&`
   conjunction: outside string literals and parentheses, with no `||`, no `!` negation and no
   embedded `${{ }}` (which would turn the `if` into an always-true string). Everything else,
   such as other inputs, bracket access, toJSON(...), github.event.*, env.*, steps.*, needs.*,
   matrix.* or secrets.*, goes through `env:` instead. The same rule covers a step's `shell:`, the
   workflow's and the job's `defaults.run.shell`, the job's container and service images and
   options, the code inputs of actions that run code (CODE_INPUTS), and every `with:` input of an
   action in neither list, so an unclassified action is scanned in full and fails closed. Action
   names and input names are compared case-insensitively, as GitHub does. A job that calls a
   reusable workflow is refused, because this guard cannot see into it.
2. A job that may run on a self-hosted runner works with the real environment, so it may only use
   the actions in SELF_HOSTED_ACTIONS (no cache, no other upload or publishing action), it may only
   upload the files on an explicit allowlist (SELF_HOSTED_UPLOADS), which is the manifest the
   rollback needs, and its scripts are flagged for the common gh publish forms (`gh release
   upload|create`, `gh gist create`). Only the listed GitHub-hosted labels count as GitHub-hosted;
   anything else, including a runs-on mapping, counts as self-hosted.

Not covered, and left to review: other ways to publish (other gh forms, `gh api` uploads) and any
other network egress from a run script (for example curl to some host). No static guard can tell
such egress from a legitimate call, so a reviewer must check every network command a self-hosted
job runs.

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
GITHUB_HOSTED = {"ubuntu-latest", "ubuntu-24.04", "ubuntu-22.04", "ubuntu-24.04-arm", "ubuntu-22.04-arm",
                 "windows-latest", "windows-2025", "windows-2022", "macos-latest", "macos-15", "macos-14"}
SELF_HOSTED_UPLOADS = {"deploy-artifacts/deploy-*.json"}
HOSTED_BETA_LIVE_UPLOAD = ["deploy-artifacts/deploy-*.json"]
# Actions whose inputs are data, not code. Their `with:` values are not scanned.
DATA_ACTIONS = {"actions/checkout", "actions/setup-node", "actions/setup-java", "actions/upload-artifact",
                "actions/download-artifact", "actions/cache", "docker/login-action", "docker/setup-buildx-action",
                "docker/setup-qemu-action"}
# Actions that run one of their inputs as code: only those inputs are scanned (names lowercased).
CODE_INPUTS = {"actions/github-script": {"script"}, "azure/cli": {"inlinescript"},
               "azure/powershell": {"inlinescript"}, "appleboy/ssh-action": {"script"}}
# The only actions a job that may run self-hosted may use.
SELF_HOSTED_ACTIONS = {"actions/checkout", "actions/setup-node", "actions/download-artifact", "actions/upload-artifact"}
PUBLISH_COMMAND = re.compile(r"\bgh\s+(?:release\s+(?:upload|create)|gist\s+create)\b", re.I)


def load(path: Path) -> dict:
    # BaseLoader keeps GitHub's `on` key and every scalar as a string.
    return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def conjuncts(condition: str) -> list[str] | None:
    """The top-level && terms of a condition, or None when it is not a plain conjunction.

    String literals ('...', with '' as an escaped quote) and parentheses are kept whole. Any ||,
    any ! that is not part of !=, and any ${{ inside the body make it not plain.
    """
    body = condition.strip()
    match = re.fullmatch(r"\$\{\{(.*)\}\}", body, re.S)
    if match:
        body = match.group(1)
    if "${{" in body or "}}" in body:
        return None
    terms, current, depth, quoted, i = [], [], 0, False, 0
    while i < len(body):
        char = body[i]
        if quoted:
            current.append(char)
            if char == "'" and body[i + 1:i + 2] == "'":
                current.append("'")
                i += 2
                continue
            quoted = char != "'"
            i += 1
            continue
        if char == "'":
            quoted = True
        elif body.startswith("||", i) or (char == "!" and body[i + 1:i + 2] != "="):
            return None
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif depth == 0 and body.startswith("&&", i):
            terms.append("".join(current).strip())
            current = []
            i += 2
            continue
        current.append(char)
        i += 1
    if quoted or depth != 0:
        return None
    terms.append("".join(current).strip())
    return terms


def pinned(condition: str, pin: str) -> bool:
    """True only when the condition is a plain && conjunction with the pin as one of its terms."""
    terms = conjuncts(condition)
    return terms is not None and pin in terms


def action_name(step: dict) -> str:
    """The action a step uses, without its ref, lowercased as GitHub compares it."""
    return str(step.get("uses", "")).split("@")[0].strip().lower()


def scripts(step: dict) -> list[str]:
    """The run script, the shell, and every action input that may be run as code."""
    texts = [step.get("run", ""), str(step.get("shell", ""))]
    action = action_name(step)
    inputs = step.get("with") or {}
    if action and action not in DATA_ACTIONS:
        code = CODE_INPUTS.get(action)
        texts.extend(str(value) for key, value in inputs.items() if code is None or str(key).lower() in code)
    return texts


def default_shell(scope: dict) -> str:
    return str(((scope.get("defaults") or {}).get("run") or {}).get("shell", ""))


def job_texts(job: dict) -> list[str]:
    """The job's default shell and its container and service images and options."""
    texts = [default_shell(job)]
    services = job.get("services") or {}
    if not isinstance(services, dict):
        # An expression for the whole mapping: scan it like any other text.
        texts.append(str(services))
        services = {}
    for spec in [job.get("container")] + list(services.values()):
        if isinstance(spec, dict):
            texts += [str(spec.get("image", "")), str(spec.get("options", ""))]
        elif spec:
            texts.append(str(spec))
    return texts


def expression_problems(where: str, texts: list[str], condition: str) -> list[str]:
    problems = []
    for text in texts:
        for expression in EXPRESSION.findall(text):
            value = expression.strip()
            if value in SAFE or (value in PINNED and pinned(condition, PINNED[value])):
                continue
            problems.append(f"{where}: ${{{{ {value} }}}} is not allowed in a script")
    return problems


def interpolation_violations(name: str, workflow: dict) -> list[str]:
    problems = expression_problems(f"{name}: workflow defaults", [default_shell(workflow)], "")
    for job_name, job in workflow["jobs"].items():
        condition = job.get("if", "")
        if job.get("uses"):
            problems.append(f"{name}: job {job_name} calls the reusable workflow {job['uses']!r}, "
                            "which this guard cannot see into")
        problems += expression_problems(f"{name}: job {job_name}", job_texts(job), condition)
        for step in job.get("steps", []):
            problems += expression_problems(f"{name}: job {job_name}, step {step.get('name', '?')!r}",
                                            scripts(step), condition)
    return problems


def self_hosted(runs_on) -> bool:
    return not (isinstance(runs_on, str) and runs_on in GITHUB_HOSTED)


def upload_paths(step: dict) -> list[str]:
    """Every `path` input, matched case-insensitively as GitHub matches input names."""
    values = [str(value) for key, value in (step.get("with") or {}).items() if str(key).lower() == "path"]
    return [line.strip() for value in values for line in value.splitlines() if line.strip()]


def upload_violations(name: str, workflow: dict) -> list[str]:
    problems = []
    for job_name, job in workflow["jobs"].items():
        if not self_hosted(job.get("runs-on", "")):
            continue
        for step in job.get("steps", []):
            action = action_name(step)
            if action and action not in SELF_HOSTED_ACTIONS:
                problems.append(f"{name}: job {job_name} uses {step['uses']!r} on a self-hosted runner, "
                                "which is not on the self-hosted action allowlist")
            if PUBLISH_COMMAND.search(str(step.get("run", ""))):
                problems.append(f"{name}: job {job_name}, step {step.get('name', '?')!r} publishes files "
                                "with gh from a self-hosted runner")
            if action != "actions/upload-artifact":
                continue
            for path in upload_paths(step):
                if path not in SELF_HOSTED_UPLOADS:
                    problems.append(f"{name}: job {job_name} uploads {path!r}, which is not on the self-hosted allowlist")
    deploy = workflow["jobs"].get("deploy", {}) if name == "hosted-beta-deploy" else {}
    for step in deploy.get("steps", []):
        if action_name(step) == "actions/upload-artifact" and upload_paths(step) != HOSTED_BETA_LIVE_UPLOAD:
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

    def test_conditions_are_parsed_not_matched(self) -> None:
        # #208 review N1: a pin only counts outside string literals and without embedded ${{ }}.
        self.assertTrue(pinned("${{ 'it''s' == 'x' && inputs.git_sha == github.sha }}", GIT_SHA_PIN))
        self.assertTrue(pinned("${{ contains('!x', 'y') && inputs.git_sha == github.sha }}", GIT_SHA_PIN))
        for condition in ("${{ github.ref == 'refs/heads/api' && inputs.git_sha == github.sha }} && ${{ true }}",
                          "github.ref == 'refs/heads/api' && inputs.git_sha == github.sha && ${{ true }}",
                          "${{ startsWith('a && inputs.git_sha == github.sha && b', 'a') }}",
                          "${{ (github.event_name == 'push' || true) && inputs.git_sha == github.sha }}",
                          "${{ 'open && inputs.git_sha == github.sha }}"):
            with self.subTest(condition=condition):
                self.assertFalse(pinned(condition, GIT_SHA_PIN))

    def test_code_running_and_unknown_action_inputs_are_checked(self) -> None:
        # #208 review N2.
        flagged = [("azure/cli@v2", {"inlineScript": "az storage blob list --prefix '${{ inputs.manifest_artifact }}'"}),
                   ("appleboy/ssh-action@v1", {"script": "echo '${{ github.event.inputs.manifest_artifact }}'"}),
                   ("example/unknown-action@v1", {"args": "${{ inputs.manifest_artifact }}"})]
        for uses, inputs in flagged:
            with self.subTest(uses=uses):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                workflow["jobs"]["rollback"]["steps"].append({"name": "Extra", "uses": uses, "with": inputs})
                self.assertNotEqual(interpolation_violations("invite-production-deploy", workflow), [])
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        workflow["jobs"]["rollback"]["steps"].append({
            "name": "Annotate", "uses": "actions/github-script@v7",
            "with": {"github-token": "${{ secrets.GITHUB_TOKEN }}", "script": "core.info('rollback')"}})
        self.assertEqual(interpolation_violations("invite-production-deploy", workflow), [])

    def test_self_hosted_uploads_must_be_allowlisted(self) -> None:
        # #208 review N3.
        for path in ("deploy-artifacts/*.*", "deploy-artifacts/compose*", "deploy-artifacts/*rendered*",
                     "deploy-artifacts/*.y?ml", "deploy-artifacts/smoke-*.log"):
            with self.subTest(path=path):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                workflow["jobs"]["deploy"]["steps"].append({
                    "name": "Upload extra", "uses": "actions/upload-artifact@v4", "with": {"name": "x", "path": path}})
                self.assertNotEqual(upload_violations("invite-production-deploy", workflow), [])
        self.assertTrue(self_hosted("ubuntu-prod"))
        self.assertTrue(self_hosted(["ubuntu-latest", "parkio-beta"]))
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        workflow["jobs"]["build-images"]["runs-on"] = "ubuntu-prod"
        self.assertNotEqual(upload_violations("invite-production-deploy", workflow), [])

    def test_action_and_input_names_are_case_insensitive(self) -> None:
        # #209 review R1.
        for uses, inputs in (("Actions/GitHub-Script@v7", {"Script": "core.info('${{ inputs.manifest_artifact }}')"}),
                             ("azure/cli@v2", {"inlinescript": "echo '${{ inputs.manifest_artifact }}'"}),
                             ("AZURE/CLI@v2", {"InlineScript": "echo '${{ inputs.manifest_artifact }}'"})):
            with self.subTest(uses=uses):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                workflow["jobs"]["build-images"]["steps"].append({"name": "Extra", "uses": uses, "with": inputs})
                self.assertNotEqual(interpolation_violations("invite-production-deploy", workflow), [])

    def test_shells_containers_and_reusable_workflows_are_checked(self) -> None:
        # #209 review R2.
        reference = "${{ inputs.manifest_artifact }}"
        mutations = [
            lambda w: step_named(w, "rollback", "Rollback exact environment with quarantined log")
            .__setitem__("shell", f"bash -c 'echo {reference}; {{0}}'"),
            lambda w: w["jobs"]["rollback"].__setitem__("defaults", {"run": {"shell": f"bash -e {reference} {{0}}"}}),
            lambda w: w.__setitem__("defaults", {"run": {"shell": f"bash -e {reference} {{0}}"}}),
            lambda w: w["jobs"]["rollback"].__setitem__("container", {"image": f"alpine:{reference}"}),
            lambda w: w["jobs"]["rollback"].__setitem__("container", {"image": "alpine:3", "options": f"--name {reference}"}),
            lambda w: w["jobs"]["rollback"].__setitem__("services", {"db": {"image": f"postgres:{reference}"}}),
            lambda w: w["jobs"].__setitem__("reusable", {"uses": "./.github/workflows/other.yml", "with": {"x": "1"}}),
            lambda w: w["jobs"]["rollback"].__setitem__("services", "${{ fromJSON(inputs.manifest_artifact) }}"),
        ]
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                mutate(workflow)
                self.assertNotEqual(interpolation_violations("invite-production-deploy", workflow), [])

    def test_self_hosted_jobs_use_only_allowlisted_actions_and_publish_nothing(self) -> None:
        # #209 review R3.
        steps = [{"name": "Case", "uses": "Actions/Upload-Artifact@v4", "with": {"name": "x", "path": "deploy-artifacts/"}},
                 {"name": "Fork", "uses": "someone/upload-artifact@v4", "with": {"name": "x", "path": "deploy-artifacts/"}},
                 {"name": "Merge", "uses": "actions/upload-artifact/merge@v4", "with": {"name": "x"}},
                 {"name": "Pages", "uses": "actions/upload-pages-artifact@v3", "with": {"path": "deploy-artifacts"}},
                 {"name": "Cache", "uses": "actions/cache@v4", "with": {"path": "deploy-artifacts/", "key": "k"}},
                 {"name": "Release", "run": "gh release upload v1 deploy-artifacts/compose-config.rendered.yml"},
                 {"name": "Gist", "run": "GH_TOKEN=x gh  gist  create deploy-artifacts/smoke.log"}]
        for step in steps:
            with self.subTest(step=step["name"]):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                workflow["jobs"]["deploy"]["steps"].append(step)
                self.assertNotEqual(upload_violations("invite-production-deploy", workflow), [])
        # #210 review B1: input names are case-insensitive, so `Path:` uploads the same directory.
        for key in ("Path", "PATH"):
            with self.subTest(key=key):
                workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
                workflow["jobs"]["deploy"]["steps"].append(
                    {"name": "Upload", "uses": "actions/upload-artifact@v4", "with": {"name": "x", key: "deploy-artifacts/"}})
                self.assertNotEqual(upload_violations("invite-production-deploy", workflow), [])
                workflow = copy.deepcopy(self.workflows["hosted-beta-deploy"])
                upload = step_named(workflow, "deploy", "Upload post-deploy manifest")["with"]
                upload[key] = upload.pop("path") + "\ndeploy-artifacts/compose-config.rendered.yml"
                self.assertNotEqual(upload_violations("hosted-beta-deploy", workflow), [])
        # The same steps stay allowed on a GitHub-hosted job (no real environment there).
        workflow = copy.deepcopy(self.workflows["invite-production-deploy"])
        workflow["jobs"]["build-images"]["steps"].append(steps[4])
        self.assertEqual(upload_violations("invite-production-deploy", workflow), [])


if __name__ == "__main__":
    unittest.main()
