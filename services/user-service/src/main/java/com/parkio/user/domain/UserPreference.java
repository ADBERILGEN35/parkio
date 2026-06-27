package com.parkio.user.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's preferences (1:1 with a {@link UserProfile}). Pure domain. The search
 * radius is clamped to a safe range so downstream geo queries stay bounded.
 */
public final class UserPreference {

    public static final int MIN_RADIUS_METERS = 100;
    public static final int MAX_RADIUS_METERS = 50_000;
    public static final int DEFAULT_RADIUS_METERS = 1_000;
    public static final int MIN_SMART_RETURN_LEAD_MINUTES = 5;
    public static final int MAX_SMART_RETURN_LEAD_MINUTES = 120;
    public static final int DEFAULT_SMART_RETURN_LEAD_MINUTES = 15;

    private final UUID id;
    private final UUID userProfileId;
    private int preferredRadiusMeters;
    private boolean notificationsEnabled;
    private boolean smartReturnEnabled;
    private Double homeLatitude;
    private Double homeLongitude;
    private String homeLabel;
    private LocalTime defaultReturnTime;
    private int reminderLeadMinutes;
    private LocalDate lastSmartReturnPromptDate;
    private SmartReturnTodayStatus smartReturnTodayStatus;
    private Instant todayExpectedReturnAt;
    private Instant todayReturnCheckClaimedAt;
    private Instant todayReturnCheckClaimExpiresAt;
    private Instant todayReturnCheckCompletedAt;
    private Instant todayNotificationSentAt;
    private final Long version;

    public UserPreference(UUID id,
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
        this.id = Objects.requireNonNull(id, "id");
        this.userProfileId = Objects.requireNonNull(userProfileId, "userProfileId");
        this.preferredRadiusMeters = requireValidRadius(preferredRadiusMeters);
        this.notificationsEnabled = notificationsEnabled;
        this.smartReturnEnabled = smartReturnEnabled;
        this.homeLatitude = requireValidLatitude(homeLatitude);
        this.homeLongitude = requireValidLongitude(homeLongitude);
        this.homeLabel = normalizeHomeLabel(homeLabel);
        this.defaultReturnTime = defaultReturnTime;
        this.reminderLeadMinutes = requireValidLeadMinutes(reminderLeadMinutes);
        this.lastSmartReturnPromptDate = lastSmartReturnPromptDate;
        this.smartReturnTodayStatus =
                smartReturnTodayStatus == null ? SmartReturnTodayStatus.UNKNOWN : smartReturnTodayStatus;
        this.todayExpectedReturnAt = todayExpectedReturnAt;
        this.todayReturnCheckClaimedAt = todayReturnCheckClaimedAt;
        this.todayReturnCheckClaimExpiresAt = todayReturnCheckClaimExpiresAt;
        this.todayReturnCheckCompletedAt = todayReturnCheckCompletedAt;
        this.todayNotificationSentAt = todayNotificationSentAt;
        this.version = version;
    }

    /** Creates the default preferences for a newly-created profile. */
    public static UserPreference createDefault(UUID userProfileId) {
        return new UserPreference(UUID.randomUUID(), userProfileId, DEFAULT_RADIUS_METERS, true,
                false, null, null, null, null, DEFAULT_SMART_RETURN_LEAD_MINUTES,
                null, SmartReturnTodayStatus.UNKNOWN, null, null, null, null, null, null);
    }

    /** Applies a partial update; {@code null} fields are left unchanged. */
    public void update(Integer preferredRadiusMeters, Boolean notificationsEnabled) {
        if (preferredRadiusMeters != null) {
            this.preferredRadiusMeters = requireValidRadius(preferredRadiusMeters);
        }
        if (notificationsEnabled != null) {
            this.notificationsEnabled = notificationsEnabled;
        }
    }

