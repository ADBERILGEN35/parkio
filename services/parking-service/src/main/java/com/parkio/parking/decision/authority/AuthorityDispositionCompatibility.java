package com.parkio.parking.decision.authority;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Exhaustive CurrentStatus × PublicationDisposition matrix for controlled authority.
 *
 * <p>Initial canary enables authoritative apply only for
 * {@link ParkingSpotStatus#PENDING_VALIDATION} × {@link PublicationDisposition#FULL_PUBLISH}.
 * HOLD/REJECTED/SHADOW/LIMITED_PUBLISH/EXPIRED remain non-authoritative.
 */
public final class AuthorityDispositionCompatibility {

    private static final Map<ParkingSpotStatus, Map<PublicationDisposition, AuthorityTransitionClass>> MATRIX =
            buildMatrix();

    private AuthorityDispositionCompatibility() {}

    public static AuthorityTransitionClass classify(
            ParkingSpotStatus currentStatus, PublicationDisposition disposition) {
        Objects.requireNonNull(currentStatus, "currentStatus");
        Objects.requireNonNull(disposition, "disposition");
        Map<PublicationDisposition, AuthorityTransitionClass> row = MATRIX.get(currentStatus);
        if (row == null) {
            return AuthorityTransitionClass.INVALID_TRANSITION;
        }
        AuthorityTransitionClass value = row.get(disposition);
        return value != null ? value : AuthorityTransitionClass.FUTURE_UNSUPPORTED;
    }

    /**
     * Dispositions the initial canary may apply authoritatively when matrix says
     * {@link AuthorityTransitionClass#APPLY_SUPPORTED}.
     */
    public static boolean isCanaryAuthorityDisposition(PublicationDisposition disposition) {
        return disposition == PublicationDisposition.FULL_PUBLISH;
    }

    private static Map<ParkingSpotStatus, Map<PublicationDisposition, AuthorityTransitionClass>> buildMatrix() {
        Map<ParkingSpotStatus, Map<PublicationDisposition, AuthorityTransitionClass>> matrix =
                new EnumMap<>(ParkingSpotStatus.class);
        for (ParkingSpotStatus status : ParkingSpotStatus.values()) {
            Map<PublicationDisposition, AuthorityTransitionClass> row =
                    new EnumMap<>(PublicationDisposition.class);
            for (PublicationDisposition disposition : PublicationDisposition.values()) {
                row.put(disposition, defaultClass(status, disposition));
            }
            matrix.put(status, row);
        }
        // Explicit canary-supported cell
        matrix.get(ParkingSpotStatus.PENDING_VALIDATION)
                .put(PublicationDisposition.FULL_PUBLISH, AuthorityTransitionClass.APPLY_SUPPORTED);

        // Idempotent: already ACTIVE + FULL_PUBLISH
        matrix.get(ParkingSpotStatus.ACTIVE)
                .put(PublicationDisposition.FULL_PUBLISH, AuthorityTransitionClass.NO_OP_IDEMPOTENT);
        matrix.get(ParkingSpotStatus.VERIFIED)
                .put(PublicationDisposition.FULL_PUBLISH, AuthorityTransitionClass.NO_OP_IDEMPOTENT);

        // HOLD could map to review but is intentionally not authority-enabled yet
        matrix.get(ParkingSpotStatus.PENDING_VALIDATION)
                .put(PublicationDisposition.HOLD, AuthorityTransitionClass.LEGACY_ONLY);
        matrix.get(ParkingSpotStatus.PENDING_REVIEW)
                .put(PublicationDisposition.HOLD, AuthorityTransitionClass.NO_OP_IDEMPOTENT);

        return matrix;
    }

    private static AuthorityTransitionClass defaultClass(
            ParkingSpotStatus status, PublicationDisposition disposition) {
        if (disposition == PublicationDisposition.LIMITED_PUBLISH
                || disposition == PublicationDisposition.SHADOW
                || disposition == PublicationDisposition.EXPIRED) {
            return AuthorityTransitionClass.FUTURE_UNSUPPORTED;
        }
        if (disposition == PublicationDisposition.REJECTED) {
            // Conservative: rejection authority disabled in initial canary.
            return AuthorityTransitionClass.LEGACY_ONLY;
        }
        if (status == ParkingSpotStatus.REJECTED
                || status == ParkingSpotStatus.REVIEW_FAILED
                || status == ParkingSpotStatus.EXPIRED
                || status == ParkingSpotStatus.FILLED) {
            return AuthorityTransitionClass.INVALID_TRANSITION;
        }
        if (status == ParkingSpotStatus.PENDING_REVIEW
                || status == ParkingSpotStatus.SUSPICIOUS) {
            return AuthorityTransitionClass.MANUAL_REVIEW_ONLY;
        }
        return AuthorityTransitionClass.LEGACY_ONLY;
    }
}