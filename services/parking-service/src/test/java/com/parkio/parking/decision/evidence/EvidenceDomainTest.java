package com.parkio.parking.decision.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.parking.decision.assessment.ReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceDomainTest {

    private static final Instant T0 = Instant.parse("2026-07-27T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-07-27T12:01:00Z");

    @Test
    void evidenceItemIsImmutableAndValidatesStrength() {
        EvidenceItem item = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.SUPPORTS_PUBLISH,
                80,
                T0,
                ReasonCode.of("LIKELY_PARKING"),
                "media:abc");

        assertThat(item.strength()).isEqualTo(80);
        assertThat(item.reasonCode()).contains(ReasonCode.of("LIKELY_PARKING"));
        assertThat(item.sourceReference()).contains("media:abc");
        assertThatThrownBy(() -> EvidenceItem.of(
                        EvidenceType.AI_CONTENT_ANALYSIS,
                        EvidenceSource.AI_VALIDATION_SERVICE,
                        EvidencePolarity.SUPPORTS_PUBLISH,
                        101,
                        T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void absentPolarityIsDistinctFromOpposes() {
        EvidenceItem absent = EvidenceItem.of(
                EvidenceType.DEVICE_INTEGRITY,
                EvidenceSource.CLIENT_DEVICE,
                EvidencePolarity.ABSENT,
                0,
                T0);
        EvidenceItem negative = EvidenceItem.of(
                EvidenceType.DEVICE_INTEGRITY,
                EvidenceSource.CLIENT_DEVICE,
                EvidencePolarity.OPPOSES_PUBLISH,
                90,
                T0);

        assertThat(absent.polarity()).isEqualTo(EvidencePolarity.ABSENT);
        assertThat(negative.polarity()).isEqualTo(EvidencePolarity.OPPOSES_PUBLISH);
        assertThat(absent).isNotEqualTo(negative);
    }

    @Test
    void evidenceVectorRejectsNullCollectionAndNullItems() {
        UUID spotId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();

        assertThatThrownBy(() -> EvidenceVector.of(spotId, evalId, T0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EvidenceVector.of(spotId, evalId, T0, java.util.Arrays.asList(
                        EvidenceItem.of(
                                EvidenceType.AI_CONTENT_ANALYSIS,
                                EvidenceSource.AI_VALIDATION_SERVICE,
                                EvidencePolarity.NEUTRAL,
                                10,
                                T0),
                        null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evidenceVectorCanonicalizesDeterministicOrder() {
        UUID spotId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();

        EvidenceItem later = EvidenceItem.of(
                EvidenceType.OUTCOME_FEEDBACK,
                EvidenceSource.USER_OUTCOME,
                EvidencePolarity.SUPPORTS_PUBLISH,
                70,
                T1);
        EvidenceItem earlier = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.OPPOSES_PUBLISH,
                40,
                T0);

        EvidenceVector a = EvidenceVector.of(spotId, evalId, T1, List.of(later, earlier));
        EvidenceVector b = EvidenceVector.of(spotId, evalId, T1, List.of(earlier, later));

        assertThat(a.items()).containsExactlyElementsOf(b.items());
        assertThat(a).isEqualTo(b);
        assertThat(a.items().get(0).type()).isEqualTo(EvidenceType.AI_CONTENT_ANALYSIS);
        assertThat(a.schemaVersion()).isEqualTo(EvidenceVector.SCHEMA_VERSION_V1);
    }

    @Test
    void evidenceVectorPreservesConflictingItemsWithoutMerging() {
        UUID spotId = UUID.randomUUID();
        UUID evalId = UUID.randomUUID();
        EvidenceItem support = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.SUPPORTS_PUBLISH,
                80,
                T0,
                ReasonCode.of("LIKELY_PARKING"),
                "a");
        EvidenceItem oppose = EvidenceItem.of(
                EvidenceType.AI_CONTENT_ANALYSIS,
                EvidenceSource.AI_VALIDATION_SERVICE,
                EvidencePolarity.OPPOSES_PUBLISH,
                80,
                T0,
                ReasonCode.of("NOT_A_PARKING_SPOT"),
                "b");

        EvidenceVector vector = EvidenceVector.of(spotId, evalId, T0, List.of(support, oppose));
        assertThat(vector.size()).isEqualTo(2);
    }
}