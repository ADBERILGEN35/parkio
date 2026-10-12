#!/usr/bin/env bash
# E retry installation (owner request 2026-10-12, after E stopped at "CSRF checks: seeded account available"): installs T1 v7,
# t1-step.sh v7, nr-step.sh v7 and e-launch.sh v7 from one pinned commit, keeping the installed v6 copies. It starts nothing.
# Run as civo:
#   bash ~/parkio-release-20261009/tools/e-install-v7.sh
# 1. Downloads the four files into a private staging directory; checks each SHA-256 and its syntax. Any failure stops here:
#    nothing is installed or changed.
# 2. Checks every installed copy before replacing any. Each must be the v6 that e-install.sh installed, or already the new one.
#    Anything else stops here, nothing replaced.
# 3. Keeps each earlier copy as <name>.<tag> (cp -p: mode and times kept; an existing different keep file stops), installs the
#    four (mode 600) and verifies the installed checksums.
# Exit 0 when all four are installed and verified; 1 otherwise. Then run: bash ~/parkio-release-20261009/tools/e-launch.sh launch
set -uo pipefail
umask 077
C=ebc72708a9931cf735a2e6b249659a0996ed9e32
BASE="${EI_BASE:-https://raw.githubusercontent.com/ADBERILGEN35/parkio/$C/agent-tools/parkio-civo-release-20261009}"  # override: self-test only
TD="${EI_TD:-$HOME/parkio-release-20261009/tools}"
FILES=(
  "t1-release-step.py 2670b41e58e75b8edcfc57c6ad4b4972cc4f3c5e14a45b0b8c7132bf07efe0d4 180385f9cd40d671d1348d6211283fecf88b61225dd9c1f54db7749f315caf79 v6-180385f9"
  "t1-step.sh 3c0b692a1f86ef7fbe0489ac37f09dd9847166f3fac2877c597c9b063d337d33 a3bf4f317a87251321211cfa39b1c9804dde86c78858bec31cf466d67d5a2539 v6-a3bf4f31"
  "nr-step.sh 9bed0754cea05970b2c235b252673ffb8e389d06289b892ebc17792ac2e6e024 572c68d6e94233eb6609ac1624054faf1d0fca6a937f6677efb8a57534715f43 v6-572c68d6"
  "e-launch.sh eb72049d42beac3203ddb85bcc7d33d9e216746432534f99e2246d081b048549 0b6f79f7b46e3a117ade427f7d826df8b055cd9407f338e7ca7ae8fe78033a42 v6-0b6f79f7"
)
sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
stop() { echo "STOP: $*"; exit 1; }

echo "E retry installation (v7) | $(date -u +%Y-%m-%dT%H:%M:%SZ) | commit ${C:0:12} | $TD"
mkdir -p "$TD" && chmod 700 "$TD" || stop "cannot use $TD - nothing was installed"
S="$(mktemp -d "$TD/.e-install-v7-XXXXXX")" || stop "cannot create a staging directory - nothing was installed"
trap 'rm -rf "$S"' EXIT

echo "== 1. download and verify (nothing is installed yet)"
for line in "${FILES[@]}"; do
  read -r name new prev tag <<< "$line"
  curl -fsSL -o "$S/$name" "$BASE/$name" || stop "download of $name failed - nothing was installed"
  [ "$(sha "$S/$name")" = "$new" ] || stop "$name checksum mismatch (got $(sha "$S/$name" | cut -c1-12)) - nothing was installed"
  case "$name" in
    *.py) python3 -I -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' "$S/$name" 2>/dev/null ;;
    *) bash -n "$S/$name" 2>/dev/null ;;
  esac || stop "$name syntax check failed - nothing was installed"
  echo "  $name: sha256 ${new:0:16} OK, syntax ok"
done

echo "== 2. the installed copies (nothing is replaced yet)"
for line in "${FILES[@]}"; do
  read -r name new prev tag <<< "$line"
  cur="$(sha "$TD/$name")"
  if [ "$cur" = "$new" ]; then
    echo "  $name: already the new version"
  elif [ -n "$cur" ] && [ "$cur" = "$prev" ]; then
    if [ -e "$TD/$name.$tag" ] && ! cmp -s "$TD/$name" "$TD/$name.$tag"; then
      stop "$name.$tag exists with other content - nothing was replaced"
    fi
    echo "  $name: the earlier version ${prev:0:12}, kept as $name.$tag"
  elif [ -z "$cur" ] && [ "$prev" = none ]; then
    echo "  $name: not installed yet"
  else
    stop "the installed $name (${cur:0:12}) is neither ${prev:0:12} nor ${new:0:12} - nothing was replaced"
  fi
done

echo "== 3. install"
for line in "${FILES[@]}"; do
  read -r name new prev tag <<< "$line"
  cur="$(sha "$TD/$name")"
  [ "$cur" = "$new" ] && continue
  if [ "$cur" = "$prev" ] && [ ! -e "$TD/$name.$tag" ]; then
    cp -p "$TD/$name" "$TD/$name.$tag" || stop "cannot keep $name as $name.$tag"
  fi
  cp "$S/$name" "$TD/$name.new" && chmod 600 "$TD/$name.new" && mv -f "$TD/$name.new" "$TD/$name" \
    || stop "cannot install $name (earlier copies are kept)"
done
for line in "${FILES[@]}"; do
  read -r name new prev tag <<< "$line"
  [ "$(sha "$TD/$name")" = "$new" ] && [ "$(stat -c %a "$TD/$name")" = 600 ] || stop "$name is not the verified new version after install"
  echo "  $name installed: ${new:0:16}, mode 600$( [ "$prev" != none ] && [ -e "$TD/$name.$tag" ] && echo "; earlier copy $name.$tag kept")"
done
echo "E RETRY INSTALLATION COMPLETE - next: bash $TD/e-launch.sh launch"
