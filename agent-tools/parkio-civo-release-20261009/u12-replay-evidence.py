#!/usr/bin/env python3
"""U12 criterion 5 evidence, read-only (Asana 1219001760069487): "DLT replay of old events does not regress".

Run on parkio-civo-prod as civo, with sudo:
  sudo python3 -I /home/civo/parkio-release-20261009/tools/u12-replay-evidence.py

Inputs: the release's U12 record in ~/parkio-release-20261009/state.json, key "u12", written by T1 at D7. It holds:
- Tg: the start of the U12 gamification container;
- scoreEndAtTg: parkio.gamification.score end offsets at T_g;
- dltUserEndBefore: parkio.dlt.user end offsets before any service started, i.e. cutoff C.

Kafka calls run in the broker container with a small CLI heap. All are read-only: kafka-get-offsets, kafka-consumer-groups
--describe (no group join) and kafka-configs --describe. No consumer runs, no offset changes and no payload is read.

What it records:
  1 dlt       parkio.dlt.user: the records retained now, and the end offsets against cutoff C. With 0 retained and the ends
              unchanged, the DLT holds no record at all, so a DLT replay cannot replay a pre-U12 (version-less) event. Every
              record that reaches the DLT after T_g comes from the U12 producer and carries aggregateVersion, whose replay does not
              regress. That was proven by #217's PostgreSQL+Kafka ITs; user-service and gamification-service sources are
              identical from #217's merge 2fb51859 to the PIN f888a81b.
  2 group     parkio.user's committed offsets on parkio.gamification.score against the end offsets at T_g: not behind.
  3 residual  pre-T_g records still retained in parkio.gamification.score (per partition, the end at T_g minus the log start
              now), and the topic's retention.ms and segment.ms. Such records reach user-service only through an offset
              reset (a procedural control), never through a DLT replay. The tool prints the date after which none can remain.

Evidence: ~civo/parkio-release-20261009/u12/<run stamp>/replay-evidence.json (dir 700, file 600).
Exit 0 when the record was written (INSPECT and FINDING are results, shown in the table); 1 when it was not.
"""
import datetime as dt, hashlib, json, os, re, subprocess, sys, time

OUT = os.environ.get("U12E_OUT", "/home/civo/parkio-release-20261009")
STATE = os.environ.get("U12E_STATE", os.path.join(OUT, "state.json"))
OWNER = os.environ.get("U12E_OWNER", "civo")
DOCKER = os.environ.get("U12E_DOCKER", "docker")
KAFKA = os.environ.get("U12E_KAFKA", "parkio-kafka")
TEST = bool(os.environ.get("U12E_TEST"))
GROUP, TOPIC, DLT = "parkio.user", "parkio.gamification.score", "parkio.dlt.user"
CHECK_INTERVAL_MS = 300000   # Kafka's default log.retention.check.interval.ms
RUNSTAMP = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())


def stop(msg):
    print(f"STOP: {msg}")
    print("U12 REPLAY EVIDENCE NOT RECORDED - nothing was changed")
    sys.exit(1)


def kafka(*args):
    try:
        p = subprocess.run([DOCKER, "exec", "-e", "KAFKA_HEAP_OPTS=-Xms32m -Xmx128m", KAFKA, "timeout", "60", *args],
                           capture_output=True, text=True, timeout=90)
        return p.returncode, p.stdout, p.stderr
    except (OSError, subprocess.TimeoutExpired) as e:
        return 127, "", str(e)


def offsets(topic, when):
    rc, out, _ = kafka("kafka-get-offsets", "--bootstrap-server", "localhost:9092", "--topic", topic, "--time", when)
    if rc != 0:
        return None
    result = {}
    for line in out.splitlines():
        m = re.match(r"^(\S+):(\d+):(-?\d+)$", line.strip())
        if m and m.group(1) == topic:
            result[int(m.group(2))] = int(m.group(3))
    return result or None


