#!/usr/bin/env bash
# N-02 real-GCS acceptance (#483): the exact, owner-approved cloud operation around the harness.
#
# Every command works only on the approved names below, only through the operation's own gcloud
# configuration directory, and passes --project explicitly; nothing reads or changes the operator's
# default gcloud configuration or Application Default Credentials. State (project number, lien
# baseline, IAM policies, run records) is kept outside the repository in N02_STATE_DIR.
#
#   preflight        read-only: tools, credential isolation, account, billing accounts, project ID
#   provision        create the project, link billing, enable APIs, buckets, service accounts,
#                    custom roles and bindings; records identities and the lien baseline
#   verify           read-only: the provisioned configuration and IAM policies match the approval
#   run              one harness attempt from a clean checkout of N02_APPROVED_SHA (at most two)
#   cleanup-status   read-only: earliest cleanup time and what still exists
#   cleanup          after the retain-until: delete objects, buckets, service accounts, roles, the
#                    Object Retention Lock lien and the project, each after identity checks
#   forget-credentials  delete the operation's own gcloud configuration directory (no revocation)
#
# Destructive commands need N02_CONFIRM: provision "provision-<project>", cleanup
# "cleanup-<project>-<project number>". No command revokes OAuth tokens: Google revokes the whole
# grant of a client, which would also sign the account out of gcloud/ADC on other machines.
set -euo pipefail

readonly PROJECT_ID="parkio-n02-accept-5c2c7f"
readonly PROJECT_NAME="parkio-n02-accept"
readonly REGION="europe-west4"
readonly BUCKET="parkio-n02-orl-accept-20261022-5c2c7f"
readonly CONTROL_BUCKET="parkio-n02-noorl-ctrl-20261022-5c2c7f"
readonly LABELS="parkio-purpose=n02-gcs-acceptance,parkio-data=synthetic,parkio-issue=483"
readonly APIS="storage.googleapis.com iam.googleapis.com iamcredentials.googleapis.com cloudresourcemanager.googleapis.com"
readonly ACCOUNTS="n02-writer n02-writer-nodelete n02-reader n02-probe-admin"
readonly ROLE_IDS="n02EvidenceWriter n02EvidenceWriterNoDelete n02EvidenceReader n02RetentionProbeAdmin n02BucketMetadataReader n02TokenMinter"
readonly MAX_ATTEMPTS=2
readonly IAM_SETTLE_SECONDS=420
readonly CLEANUP_MARGIN_SECONDS=300
# Upper bound of any retain-until a run can request: the 45 min wall-clock cap plus 2 h retention.
readonly RUN_RETENTION_BOUND_SECONDS=$(( 45 * 60 + 2 * 3600 ))

role_permissions() {
  case "$1" in
    n02EvidenceWriter) echo "storage.buckets.get,storage.objects.create,storage.objects.delete,storage.objects.get,storage.objects.list,storage.objects.setRetention" ;;
    n02EvidenceWriterNoDelete) echo "storage.buckets.get,storage.objects.create,storage.objects.get,storage.objects.list,storage.objects.setRetention" ;;
    n02EvidenceReader) echo "storage.buckets.get,storage.objects.get,storage.objects.list" ;;
    n02RetentionProbeAdmin) echo "storage.buckets.get,storage.objects.delete,storage.objects.overrideUnlockedRetention,storage.objects.setRetention,storage.objects.update" ;;
    n02BucketMetadataReader) echo "storage.buckets.get" ;;
    n02TokenMinter) echo "iam.serviceAccounts.getAccessToken" ;;
    *) die "unknown role $1" ;;
  esac
}

# Bucket bindings: bucket account role.
bucket_bindings() {
  cat <<EOF
${BUCKET} n02-writer n02EvidenceWriter
${BUCKET} n02-writer-nodelete n02EvidenceWriterNoDelete
${BUCKET} n02-reader n02EvidenceReader
${BUCKET} n02-probe-admin n02RetentionProbeAdmin
${CONTROL_BUCKET} n02-reader n02BucketMetadataReader
EOF
}

