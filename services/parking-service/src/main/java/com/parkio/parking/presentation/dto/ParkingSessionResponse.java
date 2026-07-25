package com.parkio.parking.presentation.dto;

import com.parkio.parking.domain.ParkingSession;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Public parking-session representation; ownership and persistence internals are excluded. */
@Schema(name = "ParkingSessionResponse")
public record ParkingSessionResponse(
        @Schema(description = "Parking session identifier", format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "Current lifecycle state",
                allowableValues = {"ACTIVE", "COMPLETED", "CANCELLED"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "How the parking location was selected",
                allowableValues = {"MANUAL", "FACILITY", "CURB", "COMMUNITY", "AUTO"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String parkingSource,
        @Schema(description = "Server-controlled session start time", type = "string",
                format = "date-time", requiredMode = Schema.RequiredMode.REQUIRED)
        Instant startedAt,
        @Schema(description = "Server-controlled terminal time; null while ACTIVE", type = "string",
                format = "date-time", nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        Instant endedAt,
        @Schema(description = "Parked vehicle latitude", minimum = "-90", maximum = "90",
                requiredMode = Schema.RequiredMode.REQUIRED)
        double latitude,
        @Schema(description = "Parked vehicle longitude", minimum = "-180", maximum = "180",
                requiredMode = Schema.RequiredMode.REQUIRED)
        double longitude,
        @Schema(description = "Estimated fee normalized to exactly two fractional digits",
                type = "string", pattern = "^\\d{1,10}\\.\\d{2}$", example = "125.50",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String estimatedFee,
        @Schema(description = "Last time the user confirmed they are still parked; "
                + "defaults to startedAt for a new ACTIVE session",
                type = "string", format = "date-time", nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant lastConfirmedAt,
        @Schema(description = "How the session reached a terminal COMPLETED state; "
                + "null while ACTIVE; CANCELLED is always MANUAL",
                allowableValues = {"MANUAL", "AUTO"},
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String completionType) {

    public static ParkingSessionResponse from(ParkingSession session) {
        return new ParkingSessionResponse(
                session.getId(),
                session.getStatus().name(),
                session.getParkingSource().name(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getLatitude(),
                session.getLongitude(),
                session.getEstimatedFee() == null
                        ? null
                        : session.getEstimatedFee().toPlainString(),
                session.getLastConfirmedAt(),
                session.getCompletionType() == null ? null : session.getCompletionType().name());
    }
}
