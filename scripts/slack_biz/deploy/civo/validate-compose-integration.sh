#!/usr/bin/env bash
# Validate the waitlist ops-notification Compose integration against the
# production file set (docker/compose.production.files), with synthetic env
# only (no production secrets are read or printed).
#
#   scripts/slack_biz/deploy/civo/validate-compose-integration.sh [BASE_REF]
#
# BASE_REF (default origin/api) is rendered from `git archive` into a temp dir
# and compared service-by-service with this checkout:
#   1. disabled default: the ONLY difference is two gateway env keys
#      (ENABLED=false, ENVIRONMENT) — every image/pin, other service, volume
#      and network is identical (auth/web/GMP pins and NR untouched);
#   2. activation overlay: gateway gains exactly group_add + one bind mount
#      (create_host_path=false) + EXPORT_DIR; nothing else changes;
#   3. overlay without PARKIO_WAITLIST_OPS_INBOX_GID fails to render.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
BASE_REF="${1:-origin/api}"
cd "$ROOT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

synth_env() { # src dst
  python3 - "$1" "$2" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
text = re.sub(r"REPLACE_ME_[A-Za-z0-9_]+", "SYNTH_PLACEHOLDER_value_0123456789abcdef", text)
open(sys.argv[2], "w", encoding="utf-8").write(text)
PY
}
files_args() { # root
  local root="$1" line
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    printf -- '-f\n%s\n' "$root/$line"
  done < "$root/docker/compose.production.files"
}
render() { # root envfile out [extra -f args...]
  local root="$1" envf="$2" out="$3"; shift 3
  mapfile -t FA < <(files_args "$root")
  (cd "$root" && env -i PATH="$PATH" HOME="$HOME" DOCKER_HOST="${DOCKER_HOST:-}" \
      docker compose --env-file "$envf" "${FA[@]}" "$@" config --format json) > "$out"
}

mkdir -p "$TMP/base"
git archive "$BASE_REF" docker scripts/lib 2>/dev/null | tar -x -C "$TMP/base"
synth_env "$TMP/base/docker/.env.azure-hosted-beta.example" "$TMP/base.env"
synth_env docker/.env.azure-hosted-beta.example "$TMP/head.env"
# Same synthetic env on both sides except keys this change adds.
render "$TMP/base" "$TMP/base.env" "$TMP/base.json"
# Base was rendered from a temp checkout: normalise its absolute root path.
python3 - "$TMP/base.json" "$TMP/base" "$ROOT" <<'PY'
import sys
p, old, new = sys.argv[1:4]
t = open(p, encoding="utf-8").read().replace(old, new)
open(p, "w", encoding="utf-8").write(t)
PY
render "$ROOT" "$TMP/head.env" "$TMP/head-disabled.json"
{ cat "$TMP/head.env"; echo "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED=true"; echo "PARKIO_WAITLIST_OPS_INBOX_GID=10500"; } > "$TMP/head-active.env"
render "$ROOT" "$TMP/head-active.env" "$TMP/head-active.json" -f "$ROOT/docker/docker-compose.waitlist-ops-inbox.yml"
if render "$ROOT" "$TMP/head.env" "$TMP/should-fail.json" -f "$ROOT/docker/docker-compose.waitlist-ops-inbox.yml" 2>/dev/null; then
  missing_gid_fails=false
else
  missing_gid_fails=true
fi

python3 - "$TMP" "$missing_gid_fails" "$BASE_REF" "$(git rev-parse --short HEAD)" "$ROOT" <<'PY'
import json, re, sys
tmp, missing_gid_fails, base_ref, head, root = sys.argv[1:6]
load = lambda n: json.load(open(f"{tmp}/{n}.json"))
base, dis, act = load("base"), load("head-disabled"), load("head-active")
overlay_source = open(f"{root}/docker/docker-compose.waitlist-ops-inbox.yml", encoding="utf-8").read()
source_disables_host_path_creation = bool(
    re.search(r"(?m)^\s+create_host_path:\s*false\s*$", overlay_source)
)
fails = []
def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""))
    if not ok:
        fails.append(name)

