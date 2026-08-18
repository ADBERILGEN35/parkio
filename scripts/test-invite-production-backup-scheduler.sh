#!/usr/bin/env bash
#
# PROD-DEPLOY-01A-R3 / D2 regression tests.
#
# D2 was not a bug in one file — it was two halves of the design contradicting
# each other: units that required a persistent /opt/parkio/docker/.env.invite-production,
# and a deploy path that deliberately never creates one. These tests pin both
# halves so they cannot drift apart again:
#
#   * the units must never name a persistent production env or a secret
#   * the wrapper must render into tmpfs at 0600 and clean up on every exit path
#   * the installed payload must be the real execution closure, versioned,
#     checksummed, idempotent, and secret-free
#   * production-mode invariants (encryption, offsite, ten DBs, PRIV-001 ledger)
#     must survive the scheduler path
#
# Everything runs against a temporary prefix. No systemd unit is installed, no
# timer is enabled, and no database is contacted.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SERVICE_UNIT="$ROOT/infra/systemd/parkio-invite-backup.service"
TIMER_UNIT="$ROOT/infra/systemd/parkio-invite-backup.timer"
INSTALLER="$ROOT/scripts/azure/install-invite-production-backup-scheduler.sh"
WRAPPER="$ROOT/scripts/azure/invite-production-backup-run.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-scheduler-test-XXXXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT HUP INT TERM

pass=0
fail=0
ok() { echo "PASS: $1"; pass=$((pass + 1)); }
bad() { echo "FAIL: $1" >&2; fail=$((fail + 1)); }

has() { grep -Fq "$1" "$2"; }

# --------------------------------------------------------------------------- #
# 1. Timer policy                                                              #
# --------------------------------------------------------------------------- #
echo "=== timer policy ==="

has 'OnCalendar=*-*-* 02:30:00 UTC' "$TIMER_UNIT" \
  && ok "timer runs daily at 02:30 UTC" || bad "timer must run daily at 02:30 UTC"
has 'Persistent=true' "$TIMER_UNIT" \
  && ok "timer is Persistent=true" || bad "timer must be Persistent=true"
grep -Eq '^RandomizedDelaySec=[0-9]+m?$' "$TIMER_UNIT" \
  && ok "timer documents a randomized delay" || bad "timer must declare RandomizedDelaySec"
has 'Unit=parkio-invite-backup.service' "$TIMER_UNIT" \
  && ok "timer activates parkio-invite-backup.service" || bad "timer must activate the backup service"
has 'WantedBy=timers.target' "$TIMER_UNIT" \
  && ok "timer installs into timers.target" || bad "timer must install into timers.target"

# --------------------------------------------------------------------------- #
# 2. Service unit: no secrets, no persistent env, absolute paths               #
# --------------------------------------------------------------------------- #
echo
echo "=== service unit ==="

has 'Type=oneshot' "$SERVICE_UNIT" \
  && ok "service is oneshot" || bad "service must be Type=oneshot"

if grep -q 'Environment=PARKIO_ENV_FILE=' "$SERVICE_UNIT"; then
  bad "service must not pin a persistent PARKIO_ENV_FILE (this was D2)"
else
  ok "service pins no persistent env file"
fi
if grep -Eq '/opt/parkio/docker/\.env|\.env\.invite-production[^.]' "$SERVICE_UNIT"; then
  bad "service must not reference a persistent production env path"
else
  ok "service references no persistent production env path"
fi

# Any Environment= carrying a credential-shaped key would put a secret in a
# world-readable unit file and in `systemctl show` output.
if grep -E '^Environment=' "$SERVICE_UNIT" \
    | grep -Eq 'PASSWORD|PASSPHRASE|SECRET|TOKEN|API_KEY|WEBHOOK|PRIVATE_KEY'; then
  bad "service unit must not carry secret-shaped Environment= values"
else
  ok "service unit carries no secret-shaped Environment= values"
fi

if grep -Eq '^ExecStart=/opt/parkio/invite-production/scripts/azure/invite-production-backup-run\.sh' "$SERVICE_UNIT"; then
  ok "service ExecStart is an absolute stable path"
else
  bad "service ExecStart must be the absolute installed wrapper path"
