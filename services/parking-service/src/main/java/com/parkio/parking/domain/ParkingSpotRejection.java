package com.parkio.parking.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Structured rejection metadata persisted on a parking spot when status becomes
 * {@link ParkingSpotStatus#REJECTED}.
 *
 * <p>{@code message} is an immutable audit snapshot (catalog TR text for standard
 * AI/system reasons, or a sanitized moderator-authored note). It is <em>not</em>
 * the canonical localization authority for known product codes — clients should
 * prefer i18n keyed by {@link #code()}.
 */
public record ParkingSpotRejection(
        RejectionReasonCode code,
        String message,
        RejectionSource source,
        Instant rejectedAt,
        UUID rejectedBy,
        String policyVersion) {

    public ParkingSpotRejection {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(rejectedAt, "rejectedAt");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (message.length() > 512) {
            message = message.substring(0, 512);
        }
    }

    public Optional<UUID> rejectedByOptional() {
        return Optional.ofNullable(rejectedBy);
    }

    public Optional<String> policyVersionOptional() {
        return Optional.ofNullable(policyVersion);
    }

    public static ParkingSpotRejection of(
            RejectionReasonCode code,
            RejectionSource source,
            Instant rejectedAt,
            UUID rejectedBy,
            String policyVersion) {
        return new ParkingSpotRejection(
                code,
                RejectionReasonCatalog.messageTr(code),
                source,
                rejectedAt,
                rejectedBy,
                policyVersion);
    }

    public static ParkingSpotRejection of(
            RejectionReasonCode code,
            String message,
            RejectionSource source,
            Instant rejectedAt,
            UUID rejectedBy,
            String policyVersion) {
        String text = (message == null || message.isBlank())
                ? RejectionReasonCatalog.messageTr(code)
                : message.trim();
        return new ParkingSpotRejection(code, text, source, rejectedAt, rejectedBy, policyVersion);
    }
}
