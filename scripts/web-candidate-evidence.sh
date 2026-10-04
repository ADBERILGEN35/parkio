#!/usr/bin/env bash
# Candidate web image evidence for CL-F30 and CX-F11 (owner decision 2026-10-05, option C).
#
# Builds the web image the release builds, runs the acceptance suites against that image, and records
# what was built and tested. Nothing is pushed or published.
#   - Build: frontend/apps/web/Dockerfile with the VITE_* values of
#     docker/web-hosted-beta.release-bake.env. The one difference from a release build is the MapTiler
#     key, which is synthetic, so no secret is needed and nothing real is baked. Its 12-hex SHA-256
#     fingerprint is recorded, never the value.
#   - Run: the image on 127.0.0.1 only, with the CSP origins of the production model
#     (api.parkio.dev, media.parkio.dev). The suites mock the API and abort every other host.
#   - Suites, each a gate:
#       a11y-web          CL-F30: axe-core (WCAG 2.0/2.1/2.2 A and AA) and the keyboard walk on the
#                         populated pages, in Turkish and English (playwright.a11y.config.ts).
#       cxf11-acceptance  CX-F11: the exact-candidate scenarios, with the 4 known CL-F20 defects
#                         declared (playwright.candidate.config.ts).
#     The other e2e specs assume the dev server (same-origin API, default flags), so they are not run
#     against the image; Frontend CI runs them on the dev server.
#   - Record: OUT/provenance.json holds source, build inputs, image identity, suite results and run
#     provenance, and OUT/SUMMARY.md the same for people. Reports and logs are kept next to them.
# The container and the image are removed at the end unless KEEP_IMAGE=1.
#
#   scripts/web-candidate-evidence.sh [OUT_DIR]      (default: ./web-candidate-evidence)
#
# Exit status: 0 when every suite passed, 1 when a suite failed, 2 on a build or start failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mkdir -p "${1:-$ROOT/web-candidate-evidence}" && cd "${1:-$ROOT/web-candidate-evidence}" && pwd)"
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
  docker logs "$NAME" >"$OUT/container.log" 2>&1 || true
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  if [ "${KEEP_IMAGE:-0}" != "1" ]; then docker image rm "$TAG" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# Build arguments: every VITE_* value of the release bake, the synthetic key and the revision.
build_args=()
while IFS='=' read -r key value; do
  case "$key" in VITE_*) build_args+=(--build-arg "$key=$value") ;; esac
done < <(grep -v -E '^\s*(#|$)' "$ROOT/$BAKE")
build_args+=(--build-arg "VITE_MAPTILER_KEY=$SYNTHETIC_MAP_KEY" --build-arg "IMAGE_REVISION=$SHA")
printf '%s\n' "${build_args[@]}" | grep -v -i 'maptiler_key' >"$OUT/build-args.txt"

echo "building $TAG from $SHA with $BAKE"
if ! (cd "$ROOT" && docker build -f "$DOCKERFILE" "${build_args[@]}" \
      --label parkio.task=web-candidate-evidence -t "$TAG" .) >"$OUT/build.log" 2>&1; then
  tail -40 "$OUT/build.log" >&2
  echo "FAIL: the candidate image did not build" >&2
  exit 2
fi
docker image inspect "$TAG" >"$OUT/image-inspect.json"

docker run -d --name "$NAME" --label parkio.task=web-candidate-evidence -p "127.0.0.1:$PORT:80" \
  -e PARKIO_DOMAIN=api.parkio.dev -e PARKIO_MEDIA_DOMAIN=media.parkio.dev "$TAG" >/dev/null
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

rm -rf "$WEB/test-results/a11y"
A11Y_WEB_URL="$URL" suite a11y-web --project a11y-web --min 40 -- \
  -c playwright.a11y.config.ts --project a11y-web
cp -r "$WEB/test-results/a11y" "$OUT/a11y-pages" 2>/dev/null || true
CXF11_WEB_URL="$URL" suite cxf11-acceptance --project cxf11-acceptance --min 13 --known-defects 4 -- \
  -c playwright.candidate.config.ts --project cxf11-acceptance

python3 - "$OUT" "$ROOT" "$SHA" "$TAG" "$BAKE" "$DOCKERFILE" "$SYNTHETIC_MAP_KEY" "$STARTED" <<'PY'
import hashlib, json, os, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

out, root, sha, tag, bake, dockerfile, key, started = sys.argv[1:9]
out, root = Path(out), Path(root)

def sha256(path):
    return hashlib.sha256((root / path).read_bytes()).hexdigest()

def git(*args):
    return subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True).stdout.strip()

image = json.loads((out / "image-inspect.json").read_text())[0]
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

env = os.environ
server = env.get("GITHUB_SERVER_URL", "")
repo = env.get("GITHUB_REPOSITORY", "")
run_id = env.get("GITHUB_RUN_ID", "")
record = {
    "schema": "parkio.web-candidate-evidence/v1",
    "source": {
        "sha": sha,
        "tree": git("rev-parse", "HEAD^{tree}"),
        "clean": git("status", "--porcelain", "--untracked-files=no") == "",
        "ref": env.get("GITHUB_REF", git("rev-parse", "--abbrev-ref", "HEAD")),
        "event": env.get("GITHUB_EVENT_NAME", "local"),
    },
    "build": {
        "dockerfile": {"path": dockerfile, "sha256": sha256(dockerfile)},
        "bake": {"path": bake, "sha256": sha256(bake)},
        "args_file": "build-args.txt",
        "maptiler_key": {"kind": "synthetic", "sha256_12": hashlib.sha256(key.encode()).hexdigest()[:12]},
        "base_images": [line.split()[1] for line in (root / dockerfile).read_text().splitlines()
                        if line.startswith("FROM ")],
    },
    "image": {
        "tag": tag,
        "id": image["Id"],
        "repo_digests": image.get("RepoDigests") or [],
        "published": False,
        "created": image.get("Created"),
        "os": image.get("Os"),
        "architecture": image.get("Architecture"),
        "size": image.get("Size"),
        "revision_label": (image.get("Config", {}).get("Labels") or {}).get("org.opencontainers.image.revision"),
    },
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
lines = [
    "# Candidate web image evidence",
    "",
    f"- Source: `{sha}` (tree `{record['source']['tree']}`, clean: {record['source']['clean']}, {record['source']['event']})",
    f"- Build: `{dockerfile}` with `{bake}` (sha256 `{record['build']['bake']['sha256'][:12]}`); MapTiler key synthetic (`{record['build']['maptiler_key']['sha256_12']}`)",
    f"- Image: `{image['Id']}` ({image.get('Os')}/{image.get('Architecture')}); not published",
    f"- Run: {record['run']['url'] or 'local'}",
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
