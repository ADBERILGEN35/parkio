#!/usr/bin/env bash
# PRIV-001A — synthetic principal allowlist + managed-auth verification guards.
#
# Operator acceptance tooling only. Not a public API. Not a global verification
# bypass. Callers must still register via POST /api/v1/auth/register and erase
# via DELETE /api/v1/account.
#
# shellcheck shell=bash
# shellcheck disable=SC2034

# Reserved non-routable acceptance domain (.invalid is IANA-reserved).
# Distinct from real-e2e.parkio.local so invite-production clean-login smoke
# continues to prove absence of hosted-beta E2E credentials.
PRIV001_SYNTHETIC_DOMAIN="priv001a.parkio.invalid"

# Local-part must be exactly: priv001a-<lowercase alnum>
# Full email: priv001a-<id>@priv001a.parkio.invalid
PRIV001_EMAIL_REGEX='^priv001a-[a-z0-9]{6,64}@priv001a\.parkio\.invalid$'

# Maximum age of a freshly registered PENDING_VERIFICATION row that may be
# verified by the acceptance harness (seconds).
PRIV001_DEFAULT_MAX_AGE_SECONDS=900

parkio_priv001_normalize_email() {
  # Force lowercase ASCII; reject if tr would alter length (non-ASCII).
  local raw="${1-}"
  local lower
  lower="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  if [ "${#raw}" -ne "${#lower}" ]; then
    return 1
  fi
  printf '%s' "$lower"
}

# parkio_priv001_email_allowed <email>
# Exit 0 only for exact synthetic acceptance emails.
parkio_priv001_email_allowed() {
  local email
  if ! email="$(parkio_priv001_normalize_email "${1-}")"; then
    return 1
  fi
  if [ -z "$email" ]; then
    return 1
  fi
  # ASCII-only guard (blocks Unicode / lookalike domains).
  case "$email" in
    *[!a-z0-9@._-]*) return 1 ;;
  esac
  # Reject credential-looking or multi-@ forms early.
  case "$email" in
    *@*@*|:*|*/*|*' '* ) return 1 ;;
  esac
  # Exact regex: no subdomain confusion (user@x.priv001a.parkio.invalid fails).
  if printf '%s' "$email" | grep -Eq "$PRIV001_EMAIL_REGEX"; then
    return 0
  fi
  return 1
}

# parkio_priv001_require_email <email>
parkio_priv001_require_email() {
  if ! parkio_priv001_email_allowed "${1-}"; then
    echo "ERROR: email is not an allowed PRIV-001A synthetic principal." >&2
    echo "       Required form: priv001a-<id>@${PRIV001_SYNTHETIC_DOMAIN}" >&2
    return 2
  fi
  return 0
}

# parkio_priv001_generate_local_part
# Prints a unique local-part (without domain).
parkio_priv001_generate_local_part() {
  local stamp rand
  stamp="$(date -u +%Y%m%d%H%M%S)"
  rand="$(openssl rand -hex 4 2>/dev/null || printf '%04x%04x' "$RANDOM" "$RANDOM")"
  printf 'priv001a-%s%s' "$stamp" "$rand"
}

# parkio_priv001_generate_password
# Prints a 24-char password to stdout (caller must not log it).
parkio_priv001_generate_password() {
  openssl rand -base64 24 2>/dev/null | tr -d '/+=' | head -c 24
  printf '\n'
}

# parkio_priv001_require_invite_production_opt_in
# Requires --environment invite-production semantics via env vars set by the CLI.
parkio_priv001_require_invite_production_opt_in() {
  local environment="${PARKIO_PRIV001_ENVIRONMENT-}"
  local confirm="${PARKIO_PRIV001_CONFIRM_SYNTHETIC_ONLY-}"

  if [ "$environment" != "invite-production" ]; then
    echo "ERROR: PRIV-001A harness requires --environment invite-production (got '${environment:-}')." >&2
    return 2
  fi
  if [ "$confirm" != "yes" ]; then
    echo "ERROR: PRIV-001A harness requires --confirm-synthetic-only." >&2
    return 2
  fi
  if [ "${PARKIO_DEPLOYMENT_PROFILE:-}" != "invite-production" ] \
      && [ "${PARKIO_PRIV001_SKIP_PROFILE_CHECK:-}" != "1" ]; then
    echo "ERROR: PARKIO_DEPLOYMENT_PROFILE must be invite-production." >&2
    return 2
  fi
  return 0
}

# parkio_priv001_auth_psql <psql args...>
# Runs psql against managed auth DB using env credentials (never echoed).
# Required env when PARKIO_PG_MODE=managed (default for invite-production):
#   PARKIO_PG_HOST, PARKIO_PG_PORT, PARKIO_PG_SSLMODE (verify-full),
#   PARKIO_PG_USER, PARKIO_PG_DB, PGPASSWORD (or PARKIO_PG_PASSWORD),
#   optional PARKIO_PG_SSLROOTCERT
parkio_priv001_auth_psql() {
  local mode="${PARKIO_PG_MODE:-managed}"
  local sslmode="${PARKIO_PG_SSLMODE:-verify-full}"
  local user="${PARKIO_PG_USER:-${POSTGRES_AUTH_USER:-parkio_auth}}"
  local db="${PARKIO_PG_DB:-${POSTGRES_AUTH_DB:-parkio_auth}}"

  if [ "$sslmode" = "disable" ]; then
    echo "ERROR: PARKIO_PG_SSLMODE=disable is not allowed." >&2
    return 2
  fi

  if [ "$mode" = "managed" ]; then
    : "${PARKIO_PG_HOST:?PARKIO_PG_HOST required when PARKIO_PG_MODE=managed}"
    local pw="${PGPASSWORD:-${PARKIO_PG_PASSWORD:-}}"
    if [ -z "$pw" ]; then
      echo "ERROR: PGPASSWORD or PARKIO_PG_PASSWORD required for managed auth access." >&2
      return 2
    fi
    PGPASSWORD="$pw" PGSSLMODE="$sslmode" PGSSLROOTCERT="${PARKIO_PG_SSLROOTCERT:-}" \
      psql -h "${PARKIO_PG_HOST}" -p "${PARKIO_PG_PORT:-5432}" \
      -U "$user" -d "$db" --set=ON_ERROR_STOP=1 "$@"
    return $?
  fi

  # Local docker fallback for CI fakes / developer stacks only.
  local container="${PARKIO_AUTH_PG_CONTAINER:-parkio-postgres-auth}"
  docker exec -i "$container" psql --set=ON_ERROR_STOP=1 -U "$user" -d "$db" "$@"
}

# parkio_priv001_mark_verified <email> [max_age_seconds]
# Fail-closed, single-row verification-state mutation for a fresh synthetic user.
# Prints the verified auth user UUID on success. Never prints tokens/hashes.
parkio_priv001_mark_verified() {
  local email_raw="${1-}"
  local max_age="${2:-$PRIV001_DEFAULT_MAX_AGE_SECONDS}"
  local email

  parkio_priv001_require_invite_production_opt_in || return $?
  if ! email="$(parkio_priv001_normalize_email "$email_raw")"; then
    echo "ERROR: invalid email encoding." >&2
    return 2
  fi
  parkio_priv001_require_email "$email" || return $?

  if ! [[ "$max_age" =~ ^[0-9]+$ ]] || [ "$max_age" -lt 60 ] || [ "$max_age" -gt 3600 ]; then
    echo "ERROR: max_age_seconds must be an integer in [60, 3600]." >&2
    return 2
  fi

  # Email is allowlist-validated above; bind as a safe SQL literal (not a psql :variable,
  # which is unreliable across psql -c flag ordering on managed hosts).
  local escaped_email
  escaped_email="$(printf '%s' "$email" | sed "s/'/''/g")"

  local sql
  sql=$(cat <<SQL
BEGIN;
UPDATE auth_users
SET email_verified = TRUE,
    email_verified_at = now(),
    email_verification_token_hash = NULL,
    email_verification_expires_at = NULL,
    email_verification_sent_at = NULL,
    status = 'ACTIVE',
    version = version + 1,
    updated_at = now()
WHERE id = (
  SELECT id
  FROM auth_users
  WHERE email = '${escaped_email}'
    AND email_verified = FALSE
    AND status = 'PENDING_VERIFICATION'
    AND created_at > (now() - (${max_age} || ' seconds')::interval)
    AND NOT EXISTS (
          SELECT 1 FROM erased_user_tombstones t WHERE t.auth_user_id = auth_users.id
        )
    AND NOT EXISTS (
          SELECT 1 FROM erasure_requests e WHERE e.auth_user_id = auth_users.id
        )
  FOR UPDATE
)
RETURNING id::text;
COMMIT;
SQL
)

  local out
  if ! out="$(parkio_priv001_auth_psql -t -A -c "$sql" 2>&1)"; then
    echo "ERROR: synthetic verification mutation failed (fail-closed)." >&2
    # Strip any accidental secret-looking material from error echo.
    printf '%s\n' "$out" | sed -E 's/(password|token|hash|secret)=[^ ]+/\1=***REDACTED***/gi' >&2
    return 1
  fi

  local uuid
  uuid="$(printf '%s\n' "$out" | grep -E '^[0-9a-f-]{36}$' | tail -1)"
  if [ -z "$uuid" ]; then
    echo "ERROR: verification did not return exactly one auth user id." >&2
    return 1
  fi
  printf '%s\n' "$uuid"
  return 0
}

