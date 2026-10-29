#!/usr/bin/env bash
# Real cosign positive acceptance (G-07 / #489): every *-service image digest-pinned in the canonical
# production file set (docker/compose.production.files) must verify under the issuer, identity and
# predicate type in scripts/lib/cosign-pin.env, as the deploy gate will. Needs registry read access.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=scripts/lib/cosign-pin.env
source scripts/lib/cosign-pin.env
command -v cosign >/dev/null 2>&1 || { echo "::error::cosign missing" >&2; exit 2; }

# Later files override earlier ones, as Compose merges them. Every *-service image the merge ends
# with must be a digest pin; anything else (or an image line this parser cannot read) fails here.
refs="$(python3 - <<'PY'
import re
import sys
from pathlib import Path
files = [line.strip() for line in Path("docker/compose.production.files").read_text().splitlines()
         if line.strip() and not line.lstrip().startswith("#")]
final = {}
for name in files:
    top = service = None
    for line in Path(name).read_text().splitlines():
        if re.match(r"^[A-Za-z]", line):
            top, service = line.split(":", 1)[0], None
            continue
        m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(?:#.*)?$", line)
        if m:
            service = m.group(1)
            continue
        m = re.match(r"^    image:\s*(.*?)\s*$", line)
        if m and top == "services" and service and service.endswith("-service"):
            final[service] = re.sub(r"\s+#.*$", "", m.group(1)).strip().strip("'\"")
bad = {s: v for s, v in final.items() if not re.fullmatch(r"\S+@sha256:[0-9a-f]{64}", v)}
for service, value in sorted(bad.items()):
    print(f"::error::{service}: the production file set does not end with a digest pin: {value}", file=sys.stderr)
if bad:
    sys.exit(1)
for service in sorted(final):
    print(final[service])
PY
)" || exit 1
[ -n "$refs" ] || { echo "::error::no digest-pinned *-service image in the production file set" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "identity=$PARKIO_PROVENANCE_IDENTITY_REGEXP"
fail=0
count=0
while IFS= read -r ref; do
  count=$((count + 1))
  if cosign verify-attestation --type "$PARKIO_PROVENANCE_PREDICATE_TYPE" \
      --certificate-oidc-issuer "$PARKIO_PROVENANCE_OIDC_ISSUER" \
      --certificate-identity-regexp "$PARKIO_PROVENANCE_IDENTITY_REGEXP" \
      "$ref" < /dev/null > "$WORK/out" 2> "$WORK/err"; then
    subject="$(sed -n 's/^Certificate subject: //p' "$WORK/err" | sort -u | paste -sd ' ' -)"
    echo "PASS $ref (subject: ${subject:-not printed})"
  else
    echo "::error::pinned image does not verify under the pinned identity: $ref" >&2
    tail -5 "$WORK/err" >&2
    fail=1
  fi
done <<< "$refs"
[ "$fail" -eq 0 ] || exit 1
echo "PASS: $count pinned *-service images verify under the pinned identity"
