#!/usr/bin/env bash
# Candidate web image evidence for CL-F30 and CX-F11 (owner decision 2026-10-05, option C).
#
# Builds the web image the way .github/workflows/release.yml builds it, runs the acceptance suites
# against that image, and records what was built and tested. Nothing is pushed or published.
#   - Build: frontend/apps/web/Dockerfile for linux/amd64 with `docker buildx build --load
#     --provenance=false`, using release.yml's web build:
#       * the same bake contract check on docker/web-hosted-beta.release-bake.env;
#       * the same VITE_* values and fallbacks;
#       * VERIFY_REQUIRE_PUBLIC_EXPLORE=true and VERIFY_REQUIRE_MUNICIPAL=<the bake's municipal
#         value>, so the Dockerfile gates the compiled bundle;
#       * IMAGE_VERSION, IMAGE_REVISION and IMAGE_CREATED.
#     The remaining differences from a release build, also listed in provenance.json:
#       * VITE_MAPTILER_KEY is synthetic, so no secret is needed and nothing real is baked. Only its
#         12-hex SHA-256 fingerprint is recorded. The MapTiler style and tiles never load (the suites
#         abort third-party hosts anyway), so the style's credits (MapTiler, OpenStreetMap) are not
#         shown or measured here. The attribution control and MapLibre's credit are.
#       * IMAGE_VERSION is candidate-<sha12> instead of the v* tag, and IMAGE_CREATED is this
#         build's time.
#       * The build calls `docker buildx build` itself; release calls it through
#         docker/build-push-action. In CI both use a builder from the same pinned
#         docker/setup-buildx-action. Elsewhere the current builder is used. Either way it is recorded.
#       * The image is loaded into the local daemon only and never pushed.
#   - Run: the image on 127.0.0.1 only, with the CSP origins of the production model
#     (api.parkio.dev, media.parkio.dev). The container must run exactly the built image id. The
#     suites mock the API and abort every other host.
#   - Suites, each a gate:
#       a11y-web          CL-F30: axe-core (WCAG 2.0/2.1/2.2 A and AA) and the keyboard walk on the
#                         populated pages, in Turkish and English (playwright.a11y.config.ts).
#       cxf11-acceptance  CX-F11: the exact-candidate scenarios, all passing; CL-F20 (option A) turned
#                         its 4 known defects into plain tests (playwright.candidate.config.ts).
#     The other e2e specs assume the dev server (same-origin API, default flags), so they are not run
#     against the image; Frontend CI runs them on the dev server.
#   - Record: OUT/provenance.json holds:
#       * source: commit, parents, pull request head and base, and the ids of the web image's inputs;
#       * build inputs, builder and tool versions;
#       * the image identity and the serving container's image id;
#       * suite results and run provenance.
#     OUT/SUMMARY.md says the same for people. OUT/SHA256SUMS, written last, covers every other
#     file in OUT except hidden ones, which the artifact upload leaves out too.
# The container and the image are removed at the end unless KEEP_IMAGE=1.
#
#   scripts/web-candidate-evidence.sh [OUT_DIR]   (default: ./web-candidate-evidence; absent or empty)
#
# Exit status: 0 when every suite passed, 1 when a suite failed, 2 on a bake, build or start failure,
# 3 when the checksum manifest could not be written.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ARG="${1:-$ROOT/web-candidate-evidence}"
if [ -d "$OUT_ARG" ] && [ -n "$(ls -A "$OUT_ARG")" ]; then
  echo "FAIL: $OUT_ARG is not empty; the evidence and its SHA256SUMS need a fresh directory" >&2
  exit 2
fi
OUT="$(mkdir -p "$OUT_ARG" && cd "$OUT_ARG" && pwd)"
WEB="$ROOT/frontend/apps/web"
BIN="$ROOT/frontend/node_modules/.bin"
BAKE="docker/web-hosted-beta.release-bake.env"
DOCKERFILE="frontend/apps/web/Dockerfile"
PORT="${PARKIO_CANDIDATE_PORT:-18086}"
URL="http://127.0.0.1:$PORT"
SYNTHETIC_MAP_KEY="candidateEvidenceSyntheticMapKey00"
SHA="$(git -C "$ROOT" rev-parse HEAD)"
TAG="parkio-web-candidate-evidence:${SHA:0:12}-$$"
NAME="parkio-web-candidate-evidence-$$"
STARTED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
mkdir -p "$OUT/reports"
: >"$OUT/suites.tsv"

