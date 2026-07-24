package com.parkio.parking.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingApplicationService;
import com.parkio.parking.application.command.CreateSpotCommand;
import com.parkio.parking.application.command.SearchNearbyQuery;
import com.parkio.parking.domain.ParkingSpot;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyService;
import com.parkio.parking.infrastructure.idempotency.IdempotentResponse;
import com.parkio.parking.infrastructure.idempotency.RequestFingerprint;
import com.parkio.parking.infrastructure.metrics.CommunityClaimMetrics;
import com.parkio.parking.presentation.dto.CreateSpotRequest;
import com.parkio.parking.presentation.dto.PublicSpotResponse;
import com.parkio.parking.presentation.dto.SpotMediaAccessUrlResponse;
import com.parkio.parking.presentation.dto.SpotResponse;
import com.parkio.parking.presentation.dto.VerifySpotRequest;
import com.parkio.parking.presentation.openapi.CommunityClaimOpenApiExamples;
import com.parkio.parking.presentation.openapi.StandardApiResponses;
import com.parkio.platform.api.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
@RestController
@RequestMapping("/api/v1/parking")
public class ParkingController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLES_HEADER = "X-User-Roles";
    private static final Set<String> MODERATOR_ROLES = Set.of("MODERATOR", "ADMIN", "SUPER_ADMIN");

    private final ParkingApplicationService parkingService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final CommunityClaimMetrics communityClaimMetrics;

    public ParkingController(ParkingApplicationService parkingService,
                             IdempotencyService idempotencyService,
                             ObjectMapper objectMapper,
                             CommunityClaimMetrics communityClaimMetrics) {
        this.parkingService = parkingService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.communityClaimMetrics = communityClaimMetrics;
    }

    @Operation(summary = "Create a parking spot")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
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
    @StandardApiResponses
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
    @StandardApiResponses
    @GetMapping("/spots/{spotId}")
    public PublicSpotResponse getSpot(@RequestHeader(value = USER_ID_HEADER, required = false) String userId,
                                      @RequestHeader(value = ROLES_HEADER, required = false) String roles,
                                      @PathVariable("spotId") UUID spotId) {
        UUID viewerUserId = requireUserId(userId);
        return PublicSpotResponse.from(parkingService.getSpotForViewer(spotId, viewerUserId, hasModeratorRole(roles)));
    }

    /**
     * Short-lived signed URL for the spot's photo, on demand (detail view) rather
     * than in list responses — one signed URL per explicit request.
     */
    @Operation(summary = "Get signed URL for spot media")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
    @GetMapping("/spots/{spotId}/media-access-url")
    public SpotMediaAccessUrlResponse getSpotMediaAccessUrl(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("spotId") UUID spotId) {
        UUID requesterUserId = requireUserId(userId);
        return SpotMediaAccessUrlResponse.from(parkingService.getSpotMediaAccessUrl(spotId, requesterUserId));
    }

    @Operation(summary = "Verify a parking spot")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
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

    @Operation(
            operationId = "claimSpot",
            summary = "Claim a community parking spot",
            description = "Atomically marks an eligible spot FILLED and creates an ACTIVE COMMUNITY parking "
                    + "session. The resulting session is restored through the active-session endpoint.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Spot claimed and COMMUNITY session created",
                    headers = @Header(name = "Cache-Control", description = "Sensitive response cache policy",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = PublicSpotResponse.class),
                            examples = @ExampleObject(name = "claimedSpot",
                                    value = CommunityClaimOpenApiExamples.CLAIMED_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "Malformed request or invalid idempotency key",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "idempotencyKeyRequired",
                                    value = CommunityClaimOpenApiExamples.IDEMPOTENCY_REQUIRED),
                            @ExampleObject(name = "idempotencyKeyInvalid",
                                    value = CommunityClaimOpenApiExamples.IDEMPOTENCY_INVALID)
                    })),
            @ApiResponse(responseCode = "401", description = "Authentication required or invalid",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "missingToken",
                                    value = CommunityClaimOpenApiExamples.MISSING_TOKEN))),
            @ApiResponse(responseCode = "403", description = "Account or ownership rule prevents the claim",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "ownerCannotClaim",
                                    value = CommunityClaimOpenApiExamples.OWNER_CANNOT_CLAIM),
                            @ExampleObject(name = "accountNotActive",
                                    value = CommunityClaimOpenApiExamples.ACCOUNT_NOT_ACTIVE)
                    })),
            @ApiResponse(responseCode = "404", description = "Spot not found",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "spotNotFound",
                                    value = CommunityClaimOpenApiExamples.SPOT_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Spot, session, concurrency, or idempotency conflict",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "spotExpired",
                                    value = CommunityClaimOpenApiExamples.SPOT_EXPIRED),
                            @ExampleObject(name = "spotNotClaimable",
                                    value = CommunityClaimOpenApiExamples.SPOT_NOT_CLAIMABLE),
                            @ExampleObject(name = "activeSessionExists",
                                    value = CommunityClaimOpenApiExamples.ACTIVE_SESSION_EXISTS),
                            @ExampleObject(name = "idempotencyConflict",
                                    value = CommunityClaimOpenApiExamples.IDEMPOTENCY_CONFLICT),
                            @ExampleObject(name = "idempotencyInProgress",
                                    value = CommunityClaimOpenApiExamples.IDEMPOTENCY_IN_PROGRESS),
                            @ExampleObject(name = "genericConflict",
                                    value = CommunityClaimOpenApiExamples.GENERIC_CONFLICT)
                    })),
            @ApiResponse(responseCode = "429", description = "Gateway rate limit exceeded",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "rateLimited",
                                    value = CommunityClaimOpenApiExamples.RATE_LIMITED))),
            @ApiResponse(responseCode = "500", description = "Unexpected server failure",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "internalError",
                                    value = CommunityClaimOpenApiExamples.INTERNAL_ERROR))),
            @ApiResponse(responseCode = "503", description = "Gateway cannot verify current account/session state",
                    headers = @Header(name = "Cache-Control",
                            schema = @Schema(type = "string", example = "no-store")),
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "userStatusUnavailable",
                                    value = CommunityClaimOpenApiExamples.USER_STATUS_UNAVAILABLE)))
    })
    @PostMapping("/spots/{spotId}/claim")
    public PublicSpotResponse claimSpot(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Parameter(name = IdempotencyService.HEADER_NAME, in = ParameterIn.HEADER, required = true,
                    description = "Retry key, 8-128 characters. Reuse only for this exact claim command.",
                    schema = @Schema(type = "string", minLength = 8, maxLength = 128),
                    example = "195fc6ca-b65e-4210-8a05-6c05c86f1678")
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false) String idempotencyKey,
            @Parameter(description = "Community parking spot identifier", required = true)
            @PathVariable("spotId") UUID spotId) {
        UUID claimerUserId = requireUserId(userId);
        String path = "/api/v1/parking/spots/" + spotId + "/claim";
        String fingerprint = RequestFingerprint.sha256(path);
        try {
            IdempotentResponse<PublicSpotResponse> response = idempotencyService.execute(
                    claimerUserId, "POST", path, idempotencyKey, fingerprint, PublicSpotResponse.class,
                    () -> IdempotentResponse.first(200,
                            PublicSpotResponse.from(parkingService.claimSpot(spotId, claimerUserId))));
            communityClaimMetrics.recordSuccess(spotId, response.replayed());
            return response.body();
        } catch (RuntimeException failure) {
            communityClaimMetrics.recordFailure(spotId, failure);
            throw failure;
        }
    }

    @Operation(summary = "List current user's parking spots")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
    @GetMapping("/my-spots")
    public List<SpotResponse> listMySpots(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        UUID ownerUserId = requireUserId(userId);
        return parkingService.listMySpots(ownerUserId).stream().map(SpotResponse::from).toList();
    }

    @Operation(summary = "Get current user's parking spot by id")
    @SecurityRequirement(name = "bearerAuth")
    @StandardApiResponses
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

    /** True when the gateway-injected roles header contains MODERATOR or ADMIN. */
    private static boolean hasModeratorRole(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) {
            return false;
        }
        Set<String> roles = Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return roles.stream().anyMatch(MODERATOR_ROLES::contains);
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
