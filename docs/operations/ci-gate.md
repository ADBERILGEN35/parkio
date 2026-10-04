# CI gate

The **CI gate** check (`.github/workflows/ci-gate.yml`, U09 / CX-F09) is green only when every
workflow that a pull request's changes trigger has run, passed and shown that its tests ran.
Branch protection on `api` requires only *Build & unit tests* and *Secret scan*, so on its own it
does not notice a failed frontend, integration or container-scan job, a job that was skipped, or a
job that ran zero tests. The gate does.

## How it decides

1. **Changed files.** The files that differ from the merge-base with the base branch, as in the
   Frontend CI scope guard.
2. **Required workflows.** A workflow is required when its `pull_request` trigger matches the
   base branch and the changed files, by GitHub's own `branches`/`paths`/`paths-ignore` rules,
   for the pattern syntax below. `scripts/ci/ci_gate.py` reads those filters from the
   workflow files themselves, so the gate cannot drift from them.
   - It implements `*` (within one directory), `**` (across directories; `**/` also matches no
     directory, so `services/**/Dockerfile` covers `services/Dockerfile`) and leading `!`. That is
     all the workflows use.
   - Other glob syntax is refused, by a unit test and at run time.
3. **Required jobs.** Every job of a required workflow. A matrix job matches by its name pattern,
   for example every `Container scan (…)`.
4. **Waiting.** It polls the workflow runs of the pull request's head commit, for up to 80 minutes.
5. **Judging.** The gate fails when:
   - a required workflow has no run;
   - a required job is missing, failed, cancelled or timed out;
   - a required job was skipped and `.github/ci-gate-policy.json` allows no skip for it;
   - a job's test evidence is missing or below its minimum.

   Jobs with `continue-on-error: true` are advisory: they are reported, not judged.

## The policy file

`.github/ci-gate-policy.json` says only what the YAML cannot. Any entry that names a workflow or job
that no longer exists fails the gate as stale.
- `allowed_skips`: jobs that skip on pull requests by design, each with its reason. Examples are
  the invite-production dispatch-only jobs, the scheduled staging restore, and CodeQL while
  `CODEQL_ENABLED` is off.
- `evidence`: a log pattern whose numbers are summed, and a minimum. Today:
  - Backend unit tests: `Unit test evidence: N tests`, printed by `scripts/ci/junit_summary.py`
    after `./gradlew build`.
  - Integration tests: `Integration test evidence: N tests`, printed the same way.
  - Frontend vitest: `Tests N passed`.
  - Mobile-v2 jest: `Tests: N passed`.
- `ignore_workflows`: the gate itself.

When a workflow gains a dispatch- or schedule-only job, add an allowed skip with its reason. When
a test step changes its summary line, update its evidence pattern. The gate's unit tests check
that the repository's policy matches the repository's workflows.

## Running it locally

```bash
python3 -m unittest scripts/ci/test_ci_gate.py scripts/ci/test_junit_summary.py
# Judge an existing pull request's runs (uses the gh CLI login; no waiting):
gh api repos/ADBERILGEN35/parkio/pulls/<n>/files --jq '.[].filename' > /tmp/files.txt
python3 scripts/ci/ci_gate.py --repository ADBERILGEN35/parkio --head-sha <head sha> \
  --base-ref api --timeout-minutes 0 --changed-file-list /tmp/files.txt
```

## Before it becomes a required check (owner decision)

**The pull request controls the gate.** The gate runs on `pull_request`, from the pull request's
merge commit. A pull request can therefore weaken it in three ways: an `allowed_skips` entry, a
change to `scripts/ci/ci_gate.py`, or a workflow that drops its evidence step. That is the same
trust level as every other pull-request workflow (the gate deliberately avoids
`pull_request_target`). Before *CI gate* is made required, the owner should choose one of:
- CODEOWNERS entries for `.github/ci-gate-policy.json`, `scripts/ci/` and `.github/workflows/`,
  with code-owner review required in branch protection;
- or running the base branch's script and policy, with the pull request's workflow files read
  only as data.

## Limitations

- **Evidence is per job.** A pattern is summed over the job's whole log. Frontend CI sums every
  package's vitest run, so one package dropping to zero tests is not noticed while the others still
  run tests. The backend sums its modules the same way.
- **Cached test results count.** The unit-test evidence counts the JUnit XML that Gradle restores
  for a test task taken from its build cache. Integration tests are never cached (the reuse guard
  in the integration workflow).
- **Very large pull requests.** When a diff exceeds the 300 files that GitHub's path filters
  read, a workflow can be skipped that the gate still requires. The gate then fails after waiting,
  which fails closed.
- **Reusable workflows.** A job that calls a reusable workflow is reported as missing, failing
  closed. None exist today.
- **Re-runs.** Re-running a failed workflow does not re-trigger the gate. Re-run *CI gate* by hand
  afterwards.

## Not covered

- **Push events.** The gate judges pull requests into `api` and `master`; branch protection
  applies there.
- **Making it required.** Adding *CI gate* to the required checks is a separate owner/admin action.
  Until then it reports.
- **Checks from other apps.** The *CodeQL* and *Trivy* code-scanning results are not workflow jobs.
  The gate judges the workflow jobs that produce them.
