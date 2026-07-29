package com.parkio.parking.decision.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import com.parkio.parking.decision.evidence.EvidenceVector;
import com.parkio.parking.decision.normalization.AiValidationEvidenceInput;
import com.parkio.parking.decision.normalization.EvidenceCollectionRequest;
import com.parkio.parking.decision.normalization.ParkingSpotEvidenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceCollectionServiceTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T12:00:00Z");
    private final EvidenceCollectionService service = new EvidenceCollectionService();

    @Test
    void assemblesDeterministicEvidenceVectorWithLocationContext() {
        UUID spotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        AiValidationEvidenceInput ai = AiValidationEvidenceInput.of(
                eventId,
                mediaId,
                spotId,
                "PASSED",
                List.of("PRIVATE_PROPERTY"),
                80,
                20,
                85,
                90,
                OBSERVED_AT);
        ParkingSpotEvidenceContext context = new ParkingSpotEvidenceContext(
                spotId, mediaId, 52.5200, 13.4050, "LEGAL", true, null);
        EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                spotId, eventId, OBSERVED_AT, ai, context);

        EvidenceVector first = service.collect(request);
        EvidenceVector second = service.collect(request);

        assertThat(first).isEqualTo(second);
        assertThat(first.parkingSpotId()).isEqualTo(spotId);
        assertThat(first.evaluationId()).isEqualTo(eventId);
        assertThat(first.items()).isNotEmpty();
        assertThat(first.items()).anySatisfy(item -> assertThat(item.type())
                .isEqualTo(EvidenceType.GEOSPATIAL_CONSISTENCY));
        assertThat(first.items()).anySatisfy(item -> assertThat(item.type())
                .isEqualTo(EvidenceType.AI_CONTENT_ANALYSIS));
    }

    @Test
    void repeatedNormalizationDoesNotCreateSemanticallyDifferentVectors() {
        UUID spotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AiValidationEvidenceInput ai = AiValidationEvidenceInput.of(
                eventId,
                UUID.randomUUID(),
                spotId,
                "WARNING",
                List.of(),
                null,
                null,
                null,
                null,
                OBSERVED_AT);
        EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                spotId, eventId, OBSERVED_AT, ai, null);

        EvidenceVector run1 = service.collect(request);
        EvidenceVector run2 = service.collect(request);

        assertThat(run1).isEqualTo(run2);
    }

    @Test
    void conflictingEvidenceRemainsDistinct() {
        UUID spotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        AiValidationEvidenceInput ai = AiValidationEvidenceInput.of(
                eventId,
                UUID.randomUUID(),
                spotId,
                "PASSED",
                List.of("NO_PARKING_SIGN"),
                null,
                null,
                null,
                null,
                OBSERVED_AT);
        EvidenceCollectionRequest request = new EvidenceCollectionRequest(
                spotId, eventId, OBSERVED_AT, ai, null);

        EvidenceVector vector = service.collect(request);

        assertThat(vector.items()).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("AI_STATUS_PASSED")));
        assertThat(vector.items()).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("AI_RISK_NO_PARKING_SIGN")));
    }

    @Test
    void dedupesIdenticalItemsOnAssembly() {
        EvidenceVectorFactory factory = new EvidenceVectorFactory();
        UUID spotId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        EvidenceItem item = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.NEUTRAL,
                50,
                OBSERVED_AT,
                ReasonCode.of("AI_STATUS_WARNING"),
                evaluationId.toString());

        EvidenceVector vector = factory.assemble(
                spotId, evaluationId, OBSERVED_AT, List.of(item, item));

        assertThat(vector.size()).isOne();
    }
}
