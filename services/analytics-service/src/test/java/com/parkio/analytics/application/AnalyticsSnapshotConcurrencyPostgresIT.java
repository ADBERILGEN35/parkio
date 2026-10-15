package com.parkio.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.analytics.application.event.PointsEarnedEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * F-38R analytics half: concurrent distinct events updating the same snapshot row.
 * Records optimistic-lock / success outcomes, ledger vs snapshot agreement, and that
 * contention is visible at the application layer (Kafka DLT is the consumer's short
 * retry-then-DLT policy — not exercised here without a broker).
 *
 * <p>Disposition is recorded by assertions: if no loss is observed under concurrent
 * first-row and version conflicts, the finding is <strong>not reproducible</strong>
 * as silent snapshot loss; an atomic UPDATE is not authorized by this investigation.
 */
@Tag("integration")
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsSnapshotConcurrencyPostgresIT {

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

    @Autowired private AnalyticsApplicationService service;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentDistinctEventsPreserveLedgerAndSnapshotOrSurfaceOptimisticLock() throws Exception {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T08:00:00Z");
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger optimistic = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < workers; i++) {
            UUID eventId = UUID.randomUUID();
            long points = 1L;
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    service.handlePointsEarned(new PointsEarnedEvent(
                            eventId, userId, points, "UPLOAD", points, null, now));
                    success.incrementAndGet();
                } catch (Exception ex) {
                    Throwable cur = ex;
                    boolean opt = false;
                    while (cur != null) {
                        if (cur instanceof OptimisticLockingFailureException) {
                            opt = true;
                            break;
                        }
                        cur = cur.getCause();
                    }
                    if (opt) {
                        optimistic.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                        System.out.println("F-38R other failure: " + ex);
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // counted above
            }
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long ledger = jdbc.queryForObject(
                "SELECT COUNT(*) FROM analytics_events WHERE user_id = ?", Long.class, userId);
        Long snapshotCount = jdbc.queryForObject(
                "SELECT COALESCE(SUM(event_count),0) FROM user_analytics_snapshots WHERE user_id = ?",
                Long.class, userId);
        long snapshot = snapshotCount == null ? 0L : snapshotCount;

        // Evidence for the investigation record (printed into surefire output).
        System.out.printf(
                "F-38R analytics concurrency: success=%d optimistic=%d other=%d ledger=%d snapshot_event_count=%d%n",
                success.get(), optimistic.get(), other.get(), ledger, snapshot);

        // Investigation evidence: silent under-count would be ledger rows without matching
        // snapshot increments. Optimistic locks and other surfaced failures are recorded.
        assertThat(ledger).isEqualTo(success.get());
        assertThat(snapshot).isEqualTo(success.get());
        assertThat(ledger).isGreaterThanOrEqualTo(snapshot);
        assertThat(success.get() + optimistic.get() + other.get()).isEqualTo(workers);
        // Not-reproducible as silent loss when ledger == snapshot for every success.
        assertThat(ledger).as("no silent snapshot loss vs ledger").isEqualTo(snapshot);
    }
}
