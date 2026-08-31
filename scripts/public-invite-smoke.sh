#!/usr/bin/env bash
# Future public invite-production smoke harness (PROD-DEPLOY-01B source only).
# NOT executed against production in 01B-01. Requires explicit opt-in flags.
#
# Usage (future 01B-03+):
#   PARKIO_PUBLIC_SMOKE_CONFIRM=1 \
#   PARKIO_PUBLIC_SMOKE_BASE_URL=https://api.parkio.dev \
#   ./scripts/public-invite-smoke.sh
#
set -euo pipefail

if [ "${PARKIO_PUBLIC_SMOKE_CONFIRM:-}" != "1" ]; then
  echo "ERROR: public smoke requires PARKIO_PUBLIC_SMOKE_CONFIRM=1 (source-only harness)" >&2
  exit 2
fi

BASE_URL="${PARKIO_PUBLIC_SMOKE_BASE_URL:-}"
if [ -z "$BASE_URL" ]; then
  echo "ERROR: set PARKIO_PUBLIC_SMOKE_BASE_URL" >&2
  exit 2
fi

echo "public_invite_smoke=SKIPPED_SOURCE_ONLY"
echo "note=Harness present; execution deferred to authorized cutover package."
