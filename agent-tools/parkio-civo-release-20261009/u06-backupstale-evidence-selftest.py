#!/usr/bin/env python3
"""Self-test of u06-backupstale-evidence.py, using fakes only.

Fakes:
- an HTTP server stands in for Prometheus and Alertmanager: readiness, instant queries for the notification counters
  and the synthetic series, silences, and active or filtered alerts. Its answers carry URLs (generatorURL), which must
  never reach the output;
- docker answers ps, inspect, image inspect and `amtool config routes test` from a JSON state;
- the textfile and evidence directories are real directories.

Scenarios:
- baseline: PASS at 10:00 UTC; FAIL at 02:00 (the 100-minute window reaches the 03:30 backup) and at 04:00; PASS at
  04:10; FAIL with the synthetic file present; with an active heartbeat it passes and prints the NOTE.
- close: PASS (+2 Slack, nothing left, a manifest written). Each failure on its own fails:
  - the synthetic file left;
  - the synthetic series still in Prometheus;
  - a new silence;
  - a failed Slack notification;
  - a changed container;
  - a missing milestone;
  - no baseline.
  +3 Slack passes with the NOTE naming the other active alert.
- usage refusals.

  python3 -I u06-backupstale-evidence-selftest.py
"""
import hashlib
import http.server
import json
import os
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
            STATE.update(ready=True, counters={"slack": {"sent": slack[0], "failed": slack[1]}, "webhook": {"sent": 0, "failed": 0}},
                         syntheticSeries=0, backupStaleInAm=0, silences=["s-owner-1"], active=["Watchdog", "MunicipalIzumIncompleteSnapshotsRepeated"])
            for f in os.listdir(TF):
                os.remove(os.path.join(TF, f))
            open(os.path.join(TF, "parkio_backup.prom"), "w").write("parkio_backup_last_success{scope=\"hosted-beta\"} 1\n")
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

        def armed_run(slack_after=(12, 1), milestones=("firing_delivered", "resolved_delivered")):
            """baseline, then what the acceptance script leaves behind after a full run."""
            rc, out = tool("baseline", "--evidence-dir", EV)
            assert rc == 0, out
            open(os.path.join(EV, "armed-scope"), "w").write("invite-production\n")
            with open(os.path.join(EV, "events.jsonl"), "w") as fh:
                fh.write(json.dumps({"ts": "2026-10-13T10:05:00Z", "type": "poll", "other_active": "MunicipalIzumIncompleteSnapshotsRepeated"}) + "\n")
                for m in milestones:
                    fh.write(json.dumps({"ts": "2026-10-13T11:06:00Z", "type": "milestone", "name": m}) + "\n")
            STATE["counters"]["slack"] = {"sent": slack_after[0], "failed": slack_after[1]}

        # baseline
        reset()
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 0 and "U06 BackupStale baseline: PASS" in out and os.path.isfile(os.path.join(EV, "baseline.json")), "B1 baseline passes at 10:00 UTC", out)
        ok(oct(os.stat(EV).st_mode & 0o777) == "0o700" and oct(os.stat(os.path.join(EV, "baseline.json")).st_mode & 0o777) == "0o600",
           "B1 evidence directory 700, record 600")
        for at, want, label in (("2026-10-13T02:00:00", 1, "B2 02:00 UTC is refused (the window would reach the 03:30 backup)"),
                                ("2026-10-13T04:00:00", 1, "B2b 04:00 UTC is refused"),
                                ("2026-10-13T04:10:00", 0, "B2c 04:10 UTC passes"),
                                ("2026-10-13T01:10:00", 0, "B2d 01:10 UTC passes (ends 02:50)")):
            reset()
            rc, out = tool("baseline", "--evidence-dir", EV, at=at)
            ok(rc == want and ("FAIL the next 100 minutes stay outside" in out) == (want == 1), label, out)
        reset()
        open(os.path.join(TF, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 1 and "FAIL the textfile directory holds only parkio_backup.prom" in out, "B3 a synthetic file present fails the baseline", out)
        reset(receiver="heartbeat")
        rc, out = tool("baseline", "--evidence-dir", EV)
        ok(rc == 0 and "NOTE the heartbeat is active" in out, "B4 an active heartbeat passes with the NOTE about the slack|webhook sum", out)

        # close
        reset()
        armed_run()
        rc, out = tool("close", "--evidence-dir", EV)
        manifests = [f for f in os.listdir(EV) if f.startswith("MANIFEST-")]
        ok(rc == 0 and "U06 BackupStale close: PASS" in out and "Slack notifications rose by at least 2 (FIRING and RESOLVED) (+2.0)" in out,
           "C1 close passes with +2 Slack and everything cleaned up", out)
        lines = open(os.path.join(EV, manifests[0])).read().splitlines() if manifests else []
        ok(len(manifests) == 1 and {l.split("  ")[1] for l in lines} == {"armed-scope", "baseline.json", "close.json", "events.jsonl"} and
           all(hashlib.sha256(open(os.path.join(EV, l.split("  ")[1]), "rb").read()).hexdigest() == l.split("  ")[0] for l in lines),
           "C1 a versioned manifest lists every evidence file with its SHA-256", "\n".join(lines))
        cases = [
            ("C2 the synthetic file left behind fails", "FAIL the textfile directory again holds only parkio_backup.prom",
             lambda: open(os.path.join(TF, "parkio_backup_synthetic_stale.prom"), "w").write("# marker\n")),
            ("C3 the synthetic series still in Prometheus fails", "FAIL the synthetic series for scope invite-production is gone",
             lambda: STATE.__setitem__("syntheticSeries", 1)),
            ("C4 a new silence fails", "FAIL no silence that was not active at baseline", lambda: STATE["silences"].append("s-new")),
            ("C5 a failed Slack notification fails", "FAIL no failed Slack notification in the window",
             lambda: STATE["counters"]["slack"].__setitem__("failed", 2)),
            ("C6 a changed container fails", "FAIL every container unchanged against the baseline (web)",
             lambda: (lambda st: (st["containers"][3].__setitem__("Id", "9" * 64), json.dump(st, open(os.path.join(W, "docker.json"), "w"))))(
                 json.load(open(os.path.join(W, "docker.json"))))),
            ("C7 a BackupStale alert still in Alertmanager fails", "FAIL no BackupStale alert for scope invite-production",
             lambda: STATE.__setitem__("backupStaleInAm", 1)),
            ("C8 fewer than two Slack notifications fails", "FAIL Slack notifications rose by at least 2",
             lambda: STATE["counters"]["slack"].__setitem__("sent", 11)),
        ]
        for label, expect, mutate in cases:
            reset()
            armed_run()
            mutate()
            rc, out = tool("close", "--evidence-dir", EV)
            ok(rc == 1 and expect in out, label, out)
        reset()
        armed_run(milestones=("firing_delivered",))
        rc, out = tool("close", "--evidence-dir", EV)
        ok(rc == 1 and "FAIL events.jsonl records resolved_delivered" in out, "C9 a missing milestone fails", out)
        reset()
        armed_run(slack_after=(13, 1))
        rc, out = tool("close", "--evidence-dir", EV)
        ok(rc == 0 and "NOTE Slack rose by 3: 1 other notification(s)" in out and "MunicipalIzumIncompleteSnapshotsRepeated" in out,
           "C10 +3 Slack passes with the NOTE naming the other active alert", out)
        reset()
        os.makedirs(EV)
        rc, out = tool("close", "--evidence-dir", EV)
        ok(rc == 1 and "FAIL a passing baseline exists" in out, "C11 close without a baseline fails", out)

        # usage
        for args, label in ((["baseline"], "no evidence directory"), (["close", "--evidence-dir", os.path.join(W, "nope")], "a missing directory"),
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
