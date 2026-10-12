#!/usr/bin/env bash
# Self-test of e-install-v7.sh with a local file:// "remote" holding the four files of commit ebc72708, and a tools directory
# seeded with the v6 copies installed on the host by e-install.sh (T1 v6 180385f9, t1-step.sh v6 a3bf4f31, nr-step.sh v6
# 572c68d6, e-launch.sh v6 0b6f79f7). I1 install -> the four v7 (mode 600), the v6 copies kept (cp -p), staging removed.
# I2 a re-run -> already current.
# I3 a corrupted download -> STOP before anything is installed. I4 an unexpected installed version -> STOP, nothing
# replaced. I5 a different keep file -> STOP, nothing replaced. I6 a failed download -> STOP, nothing installed.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/e-install-v7.sh"
REPO="${EIT_REPO:-$(git -C "$HERE" rev-parse --show-toplevel)}"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/remote" "$W/v4"
for f in t1-release-step.py t1-step.sh nr-step.sh e-launch.sh; do
  git -C "$REPO" show "ebc72708a9931cf735a2e6b249659a0996ed9e32:agent-tools/parkio-civo-release-20261009/$f" > "$W/remote/$f"
  git -C "$REPO" show "8880ec3e4038ce460e503ff8263ce96794188aa7:agent-tools/parkio-civo-release-20261009/$f" > "$W/v4/$f"
done
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
seed() { rm -rf "$W/td"; mkdir -p "$W/td"; cp "$W"/v4/* "$W/td/"; chmod 600 "$W"/td/*; touch -d '2026-10-12 12:00:00' "$W"/td/*; }
tree() { (cd "$W/td" && for f in $(ls -A | sort); do echo "$f $(sha256sum "$f" | cut -c1-12) $(stat -c %a "$f")"; done); }
runit() { local name="$1"; shift; env EI_BASE="file://$W/remote" EI_TD="$W/td" "$@" bash "$TOOL" > "$W/$name" 2>&1; echo $? > "$W/$name.rc"; }
rc_is() { [ "$(cat "$W/$1.rc")" = "$2" ] && ok "$1 exit $2" || no "$1 exit $(cat "$W/$1.rc"), want $2"; }

seed; runit i1.out; sed "s#$W#<W>#g" "$W/i1.out"; rc_is i1.out 0
for s in "t1-release-step.py: sha256 2670b41e58e75b8e OK, syntax ok" "e-launch.sh: sha256 eb72049d42beac32 OK, syntax ok" \
         "t1-release-step.py: the earlier version 180385f9cd40, kept as t1-release-step.py.v6-180385f9" \
         "e-launch.sh: the earlier version 0b6f79f7b46e, kept as e-launch.sh.v6-0b6f79f7" \
         "t1-step.sh installed: 3c0b692a1f86ef7f, mode 600; earlier copy t1-step.sh.v6-a3bf4f31 kept" "E RETRY INSTALLATION COMPLETE"; do
  has i1.out "$s"
done
[ "$(sha256sum "$W/td/t1-release-step.py.v6-180385f9" | cut -c1-12)" = 180385f9cd40 ] && [ "$(stat -c '%a %y' "$W/td/t1-release-step.py.v6-180385f9" | cut -c1-23)" = "600 2026-10-12 12:00:00" ] \
  && ok "i1: the v6 T1 kept byte-identical with mode and time" || no "i1 keep copy"
[ "$(ls -A "$W/td" | grep -c '^\.e-install')" = 0 ] && ok "i1: staging directory removed" || no "i1 staging left"
[ "$(ls -A "$W/td" | wc -l)" = 8 ] && ok "i1: 4 installed + 4 kept files, nothing else" || no "i1 file count: $(ls -A "$W/td" | tr '\n' ' ')"

before="$(tree)"; runit i2.out; rc_is i2.out 0; has i2.out "t1-step.sh: already the new version"; has i2.out "e-launch.sh: already the new version"
[ "$(tree)" = "$before" ] && ok "i2: nothing changed on a re-run" || no "i2 changed files"

seed; before="$(tree)"; cp -r "$W/remote" "$W/remote.bak"; printf 'x' >> "$W/remote/nr-step.sh"; runit i3.out; rc_is i3.out 1
has i3.out "STOP: nr-step.sh checksum mismatch"; has i3.out "nothing was installed"
[ "$(tree)" = "$before" ] && ok "i3: tools directory unchanged" || no "i3 changed files"; rm -rf "$W/remote"; mv "$W/remote.bak" "$W/remote"

seed; echo "# local edit" >> "$W/td/t1-step.sh"; before="$(tree)"; runit i4.out; rc_is i4.out 1
has i4.out "STOP: the installed t1-step.sh"; has i4.out "nothing was replaced"; [ "$(tree)" = "$before" ] && ok "i4: nothing replaced" || no "i4 changed files"

seed; echo "other" > "$W/td/t1-release-step.py.v6-180385f9"; before="$(tree)"; runit i5.out; rc_is i5.out 1
has i5.out "STOP: t1-release-step.py.v6-180385f9 exists with other content - nothing was replaced"; [ "$(tree)" = "$before" ] && ok "i5: nothing replaced" || no "i5 changed files"

seed; before="$(tree)"; runit i6.out EI_BASE="file://$W/missing"; rc_is i6.out 1; has i6.out "STOP: download of t1-release-step.py failed - nothing was installed"
[ "$(tree)" = "$before" ] && ok "i6: tools directory unchanged" || no "i6 changed files"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
