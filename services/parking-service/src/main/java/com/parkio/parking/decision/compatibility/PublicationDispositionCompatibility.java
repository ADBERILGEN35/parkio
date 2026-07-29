package com.parkio.parking.decision.compatibility;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.util.Objects;
import java.util.Optional;

/**
 * Transitional, documentation-oriented mapping between future
 * {@link PublicationDisposition} values and today's {@link ParkingSpotStatus}.
 *
 * <p><strong>WP-05.2:</strong> this helper MUST NOT be invoked from production
 * application paths ({@code ParkingApplicationService}, Kafka consumers, jobs).
 * It exists for tests and design analysis only. Runtime activation is deferred
 * to WP-05.3+.
 *
 * <p>Mappings that would be lossy or unsafe return {@link Optional#empty()}.
 */
public final class PublicationDispositionCompatibility {

    private PublicationDispositionCompatibility() {
    }

    /**
     * Suggests a legacy {@link ParkingSpotStatus} that approximately corresponds
     * to {@code disposition}, when a conservative 1:1 (or many:1) mapping exists.
     *
     * <ul>
     *   <li>{@code FULL_PUBLISH} -> {@code ACTIVE} (VERIFIED remains a post-publish community state)</li>
     *   <li>{@code HOLD} -> {@code PENDING_VALIDATION} (human-review nuance maps later)</li>
     *   <li>{@code EXPIRED} -> {@code EXPIRED}</li>
     *   <li>{@code REJECTED} -> {@code REJECTED}</li>
     *   <li>{@code LIMITED_PUBLISH} -> empty (no exact current equivalent)</li>
     *   <li>{@code SHADOW} -> empty (must not map to public ACTIVE or ordinary REJECTED)</li>
     * </ul>
     */
    public static Optional<ParkingSpotStatus> suggestedLegacyStatus(PublicationDisposition disposition) {
        Objects.requireNonNull(disposition, "disposition");
        return switch (disposition) {
            case FULL_PUBLISH -> Optional.of(ParkingSpotStatus.ACTIVE);
            case HOLD -> Optional.of(ParkingSpotStatus.PENDING_VALIDATION);
            case EXPIRED -> Optional.of(ParkingSpotStatus.EXPIRED);
            case REJECTED -> Optional.of(ParkingSpotStatus.REJECTED);
            case LIMITED_PUBLISH, SHADOW -> Optional.empty();
        };
    }
}