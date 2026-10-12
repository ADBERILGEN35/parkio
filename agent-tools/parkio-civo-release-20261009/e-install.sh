#!/usr/bin/env bash
# E installation (owner request 2026-10-12): installs T1 v6, t1-step.sh v6, nr-step.sh v6 and e-launch.sh from one pinned
# commit, keeping the installed earlier copies. It starts nothing. Run as civo:
#   bash ~/parkio-release-20261009/tools/e-install.sh
# 1. Downloads the four files into a private staging directory; checks each SHA-256 and its syntax. Any failure stops here:
#    nothing is installed or changed.
# 2. Checks every installed copy before replacing any. Each must be the expected earlier version or already the new one;
#    e-launch.sh may be absent. Anything else stops here, nothing replaced.
# 3. Keeps each earlier copy as <name>.<tag> (cp -p: mode and times kept; an existing different keep file stops), installs the
#    four (mode 600) and verifies the installed checksums.
# Exit 0 when all four are installed and verified; 1 otherwise. Then run: bash ~/parkio-release-20261009/tools/e-launch.sh launch
set -uo pipefail
umask 077
C=8880ec3e4038ce460e503ff8263ce96794188aa7
BASE="${EI_BASE:-https://raw.githubusercontent.com/ADBERILGEN35/parkio/$C/agent-tools/parkio-civo-release-20261009}"  # override: self-test only
TD="${EI_TD:-$HOME/parkio-release-20261009/tools}"
FILES=(
  "t1-release-step.py 180385f9cd40d671d1348d6211283fecf88b61225dd9c1f54db7749f315caf79 01ed008c8ef2268381fcb238025c589b3b0e085f4e8366862a459372c536236e v4-01ed008c"
  "t1-step.sh a3bf4f317a87251321211cfa39b1c9804dde86c78858bec31cf466d67d5a2539 0e1395265062c4ef39056d176ac5bb9c803964647a1a32ec97e3764b2071dd7a v4-0e139526"
  "nr-step.sh 572c68d6e94233eb6609ac1624054faf1d0fca6a937f6677efb8a57534715f43 4ea46f4825f605c204b101e45b7d4f86a6087df0037e193fb8c295b6d0694dd6 v4-4ea46f48"
  "e-launch.sh 0b6f79f7b46e3a117ade427f7d826df8b055cd9407f338e7ca7ae8fe78033a42 none -"
)
sha() { sha256sum "$1" 2>/dev/null | cut -d' ' -f1; }
stop() { echo "STOP: $*"; exit 1; }

echo "E installation | $(date -u +%Y-%m-%dT%H:%M:%SZ) | commit ${C:0:12} | $TD"
mkdir -p "$TD" && chmod 700 "$TD" || stop "cannot use $TD - nothing was installed"
S="$(mktemp -d "$TD/.e-install-XXXXXX")" || stop "cannot create a staging directory - nothing was installed"
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
echo "E INSTALLATION COMPLETE - next: bash $TD/e-launch.sh launch"
