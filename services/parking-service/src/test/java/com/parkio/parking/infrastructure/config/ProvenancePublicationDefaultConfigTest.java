package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DATA-WP-11: documents and guards canonical vs production publication defaults.
 * Does not start DATA-WP-11A or deploy.
 */
class ProvenancePublicationDefaultConfigTest {
    private static final Path ROOT = Path.of("").toAbsolutePath();
    private static final Path MAIN_YML =
            ROOT.resolve("src/main/resources/application.yml");
    private static final Path PROD_YML =
            ROOT.resolve("src/main/resources/application-prod.yml");
    private static final Path AZURE_COMPOSE =
            ROOT.resolve("../../docker/docker-compose.azure-hosted-beta.yml").normalize();

    @Test
    void canonicalApplicationYamlDefaultsPublicationTrue() throws Exception {
        String yaml = Files.readString(MAIN_YML);
        assertThat(yaml).contains(
                "provenance-publication-enabled: ${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:true}");
        assertThat(yaml).contains(
                "provenance-ingest-write-enabled: ${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_INGEST_WRITE_ENABLED:true}");
        assertThat(yaml).contains("automatic-linking-enabled: false");
    }

    @Test
    void productionProfileYamlPinsPublicationFalse() throws Exception {
        String yaml = Files.readString(PROD_YML);
        assertThat(yaml).contains(
                "provenance-publication-enabled: ${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:false}");
    }

    @Test
    void azureHostedBetaComposeDefaultsPublicationTrue() throws Exception {
        String compose = Files.readString(AZURE_COMPOSE);
        assertThat(compose).contains(
                "PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED: "
                        + "${PARKIO_MUNICIPAL_REGISTRY_PROVENANCE_PUBLICATION_ENABLED:-true}");
        assertThat(compose).contains("PARKIO_MUNICIPAL_REGISTRY_AUTOMATIC_LINKING_ENABLED: \"false\"");
        assertThat(compose)
                .contains("PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED: "
                        + "${PARKIO_MUNICIPAL_IZELMAN_FACILITY_PUBLICATION_ENABLED:-false}");
    }
}
