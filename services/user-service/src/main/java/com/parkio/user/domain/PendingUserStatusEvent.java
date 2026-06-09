package com.parkio.user.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A moderation status event ({@code UserSuspended}/{@code UserRestored}) that arrived
 * before the user's profile was provisioned from {@code UserRegistered}. Parked here so
 * it is never lost (different Kafka topics give no cross-topic ordering guarantee); when
 * the profile is created, the latest pending event by {@code occurredAt} is applied and
 * the user's pending rows are removed.
 *
 * <p>{@code id} is the moderation event's {@code eventId}, giving natural dedup on
 * redelivery. Pure domain: no framework dependencies.
 */
public record PendingUserStatusEvent(
        UUID id,
        UUID authUserId,
        UserStatus targetStatus,
        Instant occurredAt,
        UUID caseId,
        Instant recordedAt) {

    public PendingUserStatusEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(authUserId, "authUserId");
        Objects.requireNonNull(targetStatus, "targetStatus");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    public static PendingUserStatusEvent of(UUID eventId,
                                            UUID authUserId,
                                            UserStatus targetStatus,
                                            Instant occurredAt,
                                            UUID caseId,
                                            Instant recordedAt) {
        return new PendingUserStatusEvent(eventId, authUserId, targetStatus, occurredAt, caseId, recordedAt);
    }
}
