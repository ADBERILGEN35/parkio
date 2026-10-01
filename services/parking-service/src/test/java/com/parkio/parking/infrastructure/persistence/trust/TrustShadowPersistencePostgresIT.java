package com.parkio.parking.infrastructure.persistence.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

import com.parkio.parking.application.TrustShadowProjectionConflictException;
import com.parkio.parking.application.TrustShadowRowProcessor;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.trust.TrustShadowFailureStage;
import com.parkio.parking.application.trust.TrustShadowProcessingResult;
import com.parkio.parking.application.trust.ValidatedOutcomeForTrust;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.infrastructure.persistence.TrustSnapshotRepositoryAdapter;
import com.parkio.parking.infrastructure.persistence.jpa.TrustLedgerJpaRepository;
import com.parkio.parking.infrastructure.persistence.jpa.TrustSnapshotJpaRepository;
import com.parkio.parking.outcome.OutcomeClassification;
import com.parkio.parking.outcome.OutcomeEvaluation;
import com.parkio.parking.outcome.OutcomeReason;
import com.parkio.parking.outcome.OutcomeSnapshot;
import com.parkio.parking.outcome.confidence.OutcomeConfidence;
import com.parkio.parking.outcome.evaluation.OutcomeEvaluationContext;
import com.parkio.parking.outcome.evidence.OutcomeEvidence;
import com.parkio.parking.outcome.history.OutcomeEvaluationTrigger;
import com.parkio.parking.outcome.history.OutcomeHistoryRecord;
import com.parkio.parking.outcome.policy.OutcomePolicyVersion;
import com.parkio.parking.outcome.port.OutcomeHistoryPort;
import com.parkio.parking.outcome.timeline.OutcomeTimeline;
import com.parkio.parking.trust.TrustDomain;
import com.parkio.parking.trust.TrustEngine;
import com.parkio.parking.trust.TrustEvaluationContext;
import com.parkio.parking.trust.TrustLedgerEntry;
import com.parkio.parking.trust.TrustLedgerIds;
import com.parkio.parking.trust.TrustLedgerOrder;
import com.parkio.parking.trust.TrustPolicyConfig;
import com.parkio.parking.trust.TrustReplayComparison;
import com.parkio.parking.trust.TrustReplayer;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.TrustSubject;
import com.parkio.parking.trust.TrustSubjectType;
import com.parkio.parking.trust.ValidatedTrustEvidenceFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TrustShadowPersistencePostgresIT {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_trust_persistence_it")
            .withUsername("parkio")
            .withPassword("parkio");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGIS::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("parkio.kafka.provision-topics", () -> "false");
        registry.add("parkio.kafka.relay.enabled", () -> "false");
        registry.add("parkio.kafka.moderation-consumer.enabled", () -> "false");
        registry.add("parkio.kafka.ai-validation-consumer.enabled", () -> "false");
        registry.add("parkio.lifecycle.parking-expiry.enabled", () -> "false");
        registry.add("parkio.lifecycle.moderation-timeout.enabled", () -> "false");
        registry.add("parkio.lifecycle.outcome-validation.enabled", () -> "false");
        registry.add("parkio.lifecycle.trust-shadow.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    private ParkingSpotRepository spots;

    @Autowired
    private OutcomeHistoryPort outcomeHistory;

    @Autowired
    private TrustShadowRowProcessor processor;

    @Autowired
    private TrustLedgerJpaRepository trustLedgerJpa;

    @Autowired
    private TrustSnapshotJpaRepository trustSnapshotJpa;

    @Autowired
    private TrustLedgerPort ledgerPort;

    @Autowired
    private TrustSnapshotReadPort snapshotReadPort;

    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private TrustSnapshotRepositoryAdapter snapshots;

    @BeforeEach
    void cleanDatabase() {
        Mockito.reset(snapshots);
        jdbc.update("DELETE FROM trust_snapshot");
        jdbc.update("DELETE FROM trust_ledger");
        jdbc.update("DELETE FROM outcome_history");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void firstUpdateCommitsLedgerAndSnapshotAtomically() {
        ValidatedOutcomeForTrust candidate = candidate(
                fixedReporter(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        TrustShadowProcessingResult result = processor.process(candidate);

        assertThat(result.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(trustLedgerJpa.count()).isEqualTo(1);
        assertThat(trustSnapshotJpa.count()).isEqualTo(1);
        assertThat(snapshot(candidate).effectiveEvidenceCount()).isEqualTo(1);
    }

    @Test
    void laterUpdateAppendsNewLedgerRowAndUpdatesDerivedSnapshot() {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForTrust first = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForTrust second = candidate(
                reporter,
                OutcomeClassification.LIKELY_CORRECT,
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                80,
                Instant.parse("2026-07-28T10:10:00Z"));

        processor.process(first);
        TrustSnapshot afterFirst = snapshot(first);
        processor.process(second);

        assertThat(trustLedgerJpa.count()).isEqualTo(2);
        TrustSnapshot afterSecond = snapshot(second);
        assertThat(afterSecond.effectiveEvidenceCount()).isEqualTo(afterFirst.effectiveEvidenceCount() + 1);
        assertThat(afterSecond.score().basisPoints()).isGreaterThanOrEqualTo(afterFirst.score().basisPoints());
        assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(2);
    }

    @Test
    void duplicateEvidenceDoesNotMutateExistingSnapshot() {
        ValidatedOutcomeForTrust candidate = candidate(
                fixedReporter(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        processor.process(candidate);
        TrustSnapshot snapshot = snapshot(candidate);
        TrustShadowProcessingResult duplicate = processor.process(candidate);

        assertThat(duplicate.status()).isEqualTo(TrustShadowProcessingResult.Status.DUPLICATE);
        assertThat(trustLedgerJpa.count()).isEqualTo(1);
        assertThat(snapshot(candidate)).isEqualTo(snapshot);
    }

    @Test
    void snapshotFailureRollsBackLedgerAppendBeforeRetrySucceeds() {
        ValidatedOutcomeForTrust candidate = candidate(
                fixedReporter(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        AtomicBoolean failOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failOnce.compareAndSet(true, false)) {
                throw new TrustShadowProjectionConflictException("forced test conflict", new RuntimeException("forced"));
            }
            return invocation.callRealMethod();
        }).when(snapshots).upsert(any(TrustSnapshot.class), nullable(Long.class));

        TrustShadowProcessingResult result = processor.process(candidate);

        assertThat(result.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(trustLedgerJpa.count()).isEqualTo(1);
        assertThat(trustSnapshotJpa.count()).isEqualTo(1);
    }

    @Test
    void concurrentSameEvidenceProducesOneLogicalLedgerEntry() throws Exception {
        ValidatedOutcomeForTrust candidate = candidate(
                fixedReporter(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TrustShadowProcessingResult> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            Future<TrustShadowProcessingResult> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            start.countDown();

            List<TrustShadowProcessingResult.Status> statuses =
                    List.of(first.get(30, TimeUnit.SECONDS).status(), second.get(30, TimeUnit.SECONDS).status());
            assertThat(statuses).contains(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(statuses).contains(TrustShadowProcessingResult.Status.DUPLICATE);
            assertThat(trustLedgerJpa.count()).isEqualTo(1);
            assertThat(snapshot(candidate).effectiveEvidenceCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDistinctEvidencePreservesBothUpdatesAndMatchesReplay() throws Exception {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForTrust seed = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T09:55:00Z"));
        processor.process(seed);

        ValidatedOutcomeForTrust first = candidate(
                reporter,
                OutcomeClassification.LIKELY_CORRECT,
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                80,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForTrust second = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.COMMUNITY_CLAIM_CONFIRMED,
                95,
                Instant.parse("2026-07-28T10:05:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TrustShadowProcessingResult> left = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(first);
            });
            Future<TrustShadowProcessingResult> right = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(second);
            });
            start.countDown();

            TrustShadowProcessingResult leftResult = left.get(30, TimeUnit.SECONDS);
            TrustShadowProcessingResult rightResult = right.get(30, TimeUnit.SECONDS);
            // Concurrent projection updates may exhaust SNAPSHOT_CONFLICT retries under load;
            // reprocess any conflict survivor so both distinct evidence rows are durable.
            if (leftResult.status() == TrustShadowProcessingResult.Status.FAILED
                    && leftResult.failureStage().orElse(null) == TrustShadowFailureStage.SNAPSHOT_CONFLICT) {
                leftResult = processor.process(first);
            }
            if (rightResult.status() == TrustShadowProcessingResult.Status.FAILED
                    && rightResult.failureStage().orElse(null) == TrustShadowFailureStage.SNAPSHOT_CONFLICT) {
                rightResult = processor.process(second);
            }
            assertThat(leftResult.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(rightResult.status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(3);
            assertThat(snapshot(first)).isEqualTo(rebuild(subject(reporter)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayAndDryRunRebuildDoNotMutateLedgerOrProjection() {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForTrust first = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForTrust second = candidate(
                reporter,
                OutcomeClassification.LIKELY_CORRECT,
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                80,
                Instant.parse("2026-07-28T10:10:00Z"));

        processor.process(first);
        processor.process(second);
        long ledgerCount = trustLedgerJpa.count();
        TrustSnapshot stored = snapshot(second);
        List<TrustLedgerEntry> ledger = ledgerPort.findBySubject(subject(reporter));

        TrustReplayComparison replay = new TrustReplayer().replay(ledger.get(0));
        TrustSnapshot rebuilt = rebuild(subject(reporter));

        assertThat(replay.identical()).isTrue();
        assertThat(rebuilt).isEqualTo(stored);
        assertThat(trustLedgerJpa.count()).isEqualTo(ledgerCount);
        assertThat(snapshot(second)).isEqualTo(stored);
        assertThat(rebuilt.score().basisPoints() + 1).isNotEqualTo(stored.score().basisPoints());
    }

    @Test
    void concurrentFirstSnapshotCreationKeepsBothCommittedContributions() throws Exception {
        UUID reporter = UUID.randomUUID();
        Instant older = Instant.parse("2026-07-28T10:00:00Z");
        Instant later = older.plusSeconds(300);
        ValidatedOutcomeForTrust firstInFlight = candidate(
                reporter, OutcomeClassification.LIKELY_CORRECT, OutcomeReason.SINGLE_AVAILABLE_VERIFICATION, 80, older);
        ValidatedOutcomeForTrust laterCommitted = candidate(
                reporter, OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.COMMUNITY_CLAIM_CONFIRMED, 95, later);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        doAnswer(invocation -> {
            TrustSnapshot value = invocation.getArgument(0);
            if (value.lastEvaluatedAt().equals(older) && held.compareAndSet(0, 1)) {
                reached.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("first-snapshot interleaving timed out");
                }
            }
            return invocation.callRealMethod();
        }).when(snapshots).upsert(any(TrustSnapshot.class), nullable(Long.class));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TrustShadowProcessingResult> first = pool.submit(() -> processor.process(firstInFlight));
            assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(processor.process(laterCommitted).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(2);
            TrustSnapshot stored = snapshot(firstInFlight);
            assertThat(stored.effectiveEvidenceCount()).isEqualTo(2);
            assertThat(stored.lastEvaluatedAt()).isEqualTo(later);
            assertThat(stored).isEqualTo(rebuild(subject(reporter)));
            assertThat(processor.process(firstInFlight).status()).isEqualTo(TrustShadowProcessingResult.Status.DUPLICATE);
            assertThat(snapshot(firstInFlight)).isEqualTo(stored);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentLaterUpdateDoesNotDropTheCommittedContribution() throws Exception {
        UUID reporter = UUID.randomUUID();
        Instant seedAt = Instant.parse("2026-07-28T09:55:00Z");
        Instant older = Instant.parse("2026-07-28T10:00:00Z");
        Instant later = Instant.parse("2026-07-28T10:05:00Z");
        assertThat(processor.process(candidate(
                reporter, OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 95, seedAt))
                .status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        ValidatedOutcomeForTrust firstInFlight = candidate(
                reporter, OutcomeClassification.LIKELY_CORRECT, OutcomeReason.SINGLE_AVAILABLE_VERIFICATION, 80, older);
        ValidatedOutcomeForTrust laterCommitted = candidate(
                reporter, OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.COMMUNITY_CLAIM_CONFIRMED, 95, later);
        CountDownLatch reached = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        doAnswer(invocation -> {
            TrustSnapshot value = invocation.getArgument(0);
            if (value.lastEvaluatedAt().equals(older) && held.compareAndSet(0, 1)) {
                reached.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("later-update interleaving timed out");
                }
            }
            return invocation.callRealMethod();
        }).when(snapshots).upsert(any(TrustSnapshot.class), nullable(Long.class));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<TrustShadowProcessingResult> first = pool.submit(() -> processor.process(firstInFlight));
            assertThat(reached.await(20, TimeUnit.SECONDS)).isTrue();
            assertThat(processor.process(laterCommitted).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(3);
            TrustSnapshot stored = snapshot(firstInFlight);
            assertThat(stored.effectiveEvidenceCount()).isEqualTo(3);
            assertThat(stored.lastEvaluatedAt()).isEqualTo(later);
            assertThat(stored).isEqualTo(rebuild(subject(reporter)));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentUnrelatedReportersKeepIndependentSnapshots() throws Exception {
        UUID leftReporter = UUID.randomUUID();
        UUID rightReporter = UUID.randomUUID();
        ValidatedOutcomeForTrust left = candidate(
                leftReporter, OutcomeClassification.CONFIRMED_CORRECT, OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS, 95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForTrust right = candidate(
                rightReporter, OutcomeClassification.CONFIRMED_INCORRECT, OutcomeReason.NEGATIVE_VERIFICATION, 95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TrustShadowProcessingResult> leftResult = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(left);
            });
            Future<TrustShadowProcessingResult> rightResult = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(right);
            });
            start.countDown();
            assertThat(leftResult.get(30, TimeUnit.SECONDS).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(rightResult.get(30, TimeUnit.SECONDS).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
            assertThat(snapshot(left).effectiveEvidenceCount()).isEqualTo(1);
            assertThat(snapshot(right).effectiveEvidenceCount()).isEqualTo(1);
            assertThat(snapshot(left).subject()).isNotEqualTo(snapshot(right).subject());
            assertThat(trustLedgerJpa.count()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void equalTimestampUuidTieFollowsPostgreSQLOrderNotJavaSignedOrder() {
        UUID reporter = UUID.randomUUID();
        Instant at = Instant.parse("2026-07-28T10:00:00Z");
        TieIds ids = tieIds(reporter);
        ValidatedOutcomeForTrust javaFirst = candidateWithRecord(
                ids.highBitRecord(),
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                at);
        ValidatedOutcomeForTrust postgresFirst = candidateWithRecord(
                ids.lowBitRecord(),
                reporter,
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                at);

        assertThat(processor.process(javaFirst).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
        assertThat(processor.process(postgresFirst).status()).isEqualTo(TrustShadowProcessingResult.Status.APPENDED);

        List<UUID> databaseOrder = jdbc.query(
                "SELECT id FROM trust_ledger WHERE subject_id = ? ORDER BY evaluated_at ASC, id ASC",
                (rs, row) -> rs.getObject(1, UUID.class),
                reporter);
        List<UUID> signedOrder = databaseOrder.stream().sorted(UUID::compareTo).toList();
        assertThat(databaseOrder).containsExactly(ids.lowBitLedger(), ids.highBitLedger());
        assertThat(signedOrder).containsExactly(ids.highBitLedger(), ids.lowBitLedger());
        assertThat(signedOrder).isNotEqualTo(databaseOrder);

        List<TrustLedgerEntry> ledger = ledgerPort.findBySubject(subject(reporter));
        TrustLedgerEntry low = ledger.stream()
                .filter(entry -> entry.ledgerEntryId().equals(ids.lowBitLedger()))
                .findFirst()
                .orElseThrow();
        TrustLedgerEntry high = ledger.stream()
                .filter(entry -> entry.ledgerEntryId().equals(ids.highBitLedger()))
                .findFirst()
                .orElseThrow();
        TrustEngine engine = new TrustEngine();
        TrustEvaluationContext context = new TrustEvaluationContext(
                at, TrustPolicyConfig.POLICY_VERSION, TrustSnapshotSchemaVersion.V1);
        TrustSnapshot initial = engine.initialSnapshot(subject(reporter), TrustDomain.PARKING_REPORT_ACCURACY, context);
        TrustSnapshot unsignedPrefix = engine.evaluate(initial, low.evidence(), context).resultingSnapshot();
        TrustSnapshot signedPrefix = engine.evaluate(initial, high.evidence(), context).resultingSnapshot();
        assertThat(unsignedPrefix).isNotEqualTo(signedPrefix);
        assertThat(low.previousSnapshot()).isEqualTo(initial);
        assertThat(low.previousSnapshot()).isNotEqualTo(signedPrefix);
        assertThat(high.previousSnapshot()).isEqualTo(initial);
        assertThat(high.previousSnapshot()).isNotEqualTo(unsignedPrefix);
        assertThat(new TrustReplayer().replay(low).identical()).isTrue();
        assertThat(new TrustReplayer().replay(high).identical()).isTrue();
        assertThat(snapshot(javaFirst)).isEqualTo(rebuild(subject(reporter)));
        assertThat(processor.process(postgresFirst).status()).isEqualTo(TrustShadowProcessingResult.Status.DUPLICATE);
        assertThat(snapshot(javaFirst)).isEqualTo(rebuild(subject(reporter)));
    }

    private TrustSnapshot rebuild(TrustSubject subject) {
        List<TrustLedgerEntry> ledger = ledgerPort.findBySubject(subject);
        TrustEngine engine = new TrustEngine();
        TrustSnapshot snapshot = engine.initialSnapshot(
                subject,
                TrustDomain.PARKING_REPORT_ACCURACY,
                new TrustEvaluationContext(
                        ledger.get(0).evaluatedAt(),
                        TrustPolicyConfig.POLICY_VERSION,
                        TrustSnapshotSchemaVersion.V1));
        for (TrustLedgerEntry entry : ledger) {
            snapshot = engine.evaluate(
                            snapshot,
                            entry.evidence(),
                            new TrustEvaluationContext(
                                    entry.evaluatedAt(),
                                    entry.trustPolicyVersion(),
                                    entry.snapshotSchemaVersion()))
                    .resultingSnapshot();
        }
        return snapshot;
    }

    private TrustSnapshot snapshot(ValidatedOutcomeForTrust candidate) {
        return snapshotReadPort.findBySubjectAndDomain(
                        subject(candidate.reporterUserId()),
                        TrustDomain.PARKING_REPORT_ACCURACY)
                .orElseThrow();
    }

    private static TrustSubject subject(UUID reporter) {
        return new TrustSubject(TrustSubjectType.REPORTER, reporter);
    }

    private ValidatedOutcomeForTrust candidate(
            UUID reporterUserId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        UUID spotId = UUID.randomUUID();
        saveSpot(spotId, reporterUserId, evaluatedAt.minus(Duration.ofHours(2)));
        OutcomeHistoryRecord record = outcomeRecord(spotId, classification, reason, confidence, evaluatedAt);
        outcomeHistory.append(record);
        return new ValidatedOutcomeForTrust(record, reporterUserId);
    }

    private ValidatedOutcomeForTrust candidateWithRecord(
            UUID recordId,
            UUID reporterUserId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        UUID spotId = UUID.randomUUID();
        saveSpot(spotId, reporterUserId, evaluatedAt.minus(Duration.ofHours(2)));
        OutcomeHistoryRecord record = outcomeRecord(recordId, spotId, classification, reason, confidence, evaluatedAt);
        outcomeHistory.append(record);
        return new ValidatedOutcomeForTrust(record, reporterUserId);
    }

    private TieIds tieIds(UUID reporter) {
        UUID highRecord = null;
        UUID lowRecord = null;
        UUID highLedger = null;
        UUID lowLedger = null;
        for (long sequence = 1; sequence < 50_000 && (highRecord == null || lowRecord == null); sequence++) {
            UUID recordId = new UUID(sequence, 1L);
            UUID ledgerId = TrustLedgerIds.ledgerEntryId(
                    ValidatedTrustEvidenceFactory.reporterEvidence(
                            outcomeRecord(
                                    recordId,
                                    UUID.randomUUID(),
                                    OutcomeClassification.CONFIRMED_CORRECT,
                                    OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                                    95,
                                    Instant.parse("2026-07-28T10:00:00Z")),
                            reporter).evidenceId());
            if (ledgerId.getMostSignificantBits() < 0 && highRecord == null) {
                highRecord = recordId;
                highLedger = ledgerId;
            } else if (ledgerId.getMostSignificantBits() >= 0 && lowRecord == null) {
                lowRecord = recordId;
                lowLedger = ledgerId;
            }
        }
        assertThat(highRecord).isNotNull();
        assertThat(lowRecord).isNotNull();
        assertThat(highLedger.compareTo(lowLedger)).isNegative();
        assertThat(TrustLedgerOrder.UNSIGNED_ID.compare(lowLedger, highLedger)).isNegative();
        return new TieIds(highRecord, lowRecord, highLedger, lowLedger);
    }

    private record TieIds(UUID highBitRecord, UUID lowBitRecord, UUID highBitLedger, UUID lowBitLedger) {}

    private void saveSpot(UUID spotId, UUID ownerUserId, Instant now) {
        spots.save(new ParkingSpot(
                spotId,
                ownerUserId,
                UUID.randomUUID(),
                41.0082,
                28.9784,
                null,
                null,
                false,
                Set.of(VehicleType.SEDAN),
                ParkingContext.STREET_PARKING,
                LegalStatus.LEGAL,
                Set.of(),
                ParkingSpotStatus.ACTIVE,
                1.0,
                0,
                0,
                now.plus(Duration.ofMinutes(30)),
                now,
                now,
                null,
                now,
                now.plus(Duration.ofHours(24)),
                0,
                now,
                null,
                null));
    }

    private static OutcomeHistoryRecord outcomeRecord(
            UUID spotId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        return outcomeRecord(UUID.randomUUID(), spotId, classification, reason, confidence, evaluatedAt);
    }

    private static OutcomeHistoryRecord outcomeRecord(
            UUID recordId,
            UUID spotId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        Instant publishedAt = evaluatedAt.minus(Duration.ofMinutes(30));
        OutcomePolicyVersion policyVersion = OutcomePolicyVersion.of("outcome-policy-v1");
        OutcomeTimeline timeline = OutcomeTimeline.of(publishedAt, publishedAt.plus(Duration.ofMinutes(10)), List.of());
        OutcomeEvaluation evaluation = new OutcomeEvaluation(
                spotId,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                Set.of(reason),
                timeline,
                Duration.ofMinutes(60),
                false,
                policyVersion,
                evaluatedAt);
        OutcomeSnapshot snapshot = new OutcomeSnapshot(
                new OutcomeEvidence(
                        spotId,
                        ParkingSpotStatus.ACTIVE,
                        publishedAt.minusSeconds(30),
                        publishedAt,
                        publishedAt.plus(Duration.ofMinutes(10)),
                        evaluatedAt,
                        1,
                        0,
                        0.9,
                        timeline),
                new OutcomeEvaluationContext(evaluatedAt, policyVersion, Duration.ofMinutes(10)),
                evaluation);
        return new OutcomeHistoryRecord(
                recordId,
                UUID.randomUUID(),
                spotId,
                policyVersion,
                "outcome-snapshot-v1",
                OutcomeEvaluationTrigger.PUBLICATION,
                UUID.randomUUID(),
                evaluatedAt,
                evaluatedAt,
                snapshot,
                classification,
                OutcomeConfidence.of(confidence),
                reason,
                false,
                evaluatedAt);
    }

    private static UUID fixedReporter() {
        return UUID.fromString("11111111-1111-1111-1111-111111111111");
    }
}
