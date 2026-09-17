#!/usr/bin/env bash
#
# Focused offline tests for Azure SAS auth precedence + MinIO client-side encryption.
# No real Azure credentials. No production upload.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"

FAILED=0
ok() { echo "OK: $*"; }
bad() { echo "FAIL: $*" >&2; FAILED=1; }

FAKE_SAS='sv=2022-11-02&ss=b&srt=co&sp=rcwl&se=2099-01-01T00:00:00Z&sig=FAKE_NOT_A_LIVE_SIGNATURE'
FAKE_SAS_Q="?${FAKE_SAS}"

# --- SAS normalize ---
got="$(parkio_backup_normalize_sas_token "${FAKE_SAS_Q}")"
if [ "${got}" = "${FAKE_SAS}" ]; then ok "leading ? stripped"; else bad "leading ? normalize"; fi
got="$(parkio_backup_normalize_sas_token "${FAKE_SAS}")"
if [ "${got}" = "${FAKE_SAS}" ]; then ok "token without ? unchanged"; else bad "bare token normalize"; fi
if parkio_backup_normalize_sas_token "??sv=bad" >/dev/null 2>&1; then
  bad "double ? must fail"
else
  ok "double ? rejected"
fi

# --- Auth precedence ---
unset BACKUP_AZURE_SAS_TOKEN AZURE_STORAGE_SAS_TOKEN BACKUP_AZURE_STORAGE_KEY AZURE_STORAGE_KEY 2>/dev/null || true
export BACKUP_AZURE_SAS_TOKEN="${FAKE_SAS}"
export AZURE_STORAGE_SAS_TOKEN="sv=ignored&sig=IGNORED"
export BACKUP_AZURE_STORAGE_KEY="account-key-should-not-win"
parkio_backup_azure_resolve_auth
if [ "${PARKIO_AZURE_AUTH_MODE}" = "SAS" ] && [ "${AZURE_STORAGE_SAS_TOKEN}" = "${FAKE_SAS}" ]; then
  ok "BACKUP_AZURE_SAS_TOKEN wins"
else
  bad "BACKUP_AZURE_SAS_TOKEN precedence (mode=${PARKIO_AZURE_AUTH_MODE})"
fi

unset BACKUP_AZURE_SAS_TOKEN
export AZURE_STORAGE_SAS_TOKEN="${FAKE_SAS_Q}"
export BACKUP_AZURE_STORAGE_KEY="account-key-should-not-win"
parkio_backup_azure_resolve_auth
if [ "${PARKIO_AZURE_AUTH_MODE}" = "SAS" ] && [ "${AZURE_STORAGE_SAS_TOKEN}" = "${FAKE_SAS}" ]; then
  ok "AZURE_STORAGE_SAS_TOKEN wins over account key"
else
  bad "native SAS precedence (mode=${PARKIO_AZURE_AUTH_MODE})"
fi

unset BACKUP_AZURE_SAS_TOKEN AZURE_STORAGE_SAS_TOKEN
export BACKUP_AZURE_STORAGE_KEY="fake-account-key-not-live"
parkio_backup_azure_resolve_auth
if [ "${PARKIO_AZURE_AUTH_MODE}" = "ACCOUNT_KEY" ]; then ok "account-key fallback"; else bad "account-key fallback"; fi

unset BACKUP_AZURE_STORAGE_KEY AZURE_STORAGE_KEY
parkio_backup_azure_resolve_auth
if [ "${PARKIO_AZURE_AUTH_MODE}" = "LOGIN" ]; then ok "login fallback"; else bad "login fallback"; fi

# Empty / malformed SAS must fail closed (not silently fall through to key).
export BACKUP_AZURE_SAS_TOKEN=" "
export BACKUP_AZURE_STORAGE_KEY="should-not-reach"
if parkio_backup_azure_resolve_auth >/dev/null 2>&1; then
  bad "whitespace SAS must fail"
else
  ok "empty/whitespace SAS rejected"
fi
export BACKUP_AZURE_SAS_TOKEN="not-a-sas-token"
if parkio_backup_azure_resolve_auth >/dev/null 2>&1; then
  bad "malformed SAS must fail"
else
  ok "malformed SAS rejected"
fi
unset BACKUP_AZURE_SAS_TOKEN BACKUP_AZURE_STORAGE_KEY

