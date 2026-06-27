package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserPreference;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record SmartReturnSettingsResponse(
        boolean enabled,
        Double homeLatitude,
        Double homeLongitude,
        String homeLabel,
        LocalTime defaultReturnTime,
        int reminderLeadMinutes,
        LocalDate lastPromptDate,
        String todayStatus,
        Instant todayExpectedReturnAt,
        Instant todayReturnCheckCompletedAt,
        Instant todayNotificationSentAt) {

    public static SmartReturnSettingsResponse from(UserPreference preference) {
        return new SmartReturnSettingsResponse(
                preference.smartReturnEnabled(),
                preference.homeLatitude(),
                preference.homeLongitude(),
                preference.homeLabel(),
                preference.defaultReturnTime(),
                preference.reminderLeadMinutes(),
                preference.lastSmartReturnPromptDate(),
                preference.smartReturnTodayStatus().name(),
                preference.todayExpectedReturnAt(),
                preference.todayReturnCheckCompletedAt(),
                preference.todayNotificationSentAt());
    }
}