cleanup() {
  local rc=$?
  docker logs "$NAME" >"$OUT/container.log" 2>&1 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  if [ "${KEEP_IMAGE:-0}" != "1" ]; then docker image rm "$TAG" >/dev/null 2>&1 || true; fi
  # Hidden files (Playwright's .last-run.json) stay out, as actions/upload-artifact leaves them out.
  if ! (cd "$OUT" && find . -type f ! -name SHA256SUMS ! -path '*/.*' -print0 | sort -z | xargs -0 -r sha256sum >SHA256SUMS); then
    echo "FAIL: could not write $OUT/SHA256SUMS" >&2
    [ "$rc" -ne 0 ] || rc=3
  fi
  exit "$rc"
}
trap cleanup EXIT

# release.yml's "Load hosted-beta web release bake profile" step: it sources the bake file and checks
# it against the hosted-beta live contract. This reads the bake the same way, in a clean subshell.
WEB_APP_ENV="${WEB_APP_ENV:-hosted-beta}"
WEB_API_BASE_URL="${WEB_API_BASE_URL:-https://api.parkio.dev/api/v1}"
declare -A bake=()
while IFS='=' read -r key value; do
  bake["$key"]="$value"
done < <(env -i PATH="$PATH" bash -c 'set -a; . "$1"; set +a; for k in $(compgen -v VITE_); do printf "%s=%s\n" "$k" "${!k}"; done' bake "$ROOT/$BAKE")
municipal="${bake[VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED]:-}"
case "$municipal" in
  true|false) ;;
  *)
    echo "FAIL: $BAKE VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED must be true or false (release.yml refuses it too)" >&2
    exit 2
    ;;
esac
if [ "$WEB_APP_ENV" = "hosted-beta" ]; then
  if [ "${bake[VITE_APP_ENV]:-}" != "hosted-beta" ] || [ "${bake[VITE_API_BASE_URL]:-}" != "$WEB_API_BASE_URL" ] \
     || [ "${bake[VITE_PUBLIC_EXPLORE_ENABLED]:-}" != "true" ] || [ "$municipal" != "true" ]; then
    echo "FAIL: $BAKE must match the hosted-beta live contract (app env, API base, Explore ON, municipal ON), as release.yml requires" >&2
    exit 2
  fi
fi

# release.yml's "Build image" build arguments for web, with the same fallbacks. Only the MapTiler key
# and IMAGE_VERSION differ (see the header).
IMAGE_VERSION="candidate-${SHA:0:12}"
IMAGE_CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
args=(
  "IMAGE_VERSION=$IMAGE_VERSION"
  "IMAGE_REVISION=$SHA"
  "IMAGE_CREATED=$IMAGE_CREATED"
  "VITE_API_BASE_URL=${bake[VITE_API_BASE_URL]:-$WEB_API_BASE_URL}"
  "VITE_APP_ENV=${bake[VITE_APP_ENV]:-$WEB_APP_ENV}"
  "VITE_MAPTILER_KEY=$SYNTHETIC_MAP_KEY"
  "VITE_PUBLIC_EXPLORE_ENABLED=${bake[VITE_PUBLIC_EXPLORE_ENABLED]:-false}"
  "VITE_WEB_MUNICIPAL_DISCOVERY_ENABLED=$municipal"
  "VITE_SMART_PARKING_ASSISTANT_ENABLED=${bake[VITE_SMART_PARKING_ASSISTANT_ENABLED]:-false}"
  "VITE_SMART_RETURN_ENABLED=${bake[VITE_SMART_RETURN_ENABLED]:-true}"
  "VITE_WAITLIST_INTAKE_MODE=${bake[VITE_WAITLIST_INTAKE_MODE]:-api}"
  "VITE_MAPTILER_STYLE=${bake[VITE_MAPTILER_STYLE]:-streets-v2}"
  "VITE_FRONTEND_ERROR_REPORTING=${bake[VITE_FRONTEND_ERROR_REPORTING]:-disabled}"
  "VERIFY_REQUIRE_PUBLIC_EXPLORE=true"
  "VERIFY_REQUIRE_MUNICIPAL=$municipal"
)
build_args=()
for arg in "${args[@]}"; do build_args+=(--build-arg "$arg"); done
printf '%s\n' "${args[@]}" | grep -v '^VITE_MAPTILER_KEY=' >"$OUT/build-args.txt"

echo "building $TAG from $SHA as release.yml builds web ($BAKE)"
if ! (cd "$ROOT" && docker buildx build --platform linux/amd64 --provenance=false --load --progress=plain \
      -f "$DOCKERFILE" "${build_args[@]}" --iidfile "$OUT/iidfile" -t "$TAG" .) >"$OUT/build.log" 2>&1; then
  tail -40 "$OUT/build.log" >&2
  echo "FAIL: the candidate image did not build; a failed bundle gate also stops the build (see build.log)" >&2
  exit 2
