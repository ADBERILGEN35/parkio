package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

/**
 * #615, #637: the runbook's hosted-beta env table and the production Compose overlay agree on which
 * settings of each HTTP municipal feed an operator can set. An env-file value reaches the container only
 * through an overlay mapping, so a row without one documents a setting that production silently ignores
 * (the runbook listed {@code PARKIO_MUNICIPAL_IZUM_MAX_RESPONSE_TIME}, which the overlay did not map).
 * Startup refuses a connect plus read timeout that reaches {@code max-response-time} and tells the
 * operator to raise {@code max-response-time} with the read timeout, so the overlay maps both bounds of
 * every feed whose timeouts it maps.
 */
class MunicipalFeedComposeOverlayMappingTest {

    private static final Path RUNBOOK = IzumReadTimeoutConfigTest.ROOT
            .resolve("../../docs/operations/municipal-parking-source-runbook.md").normalize();

    /**
     * A table row such as {@code | `parkio.municipal.konya.read-timeout` | `PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT` | 20s |}.
     */
    private static Pattern runbookRow(String source) {
        return Pattern.compile("^\\|\\s*`(parkio\\.municipal\\." + source + "\\.[a-z-]+)`\\s*\\|\\s*`(PARKIO_MUNICIPAL_"
                + envSegment(source) + "_[A-Z_]+)`\\s*\\|\\s*([^|]+?)\\s*\\|$");
    }

    /**
     * An overlay mapping such as {@code PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT: ${PARKIO_MUNICIPAL_KONYA_READ_TIMEOUT:-20s}}.
     */
    private static Pattern overlayMapping(String source) {
        return Pattern.compile("^(PARKIO_MUNICIPAL_" + envSegment(source) + "_[A-Z_]+): \\$\\{\\1:-([^}]*)}$");
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void theRunbookTableListsExactlyTheSettingsTheOverlayMapsWithTheSameDefaults(String source) throws Exception {
        Pattern rowPattern = runbookRow(source);
        Map<String, String> runbook = new HashMap<>();
        for (String line : Files.readAllLines(RUNBOOK)) {
            Matcher row = rowPattern.matcher(line.strip());
            if (row.matches()) {
                assertThat(row.group(2)).as("env name of %s", row.group(1)).isEqualTo(envName(row.group(1)));
                assertThat(runbook.put(row.group(2), row.group(3))).as("second row for %s", row.group(1)).isNull();
            }
        }

        assertThat(runbook).isNotEmpty().isEqualTo(overlayDefaults(source));
    }

    /** An overlay default that differed from application.yml would change the setting on deploy. */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void everyOverlayDefaultBindsTheSameValueAsApplicationYaml(String source) throws Exception {
        Map<String, String> overlay = overlayDefaults(source);
        BeanWrapper yaml = bind(Map.of());
        BeanWrapper deployed = bind(new HashMap<>(overlay));

        assertThat(overlay).isNotEmpty();
        for (String variable : overlay.keySet()) {
            String property = propertyPath(source, variable);
            assertThat(deployed.getPropertyValue(property)).as(variable).isEqualTo(yaml.getPropertyValue(property));
        }
    }

    /**
     * The comparison above means something only if a mapped variable reaches its setting. Most of these
     * settings are plain values in application.yml, not {@code ${...}} placeholders, so a variable reaches
     * them through Spring's environment-variable mapping, as it does in the container.
     */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void aMappedVariableReachesASettingThatApplicationYamlSetsToAPlainValue(String source) {
        BeanWrapper deployed = bind(Map.of(env(source, "MAX_RESPONSE_TIME"), "45s"));

        assertThat(deployed.getPropertyValue(source + ".maxResponseTime")).isEqualTo(Duration.ofSeconds(45));
    }

    /**
     * Startup refuses a connect plus read timeout that reaches {@code max-response-time} and names
     * {@code PARKIO_MUNICIPAL_<SOURCE>_MAX_RESPONSE_TIME} as the variable to raise with the read timeout
     * (#637). The overlay maps it, and the size bound, for every feed whose timeouts it maps.
     */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void theOverlayMapsTheTimeoutsAndTheWholeResponseBounds(String source) throws Exception {
        assertThat(overlayDefaults(source))
                .containsKeys(env(source, "CONNECT_TIMEOUT"), env(source, "READ_TIMEOUT"))
                .containsEntry(env(source, "MAX_RESPONSE_SIZE"), "16MB")
                .containsEntry(env(source, "MAX_RESPONSE_TIME"), "30s");
    }

    private static Map<String, String> overlayDefaults(String source) throws IOException {
        Pattern mappingPattern = overlayMapping(source);
        Map<String, String> mappings = new HashMap<>();
        for (String line : Files.readAllLines(IzumReadTimeoutConfigTest.AZURE_COMPOSE)) {
            Matcher mapping = mappingPattern.matcher(line.strip());
            if (mapping.matches()) {
                assertThat(mappings.put(mapping.group(1), mapping.group(2)))
                        .as("second mapping of %s", mapping.group(1)).isNull();
            }
        }
        return mappings;
    }

    private static BeanWrapper bind(Map<String, Object> environment) {
        return new BeanWrapperImpl(
                MainApplicationYaml.bind("parkio.municipal", MunicipalSourceProperties.class, environment));
    }

    /** {@code PARKIO_MUNICIPAL_KONYA_FIXED_DELAY_MS} is {@code konya.fixedDelayMs}. */
    private static String propertyPath(String source, String variable) {
        String[] words = variable.substring(env(source, "").length()).toLowerCase(Locale.ROOT).split("_");
        StringBuilder path = new StringBuilder(source).append('.').append(words[0]);
        for (int i = 1; i < words.length; i++) {
            path.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return path.toString();
    }

    private static String envName(String property) {
        return property.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static String envSegment(String source) {
        return source.toUpperCase(Locale.ROOT);
    }

    private static String env(String source, String setting) {
        return "PARKIO_MUNICIPAL_" + envSegment(source) + "_" + setting;
    }
}
