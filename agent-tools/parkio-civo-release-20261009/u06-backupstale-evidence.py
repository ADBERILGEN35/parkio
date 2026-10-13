#!/usr/bin/env python3
"""U06 BackupStale test (Asana 1219001731640694): read-only baseline and close checks around
scripts/alerting-live-backup-stale-acceptance.sh. Prepared 2026-10-13, revised after the independent review
(U06-TOOLS-REVIEW.md, M5, M6, L8, L9 and the Info notes). The test itself needs the owner's approval of a window and of
the two announcements. Run on parkio-civo-prod as civo (Python 3.8+), with the same evidence directory as the acceptance
script's PARKIO_LIVE_EVIDENCE_DIR:

  python3 -I u06-backupstale-evidence.py baseline --evidence-dir DIR                     (step 2a, before `preflight`)
  python3 -I u06-backupstale-evidence.py close --evidence-dir DIR \
      --slack-firing "<time or permalink of the FIRING message>" --slack-resolved "<time or permalink of the RESOLVED>"
                                                                                          (step 8, after `observe --until resolved`)

Why it exists: the acceptance script counts alertmanager_notifications_total{integration=~"slack|webhook"}. Once the
U06 heartbeat is active, the webhook integration rises every 2-2.5 minutes, so that sum no longer isolates the
BackupStale deliveries. This tool records the Slack integration (the operator channel) separately and does the cleanup
checks.

baseline (changes nothing; writes DIR/baseline-<stamp>.json):
  - refused once the acceptance script has armed the test (DIR/armed-scope exists): a baseline then would be meaningless;
  - this host;
  - the window: now and the next 125 minutes stay outside 02:55-04:05 UTC, the 03:30 backup. The acceptance script's
    own timeouts allow about 115 minutes;
  - the textfile directory holds only parkio_backup.prom (dot files included: no synthetic or temporary file);
  - Prometheus and Alertmanager are ready;
  - recorded, without URLs:
    - the slack and webhook counters (sent and failed);
    - Alertmanager's process start time;
    - Watchdog's receiver (null or heartbeat);
    - the active silences (ids);
    - the active alert names;
    - every parkio container (id, image, config hash).
close (changes nothing; writes DIR/close-<stamp>.json and DIR/MANIFEST-<stamp>.sha256):
  - uses the newest passing baseline in DIR;
  - the textfile directory again holds only parkio_backup.prom (a crashed arm's temporary file fails it);
  - the armed scope is one of the three production scopes;
  - the synthetic series is gone from Prometheus, and no BackupStale alert for the armed scope remains in Alertmanager;
  - no silence that was not active at baseline;
  - every container unchanged against the baseline;
  - the acceptance script's events.jsonl has firing_delivered and resolved_delivered;
  - Alertmanager did not restart in the window (a restart resets the counters; the failure then says so);
  - the Slack counters: failed did not rise, and sent rose by at least 2;
  - the operator's record of the two Slack messages (--slack-firing, --slack-resolved), required. The counter delta
    only supports it: another alert's repeat can stand in for a delivery, and above +2 the other active alerts are
    named. Watchdog never routes to Slack and is not counted among them;
  - the image ids and repo digests of prometheus, alertmanager and node-exporter.
Records are never overwritten. Each manifest lists every evidence file, earlier manifests included, so it chains them.
Exit 0 = PASS, 1 = FAIL, 2 = usage. No URL, webhook or secret is printed; the API answers are reduced to counts and names.
"""
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

TEST = os.environ.get("U06B_TEST") == "1"


def cfg(name, default):
    """Overrides are honoured only by the self-test."""
    return os.environ.get(name, default) if TEST else default


HOST = cfg("U06B_HOST", "parkio-civo-prod")
PROM = cfg("U06B_PROM", "http://127.0.0.1:9090")
AM = cfg("U06B_AM", "http://127.0.0.1:9093")
TEXTFILE_DIR = cfg("U06B_TEXTFILE_DIR", "/opt/parkio/docker/prometheus/textfile")
NOW = cfg("U06B_NOW", "")
PROJECT = "parkio"
REAL = "parkio_backup.prom"
SCOPES = ("invite-production", "hosted-beta", "azure-hosted-beta")
WINDOW_MINUTES = 125
WATCHDOG = ["alertname=Watchdog", "severity=heartbeat", "service=observability"]
IMAGES_OF = ("prometheus", "alertmanager", "node-exporter")
CHECKS = []
_T = time.time()
STAMP = time.strftime("%Y%m%dT%H%M%S", time.gmtime(_T)) + "%03dZ" % (int(_T * 1000) % 1000)   # ms: names sort in time order
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")
SLACK_REF = re.compile(r"^(https://[A-Za-z0-9.-]+\.slack\.com/archives/[A-Z0-9]+/p[0-9]{10,}|\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?Z)$")


