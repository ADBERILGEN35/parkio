# Current live rollback compatibility

E2E candidate:
`parkio-gateway-waitlist-candidate:008a6b2077f6`
(`sha256:16f7d5249763008c0856c0c1c3310eb8b17137ae50069b99dc09a489504651a1`,
revision `008a6b2077f64d82a83da6d88e7bb6efeeafb498`).

Rollback artifact (the image running on Civo at the read-only inspection):
`ghcr.io/adberilgen35/parkio/gateway-service@sha256:8b8a08ba974aeec91ec690ada406552a474c2319880752d4d4ea5ff1798028aa`
(revision `83fa625b9e37d6c0819862459d5570e305dd5121`).

The isolated 11-case Docker test used PostgreSQL 16.15 and a schema migrated
through V4 by the candidate. E09 replaced the gateway with the rollback
artifact. Flyway validated four migrations, reported current schema version 4
as newer than the artifact's latest migration 3, applied no migration, and the
gateway became healthy. A synthetic waitlist confirmation returned 202 and
committed `CONFIRMED`; history remained
`1:true,2:true,3:true,4:true`. E10 rolled forward to the candidate and became
healthy. The suite finished `PASS=11 FAIL=0`.

Synthetic data and a local mock Slack receiver only; no production mutation or
real notification.