# --- Secret never printed ---
export BACKUP_AZURE_SAS_TOKEN="${FAKE_SAS}"
OUT="$( { parkio_backup_azure_resolve_auth; echo "MODE=${PARKIO_AZURE_AUTH_MODE}"; } 2>&1 )"
if printf '%s' "${OUT}" | grep -Fq 'sig='; then
  bad "SAS signature leaked in resolve output"
else
  ok "SAS secret not printed by resolve"
fi
unset BACKUP_AZURE_SAS_TOKEN

# Offline az CLI contract: mock az and prove env-based SAS, no --sas-token argv.
MOCK_BIN="$(mktemp -d "${TMPDIR:-/tmp}/parkio-az-mock.XXXXXX")"
cat > "${MOCK_BIN}/az" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
LOG="${PARKIO_AZ_MOCK_LOG:?}"
{
  echo "ARGS:$*"
  echo "ENV_SAS_SET:$([ -n "${AZURE_STORAGE_SAS_TOKEN:-}" ] && echo yes || echo no)"
  echo "ENV_SAS_HAS_Q:$([ "${AZURE_STORAGE_SAS_TOKEN:-}" != "${AZURE_STORAGE_SAS_TOKEN#\?}" ] && echo yes || echo no)"
} >> "${LOG}"
# Fail closed if argv contains the raw token marker.
case " $* " in
  *" --sas-token "*) echo "unexpected --sas-token argv" >&2; exit 9 ;;
esac
exit 0
EOF
chmod +x "${MOCK_BIN}/az"
export PATH="${MOCK_BIN}:${PATH}"
export PARKIO_AZ_MOCK_LOG="${MOCK_BIN}/az.log"
: > "${PARKIO_AZ_MOCK_LOG}"

STAMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/parkio-azure-upload.XXXXXX")"
printf 'complete\n' > "${STAMP_DIR}/COMPLETE"
printf 'payload\n' > "${STAMP_DIR}/note.txt"
export BACKUP_AZURE_STORAGE_ACCOUNT=fakeacct
export BACKUP_AZURE_CONTAINER=parkio-production-backups
export BACKUP_AZURE_SAS_TOKEN="${FAKE_SAS}"
unset AZURE_STORAGE_KEY BACKUP_AZURE_STORAGE_KEY
UPLOAD_OUT="$(parkio_backup_offsite_upload_azure "${STAMP_DIR}" "stamp-test" 2>&1 || true)"
if printf '%s' "${UPLOAD_OUT}" | grep -Fq 'sig='; then
  bad "upload log leaked SAS"
else
  ok "upload log has no SAS secret"
fi
if printf '%s' "${UPLOAD_OUT}" | grep -Fq 'auth=SAS'; then
  ok "upload reports auth=SAS"
else
  bad "upload must report auth=SAS"
fi
if grep -q 'ENV_SAS_SET:yes' "${PARKIO_AZ_MOCK_LOG}" \
  && grep -q 'upload-batch' "${PARKIO_AZ_MOCK_LOG}" \
  && ! grep -q -- '--sas-token' "${PARKIO_AZ_MOCK_LOG}"; then
  ok "azure CLI uses AZURE_STORAGE_SAS_TOKEN env (no --sas-token argv)"
else
  bad "azure CLI SAS env contract"
  cat "${PARKIO_AZ_MOCK_LOG}" >&2 || true
fi
rm -rf "${STAMP_DIR}" "${MOCK_BIN}"
unset BACKUP_AZURE_SAS_TOKEN BACKUP_AZURE_STORAGE_ACCOUNT BACKUP_AZURE_CONTAINER

# --- MinIO seal / unseal roundtrip ---
PASS='parkio-ci-minio-seal-not-prod'
export BACKUP_ENCRYPT_PASSPHRASE="${PASS}"
SEAL_DIR="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-seal.XXXXXX")"
chmod 700 "${SEAL_DIR}"
mkdir -p "${SEAL_DIR}/minio/parkio-media/synthetic"
printf 'object-bytes\n' > "${SEAL_DIR}/minio/parkio-media/synthetic/obj.txt"
OBJ_SHA="$(sha256sum "${SEAL_DIR}/minio/parkio-media/synthetic/obj.txt" | awk '{print $1}')"

parkio_backup_seal_minio "${SEAL_DIR}"
if [ -f "${SEAL_DIR}/minio.tar.gz.enc" ] && [ ! -d "${SEAL_DIR}/minio" ]; then
  ok "MinIO sealed; plaintext tree removed"
else
  bad "MinIO seal left plaintext or missing enc"
