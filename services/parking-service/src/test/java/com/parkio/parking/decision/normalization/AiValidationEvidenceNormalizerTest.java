package com.parkio.parking.decision.normalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.PublicationDisposition;
import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiValidationEvidenceNormalizerTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T12:00:00Z");
    private final AiValidationEvidenceNormalizer normalizer = new AiValidationEvidenceNormalizer();

    @Test
    void mapsPositiveAiEvidenceWithScores() {
        UUID eventId = UUID.randomUUID();
        AiValidationEvidenceInput input = AiValidationEvidenceInput.of(
                eventId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PASSED",
                List.of(),
                82,
                12,
                90,
                88,
                OBSERVED_AT);

        List<EvidenceItem> items = normalizer.normalize(input);

        assertThat(items).anySatisfy(item -> {
            assertThat(item.reasonCode()).contains(ReasonCode.of("AI_STATUS_PASSED"));
            assertThat(item.polarity()).isEqualTo(EvidencePolarity.SUPPORTS_PUBLISH);
        });
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("EMPTY_SPACE_CONFIDENCE")));
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("LEGAL_RISK_SCORE")));
        assertThat(items.stream().map(item -> item.sourceReference().orElse("")).distinct())
                .containsExactly(eventId.toString());
    }

    @Test
    void mapsNegativeAiEvidenceFromFailedStatusAndRisks() {
        AiValidationEvidenceInput input = sampleInput("FAILED", List.of("NOT_A_PARKING_SPOT", "NO_PARKING_SIGN"));

        List<EvidenceItem> items = normalizer.normalize(input);

        assertThat(items).anySatisfy(item -> {
            assertThat(item.reasonCode()).contains(ReasonCode.of("AI_STATUS_FAILED"));
            assertThat(item.polarity()).isEqualTo(EvidencePolarity.OPPOSES_PUBLISH);
        });
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("AI_RISK_NOT_A_PARKING_SPOT")));
    }

    @Test
    void skipsOptionalScoreFieldsWhenAbsent() {
        AiValidationEvidenceInput input = sampleInput("WARNING", List.of("LOW_IMAGE_QUALITY"));

        List<EvidenceItem> items = normalizer.normalize(input);

        assertThat(items).noneMatch(item -> item.reasonCode()
                .map(ReasonCode::value)
                .filter(code -> code.equals("EMPTY_SPACE_CONFIDENCE"))
                .isPresent());
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("AI_RISK_LOW_IMAGE_QUALITY")));
    }

    @Test
    void rejectsMalformedScoreValues() {
        AiValidationEvidenceInput input = AiValidationEvidenceInput.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PASSED",
                List.of(),
                150,
                null,
                null,
                null,
                OBSERVED_AT);

        assertThatThrownBy(() -> normalizer.normalize(input))
                .isInstanceOf(EvidenceNormalizationException.class)
                .hasMessageContaining("EMPTY_SPACE_CONFIDENCE");
    }

    @Test
    void producesDeterministicOutputForIdenticalInputs() {
        AiValidationEvidenceInput input = sampleInput("PASSED", List.of("BUS_STOP", "BUS_STOP"));

        List<EvidenceItem> first = normalizer.normalize(input);
        List<EvidenceItem> second = normalizer.normalize(input);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void producesOnlyEvidenceItemsNotScoresOrDecisions() {
        List<EvidenceItem> items = normalizer.normalize(sampleInput("PASSED", List.of()));

        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item -> assertThat(item).isInstanceOf(EvidenceItem.class));
    }

    @Test
    void aiStatusIsEvidenceNotPublicationDisposition() {
        List<EvidenceItem> items = normalizer.normalize(sampleInput("FAILED", List.of()));

        assertThat(items).allSatisfy(item -> assertThat(item).isInstanceOf(EvidenceItem.class));
        assertThat(PublicationDisposition.values()).isNotEmpty();
    }

    @Test
    void rejectsUnsupportedPayloadSchemaVersion() {
        assertThatThrownBy(() -> new AiValidationEvidenceInput(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "PASSED",
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        OBSERVED_AT,
                        2))
                .isInstanceOf(EvidenceNormalizationException.class);
    }

    private static AiValidationEvidenceInput sampleInput(String status, List<String> risks) {
        return AiValidationEvidenceInput.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                status,
                risks,
                null,
                null,
                null,
                null,
                OBSERVED_AT);
    }
}
