package com.parkio.parking.decision.outcome;

import com.parkio.parking.decision.evidence.EvidenceSource;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable observation of a ParkingSpot outcome event.
 *
 * <p>Does not update Trust, Availability, or Rewards.
 */
public final class SpotOutcome {

    private final UUID parkingSpotId;
    private final SpotOutcomeType type;
    private final EvidenceSource source;
    private final UUID actorUserId;
    private final Instant occurredAt;
    private final Integer confidence;

    private SpotOutcome(
            UUID parkingSpotId,
            SpotOutcomeType type,
            EvidenceSource source,
            UUID actorUserId,
            Instant occurredAt,
            Integer confidence) {
        this.parkingSpotId = Objects.requireNonNull(parkingSpotId, "parkingSpotId");
        this.type = Objects.requireNonNull(type, "type");
        this.source = Objects.requireNonNull(source, "source");
        this.actorUserId = actorUserId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        if (confidence != null && (confidence < 0 || confidence > 100)) {
            throw new IllegalArgumentException("confidence must be between 0 and 100 inclusive when present");
        }
        this.confidence = confidence;
    }

    public static SpotOutcome of(
            UUID parkingSpotId,
            SpotOutcomeType type,
            EvidenceSource source,
            Instant occurredAt) {
        return new SpotOutcome(parkingSpotId, type, source, null, occurredAt, null);
    }

    public static SpotOutcome of(
            UUID parkingSpotId,
            SpotOutcomeType type,
            EvidenceSource source,
            UUID actorUserId,
            Instant occurredAt,
            Integer confidence) {
        return new SpotOutcome(parkingSpotId, type, source, actorUserId, occurredAt, confidence);
    }

    public UUID parkingSpotId() {
        return parkingSpotId;
    }

    public SpotOutcomeType type() {
        return type;
    }

    public EvidenceSource source() {
        return source;
    }

    public Optional<UUID> actorUserId() {
        return Optional.ofNullable(actorUserId);
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Optional<Integer> confidence() {
        return Optional.ofNullable(confidence);
    }
}