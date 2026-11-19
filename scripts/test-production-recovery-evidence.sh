#!/usr/bin/env bash
# Stub-driven E2E for N-02 / #367: default-off production recovery evidence adapter.
# Runs the real scripts/production-recovery-evidence.sh and restore entrypoints with
# synthetic fixtures/stubs. Never sets verifiedCoverage=true. Never decrypts production data.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
chmod +x "${ROOT}/scripts/production-recovery-evidence.sh" \
  "${ROOT}/scripts/lib/production-recovery-evidence.sh" \
  "${ROOT}/scripts/lib/restore-safe-preflight.sh" \
  "${ROOT}/scripts/restore-hosted-beta.sh"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: missing $1" >&2; exit 2; }; }
need bash
need python3

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-n02-pre.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
FIX="${ROOT}/services/auth-service/src/test/resources/durable-erasure-evidence/v2"
ATTEMPT="5e5e5e5e-0000-4000-8000-00000000a772"
# Trust/evidence pin the producer cluster (fixture 0001). The restore target must be a
# distinct disposable identity (fixture ticket uses 0099) — same cluster is refused.
PRODUCER_IDENTITY="postgresql:7000000000000000001:parkio_auth"
TARGET="postgresql:7000000000000000099:parkio_auth"
DATASET="2026-09-20T03-30-01Z"

make_trust() {
  python3 - "${FIX}/producer.json" "$1" "$2" <<'PY'
import json, os, sys
producer = json.load(open(sys.argv[1], encoding="utf-8"))
key = next(k for k in producer["keys"] if k["keyId"] == "interop-fixture-key-2026a")
json.dump({
    "format": "parkio-erasure-evidence-trust",
    "version": 1,
    "databaseIdentity": sys.argv[3],
    "keys": [{
        "keyId": key["keyId"],
        "producerId": key["producerId"],
        "keyHex": key["keyHex"],
        "notBefore": "2026-01-01T00:00:00Z",
    }],
}, open(sys.argv[2], "w", encoding="utf-8"))
os.chmod(sys.argv[2], 0o600)
PY
}

TRUST="${WORK}/erasure-trust.json"
make_trust "${TRUST}" "${PRODUCER_IDENTITY}"
OUT="${WORK}/trusted-erasure-set.json"
ADAPTER="${ROOT}/scripts/production-recovery-evidence.sh"

run_adapter() {
  local mode="${1:-}"
  shift || true
  case "${mode}" in
    on)
      PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1 "${ADAPTER}" evaluate "$@"
      ;;
    off)
      PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=0 "${ADAPTER}" evaluate "$@"
      ;;
    *)
      echo "ERROR: run_adapter mode must be on|off" >&2
      return 2
      ;;
  esac
}

# --- P-DEFAULT-OFF: flag unset / zero ---
rc=0
out="$(run_adapter off \
  --bundle "${FIX}/bundles/checkpoint-tail/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && printf '%s' "${out}" | grep -q 'P-DEFAULT-OFF' \
    && [ ! -e "${OUT}" ]; then
  ok "P-DEFAULT-OFF refuses when adapter flag is off"
else
  bad "P-DEFAULT-OFF must exit 3 with no trusted set (rc=${rc})"
fi

rc=0
unset PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER || true
out="$(env -u PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER \
  "${ADAPTER}" evaluate \
  --bundle "${FIX}/bundles/checkpoint-tail/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && printf '%s' "${out}" | grep -q 'P-DEFAULT-OFF'; then
  ok "P-DEFAULT-OFF refuses when adapter flag is unset"
else
  bad "unset flag must refuse as P-DEFAULT-OFF (rc=${rc})"
fi

# --- P-MISS: missing bundle ---
rc=0
out="$(run_adapter on \
  --bundle "${WORK}/missing-bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && printf '%s' "${out}" | grep -q 'P-MISS' && [ ! -e "${OUT}" ]; then
  ok "P-MISS refuses missing evidence bundle"
else
  bad "P-MISS must exit 3 for missing bundle (rc=${rc})"
fi

# --- P-KEY-CUSTODY: missing trust ---
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/checkpoint-tail/bundle.json" --trust "${WORK}/no-trust.json" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && printf '%s' "${out}" | grep -q 'P-KEY-CUSTODY' && [ ! -e "${OUT}" ]; then
  ok "P-KEY-CUSTODY refuses missing trust document"
else
  bad "P-KEY-CUSTODY must exit 3 for missing trust (rc=${rc})"
fi

# --- P-CORR: tampered frontier ---
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/frontier-all-tampered/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && [ ! -e "${OUT}" ]; then
  ok "P-CORR refuses tampered frontier evidence before any apply"
else
  bad "P-CORR tampered frontier must exit 3 with no trusted set (rc=${rc})"
fi

# --- P-INCOMPLETE: gap in frontier chain ---
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/gap/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && [ ! -e "${OUT}" ]; then
  ok "P-INCOMPLETE refuses gap evidence before any apply"
else
  bad "P-INCOMPLETE gap must exit 3 with no trusted set (rc=${rc})"
fi

