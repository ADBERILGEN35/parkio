#!/usr/bin/env bash
# Isolated entrypoint tests for F-02: COMPLETE / MinIO / prune fail-closed.
# Runs the real backup scripts against docker/mc/openssl stubs. No live DB,
# MinIO, Azure, or production paths.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
pass=0
fail=0
ok() { echo "PASS $1"; pass=$((pass + 1)); }
bad() { echo "FAIL $1"; fail=$((fail + 1)); }

need() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "ERROR: required command '$1' is missing" >&2
    exit 2
  }
}
need bash
need python3
need gzip
need tar
need openssl
need sha256sum

WORK="$(mktemp -d "${TMPDIR:-/tmp}/parkio-f02.XXXXXX")"
trap 'rm -rf "${WORK}"' EXIT
BIN="${WORK}/bin"
mkdir -p "${BIN}"

REAL_OPENSSL="$(command -v openssl)"
REAL_SHA256SUM="$(command -v sha256sum)"

if ! command -v jq >/dev/null 2>&1; then
  cat > "${BIN}/jq.py" <<'PY'
import json
import sys

vals = {}
args = sys.argv[1:]
i = 0
while i < len(args):
    arg = args[i]
    if arg == "-n":
        i += 1
        continue
    if arg == "--arg":
        vals[args[i + 1]] = args[i + 2]
        i += 3
        continue
    if arg == "--argjson":
        vals[args[i + 1]] = json.loads(args[i + 2])
        i += 3
        continue
    i += 1

if "bucket" in vals and "objectCount" in vals:
    out = {
        "bucket": vals["bucket"],
        "objectCount": vals["objectCount"],
        "path": vals.get("path", ""),
        "clientSideEncryption": vals.get("clientSideEncryption") == 1,
        "artifact": vals.get("artifact", "none"),
        "algorithm": vals.get("algorithm", "none"),
    }
elif "action" in vals and "stamp" in vals:
    out = {
        "schemaVersion": 3,
        "action": vals["action"],
        "timestamp": vals["stamp"],
        "gitSha": vals.get("gitSha", ""),
        "operator": vals.get("operator", ""),
        "envProfile": vals.get("envProfile", ""),
        "deploymentProfile": vals.get("deploymentProfile", ""),
        "destination": vals.get("destDir", ""),
        "databases": vals.get("databases", []),
        "minio": vals.get("minio", {}),
        "databasesOk": vals.get("databasesOk", 0),
        "databasesFailed": vals.get("databasesFailed", 0),
        "minioOk": vals.get("minioOk", 0),
        "retentionDays": vals.get("retentionDays", 14),
        "offsiteRetentionDays": vals.get("offsiteRetentionDays", 14),
        "encryption": {
            "enabled": vals.get("encryptionEnabled") == 1,
            "algorithm": vals.get("encryptionAlgorithm", "none"),
        },
        "offsite": {
            "kind": vals.get("offsiteKind", "none"),
            "uploaded": vals.get("offsiteUploaded") == 1,
        },
        "checksums": {"sha256sums": "SHA256SUMS"},
    }
    if "localPrune" in vals:
        out["localPrune"] = vals["localPrune"]
elif "sealed" in vals and "target" in vals:
    out = {
        "schemaVersion": 1,
        "stamp": vals["stamp"],
        "uploaded": True,
        "uploadedAt": vals["uploadedAt"],
        "offsite": {"kind": vals["kind"], "target": vals["target"]},
        "sealed": {"complete": "COMPLETE", "sha256sums": vals["sealed"]},
    }
else:
    sys.stderr.write("jq shim: unrecognized argument set\n")
    sys.exit(2)
print(json.dumps(out, separators=(",", ":")))
PY
  cat > "${BIN}/jq" <<EOF
#!/usr/bin/env bash
exec python3 "${BIN}/jq.py" "\$@"
EOF
  chmod +x "${BIN}/jq"
fi

cat > "${BIN}/docker" <<'EOF'
#!/usr/bin/env bash
set -e
if [[ "$1" == inspect ]]; then
  if [[ "$*" == *--format* ]]; then
    echo "parkio-backend"
    exit 0
  fi
  exit 0
fi
if [[ "$1" == exec ]]; then
  shift
  while [[ $# -gt 0 && "$1" == -* ]]; do
    shift
  done
  container="${1:-}"
  shift || true
  if [[ "${1:-}" == pg_dump ]]; then
    db=""
    prev=""
    for arg in "$@"; do
      if [[ "${prev}" == "-d" ]]; then
        db="${arg}"
      fi
      prev="${arg}"
    done
    if [[ -n "${FAKE_DUMP_SLEEP:-}" && "${db}" == *parkio_auth* ]]; then
      sleep "${FAKE_DUMP_SLEEP}"
    fi
    if [[ -n "${FAKE_DUMP_FAIL:-}" && "${db}" == *"${FAKE_DUMP_FAIL}"* ]]; then
      echo "pg_dump: simulated failure for ${db}" >&2
      exit 1
    fi
    printf -- '-- Parkio isolated dump stub for %s / %s\nSELECT 1;\n' "${container}" "${db}"
    exit 0
  fi
  cmd="$*"
  if [[ -n "${FAKE_PG_PREFIX:-}" && ( "${cmd}" == *to_regclass* || "${cmd}" == *json_agg* ) ]]; then
    printf '%s\n' "${container}" >> "${FAKE_LEDGER_LOG:-/dev/null}"
    if [[ "${container}" != "${FAKE_PG_PREFIX}-postgres-auth" ]]; then
      echo "Error response from daemon: No such container: ${container}" >&2
      exit 1
    fi
  fi
  if [[ "${cmd}" == *to_regclass* ]]; then
    case "${FAKE_LEDGER:-ok}" in
      absent) echo ""; exit 0 ;;
      query-fail) echo "psql: query failed" >&2; exit 1 ;;
      *) echo "erased_user_tombstones"; exit 0 ;;
    esac
  fi
  if [[ "${cmd}" == *json_agg* ]]; then
    case "${FAKE_LEDGER:-ok}" in
      query-fail) echo "psql: query failed" >&2; exit 1 ;;
      notjson) printf 'not-json\n'; exit 0 ;;
      object) printf '{"authUserId":"x"}\n'; exit 0 ;;
      *) printf '[]\n'; exit 0 ;;
    esac
  fi
  echo "unexpected docker exec: ${cmd}" >&2
  exit 2
fi
if [[ "$1" == run ]]; then
  if [[ -n "${DOCKER_RUN_LOG:-}" ]]; then
    printf '%s\n' "$@" >> "${DOCKER_RUN_LOG}"
    echo "=== end docker run ===" >> "${DOCKER_RUN_LOG}"
  fi
  # Offsite mc through docker (parkio_backup_mc_docker) is the only run that sets MC_DEST:
  # log its target and script, then copy like mc would, so the receipt can be checked.
  mc_dest="" mc_file="" mc_src="" mc_script=""
  args=("$@")
  for ((i = 0; i < ${#args[@]}; i++)); do
    case "${args[i]}" in
      -e)
        case "${args[i + 1]:-}" in
          MC_DEST=*) mc_dest="${args[i + 1]#MC_DEST=}" ;;
          SRC_FILE=*) mc_file="${args[i + 1]#SRC_FILE=}" ;;
        esac
        ;;
      -v) mc_src="${args[i + 1]%%:*}" ;;
      -c) mc_script="${args[i + 1]:-}" ;;
    esac
  done
  if [[ -n "${mc_dest}" ]]; then
    printf 'docker mc MC_DEST=%s\n%s\n=== end script ===\n' "${mc_dest}" "${mc_script}" >> "${OFFSITE_LOG:-/dev/null}"
    if [[ "${mc_script}" == *"mc mirror"* ]]; then
      mkdir -p "${mc_dest}"
      cp -a "${mc_src}/." "${mc_dest}/"
    elif [[ "${mc_script}" == *"mc cp"* ]]; then
      mkdir -p "$(dirname "${mc_dest}")"
      cp "${mc_src}/${mc_file}" "${mc_dest}"
    fi
    exit 0
  fi
  dest=""
  while [[ $# -gt 0 ]]; do
    if [[ "$1" == -v || "$1" == --volume ]]; then
      dest="${2%%:*}"
      shift 2
      continue
    fi
    shift
  done
  case "${FAKE_MINIO_MODE:-ok}" in
    fail-partial)
      mkdir -p "${dest}"
      printf 'partial-object\n' > "${dest}/partial-object"
      echo "mc: simulated mirror failure" >&2
      exit 1
      ;;
    fail-empty)
      echo "mc: simulated empty mirror failure" >&2
      exit 1
      ;;
    ok)
      mkdir -p "${dest}"
      printf 'media-object\n' > "${dest}/object.bin"
      echo 1
      exit 0
      ;;
    *)
      echo "unexpected FAKE_MINIO_MODE=${FAKE_MINIO_MODE}" >&2
      exit 2
      ;;
  esac
fi
echo "unexpected docker invocation: $*" >&2
exit 2
EOF
chmod +x "${BIN}/docker"

cat > "${BIN}/mc" <<'EOF'
#!/usr/bin/env bash
set -e
mkdir -p "$(dirname "${OFFSITE_LOG:-/tmp/parkio-mc.log}")"
echo "mc $*" >> "${OFFSITE_LOG:-/tmp/parkio-mc.log}"
if [[ "${FAKE_MC_FAIL:-0}" == 1 ]]; then
  echo "mc: simulated offsite failure" >&2
  exit 1
fi
if [[ "$1" == mirror ]]; then
  src="$3"
  dest="$4"
  mkdir -p "${dest}"
  cp -a "${src}/." "${dest}/"
  exit 0
fi
if [[ "$1" == cp ]]; then
  mkdir -p "$(dirname "$3")"
  cp "$2" "$3"
  exit 0
fi
exit 0
EOF
chmod +x "${BIN}/mc"

# Fake gcloud over a filesystem bucket: gs://<bucket>/<name> is FAKE_GCS_ROOT/<bucket>/<name>. It serves
# only what the gcs offsite kind may call: storage cp (one source, one destination) and
# storage objects list <url>/** --format=json(name,size), which answers as gcloud 592.0.0 does on the
# production VM: a JSON array of bucket-relative names with integer sizes, and "[]" with exit 0 when
# nothing matches. Each call is logged after the parallel composite upload and prompt settings it ran
# with; any other call (rm, rsync, buckets, ...) exits 2.
# FAKE_GCS_EXISTING=<name> gives that object a foreign live version before the first copy to it;
# FAKE_GCS_FAIL_NAME=<name> fails the upload of that object; FAKE_GCS_SHORT_NAME=<name> stores it one
# byte short; FAKE_GCS_DROP_NAME=<name> (or FAKE_GCS_DROP=1 for all) exits 0 without storing it;
# FAKE_GCS_MISPLACE=1 stores every upload under stamps-elsewhere/; FAKE_GCS_LIST_FAIL=1 fails every listing.
cat > "${BIN}/gcloud" <<'PY'
#!/usr/bin/env python3
import json, os, shutil, sys

args = sys.argv[1:]
with open(os.environ.get("GCLOUD_LOG", os.devnull), "a") as log:
    log.write("pcu=%s prompts=%s %s\n" % (os.environ.get("CLOUDSDK_STORAGE_PARALLEL_COMPOSITE_UPLOAD_ENABLED", "<unset>"),
                                          os.environ.get("CLOUDSDK_CORE_DISABLE_PROMPTS", "<unset>"), " ".join(args)))


def die(code, message):
    sys.stderr.write(message + "\n")
    sys.exit(code)


def local(url):
    if not url.startswith("gs://"):
        die(2, "fake gcloud: not a gs:// URL: " + url)
    return os.path.join(os.environ["FAKE_GCS_ROOT"], url[len("gs://"):])


if args[:2] == ["storage", "cp"]:
    generation, no_clobber, paths = None, False, []
    for arg in args[2:]:
        if arg.startswith("--if-generation-match="):
            generation = arg.split("=", 1)[1]
        elif arg in ("--no-clobber", "-n"):
            no_clobber = True
        elif arg.startswith("-"):
            die(2, "fake gcloud: unexpected cp flag " + arg)
        else:
            paths.append(arg)
    if len(paths) != 2:
        die(2, "fake gcloud: cp takes one source and one destination, got %r" % paths)
    src, dst = paths
    if dst.startswith("gs://") and not src.startswith("gs://"):
        target, name = local(dst), dst.rsplit("/", 1)[-1]
        if name == os.environ.get("FAKE_GCS_FAIL_NAME"):
            die(1, "ERROR: (gcloud.storage.cp) HTTPError 503: simulated failure uploading " + name)
        if name == os.environ.get("FAKE_GCS_EXISTING") and not os.path.exists(target):
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "w") as handle:
                handle.write("foreign-object\n")
        if os.path.exists(target):
            if generation == "0":
                die(1, "ERROR: (gcloud.storage.cp) HTTPError 412: At least one of the pre-conditions you specified did not hold.")
            if no_clobber:
                print("Skipping existing destination item (no-clobber): " + dst)
                sys.exit(0)
        if os.environ.get("FAKE_GCS_DROP") == "1" or name == os.environ.get("FAKE_GCS_DROP_NAME"):
            sys.exit(0)
        if os.environ.get("FAKE_GCS_MISPLACE") == "1":
            target = local(dst.replace("/stamps/", "/stamps-elsewhere/", 1))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copyfile(src, target)
        if name == os.environ.get("FAKE_GCS_SHORT_NAME"):
            with open(target, "r+b") as handle:
                handle.truncate(max(os.path.getsize(target) - 1, 0))
        sys.exit(0)
    if src.startswith("gs://") and not dst.startswith("gs://"):
        if not os.path.isfile(local(src)):
            die(1, "ERROR: (gcloud.storage.cp) The following URLs matched no objects or files: " + src)
        shutil.copyfile(local(src), dst)
        sys.exit(0)
    die(2, "fake gcloud: unsupported cp %s -> %s" % (src, dst))

if args[:3] == ["storage", "objects", "list"]:
    rest = args[3:]
    if len(rest) != 2 or not rest[0].endswith("/**") or rest[1] != "--format=json(name,size)":
        die(2, "fake gcloud: unexpected objects list arguments %r" % rest)
    if os.environ.get("FAKE_GCS_LIST_FAIL") == "1":
        die(1, "ERROR: (gcloud.storage.objects.list) HTTPError 503: simulated listing failure")
    bucket_root = local("gs://" + rest[0][len("gs://"):].split("/", 1)[0])
    found = []
    for walk_root, _, files in os.walk(local(rest[0][:-2])):
        for name in files:
            path = os.path.join(walk_root, name)
            found.append({"name": os.path.relpath(path, bucket_root).replace(os.sep, "/"),
                          "size": os.path.getsize(path)})
    print(json.dumps(sorted(found, key=lambda item: item["name"]), indent=2))
    sys.exit(0)