ADDED = {"PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED", "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENVIRONMENT"}
def strip_gateway_keys(model, keys):
    m = json.loads(json.dumps(model))
    env = m["services"]["gateway-service"].get("environment") or {}
    for k in keys:
        env.pop(k, None)
    return m

# 1. disabled default vs base
check("same service set", set(base["services"]) == set(dis["services"]),
      f"{len(dis['services'])} services")
diff_services = [s for s in base["services"]
                 if strip_gateway_keys(base, ADDED)["services"][s] != strip_gateway_keys(dis, ADDED)["services"][s]]
check("only gateway env keys differ from " + base_ref, diff_services == [], ",".join(diff_services) or "none")
for top in ("volumes", "networks", "secrets", "configs"):
    check(f"top-level {top} unchanged", base.get(top) == dis.get(top))
images = {s: dis["services"][s].get("image") for s in dis["services"]}
check("all images/pins identical to base",
      images == {s: base["services"][s].get("image") for s in base["services"]})
for svc in ("gateway-service", "auth-service", "web"):
    if svc in images:
        print(f"      {svc}: {images[svc]}")
genv = dis["services"]["gateway-service"]["environment"]
check("gateway ops disabled by default", genv.get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED") == "false")
check("no export dir by default", not genv.get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR"))
check("no group_add / inbox mount by default",
      not dis["services"]["gateway-service"].get("group_add")
      and not any("waitlist-ops-inbox" in json.dumps(v) for v in dis["services"]["gateway-service"].get("volumes", [])))
check("no webhook-like key reaches gateway", not any("WEBHOOK" in k or "SLACK" in k for k in genv))
check("no service beyond gateway mentions slack_biz/inbox",
      not any("waitlist-ops-inbox" in json.dumps(v) for s, v in dis["services"].items()))

# 2. activation overlay
changed = [s for s in dis["services"] if s != "gateway-service" and dis["services"][s] != act["services"][s]]
check("overlay changes only gateway-service", changed == [], ",".join(changed) or "none")
g = act["services"]["gateway-service"]
check("overlay group_add = inbox gid", [str(x) for x in g.get("group_add", [])] == ["10500"], str(g.get("group_add")))
mounts = [v for v in g.get("volumes", []) if v.get("target") == "/var/lib/parkio/waitlist-ops-inbox"]
bind_options = mounts[0].get("bind", {}) if len(mounts) == 1 else {}
# Compose v2 releases differ here: newer versions retain an explicit false in
# JSON, while older versions validate the field but omit its false value. A
# rendered true always fails; an omission is accepted only with the exact
# source declaration present.
create_host_path_safe = (
    bind_options.get("create_host_path") is False
    or ("create_host_path" not in bind_options and source_disables_host_path_creation)
)
check("overlay bind mount (create_host_path=false, rw)",
      len(mounts) == 1 and mounts[0].get("type") == "bind"
      and mounts[0].get("source") == "/var/lib/parkio/waitlist-ops-inbox"
      and create_host_path_safe
      and not mounts[0].get("read_only"), json.dumps(mounts))
check("overlay sets EXPORT_DIR", g["environment"].get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR") == "/var/lib/parkio/waitlist-ops-inbox")
g2 = json.loads(json.dumps(g)); d2 = json.loads(json.dumps(dis["services"]["gateway-service"]))
for k in ("group_add",):
    g2.pop(k, None)
g2["volumes"] = [v for v in g2.get("volumes", []) if v.get("target") != "/var/lib/parkio/waitlist-ops-inbox"] or None
if g2["volumes"] is None: g2.pop("volumes")
g2["environment"].pop("PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR", None)
g2["environment"]["PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED"] = "false"
check("overlay adds nothing else to gateway", g2 == d2)
check("overlay fails without PARKIO_WAITLIST_OPS_INBOX_GID", missing_gid_fails)

print(f"SUMMARY base={base_ref} head={head} FAIL={len(fails)}")
sys.exit(1 if fails else 0)
PY
