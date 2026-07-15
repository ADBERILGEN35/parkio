package com.parkio.aivalidation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.aivalidation.infrastructure.classifier.HeuristicContentRiskClassifier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Classifier-driven outcomes for the placeholder validator (fail-closed by default). */
class DeterministicAiValidatorTest {

    private static final Instant NOW = Instant.parse("2026-06-08T12:00:00Z");

    @AfterEach
    void tearDown() {
        HeuristicContentRiskClassifier.clearAllOverrides();
    }

    @Test
    void defaultUncertainProducesWarning() {
        UUID mediaId = UUID.randomUUID();
        DeterministicAiValidator validator = new DeterministicAiValidator(new HeuristicContentRiskClassifier());

        AiValidationResult result = validator.validate(mediaId, null, null, NOW);

        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
        assertThat(result.aiConfidence()).isLessThan(50);
        assertThat(result.detectedRiskTypes()).doesNotContain(AiRiskType.NOT_A_PARKING_SPOT);
    }

    @Test
    void likelyParkingOverrideProducesPassed() {
        UUID mediaId = UUID.randomUUID();
        HeuristicContentRiskClassifier.putOverride(mediaId, ContentRiskClassifier.Verdict.LIKELY_PARKING);
        DeterministicAiValidator validator = new DeterministicAiValidator(new HeuristicContentRiskClassifier());

        AiValidationResult result = validator.validate(mediaId, UUID.randomUUID(), null, NOW);

        assertThat(result.status()).isEqualTo(AiValidationStatus.PASSED);
        assertThat(result.detectedRiskTypes()).isEmpty();
    }

    @Test
    void notAParkingSpotOverrideProducesFailed() {
        UUID mediaId = UUID.randomUUID();
        HeuristicContentRiskClassifier.putOverride(mediaId, ContentRiskClassifier.Verdict.NOT_A_PARKING_SPOT);
        DeterministicAiValidator validator = new DeterministicAiValidator(new HeuristicContentRiskClassifier());

        AiValidationResult result = validator.validate(mediaId, UUID.randomUUID(), null, NOW);

        assertThat(result.status()).isEqualTo(AiValidationStatus.FAILED);
        assertThat(result.detectedRiskTypes()).contains(AiRiskType.NOT_A_PARKING_SPOT);
        assertThat(result.imageQualityScore()).isLessThan(25);
    }
}