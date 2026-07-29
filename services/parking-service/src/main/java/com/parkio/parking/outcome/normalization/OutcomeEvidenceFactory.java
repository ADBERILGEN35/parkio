package com.parkio.parking.outcome.normalization;

import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.util.List;
import java.util.Objects;

public final class OutcomeEvidenceFactory {

    private OutcomeEvidenceFactory() {}

    public static OutcomeEvidence fromContext(ParkingSpotOutcomeContext context, OutcomeTimeline timeline) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(timeline, "timeline");
        return new OutcomeEvidence(
                context.parkingSpotId(),
                context.status(),
                context.createdAt(),
                context.activatedAt(),
                context.expiresAt(),
                context.updatedAt(),
                context.verificationCount(),
                context.filledReportCount(),
                context.confidenceScore(),
                timeline);
    }

    public static OutcomeEvidence fromContextWithHistory(
            ParkingSpotOutcomeContext context,
            List<ParkingSpotStatusHistory> history) {
        OutcomeTimeline timeline = OutcomeTimelineFactory.fromStatusHistory(
                context.activatedAt(), context.expiresAt(), history);
        return fromContext(context, timeline);
    }

    public static OutcomeEvidence fromAggregateSnapshot(ParkingSpotOutcomeContext context) {
        OutcomeTimeline timeline = OutcomeTimelineFactory.fromAggregateSnapshot(
                context.activatedAt(),
                context.expiresAt(),
                context.updatedAt(),
                context.verificationCount(),
                context.filledReportCount());
        return fromContext(context, timeline);
    }
}