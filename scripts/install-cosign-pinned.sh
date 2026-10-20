#!/usr/bin/env bash
# Install the repository-pinned cosign with mandatory integrity verification.
# Usage (from repo root, as a user that can write the install dir):
#   ./scripts/install-cosign-pinned.sh
#   PARKIO_COSIGN_INSTALL_DIR=/usr/local/bin ./scripts/install-cosign-pinned.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/lib/cosign-pin.env
source "$ROOT/scripts/lib/cosign-pin.env"
INSTALL_DIR="${PARKIO_COSIGN_INSTALL_DIR:-/usr/local/bin}"
DEST="$INSTALL_DIR/cosign"

arch="$(uname -m)"
case "$arch" in
  x86_64|amd64)
    asset="cosign-linux-amd64"
    expect_sha="$PARKIO_COSIGN_SHA256_LINUX_AMD64"
    ;;
  aarch64|arm64)
    asset="cosign-linux-arm64"
    expect_sha="$PARKIO_COSIGN_SHA256_LINUX_ARM64"
    ;;
  *)
    echo "ERROR: unsupported architecture: $arch (need x86_64 or aarch64)" >&2
    exit 2
    ;;
esac

if [ -z "${expect_sha:-}" ] || [ "${#expect_sha}" -ne 64 ]; then
  echo "ERROR: pin file missing trusted sha256 for $asset" >&2
  exit 2
fi

url="https://github.com/sigstore/cosign/releases/download/${PARKIO_COSIGN_VERSION}/${asset}"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
tmp="$tmpdir/$asset"

echo "Downloading $url"
curl -fsSL -o "$tmp" "$url"
got="$(sha256sum "$tmp" | awk '{print $1}')"
if [ "$got" != "$expect_sha" ]; then
  echo "ERROR: cosign integrity check failed for $asset" >&2
  echo "  expected: $expect_sha" >&2
  echo "  got:      $got" >&2
  echo "Existing binary left untouched: $DEST" >&2
  exit 1
fi
echo "Integrity OK ($got)"

chmod 0755 "$tmp"
# Atomic replace into install dir without touching DEST until verified.
install_tmp="$INSTALL_DIR/.cosign.parkio.$$"
if [ ! -d "$INSTALL_DIR" ]; then
  echo "ERROR: install dir missing: $INSTALL_DIR" >&2
  exit 2
fi
if [ ! -w "$INSTALL_DIR" ]; then
  echo "ERROR: install dir not writable: $INSTALL_DIR (retry with suitable privileges)" >&2
  exit 2
fi
cp -f "$tmp" "$install_tmp"
chmod 0755 "$install_tmp"
mv -f "$install_tmp" "$DEST"
echo "Installed $DEST"
"$DEST" version
parsed="$("$DEST" version 2>/dev/null | sed -n 's/^[[:space:]]*GitVersion:[[:space:]]*//p' | head -n 1)"
if [ "$parsed" != "$PARKIO_COSIGN_VERSION" ]; then
  echo "ERROR: installed cosign reports '$parsed', want $PARKIO_COSIGN_VERSION" >&2
  exit 1
fi
echo "PASS: cosign $PARKIO_COSIGN_VERSION installed and verified"
