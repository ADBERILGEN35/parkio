package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.port.DurableErasureRecord;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The record the auth service hands to a durable store must carry the {@code bodyDigest} of
 * evidence format v2 (unchanged since v1), which the Python verifier recomputes; any other
 * digest makes every published record fail with "pending body digest mismatch". Fixtures:
 * {@code src/test/resources/durable-erasure-evidence/v2} (generated from the Python model).
 */
class DurableErasureRecordFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void recordDigestIsTheFormatBodyDigest() throws IOException {
        for (JsonNode input : fixture("inputs.json")) {
            String requestId = input.path("erasureRequestId").asText();
            DurableErasureRecord record = DurableErasureRecord.of(
                    UUID.fromString(requestId),
                    UUID.fromString(input.path("authUserId").asText()),
                    Instant.parse(input.path("erasedAt").asText()));
            JsonNode published = fixture("cases/valid/store/records/" + requestId + ".json");

            assertThat(record.bodyDigest()).as(requestId).isEqualTo(published.path("bodyDigest").asText());
        }
    }

    private static JsonNode fixture(String relative) throws IOException {
        try (InputStream in = DurableErasureRecordFormatTest.class.getClassLoader()
                .getResourceAsStream("durable-erasure-evidence/v2/" + relative)) {
            assertThat(in).as(relative).isNotNull();
            return JSON.readTree(in);
        }
    }
}
