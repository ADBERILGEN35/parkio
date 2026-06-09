package com.parkio.parking.presentation;

import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyService;
import com.parkio.parking.infrastructure.idempotency.IdempotentResponse;
import com.parkio.parking.infrastructure.idempotency.RequestFingerprint;
import com.parkio.parking.presentation.dto.CreateSpotRequest;
import com.parkio.parking.presentation.dto.PublicSpotResponse;
import com.parkio.parking.presentation.dto.SpotMediaAccessUrlResponse;
import com.parkio.parking.presentation.dto.SpotResponse;
import com.parkio.parking.presentation.dto.VerifySpotRequest;
import com.parkio.parking.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parking API. Translates HTTP into application commands and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>The authenticated user id is read from the {@code X-User-Id} header, which the
 * gateway strips from client input and re-injects after verifying the JWT. Requests
 * without a valid id fail closed.
 */
@Tag(name = "Parking", description = "Parking spots, search and verification")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/parking")
public class ParkingController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final ParkingApplicationService parkingService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public ParkingController(ParkingApplicationService parkingService,
                             IdempotencyService idempotencyService,
                             ObjectMapper objectMapper) {
        this.parkingService = parkingService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Create a parking spot")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/spots")
    public ResponseEntity<SpotResponse> createSpot(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String idempotencyKey,
            @Valid @RequestBody CreateSpotRequest request) {
        UUID ownerUserId = requireUserId(userId);
        String path = "/api/v1/parking/spots";
        String fingerprint = RequestFingerprint.sha256(objectMapper, createFingerprint(path, request));
        IdempotentResponse<SpotResponse> response = idempotencyService.execute(
                ownerUserId, "POST", path, idempotencyKey, fingerprint, SpotResponse.class, () -> {
                    CreateSpotCommand command = new CreateSpotCommand(ownerUserId, request.mediaId(),
                            request.latitude(), request.longitude(), request.addressText(), request.description(),
                            request.manualLocationEdited(), request.suitableVehicleTypes(), request.parkingContext(),
                            request.legalStatus(), request.violationReasons());
                    ParkingSpot spot = parkingService.createSpot(command);
                    return IdempotentResponse.first(201, SpotResponse.from(spot));
                });
        return ResponseEntity.status(response.statusCode())
                .location(URI.create(path + "/" + response.body().id()))
                .body(response.body());
    }

    @Operation(summary = "Search nearby parking spots")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/spots/nearby")
    public List<PublicSpotResponse> searchNearby(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @RequestParam("lat") double lat,
            @RequestParam("lng") double lng,
            @RequestParam(value = "radius", required = false) Double radius,
            @RequestParam(value = "limit", required = false) Integer limit) {
        UUID searcherUserId = requireUserId(userId);
        SearchNearbyQuery query = new SearchNearbyQuery(searcherUserId, lat, lng, radius, limit);
        return parkingService.searchNearby(query).stream().map(PublicSpotResponse::from).toList();
    }

    @Operation(summary = "Get parking spot details")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/spots/{spotId}")
    public PublicSpotResponse getSpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                      @PathVariable("spotId") UUID spotId) {
        UUID viewerUserId = requireUserId(userId);
        return PublicSpotResponse.from(parkingService.getSpotForViewer(spotId, viewerUserId));
    }

    /**
     * Short-lived signed URL for the spot's photo, on demand (detail view) rather
     * than in list responses — one signed URL per explicit request.
     */
    @Operation(summary = "Get signed URL for spot media")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/spots/{spotId}/media-access-url")
    public SpotMediaAccessUrlResponse getSpotMediaAccessUrl(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("spotId") UUID spotId) {
        UUID requesterUserId = requireUserId(userId);
        return SpotMediaAccessUrlResponse.from(parkingService.getSpotMediaAccessUrl(spotId, requesterUserId));
    }

    @Operation(summary = "Verify a parking spot")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/spots/{spotId}/verify")
    public PublicSpotResponse verifySpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                         @RequestHeader(value = IdempotencyService.HEADER_NAME,
                                                 required = false) String idempotencyKey,
                                         @PathVariable("spotId") UUID spotId,
                                         @Valid @RequestBody VerifySpotRequest request) {
        UUID verifierUserId = requireUserId(userId);
        String path = "/api/v1/parking/spots/" + spotId + "/verify";
        String fingerprint = RequestFingerprint.sha256(objectMapper,
                Map.of("path", path, "result", request.result().name()));
        return idempotencyService.execute(
                verifierUserId, "POST", path, idempotencyKey, fingerprint, PublicSpotResponse.class,
                () -> IdempotentResponse.first(200,
                        PublicSpotResponse.from(parkingService.verifySpot(
                                spotId, verifierUserId, request.result()))))
                .body();
    }

    @Operation(summary = "Claim a parking spot")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/spots/{spotId}/claim")
    public PublicSpotResponse claimSpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                        @RequestHeader(value = IdempotencyService.HEADER_NAME,
                                                required = false) String idempotencyKey,
                                        @PathVariable("spotId") UUID spotId) {
        UUID claimerUserId = requireUserId(userId);
        String path = "/api/v1/parking/spots/" + spotId + "/claim";
        String fingerprint = RequestFingerprint.sha256(path);
        return idempotencyService.execute(
                claimerUserId, "POST", path, idempotencyKey, fingerprint, PublicSpotResponse.class,
                () -> IdempotentResponse.first(200,
                        PublicSpotResponse.from(parkingService.claimSpot(spotId, claimerUserId))))
                .body();
    }

    @Operation(summary = "List current user's parking spots")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/my-spots")
    public List<SpotResponse> listMySpots(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        UUID ownerUserId = requireUserId(userId);
        return parkingService.listMySpots(ownerUserId).stream().map(SpotResponse::from).toList();
    }

    @Operation(summary = "Get current user's parking spot by id")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/my-spots/{spotId}")
    public SpotResponse getMySpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                  @PathVariable("spotId") UUID spotId) {
        UUID ownerUserId = requireUserId(userId);
        return SpotResponse.from(parkingService.getMySpot(ownerUserId, spotId));
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }

    private static Map<String, Object> createFingerprint(String path, CreateSpotRequest request) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("path", path);
        value.put("mediaId", request.mediaId());
        value.put("latitude", request.latitude());
        value.put("longitude", request.longitude());
        value.put("addressText", request.addressText());
        value.put("description", request.description());
        value.put("manualLocationEdited", request.manualLocationEdited());
        value.put("suitableVehicleTypes",
                request.suitableVehicleTypes().stream().map(Enum::name).sorted().toList());
        value.put("parkingContext", request.parkingContext().name());
        value.put("legalStatus", request.legalStatus().name());
        value.put("violationReasons", request.violationReasons() == null
                ? List.of()
                : request.violationReasons().stream().map(Enum::name).sorted().toList());
        return value;
    }
}
