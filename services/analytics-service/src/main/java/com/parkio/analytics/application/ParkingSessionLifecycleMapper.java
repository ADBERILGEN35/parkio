package com.parkio.analytics.application;

import com.parkio.analytics.domain.AnalyticsMetricType;
import com.parkio.analytics.domain.exception.AnalyticsContractException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * Maps ParkingSession lifecycle wire types to canonical product analytics names and
 * internal {@link AnalyticsMetricType} values. Source is encoded in the started metric
 * so counters distinguish MANUAL vs COMMUNITY without a schema migration.
 */
public final class ParkingSessionLifecycleMapper {

    public static final String WIRE_STARTED = "ParkingSessionStarted";
    public static final String WIRE_COMPLETED = "ParkingSessionCompleted";
    public static final String WIRE_CANCELLED = "ParkingSessionCancelled";
    public static final String WIRE_HISTORY_DELETED = "ParkingHistoryDeleted";

    public static final String CANONICAL_STARTED = "parking_session_started";
    public static final String CANONICAL_COMPLETED = "parking_session_completed";
    public static final String CANONICAL_CANCELLED = "parking_session_cancelled";
    public static final String CANONICAL_HISTORY_DELETED = "parking_session_history_deleted";

    public static final String AGGREGATE_TYPE = "ParkingSession";

    private static final Set<String> SUPPORTED_SOURCES = Set.of(
            "MANUAL", "FACILITY", "CURB", "COMMUNITY", "AUTO");

    private ParkingSessionLifecycleMapper() {
    }

    public static String canonicalName(String wireEventType) {
        return switch (wireEventType) {
            case WIRE_STARTED -> CANONICAL_STARTED;
            case WIRE_COMPLETED -> CANONICAL_COMPLETED;
            case WIRE_CANCELLED -> CANONICAL_CANCELLED;
            case WIRE_HISTORY_DELETED -> CANONICAL_HISTORY_DELETED;
            default -> throw new AnalyticsContractException(
                    "Unsupported ParkingSession event type: " + wireEventType);
        };
    }

    public static AnalyticsMetricType startedMetric(String source) {
        String normalized = requireSource(source);
        return switch (normalized) {
            case "MANUAL" -> AnalyticsMetricType.PARKING_SESSION_STARTED_MANUAL;
            case "COMMUNITY" -> AnalyticsMetricType.PARKING_SESSION_STARTED_COMMUNITY;
            default -> AnalyticsMetricType.PARKING_SESSION_STARTED_OTHER;
        };
    }

    public static String requireSource(String source) {
        if (source == null || source.isBlank()) {
            throw new AnalyticsContractException("source is required");
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_SOURCES.contains(normalized)) {
            throw new AnalyticsContractException("Unsupported parking source: " + source);
        }
        return normalized;
    }

    /**
     * Duration in whole seconds derived from producer timestamps. Rejects negatives.
     * Zero is allowed. Uses {@link Duration#getSeconds()} (truncates toward zero).
     */
    public static long durationSeconds(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            throw new AnalyticsContractException("startedAt and endedAt are required for duration");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new AnalyticsContractException("endedAt must not be before startedAt");
        }
        return Duration.between(startedAt, endedAt).getSeconds();
    }
}