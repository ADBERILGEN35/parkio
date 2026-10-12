#!/usr/bin/env python3
"""Self-test of t1-release-step.py against a simulated host: every command T1 runs (git, the production wrapper, docker,
psql, Kafka CLI, systemctl/sudo/journalctl, the smoke script, Trivy) and every HTTP request is answered by an in-memory
model of parkio-civo-prod, so the test needs no Docker, network or sudo. Each T1 command is loaded fresh, as on the host.

  python3 -I t1-selftest.py        (prints ok/FAIL lines and SELFTEST PASS/FAIL)
"""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import time
import types
from datetime import datetime, timezone, timedelta

TOOL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "t1-release-step.py")
PIN = "7" * 40
PASSWORD = "S3cret-Test-Password-9x"
EMAIL = "seed@real-e2e.parkio.local"
INFRA = ["alertmanager", "prometheus", "caddy"]
PINNED = ["gateway-service", "auth-service", "media-service", "parking-service", "web"]
BUILT = ["user-service", "gamification-service", "notification-service", "moderation-service", "ai-validation-service",
         "analytics-service"]
DB_OF = {"gateway-service": "gateway", "auth-service": "auth", "media-service": "media", "parking-service": "parking",
         "user-service": "user", "gamification-service": "gamification", "notification-service": "notification",
         "moderation-service": "moderation", "ai-validation-service": "ai-validation", "analytics-service": "analytics"}
SCRIPTS = {"gateway-service": (6, 1), "auth-service": (27, 4), "media-service": (18, 5), "parking-service": (42, 2),
           "user-service": (20, 1), "gamification-service": (15, 0), "notification-service": (15, 1),
           "moderation-service": (14, 1), "ai-validation-service": (11, 0), "analytics-service": (11, 2)}
FAILS = []
ROOTS = []


def check(name, ok, detail=""):
    print(f"{'ok  ' if ok else 'FAIL'} {name}" + (f" ({detail})" if detail and not ok else ""))
    if not ok:
        FAILS.append(name)


def go_blob(version):
    def s(b):
        n, out = len(b), bytearray()
        while True:
            x, n = n & 0x7F, n >> 7
            out.append(x | 0x80 if n else x)
            if not n:
                return bytes(out) + b
    data = b"\x7fELF" + b"\0" * 28 + b"\xff Go buildinf:" + bytes([8, 2]) + b"\0" * 16 + s(version.encode()) + s(b"")
    bio = io.BytesIO()
    with tarfile.open(fileobj=bio, mode="w") as t:
        info = tarfile.TarInfo("pebble")
        info.size = len(data)
        t.addfile(info, io.BytesIO(data))
    return bio.getvalue()


