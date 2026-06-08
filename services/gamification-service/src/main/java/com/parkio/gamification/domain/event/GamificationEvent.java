package com.parkio.gamification.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape for gamification domain events written to the transactional outbox.
 * The outbox adapter reads {@link #aggregateType()}, {@link #aggregateId()} and
 * {@link #eventType()} and serializes the concrete event as the payload
 * (ai-context/06). Sealed so the set of events is closed and explicit.
 */
public sealed interface GamificationEvent permits
        PointsEarnedEvent,
        PointsDeductedEvent,
        UserLevelChangedEvent,
        ContributionScoreUpdatedEvent {

    String AGGREGATE_TYPE = "GamificationUser";

    UUID eventId();

    UUID aggregateId();

    String eventType();

    Instant occurredAt();

    default String aggregateType() {
        return AGGREGATE_TYPE;
    }
}
