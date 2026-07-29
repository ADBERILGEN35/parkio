# Database Migration Governance

Never edit applied Flyway migrations. Parking WP-05: V20-V26 forward-only.

Expand/contract: nullable columns first; destructive changes require PRR.

Tests: Testcontainers upgrade path. Monotonicity: OperationalReadinessGovernanceTest.