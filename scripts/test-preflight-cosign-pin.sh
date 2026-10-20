#!/usr/bin/env bash
# B-02: preflight refuses missing/mismatched cosign.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/cosign-pin.env
source scripts/lib/cosign-pin.env
FN=$(mktemp)
trap 'rm -f "$FN"' EXIT
sed -n '/^parkio_preflight_require_cosign()/,/^}/p' scripts/preflight-hosted-beta.sh >"$FN"
# shellcheck disable=SC1090
source "$FN"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP" "$FN"' EXIT
mkdir -p "$TMP/bin"
SYS_PATH="/usr/bin:/bin"

echo "== missing cosign =="
set +e
PATH="$TMP/bin" PARKIO_ROOT="$ROOT" parkio_preflight_require_cosign >/tmp/cos-miss.txt 2>&1
ec=$?
set -e
test "$ec" -ne 0
grep -q "cosign not on PATH" /tmp/cos-miss.txt
echo "PASS: missing cosign refused"

echo "== wrong version =="
cat >"$TMP/bin/cosign" <<'C'
#!/usr/bin/env bash
echo "GitVersion:    v0.0.0-wrong"
exit 0
C
chmod +x "$TMP/bin/cosign"
set +e
PATH="$TMP/bin:$SYS_PATH" PARKIO_ROOT="$ROOT" parkio_preflight_require_cosign >/tmp/cos-bad.txt 2>&1
ec=$?
set -e
test "$ec" -ne 0
grep -q "version mismatch" /tmp/cos-bad.txt
echo "PASS: wrong cosign version refused"

echo "== matching version stub =="
cat >"$TMP/bin/cosign" <<C
#!/usr/bin/env bash
echo "GitVersion:    ${PARKIO_COSIGN_VERSION}"
exit 0
C
chmod +x "$TMP/bin/cosign"
PATH="$TMP/bin:$SYS_PATH" PARKIO_ROOT="$ROOT" parkio_preflight_require_cosign
echo "PASS: matching cosign version accepted"
echo "All cosign preflight pin checks passed."
