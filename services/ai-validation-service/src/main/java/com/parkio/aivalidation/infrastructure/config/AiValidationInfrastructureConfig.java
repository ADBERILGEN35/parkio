package com.parkio.aivalidation.infrastructure.config;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring. Exposes a system-UTC {@link Clock} (injectable/testable time),
 * the {@link DeterministicAiValidator} (wired with whichever {@link ContentRiskClassifier}
 * is active — heuristic by default, the real vision classifier when
 * {@code parkio.ai.vision.provider} selects one; see {@code VisionClassifierConfig}),
 * and scheduling for the outbox relay poller.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(VisionProperties.class)
public class AiValidationInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DeterministicAiValidator deterministicAiValidator(ContentRiskClassifier contentRiskClassifier) {
        return new DeterministicAiValidator(contentRiskClassifier);
    }
}
