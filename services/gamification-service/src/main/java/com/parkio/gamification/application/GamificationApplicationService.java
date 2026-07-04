package com.parkio.gamification.application;

import com.parkio.gamification.application.event.ModerationCaseResolvedEvent;
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
import com.parkio.gamification.application.port.TrustRuleRepository;
import com.parkio.gamification.application.port.TrustScoreRepository;
import com.parkio.gamification.application.port.UserLevelProgressRepository;
import com.parkio.gamification.application.result.LevelView;
import com.parkio.gamification.domain.AccessPolicy;
import com.parkio.gamification.domain.ContributionSnapshot;
import com.parkio.gamification.domain.LevelRule;
import com.parkio.gamification.domain.LevelRuleSet;
import com.parkio.gamification.domain.PenaltyRule;
import com.parkio.gamification.domain.PointDirection;
import com.parkio.gamification.domain.PointSourceType;
import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.domain.RewardRule;
import com.parkio.gamification.domain.TrustRule;
import com.parkio.gamification.domain.TrustScore;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.domain.event.ContributionScoreUpdatedEvent;
import com.parkio.gamification.domain.event.PointsDeductedEvent;
import com.parkio.gamification.domain.event.PointsEarnedEvent;
import com.parkio.gamification.domain.event.TrustScoreUpdatedEvent;
import com.parkio.gamification.domain.event.UserLevelChangedEvent;
import com.parkio.gamification.domain.exception.GamificationErrorCode;
import com.parkio.gamification.domain.exception.GamificationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gamification use cases: consuming parking events to award/deduct points
 * (idempotently), recomputing levels, and serving progress / level / access-policy
 * / leaderboard reads. Depends only on domain types and ports; persistence and
 * messaging sit behind the ports (ai-context/01).
 *
 * <p>This service owns points, levels, rewards/penalties, contribution score and
 * access policy only — never parking lifecycle, profiles, media or notifications
 * (ai-context/03). Point values come from seeded DB rules, not hardcoded here.
 */
@Service
@Transactional
public class GamificationApplicationService {

    private final UserLevelProgressRepository progressRepository;
    private final PointTransactionRepository pointTransactions;
    private final LevelRuleRepository levelRules;
    private final RewardRuleRepository rewardRules;
    private final PenaltyRuleRepository penaltyRules;
    private final TrustScoreRepository trustScores;
    private final TrustRuleRepository trustRules;
    private final ContributionSnapshotRepository contributionSnapshots;
    private final InboxEventRepository inbox;
    private final OutboxEventAppender outbox;
    private final LeaderboardSettings leaderboardSettings;
    private final Clock clock;

    public GamificationApplicationService(UserLevelProgressRepository progressRepository,
                                          PointTransactionRepository pointTransactions,
                                          LevelRuleRepository levelRules,
                                          RewardRuleRepository rewardRules,
                                          PenaltyRuleRepository penaltyRules,
                                          TrustScoreRepository trustScores,
                                          TrustRuleRepository trustRules,
                                          ContributionSnapshotRepository contributionSnapshots,
                                          InboxEventRepository inbox,
                                          OutboxEventAppender outbox,
                                          LeaderboardSettings leaderboardSettings,
                                          Clock clock) {
        this.progressRepository = progressRepository;
        this.pointTransactions = pointTransactions;
        this.levelRules = levelRules;
        this.rewardRules = rewardRules;
        this.penaltyRules = penaltyRules;
        this.trustScores = trustScores;
        this.trustRules = trustRules;
        this.contributionSnapshots = contributionSnapshots;
        this.inbox = inbox;
        this.outbox = outbox;
        this.leaderboardSettings = leaderboardSettings;
        this.clock = clock;
    }

    // --- Event handlers (invoked directly for now; a Kafka consumer will call them) ---

