package com.parkio.parking.outcome.normalization;

import com.parkio.parking.outcome.normalization.OutcomeSpotSnapshotData;
import com.parkio.parking.outcome.normalization.OutcomeVerificationSignalData;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.ParkingSpotStatusHistory;
import com.parkio.parking.domain.VerificationResult;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.signal.OutcomeSignal;
import com.parkio.parking.outcome.signal.OutcomeSignalSource;
import com.parkio.parking.outcome.signal.OutcomeSignalType;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Reconstructs canonical outcome evidence from immutable repository facts up to a cutoff. */
public final class OutcomeHistoricalEvidenceFactory {

    private static final Duration FIRST_VERIFICATION_DURATION = Duration.ofMinutes(15);
    private static final Duration MULTI_VERIFICATION_DURATION = Duration.ofMinutes(20);
    private static final double INITIAL_CONFIDENCE = 1.0;
    private static final double CONFIDENCE_PENALTY = 0.4;
    private static final double SUSPICIOUS_CONFIDENCE_THRESHOLD = 0.5;

    private OutcomeHistoricalEvidenceFactory() {}

    public static OutcomeEvidence create(
            OutcomeSpotSnapshotData spot,
            Duration activeDuration,
            List<ParkingSpotStatusHistory> statusHistory,
            List<OutcomeVerificationSignalData> verifications) {
        Objects.requireNonNull(spot, "spot");
        Objects.requireNonNull(activeDuration, "activeDuration");
        Objects.requireNonNull(statusHistory, "statusHistory");
        Objects.requireNonNull(verifications, "verifications");

        MutableState state = new MutableState(spot.createdAt());
        List<TimedFact> facts = new ArrayList<>();
        for (ParkingSpotStatusHistory history : statusHistory) {
            if (!isVerificationReason(history.reason())) {
                facts.add(TimedFact.fromHistory(history));
            }
        }
        for (OutcomeVerificationSignalData verification : verifications) {
            facts.add(TimedFact.fromVerification(verification));
        }
        facts.sort(Comparator.comparing(TimedFact::occurredAt).thenComparing(TimedFact::stableId));

        List<OutcomeSignal> signals = new ArrayList<>();
        for (TimedFact fact : facts) {
            switch (fact.kind()) {
                case STATUS_HISTORY -> applyHistory(state, activeDuration, fact.history(), signals);
                case VERIFICATION -> applyVerification(state, fact.verification(), signals);
            }
        }

        OutcomeTimeline timeline = OutcomeTimeline.of(state.activatedAt, state.expiresAt, signals);
        return new OutcomeEvidence(
                spot.parkingSpotId(),
                state.status,
                spot.createdAt(),
                state.activatedAt,
                state.expiresAt,
                state.updatedAt,
                state.verificationCount,
                state.filledReportCount,
                state.confidenceScore,
                timeline);
    }

    private static void applyHistory(
            MutableState state,
            Duration activeDuration,
            ParkingSpotStatusHistory history,
            List<OutcomeSignal> signals) {
        String reason = history.reason();
        Instant occurredAt = history.createdAt();
        if ("AI_PASSED".equals(reason) || "MODERATOR_APPROVED".equals(reason)) {
            state.status = ParkingSpotStatus.ACTIVE;
            if (state.activatedAt == null) {
                state.activatedAt = occurredAt;
            }
            if (state.expiresAt == null) {
                state.expiresAt = occurredAt.plus(activeDuration);
            }
            signals.add(new OutcomeSignal(
                    "MODERATOR_APPROVED".equals(reason) ? OutcomeSignalType.MODERATOR_APPROVED : OutcomeSignalType.AI_PUBLISHED,
                    OutcomeSignalSource.SYSTEM,
                    occurredAt));
        } else if ("CLAIMED".equals(reason)) {
            state.status = ParkingSpotStatus.FILLED;
            signals.add(new OutcomeSignal(OutcomeSignalType.COMMUNITY_CLAIM, OutcomeSignalSource.COMMUNITY, occurredAt));
        } else if ("MODERATOR_REJECTED".equals(reason)) {
            state.status = ParkingSpotStatus.REJECTED;
            signals.add(new OutcomeSignal(OutcomeSignalType.MODERATOR_REJECTED, OutcomeSignalSource.MODERATOR, occurredAt));
        } else if ("EXPIRED".equals(reason)) {
            state.status = ParkingSpotStatus.EXPIRED;
            signals.add(new OutcomeSignal(OutcomeSignalType.TIME_EXPIRED, OutcomeSignalSource.SYSTEM, occurredAt));
        } else if (reason != null && reason.startsWith("REVIEW_FAILED")) {
            state.status = ParkingSpotStatus.REVIEW_FAILED;
        } else if ("AI_PENDING_REVIEW".equals(reason)) {
            state.status = ParkingSpotStatus.PENDING_REVIEW;
            signals.add(new OutcomeSignal(OutcomeSignalType.AI_PENDING_REVIEW, OutcomeSignalSource.SYSTEM, occurredAt));
        } else if ("AI_REJECTED".equals(reason)) {
            state.status = ParkingSpotStatus.REJECTED;
            signals.add(new OutcomeSignal(OutcomeSignalType.AI_REJECTED, OutcomeSignalSource.SYSTEM, occurredAt));
        } else {
            state.status = history.newStatus();
        }
        state.updatedAt = occurredAt;
    }

