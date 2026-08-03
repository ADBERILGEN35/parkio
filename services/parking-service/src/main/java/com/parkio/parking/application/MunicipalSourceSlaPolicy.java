package com.parkio.parking.application;

import com.parkio.parking.externalsource.MunicipalOccupancyFreshness;
import com.parkio.parking.externalsource.MunicipalSourceOperatingMode;
import com.parkio.parking.externalsource.MunicipalSourceOperationalState;
import com.parkio.parking.externalsource.MunicipalSyncRunStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Pure consecutive-failure and SLA calculation for municipal sources.
 * Occupancy freshness thresholds remain separate from operational SLA thresholds.
 *
 * <p>When mode-aware SLA is enabled, {@link MunicipalSourceOperatingMode#OPERATOR_IMPORTED}
 * sources do not become CRITICAL/DEGRADED from seconds-since-success alone. Failure streaks,
 * stale RUNNING, never-run, and recovery semantics still apply.
 */
public final class MunicipalSourceSlaPolicy {
    public static final int DEFAULT_HISTORY_BOUND = 100;

    private MunicipalSourceSlaPolicy() {}

    public record Thresholds(
            int warningConsecutiveFailures,
            int criticalConsecutiveFailures,
            long warningSecondsSinceSuccess,
            long criticalSecondsSinceSuccess,
            long staleRunningAfterSeconds,
            long recoveringWindowSeconds) {
        public Thresholds {
            if (warningConsecutiveFailures < 1) {
                throw new IllegalArgumentException("warningConsecutiveFailures must be >= 1");
            }
            if (criticalConsecutiveFailures < warningConsecutiveFailures) {
                throw new IllegalArgumentException(
                        "criticalConsecutiveFailures must be >= warningConsecutiveFailures");
            }
            if (warningSecondsSinceSuccess < 1 || criticalSecondsSinceSuccess < warningSecondsSinceSuccess) {
                throw new IllegalArgumentException("seconds-since-success thresholds invalid");
            }
            if (staleRunningAfterSeconds < 1 || recoveringWindowSeconds < 1) {
                throw new IllegalArgumentException("stale/recovering thresholds must be >= 1");
            }
        }

        public static Thresholds defaults() {
            return new Thresholds(3, 5, 600, 1800, 600, 900);
        }
    }

    public record CompletedRun(String status, String errorCategory, Instant startedAt, Instant completedAt) {}

    public record Evaluation(
            int consecutiveFailures,
            String lastRunStatus,
            Instant lastRunAt,
            Instant lastSuccessAt,
            long secondsSinceSuccess,
            String lastFailureCategory,
            int failuresInWindow,
            int staleRunningOperations,
            MunicipalSourceOperationalState operationalState,
            boolean recovered) {}

    /**
     * Counts trailing FAILED runs newest-first. SKIPPED does not count and does not break
     * the streak. SUCCESS / PARTIAL_SUCCESS reset. RUNNING must not be included in {@code runs}.
     */
    public static int consecutiveFailures(List<CompletedRun> runsNewestFirst) {
        int count = 0;
        for (CompletedRun run : runsNewestFirst) {
            MunicipalSyncRunStatus status = parseStatus(run.status());
            if (status == MunicipalSyncRunStatus.SUCCESS
                    || status == MunicipalSyncRunStatus.PARTIAL_SUCCESS) {
                break;
            }
            if (status == MunicipalSyncRunStatus.FAILED) {
                count++;
            }
            // SKIPPED / unknown completed statuses are ignored for streak purposes.
        }
        return count;
    }

    /**
     * Legacy evaluation: seconds-since-success thresholds always apply (DATA-WP-06).
     * Prefer {@link #evaluate(boolean, boolean, boolean, MunicipalSourceOperatingMode, boolean,
     * List, Instant, int, int, Instant, Thresholds)} when mode-aware SLA is wired.
     */
    public static Evaluation evaluate(
            boolean municipalEnabled,
            boolean sourceEnabled,
            boolean schedulerEnabled,
            List<CompletedRun> runsNewestFirst,
            Instant lastSuccessAt,
            int failuresInWindow,
            int staleRunningOperations,
            Instant now,
            Thresholds thresholds) {
        return evaluate(
                municipalEnabled,
                sourceEnabled,
                schedulerEnabled,
                MunicipalSourceOperatingMode.SCHEDULED,
                false,
                runsNewestFirst,
                lastSuccessAt,
                failuresInWindow,
                staleRunningOperations,
                now,
                thresholds);
    }

    public static Evaluation evaluate(
            boolean municipalEnabled,
            boolean sourceEnabled,
            boolean schedulerEnabled,
            MunicipalSourceOperatingMode operatingMode,
            boolean sourceModeSlaEnabled,
            List<CompletedRun> runsNewestFirst,
            Instant lastSuccessAt,
            int failuresInWindow,
            int staleRunningOperations,
            Instant now,
            Thresholds thresholds) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(operatingMode, "operatingMode");

        if (!municipalEnabled || !sourceEnabled) {
            return disabled(lastSuccessAt, now);
        }

        int consecutive = consecutiveFailures(runsNewestFirst);
        CompletedRun latest = runsNewestFirst.isEmpty() ? null : runsNewestFirst.get(0);
        String lastRunStatus = latest == null ? null : latest.status();
        Instant lastRunAt = latest == null
                ? null
                : (latest.completedAt() != null ? latest.completedAt() : latest.startedAt());
        String lastFailureCategory = latestFailureCategory(runsNewestFirst);
        long secondsSinceSuccess = lastSuccessAt == null
                ? Long.MAX_VALUE
                : Math.max(0, Duration.between(lastSuccessAt, now).getSeconds());

        boolean recovered = consecutive == 0
                && lastSuccessAt != null
                && latest != null
                && isSuccess(latest.status())
                && hadPriorFailure(runsNewestFirst);

        boolean applyAge = MunicipalSourceOperatingModePolicy.applySecondsSinceSuccessThresholds(
                operatingMode, sourceModeSlaEnabled);

        MunicipalSourceOperationalState state;
        if (staleRunningOperations > 0) {
            state = MunicipalSourceOperationalState.STALE_OPERATION;
        } else if (lastSuccessAt == null && consecutive == 0 && latest == null) {
            state = MunicipalSourceOperationalState.NEVER_RUN;
        } else if (consecutive >= thresholds.criticalConsecutiveFailures()
                || (applyAge && secondsSinceSuccess >= thresholds.criticalSecondsSinceSuccess())) {
            state = MunicipalSourceOperationalState.CRITICAL;
        } else if (consecutive >= thresholds.warningConsecutiveFailures()
                || (applyAge && secondsSinceSuccess >= thresholds.warningSecondsSinceSuccess())) {
            state = MunicipalSourceOperationalState.DEGRADED;
        } else if (recovered && secondsSinceSuccess <= thresholds.recoveringWindowSeconds()) {
            state = MunicipalSourceOperationalState.RECOVERING;
        } else if (lastSuccessAt != null && consecutive == 0) {
            state = MunicipalSourceOperationalState.HEALTHY;
        } else if (consecutive > 0) {
            state = MunicipalSourceOperationalState.DEGRADED;
        } else {
            state = MunicipalSourceOperationalState.UNKNOWN;
        }

        // Scheduler disabled is still observable but not alerted by Prometheus (expr gates).
        // Keep computed state; DISABLED only when source itself is off.
        // Mode is never inferred from schedulerEnabled — a temporarily paused SCHEDULED
        // source must retain age-based SLA when source-mode SLA is enabled.
        if (!schedulerEnabled && state == MunicipalSourceOperationalState.NEVER_RUN) {
            // Enabled source with scheduler off and no runs remains NEVER_RUN.
        }

        return new Evaluation(
                consecutive,
                lastRunStatus,
                lastRunAt,
                lastSuccessAt,
                secondsSinceSuccess == Long.MAX_VALUE ? -1 : secondsSinceSuccess,
                lastFailureCategory,
                failuresInWindow,
                staleRunningOperations,
                state,
                recovered);
    }

    public static MunicipalOccupancyFreshness occupancyFreshness(
            Instant lastSuccessAt,
            Instant now,
            long agingAfterSeconds,
            long staleAfterSeconds) {
        if (lastSuccessAt == null) {
            return MunicipalOccupancyFreshness.UNAVAILABLE;
        }
        long age = Math.max(0, Duration.between(lastSuccessAt, now).getSeconds());
        if (age >= staleAfterSeconds) {
            return MunicipalOccupancyFreshness.STALE;
        }
        if (age >= agingAfterSeconds) {
            return MunicipalOccupancyFreshness.AGING;
        }
        return MunicipalOccupancyFreshness.LIVE;
    }

    private static Evaluation disabled(Instant lastSuccessAt, Instant now) {
        long seconds = lastSuccessAt == null
                ? -1
                : Math.max(0, Duration.between(lastSuccessAt, now).getSeconds());
        return new Evaluation(
                0, null, null, lastSuccessAt, seconds, null, 0, 0,
                MunicipalSourceOperationalState.DISABLED, false);
    }

    private static String latestFailureCategory(List<CompletedRun> runsNewestFirst) {
        for (CompletedRun run : runsNewestFirst) {
            if (parseStatus(run.status()) == MunicipalSyncRunStatus.FAILED) {
                return run.errorCategory();
            }
        }
        return null;
    }

    private static boolean hadPriorFailure(List<CompletedRun> runsNewestFirst) {
        boolean sawSuccess = false;
        for (CompletedRun run : runsNewestFirst) {
            MunicipalSyncRunStatus status = parseStatus(run.status());
            if (!sawSuccess && isSuccess(run.status())) {
                sawSuccess = true;
                continue;
            }
            if (sawSuccess && status == MunicipalSyncRunStatus.FAILED) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSuccess(String status) {
        MunicipalSyncRunStatus parsed = parseStatus(status);
        return parsed == MunicipalSyncRunStatus.SUCCESS
                || parsed == MunicipalSyncRunStatus.PARTIAL_SUCCESS;
    }

    private static MunicipalSyncRunStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return MunicipalSyncRunStatus.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
