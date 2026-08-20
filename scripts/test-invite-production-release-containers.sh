#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8 / gate R8-D (live).
#
# Reproduces DEFECT-1 as a real container operation on the hardened self-hosted
# runner, and proves the staged release defeats it.
#
# The mechanism, precisely: the runner service runs with UMask=0077, so
# actions/checkout writes every file 0600 parkio-runner and every directory 0700.
# Docker resolves the *host* side of a bind mount as root, so the daemon can
# always find the file — but the container process then reads it as its own
# non-root UID (prometheus/alertmanager `nobody`, loki/tempo 10001, grafana 472)
# and the kernel checks that UID against the host inode's mode. 0600 => EACCES.
# Directory mounts such as grafana/provisioning additionally need 0755 traversal.
#
# This gate stages a release under a deliberately hostile umask and then reads
# every mounted path back as the exact UID each image runs as. It must never be
# "fixed" by relaxing UMask=0077.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/runtime-release.sh"

if ! docker info >/dev/null 2>&1; then
  echo "SKIP: Docker is unavailable; this gate requires the production runner."
  exit 0
fi

PROBE_IMAGE="${PARKIO_PROBE_IMAGE:-busybox:1.36}"
PASS=0
FAIL=0
ok()  { echo "  PASS  $*"; PASS=$((PASS + 1)); }
bad() { echo "  FAIL  $*" >&2; FAIL=$((FAIL + 1)); }

TMP="$(mktemp -d)"
chmod 0755 "$TMP"
trap 'rm -rf -- "$TMP"' EXIT

SHA="$(git -C "$ROOT" rev-parse HEAD)"
export PARKIO_RUNTIME_ROOT="$TMP/runtime"
install -d -m 0755 "$PARKIO_RUNTIME_ROOT" "$PARKIO_RUNTIME_ROOT/releases"

echo "== staging under umask 0077 (the production runner's umask) =="
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
RELEASE="$(parkio_release_dir "$SHA")"
echo "  release=$RELEASE"

docker pull -q "$PROBE_IMAGE" >/dev/null 2>&1 || true

# uid                image                        mounted path
probes=(
  "nobody|prom/prometheus|docker/prometheus/prometheus.yml"
  "nobody|prom/prometheus|docker/prometheus/alerts.yml"
  "nobody|prom/alertmanager|docker/alertmanager/alertmanager.yml"
  "nobody|prom/alertmanager|docker/alertmanager/render-config.sh"
  "10001|grafana/loki|docker/loki/loki.yml"
  "10001|grafana/tempo|docker/tempo/tempo.yml"
  "472|grafana/grafana|docker/grafana/provisioning"
)

echo "== reading staged config as each image's non-root UID =="
for probe in "${probes[@]}"; do
  IFS='|' read -r uid image path <<<"$probe"
  src="$RELEASE/$path"
  if [ ! -e "$src" ]; then
    bad "$path was not staged"
    continue
  fi
  if [ -d "$src" ]; then
    cmd='ls /probe >/dev/null'
  else
    cmd='cat /probe >/dev/null'
  fi
  if docker run --rm --user "$uid" --network none \
       -v "$src":/probe:ro "$PROBE_IMAGE" sh -c "$cmd" >/dev/null 2>&1; then
    ok "uid=$uid ($image) can read $path"
  else
    bad "uid=$uid ($image) CANNOT read $path"
  fi
done

echo "== the defect is real: the same read fails against an umask-0077 checkout =="
raw="$TMP/raw"
install -d -m 0700 "$raw"
( umask 0077; cp "$ROOT/docker/prometheus/prometheus.yml" "$raw/prometheus.yml" )
if docker run --rm --user nobody --network none \
     -v "$raw/prometheus.yml":/probe:ro "$PROBE_IMAGE" sh -c 'cat /probe >/dev/null' >/dev/null 2>&1; then
  bad "an umask-0077 file was readable by nobody — the gate cannot detect DEFECT-1"
else
  ok "an umask-0077 checkout file is unreadable by nobody (defect reproduced)"
fi

echo "== Prometheus/Alertmanager validate the STAGED config in their own images =="
if docker run --rm --network none --entrypoint /bin/promtool \
     -v "$RELEASE/docker/prometheus":/etc/prometheus:ro \
     prom/prometheus:v2.54.1 check config /etc/prometheus/prometheus.yml >/dev/null 2>&1; then
  ok "promtool check config passes against the staged release"
else
  bad "promtool check config failed against the staged release"
fi

echo
echo "R8-D live container gate: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
