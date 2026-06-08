package com.parkio.gamification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA mapping for {@code level_rules} (seeded reference data). */
@Entity
@Table(name = "level_rules")
public class LevelRuleEntity {

    @Id
    @Column(name = "level", nullable = false, updatable = false)
    private int level;

    @Column(name = "min_points", nullable = false)
    private long minPoints;

    @Column(name = "max_points")
    private Long maxPoints;

    @Column(name = "search_radius_meters", nullable = false)
    private int searchRadiusMeters;

    @Column(name = "result_limit", nullable = false)
    private int resultLimit;

    @Column(name = "daily_view_limit", nullable = false)
    private int dailyViewLimit;

    @Column(name = "verified_spot_priority", nullable = false)
    private boolean verifiedSpotPriority;

    @Column(name = "notification_priority", nullable = false)
    private boolean notificationPriority;

    protected LevelRuleEntity() {
        // for JPA
    }

    public int getLevel() {
        return level;
    }

    public long getMinPoints() {
        return minPoints;
    }

    public Long getMaxPoints() {
        return maxPoints;
    }

    public int getSearchRadiusMeters() {
        return searchRadiusMeters;
    }

    public int getResultLimit() {
        return resultLimit;
    }

    public int getDailyViewLimit() {
        return dailyViewLimit;
    }

    public boolean isVerifiedSpotPriority() {
        return verifiedSpotPriority;
    }

    public boolean isNotificationPriority() {
        return notificationPriority;
    }
}
