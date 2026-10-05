#!/usr/bin/env bash
# U09: unit tests for the slack-biz Compose integration check, without Docker or a network.
#   1. validate-compose-integration.sh's files_args/render: a missing or empty production file list
#      stops the check instead of rendering the extra -f files alone. The functions are taken from the
#      script itself; a fake docker records the arguments it receives.
#   2. select-compose-base.sh in throw-away git repositories: a pull request into api, a run on api
#      itself, a branch run, and an api→master merge whose tree equals api's.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="${COMPOSE_CHECK_SCRIPT:-$HERE/validate-compose-integration.sh}"
TMP="$(mktemp -d)"
trap 'rm -rf "${TMP:?}"' EXIT
fails=0
pass() { echo "PASS $*"; }
bad() { echo "FAIL $*"; fails=$((fails + 1)); }

echo "=== files_args / render ==="
sed -n '/^files_args() {/,/^}/p; /^render() {/,/^}/p' "$SCRIPT" >"$TMP/functions.sh"
grep -q '^render() {' "$TMP/functions.sh" || { echo "FAIL cannot extract render() from $SCRIPT"; exit 1; }
mkdir -p "$TMP/bin"
# render runs Compose under `env -i`, so the fake has its output path built in.
cat >"$TMP/bin/docker" <<SH
#!/usr/bin/env bash
printf '%s\\n' "\$@" >"$TMP/args"
echo '{}'
SH
chmod +x "$TMP/bin/docker"
run_render() { # ROOT -> exit code of render ROOT env out -f overlay.yml
  rm -f "$TMP/args"
  ( export PATH="$TMP/bin:$PATH"
    bash -c 'set -euo pipefail; source "$1"; render "$2" /dev/null "$3" -f overlay.yml' _ \
      "$TMP/functions.sh" "$1" "$TMP/out.json" ) 2>"$TMP/err"
}
mkdir -p "$TMP/listed/docker" "$TMP/missing/docker" "$TMP/empty/docker"
printf '# production files\ndocker/a.yml\r\n\ndocker/b.yml\n' >"$TMP/listed/docker/compose.production.files"
printf '# nothing listed\n\n' >"$TMP/empty/docker/compose.production.files"

if run_render "$TMP/listed" && [ "$(tr '\n' ' ' <"$TMP/args")" = "compose --env-file /dev/null -f $TMP/listed/docker/a.yml -f $TMP/listed/docker/b.yml -f overlay.yml config --format json " ]; then
  pass "a listed file set renders with every listed file, in order, before the extra -f"
else
  bad "a listed file set renders with every listed file, in order: $(tr '\n' ' ' <"$TMP/args" 2>/dev/null)"
fi
rc=0; run_render "$TMP/missing" || rc=$?
if [ "$rc" -ne 0 ] && [ ! -e "$TMP/args" ] && grep -q 'missing compose file list' "$TMP/err"; then
  pass "a missing compose.production.files stops the check before Compose runs (exit $rc)"
else
  bad "a missing compose.production.files stops the check before Compose runs (exit $rc, compose called: $([ -e "$TMP/args" ] && tr '\n' ' ' <"$TMP/args" || echo no))"
fi
rc=0; run_render "$TMP/empty" || rc=$?
if [ "$rc" -ne 0 ] && [ ! -e "$TMP/args" ] && grep -q 'lists no production compose files' "$TMP/err"; then
  pass "an empty file list stops the check before Compose runs (exit $rc)"
else
  bad "an empty file list stops the check before Compose runs (exit $rc)"
fi

echo "=== select-compose-base.sh ==="
SELECT="$HERE/select-compose-base.sh"
repo="$TMP/repo"
git init -q -b master "$repo"
g() { git -C "$repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false "$@"; }
commit() { echo "$1" >"$repo/f.txt"; g add f.txt; g commit -q -m "$1"; g rev-parse HEAD; }
A="$(commit a)"
g checkout -q -b api
B="$(commit b)"
g checkout -q -b feature
C="$(commit c)"
g checkout -q api
g merge -q --no-ff --no-edit feature
M="$(g rev-parse HEAD)"     # a pull request's merge commit: first parent B (api's tip before the merge)
g reset -q --hard "$B"      # api is still at B; M is only the checkout of the PR
select_base() { # REV -> chosen base on stdout, rc
  g checkout -q --detach "$1"
  ( cd "$repo" && bash "$SELECT" api ) 2>"$TMP/select.err"
}
expect_base() { # NAME REV EXPECTED
  local got rc=0
  got="$(select_base "$2")" || rc=$?
  if [ "$rc" -eq 0 ] && [ "$got" = "$3" ]; then pass "$1 → $(cut -c1-8 <<<"$3")"; else bad "$1: expected $(cut -c1-8 <<<"$3"), got '${got:-}' (exit $rc) $(cat "$TMP/select.err")"; fi
}
expect_base "a pull request into api compares with api's tip" "$M" "$B"
expect_base "a run on api itself compares with the previous api commit" "$B" "$A"
expect_base "a run on a branch compares with its merge-base with api" "$C" "$B"
# An api→master merge whose tree equals api's: no independent base, so the check fails closed.
g checkout -q master
g merge -q --no-ff --no-edit api
U="$(g rev-parse HEAD)"
rc=0; select_base "$U" >/dev/null || rc=$?
if [ "$rc" -ne 0 ] && grep -q 'same tree as the candidate' "$TMP/select.err"; then
  pass "an api→master merge with api's tree fails closed (exit $rc)"
else
  bad "an api→master merge with api's tree fails closed (exit $rc)"
fi
# The previous selection, for the record: base_ref empty or master → origin/api, which on api itself
# is the candidate (U09 defect 1).
if [ "$(g rev-parse "api^{tree}")" = "$(g rev-parse "$B^{tree}")" ]; then
  pass "(before) the old rule picks api for a run on api: the candidate's own tree"
fi

if [ "$fails" -ne 0 ]; then
  echo "compose check tests: $fails FAILED"
  exit 1
fi
echo "compose check tests: PASS"
