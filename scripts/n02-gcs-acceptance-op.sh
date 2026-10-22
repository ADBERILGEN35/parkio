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
#                    custom roles and bindings; records identities and the lien baseline. A rerun
#                    after a failure resumes on the recorded project (or adopts the project whose
#                    creation it started) and creates only what is missing
#   verify           read-only: the provisioned configuration, IAM policies and lien match the record
#   run              one harness attempt (at most two counted)
#   cleanup-status   read-only: earliest cleanup time and what still exists
#   cleanup          after the retain-until: delete objects, buckets, service accounts, roles, the
#                    Object Retention Lock lien and the project, each after identity checks; it can
#                    be run again after a refusal or an interruption
#   forget-credentials  delete the operation's own gcloud configuration directory (no revocation)
#
# provision, verify, run and cleanup run only from a checkout at N02_APPROVED_SHA whose copy of this
# script is unmodified. Destructive commands also need N02_CONFIRM: provision "provision-<project>",
# cleanup "cleanup-<project>-<project number>". Google places the project-deletion lien at best effort;
# if none appears, provision records that and stops, and going on needs the owner's N02_ACCEPT_NO_LIEN=1.
# No command revokes OAuth tokens: Google revokes the whole grant of a client, which would also sign
# the account out of gcloud/ADC on other machines.
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
SA_SETTLE_SECONDS="${N02_SA_SETTLE_SECONDS:-60}"
RETRY_SECONDS="${N02_RETRY_SECONDS:-15}"
LIEN_POLL_SECONDS="${N02_LIEN_POLL_SECONDS:-15}"

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

g() { "${GCLOUD}" "$@"; }

# Runs a command up to 10 times, RETRY_SECONDS apart (IAM changes propagate with a delay).
retry() {
  local attempt
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    if "$@"; then
      return 0
    fi
    [ "${attempt}" = 10 ] || sleep "${RETRY_SECONDS}"
  done
  return 1
}

# Every command runs in the operation's own configuration; inherited settings are refused, not reused.
require_isolation() {
  [ "${CLOUDSDK_CONFIG:-}" = "${CONFIG_DIR}" ] || die "CLOUDSDK_CONFIG must be ${CONFIG_DIR}"
  [ -d "${CONFIG_DIR}" ] || die "${CONFIG_DIR} does not exist"
  local variable
  for variable in GOOGLE_APPLICATION_CREDENTIALS GOOGLE_CLOUD_PROJECT GOOGLE_CLOUD_QUOTA_PROJECT GOOGLE_SDK_JAVA_LOGGING; do
    [ -z "${!variable:-}" ] || die "${variable} must be unset"
  done
  for variable in $(compgen -e | grep '^CLOUDSDK_' || true); do
    [ "${variable}" = CLOUDSDK_CONFIG ] || die "${variable} must be unset (gcloud environment overrides change identity or endpoints)"
  done
  command -v python3 >/dev/null || die "python3 is required"
  g version --format=json > /dev/null || die "${GCLOUD} does not run"
  local project
  project="$(g config list --format=json | json /dev/stdin 'd.get("core", {}).get("project", "")')"
  [ -z "${project}" ] || die "the operation's configuration has core/project=${project}; it must stay unset"
}

