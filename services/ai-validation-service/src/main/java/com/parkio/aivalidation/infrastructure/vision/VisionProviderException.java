package com.parkio.aivalidation.infrastructure.vision;

/**
 * Vision provider call failed. {@link Category} feeds the error metrics; the
 * classifier always fails closed (UNCERTAIN) on this exception. Messages never
 * contain API keys, request URLs, prompts, or image data.
 */
public class VisionProviderException extends RuntimeException {

    public enum Category {
        TIMEOUT,
        HTTP_429,
        HTTP_4XX,
        HTTP_5XX,
        MALFORMED_RESPONSE,
        MAX_TOKENS,
        REFUSAL,
        UNAVAILABLE
    }

    private final Category category;

    public VisionProviderException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public VisionProviderException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
