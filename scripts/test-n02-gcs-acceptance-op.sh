#!/usr/bin/env bash
# Stub-driven end-to-end test of scripts/n02-gcs-acceptance-op.sh (#483). A stateful fake gcloud
# models the dedicated project, buckets, service accounts, roles, bindings and the Object Retention
# Lock lien, and records every call. No network, no real credentials, nothing in Google Cloud.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OP="${ROOT}/scripts/n02-gcs-acceptance-op.sh"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }
command -v python3 >/dev/null || { echo "ERROR: python3 is required" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-n02-op.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
mkdir -p "${WORK}/bin"

cat > "${WORK}/bin/gcloud" <<'PY'
#!/usr/bin/env python3
"""Fake gcloud: just the commands n02-gcs-acceptance-op.sh uses, over a JSON state file."""
import json, os, sys, time

STATE = os.environ["FAKE_STATE"]
args = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps({"args": args, "config": os.environ.get("CLOUDSDK_CONFIG", "")}) + "\n")
s = json.load(open(STATE)) if os.path.exists(STATE) else {}
s.setdefault("buckets", {}); s.setdefault("accounts", {}); s.setdefault("roles", {}); s.setdefault("liens", [])
P = "parkio-n02-accept-5c2c7f"
now = int(time.time())

def save():
    json.dump(s, open(STATE, "w"), indent=1)

def out(value):
    print(json.dumps(value))
    sys.exit(0)

def fail(message, code=1):
    print("ERROR: " + message, file=sys.stderr)
    sys.exit(code)

def flag(name, default=None):
    for a in args:
        if a.startswith("--" + name + "="):
            return a.split("=", 1)[1]
    return default

words = [a for a in args if not a.startswith("--")]
key = " ".join(words[:3])
# Knob: fail the next N calls that start with a prefix (an IAM change not yet propagated, a lost call).
for prefix, remaining in list(s.get("fail", {}).items()):
    if remaining > 0 and " ".join(words).startswith(prefix):
        s["fail"][prefix] = remaining - 1
        save()
        fail("simulated failure of " + prefix)

if words[:1] == ["version"]:
    out({"Google Cloud SDK": "590.0.0"})
if words[:2] == ["config", "list"]:
    core = {"account": s.get("account", "operator@example.com")}
    if s.get("core_project"):
        core["project"] = s["core_project"]
    out({"core": core})
if "revoke" in words:
    fail("revocation is never expected")
if words[:3] == ["billing", "accounts", "list"]:
    out([{"name": "billingAccounts/ABCDEF-123456-7890AB", "open": True}])
if words[:3] == ["billing", "accounts", "describe"]:
    out({"name": "billingAccounts/" + words[3], "open": s.get("billing_open", True)})
if words[:2] == ["projects", "describe"]:
    project = s.get("project")
    if not project or words[2] != P:
        fail("PERMISSION_DENIED")
    out(project)
if words[:2] == ["projects", "create"]:
    if s.get("project"):
        fail("ALREADY_EXISTS")
    labels = dict(item.split("=", 1) for item in flag("labels").split(","))
    s["project"] = {"projectId": words[2], "projectNumber": "123456789012", "createTime": "2026-10-22T10:00:00.000Z",
                    "lifecycleState": "ACTIVE", "labels": labels, "name": flag("name")}
    save(); out({})
if words[:3] == ["billing", "projects", "link"]:
    s["billing"] = flag("billing-account"); save(); out({})
if words[:3] == ["billing", "projects", "describe"]:
    out({"billingEnabled": bool(s.get("billing")), "billingAccountName": "billingAccounts/" + s.get("billing", "")})
if words[:2] == ["services", "enable"]:
    s["services"] = words[2:]; save(); out({})
if words[:3] == ["storage", "buckets", "create"]:
    name = words[3][5:]
    retention = "--enable-per-object-retention" in args
    s["buckets"][name] = {"name": name, "projectNumber": s["project"]["projectNumber"], "location": flag("location").upper(),
                          "storageClass": flag("default-storage-class"), "softDeletePolicy": {"retentionDurationSeconds": flag("soft-delete-duration")},
                          "iamConfiguration": {"uniformBucketLevelAccess": {"enabled": True}, "publicAccessPrevention": "enforced"},
                          "objectRetention": {"mode": "Enabled"} if retention else {}, "versioning": {"enabled": False},
                          "labels": {}, "objects": [], "bindings": []}
    if retention:
        s["liens"].append({"name": "liens/p123456789012-orl", "parent": "projects/123456789012", "origin": "storage.googleapis.com",
                           "reason": "Object retention is enabled on a bucket", "restrictions": ["resourcemanager.projects.delete"]})
    save(); out({})
