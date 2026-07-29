package com.parkio.parking.decision.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceCollectionRequestTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T12:00:00Z");

    @Test
    void rejectsParkingSpotIdentityMismatch() {
        UUID spotId = UUID.randomUUID();
        AiValidationEvidenceInput ai = AiValidationEvidenceInput.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                spotId,
                "PASSED",
                List.of(),
                null,
                null,
                null,
                null,
                OBSERVED_AT);
        ParkingSpotEvidenceContext context = new ParkingSpotEvidenceContext(
                UUID.randomUUID(),
                ai.mediaId(),
                52.0,
                13.0,
                "LEGAL",
                false,
                null);

        assertThatThrownBy(() -> new EvidenceCollectionRequest(
                        spotId, ai.eventId(), OBSERVED_AT, ai, context))
                .isInstanceOf(EvidenceNormalizationException.class)
                .hasMessageContaining("parkingSpotId mismatch");
    }
}
