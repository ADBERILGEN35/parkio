#!/usr/bin/env python3
"""Self-test of u06-backupstale-evidence.py, using fakes only. Revised after the independent review (U06-TOOLS-REVIEW.md
M5, M6, L8, L9 and the Info notes).

Fakes:
- an HTTP server stands in for Prometheus and Alertmanager:
  - readiness;
  - instant queries for the notification counters, Alertmanager's process start time and the synthetic series;
  - silences, and active or filtered alerts.
  Its answers carry URLs (generatorURL), which must never reach the output;
- docker answers ps, inspect, image inspect and `amtool config routes test` from a JSON state;
- the textfile and evidence directories are real directories.

Scenarios:
- baseline:
  - PASS at 10:00 UTC;
  - with the 125-minute window: FAIL at 01:30, 02:00 and 04:00; PASS at 00:45 and 04:10;
  - FAIL with the synthetic file or a crashed arm's temporary file present;
  - with an active heartbeat it passes with the NOTE;
  - refused once the test is armed.
- close:
  - PASS with Slack +2 while the heartbeat added webhook +4 (Slack only is counted);
  - the operator's Slack records are required (usage) and must look like a time or permalink;
  - each failure on its own fails: the synthetic file left; the series left; a BackupStale alert left; a new silence; a
    failed Slack delivery; a changed container; a missing milestone; a bad armed scope; Slack +1 while webhook +4; an
    Alertmanager restart; no baseline.
  - +3 passes with the NOTE naming the other alert, never Watchdog.
- records:
  - two closes keep both records;
  - the second manifest lists the first;
  - the first manifest still verifies.
- usage refusals.

  python3 -I u06-backupstale-evidence-selftest.py
"""
import hashlib
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.parse
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "u06-backupstale-evidence.py")
SECRET_URL = "https://hooks.slack.com/services/T000SELF/B000TEST/synthetic0webhook0value"
FIRING = "https://parkio.slack.com/archives/C0123ABCD/p1760350000000100"
RESOLVED = "2026-10-13T11:15Z"
results = []
STATE = {}


def ok(cond, label, extra=""):
    results.append(bool(cond))
    print(("ok   " if cond else "FAIL ") + label + ("" if cond else f"\n{extra[-2500:]}"))


