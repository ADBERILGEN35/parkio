package com.parkio.auth.infrastructure.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.parkio.auth.application.durable.TrustedErasureSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The verdict document ({@value #FORMAT}): what the command did, written atomically to
 * {@code --verdict-out} for every outcome once the arguments parsed. Only a COMPLETE verdict with
 * exit code 0 and no missing or failed acknowledgement can open the isolated restore's expose gate.
 */
final class RecoveryReplayVerdict {

    static final String FORMAT = "parkio-recovery-replay-verdict";
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Map<String, Object> fields = new LinkedHashMap<>();

    RecoveryReplayVerdict(RecoveryReplayArguments arguments) {
        fields.put("format", FORMAT);
        fields.put("version", 1);
        fields.put("recoveryAttemptId", arguments.attempt().toString());
        fields.put("restoredDatasetId", arguments.dataset());
    }

    /** The verified set: its digest, coverage (only as the verified sequence) and size. */
    void set(TrustedErasureSet set) {
        fields.put("erasureSetDigest", set.erasureSetDigest());
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("verifiedThroughSequence", set.verifiedThroughSequence());
        coverage.put("frontierVersion", set.frontierVersion());
        coverage.put("latestTrustedCheckpoint", set.latestTrustedCheckpoint());
        // Frontier versions that failed verification: tamper evidence, reported, never hidden.
        coverage.put("ignoredFrontierVersions", set.ignoredFrontierVersions());
        coverage.put("statement", set.statement());
        fields.put("coverage", coverage);
        fields.put("users", set.entries().size());
    }

    void put(String field, Object value) {
        fields.put(field, value);
    }

    Object get(String field) {
        return fields.get(field);
    }

    Map<String, Object> fields() {
        return fields;
    }

    /** Sets the outcome and writes the file; a failed write is reported as INTERNAL, never success. */
    RecoveryReplayExit finish(RecoveryReplayExit exit, Path target) {
        fields.put("status", exit.name());
        fields.put("exitCode", exit.code());
        if (write(target)) {
            return exit;
        }
        fields.put("status", RecoveryReplayExit.INTERNAL.name());
        fields.put("exitCode", RecoveryReplayExit.INTERNAL.code());
        fields.put("reason", "the verdict file could not be written");
        return RecoveryReplayExit.INTERNAL;
    }

    private boolean write(Path target) {
        try {
            Path absolute = target.toAbsolutePath();
            Path temporary = Files.createTempFile(absolute.getParent(), ".recovery-verdict-", ".json");
            Files.write(temporary, JSON.writeValueAsBytes(fields));
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }
}
