package com.parkio.user.presentation.dto;

import com.parkio.user.domain.PreferredLocale;
import com.parkio.user.domain.UserPreference;

/** Internal response carrying only the recipient's preferred UI locale. */
public record PreferredLocaleResponse(String preferredLocale) {

    public static PreferredLocaleResponse from(UserPreference preference) {
        PreferredLocale locale = preference.preferredLocale() == null
                ? PreferredLocale.DEFAULT
                : preference.preferredLocale();
        return new PreferredLocaleResponse(locale.code());
    }

    public static PreferredLocaleResponse defaultLocale() {
        return new PreferredLocaleResponse(PreferredLocale.DEFAULT.code());
    }
}
