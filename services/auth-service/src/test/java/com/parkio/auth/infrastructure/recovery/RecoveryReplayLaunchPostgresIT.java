package com.parkio.auth.infrastructure.recovery;

import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.EVIDENCE_IDENTITY;
import static com.parkio.auth.infrastructure.recovery.RecoveryFixtures.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.parkio.auth.application.durable.DurableErasureEvidence;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier;
import com.parkio.auth.application.durable.EvidenceBundle;
import com.parkio.auth.application.durable.EvidenceTrust;
import com.parkio.auth.application.durable.TrustedErasureSet;
import com.parkio.auth.application.durable.TrustedKey;
import com.parkio.auth.application.port.DurableErasureRecord;
import com.parkio.auth.infrastructure.messaging.ErasureAckKafkaConsumer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The recovery-replay launch as an operator runs it (PR #295 review B1, B2, B5, N4): auth-service's
 * own {@code main} in a separate JVM, with the MAIN configuration (the test classes and test
 * resources are left off its classpath), real PostgreSQL and real Kafka, settings only from the
 * environment. Each refusal must leave the target as it was (no Flyway history, no consumer group)
 * and the accepted run must hold no listening socket, join only the recovery consumer group and
 * run no retention. Synthetic keys, secrets and ids only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 15, unit = TimeUnit.MINUTES)
class RecoveryReplayLaunchPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth")
                    .withUsername("parkio")
                    .withPassword("parkio");

    /** Another cluster, standing for production in the redirect cases (review B6). */
    @Container
    static final PostgreSQLContainer<?> PRODUCTION =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_auth")
                    .withUsername("parkio")
                    .withPassword("parkio");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String LIVE_GROUP_AUTH = "parkio.auth";

    @TempDir Path dir;

    private String database;
    private String connected;
    private String cluster;
    private UUID attempt;
    private String dataset;
    private Path verdict;
    private int timeoutSeconds = 20;

    @BeforeEach
    void freshTarget() throws SQLException {
        database = "launch_target_" + DATABASES.incrementAndGet();
        admin("CREATE DATABASE " + database);
        cluster = query(database, "SELECT system_identifier::text FROM pg_control_system()");
        connected = "postgresql:" + cluster + ":" + database;
        attempt = UUID.randomUUID();
        dataset = "backup-stamp-" + attempt;
        verdict = dir.resolve("verdict.json");
    }

    @Test
    void theProfileWithoutTheFlagIsRefusedAndTouchesNothing() throws Exception {
        Map<String, String> env = env();
        env.remove("PARKIO_ACCOUNT_ERASURE_RESTORE_REPLAY_ENABLED");

        Launch launch = launch(env, fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(written().path("reason").asText()).contains("restore-replay.enabled=false");
        assertUntouched(launch);
    }

    @Test
    void aSpringOptionOnTheCommandLineIsRefused() throws Exception {
        List<String> args = new ArrayList<>(fixtureArgs());
        args.add("--spring.main.web-application-type=servlet");

        Launch launch = launch(env(), args);

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(launch.log()).contains("Spring and logging options are not accepted");
        assertUntouched(launch);
    }

    @Test
    void aWebApplicationTypeFromTheEnvironmentIsRefused() throws Exception {
        Map<String, String> env = env();
        env.put("SPRING_MAIN_WEB_APPLICATION_TYPE", "servlet");

        Launch launch = launch(env, fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(written().path("reason").asText())
                .isEqualTo("the command runs without a web server; spring.main.web-application-type=servlet is refused");
        assertUntouched(launch);
    }

    @Test
    void untrustedEvidenceIsRefusedAndTouchesNothing() throws Exception {
        Path evidence = RecoveryFixtures.trustedSetWithBundle(dir, "gap");
        ObjectNode document = (ObjectNode) RecoveryFixtures.readTree(evidence);
        document.put("recoveryAttemptId", attempt.toString()).put("restoredDatasetId", dataset).put("targetIdentity", connected);
        Files.writeString(evidence, document.toString());

        Launch launch = launch(env(), args(evidence, RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY), connected));

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.INVALID_EVIDENCE.code());
        assertUntouched(launch);
    }

    @Test
    void theProductionDatabaseIsRefusedAndTouchesNothing() throws Exception {
        // The trust document pins the connected database itself as production.
        Evidence evidence = Evidence.pinnedTo(connected, dir);

        Launch launch = launch(env(), args(evidence.trustedSet(attempt, dataset, connected), evidence.trust(), connected));

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(written().path("reason").asText()).isEqualTo("the target is refused: target identity is on the"
                + " cluster of the production identity pinned in the trust document");
        assertUntouched(launch);
    }

    @Test
    void anotherDatabaseOnTheProductionClusterIsRefusedAndTouchesNothing() throws Exception {
        // Production is parkio_auth on this cluster; the target is a scratch database beside it.
        Evidence evidence = Evidence.pinnedTo("postgresql:" + cluster + ":parkio_auth", dir);

        Launch launch = launch(env(), args(evidence.trustedSet(attempt, dataset, connected), evidence.trust(), connected));

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(written().path("reason").asText()).isEqualTo("the target is refused: target identity is on the"
                + " cluster of the production identity pinned in the trust document");
        assertUntouched(launch);
    }

    @Test
    void aBackupFromBeforeV24CannotBeAnchoredAndIsLeftAsItWas() throws Exception {
        migrate("23");
        long history = historyRows();

        Launch launch = launch(env(), fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.INVALID_EVIDENCE.code());
        assertThat(written().path("reason").asText()).isEqualTo("the restored auth database's durable-recording state"
                + " cannot be read, so the evidence cannot be anchored to the backup");
        assertThat(historyRows()).isEqualTo(history);
        assertThat(query(database, "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"))
                .isEqualTo("23");
        assertThat(launch.listeners).isEmpty();
    }

    // Review B6: no connection of the command may reach a database the preflight did not check.

    @Test
    void aPoolUrlRedirectIsRefusedAndNeitherClusterIsWritten() throws Exception {
        migrate(null);
        long history = historyRows();
        Map<String, String> env = env();
        env.put("SPRING_DATASOURCE_HIKARI_JDBCURL", productionUrl());

        Launch launch = launch(env, fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(written().path("reason").asText()).contains("spring.datasource.hikari.jdbc-url");
        assertNothingWritten(launch, history);
    }

    @Test
    void aFlywayConnectionRedirectIsRefusedAndNeitherClusterIsWritten() throws Exception {
        migrate(null);
        long history = historyRows();
        Map<String, String> env = env();
        env.put("SPRING_FLYWAY_URL", productionUrl());
        env.put("SPRING_FLYWAY_USER", PRODUCTION.getUsername());
        env.put("SPRING_FLYWAY_PASSWORD", PRODUCTION.getPassword());

        Launch launch = launch(env, fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(written().path("reason").asText()).contains("spring.flyway.");
        assertNothingWritten(launch, history);
    }

    @Test
    void aMultiHostUrlIsRefusedAndNeitherClusterIsWritten() throws Exception {
        migrate(null);
        long history = historyRows();
        Map<String, String> env = env();
        env.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "," + PRODUCTION.getHost() + ":" + PRODUCTION.getMappedPort(5432) + "/" + database);

        Launch launch = launch(env, fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TARGET_REFUSED.code());
        assertThat(written().path("reason").asText())
                .isEqualTo("spring.datasource.url is not a single-host PostgreSQL URL with allowed parameters only");
        assertNothingWritten(launch, history);
    }

    // Review N10: the profile never reaches an ordinary start, whatever activates it.

    @Test
    void anOrdinaryStartWithTheProfileIncludedIsRefusedAndTouchesNothing() throws Exception {
        Map<String, String> env = env();
        env.remove("SPRING_PROFILES_ACTIVE");
        env.put("SPRING_PROFILES_INCLUDE", RecoveryReplayLaunch.PROFILE);

        Launch launch = launch(env, List.of());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(launch.log()).contains("auth-service refused to start");
        assertUntouched(launch);
    }

    @Test
    void anOrdinaryStartWithALowerCaseProfileVariableIsRefusedAndTouchesNothing() throws Exception {
        Map<String, String> env = env();
        env.remove("SPRING_PROFILES_ACTIVE");
        env.put("spring_profiles_active", RecoveryReplayLaunch.PROFILE);

        Launch launch = launch(env, List.of());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(launch.log()).contains("auth-service refused to start");
        assertUntouched(launch);
    }

    @Test
    void anOrdinaryStartWithTheProfileAsTheDefaultIsRefusedAndTouchesNothing() throws Exception {
        // Review N16 (Y5): a default profile is active when no other profile is.
        Map<String, String> env = env();
        env.remove("SPRING_PROFILES_ACTIVE");
        env.put("SPRING_PROFILES_DEFAULT", RecoveryReplayLaunch.PROFILE);

        Launch launch = launch(env, List.of());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.REFUSED.code());
        assertThat(launch.log()).contains("auth-service refused to start");
        assertUntouched(launch);
    }

    @Test
    void anAcceptedRunServesNothingJoinsOnlyTheRecoveryGroupAndRunsNoRetention() throws Exception {
        migrate(null);
        UUID retained = UUID.randomUUID();
        update("""
                INSERT INTO outbox_events (id, event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at,
                    published, created_at)
                VALUES ('%s', '%s', 'User', '%s', 'UserRegistered', '{}', now() - interval '30 days', true,
                    now() - interval '30 days')
                """.formatted(retained, UUID.randomUUID(), UUID.randomUUID()));
        // Review N11: unpublished rows of the restored copy, for two topics the relay serves.
        UUID restoredRegistration = unpublished("AuthUser", "UserRegistered");
        UUID restoredErasureRequest = unpublished("AccountErasure", "UserErasureRequested");
        timeoutSeconds = 10;

        Launch launch = launch(env(), fixtureArgs());

        assertThat(launch.exit).as(launch.log()).isEqualTo(RecoveryReplayExit.TIMEOUT.code());
        JsonNode written = written();
        assertThat(written.path("status").asText()).isEqualTo("TIMEOUT");
        assertThat(written.path("auth").path("success").asLong()).isEqualTo(USERS);
        written.path("participants").forEach(row -> assertThat(row.path("missing").asLong()).isEqualTo(USERS));
        assertThat(launch.sampled).as("socket samples taken while the command waited").isGreaterThan(3);
        assertThat(launch.listeners).as("listening sockets of the command process").isEmpty();
        Set<String> joined = new HashSet<>(consumerGroups());
        joined.removeAll(launch.groupsBefore());
        assertThat(joined).contains(ErasureAckKafkaConsumer.RECOVERY_GROUP)
                .doesNotContain(ErasureAckKafkaConsumer.GROUP, LIVE_GROUP_AUTH);
        assertThat(query(database, "SELECT count(*) FROM outbox_events WHERE id = '" + retained + "'"))
                .as("a retention-eligible row survives: no retention job runs").isEqualTo("1");
        assertThat(query(database, "SELECT count(*) FROM erasure_restore_attempts WHERE recovery_attempt_id = '"
                + attempt + "'")).isEqualTo("1");
        // N11: the relay published this attempt's replay commands and nothing else of the copy.
        for (UUID row : List.of(restoredRegistration, restoredErasureRequest)) {
            assertThat(query(database, "SELECT published::text || '/' || failure_count FROM outbox_events WHERE id = '"
                    + row + "'")).as("a restored unpublished row stays unpublished and untouched").isEqualTo("false/0");
        }
        assertThat(query(database, """
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'UserErasureRestoreReplayRequested' AND published
                  AND payload::jsonb ->> 'recoveryAttemptId' = '%s'
                """.formatted(attempt))).isEqualTo(String.valueOf(USERS));
        List<JsonNode> erasureTopic = topic("parkio.privacy.erasure");
        assertThat(erasureTopic).as("the erasure topic holds only this attempt's replay commands").hasSize(USERS)
                .allSatisfy(envelope -> {
                    assertThat(envelope.path("eventType").asText()).isEqualTo("UserErasureRestoreReplayRequested");
                    assertThat(envelope.path("payload").path("recoveryAttemptId").asText()).isEqualTo(attempt.toString());
                });
        assertThat(topic("parkio.auth.user")).as("no restored registration was published").isEmpty();
    }

    // ----- launch -----

    private record Launch(int exit, Path logFile, int sampled, Set<String> listeners, Set<String> groupsBefore) {
        String log() {
            try {
                return Files.readString(logFile);
            } catch (IOException ex) {
                return "<no log>";
            }
        }
    }

    private Launch launch(Map<String, String> env, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ProcessHandle.current().info().command().orElse("java"));
        command.add("-Xmx512m");
        command.add("-XX:TieredStopAtLevel=1");
        command.add("-cp");
        command.add(mainClasspath());
        command.add("com.parkio.auth.AuthServiceApplication");
        command.addAll(args);
        Path logFile = dir.resolve("launch-" + UUID.randomUUID() + ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(logFile.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_") || key.startsWith("PARKIO_"));
        builder.environment().putAll(env);
        Set<String> groupsBefore = consumerGroups();
        Process process = builder.start();
        Set<String> listeners = new HashSet<>();
        int sampled = 0;
        long deadline = System.nanoTime() + Duration.ofMinutes(4).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            if (Files.exists(Path.of("/proc/" + process.pid() + "/net/tcp"))) {
                Optional<Set<String>> sample = listeningSockets(process);
                if (sample.isPresent()) {
                    // Review N13: only a complete read of the descriptors and both tables counts.
                    listeners.addAll(sample.get());
                    sampled++;
                }
            }
            process.waitFor(300, TimeUnit.MILLISECONDS);
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            throw new AssertionError("the command did not exit within 4 minutes:\n" + Files.readString(logFile));
        }
        return new Launch(process.exitValue(), logFile, sampled, listeners, groupsBefore);
    }

    /** The test JVM's classpath without the test classes and test resources: main config only. */
    private static String mainClasspath() {
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            String normalized = entry.replace('\\', '/');
            if (!normalized.contains("/build/classes/java/test") && !normalized.contains("/build/resources/test")) {
                entries.add(entry);
            }
        }
        assertThat(entries).anyMatch(entry -> entry.replace('\\', '/').contains("/build/resources/main"));
        return String.join(File.pathSeparator, entries);
    }

    /**
     * LISTEN sockets (TCP state 0A) whose inode is one of the process's own file descriptors, from
     * one complete sample. Empty only when the process exited during the read; any other read
     * failure fails the test, so an unreadable table never passes as "no listener".
     */
    private static Optional<Set<String>> listeningSockets(Process process) {
        long pid = process.pid();
        Set<String> own = new HashSet<>();
        try (var fds = Files.list(Path.of("/proc/" + pid + "/fd"))) {
            for (Path fd : fds.toList()) {
                String link;
                try {
                    link = Files.readSymbolicLink(fd).toString();
                } catch (java.nio.file.NoSuchFileException closed) {
                    continue; // this descriptor closed meanwhile; the others still count
                }
                if (link.startsWith("socket:[")) {
                    own.add(link.substring("socket:[".length(), link.length() - 1));
                }
            }
            Set<String> listening = new HashSet<>();
            for (String table : List.of("tcp", "tcp6")) {
                Path path = Path.of("/proc/" + pid + "/net/" + table);
                if (!Files.exists(path)) {
                    continue;
                }
                // One read: the table changes between reads on a busy host (the first line is the header).
                List<String> lines = Files.readAllLines(path);
                for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                    String[] columns = line.trim().split("\\s+");
                    if (columns.length > 9 && "0A".equals(columns[3]) && own.contains(columns[9])) {
                        listening.add(table + " " + columns[1]);
                    }
                }
            }
            return Optional.of(listening);
        } catch (IOException ex) {
            // An exiting process loses its /proc entries before Java reaps it (isAlive() is still
            // true for a moment): if it ends now, the read was cut short, an incomplete sample.
            if (exitsWithin(process, Duration.ofSeconds(2))) {
                return Optional.empty();
            }
            throw new AssertionError("the command's sockets could not be read: " + ex, ex);
        }
    }

    private static boolean exitsWithin(Process process, Duration bound) {
        try {
            return process.waitFor(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Map<String, String> env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SPRING_PROFILES_ACTIVE", RecoveryReplayLaunch.PROFILE);
        env.put("SPRING_DATASOURCE_URL", url(database));
        env.put("SPRING_DATASOURCE_USERNAME", POSTGRES.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", POSTGRES.getPassword());
        env.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        env.put("PARKIO_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
        env.put("PARKIO_ACCOUNT_ERASURE_RESTORE_REPLAY_ENABLED", "true");
        env.put("PARKIO_PRIVACY_ACCOUNT_ERASURE_RECOVERY_REPLAY_POLL_INTERVAL", "PT0.5S");
        env.put("PARKIO_SECURITY_JWT_GENERATE_EPHEMERAL_KEY", "true");
        env.put("PARKIO_GATEWAY_INTERNAL_SECRET", "launch-it-synthetic-gateway-secret");
        env.put("PARKIO_TRACING_ENABLED", "false");
        return env;
    }

    private List<String> fixtureArgs() {
        Path evidence = RecoveryFixtures.trustedSet(dir, attempt.toString(), dataset, connected);
        return args(evidence, RecoveryFixtures.trustDocument(dir, EVIDENCE_IDENTITY), connected);
    }

    private List<String> args(Path evidence, Path trust, String target) {
        return List.of("--evidence=" + evidence, "--trust=" + trust, "--attempt=" + attempt, "--dataset=" + dataset,
                "--target-identity=" + target, "--verdict-out=" + verdict, "--timeout-seconds=" + timeoutSeconds);
    }

    private JsonNode written() {
        return RecoveryFixtures.readTree(verdict);
    }

    private void assertUntouched(Launch launch) throws Exception {
        assertThat(query(database, "SELECT to_regclass('public.flyway_schema_history') IS NULL")).as("no migration")
                .isEqualTo("t");
        assertThat(query(database, "SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")).as("no table")
                .isEqualTo("0");
        // The Kafka container is shared by the class: no group may appear during this launch.
        assertThat(consumerGroups()).as("no consumer group joined").isEqualTo(launch.groupsBefore());
        assertThat(consumerGroups()).doesNotContain(ErasureAckKafkaConsumer.GROUP, LIVE_GROUP_AUTH);
        assertThat(launch.listeners).isEmpty();
    }

    // ----- database and Kafka -----

    private static String url(String name) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name;
    }

    private static void admin(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url("postgres"), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String query(String name, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(name), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private void update(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(database), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** A restored schema: every migration, or up to {@code target}. */
    private void migrate(String target) {
        var configuration = Flyway.configure().dataSource(url(database), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void assertNothingWritten(Launch launch, long targetHistory) throws Exception {
        assertThat(historyRows()).as("the target was not migrated").isEqualTo(targetHistory);
        assertThat(query(database, "SELECT count(*) FROM erasure_restore_attempts")).as("no attempt").isEqualTo("0");
        assertThat(productionQuery("SELECT to_regclass('public.flyway_schema_history') IS NULL"))
                .as("production was not migrated").isEqualTo("t");
        assertThat(productionQuery("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'"))
                .as("production has no table").isEqualTo("0");
        assertThat(consumerGroups()).as("no consumer group joined").isEqualTo(launch.groupsBefore());
        assertThat(launch.listeners).isEmpty();
    }

    private static String productionUrl() {
        return "jdbc:postgresql://" + PRODUCTION.getHost() + ":" + PRODUCTION.getMappedPort(5432) + "/parkio_auth";
    }

    private static String productionQuery(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(productionUrl(), PRODUCTION.getUsername(),
                PRODUCTION.getPassword()); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    /** An unpublished outbox row of the restored copy. */
    private UUID unpublished(String aggregateType, String eventType) throws SQLException {
        UUID id = UUID.randomUUID();
        update("""
                INSERT INTO outbox_events (id, event_id, aggregate_type, aggregate_id, event_type, payload, occurred_at,
                    published)
                VALUES ('%s', '%s', '%s', '%s', '%s', '{"restored": true}', now() - interval '1 day', false)
                """.formatted(id, UUID.randomUUID(), aggregateType, UUID.randomUUID(), eventType));
        return id;
    }

    /** Every record of {@code topic} from the beginning, as envelopes; empty if the topic does not exist. */
    private static List<JsonNode> topic(String topic) throws Exception {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<JsonNode> envelopes = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic, Duration.ofSeconds(10)).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            if (partitions.isEmpty()) {
                return envelopes;
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("could not read " + topic + " to its end");
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    envelopes.add(RecoveryFixtures.JSON.readTree(record.value()));
                }
            }
        }
        return envelopes;
    }

    private long historyRows() throws SQLException {
        return Long.parseLong(query(database, "SELECT count(*) FROM flyway_schema_history"));
    }

    private static Set<String> consumerGroups() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            return admin.listConsumerGroups().all().get(30, TimeUnit.SECONDS).stream()
                    .map(ConsumerGroupListing::groupId).collect(Collectors.toSet());
        }
    }

    // ----- evidence pinned to a real database identity -----

    /** Signed evidence (one record, frontier 1) pinned to {@code identity}, with its trust document. */
    private record Evidence(String identity, byte[] bundle, Path trust, Path dir) {

        static Evidence pinnedTo(String identity, Path dir) throws IOException {
            byte[] secret = new byte[32];
            new java.security.SecureRandom().nextBytes(secret);
            TrustedKey key = new TrustedKey("launch-it-key", "launch-it-producer", secret,
                    Instant.parse("2026-01-01T00:00:00Z"), null, false);
            UUID request = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            Instant erasedAt = Instant.parse("2026-10-01T08:00:00Z");
            DurableErasureRecord record = new DurableErasureRecord(request, user, erasedAt,
                    DurableErasureEvidence.bodyDigest(user, request, DurableErasureEvidence.erasedAt(erasedAt)));
            byte[] bundle = EvidenceBundle.encode(
                    Map.of(DurableErasureEvidence.recordKey(request),
                            DurableErasureEvidence.pendingRecord(record, 1, identity, key)),
                    List.of(new EvidenceBundle.FrontierVersion("v-1", DurableErasureEvidence.frontier(1, 1, identity, key))),
                    "launch-it");
            Path trust = dir.resolve("trust-" + UUID.randomUUID() + ".json");
            Files.writeString(trust, """
                    {"format": "parkio-erasure-evidence-trust", "version": 1, "databaseIdentity": "%s",
                     "keys": [{"keyId": "launch-it-key", "producerId": "launch-it-producer", "keyHex": "%s",
                               "notBefore": "2026-01-01T00:00:00Z"}]}
                    """.formatted(identity, HexFormat.of().formatHex(secret)));
            return new Evidence(identity, bundle, trust, dir);
        }

        /** The trusted-set document the isolated restore would write for this evidence. */
        Path trustedSet(UUID attempt, String dataset, String target) throws IOException {
            EvidenceTrust parsed = EvidenceTrust.parse(Files.readAllBytes(trust));
            JsonNode bundleNode = RecoveryFixtures.JSON.readTree(bundle);
            TrustedErasureSet set = TrustedErasureSet.extract(EvidenceBundle.parse(bundleNode),
                    new DurableErasureEvidenceVerifier(parsed, Instant.now()));
            ObjectNode document = RecoveryFixtures.JSON.createObjectNode();
            document.put("format", "parkio-trusted-erasure-set").put("version", 1)
                    .put("recoveryAttemptId", attempt.toString()).put("restoredDatasetId", dataset)
                    .put("targetIdentity", target).put("evidenceDatabaseIdentity", identity);
            ObjectNode coverage = document.putObject("coverage");
            coverage.put("verifiedThroughSequence", set.verifiedThroughSequence())
                    .put("frontierVersion", set.frontierVersion())
                    .put("ignoredFrontierVersions", set.ignoredFrontierVersions())
                    .put("statement", set.statement());
            coverage.putNull("latestTrustedCheckpoint");
            ObjectNode erasureSet = document.putObject("erasureSet");
            erasureSet.put("erasureSetDigest", set.erasureSetDigest());
            var entries = erasureSet.putArray("entries");
            set.entries().forEach(entry -> entries.addObject().put("authUserId", entry.authUserId().toString())
                    .put("erasedAt", DurableErasureEvidence.erasedAt(entry.erasedAt())));
            document.set("bundle", bundleNode);
            Path path = dir.resolve("trusted-set-" + UUID.randomUUID() + ".json");
            Files.writeString(path, document.toString(), StandardCharsets.UTF_8);
            return path;
        }
    }
}
