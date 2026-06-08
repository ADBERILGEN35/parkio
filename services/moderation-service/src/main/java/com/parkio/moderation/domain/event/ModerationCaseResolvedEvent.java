package com.parkio.moderation.domain.event;

import com.parkio.moderation.domain.ModerationCase;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a case is resolved. Carries {@code targetType}/{@code targetId} and
 * the {@code action} so consumers (gamification, user, parking) can react.
 */
public record ModerationCaseResolvedEvent(
        UUID eventId,
        UUID caseId,
        String targetType,
        UUID targetId,
        String action,
        UUID moderatorId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ModerationCaseResolved";
    public static final String AGGREGATE_TYPE = "ModerationCase";

    public static ModerationCaseResolvedEvent of(ModerationCase moderationCase, UUID moderatorId, Instant occurredAt) {
        return new ModerationCaseResolvedEvent(UUID.randomUUID(), moderationCase.id(),
                moderationCase.targetType().name(), moderationCase.targetId(),
                moderationCase.resolutionAction().name(), moderatorId, occurredAt);
    }

    @Override
    public String aggregateType() {
        return AGGREGATE_TYPE;
    }

    @Override
    public UUID aggregateId() {
        return caseId;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
