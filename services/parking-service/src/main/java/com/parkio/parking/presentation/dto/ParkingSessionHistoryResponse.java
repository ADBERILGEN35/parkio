package com.parkio.parking.presentation.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/** Opaque-cursor history envelope; it deliberately exposes no persistence pagination types. */
@Schema(name = "ParkingSessionHistoryResponse")
public record ParkingSessionHistoryResponse(
        @ArraySchema(
                arraySchema = @Schema(description = "Terminal parking sessions in newest-first order",
                        requiredMode = Schema.RequiredMode.REQUIRED),
                schema = @Schema(implementation = ParkingSessionResponse.class))
        List<ParkingSessionResponse> items,
        @Schema(description = "Opaque Base64URL continuation token, or null on the last page",
                type = "string", maxLength = 512,
                example = "eyJ2IjoxLCJzdGFydGVkQXQiOiIyMDI2LTA3LTIxVDA5OjAwOjAwWiIsImlkIjoiZjAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIn0",
                nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
        String nextCursor) {

    public ParkingSessionHistoryResponse {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