    private static void applyVerification(
            MutableState state,
            OutcomeVerificationSignalData verification,
            List<OutcomeSignal> signals) {
        Instant occurredAt = verification.createdAt();
        VerificationResult result = verification.result();
        switch (result) {
            case AVAILABLE -> {
                state.verificationCount += 1;
                if (state.status == ParkingSpotStatus.ACTIVE
                        || state.status == ParkingSpotStatus.VERIFIED
                        || state.status == ParkingSpotStatus.SUSPICIOUS) {
                    state.status = ParkingSpotStatus.VERIFIED;
                }
                Duration extension = state.verificationCount >= 2 ? MULTI_VERIFICATION_DURATION : FIRST_VERIFICATION_DURATION;
                Instant candidate = occurredAt.plus(extension);
                if (state.expiresAt == null || candidate.isAfter(state.expiresAt)) {
                    state.expiresAt = candidate;
                }
                signals.add(new OutcomeSignal(OutcomeSignalType.VERIFICATION_AVAILABLE, OutcomeSignalSource.COMMUNITY, occurredAt));
            }
            case FILLED -> {
                state.filledReportCount += 1;
                state.status = state.filledReportCount >= 2 ? ParkingSpotStatus.FILLED : ParkingSpotStatus.SUSPICIOUS;
                signals.add(new OutcomeSignal(OutcomeSignalType.VERIFICATION_FILLED, OutcomeSignalSource.COMMUNITY, occurredAt));
            }
            case ILLEGAL_OR_RISKY -> {
                state.confidenceScore = Math.max(0.0, state.confidenceScore - CONFIDENCE_PENALTY);
                state.status = ParkingSpotStatus.SUSPICIOUS;
                signals.add(new OutcomeSignal(OutcomeSignalType.VERIFICATION_ILLEGAL_OR_RISKY, OutcomeSignalSource.COMMUNITY, occurredAt));
            }
            case WRONG_VEHICLE_SIZE, INVALID -> {
                state.confidenceScore = Math.max(0.0, state.confidenceScore - CONFIDENCE_PENALTY);
                if (state.confidenceScore < SUSPICIOUS_CONFIDENCE_THRESHOLD
                        && state.status != ParkingSpotStatus.FILLED
                        && state.status != ParkingSpotStatus.REJECTED) {
                    state.status = ParkingSpotStatus.SUSPICIOUS;
                }
                OutcomeSignalType type = result == VerificationResult.INVALID
                        ? OutcomeSignalType.VERIFICATION_INVALID
                        : OutcomeSignalType.VERIFICATION_WRONG_VEHICLE_SIZE;
                signals.add(new OutcomeSignal(type, OutcomeSignalSource.COMMUNITY, occurredAt));
            }
        }
        state.updatedAt = occurredAt;
    }

    private static boolean isVerificationReason(String reason) {
        return reason != null && reason.startsWith("VERIFICATION_");
    }

    private record TimedFact(Kind kind, Instant occurredAt, String stableId,
                             ParkingSpotStatusHistory history, OutcomeVerificationSignalData verification) {
        static TimedFact fromHistory(ParkingSpotStatusHistory history) {
            return new TimedFact(Kind.STATUS_HISTORY, history.createdAt(), history.id().toString(), history, null);
        }

        static TimedFact fromVerification(OutcomeVerificationSignalData verification) {
            return new TimedFact(Kind.VERIFICATION, verification.createdAt(), verification.verificationId().toString(), null, verification);
        }
    }

    private enum Kind {
        STATUS_HISTORY,
        VERIFICATION
    }

    private static final class MutableState {
        private ParkingSpotStatus status = ParkingSpotStatus.PENDING_VALIDATION;
        private final Instant createdAt;
        private Instant updatedAt;
        private Instant activatedAt;
        private Instant expiresAt;
        private int verificationCount;
        private int filledReportCount;
        private double confidenceScore = INITIAL_CONFIDENCE;

        private MutableState(Instant createdAt) {
            this.createdAt = createdAt;
            this.updatedAt = createdAt;
        }
    }
}