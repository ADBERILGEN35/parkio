#!/usr/bin/env bash
# Render the effective Civo production Compose file set with a synthetic env and
# assert gateway waitlist mappings (secret values never printed).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SYNTH="${TMPDIR:-/tmp}/parkio-w01i-waitlist-synth-$$.env"
trap 'rm -f "$SYNTH"' EXIT

# Start from azure-hosted-beta example (Civo wrapper default profile family),
# then force waitlist cutover values with synthetic secrets.
cp docker/.env.azure-hosted-beta.example "$SYNTH"

# Ensure required placeholders are non-empty for compose interpolation without
# using production secrets. Values are synthetic and discarded.
python3 - <<'PY' "$SYNTH"
import re, sys
path = sys.argv[1]
text = open(path, encoding="utf-8").read()
replacements = {
    "REPLACE_ME_ops_email": "ops-synth@example.test",
    "REPLACE_ME_pkcs8_pem_with_newline_escapes": "SYNTH_PEM",
    "REPLACE_ME_gateway_secret_min_32_chars": "synth_gateway_internal_secret_32chars_xx",
    "REPLACE_ME_waitlist_hmac_secret_min_32_chars": "synth_waitlist_hash_secret_32chars_xxxx",
    "REPLACE_ME_waitlist_resend_api_key": "re_synth_waitlist_key_not_real",
    "REPLACE_ME_resend_api_key": "re_synth_shared_key_not_real",
    "REPLACE_ME_expo_access_token": "synth_expo",
    "REPLACE_ME_auth_db_password": "synth_auth_db",
    "REPLACE_ME_gateway_db_password": "synth_gateway_db",
    "REPLACE_ME_user_db_password": "synth_user_db",
    "REPLACE_ME_parking_db_password": "synth_parking_db",
    "REPLACE_ME_media_db_password": "synth_media_db",
    "REPLACE_ME_gamification_db_password": "synth_gamification_db",
    "REPLACE_ME_notification_db_password": "synth_notification_db",
    "REPLACE_ME_moderation_db_password": "synth_moderation_db",
    "REPLACE_ME_analytics_db_password": "synth_analytics_db",
    "REPLACE_ME_aivalidation_db_password": "synth_aivalidation_db",
}
for old, new in replacements.items():
    text = text.replace(old, new)
# Fill remaining REPLACE_ME_* with synth tokens so compose can render.
text = re.sub(r'REPLACE_ME_[A-Za-z0-9_]+', 'SYNTH_PLACEHOLDER', text)
open(path, "w", encoding="utf-8").write(text)
PY

FILES=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  FILES+=(-f "$line")
done < "$ROOT/docker/compose.production.files"

# Shell environment overrides --env-file for Compose interpolation. Clear any
# leftover local waitlist vars so synthetic acceptance is deterministic.
unset PARKIO_WAITLIST_HASH_SECRET PARKIO_WAITLIST_ADMISSIONS_ENABLED \
  PARKIO_WAITLIST_EMAIL_PROVIDER PARKIO_WAITLIST_ALLOW_LOGGING_PROVIDER \
  PARKIO_WAITLIST_EMAIL_FROM PARKIO_WAITLIST_EMAIL_REPLY_TO \
  PARKIO_WAITLIST_CONFIRM_URL PARKIO_WAITLIST_WITHDRAW_URL \
  PARKIO_WAITLIST_RESEND_API_KEY PARKIO_WAITLIST_RESEND_BASE_URL \
  PARKIO_EMAIL_FROM PARKIO_EMAIL_REPLY_TO PARKIO_RESEND_API_KEY \
  PARKIO_CORS_ALLOWED_ORIGINS || true

COMPOSE_BIN=""
for candidate in docker docker.exe; do
  if command -v "$candidate" >/dev/null 2>&1 \
      && "$candidate" compose version >/dev/null 2>&1; then
    COMPOSE_BIN="$candidate"
    break
  fi
done
if [ -z "$COMPOSE_BIN" ]; then
  echo "FAIL: Docker Compose required" >&2
  exit 2
fi

# Pipe resolved model directly into the assertor — do not persist secret-bearing JSON.
# Use relative -f paths from repo root (matches scripts/parkio-prod-compose.sh).
"$COMPOSE_BIN" compose --env-file "$SYNTH" "${FILES[@]}" config --format json \
  | node "$ROOT/scripts/lib/assert-civo-waitlist-gateway-env.mjs"
