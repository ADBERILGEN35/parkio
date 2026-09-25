#!/usr/bin/env bash
# Read-only FU-1 acceptance. No backup start, stamp rewrite, decrypt, or restore.
set -euo pipefail

INSTALL_UTC="2026-09-23T19:01:12Z"
FIRST_CRON="2026-09-24T03:30:00Z"
BACKUP_DIR=/var/backups/parkio
LOG=/var/log/parkio-backup.log

sanitize() {
  python3 - <<'PY'
import re, sys
text = sys.stdin.read()
text = re.sub(r"https://hooks\.slack\.com/\S+", "[REDACTED_WEBHOOK]", text)
text = re.sub(r"(?i)(sig|se|sp|sv|srt|ss|spr)=[A-Za-z0-9%._~+/-]+", r"\1=[REDACTED]", text)
text = re.sub(r"(?i)(account[-_]?key|sas|passphrase|password|secret|token)=[^\s]+", r"\1=[REDACTED]", text)
text = re.sub(r"[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}", "[REDACTED_EMAIL]", text, flags=re.I)
sys.stdout.write(text)
PY
}

echo "=== clock ==="
date -u +%Y-%m-%dT%H:%M:%SZ
echo "host=$(hostname) user=$(id -un)"
echo "install_cutoff=${INSTALL_UTC}"
echo "expected_first_cron=${FIRST_CRON}"

echo "=== running? ==="
if pgrep -af 'run-production-backup|backup-hosted-beta|backup-databases' 2>/dev/null | grep -v 'fu1-readonly' | grep -v pgrep; then
  echo RUNNING
else
  echo NOT_RUNNING
fi
if [ -e /var/lock/parkio-backup.lock ]; then
  stat -c 'lock %y %s' /var/lock/parkio-backup.lock
  if command -v fuser >/dev/null 2>&1; then
    fuser /var/lock/parkio-backup.lock 2>/dev/null && echo LOCK_HELD || echo LOCK_FREE
  fi
else
  echo NO_LOCK_FILE
fi

echo "=== cron ==="
if [ -e /etc/cron.d/parkio-backup ]; then
  sha256sum /etc/cron.d/parkio-backup
  sed -n '1,20p' /etc/cron.d/parkio-backup
else
  echo MISSING_CRON
fi

echo "=== stamps ==="
python3 - "$BACKUP_DIR" "$INSTALL_UTC" "$FIRST_CRON" <<'PY'
import json, os, subprocess, sys
from datetime import datetime
root, cutoff, first = sys.argv[1], sys.argv[2], sys.argv[3]
cut = datetime.strptime(cutoff, "%Y-%m-%dT%H:%M:%SZ")
print("BACKUP_DIR", root, "exists", os.path.isdir(root))
post = []
if os.path.isdir(root):
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not os.path.isdir(path):
            continue
        try:
            parsed = datetime.strptime(name, "%Y-%m-%dT%H-%M-%SZ")
        except ValueError:
            print("OTHER_DIR", name)
            continue
        complete = os.path.isfile(os.path.join(path, "COMPLETE"))
        after = parsed >= cut
        print(f"STAMP {name} {'COMPLETE' if complete else 'INCOMPLETE'} after_install={after}")
        if after:
            post.append(name)
print("POST_INSTALL_STAMPS", ",".join(post) if post else "NONE")
if not post:
    sys.exit(0)
