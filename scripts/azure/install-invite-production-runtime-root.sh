#!/usr/bin/env bash
#
# Provision the stable invite-production runtime root (PROD-DEPLOY-01A-R8).
#
# Root-only, idempotent, and deliberately separate from the runner installer so
# it can be applied to an already-registered runner host without touching runner
# registration.
#
# Why root has to do this
# -----------------------
# The deploy runner has NO sudo, by design, and /opt/parkio is owned by
# parkioops. The runner therefore cannot create or re-permission the runtime
# root itself — that is exactly the property we want, since it keeps a compromised
# job from re-permissioning production runtime paths. Provisioning happens once,
# here, as root.
#
# What it must guarantee
# ----------------------
#   * the release root outlives every Actions job (it is outside _work);
#   * the deploy runner can WRITE releases into it without sudo;
#   * non-root container UIDs (prometheus/alertmanager `nobody`, loki/tempo
#     10001, grafana 472) can TRAVERSE every ancestor to reach their config.
#
# The runner service keeps UMask=0077. This script does not relax it, and the
# staging code sets every mode explicitly rather than inheriting the umask.

set -euo pipefail

RUNNER_USER="${PARKIO_RUNNER_USER:-parkio-runner}"
PARKIO_BASE="/opt/parkio"
RUNTIME_ROOT="${PARKIO_RUNTIME_ROOT:-$PARKIO_BASE/invite-production}"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: runtime-root provisioning must run as root." >&2
  exit 2
fi
if ! id "$RUNNER_USER" >/dev/null 2>&1; then
  echo "ERROR: runner user '$RUNNER_USER' does not exist; install the runner first." >&2
  exit 2
fi
case "$RUNTIME_ROOT" in
  /opt/parkio/*) ;;
  *) echo "ERROR: refusing to provision a runtime root outside /opt/parkio: $RUNTIME_ROOT" >&2; exit 2 ;;
esac

# /opt/parkio is parkioops-owned and was 0750, which no non-root container UID
# can traverse. Widen the PARENT to 0755 only: this exposes nothing but the
# directory listing, and the sensitive children keep their own restrictive modes
# (asserted below) exactly the way /home is 0755 over 0700 home directories.
if [ -d "$PARKIO_BASE" ]; then
  chmod 0755 "$PARKIO_BASE"
else
  install -d -m 0755 "$PARKIO_BASE"
fi

# Secret-bearing siblings must NOT be widened. Re-assert their modes so a future
# edit of this script cannot silently open them up.
for restricted in "$PARKIO_BASE/certs" "$PARKIO_BASE/deploy-artifacts"; do
  [ -d "$restricted" ] || continue
  chmod 0750 "$restricted"
  mode="$(stat -c '%a' "$restricted")"
  if [ "$mode" != "750" ]; then
    echo "ERROR: $restricted must stay 0750 (found $mode)" >&2
    exit 3
  fi
done

install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0755 "$RUNTIME_ROOT"
install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0755 "$RUNTIME_ROOT/releases"
# Non-deploy acceptance stages a throwaway release here. It must live on the same
# host-visible filesystem as a real release — /tmp is unusable because the runner
# service runs with PrivateTmp=yes and dockerd cannot see into that namespace.
install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0755 "$RUNTIME_ROOT/acceptance"

# Ownership is re-asserted, not just created: a root-run diagnostic can leave one
# of these directories root-owned, which silently blocks the sudo-less runner.
for d in "$RUNTIME_ROOT" "$RUNTIME_ROOT/releases" "$RUNTIME_ROOT/acceptance"; do
  chown "$RUNNER_USER":"$RUNNER_USER" "$d"
  chmod 0755 "$d"
done

# Prove the traversal chain a non-root container UID actually walks.
dir="$RUNTIME_ROOT/releases"
while [ "$dir" != "/" ]; do
  mode="$(stat -c '%a' "$dir")"
  if [ "$(( 8#$mode & 8#0001 ))" -eq 0 ]; then
    echo "ERROR: $dir is mode $mode — not traversable by non-root container UIDs" >&2
    exit 3
  fi
  dir="$(dirname "$dir")"
done

echo "Invite-production runtime root provisioned."
stat -c 'runtimeRoot=%n owner=%U:%G mode=%a' "$RUNTIME_ROOT"
stat -c 'releases=%n owner=%U:%G mode=%a' "$RUNTIME_ROOT/releases"
stat -c 'acceptance=%n owner=%U:%G mode=%a' "$RUNTIME_ROOT/acceptance"
stat -c 'parkioBase=%n owner=%U:%G mode=%a' "$PARKIO_BASE"
