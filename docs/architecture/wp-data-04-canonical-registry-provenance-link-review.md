# DATA-WP-04 canonical registry and link review

## Boundary

The municipal registry stores durable facility identity and metadata. Availability is a separate,
short-lived observation stream in `municipal_occupancy_snapshots`. IZUM is the only source permitted
to create live municipal occupancy. OSM and IZELMAN never create occupancy snapshots and missing
occupancy must never be inferred from capacity.

DATA-WP-04 extends `parking-service`; it does not add a service and does not start DATA-WP-05.

## Identity, provenance, and precedence

Canonical facility UUIDs remain stable. Every external identity remains in
`municipal_facility_source_links`. Accepted merges redirect the superseded UUID through
`municipal_facility_aliases`; no source identity or occupancy history is deleted.

`municipal_facility_field_provenance` records the selected source record, content/fetch timestamps,
age class, confidence/review state, reason, and optimistic version for every registry field.
DATA-WP-10 writes allow-listed selections on successful İZUM sync and OSM import; it does not
enable public publication. `CanonicalFieldPrecedencePolicy` is the central policy:

- verified municipal name, address, operator, type, and current static capacity precede OSM;
- restrictive access wins conflicts;
- attribution follows every contributed source;
- only explicit, strong, CURRENT IZELMAN tariff assignments may be selected;
- AGING/HISTORICAL tariffs never become CURRENT;
- source publication gates remain independent, and IZELMAN publication remains off by default.

## Candidate generation and review

`LinkCandidatePolicy` supports IZUM-OSM, IZUM-IZELMAN, and OSM-IZELMAN. Distance alone and name
alone are insufficient. Candidates require geometry plus semantic evidence. Hard conflicts
(material coordinates, exclusive facility type, zone vs facility, operator contradiction,
access exclusivity, address/district conflict, capacity divergence) are retained for human review.
Automatic linking is forbidden in code and config.

The candidate uniqueness key includes both source identities, both source versions, and algorithm
version. Therefore a rejected candidate is suppressed for unchanged source versions, while changed
source content can produce a new candidate. Reviews use optimistic versions and append immutable
audit rows. Accept/reopen is reversible: source links can be restored to the original facilities,
aliases removed, and superseded facilities reactivated without deleting history.

## Lifecycle and publication

Complete successful disappearance may deactivate a source link. Partial runs and failures do not.
A facility with no active links is retained but unpublished. Reactivation reuses identity. Stale
availability remains history but is not reported as live. Public provenance is bounded and only
included when `provenance-publication-enabled` is true; review scores, reviewer identity, reasons,
raw source payloads, and unpublished source fields are never public. DATA-WP-09 implements that
publication path (allow-listed field → public `source_key` only; confidence/review status never
published).

## Flags

All flags default false:

- `parkio.municipal.registry.candidate-generation-enabled`
- `parkio.municipal.registry.review-api-enabled`
- `parkio.municipal.registry.reviewed-linking-enabled`
- `parkio.municipal.registry.automatic-linking-enabled` (hardcoded false; binding true throws)
- `parkio.municipal.registry.provenance-publication-enabled`
- `parkio.municipal.registry.provenance-ingest-write-enabled` (DATA-WP-10; default true)

Azure hosted-beta maps these on `parking-service.environment` in
`docker/docker-compose.azure-hosted-beta.yml`. Validate with the five-file chain
via `./scripts/validate-hosted-beta-compose.sh` using
`PARKIO_DEPLOYMENT_PROFILE=azure-hosted-beta` and
`docker/.env.azure-hosted-beta.example`. See
`docs/operations/municipal-registry-review-runbook.md`.

## Metrics and health

Registry metrics use only bounded labels: `source_family_pair`, `outcome`, `reason_category`,
`review_state`, `field_name`, and `algorithm_version`. The `municipalRegistry` health contribution
reports alias integrity, orphan links, no-active-source facilities, unresolved candidates, invalid
tariff precedence, inactive-facility availability, and duplicate external IDs. It always reports UP
and therefore cannot fail liveness.

## Rollback and limitations

V32 is forward-only. Operational rollback disables all registry flags; it does not reverse schema.
Accepted links are reversed through the reopen action. The implementation intentionally does not
perform automatic matching, infer tariffs, publish IZELMAN, or derive availability from static data.
Candidate generation is invoked explicitly; scheduling and live hosted-beta dry-runs are out of scope
until a separate opt-in gate.