package com.parkio.parking.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Configurable stale-session policy for ACTIVE parking sessions.
 *
 * <p>After confirmAfter without a fresh confirmation the client asks whether the user is
 * still parked (and reminder stage FIRST may fire). After reminder2After reminder stage
 * SECOND may fire. After autoCompleteAfter the scheduler completes the session.
 */
public final class ParkingSessionStalePolicy {

    private final Duration confirmAfter;
    private final Duration reminder2After;
    private final Duration autoCompleteAfter;

    public ParkingSessionStalePolicy(
            Duration confirmAfter, Duration reminder2After, Duration autoCompleteAfter) {
        this.confirmAfter = requirePositive(confirmAfter, "confirmAfter");
        this.reminder2After = requirePositive(reminder2After, "reminder2After");
        this.autoCompleteAfter = requirePositive(autoCompleteAfter, "autoCompleteAfter");
        if (this.reminder2After.compareTo(this.confirmAfter) <= 0) {
            throw new IllegalArgumentException("reminder2After must be > confirmAfter");
        }
        if (this.autoCompleteAfter.compareTo(this.reminder2After) <= 0) {
            throw new IllegalArgumentException("autoCompleteAfter must be > reminder2After");
        }
    }

    /** Production defaults: 24h / 48h / 72h. Prefer injecting via configuration. */
    public static ParkingSessionStalePolicy defaults() {
        return new ParkingSessionStalePolicy(
                Duration.ofHours(24), Duration.ofHours(48), Duration.ofHours(72));
    }

    public Duration confirmAfter() {
        return confirmAfter;
    }

    public Duration reminder2After() {
        return reminder2After;
    }

    public Duration autoCompleteAfter() {
        return autoCompleteAfter;
    }

    public Instant confirmationAnchor(ParkingSession session) {
        Objects.requireNonNull(session, "session");
        Instant confirmed = session.getLastConfirmedAt();
        return confirmed != null ? confirmed : session.getStartedAt();
    }

    public boolean needsConfirmation(ParkingSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (!session.isActive()) {
            return false;
        }
        Instant threshold = now.minus(confirmAfter);
        return !confirmationAnchor(session).isAfter(threshold);
    }

    public boolean isEligibleForAutoComplete(ParkingSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (!session.isActive()) {
            return false;
        }
        Instant threshold = now.minus(autoCompleteAfter);
        return !session.getStartedAt().isAfter(threshold)
                && !confirmationAnchor(session).isAfter(threshold);
    }

    /**
     * Next reminder stage to send, or empty if none is due / already sent.
     * Stages advance one at a time so FIRST is never skipped silently when both
     * thresholds are overdue in a single evaluation.
     */
    public Optional<ParkingSessionReminderStage> nextReminderStage(
            ParkingSession session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        if (!session.isActive()) {
            return Optional.empty();
        }
        Instant anchor = confirmationAnchor(session);
        ParkingSessionReminderStage current = session.getReminderStage();
        if (!current.hasReached(ParkingSessionReminderStage.FIRST)
                && !anchor.isAfter(now.minus(confirmAfter))) {
            return Optional.of(ParkingSessionReminderStage.FIRST);
        }
        if (!current.hasReached(ParkingSessionReminderStage.SECOND)
                && !anchor.isAfter(now.minus(reminder2After))
                && !session.getStartedAt().isAfter(now.minus(reminder2After))) {
            return Optional.of(ParkingSessionReminderStage.SECOND);
        }
        return Optional.empty();
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}