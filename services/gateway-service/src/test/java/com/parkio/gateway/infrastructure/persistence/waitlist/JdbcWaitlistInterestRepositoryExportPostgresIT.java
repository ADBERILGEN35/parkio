package com.parkio.gateway.infrastructure.persistence.waitlist;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gateway.application.waitlist.WaitlistApplicationService;
import com.parkio.gateway.application.waitlist.WaitlistExport;
import com.parkio.gateway.application.waitlist.WaitlistExportCursor;
import com.parkio.gateway.application.waitlist.WaitlistExportRow;
import com.parkio.gateway.application.waitlist.WaitlistInterest;
import com.parkio.gateway.application.waitlist.WaitlistAdminPage;
import com.parkio.gateway.application.waitlist.WaitlistAdminEntry;
import com.parkio.gateway.application.waitlist.WaitlistProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * CL-F34 confirmed CSV export on real PostgreSQL (Flyway): the export filters confirmed rows by
 * confirmation time, pages them by (confirmed_at, id) without overlap or gaps even when many rows
 * share a confirmation time, and at volume stops at the row limit while holding one page at a
 * time. Synthetic rows only.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class JdbcWaitlistInterestRepositoryExportPostgresIT {

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
    void onlyConfirmedRowsInTheConfirmationWindowAreCountedAndExported() {
        Instant early = Instant.parse("2026-09-01T10:00:00Z");
        Instant inside = Instant.parse("2026-09-10T10:00:00Z");
        Instant late = Instant.parse("2026-09-20T10:00:00Z");
        UUID before = insert("CONFIRMED", early);
        UUID wanted = insert("CONFIRMED", inside);
        UUID after = insert("CONFIRMED", late);
        insert("PENDING", null);
        insert("WITHDRAWN", inside);
        Instant from = Instant.parse("2026-09-05T00:00:00Z");
        Instant to = Instant.parse("2026-09-15T00:00:00Z");

        assertThat(repository.countConfirmedForExport(from, to)).isEqualTo(1);
        assertThat(repository.exportConfirmedPage(from, to, null, 10)).extracting(WaitlistExportRow::id)
                .containsExactly(wanted);
        assertThat(repository.countConfirmedForExport(null, null)).isEqualTo(3);
        assertThat(repository.exportConfirmedPage(null, null, null, 10)).extracting(WaitlistExportRow::id)
                .containsExactly(before, wanted, after);
        // The window is half-open: confirmedTo excludes a row confirmed exactly then.
        assertThat(repository.countConfirmedForExport(early, inside)).isEqualTo(1);
    }

    @Test
    void keysetPagesNeverOverlapOrSkipRowsThatShareAConfirmationTime() {
        Instant same = Instant.parse("2026-09-10T10:00:00Z");
        Set<UUID> inserted = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            inserted.add(insert("CONFIRMED", same));
        }
        inserted.add(insert("CONFIRMED", same.plusSeconds(1)));

        List<UUID> exported = new ArrayList<>();
        WaitlistExportCursor after = null;
        while (true) {
            List<WaitlistExportRow> page = repository.exportConfirmedPage(null, null, after, 4);
            if (page.isEmpty()) {
                break;
            }
            assertThat(page).hasSizeLessThanOrEqualTo(4);
            page.forEach(row -> exported.add(row.id()));
            WaitlistExportRow last = page.get(page.size() - 1);
            after = new WaitlistExportCursor(last.confirmedAt(), last.id());
        }

        assertThat(exported).doesNotHaveDuplicates().hasSize(26);
        assertThat(new HashSet<>(exported)).isEqualTo(inserted);
    }

    @Test
    void atVolumeTheExportStopsAtTheLimitAndHoldsOnePageAtATime() {
        int total = 60_000;
        jdbc.update("""
                INSERT INTO waitlist_interest (id, email, email_hash, consent_timestamp, source, ip_hash, created_at,
                                               status, confirmed_at)
                SELECT gen_random_uuid(), 'volume-' || n || '@parkio.dev', md5('volume-' || n), now(),
                       'parkio.dev-landing', 'ip-hash', now(), 'CONFIRMED',
                       TIMESTAMP WITH TIME ZONE '2026-09-01 00:00:00+00' + (n % 5000) * INTERVAL '1 second'
                FROM generate_series(1, ?) AS n
                """, total);
        WaitlistProperties properties = new WaitlistProperties();
        WaitlistApplicationService service = new WaitlistApplicationService(
                repository, null, null, null, properties, null, null, Clock.systemUTC());

        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long baseline = runtime.totalMemory() - runtime.freeMemory();
        AtomicLong peak = new AtomicLong(baseline);
        AtomicInteger largestPage = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        Set<UUID> seen = new HashSet<>();

        WaitlistExport export = service.export(null, null).block();
        export.pages().doOnNext(page -> {
            largestPage.accumulateAndGet(page.size(), Math::max);
            rows.addAndGet(page.size());
            page.forEach(row -> seen.add(row.id()));
            peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), Math::max);
        }).blockLast();

        assertThat(export.matchingRows()).isEqualTo(total);
        assertThat(export.rowLimit()).isEqualTo(50_000);
        assertThat(export.truncated()).isTrue();
        assertThat(rows.get()).isEqualTo(50_000);
        assertThat(seen).hasSize(50_000);
        assertThat(largestPage.get()).isLessThanOrEqualTo(1_000);
        // Evidence only (JVM heap is not deterministic): printed into the test report.
        System.out.printf("export-memory baseline=%d peak=%d delta=%d bytes rows=%d largestPage=%d%n",
                baseline, peak.get(), peak.get() - baseline, rows.get(), largestPage.get());
    }

    @Test
    void exportCarriesTheStoredConsentVersionAndLegacyRowsReadAsUnversioned() {
        Instant confirmed = Instant.parse("2026-09-10T10:00:00Z");
        UUID legacy = insert("CONFIRMED", confirmed);
        UUID versioned = insert("CONFIRMED", confirmed.plusSeconds(1));
        jdbc.update("UPDATE waitlist_interest SET consent_text_version = ? WHERE id = ?", "waitlist-consent-v1", versioned);

        List<WaitlistExportRow> rows = repository.exportConfirmedPage(null, null, null, 10);
        assertThat(rows).extracting(WaitlistExportRow::id).containsExactly(legacy, versioned);
        // CL-F18: a row inserted without a version is legacy-unversioned (the V6 backfill default).
        assertThat(rows.get(0).consentTextVersion()).isEqualTo("legacy-unversioned");
        assertThat(rows.get(1).consentTextVersion()).isEqualTo("waitlist-consent-v1");
        assertThat(rows.get(1).confirmedAt()).isEqualTo(confirmed.plusSeconds(1));
        assertThat(repository.findById(versioned)).get()
                .extracting(WaitlistInterest::consentTextVersion).isEqualTo("waitlist-consent-v1");
        WaitlistAdminPage page = repository.findAdminPage(null, null, null, 0, 10);
        assertThat(page.content()).extracting(WaitlistAdminEntry::consentTextVersion)
                .containsExactlyInAnyOrder("legacy-unversioned", "waitlist-consent-v1");
        assertThat(page.content()).allSatisfy(entry -> assertThat(entry.consentTimestamp()).isNotNull());
    }

    private static UUID insert(String status, Instant confirmedAt) {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO waitlist_interest (id, email, email_hash, consent_timestamp, source, ip_hash, created_at,
                                               status, confirmed_at)
                VALUES (?, ?, ?, ?, 'parkio.dev-landing', 'ip-hash', ?, ?, ?)
                """, id, id + "@parkio.dev", id.toString().replace("-", ""), Timestamp.from(createdAt),
                Timestamp.from(createdAt), status, confirmedAt == null ? null : Timestamp.from(confirmedAt));
        return id;
    }
}
