#!/usr/bin/env python3
"""U06 BackupStale test (Asana 1219001731640694): read-only baseline and close checks around
scripts/alerting-live-backup-stale-acceptance.sh. Prepared 2026-10-13; the test itself needs the owner's approval of a
window and of the two announcements. Run on parkio-civo-prod as civo (Python 3.8+), with the same evidence directory as
the acceptance script's PARKIO_LIVE_EVIDENCE_DIR:

  python3 -I u06-backupstale-evidence.py baseline --evidence-dir DIR   (step 2, before `preflight`)
  python3 -I u06-backupstale-evidence.py close    --evidence-dir DIR   (step 8, after `observe --until resolved`)

Why it exists: the acceptance script counts alertmanager_notifications_total{integration=~"slack|webhook"}. Once the
U06 heartbeat is active, the webhook integration rises every 2-2.5 minutes, so that sum no longer isolates the
BackupStale deliveries. This tool records the Slack integration (the operator channel) separately and does the cleanup
checks.

baseline (changes nothing; writes DIR/baseline.json):
  - this host;
  - the window: now and the next 100 minutes stay outside 02:55-04:05 UTC, the 03:30 backup;
  - the textfile directory holds only parkio_backup.prom (no synthetic file);
  - Prometheus and Alertmanager are ready;
  - recorded, without URLs:
    - the slack and webhook counters (sent and failed);
    - Watchdog's receiver (null or heartbeat);
    - the active silences (ids);
    - the active alert names;
    - every parkio container (id, image, config hash).
close (changes nothing; writes DIR/close.json and DIR/MANIFEST-<stamp>.sha256):
  - the textfile directory again holds only parkio_backup.prom;
  - the synthetic series is gone from Prometheus, and no BackupStale alert for the armed scope remains in Alertmanager;
  - no silence that was not active at baseline;
  - every container unchanged against the baseline;
  - the acceptance script's events.jsonl has firing_delivered and resolved_delivered;
  - the Slack counters:
    - failed did not rise;
    - sent rose by at least 2 (FIRING and RESOLVED);
    - more than 2 is reported together with the other alerts active in the window. Slack then has to show exactly one
      FIRING and one RESOLVED "Hosted-beta backup is stale" message, which the operator records;
  - the image ids and repo digests of prometheus, alertmanager and node-exporter.
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
SYNTHETIC = "parkio_backup_synthetic_stale.prom"
WATCHDOG = ["alertname=Watchdog", "severity=heartbeat", "service=observability"]
IMAGES_OF = ("prometheus", "alertmanager", "node-exporter")
CHECKS = []
STAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
URL = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*://[^\s'\"]+")


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


def prom_sum(expr):
    d = get_json(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": expr}))
    if not d or d.get("status") != "success":
        return None
    return sum(float(x["value"][1]) for x in d["data"]["result"])


def prom_count(expr):
    d = get_json(PROM + "/api/v1/query?" + urllib.parse.urlencode({"query": expr}))
    if not d or d.get("status") != "success":
        return None
    return len(d["data"]["result"])


def counters():
    out = {}
    for integ in ("slack", "webhook"):
        out[integ] = {"sent": prom_sum(f'alertmanager_notifications_total{{integration="{integ}"}}'),
                      "failed": prom_sum(f'alertmanager_notifications_failed_total{{integration="{integ}"}}')}
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
    try:
        return sorted(f for f in os.listdir(TEXTFILE_DIR) if not f.startswith("."))
    except OSError:
        return None


def private_write(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)


def finish(evdir, name, rec):
    rec["checks"] = CHECKS
    rec["verdict"] = "PASS" if CHECKS and all(c["pass"] for c in CHECKS) else "FAIL"
    path = os.path.join(evdir, f"{name}.json")
    private_write(path, json.dumps(rec, indent=1, sort_keys=True) + "\n")
    say(f"U06 BackupStale {name}: {rec['verdict']} | record {path}")
    return 0 if rec["verdict"] == "PASS" else 1


def in_backup_window(t):
    start = t.replace(hour=2, minute=55, second=0, microsecond=0)
    for day in (-1, 0, 1):
        s = start + timedelta(days=day)
        if s < t + timedelta(minutes=100) and t < s + timedelta(minutes=70):
            return True
    return False


def cmd_baseline(evdir):
    say(f"U06 BackupStale baseline | {STAMP} | read-only")
    os.makedirs(evdir, mode=0o700, exist_ok=True)
    rec = {"tool": "u06-backupstale-evidence", "command": "baseline", "stamp": STAMP}
    check(f"host {HOST}", socket.gethostname() == HOST, socket.gethostname())
    t = now()
    check("the next 100 minutes stay outside 02:55-04:05 UTC (the 03:30 backup)", not in_backup_window(t), t.strftime("%H:%M UTC"))
    files = textfiles()
    check(f"the textfile directory holds only {REAL}", files == [REAL], ", ".join(files) if files is not None else "unreadable")
    check("Prometheus ready", ready(PROM))
    check("Alertmanager ready", ready(AM))
    c = counters()
    check("slack and webhook counters readable", all(v is not None for i in c.values() for v in i.values()), json.dumps(c))
    receiver = watchdog_receiver()
    check("Watchdog's receiver readable (null or heartbeat)", receiver in ("null", "heartbeat"), receiver or "?")
    if receiver == "heartbeat":
        say("  NOTE the heartbeat is active: the acceptance script's slack|webhook sum includes heartbeat deliveries; the "
            "Slack-only delta from `close` is the delivery evidence")
    sil, alerts, cs = silences(), active_alerts(), containers()
    check("silences, active alerts and containers readable", sil is not None and alerts is not None and cs is not None,
          f"{len(sil or [])} active silence(s); active alerts: {', '.join(alerts or []) or 'none'}")
    rec.update(counters=c, watchdogReceiver=receiver, silences=sil, activeAlerts=alerts, containers=cs, textfiles=files)
    return finish(evdir, "baseline", rec)


def cmd_close(evdir):
    say(f"U06 BackupStale close | {STAMP} | read-only")
    rec = {"tool": "u06-backupstale-evidence", "command": "close", "stamp": STAMP}
    try:
        base = json.load(open(os.path.join(evdir, "baseline.json"), encoding="utf-8"))
    except (OSError, ValueError):
        base = None
    if not check("a passing baseline exists in the evidence directory", bool(base) and base.get("verdict") == "PASS"):
        return finish(evdir, "close", rec)
    files = textfiles()
    check(f"the textfile directory again holds only {REAL}", files == [REAL], ", ".join(files) if files is not None else "unreadable")
    try:
        scope = open(os.path.join(evdir, "armed-scope"), encoding="utf-8").readline().strip()
    except OSError:
        scope = ""
    check("the armed scope is recorded by the acceptance script", re.fullmatch(r"invite-production|hosted-beta|azure-hosted-beta", scope or "") is not None,
          scope or "missing")
    if scope:
        n = prom_count(f'parkio_backup_last_timestamp_seconds{{scope="{scope}"}}')
        check(f"the synthetic series for scope {scope} is gone from Prometheus", n == 0, f"{n} series")
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
                    if x not in ("none", "-", "unknown")})
    c = counters()
    b = base.get("counters") or {}
    sent = None if c["slack"]["sent"] is None or (b.get("slack") or {}).get("sent") is None else c["slack"]["sent"] - b["slack"]["sent"]
    failed = None if c["slack"]["failed"] is None or (b.get("slack") or {}).get("failed") is None else c["slack"]["failed"] - b["slack"]["failed"]
    check("no failed Slack notification in the window", failed == 0, f"failed +{failed}")
    check("Slack notifications rose by at least 2 (FIRING and RESOLVED)", sent is not None and sent >= 2, f"+{sent}")
    if sent is not None and sent > 2:
        say(f"  NOTE Slack rose by {int(sent)}: {int(sent) - 2} other notification(s) in the window; other active alerts seen: "
            f"{', '.join(other) or 'none'}. Slack must show exactly one FIRING and one RESOLVED 'Hosted-beta backup is stale'.")
    images = {}
    for svc in IMAGES_OF:
        rc, out = run(["docker", "inspect", "--format", "{{.Image}}", f"parkio-{svc}"])
        iid = out.strip()
        rc2, digests = run(["docker", "image", "inspect", "--format", "{{json .RepoDigests}}", iid]) if iid else (1, "")
        images[svc] = {"image": iid or None, "repoDigests": json.loads(digests) if rc2 == 0 and digests.strip() else None}
    check("prometheus, alertmanager and node-exporter image ids recorded", all(v["image"] for v in images.values()),
          ", ".join(f"{k} {(v['image'] or '?')[7:19]}" for k, v in images.items()))
    rec.update(counters=c, slackDelta={"sent": sent, "failed": failed}, otherActive=other, images=images,
               milestones={k: v.get("ts") for k, v in milestones.items()}, newSilences=len(new), changedContainers=changed)
    code = finish(evdir, "close", rec)
    lines = []
    for f in sorted(os.listdir(evdir)):
        p = os.path.join(evdir, f)
        if os.path.isfile(p) and not f.startswith("MANIFEST-"):
            lines.append(hashlib.sha256(open(p, "rb").read()).hexdigest() + "  " + f)
    manifest = os.path.join(evdir, f"MANIFEST-{STAMP}.sha256")
    private_write(manifest, "\n".join(lines) + "\n")
    say(f"manifest {manifest} ({len(lines)} files)")
    return code


def main(argv):
    if len(argv) != 3 or argv[0] not in ("baseline", "close") or argv[1] != "--evidence-dir":
        print(__doc__, file=sys.stderr)
        return 2
    evdir = os.path.abspath(argv[2])
    if argv[0] == "close" and not os.path.isdir(evdir):
        print("usage: the evidence directory does not exist", file=sys.stderr)
        return 2
    return cmd_baseline(evdir) if argv[0] == "baseline" else cmd_close(evdir)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
