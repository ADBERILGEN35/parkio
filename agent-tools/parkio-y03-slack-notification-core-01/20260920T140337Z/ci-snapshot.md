# CI snapshot — PR #54 (captured during package)

| Field | Value |
|-------|-------|
| PR | https://github.com/ADBERILGEN35/parkio/pull/54 |
| Draft | yes |
| Base OID (api) | `4a9ba2184e867b8ea8b927f7c3f8bcb137c2760a` |
| Head OID | `8df6bb531b50530860205959f6388f3d08d0b9ff` |
| Checkout SHA for CI | same as head (`8df6bb53…`) |

## Relevant results at capture

| Check | Result |
|-------|--------|
| Config + script checks | **pass** (4m47s) |
| Build & unit tests | **pass** (5m50s) |
| Secret scan | **pass** |
| Backup → restore → assert | **pass** |
| Dependency vulnerability scan | **pass** |
| Container scans (sampled) | **pass** |
| CodeQL (javascript-typescript) | **pass** |
| CodeQL (java-kotlin) | **pending** at capture |
| Integration tests (Testcontainers) | **pending** at capture |
| Full Docker Compose runtime validation | **pending** at capture |
| k6 smoke | **pending** at capture |
| Compose dependency recovery drill | **pending** at capture |
| Deploy invite-production | skipping |

Local mock acceptance (not CI): **15/15 PASS**.
