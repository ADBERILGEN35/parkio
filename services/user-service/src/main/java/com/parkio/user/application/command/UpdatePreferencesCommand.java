package com.parkio.user.application.command;

import com.parkio.user.domain.PreferredLocale;

/** Partial preferences update; {@code null} fields mean "leave unchanged". */
public record UpdatePreferencesCommand(
        Integer preferredRadiusMeters,
        Boolean notificationsEnabled,
        PreferredLocale preferredLocale) {
}
