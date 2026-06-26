package com.parkio.parking.presentation;

import com.parkio.parking.application.geocoding.GeocodeResult;
import com.parkio.parking.application.geocoding.GeocodingService;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.presentation.dto.GeocodeResultResponse;
import com.parkio.parking.presentation.dto.GeocodeSearchResponse;
import com.parkio.parking.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forward-geocoding API (address/place text → coordinates). Replaces the former
 * browser-direct Nominatim call: the SPA now reaches this through the authenticated
 * gateway route {@code /api/v1/geocoding/**}, so the provider stays server-side
 * (key/host hidden, usage policy honored, cached, rate-limited).
 *
 * <p>Like the rest of parking-service, the authenticated user id is read from the
 * gateway-injected {@code X-User-Id} header and required (fail closed). Query and
 * limit validation lives in {@link GeocodingService} and surfaces as HTTP 400.
 * A provider outage degrades to an empty list (HTTP 200), never an error — the
 * typeahead simply shows no suggestions.
 */
@Tag(name = "Geocoding", description = "Forward geocoding (place text → coordinates)")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/geocoding")
public class GeocodingController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final GeocodingService geocodingService;

    public GeocodingController(GeocodingService geocodingService) {
        this.geocodingService = geocodingService;
    }

    @Operation(summary = "Search places by free text")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/search")
    public GeocodeSearchResponse search(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam("q") String query,
            @RequestParam(value = "limit", required = false) Integer limit) {
        requireUserId(userId);
        List<GeocodeResult> results = geocodingService.search(query, limit);
        return new GeocodeSearchResponse(results.stream().map(GeocodeResultResponse::from).toList());
    }

    /** The gateway injects a verified id on authenticated routes; absence fails closed (401). */
    private static UUID requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Authenticated user id is required.");
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Authenticated user id is malformed.");
        }
    }
}
