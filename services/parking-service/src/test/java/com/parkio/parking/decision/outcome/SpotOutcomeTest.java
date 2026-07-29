package com.parkio.parking.decision.outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.evidence.EvidenceSource;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SpotOutcomeTest {

    private static final Instant NOW = Instant.parse("2026-07-27T16:00:00Z");

    @Test
    void outcomeVocabularyIsComplete() {
        assertThat(SpotOutcomeType.values())
                .containsExactlyInAnyOrder(
                        SpotOutcomeType.PARKED_SUCCESSFULLY,
                        SpotOutcomeType.ARRIVED_BUT_OCCUPIED,
                        SpotOutcomeType.LOCATION_NOT_FOUND,
                        SpotOutcomeType.INVALID_OR_ILLEGAL,
                        SpotOutcomeType.REPORT_ALREADY_STALE);
    }

    @Test
    void outcomeRequiresTargetTypeSourceAndTime() {
        UUID spotId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SpotOutcome outcome = SpotOutcome.of(
                spotId,
                SpotOutcomeType.PARKED_SUCCESSFULLY,
                EvidenceSource.USER_OUTCOME,
                actor,
                NOW,
                90);

        assertThat(outcome.parkingSpotId()).isEqualTo(spotId);
        assertThat(outcome.actorUserId()).contains(actor);
        assertThat(outcome.confidence()).contains(90);
        assertThatThrownBy(() -> SpotOutcome.of(
                        spotId,
                        SpotOutcomeType.LOCATION_NOT_FOUND,
                        EvidenceSource.USER_OUTCOME,
                        actor,
                        NOW,
                        101))
                .isInstanceOf(IllegalArgumentException.class);
    }
}