GCLOUD="${N02_GCLOUD:-gcloud}"
STATE="${N02_STATE_DIR:-${HOME}/parkio-n02-483/state}"
CONFIG_DIR="${HOME}/.config/gcloud-parkio-n02-483"

die() { echo "STOP: $*" >&2; exit 1; }
say() { echo "== $*"; }
sa_email() { echo "$1@${PROJECT_ID}.iam.gserviceaccount.com"; }

# Reads one JSON value: json FILE EXPR, where EXPR is a Python expression over `d`.
json() {
  python3 -I -c 'import json,sys
d=json.load(open(sys.argv[1], encoding="utf-8"))
v=eval(sys.argv[2], {"__builtins__": {"len": len, "sorted": sorted, "set": set, "list": list, "str": str}}, {"d": d})
print(json.dumps(v) if isinstance(v, (dict, list, bool)) or v is None else v)' "$1" "$2"
}

# The latest retainUntilTime anywhere in a JSON listing, as epoch seconds (0 when none).
latest_retain_until() {
  python3 -I -c 'import json,sys,datetime
found=[0]
def walk(v):
    if isinstance(v, dict):
        for k, x in v.items():
            if k in ("retainUntilTime", "retain_until_time") and isinstance(x, str):
                found.append(int(datetime.datetime.fromisoformat(x.replace("Z", "+00:00")).timestamp()))
            walk(x)
    elif isinstance(v, list):
        for x in v:
            walk(x)
walk(json.load(open(sys.argv[1], encoding="utf-8")))
print(max(found))' "$1"
}

g() { "${GCLOUD}" "$@"; }

# Every command runs in the operation's own configuration; inherited settings are refused, not reused.
require_isolation() {
  [ "${CLOUDSDK_CONFIG:-}" = "${CONFIG_DIR}" ] || die "CLOUDSDK_CONFIG must be ${CONFIG_DIR}"
  [ -d "${CONFIG_DIR}" ] || die "${CONFIG_DIR} does not exist"
  local variable
  for variable in GOOGLE_APPLICATION_CREDENTIALS GOOGLE_CLOUD_PROJECT GOOGLE_CLOUD_QUOTA_PROJECT CLOUDSDK_CORE_PROJECT \
      CLOUDSDK_CORE_ACCOUNT CLOUDSDK_ACTIVE_CONFIG_NAME CLOUDSDK_BILLING_QUOTA_PROJECT GOOGLE_SDK_JAVA_LOGGING; do
    [ -z "${!variable:-}" ] || die "${variable} must be unset"
  done
  command -v python3 >/dev/null || die "python3 is required"
  g version --format=json > /dev/null || die "${GCLOUD} does not run"
  local project
  project="$(g config list --format=json | json /dev/stdin 'd.get("core", {}).get("project", "")')"
  [ -z "${project}" ] || die "the operation's configuration has core/project=${project}; it must stay unset"
}

active_account() {
  local account
  account="$(g config list --format=json | json /dev/stdin 'd.get("core", {}).get("account", "")')"
  [ -n "${account}" ] || die "no account is logged in to ${CONFIG_DIR} (gcloud auth login --no-launch-browser)"
  echo "${account}"
}

state_file() { echo "${STATE}/$1"; }
recorded() { cat "$(state_file "$1")" 2>/dev/null || true; }
record() { mkdir -p "${STATE}"; printf '%s\n' "$2" > "$(state_file "$1")"; }

describe_project() { g projects describe "${PROJECT_ID}" --format=json; }

# The project is the one provisioned here: same ID, number and creation time, ACTIVE, labelled.
require_project_identity() {
  local number created file
  number="$(recorded project-number)"
  created="$(recorded project-create-time)"
  [ -n "${number}" ] && [ -n "${created}" ] || die "no provisioning record in ${STATE}"
  file="$(mktemp)"
  describe_project > "${file}" || die "cannot describe ${PROJECT_ID}"
  [ "$(json "${file}" 'd["projectId"]')" = "${PROJECT_ID}" ] || die "project ID differs"
  [ "$(json "${file}" 'd["projectNumber"]')" = "${number}" ] || die "project number differs from the recorded ${number}"
  [ "$(json "${file}" 'd.get("createTime", "")')" = "${created}" ] || die "project creation time differs"
  [ "$(json "${file}" 'd.get("lifecycleState", "")')" = "ACTIVE" ] || die "project is not ACTIVE"
  [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] || die "project label parkio-purpose missing"
  [ "$(json "${file}" 'd.get("labels", {}).get("parkio-issue", "")')" = "483" ] || die "project label parkio-issue missing"
  rm -f "${file}"
}

