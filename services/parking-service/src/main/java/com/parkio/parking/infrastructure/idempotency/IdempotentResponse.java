package com.parkio.parking.infrastructure.idempotency;

public record IdempotentResponse<T>(int statusCode, T body, boolean replayed) {

    public static <T> IdempotentResponse<T> first(int statusCode, T body) {
        return new IdempotentResponse<>(statusCode, body, false);
    }

    public static <T> IdempotentResponse<T> replay(int statusCode, T body) {
        return new IdempotentResponse<>(statusCode, body, true);
    }
}
