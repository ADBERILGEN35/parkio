#!/usr/bin/env bash
# Local self-test of u12-replay-evidence.py. A fake `docker` answers the three read-only Kafka CLIs from a JSON fixture
# (end and start offsets per topic, the parkio.user group describe, topic configs with and without --all support); a fake
# T1 state.json holds the release's U12 record (T_g 2026-10-12T12:49:14Z).
# R1 DLT empty and unchanged since cutoff C, group at the T_g ends, 21000 pre-T_g score records with retention 30 d and
#    segment 7 d -> dlt PASS, group PASS, residual RECORDED "none can remain after 2026-11-18T12:54Z", record 700/600;
# R2 two DLT records written since C -> dlt INSPECT; R3 the group behind on partition 2 -> group FINDING;
# R4 the topic's log start past every T_g end -> residual PASS; R5 kafka-configs without --all -> retention read from the
#    dynamic configs, no end date without segment.ms; R6 no U12 record in the state -> STOP, no record; R7 Kafka unreachable
#    -> STOP; R8 the source runs no consumer and changes no offset or config.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/u12-replay-evidence.py"
W="$(mktemp -d)"; trap 'rm -rf "${W:?}"' EXIT
mkdir -p "$W/bin" "$W/out"
cat > "$W/bin/docker" <<'PY'
#!/usr/bin/env python3
import json, os, sys
a = sys.argv[1:]
f = json.load(open(os.environ["FAKE_KAFKA"]))
if os.environ.get("FAKE_DOWN") or a[:1] != ["exec"]:
    sys.stderr.write("Error: No such container: parkio-kafka\n"); sys.exit(1)
cmd = a[a.index("60") + 1:]
def opt(n):
    return cmd[cmd.index(n) + 1] if n in cmd else None
if cmd[0] == "kafka-get-offsets":
    t, w = opt("--topic"), opt("--time")
    for p, o in sorted(f["offsets"][t][w].items(), key=lambda x: int(x[0])):
        print(f"{t}:{p}:{o}")
    sys.exit(0)
if cmd[0] == "kafka-consumer-groups":
    print("\nGROUP           TOPIC                     PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID")
    for p, o in sorted(f["committed"].items(), key=lambda x: int(x[0])):
        print(f"parkio.user     parkio.gamification.score {p}          {o}            {o}             0    c-1          /10.0.0.5  c")
    print("parkio.user     parkio.user.profile       0          5               5               0    c-1          /10.0.0.5  c")
    sys.exit(0)
if cmd[0] == "kafka-configs":
    if "--all" in cmd:
        if f.get("noAll"):
            sys.stderr.write("--all is not a recognized option\n"); sys.exit(1)
        print(f"All configs for topic {opt('--entity-name')} are:")
        print("  compression.type=producer sensitive=false synonyms={DEFAULT_CONFIG:compression.type=producer}")
        print(f"  retention.ms={f['retention']} sensitive=false synonyms={{DYNAMIC_TOPIC_CONFIG:retention.ms={f['retention']}}}")
        print(f"  segment.ms={f['segment']} sensitive=false synonyms={{DEFAULT_CONFIG:log.roll.hours=168}}")
    else:
        print(f"Dynamic configs for topic {opt('--entity-name')} are:")
        print(f"  retention.ms={f['retention']} sensitive=false synonyms={{DYNAMIC_TOPIC_CONFIG:retention.ms={f['retention']}}}")
    sys.exit(0)
sys.stderr.write(f"fake docker: unhandled {cmd}\n"); sys.exit(2)
PY
chmod +x "$W/bin/docker"
fixture() {  # dlt-end dlt-start committed-p2 score-start-shift noAll
  python3 -I - "$W/kafka.json" "$@" <<'PY'
import json, sys
path, dlt_end, dlt_start, com2, shift, no_all = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]), sys.argv[6] == "1"
at_tg = {p: 1000 * (p + 1) for p in range(6)}
json.dump({"offsets": {
    "parkio.dlt.user": {"-1": {p: dlt_end if p == 0 else 0 for p in range(3)}, "-2": {p: dlt_start if p == 0 else 0 for p in range(3)}},
    "parkio.gamification.score": {"-1": {p: at_tg[p] + 50 for p in range(6)}, "-2": {p: shift if shift >= 0 else 0 for p in range(6)}}},
  "committed": {p: (com2 if p == 2 else at_tg[p] + 50) for p in range(6)},
  "retention": 2592000000, "segment": 604800000, "noAll": no_all}, open(path, "w"))
