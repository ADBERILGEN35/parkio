package com.parkio.aivalidation.infrastructure.config;

import com.parkio.aivalidation.domain.ContentRiskClassifier;
import com.parkio.aivalidation.domain.DeterministicAiValidator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Infrastructure wiring. Exposes a system-UTC {@link Clock} (injectable/testable time),
 * the placeholder {@link DeterministicAiValidator} (wired with {@link ContentRiskClassifier}),
 * and scheduling for the outbox relay poller. Real model integration would replace the
 * classifier / validator beans with adapters behind ports (backlog).
 */
@Configuration
@EnableScheduling
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