    /** Owner reward for submitting a spot. */
    public void handleParkingSpotCreated(ParkingSpotCreatedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        RewardRule rule = reward(RewardRuleKeys.UPLOAD_OWNER);
        award(event.ownerUserId(), transactionKey(event.eventId(), RewardRuleKeys.UPLOAD_OWNER),
                rule, event.eventId(), event.parkingSpotId());
        markProcessed(event.eventId(), "ParkingSpotCreated");
    }

    /** Owner + verifier rewards (and an owner trust gain) when a spot is confirmed available. */
    public void handleParkingSpotVerified(ParkingSpotVerifiedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        if (event.isAvailable()) {
            award(event.ownerUserId(), transactionKey(event.eventId(), RewardRuleKeys.VERIFIED_OWNER),
                    reward(RewardRuleKeys.VERIFIED_OWNER), event.eventId(), event.parkingSpotId());
            award(event.actorUserId(), transactionKey(event.eventId(), RewardRuleKeys.VERIFIED_VERIFIER),
                    reward(RewardRuleKeys.VERIFIED_VERIFIER), event.eventId(), event.parkingSpotId());
            applyTrust(event.ownerUserId(), TrustRuleKeys.SPOT_VERIFIED_OWNER, event.eventId());
        }
        markProcessed(event.eventId(), "ParkingSpotVerified");
    }

    /** Owner + claimer rewards (and an owner trust gain) when a spot is successfully claimed. */
    public void handleParkingSpotClaimed(ParkingSpotClaimedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        award(event.ownerUserId(), transactionKey(event.eventId(), RewardRuleKeys.CLAIMED_OWNER),
                reward(RewardRuleKeys.CLAIMED_OWNER), event.eventId(), event.parkingSpotId());
        award(event.actorUserId(), transactionKey(event.eventId(), RewardRuleKeys.CLAIMED_CLAIMER),
                reward(RewardRuleKeys.CLAIMED_CLAIMER), event.eventId(), event.parkingSpotId());
        applyTrust(event.ownerUserId(), TrustRuleKeys.SPOT_CLAIMED_OWNER, event.eventId());
        markProcessed(event.eventId(), "ParkingSpotClaimed");
    }

    /**
     * Owner point + trust penalties when a moderator rejects a spot. Reuses the existing
     * {@code PARKING_REJECTED_OWNER} penalty rule plus the {@code TRUST_SPOT_REJECTED_OWNER}
     * trust rule. The penalties apply only when the event carries the spot owner (still
     * inbox-deduplicated otherwise). Idempotent twice over: inbox by {@code eventId} and
     * the points transaction key.
     */
    public void handleParkingSpotRejectedByModerator(ParkingSpotRejectedByModeratorEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        if (event.ownerUserId() != null) {
            PenaltyRule rule = penalty(RewardRuleKeys.REJECTED_OWNER);
            deduct(event.ownerUserId(), transactionKey(event.eventId(), RewardRuleKeys.REJECTED_OWNER),
                    rule, event.eventId(), event.parkingSpotId());
            applyTrust(event.ownerUserId(), TrustRuleKeys.SPOT_REJECTED_OWNER, event.eventId());
        }
        markProcessed(event.eventId(), "ParkingSpotRejectedByModerator");
    }

    /**
     * Admin trust/point penalties decided by moderation on a USER-targeted case
     * (ai-context/02: moderation decides, gamification computes). {@code REDUCE_TRUST}
     * applies the seeded {@code TRUST_MODERATION_PENALTY} trust rule; {@code DEDUCT_POINTS}
     * applies the seeded fake/spam penalty selected by the case reason. Other actions
     * (SUSPEND_USER, REJECT, ...) are handled elsewhere and only marked processed here.
     */
    public void handleModerationCaseResolved(ModerationCaseResolvedEvent event) {
        if (alreadyProcessed(event.eventId())) {
            return;
        }
        if (event.targetsUser()) {
            if (ModerationCaseResolvedEvent.ACTION_REDUCE_TRUST.equals(event.action())) {
                applyTrust(event.targetId(), TrustRuleKeys.MODERATION_PENALTY, event.eventId());
            } else if (ModerationCaseResolvedEvent.ACTION_DEDUCT_POINTS.equals(event.action())) {
                String ruleKey = penaltyRuleKeyFor(event.reason());
                deduct(event.targetId(), transactionKey(event.eventId(), ruleKey),
                        penalty(ruleKey), event.eventId(), null);
            }
        }
        markProcessed(event.eventId(), "ModerationCaseResolved");
    }

