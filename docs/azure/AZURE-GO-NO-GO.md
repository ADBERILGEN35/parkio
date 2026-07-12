# Azure GO / NO-GO

## Decisions

| Decision | Verdict | Evidence and conditions | Main blocker / cost risk |
|---|---|---|---|
| 1. Full current Compose on one VM | **NO-GO under credit** | repo target 8 vCPU/24 GiB, 15.8-GiB ceilings; use D8as_v5 8/32 after paid approval | USD 317.52 fixed/30d before variable usage; single-host risk |
| 2. Reduced beta stack on one VM | **CONDITIONAL GO** | deterministic 32-service profile, 14-GiB ceilings, no Alertmanager/Loki/Promtail/Tempo, 24h stabilization | Docker render and Azure runtime still unverified |
| 3. Azure managed services | **NO-GO** | reconsider after beta and IaC/config work | transparent managed floor USD 705+/30d; compatibility work |
| 4. 8-GiB VM | **NO-GO** | measured reduced run used 7.7 GiB idle and 10 GiB under read load | certain memory pressure/no OS headroom |
| 5. 16-GiB VM | **CONDITIONAL GO** | only reduced observability, strict triggers, measured target proof | current ceilings do not satisfy repo 85% contract |
| 6. B-series | **NO-GO for 24/7; GO for scheduled experiment** | B4ms saves only USD 11.52/30d vs D4as_v5 | credit depletion/throttling during Gradle builds, Kafka and Spring startup |
| 7. D-series | **CONDITIONAL GO** | D4as_v5 fixed CPU best credit compromise | 4 vCPU below repo sizing; D8 sizes exceed credit |
| 8. Closed hosted beta | **CONDITIONAL GO** | waitlist regressions and static profile checks pass; named testers and runtime gates remain | live Docker/Azure proof blocked |
| 9. External/public hosted beta | **NO-GO** | requires HA/PITR, legal/privacy closure, full write/soak/security/runtime evidence | single VM/data plane, RF=1, no public-production DR/on-call |
| 10. 30-day temporary deployment | **CONDITIONAL GO** | USD 180 cap, day-22 decision, day-29 deallocate, day-30 delete | variable meters can consume USD 32 fixed buffer |
| 11. Continue after credit expiry | **NO-GO without new approval** | approved recurring budget and migration/production plan required | free subscription can be disabled; paid cost not accepted |

## Required conditions before Azure GO

1. Fresh release commit is clean; unrelated current frontend/Hostinger work is resolved by its owner.
2. Azure CLI/Cloud Shell account, quota, region availability, portal price, credit balance, and expiry are verified.
3. Mobile/web artifact checks continue to enforce `https://api.parkio.dev/api/v1`.
4. The `azure-hosted-beta` profile renders with 32 runtime and four disabled services.
5. Preflight and Compose render pass on Docker Compose v2.24.4+ with real secrets.
6. AMD64 image builds and all pinned third-party manifests pass.
7. TLS, public-port denial, authenticated flows, waitlist 202, upload/ClamAV/MinIO, Kafka lag/outbox, and health checks pass.
8. D4as_v5 completes deploy/restart without OOM/swap pressure and keeps at least 2 GiB normal headroom for 24 hours.
9. Encrypted off-VM backup and disposable PostgreSQL/PostGIS + media restore pass.
10. Cost forecast remains below USD 180 through planned exit.

## Exact blockers today

- No Azure resources/credentials were used; live deployment is NOT VERIFIED.
- Azure CLI is absent in the audit environment.
- Docker is unavailable through WSL and `docker.exe` transport, blocking the new five-file render, dry-runs, images, exposure assertions, and runtime proof.
- The repository's recommended full-stack sizing is unaffordable for 30 days with USD 200.
- The D4as_v5 proposal is below documented CPU/RAM headroom and lacks Azure measurements.
- The waitlist test is fixed, but real intake still requires live API/privacy/backup verification before opening Hostinger collection.
- Write/upload, long soak, Azure disk performance, cold build time, restore time, and live rollback are not proven on Azure.
- Public production blockers remain: single VM, single databases, Kafka RF=1, no PITR/HA, local env secrets, no tested Azure DR/IaC/on-call.

## Final verdict

**AZURE CLOSED 30-DAY HOSTED BETA: CONDITIONAL GO.** The credit is likely sufficient for the fixed D4as_v5 profile at USD 167.76/30 days, leaving USD 32.24 before variable meters. Use it only as a temporary validation environment with reduced observability, hard cost gates, named testers, and verified exit backups.

**FULL STACK OR PUBLIC BETA: NO-GO under the USD 200 credit.**
