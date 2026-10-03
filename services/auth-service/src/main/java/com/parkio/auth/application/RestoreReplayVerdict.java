package com.parkio.auth.application;

import java.util.Map;
import java.util.UUID;

/**
 * Whether every configured participant acknowledged the replay of every user of a recovery
 * attempt. {@code missing} and {@code failed} count users per participant. A {@code COMPLETE}
 * verdict says nothing about exposure, which stage 4 decides; {@code verifiedCoverage} stays false.
 */
public record RestoreReplayVerdict(
        UUID recoveryAttemptId,
        Status status,
        int userCount,
        Map<String, Long> missing,
        Map<String, Long> failed,
        String reason) {

    public enum Status { COMPLETE, BLOCKED }

    public RestoreReplayVerdict {
        missing = Map.copyOf(missing);
        failed = Map.copyOf(failed);
    }

    static RestoreReplayVerdict complete(UUID recoveryAttemptId, int userCount) {
        return new RestoreReplayVerdict(recoveryAttemptId, Status.COMPLETE, userCount, Map.of(), Map.of(), null);
    }

    static RestoreReplayVerdict blocked(UUID recoveryAttemptId, int userCount, Map<String, Long> missing,
                                        Map<String, Long> failed, String reason) {
        return new RestoreReplayVerdict(recoveryAttemptId, Status.BLOCKED, userCount, missing, failed, reason);
    }
}
