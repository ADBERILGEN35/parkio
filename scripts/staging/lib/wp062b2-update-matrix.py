import json
from pathlib import Path
p=Path("build/operational-evidence/wp062b2-prereq/post-historical-change-matrix.json")
data=json.loads(p.read_text())
extra=[
 {
  "file": "services/.../RewardShadowMigrationPostgresIT.java",
  "section": "full-migrate version assert",
  "behaviorAffected": "expects Flyway current version 26 after full migrate",
  "evidenceAffected": "parking integrationTest",
  "historicalRunCovers": False,
  "rerunMandatory": True,
  "verificationMethod": "parking-service:integrationTest"
 },
 {
  "file": "services/.../TrustShadowMigrationPostgresIT.java",
  "section": "full-migrate version assert",
  "behaviorAffected": "expects Flyway current version 26 after full migrate",
  "evidenceAffected": "parking integrationTest",
  "historicalRunCovers": False,
  "rerunMandatory": True,
  "verificationMethod": "parking-service:integrationTest"
 },
 {
  "file": "services/.../TrustShadowPersistencePostgresIT.java",
  "section": "concurrentDistinctEvidencePreservesBothUpdatesAndMatchesReplay",
  "behaviorAffected": "reprocess SNAPSHOT_CONFLICT survivors; production retry unchanged",
  "evidenceAffected": "parking integrationTest",
  "historicalRunCovers": False,
  "rerunMandatory": True,
  "verificationMethod": "parking-service:integrationTest"
 },
 {
  "file": "scripts/staging/_wp062b2_run_restored_stack.sh",
  "section": "WP-06.2B.2 launcher",
  "behaviorAffected": "new run ID, wp062b2 marker, isolation marker, inventories",
  "evidenceAffected": "new evidence root",
  "historicalRunCovers": False,
  "rerunMandatory": True,
  "verificationMethod": "fresh restored-stack execution"
 }
]
data["changes"].extend(extra)
data["wp062b2Notes"]="Historical run wp062b-20260728211226 preserved; amendments do not certify post-run code."
p.write_text(json.dumps(data, indent=2)+"\n")
print("matrix updated", len(data["changes"]))