fi
if grep -Eq '^Exec[A-Za-z]*=(?!/)' "$SERVICE_UNIT" 2>/dev/null \
  || grep -E '^Exec[A-Za-z]*=' "$SERVICE_UNIT" | grep -qv '=[-+!@]*/'; then
  bad "every Exec* must use an absolute path"
else
  ok "every Exec* uses an absolute path"
fi
has 'NoNewPrivileges=true' "$SERVICE_UNIT" \
  && ok "service sets NoNewPrivileges" || bad "service must set NoNewPrivileges"
has 'UMask=0077' "$SERVICE_UNIT" \
  && ok "service sets a restrictive UMask" || bad "service must set a restrictive UMask"

# PrivateTmp would give the unit a private /dev/shm... but more importantly the
# wrapper's tmpfs render must remain reachable, so assert the known-good choice.
if grep -q '^PrivateTmp=true' "$SERVICE_UNIT"; then
  bad "PrivateTmp=true breaks the /dev/shm ephemeral env render"
else
  ok "PrivateTmp is not enabled (tmpfs render stays reachable)"
fi

# --------------------------------------------------------------------------- #
# 3. Wrapper secret model                                                      #
# --------------------------------------------------------------------------- #
echo
echo "=== wrapper secret model ==="

has 'mktemp /dev/shm/parkio-invite-backup-' "$WRAPPER" \
  && ok "wrapper renders the env into /dev/shm" || bad "wrapper must render the env into /dev/shm"
has 'chmod 600' "$WRAPPER" \
  && ok "wrapper chmods the env 0600" || bad "wrapper must chmod the env 0600"
grep -q "stat -c '%a'" "$WRAPPER" && grep -q '!= "600"' "$WRAPPER" \
  && ok "wrapper verifies the env mode is 600" || bad "wrapper must verify the env mode"
has 'az login --identity' "$WRAPPER" \
  && ok "wrapper authenticates with the VM managed identity" || bad "wrapper must use managed identity"
has 'trap cleanup EXIT' "$WRAPPER" \
  && ok "wrapper cleans up on every exit path" || bad "wrapper must trap EXIT for cleanup"
grep -q "trap 'exit 143' HUP INT TERM" "$WRAPPER" \
  && ok "wrapper cleans up on signal termination" || bad "wrapper must handle HUP/INT/TERM"

if grep -Eq 'echo .*(PASSWORD|PASSPHRASE|SECRET|API_KEY|WEBHOOK)' "$WRAPPER"; then
  bad "wrapper must never echo a secret-shaped value"
else
  ok "wrapper echoes no secret-shaped value"
fi
# Secrets must reach the backup via the env FILE, never as argv (process listings).
if grep -Eq -- '--(password|passphrase|api-key|webhook)[ =]' "$WRAPPER"; then
  bad "wrapper must not pass secrets as command-line arguments"
else
  ok "wrapper passes no secret on the command line"
fi

# --------------------------------------------------------------------------- #
# 4. Fail-closed behaviour                                                     #
# --------------------------------------------------------------------------- #
echo
echo "=== fail-closed behaviour ==="

for condition in \
  "managed-identity login failed" \
  "could not materialize the production env from Key Vault"; do
  if grep -Fq "$condition" "$WRAPPER"; then
    ok "wrapper fails closed: ${condition}"
  else
    bad "wrapper must fail closed on: ${condition}"
  fi
done

# The wrapper must not swallow a backup failure into a success exit.
if grep -q 'exit "$status"' "$WRAPPER"; then
  ok "wrapper propagates backup failure status"
else
  bad "wrapper must propagate the backup exit status"
fi

# Missing-secret failure: render fails -> wrapper exits 3 and removes the env.
STUB_ROOT="$WORK/stubroot"
mkdir -p "$STUB_ROOT/scripts/azure" "$STUB_ROOT/scripts"
cp "$WRAPPER" "$STUB_ROOT/scripts/azure/invite-production-backup-run.sh"
chmod +x "$STUB_ROOT/scripts/azure/invite-production-backup-run.sh"
cat > "$STUB_ROOT/scripts/azure/render-invite-production-env.sh" <<'STUB'
#!/usr/bin/env sh
echo "ERROR: required Key Vault secret names are missing:" >&2
exit 3
STUB
chmod +x "$STUB_ROOT/scripts/azure/render-invite-production-env.sh"
printf '#!/usr/bin/env sh\nexit 0\n' > "$STUB_ROOT/scripts/backup-hosted-beta.sh"
chmod +x "$STUB_ROOT/scripts/backup-hosted-beta.sh"

