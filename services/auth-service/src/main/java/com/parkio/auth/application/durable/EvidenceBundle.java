package com.parkio.auth.application.durable;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A read-only, self-contained export of the off-host evidence store
 * ({@code scripts/lib/recovery_evidence_bundle.py}, format {@value #FORMAT} version {@value #VERSION}):
 * every pending record, sequence marker and checkpoint, plus every version of the frontier with its
 * store version id. Recovery verifies the bundle itself, never a claim about it, so the restored
 * environment needs no store credentials. Same checks and messages as the Python
 * {@code BundleStore}.
 */
public final class EvidenceBundle implements EvidenceObjects {

    public static final String FORMAT = "parkio-erasure-evidence-bundle";
    public static final int VERSION = 1;
    private static final List<String> OBJECT_PREFIXES = List.of("records/", "sequences/", "checkpoints/");

    /** One stored version of the frontier: the store's version id and the bytes of that version. */
    public record FrontierVersion(String versionId, byte[] bytes) {
    }

    private final Map<String, byte[]> objects;
    private final List<FrontierVersion> frontierVersions;

    private EvidenceBundle(Map<String, byte[]> objects, List<FrontierVersion> frontierVersions) {
        this.objects = objects;
        this.frontierVersions = frontierVersions;
    }

    /** Parses and checks a bundle: format, version, content digest, keys, base64 and version ids. */
    public static EvidenceBundle parse(JsonNode bundle) {
        if (bundle == null || !bundle.isObject()) {
            throw new DurableEvidenceException("evidence bundle is not a JSON object");
        }
        if (!FORMAT.equals(bundle.path("format").asText(null))
                || !bundle.path("version").isIntegralNumber() || bundle.path("version").asLong() != VERSION) {
            throw new DurableEvidenceException("unsupported evidence bundle format");
        }
        if (!contentDigest(bundle).equals(bundle.path("bundleDigest").asText(null))) {
            throw new DurableEvidenceException("evidence bundle digest mismatch");
        }
        JsonNode objectsNode = bundle.path("objects");
        JsonNode versionsNode = bundle.path("frontierVersions");
        if (!objectsNode.isObject() || !versionsNode.isArray()) {
            throw new DurableEvidenceException("evidence bundle is incomplete");
        }
        Map<String, byte[]> objects = new TreeMap<>();
        for (Map.Entry<String, JsonNode> field : objectsNode.properties()) {
            checkKey(field.getKey());
            objects.put(field.getKey(), base64(field.getValue(), "object"));
        }
        List<FrontierVersion> versions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : versionsNode) {
            JsonNode id = item.path("versionId");
            if (!item.isObject() || !id.isTextual() || id.asText().isEmpty()) {
                throw new DurableEvidenceException("evidence bundle frontier version has no id");
            }
            if (!seen.add(id.asText())) {
                throw new DurableEvidenceException("evidence bundle repeats a frontier version id");
            }
            versions.add(new FrontierVersion(id.asText(), base64(item.path("data"), "frontier version")));
        }
        return new EvidenceBundle(Collections.unmodifiableMap(objects), List.copyOf(versions));
    }

    /** SHA-256 of the canonical bundle without its own {@code bundleDigest} field. */
    static String contentDigest(JsonNode bundle) {
        Map<String, Object> content = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = (Map<String, Object>) CanonicalJson.plain(bundle);
        plain.forEach((key, value) -> {
            if (!"bundleDigest".equals(key)) {
                content.put(key, value);
            }
        });
        return DurableErasureEvidence.sha256Hex(CanonicalJson.bytes(content));
    }

    public List<FrontierVersion> frontierVersions() {
        return frontierVersions;
    }

    @Override
    public List<String> list(String prefix) {
        return objects.keySet().stream().filter(key -> key.startsWith(prefix)).sorted().toList();
    }

    @Override
    public Optional<byte[]> find(String key) {
        if (DurableErasureEvidence.FRONTIER_KEY.equals(key)) {
            throw new DurableEvidenceException("the frontier is versioned; read all of its versions");
        }
        return Optional.ofNullable(objects.get(key));
    }

    @Override
    public List<byte[]> findAll(String key) {
        if (!DurableErasureEvidence.FRONTIER_KEY.equals(key)) {
            throw new DurableEvidenceException("only the frontier is versioned: " + key);
        }
        return frontierVersions.stream().map(FrontierVersion::bytes).toList();
    }

    private static void checkKey(String key) {
        boolean evidenceKey = OBJECT_PREFIXES.stream().anyMatch(key::startsWith);
        if (!evidenceKey || List.of(key.split("/", -1)).contains("..")) {
            throw new DurableEvidenceException("bundle object key is not an evidence key: '" + key + "'");
        }
    }

    private static byte[] base64(JsonNode value, String label) {
        if (!value.isTextual()) {
            throw new DurableEvidenceException("bundle " + label + " is not base64 text");
        }
        try {
            return Base64.getDecoder().decode(value.asText());
        } catch (IllegalArgumentException ex) {
            throw new DurableEvidenceException("bundle " + label + " is not base64 text");
        }
    }
}
