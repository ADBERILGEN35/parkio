#!/usr/bin/env bash
# B-02: real preflight-hosted-beta.sh refuses missing/mismatched cosign (exact GitVersion).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/cosign-pin.env
source scripts/lib/cosign-pin.env
PREFLIGHT="$ROOT/scripts/preflight-hosted-beta.sh"
FIXTURE="$ROOT/scripts/preflight-fixtures/valid.env"
test -f "$FIXTURE"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
SYS_PATH="/usr/bin:/bin"
OUT="$TMP/out.txt"

run_preflight() {
  # Real script path (--skip-compose); cosign stub first on PATH.
  set +e
  PATH="$TMP/bin:$SYS_PATH" "$PREFLIGHT" --env-file "$FIXTURE" --skip-compose >"$OUT" 2>&1
  echo $?
  set -e
}

echo "== missing cosign =="
rm -f "$TMP/bin/cosign"
ec="$(run_preflight)"
test "$ec" -ne 0
grep -q "cosign not on PATH" "$OUT"
echo "PASS: missing cosign refused via real preflight"

echo "== wrong version =="
cat >"$TMP/bin/cosign" <<'C'
#!/usr/bin/env bash
echo "GitVersion:    v0.0.0-wrong"
exit 0
C
chmod +x "$TMP/bin/cosign"
ec="$(run_preflight)"
test "$ec" -ne 0
grep -q "version mismatch" "$OUT"
echo "PASS: wrong version refused"

echo "== near-match suffix =="
cat >"$TMP/bin/cosign" <<C
#!/usr/bin/env bash
echo "GitVersion:    ${PARKIO_COSIGN_VERSION}-rc.1"
exit 0
C
chmod +x "$TMP/bin/cosign"
ec="$(run_preflight)"
test "$ec" -ne 0
grep -q "version mismatch" "$OUT"
# Must not accept substring of pin inside a longer GitVersion
grep -q "${PARKIO_COSIGN_VERSION}-rc.1" "$OUT" || grep -q "have: ${PARKIO_COSIGN_VERSION}-rc.1" "$OUT"
echo "PASS: near-match suffix refused"

echo "== nonzero exit while printing expected version =="
cat >"$TMP/bin/cosign" <<C
#!/usr/bin/env bash
echo "GitVersion:    ${PARKIO_COSIGN_VERSION}"
exit 3
C
chmod +x "$TMP/bin/cosign"
ec="$(run_preflight)"
test "$ec" -ne 0
grep -q "cosign version failed" "$OUT"
echo "PASS: nonzero cosign version exit refused"

echo "== matching version =="
cat >"$TMP/bin/cosign" <<C
#!/usr/bin/env bash
echo "GitVersion:    ${PARKIO_COSIGN_VERSION}"
echo "GitCommit:     deadbeef"
exit 0
C
chmod +x "$TMP/bin/cosign"
ec="$(run_preflight)"
test "$ec" -eq 0
grep -q "preflight: cosign ${PARKIO_COSIGN_VERSION} ok" "$OUT"
grep -q "PREFLIGHT: PASS" "$OUT"
echo "PASS: matching cosign version accepted via real preflight"
echo "All cosign preflight pin checks passed."
