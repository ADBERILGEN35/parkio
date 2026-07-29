package com.parkio.parking.outcome.normalization;

import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Builds outcome timelines from repository-backed history rows or aggregate snapshots. */
public final class OutcomeTimelineFactory {

    private OutcomeTimelineFactory() {}

    public static OutcomeTimeline fromStatusHistory(
            Instant publishedAt,
            Instant validationWindowEnd,
            List<ParkingSpotStatusHistory> history) {
        Objects.requireNonNull(history, "history");
        List<OutcomeSignal> signals = new ArrayList<>();
        if (publishedAt != null) {
            signals.add(new OutcomeSignal(OutcomeSignalType.PUBLISHED, OutcomeSignalSource.SYSTEM, publishedAt));
        }
        history.stream()
                .sorted(Comparator.comparing(ParkingSpotStatusHistory::createdAt))
                .forEach(row -> mapReason(row.reason(), row.createdAt()).ifPresent(signals::add));
        return OutcomeTimeline.of(publishedAt, validationWindowEnd, signals);
    }

    public static OutcomeTimeline fromAggregateSnapshot(
            Instant publishedAt,
            Instant validationWindowEnd,
            Instant updatedAt,
            int verificationCount,
            int filledReportCount) {
        List<OutcomeSignal> signals = new ArrayList<>();
        if (publishedAt != null) {
            signals.add(new OutcomeSignal(OutcomeSignalType.PUBLISHED, OutcomeSignalSource.SYSTEM, publishedAt));
        }
        for (int i = 0; i < verificationCount; i++) {
            signals.add(new OutcomeSignal(
                    OutcomeSignalType.VERIFICATION_AVAILABLE,
                    OutcomeSignalSource.COMMUNITY,
                    updatedAt != null ? updatedAt : publishedAt));
        }
        for (int i = 0; i < filledReportCount; i++) {
            signals.add(new OutcomeSignal(
                    OutcomeSignalType.VERIFICATION_FILLED,
                    OutcomeSignalSource.COMMUNITY,
                    updatedAt != null ? updatedAt : publishedAt));
        }
        return OutcomeTimeline.of(publishedAt, validationWindowEnd, signals);
    }

    public static Optional<OutcomeSignal> mapReason(String reason, Instant occurredAt) {
        if (reason == null || occurredAt == null) {
            return Optional.empty();
        }
        return switch (reason) {
            case "AI_PASSED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.AI_PUBLISHED, OutcomeSignalSource.AI, occurredAt));
            case "AI_REJECTED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.AI_REJECTED, OutcomeSignalSource.AI, occurredAt));
            case "AI_PENDING_REVIEW" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.AI_PENDING_REVIEW, OutcomeSignalSource.AI, occurredAt));
            case "MODERATOR_APPROVED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.MODERATOR_APPROVED, OutcomeSignalSource.MODERATOR, occurredAt));
            case "MODERATOR_REJECTED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.MODERATOR_REJECTED, OutcomeSignalSource.MODERATOR, occurredAt));
            case "CLAIMED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.COMMUNITY_CLAIM, OutcomeSignalSource.COMMUNITY, occurredAt));
            case "EXPIRED" -> Optional.of(new OutcomeSignal(
                    OutcomeSignalType.TIME_EXPIRED, OutcomeSignalSource.SYSTEM, occurredAt));
            case String r when r.startsWith("VERIFICATION_") -> Optional.of(mapVerificationReason(r, occurredAt));
            default -> Optional.empty();
        };
    }

    private static OutcomeSignal mapVerificationReason(String reason, Instant occurredAt) {
        OutcomeSignalType type = switch (reason) {
            case "VERIFICATION_AVAILABLE" -> OutcomeSignalType.VERIFICATION_AVAILABLE;
            case "VERIFICATION_FILLED" -> OutcomeSignalType.VERIFICATION_FILLED;
            case "VERIFICATION_INVALID" -> OutcomeSignalType.VERIFICATION_INVALID;
            case "VERIFICATION_ILLEGAL_OR_RISKY" -> OutcomeSignalType.VERIFICATION_ILLEGAL_OR_RISKY;
            case "VERIFICATION_WRONG_VEHICLE_SIZE" -> OutcomeSignalType.VERIFICATION_WRONG_VEHICLE_SIZE;
            default -> OutcomeSignalType.VERIFICATION_INVALID;
        };
        return new OutcomeSignal(type, OutcomeSignalSource.COMMUNITY, occurredAt);
    }
}