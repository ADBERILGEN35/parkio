package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * #615: the runbook's hosted-beta env table and the production Compose overlay agree on which İZUM
 * settings an operator can set. An env-file value reaches the container only through an overlay
 * mapping, so a row without one documents a setting that production silently ignores (the runbook
 * listed {@code PARKIO_MUNICIPAL_IZUM_MAX_RESPONSE_TIME}, which the overlay did not map).
 */
class IzumComposeOverlayMappingTest {

    private static final Path RUNBOOK = IzumReadTimeoutConfigTest.ROOT
            .resolve("../../docs/operations/municipal-parking-source-runbook.md").normalize();
    /** A table row such as {@code | `parkio.municipal.izum.read-timeout` | `PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT` | 27s |}. */
    private static final Pattern RUNBOOK_ROW = Pattern.compile(
            "^\\|\\s*`(parkio\\.municipal\\.izum\\.[a-z-]+)`\\s*\\|\\s*`(PARKIO_MUNICIPAL_IZUM_[A-Z_]+)`\\s*\\|\\s*([^|]+?)\\s*\\|$");
    /** An overlay mapping such as {@code PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT: ${PARKIO_MUNICIPAL_IZUM_READ_TIMEOUT:-27s}}. */
    private static final Pattern OVERLAY_MAPPING = Pattern.compile(
            "^(PARKIO_MUNICIPAL_IZUM_[A-Z_]+): \\$\\{\\1:-([^}]*)}$");

    @Test
    void theRunbookTableListsExactlyTheIzumSettingsTheOverlayMapsWithTheSameDefaults() throws Exception {
        Map<String, String> runbook = new HashMap<>();
        for (String line : Files.readAllLines(RUNBOOK)) {
            Matcher row = RUNBOOK_ROW.matcher(line.strip());
            if (row.matches()) {
                assertThat(row.group(2)).as("env name of %s", row.group(1)).isEqualTo(envName(row.group(1)));
                runbook.put(row.group(2), row.group(3));
            }
        }

        assertThat(runbook).isNotEmpty().isEqualTo(overlayDefaults());
    }

    /** An overlay default that differed from application.yml would change the setting on deploy. */
    @Test
    void everyOverlayDefaultBindsTheSameValueAsApplicationYaml() throws Exception {
        MunicipalSourceProperties.Izum yaml = IzumReadTimeoutConfigTest.bindIzum(Map.of());
        MunicipalSourceProperties.Izum overlay = IzumReadTimeoutConfigTest.bindIzum(new HashMap<>(overlayDefaults()));

        assertThat(settings(overlay)).isEqualTo(settings(yaml));
    }

    @Test
    void theOverlayMapsTheWholeResponseBounds() throws Exception {
        assertThat(overlayDefaults())
                .containsEntry("PARKIO_MUNICIPAL_IZUM_MAX_RESPONSE_SIZE", "16MB")
                .containsEntry("PARKIO_MUNICIPAL_IZUM_MAX_RESPONSE_TIME", "30s");
    }

    private static Map<String, String> overlayDefaults() throws Exception {
        Map<String, String> mappings = new HashMap<>();
        for (String line : Files.readAllLines(IzumReadTimeoutConfigTest.AZURE_COMPOSE)) {
            Matcher mapping = OVERLAY_MAPPING.matcher(line.strip());
            if (mapping.matches()) {
                assertThat(mappings.put(mapping.group(1), mapping.group(2)))
                        .as("second mapping of %s", mapping.group(1)).isNull();
            }
        }
        return mappings;
    }

    private static String envName(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static List<Object> settings(MunicipalSourceProperties.Izum izum) {
        return List.of(
                izum.isEnabled(),
                izum.isSchedulerEnabled(),
                izum.getFixedDelayMs(),
                izum.getConnectTimeout(),
                izum.getReadTimeout(),
                izum.getMaxRetries(),
                izum.getMaxResponseSize(),
                izum.getMaxResponseTime());
    }
}
