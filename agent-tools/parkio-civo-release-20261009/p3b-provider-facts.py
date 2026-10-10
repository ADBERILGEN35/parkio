#!/usr/bin/env python3
"""P3b - read-only provider facts behind the P3 preflight result (push and AI vision) on parkio-civo-prod.

Run as civo on the host (Python 3.8+), after P3:
  python3 -I /tmp/p3b-provider-facts.py

It changes nothing. For the push and AI-vision settings it reports provider names from a fixed list and
credential presence only (absent, empty, placeholder-like, set); it never prints a credential, URL, e-mail,
token, row content or any other environment value. Sources: the live env file (as preflight reads it: the last
occurrence of each key), the release Compose model rendered in memory (what D7 would start, compose defaults
applied), the running containers (what runs today), and counts from the notification database through docker
exec psql (read-only transaction, statement and lock timeouts). Report goes to ~/parkio-release-20261009
(mode 600).

A. Env file. B. Release model. C. Running containers. D. Push impact (counts). E. What the preflight rules and
the services do with these settings (source at 22f96990; the push code is unchanged since bf9cad51).
"""
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
LIVE = os.environ.get("P3B_LIVE", "/opt/parkio")
REL = os.environ.get("P3B_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("P3B_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("P3B_PROJECT", "parkio")
OUT = os.environ.get("P3B_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
PG_PREFIX = os.environ.get("P3B_PG_PREFIX", "parkio-postgres-")
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
# key -> kind: ("name", allowed provider names) | ("bool",) | ("secret",)
KEYS = {
    "PARKIO_PUSH_DELIVERY_PROVIDER": ("name", {"expo", "noop", "fcm-disabled"}),
    "PARKIO_PUSH_DELIVERY_ENABLED": ("bool",),
    "PARKIO_EXPO_ACCESS_TOKEN": ("secret",),
    "PARKIO_EXPO_PUSH_BASE_URL": ("secret",),
    "PARKIO_AI_VISION_PROVIDER": ("name", {"gemini", "heuristic"}),
    "PARKIO_AI_VISION_GEMINI_API_KEY": ("secret",),
}
SERVICES = {
    "notification-service": ["PARKIO_PUSH_DELIVERY_PROVIDER", "PARKIO_PUSH_DELIVERY_ENABLED", "PARKIO_EXPO_ACCESS_TOKEN",
                             "PARKIO_EXPO_PUSH_BASE_URL"],
    "ai-validation-service": ["PARKIO_AI_VISION_PROVIDER", "PARKIO_AI_VISION_GEMINI_API_KEY"],
}
# scripts/preflight-hosted-beta.sh is_placeholder
PLACEHOLDER = re.compile(r"CHANGE_ME|CHANGEME|CHANGE-ME|PLACEHOLDER|REPLACE_ME|REPLACEME|YOUR_|<[A-Za-z_-]+>|DUMMY|"
                         r"SAMPLE_|TODO|FIXME|00000000-0000-0000-0000-000000000000", re.I)
BOOLEAN = {"true", "false", "1", "0", "yes", "no", "on", "off"}

REPORT = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"P3b-provider-facts-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    save()
    sys.exit(1)


def run(cmd, cwd=None, stdin=None, timeout=120):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=cwd, input=stdin, timeout=timeout)
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    return next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")[:160]


def classify(key, value):
    """Provider name from the fixed list, a boolean, or presence only. value None = absent."""
    kind = KEYS[key]
    if value is None:
        return "absent"
    if value == "":
        return "empty"
    if kind[0] == "name":
        return value if value in kind[1] else "<other value>"
    if kind[0] == "bool":
        return value.lower() if value.lower() in BOOLEAN else "<non-boolean>"
    return "placeholder-like" if PLACEHOLDER.search(value) else "set"


def env_file_values():
    """Last occurrence of each key, quotes stripped (preflight env_get semantics), and the line count."""
    values, counts = {}, {}
    with open(ENV_FILE, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            for key in list(KEYS) + ["PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE", "PARKIO_DEPLOYMENT_PROFILE"]:
                if line.startswith(key + "="):
                    raw = line.rstrip("\n").split("=", 1)[1]
                    raw = re.sub(r'^"(.*)"$', r"\1", raw)
                    raw = re.sub(r"^'(.*)'$", r"\1", raw)
                    values[key] = raw
                    counts[key] = counts.get(key, 0) + 1
    return values, counts


def render_model():
    canonical = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    files = [os.path.join(REL, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        files.append(os.path.join(REL, CIVO_OVERLAY))
    cmd = ["docker", "compose", "--env-file", ENV_FILE]
    for f in files:
        cmd += ["-f", f]
    rc, out, err = run(cmd + ["config", "--format", "json"], cwd=REL)
    return (json.loads(out), None) if rc == 0 else (None, first_error(err))


def container_env(service):
    rc, out, _ = run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={service}", "--filter", "status=running"])
    ids = out.split()
    if len(ids) != 1:
        return None, f"{len(ids)} running containers"
    rc, out, _ = run(["docker", "inspect", ids[0]])
    if rc != 0:
        return None, "inspect failed"
    c = json.loads(out)[0]
    env = {}
    for item in (c.get("Config") or {}).get("Env") or []:
        key, _, value = item.partition("=")
        env[key] = value
    return env, (c.get("State") or {}).get("StartedAt", "")[:19]


def describe(keys, values):
    return "; ".join(f"{k} {classify(k, values.get(k))}" for k in keys)


def section_a():
    say("A. Live env file (as preflight reads it: last occurrence of each key, quotes stripped)")
    values, counts = env_file_values()
    for key in KEYS:
        n = counts.get(key, 0)
        say(f"  {key}: {classify(key, values.get(key))}{f' ({n} lines; the last one counts)' if n > 1 else ''}")
    say(f"  PARKIO_DEPLOYMENT_PROFILE: "
        f"{values.get('PARKIO_DEPLOYMENT_PROFILE') if values.get('PARKIO_DEPLOYMENT_PROFILE') in ('hosted-beta', 'azure-hosted-beta', 'invite-production') else ('absent' if 'PARKIO_DEPLOYMENT_PROFILE' not in values else '<other value>')}")
    say(f"  PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE in the file: "
        f"{'present' if 'PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE' in values else 'absent'} (preflight reads it from the "
        f"process environment only, and it accepts no noop push provider)")
    return values


def section_b():
    say("")
    say("B. Release model (rendered in memory; what D7 starts, compose defaults applied)")
    model, err = render_model()
    if model is None:
        say(f"  render failed: {err}")
        return
    services = model.get("services") or {}
    for svc, keys in SERVICES.items():
        env = (services.get(svc) or {}).get("environment") or {}
        if isinstance(env, list):
            env = dict(e.split("=", 1) if "=" in e else (e, "") for e in env)
        values = {k: ("" if env[k] is None else str(env[k])) for k in keys if k in env}
        say(f"  {svc}: {describe(keys, values)}")


def section_c():
    say("")
    say("C. Running containers (what runs today)")
    for svc, keys in SERVICES.items():
        env, note = container_env(svc)
        if env is None:
            say(f"  {svc}: {note}")
            continue
        say(f"  {svc} (started {note}): {describe(keys, env)}")


def section_d():
    say("")
    say("D. Push impact (notification database, counts only)")
    sql = ("SET default_transaction_read_only = on;\nSET statement_timeout = '60s';\nSET lock_timeout = '2s';\n"
           "select 'tok', platform, active, count(*) from device_tokens group by platform, active order by 2, 3;\n"
           "select 'att', status, count(*), count(*) filter (where provider_message_id like 'noop-%'), "
           "coalesce(to_char(max(coalesce(attempted_at, created_at)) at time zone 'UTC', 'YYYY-MM-DD'), '-') "
           "from notification_delivery_attempts where channel = 'PUSH' group by status order by 2;\n")
    cmd = ["docker", "exec", "-i", PG_PREFIX + "notification", "sh", "-c",
           'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -']
    rc, out, err = run(cmd, stdin=sql)
    if rc != 0:
        say(f"  notification database: query failed (exit {rc}) {first_error(err)}")
        return
    rows = [ln.split("|") for ln in out.splitlines() if ln.strip()]
    tokens = [r for r in rows if r[0] == "tok" and len(r) == 4]
    active = sum(int(r[3]) for r in tokens if r[2] == "t")
    inactive = sum(int(r[3]) for r in tokens if r[2] != "t")
    platforms = ", ".join(f"{r[1] if re.match(r'^[A-Za-z_]{1,16}$', r[1]) else '<other>'} "
                          f"{'active' if r[2] == 't' else 'inactive'} {r[3]}" for r in tokens)
    say(f"  device tokens: active {active}, inactive {inactive}{' (' + platforms + ')' if platforms else ''}")
    attempts = [r for r in rows if r[0] == "att" and len(r) == 5]
    say("  PUSH delivery attempts by status: " + ("; ".join(
        f"{r[1] if re.match(r'^[A-Z_]{1,16}$', r[1]) else '<other>'} {r[2]}"
        + (f" (by the noop sender {r[3]})" if r[3] != "0" else "") + f", latest {r[4]}" for r in attempts) or "none"))


def section_e():
    say("")
    say("E. Rules and runtime behaviour (source at 22f96990; push code identical at bf9cad51)")
    for line in (
        "preflight, hosted-beta and azure-hosted-beta profiles: PARKIO_EXPO_ACCESS_TOKEN empty or placeholder-like "
        "-> FAIL (only invite-production is exempt, as a web-only profile)",
        "preflight: push provider expo -> ok; noop or unset -> FAIL; PARKIO_PREFLIGHT_ALLOW_PROVIDER_OVERRIDE=1 accepts "
        "only another real provider, never noop",
        "preflight: AI vision heuristic or unset -> WARN only (uploads fail closed into PENDING_REVIEW); gemini needs "
        "PARKIO_AI_VISION_GEMINI_API_KEY",
        "notification-service, provider noop (the default): each due push attempt is marked SENT with a synthetic "
        "noop-<uuid> id and nothing is sent; attempts exist only for users with an active device token",
        "notification-service, provider expo: startup fails without PARKIO_EXPO_ACCESS_TOKEN (ExpoPushConfig); a wrong "
        "token fails each send and retries up to max-attempts",
        "notification-service, PARKIO_PUSH_DELIVERY_ENABLED=false: the worker sends nothing and attempts stay PENDING "
        "(the invite-production web-only setting, with provider noop and no token)",
        "documented hosted-beta target (env examples, docs/beta/deploy-runbook.md): provider expo with a real token",
    ):
        say(f"  - {line}")


def main():
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if not os.path.isfile(ENV_FILE):
        stop("missing env file")
    rc, out, _ = run(["git", "--no-optional-locks", "-C", REL, "rev-parse", "HEAD"])
    if out.strip() != PIN and not os.environ.get("P3B_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    say(f"P3b provider facts | stamp {STAMP} | release {REL} @ {out.strip()[:12]} | project {PROJECT}")
    say("")
    section_a()
    section_b()
    section_c()
    section_d()
    section_e()
    say("")
    say("P3b COMPLETE (read-only)")
    save()


if __name__ == "__main__":
    main()
