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
}
