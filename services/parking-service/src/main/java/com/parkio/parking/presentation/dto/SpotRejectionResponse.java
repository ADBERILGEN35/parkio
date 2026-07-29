package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.ParkingSpotRejection;
import com.parkio.parking.domain.RejectionReasonCatalog;
import com.parkio.parking.domain.RejectionReasonCode;
import com.parkio.parking.domain.RejectionSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Additive rejection detail returned only when a spot has structured rejection metadata.
 *
 * <p>{@code message} is a server-side fallback / audit snapshot (TR catalog text, or a
 * moderator-authored note when that is what was persisted). Clients should prefer
 * localized copy from {@code code} for known product reasons.
 *
 * <p>{@code moderatorNote} is set only when {@code source=MODERATOR} and the persisted
 * text is an author note (not the catalog default).
 */
public record SpotRejectionResponse(
        String code,
        String message,
        String source,
        Instant rejectedAt,
        UUID rejectedBy,
        String policyVersion,
        String moderatorNote) {

    public SpotRejectionResponse(
            String code,
            String message,
            String source,
            Instant rejectedAt,
            UUID rejectedBy,
            String policyVersion) {
        this(code, message, source, rejectedAt, rejectedBy, policyVersion, null);
    }

    public static SpotRejectionResponse from(ParkingSpotRejection rejection) {
        if (rejection == null) {
            return null;
        }
        return new SpotRejectionResponse(
                rejection.code().name(),
                rejection.message(),
                rejection.source().name(),
                rejection.rejectedAt(),
                rejection.rejectedBy(),
                rejection.policyVersion(),
                deriveModeratorNote(rejection));
    }

    private static String deriveModeratorNote(ParkingSpotRejection rejection) {
        if (rejection.source() != RejectionSource.MODERATOR) {
            return null;
        }
        String text = rejection.message();
        String catalogTr = RejectionReasonCatalog.messageTr(RejectionReasonCode.MANUAL_MODERATOR_REJECTION);
        String catalogEn = RejectionReasonCatalog.messageEn(RejectionReasonCode.MANUAL_MODERATOR_REJECTION);
        if (text.equals(catalogTr) || text.equals(catalogEn)) {
            return null;
        }
        return text;
    }
}