    // --- Queries ---

    @Transactional(readOnly = true)
    public UserLevelProgress getProgress(UUID userId) {
        return loadProgressOrDefault(userId);
    }

    @Transactional(readOnly = true)
    public List<PointTransaction> getRecentTransactions(UUID userId, int limit) {
        return pointTransactions.findRecentByUserId(userId, limit);
    }

    @Transactional(readOnly = true)
    public LevelView getLevelView(UUID userId) {
        UserLevelProgress progress = loadProgressOrDefault(userId);
        LevelRuleSet rules = levelRuleSet();
        LevelRule current = rules.ruleForLevel(progress.currentLevel());
        Long nextMin = rules.rules().stream()
                .filter(rule -> rule.level() == current.level() + 1)
                .map(LevelRule::minPoints)
                .findFirst()
                .orElse(null);
        return new LevelView(userId, progress.currentLevel(), progress.totalPoints(),
                current.minPoints(), nextMin);
    }

    @Transactional(readOnly = true)
    public AccessPolicy getAccessPolicy(UUID userId) {
        UserLevelProgress progress = loadProgressOrDefault(userId);
        LevelRule rule = levelRuleSet().ruleForLevel(progress.currentLevel());
        return AccessPolicy.from(userId, rule);
    }

    @Transactional(readOnly = true)
    public List<LevelRule> getLevels() {
        return levelRuleSet().rules();
    }

    @Transactional(readOnly = true)
    public List<UserLevelProgress> getLeaderboard(Integer requestedLimit) {
        return progressRepository.findTopByPoints(resolveLeaderboardLimit(requestedLimit));
    }

    // --- Internals ---

    private void award(UUID userId, String idempotencyKey, RewardRule rule,
                       UUID relatedEventId, UUID relatedSpotId) {
        applyPoints(userId, idempotencyKey, rule.sourceType(), PointDirection.EARNED, rule.points(),
                relatedEventId, relatedSpotId);
    }

    private void deduct(UUID userId, String idempotencyKey, PenaltyRule rule,
                        UUID relatedEventId, UUID relatedSpotId) {
        applyPoints(userId, idempotencyKey, rule.sourceType(), PointDirection.DEDUCTED, rule.points(),
                relatedEventId, relatedSpotId);
    }