fi
if [ -f "${SEAL_DIR}/minio.tar.gz.enc.sha256" ]; then
  ok "encrypted MinIO checksum present"
else
  bad "missing encrypted MinIO checksum"
fi
if [ -f "${SEAL_DIR}/minio-encryption.json" ] \
  && grep -q '"clientSideEncryption":true' "${SEAL_DIR}/minio-encryption.json" \
  && grep -q 'aes-256-cbc-pbkdf2' "${SEAL_DIR}/minio-encryption.json"; then
  ok "minio-encryption.json metadata"
else
  bad "minio-encryption.json"
fi

# Refuse plaintext offsite
PLAIN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-plain.XXXXXX")"
mkdir -p "${PLAIN_DIR}/minio/x"
printf 'x\n' > "${PLAIN_DIR}/COMPLETE"
if parkio_backup_assert_minio_offsite_safe "${PLAIN_DIR}" >/dev/null 2>&1; then
  bad "plaintext MinIO must be refused for offsite"
else
  ok "plaintext MinIO refused for offsite"
fi
rm -rf "${PLAIN_DIR}"

OUT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-out.XXXXXX")"
parkio_backup_unseal_minio "${SEAL_DIR}" "${OUT}"
GOT_SHA="$(sha256sum "${OUT}/minio/parkio-media/synthetic/obj.txt" | awk '{print $1}')"
if [ "${GOT_SHA}" = "${OBJ_SHA}" ]; then
  ok "MinIO decrypt/restore roundtrip"
else
  bad "MinIO roundtrip checksum mismatch"
fi

WRONG_OUT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-wrong.XXXXXX")"
if BACKUP_ENCRYPT_PASSPHRASE='wrong-passphrase' parkio_backup_unseal_minio "${SEAL_DIR}" "${WRONG_OUT}" >/dev/null 2>&1; then
  bad "wrong passphrase must fail MinIO unseal"
else
  ok "wrong passphrase fails MinIO unseal"
fi

CORRUPT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-corrupt.XXXXXX")"
cp -a "${SEAL_DIR}/." "${CORRUPT}/"
dd if=/dev/zero of="${CORRUPT}/minio.tar.gz.enc" bs=16 count=1 conv=notrunc >/dev/null 2>&1 || true
# Sidecar may hold an absolute path from the original seal dir; compare digests directly.
expected="$(awk '{print $1}' "${CORRUPT}/minio.tar.gz.enc.sha256")"
actual="$(sha256sum "${CORRUPT}/minio.tar.gz.enc" | awk '{print $1}')"
if [ "${actual}" = "${expected}" ]; then
  bad "corrupt encrypted MinIO must fail checksum"
else
  ok "corrupt encrypted MinIO fails checksum"
fi

# Historical plaintext compatibility
HIST="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-hist.XXXXXX")"
mkdir -p "${HIST}/minio/parkio-media"
printf 'legacy\n' > "${HIST}/minio/parkio-media/legacy.txt"
HIST_OUT="$(mktemp -d "${TMPDIR:-/tmp}/parkio-minio-hist-out.XXXXXX")"
parkio_backup_unseal_minio "${HIST}" "${HIST_OUT}"
if [ -f "${HIST_OUT}/minio/parkio-media/legacy.txt" ]; then
  ok "historical plaintext MinIO compatibility"
else
  bad "historical plaintext MinIO compatibility"
fi

# Postgres encryption helper unchanged (still aes-256-cbc + pbkdf2 in dump path)
if grep -q 'openssl enc -aes-256-cbc -pbkdf2' "${ROOT}/scripts/backup-databases.sh"; then
  ok "Postgres encryption convention unchanged"
else
  bad "Postgres encryption convention changed unexpectedly"
fi

# No remote delete
if grep -En 'blob delete|az storage blob delete|remote.?prune|offsite.?delete' \
  "${ROOT}/scripts/lib/backup-common.sh" >/dev/null 2>&1; then
  bad "remote delete present"
else
  ok "no remote-delete command"
fi

rm -rf "${SEAL_DIR}" "${OUT}" "${WRONG_OUT}" "${CORRUPT}" "${HIST}" "${HIST_OUT}"
unset BACKUP_ENCRYPT_PASSPHRASE

if [ "${FAILED}" -ne 0 ]; then
  echo "RESULT: FAIL — Azure SAS / MinIO encryption tests"
  exit 1
fi
echo "RESULT: PASS — Azure SAS / MinIO encryption tests"
