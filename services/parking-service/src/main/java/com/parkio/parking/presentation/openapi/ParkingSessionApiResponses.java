package com.parkio.parking.presentation.openapi;

import com.parkio.platform.api.ApiError;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Common edge and server failures that do not overlap operation-specific responses. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "Authentication required or invalid token",
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                content = @Content(
                        schema = @Schema(implementation = ApiError.class),
                        examples = @ExampleObject(
                                name = "rateLimited",
                                value = ParkingSessionOpenApiExamples.RATE_LIMITED))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error",
                content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public @interface ParkingSessionApiResponses {
}
