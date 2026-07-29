package com.parkio.parking.infrastructure.persistence.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.FraudShadowRowProcessor;
import com.parkio.parking.application.fraud.FraudShadowProcessingResult;
import com.parkio.parking.application.fraud.ValidatedOutcomeForFraud;
import com.parkio.parking.application.port.FraudLedgerPort;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.fraud.FraudLedgerEntry;
import com.parkio.parking.fraud.FraudReplayComparison;
import com.parkio.parking.fraud.FraudReplayer;
import com.parkio.parking.fraud.FraudSubject;
import com.parkio.parking.fraud.FraudSubjectType;
import com.parkio.parking.infrastructure.persistence.jpa.FraudLedgerJpaRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
class FraudShadowPersistencePostgresIT {

    private static final DockerImageName POSTGIS_IMAGE =
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_fraud_persistence_it")
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
        registry.add("parkio.lifecycle.reward-shadow.enabled", () -> "false");
        registry.add("parkio.lifecycle.fraud-shadow.enabled", () -> "false");
        registry.add("parkio.lifecycle.calibration.enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    private ParkingSpotRepository spots;

    @Autowired
    private OutcomeHistoryPort outcomeHistory;

    @Autowired
    private FraudShadowRowProcessor processor;

    @Autowired
    private FraudLedgerPort ledger;

    @Autowired
    private FraudLedgerJpaRepository jpa;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM fraud_evaluation_ledger");
        jdbc.update("DELETE FROM outcome_history");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void firstEligibleOutcomeCommitsOneFraudEvaluation() {
        ValidatedOutcomeForFraud candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        FraudShadowProcessingResult result = processor.process(candidate);

        assertThat(result.status()).isEqualTo(FraudShadowProcessingResult.Status.APPENDED);
        assertThat(jpa.count()).isEqualTo(1);
        assertThat(latest(candidate).riskScoreBasisPoints()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void duplicateOutcomeDeliveryDoesNotCreateSecondEvaluation() {
        ValidatedOutcomeForFraud candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        processor.process(candidate);
        FraudShadowProcessingResult duplicate = processor.process(candidate);

        assertThat(duplicate.status()).isEqualTo(FraudShadowProcessingResult.Status.DUPLICATE);
        assertThat(jpa.count()).isEqualTo(1);
    }

    @Test
    void sameEvidenceProcessedConcurrentlyProducesOneLogicalEvaluation() throws Exception {
        ValidatedOutcomeForFraud candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FraudShadowProcessingResult> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            Future<FraudShadowProcessingResult> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            start.countDown();

            List<FraudShadowProcessingResult.Status> statuses =
                    List.of(first.get(30, TimeUnit.SECONDS).status(), second.get(30, TimeUnit.SECONDS).status());
            assertThat(statuses).contains(FraudShadowProcessingResult.Status.APPENDED);
            assertThat(statuses).contains(FraudShadowProcessingResult.Status.DUPLICATE);
            assertThat(jpa.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distinctOutcomesForSameReporterPreserveBothEvaluations() throws Exception {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForFraud first = candidate(
                reporter,
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForFraud second = candidate(
                reporter,
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.MODERATOR_REJECTION,
                95,
                Instant.parse("2026-07-28T10:05:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<FraudShadowProcessingResult> left = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(first);
            });
            Future<FraudShadowProcessingResult> right = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(second);
            });
            start.countDown();

            assertThat(left.get(30, TimeUnit.SECONDS).status()).isEqualTo(FraudShadowProcessingResult.Status.APPENDED);
            assertThat(right.get(30, TimeUnit.SECONDS).status()).isEqualTo(FraudShadowProcessingResult.Status.APPENDED);
            assertThat(ledger.findBySubject(subject(reporter))).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void replayMatchesStoredEvaluation() {
        ValidatedOutcomeForFraud candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        processor.process(candidate);
        FraudLedgerEntry entry = latest(candidate);

        FraudReplayComparison replay = new FraudReplayer().replay(entry);

        assertThat(replay.identical()).isTrue();
    }

    @Test
    void replayDoesNotMutateDatabase() {
        ValidatedOutcomeForFraud candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_INCORRECT,
                OutcomeReason.NEGATIVE_VERIFICATION,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        processor.process(candidate);
        FraudLedgerEntry entry = latest(candidate);
        long countBefore = jpa.count();

        FraudReplayComparison replay = new FraudReplayer().replay(entry);

        assertThat(replay.identical()).isTrue();
        assertThat(jpa.count()).isEqualTo(countBefore);
    }

    private FraudLedgerEntry latest(ValidatedOutcomeForFraud candidate) {
        return ledger.findBySubject(subject(candidate.reporterUserId())).stream()
                .filter(entry -> entry.sourceOutcomeRecordId().equals(candidate.outcomeRecord().recordId()))
                .findFirst()
                .orElseThrow();
    }

    private static FraudSubject subject(UUID reporter) {
        return new FraudSubject(FraudSubjectType.USER, reporter);
    }

    private ValidatedOutcomeForFraud candidate(
            UUID reporterUserId,
            UUID spotId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        saveSpot(spotId, reporterUserId, evaluatedAt.minus(Duration.ofHours(2)));
        OutcomeHistoryRecord record = outcomeRecord(spotId, classification, reason, confidence, evaluatedAt);
        outcomeHistory.append(record);
        return new ValidatedOutcomeForFraud(record, reporterUserId);
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
}
