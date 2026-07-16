package com.parkio.aivalidation.infrastructure.vision;

import com.parkio.aivalidation.infrastructure.config.VisionProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Readiness detail for vision: provider/model configured and last successful call.
 * Never exposes API keys or image payloads.
 */
@Component("visionProvider")
@ConditionalOnProperty(name = "parkio.ai.vision.provider", havingValue = "gemini")
public class VisionProviderHealthIndicator implements HealthIndicator {

    private final VisionProperties properties;
    private final VisionProviderClient providerClient;
    private final VisionMetrics metrics;

    public VisionProviderHealthIndicator(VisionProperties properties,
                                         VisionProviderClient providerClient,
                                         VisionMetrics metrics) {
        this.properties = properties;
        this.providerClient = providerClient;
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        boolean keyPresent = properties.getGemini().getApiKey() != null
                && !properties.getGemini().getApiKey().isBlank();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("providerConfigured", keyPresent);
        details.put("providerId", providerClient.providerId());
        details.put("modelConfigured", providerClient.modelId());
        details.put("lastSuccessfulProviderCallEpochSeconds", metrics.lastSuccessEpochSeconds());
        if (!keyPresent) {
            return Health.down().withDetails(details).build();
        }
        return Health.up().withDetails(details).build();
    }
}
