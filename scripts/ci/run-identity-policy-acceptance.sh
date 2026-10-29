#!/usr/bin/env bash
# Real cosign identity-policy acceptance (G-07 / #489), offline.
#
# Checks PARKIO_PROVENANCE_IDENTITY_REGEXP from scripts/lib/cosign-pin.env with the pinned cosign
# against synthetic certificates: a throwaway CA, one leaf per identity carrying the GitHub Actions
# issuer extensions, and a blob signed by each leaf. Only the publishing identity
# (candidate-publish.yml on refs/heads/api) may verify; every other workflow, branch or ref must be
# refused for its identity. No registry, Rekor or network access; nothing leaves the temp dir.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
# shellcheck source=scripts/lib/cosign-pin.env
source "$ROOT/scripts/lib/cosign-pin.env"

for tool in cosign openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "::error::$tool missing" >&2; exit 2; }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

issuer="$PARKIO_PROVENANCE_OIDC_ISSUER"
policy="$PARKIO_PROVENANCE_IDENTITY_REGEXP"
repo="https://github.com/ADBERILGEN35/parkio/.github/workflows"
accepted="$repo/candidate-publish.yml@refs/heads/api"
refused=(
  "$repo/any.yml@refs/heads/any-branch"
  "$repo/release.yml@refs/heads/api"
  "$repo/candidate-publish.yml@refs/heads/master"
  "$repo/candidate-publish.yml@refs/heads/api-evil"
  "$repo/candidate-publish.yml@refs/pull/1/merge"
  "$repo/candidate-publish.yml@refs/tags/api"
  "$repo/candidate-publishXyml@refs/heads/api"
  "https://example.invalid/?$accepted"
)
echo "policy=$policy"
echo "issuer=$issuer"

# Fulcio-style issuer extensions: 57264.1.1 holds the raw string, 57264.1.8 a DER UTF8String.
issuer_hex="$(printf '%s' "$issuer" | od -An -tx1 | tr -d ' \n')"
openssl ecparam -name prime256v1 -genkey -noout -out ca.key 2>/dev/null
openssl req -x509 -new -key ca.key -subj "/O=parkio-synthetic/CN=identity policy test CA" -days 1 \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign" -out ca.pem 2>/dev/null
printf 'parkio synthetic identity policy probe\n' > blob

# verify <n> <identity>: signs the blob with a leaf for <identity>; leaves cosign's exit code and output.
verify() {
  local n="$1" identity="$2"
  openssl ecparam -name prime256v1 -genkey -noout -out "$n.key" 2>/dev/null
  openssl req -new -key "$n.key" -subj "/O=parkio-synthetic" -out "$n.csr" 2>/dev/null
  printf '%s\n' \
    "basicConstraints=critical,CA:FALSE" \
    "keyUsage=critical,digitalSignature" \
    "extendedKeyUsage=codeSigning" \
    "subjectAltName=critical,URI:$identity" \
    "1.3.6.1.4.1.57264.1.1=DER:$issuer_hex" \
    "1.3.6.1.4.1.57264.1.8=ASN1:UTF8String:$issuer" > "$n.ext"
  openssl x509 -req -in "$n.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -days 1 -extfile "$n.ext" \
    -out "$n.pem" 2>/dev/null
  openssl dgst -sha256 -sign "$n.key" -out "$n.sig.der" blob
  base64 -w0 "$n.sig.der" > "$n.sig"
  set +e
  cosign verify-blob --certificate "$n.pem" --certificate-chain ca.pem --signature "$n.sig" \
    --certificate-oidc-issuer "$issuer" --certificate-identity-regexp "$policy" \
    --insecure-ignore-tlog=true --insecure-ignore-sct=true blob > "$n.out" 2>&1
  verify_ec=$?
  set -e
}

fail=0
# The accepted identity must verify, or the refusals below would prove nothing about the policy.
verify accepted "$accepted"
if [ "$verify_ec" -eq 0 ] && grep -q 'Verified OK' accepted.out; then
  echo "PASS accepted: $accepted"
else
  echo "::error::publishing identity was not accepted: $accepted" >&2
  cat accepted.out >&2
  fail=1
fi
i=0
for identity in "${refused[@]}"; do
  i=$((i + 1))
  verify "refused$i" "$identity"
  if [ "$verify_ec" -ne 0 ] && grep -q 'none of the expected identities matched' "refused$i.out"; then
    echo "PASS refused: $identity"
  else
    echo "::error::identity not refused for its identity (exit $verify_ec): $identity" >&2
    cat "refused$i.out" >&2
    fail=1
  fi
done
[ "$fail" -eq 0 ] || exit 1
echo "PASS: identity policy accepts only $accepted (${#refused[@]} other identities refused)"
