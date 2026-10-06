package com.parkio.auth.application.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The file the isolated restore hands to the recovery-replay command (format {@value #FORMAT},
 * written by {@code trusted_set_document} in {@code scripts/lib/recovery_evidence_bundle.py}). It
 * names the recovery attempt, the restored dataset and the isolated target, and embeds the evidence
 * bundle with the coverage and erasure set the restore derived from it. Nothing in it is trusted:
 * {@link #verify} re-derives everything from the bundle with the command's own trust document and
 * refuses any difference.
 */
public record TrustedErasureSetDocument(String recoveryAttemptId,
                                        String restoredDatasetId,
                                        String targetIdentity,
                                        String evidenceDatabaseIdentity,
                                        long verifiedThroughSequence,
                                        String frontierVersion,
                                        Long latestTrustedCheckpoint,
                                        String statement,
                                        String erasureSetDigest,
                                        List<Map<String, String>> entries,
                                        EvidenceBundle bundle) {

    public static final String FORMAT = "parkio-trusted-erasure-set";
    public static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public TrustedErasureSetDocument {
        entries = List.copyOf(entries);
    }

    public static TrustedErasureSetDocument parse(byte[] raw) {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (IOException ex) {
            throw new DurableEvidenceException("trusted-set file is not JSON");
        }
        if (root == null || !root.isObject() || !FORMAT.equals(root.path("format").asText(null))
                || !root.path("version").isIntegralNumber() || root.path("version").asLong() != VERSION) {
            throw new DurableEvidenceException("unsupported trusted-set file format");
        }
        JsonNode coverage = root.path("coverage");
        JsonNode set = root.path("erasureSet");
        if (!coverage.path("verifiedThroughSequence").isIntegralNumber() || !set.path("entries").isArray()) {
            throw new DurableEvidenceException("trusted-set file is incomplete");
        }
        List<Map<String, String>> entries = new ArrayList<>();
        for (JsonNode entry : set.path("entries")) {
            entries.add(Map.of("authUserId", entry.path("authUserId").asText(""),
                    "erasedAt", entry.path("erasedAt").asText("")));
        }
        JsonNode checkpoint = coverage.path("latestTrustedCheckpoint");
        return new TrustedErasureSetDocument(
                text(root, "recoveryAttemptId"), text(root, "restoredDatasetId"), text(root, "targetIdentity"),
                text(root, "evidenceDatabaseIdentity"), coverage.path("verifiedThroughSequence").asLong(),
                text(coverage, "frontierVersion"), checkpoint.isIntegralNumber() ? checkpoint.asLong() : null,
                text(coverage, "statement"), text(set, "erasureSetDigest"), entries,
                EvidenceBundle.parse(root.path("bundle")));
    }

    /**
     * Re-derives the set from the embedded bundle with {@code verifier} (the command's trust and
     * clock) and returns it if the file states exactly that set; otherwise refuses.
     */
    public TrustedErasureSet verify(DurableErasureEvidenceVerifier verifier, String trustedDatabaseIdentity) {
        TrustedErasureSet derived = TrustedErasureSet.extract(bundle, verifier);
        List<Map<String, String>> derivedEntries = derived.entries().stream()
                .map(entry -> Map.of("authUserId", entry.authUserId().toString(),
                        "erasedAt", DurableErasureEvidence.erasedAt(entry.erasedAt())))
                .toList();
        boolean same = trustedDatabaseIdentity.equals(evidenceDatabaseIdentity)
                && derived.verifiedThroughSequence() == verifiedThroughSequence
                && derived.frontierVersion().equals(frontierVersion)
                && java.util.Objects.equals(derived.latestTrustedCheckpoint(), latestTrustedCheckpoint)
                && derived.statement().equals(statement)
                && derived.erasureSetDigest().equals(erasureSetDigest)
                && derivedEntries.equals(entries);
        if (!same) {
            throw new DurableEvidenceException("trusted-set file does not match its evidence");
        }
        return derived;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new DurableEvidenceException("trusted-set file has no " + field);
        }
        return value.asText();
    }
}