stamp = post[-1]
path = os.path.join(root, stamp)
print("ACCEPT_CANDIDATE", stamp)
names = sorted(os.listdir(path))
print("STAMP_ENTRIES", ",".join(names))
print("HAS_COMPLETE", os.path.isfile(os.path.join(path, "COMPLETE")))
print("HAS_MANIFEST", os.path.isfile(os.path.join(path, "backup-manifest.json")))
print("HAS_SHA256SUMS", os.path.isfile(os.path.join(path, "SHA256SUMS")))
print("HAS_ERASURE", os.path.isfile(os.path.join(path, "erasure-tombstones.json")))
enc = [n for n in names if n.endswith(".sql.gz.enc")]
print("ENC_DUMP_COUNT", len(enc))
print("ENC_DUMP_NAMES", ",".join(enc))
minio = [n for n in names if n.startswith("minio") or n == "minio.tar.gz.enc"]
print("MINIO_ARTIFACTS", ",".join(minio) if minio else "NONE")
man = os.path.join(path, "backup-manifest.json")
if os.path.isfile(man):
    data = json.load(open(man, encoding="utf-8"))
    dbs = data.get("databases") or data.get("databaseResults") or []
    print("MANIFEST_KEYS", ",".join(sorted(data.keys())))
    if isinstance(dbs, list):
        print("MANIFEST_DB_COUNT", len(dbs))
        failed = data.get("databasesFailed")
        if failed is None:
            failed = sum(1 for row in dbs if isinstance(row, dict) and row.get("ok") is False)
        print("MANIFEST_DBS_FAILED", failed)
        for row in dbs:
            if isinstance(row, dict):
                print("DB_RESULT", row.get("name") or row.get("database"), "ok=", row.get("ok"), "status=", row.get("status"))
            else:
                print("DB_RESULT", row)
    print("MANIFEST_MINIO", data.get("minio") or data.get("minioResult") or data.get("minioStatus"))
    print("MANIFEST_OFFSITE_UPLOADED", (data.get("offsite") or {}).get("uploaded") if isinstance(data.get("offsite"), dict) else data.get("offsiteUploaded"))
    print("MANIFEST_PRODUCTION_MODE", data.get("productionMode") or data.get("production_mode"))
erasure = os.path.join(path, "erasure-tombstones.json")
if os.path.isfile(erasure):
    raw = json.load(open(erasure, encoding="utf-8"))
    if isinstance(raw, dict):
        print("ERASURE_KEYS", ",".join(sorted(raw.keys())))
        entries = raw.get("entries") or raw.get("tombstones") or raw.get("erasureSet")
        print("ERASURE_ENTRY_COUNT", len(entries) if isinstance(entries, list) else "NA")
    else:
        print("ERASURE_TYPE", type(raw).__name__)
sums = os.path.join(path, "SHA256SUMS")
if os.path.isfile(sums):
    proc = subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=path, capture_output=True, text=True)
    print("SHA256SUMS_EXIT", proc.returncode)
    # names and OK/FAIL only
    for line in (proc.stdout or "").splitlines():
        print("SHA", line.split(":")[-1].strip() if ":" in line else line)
    if proc.returncode != 0:
        err = proc.stderr.strip().splitlines()[:8]
        print("SHA_ERR_LINES", len(err))
PY

echo "=== log ==="
if [ -f "$LOG" ]; then
  stat -c '%y %s %n' "$LOG"
  echo "-- lines after install cutoff (sanitized, no dumps) --"
  awk -v cut="$INSTALL_UTC" '
    BEGIN { found=0 }
    {
      line=$0
      if (index(line, "2026-09-23T19:") || index(line, "2026-09-24T")) found=1
      if (found) print
    }
  ' "$LOG" | sanitize | tail -n 60
else
  echo NO_BACKUP_LOG
fi

echo "=== telemetry files ==="
for tf in /var/lib/parkio/observability/textfile/parkio_backup.prom /opt/parkio/docker/prometheus/textfile/parkio_backup.prom; do
  if [ -f "$tf" ]; then
    stat -c '%y %n' "$tf"
    cat "$tf"
  else
    echo "MISSING $tf"
  fi
done

echo "=== local prometheus ==="
if command -v curl >/dev/null 2>&1; then
  for q in parkio_backup_last_success parkio_backup_offsite_last_success parkio_backup_last_timestamp_seconds; do
    curl -fsS --max-time 3 "http://127.0.0.1:9090/api/v1/query?query=$q" 2>/dev/null | python3 -c 'import json,sys
try:
 d=json.load(sys.stdin)
 print("prom", d.get("status"), sys.argv[1] if False else "")
except Exception as exc:
 print("PROM_UNAVAILABLE", type(exc).__name__)
' || echo "PROM_QUERY_FAILED $q"
    curl -fsS --max-time 3 "http://127.0.0.1:9090/api/v1/query?query=$q" 2>/dev/null | python3 -c 'import json,sys,datetime
q=sys.argv[1]
d=json.load(sys.stdin)
for r in d.get("data",{}).get("result",[]):
  val=r.get("value",[None,None])[1]
  print(q, val, r.get("metric",{}).get("scope"))
' "$q" || true
  done
fi

echo "=== peers not touched ==="
for n in parkio-web parkio-gateway-service-1 parkio-alertmanager; do
  docker inspect "$n" --format "$n started={{.State.StartedAt}} status={{.State.Status}}" 2>/dev/null || echo "MISSING_$n"
done
echo FU1_RO_OK
