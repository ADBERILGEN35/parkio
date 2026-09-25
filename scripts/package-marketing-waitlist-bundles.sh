#!/usr/bin/env bash
# Package Hostinger marketing zips from a fixed source tree.
#   staged  — signup unavailable (no mock success); confirm/unsubscribe stay api
#   launch  — signup api; ?waitlistMock=1 cannot enable fake success
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="${1:?usage: $0 <staged|launch> [outdir]}"
OUTDIR="${2:-$ROOT/dist/marketing-bundles}"
SRC="$ROOT/web/marketing"
SHA="$(git -C "$ROOT" rev-parse HEAD)"
mkdir -p "$OUTDIR"
STAGE="$OUTDIR/work-$MODE-$$"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -a "$SRC/." "$STAGE/"

# Never ship adapter unit tests or local-only notes as required runtime.
rm -f "$STAGE/waitlist.adapter.test.mjs"

case "$MODE" in
  staged)
    python3 - <<'PY' "$STAGE/index.html"
import re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8").read()
html, n = re.subn(
    r'(<meta name="parkio-waitlist-mode" content=")[^"]*(")',
    r'\1unavailable\2',
    html,
    count=1,
)
if n != 1:
    raise SystemExit("failed to set staged waitlist mode=unavailable")
open(path, "w", encoding="utf-8").write(html)
PY
    ;;
  launch)
    python3 - <<'PY' "$STAGE/index.html"
import re, sys
path = sys.argv[1]
html = open(path, encoding="utf-8").read()
html, n = re.subn(
    r'(<meta name="parkio-waitlist-mode" content=")[^"]*(")',
    r'\1api\2',
    html,
    count=1,
)
if n != 1:
    raise SystemExit("failed to set launch waitlist mode=api")
open(path, "w", encoding="utf-8").write(html)
PY
    ;;
  *)
    echo "unknown mode: $MODE" >&2
    exit 2
    ;;
esac

# Confirm/unsubscribe must remain api in both bundles.
for page in "$STAGE/waitlist/confirm/index.html" "$STAGE/waitlist/unsubscribe/index.html"; do
  grep -q 'parkio-waitlist-mode" content="api"' "$page"
done

# Production safety: waitlist.js must ignore query mock when meta is api/unavailable.
grep -q 'Production/api bundles must never honor' "$STAGE/waitlist.js"

ZIP="$OUTDIR/parkio-marketing-${MODE}-${SHA:0:12}.zip"
rm -f "$ZIP"
(
  cd "$STAGE"
  if command -v zip >/dev/null 2>&1; then
    zip -qr "$ZIP" .
  else
    tar -a -c -f "$ZIP" .
  fi
)

HASH=""
if command -v sha256sum >/dev/null 2>&1; then
  HASH="$(sha256sum "$ZIP" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  HASH="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
else
  HASH="$(python3 -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$ZIP")"
fi

MANIFEST="$OUTDIR/parkio-marketing-${MODE}-${SHA:0:12}.MANIFEST.txt"
{
  echo "mode=$MODE"
  echo "source_sha=$SHA"
  echo "zip=$ZIP"
  echo "zip_sha256=$HASH"
  echo "landing_meta=$(grep -o 'parkio-waitlist-mode" content="[^"]*"' "$STAGE/index.html" | head -1)"
  echo "confirm_meta=api"
  echo "unsubscribe_meta=api"
  echo "query_mock_bypass=disabled_for_api_and_unavailable"
} > "$MANIFEST"

rm -rf "$STAGE"
echo "PACKAGED $MODE -> $ZIP"
echo "MANIFEST $MANIFEST"
cat "$MANIFEST"
