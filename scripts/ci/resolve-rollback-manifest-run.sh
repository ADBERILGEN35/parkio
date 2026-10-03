#!/usr/bin/env bash
# Resolve the deploy run that holds a rollback manifest (U13 CL-F06).
#
# A rollback runs as a new workflow run, and actions/download-artifact only sees the current run
# unless it gets a run-id and a token. Before the rollback downloads from the run an operator
# names, this script checks that run. It must be:
#   - a run of the same workflow in this repository;
#   - on the expected branch;
#   - started by workflow_dispatch;
#   - completed successfully, with its deploy job (--deploy-job) successful too, so that a
#     build-only run, whose deploy was skipped, cannot serve as a rollback target;
#   - holding exactly one unexpired artifact with the given name.
# Anything else fails closed (exit 3).
#
#   resolve-rollback-manifest-run.sh --repo OWNER/NAME --workflow FILE.yml --branch BRANCH \
#     --deploy-job "JOB NAME" { --reference RUN_ID/ARTIFACT[@SHA256] | --run-id ID --artifact NAME }
#
# --reference is the form the rollback input takes; the deploy job's summary prints it with the
# manifest's SHA-256, which the rollback then checks (verify-rollback-manifest.sh --sha256).
# Needs GITHUB_TOKEN with actions: read. GITHUB_API_URL overrides the API base (tests).
# Prints run_id, artifact, manifest_sha256 (empty without @SHA256), head_sha and artifact_id as
# key=value lines, and appends them to GITHUB_OUTPUT when it is set.
set -euo pipefail

die() { echo "resolve-rollback-manifest-run: $*" >&2; exit 2; }
refuse() { echo "resolve-rollback-manifest-run: REFUSED: $*" >&2; exit 3; }

repo=""; workflow=""; branch=""; run_id=""; artifact=""; reference=""; deploy_job=""; manifest_sha256=""
while [ $# -gt 0 ]; do
  case "$1" in
    --deploy-job) deploy_job="${2:-}"; shift 2 || die "--deploy-job needs a value" ;;
    --reference) reference="${2:-}"; shift 2 || die "--reference needs a value" ;;
    --repo) repo="${2:-}"; shift 2 || die "--repo needs a value" ;;
    --workflow) workflow="${2:-}"; shift 2 || die "--workflow needs a value" ;;
    --branch) branch="${2:-}"; shift 2 || die "--branch needs a value" ;;
    --run-id) run_id="${2:-}"; shift 2 || die "--run-id needs a value" ;;
    --artifact) artifact="${2:-}"; shift 2 || die "--artifact needs a value" ;;
    *) die "unknown argument: $1" ;;
  esac
done

if [ -n "$reference" ]; then
  case "$reference" in
    */*) run_id="${reference%%/*}"; artifact="${reference#*/}" ;;
    *) refuse "the reference '$reference' is not RUN_ID/ARTIFACT; copy it from the deploy run's job summary" ;;
  esac
  case "$artifact" in
    *@*) manifest_sha256="${artifact#*@}"; artifact="${artifact%%@*}"
         [[ "$manifest_sha256" =~ ^[0-9a-f]{64}$ ]] || refuse "the SHA-256 in the reference is not 64 lowercase hex characters" ;;
  esac
fi
[ -n "$deploy_job" ] || die "--deploy-job is required"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "--repo must be OWNER/NAME"
[[ "$workflow" =~ ^[A-Za-z0-9_.-]+\.ya?ml$ ]] || die "--workflow must be a workflow file name"
[ -n "$branch" ] || die "--branch is required"
[[ "$run_id" =~ ^[1-9][0-9]{0,19}$ ]] || refuse "the run id '$run_id' is not a run number; pass the deploy run's id"
[[ "$artifact" =~ ^[A-Za-z0-9._-]+$ ]] || refuse "the artifact name '$artifact' is not valid"
[ -n "${GITHUB_TOKEN:-}" ] || die "GITHUB_TOKEN is required"

api="${GITHUB_API_URL:-https://api.github.com}"
get() {
  curl -fsS --max-time 30 \
    -H "Authorization: Bearer $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$api/$1"
}

run_json="$(get "repos/$repo/actions/runs/$run_id")" || refuse "run $run_id is not readable in $repo"
artifacts_json="$(get "repos/$repo/actions/runs/$run_id/artifacts?name=$artifact&per_page=100")" \
  || refuse "the artifacts of run $run_id are not readable"
jobs_json="$(get "repos/$repo/actions/runs/$run_id/jobs?filter=latest&per_page=100")" \
  || refuse "the jobs of run $run_id are not readable"

result="$(RUN_JSON="$run_json" ARTIFACTS_JSON="$artifacts_json" JOBS_JSON="$jobs_json" \
  python3 - "$repo" "$workflow" "$branch" "$artifact" "$deploy_job" <<'PY'
import json, os, sys
repo, workflow, branch, artifact, deploy_job = sys.argv[1:6]
run = json.loads(os.environ["RUN_JSON"])
problems = []
if (run.get("repository") or {}).get("full_name") != repo:
    problems.append(f"it belongs to {(run.get('repository') or {}).get('full_name')!r}, not {repo}")
if run.get("path") != f".github/workflows/{workflow}":
    problems.append(f"it is a run of {run.get('path')!r}, not .github/workflows/{workflow}")
if run.get("head_branch") != branch:
    problems.append(f"it ran on branch {run.get('head_branch')!r}, not {branch!r}")
if run.get("event") != "workflow_dispatch":
    problems.append(f"it was started by {run.get('event')!r}, not workflow_dispatch")
if run.get("status") != "completed" or run.get("conclusion") != "success":
    problems.append(f"it ended {run.get('status')}/{run.get('conclusion')}, not completed/success")
deploys = [j for j in json.loads(os.environ["JOBS_JSON"]).get("jobs", []) if j.get("name") == deploy_job]
if len(deploys) != 1 or deploys[0].get("conclusion") != "success":
    seen = ", ".join(str(j.get("conclusion")) for j in deploys) or "absent"
    problems.append(f"its {deploy_job!r} job is {seen}, not one successful job (a build-only run is no rollback target)")
matches = [a for a in json.loads(os.environ["ARTIFACTS_JSON"]).get("artifacts", [])
           if a.get("name") == artifact and not a.get("expired")]
if len(matches) != 1:
    problems.append(f"it holds {len(matches)} unexpired artifact(s) named {artifact}, not exactly one")
if problems:
    print("REFUSE " + "; ".join(problems))
else:
    print(f"OK {run['head_sha']} {matches[0]['id']}")
PY
)"

case "$result" in
  "OK "*) ;;
  "REFUSE "*) refuse "run $run_id: ${result#REFUSE }" ;;
  *) die "unexpected result: $result" ;;
esac
read -r _ head_sha artifact_id <<<"$result"
[[ "$head_sha" =~ ^[0-9a-f]{40}$ ]] || refuse "run $run_id has no valid head sha"
# The rollback downloads by this id, so the download fetches exactly the artifact checked here.
[[ "$artifact_id" =~ ^[1-9][0-9]{0,19}$ ]] || refuse "run $run_id: the artifact has no valid id"
lines="run_id=$run_id
artifact=$artifact
manifest_sha256=$manifest_sha256
head_sha=$head_sha
artifact_id=$artifact_id"
echo "$lines"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "$lines" >> "$GITHUB_OUTPUT"
fi
