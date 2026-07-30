package com.parkio.parking.externalsource.izelman;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IzelmanMapperTest {
    @Test
    void mapsFacilityWithoutAvailability() {
        var mapped = new IzelmanFacilityMapper().map(IzelmanSourceKeys.OPEN, Map.of(
                "OTOPARK_ADI", "Üçkuyular Açık Otoparkı", "ILCE", "Balçova",
                "ENLEM", "38.401234", "BOYLAM", "27.051234", "KAPASITE", "120"));
        assertThat(mapped.facilityType()).isEqualTo(MunicipalFacilityType.OFF_STREET);
        assertThat(mapped.operatorName()).isEqualTo("İZELMAN A.Ş.");
        assertThat(mapped.sourceMetadata()).containsEntry("availability", "UNKNOWN");
    }

    @Test
    void mapsInvalidCoordinatesAsStreetNameOnly() {
        var mapped = new IzelmanRoadsideMapper().map(Map.of(
                "OTOPARK_ADI", "Şair Eşref Bulvarı", "ILCE", "Konak",
                "ADRES_VEYA_TARIF", "Şair Eşref Bulvarı", "ENLEM", "999", "BOYLAM", "x"));
        assertThat(mapped.geometryKind()).isEqualTo(NormalizedRoadsideSegment.GeometryKind.STREET_NAME_ONLY);
        assertThat(mapped.latitude()).isNull();
    }

    @Test
    void agedTariffIsNeverCurrentAndParsesBands() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("OTOPARK_ADI", "Konak");
        row.put("0-1 Saat", "30,00");
        row.put("2-4 saat", "50");
        var aging = new IzelmanTariffMapper().map(row,
                Instant.parse("2024-09-02T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(aging.currentness()).isEqualTo(TariffCurrentness.UNKNOWN);
        assertThat(aging.bands()).hasSize(2);
        assertThat(aging.bands().getFirst().durationToMinutes()).isEqualTo(60);

        var historical = new IzelmanTariffMapper().map(row,
                Instant.parse("2022-11-28T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(historical.currentness()).isEqualTo(TariffCurrentness.HISTORICAL);
    }

    @Test
    void rejectsFacilityWithoutCoordinates() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new IzelmanFacilityMapper().map(IzelmanSourceKeys.OPEN, Map.of(
                        "OTOPARK_ADI", "Broken", "ILCE", "Konak", "ENLEM", "999", "BOYLAM", "x")));
    }

    @Test
    void deterministicIdIsStableAndCoordinateSensitive() {
        String first = IzelmanExternalId.of(IzelmanSourceKeys.OPEN, "İnciraltı", 38.1, 27.1, "Balçova");
        assertThat(IzelmanExternalId.of(IzelmanSourceKeys.OPEN, "  İnciraltı ", 38.1, 27.1, "Balçova"))
                .isEqualTo(first);
        assertThat(IzelmanExternalId.of(IzelmanSourceKeys.OPEN, "İnciraltı", 38.2, 27.1, "Balçova"))
                .isNotEqualTo(first);
    }

    @Test
    void officialMatrixTitleCreatesDistinctPlanIdentity() {
        Map<String, String> a = matrixRow("Alsancak Tam Otomatik Otoparki", "40", "70");
        Map<String, String> b = matrixRow("Bostanli Migros Katli Otoparki", "40", "80");
        var mapper = new IzelmanTariffMapper();
        Instant content = Instant.parse("2024-09-02T00:00:00Z");
        Instant fetched = Instant.parse("2026-07-30T00:00:00Z");
        var planA = mapper.map(a, content, fetched);
        var planB = mapper.map(b, content, fetched);
        assertThat(planA.externalId()).isNotEqualTo(planB.externalId());
        assertThat(planA.planName()).contains("Alsancak");
        assertThat(planA.bands()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(planA.currentness()).isEqualTo(TariffCurrentness.UNKNOWN);
    }

    @Test
    void sameTitleDifferentDurationsAreSeparateBandsNotDuplicatePlans() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("OTOPARK / FIYAT", "Ornek Otopark");
        row.put("0-1 Saat", "20");
        row.put("2-4 saat", "40");
        row.put("0-24 Saat(Motosiklet )", "15");
        var plan = new IzelmanTariffMapper().map(row,
                Instant.parse("2024-09-02T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.bands()).hasSize(3);
        assertThat(plan.bands()).extracting(NormalizedTariffPlan.RateBand::vehicleClass)
                .contains("CAR", "MOTORCYCLE");
    }

    @Test
    void subscriptionAndLostTicketColumnsBecomeNonDurationBands() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("OTOPARK / FIYAT", "Ornek Otopark");
        row.put("Aylik Abone Ucreti", "3000");
        row.put("Kayip Bilet", "250");
        var plan = new IzelmanTariffMapper().map(row,
                Instant.parse("2024-09-02T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.bands()).extracting(NormalizedTariffPlan.RateBand::feeKind)
                .contains(NormalizedTariffPlan.FeeKind.SUBSCRIPTION, NormalizedTariffPlan.FeeKind.OTHER);
    }

    @Test
    void fetchTimestampDoesNotPromoteAgingTariffToCurrent() {
        Map<String, String> row = matrixRow("Fresh Fetch", "10", "20");
        var plan = new IzelmanTariffMapper().map(row,
                Instant.parse("2024-09-02T00:00:00Z"), Instant.parse("2026-07-30T12:00:00Z"));
        assertThat(plan.currentness()).isNotEqualTo(TariffCurrentness.CURRENT);
    }

    @Test
    void historicalTariffRemainsNonCurrent() {
        Map<String, String> row = matrixRow("Old Lot", "10", "20");
        var plan = new IzelmanTariffMapper().map(row,
                Instant.parse("2022-11-01T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.currentness()).isEqualTo(TariffCurrentness.HISTORICAL);
    }

    @Test
    void ambiguousTariffTextCountsAsTextualFallback() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("OTOPARK / FIYAT", "Ornek");
        row.put("0-1 Saat", "ucretsiz/belirsiz");
        var mapper = new IzelmanTariffMapper();
        assertThat(mapper.countTextualFallbackCells(row)).isEqualTo(1);
        var plan = mapper.map(row, Instant.parse("2024-09-02T00:00:00Z"), Instant.parse("2026-07-30T00:00:00Z"));
        assertThat(plan.bands()).isEmpty();
    }

    @Test
    void identicalCanonicalRowsShareRawHash() {
        Map<String, String> row = matrixRow("Same", "10", "20");
        var mapper = new IzelmanTariffMapper();
        Instant content = Instant.parse("2024-09-02T00:00:00Z");
        Instant fetched = Instant.parse("2026-07-30T00:00:00Z");
        assertThat(mapper.map(row, content, fetched).rawRecordHash())
                .isEqualTo(mapper.map(new LinkedHashMap<>(row), content, fetched).rawRecordHash());
    }

    private static Map<String, String> matrixRow(String name, String hour1, String hour2) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("OTOPARK / FIYAT", name);
        row.put("0-1 Saat", hour1);
        row.put("2-4 saat", hour2);
        return row;
    }

}