die(2, "fake gcloud: unexpected call: " + " ".join(args))
PY
chmod +x "${BIN}/gcloud"

cat > "${BIN}/openssl" <<EOF
#!/usr/bin/env bash
set -e
if [[ "\${FAKE_OPENSSL_SEAL_FAIL:-0}" == 1 ]]; then
  for arg in "\$@"; do
    if [[ "\${arg}" == *minio.tar.gz.enc && "\${arg}" != *.sha256 ]]; then
      echo "openssl: simulated MinIO seal failure" >&2
      exit 1
    fi
  done
fi
exec "${REAL_OPENSSL}" "\$@"
EOF
chmod +x "${BIN}/openssl"

cat > "${BIN}/sha256sum" <<EOF
#!/usr/bin/env bash
set -e
if [[ "\${FAKE_INTEGRITY_SHA_FAIL:-0}" == 1 && "\$#" -ge 3 ]]; then
  echo "sha256sum: simulated SHA256SUMS failure" >&2
  exit 1
fi
exec "${REAL_SHA256SUM}" "\$@"
EOF
chmod +x "${BIN}/sha256sum"

REAL_MKTEMP="$(command -v mktemp)"
cat > "${BIN}/mktemp" <<EOF
#!/usr/bin/env bash
if [[ "\${FAKE_RECEIPT_FAIL:-0}" == 1 && "\$*" == *.offsite-receipt.json.* ]]; then
  echo "mktemp: simulated offsite receipt write failure" >&2
  exit 1
fi
exec "${REAL_MKTEMP}" "\$@"
EOF
chmod +x "${BIN}/mktemp"

export PATH="${BIN}:${PATH}"
export PARKIO_DEPLOYMENT_PROFILE=hosted-beta
export BACKUP_PRODUCTION_MODE=1
export BACKUP_ENCRYPT_PASSPHRASE="f02-test-passphrase-not-a-secret"
export BACKUP_OFFSITE_KIND=s3
export MINIO_ROOT_PASSWORD="f02-minio-not-a-secret"
export MINIO_BUCKET=parkio-media
export PARKIO_BACKUP_GIT_SHA="f02test000000000000000000000000000000000"

reset_fakes() {
  export FAKE_MINIO_MODE=ok
  export FAKE_LEDGER=ok
  export FAKE_MC_FAIL=0
  export FAKE_OPENSSL_SEAL_FAIL=0
  export FAKE_INTEGRITY_SHA_FAIL=0
  export FAKE_RECEIPT_FAIL=0
  unset FAKE_DUMP_FAIL FAKE_DUMP_SLEEP FAKE_GCS_EXISTING FAKE_GCS_FAIL_NAME FAKE_GCS_SHORT_NAME FAKE_GCS_LIST_FAIL \
    FAKE_GCS_DROP FAKE_GCS_DROP_NAME FAKE_GCS_MISPLACE
}

plant_old_good() {
  local backup_dir="$1"
  local old="${backup_dir}/2020-01-01T00-00-00Z"
  mkdir -p "${old}"
  printf 'keep-previous-good\n' > "${old}/COMPLETE"
  printf '[]\n' > "${old}/erasure-tombstones.json"
  python3 -c 'import os,time,sys; p=sys.argv[1]; t=time.time()-20*86400; os.utime(p,(t,t))' "${old}"
  echo "${old}"
}

latest_stamp_dir() {
  local backup_dir="$1"
  python3 -c '
import os, sys
root = sys.argv[1]
dirs = [os.path.join(root, name) for name in os.listdir(root)
        if os.path.isdir(os.path.join(root, name)) and name != "2020-01-01T00-00-00Z"]
if not dirs:
    sys.exit(1)
print(max(dirs, key=os.path.getmtime))
' "${backup_dir}"
}

assert_no_usable_complete() {
  local stamp="$1"
  if [ -f "${stamp}/COMPLETE" ]; then
    return 1
  fi
  return 0
}

assert_not_uploaded() {
  local offsite="$1"
  if find "${offsite}" -name COMPLETE -type f 2>/dev/null | grep -q .; then
    return 1
  fi
  return 0
}

metric() {
  local file="$1"
  local name="$2"
  awk -v n="${name}" '$1 ~ n { print $2; exit }' "${file}"
}

# U14: the post-upload receipt lives beside the stamp directory, names where the stamp
# was uploaded and binds the sealed digest. The stamp itself still matches SHA256SUMS
# exactly (no extra file inside it), COMPLETE still binds SHA256SUMS, and the remote
# COMPLETE is the local one. Prints the first problem and returns 1.
# Usage: check_receipt <stamp dir> <remote dir holding <stamp>/> [kind (s3)] [target base (remote dir)]
check_receipt() {
  python3 - "$@" <<'PY'
import hashlib, json, pathlib, re, sys

stamp, dest = pathlib.Path(sys.argv[1]), sys.argv[2]
kind = sys.argv[3] if len(sys.argv) > 3 else "s3"
target_base = sys.argv[4] if len(sys.argv) > 4 else dest
receipt = stamp.parent / (stamp.name + ".offsite-receipt.json")

def fail(message):
    print(message)
    sys.exit(1)

if not receipt.is_file():
    fail(f"no receipt beside {stamp.name}")
data = json.loads(receipt.read_text())
digest = hashlib.sha256((stamp / "SHA256SUMS").read_bytes()).hexdigest()
expected = {
    "schemaVersion": 1,
    "stamp": stamp.name,
    "uploaded": True,
    "offsite": {"kind": kind, "target": f"{target_base}/{stamp.name}"},
    "sealed": {"complete": "COMPLETE", "sha256sums": digest},
}
for key, value in expected.items():
    if data.get(key) != value:
        fail(f"receipt {key}={data.get(key)!r}, expected {value!r}")
if not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", str(data.get("uploadedAt"))):
    fail(f"receipt uploadedAt={data.get('uploadedAt')!r} is not a UTC timestamp")
complete = dict(line.split("=", 1) for line in (stamp / "COMPLETE").read_text().splitlines() if "=" in line)
if complete.get("sha256sums") != digest:
    fail("COMPLETE no longer binds SHA256SUMS")
listed = {}
for line in (stamp / "SHA256SUMS").read_text().splitlines():
    value, name = line.split(None, 1)
    listed[name.lstrip("*").removeprefix("./")] = value
present = {path.relative_to(stamp).as_posix() for path in stamp.rglob("*") if path.is_file()}
present -= {"SHA256SUMS", "COMPLETE"}
if present != set(listed):
    fail(f"stamp files differ from SHA256SUMS: extra={sorted(present - set(listed))}")
for name, value in listed.items():
    if hashlib.sha256((stamp / name).read_bytes()).hexdigest() != value:
        fail(f"{name} no longer matches SHA256SUMS")
if (pathlib.Path(dest) / stamp.name / "COMPLETE").read_bytes() != (stamp / "COMPLETE").read_bytes():
    fail("remote COMPLETE differs from the local COMPLETE")
PY
}

no_receipt() {
  ! find "$(dirname "$1")" -maxdepth 1 -name "$(basename "$1").offsite-receipt.json*" | grep -q .
}

manifest_uploaded() {
  python3 -c 'import json,sys; print(str(json.load(open(sys.argv[1]))["offsite"]["uploaded"]).lower())' "$1"
}

manifest_local_prune() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("localPrune", "<absent>"))' "$1" 2>/dev/null \
    || echo "<unreadable>"
}

# Extra arguments go to backup-hosted-beta.sh. CASE_MC_URL=<url> selects the docker mc path.
run_hosted() {
  local case_dir="$1"
  shift
  mkdir -p "${case_dir}/backups" "${case_dir}/artifacts" "${case_dir}/textfile" "${case_dir}/offsite/parkio-backups"
  export BACKUP_DIR="${case_dir}/backups"
  export PARKIO_BACKUP_ARTIFACT_DIR="${case_dir}/artifacts"
  export PARKIO_PROMETHEUS_TEXTFILE_DIR="${case_dir}/textfile"
  export BACKUP_MC_DEST="${case_dir}/offsite/parkio-backups"
  export BACKUP_RETENTION_DAYS=14
  export OFFSITE_LOG="${case_dir}/mc.log"
  unset BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL
  unset PARKIO_ENV_FILE
  if [ -n "${CASE_MC_URL:-}" ]; then
    export BACKUP_MC_URL="${CASE_MC_URL}"
  fi
  bash "${ROOT}/scripts/backup-hosted-beta.sh" "$@"
}

# The gcs kind against the fake gcloud (first on PATH). The synthetic bucket lives in
# <case>/gcs; CASE_GCS_DEST, when set (even empty), replaces the destination.
GCS_DEST=gs://parkio-synthetic-backups/stamps
run_hosted_gcs() {
  local case_dir="$1" rc=0
  shift
  mkdir -p "${case_dir}/gcs"
  export BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${CASE_GCS_DEST-${GCS_DEST}}"
  export FAKE_GCS_ROOT="${case_dir}/gcs" GCLOUD_LOG="${case_dir}/gcloud.log"
  run_hosted "${case_dir}" "$@" || rc=$?
  export BACKUP_OFFSITE_KIND=s3
  unset BACKUP_GCS_DEST FAKE_GCS_ROOT GCLOUD_LOG
  return "${rc}"
}

# The gcloud calls of one successful gcs run, in order: one create-only upload per stamp file except
# COMPLETE (by name), a listing, COMPLETE, a listing. Every call disables parallel composite uploads
# and prompts.
check_gcloud_calls() { # log stamp-dir
  python3 - "$1" "$2" "${GCS_DEST}" <<'PY'
import os, sys

log, stamp, dest = sys.argv[1:]
name = os.path.basename(stamp)
if not os.path.isfile(log):
    print("no gcloud call was logged")
    sys.exit(1)
calls = [line.rstrip("\n") for line in open(log) if line.strip()]
if any(not call.startswith("pcu=False prompts=1 ") for call in calls):
    print("a gcloud call ran without CLOUDSDK_STORAGE_PARALLEL_COMPOSITE_UPLOAD_ENABLED=False "
          "and CLOUDSDK_CORE_DISABLE_PROMPTS=1")
    sys.exit(1)
calls = [call.split(" ", 2)[2] for call in calls]
upload = "storage cp --if-generation-match=0 {0}/{1} {2}/{3}/{1}"
listing = "storage objects list %s/%s/** --format=json(name,size)" % (dest, name)
expected = [upload.format(stamp, f, dest, name) for f in sorted(os.listdir(stamp)) if f != "COMPLETE"]
expected += [listing, upload.format(stamp, "COMPLETE", dest, name), listing]
if calls != expected:
    extra = [call for call in calls if call not in expected]
    print("%d gcloud calls, expected %d; first unexpected: %r" % (len(calls), len(expected), extra[:1]))
    sys.exit(1)
PY
}

run_db_only() {
  local case_dir="$1"
  mkdir -p "${case_dir}/backups" "${case_dir}/offsite/parkio-backups"
  export BACKUP_DIR="${case_dir}/backups"
  export BACKUP_MC_DEST="${case_dir}/offsite/parkio-backups"
  export BACKUP_RETENTION_DAYS=14
  export OFFSITE_LOG="${case_dir}/mc.log"
  unset BACKUP_DEST_DIR BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL
  unset PARKIO_ENV_FILE
  bash "${ROOT}/scripts/backup-databases.sh"
}

# --- trap / stale COMPLETE marker ---
# shellcheck source=lib/backup-common.sh
source "${ROOT}/scripts/lib/backup-common.sh"
stale="${WORK}/stale-stamp"
mkdir -p "${stale}"
printf 'bogus\n' > "${stale}/COMPLETE"
printf 'tmp\n' > "${stale}/.COMPLETE.aaaaaa"
DEST_DIR="${stale}"
PARKIO_BACKUP_FINALIZED=0
parkio_backup_clear_unfinalized_complete
if [ ! -f "${stale}/COMPLETE" ] && [ ! -f "${stale}/.COMPLETE.aaaaaa" ]; then
  ok "unfinalized trap removes COMPLETE and stale .COMPLETE.* markers"
else
  bad "unfinalized trap must clear COMPLETE markers"
fi
printf 'keep\n' > "${stale}/COMPLETE"
PARKIO_BACKUP_FINALIZED=1
parkio_backup_clear_unfinalized_complete
if [ -f "${stale}/COMPLETE" ]; then
  ok "finalized COMPLETE is left in place by the trap"
else
  bad "finalized COMPLETE must survive the EXIT trap"
fi
unset DEST_DIR
PARKIO_BACKUP_FINALIZED=0

# --- receipt writer on its own (U14) ---
unit="${WORK}/receipt-unit"
mkdir -p "${unit}/2026-01-01T00-00-00Z" "${unit}/2026-01-02T00-00-00Z"
printf 'payload\n' > "${unit}/2026-01-01T00-00-00Z/data.bin"
parkio_backup_write_stamp_integrity "${unit}/2026-01-01T00-00-00Z" "2026-01-01T00-00-00Z"
if (
  export BACKUP_OFFSITE_KIND=azure BACKUP_AZURE_CONTAINER=parkio-backups BACKUP_AZURE_STORAGE_ACCOUNT=u14synthacct
  parkio_backup_write_offsite_receipt "${unit}/2026-01-01T00-00-00Z"
) && python3 - "${unit}/2026-01-01T00-00-00Z.offsite-receipt.json" <<'PY'
import json, sys
text = open(sys.argv[1]).read()
assert json.loads(text)["offsite"] == {"kind": "azure", "target": "azure://parkio-backups/2026-01-01T00-00-00Z"}
assert "u14synthacct" not in text
PY
then
  ok "azure receipt names azure://<container>/<stamp> and not the storage account"
else
  bad "azure receipt must name azure://<container>/<stamp> without the storage account"
fi
if ! (export BACKUP_MC_DEST=offsite/parkio-backups; parkio_backup_write_offsite_receipt "${unit}/2026-01-02T00-00-00Z") 2>/dev/null \
  && ! (export BACKUP_OFFSITE_KIND=none; parkio_backup_write_offsite_receipt "${unit}/2026-01-02T00-00-00Z") 2>/dev/null \
  && no_receipt "${unit}/2026-01-02T00-00-00Z"; then
  ok "no receipt for a stamp without COMPLETE or for offsite kind none"
else
  bad "the receipt writer must refuse an unsealed stamp and offsite kind none"
fi

# --- full success ---
reset_fakes
case_ok="${WORK}/full-ok"
old_ok="$(plant_old_good "${case_ok}/backups")"
# Receipts whose stamp is gone (after prune) must go too; so must a leftover temp file.
printf '{}\n' > "${old_ok}.offsite-receipt.json"
printf 'partial\n' > "${old_ok}.offsite-receipt.json.AbC123"
printf '{}\n' > "${case_ok}/backups/2019-12-31T00-00-00Z.offsite-receipt.json"
if run_hosted "${case_ok}"; then
  ok "full production path exits 0"