class Host:
    def __init__(self, root):
        self.root = root
        self.rel, self.live, self.out = (os.path.join(root, d) for d in ("release", "live", "out"))
        self.final = os.path.join(root, "final")
        for d in (self.rel, self.live, self.out, self.final):
            os.makedirs(d)
        os.makedirs(os.path.join(self.rel, "docker"))
        open(os.path.join(self.rel, "docker", "compose.production.files"), "w").write(
            "docker/docker-compose.yml\ndocker/docker-compose.apps.yml\ndocker/docker-compose.gmp-release-pins.yml\n")
        open(os.path.join(self.rel, ".trivyignore.yaml"), "w").write("vulnerabilities: []\n")
        os.makedirs(os.path.join(self.live, "docker"))
        self.live_files = [os.path.join(self.live, "docker", f) for f in ("docker-compose.yml", "docker-compose.apps.yml")]
        for f in self.live_files:
            open(f, "w").write("services: {}\n")
        self.env_file = os.path.join(root, "env")
        open(self.env_file, "w").write(f"PARKIO_REAL_USER_EMAIL={EMAIL}\nOTHER=1\n")
        self.flyway = {}
        for svc, (n, pending) in SCRIPTS.items():
            d = os.path.join(self.rel, "services", svc, "src", "main", "resources", "db", "migration")
            os.makedirs(d)
            for v in range(1, n + 1):
                open(os.path.join(d, f"V{v}__s{v}.sql"), "w").write("select 1;\n")
            self.flyway[DB_OF[svc]] = [(str(v), f"V{v}__s{v}.sql", "SQL", "t") for v in range(1, n - pending + 1)]
        self.head = PIN
        self.porcelain = ""
        self.images, self.labels, self.containers = {}, {}, {}
        self.target_hash, self.live_hash, self.model_images = {}, {}, {}
        n = 0
        for svc in INFRA + PINNED + BUILT:
            n += 1
            old = f"sha256:{svc}-old"
            self.containers[svc] = {"id": f"c{n:03d}-old-{svc}", "image": old, "hash": f"hash-old-{svc}", "status": "running",
                                    "health": "healthy", "started": "2026-10-01T00:00:00.000Z", "env": ["A=1"], "mounts": [],
                                    "groupAdd": []}
            self.target_hash[svc] = f"hash-new-{svc}" if svc not in INFRA else f"hash-new-{svc}"
            self.live_hash[svc] = f"hash-old-{svc}"
            if svc in PINNED:
                ref = f"ghcr.io/x/{svc}@sha256:{'ab' * 32}"
                self.model_images[svc] = ref
                self.images[ref] = f"sha256:{svc}-new"
                self.images[f"ghcr.io/x/{svc}:rollback-pre-22f96990"] = old
            if svc in BUILT:
                self.images[f"parkio-{svc}:latest"] = old
                self.images[f"parkio-{svc}:rollback-pre-22f96990"] = old
        self.live_hash["notification-service"] = "hash-live-differs"
        self.live_hash["web"] = "hash-live-differs"
        self.recorded_hash = {svc: f"hash-old-{svc}" for svc in self.containers}
        self.recorded_hash["notification-service"] = "hash-recorded-differs"
        self.kafka_end = {"parkio.gamification.score": {p: 10 * p for p in range(6)}, "parkio.dlt.user": {0: 0, 1: 0, 2: 0}}
        self.kafka_start = {"parkio.gamification.score": {p: 0 for p in range(6)}, "parkio.dlt.user": {0: 0, 1: 0, 2: 0}}
        self.committed = {("parkio.user", "parkio.gamification.score"): {p: 10 * p for p in range(6)}}
        self.units = {"parkio-nr-log-source.service": "active", "parkio-nr-log-continuous.service": "active",
                      "parkio-nr-log-continuous-guard.timer": "active", "parkio-nr-log-continuous-guard.service": "inactive"}
        self.attached = 3
        self.nr_stream = "stderr"
        self.timer_since = time.time() - 86400
        self.guard_runs = []          # (invocation, epoch, ok)
        self.guard_result, self.guard_status, self.guard_fail = "success", "0", False
        self.smoke_fail = 0
        self.pebble_present = set()
        self.trivy_fail = {}
        self.trivy_error = set()      # services whose scans write no report (scanner error, exit 1)
        self.db_age = {"vulnerabilityDb": 3, "javaDb": 20}   # hours; None = that database's metadata missing
        self.now = lambda: datetime.now(timezone.utc)        # the clock T1 sees (t1() sets the fake one)
        self.build_fail = set()
        self.skip_migration = set()
        self.parking_rows = "0"
        self.csrf_broken = False
        self.calls = []
        self.created = {}
        self.n = 100

    # ------------------------------------------------------------ the command interpreter
    def run(self, cmd, cwd=None, env=None, timeout=120, binary=False, stdin=None):
        self.calls.append((list(cmd), cwd))
        out, rc, err = self._run(list(cmd), cwd, env, stdin)
        if binary:
            return rc, out if isinstance(out, bytes) else out.encode(), err
        return rc, out, err

    def _run(self, cmd, cwd, env, stdin):
        if cmd[0] == "git":
            return (self.head if "rev-parse" in cmd else self.porcelain), 0, ""
        if cmd[0] == "./scripts/parkio-prod-compose.sh":
            return self.wrapper(cmd[1:], cwd)
        if cmd[0] == "pgrep":
            return "", 1, ""
        if cmd[:2] == ["systemctl", "show"]:
            if cmd[2].endswith("guard.timer"):
                return "ActiveEnterTimestamp=" + datetime.fromtimestamp(self.timer_since, timezone.utc).strftime(
                    "%a %Y-%m-%d %H:%M:%S UTC") + "\n", 0, ""
            return f"Result={self.guard_result}\nExecMainStatus={self.guard_status}\n", 0, ""
        if cmd[0] == "systemctl":
            return self.units.get(cmd[2], "unknown"), 0, ""
        if cmd[:2] == ["sudo", "systemctl"]:
            if cmd[2] == "start" and cmd[3].endswith("guard.timer") and self.units.get(cmd[3]) != "active":
                self.timer_since = time.time()
            self.units[cmd[3]] = "active" if cmd[2] == "start" else "inactive"
            if cmd[3].endswith("guard.service") and cmd[2] == "start":
                self.units[cmd[3]] = "inactive"
                self.add_guard_run(not self.guard_fail, 0)
                self.guard_result, self.guard_status = ("success", "0") if not self.guard_fail else ("exit-code", "1")
            return "", 0, ""
        if cmd[:2] == ["sudo", "journalctl"]:
            # like journald: systemd's own unit messages surround the script's line; only the script's line carries
            # _SYSTEMD_INVOCATION_ID
            lines = []
            for inv, ts, ok in self.guard_runs:
                us = str(int(ts * 1e6))
                lines.append({"MESSAGE": "Starting parkio-nr-log-continuous-guard.service...", "INVOCATION_ID": inv, "__REALTIME_TIMESTAMP": us})
                lines.append({"MESSAGE": "continuous-guard PASS sources=current transport=running storage=within-bounds" if ok
                              else "continuous-guard FAIL reason=source_not_attached; stopping dedicated log transport",
                              "_SYSTEMD_INVOCATION_ID": inv, "__REALTIME_TIMESTAMP": us})
                lines.append({"MESSAGE": "parkio-nr-log-continuous-guard.service: Deactivated successfully.", "INVOCATION_ID": inv,
                              "__REALTIME_TIMESTAMP": us})
                lines.append({"MESSAGE": "Finished parkio-nr-log-continuous-guard.service.", "INVOCATION_ID": inv, "__REALTIME_TIMESTAMP": us})
            return "".join(json.dumps(x) + "\n" for x in lines), 0, ""
        if cmd[:2] == ["sudo", "env"]:
            # like resolve_production_sources.sh --check-live-helper: PASS/FAIL lines on stderr, stdout empty
            lines = "".join(f"source-preflight PASS: service=s{i} live-helper={'attached' if i < self.attached else 'detached'} "
                            f"id={i}\n" for i in range(3))
            rc = 0 if self.attached == 3 else 1
            return (lines, rc, "") if self.nr_stream == "stdout" else ("", rc, lines)
        if cmd[0] == "journalctl":
            return "guard checks\ncontinuous-guard PASS\n", 0, ""
        if cmd[0] == "scripts/alerting-live-backup-stale-acceptance.sh":
            return "delivery-rules PASS\n", 0, ""
        if cmd[0] == "./scripts/smoke-hosted-beta.sh":
            assert env and env.get("PARKIO_REAL_USER_PASSWORD") == PASSWORD
            return f"PASS: health\n=== smoke summary: pass=12 fail={self.smoke_fail} ===\n", 0 if not self.smoke_fail else 1, ""
        if cmd[0] == "rm":
            shutil.rmtree(cmd[-1], ignore_errors=True)
            return "", 0, ""
        if cmd[0] == "docker":
            return self.docker(cmd[1:], stdin)
        return "", 127, f"unexpected command {cmd[0]}"

    def add_guard_run(self, ok, ago):
        self.n += 1
        self.guard_runs.append((f"{self.n:032x}", time.time() - ago, ok))

    def wrapper(self, args, cwd):
        files = []
        while args and args[0] == "-f":
            files.append(args[1])
            args = args[2:]
        sub = args[0]
        if sub == "config" and args[1] == "--hash":
            svc = args[2]
            if cwd == self.live and svc == "gateway-service":
                return "", 1, "open docker/docker-compose.waitlist-ops-inbox.yml: no such file"

            h = (self.target_hash if cwd == self.rel else self.live_hash)[svc]
            if svc == "gateway-service" and "docker/docker-compose.waitlist-ops-inbox.yml" not in files:
                h += "-no-overlay"
            return f"{svc} {h}\n", 0, ""
        if sub == "config":
            return json.dumps({"services": {s: {"image": r} for s, r in self.model_images.items()}}), 0, ""
        if sub == "build":
            svc = args[-1]
            if svc in self.build_fail:
                return "", 1, "build failed"
            rev = next(a.split("=", 1)[1] for a in args if a.startswith("IMAGE_REVISION="))
            self.n += 1
            new = f"sha256:{svc}-built-{self.n}"
            self.images[f"parkio-{svc}:latest"] = new
            self.labels[new] = rev
            return "built\n", 0, ""
        if sub == "up":
            svcs = [a for a in args[1:] if not a.startswith("-")]
            for svc in svcs:
                self.n += 1
                c = self.containers[svc]
                if cwd == self.rel and c["hash"] == self.target_hash[svc]:
                    if c["status"] == "running":
                        continue  # Compose leaves a running container whose config hash already matches
                    if svc in DB_OF and svc not in self.skip_migration:  # it starts the stopped one: Flyway runs again
                        have = {r[1] for r in self.flyway[DB_OF[svc]]}
                        self.flyway[DB_OF[svc]] += [(str(v), f"V{v}__s{v}.sql", "SQL", "t") for v in range(1, SCRIPTS[svc][0] + 1)
                                                    if f"V{v}__s{v}.sql" not in have]
                        c.update(status="running", health="healthy")
                    continue
                if cwd == self.rel:
                    if svc in INFRA:
                        image = c["image"]
                    elif svc in PINNED:
                        image = self.images[self.model_images[svc]]
                    else:
                        image = self.images[f"parkio-{svc}:latest"]
                    h = self.target_hash[svc]
                    if svc in DB_OF and svc not in self.skip_migration:
                        have = {r[1] for r in self.flyway[DB_OF[svc]]}
                        n, _ = SCRIPTS[svc]
                        self.flyway[DB_OF[svc]] += [(str(v), f"V{v}__s{v}.sql", "SQL", "t") for v in range(1, n + 1)
                                                    if f"V{v}__s{v}.sql" not in have]
                    env = ["A=1"] + (["PARKIO_WAITLIST_OPS_NOTIFICATIONS_EXPORT_DIR=/var/lib/parkio/waitlist-ops-inbox"]
                                     if svc == "gateway-service" and files else [])
                    mounts = ["/var/lib/parkio/waitlist-ops-inbox"] if svc == "gateway-service" and files else []
                    group = ["987"] if svc == "gateway-service" and files else []
                else:
                    image = f"sha256:{svc}-old" if svc not in BUILT else self.images[f"parkio-{svc}:latest"]
                    h, env, mounts, group = self.live_hash[svc], ["A=1"], [], []
                failed_start = cwd == self.rel and svc in self.skip_migration
                self.containers[svc] = {"id": f"c{self.n:03d}-{svc}", "image": image, "hash": h,
                                        "status": "exited" if failed_start else "running",
                                        "health": None if failed_start else "healthy",
                                        "started": f"2026-10-11T10:{self.n % 60:02d}:00.000Z", "env": env,
                                        "mounts": mounts, "groupAdd": group}
            return "up\n", 0, ""
        return "", 2, f"unexpected wrapper subcommand {sub}"

    def docker(self, a, stdin):
        if a[0] == "ps":
            svc = next(x.rsplit("=", 1)[1] for x in a if x.startswith("label=com.docker.compose.service="))
            return self.containers[svc]["id"] + "\n", 0, ""
        if a[0] == "compose":
            files, i = [], 1
            while i < len(a) and a[i].startswith("-"):
                if a[i] == "-f":
                    files.append(a[i + 1])
                i += 2
            sub = a[i:]
            svc = sub[-1]
            if sub[:2] == ["config", "--hash"]:
                if any(not os.path.isfile(f) for f in files):
                    return "", 1, "missing file"
                return f"{svc} {self.recorded_hash[svc]}\n", 0, ""
            if sub[0] == "up":
                self.n += 1
                image = self.images[f"parkio-{svc}:latest"] if svc in BUILT else f"sha256:{svc}-old"
                self.containers[svc] = dict(self.containers[svc], id=f"c{self.n:03d}-rb-{svc}", image=image,
                                            hash=self.recorded_hash[svc], status="running", health="healthy")
                return "up\n", 0, ""
            return "", 2, "unexpected compose"
        if a[0] == "inspect":
            c = next(c for c in self.containers.values() if c["id"] == a[-1])
            svc = next(k for k, v in self.containers.items() if v is c)
            files = self.live_files + ([os.path.join(self.live, "docker", "web-binding.yml")] if svc == "web" else [])
            labels = {"com.docker.compose.config-hash": c["hash"], "com.docker.compose.project.working_dir": self.live,
                      "com.docker.compose.project.config_files": ",".join(files),
                      "com.docker.compose.project.environment_file": self.env_file}
            return json.dumps([{"Id": c["id"], "Image": c["image"], "State": {"Status": c["status"], "StartedAt": c["started"],
                                                                               "Health": {"Status": c["health"]}},
                                "Config": {"Labels": labels, "Env": c["env"]},
                                "Mounts": [{"Destination": m} for m in c["mounts"]], "HostConfig": {"GroupAdd": c["groupAdd"]}}]), 0, ""
        if a[:2] == ["image", "inspect"]:
            ref = a[-1]
            if ref not in self.images:
                return "", 1, "No such image"
            if "revision" in a[3]:
                return self.labels.get(self.images[ref], "unknown") + "\n", 0, ""
            return self.images[ref] + "\n", 0, ""
        if a[0] == "tag":
            if a[1] not in self.images:
                return "", 1, "No such image"
            self.images[a[2]] = self.images[a[1]]
            return "", 0, ""
        if a[0] == "create":
            cid = f"probe{len(self.created)}"
            self.created[cid] = a[-1]
            return cid + "\n", 0, ""
        if a[0] == "cp":
            ref = self.created[a[2].split(":", 1)[0]]
            svc = ref.split(":")[0].replace("parkio-", "")
            if svc in self.pebble_present:
                return go_blob("go1.26.7"), 0, ""
            return b"", 1, "Error response from daemon: Could not find the file /usr/bin/pebble in container x"
        if a[0] == "rm":
            self.created.pop(a[-1], None)
            return "", 0, ""
        if a[:2] == ["image", "rm"]:
            return "", 0, ""
        if a[0] == "run":
            cache = next((x.rsplit(":", 1)[0] for x in a if x.endswith(":/trivy-cache")), None)
            if cache is None or a[a.index("--cache-dir") + 1] != "/trivy-cache":
                return "", 1, "2026-10-12T11:29:56Z\tFATAL\tFatal error\trun error: mkdir /root/.cache: permission denied"
            for sub, name in (("db", "vulnerabilityDb"), ("java-db", "javaDb")):
                if self.db_age.get(name) is not None:
                    os.makedirs(os.path.join(cache, sub), exist_ok=True)
                    at = (self.now() - timedelta(hours=self.db_age[name])).strftime("%Y-%m-%dT%H:%M:%S.123456789Z")
                    path = os.path.join(cache, sub, "metadata.json")
                    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as fh:
                        json.dump({"Version": 2 if sub == "db" else 1, "UpdatedAt": at, "NextUpdate": at, "DownloadedAt": at}, fh)
            ref = a[-1]
            svc = ref.split("/")[1].split(":")[0]
            if svc in self.trivy_error:
                return "", 1, "2026-10-12T11:29:56Z\tFATAL\tFatal error\trun error: init error: DB error"
            lib = "--pkg-types" in a
            failing = self.trivy_fail.get(svc) == ("library" if lib else "critical")
            vulns = [{"VulnerabilityID": "CVE-2026-0001", "PkgName": "org.example:lib", "InstalledVersion": "1.0"}] if failing else []
            res = {"Results": [{"Target": f"{ref} (ubuntu 26.04)", "Vulnerabilities": []}, {"Target": "Java", "Vulnerabilities": vulns}]}
            return json.dumps(res), 1 if failing else 0, ""
        if a[0] == "exec":
            rest = [x for x in a[1:] if x not in ("-i",)]
            if "amtool" in rest:
                return ("Routing tree:\n└── default-route  receiver: warning\n"
                        "    ├── {alertname=\"Watchdog\"}  receiver: null\n"
                        "    ├── {severity=\"critical\"}  receiver: critical\n"
                        "    └── {severity=\"warning\"}  receiver: warning\n"), 0, ""
            if "wget" in rest:
                rules = [{"name": n, "health": "ok"} for n in ("Watchdog", "AlertmanagerNotificationsFailing",
                                                                 "PrometheusNotificationsFailing", "BackupStale")]
                return json.dumps({"status": "success", "data": {"groups": [{"rules": rules}]}}), 0, ""
            if rest[0] == "-e":
                return self.kafka(rest[rest.index("60") + 1:])
            db = rest[0].replace("parkio-postgres-", "")
            return self.psql(db, stdin or "")
        return "", 2, f"unexpected docker {a[0]}"

    def psql(self, db, sql):
        if "flyway_schema_history" in sql:
            return "".join(f"{v}|{s}|{t}|{ok}\n" for v, s, t, ok in self.flyway[db]), 0, ""
        if "outbox_events" in sql:
            return "0|0\n", 0, ""
        if "information_schema" in sql:
            return f"timestamp with time zone\n{self.parking_rows}\n", 0, ""
        if "parking_sessions" in sql:
            return f"{self.parking_rows}\n", 0, ""
        return "", 1, "unexpected sql"

    def kafka(self, a):
        if a[0] == "kafka-get-offsets":
            topic, when = a[a.index("--topic") + 1], a[a.index("--time") + 1]
            src = self.kafka_end if when == "-1" else self.kafka_start
            return "".join(f"{topic}:{p}:{o}\n" for p, o in src.get(topic, {}).items()), 0, ""
        if a[0] == "kafka-consumer-groups":
            group = a[a.index("--group") + 1]
            lines = ["GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID"]
            for (g, t), offs in self.committed.items():
                if g == group:
                    lines += [f"{g} {t} {p} {o} {o} 0 - - -" for p, o in offs.items()]
            return "\n".join(lines) + "\n", 0, ""
        return "", 2, "unexpected kafka"

    # ------------------------------------------------------------ HTTP
    def http(self, method, url, headers=None, body=None, opener=None, timeout=30):
        headers = headers or {}
        if url.endswith("/actuator/health"):
            return 200, {}, b"{}"
        if url.endswith("/actuator/prometheus"):
            return 404, {}, b""
        if url.startswith("https://app.parkio.dev"):
            return 200, {"content-security-policy": "default-src 'self'"}, b""
        if url.rstrip("/") == "https://parkio.dev":
            return 200, {"content-security-policy": "x", "strict-transport-security": "max-age=31536000"}, b""
        if url.endswith("/auth/login"):
            ok = json.loads(body)["password"] == PASSWORD
            return (200, {}, b'{"csrfToken":"tok"}') if ok else (401, {}, b"{}")
        if url.endswith("/auth/refresh-token"):
            if headers.get("Origin") != "https://app.parkio.dev":
                return 403, {}, b""
            if self.csrf_broken:
                return 200, {}, b""
            return (200, {}, b"{}") if headers.get("X-XSRF-TOKEN") == "tok" else (403, {}, b"")
        if url.endswith("/auth/csrf"):
            return 200, {}, b"{}"
        if url.endswith("/auth/logout"):
            return 204, {}, b""
        return 599, {}, b""


