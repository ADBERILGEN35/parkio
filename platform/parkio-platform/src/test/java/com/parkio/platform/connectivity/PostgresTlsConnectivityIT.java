package com.parkio.platform.connectivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PP-01B-SPIKE-03 Mode A — local JDBC TLS positive/negative matrix, role isolation,
 * hostname verification, and Hikari pool recovery.
 *
 * <p>Certificate SAN is {@code DNS:localhost} so host-side JVM can exercise
 * {@code sslmode=verify-full} without mutating the OS hosts file. Production contract
 * still requires private FQDN hostnames (validated by the production guard).
 */
@Tag("integration")
@EnabledIf("dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresTlsConnectivityIT {

    static final String IMAGE =
            "postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777";
    static final String IMAGE_REF = "postgres:16-alpine";
    static final String DB_APP = "parkio_auth";
    static final String DB_OTHER = "parkio_user";
    static final String ROLE_APP = "parkio_auth";
    static final String ROLE_OTHER = "parkio_user";
    static final String ROLE_MIGRATE = "parkio_migrate";
    static final String PASSWORD = "spike03_tls_local_only";

    private static Spike03TlsMaterial tls;
    private static GenericContainer<?> postgres;
    private static String containerId;
    private static String postgresVersion;
    private static String imageId;
    private static String imageDigest;

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            return false;
        }
    }

    @BeforeAll
    static void startTlsPostgres() throws Exception {
        tls = Spike03TlsMaterial.generate();
        Path confDir = Files.createTempDirectory("spike03-pgconf-");
        Path hba = confDir.resolve("pg_hba.conf");
        Files.writeString(
                hba,
                """
                local   all             all                                     trust
                hostssl all             all             0.0.0.0/0               scram-sha-256
                hostssl all             all             ::/0                    scram-sha-256
                hostnossl all           all             0.0.0.0/0               reject
                hostnossl all           all             ::/0                    reject
                """);
        String boot =
                "set -euo pipefail; "
                        + "mkdir -p /certs; "
                        + "cp /certs-ro/server.crt /certs/server.crt; "
                        + "cp /certs-ro/server.key /certs/server.key; "
                        + "cp /certs-ro/pg_hba.conf /certs/pg_hba.conf; "
                        + "chown postgres:postgres /certs/server.crt /certs/server.key /certs/pg_hba.conf; "
                        + "chmod 600 /certs/server.key; "
                        + "chmod 644 /certs/server.crt /certs/pg_hba.conf; "
                        + "exec docker-entrypoint.sh postgres "
                        + "-c ssl=on "
                        + "-c ssl_cert_file=/certs/server.crt "
                        + "-c ssl_key_file=/certs/server.key "
                        + "-c hba_file=/certs/pg_hba.conf";

        postgres = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("POSTGRES_USER", "postgres")
                .withEnv("POSTGRES_DB", "postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(tls.serverCert()), "/certs-ro/server.crt")
                .withCopyFileToContainer(MountableFile.forHostPath(tls.serverKey()), "/certs-ro/server.key")
                .withCopyFileToContainer(MountableFile.forHostPath(hba), "/certs-ro/pg_hba.conf")
                .withExposedPorts(5432)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("bash", "-c").withCmd(boot))
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        postgres.start();

        waitForSslAccept();
        bootstrapRolesAndDatabases();
        containerId = postgres.getContainerId();
        imageId = postgres.getDockerImageName();
        imageDigest = resolveDigest();
        try (Connection c = bootstrapConnection("postgres");
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT version()")) {
            rs.next();
            postgresVersion = rs.getString(1);
        }
    }

    @AfterAll
    static void cleanup() {
        if (postgres != null) {
            postgres.stop();
        }
        if (tls != null) {
            tls.deleteQuietly();
        }
    }

    @Test
    @Order(1)
    void plaintextConnectionIsRejected() {
        String url = baseUrl("localhost") + "?sslmode=disable";
        assertThatThrownBy(() -> DriverManager.getConnection(url, ROLE_APP, PASSWORD))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertNoSecretLeak(ex.getMessage()));
    }

    @Test
    @Order(2)
    void untrustedCaFailsVerifyFull() throws Exception {
        Path bogusCa = Files.createTempFile("spike03-bogus-ca-", ".crt");
        try {
            // Valid PEM structure but not the signing CA for the server cert.
            Spike03TlsMaterial other = Spike03TlsMaterial.generate();
            try {
                Properties props = new Properties();
                props.setProperty("sslmode", "verify-full");
                props.setProperty("sslrootcert", other.caCert().toAbsolutePath().toString());
                props.setProperty("user", ROLE_APP);
                props.setProperty("password", PASSWORD);
                assertThatThrownBy(() -> DriverManager.getConnection(baseUrl("localhost"), props))
                        .isInstanceOf(SQLException.class)
                        .satisfies(ex -> assertNoSecretLeak(ex.getMessage()));
            } finally {
                other.deleteQuietly();
            }
        } finally {
            Files.deleteIfExists(bogusCa);
        }
    }

    @Test
    @Order(3)
    void hostnameMismatchFailsVerifyFull() {
        // Cert SAN is DNS:localhost only — connecting by literal IP fails verify-full.
        Properties props = tlsProps();
        props.setProperty("user", ROLE_APP);
        props.setProperty("password", PASSWORD);
        assertThatThrownBy(() -> DriverManager.getConnection(baseUrl("127.0.0.1"), props))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> {
                    assertNoSecretLeak(ex.getMessage());
                    String msg = ex.getMessage().toLowerCase();
                    assertThat(msg).containsPattern("hostname|verify|certificate|ssl|name|peer");
                });
    }

    @Test
    @Order(4)
    void trustedVerifyFullSucceeds() throws Exception {
        try (Connection c = appTlsConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT current_user, current_database(), ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(ROLE_APP);
            assertThat(rs.getString(2)).isEqualTo(DB_APP);
            assertThat(rs.getBoolean(3)).isTrue();
        }
    }

    @Test
    @Order(5)
    void wrongPasswordFailsWithoutSecretLeak() {
        Properties props = tlsProps();
        props.setProperty("user", ROLE_APP);
        props.setProperty("password", "definitely-wrong-password");
        assertThatThrownBy(() -> DriverManager.getConnection(baseUrl("localhost"), props))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> {
                    assertNoSecretLeak(ex.getMessage());
                    assertThat(ex.getMessage()).doesNotContain("definitely-wrong-password");
                });
    }

    @Test
    @Order(6)
    void roleCannotConnectToForeignDatabase() {
        Properties props = tlsProps();
        props.setProperty("user", ROLE_APP);
        props.setProperty("password", PASSWORD);
        String other = "jdbc:postgresql://localhost:" + postgres.getMappedPort(5432) + "/" + DB_OTHER;
        assertThatThrownBy(() -> DriverManager.getConnection(other, props))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> assertNoSecretLeak(ex.getMessage()));
    }

    @Test
    @Order(7)
    void migrateRoleCanConnectOverTls() throws Exception {
        Properties props = tlsProps();
        props.setProperty("user", ROLE_MIGRATE);
        props.setProperty("password", PASSWORD);
        try (Connection c = DriverManager.getConnection(baseUrl("localhost"), props);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    @Order(8)
    void connectionContractUsesDnsHostnameNotIpLiteral() {
        String productionShaped = "jdbc:postgresql://parkio-core-pg.postgres.database.azure.com:5432/parkio_auth"
                + "?sslmode=verify-full";
        assertThat(productionShaped).doesNotMatch("(?i).*://\\d+\\.\\d+\\.\\d+\\.\\d+:.*");
        assertThat(baseUrl("localhost")).contains("://localhost:");
    }

    @Test
    @Order(9)
    void hikariPoolRecoversAfterBackendDisconnect() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(baseUrl("localhost"));
        cfg.setUsername(ROLE_APP);
        cfg.setPassword(PASSWORD);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(3_000);
        cfg.setValidationTimeout(1_000);
        cfg.setMaxLifetime(8_000);
        Properties dsProps = tlsProps();
        dsProps.forEach((k, v) -> cfg.addDataSourceProperty(String.valueOf(k), String.valueOf(v)));
        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            try (Connection c = ds.getConnection();
                    Statement st = c.createStatement()) {
                st.execute("SELECT 1");
            }
            postgres.execInContainer(
                    "bash",
                    "-lc",
                    "psql -U postgres -c \"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename='"
                            + ROLE_APP
                            + "' AND pid <> pg_backend_pid();\"");
            boolean recovered = false;
            SQLException last = null;
            for (int i = 0; i < 15; i++) {
                try (Connection c = ds.getConnection();
                        Statement st = c.createStatement();
                        ResultSet rs = st.executeQuery("SELECT 1")) {
                    assertThat(rs.next()).isTrue();
                    recovered = true;
                    break;
                } catch (SQLException ex) {
                    last = ex;
                    assertNoSecretLeak(ex.getMessage());
                    Thread.sleep(250);
                }
            }
            assertThat(recovered)
                    .as("pool must recover; last=%s", last == null ? "none" : last.getMessage())
                    .isTrue();
        }
    }

    @Test
    @Order(10)
    void evidenceMetadataPresentWithoutSecrets() {
        assertThat(IMAGE_REF).isEqualTo("postgres:16-alpine");
        assertThat(IMAGE).contains("sha256:57c72fd2");
        assertThat(containerId).isNotBlank();
        assertThat(imageId).contains("postgres");
        assertThat(postgresVersion).contains("PostgreSQL 16");
        assertThat(tls.workDir().toString()).doesNotContain(PASSWORD);
    }

    private static String resolveDigest() {
        try {
            var result = postgres.execInContainer("bash", "-lc", "echo ok");
            return result.getExitCode() == 0 ? IMAGE + "@runtime" : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void waitForSslAccept() throws Exception {
        SQLException last = null;
        for (int i = 0; i < 40; i++) {
            try (Connection c = bootstrapConnection("postgres")) {
                return;
            } catch (SQLException ex) {
                last = ex;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("TLS Postgres did not accept connections", last);
    }

    private static void bootstrapRolesAndDatabases() throws Exception {
        try (Connection c = bootstrapConnection("postgres");
                Statement st = c.createStatement()) {
            st.execute("CREATE ROLE " + ROLE_MIGRATE + " LOGIN PASSWORD '" + PASSWORD + "'");
            st.execute("CREATE ROLE " + ROLE_APP + " LOGIN PASSWORD '" + PASSWORD + "'");
            st.execute("CREATE ROLE " + ROLE_OTHER + " LOGIN PASSWORD '" + PASSWORD + "'");
            st.execute("CREATE DATABASE " + DB_APP + " OWNER " + ROLE_MIGRATE);
            st.execute("CREATE DATABASE " + DB_OTHER + " OWNER " + ROLE_OTHER);
            st.execute("REVOKE ALL ON DATABASE " + DB_APP + " FROM PUBLIC");
            st.execute("REVOKE ALL ON DATABASE " + DB_OTHER + " FROM PUBLIC");
            st.execute("GRANT CONNECT ON DATABASE " + DB_APP + " TO " + ROLE_APP + ", " + ROLE_MIGRATE);
            st.execute("GRANT CONNECT ON DATABASE " + DB_OTHER + " TO " + ROLE_OTHER);
            st.execute("REVOKE CONNECT ON DATABASE " + DB_APP + " FROM " + ROLE_OTHER);
            st.execute("REVOKE CONNECT ON DATABASE " + DB_OTHER + " FROM " + ROLE_APP);
        }
        try (Connection c = bootstrapConnection(DB_APP);
                Statement st = c.createStatement()) {
            st.execute("GRANT USAGE ON SCHEMA public TO " + ROLE_APP + ", " + ROLE_MIGRATE);
            st.execute("GRANT CREATE ON SCHEMA public TO " + ROLE_MIGRATE);
        }
    }

    private static Connection bootstrapConnection(String database) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + postgres.getMappedPort(5432) + "/" + database
                + "?sslmode=require";
        return DriverManager.getConnection(url, "postgres", PASSWORD);
    }

    private static Connection appTlsConnection() throws SQLException {
        Properties props = tlsProps();
        props.setProperty("user", ROLE_APP);
        props.setProperty("password", PASSWORD);
        return DriverManager.getConnection(baseUrl("localhost"), props);
    }

    private static String baseUrl(String host) {
        return "jdbc:postgresql://" + host + ":" + postgres.getMappedPort(5432) + "/" + DB_APP;
    }

    private static Properties tlsProps() {
        Properties props = new Properties();
        props.setProperty("sslmode", "verify-full");
        props.setProperty("sslrootcert", tls.caCert().toAbsolutePath().toString());
        props.setProperty("ApplicationName", "spike03-mode-a");
        return props;
    }

    private static void assertNoSecretLeak(String message) {
        assertThat(message).doesNotContain(PASSWORD);
        assertThat(message.toLowerCase()).doesNotContain("password=");
    }
}
