package com.parkio.user.infrastructure.persistence.mapper;

import com.parkio.user.domain.UserPreference;
import com.parkio.user.domain.UserProfile;
import com.parkio.user.domain.UserTrustProfile;
import com.parkio.user.domain.UserTrustScoreHistory;
import com.parkio.user.domain.UserVehicleProfile;
import com.parkio.user.infrastructure.persistence.entity.UserPreferenceEntity;
import com.parkio.user.infrastructure.persistence.entity.UserProfileEntity;
import com.parkio.user.infrastructure.persistence.entity.UserTrustProfileEntity;
import com.parkio.user.infrastructure.persistence.entity.UserTrustScoreHistoryEntity;
import com.parkio.user.infrastructure.persistence.entity.UserVehicleProfileEntity;

/**
 * Translates between domain models and JPA entities, keeping the adapters thin
 * and the domain persistence-agnostic.
 */
public final class UserPersistenceMapper {

    private UserPersistenceMapper() {
    }

    public static UserProfile toDomain(UserProfileEntity e) {
        return new UserProfile(e.getId(), e.getAuthUserId(), e.getEmail(), e.getDisplayName(),
                e.getPhoneNumber(), e.getCity(), e.getStatus(), e.getCreatedAt(), e.getVersion());
    }

    public static UserProfileEntity toEntity(UserProfile p) {
        return new UserProfileEntity(p.id(), p.authUserId(), p.email(), p.displayName(),
                p.phoneNumber(), p.city(), p.status(), p.createdAt(), p.version());
    }

    public static UserPreference toDomain(UserPreferenceEntity e) {
        return new UserPreference(e.getId(), e.getUserProfileId(), e.getPreferredRadiusMeters(),
                e.isNotificationsEnabled(), e.getVersion());
    }

    public static UserPreferenceEntity toEntity(UserPreference p) {
        return new UserPreferenceEntity(p.id(), p.userProfileId(), p.preferredRadiusMeters(),
                p.notificationsEnabled(), p.version());
    }

    public static UserVehicleProfile toDomain(UserVehicleProfileEntity e) {
        return new UserVehicleProfile(e.getId(), e.getUserProfileId(), e.getVehicleType(),
                e.getPlate(), e.getVersion());
    }

    public static UserVehicleProfileEntity toEntity(UserVehicleProfile v) {
        return new UserVehicleProfileEntity(v.id(), v.userProfileId(), v.vehicleType(),
                v.plate(), v.version());
    }

    public static UserTrustProfile toDomain(UserTrustProfileEntity e) {
        return new UserTrustProfile(e.getId(), e.getUserProfileId(), e.getTrustScore(),
                e.getTrustBand(), e.getTotalPoints(), e.getCurrentLevel(), e.getVersion());
    }

    public static UserTrustProfileEntity toEntity(UserTrustProfile t) {
        return new UserTrustProfileEntity(t.id(), t.userProfileId(), t.trustScore(),
                t.trustBand(), t.totalPoints(), t.currentLevel(), t.version());
    }

    public static UserTrustScoreHistoryEntity toEntity(UserTrustScoreHistory h) {
        return new UserTrustScoreHistoryEntity(h.id(), h.userProfileId(), h.previousScore(),
                h.newScore(), h.reason(), h.occurredAt());
    }

    public static UserTrustScoreHistory toDomain(UserTrustScoreHistoryEntity e) {
        return new UserTrustScoreHistory(e.getId(), e.getUserProfileId(), e.getPreviousScore(),
                e.getNewScore(), e.getReason(), e.getOccurredAt());
    }
}
