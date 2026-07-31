package com.parkio.parking.externalsource.registry.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Structural guardrail for DATA-WP-10 ingest provenance allow-list and publication separation. */
class IngestProvenanceBoundaryTest {

    @Test
    void ingestPolicyUsesPublicationAllowListAndDoesNotTouchLinks() throws IOException {
        Path policy = locate(
                "services/parking-service/src/main/java/com/parkio/parking/externalsource/registry/IngestFieldProvenancePolicy.java");
        Path service = locate(
                "services/parking-service/src/main/java/com/parkio/parking/application/FieldProvenanceApplicationService.java");
        assertThat(policy).exists();
        assertThat(service).exists();
        String policySource = Files.readString(policy, StandardCharsets.UTF_8);
        String serviceSource = Files.readString(service, StandardCharsets.UTF_8);

        assertThat(policySource).contains("PUBLIC_FIELD_ALLOWLIST");
        assertThat(policySource).doesNotContain("TARIFF_ASSIGNMENT");
        assertThat(policySource).doesNotContain("municipal_link_candidates");
        assertThat(serviceSource).contains("skipped_other_source");
        assertThat(serviceSource).contains("isProvenanceIngestWriteEnabled");
        assertThat(serviceSource).doesNotContain("isProvenancePublicationEnabled()");
    }

    private static Path locate(String relative) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(relative);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromModule = cwd.resolve(relative.replace("services/parking-service/", ""));
        if (Files.exists(fromModule)) {
            return fromModule;
        }
        return direct;
    }
}