if words[:3] == ["storage", "buckets", "update"]:
    b = s["buckets"][words[3][5:]]
    if "--versioning" in args:
        b["versioning"]["enabled"] = True
    if flag("update-labels"):
        b["labels"].update(dict(item.split("=", 1) for item in flag("update-labels").split(",")))
    save(); out({})
if words[:3] == ["storage", "buckets", "describe"]:
    name = words[3][5:]
    if name not in s["buckets"] or name in s.get("describe_fail", []):
        fail("NotFound")
    b = {k: v for k, v in s["buckets"][name].items() if k not in ("objects", "bindings")}
    if not b["objectRetention"]:
        del b["objectRetention"]
    out(b)
if words[:3] == ["storage", "buckets", "list"]:
    out([{"name": n} for n in s["buckets"]])
if words[:3] == ["storage", "buckets", "add-iam-policy-binding"]:
    binding = {"role": flag("role"), "members": [flag("member")]}
    if binding not in s["buckets"][words[3][5:]]["bindings"]:
        s["buckets"][words[3][5:]]["bindings"].append(binding)
    save(); out({})
if words[:3] == ["storage", "buckets", "get-iam-policy"]:
    out({"bindings": [{"role": "roles/storage.legacyBucketOwner", "members": ["projectOwner:" + P]}]
         + s["buckets"][words[3][5:]]["bindings"]})
if words[:3] == ["storage", "buckets", "delete"]:
    name = words[3][5:]
    if s["buckets"][name]["objects"]:
        fail("bucket not empty")
    if not s.get("delete_noop"):
        del s["buckets"][name]
    save(); out({})
if words[:2] == ["storage", "ls"]:
    # Like gcloud 590: a recursive listing of a bucket URL also yields the bucket itself (a "gs://B/:"
    # header in plain output, a cloud_bucket entry in JSON); an empty non-recursive JSON listing is "\n".
    b = s["buckets"][words[2][5:]]
    if "--json" in args:
        items = [{"url": "gs://%s/%s#%d" % (b["name"], o["name"], o["generation"]), "type": "cloud_object",
                  "metadata": {"retention": {"mode": "Locked", "retainUntilTime": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(o["until"]))}}}
                 for o in b["objects"]]
        if "--recursive" in args:
            items.insert(0, {"url": "gs://%s/" % b["name"], "type": "cloud_bucket", "metadata": {"name": b["name"]}})
        if not items:
            print(); sys.exit(0)
        out(items)
    if "--recursive" in args:
        print("gs://%s/:" % b["name"])
    for o in b["objects"]:
        print("gs://%s/%s#%d" % (b["name"], o["name"], o["generation"]))
    sys.exit(0)
if words[:2] == ["storage", "rm"]:
    b = s["buckets"][words[2][5:words[2].index("/**")]]
    if not b["objects"] and "--recursive" not in args:
        fail("The following URLs matched no objects or files: " + words[2])
    if any(o["until"] > now for o in b["objects"]):
        fail("Object is subject to object retention")
    b["objects"] = []; save(); out({})
if words[:3] == ["iam", "service-accounts", "create"]:
    s["accounts"][words[3] + "@" + P + ".iam.gserviceaccount.com"] = {"bindings": []}; save(); out({})
if words[:3] == ["iam", "service-accounts", "describe"]:
    if words[3] not in s["accounts"]:
        fail("NOT_FOUND")
    out({"email": words[3]})
if words[:3] == ["iam", "service-accounts", "add-iam-policy-binding"]:
    binding = {"role": flag("role"), "members": [flag("member")]}
    if binding not in s["accounts"][words[3]]["bindings"]:
        s["accounts"][words[3]]["bindings"].append(binding)
    save(); out({})
if words[:3] == ["iam", "service-accounts", "get-iam-policy"]:
    out({"bindings": s["accounts"][words[3]]["bindings"]})
