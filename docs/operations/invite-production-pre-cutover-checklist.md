# Invite-production canonical pre-cutover checklist

This is the only `PROD-DEPLOY-01A` cutover checklist. A checked item requires
current evidence from the exact candidate SHA. Completing it does not authorize
DNS switching, data migration, traffic, or invitations.

## Candidate and CI

- [ ] Candidate SHA is recorded and equals `origin/api`.
- [ ] Backend CI: SUCCESS.
- [ ] Backend Integration: SUCCESS.
- [ ] Security CI: SUCCESS; secret, dependency, and container scans have no
  current unwaived HIGH/CRITICAL.
- [ ] Backup Restore Drill: SUCCESS.
- [ ] Observability Validation: SUCCESS.
- [ ] Frontend CI: SUCCESS, including `MapPage.test.tsx`.
- [ ] Staging verification: SUCCESS; no `gradlew` permission failure.
- [ ] Chaos/runtime/performance gates triggered by the candidate are green.

## Managed data plane

- [x] PostgreSQL 16 Flexible Server is `Ready`, private-only, FQDN-based, and
  uses a delegated subnet/private DNS.
- [x] PITR retention is 30 days; HA is explicitly deferred for invite only.
- [x] Ten empty logical DBs, ten runtime roles, and ten separate migration roles
  exist without cross-database grants.
- [x] `verify-full` negotiated TLS 1.3 from the application VM.
- [x] PostGIS 3.6.1, SRID 4326, GiST, and `ST_DWithin` synthetic proof passed.
- [ ] Exact Flyway latest versions for all ten services are recorded with no drift.
- [ ] All DB-backed Spring services boot healthy; at minimum auth, user,
  parking, and gateway show Flyway/Hikari success without local Postgres.
- [ ] Managed connection count stays inside the 60-connection invite budget.

## Secrets and privacy

- [x] Key Vault and VM managed identity are provisioned; generated first-party
  secrets are present without committed values.
- [ ] Operator supplies ACME contact, MapTiler, Resend, Expo, and Slack values;
  env renderer passes without printing values.
- [ ] Rotation procedure is operator-reviewed.
- [ ] Account-erasure flag is ON, all eight handlers/topic/coordinator/tombstone/
  backup-ledger/replay/`AccountErasureStuck` paths are present.
- [ ] A disposable synthetic account passes create -> data -> erase -> login
  blocked -> data gone/anonymized.

## Runtime and edge

- [x] Host is 4 vCPU / 32 GiB with 256 GiB data disk; Docker/Compose are healthy.
- [ ] Prometheus, Alertmanager, Grafana, node/service/provider/backup metrics,
  and Azure PostgreSQL metrics are live.
- [ ] Safe Alertmanager acceptance delivered one FIRING and one RESOLVED to the
  real operator Slack receiver; human visually confirms both.
- [ ] External probes allow only 443 and reject service, DB, monitoring, Kafka,
  Redis, and MinIO internal/admin ports.
- [ ] CORS is exactly `https://app.parkio.dev`; forwarded scheme/host and trusted
  proxy semantics pass; no credentialed wildcard.
- [ ] DNS remains unchanged in 01A; certificate/renewal configuration is ready
  for the separately authorized 01B DNS window.
- [ ] Immutable web build contains the production API URL, no hosted-beta URL or
  debug flag, and is tied to the candidate SHA/digest.

## Backup, providers, and policy

- [ ] `parkio-invite-backup.timer` is enabled as the only scheduler.
- [ ] One real production-mode synthetic/empty backup proves managed TLS, ten
  encrypted DB artifacts, MinIO copy, manifest/checksum, offsite OAuth upload,
  success metric, and healthy paging.
- [ ] IZUM and ISPARK safe GET/provider probes pass from the production VM.
- [ ] ANPARK, KONYA, KAYSERI, OSM import, shadow, evaluation, rollup, learned
  ranking, and AI ranking authority are OFF.
- [ ] Ranking is exactly `DETERMINISTIC_V1`; recommendations and intended invite
  features are ON.

## Deployment and window

- [ ] Repository-native deploy workflow accepts exact SHA, builds SHA-tagged
  images, records image IDs, injects Key Vault secrets, excludes local Postgres,
  runs health/smoke, and stores a secret-free manifest.
- [ ] Manifest records SHA, tag/IDs, environment, time, DB identity, Flyway
  versions, feature flags, rollback reference, and workflow/operator identity.
- [ ] Rollback reference is recorded and both rollback boundaries are reviewed.
- [ ] Disposable smoke account exists; no personal/operator account is used.
- [ ] Maintenance/cutover window is separately approved.
- [ ] Operator explicitly authorizes PROD-DEPLOY-01B. Do not infer this from all
  other boxes being checked.
