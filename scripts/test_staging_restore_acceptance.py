"""Offline contract test: a requested staging restore counts only if it ran and succeeded (U08).

The scheduled dispatcher on master asks staging-verification on api for a restore. The
acceptance job runs only when that request is present, and still runs if restore-evidence
failed, was skipped, or was cancelled. A skipped, cancelled, failed, or missing result then
fails the job. Runs without a restore request skip the job, so they do not publish a green
"restore succeeded" check. The test executes the committed acceptance step and pins the
restore-evidence settings that make job success mean the pipeline ran. It does not dispatch
anything, download artifacts, or validate artifact contents.
"""

import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[1]
RECEIVER = ROOT / ".github/workflows/staging-verification.yml"

# The request as the event and input state it, independent of restore-evidence's own condition.
REQUEST = "${{ github.event_name == 'schedule' || (github.event_name == 'workflow_dispatch' && inputs.run_restore) }}"
RESTORE_IF = "github.event_name == 'schedule' || (github.event_name == 'workflow_dispatch' && inputs.run_restore)"
ACCEPTANCE_IF = "${{ always() && (github.event_name == 'schedule' || (github.event_name == 'workflow_dispatch' && inputs.run_restore)) }}"


def workflow():
    # BaseLoader keeps the YAML key `on` and every scalar literal.
    return yaml.load(RECEIVER.read_text(), Loader=yaml.BaseLoader)


def _declared_shell(owner):
    defaults = owner.get("defaults") if isinstance(owner, dict) else None
    run = defaults.get("run") if isinstance(defaults, dict) else None
    if isinstance(run, dict):
        return run.get("shell")
    return None


def effective_shell(document, job, step):
    """Shell Actions uses for this step.

    Step shell wins, then the job default, then the workflow default. An omitted
    shell on Linux is ``bash -e {0}``. The name ``bash`` is the documented
    pipefail template. ``{0}`` is the step script file, not ``bash -c``.
    """
    chosen = step.get("shell")
    if chosen is None:
        chosen = _declared_shell(job)
    if chosen is None:
        chosen = _declared_shell(document)
    if chosen is None:
        return "bash -e {0}"
    if chosen == "bash":
        return "bash --noprofile --norc -eo pipefail {0}"
    if chosen == "sh":
        return "sh -e {0}"
    if "{0}" not in chosen:
        return chosen + " {0}"
    return chosen


def actions_shell(script, cwd, env, shell):
    """Run a workflow step with the shell that step actually selects."""
    with tempfile.TemporaryDirectory(prefix="parkio-u08-step-") as directory:
        script_path = Path(directory) / "step.sh"
        script_path.write_text(script)
        command = shlex.split(shell.replace("{0}", str(script_path)))
        return subprocess.run(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True)


def effective_compose_project(args, env_project):
    """Project Compose would use: the last -p/--project-name, else the process env.

    A CLI name overrides COMPOSE_PROJECT_NAME. Equivalent flags that name the
    run-specific project stay valid.
    """
    project = env_project
    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ("-p", "--project-name") and index + 1 < len(args):
            project = args[index + 1]
            index += 2
            continue
        if arg.startswith("--project-name="):
            project = arg.split("=", 1)[1]
        elif arg.startswith("-p") and arg != "-p" and not arg.startswith("--"):
            project = arg[2:]
        index += 1
    return project


def pipeline_step(restore):
    matches = [s for s in restore["steps"]
               if s.get("name") == "Run verification pipeline (restore + semantic [+ journeys])"]
    if len(matches) != 1:
        raise AssertionError("restore pipeline step missing")
    return matches[0]


def teardown_step(restore):
    matches = [s for s in restore["steps"] if s.get("name") == "Tear down databases"]
    if len(matches) != 1:
        raise AssertionError("teardown step missing")
    return matches[0]


