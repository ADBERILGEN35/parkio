#!/usr/bin/env bash
# WP-06.2A.1 — bounded JSON helpers via Python 3 (no undeclared jq dependency).
# Usage: source this file; call json_require_python / json_get / json_assert_jwks

json_require_python() {
  if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: python3 is required for JSON parsing in staging/smoke scripts (jq is not used)." >&2
    exit 2
  fi
}

# json_get <file> <dotted.path>  — prints scalar/string or empty; fails on invalid JSON
json_get() {
  local file="$1" path="$2"
  json_require_python
  python3 - "$file" "$path" <<'PY'
import json, sys
path = sys.argv[2].split(".")
with open(sys.argv[1], encoding="utf-8") as fh:
    doc = json.load(fh)
cur = doc
for part in path:
    if isinstance(cur, dict) and part in cur:
        cur = cur[part]
    elif isinstance(cur, list) and part.isdigit() and int(part) < len(cur):
        cur = cur[int(part)]
    else:
        print("")
        sys.exit(0)
if cur is None:
    print("")
elif isinstance(cur, (dict, list)):
    print(json.dumps(cur, separators=(",", ":")))
else:
    print(cur)
PY
}

# json_assert_jwks <file> — rejects empty body / empty keys / missing kid|kty|alg
json_assert_jwks() {
  local file="$1"
  json_require_python
  python3 - "$file" <<'PY'
import json, sys
raw = open(sys.argv[1], encoding="utf-8").read()
if not raw.strip():
    print("ERROR: JWKS body is empty", file=sys.stderr); sys.exit(1)
try:
    doc = json.loads(raw)
except json.JSONDecodeError as e:
    print(f"ERROR: JWKS is not valid JSON: {e}", file=sys.stderr); sys.exit(1)
keys = doc.get("keys")
if not isinstance(keys, list) or len(keys) < 1:
    print("ERROR: JWKS has no keys", file=sys.stderr); sys.exit(1)
k0 = keys[0]
for field in ("kid", "kty", "alg"):
    if not k0.get(field):
        print(f"ERROR: JWKS key missing required field '{field}'", file=sys.stderr); sys.exit(1)
print(f"OK jwks keys={len(keys)} kid={k0.get('kid')} alg={k0.get('alg')}")
PY
}

# json_array_contains_id <file> <uuid> — for nearby search results (list of objects with id)
json_array_contains_id() {
  local file="$1" spot_id="$2"
  json_require_python
  python3 - "$file" "$spot_id" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
want = sys.argv[2]
items = doc if isinstance(doc, list) else doc.get("content", doc.get("items", []))
if not isinstance(items, list):
    print("ERROR: expected JSON array of spots", file=sys.stderr); sys.exit(1)
ids = [str(x.get("id","")) for x in items if isinstance(x, dict)]
if want not in ids:
    print(f"ERROR: spot {want} not in nearby results (count={len(ids)})", file=sys.stderr); sys.exit(1)
print(f"OK nearby contains spot count={len(ids)}")
PY
}