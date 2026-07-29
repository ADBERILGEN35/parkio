from pathlib import Path
bad=[]
paths=[
 "scripts/staging/run-wp062b-restored-stack-verification.sh",
 "docker/docker-compose.restored-application-verification.yml",
 "scripts/staging/lib/ensure-jwt-material.py",
 "scripts/staging/lib/safety-guards.sh",
 ".github/workflows/shared-staging-verification.yml",
 "scripts/staging/lib/wp062b1-evidence-consistency-audit.py",
 "scripts/staging/lib/wp062b1-amend-evidence-schema.py",
 "services/parking-service/src/test/java/com/parkio/parking/application/ExposureShadowApplicationServiceTest.java",
 "services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/trust/TrustShadowPersistencePostgresIT.java",
 "services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/trust/TrustShadowMigrationPostgresIT.java",
 "services/parking-service/src/test/java/com/parkio/parking/infrastructure/persistence/reward/RewardShadowMigrationPostgresIT.java",
]
for pth in paths:
  b=Path(pth).read_bytes()
  if b"\x00" in b: bad.append("NUL:"+pth)
  if b.startswith(b"\xff\xfe") or b.startswith(b"\xfe\xff"): bad.append("UTF16:"+pth)
print("utf8_ok" if not bad else bad)
if bad: raise SystemExit(1)