class StagingRestoreAcceptanceTest(unittest.TestCase):
    def setUp(self):
        self.jobs = workflow()["jobs"]
        self.assertIn("restore-acceptance", self.jobs,
                      "no executed-job assertion: a requested restore that was skipped would leave a green run")
        self.acceptance = self.jobs["restore-acceptance"]
        self.step = self.acceptance["steps"][0]

    def run_check(self, requested, result):
        env = dict(os.environ, RESTORE_REQUESTED=requested, RESTORE_RESULT=result)
        env.pop("GITHUB_STEP_SUMMARY", None)
        return subprocess.run(["bash", "-e", "-c", self.step["run"]], env=env, stdin=subprocess.DEVNULL,
                              capture_output=True, text=True)

    def test_requested_restore_that_was_skipped_fails(self):
        result = self.run_check("true", "skipped")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("finished as 'skipped'", result.stdout)

    def test_requested_restore_that_was_cancelled_or_failed_fails(self):
        for outcome in ("cancelled", "failure", ""):
            with self.subTest(outcome=outcome):
                result = self.run_check("true", outcome)
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertIn("::error::Restore was requested", result.stdout)

    def test_requested_restore_that_succeeded_passes(self):
        result = self.run_check("true", "success")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("executed and succeeded", result.stdout)

    def test_unrequested_run_skips_the_acceptance_job(self):
        # GitHub skips the job when this condition is false. The check must not succeed.
        self.assertEqual(self.acceptance["if"], ACCEPTANCE_IF)
        self.assertIn("always()", self.acceptance["if"])
        self.assertIn("inputs.run_restore", self.acceptance["if"])
        self.assertNotEqual(self.acceptance["if"], "always()")
        self.assertNotIn("success()", self.acceptance["if"])

    def test_false_request_is_not_reported_as_executed_success(self):
        # Defense in depth if the step runs with a false env value. It must not
        # claim that a restore executed. Check-level distinction is the job skip above.
        for outcome in ("skipped", "success"):
            with self.subTest(outcome=outcome):
                result = self.run_check("false", outcome)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("structural-only run. It is not restore evidence.", result.stdout)
                self.assertNotIn("executed and succeeded", result.stdout)

    def test_unknown_request_state_fails_closed(self):
        for requested in ("", "TRUE", "yes"):
            with self.subTest(requested=requested):
                result = self.run_check(requested, "success")
                self.assertNotEqual(result.returncode, 0, result.stdout)

    def test_wiring_reads_the_request_and_the_restore_result(self):
        self.assertEqual(self.acceptance["needs"], "restore-evidence")
        self.assertEqual(self.acceptance["if"], ACCEPTANCE_IF)
        self.assertEqual(self.acceptance["permissions"], {})
        self.assertNotIn("continue-on-error", self.acceptance)
        self.assertNotIn("continue-on-error", self.step)
        self.assertEqual(len(self.acceptance["steps"]), 1)
        self.assertNotIn("uses", self.step)
        self.assertEqual(self.step["env"]["RESTORE_REQUESTED"], REQUEST)
        self.assertNotIn("needs.", self.step["env"]["RESTORE_REQUESTED"])
        self.assertEqual(self.step["env"]["RESTORE_RESULT"], "${{ needs.restore-evidence.result }}")

    def test_restore_job_isolation_and_cleanup_unchanged(self):
        restore = self.jobs["restore-evidence"]
        self.assertEqual(restore["if"], RESTORE_IF)
        self.assertEqual(restore["env"]["PARKIO_ENVIRONMENT_TYPE"], "CI_EPHEMERAL")
        self.assertEqual(restore["env"]["PARKIO_STAGING_ISOLATION_MARKER"], "wp062-ci-${{ github.run_id }}")
        self.assertEqual(restore["env"]["COMPOSE_PROJECT_NAME"], "parkio-wp062-${{ github.run_id }}")
        teardown = teardown_step(restore)
        self.assertEqual(teardown["if"], "always()")
        self.assertNotIn("prune", teardown["run"])
        self.assertEqual(restore["env"]["PARKIO_STAGING_RUN_RESTORE"], "yes")
        self.assertEqual(restore["env"]["PARKIO_STAGING_RUN_MINIO"], "yes")
        self.assertEqual(restore["env"]["PARKIO_STAGING_ALLOW_DESTRUCTIVE"], "yes")
        self.assertNotIn("continue-on-error", restore)
        pipeline = pipeline_step(restore)
        self.assertNotIn("if", pipeline)
        self.assertNotIn("continue-on-error", pipeline)
        document = workflow()
        self.assertEqual(effective_shell(document, restore, pipeline), "bash -e {0}")
        self.assertEqual(effective_shell(document, restore, teardown), "bash -e {0}")
        self.assertNotIn("|| true", pipeline["run"])
        self.assertIn("./scripts/staging/run-verification-pipeline.sh", pipeline["run"])
        upload = [s for s in restore["steps"] if s.get("name") == "Upload operational evidence"]
        self.assertEqual(len(upload), 1)
        self.assertEqual(upload[0]["if"], "always()")
        self.assertEqual(upload[0]["uses"], "actions/upload-artifact@v4")
        self.assertEqual(upload[0]["with"]["name"], "wp062-operational-evidence-${{ github.run_id }}")
        self.assertEqual(upload[0]["with"]["path"], "build/operational-evidence/")
        # Upload ignores missing files. Job success is not artifact validation.
        self.assertEqual(upload[0]["with"]["if-no-files-found"], "ignore")

    def test_restore_pipeline_runs_and_propagates_failure(self):
        restore = self.jobs["restore-evidence"]
        pipeline = pipeline_step(restore)
        succeeded = self._run_pipeline(restore, pipeline, exit_code=0)
        self.assertEqual(succeeded.returncode, 0, succeeded.stderr)
        failed = self._run_pipeline(restore, pipeline, exit_code=1)
        self.assertNotEqual(failed.returncode, 0, failed.stdout + failed.stderr)

    def test_project_scoped_volume_cleanup_on_both_paths(self):
        restore = self.jobs["restore-evidence"]
        teardown = teardown_step(restore)
        project = restore["env"]["COMPOSE_PROJECT_NAME"]
        primary = self._run_cleanup(restore, teardown, project, fail=set())
        self.assertEqual(primary.returncode, 0, primary.stderr)
        self._assert_volume_cleanup(primary.calls, project, expected=1)
        fallback = self._run_cleanup(restore, teardown, project, fail={1})
        self.assertEqual(fallback.returncode, 0, fallback.stderr)
        self._assert_volume_cleanup(fallback.calls, project, expected=2)
        both_fail = self._run_cleanup(restore, teardown, project, fail={1, 2})
        self.assertNotEqual(both_fail.returncode, 0, both_fail.stdout + both_fail.stderr)

    def _run_pipeline(self, job, step, exit_code):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in ("scripts/staging/lib/helper.sh", "scripts/tool.sh"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("#!/bin/sh\n")
            pipeline = root / "scripts/staging/run-verification-pipeline.sh"
            pipeline.write_text("#!/bin/sh\necho ran\nexit \"$PIPELINE_EXIT\"\n")
            env = self._step_env(job, step, {"PIPELINE_EXIT": str(exit_code)})
            return actions_shell(step["run"], root, env, effective_shell(workflow(), job, step))

    def _run_cleanup(self, job, step, project, fail):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / "calls"
            stub = root / "docker"
            fail_list = " ".join(str(n) for n in sorted(fail))
            stub.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$COMPOSE_PROJECT_NAME\" >> \"$CLEANUP_CALLS\"\n"
                "printf '%s\\n' \"$*\" >> \"$CLEANUP_CALLS\"\n"
                "n=$(grep -c . \"$CLEANUP_CALLS\")\n"
                "n=$((n / 2))\n"
                "case \" $CLEANUP_FAIL \" in\n"
                "  *\" $n\"*) exit 1 ;;\n"
                "esac\n"
                "exit 0\n"
            )
            stub.chmod(0o755)
            env = self._step_env(job, step, {
                "PATH": f"{root}{os.pathsep}{os.environ.get('PATH', '')}",
                "COMPOSE_PROJECT_NAME": project,
                "CLEANUP_CALLS": str(calls),
                "CLEANUP_FAIL": fail_list,
            })
            # Step env is applied before the harness keys, then the run-specific
            # project is restored unless the step itself overrides it.
            step_project = (step.get("env") or {}).get("COMPOSE_PROJECT_NAME")
            env["COMPOSE_PROJECT_NAME"] = step_project if step_project is not None else project
            result = actions_shell(step["run"], root, env, effective_shell(workflow(), job, step))
            result.calls = calls.read_text().splitlines() if calls.exists() else []
            return result

    def _step_env(self, job, step, extra):
        env = dict(os.environ)
        for source in (job.get("env") or {}, step.get("env") or {}):
            env.update(source)
        env.update(extra)
        return env

    def _assert_volume_cleanup(self, lines, project, expected):
        self.assertEqual(len(lines), expected * 2)
        for index in range(expected):
            env_project = lines[index * 2]
            args = lines[index * 2 + 1].split()
            self.assertEqual(effective_compose_project(args, env_project), project)
            self.assertIn("compose", args)
            self.assertIn("down", args)
            self.assertIn("-v", args)
            self.assertNotIn("prune", args)


if __name__ == "__main__":
    unittest.main()