class FakeDatetime(datetime):
    @classmethod
    def now(cls, tz=None):
        return datetime(2026, 10, 11, 10, 0, 0, tzinfo=timezone.utc)


def t1(host, *argv, pin=PIN, counter=[0]):
    counter[0] += 1
    os.environ.update(T1_RELEASE=host.rel, T1_LIVE=host.live, T1_ENV_FILE=host.env_file, T1_OUT=host.out, T1_FINAL_DIR=host.final)
    spec = importlib.util.spec_from_file_location(f"t1_{counter[0]}", TOOL)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.PIN, m.STAMP = pin, f"20261011T1000{counter[0]:02d}Z"
    m.run, m.http, m.free_bytes = host.run, host.http, (lambda p: 50 * 1024 ** 3)
    m.time = types.SimpleNamespace(time=time.time, sleep=lambda s: None)
    m.datetime = FakeDatetime
    host.now = lambda: FakeDatetime.now(timezone.utc)
    buf = io.StringIO()
    code = 0
    old = sys.argv
    sys.argv = ["t1"] + list(argv)
    try:
        with contextlib.redirect_stdout(buf):
            m.main()
    except SystemExit as e:
        code = e.code if isinstance(e.code, int) else 1
    finally:
        sys.argv = old
    return code, buf.getvalue()