FAKE_BIN="$WORK/bin"
mkdir -p "$FAKE_BIN"
printf '#!/usr/bin/env sh\nexit 0\n' > "$FAKE_BIN/az"
chmod +x "$FAKE_BIN/az"

shm_before="$(find /dev/shm -maxdepth 1 -name 'parkio-invite-backup-*' 2>/dev/null | wc -l)"
set +e
PATH="$FAKE_BIN:$PATH" "$STUB_ROOT/scripts/azure/invite-production-backup-run.sh" >"$WORK/missing.log" 2>&1
missing_status=$?
set -e
shm_after="$(find /dev/shm -maxdepth 1 -name 'parkio-invite-backup-*' 2>/dev/null | wc -l)"

if [ "$missing_status" -eq 3 ]; then
  ok "missing Key Vault secret fails closed (exit 3)"
else
  bad "missing Key Vault secret must fail closed with exit 3 (got $missing_status)"
fi
if [ "$shm_before" -eq "$shm_after" ]; then
  ok "ephemeral env removed after a failed render"
else
  bad "ephemeral env leaked into /dev/shm after a failed render"
fi

# Success path: env rendered, backup invoked, tmpfs left clean.
cat > "$STUB_ROOT/scripts/azure/render-invite-production-env.sh" <<'STUB'
#!/usr/bin/env sh
# $1 == --output, $2 == path
printf 'PARKIO_DEPLOYMENT_PROFILE=invite-production\n' > "$2"
chmod 600 "$2"
STUB
chmod +x "$STUB_ROOT/scripts/azure/render-invite-production-env.sh"
cat > "$STUB_ROOT/scripts/backup-hosted-beta.sh" <<'STUB'
#!/usr/bin/env sh
for arg in "$@"; do
  case "$arg" in
    /dev/shm/parkio-invite-backup-*.env) echo "env-file-ok" ;;
  esac
done
exit 0
STUB
chmod +x "$STUB_ROOT/scripts/backup-hosted-beta.sh"

set +e
PATH="$FAKE_BIN:$PATH" "$STUB_ROOT/scripts/azure/invite-production-backup-run.sh" >"$WORK/success.log" 2>&1
success_status=$?
set -e
shm_after="$(find /dev/shm -maxdepth 1 -name 'parkio-invite-backup-*' 2>/dev/null | wc -l)"

if [ "$success_status" -eq 0 ]; then
  ok "success path completes"
else
  bad "success path failed (status=$success_status): $(tail -3 "$WORK/success.log")"
fi
if grep -q 'env-file-ok' "$WORK/success.log"; then
  ok "backup receives the tmpfs env file"
else
  bad "backup must receive the tmpfs env file"
fi
if [ "$shm_before" -eq "$shm_after" ]; then
  ok "ephemeral env removed after a successful run"
else
  bad "ephemeral env leaked into /dev/shm after a successful run"
fi

# --------------------------------------------------------------------------- #
# 5. Installation: closure, versioning, idempotency, secret-safety             #
# --------------------------------------------------------------------------- #
echo
echo "=== installation ==="

PREFIX="$WORK/opt/parkio/invite-production"
UNITS="$WORK/etc/systemd/system"

"$INSTALLER" --dry-run --prefix "$PREFIX" --unit-dir "$UNITS" >"$WORK/install1.log" 2>&1 \
  && ok "first install succeeds" || bad "first install failed: $(tail -3 "$WORK/install1.log")"

for relative in \
  scripts/azure/invite-production-backup-run.sh \
  scripts/azure/render-invite-production-env.sh \
  scripts/azure/render-invite-production-env.py \
  scripts/backup-hosted-beta.sh \
  scripts/backup-databases.sh \
  scripts/backup-minio.sh \
  scripts/lib/backup-common.sh \
  scripts/lib/erasure-tombstones.sh \
  docker/.env.invite-production.example; do
  [ -f "$PREFIX/$relative" ] || bad "payload missing $relative"
