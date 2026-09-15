package com.parkio.parking.externalsource.registry.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural guardrail for DATA-WP-09: public provenance publication must not
 * select or serialize confidence, review, link, or candidate internals.
 */
class PublicProvenancePublicationBoundaryTest {

    private static final List<String> FORBIDDEN_SQL_OR_FIELDS = List.of(
            "confidence_or_review_state",
            "source_record_id",
            "selection_reason",
            "source_age_class",
            "reviewed_by",
            "total_score",
            "score_components",
            "rejection_reason",
            "municipal_facility_source_links",
            "municipal_link_candidates",
            "municipal_link_review_audit");

    @Test
    void publicationServiceDoesNotQueryOrExposeInternalProvenanceColumns() throws IOException {
        Path service = locate(
                "services/parking-service/src/main/java/com/parkio/parking/application/RegistryPublicationService.java");
        Path policy = locate(
                "services/parking-service/src/main/java/com/parkio/parking/externalsource/registry/PublicProvenancePublicationPolicy.java");
        assertThat(service).exists();
        assertThat(policy).exists();

        String serviceSource = Files.readString(service, StandardCharsets.UTF_8);
        String policySource = Files.readString(policy, StandardCharsets.UTF_8);

        for (String forbidden : FORBIDDEN_SQL_OR_FIELDS) {
            assertThat(serviceSource)
                    .as("RegistryPublicationService must not reference %s", forbidden)
                    .doesNotContain(forbidden);
            assertThat(policySource)
                    .as("PublicProvenancePublicationPolicy must not reference %s", forbidden)
                    .doesNotContain(forbidden);
        }

        assertThat(serviceSource).contains("SELECT field_name, source_key");
        assertThat(serviceSource).contains("readOnly = true");
        assertThat(policySource).contains("PUBLIC_FIELD_ALLOWLIST");
        assertThat(policySource).doesNotContain("TARIFF_ASSIGNMENT");
    }

    private static Path locate(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path nested = cwd.resolve("parking-service").resolve(relative.replace("services/parking-service/", ""));
        if (Files.exists(nested)) {
            return nested;
        }
        Path fromModule = cwd.resolve(relative.replace("services/parking-service/", ""));
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return direct;
    }
}
