#!/usr/bin/env python3
"""P3 - read-only gates before the deployment decisions of the scoped release on parkio-civo-prod.

Run as civo on the host (Python 3.8+), after P2:
  python3 -I /tmp/p3-readonly-gates.py

Release 22f96990 in /opt/parkio-release (clean); live checkout /opt/parkio (untouched); project parkio.
It changes nothing: no container, offset, record, file of either checkout, unit or mount. It runs git status,
the release's preflight (env validation only), the release wrapper's `config --quiet` (renders, starts nothing),
read-only SQL through docker exec psql (default_transaction_read_only, statement and lock timeouts), read-only
Kafka offset, group and broker-config queries through docker exec (small heap, 60 s bound, one at a time),
systemctl is-active/show and statvfs. It prints no secret, environment value, URL, e-mail or row content.
Report and JSON record go to ~/parkio-release-20261009 (mode 600).

A. Release checkout clean at 22f96990; an env-file copy inside it (presence, identical to the live one or not).
B. Preflight of the live env file with the release's scripts/preflight-hosted-beta.sh (profile azure-hosted-beta,
   --skip-compose), reduced to categories, FAIL/WARN subject names and the summary line; the release wrapper's
   models (canonical, and with the gateway's waitlist overlay) render with `config --quiet`.
C. Kafka: every partition of every topic of the 17 consumer groups the release recreates (every service sets
   auto.offset.reset=earliest in its consumer factory): log start, log end, retained offsets, committed offset or
   none, and what a restarted consumer reads (log end minus the committed offset, or minus the log start when
   there is none); group state and members; offsets.retention.minutes; retained records of every parkio.dlt.*
   topic.
D. U12-A: gamification outbox dead-lettered and unpublished rows.
E. parking rows for the V41 gate.
F. NR units and the guard's last result; free space; newest backup and its metrics' age.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

PIN = "22f9699038cdae15ffff5dd7433c4a64aed0a951"
LIVE = os.environ.get("P3_LIVE", "/opt/parkio")
REL = os.environ.get("P3_RELEASE", "/opt/parkio-release")
ENV_FILE = os.environ.get("P3_ENV_FILE", "/opt/parkio/docker/.env.azure-hosted-beta")
PROJECT = os.environ.get("P3_PROJECT", "parkio")
OUT = os.environ.get("P3_OUT", os.path.join(os.path.expanduser("~"), "parkio-release-20261009"))
PG_PREFIX = os.environ.get("P3_PG_PREFIX", "parkio-postgres-")
NR_BUDGET = os.environ.get("P3_NR_BUDGET", "/var/lib/parkio-nr-log-continuous/budget")
WAITLIST_OVERLAY = "docker/docker-compose.waitlist-ops-inbox.yml"
# Consumer groups and topics of every @KafkaListener at 22f96990 (identical at bf9cad51), and the step that
# recreates the service. Every consumer factory sets auto.offset.reset=earliest and enable.auto.commit=false.
GROUPS = {
    "parkio.aivalidation": ("D7 ai-validation", ["parkio.media.media", "parkio.parking.spot"]),
    "parkio.ai-validation.erasure": ("D7 ai-validation", ["parkio.privacy.erasure"]),
    "parkio.analytics": ("D7 analytics", ["parkio.gamification.score", "parkio.notification.notification",
                                          "parkio.parking.spot", "parkio.parking.session"]),
    "parkio.analytics.erasure": ("D7 analytics", ["parkio.privacy.erasure"]),
    "parkio.auth": ("D5 auth", ["parkio.moderation.action"]),
    "parkio.auth.erasure": ("D5 auth", ["parkio.privacy.erasure"]),
    "parkio.gamification": ("D7 gamification", ["parkio.moderation.action", "parkio.moderation.case",
                                                "parkio.parking.spot"]),
    "parkio.gamification.erasure": ("D7 gamification", ["parkio.privacy.erasure"]),
    "parkio.media.erasure": ("D6a media", ["parkio.privacy.erasure"]),
    "parkio.moderation": ("D7 moderation", ["parkio.aivalidation.result", "parkio.media.media", "parkio.parking.spot"]),
    "parkio.moderation.erasure": ("D7 moderation", ["parkio.privacy.erasure"]),
    "parkio.notification": ("D7 notification", ["parkio.gamification.score", "parkio.moderation.action",
                                                "parkio.moderation.case", "parkio.parking.spot", "parkio.parking.session"]),
    "parkio.notification.erasure": ("D7 notification", ["parkio.privacy.erasure"]),
    "parkio.parking": ("D6b parking", ["parkio.aivalidation.result", "parkio.moderation.action"]),
    "parkio.parking.erasure": ("D6b parking", ["parkio.privacy.erasure"]),
    "parkio.user": ("D7 user", ["parkio.gamification.score", "parkio.moderation.action", "parkio.auth.user"]),
    "parkio.user.erasure": ("D7 user", ["parkio.privacy.erasure"]),
}
U12_GROUP, U12_TOPIC = "parkio.user", "parkio.gamification.score"
KAFKA_EXEC = ["docker", "exec", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m"]
GUARD_MIN_FREE = 5368709120
WRAPPER_MIN_FREE = 12 * 1024 ** 3
SUBJECT = re.compile(r"^[A-Za-z0-9_.:/-]{1,80}$")

REPORT = []
RECORD = {"tool": "p3-readonly-gates", "release": PIN}
GATES = []
STAMP = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+")


def say(line=""):
    line = EMAIL.sub("<email>", URL.sub("<url>", line))
    print(line, flush=True)
    REPORT.append(line)


def gate(name, ok, detail=""):
    GATES.append((name, ok))
    say(f"  {'PASS' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")


def save():
    os.makedirs(OUT, mode=0o700, exist_ok=True)
    for suffix, text in ((".txt", "\n".join(REPORT) + "\n"), (".json", json.dumps(RECORD, indent=2, sort_keys=True) + "\n")):
        path = os.path.join(OUT, f"P3-gates-{STAMP}{suffix}")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(text)
        print(f"saved: {path}")


def stop(message):
    say(f"STOP: {message}")
    RECORD["result"] = "STOPPED"
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
    return line[:200]


def git(*args, repo):
    rc, out, _ = run(["git", "--no-optional-locks", "-C", repo] + list(args))
    return rc, out.strip()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def env_value(key):
    count, value = 0, None
    with open(ENV_FILE, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith(key + "="):
                count += 1
                value = line.rstrip("\n").split("=", 1)[1].strip().strip("'\"")
    return count, value


def gib(n):
    return f"{n / 1024 ** 3:.1f} GiB"


def free_bytes(path):
    while path and not os.path.exists(path):
        path = os.path.dirname(path)
    try:
        st = os.statvfs(path or "/")
    except OSError:
        return None
    return st.f_bavail * st.f_frsize


def container(service):
    rc, out, _ = run(["docker", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", f"label=com.docker.compose.service={service}", "--filter", "status=running"])
    ids = out.split()
    return ids[0] if len(ids) == 1 else None


def psql(db, sql):
    script = ("SET default_transaction_read_only = on;\nSET statement_timeout = '60s';\n"
              "SET lock_timeout = '2s';\n" + sql + "\n")
    cmd = ["docker", "exec", "-i", PG_PREFIX + db, "sh", "-c",
           'psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -f -']
    rc, out, err = run(cmd, stdin=script)
    return rc, [ln.split("|") for ln in out.splitlines() if ln.strip()], err


def kafka(kid, *args):
    return run(KAFKA_EXEC + [kid, "timeout", "60"] + list(args), timeout=90)


def section_a():
    say("A. Release checkout")
    for path in (LIVE, REL):
        if not os.path.isdir(path):
            stop(f"missing directory {path}")
    if not os.path.isfile(ENV_FILE):
        stop("missing env file")
    rc, head = git("rev-parse", "HEAD", repo=REL)
    if head != PIN and not os.environ.get("P3_PIN_ANY"):
        stop(f"{REL} is not at {PIN}")
    rc, porcelain = git("status", "--porcelain", repo=REL)
    rc2, live_head = git("rev-parse", "--short=8", "HEAD", repo=LIVE)
    rc3, live_porcelain = git("status", "--porcelain", repo=LIVE)
    say(f"  P3 | stamp {STAMP} | release {REL} @ {head[:12]} | live {LIVE} @ {live_head} "
        f"({len(live_porcelain.splitlines())} changes, untouched) | project {PROJECT}")
    gate("release checkout clean", not porcelain, f"{len(porcelain.splitlines())} changes")
    copy = os.path.join(REL, "docker", os.path.basename(ENV_FILE))
    if os.path.realpath(copy) == os.path.realpath(ENV_FILE):
        state, ok = "is the live env file itself", True
    elif not os.path.exists(copy):
        state, ok = "absent", True
    else:
        same = sha256_file(copy) == sha256_file(ENV_FILE)
        state = f"present, mode {oct(os.stat(copy).st_mode & 0o777)[2:]}, " + (
            "identical to the live env file" if same else "DIFFERENT from the live env file")
        ok = same
    RECORD["releaseEnvCopy"] = state
    gate("no divergent env-file copy in the release checkout", ok,
         f"docker/{os.path.basename(ENV_FILE)} {state}" + ("" if ok else
         "; the wrapper falls back to it when PARKIO_ENV_FILE is unset (decision: remove or refresh)"))


def section_b():
    say("")
    say("B. Preflight (env validation, release scripts) and release wrapper models")
    script = os.path.join(REL, "scripts", "preflight-hosted-beta.sh")
    rc, out, err = run(["sh", script, "--env-file", ENV_FILE, "--deployment-profile", "azure-hosted-beta",
                        "--skip-compose"], cwd=REL, timeout=300)
    summary, fails, warns = "", [], []
    for line in (out + "\n" + err).splitlines():
        s = line.strip()
        m = re.match(r"^\[(.+)\]$", s)
        if m:
            say(f"  [{m.group(1)[:60]}]")
            continue
        m = re.match(r"^(FAIL|WARN)\s+(\S+)", s)
        if m:
            subject = m.group(2).rstrip(":")
            subject = subject if SUBJECT.match(subject) else "<subject>"
            (fails if m.group(1) == "FAIL" else warns).append(subject)
            say(f"    {m.group(1)} {subject}")
            continue
        if s.startswith("SKIP "):
            say(f"    {s[:80]}")
        if s.startswith("=== PREFLIGHT:"):
            summary = re.sub(r"[^\w\s:()=,.-]", "-", s)[:120]
    RECORD["preflight"] = {"exit": rc, "fail": fails, "warn": warns}
    gate("preflight (azure-hosted-beta, env validation)", rc == 0, f"exit {rc}; {summary or 'no summary line'}")
    say("    (--skip-compose: the deployed model is the wrapper's, rendered below; preflight's own render uses the"
        " deprecated azure profile list)")
    env = dict(os.environ, PARKIO_ENV_FILE=ENV_FILE)
    wrapper = os.path.join(REL, "scripts", "parkio-prod-compose.sh")
    for label, extra in (("canonical", []), ("gateway with the waitlist overlay", ["-f", WAITLIST_OVERLAY])):
        rc, out, err = run([wrapper] + extra + ["config", "--quiet"], env=env, cwd=REL, timeout=180)
        unset = sorted(set(re.findall(r'The "([A-Za-z0-9_]+)" variable is not set', err)))
        detail = f"exit {rc}" + (f"; unset variables: {', '.join(unset)}" if unset else "")
        if rc != 0:
            detail += f"; {first_error(err)}"
        gate(f"release wrapper model renders ({label})", rc == 0, detail)


def parse_offsets(text):
    result = {}
    for line in text.splitlines():
        m = re.match(r"^(\S+):(\d+):(-?\d+)$", line.strip())
        if m:
            result[(m.group(1), int(m.group(2)))] = int(m.group(3))
    return result


def section_c():
    say("")
    say("C. Kafka: every partition of the 17 groups the release recreates (auto.offset.reset=earliest everywhere)")
    kid = container("kafka")
    if not kid:
        gate("Kafka facts", False, "kafka container: not exactly one running")
        return None
    topics = sorted({t for _, ts in GROUPS.values() for t in ts})
    pattern = "(" + "|".join(re.escape(t) for t in topics) + r"|parkio\.dlt\..*)"
    offsets = {}
    for when in ("-2", "-1"):
        rc, out, err = kafka(kid, "kafka-get-offsets", "--bootstrap-server", "localhost:9092", "--topic", pattern,
                             "--time", when)
        if rc != 0:
            gate("Kafka facts", False, f"kafka-get-offsets {when} failed (exit {rc}) {first_error(err)}")
            return None
        offsets[when] = parse_offsets(out)
    rc, out, err = kafka(kid, "kafka-consumer-groups", "--bootstrap-server", "localhost:9092", "--list")
    if rc != 0:
        gate("Kafka facts", False, f"group list failed (exit {rc}) {first_error(err)}")
        return None
    existing = set(out.split())
    committed, states = {}, {}
    rc, out, err = kafka(kid, "kafka-consumer-groups", "--bootstrap-server", "localhost:9092", "--describe",
                         "--all-groups")
    if rc != 0 and not out.strip():
        gate("Kafka facts", False, f"group describe failed (exit {rc}) {first_error(err)}")
        return None
    header = None
    for line in out.splitlines():
        cols = line.split()
        if "GROUP" in cols and "PARTITION" in cols:
            header = cols
            continue
        if header and len(cols) >= 6 and cols[2].isdigit():
            row = dict(zip(header, cols))
            if row.get("CURRENT-OFFSET", "-").lstrip("-").isdigit():
                committed[(row["GROUP"], row["TOPIC"], int(row["PARTITION"]))] = int(row["CURRENT-OFFSET"])
    rc, out, err = kafka(kid, "kafka-consumer-groups", "--bootstrap-server", "localhost:9092", "--describe",
                         "--all-groups", "--state")
    for line in out.splitlines():
        cols = line.split()
        if len(cols) >= 3 and cols[0] in GROUPS and cols[-1].isdigit():
            states[cols[0]] = (cols[-2], int(cols[-1]))
    retention = None
    rc, out, _ = kafka(kid, "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092")
    m = re.search(r"\(id: (\d+) ", out)
    if m:
        rc, out, _ = kafka(kid, "kafka-configs", "--bootstrap-server", "localhost:9092", "--entity-type", "brokers",
                           "--entity-name", m.group(1), "--describe", "--all")
        m = re.search(r"offsets\.retention\.minutes=(\d+)", out)
        retention = int(m.group(1)) if m else None
    say(f"  offsets.retention.minutes: {retention if retention is not None else 'unknown'} "
        f"(committed offsets of a group without members expire after it)")
    say("  per partition: pN retained/restart-reads, * = no committed offset (a restarted consumer reads from the log"
        " start); offsets count records (no transactional producer)")
    starts, ends = offsets["-2"], offsets["-1"]
    record, totals = {}, {"partitions": 0, "noCommit": 0, "retained": 0, "pending": 0, "fromLogStart": 0}
    replay = []
    for group in sorted(GROUPS):
        step, group_topics = GROUPS[group]
        state = states.get(group)
        presence = ("absent on the broker (a new consumer would read every retained record)" if group not in existing
                    else (f"{state[0]}, {state[1]} member(s)" if state else "present"))
        say(f"  {group} ({step}): {presence}")
        record[group] = {"step": step, "present": group in existing, "state": state, "topics": {}}
        for topic in group_topics:
            parts = sorted(p for (t, p) in ends if t == topic)
            if not parts:
                say(f"    {topic}: topic absent (no partition, nothing to read)")
                record[group]["topics"][topic] = None
                continue
            cells, t_ret, t_reads, t_nc = [], 0, 0, 0
            rows = []
            for p in parts:
                start, end = starts.get((topic, p), 0), ends[(topic, p)]
                retained = max(end - start, 0)
                c = committed.get((group, topic, p))
                reads = max(end - max(c, start), 0) if c is not None else retained
                totals["partitions"] += 1
                totals["retained"] += retained
                if c is None:
                    totals["noCommit"] += 1
                    totals["fromLogStart"] += reads
                    t_nc += 1
                    if reads:
                        replay.append(f"{group} {topic} p{p} {reads}")
                else:
                    totals["pending"] += reads
                t_ret += retained
                t_reads += reads
                cells.append(f"p{p} {retained}/{reads}{'*' if c is None else ''}")
                rows.append({"partition": p, "logStart": start, "logEnd": end, "retained": retained,
                             "committed": c, "restartReads": reads})
            record[group]["topics"][topic] = rows
            say(f"    {topic}: {'  '.join(cells)}  ({len(parts)} partitions, {len(parts) - t_nc} committed; "
                f"retained {t_ret}; restart reads {t_reads})")
    dlt = {}
    for (t, p), end in sorted(ends.items()):
        if t.startswith("parkio.dlt."):
            dlt.setdefault(t, []).append((p, max(end - starts.get((t, p), 0), 0)))
    say("  DLT topics (retained records per partition): " + ("; ".join(
        f"{t} " + " ".join(f"p{p} {n}" for p, n in rows) for t, rows in sorted(dlt.items())) or "none"))
    others = sorted(g for g in existing if g not in GROUPS)
    say("  other groups on the broker (not recreated by this release): " + (", ".join(
        g if re.match(r"^[A-Za-z0-9._-]{1,120}$", g) else "<name>" for g in others) or "none"))
    say(f"  totals: {totals['partitions']} group partitions, {totals['noCommit']} without a committed offset; retained "
        f"{totals['retained']}; restart reads {totals['pending'] + totals['fromLogStart']} (pending after a commit "
        f"{totals['pending']}, from the log start {totals['fromLogStart']})")
    RECORD["kafka"] = {"offsetsRetentionMinutes": retention, "groups": record, "totals": totals, "otherGroups": others,
                       "dlt": {t: dict(rows) for t, rows in dlt.items()}}
    gate("no release group would re-read retained records from the log start", not replay,
         "none" if not replay else "decision needed: " + "; ".join(replay[:20]))
    u12 = record.get(U12_GROUP, {}).get("topics", {}).get(U12_TOPIC) or []
    u12_reads = sum(r["restartReads"] for r in u12)
    dlt_user = sum(n for _, n in dlt.get("parkio.dlt.user", []))
    return {"u12Reads": u12_reads, "dltUser": dlt_user}


def section_d(kafka_facts):
    say("")
    say("D. U12 part A")
    rc, rows, err = psql("gamification",
                         "select 'dl', count(*) from outbox_events where dead_lettered and not acknowledged_deadletter;\n"
                         "select 'pending', count(*) from outbox_events where not published and not dead_lettered;")
    if rc != 0:
        gate("U12-A", False, f"gamification outbox query failed (exit {rc}) {first_error(err)}")
        return
    facts = {r[0]: int(r[1]) for r in rows if len(r) == 2}
    say(f"  gamification outbox: open dead-lettered {facts.get('dl')}; unpublished, not dead-lettered "
        f"{facts.get('pending')}")
    if kafka_facts is None:
        gate("U12-A", False, "Kafka facts missing")
        return
    say(f"  parkio.dlt.user retained {kafka_facts['dltUser']}; {U12_GROUP} restart reads on {U12_TOPIC} "
        f"{kafka_facts['u12Reads']}")
    RECORD["u12a"] = dict(facts, **kafka_facts)
    gate("U12-A", facts.get("dl") == 0 and kafka_facts["dltUser"] == 0 and kafka_facts["u12Reads"] == 0,
         "dead-lettered 0, DLT 0 and nothing for user-service to re-read on the score topic" if facts.get("dl") == 0
         and kafka_facts["dltUser"] == 0 and kafka_facts["u12Reads"] == 0 else "decision needed per row/record")


def section_e():
    say("")
    say("E. parking rows (V41 gate)")
    rc, rows, err = psql("parking",
                         "select 'coltype', data_type from information_schema.columns where table_schema = 'public' "
                         "and table_name = 'parking_sessions' and column_name = 'last_confirmed_at';\n"
                         "select 'sig', count(*), count(last_confirmed_at), "
                         "count(*) filter (where last_confirmed_at = (started_at at time zone 'Europe/Istanbul') "
                         "and last_confirmed_at <> (started_at at time zone 'UTC')) from parking_sessions;")
    if rc != 0:
        gate("V41: no Istanbul-signature rows", False, f"parking query failed (exit {rc}) {first_error(err)}")
        return
    facts = {}
    for r in rows:
        facts[r[0]] = r[1:]
    sig = facts.get("sig") or ["?", "?", "?"]
    RECORD["parking"] = {"columnType": (facts.get("coltype") or ["absent"])[0], "rows": sig[0],
                         "withLastConfirmedAt": sig[1], "istanbulSignature": sig[2]}
    say(f"  last_confirmed_at type {(facts.get('coltype') or ['absent'])[0]}; rows {sig[0]}; with last_confirmed_at "
        f"{sig[1]}; Istanbul signature {sig[2]}")
    gate("V41: no Istanbul-signature rows", sig[2] == "0",
         "V41 in a UTC session changes no value" if sig[2] == "0" else
         f"{sig[2]} row(s) would stay 3 h off after V41 in UTC (decision before D6b)")


def section_f():
    say("")
    say("F. NR, free space, backup (read-only)")
    states = []
    for unit in ("parkio-nr-log-source.service", "parkio-nr-log-continuous.service",
                 "parkio-nr-log-continuous-guard.timer"):
        rc, out, _ = run(["systemctl", "is-active", unit])
        states.append(f"{unit} {out.strip() or 'unknown'}")
    say("  " + "; ".join(states))
    rc, out, _ = run(["systemctl", "show", "parkio-nr-log-continuous-guard.service", "-p", "Result", "-p",
                      "ExecMainStatus", "-p", "ExecMainExitTimestamp"])
    say(f"  guard service last run: {' '.join(out.split()) or 'unknown'}")
    rc, root, _ = run(["docker", "info", "--format", "{{.DockerRootDir}}"])
    parts = []
    for name, path, bound in (("/", "/", WRAPPER_MIN_FREE), ("docker root", root.strip() or "/var/lib/docker", None),
                              ("NR budget fs", NR_BUDGET, GUARD_MIN_FREE)):
        n = free_bytes(path)
        parts.append(f"{name} unknown" if n is None else f"{name} {gib(n)}" + (
            "" if bound is None else (" (>= bound)" if n >= bound else " (BELOW bound)")))
    say("  free space: " + "; ".join(parts))
    count, tdir = env_value("PARKIO_PROMETHEUS_TEXTFILE_DIR")
    tdir = tdir if count and tdir else "docker/prometheus/textfile"
    prom = os.path.join(tdir if tdir.startswith("/") else os.path.join(LIVE, tdir), "parkio_backup.prom")
    now = datetime.now(timezone.utc).timestamp()
    if os.access(prom, os.R_OK):
        ages = []
        for line in open(prom, encoding="utf-8", errors="replace"):
            m = re.match(r'^(parkio_backup_[a-z_]+_timestamp_seconds)\{([^}]*)\}\s+([-0-9.eE+]+)\s*$', line.strip())
            if m and 'scope="azure-hosted-beta"' in m.group(2):
                ages.append(f"{m.group(1)} age {(now - float(m.group(3))) / 3600:.1f} h")
        say("  backup metrics (azure-hosted-beta): " + ("; ".join(ages) or "no timestamp metric"))
    else:
        say("  backup metrics: not readable")
    count, bdir = env_value("BACKUP_DIR")
    bdir = bdir if count and bdir else "./backups"
    bdir = bdir if bdir.startswith("/") else os.path.normpath(os.path.join(LIVE, bdir))
    try:
        stamps = sorted(n for n in os.listdir(bdir) if re.match(r"^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}Z$", n))
    except OSError:
        stamps = None
    if not stamps:
        say("  newest backup: " + ("directory not readable" if stamps is None else "none"))
    else:
        newest = stamps[-1]
        made = datetime.strptime(newest, "%Y-%m-%dT%H-%M-%SZ").replace(tzinfo=timezone.utc).timestamp()
        complete = os.path.exists(os.path.join(bdir, newest, "COMPLETE"))
        say(f"  newest backup {newest} (age {(now - made) / 3600:.1f} h), COMPLETE {'yes' if complete else 'no'}; "
            f"P5 makes the fresh one")


def main():
    section_a()
    section_b()
    kafka_facts = section_c()
    section_d(kafka_facts)
    section_e()
    section_f()
    rc, porcelain = git("status", "--porcelain", repo=REL)
    gate("release checkout still clean", not porcelain)
    failed = [name for name, ok in GATES if not ok]
    RECORD.update(stamp=STAMP, gates={name: ok for name, ok in GATES}, result="PASS" if not failed else "FAIL")
    say("")
    say("SUMMARY")
    say(f"gates passed {len(GATES) - len(failed)}/{len(GATES)}" + (f"; failed: {', '.join(failed)}" if failed else ""))
    say("P3 COMPLETE (read-only)" if not failed else "P3 COMPLETE (read-only); a gate needs a decision, see FAIL lines")
    save()
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()
