# Municipal registry review runbook

DATA-WP-04 is dark by default. Never enable automatic linking; `PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED` must remain `false`. Binding `true` fails startup. Candidate generation never applies links; enabling the review API alone does not enable reviewed-link application (`reviewed-linking-enabled` is required separately).

## Compose location and validation

Canonical Azure overlay mapping for registry flags:

`docker/docker-compose.azure-hosted-beta.yml` → `services.parking-service.environment`

Full hosted-beta Azure validation (no `--skip-compose`):

```bash
PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta \
  PARKIO_ENV_FILE=docker/.env.azure-hosted-beta.example \
  ./scripts/validate-hosted-beta-compose.sh
```

That command merges five files via `scripts/lib/deploy-common.sh`. Do not validate `docker-compose.azure-hosted-beta.yml` against only the base compose file: the Azure overlay has no `image`/`build` and fails with "neither an image nor a build context".

## Kill switches

Disable in this order when anomalies appear:

1. `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED=false`
2. `PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED=false`
3. `PARKIO_MUNICIPAL_REGISTRY_REVIEW_API_ENABLED=false`
4. `PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED=false`

These switches do not affect IZUM availability collection. Do not enable IZELMAN publication as part of registry review.

## Public provenance publication (DATA-WP-09)

`PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED` defaults **false**. When temporarily
enabled (DATA-WP-09A only), nearby/detail DTOs may include:

- `contributingSourceKeys` — distinct publishable source keys
- `selectedFieldProvenanceSummary` — allow-listed field → `source_key` only

Never expect confidence, review status, candidate IDs, or İZELMAN fields while İZELMAN
publication remains off. Disabling the flag restores null provenance fields immediately; no
database rewrite. Do not enable this flag in hosted-beta under DATA-WP-09 implementation alone.

## Review notes

Use only ADMIN or SUPER_ADMIN credentials. Inspect bounded evidence and hard conflicts. Accept only when multiple independent signals identify the same facility and choose one of the two canonical facility IDs. Reject insufficient evidence; use distinct when evidence establishes separate facilities. Reopen reverses an accepted link without deleting source identities, aliases history, audit, or occupancy history. Concurrent reviewers must supply the current optimistic `expectedVersion`; a stale version returns HTTP 409 and leaves exactly one applied final state.

Rejections suppress candidate regeneration only for the same pair of source versions and algorithm version. A changed source version can legitimately create a new candidate.

## Bounded candidate generation (DATA-WP-05)

The ADMIN/SUPER_ADMIN endpoint is available only when
`PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED=true`:

`POST /api/v1/parking/admin/municipal/registry/link-candidates/generate`

Supported pair: `IZUM` + `OSM`. `IZUM` + `IZELMAN` and `OSM` + `IZELMAN`
are designed but rejected until a later explicit data-availability gate. This does
not claim that OSM or IZELMAN input is available in any environment.

Bounds default to 100 m / 100 left records / 1,000 pairs / 20 samples and clamp
at 250 m / 1,000 left records / 10,000 pairs / 20 samples. Non-positive values
return HTTP 400. `dryRun=true` and `persistCandidates=false` evaluate only.
`persistCandidates=true` requires `dryRun=false`; it inserts PENDING review
candidates but never links aliases or mutates occupancy, tariffs, or publication.
Only one RUNNING audit exists per source-family pair; overlap returns HTTP 409.

Inspect runs with:

- `GET /api/v1/parking/admin/municipal/registry/link-candidate-runs/{runId}`
- `GET /api/v1/parking/admin/municipal/registry/link-candidate-runs?page=0&size=20&sourceFamilyPair=IZUM_OSM`

DATA-WP-05A has not started. Do not enable registry flags in hosted-beta Compose
or run live source input under this package.

## Integration coverage

Postgres/PostGIS ITs cover V31→V32 and V32→V33 migration, bounded discovery,
candidate uniqueness/idempotency, dry-run write protection, hard-conflict
retention, accept/reject/reopen mutation paths, occupancy preservation,
source-link lifecycle isolation, and automatic-linking prohibition. Live
hosted-beta shadow against official datasets remains an operator gate outside
this package.

## Dry-run

Run `powershell -File scripts/registry-candidate-dry-run.ps1`. It uses deterministic fixtures and prints aggregate counts only. Live input is intentionally unsupported. A dry-run may write its bounded generation-run audit record; it writes no candidates, links, aliases, occupancy, tariffs, provenance publication, or source data.

## Monitoring and rollback

Inspect `/actuator/health` component `municipalRegistry`, including active,
latest-completed, and stale generation-run details, and bounded
`parkio.municipal.registry.*` metrics. Health findings do not fail liveness.
On rollback, disable all flags. V32 and V33 are forward-only; do not drop
tables. Reopen any incorrect accepted decisions through the API and retain the
audit trail.