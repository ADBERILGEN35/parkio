package com.parkio.parking.decision.port;

import com.parkio.parking.decision.audit.DecisionAuditRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only Decision Audit Store port (ADR-WP05 {@code DecisionAuditPort}).
 *
 * <p>Shadow writes may be best-effort at the application boundary. Authoritative
 * writes must participate in the same transaction as the applied mutation and
 * must never be swallowed.
 */
public interface DecisionAuditPort {

    /** Append a completed audit record. Must not mutate existing rows. */
    void append(DecisionAuditRecord record);

    /**
     * Append an AUTHORITATIVE applied audit record. Failures must propagate so
     * the surrounding transaction rolls back. Must not be wrapped in best-effort
     * catch blocks at the authority boundary.
     */
    default void appendAuthoritativeRequired(DecisionAuditRecord record) {
        append(record);
    }

    Optional<DecisionAuditRecord> findById(UUID auditId);

    List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId);

    List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId);

    List<DecisionAuditRecord> findByPolicyVersion(String policyVersion);

    List<DecisionAuditRecord> findByEvaluatedAtBetween(Instant fromInclusive, Instant toExclusive);

    /** Idempotency lookup for a successfully applied authoritative evaluation. */
    default Optional<DecisionAuditRecord> findAuthoritativeApplied(
            UUID evaluationId, String policyVersion) {
        return Optional.empty();
    }

    /** No-op port for disabled shadow / unit tests without persistence. */
    static DecisionAuditPort noop() {
        return new DecisionAuditPort() {
            @Override
            public void append(DecisionAuditRecord record) {}

            @Override
            public Optional<DecisionAuditRecord> findById(UUID auditId) {
                return Optional.empty();
            }

            @Override
            public List<DecisionAuditRecord> findByParkingSpotId(UUID parkingSpotId) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByEvaluationId(UUID evaluationId) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByPolicyVersion(String policyVersion) {
                return List.of();
            }

            @Override
            public List<DecisionAuditRecord> findByEvaluatedAtBetween(
                    Instant fromInclusive, Instant toExclusive) {
                return List.of();
            }
        };
    }
}