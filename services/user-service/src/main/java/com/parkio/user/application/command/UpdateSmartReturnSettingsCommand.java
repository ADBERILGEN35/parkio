package com.parkio.user.application.command;

import java.time.LocalTime;

/** Partial update for Smart Return settings. Null values are left unchanged. */
public record UpdateSmartReturnSettingsCommand(
        Boolean enabled,
        Double homeLatitude,
        Double homeLongitude,
        String homeLabel,
        LocalTime defaultReturnTime,
        Integer reminderLeadMinutes) {
}
