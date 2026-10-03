package com.parkio.gateway.infrastructure.persistence.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.application.waitlist.WaitlistAdminEntry;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * CL-F34 on real PostgreSQL (the gateway's Flyway schema): the waitlist admin page must
 * not turn a large page number into a negative OFFSET (a database error), and rows that
 * share {@code created_at} must keep one order across pages.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class JdbcWaitlistInterestRepositoryAdminPagePostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("parkio_gateway")
                    .withUsername("parkio_gateway")
                    .withPassword("parkio_gateway");

    private static JdbcTemplate jdbc;
    private static JdbcWaitlistInterestRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcWaitlistInterestRepository(jdbc);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM waitlist_interest");
    }

    @Test
    void pageFarBeyondTheEndIsEmpty() {
        insert(UUID.randomUUID(), Instant.parse("2026-09-01T10:00:00Z"));

        WaitlistAdminPage page = repository.findAdminPage(null, null, null, Integer.MAX_VALUE, 100);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void rowsWithEqualCreationTimesArePagedInIdOrder() {
        Instant createdAt = Instant.parse("2026-09-01T10:00:00Z");
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            insert(id, createdAt);
        }

        List<UUID> paged = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < 4; pageNumber++) {
            repository.findAdminPage(null, null, null, pageNumber, 2).content().stream()
                    .map(WaitlistAdminEntry::id)
                    .forEach(paged::add);
        }

        // PostgreSQL orders uuid by unsigned bytes, which is the order of the canonical hex
        // strings; java.util.UUID.compareTo compares signed longs and would disagree.
        assertThat(paged.stream().map(UUID::toString).toList())
                .containsExactlyElementsOf(ids.stream().map(UUID::toString).sorted(Comparator.reverseOrder()).toList());
    }

    private static void insert(UUID id, Instant createdAt) {
        jdbc.update("""
                INSERT INTO waitlist_interest (id, email, email_hash, consent_timestamp, source, ip_hash, created_at)
                VALUES (?, ?, ?, ?, 'parkio.dev-landing', 'ip-hash', ?)
                """, id, id + "@parkio.dev", id.toString().replace("-", ""), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }
}
