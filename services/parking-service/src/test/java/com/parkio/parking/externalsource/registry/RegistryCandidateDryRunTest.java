package com.parkio.parking.externalsource.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.MunicipalAccessClassification;
import com.parkio.parking.externalsource.MunicipalFacilityType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryCandidateDryRunTest {
    @Test
    void fixtureDryRunReportsAggregatesOnly() throws Exception {
        Map<String, Integer> aggregates = new HashMap<>();
        try (var input = getClass().getResourceAsStream(
                        "/fixtures/municipal/registry/link-candidate-scenarios.csv");
                var reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] value = line.split(",");
                LinkCandidateScore score = LinkCandidatePolicy.evaluate(new LinkCandidateEvidence(
                        "izmir-izum-otoparklar", value[0] + "-a", "fixture-v1",
                        "osm-geofabrik-turkey", value[0] + "-b", "fixture-v1",
                        Double.parseDouble(value[1]),
                        Double.parseDouble(value[2]),
                        Double.parseDouble(value[3]),
                        MunicipalFacilityType.valueOf(value[6]),
                        MunicipalFacilityType.valueOf(value[7]),
                        MunicipalAccessClassification.PUBLIC,
                        MunicipalAccessClassification.PUBLIC,
                        null,
                        null,
                        Boolean.parseBoolean(value[4]),
                        Boolean.parseBoolean(value[5])));
                String outcome = !score.hardConflicts().isEmpty()
                        ? "HARD_CONFLICT" : score.candidate() ? "CANDIDATE" : "SKIP";
                aggregates.merge(outcome, 1, Integer::sum);
                assertThat(outcome).isEqualTo(value[8]);
            }
        }
        assertThat(aggregates).containsEntry("CANDIDATE", 1).containsEntry("HARD_CONFLICT", 2).containsEntry("SKIP", 3);
        // Aggregate-only dry run: no persistence, occupancy, tariff, linking, or publication dependency.
        System.out.printf("registry_fixture_dry_run candidate=%d hard_conflict=%d skipped=%d%n",
                aggregates.get("CANDIDATE"), aggregates.get("HARD_CONFLICT"), aggregates.get("SKIP"));
    }
}
