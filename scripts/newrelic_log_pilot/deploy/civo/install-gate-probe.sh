#!/usr/bin/env bash
# Install the NR budget-gate textfile probe timer on a Civo/production host.
#
#   install-gate-probe.sh                 # dry run (default): print every action
#   sudo install-gate-probe.sh --apply    # copy units, daemon-reload, enable timer
#   sudo install-gate-probe.sh --apply --no-systemd   # files/dirs only (CI)
#
# Requires /opt/parkio-nr-log-continuous/current (NR log pilot release). Does not
# edit budget_gate.py or start the NR pilot compose stack.
set -euo pipefail

APPLY=0
SYSTEMD=1
for a in "$@"; do
  case "$a" in
    --apply) APPLY=1 ;;
    --no-systemd) SYSTEMD=0 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

HERE="$(cd "$(dirname "$0")" && pwd)"
UNIT_SRC="${PARKIO_NR_GATE_PROBE_UNIT_SRC:-$HERE/../../systemd}"
NR_ROOT="${PARKIO_NR_LOG_CONTINUOUS_ROOT:-/opt/parkio-nr-log-continuous/current}"
TEXTFILE_DIR="${PARKIO_PROMETHEUS_TEXTFILE_DIR:-/opt/parkio/docker/prometheus/textfile}"
PROMETHEUS_DIR="${PARKIO_PROMETHEUS_DIR:-/opt/parkio/docker/prometheus}"
UNIT_DIR="${PARKIO_SYSTEMD_UNIT_DIR:-/etc/systemd/system}"
SYSTEMCTL="${PARKIO_SYSTEMCTL:-systemctl}"
UNITS=(parkio-nr-gate-probe.service parkio-nr-gate-probe.timer)
TIMER=parkio-nr-gate-probe.timer

run() { echo "+ $*"; if [ "$APPLY" = 1 ]; then "$@"; fi; }

install_root_dir() {
  if [ "$(id -u)" = 0 ]; then
    run install -d -m 0755 -o root -g root "$1"
  else
    run install -d -m 0755 "$1"
  fi
}

install_root_file() {
  if [ "$(id -u)" = 0 ]; then
    run install -m 0644 -o root -g root "$1" "$2"
  else
    run install -m 0644 "$1" "$2"
  fi
}

if [ "$APPLY" = 1 ] && [ "$(id -u)" != 0 ] && [ "$UNIT_DIR" = /etc/systemd/system ]; then
  echo "--apply requires root" >&2
  exit 1
fi
python3 - <<'PY' || { echo "python3 >= 3.10 required" >&2; exit 1; }
import sys
sys.exit(0 if sys.version_info >= (3, 10) else 1)
PY

if [ ! -d "$NR_ROOT/scripts/newrelic_log_pilot" ]; then
  echo "refusing: NR log continuous release not found at $NR_ROOT" >&2
  exit 1
fi
if [ ! -f "$NR_ROOT/scripts/newrelic_log_pilot/export_gate_probe_textfile.py" ]; then
  echo "refusing: export_gate_probe_textfile.py missing under $NR_ROOT" >&2
  exit 1
fi

install_root_dir "$PROMETHEUS_DIR"
install_root_dir "$TEXTFILE_DIR"

for u in "${UNITS[@]}"; do
  src="$UNIT_SRC/$u"
  [ -f "$src" ] || { echo "refusing: unit source missing: $src" >&2; exit 1; }
  install_root_file "$src" "$UNIT_DIR/$u"
done

if [ "$SYSTEMD" = 1 ]; then
  run "$SYSTEMCTL" daemon-reload
  run "$SYSTEMCTL" enable --now "$TIMER"
fi

echo
echo "NR gate probe timer: $TIMER (textfile dir $TEXTFILE_DIR)"
[ "$APPLY" = 1 ] || echo "(dry run — nothing changed)"