cmd_preflight() {
  require_isolation
  local account file
  account="$(active_account)"
  say "account ${account} in ${CONFIG_DIR}"
  file="$(mktemp)"
  g billing accounts list --filter=open=true --format=json > "${file}"
  say "open billing accounts visible to the account: $(json "${file}" 'len(d)')"
  if g projects describe "${PROJECT_ID}" --format=json > /dev/null 2>&1; then
    say "project ${PROJECT_ID} already exists and is visible"
  else
    say "project ${PROJECT_ID} is not visible to the account (not created, or not accessible)"
  fi
  rm -f "${file}"
}

cmd_provision() {
  require_isolation
  [ "${N02_CONFIRM:-}" = "provision-${PROJECT_ID}" ] || die "N02_CONFIRM must be provision-${PROJECT_ID}"
  local billing="${N02_BILLING_ACCOUNT:-}"
  [[ "${billing}" =~ ^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$ ]] || die "N02_BILLING_ACCOUNT must look like XXXXXX-XXXXXX-XXXXXX"
  [ -z "$(recorded project-number)" ] || die "${STATE} already records a provisioned project"
  local account file
  account="$(active_account)"
  file="$(mktemp)"
  g billing accounts describe "${billing}" --format=json > "${file}"
  [ "$(json "${file}" 'd.get("open", False)')" = "true" ] || die "billing account ${billing} is not open"
  if g projects describe "${PROJECT_ID}" --format=json > /dev/null 2>&1; then
    die "${PROJECT_ID} already exists; nothing was changed"
  fi

  say "project"
  g projects create "${PROJECT_ID}" --name="${PROJECT_NAME}" --labels="parkio-purpose=n02-gcs-acceptance,parkio-issue=483" \
    --no-enable-cloud-apis
  describe_project > "${file}"
  record project-number "$(json "${file}" 'd["projectNumber"]')"
  record project-create-time "$(json "${file}" 'd["createTime"]')"
  record operator "${account}"
  record billing-account "${billing}"
  require_project_identity

  say "billing"
  g billing projects link "${PROJECT_ID}" --billing-account="${billing}"
  g billing projects describe "${PROJECT_ID}" --format=json > "${file}"
  [ "$(json "${file}" 'd.get("billingEnabled", False)')" = "true" ] || die "billing is not enabled"
  [ "$(json "${file}" 'd.get("billingAccountName", "")')" = "billingAccounts/${billing}" ] || die "billing account differs"

  say "APIs"
  # shellcheck disable=SC2086
  g services enable ${APIS} --project="${PROJECT_ID}"

  say "buckets"
  g storage buckets create "gs://${BUCKET}" --project="${PROJECT_ID}" --location="${REGION}" \
    --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention \
    --soft-delete-duration=0 --enable-per-object-retention
  sleep "${N02_BUCKET_SPACING_SECONDS:-3}"
  g storage buckets create "gs://${CONTROL_BUCKET}" --project="${PROJECT_ID}" --location="${REGION}" \
    --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention \
    --soft-delete-duration=0
  g storage buckets update "gs://${BUCKET}" --versioning --update-labels="${LABELS}"
  g storage buckets update "gs://${CONTROL_BUCKET}" --versioning --update-labels="${LABELS}"
  g alpha resource-manager liens list --project="${PROJECT_ID}" --format=json > "$(state_file liens-after-orl.json)"

  say "service accounts (no keys)"
  local name role bucket
  for name in ${ACCOUNTS}; do
    g iam service-accounts create "${name}" --project="${PROJECT_ID}" --display-name="N-02 #483 ${name} (no keys)"
  done
  for name in ${ACCOUNTS}; do
    local tries=0
    until g iam service-accounts describe "$(sa_email "${name}")" --project="${PROJECT_ID}" --format=json > /dev/null 2>&1; do
      tries=$((tries + 1)); [ "${tries}" -le 30 ] || die "service account ${name} did not appear"
      sleep "${N02_POLL_SECONDS:-10}"
    done
  done

  say "custom roles"
  for role in ${ROLE_IDS}; do
    g iam roles create "${role}" --project="${PROJECT_ID}" --title="N-02 #483 ${role}" --stage=GA \
      --permissions="$(role_permissions "${role}")"
  done

  say "bindings"
  while read -r bucket name role; do
    g storage buckets add-iam-policy-binding "gs://${bucket}" --member="serviceAccount:$(sa_email "${name}")" \
      --role="projects/${PROJECT_ID}/roles/${role}"
  done < <(bucket_bindings)
  for name in ${ACCOUNTS}; do
    g iam service-accounts add-iam-policy-binding "$(sa_email "${name}")" --project="${PROJECT_ID}" \
      --member="user:${account}" --role="projects/${PROJECT_ID}/roles/n02TokenMinter"
  done
  record iam-bound-at "$(date -u +%s)"
  rm -f "${file}"
  cmd_verify
  say "provisioned; wait ${IAM_SETTLE_SECONDS} s for IAM propagation, then log in ADC and use: run"
}