fi
docker image inspect "$TAG" >"$OUT/image-inspect.json"
docker buildx inspect >"$OUT/builder.txt" 2>&1 || true

docker run -d --pull never --name "$NAME" --label parkio.task=web-candidate-evidence -p "127.0.0.1:$PORT:80" \
  -e PARKIO_DOMAIN=api.parkio.dev -e PARKIO_MEDIA_DOMAIN=media.parkio.dev "$TAG" >/dev/null
built_id="$(docker image inspect -f '{{.Id}}' "$TAG")"
served_id="$(docker inspect -f '{{.Image}}' "$NAME")"
printf '%s\n' "$served_id" >"$OUT/container-image-id.txt"
if [ "$served_id" != "$built_id" ]; then
  echo "FAIL: the container runs $served_id, not the built image $built_id" >&2
  exit 2
fi
ready=no
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "$URL/login"; then ready=yes; break; fi
  sleep 1
done
if [ "$ready" != yes ]; then
  docker logs "$NAME" >&2 || true
  echo "FAIL: the candidate image did not serve /login on $URL" >&2
  exit 2
fi
curl -sS -D - -o /dev/null "$URL/login" | grep -i -E '^(HTTP|content-security-policy)' >"$OUT/headers.txt" || true

suite() { # suite NAME ASSERT_ARGS... -- PLAYWRIGHT_ARGS...
  local name="$1" rc=0 assert=() args=()
  shift
  while [ "$1" != "--" ]; do assert+=("$1"); shift; done
  shift
  args=("$@")
  echo "running $name against $URL"
  (cd "$WEB" && PLAYWRIGHT_JSON_OUTPUT_NAME="$OUT/reports/$name.json" \
     "$BIN/playwright" test "${args[@]}" --retries=0 --reporter=list,json --output="$OUT/$name-output") \
    >"$OUT/$name.log" 2>&1 || rc=$?
  if [ "$rc" -eq 0 ]; then
    (cd "$WEB" && node scripts/assert-playwright-report.mjs "$OUT/reports/$name.json" "${assert[@]}") \
      >>"$OUT/$name.log" 2>&1 || rc=$?
  fi
  printf '%s\t%s\n' "$name" "$rc" >>"$OUT/suites.tsv"
  tail -3 "$OUT/$name.log"
}

rm -rf "${WEB:?}/test-results/a11y"
A11Y_WEB_URL="$URL" suite a11y-web --project a11y-web --min 40 -- \
  -c playwright.a11y.config.ts --project a11y-web
cp -r "$WEB/test-results/a11y" "$OUT/a11y-pages" 2>/dev/null || true
CXF11_WEB_URL="$URL" suite cxf11-acceptance --project cxf11-acceptance --min 21 -- \
  -c playwright.candidate.config.ts --project cxf11-acceptance

python3 - "$OUT" "$ROOT" "$SHA" "$TAG" "$BAKE" "$DOCKERFILE" "$SYNTHETIC_MAP_KEY" "$STARTED" \
  "$IMAGE_VERSION" "$IMAGE_CREATED" "$municipal" "$BIN" <<'PY'
import hashlib, json, os, re, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

out, root, sha, tag, bake, dockerfile, key, started, image_version, image_created, municipal, bin_dir = sys.argv[1:13]
out, root = Path(out), Path(root)

def sha256(path):
    return hashlib.sha256((root / path).read_bytes()).hexdigest()

def git(*args):
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True).stdout.strip()

def tool(*args, cwd=None):
    """A tool's version output, or None when the tool is not available."""
    try:
        done = subprocess.run(list(args), capture_output=True, text=True, timeout=120, cwd=cwd)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if done.returncode != 0:
        return None
    return done.stdout.strip() or None

def blob(path):
    return git("rev-parse", f"HEAD:{path}") or None

image = json.loads((out / "image-inspect.json").read_text())[0]
labels = image.get("Config", {}).get("Labels") or {}
served = (out / "container-image-id.txt").read_text().strip()
iidfile = out / "iidfile"
build_log = (out / "build.log").read_text(errors="replace")
frontend = re.search(r"docker-image://docker\.io/docker/dockerfile:1@(sha256:[0-9a-f]{64})", build_log)
apk_upgrades = sorted({f"{m[0]} {m[1]} -> {m[2]}" for m in re.findall(r"Upgrading (\S+) \((\S+) -> (\S+)\)", build_log)})
builder = {}
for line in (out / "builder.txt").read_text(errors="replace").splitlines():
    field, _, value = line.partition(":")
    if field.strip() in ("Name", "Driver", "BuildKit version") and value.strip():
        builder.setdefault(field.strip(), value.strip())
