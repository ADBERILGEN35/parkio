package com.parkio.parking.infrastructure.persistence.reward;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.application.RewardShadowRowProcessor;
import com.parkio.parking.application.port.ParkingSpotRepository;
import com.parkio.parking.application.port.RewardLedgerPort;
import com.parkio.parking.application.reward.RewardShadowProcessingResult;
import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import com.parkio.parking.domain.LegalStatus;
import com.parkio.parking.domain.ParkingContext;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.ParkingSpotStatus;
import com.parkio.parking.domain.VehicleType;
import com.parkio.parking.infrastructure.persistence.jpa.PendingRewardLedgerJpaRepository;
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
import com.parkio.parking.reward.PendingRewardIntent;
import com.parkio.parking.reward.RewardReplayComparison;
import com.parkio.parking.reward.RewardReplayer;
import com.parkio.parking.reward.RewardSubject;
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
import com.parkio.parking.testsupport.PostgisTestImages;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class RewardShadowPersistencePostgresIT {

    private static final DockerImageName POSTGIS_IMAGE = PostgisTestImages.dockerImageName();

    @Container
    static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("parkio_reward_persistence_it")
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
        registry.add("parkio.lifecycle.retention.outbox-enabled", () -> "false");
        registry.add("parkio.lifecycle.retention.inbox-enabled", () -> "false");
        registry.add("management.tracing.enabled", () -> "false");
    }

    @Autowired
    private ParkingSpotRepository spots;

    @Autowired
    private OutcomeHistoryPort outcomeHistory;

    @Autowired
    private RewardShadowRowProcessor processor;

    @Autowired
    private RewardLedgerPort ledger;

    @Autowired
    private PendingRewardLedgerJpaRepository jpa;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM pending_reward_ledger");
        jdbc.update("DELETE FROM outcome_history");
        jdbc.update("DELETE FROM parking_spots");
    }

    @Test
    void firstEligibleOutcomeCommitsOneLedgerIntent() {
        ValidatedOutcomeForReward candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        RewardShadowProcessingResult result = processor.process(candidate);

        assertThat(result.status()).isEqualTo(RewardShadowProcessingResult.Status.APPENDED);
        assertThat(jpa.count()).isEqualTo(1);
        assertThat(latest(candidate).calculatedAmount().value()).isGreaterThan(0);
    }

    @Test
    void duplicateOutcomeDeliveryDoesNotCreateSecondIntent() {
        ValidatedOutcomeForReward candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));

        processor.process(candidate);
        RewardShadowProcessingResult duplicate = processor.process(candidate);

        assertThat(duplicate.status()).isEqualTo(RewardShadowProcessingResult.Status.DUPLICATE);
        assertThat(jpa.count()).isEqualTo(1);
    }

    @Test
    void sameEvidenceProcessedConcurrentlyProducesOneLogicalIntent() throws Exception {
        ValidatedOutcomeForReward candidate = candidate(
                fixedReporter(),
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RewardShadowProcessingResult> first = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            Future<RewardShadowProcessingResult> second = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(candidate);
            });
            start.countDown();

            List<RewardShadowProcessingResult.Status> statuses =
                    List.of(first.get(30, TimeUnit.SECONDS).status(), second.get(30, TimeUnit.SECONDS).status());
            assertThat(statuses).contains(RewardShadowProcessingResult.Status.APPENDED);
            assertThat(statuses).contains(RewardShadowProcessingResult.Status.DUPLICATE);
            assertThat(jpa.count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void distinctOutcomesForSameSubjectPreserveBothIntentsAndReplayIdentically() throws Exception {
        UUID reporter = fixedReporter();
        ValidatedOutcomeForReward first = candidate(
                reporter,
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.MULTIPLE_AVAILABLE_VERIFICATIONS,
                95,
                Instant.parse("2026-07-28T10:00:00Z"));
        ValidatedOutcomeForReward second = candidate(
                reporter,
                UUID.randomUUID(),
                OutcomeClassification.CONFIRMED_CORRECT,
                OutcomeReason.COMMUNITY_CLAIM_CONFIRMED,
                95,
                Instant.parse("2026-07-28T10:05:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<RewardShadowProcessingResult> left = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(first);
            });
            Future<RewardShadowProcessingResult> right = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return processor.process(second);
            });
            start.countDown();

            assertThat(left.get(30, TimeUnit.SECONDS).status()).isEqualTo(RewardShadowProcessingResult.Status.APPENDED);
            assertThat(right.get(30, TimeUnit.SECONDS).status()).isEqualTo(RewardShadowProcessingResult.Status.APPENDED);
            assertThat(ledger.findBySubject(new RewardSubject(RewardSubject.Type.USER, reporter))).hasSize(2);
            for (PendingRewardIntent intent : ledger.findBySubject(new RewardSubject(RewardSubject.Type.USER, reporter))) {
                RewardReplayComparison replay = new RewardReplayer().replay(intent);
                assertThat(replay.identical()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private PendingRewardIntent latest(ValidatedOutcomeForReward candidate) {
        return ledger.findLatestForContribution(
                        com.parkio.parking.reward.ValidatedRewardContributionFactory
                                .reporterContribution(candidate.outcomeRecord(), candidate.reporterUserId())
                                .contributionId())
                .orElseThrow();
    }

    private ValidatedOutcomeForReward candidate(
            UUID reporterUserId,
            UUID spotId,
            OutcomeClassification classification,
            OutcomeReason reason,
            int confidence,
            Instant evaluatedAt) {
        saveSpot(spotId, reporterUserId, evaluatedAt.minus(Duration.ofHours(2)));
        OutcomeHistoryRecord record = outcomeRecord(spotId, classification, reason, confidence, evaluatedAt);
        outcomeHistory.append(record);
        return new ValidatedOutcomeForReward(record, reporterUserId);
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
