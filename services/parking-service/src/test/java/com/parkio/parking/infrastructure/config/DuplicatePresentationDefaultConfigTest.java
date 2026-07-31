package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-12: guards canonical vs production duplicate-presentation defaults.
 * Does not start DATA-WP-12A or deploy.
 */
class DuplicatePresentationDefaultConfigTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path MAIN_YML =
            ROOT.resolve("src/main/resources/application.yml");
    private static final Path PROD_YML =
            ROOT.resolve("src/main/resources/application-prod.yml");
    private static final Path AZURE_COMPOSE =
            ROOT.resolve("../../docker/docker-compose.azure-hosted-beta.yml").normalize();

    @Test
    void javaDiscoveryDefaultIsTrue() {
        assertThat(new MunicipalSourceProperties().getDiscovery().isDuplicatePresentationEnabled())
                .isTrue();
    }

    @Test
    void canonicalApplicationYamlDefaultsDuplicatePresentationTrue() throws Exception {
        String yaml = Files.readString(MAIN_YML);
        assertThat(yaml).contains(
                "duplicate-presentation-enabled: ${PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED:true}");
        assertThat(yaml).contains(
                "duplicate-radius-meters: ${PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_RADIUS_METERS:100}");
        assertThat(yaml).contains(
                "overfetch-factor: ${PARKIO_MUNICIPAL_DISCOVERY_OVERFETCH_FACTOR:2}");
        assertThat(yaml).contains(
                "overfetch-absolute-max: ${PARKIO_MUNICIPAL_DISCOVERY_OVERFETCH_ABSOLUTE_MAX:200}");
    }

    @Test
    void productionProfileYamlPinsDuplicatePresentationFalse() throws Exception {
        String yaml = Files.readString(PROD_YML);
        assertThat(yaml).contains(
                "duplicate-presentation-enabled: ${PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED:false}");
    }

    @Test
    void azureHostedBetaComposeDefaultsDuplicatePresentationTrue() throws Exception {
        String compose = Files.readString(AZURE_COMPOSE);
        assertThat(compose).contains(
                "PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED: "
                        + "${PARKIO_MUNICIPAL_DISCOVERY_DUPLICATE_PRESENTATION_ENABLED:-true}");
        assertThat(compose).contains("PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED: \"false\"");
        assertThat(compose)
                .contains("PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED: "
                        + "${PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED:-false}");
    }
}