package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserPreference;

public record PreferencesResponse(int preferredRadiusMeters, boolean notificationsEnabled) {

    public static PreferencesResponse from(UserPreference p) {
        return new PreferencesResponse(p.preferredRadiusMeters(), p.notificationsEnabled());
    }
}
