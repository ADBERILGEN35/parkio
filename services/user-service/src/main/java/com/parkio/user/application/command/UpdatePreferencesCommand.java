package com.parkio.user.application.command;

/** Partial preferences update; {@code null} fields mean "leave unchanged". */
public record UpdatePreferencesCommand(Integer preferredRadiusMeters, Boolean notificationsEnabled) {
}
