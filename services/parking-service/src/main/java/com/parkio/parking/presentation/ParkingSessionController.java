package com.parkio.parking.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.parking.application.ParkingSessionHistoryPage;
import com.parkio.parking.application.ParkingSessionService;
import com.parkio.parking.domain.ParkingSource;
import com.parkio.parking.domain.exception.ParkingErrorCode;
import com.parkio.parking.domain.exception.ParkingException;
import com.parkio.parking.infrastructure.idempotency.IdempotencyService;
import com.parkio.parking.infrastructure.idempotency.IdempotentResponse;
import com.parkio.parking.infrastructure.idempotency.RequestFingerprint;
import com.parkio.parking.infrastructure.config.ParkingProperties;
import com.parkio.parking.presentation.dto.ParkingSessionHistoryResponse;
import com.parkio.parking.presentation.dto.ParkingSessionLifecycleConfigResponse;
import com.parkio.parking.presentation.dto.ParkingSessionResponse;
import com.parkio.parking.presentation.dto.StartParkingSessionRequest;
import com.parkio.parking.presentation.openapi.ParkingSessionApiResponses;
import com.parkio.parking.presentation.openapi.ParkingSessionOpenApiExamples;
import com.parkio.platform.api.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the authenticated user's parking-session lifecycle. */
@Tag(name = "Parking Sessions", description = "Start, restore and end a user's parking session")
@SecurityRequirement(name = "bearerAuth")
@ParkingSessionApiResponses
@RestController
@RequestMapping("/api/v1/parking/sessions")
public class ParkingSessionController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String BASE_PATH = "/api/v1/parking/sessions";

    private final ParkingSessionService sessionService;
    private final IdempotencyService idempotencyService;
    private final ParkingSessionHistoryCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;
    private final ParkingProperties parkingProperties;

    public ParkingSessionController(
            ParkingSessionService sessionService,
            IdempotencyService idempotencyService,
            ParkingSessionHistoryCursorCodec cursorCodec,
            ObjectMapper objectMapper,
            ParkingProperties parkingProperties) {
        this.sessionService = sessionService;
        this.idempotencyService = idempotencyService;
        this.cursorCodec = cursorCodec;
        this.objectMapper = objectMapper;
        this.parkingProperties = parkingProperties;
    }

    @Operation(
            operationId = "startParkingSession",
            summary = "Start a manual parking session",
            description = "Starts one MANUAL session for the authenticated user. Only one ACTIVE session is allowed.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StartParkingSessionRequest.class),
                    examples = {
                            @ExampleObject(name = "manualStartWithoutFee",
                                    value = ParkingSessionOpenApiExamples.START_WITHOUT_FEE),
                            @ExampleObject(name = "manualStartWithFee",
                                    value = ParkingSessionOpenApiExamples.START_WITH_FEE)
                    }))
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Parking session started",
                    content = @Content(schema = @Schema(implementation = ParkingSessionResponse.class),
                            examples = @ExampleObject(name = "activeSession",
                                    value = ParkingSessionOpenApiExamples.ACTIVE_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "Malformed request, validation, or idempotency key error",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "validationFailure",
                                    value = ParkingSessionOpenApiExamples.VALIDATION_FAILURE))),
            @ApiResponse(responseCode = "409", description = "Active session or idempotency conflict",
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "activeSessionAlreadyExists",
                                    value = ParkingSessionOpenApiExamples.ACTIVE_SESSION_EXISTS),
                            @ExampleObject(name = "idempotencyConflict",
                                    value = ParkingSessionOpenApiExamples.IDEMPOTENCY_CONFLICT),
                            @ExampleObject(name = "genericConflict",
                                    value = ParkingSessionOpenApiExamples.GENERIC_CONFLICT)
                    }))
    })
    @PostMapping
    public ResponseEntity<ParkingSessionResponse> startSession(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Parameter(
                    name = IdempotencyService.HEADER_NAME,
                    in = ParameterIn.HEADER,
                    required = true,
                    description = "Retry key, 8-128 characters. Reuse only for the same normalized command.",
                    example = "d7ad51af-a65c-48d6-8ba8-66d6143a6b19")
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false)
            String idempotencyKey,
            @Valid @RequestBody StartParkingSessionRequest request) {
        UUID ownerId = requireUserId(userId);
        String fingerprint = RequestFingerprint.sha256(objectMapper, startFingerprint(request));
        IdempotentResponse<ParkingSessionResponse> response = idempotencyService.execute(
                ownerId,
                "POST",
                BASE_PATH,
                idempotencyKey,
                fingerprint,
                ParkingSessionResponse.class,
                () -> IdempotentResponse.first(
                        201,
                        ParkingSessionResponse.from(sessionService.startSession(
                                ownerId,
                                ParkingSource.MANUAL,
                                request.latitude(),
                                request.longitude(),
                                request.normalizedEstimatedFee(),
                                null))));
        return ResponseEntity.status(response.statusCode()).body(response.body());
    }


    @Operation(
            operationId = "getParkingSessionLifecycleConfig",
            summary = "Effective parking-session lifecycle thresholds",
            description = "Returns confirm / reminder-2 / auto-complete durations from parking-service configuration. "
                    + "Clients must use these values instead of hardcoding confirmation windows.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Effective lifecycle configuration",
                    content = @Content(schema = @Schema(implementation = ParkingSessionLifecycleConfigResponse.class)))
    })
    @GetMapping("/lifecycle-config")
    public ParkingSessionLifecycleConfigResponse lifecycleConfig(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        requireUserId(userId);
        return ParkingSessionLifecycleConfigResponse.from(parkingProperties.getSession());
    }
    @Operation(
            operationId = "getActiveParkingSession",
            summary = "Restore the active parking session",
            description = "Returns the authenticated user's ACTIVE session, or an empty 204 response.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active parking session found",
                    content = @Content(schema = @Schema(implementation = ParkingSessionResponse.class),
                            examples = @ExampleObject(name = "activeSession",
                                    value = ParkingSessionOpenApiExamples.ACTIVE_RESPONSE))),
            @ApiResponse(responseCode = "204", description = "The authenticated user has no active session",
                    content = @Content)
    })
    @GetMapping("/active")
    public ResponseEntity<ParkingSessionResponse> findActive(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        UUID ownerId = requireUserId(userId);
        return sessionService.findActive(ownerId)
                .map(ParkingSessionResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            operationId = "confirmActiveParkingSession",
            summary = "Confirm an active parking session is still parked",
            description = "Extends the confirmation window (lastConfirmedAt = now). "
                    + "Required after the configured confirm-after window (see GET /lifecycle-config) before Find My Car continues.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmation recorded",
                    content = @Content(schema = @Schema(implementation = ParkingSessionResponse.class),
                            examples = @ExampleObject(name = "confirmedSession",
                                    value = ParkingSessionOpenApiExamples.ACTIVE_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "Malformed session identifier",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Session is absent or belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "sessionNotFound",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Session is not ACTIVE",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "sessionNotActive",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_ACTIVE)))
    })
    @PostMapping("/{sessionId}/confirm-active")
    public ParkingSessionResponse confirmActiveSession(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("sessionId") UUID sessionId) {
        return ParkingSessionResponse.from(
                sessionService.confirmActiveSession(requireUserId(userId), sessionId));
    }

    @Operation(operationId = "completeParkingSession", summary = "Complete an active parking session")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parking session completed",
                    content = @Content(schema = @Schema(implementation = ParkingSessionResponse.class),
                            examples = @ExampleObject(name = "completedSession",
                                    value = ParkingSessionOpenApiExamples.COMPLETED_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "Malformed session identifier or idempotency key",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Session is absent or belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "sessionNotFound",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Terminal state, idempotency, or optimistic-lock conflict",
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "sessionNotActive",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_ACTIVE),
                            @ExampleObject(name = "idempotencyConflict",
                                    value = ParkingSessionOpenApiExamples.IDEMPOTENCY_CONFLICT),
                            @ExampleObject(name = "genericConflict",
                                    value = ParkingSessionOpenApiExamples.GENERIC_CONFLICT)
                    }))
    })
    @PostMapping("/{sessionId}/complete")
    public ParkingSessionResponse completeSession(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Parameter(name = IdempotencyService.HEADER_NAME, in = ParameterIn.HEADER, required = true,
                    description = "Retry key, 8-128 characters",
                    example = "a6ebf2c3-acbc-4284-ac93-5dfbc9d85099")
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false)
            String idempotencyKey,
            @PathVariable("sessionId") UUID sessionId) {
        return transition(userId, sessionId, idempotencyKey, "complete", true);
    }

    @Operation(operationId = "cancelParkingSession", summary = "Cancel an active parking session")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Parking session cancelled",
                    content = @Content(schema = @Schema(implementation = ParkingSessionResponse.class),
                            examples = @ExampleObject(name = "cancelledSession",
                                    value = ParkingSessionOpenApiExamples.CANCELLED_RESPONSE))),
            @ApiResponse(responseCode = "400", description = "Malformed session identifier or idempotency key",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "Session is absent or belongs to another user",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "sessionNotFound",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_FOUND))),
            @ApiResponse(responseCode = "409", description = "Terminal state, idempotency, or optimistic-lock conflict",
                    content = @Content(schema = @Schema(implementation = ApiError.class), examples = {
                            @ExampleObject(name = "sessionNotActive",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_ACTIVE),
                            @ExampleObject(name = "genericConflict",
                                    value = ParkingSessionOpenApiExamples.GENERIC_CONFLICT)
                    }))
    })
    @PostMapping("/{sessionId}/cancel")
    public ParkingSessionResponse cancelSession(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Parameter(name = IdempotencyService.HEADER_NAME, in = ParameterIn.HEADER, required = true,
                    description = "Retry key, 8-128 characters",
                    example = "18491f95-31d5-4533-b039-1b3edc0b052b")
            @RequestHeader(value = IdempotencyService.HEADER_NAME, required = false)
            String idempotencyKey,
            @PathVariable("sessionId") UUID sessionId) {
        return transition(userId, sessionId, idempotencyKey, "cancel", false);
    }

    @Operation(
            operationId = "getParkingSessionHistory",
            summary = "List terminal parking sessions",
            description = "Returns COMPLETED and CANCELLED sessions ordered by startedAt DESC, id DESC.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bounded history page",
                    content = @Content(schema = @Schema(implementation = ParkingSessionHistoryResponse.class),
                            examples = {
                                    @ExampleObject(name = "emptyHistory",
                                            value = ParkingSessionOpenApiExamples.EMPTY_HISTORY),
                                    @ExampleObject(name = "paginatedHistory",
                                            value = ParkingSessionOpenApiExamples.PAGINATED_HISTORY)
                            })),
            @ApiResponse(responseCode = "400", description = "Invalid page size or opaque cursor",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "invalidCursor",
                                    value = ParkingSessionOpenApiExamples.INVALID_CURSOR)))
    })
    @GetMapping("/history")
    public ParkingSessionHistoryResponse findHistory(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @Parameter(description = "Page size from 1 through 100", example = "20")
            @RequestParam(value = "size", defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must be at most 100")
            int size,
            @Parameter(description = "Opaque Base64URL continuation token",
                    schema = @Schema(type = "string", maxLength = 512),
                    example = "eyJ2IjoxLCJzdGFydGVkQXQiOiIyMDI2LTA3LTIxVDA5OjAwOjAwWiIsImlkIjoiZjAwMDAwMDAtMDAwMC0wMDAwLTAwMDAtMDAwMDAwMDAwMDAxIn0")
            @RequestParam(value = "cursor", required = false)
            String cursor) {
        UUID ownerId = requireUserId(userId);
        ParkingSessionHistoryPage page = cursor == null
                ? sessionService.findHistory(ownerId, size)
                : sessionService.findHistory(ownerId, cursorCodec.decode(cursor), size);
        return new ParkingSessionHistoryResponse(
                page.sessions().stream().map(ParkingSessionResponse::from).toList(),
                page.nextCursor().map(cursorCodec::encode).orElse(null));
    }

    @Operation(
            operationId = "deleteParkingSessionHistory",
            summary = "Delete terminal parking session history",
            description = """
                    Hard-deletes all COMPLETED and CANCELLED sessions owned by the authenticated user.
                    Any ACTIVE session is preserved. Repeated calls return 204.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Terminal history cleared (or already empty)",
                    content = @Content)
    })
    // Declare /history before /{sessionId} so bulk delete never binds as a UUID path variable.
    @DeleteMapping("/history")
    public ResponseEntity<Void> deleteHistory(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        sessionService.deleteTerminalHistory(requireUserId(userId));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "deleteParkingSession",
            summary = "Delete one terminal parking session",
            description = """
                    Hard-deletes one COMPLETED or CANCELLED session owned by the authenticated user.
                    ACTIVE sessions return 409. Missing, already-deleted, and foreign-owned ids return \
                    opaque 204 without revealing ownership.""")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted, already absent, or not owned",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = "Malformed session identifier",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "Session is still ACTIVE",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(name = "sessionNotTerminal",
                                    value = ParkingSessionOpenApiExamples.SESSION_NOT_TERMINAL)))
    })
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @Parameter(hidden = true)
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId,
            @PathVariable("sessionId") UUID sessionId) {
        sessionService.deleteTerminalSession(requireUserId(userId), sessionId);
        return ResponseEntity.noContent().build();
    }

    private ParkingSessionResponse transition(
            String userId,
            UUID sessionId,
            String idempotencyKey,
            String action,
            boolean complete) {
        UUID ownerId = requireUserId(userId);
        String path = BASE_PATH + "/" + sessionId + "/" + action;
        String fingerprint = RequestFingerprint.sha256(path);
        return idempotencyService.execute(
                ownerId,
                "POST",
                path,
                idempotencyKey,
                fingerprint,
                ParkingSessionResponse.class,
                () -> IdempotentResponse.first(
                        200,
                        ParkingSessionResponse.from(complete
                                ? sessionService.completeSession(ownerId, sessionId)
                                : sessionService.cancelSession(ownerId, sessionId))))
                .body();
    }

    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException exception) {
            throw new ParkingException(ParkingErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }

    private static Map<String, Object> startFingerprint(StartParkingSessionRequest request) {
        var normalizedEstimatedFee = request.normalizedEstimatedFee();
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("path", BASE_PATH);
        fingerprint.put("latitude", request.latitude());
        fingerprint.put("longitude", request.longitude());
        fingerprint.put("estimatedFee", normalizedEstimatedFee == null
                ? null
                : normalizedEstimatedFee.toPlainString());
        return fingerprint;
    }
}
