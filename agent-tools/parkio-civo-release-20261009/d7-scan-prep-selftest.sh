#!/usr/bin/env bash
# Self-test of d7-scan-prep.py and T1 v4's scanner path against real Docker, the real pinned Trivy 0.64.1, its real databases
# and real locally built Parkio Spring Boot images (needs network). The release is a shared clone at the PIN (sparse:
# .trivyignore.yaml only); the six test rollback tags <p>-<service>:rollback-pre-22f96990 name local candidate images.
# R1 T1 v3's trivy_scan as this uid: no report, 'permission denied' (the 2026-10-12 defect);
# P1 scanner present: both databases downloaded with T1 v4's command, D7's freshness gates, the six rollback tags, an
#    end-to-end scan of the notification rollback image by tag; exit 0; T1 state/journal untouched; report 600; cache 700;
# P2 scanner absent: pulled by its digest (the linux/amd64 manifest digest of the same Trivy 0.64.1, removed afterwards);
# P3 wrong T1 checksum -> STOP before anything; P4 rollback tags missing -> exit 1 before any scan.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; TOOL="$HERE/d7-scan-prep.py"; T1V4="$HERE/t1-release-step.py"
REPO="${D7ST_REPO:-$(git -C "$HERE" rev-parse --show-toplevel)}"
PIN=f888a81b113c0c486ea0949067779443857e644d
IMG="${D7ST_IMAGE:-parkio/auth-service:candidate-843ae7cb461b}"
AMD64=aquasec/trivy@sha256:de90a656e79b175a294abe85cb8b99670fab83ebf339cccd163e6f584846809a
P="d7st$$"; W="$(mktemp -d)"
SIX="user-service gamification-service notification-service moderation-service ai-validation-service analytics-service"
cleanup() {
  for s in $SIX; do docker image rm "$P-$s:rollback-pre-22f96990" >/dev/null 2>&1 || true; done
  docker image rm "$AMD64" >/dev/null 2>&1 || true
  rm -rf "${W:?}"
}
trap cleanup EXIT
docker image inspect "$IMG" >/dev/null 2>&1 || { echo "SELFTEST SETUP: $IMG not present"; exit 2; }
if docker image inspect "$AMD64" >/dev/null 2>&1; then echo "SELFTEST SETUP: $AMD64 already present (P2 needs it absent)"; exit 2; fi
git clone --quiet --shared --no-checkout "$REPO" "$W/rel"
git -C "$W/rel" sparse-checkout set --no-cone '/.trivyignore.yaml'
git -C "$W/rel" -c advice.detachedHead=false checkout --quiet --detach "$PIN"
for s in $SIX; do docker tag "parkio/$s:candidate-843ae7cb461b" "$P-$s:rollback-pre-22f96990"; done
mkdir -p "$W/out"
export D7S_T1="$T1V4" D7S_T1_SHA256="$(sha256sum "$T1V4" | cut -d' ' -f1)" T1_OUT="$W/out" T1_RELEASE="$W/rel" T1_PROJECT="$P"
fail=0
ok() { echo "ok   $*"; }
no() { echo "FAIL $*"; fail=1; }
check() { if grep -qF -- "$2" "$W/$1"; then ok "$1: $2"; else no "$1: $2"; fi; }
rc_in() { local rc; rc="$(cat "$W/$1.rc")"; case " $2 " in *" $rc "*) ok "$1 exit $rc";; *) no "$1 exit $rc, want one of $2";; esac; }
runit() { local name="$1"; shift; env "$@" python3 -I "$TOOL" > "$W/$name" 2>&1 && echo 0 > "$W/$name.rc" || echo $? > "$W/$name.rc"; }
show() { sed -e "s#$W#<W>#g" -e "s#$P#<p>#g" "$W/$1"; }

# R1: T1 v3 (55a5ae2c) as shipped - the scanner cannot reach a cache under /root as this uid
git -C "$REPO" show 55a5ae2c52cfbdc8541657bd3d387102bfe6ad88:agent-tools/parkio-civo-release-20261009/t1-release-step.py > "$W/t1v3.py"
python3 -I - "$W/t1v3.py" "$IMG" "$W/out/v3-cache" > "$W/r1.out" 2>&1 <<'PY' || true
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("t1v3", sys.argv[1]); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
errs = []
real = m.run
def run(cmd, **kw):
    rc, out, err = real(cmd, **kw)
    errs.append(err or "")
    return rc, out, err
m.run = run
os.makedirs(sys.argv[3], mode=0o700, exist_ok=True)
crit, lib, targets, note = m.trivy_scan(sys.argv[2], sys.argv[3])
print(f"v3 critical_ok={crit} library_ok={lib} targets={len(targets)} note={note!r}")
print("v3 stderr has 'mkdir /root/.cache: permission denied':", any("mkdir /root/.cache: permission denied" in e for e in errs))
PY
cat "$W/r1.out"
check r1.out "v3 critical_ok=False library_ok=False targets=0 note=''"
check r1.out "v3 stderr has 'mkdir /root/.cache: permission denied': True"

