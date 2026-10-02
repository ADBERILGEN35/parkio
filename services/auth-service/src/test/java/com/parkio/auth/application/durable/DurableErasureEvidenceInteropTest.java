package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.port.DurableErasureRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Java producer and verifier against the cross-language fixtures (format v1): Java writes
 * byte-identical objects to the Python reference producer, which the Python verifier accepts,
 * and reaches the same verdict or error as the Python verifier for every case.
 */
class DurableErasureEvidenceInteropTest {

    private static final String DATABASE = DurableEvidenceFixtures.producer().path("databaseIdentity").asText();
    private static final ProducerKey PRODUCER = new ProducerKey(
            DurableEvidenceFixtures.producer().path("producerId").asText(),
            DurableEvidenceFixtures.key("producerKeyHex"));

    @Test
    void javaWritesTheReferenceRecordsMarkersAndFrontierByteForByte() {
        for (JsonNode input : DurableEvidenceFixtures.json("inputs.json")) {
            UUID requestId = UUID.fromString(input.path("erasureRequestId").asText());
            long sequence = input.path("sequence").asLong();
            DurableErasureRecord record = DurableErasureRecord.of(
                    requestId, UUID.fromString(input.path("authUserId").asText()),
                    Instant.parse(input.path("erasedAt").asText()));

            assertThat(DurableErasureEvidence.recordKey(requestId))
                    .isEqualTo("records/" + requestId + ".json");
            assertThat(DurableErasureEvidence.pendingRecord(record, sequence, DATABASE, PRODUCER))
                    .as("record %s", requestId)
                    .isEqualTo(DurableEvidenceFixtures.bytes("cases/valid/store/" + DurableErasureEvidence.recordKey(requestId)));
            assertThat(DurableErasureEvidence.sequenceMarker(sequence, requestId))
                    .as("marker %d", sequence)
                    .isEqualTo(DurableEvidenceFixtures.bytes("cases/valid/store/" + DurableErasureEvidence.sequenceKey(sequence)));
        }
        assertThat(DurableErasureEvidence.frontier(3, 3, DATABASE, PRODUCER))
                .isEqualTo(DurableEvidenceFixtures.bytes("cases/valid/store/" + DurableErasureEvidence.FRONTIER_KEY));
    }

    @Test
    void erasedAtIsInstantTextAtMicrosecondPrecision() {
        for (JsonNode input : DurableEvidenceFixtures.json("inputs.json")) {
            String erasedAt = input.path("erasedAt").asText();
            assertThat(DurableErasureEvidence.erasedAt(Instant.parse(erasedAt))).isEqualTo(erasedAt);
        }
        assertThat(DurableErasureEvidence.erasedAt(Instant.parse("2026-09-29T08:15:30.123456789Z")))
                .isEqualTo("2026-09-29T08:15:30.123456Z");
    }

    @Test
    void aRecordWhoseDigestDoesNotMatchItsFieldsIsNotPublished() {
        DurableErasureRecord inconsistent = new DurableErasureRecord(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-09-29T08:16:00Z"), "0".repeat(64));

        assertThatThrownBy(() -> DurableErasureEvidence.pendingRecord(inconsistent, 1, DATABASE, PRODUCER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void frontierBoundariesAreOrdered() {
        assertThatThrownBy(() -> DurableErasureEvidence.frontier(3, 2, DATABASE, PRODUCER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DurableErasureEvidence.frontier(-1, 0, DATABASE, PRODUCER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void producerKeyNeverPrintsTheKey() {
        assertThat(PRODUCER.toString()).doesNotContain(DurableEvidenceFixtures.producer().path("producerKeyHex").asText())
                .contains("<redacted>");
    }

    static Stream<String> cases() {
        return DurableEvidenceFixtures.caseNames().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void javaVerifierReachesTheReferenceOutcome(String caseName) {
        JsonNode expected = DurableEvidenceFixtures.json("cases/" + caseName + "/expected.json");
        Map<String, byte[]> keys = new HashMap<>();
        for (JsonNode producer : expected.path("trustedProducers")) {
            String id = producer.asText();
            keys.put(id, id.equals(PRODUCER.producerId())
                    ? DurableEvidenceFixtures.key("producerKeyHex")
                    : DurableEvidenceFixtures.key("otherProducerKeyHex"));
        }
        DurableErasureEvidenceVerifier verifier = new DurableErasureEvidenceVerifier(
                expected.path("expectedDatabaseIdentity").asText(), keys);
        EvidenceObjects store = DurableEvidenceFixtures.store(caseName);

        for (JsonNode check : expected.path("checks")) {
            Long required = check.path("requiredThrough").isNull() ? null : check.path("requiredThrough").asLong();
            if (check.has("error")) {
                assertThatThrownBy(() -> verifier.recover(store, required))
                        .as("%s, requiredThrough %s", caseName, required)
                        .isInstanceOf(DurableEvidenceException.class)
                        .hasMessage(check.path("error").asText());
                continue;
            }
            RecoveryVerdict verdict = verifier.recover(store, required);
            String label = caseName + ", requiredThrough " + required;
            assertThat(verdict.verdict().name()).as(label).isEqualTo(check.path("verdict").asText());
            assertThat(verdict.expectedThrough()).as(label).isEqualTo(longOrNull(check.path("expectedThrough")));
            assertThat(verdict.latestTrustedSequence()).as(label).isEqualTo(longOrNull(check.path("latestTrustedSequence")));
            assertThat(verdict.latestTrustedCheckpoint()).as(label).isEqualTo(longOrNull(check.path("latestTrustedCheckpoint")));
            assertThat(verdict.listedMaximumSequence()).as(label).isEqualTo(longOrNull(check.path("listedMaximumSequence")));
            assertThat(verdict.gaps()).as(label).isEqualTo(longs(check.path("gaps")));
            assertThat(verdict.abandonedReservations()).as(label).isEqualTo(longs(check.path("abandonedReservations")));
            assertThat(verdict.pending().stream().map(VerifiedPending::erasureRequestId).sorted().toList())
                    .as(label).isEqualTo(strings(check.path("pendingRequestIds")));
            assertThat(verdict.completenessEstablished()).as(label).isEqualTo("ACCEPT_ISOLATED".equals(check.path("verdict").asText()));
        }
    }

    private static Long longOrNull(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asLong();
    }

    private static List<Long> longs(JsonNode node) {
        List<Long> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asLong()));
        return values;
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }
}
