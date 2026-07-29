package com.parkio.parking.presentation.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotRejection;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.RejectionReasonCode;
import com.parkio.parking.domain.RejectionSource;
import com.parkio.parking.domain.VehicleType;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SpotResponse / PublicSpotResponse rejection mapping. */
class SpotRejectionResponseMappingTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void spotAndPublicResponsesOmitRejectionWhenAbsent() {
        ParkingSpot spot = baseSpot(ParkingSpotStatus.ACTIVE, null);

        assertThat(SpotResponse.from(spot).rejection()).isNull();
        assertThat(PublicSpotResponse.from(spot).rejection()).isNull();
    }

    @Test
    void rejectedSpotExposesStructuredRejectionOnBothViews() {
        UUID moderatorId = UUID.randomUUID();
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.LEGACY_POLICY_RESET,
                RejectionSource.SYSTEM_MIGRATION,
                NOW,
                moderatorId,
                "2026-07-photo-policy-v3-recall");
        ParkingSpot spot = baseSpot(ParkingSpotStatus.REJECTED, rejection);

        SpotRejectionResponse ownerView = SpotResponse.from(spot).rejection();
        SpotRejectionResponse publicView = PublicSpotResponse.from(spot).rejection();

        assertThat(ownerView).isNotNull();
        assertThat(ownerView.code()).isEqualTo("LEGACY_POLICY_RESET");
        assertThat(ownerView.source()).isEqualTo("SYSTEM_MIGRATION");
        assertThat(ownerView.policyVersion()).isEqualTo("2026-07-photo-policy-v3-recall");
        assertThat(ownerView.rejectedAt()).isEqualTo(NOW);
        assertThat(ownerView.rejectedBy()).isEqualTo(moderatorId);
        assertThat(ownerView.message()).isNotBlank();

        assertThat(publicView).isNotNull();
        assertThat(publicView.code()).isEqualTo("LEGACY_POLICY_RESET");
        assertThat(publicView.source()).isEqualTo("SYSTEM_MIGRATION");
    }

    @Test
    void aiPolicyRejectionMapsProductCode() {
        ParkingSpotRejection rejection = ParkingSpotRejection.of(
                RejectionReasonCode.CLEARLY_UNRELATED_CONTENT,
                RejectionSource.AI_POLICY,
                NOW,
                null,
                "2026-07-photo-policy-v3-recall");
        SpotRejectionResponse response = SpotResponse.from(baseSpot(ParkingSpotStatus.REJECTED, rejection))
                .rejection();

        assertThat(response.source()).isEqualTo("AI_POLICY");
        assertThat(response.code()).isEqualTo("CLEARLY_UNRELATED_CONTENT");
        assertThat(response.rejectedBy()).isNull();
        assertThat(response.moderatorNote()).isNull();
    }

    @Test
    void moderatorCustomNoteExposedSeparatelyFromCatalogDefault() {
        ParkingSpotRejection withNote = ParkingSpotRejection.of(
                RejectionReasonCode.MANUAL_MODERATOR_REJECTION,
                "Please upload a street photo.",
                RejectionSource.MODERATOR,
                NOW,
                UUID.randomUUID(),
                null);
        SpotRejectionResponse noted = SpotResponse.from(baseSpot(ParkingSpotStatus.REJECTED, withNote))
                .rejection();
        assertThat(noted.moderatorNote()).isEqualTo("Please upload a street photo.");
        assertThat(noted.message()).isEqualTo("Please upload a street photo.");

        ParkingSpotRejection catalogOnly = ParkingSpotRejection.of(
                RejectionReasonCode.MANUAL_MODERATOR_REJECTION,
                RejectionSource.MODERATOR,
                NOW,
                UUID.randomUUID(),
                null);
        SpotRejectionResponse plain = SpotResponse.from(baseSpot(ParkingSpotStatus.REJECTED, catalogOnly))
                .rejection();
        assertThat(plain.moderatorNote()).isNull();
        assertThat(plain.message()).isNotBlank();
    }

    @Test
    void acceptedAndReviewStatesHaveNoRejectionMetadata() {
        assertThat(SpotResponse.from(baseSpot(ParkingSpotStatus.ACTIVE, null)).rejection()).isNull();
        assertThat(SpotResponse.from(baseSpot(ParkingSpotStatus.PENDING_REVIEW, null)).rejection()).isNull();
    }

    private static ParkingSpot baseSpot(ParkingSpotStatus status, ParkingSpotRejection rejection) {
        return new ParkingSpot(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 41.0, 29.0,
                "Main St", null, false, Set.of(VehicleType.SEDAN), ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL, Set.of(), status, 0.5, 0, 0,
                NOW.plusSeconds(600), NOW, NOW, 0L,
                NOW, NOW.plus(java.time.Duration.ofHours(24)), 0, NOW, null, null,
                rejection, rejection == null ? null : rejection.policyVersion());
    }
}