# --- P-STALE / missing frontier ---
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/missing-frontier/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && [ ! -e "${OUT}" ]; then
  ok "P-STALE/missing-frontier refuses before any apply"
else
  bad "missing-frontier must exit 3 with no trusted set (rc=${rc})"
fi

# --- Wrong databaseIdentity in trust (custody pin) ---
WRONG_TRUST="${WORK}/wrong-identity-trust.json"
make_trust "${WRONG_TRUST}" "postgresql:7000000000000000099:parkio_auth"
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/checkpoint-tail/bundle.json" --trust "${WRONG_TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 3 ] && [ ! -e "${OUT}" ]; then
  ok "P-KEY-CUSTODY refuses trust pinned to another databaseIdentity"
else
  bad "foreign-identity trust must exit 3 (rc=${rc})"
fi

# --- Trusted synthetic evidence under adapter ON still declares verifiedCoverage=false ---
rm -f "${OUT}"
rc=0
out="$(run_adapter on \
  --bundle "${FIX}/bundles/checkpoint-tail/bundle.json" --trust "${TRUST}" \
  --attempt "${ATTEMPT}" --dataset "${DATASET}" --target-identity "${TARGET}" \
  --out "${OUT}" 2>&1)" || rc=$?
if [ "${rc}" -eq 0 ] && [ -f "${OUT}" ] \
    && printf '%s' "${out}" | grep -q 'verifiedCoverage=false' \
    && printf '%s' "${out}" | grep -q 'production_restore=BLOCKED' \
    && python3 - "${OUT}" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
assert "erasureSet" in doc and "coverage" in doc
assert doc.get("verifiedCoverage") is not True
PY
then
  ok "adapter accepts trusted synthetic evidence without claiming verifiedCoverage"
else
  bad "trusted synthetic path must exit 0 with verifiedCoverage=false (rc=${rc})"
  printf '%s\n' "${out}" >&2 || true
fi

# --- Source guard: adapter and restore refusal never assign verifiedCoverage to true ---
if grep -E '^[^#]*verifiedCoverage[[:space:]]*=[[:space:]]*true' \
    "${ROOT}/scripts/lib/production-recovery-evidence.sh" \
    "${ROOT}/scripts/production-recovery-evidence.sh" \
    "${ROOT}/scripts/lib/restore-safe-preflight.sh" >/dev/null 2>&1; then
  bad "adapter/preflight must not assign verifiedCoverage to true"
else
  ok "source guard: adapter and preflight do not assign verifiedCoverage to true"
fi

# --- Production refuse gate remains intact with adapter flag set (P-LEDGER-ONLY) ---
# shellcheck source=lib/restore-safe-preflight.sh
source "${ROOT}/scripts/lib/restore-safe-preflight.sh"
rc=0
PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1 \
  parkio_restore_refuse_unverified_production >/dev/null 2>&1 || rc=$?
if [ "${rc}" -eq 3 ]; then
  ok "parkio_restore_refuse_unverified_production stays exit 3 with adapter flag set"
else
  bad "refuse-unverified must stay exit 3 (rc=${rc})"
fi

# --- Adapter enablement must not authorize standalone restore-database ---
BIN="${WORK}/bin"
mkdir -p "${BIN}"
cat > "${BIN}/docker" <<'PY'
#!/usr/bin/env python3
import sys
sys.exit(0)
PY
chmod +x "${BIN}/docker"
export PATH="${BIN}:${PATH}"

STAMP="${WORK}/${DATASET}"
mkdir -p "${STAMP}"
printf 'Salted__auth' > "${STAMP}/auth.sql.gz.enc"
printf 'stamp=%s\nsha256sums=%s\n' "${DATASET}" "$(printf '' | sha256sum | awk '{print $1}')" > "${STAMP}/COMPLETE"
ENV_FILE="${WORK}/env"
cat > "${ENV_FILE}" <<'EOF'
PARKIO_DEPLOYMENT_PROFILE=hosted-beta
EOF

rc=0
PARKIO_ENV_FILE="${ENV_FILE}" PARKIO_PRODUCTION_RECOVERY_EVIDENCE_ADAPTER=1 \
  "${ROOT}/scripts/restore-database.sh" \
  auth "${STAMP}/auth.sql.gz.enc" --yes \
  --recovery-cutoff "2026-09-20T03:30:01Z" >/dev/null 2>&1 || rc=$?
if [ "${rc}" -eq 3 ]; then
  ok "standalone restore-database stays refused with adapter flag set"
else
  bad "standalone restore-database must stay refused (rc=${rc})"
fi

# --- Isolated destination-bound path still documented as owned by test-restore-safe-preflight ---
if [ -f "${ROOT}/scripts/test-restore-safe-preflight.sh" ] \
    && grep -q 'valid synthetic databases restore through destination-bound ticket' \
      "${ROOT}/scripts/test-restore-safe-preflight.sh"; then
  ok "destination-bound isolated fixture behavior retained in test-restore-safe-preflight.sh"
else
  bad "isolated fixture coverage must remain in test-restore-safe-preflight.sh"
fi

echo
echo "${pass} passed, ${fail} failed"
[ "${fail}" -eq 0 ]