web_dir = root / "frontend" / "apps" / "web"
browsers = tool(f"{bin_dir}/playwright", "install", "--dry-run", "chromium", cwd=web_dir) or ""
chromium = re.search(r"^(?:Chrome for Testing|Chromium|browser: chromium version) ?([0-9][0-9.]*)", browsers, re.M)
package_manager = json.loads((root / "frontend" / "package.json").read_text()).get("packageManager")

# The merge commit's parents come from the commit object, which a shallow checkout still has.
header = git("cat-file", "-p", "HEAD").split("\n\n", 1)[0]
parents = [line.split()[1] for line in header.splitlines() if line.startswith("parent ")]
env = os.environ
pr_head = env.get("PARKIO_EVIDENCE_PR_HEAD_SHA") or None
pr_base = env.get("PARKIO_EVIDENCE_PR_BASE_SHA") or None

suites = []
for line in (out / "suites.tsv").read_text().splitlines():
    name, rc = line.split("\t")
    counts = {"expected": 0, "unexpected": 0, "flaky": 0, "skipped": 0}
    report = out / "reports" / f"{name}.json"
    if report.exists():
        stats = json.loads(report.read_text()).get("stats", {})
        counts = {k: stats.get(k, 0) for k in counts}
    suites.append({"name": name, "exit": int(rc), "passed": int(rc) == 0, **counts,
                   "report": f"reports/{name}.json", "log": f"{name}.log"})

