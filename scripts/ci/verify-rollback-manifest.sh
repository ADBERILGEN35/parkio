#!/usr/bin/env bash
# Verify a downloaded rollback manifest before a rollback uses it (U13 CL-F06).
#
#   verify-rollback-manifest.sh --dir DIR --git-sha SHA [--profile PROFILE] [--sha256 HEX]
#
# DIR must hold exactly one deploy-*.json, and the manifest must pass these checks:
#   - schemaVersion 1;
#   - the expected deploymentProfile, when --profile is given (the rollback script checks the
#     profile against its env file either way);
#   - gitSha equal to the deploy run's head sha (from resolve-rollback-manifest-run.sh);
#   - an imageTag;
#   - an images map whose every reference ends in that tag.
# With --sha256, the file's SHA-256 must also match (the deploy run's job summary prints it).
# Anything else fails closed (exit 3).
# Prints manifest=<path> and sha256=<hex>, and appends both to GITHUB_OUTPUT when it is set.
set -euo pipefail

die() { echo "verify-rollback-manifest: $*" >&2; exit 2; }
refuse() { echo "verify-rollback-manifest: REFUSED: $*" >&2; exit 3; }

dir=""; profile=""; git_sha=""; expected_sha256=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dir) dir="${2:-}"; shift 2 || die "--dir needs a value" ;;
    --profile) profile="${2:-}"; shift 2 || die "--profile needs a value" ;;
    --git-sha) git_sha="${2:-}"; shift 2 || die "--git-sha needs a value" ;;
    --sha256) expected_sha256="${2:-}"; shift 2 || die "--sha256 needs a value" ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -d "$dir" ] || die "--dir must be a directory"
[[ "$git_sha" =~ ^[0-9a-f]{40}$ ]] || die "--git-sha must be a 40-character sha"
if [ -n "$expected_sha256" ]; then
  [[ "$expected_sha256" =~ ^[0-9a-f]{64}$ ]] || refuse "--sha256 must be 64 lowercase hex characters"
fi

mapfile -t manifests < <(find "$dir" -maxdepth 1 -type f -name 'deploy-*.json' | sort)
[ "${#manifests[@]}" -eq 1 ] || refuse "expected exactly one deploy-*.json in $dir, found ${#manifests[@]}"
manifest="${manifests[0]}"
actual_sha256="$(sha256sum "$manifest" | cut -d' ' -f1)"
if [ -n "$expected_sha256" ] && [ "$actual_sha256" != "$expected_sha256" ]; then
  refuse "$(basename "$manifest") has SHA-256 $actual_sha256, not the expected $expected_sha256"
fi

problems="$(python3 - "$manifest" "$profile" "$git_sha" <<'PY'
import json, re, sys
path, profile, git_sha = sys.argv[1:4]
try:
    m = json.load(open(path, encoding="utf-8"))
except (OSError, ValueError) as exc:
    print(f"it is not valid JSON ({exc})"); sys.exit(0)
problems = []
if not isinstance(m, dict):
    print("it is not a JSON object"); sys.exit(0)
if m.get("schemaVersion") != 1:
    problems.append(f"schemaVersion is {m.get('schemaVersion')!r}, not 1")
if profile and m.get("deploymentProfile") != profile:
    problems.append(f"deploymentProfile is {m.get('deploymentProfile')!r}, not {profile!r}")
if m.get("gitSha") != git_sha:
    problems.append(f"gitSha is {m.get('gitSha')!r}, not the deploy run's {git_sha}")
tag = m.get("imageTag")
if not isinstance(tag, str) or not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", tag):
    problems.append(f"imageTag {tag!r} is missing or not a tag")
images = m.get("images")
if not isinstance(images, dict) or not images:
    problems.append("images is missing or empty")
elif isinstance(tag, str):
    off = sorted(svc for svc, ref in images.items() if not (isinstance(ref, str) and ref.endswith(":" + tag)))
    if off:
        problems.append("images not tagged " + tag + ": " + ", ".join(off))
print("; ".join(problems))
PY
)"
[ -z "$problems" ] || refuse "$(basename "$manifest"): $problems"

echo "manifest=$manifest"
echo "sha256=$actual_sha256"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  { echo "manifest=$manifest"; echo "sha256=$actual_sha256"; } >> "$GITHUB_OUTPUT"
fi
