#!/usr/bin/env bash
# Fetch the esbuild 0.27.4 linux-x64 binary that web-bundle-compare.py pins, from the npm registry, and verify it:
#   - the tarball against the sha512 integrity of @esbuild/linux-x64@0.27.4 in frontend/pnpm-lock.yaml at f888a81b
#     (the esbuild the web build minifies with: vite@6.4.3>esbuild 0.27.4);
#   - the extracted package/bin/esbuild against its pinned SHA-256 (web-bundle-compare.py ESBUILD_SHA256).
# Nothing is installed system-wide and no package script runs: only that one file is extracted.
#   fetch-esbuild-0.27.4.sh DEST_FILE      (DEST_FILE must not exist; it is written with mode 755)
set -euo pipefail
DEST="${1:?usage: fetch-esbuild-0.27.4.sh DEST_FILE}"
URL="https://registry.npmjs.org/@esbuild/linux-x64/-/linux-x64-0.27.4.tgz"
INTEGRITY="S5qOXrKV8BQEzJPVxAwnryi2+Iq5pB40gTEIT69BQONqR7JH1EPIcQ/Uiv9mCnn05jff9umq/5nqzxlqTOg9NA=="
BIN_SHA256="5186a0791db7cff10adf1fef03dc4790db502eaaff86117664679412fedeccae"
[ ! -e "$DEST" ] || { echo "ERROR: $DEST exists; remove it or choose another name" >&2; exit 1; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
curl -fsS --proto '=https' --max-time 180 -o "$tmp/esbuild.tgz" "$URL"
got="$(python3 -I -c 'import base64, hashlib, sys; print(base64.b64encode(hashlib.sha512(open(sys.argv[1], "rb").read()).digest()).decode())' "$tmp/esbuild.tgz")"
[ "$got" = "$INTEGRITY" ] || { echo "ERROR: the tarball does not match the lockfile's sha512 integrity" >&2; exit 1; }
tar -xzf "$tmp/esbuild.tgz" -C "$tmp" package/bin/esbuild
echo "$BIN_SHA256  $tmp/package/bin/esbuild" | sha256sum -c --quiet - || { echo "ERROR: binary SHA-256 mismatch" >&2; exit 1; }
install -m 755 "$tmp/package/bin/esbuild" "$DEST"
[ "$("$DEST" --version)" = "0.27.4" ] || { echo "ERROR: $DEST does not report version 0.27.4" >&2; rm -f "$DEST"; exit 1; }
echo "esbuild 0.27.4 linux-x64 verified (tarball sha512 = lockfile, binary sha256 ${BIN_SHA256:0:12}): $DEST"
