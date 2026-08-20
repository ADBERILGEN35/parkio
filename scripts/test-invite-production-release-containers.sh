#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R8.1 / gate R8-D (live).
#
# Proves, on the hardened self-hosted runner, that a staged runtime release is
# consumable by the exact non-root UIDs the runtime images use, and that the REAL
# merged invite-production Compose model resolves from that release.
#
# Two harness defects from run 32340585156 are fixed here and guarded against.
#
#   1. The gate staged into `mktemp -d` under /tmp. The runner service runs with
#      PrivateTmp=yes, so /tmp is a private mount namespace: dockerd, which lives
#      in the host namespace, cannot see anything written there. Every `-v` mount
#      therefore auto-created an EMPTY DIRECTORY at the target path instead of
#      binding the staged file — confirmed on the host, where
#      .../docker/prometheus/prometheus.yml exists as a root-owned *directory*.
#      File mounts failed to read, and the grafana *directory* mount silently
#      PASSED because `ls` on an auto-created empty directory succeeds. That was
#      a false green hiding a broken probe.
#
#      Fixed by staging under the stable runtime root (host-visible, the same
#      filesystem production deploys to) and by a mandatory pre-flight that
#      proves dockerd can actually see the staging path before any assertion
#      runs. Every probe now reads a real FILE and verifies its CONTENT, so an
#      empty auto-created mount can never pass again.
#
#   2. The Compose model was built from a hand-written `-f` list and the example
#      env alone, so `PARKIO_IMAGE_TAG` was unset. Production does not read that
#      from the env file — scripts/deploy-invite-production.sh derives it with
#      parkio_image_tag_for_sha and exports it. The gate now uses the same
#      canonical helpers (parkio_configure_deployment_profile for the file list,
#      parkio_image_tag_for_sha for the tag) so it tests the real deploy
#      contract rather than a synthetic one.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/lib/deploy-common.sh"
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

SHA="$(git -C "$ROOT" rev-parse HEAD)"

# ---------------------------------------------------------------------------
# Staging location. It MUST be a path dockerd can see. /tmp is disqualified on
# the runner by PrivateTmp=yes, so prefer the stable runtime root that
# production actually deploys to.
# ---------------------------------------------------------------------------
STABLE_ROOT="$(parkio_runtime_root)"
ACCEPT_ID="${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-0}"
if [ -d "$STABLE_ROOT" ] && [ -w "$STABLE_ROOT" ]; then
  ACCEPT_ROOT="$STABLE_ROOT/acceptance/$ACCEPT_ID"
elif [ -n "${GITHUB_ACTIONS:-}" ]; then
  echo "ERROR: stable runtime root is unavailable: $STABLE_ROOT" >&2
  echo "       Refusing to fall back to /tmp — PrivateTmp=yes makes it invisible" >&2
  echo "       to dockerd and would produce misleading results." >&2
  exit 3
else
  ACCEPT_ROOT="$(mktemp -d)"
fi
cleanup() { rm -rf -- "$ACCEPT_ROOT"; }
trap cleanup EXIT

# Modes are set explicitly: the runner's UMask=0077 would otherwise make these
# 0700 and the assertions below would flag the harness rather than the code.
install -d -m 0755 "$ACCEPT_ROOT" "$ACCEPT_ROOT/releases"
export PARKIO_RUNTIME_ROOT="$ACCEPT_ROOT"

echo "== pre-flight: dockerd must be able to see the staging path =="
sentinel="$ACCEPT_ROOT/.docker-visibility-probe"
printf 'parkio-visibility-%s\n' "$SHA" > "$sentinel"
chmod 0644 "$sentinel"
seen="$(docker run --rm --network none -v "$sentinel":/probe:ro "$PROBE_IMAGE" cat /probe 2>/dev/null || true)"
if [ "$seen" = "parkio-visibility-$SHA" ]; then
  ok "dockerd sees the staging path ($ACCEPT_ROOT)"
else
  bad "dockerd CANNOT see $ACCEPT_ROOT — the mount was auto-created empty."
  echo "        This is a harness/environment fault, not a release-permission fault." >&2
  echo "        Every subsequent probe would report a false failure; aborting." >&2
  exit 3
fi
rm -f -- "$sentinel"

echo "== staging under umask 0077 (the production runner's umask) =="
( umask 0077; parkio_stage_runtime_release "$ROOT" "$SHA" >/dev/null )
RELEASE="$(parkio_release_dir "$SHA")"
echo "  release=$RELEASE"
parkio_assert_release_is_stable "$RELEASE" || bad "release path is not stable"

# uid | image | staged path (a FILE whose content is verified — never a bare ls)
probes=(
  "nobody|prom/prometheus|docker/prometheus/prometheus.yml"
  "nobody|prom/prometheus|docker/prometheus/alerts.yml"
  "nobody|prom/prometheus|docker/prometheus/textfile/.gitkeep"
  "nobody|prom/alertmanager|docker/alertmanager/alertmanager.yml"
  "nobody|prom/alertmanager|docker/alertmanager/render-config.sh"
  "nobody|prom/blackbox-exporter|docker/blackbox/blackbox.yml"
  "10001|grafana/loki|docker/loki/loki.yml"
  "10001|grafana/tempo|docker/tempo/tempo.yml"
  "472|grafana/grafana|docker/grafana/provisioning/dashboards/dashboards.yml"
  "472|grafana/grafana|docker/grafana/provisioning/datasources/datasource.yml"
)

