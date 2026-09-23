#!/usr/bin/env bash
# Secret-free precedence and duplicate-mount checks for the auth
# registration overlay. Does not read live env or print secrets.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FILES="$ROOT/docker/compose.production.files"
OVERLAY="$ROOT/docker/docker-compose.auth-registration-env.yml"
PIN="$ROOT/docker/docker-compose.auth-release-pin.yml"
INBOX="$ROOT/docker/docker-compose.waitlist-ops-inbox.yml"

fail() { echo "FAIL $1"; exit 1; }
pass() { echo "PASS $1"; }

grep -q '^docker/docker-compose.auth-registration-env.yml$' "$FILES" || fail "overlay listed in compose.production.files"
! grep -q '^docker/docker-compose.waitlist-ops-inbox.yml$' "$FILES" || fail "waitlist inbox must stay activation-only"

python3 - "$FILES" "$OVERLAY" "$PIN" "$INBOX" <<'PY'
import sys
from pathlib import Path
files, overlay, pin, inbox = map(Path, sys.argv[1:5])
lines = [ln.strip() for ln in files.read_text().splitlines() if ln.strip() and not ln.strip().startswith("#")]
try:
    i_overlay = lines.index("docker/docker-compose.auth-registration-env.yml")
    i_pin = lines.index("docker/docker-compose.auth-release-pin.yml")
except ValueError as exc:
    raise SystemExit(f"FAIL missing listed file: {exc}")
if not i_overlay < i_pin:
    raise SystemExit("FAIL overlay must precede auth-release-pin")
print("PASS overlay precedes auth pin")
ot = overlay.read_text()
if "volumes:" in ot or "image:" in ot:
    raise SystemExit("FAIL overlay must not set image or volumes")
print("PASS overlay has no image or volumes")
if "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN: ${PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN:-}" not in ot:
    raise SystemExit("FAIL token must be env interpolation only")
if any(tok in ot for tok in ("sk-", "xoxb-", "REPLACE_ME_invite_operator_token")):
    raise SystemExit("FAIL overlay contains a token literal")
print("PASS token is interpolation only")
pt = pin.read_text()
if "PARKIO_REGISTRATION_" in pt:
    raise SystemExit("FAIL auth pin must remain image-only")
print("PASS auth pin has no registration env")
it = inbox.read_text()
if "auth-service:" in it:
    raise SystemExit("FAIL waitlist overlay must stay gateway-only")
if "volumes:" not in it:
    raise SystemExit("FAIL waitlist overlay expected to declare the inbox mount")
print("PASS waitlist overlay does not remount auth-service")
PY
echo "AUTH_REGISTRATION_OVERLAY_CHECKS_OK"