def committed(group, topic):
    rc, out, _ = kafka("kafka-consumer-groups", "--bootstrap-server", "localhost:9092", "--describe", "--group", group)
    if rc != 0 and not out.strip():
        return None
    result, header = {}, None
    for line in out.splitlines():
        cols = line.split()
        if "GROUP" in cols and "PARTITION" in cols:
            header = cols
            continue
        if header and len(cols) >= len(header) - 1 and len(cols) >= 4:
            row = dict(zip(header, cols))
            if row.get("TOPIC") == topic and row.get("CURRENT-OFFSET", "-").isdigit():
                result[int(row["PARTITION"])] = int(row["CURRENT-OFFSET"])
    return result


def topic_config(topic):
    base = ["kafka-configs", "--bootstrap-server", "localhost:9092", "--describe", "--entity-type", "topics",
            "--entity-name", topic]
    rc, out, _ = kafka(*base, "--all")
    if rc != 0:
        rc, out, _ = kafka(*base)
    found = {}
    for line in out.splitlines():
        for key in ("retention.ms", "segment.ms"):
            m = re.match(rf"^\s*{re.escape(key)}=(-?\d+)\b", line)
            if m and key not in found:
                found[key] = int(m.group(1))
    return found


def parse_time(s):
    s = (s or "").strip().replace("Z", "+00:00")
    s = re.sub(r"(\.\d{6})\d+", r"\1", s)   # Docker's StartedAt has nanoseconds; datetime takes microseconds
    try:
        t = dt.datetime.fromisoformat(s)
    except ValueError:
        return None
    return t if t.tzinfo else t.replace(tzinfo=dt.timezone.utc)