def module(host, counter=[100]):
    """T1 loaded with the fake host's run(), for unit-level checks."""
    counter[0] += 1
    os.environ.update(T1_RELEASE=host.rel, T1_LIVE=host.live, T1_ENV_FILE=host.env_file, T1_OUT=host.out, T1_FINAL_DIR=host.final)
    spec = importlib.util.spec_from_file_location(f"t1m_{counter[0]}", TOOL)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.run = host.run
    host.now = lambda: datetime.now(timezone.utc)
    return m


def receipt(host, minutes_ago):
    d = os.path.join(host.out, "backup")
    os.makedirs(d, exist_ok=True)
    stamp = (datetime.now(timezone.utc) - timedelta(minutes=minutes_ago)).strftime("%Y-%m-%dT%H-%M-%SZ")
    for f in os.listdir(d):
        os.remove(os.path.join(d, f))
    json.dump({"stamp": stamp, "result": "PASS"}, open(os.path.join(d, f"P5b-receipt-{stamp}.json"), "w"))


def state(host):
    return json.load(open(os.path.join(host.out, "state.json")))


def session():
    os.environ.update(PARKIO_REAL_USER_PASSWORD=PASSWORD, PARKIO_GATEWAY_URL="https://api.parkio.dev",
                      PARKIO_SMOKE_EXPECT_DIRECT_BLOCKED="1", PARKIO_DEPLOYMENT_PROFILE="azure-hosted-beta",
                      PARKIO_ENV_FILE="/x")