class Fake(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = urllib.parse.parse_qs(u.query)
        st = STATE
        if u.path == "/-/ready":
            return self.reply(200 if st["ready"] else 503, b"ok")
        if u.path == "/api/v1/query":
            expr = q["query"][0]
            if expr.startswith("alertmanager_notifications"):
                integ = "slack" if 'integration="slack"' in expr else "webhook"
                kind = "failed" if "failed" in expr else "sent"
                v = st["counters"][integ][kind]
                res = [{"metric": {"integration": integ, "instance": "x"}, "value": [0, str(v / 2)]},
                       {"metric": {"integration": integ, "instance": "y"}, "value": [0, str(v / 2)]}]
            elif expr.startswith("process_start_time_seconds"):
                res = [{"metric": {"job": "alertmanager"}, "value": [0, str(st["amStart"])]}]
            elif expr.startswith("parkio_backup_last_timestamp_seconds"):
                res = [{"metric": {"scope": "invite-production"}, "value": [0, "1"]}] * st["syntheticSeries"]
            else:
                res = []
            return self.reply(200, json.dumps({"status": "success", "data": {"resultType": "vector", "result": res}}).encode())
        if u.path == "/api/v2/silences":
            return self.reply(200, json.dumps([{"id": i, "status": {"state": "active"}, "comment": SECRET_URL} for i in st["silences"]]).encode())
        if u.path == "/api/v2/alerts":
            if "filter" in q:
                body = [{"labels": {"alertname": "BackupStale"}, "generatorURL": SECRET_URL}] * st["backupStaleInAm"]
            else:
                body = [{"labels": {"alertname": n}, "generatorURL": SECRET_URL} for n in st["active"]]
            return self.reply(200, json.dumps(body).encode())
        return self.reply(404, b"")

    def reply(self, code, body):
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


FAKE_DOCKER = r'''#!/usr/bin/env python3
import json, os, sys
st = json.load(open(os.path.join(os.environ["U06B_W"], "docker.json")))
a = sys.argv[1:]
cs = st["containers"]
if a[0] == "ps":
    print("\n".join(c["Id"] for c in cs)); sys.exit(0)
if a[:2] == ["inspect", "--format"]:
    c = next((c for c in cs if c["Name"] == "/" + a[3]), None)
    if not c: sys.exit(1)
    print(c["Image"]); sys.exit(0)
if a[0] == "inspect":
    print(json.dumps([c for c in cs if c["Id"] in a[1:]])); sys.exit(0)
if a[:2] == ["image", "inspect"]:
    print(json.dumps(["ghcr.io/x@sha256:" + a[-1][7:]])); sys.exit(0)
if a[0] == "exec" and "amtool" in a:
    print(st["receiver"]); sys.exit(0)
sys.exit(2)
'''


def container(svc, n):
    return {"Id": str(n) * 64, "Name": "/parkio-" + svc, "Image": "sha256:" + str(n) * 64,
            "Config": {"Labels": {"com.docker.compose.project": "parkio", "com.docker.compose.service": svc,
                                  "com.docker.compose.config-hash": "h" + str(n)}}}


def main():
    W = tempfile.mkdtemp(prefix="u06b-")
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Fake)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{srv.server_address[1]}"
    outputs = []
    try:
        TF, EV, BIN = (os.path.join(W, d) for d in ("textfile", "evidence", "bin"))
        os.makedirs(TF)
        os.makedirs(BIN)
        open(os.path.join(BIN, "docker"), "w").write(FAKE_DOCKER)
        os.chmod(os.path.join(BIN, "docker"), 0o755)

        def reset(slack=(10, 1), receiver="null"):
            STATE.clear()
            STATE.update(ready=True, counters={"slack": {"sent": slack[0], "failed": slack[1]}, "webhook": {"sent": 40, "failed": 0}},
                         amStart=1760300000.0, syntheticSeries=0, backupStaleInAm=0, silences=["s-owner-1"],
                         active=["Watchdog", "MunicipalIzumIncompleteSnapshotsRepeated"])
            for f in os.listdir(TF):
                os.remove(os.path.join(TF, f))
            open(os.path.join(TF, "parkio_backup.prom"), "w").write("parkio_backup_last_success{scope=\"hosted-beta\"} 1\n")
            open(os.path.join(TF, ".gitkeep"), "w").close()        # tracked in the repository, present on the host (N1)
            shutil.rmtree(EV, ignore_errors=True)
            json.dump({"containers": [container("prometheus", 1), container("alertmanager", 2), container("node-exporter", 3),
                                      container("web", 4)], "receiver": receiver}, open(os.path.join(W, "docker.json"), "w"))

        def tool(*args, at="2026-10-13T10:00:00"):
            epoch = datetime.fromisoformat(at).replace(tzinfo=timezone.utc).timestamp()
            env = {"PATH": BIN + ":" + os.environ["PATH"], "HOME": W, "LANG": "C.UTF-8", "U06B_TEST": "1", "U06B_W": W,
                   "U06B_HOST": socket.gethostname(), "U06B_PROM": base_url, "U06B_AM": base_url, "U06B_TEXTFILE_DIR": TF,
                   "U06B_NOW": str(epoch)}
            p = subprocess.run([sys.executable, "-I", TOOL] + list(args), capture_output=True, text=True, env=env, timeout=120)
            outputs.append(p.stdout + p.stderr)
            return p.returncode, p.stdout + p.stderr

        def close(*extra):
            return tool("close", "--evidence-dir", EV, "--slack-firing", FIRING, "--slack-resolved", RESOLVED, *extra)

        def armed_run(slack_after=(12, 1), webhook_after=(44, 0), milestones=("firing_delivered", "resolved_delivered"), scope="invite-production"):
            """baseline, then what the acceptance script leaves behind after a full run (the heartbeat added webhook sends)."""
            rc, out = tool("baseline", "--evidence-dir", EV)
            assert rc == 0, out
            open(os.path.join(EV, "armed-scope"), "w").write(scope + "\n")
            with open(os.path.join(EV, "events.jsonl"), "w") as fh:
                fh.write(json.dumps({"ts": "2026-10-13T10:05:00Z", "type": "poll", "other_active": "MunicipalIzumIncompleteSnapshotsRepeated,Watchdog"}) + "\n")
                for m in milestones:
                    fh.write(json.dumps({"ts": "2026-10-13T11:06:00Z", "type": "milestone", "name": m}) + "\n")
            STATE["counters"]["slack"] = {"sent": slack_after[0], "failed": slack_after[1]}
            STATE["counters"]["webhook"] = {"sent": webhook_after[0], "failed": webhook_after[1]}

        def records(prefix):
            return sorted(f for f in os.listdir(EV) if f.startswith(prefix))

        # ---- baseline
        reset()
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 0 and "U06 BackupStale baseline: PASS" in out and len(records("baseline-")) == 1, "B1 baseline passes at 10:00 UTC", out)
        ok(oct(os.stat(EV).st_mode & 0o777) == "0o700" and oct(os.stat(os.path.join(EV, records("baseline-")[0])).st_mode & 0o777) == "0o600",
           "B1 evidence directory 700, record 600")
        ok("active alerts: MunicipalIzumIncompleteSnapshotsRepeated" in out and "Watchdog" not in out.split("active alerts:")[1].split("\n")[0],
           "B1 Watchdog is not listed among the active alerts that could reach Slack", out)
        for at, want, label in (("2026-10-13T01:30:00", 1, "B2 01:30 UTC is refused (125 minutes reach 02:55; review L8)"),
                                ("2026-10-13T02:00:00", 1, "B2b 02:00 UTC is refused"),
                                ("2026-10-13T04:00:00", 1, "B2c 04:00 UTC is refused"),
                                ("2026-10-13T04:10:00", 0, "B2d 04:10 UTC passes"),
                                ("2026-10-13T00:45:00", 0, "B2e 00:45 UTC passes (the window ends 02:50)")):
            reset()
            rc, out = tool("baseline", "--evidence-dir", EV, at=at)
            ok(rc == want and ("FAIL the next 125 minutes stay outside" in out) == (want == 1), label, out)
        for name, label in (("parkio_backup_synthetic_stale.prom", "B3 a synthetic file present fails the baseline"),
                            (".parkio_backup_synthetic_stale.Ab12Cd", "B3b a crashed arm's temporary file fails the baseline")):
            reset()
            open(os.path.join(TF, name), "w").write("# marker\n")
            rc, out = tool("baseline", "--evidence-dir", EV)
            ok(rc == 1 and "FAIL the textfile directory holds only parkio_backup.prom" in out, label, out)
        reset()
        open(os.path.join(TF, ".gitkeep"), "w").write("x")
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 1 and "FAIL the textfile directory holds only parkio_backup.prom" in out, "B3c a .gitkeep with content is not the tracked empty file", out)
        reset(receiver="heartbeat")
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 0 and "NOTE the heartbeat is active" in out, "B4 an active heartbeat passes with the NOTE about the slack|webhook sum", out)
        open(os.path.join(EV, "armed-scope"), "w").write("invite-production\n")
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 2 and "already armed" in out and len(records("baseline-")) == 1, "B5 a baseline once the test is armed is refused (review M5)", out)

        # ---- close PASS, with heartbeat webhook sends in the window
        reset(receiver="heartbeat")
        armed_run()
        rc, out = close()
        m1 = records("MANIFEST-")
        ok(rc == 0 and "U06 BackupStale close: PASS" in out and "Slack notifications rose by at least 2 (supporting the FIRING and RESOLVED messages) (+2.0)" in out,
           "C1 close passes with Slack +2 while the heartbeat added webhook +4 (Slack only is counted)", out)
        ok("NOTE Slack rose by 2; other alerts active in the window: MunicipalIzumIncompleteSnapshotsRepeated. The counters" in out,
           "C1 another active alert is named even at exactly +2, and Watchdog is not (review L9)", out)
        lines = open(os.path.join(EV, m1[0])).read().splitlines() if m1 else []
        ok(len(m1) == 1 and all(hashlib.sha256(open(os.path.join(EV, l.split("  ")[1]), "rb").read()).hexdigest() == l.split("  ")[0] for l in lines)
           and {l.split("  ")[1] for l in lines} >= {"armed-scope", "events.jsonl", records("baseline-")[0], records("close-")[0]},
           "C1 a versioned manifest lists every evidence file with its SHA-256", "\n".join(lines))
        # a second close keeps both records and chains the manifests (review M5)
        rc, out = close()
        m2 = records("MANIFEST-")
        first_ok = all(hashlib.sha256(open(os.path.join(EV, l.split("  ")[1]), "rb").read()).hexdigest() == l.split("  ")[0] for l in lines)
        second = open(os.path.join(EV, m2[-1])).read() if len(m2) == 2 else ""
        ok(len(records("close-")) == 2 and len(m2) == 2 and m1[0] in second and first_ok,
           "M5 two closes keep both records; the second manifest lists the first; the first still verifies", second)
        reset(receiver="heartbeat")
        armed_run(slack_after=(11, 1), webhook_after=(44, 0))
        rc, out = close()
        ok(rc == 1 and "FAIL Slack notifications rose by at least 2" in out, "C2 Slack +1 fails even though webhook rose by 4 (no slack|webhook sum)", out)
        cases = [
            ("C3 the synthetic file left behind fails", "FAIL the textfile directory again holds only parkio_backup.prom",
             lambda: open(os.path.join(TF, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")),
            ("C3b a crashed arm's temporary file fails", "FAIL the textfile directory again holds only parkio_backup.prom",
             lambda: open(os.path.join(TF, ".parkio_backup_synthetic_stale.Zz99Yy"), "w").write("x")),
            ("C4 the synthetic series still in Prometheus fails", "FAIL the synthetic series for scope invite-production is gone",
             lambda: STATE.__setitem__("syntheticSeries", 1)),
            ("C5 a new silence fails", "FAIL no silence that was not active at baseline", lambda: STATE["silences"].append("s-new")),
            ("C6 a failed Slack notification fails", "FAIL no failed Slack notification in the window",
             lambda: STATE["counters"]["slack"].__setitem__("failed", 2)),
            ("C7 a changed container fails", "FAIL every container unchanged against the baseline (web)",
             lambda: (lambda st: (st["containers"][3].__setitem__("Id", "9" * 64), json.dump(st, open(os.path.join(W, "docker.json"), "w"))))(
                 json.load(open(os.path.join(W, "docker.json"))))),
            ("C8 a BackupStale alert still in Alertmanager fails", "FAIL no BackupStale alert for scope invite-production",
             lambda: STATE.__setitem__("backupStaleInAm", 1)),
            ("C9 an Alertmanager restart in the window fails, with the reason", "FAIL Alertmanager did not restart in the window",
             lambda: STATE.__setitem__("amStart", 1760350000.0)),
        ]
        for label, expect, mutate in cases:
            reset()
            armed_run()
            mutate()
            rc, out = close()
            ok(rc == 1 and expect in out, label, out)
        reset()
        armed_run(milestones=("firing_delivered",))
        rc, out = close()
        ok(rc == 1 and "FAIL events.jsonl records resolved_delivered" in out, "C10 a missing milestone fails", out)
        reset()
        armed_run(scope="bogus-scope")
        rc, out = close()
        ok(rc == 1 and "FAIL the armed scope is one of the production scopes" in out, "C11 a bad armed scope fails", out)
        reset()
        armed_run(slack_after=(13, 1))
        rc, out = close()
        ok(rc == 0 and "NOTE Slack rose by 3" in out and "MunicipalIzumIncompleteSnapshotsRepeated" in out,
           "C12 +3 passes with the NOTE naming the other active alert", out)
        reset()
        armed_run()
        rc, out = tool("close", "--evidence-dir", EV, "--slack-firing", "yesterday", "--slack-resolved", RESOLVED)
        ok(rc == 1 and "FAIL the operator recorded the Slack FIRING and RESOLVED messages" in out, "C13 a Slack record that is no time or permalink fails", out)
        reset()
        os.makedirs(EV)
        rc, out = close()
        ok(rc == 1 and "FAIL a passing baseline exists" in out, "C14 close without a baseline fails", out)

        # ---- usage
        reset()
        os.makedirs(EV)
        for args, label in ((["baseline"], "no evidence directory"),
                            (["close", "--evidence-dir", EV], "close without the Slack records"),
                            (["close", "--evidence-dir", EV, "--slack-firing", FIRING], "close with one Slack record"),
                            (["close", "--evidence-dir", os.path.join(W, "nope"), "--slack-firing", FIRING, "--slack-resolved", RESOLVED], "a missing directory"),
                            (["baseline", "--evidence-dir", EV, "--extra"], "an extra argument"),
                            (["status", "--evidence-dir", EV], "an unknown command")):
            rc, out = tool(*args)
            ok(rc == 2, f"U usage refusal: {label}", out)
        text = "\n".join(outputs)
        for root, _, files in os.walk(W):
            for f in files:
                if root.startswith(EV):
                    text += open(os.path.join(root, f), encoding="utf-8", errors="replace").read()
        ok(SECRET_URL not in text and "hooks.slack.com" not in text, "no URL from the APIs in any output or record")
    finally:
        srv.shutdown()
        shutil.rmtree(W, ignore_errors=True)
    print(f"=== summary: {sum(results)} ok, {len(results) - sum(results)} FAIL ===")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
