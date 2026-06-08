package com.parkio.aivalidation.infrastructure.config;

import com.parkio.aivalidation.domain.DeterministicAiValidator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Infrastructure wiring. Exposes a system-UTC {@link Clock} (injectable/testable time)
 * and the placeholder {@link DeterministicAiValidator}. Real model integration would
 * replace the validator bean with an adapter behind a port (backlog).
 */
@Configuration
public class AiValidationInfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DeterministicAiValidator deterministicAiValidator() {
        return new DeterministicAiValidator();
    }
}
