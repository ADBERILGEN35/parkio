package com.parkio.notification.application.command;

/** Partial preference update; {@code null} fields are left unchanged. */
public record UpdatePreferencesCommand(Boolean pushEnabled, Boolean emailEnabled, Boolean inAppEnabled) {
}
