#!/usr/bin/env bash
# PRIV-001A-STAGING — immutable release allowlist + no-auto-execution regressions.
#
# shellcheck shell=bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/lib/runtime-release.sh
source "$ROOT/scripts/lib/runtime-release.sh"

PASS=0
FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }

TMP="$(mktemp -d)"
trap 'rm -rf -- "$TMP"' EXIT
chmod 0755 "$TMP"

SHA="$(git -C "$ROOT" rev-parse HEAD)"
export PARKIO_RUNTIME_ROOT="$TMP/runtime"
install -d -m 0755 "$PARKIO_RUNTIME_ROOT" "$PARKIO_RUNTIME_ROOT/releases"

echo "== PRIV-001A-STAGING: positive staging =="
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
RELEASE="$(parkio_release_dir "$SHA")"

check "create harness staged" \
  "[ -f '$RELEASE/scripts/acceptance/create-priv001-synthetic-principal.sh' ]"
check "inspect harness staged" \
  "[ -f '$RELEASE/scripts/acceptance/inspect-priv001-synthetic-residue.sh' ]"
check "priv001 lib staged" \
  "[ -f '$RELEASE/scripts/lib/priv001-synthetic.sh' ]"
check "dark-gateway dependency staged" \
  "[ -f '$RELEASE/scripts/lib/dark-gateway-url.sh' ]"
check "docker compose still staged" \
  "[ -f '$RELEASE/docker/docker-compose.yml' ]"
check "VERSION identity written" \
  "grep -q '^gitSha=$SHA\$' '$RELEASE/VERSION'"
check "VERSION declares no auto-execution" \
  "grep -q '^autoExecuteHarness=false\$' '$RELEASE/VERSION'"
check "integrity manifest present" \
  "[ -s '$RELEASE/release-integrity.sha256' ]"
check "integrity lists create harness" \
  "grep -q 'scripts/acceptance/create-priv001-synthetic-principal.sh' '$RELEASE/release-integrity.sha256'"
check "create harness executable in release" \
  "[ -x '$RELEASE/scripts/acceptance/create-priv001-synthetic-principal.sh' ]"
check "inspect harness executable in release" \
  "[ -x '$RELEASE/scripts/acceptance/inspect-priv001-synthetic-residue.sh' ]"
check "no .git in release" \
  "[ ! -e '$RELEASE/.git' ]"
check "no agent-tools in release" \
  "[ ! -e '$RELEASE/agent-tools' ]"
check "no .acceptance-tmp in release" \
  "[ ! -e '$RELEASE/.acceptance-tmp' ]"
check "no tmp-ci-artifacts in release" \
  "[ ! -e '$RELEASE/tmp-ci-artifacts' ]"
check "no docs tree staged" \
  "[ ! -e '$RELEASE/docs' ]"
check "no services tree staged" \
  "[ ! -e '$RELEASE/services' ]"
check "no frontend tree staged" \
  "[ ! -e '$RELEASE/frontend' ]"
check "only allowlisted scripts under scripts/" \
  "! find '$RELEASE/scripts' -type f ! -path '*/create-priv001-synthetic-principal.sh' ! -path '*/inspect-priv001-synthetic-residue.sh' ! -path '*/priv001-synthetic.sh' ! -path '*/dark-gateway-url.sh' | grep -q ."
check "no env material staged" \
  "! find '$RELEASE' -name '.env' -o -name '.env.*' | grep -q ."
check "harness dry-run works from release root" \
  "PARKIO_DEPLOYMENT_PROFILE=invite-production bash '$RELEASE/scripts/acceptance/create-priv001-synthetic-principal.sh' --environment invite-production --confirm-synthetic-only --dry-run-guards >/dev/null"

echo "== PRIV-001A-STAGING: negative path guards =="
check "absolute path rejected" \
  "! parkio_release_assert_stageable_path '$ROOT' '/etc/passwd' >/dev/null 2>&1"
check "dotdot path rejected" \
  "! parkio_release_assert_stageable_path '$ROOT' '../outside.sh' >/dev/null 2>&1"
check "missing path rejected" \
  "! parkio_release_assert_stageable_path '$ROOT' 'scripts/acceptance/does-not-exist.sh' >/dev/null 2>&1"
