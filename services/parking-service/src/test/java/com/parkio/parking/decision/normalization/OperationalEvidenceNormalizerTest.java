package com.parkio.parking.decision.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationalEvidenceNormalizerTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T12:00:00Z");
    private final OperationalEvidenceNormalizer normalizer = new OperationalEvidenceNormalizer();

    @Test
    void recordsStaleModerationEventWhenContextProvidesWatermark() {
        UUID spotId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        AiValidationEvidenceInput input = AiValidationEvidenceInput.of(
                UUID.randomUUID(),
                mediaId,
                spotId,
                "PASSED",
                List.of(),
                null,
                null,
                null,
                null,
                OBSERVED_AT);
        ParkingSpotEvidenceContext context = new ParkingSpotEvidenceContext(
                spotId,
                mediaId,
                52.0,
                13.0,
                "LEGAL",
                false,
                OBSERVED_AT.plusSeconds(60));

        List<EvidenceItem> items = normalizer.normalize(input, Optional.of(context));

        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("STALE_MODERATION_EVENT")));
    }

    @Test
    void preservesConflictingOperationalSignals() {
        UUID spotId = UUID.randomUUID();
        AiValidationEvidenceInput input = AiValidationEvidenceInput.of(
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
                spotId,
                UUID.randomUUID(),
                52.0,
                13.0,
                "LEGAL",
                false,
                null);

        List<EvidenceItem> items = normalizer.normalize(input, Optional.of(context));

        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("AI_EVENT_CORRELATED")));
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("MEDIA_SPOT_MISMATCH")));
    }
}