def main():
    print(f"U12 replay evidence (criterion 5) | {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} | read-only")
    if not TEST and os.geteuid() != 0:
        stop("run as root (sudo python3 -I ...)")
    try:
        u12 = json.load(open(STATE, encoding="utf-8")).get("u12") or {}
    except (OSError, ValueError):
        stop(f"cannot read the release state {STATE}")
    tg = parse_time(u12.get("Tg"))
    at_tg = {int(k): int(v) for k, v in (u12.get("scoreEndAtTg") or {}).items()}
    cut_c = {int(k): int(v) for k, v in (u12.get("dltUserEndBefore") or {}).items()}
    if not tg or not at_tg or not cut_c:
        stop("the release state has no complete U12 record (Tg, scoreEndAtTg, dltUserEndBefore)")
    print(f"== 0. release U12 record: T_g {tg.strftime('%Y-%m-%dT%H:%M:%SZ')}; {len(at_tg)} score partitions; "
          f"{len(cut_c)} DLT partitions at cutoff C")
    rows, rec = [], {"tool": "u12-replay-evidence", "runStamp": RUNSTAMP, "criterion": "DLT replay of old events does not regress",
                     "Tg": tg.strftime("%Y-%m-%dT%H:%M:%SZ"), "checks": {}}

    def put(key, verdict, detail, **data):
        rec["checks"][key] = dict(verdict=verdict, detail=detail, **data)
        rows.append((key, verdict, detail))

    # 1 the DLT
    d_end, d_start = offsets(DLT, "-1"), offsets(DLT, "-2")
    if d_end is None or d_start is None:
        stop(f"cannot read the offsets of {DLT} (is {KAFKA} running?)")
    retained = sum(max(d_end[p] - d_start.get(p, 0), 0) for p in d_end)
    since_c = sum(max(d_end[p] - cut_c.get(p, 0), 0) for p in d_end)
    same = d_end == cut_c
    if retained == 0 and same:
        put("dlt", "PASS", f"{DLT} retains 0 records and its end offsets equal cutoff C: no pre-U12 event can be replayed from it",
            retained=retained, sinceCutoff=since_c, endNow=d_end, cutoffC=cut_c)
    else:
        put("dlt", "INSPECT", f"{DLT} retains {retained} record(s); {since_c} written since cutoff C. Their aggregateVersion "
            "presence is not read here (no payload read); inspect them with a reviewed tool before any redrive",
            retained=retained, sinceCutoff=since_c, endNow=d_end, cutoffC=cut_c)

    # 2 the group
    s_start, s_end = offsets(TOPIC, "-2"), offsets(TOPIC, "-1")
    com = committed(GROUP, TOPIC)
    if s_start is None or s_end is None or com is None:
        stop(f"cannot read {TOPIC} offsets or the {GROUP} group")
    behind = sorted(p for p, end in at_tg.items() if end > s_start.get(p, 0) and com.get(p, -1) < end)
    put("group", "FINDING" if behind else "PASS",
        f"{GROUP} committed offsets on {TOPIC} are " + (f"behind the T_g ends on partitions {behind}" if behind else
                                                        "at or past the T_g ends on every partition with pre-T_g records"),
        behindPartitions=behind, committed=com, endAtTg=at_tg)

    # 3 the residual in the topic
    cfg = topic_config(TOPIC)
    pre = {p: max(at_tg[p] - s_start.get(p, 0), 0) for p in at_tg}
    remaining = sum(pre.values())
    ret, seg = cfg.get("retention.ms"), cfg.get("segment.ms")
    if remaining == 0:
        put("residual", "PASS", f"{TOPIC} retains no record from before T_g: none can be replayed by any path",
            preTgRetained=0, perPartition=pre, retentionMs=ret, segmentMs=seg, logStartNow=s_start)
    else:
        if ret is not None and ret >= 0 and seg is not None:
            bound = tg + dt.timedelta(milliseconds=seg + ret + CHECK_INTERVAL_MS)
            when = bound.strftime("%Y-%m-%dT%H:%MZ")
            tail = f"; none can remain after {when} (T_g + segment.ms + retention.ms + the check interval)"
        else:
            when, tail = None, "; retention.ms or segment.ms unreadable, so no end date"
        put("residual", "RECORDED",
            f"{TOPIC} still retains {remaining} record(s) from before T_g; they reach user-service only through an offset reset "
            f"of {GROUP} (a procedural control), not through a DLT replay" + tail,
            preTgRetained=remaining, perPartition=pre, retentionMs=ret, segmentMs=seg, noneAfter=when, logStartNow=s_start)

    rec["codeEvidence"] = {"its": "#217 GamificationProjectionOrderingPostgresIT and the user/gamification ITs (PostgreSQL 16 + Kafka)",
                           "sourceIdentical": "services/user-service/src/main and services/gamification-service/src/main are "
                                              "identical at 2fb51859 (#217 merge) and f888a81b (the deployed PIN)"}
    print("== 1-3. results")
    for key, verdict, detail in rows:
        print(f"  {key:9} {verdict:8} {detail}")
    evd = os.path.join(OUT, "u12", RUNSTAMP)
    os.makedirs(evd, exist_ok=True)
    path = os.path.join(evd, "replay-evidence.json")
    with open(path, "w") as f:
        json.dump(rec, f, indent=2, sort_keys=True)
    if not TEST:
        subprocess.run(["chown", "-R", f"{OWNER}:{OWNER}", os.path.join(OUT, "u12")], check=False)
    os.chmod(os.path.join(OUT, "u12"), 0o700)
    os.chmod(evd, 0o700)
    os.chmod(path, 0o600)
    print(f"evidence: {path} sha256 {hashlib.sha256(open(path, 'rb').read()).hexdigest()}")
    counts = {v: sum(1 for _, x, _ in rows if x == v) for v in ("PASS", "INSPECT", "FINDING", "RECORDED")}
    print("U12 REPLAY EVIDENCE RECORDED - " + ", ".join(f"{n} {v}" for v, n in counts.items() if n))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:   # no traceback: it could quote a value
        stop(f"unexpected {type(e).__name__}")
