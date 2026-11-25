package com.parkio.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.notification.application.port.DeviceTokenHandover;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * #601 on PostgreSQL: the partial index on {@code device_tokens (token) WHERE active} applies to an empty and to a
 * populated schema, and the token handover's UPDATE uses it with a custom and with a generic plan. The plans are
 * of the statement Hibernate sends, captured on its way to the driver.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeviceTokenIndexPostgresIT {

    private static final String INDEX = "idx_device_tokens_active_token";
    private static final Queue<String> STATEMENTS = new ConcurrentLinkedQueue<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @TestConfiguration
    static class CaptureStatements {
        @Bean
        HibernatePropertiesCustomizer captureStatements() {
            StatementInspector capture = sql -> {
                STATEMENTS.add(sql);
                return sql;
            };
            return properties -> properties.put(AvailableSettings.STATEMENT_INSPECTOR, capture);
        }
    }

    @Autowired private DeviceTokenHandover handover;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void handoverUpdateUsesTheActiveTokenIndex() {
        // 20 000 other rows, a quarter of them inactive, and the handed-over token for three earlier accounts.
        insertTokens(jdbc, 20_000);
        String token = "ExponentPushToken[plan-target]";
        insertToken(jdbc, token, true);
        insertToken(jdbc, token, true);
        insertToken(jdbc, token, false);
        jdbc.execute("ANALYZE device_tokens");

        STATEMENTS.clear();
        UUID newHolder = UUID.randomUUID();
        Integer deactivated = new TransactionTemplate(transactionManager).execute(
                status -> handover.deactivateForOtherUsers(token, newHolder, Instant.now()));
        assertThat(deactivated).isEqualTo(2);
        String update = STATEMENTS.stream()
                .filter(sql -> sql.startsWith("update device_tokens"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no handover UPDATE captured in " + STATEMENTS));
        // Its parameters are, in order, updated_at, token and user_id.
        assertThat(update.chars().filter(c -> c == '?').count()).isEqualTo(3);

        Map<String, String> plans = new TransactionTemplate(transactionManager).execute(status -> {
            String numbered = update.replaceFirst("\\?", "\\$1").replaceFirst("\\?", "\\$2")
                    .replaceFirst("\\?", "\\$3");
            String execute = "EXECUTE handover_plan('2026-11-25T10:00:00Z', '" + token + "', '" + newHolder + "')";
            jdbc.execute("PREPARE handover_plan AS " + numbered);
            try {
                jdbc.execute("SET LOCAL plan_cache_mode = force_custom_plan");
                String custom = String.join("\n", jdbc.queryForList("EXPLAIN " + execute, String.class));
                jdbc.execute("SET LOCAL plan_cache_mode = force_generic_plan");
                String generic = String.join("\n", jdbc.queryForList("EXPLAIN " + execute, String.class));
                return Map.of("custom", custom, "generic", generic);
            } finally {
                jdbc.execute("DEALLOCATE handover_plan");
            }
        });
        System.out.println("Handover UPDATE as sent: " + update);
        plans.forEach((kind, plan) -> System.out.println("EXPLAIN, " + kind + " plan:\n" + plan));

        assertThat(plans.get("custom")).contains(INDEX).doesNotContain("Seq Scan");
        assertThat(plans.get("generic")).contains(INDEX).doesNotContain("Seq Scan");
    }

    @Test
    void indexMigrationAppliesToAPopulatedSchema() {
        DriverManagerDataSource populated = databaseAtV16("device_tokens_populated");
        JdbcTemplate populatedJdbc = new JdbcTemplate(populated);
        insertTokens(populatedJdbc, 5_000);
        // Rows the handover leaves alone today: one token active for two accounts, and inactive copies.
        String shared = "ExponentPushToken[populated-shared]";
        insertToken(populatedJdbc, shared, true);
        insertToken(populatedJdbc, shared, true);
        insertToken(populatedJdbc, shared, false);

        MigrateResult result = migrateTo17(populated);

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("17");
        assertThat(populatedJdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                String.class, INDEX))
                .isEqualTo("CREATE INDEX " + INDEX + " ON public.device_tokens USING btree (token) WHERE active");
        assertThat(populatedJdbc.queryForObject(
                "SELECT indisvalid AND indisready FROM pg_index WHERE indexrelid = ?::regclass",
                Boolean.class, INDEX)).isTrue();
        assertThat(populatedJdbc.queryForObject("SELECT COUNT(*) FROM device_tokens", Long.class)).isEqualTo(5_003);
    }

    /** The path V17 leaves open for a large table: the same index, built CONCURRENTLY before the deploy. */
    @Test
    void anEqualIndexBuiltBeforehandIsKept() {
        DriverManagerDataSource database = databaseAtV16("device_tokens_prebuilt");
        JdbcTemplate prebuilt = new JdbcTemplate(database);
        insertTokens(prebuilt, 1_000);
        prebuilt.execute("CREATE INDEX CONCURRENTLY " + INDEX + " ON device_tokens (token) WHERE active");
        Long before = prebuilt.queryForObject("SELECT ?::regclass::oid", Long.class, INDEX);

        MigrateResult result = migrateTo17(database);

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(prebuilt.queryForObject("SELECT ?::regclass::oid", Long.class, INDEX)).isEqualTo(before);
    }

    static Stream<Arguments> unusableIndexesOfThatName() {
        return Stream.of(
                // What a failed CREATE INDEX CONCURRENTLY leaves behind (set directly; the container user is a
                // superuser).
                Arguments.of("invalid", List.of(
                        "CREATE INDEX " + INDEX + " ON device_tokens (token) WHERE active",
                        "UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + INDEX + "'::regclass")),
                Arguments.of("another definition", List.of(
                        "CREATE INDEX " + INDEX + " ON device_tokens (user_id)")),
                Arguments.of("not partial", List.of(
                        "CREATE INDEX " + INDEX + " ON device_tokens (token)")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableIndexesOfThatName")
    void anUnusableIndexOfThatNameStopsTheMigration(String kind, List<String> setup) {
        DriverManagerDataSource database = databaseAtV16("device_tokens_" + kind.replace(' ', '_'));
        JdbcTemplate prebuilt = new JdbcTemplate(database);
        setup.forEach(prebuilt::execute);

        assertThatThrownBy(() -> migrateTo17(database))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(INDEX + " is not a valid btree index on device_tokens (token) WHERE active");
        assertThat(Flyway.configure().dataSource(database).locations("classpath:db/migration").load()
                .info().current().getVersion().getVersion()).isEqualTo("16");
    }

    /** A new database in the container, migrated to V16, the version before the index. */
    private DriverManagerDataSource databaseAtV16(String name) {
        jdbc.execute("CREATE DATABASE " + name);
        DriverManagerDataSource database = new DriverManagerDataSource(
                "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + name,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(database).locations("classpath:db/migration").target("16").load().migrate();
        return database;
    }

    /** V17 only, so the assertions stay true when later migrations arrive. */
    private static MigrateResult migrateTo17(DriverManagerDataSource database) {
        return Flyway.configure().dataSource(database).locations("classpath:db/migration").target("17").load()
                .migrate();
    }

    private static void insertTokens(JdbcTemplate jdbc, int count) {
        jdbc.update("""
                INSERT INTO device_tokens (id, user_id, token, platform, active, created_at, updated_at, version)
                SELECT gen_random_uuid(), gen_random_uuid(), 'ExponentPushToken[bulk-' || n || ']', 'ANDROID',
                       n % 4 <> 0, now(), now(), 0
                FROM generate_series(1, ?) AS n""", count);
    }

    private static void insertToken(JdbcTemplate jdbc, String token, boolean active) {
        jdbc.update("""
                INSERT INTO device_tokens (id, user_id, token, platform, active, created_at, updated_at, version)
                VALUES (gen_random_uuid(), gen_random_uuid(), ?, 'IOS', ?, now(), now(), 0)""", token, active);
    }
}
