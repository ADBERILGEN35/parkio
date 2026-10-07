#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
import json, os, subprocess
p = "/var/backups/parkio/2026-09-24T03-30-01Z"
proc = subprocess.run(["sha256sum", "-c", "SHA256SUMS"], cwd=p, capture_output=True, text=True)
print("SHA_EXIT", proc.returncode)
for line in (proc.stdout or "").splitlines():
    name, _, result = line.partition(":")
    result = result.strip()
    if result != "OK":
        print("SHA_FAIL", name, result)
print("SHA_OK_COUNT", sum(1 for line in (proc.stdout or "").splitlines() if line.endswith("OK")))
print("SHA_FAIL_COUNT", sum(1 for line in (proc.stdout or "").splitlines() if "FAILED" in line))
man = json.load(open(os.path.join(p, "backup-manifest.json"), encoding="utf-8"))
print("DB_TYPE", type(man.get("databases")).__name__)
for row in man.get("databases") or []:
    if isinstance(row, dict):
        print("DB", row.get("name") or row.get("database"), "ok", row.get("ok"), "status", row.get("status"), "keys", ",".join(sorted(row.keys())))
    else:
        print("DB_STR", row)
off = man.get("offsite") or {}
print("OFFSITE_KEYS", ",".join(sorted(off.keys())) if isinstance(off, dict) else type(off).__name__)
if isinstance(off, dict):
    print("OFFSITE_UPLOADED", off.get("uploaded"), "KIND", off.get("kind") or off.get("destinationKind"))
print("CHECKSUMS", man.get("checksums"))
er = json.load(open(os.path.join(p, "erasure-tombstones.json"), encoding="utf-8"))
print("ERASURE_TYPE", type(er).__name__, "LEN", len(er) if isinstance(er, list) else "NA")
if isinstance(er, list) and er and isinstance(er[0], dict):
    print("ERASURE0_KEYS", ",".join(sorted(er[0].keys())))
print("COMPLETE_SIZE", os.path.getsize(os.path.join(p, "COMPLETE")))
PY
echo "=== LOG TAIL ==="
tail -n 40 /var/log/parkio-backup.log | python3 -c 'import re,sys
t=sys.stdin.read()
t=re.sub(r"https://hooks\.slack\.com/\S+","[REDACTED_WEBHOOK]",t)
t=re.sub(r"(?i)(sig|se|sp|sv|sas|passphrase|password|secret|token)=[^\s]+",r"\1=[REDACTED]",t)
print(t)
'
