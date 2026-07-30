package com.parkio.parking.externalsource.izelman;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IzelmanOfficialTariffMatrixTest {
    @Test
    void officialMatrixYieldsOnePlanPerFacilityWithManyBands() throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream(
                "/fixtures/municipal/izelman/official-otopark-ucretleri.csv")) {
            assertThat(in).isNotNull();
            bytes = in.readAllBytes();
        }
        var csv = new IzelmanCsvReader().read(bytes);
        assertThat(csv.rows()).hasSize(39);
        assertThat(csv.headers()).anyMatch(h -> h.contains("OTOPARK") && h.contains("FIYAT"));

        var mapper = new IzelmanTariffMapper();
        Instant content = Instant.parse("2024-09-02T00:00:00Z");
        Instant fetched = Instant.parse("2026-07-30T00:00:00Z");
        Set<String> planIds = new HashSet<>();
        int bands = 0;
        int trueDupes = 0;
        Set<String> hashes = new HashSet<>();
        for (var row : csv.rows()) {
            var plan = mapper.map(row, content, fetched);
            assertThat(plan.currentness()).isNotEqualTo(TariffCurrentness.CURRENT);
            if (!hashes.add(plan.rawRecordHash())) {
                trueDupes++;
            }
            planIds.add(plan.externalId());
            bands += plan.bands().size();
            assertThat(plan.bands()).isNotEmpty();
        }
        assertThat(planIds).hasSize(39);
        assertThat(bands).isGreaterThan(39);
        assertThat(trueDupes).isZero();
        assertThat(bands).isEqualTo(212);
    }
}