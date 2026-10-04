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
   base branch and the changed files, by the same `branches`/`paths`/`paths-ignore` rules GitHub
   uses to decide whether to run it. `scripts/ci/ci_gate.py` reads those filters from the
   workflow files themselves, so the gate cannot drift from them.
   - It implements `*`, `**` and `!` patterns, which are all the workflows use.
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

## Not covered

- **Push events.** The gate judges pull requests into `api` and `master`; branch protection
  applies there.
- **Making it required.** Adding *CI gate* to the required checks is a separate owner/admin action.
  Until then it reports.
- **Checks from other apps.** The *CodeQL* and *Trivy* code-scanning results are not workflow jobs.
  The gate judges the workflow jobs that produce them.
