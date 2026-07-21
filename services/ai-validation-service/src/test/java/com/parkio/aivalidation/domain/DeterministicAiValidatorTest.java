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

    @Test
    void uncertainClassificationCarriesReviewExplanationAndNeverFailsFromLowConfidence() {
        UUID mediaId = UUID.randomUUID();
        ContentRiskClassifier classifier = new ContentRiskClassifier() {
            @Override
            public Verdict classify(UUID id) {
                return Verdict.UNCERTAIN;
            }

            @Override
            public ContentClassification classifyDetailed(UUID id) {
                return ContentClassification.semantic(
                        Verdict.UNCERTAIN,
                        "NEARBY_BARRIER_NOT_BLOCKING_TARGET",
                        "UNCERTAIN",
                        "UNCERTAIN",
                        "NEARBY_ONLY",
                        "OK");
            }
        };
        DeterministicAiValidator validator = new DeterministicAiValidator(classifier);

        AiValidationResult result = validator.validate(mediaId, null, null, NOW);

        assertThat(result.status()).isEqualTo(AiValidationStatus.WARNING);
        assertThat(AiValidationDecision.from(result.status())).isEqualTo(AiValidationDecision.REVIEW);
        assertThat(result.aiConfidence()).isLessThan(50);
        assertThat(result.findings()).anyMatch(f ->
                DeterministicAiValidator.REVIEW_EXPLANATION_KEY.equals(f.message()));
        assertThat(result.findings()).anyMatch(f ->
                f.message() != null
                        && f.message().startsWith(DeterministicAiValidator.REASON_CODE_PREFIX
                        + "NEARBY_BARRIER_NOT_BLOCKING_TARGET"));
    }
}