differences = [
    "VITE_MAPTILER_KEY is synthetic (fingerprint in build.maptiler_key); release bakes the WEB_MAPTILER_KEY "
    "secret. The MapTiler style and tiles do not load, so the style's credits (MapTiler, OpenStreetMap) are "
    "not measured; the attribution control and MapLibre's credit are.",
    f"IMAGE_VERSION is {image_version}; release uses the v* tag. IMAGE_CREATED is this build's time.",
    "Built by `docker buildx build` directly; release runs it through docker/build-push-action with the same "
    "builder setup (docker/setup-buildx-action in CI).",
    "Loaded into the local daemon only and never pushed.",
]
server = env.get("GITHUB_SERVER_URL", "")
repo = env.get("GITHUB_REPOSITORY", "")
run_id = env.get("GITHUB_RUN_ID", "")
record = {
    "schema": "parkio.web-candidate-evidence/v2",
    "source": {
        "sha": sha,
        "tree": git("rev-parse", "HEAD^{tree}"),
        "parents": parents,
        "clean": git("status", "--porcelain", "--untracked-files=no") == "",
        "ref": env.get("GITHUB_REF", git("rev-parse", "--abbrev-ref", "HEAD")),
        "event": env.get("GITHUB_EVENT_NAME", "local"),
        # event_base is the base branch tip in the event; merge_parent is the base the merge commit was
        # actually made on, which can be newer.
        "pull_request": {"head": pr_head, "event_base": pr_base,
                         "merge_parent": parents[0] if len(parents) == 2 else None,
                         "head_is_parent": pr_head in parents if pr_head else None} if (pr_head or pr_base) else None,
        # Everything the image is built from: the Dockerfile copies only from frontend/, the build
        # context filter is the root .dockerignore, and the arguments come from the bake file and
        # release.yml. A v* tag is covered by this evidence when all four ids are equal at the tag
        # (git rev-parse <tag>:<path>); see docs/releases/HOSTED-BETA-WEB-BAKE.md.
        "web_inputs": {
            "frontend_tree": blob("frontend"),
            "bake_blob": blob(bake),
            "dockerignore_blob": blob(".dockerignore"),
            "release_workflow_blob": blob(".github/workflows/release.yml"),
        },
    },
    "build": {
        "as": ".github/workflows/release.yml, job images, service web",
        "dockerfile": {"path": dockerfile, "sha256": sha256(dockerfile),
                       "frontend": frontend.group(1) if frontend else None},
        "bake": {"path": bake, "sha256": sha256(bake)},
        "lockfile": {"path": "frontend/pnpm-lock.yaml", "sha256": sha256("frontend/pnpm-lock.yaml")},
        "args_file": "build-args.txt",
        "bundle_gates": {"VERIFY_REQUIRE_PUBLIC_EXPLORE": "true", "VERIFY_REQUIRE_MUNICIPAL": municipal},
        "image_version": image_version,
        "image_created": image_created,
        "maptiler_key": {"kind": "synthetic", "sha256_12": hashlib.sha256(key.encode()).hexdigest()[:12]},
        "base_images": [line.split()[1] for line in (root / dockerfile).read_text().splitlines()
                        if line.startswith("FROM ")],
        "apk_upgrades": apk_upgrades,
        "platform": "linux/amd64",
        "builder": {"name": builder.get("Name"), "driver": builder.get("Driver"),
                    "buildkit": builder.get("BuildKit version"), "buildx": tool("docker", "buildx", "version")},
        "differences_from_release": differences,
    },
    "tools": {
        "docker_client": tool("docker", "version", "--format", "{{.Client.Version}}"),
        "docker_server": tool("docker", "version", "--format", "{{.Server.Version}}"),
        "node": tool("node", "--version"),
        "pnpm": tool("pnpm", "--version"),
        "pnpm_declared": package_manager,
        "playwright": tool(f"{bin_dir}/playwright", "--version", cwd=web_dir),
        "chromium": chromium.group(1) if chromium else None,
    },
    "image": {
        "tag": tag,
        "id": image["Id"],
        "iidfile": iidfile.read_text().strip() if iidfile.exists() else None,
        # A containerd image store lists a local content digest here even though nothing is pushed.
        "local_repo_digests": image.get("RepoDigests") or [],
        "published": False,
        "created": image.get("Created"),
        "os": image.get("Os"),
        "architecture": image.get("Architecture"),
        "size": image.get("Size"),
        "revision_label": labels.get("org.opencontainers.image.revision"),
        "version_label": labels.get("org.opencontainers.image.version"),
        "created_label": labels.get("org.opencontainers.image.created"),
        "rootfs_layers": (image.get("RootFS") or {}).get("Layers") or [],
    },
    "served_by": {"container_image_id": served, "is_built_image": served == image["Id"]},
    "suites": suites,
    "run": {
        "url": f"{server}/{repo}/actions/runs/{run_id}" if run_id else None,
        "id": run_id or None,
        "attempt": env.get("GITHUB_RUN_ATTEMPT"),
        "workflow": env.get("GITHUB_WORKFLOW"),
        "job": env.get("GITHUB_JOB"),
        "runner": f'{env.get("RUNNER_OS", "")} {env.get("RUNNER_ARCH", "")}'.strip() or None,
        "started": started,
        "finished": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    },
}
(out / "provenance.json").write_text(json.dumps(record, indent=2) + "\n")
src = record["source"]
pr = src["pull_request"]
lines = [
    "# Candidate web image evidence",
    "",
    f"- Source: `{sha}` (tree `{src['tree']}`, parents {', '.join(f'`{p[:12]}`' for p in parents) or 'none'}, "
    f"clean: {src['clean']}, {src['event']})" + (f"; pull request head `{(pr['head'] or '')[:12]}`, merged onto "
                                                  f"`{(pr['merge_parent'] or '')[:12]}` (event base `{(pr['event_base'] or '')[:12]}`)" if pr else ""),
    f"- Build: as release.yml builds web: `{dockerfile}` with `{bake}` (sha256 `{record['build']['bake']['sha256'][:12]}`), "
    f"bundle gates VERIFY_REQUIRE_PUBLIC_EXPLORE=true and VERIFY_REQUIRE_MUNICIPAL={municipal}, "
    f"builder {builder.get('Driver') or 'unknown'} (BuildKit {builder.get('BuildKit version') or 'unknown'})",
    f"- Image: `{image['Id']}` ({image.get('Os')}/{image.get('Architecture')}); not published; "
    f"served by the container: {'yes' if served == image['Id'] else 'NO'}",
    f"- Run: {record['run']['url'] or 'local'}",
    "- Differences from a release build:",
    *[f"  - {d}" for d in differences],
    "- Checksums: `SHA256SUMS` covers every other file of this evidence except hidden ones, which the artifact leaves out.",
    "",
    "| Suite | Result | Expected | Unexpected | Flaky | Skipped |",
    "|---|---|---|---|---|---|",
]
for s in suites:
    lines.append(f"| {s['name']} | {'PASS' if s['passed'] else 'FAIL'} (exit {s['exit']}) | {s['expected']} | {s['unexpected']} | {s['flaky']} | {s['skipped']} |")
(out / "SUMMARY.md").write_text("\n".join(lines) + "\n")
print("\n".join(lines))
PY

if awk -F'\t' '$2 != 0 { bad = 1 } END { exit bad ? 0 : 1 }' "$OUT/suites.tsv"; then
  echo "FAIL: a suite failed against the candidate image; see $OUT" >&2
  exit 1
fi
echo "candidate evidence: PASS ($OUT)"
