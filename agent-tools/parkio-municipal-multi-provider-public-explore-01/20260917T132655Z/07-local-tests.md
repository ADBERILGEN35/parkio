# 07 — Local Tests

**UTC:** 2026-09-17T13:32Z civarı  
**Workspace HEAD (pre-commit):** `06ee749…` + local mutations

## Unit

```
./gradlew :services:parking-service:test
  --tests PublicExplorePropertiesTest
  --tests PublicExplorePublicationPolicyTest
  --tests PublicExploreQueryServiceTest
  --tests PublicExploreControllerTest
```

**RESULT = PASS** (exit 0)

Kapsanan: CFG-A..I semantiği, IZUM regression, Kadıköy origin, mixed attribution/occupancy izolasyonu, empty allowlist, limit/radius guards, community threshold.

## Integration (Docker)

```
./gradlew :services:parking-service:integrationTest
  --tests PublicExploreRepositoryPostgresIT
```

**RESULT = PASS** (exit 0, ~15s)

Kapsanan: IZUM-only clamp 6 / count 22, multi-provider interleave + global 6, Kadıköy ISPARK spatial, empty allowlist, by-id scope.

## DB migration

**0**