def say(line=""):
    print(URL.sub("<url>", line), flush=True)


def check(what, ok, detail=""):
    CHECKS.append({"check": what, "pass": bool(ok), "detail": URL.sub("<url>", str(detail))})
    say(("  PASS " if ok else "  FAIL ") + what + (f" ({detail})" if detail else ""))
    return bool(ok)


def now():
    return datetime.fromtimestamp(float(NOW), timezone.utc) if NOW else datetime.now(timezone.utc)


def base_env():
    env = {k: os.environ[k] for k in ("PATH", "HOME", "LANG", "USER", "LOGNAME") if k in os.environ}
    if TEST and "U06B_W" in os.environ:
        env["U06B_W"] = os.environ["U06B_W"]
    return env


def run(cmd, timeout=60):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL, env=base_env())
    except (OSError, subprocess.TimeoutExpired):
        return 1, ""
    return p.returncode, p.stdout


def get_json(url):
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            return json.loads(r.read().decode("utf-8"))
    except (OSError, ValueError):
        return None


def ready(base):
    try:
        with urllib.request.urlopen(base + "/-/ready", timeout=10) as r:
            return r.status == 200
    except OSError:
        return False


def prom_result(expr):
    d = get_json(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": expr}))
    if not d or d.get("status") != "success":
        return None
    return d["data"]["result"]


def prom_sum(expr):
    r = prom_result(expr)
    return None if r is None else sum(float(x["value"][1]) for x in r)


def counters():
    out = {}
    for integ in ("slack", "webhook"):
        out[integ] = {"sent": prom_sum(f'alertmanager_notifications_total{{integration="{integ}"}}'),
                      "failed": prom_sum(f'alertmanager_notifications_failed_total{{integration="{integ}"}}')}
    r = prom_result('process_start_time_seconds{job="alertmanager"}')
    out["alertmanagerStart"] = None if not r else max(float(x["value"][1]) for x in r)
    return out


def silences():
    d = get_json(AM + "/api/v2/silences")
    return None if d is None else sorted(s.get("id") for s in d if (s.get("status") or {}).get("state") == "active")


def active_alerts():
    d = get_json(AM + "/api/v2/alerts?active=true")
    return None if d is None else sorted({(a.get("labels") or {}).get("alertname", "?") for a in d})


def watchdog_receiver():
    rc, out = run(["docker", "exec", "parkio-alertmanager", "amtool", "config", "routes", "test",
                   "--alertmanager.url=http://localhost:9093"] + WATCHDOG)
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    return lines[-1] if rc == 0 and lines else None


def containers():
    rc, out = run(["docker", "ps", "-a", "--no-trunc", "--filter", f"label=com.docker.compose.project={PROJECT}", "--format", "{{.ID}}"])
    ids = out.split()
    if rc != 0 or not ids:
        return None
    rc, info = run(["docker", "inspect"] + ids)
    if rc != 0:
        return None
    result = {}
    for d in json.loads(info):
        labels = (d.get("Config") or {}).get("Labels") or {}
        svc = labels.get("com.docker.compose.service") or d.get("Name", "").lstrip("/")
        result.setdefault(svc, []).append(json.dumps({"id": d.get("Id"), "image": d.get("Image"),
                                                      "hash": labels.get("com.docker.compose.config-hash")}, sort_keys=True))
    return {k: sorted(v) for k, v in result.items()}


def textfiles():
    """Every entry of the textfile directory, dot files included (a crashed arm leaves .parkio_backup_synthetic_stale.*)."""
    try:
        return sorted(os.listdir(TEXTFILE_DIR))
    except OSError:
        return None


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def finish(evdir, name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    path = os.path.join(evdir, f"{name}-{STAMP}.json")
    private_write(path, json.dumps(rec, indent=1, sort_keys=True) + "\n")
    say(f"U06 BackupStale {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def in_backup_window(t):
    start = t.replace(hour=2, minute=55, second=0, microsecond=0)
    for day in (-1, 0, 1):
        s = start + timedelta(days=day)
        if s < t + timedelta(minutes=WINDOW_MINUTES) and t < s + timedelta(minutes=70):
            return True
    return False


def newest_passing_baseline(evdir):
    best = None
    for f in sorted(os.listdir(evdir)):
        if re.fullmatch(r"baseline-\d{8}T\d{9}Z\.json", f):
            try:
                r = json.load(open(os.path.join(evdir, f), encoding="utf-8"))
            except (OSError, ValueError):
                continue
            if r.get("verdict") == "PASS":
                best = (f, r)
    return best


def cmd_baseline(evdir):
    say(f"U06 BackupStale baseline | {STAMP} | read-only")
    if os.path.exists(os.path.join(evdir, "armed-scope")):
        print("usage: the test is already armed in this evidence directory (armed-scope exists); a baseline now would be "
              "meaningless. Disarm and use a new directory to start over.", file=sys.stderr)
        return 2
    os.makedirs(evdir, mode=0o700, exist_ok=True)
    rec = {"tool": "u06-backupstale-evidence", "command": "baseline", "stamp": STAMP}
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    t = now()
    check(f"the next {WINDOW_MINUTES} minutes stay outside 02:55-04:05 UTC (the 03:30 backup)", not in_backup_window(t), t.strftime("%H:%M UTC"))
    files = textfiles()
    check(f"the textfile directory holds only {REAL}", files == [REAL], ", ".join(files) if files is not None else "unreadable")
    check("Prometheus ready", ready(PROM))
    check("Alertmanager ready", ready(AM))
    c = counters()
    check("slack and webhook counters and Alertmanager's start time readable",
          all(v is not None for k, i in c.items() if k != "alertmanagerStart" for v in i.values()) and c["alertmanagerStart"] is not None,
          json.dumps({k: v for k, v in c.items() if k != "alertmanagerStart"}))
    receiver = watchdog_receiver()
    check("Watchdog's receiver readable (null or heartbeat)", receiver in ("null", "heartbeat"), receiver or "?")
    if receiver == "heartbeat":
        say("  NOTE the heartbeat is active: the acceptance script's slack|webhook sum includes heartbeat deliveries; the "
            "Slack-only delta from `close` is the counter evidence")
    sil, alerts, cs = silences(), active_alerts(), containers()
    check("silences, active alerts and containers readable", sil is not None and alerts is not None and cs is not None,
          f"{len(sil or [])} active silence(s); active alerts: {', '.join(a for a in (alerts or []) if a != 'Watchdog') or 'none'}")
    rec.update(counters=c, watchdogReceiver=receiver, silences=sil, activeAlerts=alerts, containers=cs, textfiles=files)
    return finish(evdir, "baseline", rec)


def cmd_close(evdir, firing, resolved):
    say(f"U06 BackupStale close | {STAMP} | read-only")
    rec = {"tool": "u06-backupstale-evidence", "command": "close", "stamp": STAMP,
           "slackFiring": firing, "slackResolved": resolved}
    found = newest_passing_baseline(evdir)
    if not check("a passing baseline exists in the evidence directory", bool(found), found[0] if found else "none"):
        return finish(evdir, "close", rec)
    rec["baselineRecord"] = found[0]
    base = found[1]
    files = textfiles()
    check(f"the textfile directory again holds only {REAL}", files == [REAL], ", ".join(files) if files is not None else "unreadable")
    try:
        scope = open(os.path.join(evdir, "armed-scope"), encoding="utf-8").readline().strip()
    except OSError:
        scope = ""
    if check("the armed scope is one of the production scopes, as the acceptance script recorded it", scope in SCOPES, scope or "missing"):
        r = prom_result(f'parkio_backup_last_timestamp_seconds{{scope="{scope}"}}')
        check(f"the synthetic series for scope {scope} is gone from Prometheus", r == [], "unreadable" if r is None else f"{len(r)} series")
        d = get_json(AM + "/api/v2/alerts?" + urllib.parse.urlencode([("filter", 'alertname="BackupStale"'), ("filter", f'scope="{scope}"')]))
        check(f"no BackupStale alert for scope {scope} in Alertmanager", d == [], "unreadable" if d is None else f"{len(d)} alert(s)")
    sil = silences()
    new = sorted(set(sil or []) - set(base.get("silences") or []))
    check("no silence that was not active at baseline", sil is not None and not new, f"new: {len(new)}" if sil is not None else "unreadable")
    cs = containers()
    changed = sorted(s for s in set(cs or {}) | set(base.get("containers") or {}) if (cs or {}).get(s) != (base.get("containers") or {}).get(s))
    check("every container unchanged against the baseline", cs is not None and not changed, ", ".join(changed))
    events = []
    try:
        for line in open(os.path.join(evdir, "events.jsonl"), encoding="utf-8"):
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
    except OSError:
        pass
    milestones = {e.get("name"): e for e in events if e.get("type") == "milestone"}
    for m in ("firing_delivered", "resolved_delivered"):
        check(f"events.jsonl records {m}", m in milestones, (milestones.get(m) or {}).get("ts", "missing"))
    other = sorted({x for e in events if e.get("type") == "poll" for x in str(e.get("other_active") or "").replace(",", " ").split()
                    if x not in ("none", "-", "unknown", "Watchdog")})
    c = counters()
    b = base.get("counters") or {}
    restarted = c.get("alertmanagerStart") is None or c.get("alertmanagerStart") != b.get("alertmanagerStart")
    check("Alertmanager did not restart in the window (a restart resets its counters)", not restarted,
          "start time changed: the counter deltas below are not comparable" if restarted else "")
    sent = None if c["slack"]["sent"] is None or (b.get("slack") or {}).get("sent") is None else c["slack"]["sent"] - b["slack"]["sent"]
    failed = None if c["slack"]["failed"] is None or (b.get("slack") or {}).get("failed") is None else c["slack"]["failed"] - b["slack"]["failed"]
    check("no failed Slack notification in the window", failed == 0, f"failed +{failed}")
    check("Slack notifications rose by at least 2 (supporting the FIRING and RESOLVED messages)", sent is not None and sent >= 2, f"+{sent}")
    check("the operator recorded the Slack FIRING and RESOLVED messages (time or permalink)",
          bool(SLACK_REF.match(firing or "")) and bool(SLACK_REF.match(resolved or "")) and firing != resolved,
          "both given" if firing and resolved else "missing")
    if sent is not None and (sent > 2 or other):
        say(f"  NOTE Slack rose by {int(sent)}; other alerts active in the window: {', '.join(other) or 'none'}. The counters cannot "
            "attribute deliveries to BackupStale; the recorded Slack messages are the delivery evidence.")
    images = {}
    for svc in IMAGES_OF:
        rc, out = run(["docker", "inspect", "--format", "{{.Image}}", f"parkio-{svc}"])
        iid = out.strip()
        rc2, digests = run(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", iid]) if iid else (1, "")
        images[svc] = {"image": iid or None, "repoDigests": json.loads(digests) if rc2 == 0 and digests.strip() else None}
    check("prometheus, alertmanager and node-exporter image ids recorded", all(v["image"] for v in images.values()),
          ", ".join(f"{k} {(v['image'] or '?')[7:19]}" for k, v in images.items()))
    rec.update(counters=c, slackDelta={"sent": sent, "failed": failed}, otherActive=other, images=images, alertmanagerRestarted=restarted,
               milestones={k: v.get("ts") for k, v in milestones.items()}, newSilences=len(new), changedContainers=changed)
    code = finish(evdir, "close", rec)
    lines = []
    for f in sorted(os.listdir(evdir)):
        p = os.path.join(evdir, f)
        if os.path.isfile(p) and f != f"MANIFEST-{STAMP}.sha256":
            with open(p, "rb") as fh:
                lines.append(hashlib.sha256(fh.read()).hexdigest() + "  " + f)
    manifest = os.path.join(evdir, f"MANIFEST-{STAMP}.sha256")
    private_write(manifest, "\n".join(lines) + "\n")
    say(f"manifest {manifest} ({len(lines)} files, earlier manifests included)")
    return code


def main(argv):
    if len(argv) < 3 or argv[0] not in ("baseline", "close") or argv[1] != "--evidence-dir":
        print(__doc__, file=sys.stderr)
        return 2
    cmd, evdir, rest = argv[0], os.path.abspath(argv[2]), argv[3:]
    if cmd == "baseline":
        if rest:
            print("usage: baseline takes only --evidence-dir", file=sys.stderr)
            return 2
        return cmd_baseline(evdir)
    opts = {}
    while rest:
        if len(rest) < 2 or rest[0] not in ("--slack-firing", "--slack-resolved") or rest[0] in opts:
            print("usage: close --evidence-dir DIR --slack-firing REF --slack-resolved REF", file=sys.stderr)
            return 2
        opts[rest[0]] = rest[1]
        rest = rest[2:]
    if set(opts) != {"--slack-firing", "--slack-resolved"}:
        print("usage: close needs --slack-firing and --slack-resolved: the time (YYYY-MM-DDTHH:MMZ) or permalink of each "
              "Slack message, recorded by the operator", file=sys.stderr)
        return 2
    if not os.path.isdir(evdir):
        print("usage: the evidence directory does not exist", file=sys.stderr)
        return 2
    return cmd_close(evdir, opts["--slack-firing"], opts["--slack-resolved"])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