else
  bad "full production path must succeed"
fi
stamp_ok="$(latest_stamp_dir "${case_ok}/backups")"
if [ -f "${stamp_ok}/COMPLETE" ] && [ -f "${stamp_ok}/minio.tar.gz.enc" ] && [ ! -d "${stamp_ok}/minio" ]; then
  ok "full success writes COMPLETE and sealed MinIO only"
else
  bad "full success must seal MinIO and write COMPLETE"
fi
dump_count="$(find "${stamp_ok}" -maxdepth 1 -name '*.sql.gz.enc' | wc -l | tr -d ' ')"
if [ "${dump_count}" = "10" ]; then
  ok "full success dumped 10 encrypted databases"
else
  bad "full success must dump 10 databases (found ${dump_count})"
fi
if find "${case_ok}/offsite" -name COMPLETE -type f | grep -q .; then
  ok "full success uploaded COMPLETE"
else
  bad "full success must upload COMPLETE"
fi
if [ -f "${case_ok}/artifacts/backup-current.json" ]; then
  ok "full success updates backup-current.json"
else
  bad "full success must publish backup-current.json"
fi
ms="$(metric "${case_ok}/textfile/parkio_backup.prom" 'parkio_backup_last_success')"
os="$(metric "${case_ok}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success')"
if [ "${ms}" = "1" ] && [ "${os}" = "1" ]; then
  ok "full success metrics report local and offsite success"
else
  bad "full success metrics must be last_success=1 offsite=1 (got ${ms}/${os})"
fi
if why="$(check_receipt "${stamp_ok}" "${case_ok}/offsite/parkio-backups")"; then
  ok "full success writes a receipt beside the stamp that binds the unchanged sealed stamp"
else
  bad "full success receipt: ${why}"
fi
sealed_flag="$(manifest_uploaded "${stamp_ok}/backup-manifest.json")"
live_flag="$(manifest_uploaded "${case_ok}/artifacts/backup-current.json")"
if [ "${sealed_flag}" = "false" ] && [ "${live_flag}" = "true" ]; then
  ok "sealed manifest keeps its seal-time uploaded=false; live manifest and receipt say uploaded"
else
  bad "manifest offsite.uploaded must be sealed=false live=true (got ${sealed_flag}/${live_flag})"
fi
if [ ! -d "${old_ok}" ] && no_receipt "${old_ok}" \
  && no_receipt "${case_ok}/backups/2019-12-31T00-00-00Z"; then
  ok "prune removes receipts and receipt temp files whose stamp is gone"
else
  bad "prune must remove orphan receipts (old stamp present: $([ -d "${old_ok}" ] && echo yes || echo no))"
fi
if [ -f "${old_ok}/COMPLETE" ]; then
  ok "recent previous-good stamp is retained on success when not expired by clock"
else
  # The planted stamp is 20 days old; success prune may remove it. That is
  # retention, not the failed-run defect. Re-plant for failure cases below.
  ok "success-path prune of expired stamps is allowed"
fi

# --- #520 overwrite boundary (s3 kind): the upload writes only under <dest>/<new stamp> ---
# mc mirror without --remove deletes nothing; every write names the new stamp's prefix.
dest_ok="${case_ok}/offsite/parkio-backups/$(basename "${stamp_ok}")"
mc_mirror=0
mc_copy=0
mc_other=""
while IFS= read -r call; do
  case "${call}" in
    "") ;;
    *--remove*) mc_other="${mc_other} | ${call}" ;;
    "mc mirror --overwrite "*" ${dest_ok}") mc_mirror=$((mc_mirror + 1)) ;;
    "mc cp "*" ${dest_ok}/COMPLETE") mc_copy=$((mc_copy + 1)) ;;
    *) mc_other="${mc_other} | ${call}" ;;
  esac
done < "${case_ok}/mc.log"
if [ "${mc_mirror}" -eq 1 ] && [ "${mc_copy}" -eq 1 ] && [ -z "${mc_other}" ]; then
  ok "s3 upload writes only under the new stamp's prefix and removes nothing"
else
  bad "s3 upload must stay under the new stamp's prefix (mirror=${mc_mirror} cp=${mc_copy}${mc_other})"
fi

