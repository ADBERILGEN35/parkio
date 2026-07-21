package com.parkio.aivalidation.infrastructure.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CircuitBreakingVisionProviderClientTest {

    @Test
    void openBreakerFailsImmediatelyAsUnavailable() {
        CircuitBreaker breaker = CircuitBreaker.of("geminiVision", CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build());

        AtomicInteger calls = new AtomicInteger();
        VisionProviderClient failing = new VisionProviderClient() {
            @Override
            public String providerId() {
                return "gemini";
            }

            @Override
            public String modelId() {
                return "test";
            }

            @Override
            public VisionAnalysis analyze(byte[] imageBytes, String contentType, ClaimedRegion claimedRegion) {
                calls.incrementAndGet();
                throw new VisionProviderException(VisionProviderException.Category.TIMEOUT, "slow");
            }
        };
        VisionProviderClient client = new CircuitBreakingVisionProviderClient(failing, breaker);

        assertThatThrownBy(() -> client.analyze(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(VisionProviderException.class);
        assertThatThrownBy(() -> client.analyze(new byte[] {1}, "image/jpeg"))
                .isInstanceOf(VisionProviderException.class);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeOpen = calls.get();
        assertThatThrownBy(() -> client.analyze(new byte[] {1}, "image/jpeg"))
                .isInstanceOfSatisfying(VisionProviderException.class, ex ->
                        assertThat(ex.category()).isEqualTo(VisionProviderException.Category.UNAVAILABLE)
                                .as("open breaker must fail fast"));
        assertThat(calls.get()).isEqualTo(callsBeforeOpen);
    }
}
