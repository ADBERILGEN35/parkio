import json
from pathlib import Path
root=Path("build/operational-evidence/wp062b2-20260729073440")
out={}
for rel in [
 "datasource-repoint-report.json","restored-auth-journey.json","restored-parking-journey.json",
 "restored-media-journey.json","post-restore-write-report.json","wp05-defaults-report.json",
 "critical-journeys-restored/restored_nearby_search.json","critical-journeys-restored/summary.json",
 "cleanup-report.json","cleanup-live-revalidation.json","final-state-summary.json",
 "historical-run-comparison.json","gateway-route-baseline.json","shared-staging-summary.json",
 "environment-manifest.json","evidence-consistency-audit.json"
]:
  d=json.loads((root/rel).read_text(encoding="utf-8-sig"))
  slim={k:d[k] for k in d if k in {
    "status","technicalStatus","signOffDecision","baseliningStatus","classification","result",
    "nearbyClassification","login","refresh","checksumMatch","comparisonStatus",
    "developerParkioUnchanged","remainingWp062Containers","executionClassification",
    "wp063Eligible","sharedStagingLabel","sourceDatabases","restoreDatabases",
    "kafkaIsolation","redisIsolation","sourceMinioBucket","restoreMinioBucket",
    "sourceComposeProject","restoreComposeProject","mandatoryJourneyFailures","ok_count","issues",
    "environment","policyUnchanged","services"
  } or k.endswith("Status")}
  if "stages" in d and isinstance(d["stages"], dict):
    slim["stageStatuses"]={k:v.get("status") for k,v in d["stages"].items()}
  if "services" in d and isinstance(d["services"], list):
    slim["services"]=[{k:s.get(k) for k in ("name","jdbcDatabase","pointsToRestoreDb","status") if k in s or True} for s in d["services"]]
  out[rel]=slim
(root/"evidence-key-fields.json").write_text(json.dumps(out, indent=2)+"\n")
print(json.dumps(out, indent=2)[:8000])