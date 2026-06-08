package com.parkio.notification.presentation.dto;

/** Partial preferences update; {@code null} fields are left unchanged. */
public record PreferencesRequest(Boolean pushEnabled, Boolean emailEnabled, Boolean inAppEnabled) {
}
