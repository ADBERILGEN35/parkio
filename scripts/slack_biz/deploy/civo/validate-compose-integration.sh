#!/usr/bin/env bash
# Validate the waitlist ops-notification Compose integration against the
# production file set (docker/compose.production.files), with synthetic env
# only (no production secrets are read or printed).
#
#   scripts/slack_biz/deploy/civo/validate-compose-integration.sh [BASE_REF]
#
# BASE_REF (default origin/api) is rendered from `git archive` into a temp dir
# and compared service-by-service with this checkout:
#   1. disabled default: images/pins, mounts, group_add, volumes, networks
#      and non-allowlisted env must match BASE_REF. Allowlisted keys are the
#      documented production compose mappings (waitlist ops + auth
#      registration). A surprise pin, mount, or extra env key still fails.
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
  local root="$1" line list="$1/docker/compose.production.files"
  [[ -f "$list" ]] || { echo "FAIL: missing compose file list $list" >&2; return 1; }
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    printf -- '-f\n%s\n' "$root/$line"
  done < "$list"
}
render() { # root envfile out [extra -f args...]
  local root="$1" envf="$2" out="$3" files; shift 3
  # Captured first: a process substitution would lose a files_args failure, and Compose would then
  # render the extra -f files alone. Any failure here stops the check (U09).
  files="$(files_args "$root")" || { echo "FAIL: cannot list the production compose files of $root" >&2; exit 1; }
  [[ -n "$files" ]] || { echo "FAIL: $root lists no production compose files" >&2; exit 1; }
  mapfile -t FA <<<"$files"
  (cd "$root" && env -i PATH="$PATH" HOME="$HOME" DOCKER_HOST="${DOCKER_HOST:-}" \
      docker compose --env-file "$envf" "${FA[@]}" "$@" config --format json) > "$out"
}

mkdir -p "$TMP/base"
git archive "$BASE_REF" docker scripts/lib 2>/dev/null | tar -x -C "$TMP/base"
if [[ ! -f "$TMP/base/docker/compose.production.files" ]]; then
  echo "FAIL: ${BASE_REF} has no docker/compose.production.files." >&2
  echo "The production compose contract lives on origin/api; refusing a pre-contract comparison base." >&2
  exit 1
fi
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

# Sentinel CSP inputs: web's connect-src must follow them. With the example defaults, a source
# written out in the Compose file would match Caddy's too (#200 review N1).
{ cat "$TMP/head.env"
  echo "PARKIO_DOMAIN=api.csp-sentinel.invalid"
  echo "PARKIO_MEDIA_DOMAIN=media.csp-sentinel.invalid"
  echo 'PARKIO_MAP_CONNECT_SRC="https://tiles.csp-sentinel.invalid https://*.maps.csp-sentinel.invalid"'
} > "$TMP/head-csp.env"
render "$ROOT" "$TMP/head-csp.env" "$TMP/head-csp.json"
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

GATEWAY_ALLOWED = {
    "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED",
    "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENVIRONMENT",
    "PARKIO_WAITLIST_OPS_NOTIFICATIONS_CONTRACT_VERSION",
    "PARKIO_WAITLIST_FULL_NAME_REQUIRED",
    "PARKIO_WAITLIST_EXPORT_MAX_ROWS",
}
# CL-F17 / PRIV-002: location-log retention (fail-closed default off; enabling is a release step).
PARKING_ALLOWED = {
    "PARKIO_LOCATION_LOG_RETENTION_ENABLED",
    "PARKIO_LOCATION_LOG_RETENTION",
}
AUTH_ALLOWED = {
    "PARKIO_REGISTRATION_MODE",
    "PARKIO_REGISTRATION_INVITE_CREATION_ENABLED",
    "PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN",
    "PARKIO_REGISTRATION_INVITE_TTL",
}