done
ok "payload contains the full backup execution closure"

[ -x "$PREFIX/scripts/azure/invite-production-backup-run.sh" ] \
  && ok "wrapper is installed executable" || bad "wrapper must be installed executable"
[ -f "$PREFIX/VERSION" ] && grep -q '^gitSha=' "$PREFIX/VERSION" \
  && ok "payload records its source revision" || bad "payload must record gitSha in VERSION"
[ -f "$PREFIX/MANIFEST.sha256" ] \
  && ok "payload records checksums" || bad "payload must record checksums"
( cd "$PREFIX" && sha256sum --quiet --check MANIFEST.sha256 ) >/dev/null 2>&1 \
  && ok "installed checksums verify" || bad "installed checksums must verify"

# The whole point of the stable layout: no developer checkout, no repo sprawl.
if [ -e "$PREFIX/.git" ] || [ -d "$PREFIX/services" ] || [ -d "$PREFIX/frontend" ]; then
  bad "payload must not be an arbitrary checkout"
else
  ok "payload is not an arbitrary checkout"
fi
if [ -e "$PREFIX/docker/.env.invite-production" ]; then
  bad "payload must never contain a rendered production env"
else
  ok "payload contains no rendered production env"
fi
if grep -RIq -E 'BEGIN [A-Z ]*PRIVATE KEY|hooks\.slack\.com/services/[A-Za-z0-9]|^re_[A-Za-z0-9]{16}' "$PREFIX" 2>/dev/null; then
  bad "payload contains secret-shaped material"
else
  ok "payload contains no secret-shaped material"
fi

# Units: rendered for the prefix, and the timer is never auto-enabled.
[ -f "$UNITS/parkio-invite-backup.service" ] && [ -f "$UNITS/parkio-invite-backup.timer" ] \
  && ok "both units are installed" || bad "both units must be installed"
if grep -q 'DRY-RUN' "$WORK/install1.log" && ! grep -q 'enable --now' "$WORK/install1.log"; then
  ok "installation does not enable the timer by itself"
else
  bad "installation must not enable the timer as a side effect"
fi

# Idempotency: a second install must converge to identical content. VERSION is
# excluded because it records installedAt, which is expected to move; everything
# the scheduler actually executes must be byte-identical.
grep -v ' \./VERSION$' "$PREFIX/MANIFEST.sha256" > "$WORK/manifest-1.sha256"
STALE="$PREFIX/scripts/lib/stale-from-older-payload.sh"
echo "# left by a previous revision" > "$STALE"
"$INSTALLER" --dry-run --prefix "$PREFIX" --unit-dir "$UNITS" >"$WORK/install2.log" 2>&1 \
  && ok "second install succeeds (idempotent)" || bad "second install failed"
grep -v ' \./VERSION$' "$PREFIX/MANIFEST.sha256" > "$WORK/manifest-2.sha256"
if diff -q "$WORK/manifest-1.sha256" "$WORK/manifest-2.sha256" >/dev/null; then
  ok "re-install produces an identical payload"
else
  bad "re-install must produce an identical payload"
fi
if [ ! -e "$STALE" ]; then
  ok "upgrade removes files left by an older payload"
else
  bad "upgrade must not leave stale files from an older payload"
fi
if [ "$(find "$UNITS" -name 'parkio-invite-backup.timer' | wc -l)" -eq 1 ]; then
  ok "no duplicate timer unit"
else
  bad "installation must not duplicate the timer unit"
fi

# Failed install cleanup: a missing payload file must abort before touching the
# prefix, and must not leave a staging directory behind.
stage_before="$(find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'parkio-invite-scheduler-*' 2>/dev/null | wc -l)"
BROKEN="$WORK/broken"
mkdir -p "$BROKEN/scripts/azure" "$BROKEN/scripts/lib" "$BROKEN/docker" "$BROKEN/infra/systemd"
cp "$INSTALLER" "$BROKEN/scripts/azure/"
set +e
"$BROKEN/scripts/azure/install-invite-production-backup-scheduler.sh" \
  --dry-run --prefix "$WORK/broken-prefix" --unit-dir "$WORK/broken-units" >"$WORK/broken.log" 2>&1