if words[:4] == ["iam", "service-accounts", "keys", "list"]:
    out([])
if words[:3] == ["iam", "service-accounts", "delete"]:
    del s["accounts"][words[3]]; save(); out({})
if words[:3] == ["iam", "roles", "create"]:
    s["roles"][words[3]] = {"includedPermissions": flag("permissions").split(","), "deleted": False}; save(); out({})
if words[:3] == ["iam", "roles", "describe"]:
    if words[3] not in s["roles"]:
        fail("NOT_FOUND")
    out(s["roles"][words[3]])
if words[:3] == ["iam", "roles", "delete"]:
    s["roles"][words[3]]["deleted"] = True; save(); out({})
if words[:4] == ["alpha", "resource-manager", "liens", "list"]:
    if s.get("lien_delay"):
        s["lien_delay"] -= 1; save(); out([])
    out(s["liens"])
if words[:4] == ["alpha", "resource-manager", "liens", "delete"]:
    s["liens"] = [l for l in s["liens"] if l["name"] != "liens/" + words[4]]; save(); out({})
if words[:2] == ["projects", "delete"]:
    if words[2] != s["project"]["projectNumber"]:
        fail("projects are deleted by number in this test")
    if s["liens"]:
        fail("FAILED_PRECONDITION: a lien prevents deletion")
    s["project"]["lifecycleState"] = "DELETE_REQUESTED"; save(); out({})
fail("unexpected fake gcloud call: " + " ".join(args), 9)
PY
chmod +x "${WORK}/bin/gcloud"

# A fresh operator home, dedicated configuration directory and fake cloud per scenario.
reset() {
  export HOME="${WORK}/home-$1"
  rm -rf "${HOME}"
  mkdir -p "${HOME}/.config/gcloud-parkio-n02-483"
  export CLOUDSDK_CONFIG="${HOME}/.config/gcloud-parkio-n02-483"
  export FAKE_STATE="${HOME}/fake-cloud.json" FAKE_LOG="${HOME}/calls.log"
  export N02_GCLOUD="${WORK}/bin/gcloud" N02_STATE_DIR="${HOME}/state" N02_POLL_SECONDS=0 N02_BUCKET_SPACING_SECONDS=0 \
    N02_SA_SETTLE_SECONDS=0 N02_RETRY_SECONDS=0 N02_LIEN_POLL_SECONDS=0
  export N02_APPROVED_SHA="$(git -C "${ROOT}" rev-parse HEAD)"
  : > "${FAKE_LOG}"
  unset N02_CONFIRM N02_BILLING_ACCOUNT N02_GRADLE GOOGLE_APPLICATION_CREDENTIALS CLOUDSDK_CORE_PROJECT || true
}
calls() { python3 -I -c 'import json,sys; [print(" ".join(json.loads(l)["args"])) for l in open(sys.argv[1])]' "${FAKE_LOG}"; }
fake() { python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); exec(sys.argv[2]); json.dump(s, open(sys.argv[1], "w"))' "${FAKE_STATE}" "$1"; }
expect_stop() {
  local name="$1" pattern="$2"; shift 2
  local output
  if output="$("$@" 2>&1)"; then
    bad "${name}: succeeded"
  elif grep -q -- "${pattern}" <<<"${output}"; then
    ok "${name}"
  else
    bad "${name}: ${output}"
  fi
}
writes() { calls | grep -c -E '^(projects create|projects delete|billing projects link|services enable|storage buckets (create|update|delete|add-iam-policy-binding)|storage rm|iam (roles|service-accounts) (create|delete|add-iam-policy-binding)|alpha resource-manager liens delete)' || true; }
provision() { N02_CONFIRM="provision-parkio-n02-accept-5c2c7f" N02_BILLING_ACCOUNT="ABCDEF-123456-7890AB" bash "${OP}" provision; }