def fresh():
    root = tempfile.mkdtemp(prefix="t1selftest-")
    ROOTS.append(root)
    return Host(root)


def main():
    session()
    outputs = []

    # S1: the whole sequence on a healthy host
    h = fresh()
    code, out = t1(h, "plan"); outputs.append(out)
    check("S1 plan exit 0", code == 0, out[-600:])
    check("S1 plan: rollback of notification needs a decision (live wrapper renders a different config)",
          "notification-service" in out and "decision needed" in out)
    check("S1 plan: gateway rollback via its recorded inputs (live wrapper lacks the overlay)",
          "gateway-service: running/healthy" in out and "gateway-service: running/healthy image gateway-serv hash hash-old-gat; rollback: recorded inputs" in out, out[:3000])
    check("S1 plan: web rollback via recorded inputs with the binding rebuilt from its image",
          "web: running/healthy image web-old hash hash-old-web; rollback: recorded inputs" in out
          and os.path.exists(os.path.join(h.out, "planned", "web-binding-rebuilt.yml")))
    check("S1 plan: alertmanager rollback via the live wrapper", "alertmanager: running/healthy image alertmanager hash hash-old-ale; rollback: live wrapper" in out)
    check("S1 planned record written", any(f.startswith("planned-") for f in os.listdir(os.path.join(h.out, "planned"))))
    code, out = t1(h, "step", "D2a"); outputs.append(out)
    check("S1 D2a refused without a fresh P5 receipt", code == 1 and "verified P5 backup within 2 hours" in out
          and "a pre-check failed; nothing was changed" in out)
    receipt(h, 150)
    code, out = t1(h, "step", "D2a"); outputs.append(out)
    check("S1 D2a refused with a 150 min old receipt", code == 1 and "FAIL verified P5 backup within 2 hours" in out)
    receipt(h, 20)
    code, out = t1(h, "step", "D2a"); outputs.append(out)
    check("S1 D2a ok", code == 0, out[-800:])
    check("S1 D2a ran the wrapper with --no-deps alertmanager",
          ("./scripts/parkio-prod-compose.sh up -d --no-build --no-deps alertmanager" in out))
    check("S1 D2a Watchdog route check", "PASS Watchdog -> null ahead of the severity routes" in out)
    code, out = t1(h, "step", "D3"); outputs.append(out)
    check("S1 D3 before D2b/D2c/nr-pause refused", code == 1 and "the previous step nr-pause is not ok" in out)
    for s in ("D2b", "D2c"):
        code, out = t1(h, "step", s); outputs.append(out)
        check(f"S1 {s} ok", code == 0, out[-600:])
    code, out = t1(h, "nr-pause"); outputs.append(out)
    check("S1 nr-pause ok, timer stopped", code == 0 and h.units["parkio-nr-log-continuous-guard.timer"] == "inactive", out[-400:])
    code, out = t1(h, "step", "D3"); outputs.append(out)
    check("S1 D3 ok with overlay, V6, NR attached, smoke", code == 0 and "-f docker/docker-compose.waitlist-ops-inbox.yml up -d" in out
          and "applied now: V6" in out and "PASS NR three sources attached" in out and "PASS smoke exit 0" in out, out[-900:])
    for s in ("D4", "D5", "D6a", "D6b"):
        code, out = t1(h, "step", s); outputs.append(out)
        check(f"S1 {s} ok", code == 0, out[-700:])
    check("S1 D5 applied auth V24-V27", "applied now: V24, V25, V26, V27" in outputs[-3])
    code, out = t1(h, "rollback", "auth-service"); outputs.append(out)
    check("S1 rollback auth after V24-V27 REFUSED (F-INV-3)", code == 1 and "REFUSED (F-INV-3, no override)" in out
          and h.containers["auth-service"]["image"] == "sha256:auth-service-new")
    code, out = t1(h, "step", "D7"); outputs.append(out)
    check("S1 D7 before nr-resume refused", code == 1 and "the previous step nr-resume is not ok" in out)
    code, out = t1(h, "nr-resume"); outputs.append(out)
    check("S1 nr-resume ok, timer active, guard PASS", code == 0 and h.units["parkio-nr-log-continuous-guard.timer"] == "active"
          and "PASS latest guard run printed continuous-guard PASS and no FAIL" in out
          and "PASS guard timer and one guard run started" in out, out[-500:])
    code, out = t1(h, "step", "D7"); outputs.append(out)
    check("S1 D7 ok", code == 0, out[-1500:])
    check("S1 D7 checked pebble absent and scanned before starting",
          out.index("final filesystem without /usr/bin/pebble") < out.index("scan: CRITICAL fixed gate")
          < out.index("D7.4 Run:"))
    check("S1 D7 recorded the remaining exposure", "remaining exposure (recorded, not cleaned)" in out)
    check("S1 D7 tagged parkio/<svc>:sha-<pin8>", f"parkio/user-service:sha-{PIN[:8]}" in h.images)
    d7rec = [f for f in os.listdir(os.path.join(h.out, "steps")) if f.startswith("D7-") and f.endswith(".json")]
    d7j = json.load(open(os.path.join(h.out, "steps", sorted(d7rec)[-1]))) if d7rec else {}
    check("S8 D7 recorded both databases and gated their freshness before starting",
          all((d7j.get("trivyDb") or {}).get(k) for k in ("vulnerabilityDb", "javaDb"))
          and "PASS scan databases: vulnerability DB present and updated within 48 h: updated" in out
          and "PASS scan databases: Java DB present and updated within 168 h: updated" in out
          and out.index("scan databases: Java DB") < out.index("D7.4 Run:"), out[-900:])
    check("S8 D7 scanned with the cache at /trivy-cache, never under /root",
          all(c[c.index("--cache-dir") + 1] == "/trivy-cache" and not any(":/root" in x for x in c)
              for c, _ in h.calls if c[:2] == ["docker", "run"]))
    code, out = t1(h, "final"); outputs.append(out)
    check("S1 final ok", code == 0, out[-1200:])
    man = os.path.join(h.final, "deployed-manifest.json")
    check("S1 deployed-manifest.json written and equal to R/final/current.json", os.path.exists(man)
          and open(man).read() == open(os.path.join(h.out, "final", "current.json")).read())
    mj = json.load(open(man)) if os.path.exists(man) else {}
    check("S1 manifest: gitSha, pins, six images, migrations read from the databases",
          mj.get("gitSha") == PIN and len(mj.get("pinnedImages", {})) == 5 and len(mj.get("images", {})) == 6
          and len(mj.get("migrationVersions", {}).get("auth-service") or []) == 27)
    check("S1 state complete", state(h)["status"] == "complete")
    j = open(os.path.join(h.out, "journal.tsv")).read().splitlines()
    check("S1 journal has start/ok lines and a refused rollback", any("\trefused\t" in ln for ln in j)
          and sum("\tok\t" in ln for ln in j) == 14)
    modes = {oct(os.stat(os.path.join(dp, f)).st_mode & 0o777) for dp, _, fs in os.walk(h.out) for f in fs
             if not dp.endswith("backup")}
    check("S1 T1 records mode 600 (the test's own P5b receipt excluded)", modes == {"0o600"}, str(modes))
    check("S1 no password in any output or record", all(PASSWORD not in o for o in outputs)
          and all(PASSWORD not in open(os.path.join(dp, f), errors="replace").read() for dp, _, fs in os.walk(h.out) for f in fs))
    check("S1 never a whole-project up, --force-recreate or --allow-dirty", not any(
        c[0] == "./scripts/parkio-prod-compose.sh" and ("up" in c and "--no-deps" not in c or "--force-recreate" in c
                                                        or "--allow-dirty" in c) for c, _ in h.calls))

    # S2: a smoke failure stops the sequence; a later step refuses; re-run after the fix
    h = fresh(); receipt(h, 10)
    for s in ("plan",):
        t1(h, s)
    for s in ("D2a", "D2b", "D2c"):
        t1(h, "step", s)
    t1(h, "nr-pause")
    h.smoke_fail = 2
    code, out = t1(h, "step", "D3")
    check("S2 D3 smoke failure -> FAIL, state partial", code == 1 and "FAIL smoke exit 0 and fail=0" in out
          and state(h)["status"] == "partial")
    code, out = t1(h, "step", "D4")
    check("S2 D4 refuses after a failed D3", code == 1 and "the previous step D3 is not ok (fail)" in out)
    h.smoke_fail = 0
    code, out = t1(h, "step", "D3")
    check("S2 D3 re-run ok after the fix", code == 0 and "not recreated: already at the target" in out, out[-500:])

    # S3: D7 aborts before any start when a final image still carries pebble, or fails the scan, or a build fails
    for label, fault in (("pebble", lambda h: h.pebble_present.add("moderation-service")),
                         ("library scan", lambda h: h.trivy_fail.update({"analytics-service": "library"})),
                         ("build", lambda h: h.build_fail.add("notification-service"))):
        h = fresh(); receipt(h, 10)
        t1(h, "plan")
        for s in ("D2a", "D2b", "D2c"):
            t1(h, "step", s)
        t1(h, "nr-pause")
        for s in ("D3", "D4", "D5", "D6a", "D6b"):
            t1(h, "step", s)
        t1(h, "nr-resume")
        before = {s: h.containers[s]["id"] for s in BUILT}
        fault(h)
        code, out = t1(h, "step", "D7")
        check(f"S3 D7 {label} fault -> FAIL before any start", code == 1 and "D7.4 Run:" not in out
              and all(h.containers[s]["id"] == before[s] for s in BUILT), out[-700:])
        check(f"S3 D7 {label} fault -> latest tags pointed back at the rollback tags",
              all(h.images[f"parkio-{s}:latest"] == h.images[f"parkio-{s}:rollback-pre-22f96990"] for s in BUILT))

    # S4: a migration that did not apply fails the step; NR detach fails nr-resume; V41 rows stop D6b; unfilled PIN
    h = fresh(); receipt(h, 10)
    t1(h, "plan")
    for s in ("D2a", "D2b", "D2c"):
        t1(h, "step", s)
    t1(h, "nr-pause")
    t1(h, "step", "D3"); t1(h, "step", "D4")
    h.skip_migration.add("auth-service")
    code, out = t1(h, "step", "D5")
    check("S4 D5 without its migrations -> FAIL (MISSING V24..)", code == 1 and "MISSING V24__s24.sql" in out)
    h.skip_migration.clear()
    code, out = t1(h, "step", "D5")
    check("S4 D5 re-run applies V24-V27", code == 0 and "applied now: V24, V25, V26, V27" in out, out[-400:])
    t1(h, "step", "D6a")
    h.parking_rows = "3"
    code, out = t1(h, "step", "D6b")
    check("S4 D6b with parking rows -> stop before any change", code == 1 and "V41 gate" in out
          and "nothing was changed" in out and h.containers["parking-service"]["image"].endswith("-old"))
    h.parking_rows = "0"
    t1(h, "step", "D6b")
    h.attached = 2
    code, out = t1(h, "nr-resume")
    check("S4 nr-resume with 2/3 attached -> FAIL, timer stays stopped", code == 1
          and h.units["parkio-nr-log-continuous-guard.timer"] == "inactive" and "The timer stays stopped" in out)
    code, out = t1(h, "step", "D7")
    check("S4 D7 refuses after a failed nr-resume", code == 1 and "the previous step nr-resume is not ok" in out)
    code, out = t1(h, "plan", pin="__NEW_PIN__")
    check("S4 unfilled PIN -> STOP", code == 1 and "unfilled PIN" in out)

    # S5: rollback of a service without migrations uses the live wrapper; E fails on a broken CSRF check (no manifest)
    h = fresh(); receipt(h, 10)
    t1(h, "plan"); t1(h, "step", "D2a")
    code, out = t1(h, "rollback", "alertmanager")
    check("S5 rollback alertmanager via the live wrapper restores the previous hash and image", code == 0
          and h.containers["alertmanager"]["hash"] == "hash-old-alertmanager" and "cd " + h.live in out, out[-500:])
    code, out = t1(h, "rollback", "notification-service")
    check("S5 rollback notification refused: not reproducible (env changed since it started)", code == 1 and "owner decision needed" in out)
    t1(h, "step", "D2b"); t1(h, "step", "D2a")
    h2 = fresh(); receipt(h2, 10)
    t1(h2, "plan")
    for st in ("D2a", "D2b", "D2c"):
        t1(h2, "step", st)
    t1(h2, "nr-pause"); t1(h2, "step", "D3"); t1(h2, "step", "D4")
    code, out = t1(h2, "rollback", "web")
    check("S5 rollback web via recorded inputs restores the previous image and hash", code == 0
          and h2.containers["web"]["hash"] == "hash-old-web" and h2.containers["web"]["image"] == "sha256:web-old"
          and "web binding rebuilt from its image" in out, out[-600:])
    h = fresh(); receipt(h, 10)
    t1(h, "plan")
    for s in ("D2a", "D2b", "D2c"):
        t1(h, "step", s)
    t1(h, "nr-pause")
    for s in ("D3", "D4", "D5", "D6a", "D6b"):
        t1(h, "step", s)
    t1(h, "nr-resume"); t1(h, "step", "D7")
    h.csrf_broken = True
    code, out = t1(h, "final")
    check("S5 final with a broken CSRF check -> FAIL, no manifest", code == 1 and "FAIL refresh without X-XSRF-TOKEN -> 403" in out
          and not os.path.exists(os.path.join(h.final, "deployed-manifest.json")))
    h.csrf_broken = False
    h.committed[("parkio.user", "parkio.gamification.score")][3] = 5
    code, out = t1(h, "final")
    check("S5 final with parkio.user behind on a score partition -> FAIL (U12 B)", code == 1 and "behind: [3]" in out)

    # S7 (2026-10-12 regression): the guard unit's journal is not readable as civo and systemd logs its own unit messages after
    # the script's PASS line; T1 v3 reads the latest invocation's own lines with sudo. With the timer already active (started by
    # an earlier nr-resume) nothing is started.
    h = fresh(); receipt(h, 10)
    t1(h, "plan")
    for st in ("D2a", "D2b", "D2c"):
        t1(h, "step", st)
    t1(h, "nr-pause")
    for st in ("D3", "D4", "D5", "D6a", "D6b"):
        t1(h, "step", st)
    h.units["parkio-nr-log-continuous-guard.timer"] = "active"
    h.timer_since = time.time() - 600
    h.add_guard_run(True, 70)
    h.add_guard_run(True, 10)
    before = len(h.calls)
    code, out = t1(h, "nr-resume")
    starts = [c for c, _ in h.calls[before:] if c[:3] == ["sudo", "systemctl", "start"]]
    check("S7 timer already active: nr-resume verifies the latest run and starts nothing", code == 0 and not starts
          and "Guard timer already active" in out and "PASS latest guard run printed continuous-guard PASS and no FAIL" in out
          and "PASS latest guard run: systemd Result=success, ExecMainStatus=0" in out, out[-700:])
    check("S7 nr-resume recorded ok with the guard run's evidence", state(h)["steps"]["nr-resume"]["status"] == "ok"
          and state(h).get("nrResumedAt"), str(state(h).get("nrResumedAt")))
    check("S7 T1 never reads the guard journal without sudo", not any(c[0] == "journalctl" for c, _ in h.calls))
    h.add_guard_run(False, 5)
    code, out = t1(h, "nr-resume")
    check("S7 latest run printed FAIL -> nr-resume FAIL", code == 1 and "0 PASS, 1 FAIL" in out, out[-500:])
    h.guard_runs = [(f"{1:032x}", time.time() - 600, True)]
    code, out = t1(h, "nr-resume")
    check("S7 latest run older than 180 s -> FAIL", code == 1 and "FAIL latest guard run within the last 180 s" in out, out[-400:])
    h.guard_runs = [(f"{2:032x}", time.time() - 30, True)]
    h.guard_result, h.guard_status = "exit-code", "1"
    code, out = t1(h, "nr-resume")
    check("S7 systemd result not success -> FAIL", code == 1 and "FAIL latest guard run: systemd Result=success" in out, out[-400:])
    h.guard_result, h.guard_status = "success", "0"
    h.guard_runs = [(f"{3:032x}", time.time() - 300, False), (f"{4:032x}", time.time() - 20, True)]
    code, out = t1(h, "nr-resume")
    check("S7 a FAIL since the timer started -> FAIL", code == 1 and "FAIL no continuous-guard FAIL since the timer started" in out,
          out[-400:])
    h2 = fresh(); receipt(h2, 10)
    t1(h2, "plan")
    for st in ("D2a", "D2b", "D2c"):
        t1(h2, "step", st)
    t1(h2, "nr-pause")
    for st in ("D3", "D4", "D5", "D6a", "D6b"):
        t1(h2, "step", st)
    h2.guard_fail = True
    code, out = t1(h2, "nr-resume")
    check("S7 start path with a failing guard run -> FAIL", code == 1 and "0 PASS, 1 FAIL" in out, out[-400:])

    # S8 (2026-10-12 regression): T1 v3 mounted the scanner cache under /root, which the scanner cannot reach as civo
    # ('mkdir /root/.cache: permission denied'); a scan without a report was not told apart from findings; no DB record
    h = fresh()
    m = module(h)
    base = m.trivy_base("/c")
    check("S8 trivy_base: cache at /trivy-cache (= --cache-dir), as civo's uid, nothing under /root",
          "/c:/trivy-cache" in base and base[base.index("--cache-dir") + 1] == "/trivy-cache"
          and base[base.index("--user") + 1] == f"{os.getuid()}:{os.getgid()}" and not any("/root" in x for x in base), str(base))
    cache = os.path.join(h.out, "trivy-cache")
    os.makedirs(cache, exist_ok=True)
    h.trivy_error.add("user-service")
    crit, lib, targets, note = m.trivy_scan("parkio/user-service:sha-x", cache)
    check("S8 a scan without a report fails with the scanner's error, not as a finding",
          (crit, lib) == (False, False) and "critical scan wrote no report (exit 1)" in note
          and "library scan wrote no report (exit 1)" in note and "DB error" in note, note)
    h.trivy_error.clear()
    h.trivy_fail["user-service"] = "library"
    crit, lib, targets, note = m.trivy_scan("parkio/user-service:sha-x", cache)
    check("S8 a library finding fails the library gate and names the finding", (crit, lib) == (True, False)
          and "findings: CVE-2026-0001 org.example:lib 1.0" in note, f"{crit} {lib} {note}")
    meta = m.trivy_db_meta(cache)
    check("S8 trivy_db_meta reads both databases with their age", meta["vulnerabilityDb"]["ageHours"] == 3.0
          and meta["javaDb"]["ageHours"] == 20.0, str(meta))
    for label, ages, needle in (("a stale vulnerability DB", {"vulnerabilityDb": 60, "javaDb": 20},
                                 "FAIL scan databases: vulnerability DB present and updated within 48 h: updated"),
                                ("a missing Java DB", {"vulnerabilityDb": 3, "javaDb": None},
                                 "FAIL scan databases: Java DB present and updated within 168 h: missing"),
                                ("a scanner error", None, "critical scan wrote no report")):
        h = fresh(); receipt(h, 10)
        t1(h, "plan")
        for s in ("D2a", "D2b", "D2c"):
            t1(h, "step", s)
        t1(h, "nr-pause")
        for s in ("D3", "D4", "D5", "D6a", "D6b"):
            t1(h, "step", s)
        t1(h, "nr-resume")
        before = {s: h.containers[s]["id"] for s in BUILT}
        if ages is None:
            h.trivy_error.add("analytics-service")
        else:
            h.db_age = ages
        code, out = t1(h, "step", "D7")
        check(f"S8 D7 with {label} -> FAIL before any start, latest tags pointed back", code == 1 and needle in out
              and "D7.4 Run:" not in out and all(h.containers[s]["id"] == before[s] for s in BUILT)
              and all(h.images[f"parkio-{s}:latest"] == h.images[f"parkio-{s}:rollback-pre-22f96990"] for s in BUILT),
              out[-900:])

    # S6 (2026-10-12 regression): the live helper check prints its lines on stderr; D3 counted 0/0 from stdout only
    h = fresh()
    for stream in ("stderr", "stdout"):
        h.nr_stream, h.attached = stream, 3
        rc, attached, total = module(h).nr_attached()
        check(f"S6 nr_attached counts the check's {stream} lines: 3/3, exit 0", (rc, attached, total) == (0, 3, 3),
              f"{rc} {attached}/{total}")
    h.nr_stream, h.attached = "stderr", 2
    rc, attached, total = module(h).nr_attached()
    check("S6 nr_attached with one source detached (stderr): 2/3, exit 1", (rc, attached, total) == (1, 2, 3),
          f"{rc} {attached}/{total}")

    for root in ROOTS:
        shutil.rmtree(root, ignore_errors=True)
    print(f"{len(FAILS)} failed" if FAILS else "all checks passed")
    print("SELFTEST PASS" if not FAILS else "SELFTEST FAIL")
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
