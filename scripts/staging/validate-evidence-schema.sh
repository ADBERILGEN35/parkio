#!/usr/bin/env bash
set -euo pipefail
DIR="${1:-build/operational-evidence}"
SCHEMA="docs/operations/evidence/operational-evidence-schema.json"
if [ ! -f "${SCHEMA}" ]; then echo "FAIL: schema missing" >&2; exit 1; fi
if [ ! -d "${DIR}" ]; then echo "SKIP: no evidence dir ${DIR}"; exit 0; fi
# Prefer explicit run-root summary.json (WP-06.2B.1), then PARKIO_EVIDENCE_DIR,
# then newest nested journey summary that includes repositoryCommit.
if [ -f "${DIR}/summary.json" ]; then
  FILES=("${DIR}/summary.json")
elif [ -n "${PARKIO_EVIDENCE_DIR:-}" ] && [ -f "${PARKIO_EVIDENCE_DIR}/summary.json" ]; then
  FILES=("${PARKIO_EVIDENCE_DIR}/summary.json")
else
  mapfile -t FILES < <(find "${DIR}" -name 'summary.json' -printf '%T@ %p\n' 2>/dev/null | sort -rn | while read -r _ts path; do
    python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); sys.exit(0 if "repositoryCommit" in d else 1)' "${path}" 2>/dev/null && echo "${path}" && break
  done)
  FILES=("${FILES[@]}")
fi
for f in "${FILES[@]}"; do
  [ -f "${f}" ] || continue
  [[ "${f}" == *summary.json ]] || continue
  python3 - <<PY "${f}" "${SCHEMA}" || exit 1
import json, sys
doc = json.load(open(sys.argv[1]))
schema = json.load(open(sys.argv[2]))
for k in schema.get("required", []):
    if k not in doc:
        print(f"FAIL missing {k} in {sys.argv[1]}"); sys.exit(1)
if not isinstance(doc.get("stages"), dict):
    print(f"FAIL stages must be object in {sys.argv[1]}"); sys.exit(1)
print(f"OK validated {sys.argv[1]}")
PY
done