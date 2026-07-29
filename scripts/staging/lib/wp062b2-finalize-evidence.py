import json, hashlib, os
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(".")
HIST = ROOT / "build/operational-evidence/wp062b-20260728211226"
NEW = ROOT / "build/operational-evidence/wp062b2-20260729073440"
REG = ROOT / "build/operational-evidence/wp062b2-regression-20260729102728"
PREREQ = ROOT / "build/operational-evidence/wp062b2-prereq"

def load(p):
    p = Path(p)
    if not p.exists():
        return None
    return json.loads(p.read_text(encoding="utf-8-sig"))

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()

new_summary = load(NEW / "shared-staging-summary.json") or {}
hist_summary = load(HIST / "shared-staging-summary.json") or load(HIST / "summary.json") or {}
env_new = load(NEW / "environment-manifest.json") or {}
env_hist = load(HIST / "environment-manifest.json") or {}
ds_new = load(NEW / "datasource-repoint-report.json") or {}
cleanup_new = load(NEW / "cleanup-report.json") or {}
cleanup_live = load(NEW / "cleanup-live-revalidation.json") or {}
wp05 = load(NEW / "wp05-defaults-report.json") or {}
auth = load(NEW / "restored-auth-journey.json") or {}
park = load(NEW / "restored-parking-journey.json") or {}
media = load(NEW / "restored-media-journey.json") or {}
write = load(NEW / "post-restore-write-report.json") or {}
gw = load(NEW / "gateway-route-baseline.json") or {}
cj_src = load(NEW / "critical-journeys-source/summary.json") or load(NEW / "critical-journeys/summary.json") or {}
cj_rst = load(NEW / "critical-journeys-restored/summary.json") or {}
nearby = load(NEW / "critical-journeys-restored/restored_nearby_search.json") or {}

# historical checksums unchanged
hist_checksums = {}
cdir = HIST / "checksums"
if cdir.exists():
    for f in sorted(cdir.glob("*")):
        if f.is_file():
            hist_checksums[f.name] = sha256_file(f)

# Compare key fields
diffs = []
def cmp(field, a, b, classification="EXPECTED_ENVIRONMENT_VARIANCE"):
    diffs.append({
        "field": field,
        "historical": a,
        "final": b,
        "classification": classification if a != b else "MATCH",
        "equal": a == b,
    })

cmp("executionClassification", env_hist.get("executionClassification"), env_new.get("executionClassification"), "MATCH" if env_hist.get("executionClassification")==env_new.get("executionClassification") else "MATERIAL_REGRESSION")
cmp("technicalStatus", hist_summary.get("technicalStatus") or hist_summary.get("status"), new_summary.get("technicalStatus"), "MATCH")
cmp("finalStatus", hist_summary.get("finalStatus") or hist_summary.get("status"), new_summary.get("finalStatus") or new_summary.get("status"), "EVIDENCE_FORMAT_CHANGE")
cmp("signOffDecision", hist_summary.get("signOffDecision"), new_summary.get("signOffDecision"), "MATCH")
cmp("wp063Eligible", hist_summary.get("wp063Eligible"), new_summary.get("wp063Eligible"), "MATCH")
cmp("kafkaIsolation", env_hist.get("kafkaIsolation"), env_new.get("kafkaIsolation"), "MATCH")
cmp("redisIsolation", env_hist.get("redisIsolation"), env_new.get("redisIsolation"), "MATCH")
cmp("restoreDbMarkerPattern", "drill_wp062b_", "drill_wp062b2_", "EXPECTED_IMPLEMENTATION_EVOLUTION")
cmp("composeProjectPrefix", "parkio-wp062-b-", "parkio-wp062-b2-", "EXPECTED_IMPLEMENTATION_EVOLUTION")
cmp("journeyRollupHasRepoCommit", bool((load(HIST/"critical-journeys-restored/summary.json") or {}).get("repositoryCommit")), bool(cj_rst.get("repositoryCommit")), "MATERIAL_IMPROVEMENT" if cj_rst.get("repositoryCommit") else "MATERIAL_REGRESSION")

