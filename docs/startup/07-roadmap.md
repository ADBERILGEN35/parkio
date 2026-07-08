# Roadmap

## Stage 0: Current RC1 State

Status:

- `v1.0.0-rc1` application release candidate.
- Backend certification complete for hosted-beta application layer.
- Frontend certification complete for hosted-beta scope with mobile caveat.
- Release documentation and operator runbooks present.
- Live hosted-beta deployment not yet executed.
- Public production not ready.

## Stage 1: Hosted-Beta Deployment

Goal: turn the certified application release candidate into a live, operator-run beta environment.

Required before claiming a live beta:

- Provision VPS.
- Configure DNS for `parkio.dev` hosted-beta subdomains.
- Prepare real hosted-beta `.env`.
- Run preflight successfully.
- Deploy `v1.0.0-rc1`.
- Run HTTPS smoke tests.
- Run backup and restore drill.
- Verify observability and alerting.
- Run mobile device smoke before widening mobile beta.

Success criteria:

- Live HTTPS endpoints verified.
- Login, upload, spot create, verify/claim, notifications, Smart Return, and RBAC smoke-tested where enabled.
- Backup/restore and alerting evidence recorded.

## Stage 2: Beta Cohort 1

Goal: controlled validation in one constrained geography.

Focus:

- Registration and login.
- Upload photo and create spot.
- Nearby discovery.
- Spot detail and photo access.
- Verify and claim.
- Reports and moderation queue.
- Notifications.
- Smart Return only after operator smoke.

Metrics:

- Not yet measured.

Decision gate:

- Continue only if spot quality, tester support burden, and early retention justify expansion.

## Stage 3: Beta Cohort 2

Goal: expand carefully after cohort 1 shows useful local density.

Focus:

- More testers or a second nearby geography.
- Reliability under higher contribution volume.
- Moderation capacity.
- Notification quality.
- Mobile device coverage.
- Early retention signals.

Metrics:

- Not yet measured.

## Stage 4: Public Beta

Public beta requires stronger operational proof:

- Managed or hardened data backups.
- Security review beyond static CI gates.
- Load/performance evidence.
- Final legal and privacy copy.
- Account/data deletion policy and workflow.
- Support and incident process.

## Stage 5: Production Hardening

Public production blockers from repo evidence:

- Managed HA PostgreSQL/PostGIS with PITR.
- Managed Kafka or replicated broker setup.
- Secrets manager and rotation.
- CD with rollback and approval.
- Alerting and on-call process.
- Production-scale load and security testing.
- Mobile device certification completion.

## Product Roadmap

- Validate beta user journey.
- Improve spot quality and trust signals.
- Tune gamification and trust scoring with real data.
- Validate Smart Return usefulness.
- Improve beta feedback loops.
- Prepare public landing page at `parkio.dev`.
- Build future premium/partner hypotheses only after measured usage.
