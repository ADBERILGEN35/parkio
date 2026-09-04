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

tree_fingerprint() {
  local root="$1"
  {
    find "$root" -mindepth 1 -printf '%P|%y|%m|%l\n' | LC_ALL=C sort
    find "$root" -type f -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum
  } | sha256sum | awk '{print $1}'
}

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

if grep -Eq '^ExecStart=/opt/parkio/invite-production-backup/scripts/azure/invite-production-backup-run\.sh' "$SERVICE_UNIT"; then
  ok "service ExecStart is an absolute stable path"
else
  bad "service ExecStart must use the dedicated scheduler payload"
fi
if grep -Eq '/opt/parkio/invite-production/(current|releases|acceptance)(/|$)' "$SERVICE_UNIT"; then
  bad "service must not reference a runtime-owned path"
else
  ok "service references no runtime-owned path"
fi
if grep -Eq '/_work(/|$)' "$SERVICE_UNIT"; then
  bad "service must not reference Actions _work"
else
  ok "service references no Actions _work path"
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

RUNTIME_ROOT="$WORK/opt/parkio/invite-production"
PREFIX="$WORK/opt/parkio/invite-production-backup"
UNITS="$WORK/etc/systemd/system"
BACKUP_DATA="$WORK/var/backups/parkio"

has 'DEFAULT_PREFIX="/opt/parkio/invite-production-backup"' "$INSTALLER" \
  && ok "installer defaults to the dedicated payload root" \
  || bad "installer must default to /opt/parkio/invite-production-backup"
if grep -Fq '/opt/parkio/invite-production-backup' "$ROOT/scripts/deploy-invite-production.sh" \
  && ! grep -Eq 'artifacts-safe\.py.* /opt/parkio/invite-production$' "$ROOT/scripts/deploy-invite-production.sh"; then
  ok "deploy integration scans the dedicated scheduler payload"
else
  bad "deploy integration must use the dedicated scheduler payload"
fi

# Model the certified live layout exactly. The non-dry-run invocation is safe in
# this fixture because systemctl/id are stubbed. Reverting only the installer to
# the pre-R10A implementation makes this regression fail: that implementation
# exits zero after replacing RUNTIME_ROOT with the scheduler payload.
mkdir -p "$RUNTIME_ROOT/releases/70f7ca8" "$RUNTIME_ROOT/acceptance" "$BACKUP_DATA/certified-success"
printf 'runtime-release\n' > "$RUNTIME_ROOT/releases/70f7ca8/runtime-marker"
printf 'acceptance-state\n' > "$RUNTIME_ROOT/acceptance/acceptance-marker"
printf 'successful-backup\n' > "$BACKUP_DATA/certified-success/COMPLETE"
ln -s releases/70f7ca8 "$RUNTIME_ROOT/current"
runtime_before="$(tree_fingerprint "$RUNTIME_ROOT")"
backup_before="$(tree_fingerprint "$BACKUP_DATA")"

id() { if [ "${1:-}" = "-u" ]; then printf '0\n'; else command id "$@"; fi; }
systemctl() { return 0; }
export -f id systemctl

set +e
"$INSTALLER" --prefix "$RUNTIME_ROOT" --unit-dir "$UNITS" >"$WORK/runtime-root-override.log" 2>&1
runtime_override_status=$?
set -e
if [ "$runtime_override_status" -ne 0 ] \
  && [ -L "$RUNTIME_ROOT/current" ] \
  && [ -f "$RUNTIME_ROOT/releases/70f7ca8/runtime-marker" ] \
  && [ -f "$RUNTIME_ROOT/acceptance/acceptance-marker" ] \
  && [ "$(tree_fingerprint "$RUNTIME_ROOT")" = "$runtime_before" ]; then
  ok "real installer refuses the live runtime-root layout without changing it"
else
  bad "real installer must fail closed before replacing current/releases/acceptance"
fi

# Destination guard matrix. These calls must fail before staging or copying.
for unsafe in \
  "" \
  "relative/invite-production-backup" \
  "$WORK/opt/parkio/backup/../invite-production-backup" \
  "$WORK/_work/repo/invite-production-backup" \
  "/opt/parkio/invite-production" \
  "/opt/parkio/invite-production/releases/candidate" \
  "/var/backups/parkio" \
  "/dev/shm/parkio-invite-production-backup" \
  "/" \
  "/opt/parkio"; do
  set +e
  "$INSTALLER" --dry-run --prefix "$unsafe" --unit-dir "$UNITS" >"$WORK/unsafe.log" 2>&1
  unsafe_status=$?
  set -e
  if [ "$unsafe_status" -ne 0 ]; then
    ok "unsafe destination rejected: $unsafe"
  else
    bad "unsafe destination must be rejected: $unsafe"
  fi
done

mkdir -p "$WORK/symlink-target"
ln -s "$WORK/symlink-target" "$WORK/symlink-prefix"
set +e
"$INSTALLER" --dry-run --prefix "$WORK/symlink-prefix" --unit-dir "$UNITS" >"$WORK/symlink.log" 2>&1
symlink_status=$?
set -e
if [ "$symlink_status" -ne 0 ]; then
  ok "symlinked destination is rejected"
else
  bad "symlinked destination must be rejected"
fi

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
if [ "$(stat -c '%a' "$PREFIX")" = "750" ]; then
  ok "payload root is installed mode 0750"
else
  bad "payload root must be mode 0750"
fi
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
if grep -RIq -E '(^|/)_work(/|$)' "$PREFIX" 2>/dev/null; then
  bad "payload must not contain an Actions _work reference"
else
  ok "payload contains no Actions _work reference"
fi

