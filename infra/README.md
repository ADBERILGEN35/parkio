# infra

Infrastructure-as-code for deploying Parkio.

Suggested structure:

- `kubernetes/` — manifests / Helm charts / Kustomize overlays per environment.
- `terraform/` — cloud resources (networking, databases, queues, object storage).
- `environments/` — environment-specific configuration (dev, staging, prod).

This directory holds deployment definitions only; application configuration that
ships with a service lives in that service's `src/main/resources`.