# Root-filesystem hardening (B8) is checked by scripts/assert-compose-hardening.sh, which records
# every tmpfs entry and media-service's anonymous /tmp volume, so this drift check leaves them to it
# (docs/operations/container-hardening-inventory.md). Other volumes, binds and images stay strict.
HARDENING_KEYS = ("read_only", "tmpfs")
# Kafka GC logs capped to fit the /var/log/kafka tmpfs (B8).
KAFKA_ALLOWED = {"KAFKA_GC_LOG_OPTS"}
KAFKA_GC_LOG_OPTS = "-Xlog:gc*:file=/var/log/kafka/kafkaServer-gc.log:time,tags:filecount=2,filesize=8M"
HARDENING_SCRATCH_VOLUMES = {"media-service": "/tmp"}
# Web image CSP connect-src (CL-F39.4, B9), rendered in Compose from Caddy's inputs.
WEB_ALLOWED = {"PARKIO_WEB_CSP_CONNECT_SRC"}

# Authorized GHCR linux/amd64 MinIO pin retarget (see docs/operations/minio-ghcr-amd64.md).
MINIO_IMAGE_SERVICES = ("minio", "minio-setup")
# Postgres health over TCP (#243, #239 review N2): the image's socket-only init server must not
# count as healthy. Only this exact command on a postgres-* service is accepted as the base's socket
# check; any other healthcheck change still fails. The base's socket check is read as the TCP check,
# never the other way round, so a head that goes back to the socket check fails (#243 review N3).
POSTGRES_SOCKET_HEALTHCHECK = ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
POSTGRES_TCP_HEALTHCHECK = ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U $$POSTGRES_USER -d $$POSTGRES_DB"]


def strip_allowlisted_env(model, from_base=False):
    m = json.loads(json.dumps(model))
    genv = m["services"]["gateway-service"].setdefault("environment", {})
    aenv = m["services"]["auth-service"].setdefault("environment", {})
    for k in GATEWAY_ALLOWED:
        genv.pop(k, None)
    for k in AUTH_ALLOWED:
        aenv.pop(k, None)
    if "parking-service" in m["services"]:
        penv = m["services"]["parking-service"].setdefault("environment", {})
        for k in PARKING_ALLOWED:
            penv.pop(k, None)
    if "web" in m["services"]:
        wenv = m["services"]["web"].setdefault("environment", {})
        for k in WEB_ALLOWED:
            wenv.pop(k, None)
        if not wenv:
            m["services"]["web"].pop("environment")
    for svc in MINIO_IMAGE_SERVICES:
        if svc in m.get("services", {}):
            m["services"][svc].pop("image", None)
    for svc in m.get("services", {}).values():
        for key in HARDENING_KEYS:
            svc.pop(key, None)
    for name, svc in m.get("services", {}).items():
        health = svc.get("healthcheck")
        if from_base and name.startswith("postgres-") and isinstance(health, dict) \
                and health.get("test") == POSTGRES_SOCKET_HEALTHCHECK:
            health["test"] = POSTGRES_TCP_HEALTHCHECK
    if "kafka" in m.get("services", {}):
        kenv = m["services"]["kafka"].get("environment", {})
        for k in KAFKA_ALLOWED:
            kenv.pop(k, None)
    for name, target in HARDENING_SCRATCH_VOLUMES.items():
        svc = m.get("services", {}).get(name)
        if not svc or "volumes" not in svc:
            continue
        svc["volumes"] = [
            v for v in svc["volumes"]
            if not (isinstance(v, dict) and v.get("type") == "volume" and not v.get("source")
                    and v.get("target") == target)
        ]
        if not svc["volumes"]:
            svc.pop("volumes")
    return m

# 1. disabled default vs base
check("same service set", set(base["services"]) == set(dis["services"]),
      f"{len(dis['services'])} services")
base_cmp, dis_cmp = strip_allowlisted_env(base, from_base=True), strip_allowlisted_env(dis)
diff_services = [s for s in base["services"] if base_cmp["services"][s] != dis_cmp["services"][s]]
check("only allowlisted env keys differ from " + base_ref, diff_services == [], ",".join(diff_services) or "none")
for top in ("volumes", "networks", "secrets", "configs"):
    check(f"top-level {top} unchanged", base.get(top) == dis.get(top))