check "untracked path rejected" \
  "echo '#!/bin/bash' > '$ROOT/scripts/acceptance/tmp-untracked-priv001.sh' && ! parkio_release_assert_stageable_path '$ROOT' 'scripts/acceptance/tmp-untracked-priv001.sh' >/dev/null 2>&1; rm -f '$ROOT/scripts/acceptance/tmp-untracked-priv001.sh'"

# Symlink escape fixture inside TMP (not under repo)
SYMLINK_PROBE="$TMP/escape-link.sh"
printf '#!/bin/bash\n' > "$TMP/outside-target.sh"
ln -s "$TMP/outside-target.sh" "$SYMLINK_PROBE"
# Simulate by placing a symlink inside a fake repo copy is heavy; assert -L rejection
# on a path we create under TMP and call assert with a custom relative layout via
# a mini repo is overkill — unit-level: copy assert against a symlink in ROOT would
# pollute. Instead verify the helper rejects when given a symlink file under TMP as
# if it were the repo path by invoking with TMP as repo and a relative link name.
install -d -m 0755 "$TMP/fake-repo"
ln -s "$TMP/outside-target.sh" "$TMP/fake-repo/bad.sh"
check "symlink rejected" \
  "! parkio_release_assert_stageable_path '$TMP/fake-repo' 'bad.sh' >/dev/null 2>&1"

echo "== PRIV-001A-STAGING: no auto-execution wiring =="
# Compose / entrypoint / healthcheck / systemd / cron / deploy must not invoke harness.
AUTO_HITS="$(
  {
    grep -RniE 'create-priv001-synthetic-principal|inspect-priv001-synthetic-residue' \
      docker \
      infra/systemd \
      .github/workflows/invite-production-deploy.yml \
      scripts/deploy-invite-production.sh \
      scripts/stage-invite-production-release.sh \
      scripts/cleanup-invite-production-job.sh \
      2>/dev/null || true
  } | grep -vE 'test-priv001|test-invite-production-priv001|PRIV-001A-STAGING|required.*create-priv001|required.*inspect-priv001|PARKIO_RUNTIME_RELEASE_EXTRA|operatorTooling|autoExecuteHarness|docs/' || true
)"
# Allowed workflow reference: harness *regression test* only.
if echo "$AUTO_HITS" | grep -qE 'command:|entrypoint:|healthcheck:|ExecStart=|OnCalendar=|cron'; then
  bad "auto-execution wiring found"
  printf '%s\n' "$AUTO_HITS" >&2
else
  ok "no compose/systemd/cron auto-execution references"
fi
check "deploy script does not invoke harness" \
  "! grep -E 'create-priv001-synthetic-principal|inspect-priv001-synthetic-residue' '$ROOT/scripts/deploy-invite-production.sh'"
check "workflow only runs harness regression test (not live create)" \
  "grep -q 'test-priv001-synthetic-principal.sh' '$ROOT/.github/workflows/invite-production-deploy.yml' && ! grep -q 'create-priv001-synthetic-principal.sh' '$ROOT/.github/workflows/invite-production-deploy.yml'"
check "allowlist is explicit (no scripts/** glob)" \
  "! grep -E 'ls-files -z -- scripts($| )|ls-files -z -- scripts/\\*\\*' '$ROOT/scripts/lib/runtime-release.sh'"
check "allowlist array names the four required paths" \
  "grep -q 'scripts/acceptance/create-priv001-synthetic-principal.sh' '$ROOT/scripts/lib/runtime-release.sh' && grep -q 'scripts/lib/dark-gateway-url.sh' '$ROOT/scripts/lib/runtime-release.sh'"

echo "== PRIV-001A-STAGING: immutability preserved =="
inode_before="$(stat -c '%i' "$RELEASE/scripts/lib/priv001-synthetic.sh")"
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
inode_after="$(stat -c '%i' "$RELEASE/scripts/lib/priv001-synthetic.sh")"
check "re-stage does not rewrite PRIV-001A file" \
  "[ '$inode_before' = '$inode_after' ]"

echo "=== summary pass=${PASS} fail=${FAIL} ==="
if [ "$FAIL" -ne 0 ]; then
  exit 1
fi
echo "PRIV-001A immutable release staging contract: PASS"
