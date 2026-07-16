package com.parkio.aivalidation.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.aivalidation.application.port.AiValidationResultRepository;
import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.infrastructure.vision.CircuitBreakingVisionProviderClient;
import com.parkio.aivalidation.infrastructure.vision.GeminiVisionClient;
import com.parkio.aivalidation.infrastructure.vision.MediaContentFetcher;
import com.parkio.aivalidation.infrastructure.vision.MediaContentHttpClient;
import com.parkio.aivalidation.infrastructure.vision.VisionContentRiskClassifier;
import com.parkio.aivalidation.infrastructure.vision.VisionMetrics;
import com.parkio.aivalidation.infrastructure.vision.VisionProviderClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Wires the real vision classifier when {@code parkio.ai.vision.provider=gemini}.
 */
@Configuration
@ConditionalOnProperty(name = "parkio.ai.vision.provider", havingValue = "gemini")
public class VisionClassifierConfig {

    private static final Logger log = LoggerFactory.getLogger(VisionClassifierConfig.class);

    @Bean
    public VisionProviderClient visionProviderClient(RestClient.Builder restClientBuilder,
                                                     ObjectMapper objectMapper,
                                                     VisionProperties visionProperties,
                                                     CircuitBreakerRegistry circuitBreakerRegistry) {
        GeminiVisionClient client =
                new GeminiVisionClient(restClientBuilder, objectMapper, visionProperties);
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("geminiVision");
        log.info("AI vision provider enabled: provider={} model={} circuitBreaker={}",
                client.providerId(), client.modelId(), breaker.getState());
        return new CircuitBreakingVisionProviderClient(client, breaker);
    }

    @Bean
    public VisionMetrics visionMetrics(MeterRegistry meterRegistry,
                                       VisionProviderClient visionProviderClient) {
        return new VisionMetrics(meterRegistry, visionProviderClient.providerId());
    }

    @Bean
    public MediaContentFetcher mediaContentFetcher(RestClient.Builder restClientBuilder,
                                                   VisionProperties visionProperties,
                                                   @Value("${parkio.gateway.internal-secret}")
                                                   String internalSecret,
                                                   VisionMetrics visionMetrics) {
        return new MediaContentHttpClient(restClientBuilder, visionProperties, internalSecret,
                visionMetrics);
    }

    @Bean
    @Primary
    public ContentRiskClassifier visionContentRiskClassifier(MediaContentFetcher mediaContentFetcher,
                                                             VisionProviderClient visionProviderClient,
                                                             AiValidationResultRepository results,
                                                             VisionProperties visionProperties,
                                                             VisionMetrics visionMetrics,
                                                             Clock clock) {
        return new VisionContentRiskClassifier(mediaContentFetcher, visionProviderClient,
                results, visionProperties, visionMetrics, clock);
    }
}