echo "== reading staged config as each image's real non-root UID =="
for probe in "${probes[@]}"; do
  IFS='|' read -r uid image path <<<"$probe"
  src="$RELEASE/$path"
  if [ ! -f "$src" ]; then
    bad "$path was not staged as a regular file"
    continue
  fi
  want="$(sha256sum "$src" | cut -d' ' -f1)"
  got="$(docker run --rm --user "$uid" --network none \
           -v "$src":/probe:ro "$PROBE_IMAGE" sha256sum /probe 2>/dev/null | cut -d' ' -f1 || true)"
  if [ -n "$got" ] && [ "$got" = "$want" ]; then
    ok "uid=$uid ($image) reads $path with matching content"
  else
    bad "uid=$uid ($image) CANNOT read $path (content mismatch or EACCES)"
  fi
done

# The grafana provisioning DIRECTORY is mounted whole; prove traversal + read.
if docker run --rm --user 472 --network none \
     -v "$RELEASE/docker/grafana/provisioning":/prov:ro "$PROBE_IMAGE" \
     sh -c 'cat /prov/dashboards/dashboards.yml >/dev/null' >/dev/null 2>&1; then
  ok "uid=472 traverses the mounted grafana provisioning directory"
else
  bad "uid=472 cannot traverse the mounted grafana provisioning directory"
fi

echo "== negative control: an umask-0077 checkout file must stay unreadable =="
raw="$ACCEPT_ROOT/raw"
install -d -m 0755 "$raw"
( umask 0077; cp "$ROOT/docker/prometheus/prometheus.yml" "$raw/prometheus.yml" )
rawmode="$(stat -c '%a' "$raw/prometheus.yml")"
if [ "$rawmode" != "600" ]; then
  bad "negative control did not produce a 0600 file (got $rawmode)"
elif docker run --rm --user nobody --network none \
       -v "$raw/prometheus.yml":/probe:ro "$PROBE_IMAGE" cat /probe >/dev/null 2>&1; then
  bad "a 0600 checkout file was readable by nobody — the gate cannot detect DEFECT-1"
else
  ok "a 0600 checkout file is unreadable by nobody (defect reproduced)"
fi

echo "== Prometheus validates the STAGED config in its own image =="
if docker run --rm --network none --entrypoint /bin/promtool \
     -v "$RELEASE/docker/prometheus":/etc/prometheus:ro \
     prom/prometheus:v2.54.1 check config /etc/prometheus/prometheus.yml >/dev/null 2>&1; then
  ok "promtool check config passes against the staged release"
else
  bad "promtool check config failed against the staged release"
fi

echo "== the REAL merged invite-production Compose model resolves from the release =="
# Same helpers the deploy uses, so this asserts the production contract rather
# than a synthetic one. parkio_configure_deployment_profile supplies the exact
# -f list; parkio_image_tag_for_sha supplies the tag production derives.
envfile="$ACCEPT_ROOT/model.env"
cp "$ROOT/docker/.env.invite-production.example" "$envfile"
chmod 0600 "$envfile"
PARKIO_DEPLOYMENT_PROFILE=invite-production parkio_configure_deployment_profile "$envfile"
EXPECTED_TAG="$(parkio_image_tag_for_sha "$SHA")"
export PARKIO_IMAGE_TAG="$EXPECTED_TAG"
export PARKIO_GIT_SHA="$SHA"
export PARKIO_IMAGE_CREATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
export PARKIO_IMAGE_VERSION="${PARKIO_IMAGE_VERSION:-0.0.1-SNAPSHOT}"

model="$ACCEPT_ROOT/model.json"
if PARKIO_COMPOSE_BASE_DIR="$RELEASE" parkio_compose "$envfile" config --format json \
     > "$model" 2>"$ACCEPT_ROOT/model.err"; then
  ok "the real merged Compose model resolves from the staged release"
  if grep -qE '/_work/|/source-[0-9]+-[0-9]+/' "$model"; then
    bad "the resolved model still references the Actions workspace"
  else
    ok "no resolved path in the model points into the Actions workspace"
  fi
  if grep -q "parkio/media-service:$EXPECTED_TAG" "$model"; then
    ok "model pins the exact candidate image tag ($EXPECTED_TAG)"
  else
    bad "model does not pin the expected candidate image tag ($EXPECTED_TAG)"
  fi
  if grep -q "$RELEASE/docker/prometheus/prometheus.yml" "$model"; then
    ok "Prometheus config resolves into the stable release"
  else
    bad "Prometheus config did not resolve into the stable release"
  fi
else
  bad "Compose model failed to resolve: $(tail -3 "$ACCEPT_ROOT/model.err" | tr '\n' ' ')"
fi
rm -f -- "$envfile"

echo "== no running container may bind-mount the runner workspace =="
stale=""
for cid in $(docker ps -q 2>/dev/null || true); do
  while IFS= read -r src; do
    [ -n "$src" ] || continue
    case "$src" in
      */_work/*) stale="$stale $(docker inspect -f '{{.Name}}' "$cid" | sed 's|^/||')" ;;
    esac
  done < <(docker inspect -f '{{range .Mounts}}{{println .Source}}{{end}}' "$cid" 2>/dev/null || true)
done
if [ -z "$stale" ]; then
  ok "no running container bind-mounts the runner _work tree"
else
  bad "running containers still bind-mount _work:$(echo "$stale" | tr ' ' '\n' | sort -u | tr '\n' ' ')"
fi

echo
echo "R8-D live container gate: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
