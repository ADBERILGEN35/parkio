package com.parkio.parking.presentation.openapi;

/** Compile-time JSON examples shared by parking-session OpenAPI annotations. */
public final class ParkingSessionOpenApiExamples {

    public static final String START_WITHOUT_FEE = """
            {"latitude":41.0082,"longitude":28.9784}
            """;
    public static final String START_WITH_FEE = """
            {"latitude":41.0082,"longitude":28.9784,"estimatedFee":"125.50"}
            """;
    public static final String ACTIVE_RESPONSE = """
            {"id":"d431ad5a-f8ce-4be2-b4dc-248b47990b39","status":"ACTIVE","parkingSource":"MANUAL","startedAt":"2026-07-21T09:00:00Z","endedAt":null,"latitude":41.0082,"longitude":28.9784,"estimatedFee":"125.50","lastConfirmedAt":"2026-07-21T09:00:00Z","completionType":null}
            """;
    public static final String COMPLETED_RESPONSE = """
            {"id":"d431ad5a-f8ce-4be2-b4dc-248b47990b39","status":"COMPLETED","parkingSource":"MANUAL","startedAt":"2026-07-21T09:00:00Z","endedAt":"2026-07-21T11:15:00Z","latitude":41.0082,"longitude":28.9784,"estimatedFee":"125.50","lastConfirmedAt":"2026-07-21T09:00:00Z","completionType":"MANUAL"}
            """;
    public static final String COMPLETED_AUTO_RESPONSE = """
            {"id":"d431ad5a-f8ce-4be2-b4dc-248b47990b39","status":"COMPLETED","parkingSource":"MANUAL","startedAt":"2026-07-18T09:00:00Z","endedAt":"2026-07-21T09:00:00Z","latitude":41.0082,"longitude":28.9784,"estimatedFee":"125.50","lastConfirmedAt":"2026-07-18T09:00:00Z","completionType":"AUTO"}
            """;
    public static final String LIFECYCLE_CONFIG_RESPONSE = """
            {"confirmAfterMs":86400000,"reminder2AfterMs":172800000,"autoCompleteAfterMs":259200000,"confirmAfter":"PT24H","reminder2After":"PT48H","autoCompleteAfter":"PT72H","remindersEnabled":true,"autoCompleteEnabled":true}
            """;
    public static final String CANCELLED_RESPONSE = """
            {"id":"d431ad5a-f8ce-4be2-b4dc-248b47990b39","status":"CANCELLED","parkingSource":"MANUAL","startedAt":"2026-07-21T09:00:00Z","endedAt":"2026-07-21T09:05:00Z","latitude":41.0082,"longitude":28.9784,"estimatedFee":null,"lastConfirmedAt":"2026-07-21T09:00:00Z","completionType":"MANUAL"}
            """;
    public static final String EMPTY_HISTORY = """
            {"items":[],"nextCursor":null}
            """;
    public static final String PAGINATED_HISTORY = """
            {"items":[{"id":"d431ad5a-f8ce-4be2-b4dc-248b47990b39","status":"COMPLETED","parkingSource":"MANUAL","startedAt":"2026-07-21T09:00:00Z","endedAt":"2026-07-21T11:15:00Z","latitude":41.0082,"longitude":28.9784,"estimatedFee":"125.50","lastConfirmedAt":"2026-07-21T09:00:00Z","completionType":"MANUAL"}],"nextCursor":"eyJ2IjoxLCJzdGFydGVkQXQiOiIyMDI2LTA3LTIxVDA5OjAwOjAwWiIsImlkIjoiZDQzMWFkNWEtZjhjZS00YmUyLWI0ZGMtMjQ4YjQ3OTkwYjM5In0"}
            """;
    public static final String ACTIVE_SESSION_EXISTS = """
            {"code":"ACTIVE_PARKING_SESSION_EXISTS","message":"The user already has an active parking session.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String SESSION_NOT_FOUND = """
            {"code":"PARKING_SESSION_NOT_FOUND","message":"Parking session was not found.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String SESSION_NOT_ACTIVE = """
            {"code":"PARKING_SESSION_NOT_ACTIVE","message":"Only an active parking session can be completed or cancelled.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String SESSION_NOT_TERMINAL = """
            {"code":"PARKING_SESSION_NOT_TERMINAL","message":"An active parking session cannot be deleted.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String INVALID_CURSOR = """
            {"code":"INVALID_PARKING_SESSION_CURSOR","message":"Parking session history cursor is invalid.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String VALIDATION_FAILURE = """
            {"code":"VALIDATION_FAILED","message":"Request validation failed.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","fieldErrors":[{"field":"estimatedFee","message":"estimatedFee must be a non-negative decimal string with at most 2 fractional digits and a maximum value of 9999999999.99"}],"timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String IDEMPOTENCY_CONFLICT = """
            {"code":"IDEMPOTENCY_KEY_CONFLICT","message":"Idempotency-Key was already used for a different request.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String GENERIC_CONFLICT = """
            {"code":"CONFLICT","message":"The request conflicts with the current state of the resource.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;
    public static final String RATE_LIMITED = """
            {"code":"RATE_LIMITED","message":"Too many requests. Try again later.","traceId":"8a56ef7e-69de-4f3c-8fe5-32b83d67f1b4","timestamp":"2026-07-21T09:00:00Z"}
            """;

    private ParkingSessionOpenApiExamples() {
    }
}