    /**
     * Applies one point change idempotently: skips if the key was already used,
     * otherwise updates progress, writes the ledger entry, and appends the points /
     * level-change / contribution outbox events — all in the caller's transaction.
     */
    private void applyPoints(UUID userId, String idempotencyKey, PointSourceType sourceType,
                             PointDirection direction, long magnitude, UUID relatedEventId, UUID relatedSpotId) {
        if (pointTransactions.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        Instant now = clock.instant();
        UserLevelProgress progress = progressRepository.findByUserId(userId)
                .orElseGet(() -> UserLevelProgress.createDefault(userId, now));
        int previousLevel = progress.currentLevel();

        long delta = direction == PointDirection.EARNED ? magnitude : -magnitude;
        progress.applyPoints(delta, levelRuleSet(), now);
        UserLevelProgress saved = progressRepository.save(progress);

        pointTransactions.save(PointTransaction.record(userId, idempotencyKey, sourceType, direction,
                magnitude, relatedEventId, relatedSpotId, now));

        if (direction == PointDirection.EARNED) {
            outbox.append(PointsEarnedEvent.of(userId, magnitude, sourceType, saved.totalPoints(),
                    relatedEventId, now));
        } else {
            outbox.append(PointsDeductedEvent.of(userId, magnitude, sourceType, saved.totalPoints(),
                    relatedEventId, now));
        }

        if (saved.currentLevel() != previousLevel) {
            outbox.append(UserLevelChangedEvent.of(userId, previousLevel, saved.currentLevel(),
                    saved.totalPoints(), now));
        }

        // Simplified contribution score (lifetime points) until a decay job lands.
        contributionSnapshots.save(ContributionSnapshot.capture(userId, saved.totalPoints(), now));
        outbox.append(ContributionScoreUpdatedEvent.of(userId, saved.totalPoints(), now));
    }

    /**
     * Applies a seeded trust rule to the user's trust score (created lazily at the
     * initial 100). Clamped no-ops (e.g. a gain at 100) neither persist nor emit; real
     * changes save the aggregate and append a {@code TrustScoreUpdated} outbox event in
     * the caller's transaction. Idempotency comes from the caller's inbox dedup.
     */
    private void applyTrust(UUID userId, String ruleKey, UUID relatedEventId) {
        if (userId == null) {
            return;
        }
        TrustRule rule = trustRules.findByRuleKey(ruleKey)
                .orElseThrow(() -> new GamificationException(GamificationErrorCode.RULE_NOT_CONFIGURED,
                        "Missing trust rule: " + ruleKey));
        Instant now = clock.instant();
        TrustScore trust = trustScores.findByUserId(userId)
                .orElseGet(() -> TrustScore.createDefault(userId, now));
        int previousScore = trust.score();
        if (trust.apply(rule.delta(), now)) {
            TrustScore saved = trustScores.save(trust);
            outbox.append(TrustScoreUpdatedEvent.of(userId, previousScore, saved.score(),
                    ruleKey, relatedEventId, now));
        }
    }

    /** Maps a moderation case reason to the seeded DEDUCT_POINTS penalty rule. */
    private static String penaltyRuleKeyFor(String reason) {
        return "FAKE_PHOTO".equals(reason) || "OLD_PHOTO".equals(reason) || "DUPLICATE_PHOTO".equals(reason)
                ? PenaltyRuleKeys.FAKE
                : PenaltyRuleKeys.SPAM;
    }

    private UserLevelProgress loadProgressOrDefault(UUID userId) {
        return progressRepository.findByUserId(userId)
                .orElseGet(() -> UserLevelProgress.createDefault(userId, clock.instant()));
    }

    private LevelRuleSet levelRuleSet() {
        return new LevelRuleSet(levelRules.findAll());
    }

    private boolean alreadyProcessed(UUID eventId) {
        return inbox.existsByEventId(eventId);
    }

    private void markProcessed(UUID eventId, String eventType) {
        inbox.markProcessed(eventId, eventType, clock.instant());
    }

    private RewardRule reward(String ruleKey) {
        return rewardRules.findByRuleKey(ruleKey)
                .orElseThrow(() -> new GamificationException(GamificationErrorCode.RULE_NOT_CONFIGURED,
                        "Missing reward rule: " + ruleKey));
    }

    private PenaltyRule penalty(String ruleKey) {
        return penaltyRules.findByRuleKey(ruleKey)
                .orElseThrow(() -> new GamificationException(GamificationErrorCode.RULE_NOT_CONFIGURED,
                        "Missing penalty rule: " + ruleKey));
    }

    private int resolveLeaderboardLimit(Integer requested) {
        if (requested == null) {
            return leaderboardSettings.defaultLimit();
        }
        if (requested <= 0 || requested > leaderboardSettings.maxLimit()) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and " + leaderboardSettings.maxLimit());
        }
        return requested;
    }

    private static String transactionKey(UUID eventId, String ruleKey) {
        return eventId + ":" + ruleKey;
    }
}
