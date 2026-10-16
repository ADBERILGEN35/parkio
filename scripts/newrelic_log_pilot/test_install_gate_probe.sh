#!/usr/bin/env bash
# Stub-driven regression for install-gate-probe.sh (#439 / Y-05).
# Uses a fake systemctl and temp roots; no real host paths or systemd.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALLER="$ROOT/scripts/newrelic_log_pilot/deploy/civo/install-gate-probe.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-gate-probe-install-XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

NR_ROOT="$WORK/nr-current"
UNIT_DIR="$WORK/units"
TEXTFILE_DIR="$WORK/textfile"
FAKE_BIN="$WORK/bin"
mkdir -p "$NR_ROOT/scripts/newrelic_log_pilot" "$UNIT_DIR" "$TEXTFILE_DIR" "$FAKE_BIN"
touch "$NR_ROOT/scripts/newrelic_log_pilot/export_gate_probe_textfile.py"

cat >"$FAKE_BIN/systemctl" <<'STUB'
#!/usr/bin/env bash
echo "stub-systemctl $*" >>"${PARKIO_STUB_SYSTEMCTL_LOG:?}"
exit 0
STUB
chmod +x "$FAKE_BIN/systemctl"

export PARKIO_NR_LOG_CONTINUOUS_ROOT="$NR_ROOT"
export PARKIO_PROMETHEUS_DIR="$WORK/prometheus"
export PARKIO_PROMETHEUS_TEXTFILE_DIR="$TEXTFILE_DIR"
export PARKIO_SYSTEMD_UNIT_DIR="$UNIT_DIR"
export PARKIO_SYSTEMCTL="$FAKE_BIN/systemctl"
export PARKIO_STUB_SYSTEMCTL_LOG="$WORK/systemctl.log"

echo "=== dry run prints plan, changes nothing ==="
out="$WORK/dry-run.out"
if "$INSTALLER" >"$out" 2>&1; then ok "dry-run exit 0"; else bad "dry-run exit 0"; cat "$out"; fi
grep -q 'install -d' "$out" && ok "dry-run prints textfile directory plan" || bad "dry-run missing install -d"
grep -q 'parkio-nr-gate-probe.service' "$out" && ok "dry-run prints service unit install" || bad "dry-run missing service unit"
grep -q 'parkio-nr-gate-probe.timer' "$out" && ok "dry-run prints timer unit install" || bad "dry-run missing timer unit"
grep -q 'daemon-reload' "$out" && ok "dry-run prints daemon-reload" || bad "dry-run missing daemon-reload"
grep -q 'enable --now parkio-nr-gate-probe.timer' "$out" && ok "dry-run prints enable --now timer" || bad "dry-run missing enable --now"
grep -q 'dry run' "$out" && ok "dry-run banner present" || bad "dry-run banner missing"
[ ! -f "$UNIT_DIR/parkio-nr-gate-probe.service" ] && ok "dry-run did not copy service unit" || bad "dry-run copied service unit"
[ ! -s "$WORK/systemctl.log" ] && ok "dry-run did not invoke systemctl" || bad "dry-run invoked systemctl"

echo
echo "=== refuses missing NR release root ==="
if PARKIO_NR_LOG_CONTINUOUS_ROOT=/opt/parkio-nr-log-continuous/current "$INSTALLER" >"$WORK/refuse.out" 2>&1; then
  bad "must refuse when default NR root missing"
else
  grep -q 'refusing: NR log continuous release not found' "$WORK/refuse.out" \
    && ok "refuses missing /opt/parkio-nr-log-continuous/current" \
    || bad "refusal message wrong"; cat "$WORK/refuse.out"
fi

echo
echo "=== apply copies units and drives systemd ==="
: >"$WORK/systemctl.log"
if "$INSTALLER" --apply >"$WORK/apply.out" 2>&1; then ok "apply exit 0"; else bad "apply exit 0"; cat "$WORK/apply.out"; fi
for u in parkio-nr-gate-probe.service parkio-nr-gate-probe.timer; do
  [ -f "$UNIT_DIR/$u" ] && ok "apply installed $u" || bad "apply missing $u"
done
[ -d "$TEXTFILE_DIR" ] && ok "apply created textfile directory" || bad "apply missing textfile dir"
grep -q 'stub-systemctl daemon-reload' "$WORK/systemctl.log" \
  && ok "apply ran daemon-reload" || bad "apply missing daemon-reload"
grep -q 'stub-systemctl enable --now parkio-nr-gate-probe.timer' "$WORK/systemctl.log" \
  && ok "apply enabled timer now" || bad "apply missing enable --now"; cat "$WORK/systemctl.log"

echo
if [ "$fail" -eq 0 ]; then
  echo "All $pass gate-probe installer checks passed."
  exit 0
fi
echo "$fail check(s) failed ($pass passed)." >&2
exit 1