broken_status=$?
set -e
stage_after="$(find "${TMPDIR:-/tmp}" -maxdepth 1 -name 'parkio-invite-scheduler-*' 2>/dev/null | wc -l)"
if [ "$broken_status" -ne 0 ]; then
  ok "install aborts when the payload closure is incomplete"
else
  bad "install must abort when a payload file is missing"
fi
if [ ! -d "$WORK/broken-prefix" ]; then
  ok "failed install leaves no partial prefix"
else
  bad "failed install must not leave a partial prefix"
fi
if [ "$stage_before" -eq "$stage_after" ]; then
  ok "failed install removes its staging directory"
else
  bad "failed install leaked a staging directory"
fi

# --------------------------------------------------------------------------- #
# 6. Production-mode invariants survive the scheduler path                     #
# --------------------------------------------------------------------------- #
echo
echo "=== production-mode invariants ==="

# shellcheck source=lib/backup-common.sh
source "$ROOT/scripts/lib/backup-common.sh"

if [ "${#PARKIO_DB_SERVICES[@]}" -eq 10 ]; then
  ok "backup covers ten logical databases"
else
  bad "backup must cover ten logical databases (found ${#PARKIO_DB_SERVICES[@]})"
fi
for expected in auth gateway user parking media gamification notification moderation analytics ai-validation; do
  if printf '%s\n' "${PARKIO_DB_SERVICES[@]}" | grep -q "^${expected}:"; then
    :
  else
    bad "backup is missing the ${expected} database"
  fi
done
ok "all ten expected database names are present"

# Fail-closed production mode: encryption and offsite are both mandatory.
(
  set +e
  BACKUP_PRODUCTION_MODE=1 BACKUP_ENCRYPT_PASSPHRASE="" \
    BACKUP_AZURE_STORAGE_ACCOUNT=acct BACKUP_AZURE_CONTAINER=cont \
    bash -c "source '$ROOT/scripts/lib/backup-common.sh'; parkio_backup_preflight" >/dev/null 2>&1
  test $? -ne 0
) && ok "production mode requires encryption" || bad "production mode must require encryption"

(
  set +e
  BACKUP_PRODUCTION_MODE=1 BACKUP_ENCRYPT_PASSPHRASE=pass \
    BACKUP_AZURE_STORAGE_ACCOUNT="" BACKUP_AZURE_CONTAINER="" BACKUP_MC_DEST="" BACKUP_OFFSITE_KIND="" \
    bash -c "source '$ROOT/scripts/lib/backup-common.sh'; parkio_backup_preflight" >/dev/null 2>&1
  test $? -ne 0
) && ok "production mode requires offsite" || bad "production mode must require offsite"

# The invite-production env template must keep production mode on.
grep -q '^BACKUP_PRODUCTION_MODE=1$' docker/.env.invite-production.example \
  && ok "invite-production template pins BACKUP_PRODUCTION_MODE=1" \
  || bad "invite-production template must pin BACKUP_PRODUCTION_MODE=1"

# PRIV-001: the erasure ledger must still be exported beside the dumps.
grep -q 'parkio_export_erasure_tombstones' scripts/backup-databases.sh \
  && ok "backup exports the PRIV-001 erasure tombstone ledger" \
  || bad "backup must export the PRIV-001 erasure tombstone ledger"
grep -q 'parkio_replay_erasure_tombstones' scripts/restore-drill.sh \
  && ok "restore replays the erasure tombstone ledger" \
  || bad "restore must replay the erasure tombstone ledger"

# Installed payload must carry the ledger helper, or a scheduled backup would
# silently produce artifacts with no PRIV-001 ledger.
[ -f "$PREFIX/scripts/lib/erasure-tombstones.sh" ] \
  && ok "payload ships the erasure ledger helper" \
  || bad "payload must ship the erasure ledger helper"

echo
echo "=== invite backup scheduler tests: pass=$pass fail=$fail ==="
if [ "$fail" -ne 0 ]; then
  exit 1
fi
