# infra

Infrastructure-as-code for deploying Parkio.

Suggested structure:

- `kubernetes/` — manifests / Helm charts / Kustomize overlays per environment.
- `terraform/` — cloud resources (networking, databases, queues, object storage).
- `environments/` — environment-specific configuration (dev, staging, prod).

This directory holds deployment definitions only; application configuration that
ships with a service lives in that service's `src/main/resources`.

## Managed PostgreSQL (PP-01)

Provider and topology are decided in
[`docs/architecture/adr/ADR-PP-01A-managed-postgresql.md`](../docs/architecture/adr/ADR-PP-01A-managed-postgresql.md)
(**ACCEPTED WITH CONDITIONS**):

- Primary: Azure Database for PostgreSQL Flexible Server
- Alternate: AWS RDS for PostgreSQL
- Default topology: 2 clusters · 10 logical databases · 10 isolated roles

**PP-01 remains open.** PP-01B may author IaC and run named sandbox spikes
([`docs/architecture/pp-01b-spike-registry.md`](../docs/architecture/pp-01b-spike-registry.md))
only. No production apply, public production GO, or municipal production
enablement is authorized by PP-01A. This directory is still a placeholder until
PP-01B lands IaC.
