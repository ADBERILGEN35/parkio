# Database Migration Governance

Never edit applied Flyway migrations. Parking WP-05: V20-V26 forward-only.

Expand/contract: nullable columns first; destructive changes require PRR.

Tests: Testcontainers upgrade path. Monotonicity: OperationalReadinessGovernanceTest.

Managed PostgreSQL: `parkio_parking` begins at V2 behind an explicit Flyway baseline — the migration role may not run `CREATE EXTENSION`. See [managed-parking-flyway-baseline.md](managed-parking-flyway-baseline.md).