# --- credential isolation ---------------------------------------------------------------------
reset isolation
expect_stop "refuses another CLOUDSDK_CONFIG" "CLOUDSDK_CONFIG must be" env CLOUDSDK_CONFIG="${HOME}/.config/gcloud" bash "${OP}" preflight
expect_stop "refuses an inherited key file" "GOOGLE_APPLICATION_CREDENTIALS must be unset" env GOOGLE_APPLICATION_CREDENTIALS=/tmp/key.json bash "${OP}" preflight
expect_stop "refuses an inherited project" "CLOUDSDK_CORE_PROJECT must be unset" env CLOUDSDK_CORE_PROJECT=other bash "${OP}" preflight
expect_stop "refuses any other gcloud override" "CLOUDSDK_AUTH_IMPERSONATE_SERVICE_ACCOUNT must be unset" env CLOUDSDK_AUTH_IMPERSONATE_SERVICE_ACCOUNT=x@y bash "${OP}" preflight
echo '{"core_project": "other-project"}' > "${FAKE_STATE}"
expect_stop "refuses a configured core/project" "core/project=other-project" bash "${OP}" preflight
rm -f "${FAKE_STATE}"
if bash "${OP}" preflight > "${HOME}/preflight.out" 2>&1 && grep -q "not visible" "${HOME}/preflight.out" \
    && [ "$(writes)" = "0" ]; then ok "preflight is read-only"; else bad "preflight: $(cat "${HOME}/preflight.out")"; fi

# --- provision ------------------------------------------------------------------------------------
reset provision-guards
expect_stop "provision needs the confirmation" "N02_CONFIRM must be provision-" env N02_BILLING_ACCOUNT=ABCDEF-123456-7890AB bash "${OP}" provision
expect_stop "provision runs only from the approved commit" "checkout is not" env N02_APPROVED_SHA="$(printf 'e%.0s' {1..40})" N02_CONFIRM=provision-parkio-n02-accept-5c2c7f N02_BILLING_ACCOUNT=ABCDEF-123456-7890AB bash "${OP}" provision
expect_stop "provision rejects a wrong confirmation" "N02_CONFIRM must be provision-" env N02_CONFIRM=provision-other N02_BILLING_ACCOUNT=ABCDEF-123456-7890AB bash "${OP}" provision
expect_stop "provision rejects a malformed billing account" "N02_BILLING_ACCOUNT must look like" env N02_CONFIRM=provision-parkio-n02-accept-5c2c7f N02_BILLING_ACCOUNT=123 bash "${OP}" provision
echo '{"billing_open": false}' > "${FAKE_STATE}"
expect_stop "provision rejects a closed billing account" "is not open" provision
echo '{"project": {"projectId": "parkio-n02-accept-5c2c7f", "projectNumber": "555555555555", "createTime": "2020-01-01T00:00:00Z", "lifecycleState": "ACTIVE", "labels": {}}}' > "${FAKE_STATE}"
expect_stop "provision never adopts an existing project" "already exists; nothing was changed" provision
if [ "$(writes)" = "0" ]; then ok "refused provisioning changed nothing"; else bad "refused provisioning wrote: $(calls)"; fi

reset provision
if provision > "${HOME}/provision.out" 2>&1; then ok "provision completes"; else bad "provision: $(tail -5 "${HOME}/provision.out")"; fi
if calls | grep -E '^(storage buckets create|services enable|iam service-accounts (create|add-iam-policy-binding|describe|get-iam-policy|keys list)|iam roles (create|describe)|alpha resource-manager liens list|storage buckets list)' \
    | grep -v -- '--project=parkio-n02-accept-5c2c7f' | grep -q .; then
  bad "a project-scoped call lacks --project"
else
  ok "every project-scoped call names the project"
fi
if calls | grep -q -E '^(config set|auth )'; then bad "provision touched the gcloud configuration or credentials"; else ok "no configuration or credential change"; fi
if calls | grep -q -- '--enable-per-object-retention' && [ "$(calls | grep -c -- '--enable-per-object-retention')" = "1" ] \
    && calls | grep '^storage buckets create gs://parkio-n02-orl-accept' | grep -q -- '--soft-delete-duration=0'; then
  ok "Object Retention Lock only on the test bucket, soft delete off at creation"
else bad "bucket creation flags"; fi
if calls | grep -q -- '--lock-retention-period\|--retention-period'; then bad "Bucket Lock flag used"; else ok "no Bucket Lock"; fi
for f in project-number project-create-time operator billing-account liens-after-orl.json iam-bound-at; do
  [ -s "${N02_STATE_DIR}/${f}" ] || bad "state ${f} not recorded"
