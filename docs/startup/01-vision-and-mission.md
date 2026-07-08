# Vision and Mission

## Vision

Make parking less uncertain by turning fresh driver observations into a trusted, community-powered parking intelligence layer.

## Mission

Parkio helps drivers find, share, verify, and manage real-world parking availability through a mobile-first platform that rewards useful contributions, protects sensitive user data, and keeps product claims tied to operational evidence.

## Product Principles

- Useful in the moment: help a driver decide where to park now, not just learn about parking later.
- Community confidence: combine photos, freshness, verification, claims, reports, moderation, and contributor history.
- Mobile-first contribution: make capture, verification, claiming, reporting, and notifications work where parking decisions happen.
- Honest availability: do not promote expired, filled, suspicious, rejected, or legally risky spots as reliable parking.
- Measured expansion: start with one constrained beta area and expand only after data quality and retention are visible.

## Privacy Principles

- Minimize sensitive data outside auth and user services.
- Treat precise user location history as sensitive.
- Store spot location when a user intentionally submits a spot; do not imply passive background tracking as a default.
- Keep Smart Return opt-in and clear about what location/return settings it needs.
- Serve spot photos through visibility checks and short-lived signed URLs.
- Strip original image EXIF/GPS/device metadata before storage.
- Do not sell raw individual movement history.

## Community Principles

- Reward useful contributions and successful claims.
- Use trust and contribution scores to shape reliability over time.
- Let users report inaccurate, unsafe, illegal, occupied, fake, or offensive spots.
- Keep unconfirmed community reports separate from authoritative moderation decisions.
- Avoid guaranteeing availability; Parkio improves parking intelligence but cannot reserve public spots.

## Technical Principles

- Service-owned domains and databases.
- No shared domain models across services.
- Gateway-only public ingress.
- Fail-closed auth, secrets, upload scanning, and privileged routes.
- Event-driven workflows with outbox/inbox and idempotent consumers.
- Operator-run hosted beta before public production.
- Public-production claims require measured runtime evidence.