# parkio_priv001_secret_scan_text <file>
# Fails if common secret patterns appear in harness evidence.
parkio_priv001_secret_scan_text() {
  local f="${1-}"
  if [ ! -f "$f" ]; then
    echo "ERROR: secret scan target missing: $f" >&2
    return 2
  fi
  if grep -Ei \
      'hooks\.slack\.com|xox[bp]-|Bearer [A-Za-z0-9._-]{20,}|eyJ[A-Za-z0-9_-]{8,}\.|access_token=|refresh_token=|password=[^[:space:]]+|postgres(ql)?://[^:]+:[^@]+@|PGPASSWORD=|password_hash=|email_verification_token' \
      "$f" >/dev/null 2>&1; then
    echo "ERROR: secret/token-like material found in evidence file." >&2
    return 1
  fi
  return 0
}

# parkio_priv001_json_loads_stdin
# Validates stdin is JSON. Prefers python3, falls back to node.
parkio_priv001_json_loads_stdin() {
  if command -v python3 >/dev/null 2>&1 \
      && python3 -c 'import json' >/dev/null 2>&1; then
    python3 -c 'import json,sys; json.loads(sys.stdin.read())'
    return $?
  fi
  if command -v node >/dev/null 2>&1; then
    node -e 'JSON.parse(require("fs").readFileSync(0,"utf8"))'
    return $?
  fi
  echo "ERROR: python3 or node required for JSON validation." >&2
  return 2
}