done
[ "$(cat "${N02_STATE_DIR}/project-number")" = "123456789012" ] && ok "project number recorded" || bad "project number"
creates_before="$(calls | grep -c -E '^(projects create|storage buckets create|iam (service-accounts|roles) create)')"
if provision > "${HOME}/again.out" 2>&1 && grep -q "resuming on the recorded project" "${HOME}/again.out" \
    && [ "$(calls | grep -c -E '^(projects create|storage buckets create|iam (service-accounts|roles) create)')" = "${creates_before}" ]; then
  ok "a second provision resumes and creates nothing"
else bad "second provision: $(tail -3 "${HOME}/again.out")"; fi
if bash "${OP}" verify > /dev/null 2>&1; then ok "verify passes on the provisioned state"; else bad "verify failed"; fi
fake 's["buckets"]["parkio-n02-orl-accept-20261022-5c2c7f"]["bindings"].append({"role": "roles/storage.admin", "members": ["serviceAccount:n02-reader@parkio-n02-accept-5c2c7f.iam.gserviceaccount.com"]})'
expect_stop "verify catches an extra bucket grant" "binding of n02-reader" bash "${OP}" verify
fake 's["buckets"]["parkio-n02-orl-accept-20261022-5c2c7f"]["bindings"].pop()'
fake 's["liens"].append({"name": "liens/extra", "parent": "projects/123456789012", "origin": "x", "reason": "y", "restrictions": ["resourcemanager.projects.delete"]})'
expect_stop "verify catches a lien that differs from the baseline" "liens differ from the recorded baseline" bash "${OP}" verify

reset provision-resume
echo '{"fail": {"storage buckets add-iam-policy-binding gs://parkio-n02-orl-accept-20261022-5c2c7f": 25}, "lien_delay": 2}' > "${FAKE_STATE}"
expect_stop "a persistent binding failure stops provisioning" "binding n02-writer on" provision
creates_before="$(calls | grep -c '^projects create' || true)"
fake 's["fail"] = {}'
expect_stop "resume refuses another billing account" "recorded billing account differs" \
  env N02_CONFIRM=provision-parkio-n02-accept-5c2c7f N02_BILLING_ACCOUNT=ABCDEF-123456-000000 bash "${OP}" provision
fake 's["project"]["projectNumber"] = "999999999999"'
writes_before="$(writes)"
expect_stop "resume refuses a project that is not the recorded one" "project number differs" provision
[ "$(writes)" = "${writes_before}" ] && ok "a refused resume changes nothing" || bad "a refused resume wrote: $(calls | tail -3)"
fake 's["project"]["projectNumber"] = "123456789012"'
if provision > "${HOME}/resume.out" 2>&1 && grep -q "resuming on the recorded project" "${HOME}/resume.out"; then ok "provision resumes on the recorded project"; else bad "resume: $(tail -3 "${HOME}/resume.out")"; fi
[ "$(calls | grep -c '^projects create')" = "${creates_before}" ] && [ "$(calls | grep -c '^storage buckets create')" = "2" ] \
  && [ "$(calls | grep -c '^iam service-accounts create')" = "4" ] && ok "resume creates nothing twice" || bad "resume duplicated creations"
[ "$(python3 -I -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "${N02_STATE_DIR}/liens-after-orl.json")" = "1" ] \
  && ok "the late lien is recorded after polling" || bad "lien baseline"

# --- run ------------------------------------------------------------------------------------------
reset run
provision > /dev/null 2>&1
SHA="$(git -C "${ROOT}" rev-parse HEAD)"
cat > "${WORK}/bin/fake-gradle" <<'SH'
#!/usr/bin/env bash
# Pretends to be the harness: writes a journal with or without writes into the output directory.
last="${!#}"
out="${last#--args=run }"
mkdir -p "${out}"
if [ "${FAKE_RUN_WRITES:-1}" = "1" ]; then
  printf '%s\n' '{"event":"start"}' '{"event":"write-requested"}' '{"event":"finish"}' > "${out}/journal.jsonl"
  printf '%s\n' "{\"budget\":{\"latestRetainUntil\":\"${FAKE_LATEST:-2026-01-01T00:00:00Z}\"}}" > "${out}/result.json"
else
  printf '%s\n' '{"event":"start"}' '{"event":"stopped"}' > "${out}/journal.jsonl"
