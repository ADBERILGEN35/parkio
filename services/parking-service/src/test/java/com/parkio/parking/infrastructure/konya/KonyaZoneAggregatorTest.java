package com.parkio.parking.infrastructure.konya;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KonyaZoneAggregatorTest {
    private KonyaZoneAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new KonyaZoneAggregator(
                new KonyaCoordinateParser(new ObjectMapper()), new KonyaGeoValidator());
    }

    @Test
    void aggregatesMultipleBayRowsIntoOneZoneWithoutCapacityInflation() {
        List<KonyaParkingRecordDto> rows = List.of(
                row("ZİNDANKALE", 77, 23, "[[32.48686462640762, 37.8728907124379]]"),
                row("ZİNDANKALE", 77, 30, "[[32.4868753552437, 37.8728949469733]]"),
                row("ZİNDANKALE", 77, 24, "[[32.48814672231674, 37.8729881066916]]"));
        KonyaZoneAggregator.AggregationResult result = aggregator.aggregate(rows);
        assertThat(result.zones()).hasSize(1);
        assertThat(result.zones().get(0).capacityTotal()).isEqualTo(77);
        assertThat(result.zones().get(0).sourceRowCount()).isEqualTo(3);
    }

    @Test
    void excludesSuspiciousCoordinatesButKeepsZoneWhenValidSiblingExists() {
        List<KonyaParkingRecordDto> rows = List.of(
                row("MERAM BÖLGESİ", 636, 300, "[[32.7478837966919, 39.8770403869553]]"),
                row("MERAM BÖLGESİ", 636, 100, "[32.4200373888016, 37.8516916957049]"));
        KonyaZoneAggregator.AggregationResult result = aggregator.aggregate(rows);
        assertThat(result.zones()).hasSize(1);
        assertThat(result.zones().get(0).latitude()).isBetween(37.84, 37.86);
        assertThat(result.invalidCoordinateRows()).isEqualTo(1);
    }

    @Test
    void unmappableWhenAllCoordinatesInvalid() {
        List<KonyaParkingRecordDto> rows = List.of(
                row("MERAM BÖLGESİ", 636, 300, "[[32.7478837966919, 39.8770403869553]]"));
        KonyaZoneAggregator.AggregationResult result = aggregator.aggregate(rows);
        assertThat(result.zones()).isEmpty();
        assertThat(result.unmappableZones()).isEqualTo(1);
    }

    @Test
    void rowReorderProducesSameExternalId() {
        KonyaParkingRecordDto a = row("SELÇUKLU MERKEZ", 45, 45, "[32.500500, 37.880100]");
        KonyaParkingRecordDto b = row("  selçuklu  merkez  ", 45, 45, "[32.500600, 37.880200]");
        String id1 = aggregator.aggregate(List.of(a, b)).zones().get(0).externalId();
        String id2 = aggregator.aggregate(List.of(b, a)).zones().get(0).externalId();
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void zeroCapacityNormalizesToNullInZoneBuilderOutput() {
        List<KonyaParkingRecordDto> rows = List.of(
                row("MEVLANA ÇARŞI", 0, 0, "[32.501000, 37.870000]"));
        KonyaZoneAggregator.AggregationResult result = aggregator.aggregate(rows);
        assertThat(result.zones()).hasSize(1);
        assertThat(result.zones().get(0).capacityTotal()).isNull();
    }

    @Test
    void centroidDeterministicForDuplicatePoints() {
        List<KonyaParkingRecordDto> rows = List.of(
                row("DUP", 10, 5, "[32.500500, 37.880100]"),
                row("DUP", 10, 5, "[32.500500, 37.880100]"));
        KonyaZoneAggregator.AggregatedZone zone = aggregator.aggregate(rows).zones().get(0);
        assertThat(zone.latitude()).isEqualTo(37.880100);
        assertThat(zone.longitude()).isEqualTo(32.500500);
    }

    @Test
    void normalizeZoneNameCollapsesWhitespaceAndCase() {
        assertThat(KonyaZoneAggregator.normalizeZoneName("  Meram   Bölgesi ")).isEqualTo("MERAM BÖLGESİ");
        assertThat(KonyaZoneAggregator.normalizeZoneName(null)).isNull();
    }

    @Test
    void formatHoursSupportsMidnightAndTwentyFourHourClose() {
        assertThat(KonyaZoneAggregator.hhmm(800)).isEqualTo("08:00");
        assertThat(KonyaZoneAggregator.hhmm(2400)).isEqualTo("24:00");
        assertThat(KonyaZoneAggregator.formatHours(new KonyaParkingRecordDto(
                        1L, "z", null, null, null, null, null, null, 800, 2400)))
                .isEqualTo("08:00-24:00");
    }

    private static KonyaParkingRecordDto row(
            String zone, int zoneCap, int peronCap, String coord) {
        return new KonyaParkingRecordDto(1L, zone, zone + " address", zoneCap, "p", "a", peronCap, coord, 800, 1900);
    }

    @Test
    void emptyInputReturnsEmptyAggregation() {
        KonyaZoneAggregator.AggregationResult result = aggregator.aggregate(Collections.emptyList());
        assertThat(result.zones()).isEmpty();
        assertThat(result.logicalZoneCount()).isZero();
    }
}