cmd_verify() {
  require_isolation
  require_project_identity
  local file number bucket name role
  number="$(recorded project-number)"
  file="$(mktemp)"
  for bucket in "${BUCKET}" "${CONTROL_BUCKET}"; do
    g storage buckets describe "gs://${bucket}" --raw --format=json > "${file}"
    [ "$(json "${file}" 'str(d.get("projectNumber", ""))')" = "${number}" ] || die "${bucket} is in another project"
    [ "$(json "${file}" 'd.get("location", "").lower()')" = "${REGION}" ] || die "${bucket} location differs"
    [ "$(json "${file}" 'd.get("storageClass", "")')" = "STANDARD" ] || die "${bucket} storage class differs"
    [ "$(json "${file}" 'd.get("versioning", {}).get("enabled", False)')" = "true" ] || die "${bucket} versioning is off"
    [ "$(json "${file}" 'str(d.get("softDeletePolicy", {}).get("retentionDurationSeconds", "unset"))')" = "0" ] || die "${bucket} soft delete is not off"
    [ "$(json "${file}" 'd.get("iamConfiguration", {}).get("uniformBucketLevelAccess", {}).get("enabled", False)')" = "true" ] || die "${bucket} UBLA is off"
    [ "$(json "${file}" 'd.get("iamConfiguration", {}).get("publicAccessPrevention", "")')" = "enforced" ] || die "${bucket} public access prevention is not enforced"
    [ "$(json "${file}" '"retentionPolicy" not in d')" = "true" ] || die "${bucket} has a bucket retention policy (Bucket Lock is out of scope)"
    [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] || die "${bucket} labels differ"
    g storage buckets get-iam-policy "gs://${bucket}" --format=json > "$(state_file "iam-${bucket}.json")"
  done
  g storage buckets describe "gs://${BUCKET}" --raw --format=json > "${file}"
  [ "$(json "${file}" 'str(d.get("objectRetention", {}).get("mode", ""))')" = "Enabled" ] || die "${BUCKET} lacks Object Retention Lock"
  g storage buckets describe "gs://${CONTROL_BUCKET}" --raw --format=json > "${file}"
  [ "$(json "${file}" 'str(d.get("objectRetention", {}).get("mode", ""))')" != "Enabled" ] || die "${CONTROL_BUCKET} must not have Object Retention Lock"
  while read -r bucket name role; do
    [ "$(json "$(state_file "iam-${bucket}.json")" "sorted(b['role'] for b in d.get('bindings', []) if 'serviceAccount:$(sa_email "${name}")' in b.get('members', []))")" = "[\"projects/${PROJECT_ID}/roles/${role}\"]" ] \
      || die "binding of ${name} on ${bucket} differs"
  done < <(bucket_bindings)
  for name in ${ACCOUNTS}; do
    g iam service-accounts get-iam-policy "$(sa_email "${name}")" --project="${PROJECT_ID}" --format=json > "$(state_file "iam-sa-${name}.json")"
    [ "$(json "$(state_file "iam-sa-${name}.json")" "sorted(b['role'] + ' ' + m for b in d.get('bindings', []) for m in b.get('members', []))")" = "[\"projects/${PROJECT_ID}/roles/n02TokenMinter user:$(recorded operator)\"]" ] \
      || die "${name} grants more than token minting to the operator"
    g iam service-accounts keys list --iam-account="$(sa_email "${name}")" --project="${PROJECT_ID}" --managed-by=user --format=json > "${file}"
    [ "$(json "${file}" 'len(d)')" = "0" ] || die "${name} has a user-managed key"
  done
  for role in ${ROLE_IDS}; do
    g iam roles describe "${role}" --project="${PROJECT_ID}" --format=json > "${file}"
    [ "$(json "${file}" "','.join(sorted(d.get('includedPermissions', [])))")" = "$(role_permissions "${role}")" ] || die "${role} permissions differ"
  done
  rm -f "${file}"
  say "verified: ${PROJECT_ID} (${number}), gs://${BUCKET}, gs://${CONTROL_BUCKET}, ${ROLE_IDS// /, }"
}