    public void updateSmartReturnSettings(Boolean enabled,
                                          Double homeLatitude,
                                          Double homeLongitude,
                                          String homeLabel,
                                          LocalTime defaultReturnTime,
                                          Integer reminderLeadMinutes) {
        if (enabled != null) {
            this.smartReturnEnabled = enabled;
        }
        if (homeLatitude != null || homeLongitude != null) {
            if (homeLatitude == null || homeLongitude == null) {
                throw new IllegalArgumentException("homeLatitude and homeLongitude must be supplied together");
            }
            this.homeLatitude = requireValidLatitude(homeLatitude);
            this.homeLongitude = requireValidLongitude(homeLongitude);
        }
        if (homeLabel != null) {
            this.homeLabel = normalizeHomeLabel(homeLabel);
        }
        if (defaultReturnTime != null) {
            this.defaultReturnTime = defaultReturnTime;
        }
        if (reminderLeadMinutes != null) {
            this.reminderLeadMinutes = requireValidLeadMinutes(reminderLeadMinutes);
        }
        if (this.smartReturnEnabled && !hasHomeLocation()) {
            throw new IllegalArgumentException("Home location is required to enable Smart Return");
        }
    }

    public void answerLeftByCar(Instant expectedReturnAt, Instant now) {
        if (!smartReturnEnabled || !hasHomeLocation()) {
            throw new IllegalArgumentException("Smart Return must be enabled with a saved home location");
        }
        if (expectedReturnAt == null || !expectedReturnAt.isAfter(now)) {
            throw new IllegalArgumentException("expectedReturnAt must be in the future");
        }
        this.smartReturnTodayStatus = SmartReturnTodayStatus.LEFT_BY_CAR;
        this.todayExpectedReturnAt = expectedReturnAt;
        this.todayReturnCheckClaimedAt = null;
        this.todayReturnCheckClaimExpiresAt = null;
        this.todayReturnCheckCompletedAt = null;
        this.todayNotificationSentAt = null;
    }

    public void answerNotByCar() {
        this.smartReturnTodayStatus = SmartReturnTodayStatus.NOT_BY_CAR;
        this.todayExpectedReturnAt = null;
        this.todayReturnCheckClaimedAt = null;
        this.todayReturnCheckClaimExpiresAt = null;
        this.todayReturnCheckCompletedAt = null;
        this.todayNotificationSentAt = null;
    }

    public void cancelToday() {
        this.smartReturnTodayStatus = SmartReturnTodayStatus.CANCELLED;
        this.todayExpectedReturnAt = null;
        this.todayReturnCheckClaimedAt = null;
        this.todayReturnCheckClaimExpiresAt = null;
        this.todayReturnCheckCompletedAt = null;
        this.todayNotificationSentAt = null;
    }

    public void editReturnTime(Instant expectedReturnAt, Instant now) {
        answerLeftByCar(expectedReturnAt, now);
    }

    public void markPromptSent(LocalDate promptDate) {
        this.lastSmartReturnPromptDate = Objects.requireNonNull(promptDate, "promptDate");
    }

    public void claimReturnCheck(Instant claimedAt, Instant claimExpiresAt) {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt");
        if (!claimExpiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException("claimExpiresAt must be after claimedAt");
        }
        if (smartReturnTodayStatus != SmartReturnTodayStatus.LEFT_BY_CAR
                && smartReturnTodayStatus != SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS) {
            throw new IllegalStateException("Smart Return check is not claimable");
        }
        this.smartReturnTodayStatus = SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS;
        this.todayReturnCheckClaimedAt = claimedAt;
        this.todayReturnCheckClaimExpiresAt = claimExpiresAt;
    }

