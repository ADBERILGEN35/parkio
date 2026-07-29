package com.parkio.parking.availability.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.availability.evidence.AvailabilityEvidence;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingSpotStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AvailabilityEvidenceFactoryTest {

    @Test
    void mapsContextFieldsIntoEvidence() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-07-28T08:00:00Z");
        Instant activated = created.plusSeconds(120);
        Instant expires = activated.plusSeconds(600);

        ParkingSpotAvailabilityContext context = new ParkingSpotAvailabilityContext(
                id,
                ParkingSpotStatus.VERIFIED,
                LegalStatus.LEGAL,
                created,
                activated,
                expires,
                2,
                0,
                0.85);

        AvailabilityEvidence evidence = AvailabilityEvidenceFactory.fromContext(context);

        assertThat(evidence.parkingSpotId()).isEqualTo(id);
        assertThat(evidence.status()).isEqualTo(ParkingSpotStatus.VERIFIED);
        assertThat(evidence.verificationCount()).isEqualTo(2);
        assertThat(evidence.isPublished()).isTrue();
    }
}