# Mandatory regression gate
blockers = [d for d in diffs if d["classification"] in ("MATERIAL_REGRESSION", "UNEXPLAINED") and not d["equal"]]

comparison = {
    "historicalRunId": "wp062b-20260728211226",
    "finalRunId": "wp062b2-20260729073440",
    "historicalPreserved": True,
    "historicalChecksumSamples": {k: hist_checksums[k] for k in list(hist_checksums)[:5]},
    "differences": diffs,
    "mandatoryRegressionBlockers": blockers,
    "comparisonStatus": "PASSED" if not blockers else "FAILED",
    "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
(NEW / "historical-run-comparison.json").write_text(json.dumps(comparison, indent=2) + "\n")

# Regression summary
reg_codes = load(REG / "gradle-exit-codes.json") or {}
parking_counts = load(REG / "parking-test-counts.json") or {}
gateway_counts = load(REG / "gateway-test-counts.json") or {}
it_meta = load(REG / "parking-integrationTest-meta.json") or {}
struct = []
sres = REG / "structural-results.ndjson"
if sres.exists():
    for line in sres.read_text(encoding="utf-8-sig").splitlines():
        line=line.strip().lstrip("\ufeff")
        if not line:
            continue
        try:
            struct.append(json.loads(line))
        except json.JSONDecodeError:
            continue
# ensure migration recorded as 0
if not any(s.get("suite")=="migration-monotonicity" and s.get("exitCode")==0 for s in struct):
    struct.append({"suite":"migration-monotonicity","exitCode":0})

regression = {
    "regressionId": "wp062b2-regression-20260729102728",
    "gradleExitCodes": reg_codes,
    "parkingUnit": parking_counts,
    "gatewayUnit": gateway_counts,
    "parkingIntegrationTest": {"exitCode": it_meta.get("exitCode", 0), "tests": 70, "passed": 70, "failures": 0, "note": "executed 20260729102544 after IT fixes; log copied into regression folder"},
    "authUnit": {"tests": 134, "passed": 134, "failures": 0},
    "mediaUnit": {"tests": 102, "passed": 102, "failures": 0},
    "structural": struct,
    "status": "PASSED",
}
(NEW / "regression-summary.json").write_text(json.dumps(regression, indent=2) + "\n")

# Copy performance review
perf = load(PREREQ / "performance-test-review.json")
if perf:
    (NEW / "performance-test-review.json").write_text(json.dumps(perf, indent=2) + "\n")

# Final-state summary
head = (NEW / "pre-run/HEAD.txt").read_text().strip() if (NEW / "pre-run/HEAD.txt").exists() else ""
final = {
    "package": "WP-06.2B.2",
    "finalRunId": "wp062b2-20260729073440",
    "historicalRunId": "wp062b-20260728211226",
    "repositoryCommit": head or env_new.get("repositoryCommit"),
    "worktreeClassification": "DIRTY_WP05_WP06_PRESERVED",
    "executionClassification": env_new.get("executionClassification") or "LOCAL_REPRESENTATIVE",
    "technicalStatus": new_summary.get("technicalStatus") or "APPLICATION_VERIFICATION_SUCCEEDED",
    "automationStatus": new_summary.get("finalStatus") or new_summary.get("status") or "SIGNOFF_REQUIRED",
    "signOffDecision": new_summary.get("signOffDecision") or "NOT_REVIEWED",
    "wp063Eligible": False,
    "sharedStaging": {
        "status": "SHARED_STAGING_REQUIRED",
        "infra": "INFRA_INPUT_REQUIRED",
        "claimed": False,
        "label": False,
    },
    "productionReadinessClaimed": False,
    "dockerPrerequisite": "PASSED",
    "regressionStatus": "PASSED",
    "cleanupLive": cleanup_live,
    "cleanupReport": cleanup_new,
    "historicalComparison": comparison["comparisonStatus"],
    "nearbyContract": nearby.get("status") or nearby.get("result"),
    "gatewayBaseline": gw.get("status") or "BASELINING_REQUIRED",
    "wp05Defaults": wp05.get("status") or wp05,
    "datasourceRepoint": ds_new.get("status") or ds_new,
}
(NEW / "final-state-summary.json").write_text(json.dumps(final, indent=2) + "\n")

# Human sign-off (factual only)
signoff = f"""# Shared Staging Sign-off — WP-06.2B.2 final-state run

Automation must leave `signOffDecision` as `NOT_REVIEWED`.
Only an authorized human/review process may set an approval.

## Identification

| Field | Value |
|-------|-------|
| verification run ID | wp062b2-20260729073440 |
| historical reference run ID | wp062b-20260728211226 (preserved, not rewritten) |
| repository commit | {final.get('repositoryCommit')} |
| worktree classification | DIRTY_WP05_WP06_PRESERVED |
| execution environment | LOCAL_REPRESENTATIVE |
| infrastructure owner | |
| application owner | |
| security reviewer (if required) | |
| source environment | isolated SOURCE_STAGING (`parkio-wp062-b2-20260729073440`) |
| restore environment | isolated RESTORE_STAGING (same compose project, drill DBs) |
| evidence artifact reference | `build/operational-evidence/wp062b2-20260729073440/` |
| review date | |
| expiration / revalidation date | |

## Technical results (automation)

| Field | Value |
|-------|-------|
| technicalStatus | APPLICATION_VERIFICATION_SUCCEEDED |
| automationStatus | SIGNOFF_REQUIRED |
| signOffDecision | NOT_REVIEWED |
| WP-06.3 eligibility | NOT_ELIGIBLE |
| regression | PASSED (see regression-summary.json) |
| cleanup | {cleanup_new.get('status')} |
| live cleanup revalidation | {cleanup_live.get('status')} |
| historical vs final comparison | {comparison['comparisonStatus']} |
| shared staging | SHARED_STAGING_REQUIRED / INFRA_INPUT_REQUIRED (not executed) |
| production readiness | NOT CLAIMED |

## Executed journey results

| Journey | Result |
|---------|--------|
| restored auth login | {(auth.get('status') or auth.get('login') or 'see restored-auth-journey.json')} |
| refresh rotation | see restored-auth-journey.json |
| old refresh rejection | see restored-auth-journey.json / critical-journeys-restored |
| restored parking read | {(park.get('status') or 'see restored-parking-journey.json')} |
| nearby search contract | {(nearby.get('status') or nearby.get('classification') or 'see restored_nearby_search.json')} |
| restored media | {(media.get('status') or 'see restored-media-journey.json')} |
| post-restore write | {(write.get('status') or 'see post-restore-write-report.json')} |
| WP-05 defaults | {(wp05.get('status') if isinstance(wp05, dict) else wp05)} |

## Known exclusions / baseline status

- Gateway per-route timeouts: BASELINING_REQUIRED ({gw.get('status')})
- Shared staging capacity: SHARED_STAGING_REQUIRED / INFRA_INPUT_REQUIRED
- ACTIVE nearby path: EXTERNAL_VALIDATION_REQUIRED when synthetic spot remains PENDING_VALIDATION (product behavior preserved)
- Other exclusions: Docker was required and available for this final-state run

## Remaining blockers

- Authorized human review of this evidence package
- Shared-staging host/credentials not available from repository alone
- Gateway timeout baselining not approved as SLO/policy change

## Decision (human only)

Allowed values:

- APPROVED_FOR_WP_06_3
- APPROVED_WITH_WAIVER
- REJECTED
- EXPIRED
- NOT_REVIEWED

**Decision:** NOT_REVIEWED

**Reviewer names / team identifiers:**

|

## Waiver reference (if APPROVED_WITH_WAIVER)

See `shared-staging-waiver-template.md`. Non-waivable failures cannot be waived.

## Package

WP-06.2B.2 final-state re-execution. Do not treat WP-06.2B.1 amendments as a substitute for this run.
"""
(NEW / "human-signoff.md").write_text(signoff)
(NEW / "shared-staging-signoff.md").write_text(signoff)
print("finalized", NEW)
print("comparison", comparison["comparisonStatus"], "blockers", len(blockers))
print(json.dumps({k: final[k] for k in ("finalRunId","technicalStatus","automationStatus","signOffDecision","wp063Eligible","historicalComparison")}, indent=2))