fi
exit "${FAKE_RUN_EXIT:-0}"
SH
chmod +x "${WORK}/bin/fake-gradle"
export N02_GRADLE="${WORK}/bin/fake-gradle"
expect_stop "run needs the approved SHA" "N02_APPROVED_SHA must be" env -u N02_APPROVED_SHA bash "${OP}" run
expect_stop "run refuses another checkout" "checkout is not" env N02_APPROVED_SHA="$(printf 'f%.0s' {1..40})" bash "${OP}" run
expect_stop "run waits for IAM propagation" "younger than" env N02_APPROVED_SHA="${SHA}" bash "${OP}" run
echo "$(( $(date -u +%s) - 1000 ))" > "${N02_STATE_DIR}/iam-bound-at"
expect_stop "run needs ADC in the dedicated directory" "no ADC" env N02_APPROVED_SHA="${SHA}" bash "${OP}" run
echo '{"type": "authorized_user", "quota_project_id": "other"}' > "${CLOUDSDK_CONFIG}/application_default_credentials.json"
expect_stop "run needs the ADC quota project" "quota project must be" env N02_APPROVED_SHA="${SHA}" bash "${OP}" run
echo '{"type": "service_account", "quota_project_id": "parkio-n02-accept-5c2c7f"}' > "${CLOUDSDK_CONFIG}/application_default_credentials.json"
expect_stop "run refuses a key file as ADC" "ADC must be a user login" env N02_APPROVED_SHA="${SHA}" bash "${OP}" run
echo '{"type": "authorized_user", "quota_project_id": "parkio-n02-accept-5c2c7f"}' > "${CLOUDSDK_CONFIG}/application_default_credentials.json"
expect_stop "a preflight-only stop does not count" "does not count" env N02_APPROVED_SHA="${SHA}" FAKE_RUN_WRITES=0 FAKE_RUN_EXIT=3 bash "${OP}" run
if N02_APPROVED_SHA="${SHA}" bash "${OP}" run > /dev/null 2>&1 && N02_APPROVED_SHA="${SHA}" FAKE_RUN_EXIT=1 bash "${OP}" run > /dev/null 2>&1; then
  bad "a failing attempt reported success"
else
  ok "attempts report the harness exit code"
fi
expect_stop "no third attempt" "attempts are used" env N02_APPROVED_SHA="${SHA}" bash "${OP}" run
[ -d "${N02_STATE_DIR}/runs/attempt-1" ] && [ -d "${N02_STATE_DIR}/runs/attempt-2" ] && ok "attempts recorded" || bad "attempt records"

