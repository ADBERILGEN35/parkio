package com.parkio.parking.decision.score;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScoreValueObjectsTest {

    @Test
    void evidenceScoreAcceptsBounds() {
        assertThat(EvidenceScore.of(0).value()).isZero();
        assertThat(EvidenceScore.of(100).value()).isEqualTo(100);
    }

    @Test
    void evidenceScoreRejectsOutOfRange() {
        assertThatThrownBy(() -> EvidenceScore.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvidenceScore.of(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void riskAndAvailabilityUseSameHundredScale() {
        assertThat(RiskScore.of(0).value()).isZero();
        assertThat(RiskScore.of(100).value()).isEqualTo(100);
        assertThat(AvailabilityScore.of(50).value()).isEqualTo(50);
        assertThatThrownBy(() -> RiskScore.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AvailabilityScore.of(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void trustScoreUsesUnitIntervalDistinctFromHundredScale() {
        TrustScore min = TrustScore.of("0.00");
        TrustScore max = TrustScore.of("1.00");
        TrustScore mid = TrustScore.of(new BigDecimal("0.5"));

        assertThat(min.value()).isEqualByComparingTo("0.00");
        assertThat(max.value()).isEqualByComparingTo("1.00");
        assertThat(mid.value()).isEqualByComparingTo("0.50");
        assertThat(TrustScore.of("0.50")).isEqualTo(mid);

        assertThatThrownBy(() -> TrustScore.of("-0.01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustScore.of("1.01")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownScoresAreRepresentedByAbsenceNotZero() {
        Optional<EvidenceScore> missingEvidence = Optional.empty();
        Optional<TrustScore> missingTrust = Optional.empty();

        assertThat(missingEvidence).isEmpty();
        assertThat(missingTrust).isEmpty();
        assertThat(EvidenceScore.of(0).value()).isZero();
        assertThat(TrustScore.of("0.00").value()).isEqualByComparingTo("0.00");
    }
}