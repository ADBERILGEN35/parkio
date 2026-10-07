package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.ErasureCheckpointProducer;
import com.parkio.auth.application.durable.EvidenceBundle;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.port.DurableErasureCheckpoint;
import com.parkio.auth.infrastructure.persistence.JdbcErasureLedgerCapture;
import io.minio.MinioClient;
import io.minio.messages.RetentionMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Recovery drill tool (U02 T3; coordinator decision D2). Test scope only: it is never packaged in
 * the service image and adds no production entry point. Run through the auth-service Gradle task
 * {@code recoveryDrillTool} against the disposable drill environment only:
 *
 * <ul>
 *   <li>{@code checkpoint}: publishes one erasure checkpoint with the real
 *       {@link ErasureCheckpointProducer} (SHARE-lock capture of the primary auth database, then the
 *       object-lock store). Production has no producer caller; the cadence is an operator decision.</li>
 *   <li>{@code export-bundle <out>}: exports the evidence store as a bundle with the store's own
 *       read rules ({@link ObjectLockEvidenceObjects}: the oldest version of a record, marker or
 *       checkpoint is canonical; every frontier version with its version id). A production export
 *       procedure is a later owner decision.</li>
 * </ul>
 *
 * <p>Settings come from {@code DRILL_*} environment variables, so no secret is on a command line,
 * and nothing secret is printed.
 */
public final class RecoveryDrillTool {

    private RecoveryDrillTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("usage: checkpoint | export-bundle <out>");
        }
        switch (args[0]) {
            case "checkpoint" -> checkpoint();
            case "export-bundle" -> {
                if (args.length != 2) {
                    throw new IllegalArgumentException("usage: export-bundle <out>");
                }
                exportBundle(Path.of(args[1]));
            }
            default -> throw new IllegalArgumentException("unknown drill command " + args[0]);
        }
    }

    private static void checkpoint() throws Exception {
        EvidenceTrust trust = EvidenceTrust.parse(Files.readAllBytes(Path.of(env("DRILL_TRUST_FILE"))));
        ObjectLockDurableErasureRecordStore store = new ObjectLockDurableErasureRecordStore(
                new ObjectLockBucket(client(), env("DRILL_STORE_BUCKET")), trust, env("DRILL_PRODUCER_KEY_ID"),
                RetentionMode.valueOf(env("DRILL_STORE_RETENTION_MODE")),
                Duration.parse(env("DRILL_STORE_RETENTION")), Clock.systemUTC());
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env("DRILL_JDBC_URL"), env("DRILL_JDBC_USER"), env("DRILL_JDBC_PASSWORD"));
        JdbcErasureLedgerCapture capture = new JdbcErasureLedgerCapture(new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource), Duration.ofSeconds(12), Duration.ofSeconds(20));
        DurableErasureCheckpoint checkpoint = new ErasureCheckpointProducer(store, capture).produce();
        System.out.println("drill checkpoint published sequence=" + checkpoint.sequence()
                + " entries=" + checkpoint.entryCount());
    }

    private static void exportBundle(Path out) throws Exception {
        ObjectLockBucket bucket = new ObjectLockBucket(client(), env("DRILL_STORE_BUCKET"));
        ObjectLockEvidenceObjects objects = new ObjectLockEvidenceObjects(bucket);
        Map<String, byte[]> canonical = new LinkedHashMap<>();
        for (String prefix : List.of("records/", "sequences/", "checkpoints/")) {
            for (String key : objects.list(prefix)) {
                canonical.put(key, objects.find(key)
                        .orElseThrow(() -> new IllegalStateException("listed object vanished: " + key)));
            }
        }
        List<EvidenceBundle.FrontierVersion> versions = bucket.allVersions(
                        com.parkio.auth.application.durable.DurableErasureEvidence.FRONTIER_KEY).stream()
                .map(version -> new EvidenceBundle.FrontierVersion(version.versionId(), version.bytes()))
                .toList();
        Files.write(out, EvidenceBundle.encode(canonical, versions, "object-lock:" + env("DRILL_STORE_BUCKET")));
        System.out.println("drill bundle exported objects=" + canonical.size() + " frontierVersions=" + versions.size());
    }

    private static MinioClient client() {
        return MinioClient.builder()
                .endpoint(env("DRILL_STORE_ENDPOINT"))
                .credentials(env("DRILL_STORE_ACCESS_KEY"), env("DRILL_STORE_SECRET_KEY"))
                .region(System.getenv().getOrDefault("DRILL_STORE_REGION", "us-east-1"))
                .build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
