#!/usr/bin/env python3
"""P1 - read-only, value-free facts for the open decisions of the scoped release on parkio-civo-prod.

Run as civo on the host (Python 3.8+):
  python3 -I /tmp/p1-release-facts.py

Release 22f96990 in /opt/parkio-release; live checkout /opt/parkio (dirty, untouched); project parkio.
It changes nothing. It runs docker ps/inspect/info, docker compose config (render only, kept in memory),
read-only SQL through docker exec psql (default_transaction_read_only, statement and lock timeouts),
read-only Kafka offset and consumer-group queries through docker exec (small heap, 60 s bound),
amtool routes show (receiver names and matchers only), cat/readlink of the parking container's
time-zone files, git status/rev-parse without optional locks, statvfs, systemctl is-active/show, and two
public GETs of the marketing site. It writes its report into ~/parkio-release-20261009 only. It prints
no secret, URL, credential, row content or personal data. The only environment values it prints are
booleans of a fixed list of feature flags and time-zone names; for other named keys only set/empty/absent.

A. Pending migrations: PostgreSQL version, size and estimated rows of every table a pending migration
   alters, and duplicate counts that would make a new unique index fail.
B. parking V41: the session time zone V17 ran in and V41 will run in, and the rows each back-fill
   signature matches (counts only).
C. Feature flags that decide what the new releases write (fixed list), and the alert and heartbeat keys.
D. Alertmanager: full blob id of the live renderer and the live routing tree.
E. Backup: the live backup scripts (state and blob ids), offsite receipt support, last metrics and stamp.
F. NR guard prerequisites: logging configuration of the three sources (live vs release model), free space.
G. U12 part A: dead-lettered gamification outbox rows, retained parkio.dlt.user records, parkio.user lag.
H. Marketing bundle: whether the live waitlist form sends consentTextVersion (CL-F18).
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
LIVE = os.environ.get("P1_LIVE", "/opt/parkio")
REL = os.environ.get("P1_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("P1_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("P1_PROJECT", "parkio")
OUT = os.environ.get("P1_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
PG_PREFIX = os.environ.get("P1_PG_PREFIX", "parkio-postgres-")
MARKETING = os.environ.get("P1_MARKETING_BASE", "https://parkio.dev")
NR_BUDGET = os.environ.get("P1_NR_BUDGET", "/var/lib/parkio-nr-log-continuous/budget")
CIVO_TRIGGER = "docker/docker-compose.azure-hosted-beta.yml"
CIVO_OVERLAY = "docker/docker-compose.civo-alertmanager.yml"
# Tables each pending migration alters (release 22f96990 vs the applied versions D0.2.3 reported).
TOUCHED = {
    "gateway": ["waitlist_interest"],
    "auth": ["erasure_requests"],
    "media": ["media_files"],
    "parking": ["parking_sessions", "parking_spot_search_logs", "parking_spot_view_logs"],
    "user": ["user_trust_profiles"],
    "notification": ["outbox_events"],
    "moderation": ["user_reports", "appeals"],
    "analytics": ["outbox_events"],
}
SENTINEL = "00000000-0000-4000-8000-000000000001"
DUPLICATES = {
    "media": [("media V18 uq_media_files_owner_checksum_live",
               "select count(*) from (select 1 from media_files where status <> 'DELETED' "
               "group by owner_user_id, checksum having count(*) > 1) d")],
    "moderation": [("moderation V14 uq_user_reports_reporter_target_reason",
                    "select count(*) from (select 1 from user_reports where reporter_user_id::text <> '%s' "
                    "and target_id::text <> '%s' group by reporter_user_id, target_type, target_id, reason "
                    "having count(*) > 1) d" % (SENTINEL, SENTINEL)),
                   ("moderation V14 uq_appeals_case_user",
                    "select count(*) from (select 1 from appeals where appeal_user_id::text <> '%s' "
                    "group by case_id, appeal_user_id having count(*) > 1) d" % SENTINEL)],
}
FLAGS = ["PARKIO_ACCOUNT_ERASURE_ENABLED", "PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_ENABLED",
         "PARKIO_ACCOUNT_ERASURE_DURABLE_RECORDING_RETRY_WORKER_ENABLED",
         "PARKIO_ACCOUNT_ERASURE_RESTORE_REPLAY_ENABLED", "PARKIO_RESTORE_REPLAY_ENABLED",
         "PARKIO_ERASURE_CHECKPOINT_ENABLED", "PARKIO_ERASURE_STORE_OBJECT_LOCK_ENABLED",
         "PARKIO_MEDIA_ERASURE_WORKER_ENABLED", "PARKIO_WAITLIST_CONSENT_REQUIRED",
         "PARKIO_WAITLIST_OPS_NOTIFICATIONS_ENABLED", "PARKIO_SLACK_BIZ_ENABLED"]
PRESENCE = ["PARKIO_ALERT_SLACK_WEBHOOK_URL", "PARKIO_ALERT_WEBHOOK_URL", "PARKIO_ALERT_HEARTBEAT_URL",
            "PARKIO_ALERT_HEARTBEAT_SECRET", "BACKUP_DIR", "PARKIO_PROMETHEUS_TEXTFILE_DIR"]
BACKUP_FILES = ["scripts/run-production-backup.sh", "scripts/backup-hosted-beta.sh", "scripts/lib/backup-common.sh",
                "scripts/backup-databases.sh", "scripts/backup-minio.sh", "scripts/lib/backup-metrics.py"]
NR_SOURCES = ["gateway-service", "auth-service", "parking-service"]
NR_UNITS = ["parkio-nr-log-source.service", "parkio-nr-log-continuous.service",
            "parkio-nr-log-continuous-guard.timer"]
GUARD_MIN_FREE = 5368709120     # continuous_guard.sh default PARKIO_NR_HOST_MIN_FREE_BYTES
WRAPPER_MIN_FREE = 12 * 1024 ** 3  # deploy capacity gate (parkio_require_free_disk /)
# Kafka CLI tools inherit the broker's KAFKA_HEAP_OPTS inside its container; keep them small, one at a time.
KAFKA_EXEC = ["docker", "exec", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m"]
ZONE = re.compile(r"^[A-Za-z][A-Za-z0-9_+/-]{0,63}$")
BOOLEAN = {"true", "false", "1", "0", "yes", "no", "on", "off"}

REPORT = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line)
    REPORT.append(line)


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    path = os.path.join(OUT, f"P1-facts-{STAMP}.txt")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write("\n".join(REPORT) + "\n")
    print(f"report saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    save()
    sys.exit(1)


def run(cmd, env=None, cwd=None, stdin=None, timeout=120):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, env=env, cwd=cwd, input=stdin, timeout=timeout)
    except FileNotFoundError:
        return 127, "", ""
    except subprocess.TimeoutExpired:
        return 124, "", "timed out"
    return p.returncode, p.stdout, p.stderr


def first_error(err):
    line = next((ln.strip() for ln in (err or "").splitlines() if ln.strip()), "")
    return line[:160]


def blob(path):
    if not path or not os.path.isfile(path):
        return "missing"
    with open(path, "rb") as fh:
        data = fh.read()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


def git(*args, repo=LIVE):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out.strip()


def file_state(rel):
    rc, out = git("status", "--porcelain", "--", rel)
    state = "unmodified" if rc == 0 and not out else ("untracked" if out.startswith("??") else "MODIFIED")
    rc, head = git("rev-parse", f"HEAD:{rel}")
    return state, (head[:12] if rc == 0 else "-")


def env_value(key):
    """(lines, last value) of KEY in the env file; the value never leaves this process unless allowed."""
    count, value = 0, None
    with open(ENV_FILE, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith(key + "="):
                count += 1
                value = line.rstrip("\n").split("=", 1)[1].strip().strip("'\"")
    return count, value


def presence(key):
    count, value = env_value(key)
    return "absent" if not count else ("empty" if not value else "set")


def user_timezone(options):
    m = re.search(r"-Duser\.timezone=(\S+)", options or "")
    if not m:
        return "no user.timezone"
    return f"user.timezone={m.group(1)}" if ZONE.match(m.group(1)) else "user.timezone=<unexpected value>"


def zone_or_mask(value):
    return value if value and ZONE.match(value) else "<unexpected value>"


def gib(n):
    return f"{n / 1024 ** 3:.1f} GiB"


def free_bytes(path):
    while path and not os.path.exists(path):
        path = os.path.dirname(path)
    try:
        st = os.statvfs(path or "/")
    except OSError:
        return None, path
    return st.f_bavail * st.f_frsize, path


def container(service, running=True):
    cmd = ["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
           "--filter", f"label=com.docker.compose.service={service}"]
    if running:
        cmd += ["--filter", "status=running"]
    rc, out, _ = run(cmd)
    ids = out.split()
    if len(ids) != 1:
        return None
    rc, out, _ = run(["docker", "inspect", ids[0]])
    return json.loads(out)[0] if rc == 0 else None


def psql(db, sql):
    script = ("SET default_transaction_read_only = on;\nSET statement_timeout = '60s';\n"
              "SET lock_timeout = '2s';\n" + sql + "\n")
    cmd = ["docker", "exec", "-i", PG_PREFIX + db, "sh", "-c",
           'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -']
    rc, out, err = run(cmd, stdin=script)
    rows = [ln.split("|") for ln in out.splitlines() if ln.strip()]
    return rc, rows, err


def render_model():
    canonical = [ln.strip() for ln in open(os.path.join(REL, "docker", "compose.production.files"), encoding="utf-8")
                 if ln.strip() and not ln.strip().startswith("#")]
    files = [os.path.join(REL, f) for f in canonical]
    if CIVO_TRIGGER in canonical:
        files.append(os.path.join(REL, CIVO_OVERLAY))
    cmd = ["docker", "compose", "--env-file", ENV_FILE]
    for f in files:
        cmd += ["-f", f]
    rc, out, err = run(cmd + ["config", "--format", "json"])
    if rc != 0:
        return None
    return json.loads(out)


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "parkio-release-p1/1"})
    with urllib.request.urlopen(req, timeout=15) as resp:  # nosec - fixed public URL
        return resp.read(2_000_000).decode("utf-8", errors="replace"), {k.lower(): v for k, v in resp.headers.items()}


def section_a():
    say("")
    say("A. Pending migrations: PostgreSQL version, tables they alter (estimated rows, total size), duplicates")
    for db, tables in TOUCHED.items():
        names = ", ".join("'%s'" % t for t in tables)
        sql = ("select 'v', current_setting('server_version_num');\n"
               "select 't', c.relname, case when c.reltuples >= 0 then c.reltuples::bigint else coalesce(s.n_live_tup, -1) end, "
               "pg_total_relation_size(c.oid) from pg_class c "
               "join pg_namespace n on n.oid = c.relnamespace left join pg_stat_user_tables s on s.relid = c.oid "
               f"where n.nspname = 'public' and c.relkind = 'r' and c.relname in ({names}) order by 2;")
        for label, query in DUPLICATES.get(db, []):
            sql += f"\nselect 'd', '{label}', ({query});"
        rc, rows, err = psql(db, sql)
        if rc != 0:
            say(f"  {db}: query failed (exit {rc}) {first_error(err)}")
            continue
        version = next((r[1] for r in rows if r[0] == "v"), "?")
        parts = [f"{r[1]} ~{r[2]} rows {int(r[3]) / 1024 ** 2:.1f} MiB" for r in rows if r[0] == "t" and len(r) == 4]
        missing = [t for t in tables if t not in {r[1] for r in rows if r[0] == "t"}]
        say(f"  {db}: PostgreSQL {version}; {'; '.join(parts) or 'no table found'}"
            f"{'; absent ' + str(missing) if missing else ''}")
        for r in rows:
            if r[0] == "d" and len(r) == 3:
                say(f"    duplicates blocking {r[1]}: {r[2]}")


def section_b():
    say("")
    say("B. parking V41: session time zones and back-fill signatures (counts only)")
    sql = ("select 'tz', current_setting('TimeZone');\n"
           "select 'tzsource', source from pg_settings where name = 'TimeZone';\n"
           "select 'dbrole', coalesce(d.datname, '*') || '/' || coalesce(r.rolname, '*'), cfg from pg_db_role_setting s "
           "left join pg_database d on d.oid = s.setdatabase left join pg_roles r on r.oid = s.setrole "
           "cross join lateral unnest(s.setconfig) as cfg where lower(cfg) like 'timezone=%';\n"
           "select 'coltype', data_type from information_schema.columns where table_schema = 'public' "
           "and table_name = 'parking_sessions' and column_name = 'last_confirmed_at';\n"
           "with v as (select version, installed_on, case when version ~ '^[0-9]+$' then version::int end as n "
           "from flyway_schema_history where success) select 'inst', version, "
           "to_char(installed_on, 'YYYY-MM-DD\"T\"HH24:MI:SS') from v where n = 17 or n = (select max(n) from v) "
           "order by n;\n"
           "select 'sig', count(*), count(last_confirmed_at), count(*) filter (where status = 'ACTIVE'), "
           "count(*) filter (where last_confirmed_at = (started_at at time zone 'UTC')), "
           "count(*) filter (where last_confirmed_at = (started_at at time zone 'Europe/Istanbul') "
           "and last_confirmed_at <> (started_at at time zone 'UTC')), "
           "count(*) filter (where last_confirmed_at = (started_at at time zone current_setting('TimeZone')) "
           "and last_confirmed_at <> (started_at at time zone 'UTC')) from parking_sessions;")
    rc, rows, err = psql("parking", sql)
    facts = {}
    if rc != 0:
        say(f"  parking database: query failed (exit {rc}) {first_error(err)}")
    else:
        for r in rows:
            facts.setdefault(r[0], []).append(r[1:])
        tz = (facts.get("tz") or [["?"]])[0][0]
        say(f"  database server default TimeZone (a psql session that sends none): {zone_or_mask(tz)}"
            f" (source {(facts.get('tzsource') or [['?']])[0][0]})")
        settings = facts.get("dbrole") or []
        say("  ALTER DATABASE/ROLE TimeZone settings: "
            + (", ".join(f"{who} {zone_or_mask(cfg.split('=', 1)[1])}" for who, cfg in settings) or "none"))
        say(f"  parking_sessions.last_confirmed_at type today: {(facts.get('coltype') or [['absent']])[0][0]}")
        for version, installed in facts.get("inst") or []:
            say(f"  flyway V{version} installed_on (wall clock of the migrating session): {installed}")
        sig = (facts.get("sig") or [[]])[0]
        if len(sig) == 6:
            say(f"  rows: total {sig[0]}; with last_confirmed_at {sig[1]}; ACTIVE {sig[2]}")
            say(f"    equal to started_at as UTC wall clock (consistent with a UTC back-fill or app write): {sig[3]}")
            say(f"    Europe/Istanbul back-fill signature (V41 repairs these only in an Istanbul session): {sig[4]}")
            say(f"    signature of the server default zone (what V41 changes in a session in that zone): {sig[5]}")
    c = container("parking-service")
    if not c:
        say("  parking-service container: not exactly one running")
        return
    env = dict(e.split("=", 1) if "=" in e else (e, "") for e in (c.get("Config") or {}).get("Env") or [])
    say(f"  running parking container: TZ {zone_or_mask(env['TZ']) if 'TZ' in env else 'absent'}; "
        + "; ".join(f"{k} {user_timezone(env[k])}" for k in ("JAVA_TOOL_OPTIONS", "JAVA_OPTS", "JDK_JAVA_OPTIONS") if k in env))
    rc, out, _ = run(["docker", "exec", c["Id"], "sh", "-c",
                      "cat /etc/timezone 2>/dev/null | head -1; echo '|'; readlink /etc/localtime 2>/dev/null"], timeout=30)
    tzfile, _, link = out.partition("|")
    say(f"    image files: /etc/timezone {zone_or_mask(tzfile.strip()) if tzfile.strip() else 'absent'}; "
        f"/etc/localtime -> {link.strip() if link.strip() and re.match(r'^[A-Za-z0-9_./+-]+$', link.strip()) else 'not a link'}")
    created = c.get("Created", "")[:19]
    say(f"    container created (UTC): {created}  (compare with the latest installed_on above: about equal means that "
        f"container's migrations ran in a UTC session, if it applied them)")
    count, tzv = env_value("TZ")
    count2, jto = env_value("JAVA_TOOL_OPTIONS")
    say(f"  env file: TZ {zone_or_mask(tzv) if count else 'absent'}; JAVA_TOOL_OPTIONS "
        f"{user_timezone(jto) if count2 else 'absent (compose default, no user.timezone)'}")


def section_c():
    say("")
    say("C. Feature flags (fixed list; unset = the code default) and alert keys (set / empty / absent)")
    for key in FLAGS:
        count, value = env_value(key)
        shown = "unset" if not count else (value.lower() if value.lower() in BOOLEAN or value == "" else "<non-boolean>")
        say(f"  {key}: {shown or 'empty'}")
    say("  " + "; ".join(f"{k} {presence(k)}" for k in PRESENCE))


def section_d():
    say("")
    say("D. Alertmanager")
    path = os.path.join(LIVE, "docker", "alertmanager", "render-config.sh")
    state, head = file_state("docker/alertmanager/render-config.sh")
    say(f"  live render-config.sh blob {blob(path)} ({state}; HEAD {head}); release blob "
        f"{blob(os.path.join(REL, 'docker', 'alertmanager', 'render-config.sh'))}")
    c = container("alertmanager")
    if not c:
        say("  alertmanager container: not exactly one running")
        return
    rc, out, err = run(["docker", "exec", c["Id"], "amtool", "config", "routes", "show",
                        "--config.file=/tmp/alertmanager.yml"], timeout=30)
    if rc != 0:
        say(f"  live routing tree: amtool failed (exit {rc}) {first_error(err)}")
        return
    say("  live routing tree (receiver names and matchers):")
    for line in out.splitlines():
        if line.strip():
            ascii_line = (line.replace("\u2514", "`").replace("\u251c", "|").replace("\u2500", "-")
                          .replace("\u2502", "|"))
            say("    " + re.sub(r"[^\w\s{}=\"!~.,:|()\[\]<>/*+`-]", "", ascii_line))


def section_e():
    say("")
    say("E. Backup (the live checkout's scripts run the nightly backup)")
    for rel in BACKUP_FILES:
        state, head = file_state(rel)
        say(f"  {rel}: {state}; live blob {blob(os.path.join(LIVE, rel))[:12]}; HEAD {head}")
    common = os.path.join(LIVE, "scripts", "lib", "backup-common.sh")
    support = "yes" if os.path.isfile(common) and "offsite-receipt" in open(common, encoding="utf-8", errors="replace").read() else "no"
    say(f"  live backup writes an offsite receipt file: {support}")
    count, tdir = env_value("PARKIO_PROMETHEUS_TEXTFILE_DIR")
    tdir = tdir if count and tdir else "docker/prometheus/textfile"
    prom = os.path.join(tdir if tdir.startswith("/") else os.path.join(LIVE, tdir), "parkio_backup.prom")
    if not os.access(prom, os.R_OK):
        say("  parkio_backup.prom: not readable or absent")
    else:
        now = datetime.now(timezone.utc).timestamp()
        metrics = []
        for line in open(prom, encoding="utf-8", errors="replace"):
            m = re.match(r'^(parkio_backup_[a-z_]+)\{([^}]*)\}\s+([-0-9.eE+]+)\s*$', line.strip())
            if not m or 'scope="azure-hosted-beta"' not in m.group(2):
                continue
            name, value = m.group(1), float(m.group(3))
            if name.endswith("_timestamp_seconds"):
                metrics.append(f"{name} age {(now - value) / 3600:.1f} h")
            elif name in ("parkio_backup_last_success", "parkio_backup_offsite_last_success",
                          "parkio_backup_production_mode", "parkio_backup_encryption_enabled"):
                metrics.append(f"{name} {value:g}")
        say(f"  textfile metrics (scope azure-hosted-beta): {'; '.join(metrics) or 'none'}")
    count, bdir = env_value("BACKUP_DIR")
    bdir = bdir if count and bdir else "./backups"
    bdir = bdir if bdir.startswith("/") else os.path.normpath(os.path.join(LIVE, bdir))
    try:
        stamps = sorted(n for n in os.listdir(bdir) if re.match(r"^\d{4}-?\d{2}-?\d{2}T\d{2}-?\d{2}-?\d{2}Z$", n))
    except OSError:
        say(f"  backup stamps: directory not readable by {os.environ.get('USER', 'this user')} (BACKUP_DIR {presence('BACKUP_DIR')})")
        return
    if not stamps:
        say("  backup stamps: none")
        return
    newest = stamps[-1]
    if not os.access(os.path.join(bdir, newest), os.R_OK | os.X_OK):
        say(f"  backup stamps: {len(stamps)}; newest {newest}; its directory is not readable by "
            f"{os.environ.get('USER', 'this user')}")
        return
    complete = os.path.exists(os.path.join(bdir, newest, "COMPLETE"))
    receipt = os.path.exists(os.path.join(bdir, newest + ".offsite-receipt.json"))
    say(f"  backup stamps: {len(stamps)}; newest {newest}; COMPLETE {'yes' if complete else 'no'}; "
        f"offsite receipt {'yes' if receipt else 'no'}")


def section_f(model):
    say("")
    say("F. NR guard prerequisites (read-only)")
    services = (model or {}).get("services") or {}
    for svc in NR_SOURCES:
        c = container(svc)
        lc = ((c or {}).get("HostConfig") or {}).get("LogConfig") or {}
        live = (lc.get("Type"), (lc.get("Config") or {}).get("max-size"), (lc.get("Config") or {}).get("max-file"))
        ml = (services.get(svc) or {}).get("logging") or {}
        rel = (ml.get("driver"), (ml.get("options") or {}).get("max-size"), (ml.get("options") or {}).get("max-file"))
        rel = tuple(str(x) if x is not None else None for x in rel)
        say(f"  {svc}: live {'/'.join(str(x) for x in live)} | release model {'/'.join(str(x) for x in rel)} | "
            f"{'equal' if live == rel else 'DIFFERENT'}")
    rc, root, _ = run(["docker", "info", "--format", "{{.DockerRootDir}}"])
    for label, path, bound in (("/", "/", WRAPPER_MIN_FREE), ("docker root", root.strip() or "/var/lib/docker", None),
                               ("NR budget filesystem", NR_BUDGET, GUARD_MIN_FREE)):
        n, used = free_bytes(path)
        if n is None:
            say(f"  free space {label}: unknown")
            continue
        verdict = "" if bound is None else (f"; above the {gib(bound)} bound by {gib(n - bound)}" if n >= bound
                                           else f"; BELOW the {gib(bound)} bound")
        say(f"  free space {label}: {gib(n)}{verdict}")
    for unit in NR_UNITS:
        rc, out, _ = run(["systemctl", "is-active", unit])
        say(f"  {unit}: {out.strip() or 'unknown'}")
    rc, out, _ = run(["systemctl", "show", "parkio-nr-log-continuous-guard.timer", "-p", "TimersMonotonic",
                      "-p", "AccuracyUSec", "-p", "LastTriggerUSec", "-p", "NextElapseUSecMonotonic"])
    say(f"  guard timer: {' '.join(out.split()) or 'unknown'}")


def section_g():
    say("")
    say("G. U12 part A (counts only)")
    rc, rows, err = psql("gamification",
                         "select 'dl', event_type, count(*) from outbox_events where dead_lettered and not "
                         "acknowledged_deadletter group by event_type order by event_type;\n"
                         "select 'pending', count(*), coalesce(extract(epoch from now() - min(created_at))::bigint, 0) "
                         "from outbox_events where not published and not dead_lettered;")
    totals = {}
    if rc != 0:
        say(f"  gamification outbox: query failed (exit {rc}) {first_error(err)}")
    else:
        dl = [(r[1], int(r[2])) for r in rows if r[0] == "dl"]
        pend = next((r for r in rows if r[0] == "pending"), ["", "?", "?"])
        totals["dead-lettered gamification rows"] = sum(n for _, n in dl)
        say(f"  open dead-lettered gamification outbox rows: {sum(n for _, n in dl)}"
            f"{' (' + ', '.join(f'{t} {n}' for t, n in dl) + ')' if dl else ''}")
        say(f"  unpublished, not dead-lettered gamification rows: {pend[1]} (oldest {pend[2]} s)")
    k = container("kafka")
    if not k:
        say("  kafka container: not exactly one running")
        return totals
    offsets = {}
    for when in ("-2", "-1"):
        rc, out, err = run(KAFKA_EXEC + [k["Id"], "timeout", "60", "kafka-get-offsets", "--bootstrap-server",
                                          "localhost:9092", "--topic", "parkio.dlt.user", "--time", when], timeout=90)
        if rc != 0:
            say(f"  parkio.dlt.user offsets ({when}): failed (exit {rc}) {first_error(err)}")
            return totals
        for line in out.splitlines():
            m = re.match(r"^parkio\.dlt\.user:(\d+):(\d+)$", line.strip())
            if m:
                offsets.setdefault(int(m.group(1)), {})[when] = int(m.group(2))
    if not offsets:
        say("  parkio.dlt.user: topic absent or has no partitions")
    else:
        kept = {p: v.get("-1", 0) - v.get("-2", 0) for p, v in sorted(offsets.items())}
        totals["retained parkio.dlt.user records"] = sum(kept.values())
        say(f"  parkio.dlt.user retained records: {sum(kept.values())} ("
            + ", ".join(f"p{p} {n}" for p, n in kept.items()) + ")")
    rc, out, err = run(KAFKA_EXEC + [k["Id"], "timeout", "60", "kafka-consumer-groups", "--bootstrap-server",
                                      "localhost:9092", "--describe", "--group", "parkio.user"], timeout=90)
    if rc != 0:
        say(f"  group parkio.user: describe failed (exit {rc}) {first_error(err)}")
        return totals
    header, lags = None, {}
    for line in out.splitlines():
        cols = line.split()
        if "TOPIC" in cols and "LAG" in cols:
            header = cols
            continue
        if header and len(cols) >= len(header) - 3 and cols[0] == "parkio.user":
            row = dict(zip(header, cols))
            lag = row.get("LAG", "-")
            entry = lags.setdefault(row.get("TOPIC", "?"), [0, 0])
            if lag.isdigit():
                entry[0] += int(lag)
            else:
                entry[1] += 1
    say("  group parkio.user lag per topic: "
        + (", ".join(f"{t} {v[0]}{' (+' + str(v[1]) + ' partitions without a committed offset)' if v[1] else ''}"
                     for t, v in sorted(lags.items())) or "no committed offsets reported"))
    return totals


def section_h():
    say("")
    say("H. Marketing bundle (public site; CL-F18 consent)")
    try:
        page, headers = fetch(MARKETING.rstrip("/") + "/")
        m = re.search(r'<meta\s+name="parkio-waitlist-mode"\s+content="([a-z]+)"', page)
        mode = m.group(1) if m and m.group(1) in ("api", "unavailable", "mock") else ("absent" if not m else "<other>")
        say(f"  landing page: waitlist mode {mode}; Content-Security-Policy header "
            f"{'present' if 'content-security-policy' in headers else 'absent'}; Strict-Transport-Security "
            f"{'present' if 'strict-transport-security' in headers else 'absent'}")
        script, _ = fetch(MARKETING.rstrip("/") + "/waitlist.js")
        say(f"  waitlist.js sends consentTextVersion: {'yes' if 'consentTextVersion' in script else 'no'}; "
            f"carries waitlist-consent-v1: {'yes' if 'waitlist-consent-v1' in script else 'no'}")
    except Exception as error:  # noqa: BLE001 - report and continue
        say(f"  marketing site: fetch failed ({type(error).__name__})")


def main():
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if not os.path.isfile(ENV_FILE):
        stop("missing env file")
    rc, head = git("rev-parse", "HEAD", repo=REL)
    if head != PIN and not os.environ.get("P1_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, porcelain = git("status", "--porcelain", repo=REL)
    rc2, live_head = git("rev-parse", "--short=8", "HEAD")
    rc3, live_porcelain = git("status", "--porcelain")
    say(f"P1 release facts | stamp {STAMP} | release {REL} @ {head[:12]} ({len(porcelain.splitlines())} changes)"
        f" | live {LIVE} @ {live_head} ({len(live_porcelain.splitlines())} changes) | project {PROJECT}")
    model = render_model()
    if model is None:
        say("  release model render failed (section F compares against nothing)")
    section_a()
    section_b()
    section_c()
    section_d()
    section_e()
    section_f(model)
    totals = section_g()
    section_h()
    say("")
    say("SUMMARY")
    say("U12 part A totals: " + ("; ".join(f"{k} {v}" for k, v in totals.items()) or "incomplete, see G"))
    say("P1 COMPLETE (read-only)")
    save()


if __name__ == "__main__":
    main()