    public void completeReturnCheck(boolean notificationSent, Instant now) {
        if (smartReturnTodayStatus == SmartReturnTodayStatus.CANCELLED
                || smartReturnTodayStatus == SmartReturnTodayStatus.NOT_BY_CAR
                || !smartReturnEnabled) {
            return;
        }
        if (smartReturnTodayStatus != SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS
                && smartReturnTodayStatus != SmartReturnTodayStatus.LEFT_BY_CAR) {
            throw new IllegalStateException("Smart Return check is not in progress");
        }
        this.smartReturnTodayStatus = SmartReturnTodayStatus.LEFT_BY_CAR;
        this.todayReturnCheckClaimedAt = null;
        this.todayReturnCheckClaimExpiresAt = null;
        this.todayReturnCheckCompletedAt = Objects.requireNonNull(now, "now");
        if (notificationSent) {
            this.todayNotificationSentAt = now;
        }
    }

    public void expireReturnCheckClaim() {
        if (smartReturnTodayStatus == SmartReturnTodayStatus.RETURN_CHECK_IN_PROGRESS) {
            this.smartReturnTodayStatus = SmartReturnTodayStatus.LEFT_BY_CAR;
            this.todayReturnCheckClaimedAt = null;
            this.todayReturnCheckClaimExpiresAt = null;
        }
    }

    public boolean hasHomeLocation() {
        return homeLatitude != null && homeLongitude != null;
    }

    private static int requireValidRadius(int radius) {
        if (radius < MIN_RADIUS_METERS || radius > MAX_RADIUS_METERS) {
            throw new IllegalArgumentException(
                    "preferredRadiusMeters must be between " + MIN_RADIUS_METERS + " and " + MAX_RADIUS_METERS);
        }
        return radius;
    }

    private static Double requireValidLatitude(Double latitude) {
        if (latitude == null) {
            return null;
        }
        if (latitude < -90.0 || latitude > 90.0 || latitude.isNaN() || latitude.isInfinite()) {
            throw new IllegalArgumentException("homeLatitude must be between -90 and 90");
        }
        return latitude;
    }

    private static Double requireValidLongitude(Double longitude) {
        if (longitude == null) {
            return null;
        }
        if (longitude < -180.0 || longitude > 180.0 || longitude.isNaN() || longitude.isInfinite()) {
            throw new IllegalArgumentException("homeLongitude must be between -180 and 180");
        }
        return longitude;
    }

    private static int requireValidLeadMinutes(int minutes) {
        if (minutes < MIN_SMART_RETURN_LEAD_MINUTES || minutes > MAX_SMART_RETURN_LEAD_MINUTES) {
            throw new IllegalArgumentException("reminderLeadMinutes must be between "
                    + MIN_SMART_RETURN_LEAD_MINUTES + " and " + MAX_SMART_RETURN_LEAD_MINUTES);
        }
        return minutes;
    }

    private static String normalizeHomeLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 160 ? trimmed.substring(0, 160) : trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID userProfileId() {
        return userProfileId;
    }

    public int preferredRadiusMeters() {
        return preferredRadiusMeters;
    }

    public boolean notificationsEnabled() {
        return notificationsEnabled;
    }

    public boolean smartReturnEnabled() {
        return smartReturnEnabled;
    }

    public Double homeLatitude() {
        return homeLatitude;
    }

    public Double homeLongitude() {
        return homeLongitude;
    }

    public String homeLabel() {
        return homeLabel;
    }

    public LocalTime defaultReturnTime() {
        return defaultReturnTime;
    }

    public int reminderLeadMinutes() {
        return reminderLeadMinutes;
    }

    public LocalDate lastSmartReturnPromptDate() {
        return lastSmartReturnPromptDate;
    }

    public SmartReturnTodayStatus smartReturnTodayStatus() {
        return smartReturnTodayStatus;
    }

    public Instant todayExpectedReturnAt() {
        return todayExpectedReturnAt;
    }

    public Instant todayReturnCheckClaimedAt() {
        return todayReturnCheckClaimedAt;
    }

    public Instant todayReturnCheckClaimExpiresAt() {
        return todayReturnCheckClaimExpiresAt;
    }

    public Instant todayReturnCheckCompletedAt() {
        return todayReturnCheckCompletedAt;
    }

    public Instant todayNotificationSentAt() {
        return todayNotificationSentAt;
    }

    public Long version() {
        return version;
    }
}
