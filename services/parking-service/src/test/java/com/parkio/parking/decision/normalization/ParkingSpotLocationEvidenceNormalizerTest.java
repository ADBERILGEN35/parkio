package com.parkio.parking.decision.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.decision.assessment.ReasonCode;
import com.parkio.parking.decision.evidence.EvidenceItem;
import com.parkio.parking.decision.evidence.EvidencePolarity;
import com.parkio.parking.decision.evidence.EvidenceSource;
import com.parkio.parking.decision.evidence.EvidenceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ParkingSpotLocationEvidenceNormalizerTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-06-08T12:00:00Z");
    private final ParkingSpotLocationEvidenceNormalizer normalizer = new ParkingSpotLocationEvidenceNormalizer();

    @Test
    void mapsValidCoordinatesAndLegalStatus() {
        ParkingSpotEvidenceContext context = new ParkingSpotEvidenceContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                52.0,
                13.0,
                "LEGAL",
                false,
                null);

        List<EvidenceItem> items = normalizer.normalize(context, OBSERVED_AT);

        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("COORDINATES_VALID")));
        assertThat(items).anySatisfy(item -> assertThat(item.reasonCode())
                .contains(ReasonCode.of("SUBMITTER_LEGAL_OK")));
    }

    @Test
    void doesNotEmitDeviceOrTrustPlaceholderEvidence() {
        List<EvidenceItem> items = normalizer.normalize(
                new ParkingSpotEvidenceContext(
                        UUID.randomUUID(), UUID.randomUUID(), 52.0, 13.0, "LEGAL", false, null),
                OBSERVED_AT);

        assertThat(items.stream().map(EvidenceItem::type)).doesNotContain(
                EvidenceType.USER_TRUST_HISTORY, EvidenceType.DEVICE_INTEGRITY);
        assertThat(items.stream().map(EvidenceItem::source)).doesNotContain(
                EvidenceSource.GAMIFICATION_SERVICE, EvidenceSource.CLIENT_DEVICE);
    }
}
