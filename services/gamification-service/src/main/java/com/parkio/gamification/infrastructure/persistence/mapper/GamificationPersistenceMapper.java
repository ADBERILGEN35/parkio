package com.parkio.gamification.infrastructure.persistence.mapper;

import com.parkio.gamification.domain.ContributionSnapshot;
import com.parkio.gamification.domain.LevelRule;
import com.parkio.gamification.domain.PenaltyRule;
import com.parkio.gamification.domain.PointTransaction;
import com.parkio.gamification.domain.RewardRule;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.infrastructure.persistence.entity.ContributionSnapshotEntity;
import com.parkio.gamification.infrastructure.persistence.entity.LevelRuleEntity;
import com.parkio.gamification.infrastructure.persistence.entity.PenaltyRuleEntity;
import com.parkio.gamification.infrastructure.persistence.entity.PointTransactionEntity;
import com.parkio.gamification.infrastructure.persistence.entity.RewardRuleEntity;
import com.parkio.gamification.infrastructure.persistence.entity.UserLevelProgressEntity;

/**
 * Translates between domain models and JPA entities, keeping adapters thin and the
 * domain persistence-agnostic.
 */
public final class GamificationPersistenceMapper {

    private GamificationPersistenceMapper() {
    }

    public static UserLevelProgress toDomain(UserLevelProgressEntity e) {
        return new UserLevelProgress(e.getUserId(), e.getTotalPoints(), e.getCurrentLevel(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }

    public static UserLevelProgressEntity toEntity(UserLevelProgress p) {
        return new UserLevelProgressEntity(p.userId(), p.totalPoints(), p.currentLevel(),
                p.createdAt(), p.updatedAt(), p.version());
    }

    public static PointTransaction toDomain(PointTransactionEntity e) {
        return new PointTransaction(e.getId(), e.getUserId(), e.getIdempotencyKey(), e.getSourceType(),
                e.getDirection(), e.getPoints(), e.getRelatedEventId(), e.getRelatedSpotId(), e.getCreatedAt());
    }

    public static PointTransactionEntity toEntity(PointTransaction t) {
        return new PointTransactionEntity(t.id(), t.userId(), t.idempotencyKey(), t.sourceType(),
                t.direction(), t.points(), t.relatedEventId(), t.relatedSpotId(), t.createdAt());
    }

    public static LevelRule toDomain(LevelRuleEntity e) {
        return new LevelRule(e.getLevel(), e.getMinPoints(), e.getMaxPoints(), e.getSearchRadiusMeters(),
                e.getResultLimit(), e.getDailyViewLimit(), e.isVerifiedSpotPriority(), e.isNotificationPriority());
    }

    public static RewardRule toDomain(RewardRuleEntity e) {
        return new RewardRule(e.getRuleKey(), e.getSourceType(), e.getPoints(), e.getDescription());
    }

    public static PenaltyRule toDomain(PenaltyRuleEntity e) {
        return new PenaltyRule(e.getRuleKey(), e.getSourceType(), e.getPoints(), e.getDescription());
    }

    public static ContributionSnapshotEntity toEntity(ContributionSnapshot s) {
        return new ContributionSnapshotEntity(s.id(), s.userId(), s.score(), s.capturedAt());
    }
}
