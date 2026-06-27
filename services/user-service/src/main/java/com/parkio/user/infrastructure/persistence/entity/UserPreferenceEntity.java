package com.parkio.user.infrastructure.persistence.entity;

import com.parkio.user.domain.SmartReturnTodayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** JPA mapping for {@code user_preferences}. */
@Entity
@Table(name = "user_preferences")
public class UserPreferenceEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_profile_id", nullable = false, unique = true, updatable = false)
    private UUID userProfileId;

    @Column(name = "preferred_radius_meters", nullable = false)
    private int preferredRadiusMeters;

    @Column(name = "notifications_enabled", nullable = false)
    private boolean notificationsEnabled;

    @Column(name = "smart_return_enabled", nullable = false)
    private boolean smartReturnEnabled;

    @Column(name = "home_latitude")
    private Double homeLatitude;

    @Column(name = "home_longitude")
    private Double homeLongitude;

    @Column(name = "home_label")
    private String homeLabel;

    @Column(name = "default_return_time")
    private LocalTime defaultReturnTime;

    @Column(name = "reminder_lead_minutes", nullable = false)
    private int reminderLeadMinutes;

    @Column(name = "last_smart_return_prompt_date")
    private LocalDate lastSmartReturnPromptDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "smart_return_today_status", nullable = false)
    private SmartReturnTodayStatus smartReturnTodayStatus;

    @Column(name = "today_expected_return_at")
    private Instant todayExpectedReturnAt;

    @Column(name = "today_return_check_claimed_at")
    private Instant todayReturnCheckClaimedAt;

    @Column(name = "today_return_check_claim_expires_at")
    private Instant todayReturnCheckClaimExpiresAt;

    @Column(name = "today_return_check_completed_at")
    private Instant todayReturnCheckCompletedAt;

    @Column(name = "today_notification_sent_at")
    private Instant todayNotificationSentAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected UserPreferenceEntity() {
        // for JPA
    }

    public UserPreferenceEntity(UUID id,
                                UUID userProfileId,
                                int preferredRadiusMeters,
                                boolean notificationsEnabled,
                                boolean smartReturnEnabled,
                                Double homeLatitude,
                                Double homeLongitude,
                                String homeLabel,
                                LocalTime defaultReturnTime,
                                int reminderLeadMinutes,
                                LocalDate lastSmartReturnPromptDate,
                                SmartReturnTodayStatus smartReturnTodayStatus,
                                Instant todayExpectedReturnAt,
                                Instant todayReturnCheckClaimedAt,
                                Instant todayReturnCheckClaimExpiresAt,
                                Instant todayReturnCheckCompletedAt,
                                Instant todayNotificationSentAt,
                                Long version) {
        this.id = id;
        this.userProfileId = userProfileId;
        this.preferredRadiusMeters = preferredRadiusMeters;
        this.notificationsEnabled = notificationsEnabled;
        this.smartReturnEnabled = smartReturnEnabled;
        this.homeLatitude = homeLatitude;
        this.homeLongitude = homeLongitude;
        this.homeLabel = homeLabel;
        this.defaultReturnTime = defaultReturnTime;
        this.reminderLeadMinutes = reminderLeadMinutes;
        this.lastSmartReturnPromptDate = lastSmartReturnPromptDate;
        this.smartReturnTodayStatus = smartReturnTodayStatus;
        this.todayExpectedReturnAt = todayExpectedReturnAt;
        this.todayReturnCheckClaimedAt = todayReturnCheckClaimedAt;
        this.todayReturnCheckClaimExpiresAt = todayReturnCheckClaimExpiresAt;
        this.todayReturnCheckCompletedAt = todayReturnCheckCompletedAt;
        this.todayNotificationSentAt = todayNotificationSentAt;
        this.version = version;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserProfileId() {
        return userProfileId;
    }

    public int getPreferredRadiusMeters() {
        return preferredRadiusMeters;
    }

    public boolean isNotificationsEnabled() {
        return notificationsEnabled;
    }

    public boolean isSmartReturnEnabled() {
        return smartReturnEnabled;
    }

    public Double getHomeLatitude() {
        return homeLatitude;
    }

    public Double getHomeLongitude() {
        return homeLongitude;
    }

    public String getHomeLabel() {
        return homeLabel;
    }

    public LocalTime getDefaultReturnTime() {
        return defaultReturnTime;
    }

    public int getReminderLeadMinutes() {
        return reminderLeadMinutes;
    }

    public LocalDate getLastSmartReturnPromptDate() {
        return lastSmartReturnPromptDate;
    }

    public SmartReturnTodayStatus getSmartReturnTodayStatus() {
        return smartReturnTodayStatus;
    }

    public Instant getTodayExpectedReturnAt() {
        return todayExpectedReturnAt;
    }

    public Instant getTodayReturnCheckClaimedAt() {
        return todayReturnCheckClaimedAt;
    }

    public Instant getTodayReturnCheckClaimExpiresAt() {
        return todayReturnCheckClaimExpiresAt;
    }

    public Instant getTodayReturnCheckCompletedAt() {
        return todayReturnCheckCompletedAt;
    }

    public Instant getTodayNotificationSentAt() {
        return todayNotificationSentAt;
    }

    public Long getVersion() {
        return version;
    }
}
