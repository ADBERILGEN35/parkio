package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import com.parkio.auth.application.port.DurableErasureRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Java producer and verifier against the cross-language fixtures (format v2): Java writes
 * byte-identical objects to the Python reference producer, which the Python verifier accepts,
 * reaches the same verdict or error as the Python verifier for every case under the case's
 * trust and verification instant, and its trust-document loader accepts and refuses the same
 * documents with the same messages.
 */
class DurableErasureEvidenceInteropTest {

    private static final String DATABASE = DurableEvidenceFixtures.producer().path("databaseIdentity").asText();
    private static final TrustedKey PRODUCER = DurableEvidenceFixtures.signingKey();

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
    void javaWritesTheReferenceCheckpointAndItsReservationByteForByte() {
        List<ErasureLedgerEntry> entries = new ArrayList<>();
        for (JsonNode input : DurableEvidenceFixtures.json("inputs.json")) {
            entries.add(new ErasureLedgerEntry(UUID.fromString(input.path("authUserId").asText()),
                    Instant.parse(input.path("erasedAt").asText())));
        }
        // The writer orders the ledger itself.
        Collections.reverse(entries);

        assertThat(DurableErasureEvidence.checkpoint(3, entries, DATABASE, PRODUCER))
                .isEqualTo(DurableEvidenceFixtures.bytes(
                        "cases/checkpoint-ledger/store/" + DurableErasureEvidence.checkpointKey(3)));
        assertThat(DurableErasureEvidence.sequenceMarker(3, DurableErasureEvidence.CHECKPOINT_RESERVATION))
                .isEqualTo(DurableEvidenceFixtures.bytes(
                        "cases/checkpoint-ledger/store/" + DurableErasureEvidence.sequenceKey(3)));
    }

    @Test
    void checkpointLedgerFollowsPostgresUuidOrderNotUuidCompareTo() {
        UUID high = UUID.fromString("80000000-0000-4000-8000-000000000001");
        UUID low = UUID.fromString("10000000-0000-4000-8000-000000000001");
        // UUID.compareTo compares signed longs: 0x8000... is negative and sorts first.
        assertThat(high.compareTo(low)).isNegative();

        List<Map<String, Object>> ledger = DurableErasureEvidence.ledgerEntries(List.of(
                new ErasureLedgerEntry(high, Instant.parse("2026-09-29T08:16:00Z")),
                new ErasureLedgerEntry(low, Instant.parse("2026-09-29T08:15:30.123456789Z"))));

        assertThat(ledger).extracting(entry -> entry.get("authUserId")).containsExactly(low.toString(), high.toString());
        assertThat(ledger.get(0).get("erasedAt")).isEqualTo("2026-09-29T08:15:30.123456Z");
    }

    @Test
    void aCheckpointWithTwoEntriesForOneUserIsNotWritten() {
        UUID user = UUID.randomUUID();
        List<ErasureLedgerEntry> duplicated = List.of(
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")),
                new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:17:00Z")));

        assertThatThrownBy(() -> DurableErasureEvidence.checkpoint(1, duplicated, DATABASE, PRODUCER))
                .isInstanceOf(IllegalArgumentException.class);
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
    void trustedKeyNeverPrintsTheKey() {
        String secretHex = java.util.HexFormat.of().formatHex(PRODUCER.key());
        String secretText = new String(PRODUCER.key(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(PRODUCER.toString()).doesNotContain(secretHex).doesNotContain(secretText).contains("<redacted>");
    }

    @Test
    void trustDocumentLoaderMatchesTheReferenceLoader() {
        JsonNode cases = DurableEvidenceFixtures.json("trust-documents.json");
        assertThat(cases.size()).isGreaterThanOrEqualTo(14);
        for (JsonNode trustCase : cases) {
            String name = trustCase.path("name").asText();
            byte[] document = trustCase.path("document").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (trustCase.path("error").isNull()) {
                EvidenceTrust trust = EvidenceTrust.parse(document);
                assertThat(trust.databaseIdentity()).as(name).isEqualTo(DATABASE);
                assertThat(trust.key(PRODUCER.keyId())).as(name).isPresent();
            } else {
                assertThatThrownBy(() -> EvidenceTrust.parse(document))
                        .as(name)
                        .isInstanceOf(DurableEvidenceException.class)
                        .hasMessage(trustCase.path("error").asText());
            }
        }
    }

    static Stream<String> cases() {
        return DurableEvidenceFixtures.caseNames().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void javaVerifierReachesTheReferenceOutcome(String caseName) {
        JsonNode expected = DurableEvidenceFixtures.json("cases/" + caseName + "/expected.json");
        DurableErasureEvidenceVerifier verifier = new DurableErasureEvidenceVerifier(
                DurableEvidenceFixtures.trust(expected), Instant.parse(expected.path("verifiedAt").asText()));
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