# --- #520: --no-prune keeps every local stamp on a fully successful run ---
# While offsite fails, no run prunes, so expired stamps that never reached offsite pile
# up. The first run against a working target must be able to leave all of them in place.
reset_fakes
case_np="${WORK}/full-ok-no-prune"
old_np="$(plant_old_good "${case_np}/backups")"
printf '{}\n' > "${old_np}.offsite-receipt.json"
printf '{}\n' > "${case_np}/backups/2019-12-31T00-00-00Z.offsite-receipt.json"
before_np=("${case_np}/backups"/*)
rc=0
run_hosted "${case_np}" --no-prune > "${case_np}.log" 2>&1 || rc=$?
stamp_np="$(latest_stamp_dir "${case_np}/backups" || true)"
ms="$(metric "${case_np}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
os="$(metric "${case_np}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
why=""
if [ "${rc}" -eq 0 ] && [ -n "${stamp_np}" ] && [ "${ms}" = "1" ] && [ "${os}" = "1" ] \
  && [ -f "${case_np}/artifacts/backup-current.json" ] \
  && why="$(check_receipt "${stamp_np}" "${case_np}/offsite/parkio-backups")"; then
  ok "--no-prune run seals, uploads, writes its receipt and reports success"
else
  bad "--no-prune run must succeed like a default run (rc=${rc} metrics=${ms:-none}/${os:-none} ${why})"
fi
kept_np=1
for entry in "${before_np[@]}"; do
  [ -e "${entry}" ] || kept_np=0
done
if [ "${rc}" -eq 0 ] && [ "${kept_np}" -eq 1 ] && [ -f "${old_np}/COMPLETE" ]; then
  ok "--no-prune keeps the expired stamp and every receipt after a successful run"
else
  bad "--no-prune must delete nothing on success (rc=${rc}, all entries kept: ${kept_np})"
fi
if grep -qx 'localPrune=skipped' "${case_np}.log" \
  && grep -q '^Local prune skipped (--no-prune)' "${case_np}.log"; then
  ok "--no-prune logs localPrune=skipped and the skipped prune"
else
  bad "--no-prune must log localPrune=skipped and the skipped prune"
fi
sealed_np="<no stamp>"
if [ -n "${stamp_np}" ]; then
  sealed_np="$(manifest_local_prune "${stamp_np}/backup-manifest.json")"
fi
live_np="$(manifest_local_prune "${case_np}/artifacts/backup-current.json")"
sealed_ok="$(manifest_local_prune "${stamp_ok}/backup-manifest.json")"
live_ok="$(manifest_local_prune "${case_ok}/artifacts/backup-current.json")"
if [ "${sealed_np}" = skipped ] && [ "${live_np}" = skipped ] \
  && [ "${sealed_ok}" = after-success ] && [ "${live_ok}" = after-success ]; then
  ok "manifests record localPrune=skipped with --no-prune and after-success by default"
else
  bad "manifest localPrune must be skipped/after-success (got ${sealed_np}/${live_np} and ${sealed_ok}/${live_ok})"
fi

# --- #520 review: the env file cannot change the per-run switches ---
# Arguments are parsed before the env file is sourced (set -a). A NO_PRUNE or DRY_RUN line in
# it must neither cancel --no-prune / --dry-run nor turn them on for a run without the flag.
reset_fakes
case_env0="${WORK}/env-no-prune-0"
old_env0="$(plant_old_good "${case_env0}/backups")"
printf 'NO_PRUNE=0\n' > "${case_env0}.env"
rc=0
run_hosted "${case_env0}" --env-file "${case_env0}.env" --no-prune > "${case_env0}.log" 2>&1 || rc=$?
if [ "${rc}" -eq 0 ] && [ -f "${old_env0}/COMPLETE" ] && grep -qx 'localPrune=skipped' "${case_env0}.log"; then
  ok "--no-prune keeps stamps when the env file says NO_PRUNE=0"
else
  bad "an env file NO_PRUNE=0 must not cancel --no-prune (rc=${rc}, old stamp kept: $([ -d "${old_env0}" ] && echo yes || echo no))"
fi
reset_fakes
case_env1="${WORK}/env-no-prune-1"
old_env1="$(plant_old_good "${case_env1}/backups")"
printf 'NO_PRUNE=1\n' > "${case_env1}.env"
rc=0
run_hosted "${case_env1}" --env-file "${case_env1}.env" > "${case_env1}.log" 2>&1 || rc=$?
if [ "${rc}" -eq 0 ] && [ ! -d "${old_env1}" ] && grep -qx 'localPrune=after-success' "${case_env1}.log" \
  && grep -q '^WARN: .*NO_PRUNE' "${case_env1}.log"; then
  ok "an env file NO_PRUNE=1 is ignored with a warning; the default prune still runs"
else
  bad "an env file NO_PRUNE=1 must not turn the skip on (rc=${rc}, old stamp kept: $([ -d "${old_env1}" ] && echo yes || echo no))"
fi
reset_fakes
case_envdry="${WORK}/env-dry-run-0"
old_envdry="$(plant_old_good "${case_envdry}/backups")"
printf 'DRY_RUN=0\n' > "${case_envdry}.env"
rc=0
run_hosted "${case_envdry}" --env-file "${case_envdry}.env" --dry-run > "${case_envdry}.log" 2>&1 || rc=$?
dirs_envdry="$(find "${case_envdry}/backups" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')"
if [ "${rc}" -eq 0 ] && [ "${dirs_envdry}" = 1 ] && [ -f "${old_envdry}/COMPLETE" ] \
  && grep -qx 'dryRun=1' "${case_envdry}.log"; then
  ok "--dry-run stays a dry run when the env file says DRY_RUN=0"
else
  bad "an env file DRY_RUN=0 must not turn --dry-run into a real run (rc=${rc}, stamp dirs=${dirs_envdry})"
fi

# --- #612: the env file cannot turn the MinIO step of a real run into a dry run ---
# backup-minio.sh re-sources the same env file. A DRY_RUN=1 line there skipped the mirror while
# the orchestrator ran for real, so the run refused COMPLETE and exited 1.
reset_fakes
case_envdry1="${WORK}/env-dry-run-1"
printf 'DRY_RUN=1\n' > "${case_envdry1}.env"
rc=0
run_hosted "${case_envdry1}" --env-file "${case_envdry1}.env" > "${case_envdry1}.log" 2>&1 || rc=$?
stamp_envdry1="$(latest_stamp_dir "${case_envdry1}/backups" || true)"
ms="$(metric "${case_envdry1}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_envdry1}" ] && [ -f "${stamp_envdry1}/COMPLETE" ] \
  && [ -f "${stamp_envdry1}/minio.tar.gz.enc" ] && [ "${ms}" = "1" ] \
  && grep -qx 'dryRun=0' "${case_envdry1}.log" \
  && grep -qE '^MinIO backup -> .*, dryRun=0\)$' "${case_envdry1}.log" \
  && ! grep -q '^DRY-RUN: would mirror' "${case_envdry1}.log" \
  && grep -q '^WARN: ignoring DRY_RUN' "${case_envdry1}.log"; then
  ok "an env file DRY_RUN=1 is ignored by the orchestrator and its MinIO step; the run completes"
else
  bad "an env file DRY_RUN=1 must not skip the MinIO mirror of a real run (rc=${rc}, metric=${ms:-none})"
fi
# backup-minio.sh on its own: like the orchestrator, it takes --dry-run from its command line only.
reset_fakes
case_mdry="${WORK}/minio-env-dry-run"
mkdir -p "${case_mdry}/stamp"
printf 'DRY_RUN=1\n' > "${case_mdry}.env"
rc=0
bash "${ROOT}/scripts/backup-minio.sh" "${case_mdry}/stamp" --env-file "${case_mdry}.env" \
  > "${case_mdry}.out" 2> "${case_mdry}.err" || rc=$?
if [ "${rc}" -eq 0 ] && [ "$(tail -1 "${case_mdry}.out")" = 1 ] \
  && [ -f "${case_mdry}/stamp/minio/parkio-media/object.bin" ] \
  && grep -q '^WARN: ignoring DRY_RUN' "${case_mdry}.err"; then
  ok "backup-minio.sh ignores an env file DRY_RUN=1 with a warning and mirrors"
else
  bad "backup-minio.sh must not dry-run on an env file DRY_RUN=1 (rc=${rc})"
fi
rm -rf "${case_mdry}/stamp"
mkdir -p "${case_mdry}/stamp"
printf 'DRY_RUN=0\n' > "${case_mdry}.env"
rc=0
bash "${ROOT}/scripts/backup-minio.sh" "${case_mdry}/stamp" --env-file "${case_mdry}.env" --dry-run \
  > "${case_mdry}.out" 2> "${case_mdry}.err" || rc=$?
if [ "${rc}" -eq 0 ] && [ "$(tail -1 "${case_mdry}.out")" = 0 ] && [ ! -e "${case_mdry}/stamp/minio" ] \
  && grep -q '^DRY-RUN: would mirror' "${case_mdry}.err"; then
  ok "backup-minio.sh --dry-run stays a dry run when the env file says DRY_RUN=0"
else
  bad "an env file DRY_RUN=0 must not turn backup-minio.sh --dry-run into a mirror (rc=${rc})"
fi

# --- #612: the env file cannot change --operator or --artifact-dir, or their defaults ---
# OPERATOR or ARTIFACT_DIR lines in the env file replaced the flags' values, so the manifest
# named another operator and went to another directory.
manifest_operator() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["operator"])' "$1" 2>/dev/null \
    || echo "<unreadable>"
}
reset_fakes
case_who="${WORK}/env-operator-artifact-dir"
printf 'OPERATOR=env-file-operator\nARTIFACT_DIR=%s\n' "${case_who}/env-artifacts" > "${case_who}.env"
rc=0
run_hosted "${case_who}" --env-file "${case_who}.env" --operator cli-operator \
  --artifact-dir "${case_who}/cli-artifacts" > "${case_who}.log" 2>&1 || rc=$?
who="$(manifest_operator "${case_who}/cli-artifacts/backup-current.json")"
if [ "${rc}" -eq 0 ] && [ "${who}" = cli-operator ] && [ ! -e "${case_who}/env-artifacts" ] \
  && grep -qx 'operator=cli-operator' "${case_who}.log" \
  && grep -q '^WARN: ignoring OPERATOR' "${case_who}.log" \
  && grep -q '^WARN: ignoring ARTIFACT_DIR' "${case_who}.log"; then
  ok "--operator and --artifact-dir win over OPERATOR and ARTIFACT_DIR in the env file, with warnings"
else
  bad "the env file must not replace --operator or --artifact-dir (rc=${rc}, manifest operator=${who})"
fi
reset_fakes
case_who0="${WORK}/env-operator-artifact-dir-defaults"
printf 'OPERATOR=env-file-operator\nARTIFACT_DIR=%s\n' "${case_who0}/env-artifacts" > "${case_who0}.env"
rc=0
PARKIO_BACKUP_OPERATOR=caller-operator \
  run_hosted "${case_who0}" --env-file "${case_who0}.env" > "${case_who0}.log" 2>&1 || rc=$?
who0="$(manifest_operator "${case_who0}/artifacts/backup-current.json")"
if [ "${rc}" -eq 0 ] && [ "${who0}" = caller-operator ] && [ ! -e "${case_who0}/env-artifacts" ] \
  && grep -qx 'operator=caller-operator' "${case_who0}.log"; then
  ok "without the flags, PARKIO_BACKUP_OPERATOR and PARKIO_BACKUP_ARTIFACT_DIR still set them, not the env file"
else
  bad "the env file must not replace the --operator / --artifact-dir defaults (rc=${rc}, manifest operator=${who0})"
fi

# --- #520 review: the env file cannot undo what the orchestrator hands backup-databases.sh ---
# The child re-sources the same env file. BACKUP_SKIP_MC_UPLOAD= there must not make it upload
# and prune on its own, and BACKUP_DEST_DIR there must not move its dumps into another stamp.
reset_fakes
case_skip="${WORK}/env-skip-upload-no-prune"
old_skip="$(plant_old_good "${case_skip}/backups")"
printf 'BACKUP_SKIP_MC_UPLOAD=\n' > "${case_skip}.env"
rc=0
run_hosted "${case_skip}" --env-file "${case_skip}.env" --no-prune > "${case_skip}.log" 2>&1 || rc=$?
if [ "${rc}" -eq 0 ] && [ -f "${old_skip}/COMPLETE" ] \
  && grep -q '^Local prune deferred to orchestrator' "${case_skip}.log"; then
  ok "with BACKUP_SKIP_MC_UPLOAD= in the env file, --no-prune still deletes nothing"
else
  bad "the child must defer its prune under --no-prune (rc=${rc}, old stamp kept: $([ -d "${old_skip}" ] && echo yes || echo no))"
fi
reset_fakes
case_skipok="${WORK}/env-skip-upload-default"
mkdir -p "${case_skipok}"
printf 'BACKUP_SKIP_MC_UPLOAD=\n' > "${case_skipok}.env"
rc=0
run_hosted "${case_skipok}" --env-file "${case_skipok}.env" > "${case_skipok}.log" 2>&1 || rc=$?
mirrors_skipok="$(grep -c '^mc mirror ' "${case_skipok}/mc.log" 2>/dev/null || true)"
if [ "${rc}" -eq 0 ] && [ "${mirrors_skipok}" = 1 ] \
  && grep -q '^Local prune deferred to orchestrator' "${case_skipok}.log"; then
  ok "with BACKUP_SKIP_MC_UPLOAD= in the env file, the child neither uploads nor prunes"
else
  bad "the child must defer upload and prune to the orchestrator (rc=${rc}, mc mirror calls=${mirrors_skipok:-0})"
fi
reset_fakes
export FAKE_MINIO_MODE=fail-partial
case_skipfail="${WORK}/env-skip-upload-minio-fail"
old_skipfail="$(plant_old_good "${case_skipfail}/backups")"
printf 'BACKUP_SKIP_MC_UPLOAD=\n' > "${case_skipfail}.env"
rc=0
run_hosted "${case_skipfail}" --env-file "${case_skipfail}.env" > "${case_skipfail}.log" 2>&1 || rc=$?
if [ "${rc}" -ne 0 ] && [ -f "${old_skipfail}/COMPLETE" ] \
  && grep -q '^Local prune deferred to orchestrator' "${case_skipfail}.log"; then
  ok "with BACKUP_SKIP_MC_UPLOAD= in the env file, a later MinIO failure still prunes nothing"
else
  bad "a MinIO failure must leave the previous stamp (rc=${rc}, old stamp kept: $([ -d "${old_skipfail}" ] && echo yes || echo no))"
fi
reset_fakes
case_dest="${WORK}/env-dest-dir"
old_dest="$(plant_old_good "${case_dest}/backups")"
printf 'BACKUP_DEST_DIR=%s\n' "${old_dest}" > "${case_dest}.env"
before_dest="$(ls -A "${old_dest}" | LC_ALL=C sort | tr '\n' ' ')"
rc=0
run_hosted "${case_dest}" --env-file "${case_dest}.env" --no-prune > "${case_dest}.log" 2>&1 || rc=$?
after_dest="$(ls -A "${old_dest}" 2>/dev/null | LC_ALL=C sort | tr '\n' ' ')"
if [ "${rc}" -eq 0 ] && [ "${before_dest}" = "${after_dest}" ]; then
  ok "BACKUP_DEST_DIR in the env file does not send the dumps into an existing stamp"
else
  bad "the dumps must go to the new stamp (rc=${rc}, old stamp before: ${before_dest}after: ${after_dest})"
fi

# --- #639: the env file cannot move the MinIO mirror (DEST_DIR), or the orchestrator's checkout and
# env file (ROOT, ENV_FILE), and cannot reach the private arg_* names they come from ---
# backup-minio.sh on its own: a DEST_DIR line moved the plaintext mirror out of the stamp.
reset_fakes
case_mdest="${WORK}/minio-env-dest-dir"
mkdir -p "${case_mdest}/stamp"
printf 'DEST_DIR=%s\n' "${case_mdest}/elsewhere" > "${case_mdest}.env"
rc=0
bash "${ROOT}/scripts/backup-minio.sh" "${case_mdest}/stamp" --env-file "${case_mdest}.env" \
  > "${case_mdest}.out" 2> "${case_mdest}.err" || rc=$?
if [ "${rc}" -eq 0 ] && [ "$(tail -1 "${case_mdest}.out")" = 1 ] \
  && [ -f "${case_mdest}/stamp/minio/parkio-media/object.bin" ] && [ ! -e "${case_mdest}/elsewhere" ] \
  && grep -qx 'WARN: ignoring DEST_DIR from the environment or env file; only the destination argument counts.' \
    "${case_mdest}.err"; then
  ok "backup-minio.sh ignores an env file DEST_DIR with a warning and mirrors into its destination argument"
else
  bad "an env file DEST_DIR must not move the backup-minio.sh mirror (rc=${rc})"
fi
# A line that assigns a private name stops the step before it mirrors anything.
for line in "arg_dest_dir=${case_mdest}/elsewhere" "arg_dry_run=1"; do
  name="${line%%=*}"
  rm -rf "${case_mdest}/stamp" "${case_mdest}/elsewhere"
  mkdir -p "${case_mdest}/stamp"
  printf '%s\n' "${line}" > "${case_mdest}.env"
  rc=0
  bash "${ROOT}/scripts/backup-minio.sh" "${case_mdest}/stamp" --env-file "${case_mdest}.env" \
    > "${case_mdest}.out" 2> "${case_mdest}.err" || rc=$?
  if [ "${rc}" -eq 1 ] && [ ! -e "${case_mdest}/stamp/minio" ] && [ ! -e "${case_mdest}/elsewhere" ] \
    && grep -qF "${name}: readonly variable" "${case_mdest}.err"; then
    ok "backup-minio.sh stops at an env file ${name}= line (exit 1, nothing mirrored)"
  else
    bad "an env file ${name}= line must stop backup-minio.sh before the mirror (rc=${rc})"
  fi
done
# The orchestrator hands backup-minio.sh its env file, so the same line reached the MinIO step of a
# real run: COMPLETE was refused and the plaintext mirror stayed outside the stamp, neither sealed
# nor removed.
reset_fakes
case_odest="${WORK}/env-dest-dir-minio"
printf 'DEST_DIR=%s\n' "${case_odest}/elsewhere" > "${case_odest}.env"
rc=0
run_hosted "${case_odest}" --env-file "${case_odest}.env" > "${case_odest}.log" 2>&1 || rc=$?
stamp_odest="$(latest_stamp_dir "${case_odest}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_odest}" ] && [ -f "${stamp_odest}/COMPLETE" ] \
  && [ -f "${stamp_odest}/minio.tar.gz.enc" ] && [ ! -e "${stamp_odest}/minio" ] \
  && [ ! -e "${case_odest}/elsewhere" ] && grep -q '^WARN: ignoring DEST_DIR' "${case_odest}.log"; then
  ok "an env file DEST_DIR cannot move the MinIO step: the mirror is sealed in the stamp, nothing is left outside it"
else
  bad "an env file DEST_DIR must not leave a plaintext mirror outside the stamp (rc=${rc}, outside: $([ -e "${case_odest}/elsewhere" ] && echo yes || echo no))"
fi
# ROOT: the steps ran from another checkout (here a missing one, so every step failed).
reset_fakes
case_oroot="${WORK}/env-root"
printf 'ROOT=%s\n' "${case_oroot}/no-such-checkout" > "${case_oroot}.env"
rc=0
run_hosted "${case_oroot}" --env-file "${case_oroot}.env" > "${case_oroot}.log" 2>&1 || rc=$?
stamp_oroot="$(latest_stamp_dir "${case_oroot}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_oroot}" ] && [ -f "${stamp_oroot}/COMPLETE" ] \
  && ! grep -q 'no-such-checkout' "${case_oroot}.log" && grep -q '^WARN: ignoring ROOT' "${case_oroot}.log"; then
  ok "an env file ROOT is ignored with a warning; the steps run from the orchestrator's own checkout"
else
  bad "an env file ROOT must not move the orchestrator's steps (rc=${rc})"
fi
# ENV_FILE: the steps got another env file, and the manifest named it.
manifest_env_profile() {
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["envProfile"])' "$1" 2>/dev/null \
    || echo "<unreadable>"
}
reset_fakes
case_oenv="${WORK}/env-env-file"
printf 'ENV_FILE=%s\n' "${case_oenv}/other.env" > "${case_oenv}.env"
rc=0
run_hosted "${case_oenv}" --env-file "${case_oenv}.env" > "${case_oenv}.log" 2>&1 || rc=$?
profile_oenv="$(manifest_env_profile "${case_oenv}/artifacts/backup-current.json")"
if [ "${rc}" -eq 0 ] && [ "${profile_oenv}" = "${case_oenv}.env" ] && ! grep -q 'other\.env' "${case_oenv}.log" \
  && grep -q '^WARN: ignoring ENV_FILE' "${case_oenv}.log"; then
  ok "an env file ENV_FILE is ignored with a warning; the steps and the manifest get --env-file"
else
  bad "an env file ENV_FILE must not replace --env-file for the steps (rc=${rc}, manifest envProfile=${profile_oenv})"
fi
# A line that assigns one of the orchestrator's private names stops the run before it writes anything.
for line in "arg_root=${WORK}/no-such-checkout" "arg_env_file=${WORK}/other.env" "arg_dry_run=1" \
    "arg_no_prune=1" "arg_operator=env-file-operator" "arg_artifact_dir=${WORK}/env-artifacts"; do
  name="${line%%=*}"
  case_priv="${WORK}/env-private-${name}"
  printf '%s\n' "${line}" > "${case_priv}.env"
  rc=0
  run_hosted "${case_priv}" --env-file "${case_priv}.env" > "${case_priv}.log" 2>&1 || rc=$?
  written="$(find "${case_priv}/backups" "${case_priv}/artifacts" "${case_priv}/textfile" -mindepth 1 2>/dev/null \
    | wc -l | tr -d ' ')"
  if [ "${rc}" -eq 1 ] && [ "${written}" = 0 ] && grep -qF "${name}: readonly variable" "${case_priv}.log"; then
    ok "an env file ${name}= line stops the backup at the env file (exit 1, nothing written)"
  else
    bad "an env file ${name}= line must stop the backup before it writes (rc=${rc}, entries written=${written})"
  fi
done

# --- #652: backup-databases.sh, the orchestrator's dump step, sources its erasure-ledger library
# after it loads the env file. A SCRIPT_DIR= line there pointed the step at another checkout's library. ---
# A stand-in library in another "checkout": it records that it ran and writes an empty ledger, so
# the run still completes and only the marker tells which library ran.
other_checkout="${WORK}/other-checkout"
mkdir -p "${other_checkout}/lib"
cat > "${other_checkout}/lib/erasure-tombstones.sh" <<'EOF'
parkio_export_erasure_tombstones() { : > "${FAKE_LIB_MARKER:?}"; printf '[]\n' > "$1/erasure-tombstones.json"; }
EOF
WARN_SCRIPT_DIR="WARN: ignoring SCRIPT_DIR from the environment or env file; this step uses its own checkout's library."
# run_db_only_env CASE ENV_FILE: a DB-only run of backup-databases.sh with that env file.
run_db_only_env() {
  mkdir -p "$1/backups" "$1/offsite/parkio-backups"
  (
    export BACKUP_DIR="$1/backups" BACKUP_MC_DEST="$1/offsite/parkio-backups" BACKUP_RETENTION_DAYS=14 \
      OFFSITE_LOG="$1/mc.log" FAKE_LIB_MARKER="$1/other-library-ran"
    unset BACKUP_DEST_DIR BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL
    PARKIO_ENV_FILE="$2" bash "${ROOT}/scripts/backup-databases.sh"
  )
}
reset_fakes
case_sdir="${WORK}/env-script-dir"
printf 'SCRIPT_DIR=%s\n' "${other_checkout}" > "${case_sdir}.env"
rc=0
run_db_only_env "${case_sdir}" "${case_sdir}.env" > "${case_sdir}.log" 2>&1 || rc=$?
stamp_sdir="$(latest_stamp_dir "${case_sdir}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ ! -e "${case_sdir}/other-library-ran" ] && [ -n "${stamp_sdir}" ] \
  && [ -f "${stamp_sdir}/COMPLETE" ] && grep -qxF "${WARN_SCRIPT_DIR}" "${case_sdir}.log"; then
  ok "backup-databases.sh ignores an env file SCRIPT_DIR with a warning and exports the ledger with its own library"
else
  bad "an env file SCRIPT_DIR must not point backup-databases.sh at another library (rc=${rc}, other library ran: $([ -e "${case_sdir}/other-library-ran" ] && echo yes || echo no))"
fi
# The orchestrator hands the step its env file, so the same line reached the dump step of a real run.
reset_fakes
case_osdir="${WORK}/env-script-dir-orchestrator"
printf 'SCRIPT_DIR=%s\n' "${other_checkout}" > "${case_osdir}.env"
rc=0
FAKE_LIB_MARKER="${case_osdir}/other-library-ran" \
  run_hosted "${case_osdir}" --env-file "${case_osdir}.env" > "${case_osdir}.log" 2>&1 || rc=$?
stamp_osdir="$(latest_stamp_dir "${case_osdir}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ ! -e "${case_osdir}/other-library-ran" ] && [ -n "${stamp_osdir}" ] \
  && [ -f "${stamp_osdir}/COMPLETE" ] && grep -qxF "${WARN_SCRIPT_DIR}" "${case_osdir}.log"; then
  ok "an env file SCRIPT_DIR cannot reach the dump step of a real run; the stamp completes with the step's own library"
else
  bad "an env file SCRIPT_DIR must not reach backup-databases.sh under the orchestrator (rc=${rc}, other library ran: $([ -e "${case_osdir}/other-library-ran" ] && echo yes || echo no))"
fi
# A line that assigns the step's private name stops it before it dumps anything.
reset_fakes
case_psdir="${WORK}/env-script-dir-private"
printf 'arg_script_dir=%s\n' "${other_checkout}" > "${case_psdir}.env"
rc=0
run_db_only_env "${case_psdir}" "${case_psdir}.env" > "${case_psdir}.log" 2>&1 || rc=$?
written="$(find "${case_psdir}/backups" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')"
if [ "${rc}" -eq 1 ] && [ "${written}" = 0 ] && [ ! -e "${case_psdir}/other-library-ran" ] \
  && grep -qF "arg_script_dir: readonly variable" "${case_psdir}.log"; then
  ok "backup-databases.sh stops at an env file arg_script_dir= line (exit 1, nothing written)"
else
  bad "an env file arg_script_dir= line must stop backup-databases.sh before it dumps (rc=${rc}, entries written=${written})"
fi

# --- #652: the operator tools take their arguments before they load the env file. A line there
# that assigns one of them now stops the tool at that line (exit 1), before it touches a database. ---
printf 'Salted__synthetic' > "${WORK}/verify-auth.sql.gz.enc"
printf 'Salted__synthetic' > "${WORK}/verify-gateway.sql.gz.enc"
for line in "SERVICE=gateway" "DUMP_FILE=${WORK}/verify-gateway.sql.gz.enc" "KEEP=yes" \
    "SCRIPT_DIR=${other_checkout}" "ENV_FILE=${WORK}/other.env"; do
  name="${line%%=*}"
  printf '%s\n' "${line}" > "${WORK}/verify-env.env"
  rc=0
  bash "${ROOT}/scripts/verify-backup.sh" auth "${WORK}/verify-auth.sql.gz.enc" --env-file "${WORK}/verify-env.env" \
    > "${WORK}/verify-env.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 1 ] && ! grep -q '^Verifying ' "${WORK}/verify-env.log" \
    && grep -qF "${name}: readonly variable" "${WORK}/verify-env.log"; then
    ok "verify-backup.sh stops at an env file ${name}= line (exit 1) before it creates a database"
  else
    bad "an env file ${name}= line must stop verify-backup.sh before it creates a database (rc=${rc})"
  fi
done
for line in "TARGET_SERVICE=gateway" "FROM_DIR=${WORK}" "KEEP_BACKUPS=yes" "KEEP_CANARIES=yes" "ALL_SERVICES=auth" \
    "ROOT_DIR=${other_checkout}" "SCRIPT_DIR=${other_checkout}" "ENV_FILE=${WORK}/other.env"; do
  name="${line%%=*}"
  printf '%s\n' "${line}" > "${WORK}/drill-env.env"
  rc=0
  bash "${ROOT}/scripts/restore-drill.sh" --service auth --env-file "${WORK}/drill-env.env" \
    > "${WORK}/drill-env.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 1 ] && ! grep -q '^==> Parkio restore drill' "${WORK}/drill-env.log" \
    && grep -qF "${name}: readonly variable" "${WORK}/drill-env.log"; then
    ok "restore-drill.sh stops at an env file ${name}= line (exit 1) before it seeds or backs up anything"
  else
    bad "an env file ${name}= line must stop restore-drill.sh before it seeds or backs up anything (rc=${rc})"
  fi
done
# The other two drills that the backup runbook runs with the production env file. restore-drill-minio.sh
# sources its library after the env file, so the stand-in checkout gets one that records that it ran.
mkdir -p "${other_checkout}/scripts/lib"
printf ': > "${FAKE_LIB_MARKER:?}"\n' > "${other_checkout}/scripts/lib/backup-common.sh"
for line in "ROOT=${other_checkout}" "SCRIPT_DIR=${other_checkout}" "ENV_FILE=${WORK}/other.env"; do
  name="${line%%=*}"
  printf '%s\n' "${line}" > "${WORK}/drill-env.env"
  rm -f "${WORK}/other-library-ran"
  rc=0
  FAKE_LIB_MARKER="${WORK}/other-library-ran" bash "${ROOT}/scripts/restore-drill-minio.sh" \
    --env-file "${WORK}/drill-env.env" > "${WORK}/drill-env.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 1 ] && [ ! -e "${WORK}/other-library-ran" ] \
    && grep -qF "${name}: readonly variable" "${WORK}/drill-env.log"; then
    ok "restore-drill-minio.sh stops at an env file ${name}= line (exit 1) before it sources a library"
  else
    bad "an env file ${name}= line must stop restore-drill-minio.sh before it sources a library (rc=${rc}, other library ran: $([ -e "${WORK}/other-library-ran" ] && echo yes || echo no))"
  fi
done
for line in "ROOT=${other_checkout}" "OFFSITE_NAME=parkio-minio" "MC_IMAGE=example.invalid/mc" \
    "OFFSITE_IMAGE=example.invalid/minio" "SCRIPT_DIR=${other_checkout}" "ENV_FILE=${WORK}/other.env"; do
  name="${line%%=*}"
  printf '%s\n' "${line}" > "${WORK}/drill-env.env"
  rm -f "${WORK}/drill-offsite-runs.log"
  rc=0
  DOCKER_RUN_LOG="${WORK}/drill-offsite-runs.log" bash "${ROOT}/scripts/restore-drill-offsite.sh" \
    --env-file "${WORK}/drill-env.env" > "${WORK}/drill-env.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 1 ] && [ ! -s "${WORK}/drill-offsite-runs.log" ] \
    && grep -qF "${name}: readonly variable" "${WORK}/drill-env.log"; then
    ok "restore-drill-offsite.sh stops at an env file ${name}= line (exit 1) before it starts a container"
  else
    bad "an env file ${name}= line must stop restore-drill-offsite.sh before it starts a container (rc=${rc})"
  fi
done

# --- #520 review: a value option never takes the next option as its value ---
for opt_args in "--env-file --no-prune" "--operator --no-prune" "--env-file"; do
  case_opt="${WORK}/opt-$(printf '%s' "${opt_args}" | tr -c 'a-z' '-')"
  rc=0
  # shellcheck disable=SC2086 # split the option string on purpose
  run_hosted "${case_opt}" ${opt_args} > "${case_opt}.log" 2>&1 || rc=$?
  written="$(find "${case_opt}/backups" "${case_opt}/artifacts" "${case_opt}/textfile" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${rc}" -eq 2 ] && [ "${written}" = 0 ]; then
    ok "'${opt_args}' exits 2 and writes nothing"
  else
    bad "'${opt_args}' must exit 2 before writing (rc=${rc}, entries written=${written})"
  fi
done

# --- #520 review: the docker mc path (BACKUP_MC_URL) writes only under <dest>/<new stamp> ---
reset_fakes
case_dmc="${WORK}/docker-mc"
export BACKUP_MC_ACCESS_KEY=testtest-testtest BACKUP_MC_SECRET_KEY=testtest-testtest
export BACKUP_MC_DOCKER_NETWORK=parkio-backend
rc=0
# The stub logs the arguments of every docker run there, with or without MC_DEST (#639).
CASE_MC_URL=http://offsite-minio:9000 DOCKER_RUN_LOG="${case_dmc}/docker-run.log" \
  run_hosted "${case_dmc}" > "${case_dmc}.log" 2>&1 || rc=$?
unset BACKUP_MC_URL BACKUP_MC_ACCESS_KEY BACKUP_MC_SECRET_KEY BACKUP_MC_DOCKER_NETWORK
stamp_dmc="$(latest_stamp_dir "${case_dmc}/backups" || true)"
dest_dmc="${case_dmc}/offsite/parkio-backups/$(basename "${stamp_dmc:-none}")"
# #612: an allow-list, not a deny-list. The log must hold exactly two docker mc runs, the mirror
# of the new stamp and then its COMPLETE, each running exactly the script parkio_backup_mc_docker
# has for it. Any other line fails: another mc subcommand (rb, rm), a global flag in front of one
# (mc --quiet rm), another flag on an allowed one (mirror --remove), or any non-mc command.
# Prints the first difference and returns 1.
dmc_log_allowed() { # mc.log new-stamp-dest
  python3 - "$1" "$2" <<'PY'
import os, sys

log, dest = sys.argv[1:]
alias = 'mc alias set offsite "$MC_URL" "$MC_ACCESS" "$MC_SECRET" >/dev/null'
allowed = [
    (dest, ["set -eu", alias,
            'mc mb -p "offsite/$(echo "$MC_DEST" | cut -d/ -f2)" >/dev/null 2>&1 || true',
            'mc mirror --overwrite /upload "$MC_DEST"']),
    (dest + "/COMPLETE", ["set -eu", alias, 'mc cp "/upload/${SRC_FILE}" "$MC_DEST"']),
]


def fail(message):
    print(message)
    sys.exit(1)


if not os.path.isfile(log):
    fail("no docker mc run was logged")
runs, current = [], None
for line in (raw.strip() for raw in open(log, encoding="utf-8")):
    if current is None:
        if not line:
            continue
        if not line.startswith("docker mc MC_DEST="):
            fail("not a docker mc run: %s" % line)
        current = (line[len("docker mc MC_DEST="):], [])
    elif line == "=== end script ===":
        runs.append(current)
        current = None
    elif line:
        current[1].append(line)
if current is not None or len(runs) != len(allowed):
    fail("%d complete docker mc runs, expected %d" % (len(runs), len(allowed)))
for (got_dest, got), (want_dest, want) in zip(runs, allowed):
    if got_dest != want_dest:
        fail("MC_DEST=%s, expected %s" % (got_dest, want_dest))
    extra = [line for line in got if line not in want]
    if extra:
        fail("not allowed: %s" % extra[0])
    if got != want:
        fail("the script for %s is not the allowed one" % got_dest)
PY
}
# #639: the stub logs only the runs that set MC_DEST to mc.log, so a docker run without it never
# reached dmc_log_allowed. The stub logs every run's arguments to DOCKER_RUN_LOG, one per line, the
# -c script last. That log must hold exactly three runs, in order: the local MinIO mirror of
# backup-minio.sh into <stamp>/minio/parkio-media, then the two runs with MC_DEST.
# #652: each run's arguments before -c must be exactly its full allowed list: every flag, mount,
# variable, the user, the network and the image, in order. So an added --privileged, -v or --user
# fails on any of the three runs, as does a changed or missing argument. The mirror run must also
# run exactly its script; dmc_log_allowed pins the scripts of the two MC_DEST runs.
# The expected values come from the case's inputs: PIN_USER (uid:gid), PIN_NETWORK, PIN_MIRROR_IMAGE,
# PIN_OFFSITE_IMAGE, PIN_MC_URL, PIN_MC_ACCESS, PIN_MC_SECRET and PIN_UPLOAD_DIR (where the COMPLETE
# file is staged). Prints the first difference and returns 1.
docker_runs_allowed() { # docker-run.log stamp new-stamp-dest
  python3 - "$1" "$2" "$3" <<'PY'
import os, re, sys

log, stamp, dest = sys.argv[1:]
pin = {name: os.environ[name] for name in ("PIN_USER", "PIN_NETWORK", "PIN_MIRROR_IMAGE", "PIN_OFFSITE_IMAGE",
                                           "PIN_MC_URL", "PIN_MC_ACCESS", "PIN_MC_SECRET", "PIN_UPLOAD_DIR")}
offsite_head = ["run", "--rm", "--network", pin["PIN_NETWORK"], "--entrypoint", "/bin/sh"]
offsite_env = ["-e", "MC_URL=" + pin["PIN_MC_URL"], "-e", "MC_ACCESS=" + pin["PIN_MC_ACCESS"],
               "-e", "MC_SECRET=" + pin["PIN_MC_SECRET"]]
allowed = [
    ("the mirror run",
     ["run", "--rm", "-i", "--user", pin["PIN_USER"], "--network", pin["PIN_NETWORK"],
      "-v", stamp + "/minio/parkio-media:/backup", "-e", "MC_CONFIG_DIR=/tmp/.mc", "-e", "BUCKET=parkio-media",
      "--entrypoint", "/bin/sh", pin["PIN_MIRROR_IMAGE"]]),
    ("the offsite mirror run",
     offsite_head + ["-v", stamp + ":/upload:ro"] + offsite_env + ["-e", "MC_DEST=" + dest, pin["PIN_OFFSITE_IMAGE"]]),
    ("the offsite COMPLETE run",
     offsite_head + ["-v", pin["PIN_UPLOAD_DIR"] + ":/upload:ro"] + offsite_env
     + ["-e", "MC_DEST=" + dest + "/COMPLETE", "-e", re.compile(r"SRC_FILE=parkio-complete\.[A-Za-z0-9]{6}"),
        pin["PIN_OFFSITE_IMAGE"]]),
]
mirror_script = ["set -eu", "umask 077", "mc alias set local http://minio:9000 >/dev/null",
                 'mc mirror --overwrite --quiet "local/${BUCKET}" /backup >/dev/null',
                 'mc ls --recursive "local/${BUCKET}" | wc -l']
TAKES_VALUE = {"-v", "--volume", "--mount", "-e", "--env", "--env-file", "-u", "--user", "--network", "--net",
               "--entrypoint", "--cap-add", "--device", "--pid", "--ipc", "--security-opt", "-w", "--workdir"}


def fail(message):
    print(message)
    sys.exit(1)


def same(want, got):
    return want.fullmatch(got) is not None if isinstance(want, re.Pattern) else want == got


def shown(args, i):  # an argument, with its value when it is an option that takes one
    if args[i] in TAKES_VALUE and i + 1 < len(args):
        return args[i] + " " + args[i + 1]
    return args[i]


if not os.path.isfile(log):
    fail("no docker run was logged")
runs, current = [], []
for line in open(log, encoding="utf-8").read().splitlines():
    if line == "=== end docker run ===":
        runs.append(current)
        current = []
    else:
        current.append(line)
if current:
    fail("a docker run without its end marker")
for index, run in enumerate(runs):
    cut = run.index("-c") if "-c" in run else len(run)
    args, script = run[:cut], [line.strip() for line in run[cut + 1:] if line.strip()]
    if index >= len(allowed):
        fail("not allowed: docker %s" % " ".join(args))
    name, want = allowed[index]
    for i, got in enumerate(args):
        if i >= len(want):
            fail("not allowed: %s (%s, argument %d; expected nothing more before -c)" % (shown(args, i), name, i))
        if not same(want[i], got):
            expected = want[i].pattern if isinstance(want[i], re.Pattern) else want[i]
            fail("not allowed: %s (%s, argument %d; expected %s)" % (shown(args, i), name, i, expected))
    if len(args) < len(want):
        missing = want[len(args)]
        fail("missing: %s (%s, argument %d)" % (missing.pattern if isinstance(missing, re.Pattern) else missing,
                                                 name, len(args)))
    if cut == len(run):
        fail("%s has no -c script" % name)
    if index == 0:
        extra = [line for line in script if line not in mirror_script]
        if extra:
            fail("not allowed: %s" % extra[0])
        if script != mirror_script:
            fail("the MinIO mirror script is not the allowed one")
if len(runs) != len(allowed):
    fail("%d docker runs, expected %d" % (len(runs), len(allowed)))
PY
}
# The pinned values of the docker-mc case. The images are what the scripts use: MINIO_MC_IMAGE when
# the environment sets it (CI may), else each script's own pinned default.
docker_runs_pins() {
  PIN_USER="$(id -u):$(id -g)" PIN_NETWORK=parkio-backend \
    PIN_MIRROR_IMAGE="${MINIO_MC_IMAGE:-$(sed -n 's/^MC_IMAGE="${MINIO_MC_IMAGE:-\([^}]*\)}"$/\1/p' "${ROOT}/scripts/backup-minio.sh")}" \
    PIN_OFFSITE_IMAGE="${MINIO_MC_IMAGE:-$(sed -n 's/^ *local mc_image="${MINIO_MC_IMAGE:-\([^}]*\)}"$/\1/p' "${ROOT}/scripts/lib/backup-common.sh")}" \
    PIN_MC_URL=http://offsite-minio:9000 PIN_MC_ACCESS=testtest-testtest PIN_MC_SECRET=testtest-testtest \
    PIN_UPLOAD_DIR="$(dirname "${TMPDIR:-/tmp}/parkio-complete")" "$@"
}
why_dmc=""
if [ "${rc}" -eq 0 ] && [ -n "${stamp_dmc}" ] \
  && why_dmc="$(dmc_log_allowed "${case_dmc}/mc.log" "${dest_dmc}")" \
  && why_dmc="$(docker_runs_pins docker_runs_allowed "${case_dmc}/docker-run.log" "${stamp_dmc}" "${dest_dmc}")" \
  && check_receipt "${stamp_dmc}" "${case_dmc}/offsite/parkio-backups" > /dev/null; then
  ok "docker mc upload runs only the allowed mc commands, under the new stamp's prefix, and no other docker run"
else
  bad "docker mc upload must run only the allowed mc commands under the new stamp's prefix (rc=${rc} ${why_dmc})"
fi
# The allow-list itself. The first two lines got through the old deny-list (--remove, ^mc rm); the
# third pins --remove, which it caught. Each must fail on its own line, not on another difference.
for injected in 'mc rb --force offsite/parkio-backups' \
  'mc --quiet rm --recursive --force "$MC_DEST"' \
  'mc mirror --overwrite --remove /upload "$MC_DEST"'; do
  python3 - "${case_dmc}/mc.log" "${case_dmc}/mc-injected.log" "${injected}" <<'PY' \
    || { bad "cannot build the injected mc log for '${injected}'"; continue; }
import sys

source, target, injected = sys.argv[1:]
lines = open(source, encoding="utf-8").read().splitlines()
end = next(i for i, line in enumerate(lines) if line.strip() == "=== end script ===")
open(target, "w", encoding="utf-8").write("\n".join(lines[:end] + ["  " + injected] + lines[end:]) + "\n")
PY
  rc=0
  why="$(dmc_log_allowed "${case_dmc}/mc-injected.log" "${dest_dmc}")" || rc=$?
  if [ "${rc}" -ne 0 ] && [ "${why}" = "not allowed: ${injected}" ]; then
    ok "the docker mc allow-list rejects an added '${injected}' by name"
  else
    bad "the docker mc allow-list must reject an added '${injected}' as 'not allowed: ${injected}' (rc=${rc}: ${why})"
  fi
done
# The docker run check itself, on the two shapes a run without MC_DEST can take: a run of its own
# (appended), and a line added to the local mirror's script (the first run).
for injected in "run --rm --network parkio-backend --entrypoint /bin/sh mc-image -c mc rb --force offsite/parkio-backups" \
  "mc rb --force local/parkio-media"; do
  python3 - "${case_dmc}/docker-run.log" "${case_dmc}/docker-run-injected.log" "${injected}" <<'PY' \
    || { bad "cannot build the injected docker run log for '${injected}'"; continue; }
import sys

source, target, injected = sys.argv[1:]
lines = open(source, encoding="utf-8").read().splitlines()
if injected.startswith("run "):
    head, _, script = injected.partition(" -c ")
    lines += head.split() + ["-c", script, "=== end docker run ==="]
else:
    end = lines.index("=== end docker run ===")
    lines[end:end] = ["    " + injected]
open(target, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
  case "${injected}" in
    run\ *) what="an extra docker run without MC_DEST"; want="not allowed: docker ${injected%% -c *}" ;;
    *) what="a line added to the MinIO mirror script"; want="not allowed: ${injected}" ;;
  esac
  rc=0
  why="$(docker_runs_pins docker_runs_allowed "${case_dmc}/docker-run-injected.log" "${stamp_dmc}" "${dest_dmc}")" \
    || rc=$?
  if [ "${rc}" -ne 0 ] && [ "${why}" = "${want}" ]; then
    ok "the docker run check rejects ${what} by name"
  else
    bad "the docker run check must reject ${what} as '${want}' (rc=${rc}: ${why})"
  fi
done
# #652: the full argument lists. An argument added to any of the three runs fails, named with its value.
for injected in "0 --privileged" "0 -v /:/host" "1 --user 0:0" "2 -v /:/host"; do
  run_index="${injected%% *}" extra="${injected#* }"
  python3 - "${case_dmc}/docker-run.log" "${case_dmc}/docker-run-args.log" "${run_index}" "${extra}" <<'PY' \
    || { bad "cannot build the docker run log with '${extra}' added to run ${run_index}"; continue; }
import sys

source, target, run_index, extra = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4].split()
lines = open(source, encoding="utf-8").read().splitlines()
starts = [0] + [i + 1 for i, line in enumerate(lines) if line == "=== end docker run ==="][:-1]
at = starts[run_index] + 1  # right after that run's "run"
lines[at:at] = extra
open(target, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
  case "${run_index}" in
    0) run_name="the mirror run" ;;
    1) run_name="the offsite mirror run" ;;
    *) run_name="the offsite COMPLETE run" ;;
  esac
  want="not allowed: ${extra} (${run_name}, argument 1; expected --rm)"
  rc=0
  why="$(docker_runs_pins docker_runs_allowed "${case_dmc}/docker-run-args.log" "${stamp_dmc}" "${dest_dmc}")" || rc=$?
  if [ "${rc}" -ne 0 ] && [ "${why}" = "${want}" ]; then
    ok "the docker run check rejects '${extra}' added to ${run_name} by name"
  else
    bad "the docker run check must reject '${extra}' added to ${run_name} as '${want}' (rc=${rc}: ${why})"
  fi
done

# --- #520 gcs offsite kind: keyless gcloud storage, create-only objects, COMPLETE last ---
reset_fakes
case_gcs="${WORK}/gcs-ok"
plant_old_good "${case_gcs}/backups" > /dev/null
rc=0
run_hosted_gcs "${case_gcs}" > "${case_gcs}.log" 2>&1 || rc=$?
stamp_gcs="$(latest_stamp_dir "${case_gcs}/backups" || true)"
remote_gcs="${case_gcs}/gcs/parkio-synthetic-backups/stamps"
ms="$(metric "${case_gcs}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
os="$(metric "${case_gcs}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
why=""
if [ "${rc}" -eq 0 ] && [ -n "${stamp_gcs}" ] && [ "${ms}" = "1" ] && [ "${os}" = "1" ] \
  && diff -r "${stamp_gcs}" "${remote_gcs}/$(basename "${stamp_gcs}")" > /dev/null \
  && why="$(check_receipt "${stamp_gcs}" "${remote_gcs}" gcs "${GCS_DEST}")"; then
  ok "gcs run uploads the stamp byte for byte, writes a gs:// receipt beside it and reports success"
else
  bad "gcs run must upload the stamp and write its receipt (rc=${rc} metrics=${ms:-none}/${os:-none} ${why})"
fi
why="no stamp"
if [ -n "${stamp_gcs}" ] && why="$(check_gcloud_calls "${case_gcs}/gcloud.log" "${stamp_gcs}" 2>&1)"; then
  ok "gcs upload: one create-only copy per file, a listing, COMPLETE last, a second listing; composite uploads off"
else
  bad "gcs upload calls: ${why}"
fi
offsite_gcs="$(python3 -c 'import json,sys; o=json.load(open(sys.argv[1]))["offsite"]; print(o["kind"], o["uploaded"])' \
  "${case_gcs}/artifacts/backup-current.json" 2>/dev/null || true)"
if [ "${offsite_gcs}" = "gcs True" ]; then
  ok "the live manifest records offsite kind gcs, uploaded"
else
  bad "the live manifest must record offsite kind gcs, uploaded (got '${offsite_gcs}')"
fi
name_gcs="$(basename "${stamp_gcs:-none}")"
listing_gcs="$(BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${case_gcs}/gcs" \
  parkio_backup_offsite_list "${name_gcs}" 2>&1 | LC_ALL=C sort || true)"
want_gcs="$(find "${stamp_gcs:-${WORK}/none}" -mindepth 1 -maxdepth 1 -type f -printf '%f\t%s\n' 2>/dev/null | LC_ALL=C sort)"
if [ -n "${want_gcs}" ] && [ "${listing_gcs}" = "${want_gcs}" ]; then
  ok "gcs listing gives every object of the stamp: its name relative to the stamp and its byte size"
else
  bad "gcs listing must match the local stamp (got '${listing_gcs:0:160}')"
fi
rc=0
BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${case_gcs}/gcs" GCLOUD_LOG="${case_gcs}/gcloud-pull.log" \
  bash "${ROOT}/scripts/backup-offsite-pull.sh" --stamp "${name_gcs}" --dest "${case_gcs}/pulled" > "${case_gcs}-pull.log" 2>&1 || rc=$?
other_calls="$(grep -c -v -e '^pcu=False prompts=1 storage objects list ' -e '^pcu=False prompts=1 storage cp gs://' \
  "${case_gcs}/gcloud-pull.log" 2>/dev/null || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_gcs}" ] && diff -r "${stamp_gcs}" "${case_gcs}/pulled" > /dev/null \
  && [ "${other_calls}" = 0 ]; then
  ok "gcs pull copies the stamp into a new directory and verifies it, with listings and downloads only"
else
  bad "gcs pull must restore the stamp byte for byte (rc=${rc}, other gcloud calls: ${other_calls:-no log})"
fi
# #652: backup-offsite-pull.sh takes --stamp and --dest before it loads the env file. A DEST= or STAMP=
# line there pulled into another directory, or another stamp. It now stops the pull (exit 1).
for line in "DEST=${case_gcs}/pulled-elsewhere" "STAMP=2026-01-01T00-00-00Z" "ROOT=${WORK}/other-checkout" \
    "ENV_FILE=${WORK}/other.env"; do
  name="${line%%=*}"
  printf '%s\n' "${line}" > "${case_gcs}-pull.env"
  rm -rf "${case_gcs}/pulled-env" "${case_gcs}/pulled-elsewhere"
  : > "${case_gcs}/gcloud-pull-env.log"
  rc=0
  BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${case_gcs}/gcs" GCLOUD_LOG="${case_gcs}/gcloud-pull-env.log" \
    bash "${ROOT}/scripts/backup-offsite-pull.sh" --env-file "${case_gcs}-pull.env" --stamp "${name_gcs}" \
    --dest "${case_gcs}/pulled-env" > "${case_gcs}-pull-env.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 1 ] && [ ! -e "${case_gcs}/pulled-env" ] && [ ! -e "${case_gcs}/pulled-elsewhere" ] \
    && [ ! -s "${case_gcs}/gcloud-pull-env.log" ] && grep -qF "${name}: readonly variable" "${case_gcs}-pull-env.log"; then
    ok "backup-offsite-pull.sh stops at an env file ${name}= line (exit 1, no gcloud call, nothing pulled)"
  else
    bad "an env file ${name}= line must stop backup-offsite-pull.sh before it pulls (rc=${rc})"
  fi
done
# The pull writes new files only: a file already in the destination is kept, and nothing is downloaded.
pre_pull="${case_gcs}/pre-existing"
mkdir -p "${pre_pull}"
printf 'kept\n' > "${pre_pull}/SHA256SUMS"
rc=0
(
  export BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${case_gcs}/gcs" \
    GCLOUD_LOG="${case_gcs}/gcloud-prepull.log"
  parkio_backup_offsite_pull "${pre_pull}" "${name_gcs}"
) > "${case_gcs}-prepull.log" 2>&1 || rc=$?
if [ "${rc}" -ne 0 ] && [ "$(cat "${pre_pull}/SHA256SUMS")" = kept ] && [ "$(ls -A "${pre_pull}")" = SHA256SUMS ] \
  && ! grep -q 'storage cp gs://' "${case_gcs}/gcloud-prepull.log" 2>/dev/null \
  && grep -q 'already exists' "${case_gcs}-prepull.log"; then
  ok "gcs pull refuses a destination file that already exists, before any download"
else
  bad "gcs pull must keep an existing destination file and download nothing (rc=${rc})"
fi
# A listing with an object name it cannot use safely fails instead of passing the name on.
unsafe_root="${WORK}/gcs-unsafe-name"
mkdir -p "${unsafe_root}/parkio-synthetic-backups/stamps/2026-01-01T00-00-00Z"
printf 'x\n' > "${unsafe_root}/parkio-synthetic-backups/stamps/2026-01-01T00-00-00Z/bad name.bin"
unsafe_list="$(BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${unsafe_root}" \
  parkio_backup_offsite_list 2026-01-01T00-00-00Z 2>&1)" && unsafe_rc=0 || unsafe_rc=$?
if [ "${unsafe_rc}" -ne 0 ] && grep -q 'unsafe object name' <<< "${unsafe_list}"; then
  ok "gcs listing fails on an object name with an unsafe segment instead of passing it on"
else
  bad "gcs listing must refuse an unsafe object name (rc=${unsafe_rc}: ${unsafe_list})"
fi
# A stamp with no object lists as nothing ("[]", exit 0, as live gcloud answers); its pull fails on the
# missing COMPLETE and leaves the new directory empty.
mkdir -p "${WORK}/gcs-empty-bucket"
empty_list="$(BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${WORK}/gcs-empty-bucket" \
  parkio_backup_offsite_list 2026-01-01T00-00-00Z 2>&1)" && empty_rc=0 || empty_rc=$?
rc=0
BACKUP_OFFSITE_KIND=gcs BACKUP_GCS_DEST="${GCS_DEST}" FAKE_GCS_ROOT="${WORK}/gcs-empty-bucket" \
  bash "${ROOT}/scripts/backup-offsite-pull.sh" --stamp 2026-01-01T00-00-00Z --dest "${WORK}/gcs-empty-pull" \
  > "${WORK}/gcs-empty-pull.log" 2>&1 || rc=$?
if [ "${empty_rc}" -eq 0 ] && [ -z "${empty_list}" ] && [ "${rc}" -ne 0 ] \
  && grep -q 'missing COMPLETE' "${WORK}/gcs-empty-pull.log" && [ -z "$(ls -A "${WORK}/gcs-empty-pull" 2>/dev/null)" ]; then
  ok "an empty gcs listing lists nothing, and the pull of that stamp fails on the missing COMPLETE"
else
  bad "empty gcs listing (rc=${empty_rc}: '${empty_list}') and its pull (rc=${rc}) must yield nothing"
fi

# A missing gcloud or a bad destination stops the run at preflight, before anything is written.
gcs_nothing_written() { # case-dir
  [ "$(find "$1/backups" "$1/artifacts" "$1/textfile" -mindepth 1 2>/dev/null | wc -l | tr -d ' ')" = 0 ] \
    && [ ! -s "$1/gcloud.log" ]
}
i=0
for gcloud_bin in "${WORK}/no-such-dir/gcloud" "bin/gcloud" "${BIN}"; do
  i=$((i + 1))
  case_ng="${WORK}/gcs-gcloud-${i}"
  rc=0
  BACKUP_GCLOUD_BIN="${gcloud_bin}" run_hosted_gcs "${case_ng}" > "${case_ng}.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 2 ] && gcs_nothing_written "${case_ng}" && grep -q 'needs gcloud' "${case_ng}.log"; then
    ok "BACKUP_GCLOUD_BIN=${gcloud_bin#"${WORK}"/} (no executable gcloud) exits 2 before writing anything"
  else
    bad "BACKUP_GCLOUD_BIN=${gcloud_bin} must exit 2 before writing (rc=${rc})"
  fi
done
i=0
for bad_dest in "" "parkio-synthetic-backups/stamps" "s3://parkio-synthetic-backups/stamps" \
  "gs://parkio-synthetic-backups" "gs://parkio-synthetic-backups/" "gs://parkio-Synthetic/stamps" "gs://ab/stamps" \
  "gs://parkio-synthetic-backups/stamps/*" "gs://parkio-synthetic-backups/../stamps" \
  "gs://parkio-synthetic-backups//stamps" "gs://parkio-synthetic-backups/stamps#1" \
  "gs://parkio-synthetic-backups/stamps x"; do
  i=$((i + 1))
  case_bd="${WORK}/gcs-bad-dest-${i}"
  rc=0
  CASE_GCS_DEST="${bad_dest}" run_hosted_gcs "${case_bd}" > "${case_bd}.log" 2>&1 || rc=$?
  if [ "${rc}" -eq 2 ] && gcs_nothing_written "${case_bd}" && grep -q 'BACKUP_GCS_DEST must be gs://' "${case_bd}.log"; then
    ok "BACKUP_GCS_DEST='${bad_dest}' exits 2 before writing anything"
  else
    bad "BACKUP_GCS_DEST='${bad_dest}' must exit 2 before writing (rc=${rc})"
  fi
done
case_bd="${WORK}/gcs-bad-dest-not-production"
rc=0
BACKUP_PRODUCTION_MODE=0 CASE_GCS_DEST=gs://parkio-synthetic-backups run_hosted_gcs "${case_bd}" > "${case_bd}.log" 2>&1 || rc=$?
if [ "${rc}" -eq 2 ] && gcs_nothing_written "${case_bd}"; then
  ok "a bad BACKUP_GCS_DEST also stops a run outside production mode"
else
  bad "a bad BACKUP_GCS_DEST must stop a run outside production mode too (rc=${rc})"
fi
# Like BACKUP_MC_DEST, a destination the caller sets wins over a blank placeholder in the env file.
reset_fakes
case_gp="${WORK}/gcs-env-placeholder"
mkdir -p "${case_gp}"
printf 'BACKUP_GCS_DEST=\n' > "${case_gp}.env"
rc=0
run_hosted_gcs "${case_gp}" --env-file "${case_gp}.env" > "${case_gp}.log" 2>&1 || rc=$?
stamp_gp="$(latest_stamp_dir "${case_gp}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_gp}" ] \
  && [ -f "${case_gp}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_gp}")/COMPLETE" ]; then
  ok "a blank BACKUP_GCS_DEST= in the env file does not wipe the caller's destination"
else
  bad "the caller's BACKUP_GCS_DEST must survive a blank env file placeholder (rc=${rc})"
fi
# So does a gcloud path the caller sets: the env file's BACKUP_GCLOUD_BIN does not replace it.
reset_fakes
case_gb="${WORK}/gcs-env-gcloud-bin"
mkdir -p "${case_gb}"
printf 'BACKUP_GCLOUD_BIN=%s\n' "${WORK}/no-such-dir/gcloud" > "${case_gb}.env"
rc=0
BACKUP_GCLOUD_BIN="${BIN}/gcloud" run_hosted_gcs "${case_gb}" --env-file "${case_gb}.env" > "${case_gb}.log" 2>&1 || rc=$?
stamp_gb="$(latest_stamp_dir "${case_gb}/backups" || true)"
if [ "${rc}" -eq 0 ] && [ -n "${stamp_gb}" ] \
  && [ -f "${case_gb}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_gb}")/COMPLETE" ]; then
  ok "a BACKUP_GCLOUD_BIN the caller sets wins over the env file's, as BACKUP_GCS_DEST does"
else
  bad "the caller's BACKUP_GCLOUD_BIN must win over the env file (rc=${rc})"
fi

# An upload that fails, or a listing that does not show every file at its size, stops before COMPLETE.
for failure in FAKE_GCS_FAIL_NAME=minio.tar.gz.enc FAKE_GCS_SHORT_NAME=minio.tar.gz.enc FAKE_GCS_LIST_FAIL=1; do
  reset_fakes
  case_gf="${WORK}/gcs-${failure%%=*}"
  old_gf="$(plant_old_good "${case_gf}/backups")"
  rc=0
  export "${failure?}"
  run_hosted_gcs "${case_gf}" > "${case_gf}.log" 2>&1 || rc=$?
  unset "${failure%%=*}"
  stamp_gf="$(latest_stamp_dir "${case_gf}/backups" || true)"
  remote_gf="${case_gf}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_gf:-none}")"
  ms="$(metric "${case_gf}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
  os="$(metric "${case_gf}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
  if [ "${rc}" -ne 0 ] && [ -n "${stamp_gf}" ] && [ -f "${stamp_gf}/COMPLETE" ] \
    && [ -f "${remote_gf}/backup-manifest.json" ] && [ ! -e "${remote_gf}/COMPLETE" ] \
    && no_receipt "${stamp_gf}" && [ "${ms}" = "0" ] && [ "${os}" = "0" ] \
    && [ ! -f "${case_gf}/artifacts/backup-current.json" ] && [ -f "${old_gf}/COMPLETE" ]; then
    ok "${failure}: the run fails before the remote COMPLETE; no receipt, no current pointer, no prune"
  else
    bad "${failure} must fail closed before COMPLETE (rc=${rc} metrics=${ms:-none}/${os:-none})"
  fi
done
case_gf="${WORK}/gcs-FAKE_GCS_FAIL_NAME"
stamp_gf="$(latest_stamp_dir "${case_gf}/backups" || true)"
remote_gf="${case_gf}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_gf:-none}")"
if [ -n "${stamp_gf}" ] && [ -f "${remote_gf}/backup-manifest.json" ] && [ ! -e "${remote_gf}/minio.tar.gz.enc" ] \
  && [ ! -e "${remote_gf}/user.sql.gz.enc" ]; then
  ok "a failed gcs upload stops at that object; later objects are not sent"
else
  bad "a failed gcs upload must stop at the failed object"
fi

# An object that already exists under the new stamp is never replaced and fails the run.
for existing in backup-manifest.json COMPLETE; do
  reset_fakes
  case_ge="${WORK}/gcs-existing-${existing}"
  old_ge="$(plant_old_good "${case_ge}/backups")"
  rc=0
  FAKE_GCS_EXISTING="${existing}" run_hosted_gcs "${case_ge}" > "${case_ge}.log" 2>&1 || rc=$?
  stamp_ge="$(latest_stamp_dir "${case_ge}/backups" || true)"
  remote_ge="${case_ge}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_ge:-none}")"
  ms="$(metric "${case_ge}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
  os="$(metric "${case_ge}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
  kept="$(cat "${remote_ge}/${existing}" 2>/dev/null || true)"
  if [ "${rc}" -ne 0 ] && [ -n "${stamp_ge}" ] && [ "${kept}" = "foreign-object" ] \
    && { [ "${existing}" = COMPLETE ] || [ ! -e "${remote_ge}/COMPLETE" ]; } \
    && no_receipt "${stamp_ge}" && [ "${ms}" = "0" ] && [ "${os}" = "0" ] && [ -f "${old_ge}/COMPLETE" ]; then
    ok "an existing remote ${existing} fails the gcs upload closed: it is kept, no receipt, no prune"
  else
    bad "an existing remote ${existing} must fail closed (rc=${rc} remote: ${kept:-missing} metrics=${ms:-none}/${os:-none})"
  fi
done

# Copies that exit 0 but store nothing, or store under another prefix: the first listing ("[]", exit 0)
# shows none of the files, so COMPLETE is never sent.
for failure in FAKE_GCS_DROP=1 FAKE_GCS_MISPLACE=1; do
  reset_fakes
  case_gm="${WORK}/gcs-${failure%%=*}"
  old_gm="$(plant_old_good "${case_gm}/backups")"
  rc=0
  export "${failure?}"
  run_hosted_gcs "${case_gm}" > "${case_gm}.log" 2>&1 || rc=$?
  unset "${failure%%=*}"
  stamp_gm="$(latest_stamp_dir "${case_gm}/backups" || true)"
  remote_gm="${case_gm}/gcs/parkio-synthetic-backups/stamps/$(basename "${stamp_gm:-none}")"
  ms="$(metric "${case_gm}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
  os="$(metric "${case_gm}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
  complete_copies="$(grep -c -e '--if-generation-match=0 .*/COMPLETE gs://' "${case_gm}/gcloud.log" 2>/dev/null || true)"
  if [ "${rc}" -ne 0 ] && [ -n "${stamp_gm}" ] && [ ! -e "${remote_gm}/COMPLETE" ] && [ "${complete_copies}" = 0 ] \
    && no_receipt "${stamp_gm}" && [ "${ms}" = "0" ] && [ "${os}" = "0" ] && [ -f "${old_gm}/COMPLETE" ] \
    && grep -q 'at their sizes (without-complete)' "${case_gm}.log"; then
    ok "${failure}: the first listing shows nothing, so COMPLETE is not sent; no receipt, no prune"
  else
    bad "${failure} must stop before COMPLETE (rc=${rc} COMPLETE copies=${complete_copies:-none} metrics=${ms:-none}/${os:-none})"
  fi
done
# Only the second listing can see this: the COMPLETE copy exits 0, but the object never lands.
reset_fakes
case_gl="${WORK}/gcs-complete-lost"
old_gl="$(plant_old_good "${case_gl}/backups")"
rc=0
FAKE_GCS_DROP_NAME=COMPLETE run_hosted_gcs "${case_gl}" > "${case_gl}.log" 2>&1 || rc=$?
stamp_gl="$(latest_stamp_dir "${case_gl}/backups" || true)"
ms="$(metric "${case_gl}/textfile/parkio_backup.prom" 'parkio_backup_last_success' 2>/dev/null || true)"
os="$(metric "${case_gl}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success' 2>/dev/null || true)"
complete_copies="$(grep -c -e '--if-generation-match=0 .*/COMPLETE gs://' "${case_gl}/gcloud.log" 2>/dev/null || true)"
if [ "${rc}" -ne 0 ] && [ -n "${stamp_gl}" ] && [ "${complete_copies}" = 1 ] && no_receipt "${stamp_gl}" \
  && [ "${ms}" = "0" ] && [ "${os}" = "0" ] && [ ! -f "${case_gl}/artifacts/backup-current.json" ] \
  && [ -f "${old_gl}/COMPLETE" ] && grep -q 'at their sizes (with-complete)' "${case_gl}.log"; then
  ok "a COMPLETE copy that exits 0 but never lands fails the second listing: no receipt, no prune"
else
  bad "a lost COMPLETE must fail the run at the second listing (rc=${rc} COMPLETE copies=${complete_copies:-none} metrics=${ms:-none}/${os:-none})"
fi

# Statically too: the gcs code runs gcloud only through its wrapper, for copies and listings.
gcloud_uses="$(grep -v '^[[:space:]]*#' "${ROOT}/scripts/lib/backup-common.sh" \
  | grep -oE 'parkio_backup_gcloud storage [a-z]+( [a-z]+)?' | LC_ALL=C sort -u | paste -sd'|' - || true)"
if [ "${gcloud_uses}" = "parkio_backup_gcloud storage cp|parkio_backup_gcloud storage objects list" ] \
  && ! grep -v '^[[:space:]]*#' "${ROOT}/scripts/lib/backup-common.sh" | grep -q -e '--no-clobber'; then
  ok "backup-common.sh calls gcloud only for storage cp and storage objects list, never with --no-clobber"
else
  bad "backup-common.sh gcloud calls must be storage cp and storage objects list only (got '${gcloud_uses}')"
fi

# --- partial MinIO then failure ---
reset_fakes
export FAKE_MINIO_MODE=fail-partial
case_minio="${WORK}/minio-fail"
old_minio="$(plant_old_good "${case_minio}/backups")"
rc=0
run_hosted "${case_minio}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "partial MinIO mirror exits nonzero"
else
  bad "partial MinIO mirror must not succeed"
fi
stamp_minio="$(latest_stamp_dir "${case_minio}/backups")"
if assert_no_usable_complete "${stamp_minio}"; then
  ok "partial MinIO run writes no COMPLETE"
else
  bad "partial MinIO must not produce COMPLETE"
fi
if [ ! -d "${stamp_minio}/minio" ] && [ ! -f "${stamp_minio}/minio.tar.gz.enc" ]; then
  ok "partial MinIO tree was discarded and not sealed"
else
  bad "partial MinIO must not remain or be sealed"
fi
if assert_not_uploaded "${case_minio}/offsite"; then
  ok "partial MinIO did not upload COMPLETE"
else
  bad "failed MinIO stamp must not be uploaded"
fi
if [ -f "${old_minio}/COMPLETE" ]; then
  ok "previous good stamp survives MinIO failure"
else
  bad "MinIO failure must not prune the last good stamp"
fi
if [ ! -f "${case_minio}/artifacts/backup-current.json" ]; then
  ok "MinIO failure does not publish backup-current.json"
else
  bad "failed run must not replace backup-current.json"
fi

# --- DB dump failure ---
reset_fakes
export FAKE_DUMP_FAIL=parkio_parking
case_db="${WORK}/db-fail"
old_db="$(plant_old_good "${case_db}/backups")"
rc=0
run_hosted "${case_db}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "DB dump failure exits nonzero"
else
  bad "DB dump failure must not succeed"
fi
stamp_db="$(latest_stamp_dir "${case_db}/backups")"
if assert_no_usable_complete "${stamp_db}" && assert_not_uploaded "${case_db}/offsite"; then
  ok "DB dump failure has no COMPLETE and no upload"
else
  bad "DB dump failure must not seal or upload"
fi
if [ -f "${old_db}/COMPLETE" ]; then
  ok "previous good stamp survives DB dump failure"
else
  bad "DB dump failure must not prune the last good stamp"
fi

# --- ledger export / malformed ledger ---
reset_fakes
export FAKE_LEDGER=notjson
case_ledger="${WORK}/ledger-fail"
old_ledger="$(plant_old_good "${case_ledger}/backups")"
rc=0
run_hosted "${case_ledger}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "malformed ledger exits nonzero"
else
  bad "malformed ledger must not succeed"
fi
stamp_ledger="$(latest_stamp_dir "${case_ledger}/backups")"
if assert_no_usable_complete "${stamp_ledger}" && assert_not_uploaded "${case_ledger}/offsite"; then
  ok "malformed ledger has no COMPLETE and no upload"
else
  bad "malformed ledger must not seal or upload"
fi
if [ -f "${old_ledger}/COMPLETE" ]; then
  ok "previous good stamp survives ledger failure"
else
  bad "ledger failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_LEDGER=query-fail
case_ledger2="${WORK}/ledger-query-fail"
rc=0
run_hosted "${case_ledger2}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_ledger2="$(latest_stamp_dir "${case_ledger2}/backups")"
  if assert_no_usable_complete "${stamp_ledger2}"; then
    ok "ledger query failure exits nonzero without COMPLETE"
  else
    bad "ledger query failure must not write COMPLETE"
  fi
else
  bad "ledger query failure must not succeed"
fi

# --- sealing / checksum failure ---
reset_fakes
export FAKE_OPENSSL_SEAL_FAIL=1
case_seal="${WORK}/seal-fail"
old_seal="$(plant_old_good "${case_seal}/backups")"
rc=0
run_hosted "${case_seal}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "MinIO seal failure exits nonzero"
else
  bad "MinIO seal failure must not succeed"
fi
stamp_seal="$(latest_stamp_dir "${case_seal}/backups")"
if assert_no_usable_complete "${stamp_seal}" && [ ! -f "${stamp_seal}/minio.tar.gz.enc" ] \
  && assert_not_uploaded "${case_seal}/offsite"; then
  ok "seal failure discards MinIO and does not upload"
else
  bad "seal failure must not leave a sealed successful stamp"
fi
if [ -f "${old_seal}/COMPLETE" ]; then
  ok "previous good stamp survives seal failure"
else
  bad "seal failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_INTEGRITY_SHA_FAIL=1
case_sha="${WORK}/sha-fail"
rc=0
run_hosted "${case_sha}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_sha="$(latest_stamp_dir "${case_sha}/backups")"
  if assert_no_usable_complete "${stamp_sha}" && assert_not_uploaded "${case_sha}/offsite"; then
    ok "SHA256SUMS failure exits nonzero without COMPLETE or upload"
  else
    bad "checksum failure must not write COMPLETE or upload"
  fi
else
  bad "SHA256SUMS failure must not succeed"
fi

# --- interruption before completion ---
reset_fakes
export FAKE_DUMP_SLEEP=2
case_int="${WORK}/interrupt"
mkdir -p "${case_int}/backups" "${case_int}/artifacts" "${case_int}/textfile" "${case_int}/offsite/parkio-backups"
export BACKUP_DIR="${case_int}/backups"
export PARKIO_BACKUP_ARTIFACT_DIR="${case_int}/artifacts"
export PARKIO_PROMETHEUS_TEXTFILE_DIR="${case_int}/textfile"
export BACKUP_MC_DEST="${case_int}/offsite/parkio-backups"
export OFFSITE_LOG="${case_int}/mc.log"
unset BACKUP_SKIP_MC_UPLOAD BACKUP_MC_URL PARKIO_ENV_FILE
bash "${ROOT}/scripts/backup-hosted-beta.sh" > "${case_int}/run.log" 2>&1 &
int_pid=$!
slept=0
while [ "${slept}" -lt 30 ]; do
  if find "${case_int}/backups" -mindepth 1 -type d 2>/dev/null | grep -q .; then
    break
  fi
  sleep 0.1
  slept=$((slept + 1))
done
kill -TERM "${int_pid}" 2>/dev/null || true
sleep 0.3
kill -KILL "${int_pid}" 2>/dev/null || true
wait "${int_pid}" || true
int_stamp="$(find "${case_int}/backups" -mindepth 1 -maxdepth 1 -type d | head -1 || true)"
if [ -n "${int_stamp}" ] && assert_no_usable_complete "${int_stamp}" && assert_not_uploaded "${case_int}/offsite"; then
  ok "interrupted run leaves no usable COMPLETE and no upload"
else
  if [ -z "${int_stamp}" ]; then
    ok "interrupted run left no stamp directory (no COMPLETE)"
  else
    bad "interrupted run must not look complete"
  fi
fi
unset FAKE_DUMP_SLEEP

# --- standalone DB-only success ---
reset_fakes
case_dbok="${WORK}/db-only-ok"
if run_db_only "${case_dbok}"; then
  ok "standalone DB-only path exits 0"
else
  bad "standalone DB-only path must succeed"
fi
stamp_dbok="$(latest_stamp_dir "${case_dbok}/backups")"
if [ -f "${stamp_dbok}/COMPLETE" ] && [ ! -d "${stamp_dbok}/minio" ] \
  && [ ! -f "${stamp_dbok}/minio.tar.gz.enc" ]; then
  ok "DB-only success writes COMPLETE without requiring MinIO"
else
  bad "DB-only success must complete on dumps+ledger only"
fi
if find "${case_dbok}/offsite" -name COMPLETE -type f | grep -q .; then
  ok "DB-only success uploads COMPLETE"
else
  bad "DB-only success must upload when offsite is configured"
fi
if why="$(check_receipt "${stamp_dbok}" "${case_dbok}/offsite/parkio-backups")"; then
  ok "DB-only success writes a receipt beside the stamp"
else
  bad "DB-only success receipt: ${why}"
fi

# --- standalone DB-only failure ---
reset_fakes
export FAKE_DUMP_FAIL=parkio_parking
case_dbbad="${WORK}/db-only-fail"
old_dbbad="$(plant_old_good "${case_dbbad}/backups")"
rc=0
run_db_only "${case_dbbad}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "standalone DB-only dump failure exits nonzero"
else
  bad "standalone DB-only dump failure must not succeed"
fi
stamp_dbbad="$(latest_stamp_dir "${case_dbbad}/backups")"
if assert_no_usable_complete "${stamp_dbbad}" && assert_not_uploaded "${case_dbbad}/offsite"; then
  ok "standalone DB-only failure has no COMPLETE and no upload"
else
  bad "standalone DB-only failure must not seal or upload"
fi
if [ -f "${old_dbbad}/COMPLETE" ]; then
  ok "previous good stamp survives standalone DB-only failure"
else
  bad "standalone DB-only failure must not prune the last good stamp"
fi

reset_fakes
export FAKE_LEDGER=object
case_dbledger="${WORK}/db-only-ledger-fail"
rc=0
run_db_only "${case_dbledger}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  stamp_dbledger="$(latest_stamp_dir "${case_dbledger}/backups")"
  if assert_no_usable_complete "${stamp_dbledger}"; then
    ok "standalone malformed ledger exits nonzero without COMPLETE"
  else
    bad "standalone malformed ledger must not write COMPLETE"
  fi
else
  bad "standalone malformed ledger must not succeed"
fi

# --- a prefixed project exports the ledger from its own auth container ---
# Outside production mode, a missing container used to give an empty ledger and a sealed stamp.
reset_fakes
export FAKE_PG_PREFIX=parkio-rd-test-primary PARKIO_PG_CONTAINER_PREFIX=parkio-rd-test-primary
export FAKE_LEDGER_LOG="${WORK}/ledger-containers.log"
case_dbprefix="${WORK}/db-only-prefix"
rc=0
BACKUP_PRODUCTION_MODE=0 run_db_only "${case_dbprefix}" || rc=$?
read_from="$(sort -u "${FAKE_LEDGER_LOG}" 2>/dev/null | paste -sd, -)"
if [ "${rc}" -eq 0 ] && [ "${read_from}" = "parkio-rd-test-primary-postgres-auth" ]; then
  ok "a prefixed project reads the erasure ledger from its own auth container"
else
  bad "the ledger must come from the prefixed auth container (rc=${rc}, read from ${read_from:-nothing})"
fi
unset FAKE_PG_PREFIX PARKIO_PG_CONTAINER_PREFIX FAKE_LEDGER_LOG

# --- offsite failure is not offsite success ---
reset_fakes
export FAKE_MC_FAIL=1
case_off="${WORK}/offsite-fail"
old_off="$(plant_old_good "${case_off}/backups")"
rc=0
run_hosted "${case_off}" || rc=$?
if [ "${rc}" -ne 0 ]; then
  ok "offsite failure exits nonzero"
else
  bad "offsite failure must fail the backup"
fi
stamp_off="$(latest_stamp_dir "${case_off}/backups")"
if [ -f "${stamp_off}/COMPLETE" ]; then
  ok "local COMPLETE remains after local gates pass and offsite fails"
else
  bad "offsite failure after local success should keep a local COMPLETE"
fi
if assert_not_uploaded "${case_off}/offsite"; then
  ok "offsite failure did not publish COMPLETE remotely"
else
  bad "failed offsite must not leave a remote COMPLETE"
fi
os="$(metric "${case_off}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success')"
ms="$(metric "${case_off}/textfile/parkio_backup.prom" 'parkio_backup_last_success')"
if [ "${os}" = "0" ] && [ "${ms}" = "0" ]; then
  ok "offsite failure metrics are last_success=0 offsite_last_success=0"
else
  bad "offsite failure must not report offsite success (got ${ms}/${os})"
fi
if [ ! -f "${case_off}/artifacts/backup-current.json" ]; then
  ok "offsite failure does not publish backup-current.json as success"
else
  bad "offsite failure must not replace last-good current pointer"
fi
if [ -f "${old_off}/COMPLETE" ]; then
  ok "previous good stamp survives offsite failure"
else
  bad "offsite failure must not prune the last good stamp"
fi
if no_receipt "${stamp_off}"; then
  ok "offsite failure writes no receipt"
else
  bad "a failed upload must not leave a receipt"
fi

# --- upload succeeds but its receipt cannot be written ---
reset_fakes
export FAKE_RECEIPT_FAIL=1
case_rcpt="${WORK}/receipt-fail"
old_rcpt="$(plant_old_good "${case_rcpt}/backups")"
rc=0
run_hosted "${case_rcpt}" > "${case_rcpt}.log" 2>&1 || rc=$?
stamp_rcpt="$(latest_stamp_dir "${case_rcpt}/backups")"
os="$(metric "${case_rcpt}/textfile/parkio_backup.prom" 'parkio_backup_offsite_last_success')"
ms="$(metric "${case_rcpt}/textfile/parkio_backup.prom" 'parkio_backup_last_success')"
live_rcpt="$(manifest_uploaded "${case_rcpt}/artifacts/backup-$(basename "${stamp_rcpt}").json")"
if [ "${rc}" -ne 0 ] && [ -f "${case_rcpt}/offsite/parkio-backups/$(basename "${stamp_rcpt}")/COMPLETE" ] \
  && no_receipt "${stamp_rcpt}" && [ "${os}" = "0" ] && [ "${ms}" = "0" ] && [ "${live_rcpt}" = "false" ] \
  && [ ! -f "${case_rcpt}/artifacts/backup-current.json" ] && [ -f "${old_rcpt}/COMPLETE" ]; then
  ok "an unwritten receipt fails the run: no uploaded claim, no current pointer, no prune"
else
  bad "unwritten receipt must fail closed (rc=${rc} metrics=${ms}/${os} live=${live_rcpt})"
fi

# CL-F29.2: the plaintext mirror belongs to the invoking user and is private (no a+rwX).
case_perm="${WORK}/mirror-permissions"
mkdir -p "${case_perm}"
reset_fakes
rc=0
DOCKER_RUN_LOG="${case_perm}/docker-run.log" \
  bash "${ROOT}/scripts/backup-minio.sh" "${case_perm}/stamp" > "${case_perm}/count.txt" || rc=$?
user_arg="$(awk 'previous == "--user" { print; exit } { previous = $0 }' "${case_perm}/docker-run.log" 2>/dev/null || true)"
if [ "${rc}" -eq 0 ] && [ "${user_arg}" = "$(id -u):$(id -g)" ]; then
  ok "MinIO mirror container runs as the invoking user"
else
  bad "MinIO mirror container must run as $(id -u):$(id -g) (rc=${rc}, --user '${user_arg}')"
fi
if grep -q 'umask 077' "${case_perm}/docker-run.log" 2>/dev/null \
  && ! grep -q 'chmod' "${case_perm}/docker-run.log" 2>/dev/null; then
  ok "MinIO mirror container uses umask 077 and changes no permissions"
else
  bad "MinIO mirror container must use umask 077 and must not chmod the mirror"
fi
open_entries="$(find "${case_perm}/stamp/minio" -perm /077 2>/dev/null | wc -l)"
if [ -d "${case_perm}/stamp/minio/parkio-media" ] && [ "${open_entries}" -eq 0 ]; then
  ok "MinIO mirror tree grants no group/other permissions"
else
  bad "MinIO mirror tree must grant no group/other permissions (${open_entries} entries do)"
fi

echo
echo "=== backup complete gate: pass=${pass} fail=${fail} ==="
if [ "${fail}" -ne 0 ]; then
  exit 1
fi