# parkio_priv001_json_get <file> <dotted.path>
# Safe dotted getter (e.g. user.id, accessToken). Empty string when missing.
parkio_priv001_json_get() {
  local file="$1"
  local path="$2"
  if command -v python3 >/dev/null 2>&1 \
      && python3 -c 'import json' >/dev/null 2>&1; then
    python3 -c 'import json,sys
p=json.load(open(sys.argv[1],encoding="utf-8"))
cur=p
for part in sys.argv[2].split("."):
  if not isinstance(cur, dict) or part not in cur:
    print(""); break
  cur=cur[part]
else:
  print("" if cur is None else cur)
' "$file" "$path"
    return $?
  fi
  if command -v node >/dev/null 2>&1; then
    node -e 'const p=JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"));
let cur=p; for (const part of process.argv[2].split(".")) {
  if (cur==null || typeof cur!=="object" || !(part in cur)) { process.stdout.write(""); process.exit(0); }
  cur=cur[part];
}
process.stdout.write(cur==null?"":String(cur));' "$file" "$path"
    return $?
  fi
  echo "ERROR: python3 or node required for JSON query." >&2
  return 2
}

# parkio_priv001_write_evidence_json <file> <acceptance_id> <auth_user_id> <fixture_matrix>
parkio_priv001_write_evidence_json() {
  local path="$1"
  local acceptance_id="$2"
  local auth_user_id="$3"
  local matrix="$4"
  if command -v python3 >/dev/null 2>&1 \
      && python3 -c 'import json' >/dev/null 2>&1; then
    python3 -c 'import json,sys
path, acceptance_id, auth_user_id, matrix = sys.argv[1:5]
payload = {
  "package": "PRIV-001A",
  "purpose": "synthetic-principal-create",
  "acceptanceId": acceptance_id,
  "authUserId": auth_user_id,
  "syntheticDomain": "priv001a.parkio.invalid",
  "registration": "POST /api/v1/auth/register",
  "verification": "allowlisted-managed-auth-sql",
  "login": "POST /api/v1/auth/login",
  "erasure": "canonical DELETE /api/v1/account (later package)",
  "fixtureMatrix": matrix,
  "secretsInEvidence": False,
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(payload, f, indent=2)
    f.write("\n")
' "$path" "$acceptance_id" "$auth_user_id" "$matrix"
    return $?
  fi
  if command -v node >/dev/null 2>&1; then
    node -e 'const fs=require("fs");
const [path, acceptanceId, authUserId, matrix]=process.argv.slice(1);
const payload={
  package:"PRIV-001A",
  purpose:"synthetic-principal-create",
  acceptanceId, authUserId,
  syntheticDomain:"priv001a.parkio.invalid",
  registration:"POST /api/v1/auth/register",
  verification:"allowlisted-managed-auth-sql",
  login:"POST /api/v1/auth/login",
  erasure:"canonical DELETE /api/v1/account (later package)",
  fixtureMatrix:matrix,
  secretsInEvidence:false,
};
fs.writeFileSync(path, JSON.stringify(payload,null,2)+"\n");' \
      "$path" "$acceptance_id" "$auth_user_id" "$matrix"
    return $?
  fi
  echo "ERROR: python3 or node required to write evidence JSON." >&2
  return 2
}

# Allow `bash scripts/lib/priv001-synthetic.sh email-allowed <email>` for tests.
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  cmd="${1-}"
  shift || true
  case "$cmd" in
    email-allowed)
      parkio_priv001_email_allowed "${1-}"
      exit $?
      ;;
    require-opt-in)
      parkio_priv001_require_invite_production_opt_in
      exit $?
      ;;
    *)
      echo "usage: $0 email-allowed <email> | require-opt-in" >&2
      exit 2
      ;;
  esac
fi