# The checkout is the approved commit and this script is unmodified (prints the repository root;
# the harness itself refuses a live run from changed build inputs).
require_approved_checkout() {
  local sha="${N02_APPROVED_SHA:-}" root
  [[ "${sha}" =~ ^[0-9a-f]{40}$ ]] || die "N02_APPROVED_SHA must be the approved full commit SHA"
  root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
  [ "$(git -C "${root}" rev-parse HEAD)" = "${sha}" ] || die "checkout is not ${sha}"
  git -C "${root}" diff --quiet HEAD -- scripts/n02-gcs-acceptance-op.sh \
    || die "scripts/n02-gcs-acceptance-op.sh differs from ${sha}"
  echo "${root}"
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
log_command() { mkdir -p "${STATE}"; printf '%s\t%s\t%s\n' "$(date -u +%FT%TZ)" "$1" "${N02_APPROVED_SHA:-}" >> "$(state_file commands.tsv)"; }

describe_project() { g projects describe "${PROJECT_ID}" --format=json; }
describe_project_to() { describe_project > "$1"; }

# The project is the one provisioned here: same ID, number, creation time and labels. Prints its
# lifecycle state.
project_state() {
  local number created file
  number="$(recorded project-number)"
  created="$(recorded project-create-time)"
  [ -n "${number}" ] && [ -n "${created}" ] || die "no provisioning record in ${STATE}"
  file="$(mktemp)"
  describe_project > "${file}" || die "cannot describe ${PROJECT_ID}"
  [ "$(json "${file}" 'd["projectId"]')" = "${PROJECT_ID}" ] || die "project ID differs"
  [ "$(json "${file}" 'd["projectNumber"]')" = "${number}" ] || die "project number differs from the recorded ${number}"
  [ "$(json "${file}" 'd.get("createTime", "")')" = "${created}" ] || die "project creation time differs"
  [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] || die "project label parkio-purpose missing"
  [ "$(json "${file}" 'd.get("labels", {}).get("parkio-issue", "")')" = "483" ] || die "project label parkio-issue missing"
  json "${file}" 'd.get("lifecycleState", "")'
  rm -f "${file}"
}

require_project_identity() {
  local state
  state="$(project_state)" || exit 1
  [ "${state}" = ACTIVE ] || die "project is not ACTIVE (${state})"
}

# A project whose creation started here but was never recorded (the create call or the describe
# after it failed) is adopted only when its labels match and it was created after the marker.
adopt_created_project() {
  local marker file created
  marker="$(recorded project-creating)"
  [ -n "${marker}" ] || die "no provisioning record in ${STATE}"
  file="$(mktemp)"
  retry describe_project_to "${file}" \
    || die "cannot describe ${PROJECT_ID}, whose creation started at $(date -u -d "@${marker}" +%FT%TZ); if no project was created, remove $(state_file project-creating) to start again"
  [ "$(json "${file}" 'd["projectId"]')" = "${PROJECT_ID}" ] || die "project ID differs"
  [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] \
    && [ "$(json "${file}" 'd.get("labels", {}).get("parkio-issue", "")')" = "483" ] \
    || die "${PROJECT_ID} exists but is not the one created here (labels)"
  created="$(json "${file}" 'd.get("createTime", "")')"
  [ -n "${created}" ] && [ "$(date -u -d "${created}" +%s)" -ge $(( marker - 300 )) ] \
    || die "${PROJECT_ID} was created before this operation started creating it"
  record project-number "$(json "${file}" 'd["projectNumber"]')"
  record project-create-time "${created}"
  rm -f "${file}"
}

# Names of the buckets the project holds, one per line, from a successful listing only.
project_buckets() {
  local file names
  file="$(mktemp)"
  g storage buckets list --project="${PROJECT_ID}" --raw --format=json > "${file}" || { rm -f "${file}"; die "cannot list the buckets of ${PROJECT_ID}"; }
  [ -n "$(tr -d '[:space:]' < "${file}")" ] || echo '[]' > "${file}"
  names="$(json "${file}" '"\n".join(sorted(b["name"] for b in d))')"
  rm -f "${file}"
  echo "${names}"
}

# Object generations in a bucket. A recursive listing also yields the bucket and prefixes; only
# cloud_object entries count, and an empty listing is no generation.
generations_in() {
  local file count
  file="$(mktemp)"
  g storage ls --all-versions --recursive --json "gs://$1" > "${file}" || { rm -f "${file}"; die "cannot list gs://$1"; }
  count="$(python3 -I -c 'import json,sys
t=open(sys.argv[1], encoding="utf-8").read().strip()
print(sum(1 for r in (json.loads(t) if t else []) if r.get("type") == "cloud_object"))' "${file}")"
  rm -f "${file}"
  [[ "${count}" =~ ^[0-9]+$ ]] || die "cannot count the generations of gs://$1"
  echo "${count}"
}

# The latest retainUntilTime still set on any generation in a bucket, as epoch seconds (0 when none).
latest_retention_in() {
  local file value
  file="$(mktemp)"
  g storage ls --all-versions --recursive --json "gs://$1" > "${file}" || { rm -f "${file}"; die "cannot list gs://$1"; }
  value="$(python3 -I -c 'import json,sys,datetime
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
t=open(sys.argv[1], encoding="utf-8").read().strip()
walk(json.loads(t) if t else [])
print(max(found))' "${file}")"
  rm -f "${file}"
  echo "${value}"
}

lien_signature() {
  json "$1" "sorted(l['name'] + '|' + l.get('parent', '') + '|' + l.get('origin', '') + '|' + l.get('reason', '') + '|' + ','.join(l.get('restrictions', [])) for l in d)"
}

# Writes the project's liens to a file as a JSON list (an empty listing is no lien).
liens_to() {
  g alpha resource-manager liens list --project="${PROJECT_ID}" --format=json > "$1" || die "cannot list the liens of ${PROJECT_ID}"
  [ -n "$(tr -d '[:space:]' < "$1")" ] || echo '[]' > "$1"
}

# verify and run: the project's liens are those recorded after Object Retention Lock was enabled.
# A lien recorded as absent (Google places it at best effort) needs the owner's N02_ACCEPT_NO_LIEN=1.
require_lien_baseline() {
  local file
  [ -s "$(state_file liens-after-orl.json)" ] || die "no lien baseline"
  if [ -e "$(state_file lien-absent)" ] && [ "${N02_ACCEPT_NO_LIEN:-}" != 1 ]; then
    die "no project-deletion lien was recorded; the owner decides (rerun provision to look again, or N02_ACCEPT_NO_LIEN=1)"
  fi
  file="$(mktemp)"
  liens_to "${file}"
  [ "$(lien_signature "${file}")" = "$(lien_signature "$(state_file liens-after-orl.json)")" ] \
    || die "the project's liens differ from the recorded baseline"
  rm -f "${file}"
}

# cleanup: the live liens (in a file) are the recorded baseline or none: deleted by an earlier
# cleanup, removed by Google or never placed. An unrecorded lien is never accepted; without a
# baseline the project may hold neither a lien nor the Object Retention Lock bucket.
require_cleanup_liens() {
  local file="$1" present="$2" baseline
  baseline="$(state_file liens-after-orl.json)"
  if [ ! -s "${baseline}" ]; then
    if grep -qx "${BUCKET}" <<<"${present}"; then
      die "no lien baseline while gs://${BUCKET} exists; rerun provision to record it"
    fi
    [ "$(json "${file}" 'len(d)')" = "0" ] || die "the project has liens but no lien baseline was recorded"
  elif [ "$(json "${file}" 'len(d)')" != "0" ]; then
    [ "$(lien_signature "${file}")" = "$(lien_signature "${baseline}")" ] || die "the project's liens differ from the recorded baseline"
  fi
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
  require_approved_checkout > /dev/null
  [ "${N02_CONFIRM:-}" = "provision-${PROJECT_ID}" ] || die "N02_CONFIRM must be provision-${PROJECT_ID}"
  local billing="${N02_BILLING_ACCOUNT:-}"
  [[ "${billing}" =~ ^[0-9A-F]{6}-[0-9A-F]{6}-[0-9A-F]{6}$ ]] || die "N02_BILLING_ACCOUNT must look like XXXXXX-XXXXXX-XXXXXX"
  local account file name role bucket created_any polls found present baseline
  account="$(active_account)"
  file="$(mktemp)"

  if [ -z "$(recorded project-number)" ] && [ -n "$(recorded project-creating)" ]; then
    say "adopting the project whose creation started here"
    [ "$(recorded billing-account)" = "${billing}" ] || die "the recorded billing account differs"
    [ "$(recorded operator)" = "${account}" ] || die "the recorded operator account differs"
    adopt_created_project
  fi
  if [ -n "$(recorded project-number)" ]; then
    say "resuming on the recorded project"
    require_project_identity
    [ "$(recorded billing-account)" = "${billing}" ] || die "the recorded billing account differs"
    [ "$(recorded operator)" = "${account}" ] || die "the recorded operator account differs"
    log_command "provision (resume)"
  else
    g billing accounts describe "${billing}" --format=json > "${file}"
    [ "$(json "${file}" 'd.get("open", False)')" = "true" ] || die "billing account ${billing} is not open"
    if g projects describe "${PROJECT_ID}" --format=json > /dev/null 2>&1; then
      die "${PROJECT_ID} already exists; nothing was changed"
    fi
    log_command provision
    record operator "${account}"
    record billing-account "${billing}"
    # Marked first, so that a lost answer cannot leave a project this operation does not know.
    record project-creating "$(date -u +%s)"
    say "project"
    g projects create "${PROJECT_ID}" --name="${PROJECT_NAME}" --labels="parkio-purpose=n02-gcs-acceptance,parkio-issue=483" \
      --no-enable-cloud-apis
    adopt_created_project
    require_project_identity
  fi

  say "billing"
  g billing projects describe "${PROJECT_ID}" --format=json > "${file}"
  if [ "$(json "${file}" 'd.get("billingEnabled", False)')" != "true" ]; then
    g billing projects link "${PROJECT_ID}" --billing-account="${billing}"
    g billing projects describe "${PROJECT_ID}" --format=json > "${file}"
  fi
  [ "$(json "${file}" 'd.get("billingEnabled", False)')" = "true" ] || die "billing is not enabled"
  [ "$(json "${file}" 'd.get("billingAccountName", "")')" = "billingAccounts/${billing}" ] || die "billing account differs"

  say "APIs"
  # shellcheck disable=SC2086
  g services enable ${APIS} --project="${PROJECT_ID}"

  say "buckets"
  present="$(project_buckets)"
  if ! grep -qx "${BUCKET}" <<<"${present}"; then
    g storage buckets create "gs://${BUCKET}" --project="${PROJECT_ID}" --location="${REGION}" \
      --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention \
      --soft-delete-duration=0 --enable-per-object-retention
    sleep "${N02_BUCKET_SPACING_SECONDS:-3}"
  fi
  if ! grep -qx "${CONTROL_BUCKET}" <<<"${present}"; then
    g storage buckets create "gs://${CONTROL_BUCKET}" --project="${PROJECT_ID}" --location="${REGION}" \
      --default-storage-class=STANDARD --uniform-bucket-level-access --public-access-prevention \
      --soft-delete-duration=0
  fi
  g storage buckets update "gs://${BUCKET}" --versioning --update-labels="${LABELS}"
  g storage buckets update "gs://${CONTROL_BUCKET}" --versioning --update-labels="${LABELS}"

  baseline="$(state_file liens-after-orl.json)"
  if [ ! -s "${baseline}" ] || [ -e "$(state_file lien-absent)" ]; then
    say "Object Retention Lock lien (best effort; waits up to 5 min)"
    # A lien already missed once is looked for once more, not waited for again.
    polls=0
    [ ! -e "$(state_file lien-absent)" ] || polls=19
    while :; do
      liens_to "${file}"
      found="$(json "${file}" "len([l for l in d if 'resourcemanager.projects.delete' in l.get('restrictions', [])])")"
      [ "${found}" = "0" ] || break
      polls=$((polls + 1))
      [ "${polls}" -lt 20 ] || break
      sleep "${LIEN_POLL_SECONDS}"
    done
    if [ "${found}" != "0" ]; then
      cp "${file}" "${baseline}"
      rm -f "$(state_file lien-absent)"
    else
      echo '[]' > "${baseline}"
      record lien-absent "$(date -u +%s)"
      [ "${N02_ACCEPT_NO_LIEN:-}" = 1 ] || die "no project-deletion lien appeared within 5 min of enabling Object Retention Lock (recorded as absent); the owner decides: rerun provision to look again, go on with N02_ACCEPT_NO_LIEN=1, or clean up"
      say "WARNING: no project-deletion lien; going on as the owner decided (N02_ACCEPT_NO_LIEN=1)"
    fi
  fi

  say "service accounts (no keys)"
  created_any=0
  for name in ${ACCOUNTS}; do
    if ! g iam service-accounts describe "$(sa_email "${name}")" --project="${PROJECT_ID}" --format=json > /dev/null 2>&1; then
      g iam service-accounts create "${name}" --project="${PROJECT_ID}" --display-name="N-02 #483 ${name} (no keys)"
      created_any=1
    fi
  done
  for name in ${ACCOUNTS}; do
    retry g iam service-accounts describe "$(sa_email "${name}")" --project="${PROJECT_ID}" --format=json > /dev/null \
      || die "service account ${name} did not appear"
  done
  # Google: "you might need to wait for 60 seconds or more before you use the service account".
  [ "${created_any}" = 0 ] || sleep "${SA_SETTLE_SECONDS}"

  say "custom roles"
  for role in ${ROLE_IDS}; do
    if ! g iam roles describe "${role}" --project="${PROJECT_ID}" --format=json > "${file}" 2>/dev/null; then
      retry g iam roles create "${role}" --project="${PROJECT_ID}" --title="N-02 #483 ${role}" --stage=GA \
        --permissions="$(role_permissions "${role}")" || die "role ${role} could not be created"
    elif [ "$(json "${file}" 'd.get("deleted", False)')" = "true" ]; then
      die "role ${role} exists in the deleted state; it is not reused"
    fi
  done

  say "bindings (adding an existing binding changes nothing)"
  while read -r bucket name role; do
    retry g storage buckets add-iam-policy-binding "gs://${bucket}" --member="serviceAccount:$(sa_email "${name}")" \
      --role="projects/${PROJECT_ID}/roles/${role}" || die "binding ${name} on ${bucket} failed"
  done < <(bucket_bindings)
  for name in ${ACCOUNTS}; do
    retry g iam service-accounts add-iam-policy-binding "$(sa_email "${name}")" --project="${PROJECT_ID}" \
      --member="user:${account}" --role="projects/${PROJECT_ID}/roles/n02TokenMinter" \
      || die "token binding on ${name} failed"
  done
  record iam-bound-at "$(date -u +%s)"
  rm -f "${file}"
  verify_provisioned
  say "provisioned; wait ${IAM_SETTLE_SECONDS} s for IAM propagation, then log in ADC and use: run"
}

cmd_verify() {
  require_isolation
  require_approved_checkout > /dev/null
  verify_provisioned
}

verify_provisioned() {
  require_project_identity
  local file number bucket name role
  number="$(recorded project-number)"
  file="$(mktemp)"
  [ "$(project_buckets)" = "$(printf '%s\n%s' "${CONTROL_BUCKET}" "${BUCKET}")" ] || die "the project's buckets differ from the approved two"
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
  require_lien_baseline
  rm -f "${file}"
  say "verified: ${PROJECT_ID} (${number}), gs://${BUCKET}, gs://${CONTROL_BUCKET}, ${ROLE_IDS// /, }, lien as recorded"
}

cmd_run() {
  require_isolation
  local root attempts n out adc bound rc stamp
  root="$(require_approved_checkout)"
  require_project_identity
  require_lien_baseline
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
  log_command "run attempt-${n}"
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
# Without result.json the harness's own bound from run.json is used; without either, the attempt start.
earliest_cleanup() {
  local latest=0 started value dir
  for started in "${STATE}"/runs/attempt-*.started; do
    [ -f "${started}" ] || continue
    dir="${started%.started}"
    value=""
    if [ -f "${dir}/result.json" ]; then
      value="$(json "${dir}/result.json" 'd["budget"]["latestRetainUntil"] or ""')"
    fi
    if [ -z "${value}" ] && [ -f "${dir}/run.json" ]; then
      value="$(json "${dir}/run.json" 'd["run"]["latestRetainUntilBound"] or ""')"
    fi
    if [ -n "${value}" ]; then
      value="$(date -u -d "${value}" +%s)"
    else
      value=$(( $(cat "${started}") + RUN_RETENTION_BOUND_SECONDS ))
    fi
    [ "${value}" -le "${latest}" ] || latest="${value}"
  done
  echo $(( latest + CLEANUP_MARGIN_SECONDS ))
}

cmd_cleanup_status() {
  require_isolation
  local earliest now bucket state present file
  state="$(project_state)"
  say "project ${PROJECT_ID} ($(recorded project-number)) is ${state}"
  [ "${state}" = ACTIVE ] || return 0
  earliest="$(earliest_cleanup)"
  now="$(date -u +%s)"
  say "earliest full cleanup $(date -u -d "@${earliest}" +%FT%TZ) (now $(date -u -d "@${now}" +%FT%TZ))"
  present="$(project_buckets)"
  for bucket in ${present}; do
    say "gs://${bucket}: $(generations_in "${bucket}") generations, latest retain-until still set $(date -u -d "@$(latest_retention_in "${bucket}")" +%FT%TZ)"
  done
  file="$(mktemp)"
  liens_to "${file}"
  say "liens: $(json "${file}" 'len(d)'); recorded after Object Retention Lock: $(json "$(state_file liens-after-orl.json)" 'len(d)' 2>/dev/null || echo none)"
  rm -f "${file}"
}

cmd_cleanup() {
  require_isolation
  require_approved_checkout > /dev/null
  local number earliest now file bucket name role lien present remaining state
  if [ -z "$(recorded project-number)" ] && [ -n "$(recorded project-creating)" ]; then
    say "adopting the project whose creation started here"
    adopt_created_project
  fi
  number="$(recorded project-number)"
  [ -n "${number}" ] || die "no provisioning record in ${STATE}"
  [ "${N02_CONFIRM:-}" = "cleanup-${PROJECT_ID}-${number}" ] || die "N02_CONFIRM must be cleanup-${PROJECT_ID}-${number}"
  state="$(project_state)"
  if [ "${state}" = DELETE_REQUESTED ]; then
    [ -n "$(recorded cleaned-at)" ] || record cleaned-at "$(date -u +%s)"
    say "done: ${PROJECT_ID} (${number}) is already DELETE_REQUESTED; nothing was changed"
    return 0
  fi
  [ "${state}" = ACTIVE ] || die "project is not ACTIVE (${state})"
  earliest="$(earliest_cleanup)"
  now="$(date -u +%s)"
  [ "${now}" -ge "${earliest}" ] || die "too early: wait until $(date -u -d "@${earliest}" +%FT%TZ)"
  file="$(mktemp)"
  log_command cleanup

  say "the project's liens are the recorded baseline or none, and only the approved buckets exist"
  present="$(project_buckets)"
  liens_to "${file}"
  require_cleanup_liens "${file}" "${present}"
  for bucket in ${present}; do
    [ "${bucket}" = "${BUCKET}" ] || [ "${bucket}" = "${CONTROL_BUCKET}" ] || die "the project holds other buckets"
  done
  # Every check passes for every bucket before anything is deleted.
  for bucket in ${present}; do
    g storage buckets describe "gs://${bucket}" --raw --format=json > "${file}" || die "cannot describe gs://${bucket}"
    [ "$(json "${file}" 'str(d.get("projectNumber", ""))')" = "${number}" ] || die "${bucket} is in another project"
    [ "$(json "${file}" 'd.get("labels", {}).get("parkio-purpose", "")')" = "n02-gcs-acceptance" ] || die "${bucket} labels differ"
    [ "$(latest_retention_in "${bucket}")" -lt "${now}" ] || die "gs://${bucket} still holds retained generations"
  done
  for bucket in ${present}; do
    if [ "$(generations_in "${bucket}")" -gt 0 ]; then
      g storage rm --all-versions "gs://${bucket}/**"
    fi
    [ "$(generations_in "${bucket}")" = "0" ] || die "gs://${bucket} is not empty"
    g storage buckets delete "gs://${bucket}"
  done
  # Assigned first: a failed listing stops here instead of reading as "no bucket".
  remaining="$(project_buckets)"
  [ -z "${remaining}" ] || die "buckets remain in ${PROJECT_ID}; the lien and the project stay"

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
  liens_to "${file}"
  if [ "$(json "${file}" 'len(d)')" != "0" ]; then
    require_cleanup_liens "${file}" ""
    [ "$(json "${file}" 'len(d)')" = "1" ] || die "the project carries more than one lien"
    lien="$(json "${file}" 'd[0]["name"]')"
    case "$(json "${file}" 'd[0].get("parent", "")')" in
      "projects/${number}"|"projects/${PROJECT_ID}") ;;
      *) die "the lien belongs to another project" ;;
    esac
    [ "$(json "${file}" 'd[0].get("restrictions", [])')" = '["resourcemanager.projects.delete"]' ] || die "the lien restricts more than project deletion"
    g alpha resource-manager liens delete "${lien#liens/}"
    record lien-deleted "${lien}"
  fi

  say "project, by its recorded number"
  require_project_identity
  remaining="$(project_buckets)"
  [ -z "${remaining}" ] || die "buckets remain in ${PROJECT_ID}"
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
