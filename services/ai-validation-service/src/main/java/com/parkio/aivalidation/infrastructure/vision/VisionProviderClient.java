package com.parkio.aivalidation.infrastructure.vision;

/**
 * Provider-facing port of the vision pipeline: analyses actual image bytes and
 * returns a normalized three-way verdict with a confidence. Provider DTOs/SDKs stay
 * behind this interface; the domain only ever sees
 * {@link com.parkio.aivalidation.domain.ContentRiskClassifier.Verdict}.
 */
public interface VisionProviderClient {

    /** Stable identifier for logs/metrics (e.g. {@code gemini}). Never includes secrets. */
    String providerId();

    /** Model identifier for logs/metrics (e.g. {@code gemini-2.5-flash-lite}). */
    String modelId();

    /**
     * Classifies the image. Implementations must bound time and retries.
     *
     * @throws VisionProviderException on timeout, HTTP errors, malformed or refused
     *         responses — callers fail closed (UNCERTAIN)
     */
    VisionAnalysis analyze(byte[] imageBytes, String contentType);

    /**
     * Normalized provider output. {@code verdict} is one of the three domain verdict
     * names; {@code confidence} is 0..1; {@code reasonCode} is machine-readable.
     */
    record VisionAnalysis(String verdict, double confidence, String reasonCode,
                          Usage usage, String finishReason) {

        public VisionAnalysis(String verdict, double confidence, String reasonCode) {
            this(verdict, confidence, reasonCode, null, null);
        }

        public record Usage(int promptTokens, int candidatesTokens, int totalTokens) {
        }
    }
}
