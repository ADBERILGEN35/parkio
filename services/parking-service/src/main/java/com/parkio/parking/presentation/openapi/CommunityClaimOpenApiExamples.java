package com.parkio.parking.presentation.openapi;

/** Stable OpenAPI examples for the atomic community claim operation. */
public final class CommunityClaimOpenApiExamples {

    public static final String CLAIMED_RESPONSE = """
            {
              "id": "2b371445-8ab4-4a23-a1bd-9eb084187cf7",
              "mediaId": "81518eb3-a6d8-453f-aeb9-bdf9dc73457d",
              "latitude": 41.0082,
              "longitude": 28.9784,
              "addressText": "Alemdar, Istanbul",
              "description": null,
              "manualLocationEdited": false,
              "suitableVehicleTypes": ["SEDAN"],
              "parkingContext": "STREET_PARKING",
              "legalStatus": "LEGAL",
              "violationReasons": [],
              "status": "FILLED",
              "expiresAt": "2026-07-22T12:10:00Z",
              "createdAt": "2026-07-22T12:00:00Z",
              "updatedAt": "2026-07-22T12:04:00Z"
            }
            """;

    public static final String IDEMPOTENCY_REQUIRED = """
            {"code":"IDEMPOTENCY_KEY_REQUIRED","message":"Idempotency-Key header is required.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String IDEMPOTENCY_INVALID = """
            {"code":"IDEMPOTENCY_KEY_INVALID","message":"Idempotency-Key must be between 8 and 128 characters.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String IDEMPOTENCY_CONFLICT = """
            {"code":"IDEMPOTENCY_KEY_CONFLICT","message":"Idempotency-Key was already used for a different request.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String IDEMPOTENCY_IN_PROGRESS = """
            {"code":"IDEMPOTENCY_REQUEST_IN_PROGRESS","message":"A request with this Idempotency-Key is still in progress.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String MISSING_TOKEN = """
            {"code":"MISSING_TOKEN","message":"Authentication token is required.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String ACCOUNT_NOT_ACTIVE = """
            {"code":"ACCOUNT_NOT_ACTIVE","message":"Your account is not active.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String OWNER_CANNOT_CLAIM = """
            {"code":"OWNER_CANNOT_CLAIM","message":"The owner cannot claim their own spot.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String SPOT_NOT_FOUND = """
            {"code":"SPOT_NOT_FOUND","message":"Parking spot was not found.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String SPOT_EXPIRED = """
            {"code":"SPOT_EXPIRED","message":"Spot has expired.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String SPOT_NOT_CLAIMABLE = """
            {"code":"SPOT_NOT_CLAIMABLE","message":"Spot is not claimable.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String ACTIVE_SESSION_EXISTS = """
            {"code":"ACTIVE_PARKING_SESSION_EXISTS","message":"The user already has an active parking session.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String GENERIC_CONFLICT = """
            {"code":"CONFLICT","message":"The request conflicts with the current state of the resource.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String RATE_LIMITED = """
            {"code":"RATE_LIMITED","message":"Too many requests. Try again later.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String INTERNAL_ERROR = """
            {"code":"INTERNAL_ERROR","message":"An unexpected error occurred.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;
    public static final String USER_STATUS_UNAVAILABLE = """
            {"code":"USER_STATUS_UNAVAILABLE","message":"Account status could not be verified. Please try again.",
             "traceId":"4c8dc12a-2f74-45dc-93e8-03ee2bdd775d","timestamp":"2026-07-22T12:04:00Z"}
            """;

    private CommunityClaimOpenApiExamples() {
    }
}
