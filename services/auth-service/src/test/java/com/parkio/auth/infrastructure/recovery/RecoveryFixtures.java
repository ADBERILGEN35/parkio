package com.parkio.auth.infrastructure.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Inputs for the recovery-replay command built from the cross-language evidence fixtures
 * ({@code durable-erasure-evidence/v2}): the committed trusted-set document with chosen recovery
 * ids and target, and a trust document for the fixture key. Synthetic values only.
 */
final class RecoveryFixtures {

    static final ObjectMapper JSON = new ObjectMapper();
    /** The identity the fixture evidence is pinned to: "production" for these tests. */
    static final String EVIDENCE_IDENTITY = "postgresql:7000000000000000001:parkio_auth";
    static final String ATTEMPT = "5e5e5e5e-0000-4000-8000-00000000a771";
    static final String DATASET = "backup-stamp-2026-09-29T08-00-00Z";
    static final String TARGET = "postgresql:7000000000000000099:parkio_auth";
    static final int USERS = 4;

    private RecoveryFixtures() {
    }

    static Path fixture(String relative) {
        URL url = RecoveryFixtures.class.getClassLoader().getResource("durable-erasure-evidence/v2/" + relative);
        if (url == null) {
            throw new IllegalStateException("fixture " + relative + " is not on the test classpath");
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static JsonNode json(String relative) {
        try {
            return JSON.readTree(fixture(relative).toFile());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A trust document for the fixture signing key, pinned to {@code identity}. */
    static Path trustDocument(Path dir, String identity) {
        ObjectNode key = JSON.createObjectNode();
        for (JsonNode candidate : json("producer.json").path("keys")) {
            if ("interop-fixture-key-2026a".equals(candidate.path("keyId").asText())) {
                key.put("keyId", "interop-fixture-key-2026a");
                key.put("producerId", candidate.path("producerId").asText());
                key.put("keyHex", candidate.path("keyHex").asText());
                key.put("notBefore", "2026-01-01T00:00:00Z");
            }
        }
        ObjectNode document = JSON.createObjectNode();
        document.put("format", "parkio-erasure-evidence-trust");
        document.put("version", 1);
        document.put("databaseIdentity", identity);
        ((ArrayNode) document.putArray("keys")).add(key);
        return write(dir.resolve("trust.json"), document);
    }

    /** The committed trusted-set document, renamed to {@code attempt}, {@code dataset} and {@code target}. */
    static Path trustedSet(Path dir, String attempt, String dataset, String target) {
        ObjectNode document = (ObjectNode) json("trusted-set.json");
        document.put("recoveryAttemptId", attempt);
        document.put("restoredDatasetId", dataset);
        document.put("targetIdentity", target);
        return write(dir.resolve("trusted-set.json"), document);
    }

    /** The committed trusted-set document with the bundle of fixture case {@code bundleCase}. */
    static Path trustedSetWithBundle(Path dir, String bundleCase) {
        ObjectNode document = (ObjectNode) json("trusted-set.json");
        document.put("recoveryAttemptId", ATTEMPT);
        document.put("restoredDatasetId", DATASET);
        document.put("targetIdentity", TARGET);
        document.set("bundle", json("bundles/" + bundleCase + "/bundle.json"));
        return write(dir.resolve("trusted-set.json"), document);
    }

    static String[] args(Path evidence, Path trust, String attempt, String dataset, String target, Path verdict,
                         String... extra) {
        String[] base = {
            "--spring.profiles.active=recovery-replay",
            "--evidence=" + evidence, "--trust=" + trust, "--attempt=" + attempt, "--dataset=" + dataset,
            "--target-identity=" + target, "--verdict-out=" + verdict,
        };
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    static JsonNode readTree(Path path) {
        try {
            return JSON.readTree(path.toFile());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static Path write(Path path, JsonNode node) {
        try {
            Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            return path;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