images = {s: dis["services"][s].get("image") for s in dis["services"] if s not in MINIO_IMAGE_SERVICES}
base_images = {s: base["services"][s].get("image") for s in base["services"] if s not in MINIO_IMAGE_SERVICES}
check("all images/pins identical to base", images == base_images)
for svc in ("gateway-service", "auth-service", "web"):
    if svc in images:
        print(f"      {svc}: {images[svc]}")
genv = dis["services"]["gateway-service"]["environment"]
aenv = dis["services"]["auth-service"]["environment"]
check("gateway ops disabled by default", genv.get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED") == "false")
check("gateway contract version mapped", genv.get("PARKIO_WAITLIST_OPS_NOTIFICATIONS_CONTRACT_VERSION") in {"1", "2"})
check("gateway full-name-required mapped", genv.get("PARKIO_WAITLIST_FULL_NAME_REQUIRED") in {"true", "false"})
check("gateway export row cap mapped", genv.get("PARKIO_WAITLIST_EXPORT_MAX_ROWS") == "50000")
check("auth registration CLOSED by example/default", aenv.get("PARKIO_REGISTRATION_MODE") == "closed")
penv = dis["services"].get("parking-service", {}).get("environment", {})
check("parking location-log retention OFF by example/default",
      penv.get("PARKIO_LOCATION_LOG_RETENTION_ENABLED") == "false" and penv.get("PARKIO_LOCATION_LOG_RETENTION") == "P30D")
check("kafka GC logs capped for the tmpfs",
      dis["services"].get("kafka", {}).get("environment", {}).get("KAFKA_GC_LOG_OPTS") == KAFKA_GC_LOG_OPTS)
if "web" in dis["services"]:
    def expected_web_csp(model):
        # The same source list as the Caddyfile SPA policy, including its map default.
        cenv = model["services"]["caddy"].get("environment", {})
        return " ".join([
            "'self'",
            f"https://{cenv.get('PARKIO_DOMAIN')}",
            f"https://{cenv.get('PARKIO_MEDIA_DOMAIN')}",
            cenv.get("PARKIO_MAP_CONNECT_SRC") or "https://api.maptiler.com",
        ])
    sentinel = load("head-csp")
    scenv = sentinel["services"]["caddy"].get("environment", {})
    check("sentinel CSP inputs reach caddy",
          scenv.get("PARKIO_DOMAIN") == "api.csp-sentinel.invalid"
          and scenv.get("PARKIO_MEDIA_DOMAIN") == "media.csp-sentinel.invalid"
          and scenv.get("PARKIO_MAP_CONNECT_SRC") == "https://tiles.csp-sentinel.invalid https://*.maps.csp-sentinel.invalid")
    for label, model in (("example inputs", dis), ("sentinel inputs", sentinel)):
        value = model["services"]["web"].get("environment", {}).get("PARKIO_WEB_CSP_CONNECT_SRC")
        check(f"web CSP connect-src rendered from Caddy's inputs ({label})",
              value == expected_web_csp(model), value or "unset")
check("auth invite creation false by example/default", aenv.get("PARKIO_REGISTRATION_INVITE_CREATION_ENABLED") == "false")
check("auth invite ttl mapped", aenv.get("PARKIO_REGISTRATION_INVITE_TTL") == "P7D")
check(
    "auth invite token is interpolated synthetic value",
    aenv.get("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN") == "SYNTH_PLACEHOLDER_value_0123456789abcdef",
)
prod_files = [
    ln.strip()
    for ln in open(f"{root}/docker/compose.production.files", encoding="utf-8")
    if ln.strip() and not ln.strip().startswith("#")
]
check(
    "auth overlay is in the production file set before the auth pin",
    "docker/docker-compose.auth-registration-env.yml" in prod_files
    and prod_files.index("docker/docker-compose.auth-registration-env.yml")
    < prod_files.index("docker/docker-compose.auth-release-pin.yml"),
)
check(
    "waitlist inbox stays activation-only (not in production file set)",
    "docker/docker-compose.waitlist-ops-inbox.yml" not in prod_files,
)
auth_overlay = open(f"{root}/docker/docker-compose.auth-registration-env.yml", encoding="utf-8").read()
check("auth overlay declares no image or volumes", "image:" not in auth_overlay and "volumes:" not in auth_overlay)
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