PY
}
python3 -I - "$W/state.json" <<'PY'
import json, sys
json.dump({"u12": {"Tg": "2026-10-12T12:49:14.312345678Z", "scoreEndAtTg": {str(p): 1000 * (p + 1) for p in range(6)},
                   "dltUserEndBefore": {"0": 0, "1": 0, "2": 0}, "scoreEndBefore": {}}}, open(sys.argv[1], "w"))
PY
export U12E_TEST=1 U12E_DOCKER="$W/bin/docker" U12E_OUT="$W/out" U12E_STATE="$W/state.json" FAKE_KAFKA="$W/kafka.json"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
has() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
run() { python3 -I "$TOOL" > "$W/$1" 2>&1; echo "tool exit=$?" >> "$W/$1"; }

fixture 0 0 3050 0 0; run r1.out; sed -e "s#$W#<W>#g" "$W/r1.out"
has r1.out "tool exit=0"; has r1.out "T_g 2026-10-12T12:49:14Z; 6 score partitions; 3 DLT partitions at cutoff C"
has r1.out "dlt       PASS     parkio.dlt.user retains 0 records and its end offsets equal cutoff C"
has r1.out "group     PASS     parkio.user committed offsets on parkio.gamification.score are at or past the T_g ends"
has r1.out "residual  RECORDED parkio.gamification.score still retains 21000 record(s) from before T_g"
has r1.out "none can remain after 2026-11-18T12:54Z"
has r1.out "U12 REPLAY EVIDENCE RECORDED - 2 PASS, 1 RECORDED"
E="$(ls -1d "$W"/out/u12/* | tail -1)"
[ "$(stat -c %a "$W/out/u12")" = 700 ] && [ "$(stat -c %a "$E")" = 700 ] && [ "$(stat -c %a "$E/replay-evidence.json")" = 600 ] \
  && ok "r1: record 700/600" || no "r1 record modes"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1])); c=r["checks"]; assert c["dlt"]["retained"]==0 and c["residual"]["preTgRetained"]==21000 and c["residual"]["noneAfter"]=="2026-11-18T12:54Z" and r["Tg"]=="2026-10-12T12:49:14Z"' \
  "$E/replay-evidence.json" && ok "r1: record fields" || no "r1 record fields"
sleep 1; fixture 2 0 3050 0 0; run r2.out
has r2.out "dlt       INSPECT  parkio.dlt.user retains 2 record(s); 2 written since cutoff C"; has r2.out "tool exit=0"
sleep 1; fixture 0 0 2999 0 0; run r3.out
has r3.out "group     FINDING  parkio.user committed offsets on parkio.gamification.score are behind the T_g ends on partitions [2]"
sleep 1; fixture 0 0 3050 7000 0; run r4.out
has r4.out "residual  PASS     parkio.gamification.score retains no record from before T_g: none can be replayed by any path"
has r4.out "U12 REPLAY EVIDENCE RECORDED - 3 PASS"
sleep 1; fixture 0 0 3050 0 1; run r5.out
has r5.out "retention.ms or segment.ms unreadable, so no end date"; has r5.out "tool exit=0"
python3 -I -c 'import json,sys; r=json.load(open(sys.argv[1]))["checks"]["residual"]; assert r["retentionMs"]==2592000000 and r["segmentMs"] is None' \
  "$(ls -1d "$W"/out/u12/* | tail -1)/replay-evidence.json" && ok "r5: retention read from the dynamic configs" || no "r5 fallback"
n="$(find "$W/out" -name replay-evidence.json | wc -l)"
python3 -I -c 'import json,sys; json.dump({"u12": {}}, open(sys.argv[1], "w"))' "$W/state-empty.json"
U12E_STATE="$W/state-empty.json" run r6.out
has r6.out "STOP: the release state has no complete U12 record"; has r6.out "tool exit=1"
fixture 0 0 3050 0 0; FAKE_DOWN=1 run r7.out
has r7.out "STOP: cannot read the offsets of parkio.dlt.user"; has r7.out "tool exit=1"
[ "$(find "$W/out" -name replay-evidence.json | wc -l)" = "$n" ] && ok "r6-r7: no record" || no "r6-r7 wrote a record"
if grep -n -E 'kafka-console-consumer|reset-offsets|--execute|--alter|--delete|kafka-topics|--add-config' "$TOOL" >/dev/null; then
  no "r8: the tool names a consuming or changing Kafka command"; else ok "r8: only kafka-get-offsets, consumer-groups --describe, configs --describe"; fi
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
