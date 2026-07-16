package com.parkio.aivalidation.infrastructure.vision;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Decorates a {@link VisionProviderClient} with a Resilience4j circuit breaker.
 * Open breaker fails immediately (UNCERTAIN path) without spending the full timeout.
 */
public final class CircuitBreakingVisionProviderClient implements VisionProviderClient {

    private final VisionProviderClient delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakingVisionProviderClient(VisionProviderClient delegate,
                                               CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public VisionAnalysis analyze(byte[] imageBytes, String contentType) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.analyze(imageBytes, contentType));
        } catch (CallNotPermittedException ex) {
            throw new VisionProviderException(VisionProviderException.Category.UNAVAILABLE,
                    "vision circuit breaker open", ex);
        } catch (VisionProviderException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new VisionProviderException(VisionProviderException.Category.UNAVAILABLE,
                    "vision circuit breaker call failed", ex);
        }
    }
}
