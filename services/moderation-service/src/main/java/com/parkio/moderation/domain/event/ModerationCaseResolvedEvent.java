package com.parkio.moderation.domain.event;

import com.parkio.moderation.domain.ModerationCase;
import java.time.Instant;
import java.util.UUID;

/**
 * Emitted when a case is resolved. Carries {@code targetType}/{@code targetId}, the
 * {@code action} and the case {@code reason} so consumers (gamification, user,
 * parking, notification) can react — gamification uses {@code action}+{@code reason}
 * to apply the admin REDUCE_TRUST / DEDUCT_POINTS penalties. {@code reason} is an
 * additive field (event-contracts.md rule 2); older consumers ignore it.
 */
public record ModerationCaseResolvedEvent(
        UUID eventId,
        UUID caseId,
        String targetType,
        UUID targetId,
        String action,
        String reason,
        UUID moderatorId,
        Instant occurredAt) implements ModerationEvent {

    public static final String TYPE = "ModerationCaseResolved";
    public static final String AGGREGATE_TYPE = "ModerationCase";

    public static ModerationCaseResolvedEvent of(ModerationCase moderationCase, UUID moderatorId, Instant occurredAt) {
        return new ModerationCaseResolvedEvent(UUID.randomUUID(), moderationCase.id(),
                moderationCase.targetType().name(), moderationCase.targetId(),
                moderationCase.resolutionAction().name(), moderationCase.reason().name(),
                moderatorId, occurredAt);
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
