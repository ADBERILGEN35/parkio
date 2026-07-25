package com.parkio.parking.presentation.dto;

import com.parkio.parking.infrastructure.config.ParkingProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;

/**
 * Effective parking-session lifecycle thresholds from parking-service configuration.
 * Clients must use these values instead of hardcoding 24h / 48h / 72h windows.
 */
@Schema(description = "Effective parking-session stale lifecycle configuration")
public record ParkingSessionLifecycleConfigResponse(
        @Schema(description = "Milliseconds after last confirmation (or start) before stale confirmation is required",
                example = "86400000")
        long confirmAfterMs,
        @Schema(description = "Milliseconds after last confirmation before SECOND reminder is due",
                example = "172800000")
        long reminder2AfterMs,
        @Schema(description = "Milliseconds after last confirmation before automatic completion",
                example = "259200000")
        long autoCompleteAfterMs,
        @Schema(description = "ISO-8601 confirm-after duration", example = "PT24H")
        String confirmAfter,
        @Schema(description = "ISO-8601 reminder-2-after duration", example = "PT48H")
        String reminder2After,
        @Schema(description = "ISO-8601 auto-complete-after duration", example = "PT72H")
        String autoCompleteAfter,
        @Schema(description = "Whether reminder publication is enabled")
        boolean remindersEnabled,
        @Schema(description = "Whether automatic completion is enabled")
        boolean autoCompleteEnabled) {

    public static ParkingSessionLifecycleConfigResponse from(ParkingProperties.Session session) {
        Duration confirm = session.getConfirmAfter();
        Duration reminder2 = session.getReminder2After();
        Duration auto = session.getAutoCompleteAfter();
        return new ParkingSessionLifecycleConfigResponse(
                confirm.toMillis(),
                reminder2.toMillis(),
                auto.toMillis(),
                confirm.toString(),
                reminder2.toString(),
                auto.toString(),
                session.isRemindersEnabled(),
                session.isAutoCompleteEnabled());
    }
}