# Units: rendered for the prefix, and the timer is never auto-enabled.
[ -f "$UNITS/parkio-invite-backup.service" ] && [ -f "$UNITS/parkio-invite-backup.timer" ] \
  && ok "both units are installed" || bad "both units must be installed"
if grep -q 'DRY-RUN' "$WORK/install1.log" && ! grep -q 'enable --now' "$WORK/install1.log"; then
  ok "installation does not enable the timer by itself"
else
  bad "installation must not enable the timer as a side effect"
fi
if grep -Fq "$PREFIX/scripts/azure/invite-production-backup-run.sh" "$UNITS/parkio-invite-backup.service" \
  && ! grep -Eq '/_work(/|$)|/invite-production/(current|releases|acceptance)(/|$)' "$UNITS/parkio-invite-backup.service"; then
  ok "rendered service points only to the dedicated payload"
else
  bad "rendered service must point only to the dedicated payload"
fi

runtime_after_first="$(tree_fingerprint "$RUNTIME_ROOT")"
[ "$runtime_after_first" = "$runtime_before" ] \
  && ok "dedicated first install leaves runtime root byte-for-byte unchanged" \
  || bad "dedicated first install changed the runtime root"
[ "$(tree_fingerprint "$BACKUP_DATA")" = "$backup_before" ] \
  && ok "dedicated first install preserves successful backups" \
  || bad "dedicated first install changed backup data"

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

# Upgrade a recognized SHA-A payload to the candidate SHA B. VERSION is changed
# together with its manifest entry so the old payload remains valid before swap.
sed -i 's/^gitSha=.*/gitSha=fixture-sha-a/' "$PREFIX/VERSION"
(cd "$PREFIX" && find . -type f ! -name MANIFEST.sha256 -print0 \
  | sort -z | xargs -0 sha256sum > MANIFEST.sha256)
"$INSTALLER" --dry-run --prefix "$PREFIX" --unit-dir "$UNITS" >"$WORK/upgrade.log" 2>&1 \
  && ok "upgrade from payload SHA A to candidate SHA B succeeds" \
  || bad "payload SHA upgrade failed"
if grep -q '^gitSha=fixture-sha-a$' "$PREFIX/VERSION"; then
  bad "upgrade must replace the old payload revision"
else
  ok "upgrade replaces only the old scheduler payload revision"
fi

# Failed install cleanup: a missing payload file must abort before touching the
# prefix, and must not leave a staging directory behind.
stage_before="$(find "$(dirname "$PREFIX")" -maxdepth 1 -name ".$(basename "$PREFIX").stage.*" 2>/dev/null | wc -l)"
BROKEN="$WORK/broken"
mkdir -p "$BROKEN/scripts/azure" "$BROKEN/scripts/lib" "$BROKEN/docker" "$BROKEN/infra/systemd"
cp "$INSTALLER" "$BROKEN/scripts/azure/"
# Model a valid SHA-A payload and prove a broken B never changes it.
sed -i 's/^gitSha=.*/gitSha=fixture-sha-a/' "$PREFIX/VERSION"
(cd "$PREFIX" && find . -type f ! -name MANIFEST.sha256 -print0 \
  | sort -z | xargs -0 sha256sum > MANIFEST.sha256)
payload_a_before="$(tree_fingerprint "$PREFIX")"
set +e
"$BROKEN/scripts/azure/install-invite-production-backup-scheduler.sh" \
  --dry-run --prefix "$PREFIX" --unit-dir "$UNITS" >"$WORK/broken.log" 2>&1
broken_status=$?
set -e
stage_after="$(find "$(dirname "$PREFIX")" -maxdepth 1 -name ".$(basename "$PREFIX").stage.*" 2>/dev/null | wc -l)"
if [ "$broken_status" -ne 0 ]; then
  ok "install aborts when the payload closure is incomplete"
else
  bad "install must abort when a payload file is missing"
fi
if (cd "$PREFIX" && sha256sum --quiet --check MANIFEST.sha256) >/dev/null 2>&1 \
  && [ "$(tree_fingerprint "$PREFIX")" = "$payload_a_before" ] \
  && grep -q '^gitSha=fixture-sha-a$' "$PREFIX/VERSION"; then
  ok "failed SHA-B upgrade preserves the old valid SHA-A payload"
else
  bad "failed SHA-B upgrade must preserve the old valid SHA-A payload"
fi
if [ "$stage_before" -eq "$stage_after" ]; then
  ok "failed install removes its staging directory"
else
  bad "failed install leaked a staging directory"
fi

# --disable may contact only systemd. It must preserve the dedicated payload,
# runtime release tree, and successful backup artifacts.
payload_before_disable="$(tree_fingerprint "$PREFIX")"
"$INSTALLER" --disable --prefix "$PREFIX" --unit-dir "$UNITS" >"$WORK/disable.log" 2>&1 \
  && ok "disable path succeeds with stubbed systemd" || bad "disable path failed"
[ "$(tree_fingerprint "$PREFIX")" = "$payload_before_disable" ] \
  && ok "disable preserves scheduler payload" || bad "disable changed scheduler payload"
[ "$(tree_fingerprint "$RUNTIME_ROOT")" = "$runtime_before" ] \
  && ok "disable preserves runtime current/releases/acceptance" || bad "disable changed runtime root"
[ "$(tree_fingerprint "$BACKUP_DATA")" = "$backup_before" ] \
  && ok "disable preserves successful backup data" || bad "disable changed backup data"

if [ "$(find "$(dirname "$PREFIX")" -maxdepth 1 -name ".$(basename "$PREFIX").stage.*" | wc -l)" -eq 0 ] \
  && [ ! -e "${PREFIX}.previous" ]; then
  ok "installation leaves no stale stage or previous payload"
else
  bad "installation left stale stage/previous payload"
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
