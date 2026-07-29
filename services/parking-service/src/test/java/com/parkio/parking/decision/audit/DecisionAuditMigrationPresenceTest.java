package com.parkio.parking.decision.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DecisionAuditMigrationPresenceTest {

    @Test
    void flywayV20CreatesAppendOnlyDecisionAuditTable() throws Exception {
        Path migration = locate("V20__create_decision_audit.sql");
        String sql = Files.readString(migration, StandardCharsets.UTF_8);
        assertThat(sql).contains("CREATE TABLE decision_audit");
        assertThat(sql).contains("snapshot_json");
        assertThat(sql).doesNotContainIgnoringCase("UPDATE decision_audit");
        assertThat(sql).contains("idx_decision_audit_spot_evaluated");
    }

    private static Path locate(String fileName) {
        Path cwd = Path.of("").toAbsolutePath();
        Path[] candidates = new Path[] {
            cwd.resolve("src/main/resources/db/migration").resolve(fileName),
            cwd.resolve("services/parking-service/src/main/resources/db/migration").resolve(fileName)
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate " + fileName + " from " + cwd);
    }
}