# --- cleanup --------------------------------------------------------------------------------------
reset cleanup
provision > /dev/null 2>&1
mkdir -p "${N02_STATE_DIR}/runs/attempt-1"
echo "$(( $(date -u +%s) - 20000 ))" > "${N02_STATE_DIR}/runs/attempt-1.started"
CONFIRM="cleanup-parkio-n02-accept-5c2c7f-123456789012"
future="$(date -u -d '+1 hour' +%FT%TZ)"
echo "{\"budget\":{\"latestRetainUntil\":\"${future}\"}}" > "${N02_STATE_DIR}/runs/attempt-1/result.json"
expect_stop "cleanup needs the confirmation with the project number" "N02_CONFIRM must be cleanup-" env N02_CONFIRM=cleanup-parkio-n02-accept-5c2c7f bash "${OP}" cleanup
expect_stop "cleanup waits for the latest retain-until" "too early" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
rm "${N02_STATE_DIR}/runs/attempt-1/result.json"
echo "{\"run\":{\"latestRetainUntilBound\":\"${future}\"}}" > "${N02_STATE_DIR}/runs/attempt-1/run.json"
expect_stop "without a result the bound in run.json decides" "too early" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
rm "${N02_STATE_DIR}/runs/attempt-1/run.json"
echo "{\"budget\":{\"latestRetainUntil\":\"$(date -u -d '-1 hour' +%FT%TZ)\"}}" > "${N02_STATE_DIR}/runs/attempt-1/result.json"
fake 's["buckets"]["parkio-n02-orl-accept-20261022-5c2c7f"]["objects"] = [{"name": "runs/x/a.json", "generation": 1, "until": int(__import__("time").time()) + 600}]'
expect_stop "cleanup refuses while a generation is retained" "still holds retained generations" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["buckets"]["parkio-n02-orl-accept-20261022-5c2c7f"]["objects"] = [{"name": "runs/x/a.json", "generation": 1, "until": int(__import__("time").time()) - 600}, {"name": "runs/x/a.json", "generation": 2, "until": int(__import__("time").time()) - 60}]'
fake 's["project"]["projectNumber"] = "999999999999"'
expect_stop "cleanup refuses another project number" "project number differs" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["project"]["projectNumber"] = "123456789012"; s["project"]["labels"] = {}'
expect_stop "cleanup refuses an unlabelled project" "label parkio-purpose" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["project"]["labels"] = {"parkio-purpose": "n02-gcs-acceptance", "parkio-issue": "483"}; s["buckets"]["someone-elses-bucket"] = dict(s["buckets"]["parkio-n02-noorl-ctrl-20261022-5c2c7f"], name="someone-elses-bucket")'
expect_stop "cleanup refuses a project with other buckets" "holds other buckets" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 'del s["buckets"]["someone-elses-bucket"]; s["liens"].append({"name": "liens/other", "parent": "projects/123456789012", "origin": "someone", "reason": "keep", "restrictions": ["resourcemanager.projects.delete"]})'
before_destructive="$(writes)"
expect_stop "cleanup refuses an unknown lien" "liens differ from the recorded baseline" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["liens"] = [l for l in s["liens"] if l["name"] != "liens/other"]'
fake 's["liens"][0]["reason"] = "changed"'
expect_stop "cleanup refuses a lien that differs from the baseline" "liens differ from the recorded baseline" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
if [ "$(writes)" = "${before_destructive}" ]; then ok "refused cleanups deleted nothing"; else bad "a refused cleanup deleted something: $(calls | tail -5)"; fi
fake 's["liens"][0]["reason"] = "Object retention is enabled on a bucket"'
expect_stop "cleanup runs only from the approved commit" "checkout is not" env N02_APPROVED_SHA="$(printf 'e%.0s' {1..40})" N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["describe_fail"] = ["parkio-n02-noorl-ctrl-20261022-5c2c7f"]'
expect_stop "a failed bucket describe is not treated as gone" "cannot describe gs://parkio-n02-noorl-ctrl" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
fake 's["describe_fail"] = []; s["delete_noop"] = True'
expect_stop "the lien and project stay while a bucket remains" "buckets remain in parkio-n02-accept-5c2c7f" env N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup
if calls | grep -q -E '^(alpha resource-manager liens delete|projects delete)'; then bad "lien or project deleted with a bucket left"; else ok "nothing past the buckets was deleted"; fi
fake 's["delete_noop"] = False'
if N02_CONFIRM="${CONFIRM}" bash "${OP}" cleanup > "${HOME}/cleanup.out" 2>&1; then ok "cleanup completes (buckets already emptied by the previous attempt)"; else bad "cleanup: $(tail -5 "${HOME}/cleanup.out")"; fi
if calls | grep -q '^projects delete 123456789012 '; then ok "project deleted by its recorded number"; else bad "project not deleted by number"; fi
if calls | grep -q -E '^(auth |config set)'; then bad "cleanup touched credentials"; else ok "cleanup revokes nothing"; fi
python3 -I -c 'import json,sys; s=json.load(open(sys.argv[1])); assert s["project"]["lifecycleState"] == "DELETE_REQUESTED" and not s["buckets"] and not s["accounts"] and not s["liens"] and all(r["deleted"] for r in s["roles"].values())' "${FAKE_STATE}" \
  && ok "everything of the operation is gone" || bad "cleanup left resources: $(cat "${FAKE_STATE}")"

# --- credentials ----------------------------------------------------------------------------------
reset forget
touch "${CLOUDSDK_CONFIG}/credentials.db"
mkdir -p "${HOME}/.config/gcloud" && touch "${HOME}/.config/gcloud/credentials.db"
bash "${OP}" forget-credentials > /dev/null
[ ! -e "${CLOUDSDK_CONFIG}" ] && [ -e "${HOME}/.config/gcloud/credentials.db" ] && ok "only the operation's own configuration is removed" || bad "forget-credentials"
[ ! -s "${FAKE_LOG}" ] && ok "forgetting credentials calls nothing" || bad "forget-credentials called gcloud: $(calls)"

echo "passed=${pass} failed=${fail}"
[ "${fail}" -eq 0 ]
