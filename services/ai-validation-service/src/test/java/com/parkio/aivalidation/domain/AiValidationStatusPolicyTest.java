package com.parkio.aivalidation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.aivalidation.domain.exception.AiValidationException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for the advisory PASSED/WARNING/FAILED rule. Pure, no Spring. */
class AiValidationStatusPolicyTest {

    @Test
    void passedWhenLowRiskGoodQualityAndConfidence() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(10, 85, 90, List.of());
        assertThat(status).isEqualTo(AiValidationStatus.PASSED);
    }

    @Test
    void warningWhenLegalRiskScoreHigh() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(70, 85, 90, List.of());
        assertThat(status).isEqualTo(AiValidationStatus.WARNING);
    }

    @Test
    void warningWhenLegalRiskTypeDetected() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(10, 85, 90, Set.of(AiRiskType.FIRE_HYDRANT));
        assertThat(status).isEqualTo(AiValidationStatus.WARNING);
    }

    @Test
    void warningWhenImageQualityPoorButUsable() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(10, 40, 90, List.of());
        assertThat(status).isEqualTo(AiValidationStatus.WARNING);
    }

    @Test
    void warningWhenConfidenceLow() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(10, 85, 30, List.of());
        assertThat(status).isEqualTo(AiValidationStatus.WARNING);
    }

    @Test
    void failedWhenImageClearlyUnusable() {
        AiValidationStatus status = AiValidationStatusPolicy.evaluate(10, 10, 90, List.of());
        assertThat(status).isEqualTo(AiValidationStatus.FAILED);
    }

    @Test
    void failedWhenNotAParkingSpot() {
        AiValidationStatus status =
                AiValidationStatusPolicy.evaluate(10, 85, 90, Set.of(AiRiskType.NOT_A_PARKING_SPOT));
        assertThat(status).isEqualTo(AiValidationStatus.FAILED);
    }

    @Test
    void rejectsScoresOutsideRange() {
        assertThatThrownBy(() -> AiValidationStatusPolicy.evaluate(150, 85, 90, List.of()))
                .isInstanceOf(AiValidationException.class);
    }
}
