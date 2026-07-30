# Municipal registry review runbook

DATA-WP-04 is dark by default. Never enable automatic linking; `PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED` must remain `false`.

## Kill switches

Disable in this order when anomalies appear:

1. `PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED=false`
2. `PARKIO_MUNICIPAL_REGISTRY_REVIEWED_LINKING_ENABLED=false`
3. `PARKIO_MUNICIPAL_REGISTRY_REVIEW_API_ENABLED=false`
4. `PARKIO_MUNICIPAL_REGISTRY_CANDIDATE_GENERATION_ENABLED=false`

These switches do not affect IZUM availability collection. Do not enable IZELMAN publication as part of registry review.

## Review notes

Use only ADMIN or SUPER_ADMIN credentials. Inspect bounded evidence and hard conflicts. Accept only when multiple independent signals identify the same facility and choose one of the two canonical facility IDs. Reject insufficient evidence; use distinct when evidence establishes separate facilities. Reopen reverses an accepted link without deleting source identities, aliases history, audit, or occupancy history. A stale `expectedVersion` returns HTTP 409.

Rejections suppress candidate regeneration only for the same pair of source versions and algorithm version. A changed source version can legitimately create a new candidate.

## Dry-run

Run `powershell -File scripts/registry-candidate-dry-run.ps1`. It uses deterministic fixtures and prints aggregate counts only. Live input is intentionally unsupported. The dry-run writes no links, occupancy, tariffs, provenance publication, or source data.

## Monitoring and rollback

Inspect `/actuator/health` component `municipalRegistry` and bounded `parkio.municipal.registry.*` metrics. Health findings do not fail liveness. On rollback, disable all flags. V32 is forward-only; do not drop tables. Reopen any incorrect accepted decisions through the API and retain the audit trail.