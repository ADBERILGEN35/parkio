package com.parkio.parking.application;

import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.ParkingSpotStatusHistoryRepository;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotRejection;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.RejectionReasonCode;
import com.parkio.parking.domain.RejectionSource;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time quiet migration that rejects legacy (pre-v3-recall) parking inventory with
 * {@link RejectionReasonCode#LEGACY_POLICY_RESET}. Does not emit owner-penalty events.
 */
@Service
public class LegacyPolicyResetApplicationService {

    public static final String DEFAULT_TARGET_POLICY = "2026-07-photo-policy-v3-recall";
    public static final String HISTORY_REASON = "LEGACY_POLICY_RESET";

    private static final Logger log = LoggerFactory.getLogger(LegacyPolicyResetApplicationService.class);

    private static final List<String> ELIGIBLE_STATUSES = List.of(
            "PENDING_VALIDATION", "PENDING_REVIEW", "ACTIVE", "VERIFIED", "SUSPICIOUS");

    private final ParkingSpotRepository spots;
    private final ParkingSpotStatusHistoryRepository history;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LegacyPolicyResetApplicationService(
            ParkingSpotRepository spots,
            ParkingSpotStatusHistoryRepository history,
            JdbcTemplate jdbc,
            Clock clock) {
        this.spots = Objects.requireNonNull(spots);
        this.history = Objects.requireNonNull(history);
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public LegacyPolicyResetReport dryRun(String targetPolicyVersion, int limit) {
        String target = normalizeTarget(targetPolicyVersion);
        Map<String, Long> byStatus = statusBreakdown(target);
        Map<String, Long> byPolicy = policyBreakdown(target);
        long eligible = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long skippedRejected = countSkippedAlreadyRejected();
        long skippedTerminal = countSkippedOtherTerminal();
        long skippedNewPolicy = countSkippedNewPolicy(target);
        long sample = Math.min(eligible, Math.max(0, limit));
        return new LegacyPolicyResetReport(
                true,
                target,
                eligible,
                0,
                0,
                skippedRejected,
                skippedTerminal,
                skippedNewPolicy,
                byStatus,
                byPolicy,
                sample);
    }

    @Transactional
    public LegacyPolicyResetReport execute(String targetPolicyVersion, int batchSize) {
        String target = normalizeTarget(targetPolicyVersion);
        LegacyPolicyResetReport preview = dryRun(target, 0);
        List<UUID> ids = findEligibleIds(target, Math.max(1, batchSize));
        int updated = 0;
        int failed = 0;
        Instant now = clock.instant();
        for (UUID id : ids) {
            try {
                if (resetOne(id, target, now)) {
                    updated++;
                }
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Legacy policy reset failed for spotId={}", id, ex);
            }
        }
        return new LegacyPolicyResetReport(
                false,
                target,
                preview.eligibleCount(),
                updated,
                failed,
                preview.skippedAlreadyRejected(),
                preview.skippedOtherTerminal(),
                preview.skippedNewPolicy(),
                preview.statusBreakdown(),
                preview.policyVersionBreakdown(),
                ids.size());
    }

    private boolean resetOne(UUID spotId, String targetPolicy, Instant now) {
        ParkingSpot spot = spots.findById(spotId).orElse(null);
        if (spot == null) {
            return false;
        }
        if (!isEligible(spot, targetPolicy)) {
            return false;
        }
        ParkingSpotStatus previous = spot.status();
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.LEGACY_POLICY_RESET,
                RejectionSource.SYSTEM_MIGRATION,
                now,
                null,
                targetPolicy);
        if (!spot.markRejectedBySystemMigration(now, rejection)) {
            return false;
        }
        ParkingSpot saved = spots.save(spot);
        history.save(ParkingSpotStatusHistory.record(saved.id(), previous, saved.status(), HISTORY_REASON, now));
        return true;
    }

    private boolean isEligible(ParkingSpot spot, String targetPolicy) {
        if (spot.status() == ParkingSpotStatus.REJECTED
                || spot.status() == ParkingSpotStatus.FILLED
                || spot.status() == ParkingSpotStatus.EXPIRED
                || spot.status() == ParkingSpotStatus.REVIEW_FAILED) {
            return false;
        }
        if (spot.rejection() != null
                && spot.rejection().code() == RejectionReasonCode.LEGACY_POLICY_RESET) {
            return false;
        }
        String last = spot.lastAiPolicyVersion();
        return last == null || !targetPolicy.equals(last);
    }

    private List<UUID> findEligibleIds(String target, int limit) {
        return jdbc.query(
                """
                SELECT id FROM parking_spots
                WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW', 'ACTIVE', 'VERIFIED', 'SUSPICIOUS')
                  AND (rejection_reason_code IS NULL OR rejection_reason_code <> 'LEGACY_POLICY_RESET')
                  AND (last_ai_policy_version IS NULL OR last_ai_policy_version <> ?)
                ORDER BY created_at
                LIMIT ?
                """,
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                target,
                limit);
    }

    private Map<String, Long> statusBreakdown(String target) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : ELIGIBLE_STATUSES) {
            Long count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM parking_spots
                    WHERE status = ?
                      AND (rejection_reason_code IS NULL OR rejection_reason_code <> 'LEGACY_POLICY_RESET')
                      AND (last_ai_policy_version IS NULL OR last_ai_policy_version <> ?)
                    """,
                    Long.class,
                    status,
                    target);
            result.put(status, count == null ? 0L : count);
        }
        return result;
    }

    private Map<String, Long> policyBreakdown(String target) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT COALESCE(last_ai_policy_version, '<null>') AS policy_version, COUNT(*) AS cnt
                FROM parking_spots
                WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW', 'ACTIVE', 'VERIFIED', 'SUSPICIOUS')
                  AND (rejection_reason_code IS NULL OR rejection_reason_code <> 'LEGACY_POLICY_RESET')
                  AND (last_ai_policy_version IS NULL OR last_ai_policy_version <> ?)
                GROUP BY COALESCE(last_ai_policy_version, '<null>')
                ORDER BY cnt DESC
                """,
                target);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(String.valueOf(row.get("policy_version")), ((Number) row.get("cnt")).longValue());
        }
        return result;
    }

    private long countSkippedAlreadyRejected() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM parking_spots WHERE status = 'REJECTED'",
                Long.class);
        return count == null ? 0L : count;
    }

    private long countSkippedOtherTerminal() {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM parking_spots
                WHERE status IN ('FILLED', 'EXPIRED', 'REVIEW_FAILED')
                """,
                Long.class);
        return count == null ? 0L : count;
    }

    private long countSkippedNewPolicy(String target) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM parking_spots
                WHERE status IN ('PENDING_VALIDATION', 'PENDING_REVIEW', 'ACTIVE', 'VERIFIED', 'SUSPICIOUS')
                  AND last_ai_policy_version = ?
                """,
                Long.class,
                target);
        return count == null ? 0L : count;
    }

    private static String normalizeTarget(String targetPolicyVersion) {
        if (targetPolicyVersion == null || targetPolicyVersion.isBlank()) {
            return DEFAULT_TARGET_POLICY;
        }
        return targetPolicyVersion.trim();
    }

    public record LegacyPolicyResetReport(
            boolean dryRun,
            String targetPolicyVersion,
            long eligibleCount,
            int updatedCount,
            int failedCount,
            long skippedAlreadyRejected,
            long skippedOtherTerminal,
            long skippedNewPolicy,
            Map<String, Long> statusBreakdown,
            Map<String, Long> policyVersionBreakdown,
            long consideredBatchSize) {
    }
}
