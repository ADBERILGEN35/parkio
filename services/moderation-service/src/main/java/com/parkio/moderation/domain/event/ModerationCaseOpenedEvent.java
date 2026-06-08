package com.parkio.moderation.domain.event;

import com.parkio.moderation.domain.ModerationCase;
import java.time.Instant;
import java.util.UUID;

/** Emitted when a moderation case is opened. */
public record ModerationCaseOpenedEvent(
        UUID eventId,
        UUID caseId,
        String targetType,
        UUID targetId,
        String reason,
        String severity,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ModerationCaseOpened";
    public static final String AGGREGATE_TYPE = "ModerationCase";

    public static ModerationCaseOpenedEvent of(ModerationCase moderationCase, Instant occurredAt) {
        return new ModerationCaseOpenedEvent(UUID.randomUUID(), moderationCase.id(),
                moderationCase.targetType().name(), moderationCase.targetId(),
                moderationCase.reason().name(), moderationCase.severity().name(), occurredAt);
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
