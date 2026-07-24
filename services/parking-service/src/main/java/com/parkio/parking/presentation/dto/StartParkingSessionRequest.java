package com.parkio.parking.presentation.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Transport contract for starting a user-entered parking session. */
@JsonDeserialize(using = StartParkingSessionRequestDeserializer.class)
@Schema(
        name = "StartParkingSessionRequest",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record StartParkingSessionRequest(
        @NotNull(message = "latitude is required")
        @DecimalMin(value = "-90", message = "latitude must be between -90 and 90")
        @DecimalMax(value = "90", message = "latitude must be between -90 and 90")
        @Schema(description = "Parked vehicle latitude", minimum = "-90", maximum = "90",
                example = "41.0082", requiredMode = Schema.RequiredMode.REQUIRED)
        Double latitude,

        @NotNull(message = "longitude is required")
        @DecimalMin(value = "-180", message = "longitude must be between -180 and 180")
        @DecimalMax(value = "180", message = "longitude must be between -180 and 180")
        @Schema(description = "Parked vehicle longitude", minimum = "-180", maximum = "180",
                example = "28.9784", requiredMode = Schema.RequiredMode.REQUIRED)
        Double longitude,

        @Size(
                max = MAX_ESTIMATED_FEE_LENGTH,
                message = "estimatedFee must not exceed 32 characters")
        @Pattern(
                regexp = "^0*(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?$",
                message = "estimatedFee must be a non-negative decimal string with at most 2 fractional digits and a maximum value of 9999999999.99")
        @Schema(description = "Optional estimated parking fee as an exact decimal string",
                type = "string", pattern = "^0*(?:0|[1-9][0-9]{0,9})(?:\\.[0-9]{1,2})?$",
                maxLength = MAX_ESTIMATED_FEE_LENGTH,
                example = "125.50", nullable = true)
        String estimatedFee) {

    public static final int MAX_ESTIMATED_FEE_LENGTH = 32;

    public BigDecimal normalizedEstimatedFee() {
        if (estimatedFee == null) {
            return null;
        }
        if (estimatedFee.length() > MAX_ESTIMATED_FEE_LENGTH) {
            throw new IllegalArgumentException(
                    "estimatedFee must not exceed " + MAX_ESTIMATED_FEE_LENGTH + " characters");
        }
        return new BigDecimal(estimatedFee).setScale(2, RoundingMode.UNNECESSARY);
    }
}
