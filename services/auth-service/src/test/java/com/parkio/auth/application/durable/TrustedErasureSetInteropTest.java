package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The trusted erasure set (U02 stage 4) against the cross-language fixtures in
 * {@code durable-erasure-evidence/v2/bundles}, written by the Python model
 * ({@code scripts/lib/recovery_evidence_bundle.py}): Java must derive the same set, coverage and
 * frontier version from every bundle, or refuse it with the same message.
 */
class TrustedErasureSetInteropTest {

    /** A coverage report must never claim a time or the absence of later erasures. */
    static final Pattern FORBIDDEN_CLAIMS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T|no later|absen|cutoff|complete coverage|all erasures|until now",
            Pattern.CASE_INSENSITIVE);

    static Stream<String> bundles() {
        List<String> names = new ArrayList<>();
        DurableEvidenceFixtures.json("bundles.json").forEach(name -> names.add(name.asText()));
        return names.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bundles")
    void javaDerivesTheReferenceSetFromEveryBundle(String name) {
        JsonNode expected = DurableEvidenceFixtures.json("bundles/" + name + "/expected.json");
        DurableErasureEvidenceVerifier verifier = new DurableErasureEvidenceVerifier(
                DurableEvidenceFixtures.trust(expected), Instant.parse(expected.path("verifiedAt").asText()));
        JsonNode result = expected.path("result");
        JsonNode bundle = DurableEvidenceFixtures.json("bundles/" + name + "/bundle.json");

        if (result.has("error")) {
            assertThatThrownBy(() -> TrustedErasureSet.extract(EvidenceBundle.parse(bundle), verifier))
                    .as(name).isInstanceOf(DurableEvidenceException.class)
                    .hasMessage(result.path("error").asText());
            return;
        }
        TrustedErasureSet set = TrustedErasureSet.extract(EvidenceBundle.parse(bundle), verifier);
        assertThat(set.verifiedThroughSequence()).as(name).isEqualTo(result.path("verifiedThroughSequence").asLong());
        assertThat(set.frontierVersion()).as(name).isEqualTo(result.path("frontierVersion").asText());
        assertThat(set.latestTrustedCheckpoint()).as(name).isEqualTo(
                result.path("latestTrustedCheckpoint").isNull() ? null : result.path("latestTrustedCheckpoint").asLong());
        assertThat(set.ignoredFrontierVersions()).as(name).isEqualTo(result.path("ignoredFrontierVersions").asInt());
        assertThat(set.erasureSetDigest()).as(name).isEqualTo(result.path("erasureSetDigest").asText());
        assertThat(set.statement()).as(name).isEqualTo(result.path("statement").asText());
        List<String> entries = set.entries().stream()
                .map(entry -> entry.authUserId() + "@" + DurableErasureEvidence.erasedAt(entry.erasedAt())).toList();
        List<String> expectedEntries = new ArrayList<>();
        result.path("entries").forEach(entry ->
                expectedEntries.add(entry.path("authUserId").asText() + "@" + entry.path("erasedAt").asText()));
        assertThat(entries).as(name).isEqualTo(expectedEntries);
    }

    @Test
    void theCoverageStatementNamesOnlyTheVerifiedSequenceAndFrontierVersion() {
        String statement = TrustedErasureSet.statement(4, "v-0002");

        assertThat(statement).isEqualTo("erasure coverage verified through sequence 4 (frontier version v-0002)");
        assertThat(FORBIDDEN_CLAIMS.matcher(statement).find()).isFalse();
    }

    @Test
    void theCommittedTrustedSetDocumentVerifiesAgainstItsEvidence() {
        TrustedErasureSetDocument document = TrustedErasureSetDocument.parse(
                DurableEvidenceFixtures.bytes("trusted-set.json"));

        TrustedErasureSet set = document.verify(verifier(), "postgresql:7000000000000000001:parkio_auth");

        assertThat(set.erasureSetDigest()).isEqualTo(document.erasureSetDigest());
        assertThat(set.entries()).hasSize(4);
        assertThat(document.statement()).isEqualTo(set.statement());
        assertThat(FORBIDDEN_CLAIMS.matcher(document.statement()).find()).isFalse();
    }

    @Test
    void aTrustedSetDocumentThatStatesAnythingElseIsRefused() {
        List<Consumer<ObjectNode>> edits = List.of(
                root -> ((ObjectNode) root.path("erasureSet")).put("erasureSetDigest", "0".repeat(64)),
                root -> ((ArrayNode) root.path("erasureSet").path("entries")).remove(0),
                root -> ((ObjectNode) root.path("coverage")).put("verifiedThroughSequence", 5),
                root -> ((ObjectNode) root.path("coverage")).put("frontierVersion", "v-other"),
                root -> ((ObjectNode) root.path("coverage")).put("statement",
                        "erasure coverage verified through 2026-10-01T00:00:00Z"),
                root -> root.put("evidenceDatabaseIdentity", "postgresql:7000000000000000002:parkio_auth"),
                // Review N1: tamper evidence cannot be dropped from the file.
                root -> ((ObjectNode) root.path("coverage")).put("ignoredFrontierVersions", 1));
        for (Consumer<ObjectNode> edit : edits) {
            ObjectNode root = (ObjectNode) DurableEvidenceFixtures.json("trusted-set.json").deepCopy();
            edit.accept(root);
            TrustedErasureSetDocument document = TrustedErasureSetDocument.parse(
                    root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> document.verify(verifier(), "postgresql:7000000000000000001:parkio_auth"))
                    .isInstanceOf(DurableEvidenceException.class)
                    .hasMessage("trusted-set file does not match its evidence");
        }
    }

    @Test
    void javaEncodesTheSameBundleThePythonExportWrites() throws Exception {
        for (String name : List.of("valid", "checkpoint-tail", "frontier-versions-newest-first")) {
            JsonNode committed = DurableEvidenceFixtures.json("bundles/" + name + "/bundle.json");
            Map<String, byte[]> objects = new java.util.TreeMap<>();
            committed.path("objects").properties().forEach(field ->
                    objects.put(field.getKey(), java.util.Base64.getDecoder().decode(field.getValue().asText())));
            List<EvidenceBundle.FrontierVersion> versions = new ArrayList<>();
            committed.path("frontierVersions").forEach(version -> versions.add(new EvidenceBundle.FrontierVersion(
                    version.path("versionId").asText(),
                    java.util.Base64.getDecoder().decode(version.path("data").asText()))));

            JsonNode encoded = DurableEvidenceFixtures.JSON.readTree(
                    EvidenceBundle.encode(objects, versions, committed.path("source").asText()));

            assertThat(encoded.path("bundleDigest").asText()).as(name).isEqualTo(committed.path("bundleDigest").asText());
            assertThat(encoded).as(name).isEqualTo(committed);
        }
    }

    @Test
    void aBundleChangedAfterExportIsRefusedBeforeAnythingIsVerified() {
        ObjectNode root = (ObjectNode) DurableEvidenceFixtures.json("trusted-set.json").deepCopy();
        ((ObjectNode) root.path("bundle")).put("source", "edited");

        assertThatThrownBy(() -> TrustedErasureSetDocument.parse(
                root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("evidence bundle digest mismatch");
    }

    @Test
    void theFrontierIsReadOnlyThroughItsVersions() {
        EvidenceBundle bundle = EvidenceBundle.parse(DurableEvidenceFixtures.json("bundles/valid/bundle.json"));

        assertThatThrownBy(() -> bundle.find(DurableErasureEvidence.FRONTIER_KEY))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("the frontier is versioned; read all of its versions");
        assertThat(bundle.findAll(DurableErasureEvidence.FRONTIER_KEY)).hasSize(1);
        assertThatThrownBy(() -> bundle.findAll("records/x.json"))
                .isInstanceOf(DurableEvidenceException.class);
    }

    @Test
    void malformedBundlesAreRefusedWithTheModelsMessages() {
        ObjectNode valid = (ObjectNode) DurableEvidenceFixtures.json("bundles/valid/bundle.json");
        ObjectNode wrongFormat = valid.deepCopy().put("format", "something-else");
        assertThatThrownBy(() -> EvidenceBundle.parse(wrongFormat)).hasMessage("unsupported evidence bundle format");

        ObjectNode badKey = valid.deepCopy();
        ((ObjectNode) badKey.path("objects")).put("../escape", "eA==");
        assertThatThrownBy(() -> EvidenceBundle.parse(withDigest(badKey)))
                .hasMessage("bundle object key is not an evidence key: '../escape'");

        ObjectNode notBase64 = valid.deepCopy();
        String key = notBase64.path("objects").fieldNames().next();
        ((ObjectNode) notBase64.path("objects")).put(key, "%%%");
        assertThatThrownBy(() -> EvidenceBundle.parse(withDigest(notBase64))).hasMessage("bundle object is not base64 text");

        ObjectNode repeated = valid.deepCopy();
        ArrayNode versions = (ArrayNode) repeated.path("frontierVersions");
        versions.add(versions.get(0).deepCopy());
        assertThatThrownBy(() -> EvidenceBundle.parse(withDigest(repeated)))
                .hasMessage("evidence bundle repeats a frontier version id");
    }

    private static ObjectNode withDigest(ObjectNode bundle) {
        return bundle.put("bundleDigest", EvidenceBundle.contentDigest(bundle));
    }

    private static DurableErasureEvidenceVerifier verifier() {
        JsonNode expected = DurableEvidenceFixtures.json("bundles/checkpoint-tail/expected.json");
        return new DurableErasureEvidenceVerifier(DurableEvidenceFixtures.trust(expected),
                Instant.parse(expected.path("verifiedAt").asText()));
    }
}
