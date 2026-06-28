package com.parkio.user.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.user.domain.SmartReturnTodayStatus;
import com.parkio.user.domain.UserPreference;
import com.parkio.user.infrastructure.persistence.entity.UserPreferenceEntity;
import com.parkio.user.presentation.dto.SmartReturnSettingsResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserPersistenceMapperTest {

    @Test
    void mapsPreSmartReturnPreferenceRowsToDefaultSmartReturnState() {
        UserPreferenceEntity legacyPreference = new UserPreferenceEntity(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UserPreference.DEFAULT_RADIUS_METERS,
                true,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L);

        UserPreference preference = UserPersistenceMapper.toDomain(legacyPreference);
        SmartReturnSettingsResponse response = SmartReturnSettingsResponse.from(preference);

        assertThat(preference.smartReturnEnabled()).isFalse();
        assertThat(preference.reminderLeadMinutes()).isEqualTo(UserPreference.DEFAULT_SMART_RETURN_LEAD_MINUTES);
        assertThat(preference.smartReturnTodayStatus()).isEqualTo(SmartReturnTodayStatus.UNKNOWN);
        assertThat(response.enabled()).isFalse();
        assertThat(response.reminderLeadMinutes()).isEqualTo(UserPreference.DEFAULT_SMART_RETURN_LEAD_MINUTES);
        assertThat(response.todayStatus()).isEqualTo(SmartReturnTodayStatus.UNKNOWN.name());
    }
}
