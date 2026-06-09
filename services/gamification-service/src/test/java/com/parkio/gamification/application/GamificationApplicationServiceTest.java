package com.parkio.gamification.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.gamification.application.event.ParkingSpotClaimedEvent;
import com.parkio.gamification.application.event.ParkingSpotCreatedEvent;
import com.parkio.gamification.application.event.ParkingSpotRejectedByModeratorEvent;
import com.parkio.gamification.application.event.ParkingSpotVerifiedEvent;
import com.parkio.gamification.application.port.ContributionSnapshotRepository;
import com.parkio.gamification.application.port.InboxEventRepository;
import com.parkio.gamification.application.port.LevelRuleRepository;
import com.parkio.gamification.application.port.OutboxEventAppender;
import com.parkio.gamification.application.port.PenaltyRuleRepository;
import com.parkio.gamification.application.port.PointTransactionRepository;
import com.parkio.gamification.application.port.RewardRuleRepository;
import com.parkio.gamification.application.port.UserLevelProgressRepository;
import com.parkio.gamification.domain.AccessPolicy;
import com.parkio.gamification.domain.ContributionSnapshot;
import com.parkio.gamification.domain.LevelRule;
import com.parkio.gamification.domain.PenaltyRule;
import com.parkio.gamification.domain.PointSourceType;
import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.domain.RewardRule;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.domain.event.GamificationEvent;
import com.parkio.gamification.domain.event.UserLevelChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link GamificationApplicationService} using in-memory
 * fake ports (seeded with the same rule data Flyway provides) — no Spring, no DB.
 */
class GamificationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");

    private FakeProgressRepository progress;
    private FakeTransactionRepository transactions;
    private FakeLevelRuleRepository levelRules;
    private FakeRewardRuleRepository rewardRules;
    private FakePenaltyRuleRepository penaltyRules;
    private FakeContributionSnapshotRepository snapshots;
    private FakeInboxRepository inbox;
    private FakeOutbox outbox;
    private GamificationApplicationService service;

    @BeforeEach
    void setUp() {
        progress = new FakeProgressRepository();
        transactions = new FakeTransactionRepository();
        levelRules = new FakeLevelRuleRepository();
        rewardRules = new FakeRewardRuleRepository();
        penaltyRules = new FakePenaltyRuleRepository();
        snapshots = new FakeContributionSnapshotRepository();
        inbox = new FakeInboxRepository();
        outbox = new FakeOutbox();
        service = new GamificationApplicationService(progress, transactions, levelRules, rewardRules,
                penaltyRules, snapshots, inbox, outbox, new LeaderboardSettings(20, 100),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unknownUserGetsDefaultProgress() {
        UserLevelProgress p = service.getProgress(UUID.randomUUID());

        assertThat(p.totalPoints()).isZero();
        assertThat(p.currentLevel()).isEqualTo(1);
        // Reading must not persist a row.
        assertThat(progress.byUser).isEmpty();
    }

    @Test
    void createdEventAwardsOwnerFivePointsOnce() {
        UUID owner = UUID.randomUUID();
        ParkingSpotCreatedEvent event = new ParkingSpotCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW);

        service.handleParkingSpotCreated(event);

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(5);
        assertThat(transactions.all).hasSize(1);
    }

    @Test
    void duplicateEventIsSkipped() {
        UUID owner = UUID.randomUUID();
        ParkingSpotCreatedEvent event = new ParkingSpotCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, NOW);

        service.handleParkingSpotCreated(event);
        service.handleParkingSpotCreated(event); // redelivery

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(5);
        assertThat(transactions.all).hasSize(1);
    }

    @Test
    void availableVerificationAwardsOwnerTwentyAndVerifierFive() {
        UUID owner = UUID.randomUUID();
        UUID verifier = UUID.randomUUID();
        ParkingSpotVerifiedEvent event = new ParkingSpotVerifiedEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, verifier, "AVAILABLE", NOW);

        service.handleParkingSpotVerified(event);

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(20);
        assertThat(progress.byUser.get(verifier).totalPoints()).isEqualTo(5);
    }

    @Test
    void claimAwardsOwnerThirtyAndClaimerTen() {
        UUID owner = UUID.randomUUID();
        UUID claimer = UUID.randomUUID();
        ParkingSpotClaimedEvent event = new ParkingSpotClaimedEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, claimer, NOW);

        service.handleParkingSpotClaimed(event);

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(30);
        assertThat(progress.byUser.get(claimer).totalPoints()).isEqualTo(10);
    }

    @Test
    void illegalRiskCommunityVerificationDoesNotPenalizeOwnerOrReporter() {
        UUID owner = UUID.randomUUID();
        UUID reporter = UUID.randomUUID();

        service.handleParkingSpotVerified(new ParkingSpotVerifiedEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, reporter, "ILLEGAL_OR_RISKY", NOW));

        assertThat(progress.byUser).doesNotContainKeys(owner, reporter);
        assertThat(transactions.all).isEmpty();
        assertThat(outbox.eventsOfType("PointsDeducted")).isEmpty();
    }

    @Test
    void moderatorRejectionDeductsOwnerPointsOnceWhenOwnerKnown() {
        UUID owner = UUID.randomUUID();
        service.handleParkingSpotClaimed(
                new ParkingSpotClaimedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), NOW));

        ParkingSpotRejectedByModeratorEvent event = new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW);
        service.handleParkingSpotRejectedByModerator(event);
        service.handleParkingSpotRejectedByModerator(event); // redelivery — inbox no-op

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(5); // 30 - 25, applied once
    }

    @Test
    void moderatorRejectionWithoutOwnerAppliesNoPenalty() {
        ParkingSpotRejectedByModeratorEvent event = new ParkingSpotRejectedByModeratorEvent(
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                "ILLEGAL_OR_RISKY", NOW);

        service.handleParkingSpotRejectedByModerator(event);

        assertThat(transactions.all).isEmpty();
    }

    @Test
    void crossingThresholdEmitsUserLevelChangedEvent() {
        UUID owner = UUID.randomUUID();
        // Four claims => owner 4 x 30 = 120 points => level 2.
        for (int i = 0; i < 4; i++) {
            service.handleParkingSpotClaimed(
                    new ParkingSpotClaimedEvent(UUID.randomUUID(), UUID.randomUUID(), owner, UUID.randomUUID(), NOW));
        }

        assertThat(progress.byUser.get(owner).totalPoints()).isEqualTo(120);
        assertThat(progress.byUser.get(owner).currentLevel()).isEqualTo(2);
        List<GamificationEvent> levelChanges = outbox.eventsOfType("UserLevelChanged");
        assertThat(levelChanges).hasSize(1);
        assertThat(((UserLevelChangedEvent) levelChanges.get(0)).newLevel()).isEqualTo(2);
    }

    @Test
    void accessPolicyForUnknownUserReflectsLevelOne() {
        AccessPolicy policy = service.getAccessPolicy(UUID.randomUUID());

        assertThat(policy.currentLevel()).isEqualTo(1);
        assertThat(policy.searchRadiusMeters()).isEqualTo(300);
        assertThat(policy.resultLimit()).isEqualTo(3);
        assertThat(policy.dailyViewLimit()).isEqualTo(20);
        assertThat(policy.verifiedSpotPriority()).isFalse();
        assertThat(policy.notificationPriority()).isFalse();
    }

    @Test
    void accessPolicyReflectsHigherLevelRule() {
        UUID user = UUID.randomUUID();
        progress.save(new UserLevelProgress(user, 800, 4, NOW, NOW, 0L));

        AccessPolicy policy = service.getAccessPolicy(user);

        assertThat(policy.currentLevel()).isEqualTo(4);
        assertThat(policy.searchRadiusMeters()).isEqualTo(1500);
        assertThat(policy.resultLimit()).isEqualTo(15);
        assertThat(policy.verifiedSpotPriority()).isTrue();
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeProgressRepository implements UserLevelProgressRepository {
        private final Map<UUID, UserLevelProgress> byUser = new HashMap<>();

        @Override
        public UserLevelProgress save(UserLevelProgress p) {
            byUser.put(p.userId(), p);
            return p;
        }

        @Override
        public Optional<UserLevelProgress> findByUserId(UUID userId) {
            return Optional.ofNullable(byUser.get(userId));
        }

        @Override
        public List<UserLevelProgress> findTopByPoints(int limit) {
            return byUser.values().stream()
                    .sorted(Comparator.comparingLong(UserLevelProgress::totalPoints).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    private static final class FakeTransactionRepository implements PointTransactionRepository {
        private final List<PointTransaction> all = new ArrayList<>();

        @Override
        public PointTransaction save(PointTransaction transaction) {
            all.add(transaction);
            return transaction;
        }

        @Override
        public boolean existsByIdempotencyKey(String idempotencyKey) {
            return all.stream().anyMatch(t -> t.idempotencyKey().equals(idempotencyKey));
        }

        @Override
        public List<PointTransaction> findRecentByUserId(UUID userId, int limit) {
            return all.stream().filter(t -> t.userId().equals(userId)).limit(limit).toList();
        }
    }

    private static final class FakeLevelRuleRepository implements LevelRuleRepository {
        @Override
        public List<LevelRule> findAll() {
            return List.of(
                    new LevelRule(1, 0, 99L, 300, 3, 20, false, false),
                    new LevelRule(2, 100, 299L, 500, 5, 40, false, false),
                    new LevelRule(3, 300, 699L, 1000, 10, 75, false, false),
                    new LevelRule(4, 700, 1499L, 1500, 15, 150, true, false),
                    new LevelRule(5, 1500, null, 2500, 25, 300, true, true));
        }
    }

    private static final class FakeRewardRuleRepository implements RewardRuleRepository {
        private final Map<String, RewardRule> byKey = new HashMap<>();

        FakeRewardRuleRepository() {
            byKey.put("PARKING_UPLOAD_OWNER", new RewardRule("PARKING_UPLOAD_OWNER", PointSourceType.PARKING_UPLOAD, 5, null));
            byKey.put("PARKING_VERIFIED_OWNER", new RewardRule("PARKING_VERIFIED_OWNER", PointSourceType.PARKING_VERIFIED, 20, null));
            byKey.put("PARKING_VERIFIED_VERIFIER", new RewardRule("PARKING_VERIFIED_VERIFIER", PointSourceType.PARKING_VERIFIED, 5, null));
            byKey.put("PARKING_CLAIMED_OWNER", new RewardRule("PARKING_CLAIMED_OWNER", PointSourceType.PARKING_CLAIMED, 30, null));
            byKey.put("PARKING_CLAIMED_CLAIMER", new RewardRule("PARKING_CLAIMED_CLAIMER", PointSourceType.PARKING_CLAIMED, 10, null));
        }

        @Override
        public Optional<RewardRule> findByRuleKey(String ruleKey) {
            return Optional.ofNullable(byKey.get(ruleKey));
        }
    }

    private static final class FakePenaltyRuleRepository implements PenaltyRuleRepository {
        private final Map<String, PenaltyRule> byKey = new HashMap<>();

        FakePenaltyRuleRepository() {
            byKey.put("PARKING_REJECTED_OWNER", new PenaltyRule("PARKING_REJECTED_OWNER", PointSourceType.PENALTY_ILLEGAL_RISK, 25, null));
        }

        @Override
        public Optional<PenaltyRule> findByRuleKey(String ruleKey) {
            return Optional.ofNullable(byKey.get(ruleKey));
        }
    }

    private static final class FakeContributionSnapshotRepository implements ContributionSnapshotRepository {
        private final List<ContributionSnapshot> all = new ArrayList<>();

        @Override
        public ContributionSnapshot save(ContributionSnapshot snapshot) {
            all.add(snapshot);
            return snapshot;
        }
    }

    private static final class FakeInboxRepository implements InboxEventRepository {
        private final java.util.Set<UUID> processed = new java.util.HashSet<>();

        @Override
        public boolean existsByEventId(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId, String eventType, Instant processedAt) {
            processed.add(eventId);
        }
    }

    private static final class FakeOutbox implements OutboxEventAppender {
        private final List<GamificationEvent> events = new ArrayList<>();

        @Override
        public void append(GamificationEvent event) {
            events.add(event);
        }

        List<GamificationEvent> eventsOfType(String typePrefix) {
            return events.stream().filter(e -> e.eventType().equals(typePrefix)).toList();
        }
    }
}
