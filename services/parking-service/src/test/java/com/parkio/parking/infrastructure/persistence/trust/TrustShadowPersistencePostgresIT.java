package com.parkio.parking.infrastructure.persistence.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.TrustShadowProjectionConflictException;
import com.parkio.parking.application.TrustShadowRowProcessor;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.TrustLedgerPort;
import com.parkio.parking.application.port.TrustSnapshotReadPort;
import com.parkio.parking.application.port.TrustSnapshotWritePort;
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
import com.parkio.parking.trust.TrustPolicyConfig;
import com.parkio.parking.trust.TrustReplayComparison;
import com.parkio.parking.trust.TrustReplayer;
import com.parkio.parking.trust.TrustSnapshot;
import com.parkio.parking.trust.TrustSnapshotSchemaVersion;
import com.parkio.parking.trust.TrustSubject;
import com.parkio.parking.trust.TrustSubjectType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class TrustShadowPersistencePostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

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

    private static final AtomicBoolean FAIL_NEXT_SNAPSHOT_UPSERT = new AtomicBoolean(false);

    @BeforeEach
    void cleanDatabase() {
        FAIL_NEXT_SNAPSHOT_UPSERT.set(false);
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

        FAIL_NEXT_SNAPSHOT_UPSERT.set(true);

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

            TrustShadowProcessingResult leftResult = reprocessIfSnapshotConflict(left.get(30, TimeUnit.SECONDS), first);
            TrustShadowProcessingResult rightResult = reprocessIfSnapshotConflict(right.get(30, TimeUnit.SECONDS), second);
            assertAppended(leftResult, "left/earlier evidence");
            assertAppended(rightResult, "right/later evidence");
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(3);
            assertThat(snapshot(first)).isEqualTo(rebuild(subject(reporter)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialLaterThenEarlierEvidencePreservesBothAndMatchesReplay() {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForTrust seed = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T09:55:00Z"));
        ValidatedOutcomeForTrust later = candidate(
                reporter,
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.COMMUNITY_CLAIM_CONFIRMED,
                95,
                Instant.parse("2026-07-28T10:05:00Z"));
        ValidatedOutcomeForTrust earlier = candidate(
                reporter,
                OutcomeClassification.LIKELY_CORRECT,
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                80,
                Instant.parse("2026-07-28T10:00:00Z"));

        assertAppended(processor.process(seed), "seed");
        assertAppended(processor.process(later), "later evidence first");
        assertAppended(processor.process(earlier), "earlier evidence after later");
        assertThat(processor.process(earlier).status()).isEqualTo(TrustShadowProcessingResult.Status.DUPLICATE);
        assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(3);
        assertThat(snapshot(earlier)).isEqualTo(rebuild(subject(reporter)));
    }

    @Test
    void concurrentDistinctEvidenceReversedSubmitOrderPreservesBothAndMatchesReplay() throws Exception {
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
            Future<TrustShadowProcessingResult> right = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(second);
            });
            Future<TrustShadowProcessingResult> left = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(first);
            });
            start.countDown();

            TrustShadowProcessingResult leftResult = reprocessIfSnapshotConflict(left.get(30, TimeUnit.SECONDS), first);
            TrustShadowProcessingResult rightResult = reprocessIfSnapshotConflict(right.get(30, TimeUnit.SECONDS), second);
            assertAppended(leftResult, "reversed-submit earlier evidence");
            assertAppended(rightResult, "reversed-submit later evidence");
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(3);
            assertThat(snapshot(first)).isEqualTo(rebuild(subject(reporter)));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentThreeDistinctEvidencePreservesAllAndMatchesReplay() throws Exception {
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
        ValidatedOutcomeForTrust third = candidate(
                reporter,
                OutcomeClassification.LIKELY_CORRECT,
                OutcomeReason.SINGLE_AVAILABLE_VERIFICATION,
                82,
                Instant.parse("2026-07-28T10:10:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TrustShadowProcessingResult> one = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(first);
            });
            Future<TrustShadowProcessingResult> two = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(second);
            });
            Future<TrustShadowProcessingResult> three = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(third);
            });
            start.countDown();

            TrustShadowProcessingResult firstResult = reprocessIfSnapshotConflict(one.get(30, TimeUnit.SECONDS), first);
            TrustShadowProcessingResult secondResult = reprocessIfSnapshotConflict(two.get(30, TimeUnit.SECONDS), second);
            TrustShadowProcessingResult thirdResult = reprocessIfSnapshotConflict(three.get(30, TimeUnit.SECONDS), third);
            assertAppended(firstResult, "three-way first");
            assertAppended(secondResult, "three-way second");
            assertAppended(thirdResult, "three-way third");
            assertThat(ledgerPort.findBySubject(subject(reporter))).hasSize(4);
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

    private TrustShadowProcessingResult reprocessIfSnapshotConflict(
            TrustShadowProcessingResult result,
            ValidatedOutcomeForTrust candidate) {
        if (result.status() == TrustShadowProcessingResult.Status.FAILED
                && result.failureStage().orElse(null) == TrustShadowFailureStage.SNAPSHOT_CONFLICT) {
            return processor.process(candidate);
        }
        return result;
    }

    private static void assertAppended(TrustShadowProcessingResult result, String label) {
        assertThat(result.status())
                .as("%s status=%s stage=%s", label, result.status(), result.failureStage())
                .isEqualTo(TrustShadowProcessingResult.Status.APPENDED);
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
                UUID.randomUUID(),
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

    @TestConfiguration
    static class ControllableSnapshotWriteConfig {
        @Bean
        @Primary
        TrustSnapshotWritePort controllableTrustSnapshotWrites(TrustSnapshotRepositoryAdapter adapter) {
            return snapshot -> {
                if (FAIL_NEXT_SNAPSHOT_UPSERT.compareAndSet(true, false)) {
                    throw new TrustShadowProjectionConflictException(
                            "forced test conflict", new RuntimeException("forced"));
                }
                adapter.upsert(snapshot);
            };
        }
    }
}