# P1: scanner present - real database downloads, freshness gates, rollback tags, end-to-end scan by tag
runit p1.out; show p1.out; rc_in p1.out "0"
check p1.out "PASS release checkout clean at the PIN"
check p1.out "PASS scanner present locally by its pinned digest"
check p1.out "PASS scanner runs (trivy --version, throwaway container, no network): Version: 0.64.1"
check p1.out "PASS release ignore file present"
check p1.out "suppressed CVE-2026-47884: expired_at 2026-11-05"
check p1.out "PASS vulnerability DB downloaded as civo with T1's scanner command"
check p1.out "PASS Java DB downloaded as civo with T1's scanner command"
check p1.out "PASS D7's freshness gate: vulnerability DB present and updated within 48 h: updated"
check p1.out "PASS D7's freshness gate: Java DB present and updated within 168 h: updated"
check p1.out "PASS the six rollback tags $P-<service>:rollback-pre-22f96990 present: user-service"
check p1.out "Run: T1 trivy_scan $P-notification-service:rollback-pre-22f96990"
check p1.out "PASS both scans wrote a report as civo (docker socket, ignore file, JSON)"
check p1.out "the notification-service image running now (D7 replaces it), with today's databases: critical"
check p1.out "PASS free space on / >= 12 GiB after the downloads"
check p1.out "D7 SCAN PREP COMPLETE - scanner, current databases and D7's scan and abort paths ready"
[ ! -e "$W/out/state.json" ] && [ ! -e "$W/out/journal.tsv" ] && ok "p1: T1 state and journal untouched" || no "p1 touched T1 state"
R="$(ls "$W"/out/scanprep-*.txt | head -1)"
[ "$(stat -c %a "$R")" = 600 ] && [ "$(stat -c %a "${R%.txt}.json")" = 600 ] && ok "p1: report .txt/.json mode 600" || no "p1 report mode"
[ "$(stat -c %a "$W/out/trivy-cache")" = 700 ] && [ -s "$W/out/trivy-cache/db/trivy.db" ] && [ -s "$W/out/trivy-cache/java-db/trivy-java.db" ] \
  && ok "p1: cache mode 700 holds both databases" || no "p1 cache"
python3 -I - "${R%.txt}.json" <<'PY' && ok "p1: JSON record has both DB metadata, six rollback tags, the baseline, scannerPulledByPrep=false" || no "p1 json"
import json, sys
r = json.load(open(sys.argv[1]))
assert r["trivyDb"]["vulnerabilityDb"]["UpdatedAt"] and r["trivyDb"]["javaDb"]["UpdatedAt"], r["trivyDb"]
assert len(r["rollbackTags"]) == 6 and all(r["rollbackTags"].values()), r["rollbackTags"]
assert r["baseline"]["ref"].endswith("notification-service:rollback-pre-22f96990") and r["scannerPulledByPrep"] is False and r["exit"] == 0, r
PY
[ "$(docker ps -a -q --filter "ancestor=$(docker image inspect --format '{{.Id}}' aquasec/trivy@sha256:a8ca29078522f30393bdb34225e4c0994d38f37083be81a42da3a2a7e1488e9e)" | wc -l)" = 0 ] \
  && ok "p1: no scanner container left (--rm)" || no "p1 scanner containers"

# P2: scanner absent - pulled by digest (same Trivy 0.64.1, linux/amd64 manifest), databases reused
runit p2.out T1_TRIVY="$AMD64"; rc_in p2.out "0"
check p2.out "PASS scanner pulled by its pinned digest"
check p2.out "PASS scanner runs (trivy --version, throwaway container, no network): Version: 0.64.1"
check p2.out "PASS both scans wrote a report as civo"
check p2.out "note: this step pulled the scanner; D7 keeps it. After E: docker image rm $AMD64"
docker image inspect "$AMD64" >/dev/null 2>&1 && ok "p2: the pulled scanner is present" || no "p2 pull"

# P3: wrong T1 checksum -> STOP before anything; P4: rollback tags missing -> exit 1 before any scan
before="$(ls "$W/out" | wc -l)"
runit p3.out D7S_T1_SHA256="$(printf '0%.0s' $(seq 64))"; rc_in p3.out "1"; check p3.out "STOP: the installed T1"
[ "$(ls "$W/out" | wc -l)" = "$before" ] && ok "p3: nothing written" || no "p3 wrote something"
runit p4.out T1_PROJECT="$P-none"; rc_in p4.out "1"
check p4.out "FAIL the six rollback tags $P-none-<service>:rollback-pre-22f96990 present: ; missing: user-service, gamification-service, notification-service, moderation-service, ai-validation-service, analytics-service"
grep -q "trivy_scan" "$W/p4.out" && no "p4 scanned although tags were missing" || ok "p4: no scan without the rollback tags"
check p4.out "D7 SCAN PREP NOT COMPLETE"
[ "$fail" -eq 0 ] && echo "SELFTEST PASS" || echo "SELFTEST FAIL"
exit "$fail"