cmd_run() {
  require_isolation
  require_project_identity
  local sha="${N02_APPROVED_SHA:-}" root attempts n out adc bound rc stamp
  [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "N02_APPROVED_SHA must be the approved full commit SHA"
  root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
  [ "$(git -C "${root}" rev-parse HEAD)" = "${sha}" ] || die "checkout is not ${sha}"
  bound="$(recorded iam-bound-at)"
  [ -n "${bound}" ] && [ $(( $(date -u +%s) - bound )) -ge "${IAM_SETTLE_SECONDS}" ] || die "IAM bindings are younger than ${IAM_SETTLE_SECONDS} s"
  adc="${CONFIG_DIR}/application_default_credentials.json"
  [ -f "${adc}" ] || die "no ADC in ${CONFIG_DIR} (gcloud auth application-default login --no-launch-browser)"
  [ "$(json "${adc}" 'd.get("type", "")')" = "authorized_user" ] || die "ADC must be a user login"
  [ "$(json "${adc}" 'd.get("quota_project_id", "")')" = "${PROJECT_ID}" ] || die "ADC quota project must be ${PROJECT_ID}"
  mkdir -p "${STATE}/runs"
  attempts="$(find "${STATE}/runs" -mindepth 1 -maxdepth 1 -type d -name 'attempt-*' | wc -l)"
  [ "${attempts}" -lt "${MAX_ATTEMPTS}" ] || die "the approved ${MAX_ATTEMPTS} attempts are used"
  [ "$(find "${STATE}/runs" -mindepth 1 -maxdepth 1 -name 'preflight-only-*.started' | wc -l)" -lt 3 ] \
    || die "three attempts stopped in the preflight; report before trying again"
  n=$((attempts + 1))
  out="${STATE}/runs/attempt-${n}"
  record "runs/attempt-${n}.started" "$(date -u +%s)"
  (
    export N02_GCS_PROJECT="${PROJECT_ID}" N02_GCS_PROJECT_NUMBER="$(recorded project-number)" \
      N02_GCS_LOCATION="${REGION}" N02_GCS_BUCKET="${BUCKET}" N02_GCS_CONTROL_BUCKET="${CONTROL_BUCKET}" \
      N02_GCS_CONFIRM="${BUCKET}" N02_GCS_WRITER_SA="$(sa_email n02-writer)" \
      N02_GCS_WRITER_NODELETE_SA="$(sa_email n02-writer-nodelete)" N02_GCS_READER_SA="$(sa_email n02-reader)" \
      N02_GCS_PROBE_ADMIN_SA="$(sa_email n02-probe-admin)"
    cd "${root}"
    "${N02_GRADLE:-./gradlew}" --no-daemon --max-workers=1 :services:auth-service:gcsProviderAcceptance --args="run ${out}"
  ) && rc=0 || rc=$?
  # An attempt that stopped in the preflight wrote nothing; it does not use one of the two attempts.
  if [ ! -f "${out}/journal.jsonl" ] || ! grep -q '"event":"write-requested"' "${out}/journal.jsonl"; then
    stamp="$(date -u +%Y%m%dT%H%M%SZ)-${n}"
    if [ -d "${out}" ]; then
      mv "${out}" "${STATE}/runs/preflight-only-${stamp}"
    fi
    mv "$(state_file "runs/attempt-${n}.started")" "${STATE}/runs/preflight-only-${stamp}.started"
    die "attempt stopped before any write (exit ${rc}); it does not count"
  fi
  say "attempt ${n} finished with exit ${rc}; result in ${out}"
  return "${rc}"
}

# Earliest full cleanup: the latest retain-until any attempt may have requested, plus a clock margin.
earliest_cleanup() {
  local latest=0 started value dir
  for started in "${STATE}"/runs/attempt-*.started; do
    [ -f "${started}" ] || continue
    dir="${started%.started}"
    if [ -f "${dir}/result.json" ]; then
      value="$(json "${dir}/result.json" 'd["budget"]["latestRetainUntil"] or ""')"
      if [ -n "${value}" ]; then
        value="$(date -u -d "${value}" +%s)"
      else
        value=$(( $(cat "${started}") + RUN_RETENTION_BOUND_SECONDS ))
      fi
    else
      value=$(( $(cat "${started}") + RUN_RETENTION_BOUND_SECONDS ))
    fi
    [ "${value}" -le "${latest}" ] || latest="${value}"
  done
  echo $(( latest + CLEANUP_MARGIN_SECONDS ))
}

# The latest retain-until still set on any generation in a bucket (0 when none).
latest_retention_in() {
  local file value
  file="$(mktemp)"
  g storage ls --all-versions --recursive --json "gs://$1" > "${file}"
  [ -s "${file}" ] || echo '[]' > "${file}"
  value="$(latest_retain_until "${file}")"
  rm -f "${file}"
  echo "${value}"
}

cmd_cleanup_status() {
  require_isolation
  require_project_identity
  local earliest now bucket
  earliest="$(earliest_cleanup)"
  now="$(date -u +%s)"
  say "earliest full cleanup $(date -u -d "@${earliest}" +%FT%TZ) (now $(date -u -d "@${now}" +%FT%TZ))"
  for bucket in "${BUCKET}" "${CONTROL_BUCKET}"; do
    if g storage buckets describe "gs://${bucket}" --format=json > /dev/null 2>&1; then
      say "gs://${bucket}: latest retain-until still set $(date -u -d "@$(latest_retention_in "${bucket}")" +%FT%TZ)"
    else
      say "gs://${bucket}: gone"
    fi
  done
}

cmd_cleanup() {
  require_isolation
  require_project_identity
  local number earliest now file bucket name role lien
  number="$(recorded project-number)"
  [ "${N02_CONFIRM:-}" = "cleanup-${PROJECT_ID}-${number}" ] || die "N02_CONFIRM must be cleanup-${PROJECT_ID}-${number}"
  earliest="$(earliest_cleanup)"
  now="$(date -u +%s)"
  [ "${now}" -ge "${earliest}" ] || die "too early: wait until $(date -u -d "@${earliest}" +%FT%TZ)"
  file="$(mktemp)"

  say "only the approved buckets exist in the project"
  g storage buckets list --project="${PROJECT_ID}" --raw --format=json > "${file}"
  [ "$(json "${file}" "sorted(set(b['name'] for b in d) - {'${BUCKET}', '${CONTROL_BUCKET}'})")" = "[]" ] || die "the project holds other buckets"

  for bucket in "${BUCKET}" "${CONTROL_BUCKET}"; do
    g storage buckets describe "gs://${bucket}" --raw --format=json > "${file}" 2>/dev/null || { say "gs://${bucket} already gone"; continue; }
    [ "$(json "${file}" 'str(d.get("projectNumber", ""))')" = "${number}" ] || die "${bucket} is in another project"
    [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] || die "${bucket} labels differ"
    [ "$(latest_retention_in "${bucket}")" -lt "${now}" ] || die "gs://${bucket} still holds retained generations"
    if [ -n "$(g storage ls --all-versions --recursive "gs://${bucket}")" ]; then
      g storage rm --all-versions "gs://${bucket}/**"
    fi
    [ -z "$(g storage ls --all-versions --recursive "gs://${bucket}")" ] || die "gs://${bucket} is not empty"
    g storage buckets delete "gs://${bucket}"
  done

  say "service accounts and roles of this operation"
  for name in ${ACCOUNTS}; do
    if g iam service-accounts describe "$(sa_email "${name}")" --project="${PROJECT_ID}" --format=json > /dev/null 2>&1; then
      g iam service-accounts delete "$(sa_email "${name}")" --project="${PROJECT_ID}" --quiet
    fi
  done
  for role in ${ROLE_IDS}; do
    if g iam roles describe "${role}" --project="${PROJECT_ID}" --format=json > "${file}" 2>/dev/null \
        && [ "$(json "${file}" 'd.get("deleted", False)')" != "true" ]; then
      g iam roles delete "${role}" --project="${PROJECT_ID}"
    fi
  done

  say "Object Retention Lock lien: only the one recorded after the bucket was created"
  g alpha resource-manager liens list --project="${PROJECT_ID}" --format=json > "${file}"
  local baseline
  baseline="$(state_file liens-after-orl.json)"
  [ -f "${baseline}" ] || die "no lien baseline"
  [ "$(json "${file}" "len(d)")" -le 1 ] || die "the project carries more than one lien"
  if [ "$(json "${file}" 'len(d)')" = "1" ]; then
    lien="$(json "${file}" 'd[0]["name"]')"
    [ "$(json "${baseline}" "[l['name'] for l in d]")" = "[\"${lien}\"]" ] || die "the lien ${lien} is not the recorded one"
    [ "$(json "${file}" 'd[0].get("parent", "")')" = "projects/${number}" ] || die "the lien belongs to another project"
    [ "$(json "${file}" 'd[0].get("restrictions", [])')" = '["resourcemanager.projects.delete"]' ] || die "the lien restricts more than project deletion"
    [ "$(json "${file}" 'd[0].get("origin", "") + "|" + d[0].get("reason", "")')" = "$(json "${baseline}" 'd[0].get("origin", "") + "|" + d[0].get("reason", "")')" ] || die "the lien origin or reason changed"
    g alpha resource-manager liens delete "${lien#liens/}"
  fi

  say "project, by its recorded number"
  require_project_identity
  g projects delete "${number}" --quiet
  describe_project > "${file}"
  [ "$(json "${file}" 'd.get("lifecycleState", "")')" = "DELETE_REQUESTED" ] || die "the project is not DELETE_REQUESTED"
  record cleaned-at "$(date -u +%s)"
  rm -f "${file}"
  say "done: ${PROJECT_ID} (${number}) is DELETE_REQUESTED; billing stops; no credentials were revoked"
}

cmd_forget_credentials() {
  [ "${CLOUDSDK_CONFIG:-}" = "${CONFIG_DIR}" ] || die "CLOUDSDK_CONFIG must be ${CONFIG_DIR}"
  [ -d "${CONFIG_DIR}" ] || { say "nothing to remove"; return 0; }
  case "${CONFIG_DIR}" in "${HOME}"/.config/gcloud-parkio-n02-483) ;; *) die "unexpected directory";; esac
  rm -rf -- "${CONFIG_DIR}"
  say "removed ${CONFIG_DIR}; nothing was revoked on the server"
}

case "${1:-}" in
  preflight) cmd_preflight ;;
  provision) cmd_provision ;;
  verify) cmd_verify ;;
  run) cmd_run ;;
  cleanup-status) cmd_cleanup_status ;;
  cleanup) cmd_cleanup ;;
  forget-credentials) cmd_forget_credentials ;;
  *) echo "usage: $0 preflight|provision|verify|run|cleanup-status|cleanup|forget-credentials" >&2; exit 2 ;;
esac
