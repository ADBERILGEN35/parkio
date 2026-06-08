package com.parkio.notification.presentation.dto;

import com.parkio.notification.domain.NotificationPreference;
import java.util.UUID;

/** A user's notification channel preferences. */
public record PreferencesResponse(UUID userId, boolean pushEnabled, boolean emailEnabled, boolean inAppEnabled) {

    public static PreferencesResponse from(NotificationPreference p) {
        return new PreferencesResponse(p.userId(), p.pushEnabled(), p.emailEnabled(), p.inAppEnabled());
    }
}
