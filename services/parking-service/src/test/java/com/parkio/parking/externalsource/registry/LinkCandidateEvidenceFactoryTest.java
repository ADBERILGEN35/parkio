package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.DiscoveredPair;
import com.parkio.parking.application.port.LinkCandidatePairDiscoveryPort.SourceRecord;
import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LinkCandidateEvidenceFactoryTest {
    private final LinkCandidateEvidenceFactory factory = new LinkCandidateEvidenceFactory(new ObjectMapper());

    @Test
    void buildsConservativeEvidenceFromActiveVersionedRecords() {
        SourceRecord left = record("izmir-izum-otoparklar", "izum-1", "Konak Otopark1",
                "0zmir Belediyesi", "Atat�rk Cd 1", "{\"district\":\"Konak\"}", true);
        SourceRecord right = record("osm-geofabrik-turkey", "osm-1", "Konak Otoparki",
                "Izmir Belediyesi", "Ataturk Cd. 1", "{\"ilce\":\"konak\"}", true);
        var evidence = factory.create(new DiscoveredPair(left, right, 12.34));

        assertThat(evidence.sourceVersionA()).isEqualTo("hash");
        assertThat(evidence.nameSimilarity()).isGreaterThan(0.9);
        assertThat(evidence.addressMatch()).isTrue();
        assertThat(evidence.districtMatch()).isTrue();
        assertThat(evidence.operatorContradiction()).isFalse();
    }

    @Test
    void rejectsInactiveOrUnversionedSourceRecords() {
        SourceRecord inactive = record("izmir-izum-otoparklar", "a", "A", "A", null, "{}", false);
        SourceRecord active = record("osm-geofabrik-turkey", "b", "B", "B", null, "{}", true);
        assertThatThrownBy(() -> factory.create(new DiscoveredPair(inactive, active, 10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SourceRecord record(
            String source, String external, String name, String operator,
            String address, String metadata, boolean active) {
        return new SourceRecord(
                UUID.randomUUID(), UUID.randomUUID(), source, external, "hash", name, operator,
                MunicipalFacilityType.OFF_STREET, MunicipalAccessClassification.PUBLIC, 100,
                38.42, 27.14, address, metadata, true, active, "ACTIVE");
    }
}
