package com.parkio.user.presentation.dto;

import com.parkio.user.domain.UserPreference;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public record SmartReturnSettingsRequest(
        Boolean enabled,
        @DecimalMin("-90.0") @DecimalMax("90.0") Double homeLatitude,
        @DecimalMin("-180.0") @DecimalMax("180.0") Double homeLongitude,
        @Size(max = 160) String homeLabel,
        LocalTime defaultReturnTime,
        @Min(UserPreference.MIN_SMART_RETURN_LEAD_MINUTES)
        @Max(UserPreference.MAX_SMART_RETURN_LEAD_MINUTES)
        Integer reminderLeadMinutes) {
}
