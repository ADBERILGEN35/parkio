from pathlib import Path
import json
new=Path("build/operational-evidence/wp062b2-20260729073440")
s=json.loads((new/"shared-staging-summary.json").read_text(encoding="utf-8-sig"))
env=json.loads((new/"environment-manifest.json").read_text(encoding="utf-8-sig"))
cj=json.loads((new/"critical-journeys-restored"/"summary.json").read_text(encoding="utf-8-sig"))
summary={
  "evidenceSchemaVersion": s.get("evidenceSchemaVersion","1.0.0"),
  "runId": s["runId"],
  "repositoryCommit": s["repositoryCommit"],
  "environmentType": env.get("environmentType","STAGING_LOCAL"),
  "startedAt": cj.get("startedAt") or env.get("generatedAt") or "2026-07-29T07:34:40Z",
  "completedAt": cj.get("completedAt") or "2026-07-29T07:37:05Z",
  "status": s["status"],
  "stages": cj.get("stages", {}),
  "executionClassification": s.get("executionClassification"),
  "technicalStatus": s.get("technicalStatus"),
  "signOffDecision": s.get("signOffDecision"),
  "wp063Eligible": s.get("wp063Eligible"),
  "sharedStagingLabel": s.get("sharedStagingLabel"),
  "automationMayNotApprove": s.get("automationMayNotApprove", True),
}
(new/"summary.json").write_text(json.dumps(summary, indent=2)+"\n", encoding="utf-8")
print("summary.json written", summary["runId"], "stages", len(summary.get("stages") or {}))